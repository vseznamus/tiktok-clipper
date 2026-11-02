"""Телеграм-бот автоклипов: всплески чата, разбор адресов, и целиком — с поддельными Telegram, Twitch и чатом."""
from __future__ import annotations

import asyncio
import functools
import json
import os
import stat
import time

import pytest

pytest.importorskip("aiohttp")  # только для поддельных серверов в тесте; самому боту сторонние пакеты не нужны
from aiohttp import web  # noqa: E402

from clipbot.bot import ClipBot, vod_link  # noqa: E402
from clipbot.spike import ChannelChat  # noqa: E402
from clipbot.store import Store  # noqa: E402
from clipbot.telegram import Telegram  # noqa: E402
from clipbot.twitch import Chat, Twitch, auth_url, login_of, parse_code, parse_privmsg  # noqa: E402


# ------------------------------------------------------------------ всплески

def steady(c: ChannelChat, t0: int, secs: int, per_sec: int = 1, text: str = "привет всем", users: int = 60) -> None:
    """Чат: per_sec сообщений в секунду от users разных зрителей по кругу."""
    for s in range(secs):
        for k in range(per_sec):
            c.add(t0 + s + k / (per_sec + 1), text, f"viewer{(s * per_sec + k) % users}")


def test_spike_detects_burst_with_word():
    c = ChannelChat(threshold=3, min_msgs=15, cooldown=180)
    steady(c, 1000, 180)                                # 15 сообщений за окно — обычный темп
    assert c.check(1179.5) is None
    steady(c, 1180, 15, per_sec=5, text="KEKW ахах")    # 75 сообщений с реакциями от 60 человек
    sp = c.check(1194.9)
    assert sp is not None and sp.msgs == 75 and sp.authors == 60 and sp.ratio >= 3
    assert sp.word in ("KEKW", "ахах")
    assert sp.wave == "kekw ахах" and sp.wave_users == 60       # волна одинаковых сообщений


def test_spammer_is_not_a_spike_but_many_people_are():
    c = ChannelChat(threshold=3, min_msgs=15, cooldown=180)
    steady(c, 0, 180, users=15)                         # обычно: 15 сообщений от 15 человек за окно
    for i in range(75):                                 # один зритель строчит 75 сообщений за 15 с
        c.add(180 + i * 0.2, f"купите мой канал {i}", "spammer")
    assert c.check(194.9) is None
    for i in range(75):                                 # и чат-боты не в счёт
        c.add(195 + i * 0.2, "!команды", "nightbot")
    assert c.check(209.9) is None
    c2 = ChannelChat(threshold=3, min_msgs=15, cooldown=180)
    steady(c2, 0, 180, users=15)
    texts = ["ахахах", "ору", "это база", "лол", "что он сделал", "жесть", "ааааа", "KEKW"]
    for i in range(75):                                 # 60 разных людей пишут разное — всплеск, но не волна
        c2.add(180 + i * 0.2, texts[i % len(texts)] + f" {i}", f"p{i % 60}")
    sp = c2.check(194.9)
    assert sp is not None and sp.authors == 60 and sp.wave is None


