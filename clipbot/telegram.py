"""Минимальный клиент Telegram Bot API: получение сообщений (long polling) и отправка."""
from __future__ import annotations

import aiohttp


class Telegram:
    def __init__(self, session: aiohttp.ClientSession, token: str, base: str = "https://api.telegram.org"):
        self.s = session
        self.url = f"{base}/bot{token}"

    async def call(self, method: str, **params):
        async with self.s.post(f"{self.url}/{method}", json=params) as r:
            body = await r.json(content_type=None)
            if not body.get("ok"):
                raise RuntimeError(f"Telegram {method}: {body.get('description', r.status)}")
            return body.get("result")

    async def updates(self, offset: int, timeout: int = 30) -> list:
        return await self.call("getUpdates", offset=offset, timeout=timeout, allowed_updates=["message"])

    async def send(self, chat_id: int, text: str, keyboard: list | None = None) -> None:
        params = {"chat_id": chat_id, "text": text, "disable_web_page_preview": False}
        if keyboard is not None:
            params["reply_markup"] = {"keyboard": [[{"text": b} for b in row] for row in keyboard], "resize_keyboard": True}
        await self.call("sendMessage", **params)

    async def set_commands(self, cmds: list[tuple[str, str]]) -> None:
        await self.call("setMyCommands", commands=[{"command": c, "description": d} for c, d in cmds])
