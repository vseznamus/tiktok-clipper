"""Пути, настройки, sources.json и атомарная запись JSON.

Все данные лежат в DATA_DIR (по умолчанию ./data). Исходные видео — в MEDIA_ROOT,
готовые клипы — в OUTPUT_DIR. Каждую папку можно переопределить переменной окружения.
"""
from __future__ import annotations

import copy
import json
import os
import re
import threading
from pathlib import Path

APP_DIR = Path(__file__).resolve().parent.parent
FONTS_DIR = APP_DIR / "fonts"
STATIC_DIR = Path(__file__).resolve().parent / "static"

SOURCE_TYPES = ["series", "movie", "stream", "blogger"]
TYPE_NAMES = {"series": "Сериал", "movie": "Фильм", "stream": "Стрим", "blogger": "Блогер"}
VIDEO_EXTS = {".mp4", ".mkv", ".mov", ".avi", ".webm", ".m4v", ".ts", ".flv", ".wmv", ".mpg", ".mpeg", ".3gp"}

_lock = threading.RLock()


def is_termux() -> bool:
    return "com.termux" in os.environ.get("PREFIX", "") or Path("/data/data/com.termux").is_dir()


def _termux_shared() -> Path | None:
    p = Path.home() / "storage" / "shared"
    return p if p.is_dir() else None


def data_dir() -> Path:
    return Path(os.environ.get("CLIPPER_DATA", APP_DIR / "data"))


def default_media_root() -> Path:
    if os.environ.get("CLIPPER_MEDIA"):
        return Path(os.environ["CLIPPER_MEDIA"])
    shared = _termux_shared()
    if shared:
        return shared / "TikTokClipper"
    return APP_DIR / "media"


def default_output_dir() -> Path:
    if os.environ.get("CLIPPER_OUTPUT"):
        return Path(os.environ["CLIPPER_OUTPUT"])
    shared = _termux_shared()
    if shared:
        return shared / "Movies" / "TikTokClips"
    return APP_DIR / "clips"


# ---------------------------------------------------------------- JSON на диске

