"""Телеграм-бот автоклипов Twitch: следит за чатом каналов и при всплеске делает клип встроенным инструментом
Twitch (от имени владельца), а ссылку присылает в Телеграм. Каналы добавляются командами в боте.

Запуск: python -m clipbot.bot (настройки — переменные окружения, см. clipbot/.env.example).
"""
from __future__ import annotations

import asyncio
import calendar
import os
import secrets
import signal
import time

from .spike import ChannelChat, Spike
from .store import Store
from .telegram import Telegram
from .twitch import Chat, Twitch, TwitchError, login_of, parse_code

KEYBOARD = [["📋 Каналы", "➕ Добавить канал"], ["⏸ Пауза", "▶️ Продолжить"], ["⚙️ Настройки", "❓ Помощь"]]
COMMANDS = [
    ("add", "добавить канал: /add логин"), ("del", "убрать канал: /del логин"), ("list", "каналы и их чат"),
    ("login", "войти в Twitch"), ("threshold", "порог всплеска, например /threshold 3"),
    ("cooldown", "пауза между моментами канала, минут"), ("minmsgs", "минимум сообщений за 15 с"),
    ("clips", "1 — минута с моментом посередине, 2 — минута до и минута после"),
    ("vclips", "сколько зрителей должны клипнуть, чтобы я написал (0 — выкл.)"),
    ("pause", "приостановить клипы"), ("resume", "продолжить"), ("status", "состояние"), ("help", "помощь"),
]
HELP = (
    "Я слежу за чатом каналов Twitch. Когда чат «взрывается» (пишут в разы чаще обычного), через 30 секунд "
    "делаю встроенным инструментом Twitch от вашего имени минутный клип — момент в его середине — "
    "и присылаю ссылку сюда.\n\n"
    "/add логин — добавить канал (или кнопка «➕ Добавить канал»)\n"
    "/del логин — убрать канал\n"
    "/list — каналы, сколько клипов, темп чата\n"
    "/threshold 3 — во сколько раз чат должен ускориться (1.5–10)\n"
    "/cooldown 3 — минут между моментами одного канала (2–60)\n"
    "/minmsgs 15 — минимум сообщений за 15 секунд\n"
    "/clips 1 — один клип: 30 с до момента и 30 с после; /clips 2 — два клипа: минута до и минута после\n"
    "/vclips 2 — написать, когда за 2 минуты клипнули столько разных зрителей (0 — не следить)\n\n"
    "Под каждым клипом — 👍 / 👎: по оценкам я сам подстраиваю порог для канала "
    "(👎 — реже и точнее, 👍 — чаще).\n\n"
    "/pause, /resume — приостановить и продолжить\n"
    "/login — войти в Twitch заново\n"
    "/status — состояние бота"
)


CLIP_LEN = 60       # секунд в клипе — максимум у Twitch
CENTER_AFTER = 30   # через сколько секунд после всплеска делать минутный клип, чтобы момент был посередине
EDIT_LABEL = "✂️ Редактор клипа (субтитры, обрезка; ссылка живёт 24 ч)"
VC_WINDOW = 150     # клипы зрителей за последние 2,5 минуты — один момент
TUNE_MIN, TUNE_MAX = 0.7, 2.0  # пределы поправки порога канала по оценкам 👍/👎


def parse_ts(s: str) -> float:
    """«2026-11-02T18:47:39Z» → unix-время."""
    try:
        return float(calendar.timegm(time.strptime(s[:19], "%Y-%m-%dT%H:%M:%S")))
    except (ValueError, TypeError):
        return 0.0


def feedback_buttons(login: str) -> list:
    return [[("👍 Хороший", f"fb:1:{login}"), ("👎 Мимо", f"fb:0:{login}")]]


def vod_link(video_id: str, sec) -> str:
    sec = max(0, int(sec or 0))
    h, m, s = sec // 3600, sec % 3600 // 60, sec % 60
    return f"https://www.twitch.tv/videos/{video_id}?t=" + (f"{h}h" if h else "") + f"{m}m{s}s"


