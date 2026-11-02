"""Состояние бота в одном JSON-файле на сервере (права 600): владелец, каналы, настройки, токены Twitch."""
from __future__ import annotations

import json
import os
import threading
from pathlib import Path

DEFAULTS = {"threshold": 3.0, "cooldown_min": 3, "min_msgs": 15, "paused": False, "clips": 1}


class Store:
    def __init__(self, path: str | Path):
        self.path = Path(path)
        self._lock = threading.Lock()
        self.data = {"owner_id": None, "channels": {}, "settings": dict(DEFAULTS), "twitch": {}, "login_state": None}
        if self.path.is_file():
            try:
                loaded = json.loads(self.path.read_text("utf-8"))
                self.data.update(loaded)
                self.data["settings"] = {**DEFAULTS, **loaded.get("settings", {})}
            except (OSError, ValueError):
                pass  # повреждён — начнём с чистого

    def save(self) -> None:
        with self._lock:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.path.with_suffix(".tmp")
            tmp.write_text(json.dumps(self.data, ensure_ascii=False, indent=1), "utf-8")
            os.chmod(tmp, 0o600)
            tmp.replace(self.path)

    # владелец
    @property
    def owner(self):
        return self.data.get("owner_id")

    def set_owner(self, chat_id: int) -> None:
        self.data["owner_id"] = chat_id
        self.save()

    # каналы
    def channels(self) -> dict:
        return self.data["channels"]

    def add_channel(self, login: str, user_id: str, name: str) -> None:
        self.data["channels"][login] = {"id": user_id, "name": name, "clips": 0}
        self.save()

    def del_channel(self, login: str) -> bool:
        ok = self.data["channels"].pop(login, None) is not None
        self.save()
        return ok

    def count_clip(self, login: str) -> None:
        if login in self.data["channels"]:
            self.data["channels"][login]["clips"] = self.data["channels"][login].get("clips", 0) + 1
            self.save()

    # настройки
    def settings(self) -> dict:
        return self.data["settings"]

    def set(self, key: str, value) -> None:
        self.data["settings"][key] = value
        self.save()

    # Twitch
    def twitch(self) -> dict:
        return self.data["twitch"]

    def update_twitch(self, **kw) -> None:
        self.data["twitch"].update(kw)
        self.save()

    def set_login_state(self, s: str | None) -> None:
        self.data["login_state"] = s
        self.save()
