"""Twitch по официальному API: вход владельца бота (OAuth, код авторизации), пользователи, создание клипа,
чтение чата по IRC с токеном (только чтение). Клип создаётся встроенным инструментом Twitch от имени владельца."""
from __future__ import annotations

import asyncio
import time
import urllib.parse
from dataclasses import dataclass

from .net import request, ssl_context

SCOPES = "clips:edit chat:read"


class TwitchError(Exception):
    """Ошибка с понятным текстом для сообщения в Телеграм."""


def auth_url(client_id: str, redirect_uri: str, state: str, id_base: str = "https://id.twitch.tv") -> str:
    q = urllib.parse.urlencode({"response_type": "code", "client_id": client_id, "redirect_uri": redirect_uri,
                                "scope": SCOPES, "state": state, "force_verify": "true"})
    return f"{id_base}/oauth2/authorize?{q}"


def parse_code(text: str) -> tuple[str | None, str | None]:
    """Код из адреса, на который Twitch вернул браузер («http://localhost/?code=…&state=…»), или голый код."""
    text = (text or "").strip()
    if "code=" in text:
        q = urllib.parse.parse_qs(urllib.parse.urlsplit(text if "://" in text else "http://x/?" + text.split("?", 1)[-1]).query)
        return (q.get("code") or [None])[0], (q.get("state") or [None])[0]
    if text and all(ch.isalnum() for ch in text) and 20 <= len(text) <= 64:
        return text, None
    return None, None


def login_of(s: str) -> str:
    """«https://www.twitch.tv/5opka/videos» → «5opka»."""
    s = (s or "").strip().lower()
    if "twitch.tv/" in s:
        s = s.split("twitch.tv/", 1)[1]
    s = s.split("/")[0].split("?")[0].lstrip("@#")
    return "".join(ch for ch in s if ch.isalnum() or ch == "_")[:25]


@dataclass
class Clip:
    id: str
    url: str
    edit_url: str = ""


