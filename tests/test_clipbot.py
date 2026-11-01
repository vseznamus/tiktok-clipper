"""Телеграм-бот автоклипов: всплески чата, разбор адресов, и целиком — с поддельными Telegram, Twitch и чатом."""
from __future__ import annotations

import asyncio
import functools
import json
import os
import stat

import pytest

pytest.importorskip("aiohttp")  # только для поддельных серверов в тесте; самому боту сторонние пакеты не нужны
from aiohttp import web  # noqa: E402

from clipbot.bot import ClipBot  # noqa: E402
from clipbot.spike import ChannelChat  # noqa: E402
from clipbot.store import Store  # noqa: E402
from clipbot.telegram import Telegram  # noqa: E402
from clipbot.twitch import Chat, Twitch, auth_url, login_of, parse_code, parse_privmsg  # noqa: E402


# ------------------------------------------------------------------ всплески

def steady(c: ChannelChat, t0: int, secs: int, per_sec: int = 1, text: str = "привет всем") -> None:
    for s in range(secs):
        for k in range(per_sec):
            c.add(t0 + s + k / (per_sec + 1), text)


def test_spike_detects_burst_with_word():
    c = ChannelChat(threshold=3, min_msgs=15, cooldown=180)
    steady(c, 1000, 180)                                # 15 сообщений за окно — обычный темп
    assert c.check(1179.5) is None
    steady(c, 1180, 15, per_sec=5, text="KEKW ахах")    # 75 сообщений с реакциями
    sp = c.check(1194.9)
    assert sp is not None and sp.msgs == 75 and sp.ratio >= 5
    assert sp.word in ("KEKW", "ахах")


def test_spike_needs_history_min_msgs_and_respects_cooldown():
    c = ChannelChat(threshold=3, min_msgs=15, cooldown=180)
    steady(c, 0, 20, per_sec=5)
    assert c.check(19.5) is None                        # меньше минуты истории
    q = ChannelChat(threshold=3, min_msgs=15, cooldown=180)
    for s in range(0, 120, 30):                         # тихий чат: сообщение раз в 30 с
        q.add(s, "hi")
    for s in range(120, 130):
        q.add(s, "hi")                                  # 10 сообщений — «×10», но меньше min_msgs
    assert q.check(129.5) is None
    c2 = ChannelChat(threshold=3, min_msgs=15, cooldown=180)
    steady(c2, 0, 120)
    steady(c2, 120, 15, per_sec=6, text="LUL")
    assert c2.check(134.9) is not None
    c2.last_clip = 134.9
    steady(c2, 135, 15, per_sec=6, text="LUL")
    assert c2.check(149.9) is None                      # пауза после клипа


def test_parse_helpers():
    assert login_of("https://www.twitch.tv/5opka/videos?x=1") == "5opka"
    assert login_of("@Some_User") == "some_user"
    assert parse_code("http://localhost/?code=abc123&scope=clips%3Aedit&state=st1") == ("abc123", "st1")
    assert parse_code("abcdefghijklmnopqrstuvwxyz0123") == ("abcdefghijklmnopqrstuvwxyz0123", None)
    assert parse_code("привет") == (None, None)
    line = "@badge-info=;color=#FF0000 :vasya!vasya@vasya.tmi.twitch.tv PRIVMSG #5opka :KEKW ну ты даёшь\r\n"
    assert parse_privmsg(line) == ("5opka", "KEKW ну ты даёшь")
    assert parse_privmsg(":tmi.twitch.tv 001 me :Welcome") is None
    u = auth_url("cid", "http://localhost", "st")
    assert "scope=clips%3Aedit+chat%3Aread" in u and "client_id=cid" in u and "state=st" in u


def test_store_is_private_and_survives_restart(tmp_path):
    s = Store(tmp_path / "d" / "state.json")
    s.set_owner(42)
    s.add_channel("5opka", "77", "5opka")
    s.update_twitch(access="tok", refresh="ref")
    assert stat.S_IMODE(os.stat(s.path).st_mode) == 0o600
    s2 = Store(s.path)
    assert s2.owner == 42 and "5opka" in s2.channels() and s2.twitch()["refresh"] == "ref"
    assert s2.settings()["threshold"] == 3.0


# ------------------------------------------------------------------ всё вместе

