"""Поиск интересных моментов: самые громкие окна + плотность речи (если есть транскрипт/.srt)."""
from __future__ import annotations

import hashlib
from pathlib import Path

from .config import read_json, write_json
from .media import loudness_per_second
from .subtitles import parse_srt


def file_key(path: Path) -> str:
    st = path.stat()
    return hashlib.sha1(f"{path.resolve()}|{st.st_size}|{int(st.st_mtime)}".encode()).hexdigest()[:20]


def cached_loudness(path: Path, duration: float, cache_dir: Path, progress=None, cancel=None) -> list[float]:
    cp = cache_dir / f"{file_key(path)}.loud.json"
    data = read_json(cp, None)
    if data:
        return data
    levels = loudness_per_second(path, duration, progress=progress, cancel=cancel)
    write_json(cp, levels)
    return levels


def sidecar_srt(video: Path) -> Path | None:
    """Субтитры рядом с видео: film.srt, film.ru.srt, film.rus.srt и т.п."""
    cands = [video.with_suffix(".srt")] + sorted(video.parent.glob(video.stem + ".*.srt"))
    return next((c for c in cands if c.is_file()), None)


def load_cues(video: Path, cache_dir: Path) -> list[dict]:
    srt = sidecar_srt(video)
    if srt:
        try:
            return parse_srt(srt.read_text(encoding="utf-8", errors="replace"))
        except OSError:
            pass
    words = read_json(cache_dir / f"{file_key(video)}.words.json", None)
    if words:
        return [{"s": w["s"], "e": w["e"], "text": w["w"]} for w in words]
    return []


def speech_density(cues: list[dict], n: int) -> list[float]:
    """Сколько символов речи приходится на каждую секунду (нормировано к 0..1)."""
    dens = [0.0] * n
    for c in cues:
        s, e = int(c["s"]), min(n - 1, int(c["e"]))
        if e < s or s >= n:
            continue
        per = len(c["text"]) / (e - s + 1)
        for i in range(s, e + 1):
            dens[i] += per
    return [min(1.0, d / 15.0) for d in dens]  # ~15 символов/с — плотная речь


def _percentile(vals: list[float], p: float) -> float:
    s = sorted(vals)
    if not s:
        return 0.0
    k = (len(s) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def find_moments(levels: list[float], length: int, cues: list[dict] | None = None, count: int = 8) -> list[dict]:
    n = len(levels)
    length = max(5, int(length))
    if n == 0:
        return []
    if n <= length:
        return [{"start": 0.0, "end": float(n), "score": 1.0, "reasons": ["весь файл короче окна"]}]
    lo, hi = _percentile(levels, 0.10), _percentile(levels, 0.98)
    rng = max(hi - lo, 1e-6)
    loud = [min(1.0, max(0.0, (x - lo) / rng)) for x in levels]
    smooth = [sum(loud[max(0, i - 1):i + 2]) / len(loud[max(0, i - 1):i + 2]) for i in range(n)]
    speech = speech_density(cues, n) if cues else None

    def prefix(a):
        p = [0.0]
        for x in a:
            p.append(p[-1] + x)
        return p

    pl = prefix(loud)
    ps = prefix(speech) if speech else None
    cands = []
    for s in range(0, n - length + 1):
        e = s + length
        mean = (pl[e] - pl[s]) / length
        peak = max(smooth[s:e])
        if speech:
            sp = (ps[e] - ps[s]) / length
            score = 0.5 * mean + 0.2 * peak + 0.3 * sp
        else:
            sp = None
            score = 0.7 * mean + 0.3 * peak
        cands.append((score, s, mean, peak, sp))
    cands.sort(key=lambda c: -c[0])
    picked = []
    min_gap = length * 0.75
    for score, s, mean, peak, sp in cands:
        if all(abs(s - p["start"]) >= min_gap for p in picked):
            reasons = []
            if mean > 0.55:
                reasons.append("громко")
            if peak > 0.85:
                reasons.append("всплеск звука")
            if sp is not None and sp > 0.45:
                reasons.append("много речи")
            start = float(s)
            if cues:  # начинаем с начала реплики, если она рядом
                near = [c["s"] for c in cues if s - 3 <= c["s"] <= s + 1]
                if near:
                    start = max(0.0, min(near, key=lambda t: abs(t - s)) - 0.2)
            picked.append({"start": round(start, 2), "end": round(start + length, 2), "score": round(score, 3),
                           "reasons": reasons or ["выше среднего"]})
            if len(picked) >= count:
                break
    return picked