class Twitch:
    def __init__(self, client_id: str, client_secret: str, redirect_uri: str, store,
                 api_base: str = "https://api.twitch.tv/helix", id_base: str = "https://id.twitch.tv"):
        self.client_id = client_id
        self.secret = client_secret
        self.redirect = redirect_uri
        self.store = store
        self.api = api_base
        self.idb = id_base
        self._lock = asyncio.Lock()

    # ------------------------------------------------------------ вход
    def auth_url(self, state: str) -> str:
        return auth_url(self.client_id, self.redirect, state, self.idb)

    async def exchange(self, code: str) -> str:
        """Код авторизации → токены; возвращает логин Twitch владельца."""
        r = await request("POST", f"{self.idb}/oauth2/token", form={
            "client_id": self.client_id, "client_secret": self.secret, "code": code,
            "grant_type": "authorization_code", "redirect_uri": self.redirect})
        if r.status != 200:
            raise TwitchError("Twitch не принял код. Ссылка из /login действует несколько минут — получите новую и повторите. "
                              "Проверьте, что в приложении на dev.twitch.tv адрес возврата — " + self.redirect)
        await self._save_tokens(r.json())
        info = await self.validate()
        self.store.update_twitch(login=info["login"], user_id=info["user_id"])
        return info["login"]

    async def validate(self) -> dict:
        r = await request("GET", f"{self.idb}/oauth2/validate",
                          headers={"Authorization": "OAuth " + self.store.twitch().get("access", "")})
        if r.status != 200:
            raise TwitchError("Вход в Twitch устарел — отправьте /login")
        return r.json()

    async def _save_tokens(self, tok: dict) -> None:
        self.store.update_twitch(access=tok["access_token"], refresh=tok.get("refresh_token", ""),
                                 expires_at=time.time() + int(tok.get("expires_in", 3600)) - 120)

    async def refresh(self) -> None:
        async with self._lock:
            ref = self.store.twitch().get("refresh")
            if not ref:
                raise TwitchError("Нет входа в Twitch — отправьте /login")
            r = await request("POST", f"{self.idb}/oauth2/token", form={
                "client_id": self.client_id, "client_secret": self.secret,
                "grant_type": "refresh_token", "refresh_token": ref})
            if r.status != 200:
                raise TwitchError("Вход в Twitch истёк — отправьте /login")
            await self._save_tokens(r.json())

    async def token(self) -> str:
        t = self.store.twitch()
        if not t.get("access"):
            raise TwitchError("Нет входа в Twitch — отправьте /login")
        if t.get("expires_at", 0) < time.time():
            await self.refresh()
        return self.store.twitch()["access"]

    # ------------------------------------------------------------ Helix
    async def helix(self, method: str, path: str, params: dict | list | None = None) -> tuple[int, dict]:
        for attempt in range(2):
            h = {"Client-Id": self.client_id, "Authorization": "Bearer " + await self.token()}
            r = await request(method, f"{self.api}/{path}", params=params, headers=h)
            if r.status == 401 and attempt == 0:
                await self.refresh()
                continue
            return r.status, r.json()
        return 401, {}

    async def user(self, login: str) -> dict | None:
        st, body = await self.helix("GET", "users", {"login": login})
        if st != 200:
            raise TwitchError(f"Twitch ответил {st} — попробуйте позже")
        data = body.get("data") or []
        return data[0] if data else None

    async def create_clip(self, broadcaster_id: str, duration: float = 60) -> Clip:
        """Клип последних `duration` секунд эфира (Twitch: 5–60 с) — как кнопка «Клип» в плеере.
        Обработка у Twitch занимает до ~15 с."""
        st, body = await self.helix("POST", "clips", {"broadcaster_id": broadcaster_id,
                                                      "duration": f"{max(5.0, min(60.0, duration)):g}"})
        if st == 202:
            d = (body.get("data") or [{}])[0]
            cid = d.get("id", "")
            return Clip(id=cid, url=f"https://clips.twitch.tv/{cid}", edit_url=d.get("edit_url", ""))
        msg = str(body.get("message", ""))
        if st == 404:
            raise TwitchError("канал не в эфире — клип не создать")
        if st == 403:
            raise TwitchError("канал запретил клипы (или только для подписчиков/фолловеров)")
        if st == 429:
            raise TwitchError("Twitch просит подождать (слишком много клипов)")
        raise TwitchError(f"Twitch ответил {st}: {msg[:120]}")

    async def live(self, user_ids: list[str]) -> set[str]:
        """Какие из каналов сейчас в эфире (официальный Get Streams, до 100 за раз)."""
        out: set[str] = set()
        for i in range(0, len(user_ids), 100):
            params = [("user_id", u) for u in user_ids[i:i + 100]] + [("first", "100")]
            st, body = await self.helix("GET", "streams", params)
            if st != 200:
                raise TwitchError(f"Twitch ответил {st} на список эфиров")
            out |= {d.get("user_id", "") for d in body.get("data") or []}
        return out

    async def recent_clips(self, broadcaster_id: str, since: float) -> list[dict]:
        """Клипы канала, созданные после since (unix-время) — их делают зрители (официальный Get Clips)."""
        started = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(since))
        st, body = await self.helix("GET", "clips", {"broadcaster_id": broadcaster_id, "started_at": started, "first": "20"})
        if st != 200:
            raise TwitchError(f"Twitch ответил {st} на список клипов")
        return body.get("data") or []

    async def clip_ready(self, clip_id: str, wait: float = 20.0, step: float = 3.0) -> dict | None:
        """Ждёт, пока Twitch обработает клип; None — не обработался (такое бывает, если эфир на паузе)."""
        t0 = time.monotonic()
        while time.monotonic() - t0 < wait:
            await asyncio.sleep(step)
            st, body = await self.helix("GET", "clips", {"id": clip_id})
            data = body.get("data") or []
            if st == 200 and data:
                return data[0]
        return None