class Fake:
    """Поддельные Telegram Bot API, Twitch (id + helix) и чат Twitch (IRC без TLS)."""

    TOKEN = "123:TEST"

    def __init__(self):
        self.updates: list[dict] = []
        self.sent: list[dict] = []
        self.irc_lines: list[str] = []
        self.irc_writers: list[asyncio.StreamWriter] = []
        self.clip_status = {"77": 202, "88": 403}
        self.tokens_issued = 0
        self.uid = 0

    # Telegram
    async def tg(self, req):
        method = req.match_info["method"]
        body = await req.json()
        if method == "getUpdates":
            for _ in range(20):
                if self.updates:
                    break
                await asyncio.sleep(0.02)
            ups, self.updates = self.updates, []
            return web.json_response({"ok": True, "result": ups})
        if method == "sendMessage":
            self.sent.append(body)
        return web.json_response({"ok": True, "result": True})

    def say(self, chat_id: int, text: str):
        self.uid += 1
        self.updates.append({"update_id": self.uid, "message": {"chat": {"id": chat_id}, "text": text}})

    # Twitch id
    async def token(self, req):
        f = await req.post()
        if f.get("grant_type") == "authorization_code" and f.get("code") != "goodcode":
            return web.json_response({"message": "Invalid authorization code"}, status=400)
        self.tokens_issued += 1
        return web.json_response({"access_token": f"tok{self.tokens_issued}", "refresh_token": "ref", "expires_in": 14000})

    async def validate(self, req):
        return web.json_response({"login": "me", "user_id": "1", "client_id": "cid"})

    # Helix
    async def users(self, req):
        assert req.headers["Client-Id"] == "cid" and req.headers["Authorization"].startswith("Bearer tok")
        login = req.query["login"]
        known = {"5opka": "77", "noclips": "88"}
        data = [{"id": known[login], "login": login, "display_name": login.upper() if login == "noclips" else login}] if login in known else []
        return web.json_response({"data": data})

    async def clips_post(self, req):
        st = self.clip_status.get(req.query["broadcaster_id"], 404)
        if st != 202:
            return web.json_response({"message": "nope"}, status=st)
        return web.json_response({"data": [{"id": "Clip1", "edit_url": "https://clips.twitch.tv/Clip1/edit"}]}, status=202)

    async def clips_get(self, req):
        return web.json_response({"data": [{"id": req.query["id"], "url": "https://clips.twitch.tv/Clip1", "vod_offset": 3723}]})

    # IRC
    async def irc(self, reader, writer):
        self.irc_writers.append(writer)
        try:
            while True:
                raw = await reader.readline()
                if not raw:
                    break
                self.irc_lines.append(raw.decode().strip())
        except ConnectionError:
            pass

    def chat(self, ch: str, text: str):
        for w in self.irc_writers:
            if not w.is_closing():
                w.write(f"@x=1 :u!u@u.tmi.twitch.tv PRIVMSG #{ch} :{text}\r\n".encode())

    def texts(self) -> list[str]:
        return [m["text"] for m in self.sent]


async def wait_for(pred, timeout=5.0):
    for _ in range(int(timeout / 0.02)):
        if pred():
            return
        await asyncio.sleep(0.02)
    raise AssertionError("не дождались")


def test_bot_end_to_end(tmp_path):
    asyncio.run(_end_to_end(tmp_path))


