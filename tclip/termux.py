"""Мелочи для Android/Termux: блокировка сна и добавление файла в галерею."""
from __future__ import annotations

import shutil
import subprocess
import threading

_lock = threading.Lock()
_held = False


def _run(cmd: list[str]) -> bool:
    if not shutil.which(cmd[0]):
        return False
    try:
        subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True
    except OSError:
        return False


def wake_lock() -> None:
    """Не даёт телефону усыпить процессор, пока идёт задача (termux-wake-lock из termux-tools)."""
    global _held
    with _lock:
        if not _held and _run(["termux-wake-lock"]):
            _held = True


def wake_unlock() -> None:
    global _held
    with _lock:
        if _held and _run(["termux-wake-unlock"]):
            _held = False


def media_scan(path: str) -> bool:
    """Сразу показать клип в галерее. Нужны приложение Termux:API и pkg install termux-api;
    без них галерея увидит файл позже сама."""
    return _run(["termux-media-scan", str(path)])