# ------------------------------------------------------------ чат (IRC, только чтение)

def parse_privmsg(line: str) -> tuple[str, str, str] | None:
    """«@tags :ник!ник@ник.tmi.twitch.tv PRIVMSG #канал :текст» → (канал, текст, ник автора)."""
    if " PRIVMSG #" not in line:
        return None
    head, rest = line.split(" PRIVMSG #", 1)
    if " :" not in rest:
        return None
    ch, text = rest.split(" :", 1)
    prefix = head.rsplit(" :", 1)[-1].lstrip(":") if " :" in head or head.startswith(":") else ""
    user = prefix.split("!", 1)[0].lower() if "!" in prefix else ""
    return ch.strip().lower(), text.rstrip("\r\n"), user


class Chat:
    """Одно соединение с чатом Twitch на все каналы; переподключается само."""

    def __init__(self, twitch: Twitch, on_message, host: str = "irc.chat.twitch.tv", port: int = 6697, use_tls: bool = True):
        self.tw = twitch
        self.on_message = on_message
        self.host, self.port, self.tls = host, port, use_tls
        self.channels: set[str] = set()
        self.writer: asyncio.StreamWriter | None = None
        self.connected = asyncio.Event()
        self._wake = asyncio.Event()

    def reconnect(self) -> None:
        """После /login: переподключиться с новым токеном сразу, не дожидаясь паузы."""
        if self.writer:
            self.writer.close()
        self._wake.set()

    async def join(self, ch: str) -> None:
        self.channels.add(ch)
        if self.writer:
            self._send(f"JOIN #{ch}")

    async def part(self, ch: str) -> None:
        self.channels.discard(ch)
        if self.writer:
            self._send(f"PART #{ch}")

    def _send(self, line: str) -> None:
        if self.writer:
            self.writer.write((line + "\r\n").encode())

    async def run(self, stop: asyncio.Event) -> None:
        delay = 2
        while not stop.is_set():
            try:
                await self._session(stop)
                delay = 2
            except TwitchError:
                self._wake.clear()
                try:  # нет входа — ждём /login (или 30 с)
                    await asyncio.wait_for(self._wake.wait(), 30)
                except asyncio.TimeoutError:
                    pass
            except (OSError, asyncio.IncompleteReadError, ConnectionError):
                await asyncio.sleep(delay)
                delay = min(delay * 2, 60)
            finally:
                self.connected.clear()
                self.writer = None

    async def _session(self, stop: asyncio.Event) -> None:
        tok = await self.tw.token()
        nick = self.tw.store.twitch().get("login") or "justinfan12345"
        ctx = ssl_context() if self.tls else None
        reader, writer = await asyncio.open_connection(self.host, self.port, ssl=ctx)
        self.writer = writer
        self._send(f"PASS oauth:{tok}")
        self._send(f"NICK {nick}")
        for ch in sorted(self.channels):
            self._send(f"JOIN #{ch}")
        await writer.drain()
        self.connected.set()
        try:
            while not stop.is_set():
                raw = await asyncio.wait_for(reader.readline(), timeout=400)
                if not raw:
                    raise ConnectionError("чат закрыл соединение")
                line = raw.decode("utf-8", "replace")
                if line.startswith("PING"):
                    self._send("PONG" + line[4:].rstrip("\r\n"))
                    await writer.drain()
                    continue
                if "Login authentication failed" in line or "Improperly formatted auth" in line:
                    await self.tw.refresh()
                    raise ConnectionError("переподключение с новым токеном")
                m = parse_privmsg(line)
                if m:
                    self.on_message(m[0], m[1], time.time(), m[2])
        except asyncio.TimeoutError as e:
            raise ConnectionError("чат молчит — переподключаюсь") from e
        finally:
            writer.close()
