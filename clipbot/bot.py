"""Телеграм-бот автоклипов Twitch: следит за чатом каналов и при всплеске делает клип встроенным инструментом
Twitch (от имени владельца), а ссылку присылает в Телеграм. Каналы добавляются командами в боте.

Запуск: python -m clipbot.bot (настройки — переменные окружения, см. clipbot/.env.example).
"""
from __future__ import annotations

import asyncio
import os
import secrets
import signal
import time

import aiohttp

from .spike import ChannelChat, Spike
from .store import Store
from .telegram import Telegram
from .twitch import Chat, Twitch, TwitchError, login_of, parse_code

KEYBOARD = [["📋 Каналы", "➕ Добавить канал"], ["⏸ Пауза", "▶️ Продолжить"], ["⚙️ Настройки", "❓ Помощь"]]
COMMANDS = [
    ("add", "добавить канал: /add логин"), ("del", "убрать канал: /del логин"), ("list", "каналы и их чат"),
    ("login", "войти в Twitch"), ("threshold", "порог всплеска, например /threshold 3"),
    ("cooldown", "пауза между клипами канала, минут"), ("minmsgs", "минимум сообщений за 15 с"),
    ("pause", "приостановить клипы"), ("resume", "продолжить"), ("status", "состояние"), ("help", "помощь"),
]
HELP = (
    "Я слежу за чатом каналов Twitch. Когда чат «взрывается» (пишут в разы чаще обычного), делаю клип "
    "встроенным инструментом Twitch от вашего имени и присылаю ссылку сюда.\n\n"
    "/add логин — добавить канал (или кнопка «➕ Добавить канал»)\n"
    "/del логин — убрать канал\n"
    "/list — каналы, сколько клипов, темп чата\n"
    "/threshold 3 — во сколько раз чат должен ускориться (1.5–10)\n"
    "/cooldown 3 — минут между клипами одного канала (1–60)\n"
    "/minmsgs 15 — минимум сообщений за 15 секунд\n"
    "/pause, /resume — приостановить и продолжить\n"
    "/login — войти в Twitch заново\n"
    "/status — состояние бота"
)


def fmt_time(sec) -> str:
    sec = int(sec or 0)
    h, m, s = sec // 3600, sec % 3600 // 60, sec % 60
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


