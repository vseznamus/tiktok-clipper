"""Распознавание речи через сменные бэкенды.

  * faster-whisper — ПК, мини-ПК, VPS, Docker (pip install faster-whisper);
  * whisper.cpp    — Termux на Android (собирается из исходников install.sh --whisper);
  * off            — без распознавания (клип будет без субтитров, с предупреждением).

Каждый бэкенд возвращает {"language": str|None, "words": [{"w","s","e"}]} — время в секундах
от начала переданного WAV-файла.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import threading
from pathlib import Path

from .config import APP_DIR

MODELS = ["tiny", "base", "small", "medium"]

# Известные «галлюцинации» Whisper на тишине и музыке (особенно в русском).
_HALLUCINATIONS = [
    r"субтитры (сделал|создавал|подготовил|делал)", r"редактор субтитров", r"корректор",
    r"продолжение следует", r"спасибо за просмотр", r"подписывайтесь на (канал|наш канал)",
    r"dimatorzok", r"amara\.org", r"thanks for watching", r"subtitles by",
]
_HALL_RE = re.compile("|".join(_HALLUCINATIONS), re.I)


class ASRError(RuntimeError):
    pass


def clean_words(words: list[dict]) -> list[dict]:
    """Убирает пустые слова и типичные галлюцинации, сортирует по времени."""
    text = " ".join(w["w"] for w in words)
    if words and _HALL_RE.search(text) and len(words) <= 12:
        return []
    out = []
    for w in words:
        t = re.sub(r"\s+", " ", str(w.get("w", ""))).strip()
        if not t or t in {"-", "–", "—", "♪", "..."}:
            continue
        s, e = float(w["s"]), float(w["e"])
        if e < s:
            e = s
        out.append({"w": t, "s": round(s, 3), "e": round(e, 3)})
    out.sort(key=lambda w: w["s"])
    return out


# ---------------------------------------------------------------- faster-whisper

class FasterWhisperBackend:
    name = "faster-whisper"
    _cache: dict = {}
    _lock = threading.Lock()

    @staticmethod
    def available() -> tuple[bool, str]:
        try:
            import faster_whisper  # noqa: F401
            return True, ""
        except Exception as e:  # ImportError и ошибки нативных библиотек
            return False, f"faster-whisper не установлен ({type(e).__name__})"

    def __init__(self, models_dir: Path):
        self.models_dir = Path(models_dir) / "faster-whisper"

    def transcribe(self, wav: Path, language: str | None, model: str, progress=None, cancel=None) -> dict:
        from faster_whisper import WhisperModel
        key = model
        with self._lock:
            m = self._cache.get(key)
            if m is None:
                try:
                    m = WhisperModel(model, device="cpu", compute_type="int8",
                                     download_root=str(self.models_dir),
                                     cpu_threads=max(1, (os.cpu_count() or 2) - 1))
                except Exception as e:
                    raise ASRError(
                        f"Не удалось загрузить модель «{model}» для faster-whisper. При первом запуске она "
                        f"скачивается из интернета (huggingface.co). Ошибка: {e}") from e
                self._cache.clear()
                self._cache[key] = m
        segments, info = m.transcribe(str(wav), language=language, word_timestamps=True,
                                      vad_filter=True, beam_size=1, condition_on_previous_text=False)
        words = []
        total = max(info.duration or 0.0, 0.01)
        for seg in segments:
            if cancel is not None and cancel.is_set():
                break
            for w in seg.words or []:
                words.append({"w": w.word, "s": w.start, "e": w.end})
            if progress:
                progress(min(1.0, seg.end / total))
        return {"language": info.language, "words": clean_words(words)}


# ---------------------------------------------------------------- whisper.cpp

def find_whisper_cpp(custom: str = "") -> str | None:
    cands = [custom] if custom else []
    cands += [str(APP_DIR / "whisper.cpp" / "build" / "bin" / "whisper-cli"),
              str(APP_DIR / "whisper.cpp" / "build" / "bin" / "main"),
              shutil.which("whisper-cli") or "", shutil.which("whisper-cpp") or ""]
    for c in cands:
        if c and Path(c).is_file() and os.access(c, os.X_OK):
            return c
    return None


def whisper_cpp_model_path(models_dir: Path, model: str) -> Path | None:
    for d in (Path(models_dir), APP_DIR / "whisper.cpp" / "models", APP_DIR / "models"):
        for name in (f"ggml-{model}.bin", f"ggml-{model}-q5_1.bin", f"ggml-{model}-q8_0.bin"):
            if (d / name).is_file():
                return d / name
    return None


def parse_whisper_cpp_json(data: dict) -> dict:
    """Разбор JSON whisper.cpp, запущенного с -ml 1 -sow (каждый сегмент ≈ одно слово).
    Сегмент без ведущего пробела — продолжение предыдущего слова (часть токена)."""
    words: list[dict] = []
    for seg in data.get("transcription") or []:
        text = seg.get("text", "")
        off = seg.get("offsets") or {}
        s, e = off.get("from", 0) / 1000.0, off.get("to", 0) / 1000.0
        if not text.strip() or re.fullmatch(r"\s*\[[^\]]*\]\s*|\s*\([^)]*\)\s*", text):
            continue  # [музыка], (смех), [BLANK_AUDIO]
        if words and not text[:1].isspace():
            words[-1]["w"] += text.strip()
            words[-1]["e"] = e
        else:
            words.append({"w": text.strip(), "s": s, "e": e})
    lang = (data.get("result") or {}).get("language")
    return {"language": lang, "words": clean_words(words)}


class WhisperCppBackend:
    name = "whisper.cpp"

    def __init__(self, models_dir: Path, binary: str = ""):
        self.models_dir = Path(models_dir) / "whisper.cpp"
        self.binary = find_whisper_cpp(binary)

    def available(self) -> tuple[bool, str]:
        if not self.binary:
            return False, "whisper.cpp не собран (запустите: bash install.sh --whisper)"
        return True, ""

    def transcribe(self, wav: Path, language: str | None, model: str, progress=None, cancel=None) -> dict:
        mp = whisper_cpp_model_path(self.models_dir, model)
        if mp is None:
            raise ASRError(f"Нет модели ggml-{model}.bin. Скачайте её: bash install.sh --model {model}")
        out_base = Path(wav).with_suffix("")
        threads = str(max(1, min(8, (os.cpu_count() or 4))))
        cmd = [self.binary, "-m", str(mp), "-f", str(wav), "-l", language or "auto", "-ml", "1", "-sow",
               "-oj", "-of", str(out_base), "-t", threads, "-pp", "-np"]
        proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True, errors="replace")
        tail = []
        for line in proc.stderr:
            if cancel is not None and cancel.is_set():
                proc.terminate()
                break
            m = re.search(r"progress\s*=\s*(\d+)%", line)
            if m and progress:
                progress(int(m.group(1)) / 100.0)
            tail = (tail + [line])[-20:]
        proc.wait()
        if proc.returncode != 0:
            raise ASRError("whisper.cpp завершился с ошибкой: " + "".join(tail)[-500:])
        jpath = out_base.with_suffix(".json")
        raw = jpath.read_bytes().decode("utf-8", errors="replace")
        return parse_whisper_cpp_json(json.loads(raw))


# ---------------------------------------------------------------- выбор бэкенда

def backends_status(models_dir: Path, settings: dict) -> dict:
    fw_ok, fw_msg = FasterWhisperBackend.available()
    wc = WhisperCppBackend(models_dir, settings.get("whisper_cpp_bin", ""))
    wc_ok, wc_msg = wc.available()
    wc_models = [m for m in MODELS if whisper_cpp_model_path(wc.models_dir, m)]
    return {"faster-whisper": {"ok": fw_ok, "message": fw_msg},
            "whisper.cpp": {"ok": wc_ok, "message": wc_msg, "models": wc_models}}


def get_backend(models_dir: Path, settings: dict):
    """Бэкенд согласно настройкам. None — распознавание выключено или недоступно (с причиной)."""
    choice = settings.get("asr_backend") or "auto"
    if choice == "off":
        return None, "Распознавание речи выключено в настройках."
    if choice in ("auto", "faster-whisper"):
        ok, msg = FasterWhisperBackend.available()
        if ok:
            return FasterWhisperBackend(models_dir), ""
        if choice == "faster-whisper":
            return None, msg
    wc = WhisperCppBackend(models_dir, settings.get("whisper_cpp_bin", ""))
    ok, msg = wc.available()
    if ok:
        return wc, ""
    return None, ("Распознавание речи не установлено: нет ни faster-whisper, ни whisper.cpp. "
                  "Клип сделан без субтитров. " + msg)