def read_json(path: Path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return copy.deepcopy(default)
    except (json.JSONDecodeError, OSError):
        # Повреждённый файл не должен ронять сервер: откладываем его в сторону.
        try:
            path.rename(path.with_suffix(path.suffix + ".broken"))
        except OSError:
            pass
        return copy.deepcopy(default)


def write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f"{path.name}.{os.getpid()}.{threading.get_ident()}.tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


# ---------------------------------------------------------------- профили и стили

DEFAULT_PROFILE = {
    "layout": "single",        # single | split
    "mirror": True,
    "subtitles": True,
    "sub_style": "classic",
    "language": "auto",
    "single_zoom": 1.0,        # 1.0 = кадр по ширине; больше — крупнее с обрезкой боков
    "cam_rect": None,          # [x, y, w, h] в долях кадра; None = вебкамеры нет
    "screen_rect": None,       # None = весь кадр
    "screen_fit": "crop",      # crop — заполнить зону, fit — целиком с размытыми полями
    "split_screen_h": 0.45,    # доли высоты 1920: экран / зона субтитров / вебкамера
    "split_gap_h": 0.10,
    "split_cam_h": 0.45,
    "sub_y": None,             # центр субтитров по вертикали (доля); None = авто
}

TYPE_PROFILE_DEFAULTS = {
    "series": {"layout": "single"},
    "movie": {"layout": "single"},
    "stream": {"layout": "split"},
    "blogger": {"layout": "split"},
}

SUB_STYLES = {
    "classic": {"name": "Классика: белый, текущее слово жёлтое", "size": 84, "color": "FFFFFF",
                "highlight": "FFD400", "outline": 7, "shadow": 2, "uppercase": True, "words": 3},
    "neon": {"name": "Неон: белый, текущее слово зелёное", "size": 84, "color": "FFFFFF",
             "highlight": "3DFF5A", "outline": 7, "shadow": 2, "uppercase": True, "words": 3},
    "calm": {"name": "Спокойный: без подсветки, по 2 слова", "size": 76, "color": "FFFFFF",
             "highlight": None, "outline": 6, "shadow": 1, "uppercase": False, "words": 2},
}

DEFAULT_SETTINGS = {
    "asr_backend": "auto",     # auto | faster-whisper | whisper.cpp | off
    "asr_model": None,         # None = tiny на телефоне, small на ПК
    "asr_language": "auto",
    "whisper_cpp_bin": "",
    "x264_preset": None,       # None = veryfast на телефоне, faster на ПК
    "crf": 21,
    "moment_len": 30,
    "upload_max_mb": 300,
    "upload_max_minutes": 15,
}

DEFAULT_SOURCES = {
    "start_date": None,
    "interval_days": 3,
    "posts_per_block": 3,
    "type_order": list(SOURCE_TYPES),
    "sources": [],
}


def default_asr_model() -> str:
    return "tiny" if is_termux() else "small"


def default_preset() -> str:
    return "veryfast" if is_termux() else "faster"


def full_profile(source: dict | None) -> dict:
    prof = dict(DEFAULT_PROFILE)
    if source:
        prof.update(TYPE_PROFILE_DEFAULTS.get(source.get("type"), {}))
        prof.update({k: v for k, v in (source.get("profile") or {}).items() if k in DEFAULT_PROFILE})
    return prof


def slugify(text: str) -> str:
    table = str.maketrans({
        "а": "a", "б": "b", "в": "v", "г": "g", "д": "d", "е": "e", "ё": "e", "ж": "zh", "з": "z",
        "и": "i", "й": "y", "к": "k", "л": "l", "м": "m", "н": "n", "о": "o", "п": "p", "р": "r",
        "с": "s", "т": "t", "у": "u", "ф": "f", "х": "h", "ц": "c", "ч": "ch", "ш": "sh", "щ": "sch",
        "ъ": "", "ы": "y", "ь": "", "э": "e", "ю": "yu", "я": "ya"})
    s = text.lower().translate(table)
    s = re.sub(r"[^a-z0-9]+", "_", s).strip("_")
    return s[:40] or "source"


class Store:
    """Доступ к sources.json, settings.json и путям. Один экземпляр на процесс."""

    def __init__(self, data: Path | None = None):
        self.data = Path(data) if data else data_dir()
        self.data.mkdir(parents=True, exist_ok=True)
        for sub in ("clips", "cache", "work", "models"):
            (self.data / sub).mkdir(exist_ok=True)

    # --- пути
    @property
    def sources_path(self) -> Path:
        return self.data / "sources.json"

    @property
    def state_path(self) -> Path:
        return self.data / "state.json"

    @property
    def settings_path(self) -> Path:
        return self.data / "settings.json"

    @property
    def jobs_path(self) -> Path:
        return self.data / "jobs.json"

    def media_root(self) -> Path:
        p = Path(self.settings().get("media_root") or default_media_root())
        return p

    def output_dir(self) -> Path:
        return Path(self.settings().get("output_dir") or default_output_dir())

    def uploads_dir(self) -> Path:
        return self.media_root() / "_uploads"

    # --- настройки
    def settings(self) -> dict:
        with _lock:
            s = dict(DEFAULT_SETTINGS)
            s.update(read_json(self.settings_path, {}))
            return s

    def save_settings(self, patch: dict) -> dict:
        with _lock:
            cur = read_json(self.settings_path, {})
            for k, v in patch.items():
                if k in DEFAULT_SETTINGS or k in ("media_root", "output_dir"):
                    cur[k] = v
            write_json(self.settings_path, cur)
            return self.settings()

    # --- источники
    def sources(self) -> dict:
        with _lock:
            cfg = copy.deepcopy(DEFAULT_SOURCES)
            cfg.update(read_json(self.sources_path, {}))
            cfg["type_order"] = [t for t in cfg.get("type_order") or SOURCE_TYPES if t in SOURCE_TYPES] or list(SOURCE_TYPES)
            cfg["interval_days"] = max(1, int(cfg.get("interval_days") or 3))
            cfg["posts_per_block"] = max(1, int(cfg.get("posts_per_block") or 3))
            return cfg

    def save_sources(self, cfg: dict) -> None:
        with _lock:
            write_json(self.sources_path, cfg)

    def get_source(self, source_id: str) -> dict | None:
        for s in self.sources()["sources"]:
            if s["id"] == source_id:
                return s
        return None

    def upsert_source(self, src: dict) -> dict:
        with _lock:
            cfg = self.sources()
            src = dict(src)
            if src.get("type") not in SOURCE_TYPES:
                raise ValueError("Неизвестный тип источника")
            if not (src.get("title") or "").strip():
                raise ValueError("Укажите название источника")
            ids = {s["id"] for s in cfg["sources"]}
            if not src.get("id"):
                base = slugify(src["title"])
                sid, n = base, 2
                while sid in ids:
                    sid, n = f"{base}_{n}", n + 1
                src["id"] = sid
            folder = (src.get("folder") or src["id"]).strip().strip("/")
            if not folder or ".." in Path(folder).parts or Path(folder).is_absolute():
                raise ValueError("Папка должна быть простым именем внутри общей папки")
            src["folder"] = folder
            src.setdefault("enabled", True)
            src["profile"] = {k: v for k, v in (src.get("profile") or {}).items() if k in DEFAULT_PROFILE}
            for i, s in enumerate(cfg["sources"]):
                if s["id"] == src["id"]:
                    merged = dict(s)
                    merged.update(src)
                    cfg["sources"][i] = merged
                    break
            else:
                cfg["sources"].append(src)
            self.save_sources(cfg)
            (self.media_root() / folder).mkdir(parents=True, exist_ok=True)
            return self.get_source(src["id"])

    def update_profile(self, source_id: str, patch: dict) -> dict:
        with _lock:
            cfg = self.sources()
            for s in cfg["sources"]:
                if s["id"] == source_id:
                    prof = s.setdefault("profile", {})
                    for k, v in patch.items():
                        if k in DEFAULT_PROFILE:
                            prof[k] = v
                    self.save_sources(cfg)
                    return s
            raise KeyError(source_id)

    def delete_source(self, source_id: str) -> None:
        with _lock:
            cfg = self.sources()
            cfg["sources"] = [s for s in cfg["sources"] if s["id"] != source_id]
            self.save_sources(cfg)

    # --- файлы
    def safe_media_path(self, rel: str) -> Path:
        """Путь к файлу внутри MEDIA_ROOT. Выход за пределы папки запрещён."""
        root = self.media_root().resolve()
        p = (root / rel).resolve()
        if p != root and root not in p.parents:
            raise PermissionError("Файл вне общей папки")
        return p

    def list_files(self, folder: str) -> list[dict]:
        d = self.safe_media_path(folder)
        if not d.is_dir():
            return []
        out = []
        for p in d.iterdir():
            if p.is_file() and p.suffix.lower() in VIDEO_EXTS and not p.name.startswith("."):
                st = p.stat()
                out.append({"name": p.name, "path": f"{folder}/{p.name}", "size": st.st_size, "mtime": st.st_mtime})
        out.sort(key=lambda f: natural_key(f["name"]))
        return out


def natural_key(s: str):
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", s)]
