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
                          json={"offset": offset, "timeout": timeout, "allowed_updates": ["message", "callback_query"]})
        body = r.json()
        if not body.get("ok"):
            raise RuntimeError(f"Telegram getUpdates: {body.get('description', r.status)}")
        return body.get("result") or []

    async def send(self, chat_id: int, text: str, keyboard: list | None = None, reply_to: int | None = None,
                   buttons: list | None = None) -> int | None:
        """Отправляет сообщение; возвращает его номер (для ответа на него).
        keyboard — кнопки под полем ввода; buttons — кнопки под сообщением: [[(текст, данные), …], …]."""
        params = {"chat_id": chat_id, "text": text, "disable_web_page_preview": False}
        if keyboard is not None:
            params["reply_markup"] = {"keyboard": [[{"text": b} for b in row] for row in keyboard], "resize_keyboard": True}
        if buttons is not None:
            params["reply_markup"] = inline(buttons)
        if reply_to:
            params["reply_parameters"] = {"message_id": reply_to, "allow_sending_without_reply": True}
        res = await self.call("sendMessage", **params)
        return res.get("message_id") if isinstance(res, dict) else None

    async def set_buttons(self, chat_id: int, message_id: int, buttons: list) -> None:
        """Заменить кнопки под сообщением (пустой список — убрать)."""
        await self.call("editMessageReplyMarkup", chat_id=chat_id, message_id=message_id, reply_markup=inline(buttons))

    async def answer(self, callback_id: str, text: str = "") -> None:
        """Ответ на нажатие кнопки (всплывающая подсказка)."""
        await self.call("answerCallbackQuery", callback_query_id=callback_id, text=text)

    async def set_commands(self, cmds: list[tuple[str, str]]) -> None:
        await self.call("setMyCommands", commands=[{"command": c, "description": d} for c, d in cmds])


def inline(buttons: list) -> dict:
    return {"inline_keyboard": [[{"text": t, "callback_data": d} for t, d in row] for row in buttons]}