def fmt_time(sec) -> str:
    sec = int(sec or 0)
    h, m, s = sec // 3600, sec % 3600 // 60, sec % 60
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


class ClipBot:
    def __init__(self, store: Store, tg: Telegram, tw: Twitch, pair_code: str, chat: Chat | None = None, clock=time.time,
                 wall=time.time):
        self.store, self.tg, self.tw = store, tg, tw
        self.pair_code = pair_code
        self.clock = clock      # время чата (в тестах — поддельное)
        self.wall = wall        # настоящее время — для клипов зрителей (у них время создания из Twitch)
        self.watch_every = 45.0                    # раз в сколько секунд смотреть клипы зрителей
        self.vclips: dict[str, list] = {}          # канал → клипы зрителей за последние 10 минут
        self.seen: set[str] = set()                # id уже учтённых клипов
        self.vc_alert: dict[str, float] = {}       # когда последний раз писал про клипы зрителей канала
        self.last_msg: dict[str, tuple] = {}       # канал → (время, номер сообщения) последнего клипа
        self.chat = chat or Chat(tw, self.on_message)
        if chat is not None:
            chat.on_message = self.on_message
        self.chats: dict[str, ChannelChat] = {}
        self.awaiting: str | None = None           # ждём логин после «➕ Добавить канал»
        self.last_error: dict[str, float] = {}     # не засыпать одинаковыми ошибками
        self.tasks: set[asyncio.Task] = set()
        self.after_delay = float(CLIP_LEN)         # через сколько секунд делать клип «минута после»
        self.center_delay = float(CENTER_AFTER)    # пауза перед клипом с моментом посередине
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
    def on_message(self, ch: str, text: str, t: float, user: str = "") -> None:
        c = self.chats.get(ch)
        if c is not None:
            c.add(t, text, user)

    def thr(self, login: str) -> float:
        """Порог канала: общий × поправка по оценкам 👍/👎."""
        tune = float(self.store.channels().get(login, {}).get("tune", 1.0))
        return round(float(self.store.settings()["threshold"]) * tune, 2)

    async def tick(self) -> None:
        """Проверка всплесков (раз в 2 секунды)."""
        if self.store.settings().get("paused") or not self.store.owner or not self.store.twitch().get("access"):
            return
        now = self.clock()
        for login, c in list(self.chats.items()):
            sp = c.check(now, self.thr(login))
            if sp:
                c.last_clip = now
                t = asyncio.create_task(self.make_clip(login, sp))
                self.tasks.add(t)
                t.add_done_callback(self.tasks.discard)

    def _head(self, name: str, sp: Spike) -> str:
        who = f" от {sp.authors} чел." if sp.authors else ""
        if sp.wave:
            what = f", волна «{sp.wave}» ×{sp.wave_users}"
        else:
            what = f", «{sp.word}»" if sp.word else ""
        return f"🔥 {name} — чат ×{str(sp.ratio).replace('.', ',')} ({sp.msgs} сообщ.{who} за 15 с{what})"

    async def make_clip(self, login: str, sp: Spike) -> None:
        if int(self.store.settings().get("clips", 1)) == 2:
            await self.make_pair(login, sp)
        else:
            await self.make_centered(login, sp)

    async def make_centered(self, login: str, sp: Spike) -> None:
        """Один клип на минуту через 30 с после всплеска: 30 с до момента и 30 с после — момент посередине."""
        info = self.store.channels().get(login)
        if not info:
            return
        name = info.get("name") or login
        head = self._head(name, sp)
        await asyncio.sleep(self.center_delay)
        try:
            clip = await self.tw.create_clip(info["id"], CLIP_LEN)
            ready = await self.tw.clip_ready(clip.id)
        except TwitchError as e:
            await self.notify(login, str(e), f"⚠️ {name}: клип не получился — {e}")
            return
        if ready is None:
            await self.notify(login, "processing", f"{head}\n⚠️ Twitch не успел обработать клип — возможно, эфир на паузе.")
            return
        self.store.count_clip(login)
        lines = [head, f"Клип (минута, момент посередине): {ready.get('url') or clip.url}"]
        if clip.edit_url:
            lines.append(f"{EDIT_LABEL}: {clip.edit_url}")
        off = ready.get("vod_offset")
        if off is not None:
            moment = float(off) + float(ready.get("duration") or CLIP_LEN) - CENTER_AFTER
            rec = f"\nЗапись эфира с начала клипа: {vod_link(ready['video_id'], off)}" if ready.get("video_id") else ""
            lines.append(f"Момент в эфире: {fmt_time(moment)}{rec}")
        mid = await self.tg.send(self.store.owner, "\n".join(lines), buttons=feedback_buttons(login))
        self.last_msg[login] = (self.wall(), mid)

    async def make_pair(self, login: str, sp: Spike) -> None:
        """Два клипа по минуте подряд: «минута до» — сразу, «минута после» — через минуту.
        Вместе — непрерывные 2 минуты эфира, момент всплеска на стыке (Twitch не делает клипы длиннее 60 с)."""
        info = self.store.channels().get(login)
        if not info:
            return
        name = info.get("name") or login
        head = self._head(name, sp)
        t0 = time.monotonic()
        try:
            first = await self.tw.create_clip(info["id"], CLIP_LEN)
            ready = await self.tw.clip_ready(first.id)
        except TwitchError as e:
            await self.notify(login, str(e), f"⚠️ {name}: клип не получился — {e}")
            return
        if ready is None:
            await self.notify(login, "processing", f"{head}\n⚠️ Twitch не успел обработать клип — возможно, эфир на паузе.")
            return
        self.store.count_clip(login)
        lines = [head]
        off = ready.get("vod_offset")
        if off is not None:
            moment = float(off) + float(ready.get("duration") or CLIP_LEN)
            rec = f"\nЗапись эфира с минуты до момента: {vod_link(ready['video_id'], off)}" if ready.get("video_id") else ""
            lines.append(f"Момент в эфире: {fmt_time(moment)}{rec}")
        lines.append(f"① Минута до: {ready.get('url') or first.url}")
        if first.edit_url:
            lines.append(f"   {EDIT_LABEL}: {first.edit_url}")
        lines.append("② Минута после — пришлю через минуту")
        msg_id = await self.tg.send(self.store.owner, "\n".join(lines), buttons=feedback_buttons(login))
        self.last_msg[login] = (self.wall(), msg_id)

        await asyncio.sleep(max(0.0, t0 + self.after_delay - time.monotonic()))
        try:
            second = await self.tw.create_clip(info["id"], CLIP_LEN)
            ready2 = await self.tw.clip_ready(second.id)
        except TwitchError as e:
            await self.tg.send(self.store.owner, f"② Минута после не получилась — {e}", reply_to=msg_id)
            return
        if ready2 is None:
            await self.tg.send(self.store.owner, "② Twitch не успел обработать клип «минута после».", reply_to=msg_id)
            return
        self.store.count_clip(login)
        edit2 = f"\n   {EDIT_LABEL}: {second.edit_url}" if second.edit_url else ""
        await self.tg.send(self.store.owner, f"② Минута после: {ready2.get('url') or second.url}{edit2}", reply_to=msg_id)

    # ------------------------------------------------------------ клипы зрителей
    async def watch_once(self) -> None:
        """Смотрит новые клипы зрителей на каналах в эфире; если за 2,5 минуты клипнули несколько разных людей — пишет."""
        need = int(self.store.settings().get("vclips", 2))
        if need <= 0 or self.store.settings().get("paused") or not self.store.owner or not self.store.twitch().get("access"):
            return
        ids = {info["id"]: login for login, info in self.store.channels().items() if info.get("id")}
        if not ids:
            return
        live = await self.tw.live(list(ids))
        now, me = self.wall(), str(self.store.twitch().get("user_id", ""))
        for bid in live:
            login = ids.get(bid)
            if not login:
                continue
            for c in await self.tw.recent_clips(bid, now - 600):
                if c.get("id") in self.seen or str(c.get("creator_id", "")) == me:
                    continue  # свои клипы бота не считаем
                self.seen.add(c["id"])
                self.vclips.setdefault(login, []).append({**c, "ts": parse_ts(c.get("created_at", ""))})
            lst = [c for c in self.vclips.get(login, []) if now - c["ts"] <= 600]
            self.vclips[login] = lst
            recent = [c for c in lst if now - c["ts"] <= VC_WINDOW]
            people = {str(c.get("creator_id") or c.get("creator_name")) for c in recent}
            gap = max(180.0, float(self.store.settings()["cooldown_min"]) * 60)
            if len(people) >= need and now - self.vc_alert.get(login, -1e18) > gap:
                self.vc_alert[login] = now
                await self.viewer_alert(login, recent, len(people))

    async def viewer_alert(self, login: str, recent: list, people: int) -> None:
        info = self.store.channels().get(login) or {}
        name = info.get("name") or login
        top = sorted(recent, key=lambda c: -int(c.get("view_count") or 0))[:3]
        links = "\n".join(f"• {c.get('url')}" + (f" — «{c.get('title')}»" if c.get("title") else "") for c in top)
        best = top[0]
        rec = ""
        if best.get("video_id") and best.get("vod_offset") is not None:
            rec = f"\nЗапись эфира: {vod_link(best['video_id'], max(0, int(best['vod_offset']) - 30))}"
        last = self.last_msg.get(login)
        if last and self.wall() - last[0] <= 240:
            # момент уже прислан по чату — подтверждаем ответом на то сообщение
            await self.tg.send(self.store.owner, f"🎬 Зрители тоже клипнули этот момент: {len(recent)} клип. от {people} чел.\n{links}",
                               reply_to=last[1])
            return
        c = self.chats.get(login)
        if c is not None:
            c.last_clip = self.clock()  # чат не дублирует этот момент
        await self.tg.send(self.store.owner,
                           f"🎬 {name} — зрители клипают: {len(recent)} клип. от {people} чел. за 2 мин\n{links}{rec}")

    # ------------------------------------------------------------ оценки 👍 / 👎
    async def on_callback(self, q: dict) -> None:
        data, qid = q.get("data") or "", q.get("id", "")
        msg = q.get("message") or {}
        if q.get("from", {}).get("id") != self.store.owner:
            await self.tg.answer(qid, "Это личный бот")
            return
        if not data.startswith("fb:") or data == "fb:done":
            await self.tg.answer(qid, "Уже учтено")
            return
        _, v, login = data.split(":", 2)
        good = v == "1"
        info = self.store.channels().get(login)
        if info is None:
            await self.tg.answer(qid, "Этого канала уже нет в списке")
            return
        tune = float(info.get("tune", 1.0))
        tune = max(TUNE_MIN, tune * 0.95) if good else min(TUNE_MAX, tune * 1.12)
        self.store.rate(login, good, round(tune, 3))
        name = info.get("name") or login
        await self.tg.answer(qid, (f"👍 Учту: для {name} буду клипать чуть чаще" if good else f"👎 Учту: для {name} буду клипать реже")
                             + f" (порог ×{str(self.thr(login)).replace('.', ',')})")
        if msg.get("message_id"):
            await self.tg.set_buttons(msg["chat"]["id"], msg["message_id"], [[("👍 учтено" if good else "👎 учтено", "fb:done")]])

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
                   "/minmsgs": self.cmd_minmsgs, "/clips": self.cmd_clips, "/vclips": self.cmd_vclips, "/pause": self.cmd_pause, "/resume": self.cmd_resume,
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
                         f"оценки 👍{info.get('good', 0)} 👎{info.get('bad', 0)}, порог ×{str(self.thr(login)).replace('.', ',')}, "
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
        await self._num(chat_id, arg, "cooldown_min", 2, 60, int, "Пауза между моментами канала, минут")

    async def cmd_clips(self, chat_id, arg) -> None:
        await self._num(chat_id, arg, "clips", 1, 2, int,
                        "Клипов на момент (1 — минута с моментом посередине, 2 — минута до и минута после)")

    async def cmd_vclips(self, chat_id, arg) -> None:
        await self._num(chat_id, arg, "vclips", 0, 5, int, "Сколько зрителей должны клипнуть за 2 минуты (0 — не следить)")

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
            f"Порог: ×{st['threshold']} · минимум {st['min_msgs']} сообщ. за 15 с · пауза {st['cooldown_min']} мин\n"
            f"Клипы: {'два по минуте (до и после)' if int(st.get('clips', 1)) == 2 else 'один на минуту, момент посередине'}\n"
            f"Клипы зрителей: {('пишу, когда клипнули ' + str(st.get('vclips', 2)) + ' чел. за 2 мин') if int(st.get('vclips', 2)) else 'не слежу'}"),
            KEYBOARD)

    # ------------------------------------------------------------ циклы
    async def poll(self, stop: asyncio.Event) -> None:
        offset, down = 0, False
        while not stop.is_set():
            try:
                ups = await self.tg.updates(offset)
                if down:
                    print("Телеграм снова доступен", flush=True)
                    down = False
                for u in ups:
                    offset = u["update_id"] + 1
                    try:
                        if "message" in u:
                            await self.handle(u["message"])
                        elif "callback_query" in u:
                            await self.on_callback(u["callback_query"])
                    except Exception as e:  # noqa: BLE001 — одна неудачная команда не роняет бота
                        print("ошибка команды:", e, flush=True)
            except (OSError, asyncio.TimeoutError, RuntimeError) as e:
                if not down:  # в журнал — один раз за сбой, чтобы он не разрастался
                    print("Телеграм недоступен:", e, flush=True)
                    down = True
                await asyncio.sleep(5)

    async def ticker(self, stop: asyncio.Event) -> None:
        while not stop.is_set():
            await asyncio.sleep(2)
            try:
                await self.tick()
            except Exception as e:  # noqa: BLE001
                print("ошибка проверки:", e, flush=True)

    async def watcher(self, stop: asyncio.Event) -> None:
        down = False
        while not stop.is_set():
            await asyncio.sleep(self.watch_every)
            try:
                await self.watch_once()
                down = False
            except (TwitchError, OSError, asyncio.TimeoutError) as e:
                if not down:
                    print("клипы зрителей недоступны:", e, flush=True)
                    down = True
            except Exception as e:  # noqa: BLE001
                print("ошибка клипов зрителей:", e, flush=True)

    async def run(self, stop: asyncio.Event) -> None:
        try:
            await self.tg.set_commands(COMMANDS)
        except (OSError, RuntimeError) as e:
            print("не удалось задать команды:", e, flush=True)
        work = [asyncio.create_task(c) for c in (self.poll(stop), self.ticker(stop), self.watcher(stop), self.chat.run(stop))]
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
    tg = Telegram(env("TELEGRAM_BOT_TOKEN"))
    tw = Twitch(env("TWITCH_CLIENT_ID"), env("TWITCH_CLIENT_SECRET"), os.environ.get("TWITCH_REDIRECT_URI", "http://localhost"), store)
    bot = ClipBot(store, tg, tw, env("BOT_PAIR_CODE"))
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, stop.set)
    print("Бот автоклипов запущен", flush=True)
    await bot.run(stop)
    os._exit(0)  # не ждать потоков с долгими запросами (long polling) — всё сохранено


if __name__ == "__main__":
    asyncio.run(main())