class ClipBot:
    def __init__(self, store: Store, tg: Telegram, tw: Twitch, pair_code: str, chat: Chat | None = None, clock=time.time):
        self.store, self.tg, self.tw = store, tg, tw
        self.pair_code = pair_code
        self.clock = clock
        self.chat = chat or Chat(tw, self.on_message)
        if chat is not None:
            chat.on_message = self.on_message
        self.chats: dict[str, ChannelChat] = {}
        self.awaiting: str | None = None           # ждём логин после «➕ Добавить канал»
        self.last_error: dict[str, float] = {}     # не засыпать одинаковыми ошибками
        self.tasks: set[asyncio.Task] = set()
        for login in store.channels():
            self.chats[login] = self._new_chat()
            self.chat.channels.add(login)

    def _new_chat(self) -> ChannelChat:
        st = self.store.settings()
        return ChannelChat(threshold=float(st["threshold"]), min_msgs=int(st["min_msgs"]), cooldown=float(st["cooldown_min"]) * 60)

    def _apply_settings(self) -> None:
        st = self.store.settings()
        for c in self.chats.values():
            c.threshold, c.min_msgs, c.cooldown = float(st["threshold"]), int(st["min_msgs"]), float(st["cooldown_min"]) * 60

    # ------------------------------------------------------------ чат и клипы
    def on_message(self, ch: str, text: str, t: float) -> None:
        c = self.chats.get(ch)
        if c is not None:
            c.add(t, text)

    async def tick(self) -> None:
        """Проверка всплесков (раз в 2 секунды)."""
        if self.store.settings().get("paused") or not self.store.owner or not self.store.twitch().get("access"):
            return
        now = self.clock()
        for login, c in list(self.chats.items()):
            sp = c.check(now)
            if sp:
                c.last_clip = now
                t = asyncio.create_task(self.make_clip(login, sp))
                self.tasks.add(t)
                t.add_done_callback(self.tasks.discard)

    async def make_clip(self, login: str, sp: Spike) -> None:
        info = self.store.channels().get(login)
        if not info:
            return
        name = info.get("name") or login
        head = f"🔥 {name} — чат ×{str(sp.ratio).replace('.', ',')} ({sp.msgs} сообщ. за 15 с{', «' + sp.word + '»' if sp.word else ''})"
        try:
            clip = await self.tw.create_clip(info["id"])
            ready = await self.tw.clip_ready(clip.id)
            if ready is None:
                await self.notify(login, "processing", f"{head}\n⚠️ Twitch не успел обработать клип — возможно, эфир на паузе.")
                return
            extra = f" · эфир {fmt_time(ready['vod_offset'])}" if ready.get("vod_offset") is not None else ""
            self.store.count_clip(login)
            await self.tg.send(self.store.owner, f"{head}\nКлип: {ready.get('url') or clip.url}{extra}")
        except TwitchError as e:
            await self.notify(login, str(e), f"⚠️ {name}: клип не получился — {e}")

    async def notify(self, login: str, kind: str, text: str) -> None:
        """Ошибки одного вида по каналу — не чаще раза в 30 минут."""
        key = f"{login}|{kind}"
        if self.clock() - self.last_error.get(key, -1e18) < 1800:
            return
        self.last_error[key] = self.clock()
        await self.tg.send(self.store.owner, text)

    # ------------------------------------------------------------ команды
    async def handle(self, msg: dict) -> None:
        chat_id = msg.get("chat", {}).get("id")
        text = (msg.get("text") or "").strip()
        if not chat_id or not text:
            return
        if text.startswith("/start"):
            await self.cmd_start(chat_id, text)
            return
        if chat_id != self.store.owner:
            await self.tg.send(chat_id, "Это личный бот. Владелец привязывает его командой /start <код> из установки.")
            return
        cmd, _, arg = text.partition(" ")
        cmd = cmd.split("@")[0].lower()
        arg = arg.strip()
        buttons = {"📋 Каналы": "/list", "➕ Добавить канал": "/add", "⏸ Пауза": "/pause", "▶️ Продолжить": "/resume",
                   "⚙️ Настройки": "/status", "❓ Помощь": "/help"}
        if text in buttons:
            cmd, arg = buttons[text], ""
        if not cmd.startswith("/"):
            code, _ = parse_code(text)
            if self.awaiting == "add" and "code=" not in text:
                self.awaiting = None
                await self.cmd_add(chat_id, text)
            elif code and self.store.data.get("login_state"):
                self.awaiting = None
                await self.finish_login(chat_id, text)
            else:
                await self.tg.send(chat_id, "Не понял. /help — что я умею.", KEYBOARD)
            return
        self.awaiting = None
        handler = {"/help": self.cmd_help, "/login": self.cmd_login, "/add": self.cmd_add, "/del": self.cmd_del,
                   "/list": self.cmd_list, "/threshold": self.cmd_threshold, "/cooldown": self.cmd_cooldown,
                   "/minmsgs": self.cmd_minmsgs, "/pause": self.cmd_pause, "/resume": self.cmd_resume,
                   "/status": self.cmd_status}.get(cmd)
        if handler is None:
            await self.tg.send(chat_id, "Нет такой команды. /help — список.")
            return
        await handler(chat_id, arg)

    async def cmd_start(self, chat_id: int, text: str) -> None:
        code = text.partition(" ")[2].strip()
        if self.store.owner and chat_id != self.store.owner:
            await self.tg.send(chat_id, "Это личный бот — он уже привязан к владельцу.")
            return
        if not self.store.owner:
            if not code or not secrets.compare_digest(code, self.pair_code):
                await self.tg.send(chat_id, "Отправьте /start и код привязки из установки на сервере (строка «Код привязки»).")
                return
            self.store.set_owner(chat_id)
        await self.tg.send(chat_id, "Привет! Бот привязан к вам.\n\n" + HELP, KEYBOARD)
        if not self.store.twitch().get("access"):
            await self.cmd_login(chat_id, "")

    async def cmd_help(self, chat_id, _arg) -> None:
        await self.tg.send(chat_id, HELP, KEYBOARD)

    async def cmd_login(self, chat_id, _arg) -> None:
        state = secrets.token_urlsafe(12)
        self.store.set_login_state(state)
        await self.tg.send(chat_id, (
            "Вход в Twitch (нужен, чтобы читать чат и делать клипы от вашего имени):\n\n"
            f"1. Откройте ссылку и нажмите «Разрешить»:\n{self.tw.auth_url(state)}\n\n"
            "2. Браузер перейдёт на адрес вида http://localhost/?code=… — страница не откроется, это нормально.\n"
            "3. Скопируйте этот адрес из адресной строки целиком и пришлите сюда."))

    async def finish_login(self, chat_id, text) -> None:
        code, state = parse_code(text)
        want = self.store.data.get("login_state")
        if state is not None and state != want:
            await self.tg.send(chat_id, "Эта ссылка от старого входа. Отправьте /login и повторите.")
            return
        try:
            login = await self.tw.exchange(code)
        except TwitchError as e:
            await self.tg.send(chat_id, f"⚠️ {e}")
            return
        self.store.set_login_state(None)
        self.chat.reconnect()
        await self.tg.send(chat_id, f"✅ Вошёл в Twitch как {login}. Добавьте каналы: /add логин", KEYBOARD)

    async def cmd_add(self, chat_id, arg) -> None:
        if not arg:
            self.awaiting = "add"
            await self.tg.send(chat_id, "Пришлите логин канала (из адреса twitch.tv/логин) или ссылку на канал.")
            return
        login = login_of(arg)
        if not login:
            await self.tg.send(chat_id, "Не похоже на логин Twitch. Пример: /add 5opka")
            return
        if login in self.store.channels():
            await self.tg.send(chat_id, f"{login} уже в списке.")
            return
        try:
            u = await self.tw.user(login)
        except TwitchError as e:
            await self.tg.send(chat_id, f"⚠️ {e}")
            return
        if not u:
            await self.tg.send(chat_id, f"Канал «{login}» не найден на Twitch.")
            return
        self.store.add_channel(login, u["id"], u.get("display_name") or login)
        self.chats[login] = self._new_chat()
        await self.chat.join(login)
        await self.tg.send(chat_id, f"✅ Слежу за {u.get('display_name') or login}. Клип придёт, когда чат взорвётся "
                                    f"(первые минуты эфира я только привыкаю к темпу чата).", KEYBOARD)

    async def cmd_del(self, chat_id, arg) -> None:
        login = login_of(arg)
        if not login or not self.store.del_channel(login):
            names = ", ".join(self.store.channels()) or "список пуст"
            await self.tg.send(chat_id, f"Укажите канал из списка: /del логин\nСейчас: {names}")
            return
        self.chats.pop(login, None)
        await self.chat.part(login)
        await self.tg.send(chat_id, f"Больше не слежу за {login}.")

    async def cmd_list(self, chat_id, _arg) -> None:
        chs = self.store.channels()
        if not chs:
            await self.tg.send(chat_id, "Каналов пока нет. /add логин — добавить.", KEYBOARD)
            return
        now = self.clock()
        lines = []
        for login, info in chs.items():
            c = self.chats.get(login)
            per_min = sum(c._window(now - k * 15)[1] for k in range(4)) if c else 0
            lines.append(f"• {info.get('name') or login} (twitch.tv/{login}) — клипов: {info.get('clips', 0)}, "
                         f"чат сейчас: {per_min} сообщ./мин")
        await self.tg.send(chat_id, "\n".join(lines), KEYBOARD)

    async def _num(self, chat_id, arg, key, lo, hi, cast, label) -> None:
        try:
            v = cast(arg.replace(",", "."))
            if not lo <= v <= hi:
                raise ValueError
        except ValueError:
            await self.tg.send(chat_id, f"{label}: число от {lo} до {hi}. Сейчас: {self.store.settings()[key]}")
            return
        self.store.set(key, v)
        self._apply_settings()
        await self.tg.send(chat_id, f"✅ {label}: {v}")

    async def cmd_threshold(self, chat_id, arg) -> None:
        await self._num(chat_id, arg, "threshold", 1.5, 10, float, "Порог всплеска (во сколько раз чаще обычного)")

    async def cmd_cooldown(self, chat_id, arg) -> None:
        await self._num(chat_id, arg, "cooldown_min", 1, 60, int, "Пауза между клипами канала, минут")

    async def cmd_minmsgs(self, chat_id, arg) -> None:
        await self._num(chat_id, arg, "min_msgs", 3, 500, int, "Минимум сообщений за 15 секунд")

    async def cmd_pause(self, chat_id, _arg) -> None:
        self.store.set("paused", True)
        await self.tg.send(chat_id, "⏸ Клипы на паузе. /resume — продолжить.", KEYBOARD)

    async def cmd_resume(self, chat_id, _arg) -> None:
        self.store.set("paused", False)
        await self.tg.send(chat_id, "▶️ Снова слежу за чатом.", KEYBOARD)

    async def cmd_status(self, chat_id, _arg) -> None:
        st, tw = self.store.settings(), self.store.twitch()
        await self.tg.send(chat_id, (
            f"Twitch: {('вход как ' + tw['login']) if tw.get('login') else 'нет входа — /login'}\n"
            f"Чат: {'подключён' if self.chat.connected.is_set() else 'не подключён'}\n"
            f"Каналов: {len(self.store.channels())}\n"
            f"Клипы: {'на паузе' if st.get('paused') else 'включены'}\n"
            f"Порог: ×{st['threshold']} · минимум {st['min_msgs']} сообщ. за 15 с · пауза {st['cooldown_min']} мин"), KEYBOARD)

    # ------------------------------------------------------------ циклы
    async def poll(self, stop: asyncio.Event) -> None:
        offset = 0
        while not stop.is_set():
            try:
                for u in await self.tg.updates(offset):
                    offset = u["update_id"] + 1
                    if "message" in u:
                        try:
                            await self.handle(u["message"])
                        except Exception as e:  # noqa: BLE001 — одна неудачная команда не роняет бота
                            print("ошибка команды:", e, flush=True)
            except (aiohttp.ClientError, asyncio.TimeoutError, RuntimeError) as e:
                print("Телеграм недоступен:", e, flush=True)
                await asyncio.sleep(5)

    async def ticker(self, stop: asyncio.Event) -> None:
        while not stop.is_set():
            await asyncio.sleep(2)
            try:
                await self.tick()
            except Exception as e:  # noqa: BLE001
                print("ошибка проверки:", e, flush=True)

    async def run(self, stop: asyncio.Event) -> None:
        try:
            await self.tg.set_commands(COMMANDS)
        except (aiohttp.ClientError, RuntimeError) as e:
            print("не удалось задать команды:", e, flush=True)
        work = [asyncio.create_task(c) for c in (self.poll(stop), self.ticker(stop), self.chat.run(stop))]
        await stop.wait()                     # остановка (docker stop) — не ждём открытых соединений
        for t in work + list(self.tasks):
            t.cancel()
        await asyncio.gather(*work, *self.tasks, return_exceptions=True)


def env(name: str, default: str | None = None) -> str:
    v = os.environ.get(name, default)
    if not v:
        raise SystemExit(f"Не задана переменная {name} (см. clipbot/.env.example)")
    return v


async def main() -> None:
    store = Store(os.path.join(os.environ.get("DATA_DIR", "/data"), "state.json"))
    timeout = aiohttp.ClientTimeout(total=60)
    async with aiohttp.ClientSession(timeout=timeout) as s:
        tg = Telegram(s, env("TELEGRAM_BOT_TOKEN"))
        tw = Twitch(s, env("TWITCH_CLIENT_ID"), env("TWITCH_CLIENT_SECRET"), os.environ.get("TWITCH_REDIRECT_URI", "http://localhost"), store)
        bot = ClipBot(store, tg, tw, env("BOT_PAIR_CODE"))
        stop = asyncio.Event()
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGTERM, signal.SIGINT):
            loop.add_signal_handler(sig, stop.set)
        print("Бот автоклипов запущен", flush=True)
        await bot.run(stop)


if __name__ == "__main__":
    asyncio.run(main())
