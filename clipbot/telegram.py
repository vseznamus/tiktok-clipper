"""Минимальный клиент Telegram Bot API: получение сообщений (long polling) и отправка."""
from __future__ import annotations

from .net import request


class Telegram:
    def __init__(self, token: str, base: str = "https://api.telegram.org"):
        self.url = f"{base}/bot{token}"

    async def call(self, method: str, timeout: float = 30, **params):
        r = await request("POST", f"{self.url}/{method}", json=params, timeout=timeout)
        body = r.json()
        if not body.get("ok"):
            raise RuntimeError(f"Telegram {method}: {body.get('description', r.status)}")
        return body.get("result")

    async def updates(self, offset: int, timeout: int = 30) -> list:
        r = await request("POST", f"{self.url}/getUpdates", timeout=timeout + 15,
                          json={"offset": offset, "timeout": timeout, "allowed_updates": ["message"]})
        body = r.json()
        if not body.get("ok"):
            raise RuntimeError(f"Telegram getUpdates: {body.get('description', r.status)}")
        return body.get("result") or []

    async def send(self, chat_id: int, text: str, keyboard: list | None = None) -> None:
        params = {"chat_id": chat_id, "text": text, "disable_web_page_preview": False}
        if keyboard is not None:
            params["reply_markup"] = {"keyboard": [[{"text": b} for b in row] for row in keyboard], "resize_keyboard": True}
        await self.call("sendMessage", **params)

    async def set_commands(self, cmds: list[tuple[str, str]]) -> None:
        await self.call("setMyCommands", commands=[{"command": c, "description": d} for c, d in cmds])