def test_wave_key_and_wave_threshold():
    from clipbot.spike import wave_key
    assert wave_key("KEKW KEKW KEKW") == "kekw"
    assert wave_key("ахахаааааа") == wave_key("ахахааа")
    c = ChannelChat(threshold=3, min_msgs=15, cooldown=180)
    steady(c, 0, 180, users=15)
    for i in range(60):                                 # 3 человека пишут «F» — не волна; 40 человек — волна
        c.add(180 + i * 0.25, "F", f"f{i % 3}" if i < 20 else f"w{i}")
    w, n = c.wave(194.9, 43)
    assert w == "f" and n == 43


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
    assert parse_privmsg(line) == ("5opka", "KEKW ну ты даёшь", "vasya")
    assert parse_privmsg(":Petya!petya@petya.tmi.twitch.tv PRIVMSG #5opka :привет") == ("5opka", "привет", "petya")
    assert parse_privmsg(":tmi.twitch.tv 001 me :Welcome") is None
    assert vod_link("5", 63) == "https://www.twitch.tv/videos/5?t=1m3s"
    assert vod_link("5", 7384) == "https://www.twitch.tv/videos/5?t=2h3m4s"
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
        self.clip_requests: list[dict] = []
        self.offline_from = 0
        self.uid = 0
        self.calls: list[tuple] = []
        self.live: set[str] = set()
        self.viewer_clips: dict[str, list] = {}

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
            return web.json_response({"ok": True, "result": {"message_id": len(self.sent)}})
        if method in ("answerCallbackQuery", "editMessageReplyMarkup"):
            self.calls.append((method, body))
        return web.json_response({"ok": True, "result": True})

    def press(self, from_id: int, message_id: int, data: str):
        self.uid += 1
        self.updates.append({"update_id": self.uid, "callback_query": {"id": f"cb{self.uid}", "from": {"id": from_id},
                             "message": {"message_id": message_id, "chat": {"id": 7}}, "data": data}})

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
        self.clip_requests.append(dict(req.query))
        st = self.clip_status.get(req.query["broadcaster_id"], 404)
        if self.offline_from and len(self.clip_requests) >= self.offline_from:
            st = 404                                            # эфир закончился
        if st != 202:
            return web.json_response({"message": "nope"}, status=st)
        cid = f"Clip{len(self.clip_requests)}"
        return web.json_response({"data": [{"id": cid, "edit_url": f"https://clips.twitch.tv/{cid}/edit"}]}, status=202)

    async def streams(self, req):
        ids = req.query.getall("user_id")
        return web.json_response({"data": [{"user_id": i, "type": "live"} for i in ids if i in self.live]})

    async def clips_get(self, req):
        if "id" not in req.query:                               # список клипов канала (их делают зрители)
            assert "started_at" in req.query
            return web.json_response({"data": self.viewer_clips.get(req.query["broadcaster_id"], [])})
        cid = req.query["id"]
        return web.json_response({"data": [{"id": cid, "url": f"https://clips.twitch.tv/{cid}", "video_id": "999",
                                            "vod_offset": 3663, "duration": 60}]})

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
    app.router.add_get("/helix/streams", fake.streams)
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
    bot.after_delay = 0.3
    bot.watch_every = 0.2
    bot.center_delay = 0.3
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
        fake.say(7, "/cooldown 1")
        await wait_for(lambda: "число от 2 до 60" in fake.texts()[-1])

        # по умолчанию: один минутный клип через 30 с (в тесте 0,3 с) — момент посередине
        sent_before = len(fake.sent)
        c = bot.chats["5opka"]
        c.per_sec.clear(); c.started = None
        steady(c, 19_000, 180)
        steady(c, 19_180, 15, per_sec=5, text="KEKW")
        now[0] = 19_194.9
        await bot.tick()
        await asyncio.sleep(0.1)
        assert fake.clip_requests == []                          # ещё ждёт 30 секунд
        await wait_for(lambda: len(fake.sent) > sent_before)
        centered = fake.sent[-1]
        msg = centered["text"]
        assert msg.startswith("🔥 5opka — чат ×") and "от 60 чел." in msg and "волна «kekw» ×60" in msg
        assert "Клип (минута, момент посередине): https://clips.twitch.tv/Clip1" in msg
        assert "✂️ Редактор клипа (субтитры, обрезка; ссылка живёт 24 ч): https://clips.twitch.tv/Clip1/edit" in msg
        kb = centered["reply_markup"]["inline_keyboard"][0]
        assert [b["callback_data"] for b in kb] == ["fb:1:5opka", "fb:0:5opka"]
        assert "Момент в эфире: 1:01:33" in msg                  # клип 1:01:03–1:02:03, момент — его середина
        assert "Запись эфира с начала клипа: https://www.twitch.tv/videos/999?t=1h1m3s" in msg
        assert fake.clip_requests == [{"broadcaster_id": "77", "duration": "60"}]
        assert store.channels()["5opka"]["clips"] == 1
        fake.say(7, "/status")
        await wait_for(lambda: "один на минуту, момент посередине" in fake.texts()[-1])
        assert "клипнули 2 чел. за 2 мин" in fake.texts()[-1]

        # 👎 под клипом: порог канала растёт; чужое нажатие не учитывается; повторное — «уже учтено»
        mid = len(fake.sent) - 1                                 # номер сообщения с клипом (сообщения считаются с 1)
        fake.press(500, mid, "fb:0:5opka")
        await wait_for(lambda: any(m == "answerCallbackQuery" and b["text"] == "Это личный бот" for m, b in fake.calls))
        fake.press(7, mid, "fb:0:5opka")
        await wait_for(lambda: any(m == "editMessageReplyMarkup" for m, b in fake.calls))
        ans = [b["text"] for m, b in fake.calls if m == "answerCallbackQuery"][-1]
        assert ans.startswith("👎 Учту: для 5opka буду клипать реже") and "×2,8" in ans
        edit = [b for m, b in fake.calls if m == "editMessageReplyMarkup"][-1]
        assert edit["message_id"] == mid and edit["reply_markup"]["inline_keyboard"][0][0]["text"] == "👎 учтено"
        assert store.channels()["5opka"]["tune"] == 1.12 and store.channels()["5opka"]["bad"] == 1
        assert bot.thr("5opka") == 2.8
        fake.press(7, mid, "fb:done")
        await wait_for(lambda: [b["text"] for m, b in fake.calls if m == "answerCallbackQuery"][-1] == "Уже учтено")
        fake.press(7, mid, "fb:1:5opka")                       # 👍 возвращает порог вниз
        await wait_for(lambda: store.channels()["5opka"].get("good") == 1)
        assert store.channels()["5opka"]["tune"] == round(1.12 * 0.95, 3)

        # клипы зрителей: двое клипнули тот же момент → ответ на сообщение с нашим клипом; свои клипы не в счёт
        nowz = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() - 20))
        fake.viewer_clips["77"] = [
            {"id": "V1", "url": "https://clips.twitch.tv/V1", "creator_id": "501", "creator_name": "a", "created_at": nowz,
             "view_count": 5, "title": "ОРУ", "video_id": "999", "vod_offset": 3700},
            {"id": "V2", "url": "https://clips.twitch.tv/V2", "creator_id": "502", "creator_name": "b", "created_at": nowz,
             "view_count": 40, "title": "", "video_id": "999", "vod_offset": 3702},
            {"id": "MINE", "url": "https://clips.twitch.tv/MINE", "creator_id": "1", "creator_name": "me", "created_at": nowz,
             "view_count": 99, "video_id": "999", "vod_offset": 3690}]
        fake.live = {"77"}
        await wait_for(lambda: "Зрители тоже клипнули" in fake.texts()[-1])
        vc = fake.sent[-1]
        assert vc["reply_parameters"]["message_id"] == mid and "2 клип. от 2 чел." in vc["text"]
        assert vc["text"].index("V2") < vc["text"].index("V1") and "MINE" not in vc["text"] and "«ОРУ»" in vc["text"]
        await asyncio.sleep(0.6)
        assert sum("Зрители тоже клипнули" in t for t in fake.texts()) == 1  # тот же момент — один раз
        fake.live = set()

        # /clips 2: два клипа — «минута до» сразу и «минута после» через минуту
        fake.say(7, "/clips 3")
        await wait_for(lambda: "число от 1 до 2" in fake.texts()[-1])
        fake.say(7, "/clips 2")
        await wait_for(lambda: "✅ Клипов на момент" in fake.texts()[-1])
        fake.clip_requests.clear()
        sent_before = len(fake.sent)
        c.per_sec.clear(); c.started = None
        steady(c, 20_000, 180)
        steady(c, 20_180, 15, per_sec=5, text="KEKW")
        now[0] = 20_194.9
        await bot.tick()
        await wait_for(lambda: len(fake.sent) > sent_before)
        first = fake.sent[-1]
        msg = first["text"]
        assert msg.startswith("🔥 5opka — чат ×") and "волна «kekw»" in msg
        assert "Момент в эфире: 1:02:03" in msg                  # начало клипа 1:01:03 + 60 с
        assert "Запись эфира с минуты до момента: https://www.twitch.tv/videos/999?t=1h1m3s" in msg
        assert "① Минута до: https://clips.twitch.tv/Clip1" in msg and "② Минута после — пришлю через минуту" in msg
        assert "https://clips.twitch.tv/Clip1/edit" in msg
        assert fake.clip_requests[0] == {"broadcaster_id": "77", "duration": "60"}
        await bot.tick()                                         # пауза между моментами — новых клипов нет
        # через «минуту» (в тесте — 0,3 с) — второй клип ответом на первое сообщение
        await wait_for(lambda: len(fake.sent) > sent_before + 1)
        second = fake.sent[-1]
        assert second["text"].startswith("② Минута после: https://clips.twitch.tv/Clip2\n")
        assert second["text"].endswith("https://clips.twitch.tv/Clip2/edit")
        assert second["reply_parameters"]["message_id"] == len(fake.sent) - 1
        assert len(fake.clip_requests) == 2 and fake.clip_requests[1]["duration"] == "60"
        assert store.channels()["5opka"]["clips"] == 3
        await asyncio.sleep(0.4)
        assert len(fake.sent) == sent_before + 2

        # эфир закончился до «минуты после» — понятный ответ
        fake.offline_from = 4
        c.last_clip = -1e18
        steady(c, 20_400, 180)
        steady(c, 20_580, 15, per_sec=5, text="KEKW")
        now[0] = 20_594.9
        await bot.tick()
        await wait_for(lambda: "② Минута после не получилась — канал не в эфире" in fake.texts()[-1])
        assert store.channels()["5opka"]["clips"] == 4
        fake.offline_from = 0

        # канал без клипов → понятная ошибка, повтор не спамит
        n = bot.chats["noclips"]
        for k in range(2):
            n.per_sec.clear(); n.started = None; n.last_clip = -1e18
            steady(n, 30_000 + k * 1000, 180)
            steady(n, 30_180 + k * 1000, 15, per_sec=5, text="ОРУ")
            now[0] = 30_194.9 + k * 1000
            await bot.tick()
            await asyncio.sleep(0.3)
        fake.viewer_clips["88"] = [
            {"id": f"N{i}", "url": f"https://clips.twitch.tv/N{i}", "creator_id": f"6{i}", "created_at": time.strftime(
                "%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() - 10)), "view_count": i, "video_id": "555", "vod_offset": 100}
            for i in range(3)]
        fake.live = {"88"}
        await wait_for(lambda: "зрители клипают" in fake.texts()[-1])
        alone = fake.texts()[-1]
        assert alone.startswith("🎬 NOCLIPS — зрители клипают: 3 клип. от 3 чел.") and "videos/555?t=1m10s" in alone
        assert "reply_parameters" not in fake.sent[-1]
        fake.live = set()
        errs = [t for t in fake.texts() if "запретил клипы" in t]
        assert len(errs) == 1 and errs[0].startswith("⚠️ NOCLIPS")

        # пауза, список, удаление, статус
        fake.say(7, "⏸ Пауза")
        await wait_for(lambda: "на паузе" in fake.texts()[-1])
        assert store.settings()["paused"] is True
        fake.say(7, "/list")
        await wait_for(lambda: "клипов: 4" in fake.texts()[-1])
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