async def _end_to_end(tmp_path):
    fake = Fake()
    app = web.Application()
    app.router.add_post(f"/bot{Fake.TOKEN}/{{method}}", fake.tg)
    app.router.add_post("/oauth2/token", fake.token)
    app.router.add_get("/oauth2/validate", fake.validate)
    app.router.add_get("/helix/users", fake.users)
    app.router.add_post("/helix/clips", fake.clips_post)
    app.router.add_get("/helix/clips", fake.clips_get)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", 0)
    await site.start()
    port = site._server.sockets[0].getsockname()[1]
    irc = await asyncio.start_server(fake.irc, "127.0.0.1", 0)
    irc_port = irc.sockets[0].getsockname()[1]
    base = f"http://127.0.0.1:{port}"

    now = [10_000.0]
    store = Store(tmp_path / "state.json")
    stop = asyncio.Event()
    tg = Telegram(Fake.TOKEN, base)
    tw = Twitch("cid", "secret", "http://localhost", store, api_base=f"{base}/helix", id_base=base)
    tw.clip_ready = functools.partial(Twitch.clip_ready, tw, step=0.05)
    chat = Chat(tw, None, host="127.0.0.1", port=irc_port, use_tls=False)
    bot = ClipBot(store, tg, tw, "13572468", chat=chat, clock=lambda: now[0])
    run = asyncio.create_task(bot.run(stop))
    try:
        # чужой человек без кода — бот не привязывается
        fake.say(500, "/start")
        await wait_for(lambda: len(fake.sent) >= 1)
        assert store.owner is None and "код привязки" in fake.sent[-1]["text"]
        fake.say(500, "/start 00000000")
        await wait_for(lambda: len(fake.sent) >= 2)
        assert store.owner is None

        # владелец с кодом → приветствие и ссылка входа в Twitch
        fake.say(7, "/start 13572468")
        await wait_for(lambda: any("oauth2/authorize" in t for t in fake.texts()))
        assert store.owner == 7
        state = store.data["login_state"]
        assert state and f"state={state}" in fake.texts()[-1]
        assert fake.sent[-2]["reply_markup"]["keyboard"][0][0]["text"] == "📋 Каналы"

        # теперь чужой — отказ
        fake.say(500, "/list")
        await wait_for(lambda: "личный бот" in fake.texts()[-1])

        # плохой код, затем правильный адрес возврата
        fake.say(7, f"http://localhost/?code=badcode&state={state}")
        await wait_for(lambda: "не принял код" in fake.texts()[-1])
        fake.say(7, f"http://localhost/?code=goodcode&scope=clips%3Aedit+chat%3Aread&state={state}")
        await wait_for(lambda: "Вошёл в Twitch как me" in fake.texts()[-1])
        assert store.twitch()["access"].startswith("tok") and store.data["login_state"] is None
        await wait_for(lambda: any(line.startswith("PASS oauth:tok") for line in fake.irc_lines))
        assert "NICK me" in fake.irc_lines

        # каналы: кнопка «Добавить» → логин; ссылка; несуществующий; дубликат
        fake.say(7, "➕ Добавить канал")
        await wait_for(lambda: "Пришлите логин" in fake.texts()[-1])
        fake.say(7, "https://www.twitch.tv/5opka")
        await wait_for(lambda: "Слежу за 5opka" in fake.texts()[-1])
        await wait_for(lambda: "JOIN #5opka" in fake.irc_lines)
        fake.say(7, "/add nobody_here")
        await wait_for(lambda: "не найден" in fake.texts()[-1])
        fake.say(7, "/add 5opka")
        await wait_for(lambda: "уже в списке" in fake.texts()[-1])
        fake.say(7, "/add noclips")
        await wait_for(lambda: "Слежу за NOCLIPS" in fake.texts()[-1])

        # сообщения из чата доходят до счётчика
        await wait_for(lambda: chat.connected.is_set())
        fake.chat("5opka", "привет")
        await wait_for(lambda: bot.chats["5opka"].started is not None)

        # настройки
        fake.say(7, "/threshold 2,5")
        await wait_for(lambda: "✅ Порог" in fake.texts()[-1])
        assert bot.chats["5opka"].threshold == 2.5
        fake.say(7, "/cooldown 999")
        await wait_for(lambda: "число от 1 до 60" in fake.texts()[-1])

        # всплеск → клип → ссылка в Телеграм
        sent_before = len(fake.sent)
        c = bot.chats["5opka"]
        c.per_sec.clear(); c.started = None
        steady(c, 20_000, 180)
        steady(c, 20_180, 15, per_sec=5, text="KEKW")
        now[0] = 20_194.9
        await bot.tick()
        await wait_for(lambda: len(fake.sent) > sent_before)
        msg = fake.texts()[-1]
        assert msg.startswith("🔥 5opka — чат ×") and "«KEKW»" in msg
        assert "Клип: https://clips.twitch.tv/Clip1 · эфир 1:02:03" in msg
        assert store.channels()["5opka"]["clips"] == 1
        await bot.tick()                                         # пауза между клипами — второго нет
        await asyncio.sleep(0.2)
        assert len(fake.sent) == sent_before + 1

        # канал без клипов → понятная ошибка, повтор не спамит
        n = bot.chats["noclips"]
        for k in range(2):
            n.per_sec.clear(); n.started = None; n.last_clip = -1e18
            steady(n, 30_000 + k * 1000, 180)
            steady(n, 30_180 + k * 1000, 15, per_sec=5, text="ОРУ")
            now[0] = 30_194.9 + k * 1000
            await bot.tick()
            await asyncio.sleep(0.3)
        errs = [t for t in fake.texts() if "запретил клипы" in t]
        assert len(errs) == 1 and errs[0].startswith("⚠️ NOCLIPS")

        # пауза, список, удаление, статус
        fake.say(7, "⏸ Пауза")
        await wait_for(lambda: "на паузе" in fake.texts()[-1])
        assert store.settings()["paused"] is True
        fake.say(7, "/list")
        await wait_for(lambda: "клипов: 1" in fake.texts()[-1])
        fake.say(7, "/del 5opka")
        await wait_for(lambda: "Больше не слежу за 5opka" in fake.texts()[-1])
        await wait_for(lambda: "PART #5opka" in fake.irc_lines)
        fake.say(7, "/status")
        await wait_for(lambda: "вход как me" in fake.texts()[-1])
        assert "Каналов: 1" in fake.texts()[-1] and "на паузе" in fake.texts()[-1]
    finally:
        stop.set()
        fake.say(7, "/help")                                    # разбудить long polling
        for w in fake.irc_writers:
            w.close()
        await asyncio.wait_for(run, 5)
    irc.close()
    await runner.cleanup()

    # после перезапуска всё на месте
    saved = json.loads((tmp_path / "state.json").read_text("utf-8"))
    assert saved["owner_id"] == 7 and list(saved["channels"]) == ["noclips"]
    assert saved["settings"]["threshold"] == 2.5
