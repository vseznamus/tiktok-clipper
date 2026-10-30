"""Субтитры: слова с таймкодами -> строки по 2–3 слова -> ASS (с подсветкой слова) и SRT.

Время слов — относительно начала клипа. Слово: {"w": str, "s": float, "e": float}.
Строка: {"s": float, "e": float, "text": str, "words": [слова]}.
"""
from __future__ import annotations

import json
import re
from functools import lru_cache

from .config import FONTS_DIR, SUB_STYLES

W, H = 1080, 1920
# Безопасная зона TikTok для текста (px в кадре 1080x1920): сверху — вкладки,
# справа — колонка иконок (лайк/комменты/репост), снизу — ник, описание и музыка.
SAFE_LEFT, SAFE_TOP, SAFE_RIGHT, SAFE_BOTTOM = 60, 220, 900, 1440
FONT_NAME = "Montserrat ExtraBold"


@lru_cache(maxsize=1)
def _metrics() -> dict:
    with open(FONTS_DIR / "metrics.json", encoding="utf-8") as f:
        return json.load(f)


def text_width(text: str, size: float, outline: float = 0) -> float:
    m = _metrics()
    adv, units, default = m["advance"], m["units"], m["default"]
    return sum(adv.get(ch, default) for ch in text) * size / units + 2 * outline


# ---------------------------------------------------------------- группировка

_END_PUNCT = re.compile(r"[.!?…]$")
_PAUSE_PUNCT = re.compile(r"[,;:—–]$")


def group_words(words: list[dict], max_words: int = 3, max_chars: int = 22, max_gap: float = 0.7) -> list[dict]:
    lines: list[dict] = []
    cur: list[dict] = []

    def flush():
        if cur:
            lines.append({"s": cur[0]["s"], "e": cur[-1]["e"], "text": " ".join(w["w"] for w in cur),
                          "words": [dict(w) for w in cur]})
            cur.clear()

    for w in words:
        if not w.get("w", "").strip():
            continue
        if cur:
            gap = w["s"] - cur[-1]["e"]
            chars = len(" ".join(x["w"] for x in cur)) + 1 + len(w["w"])
            if (len(cur) >= max_words or chars > max_chars or gap > max_gap
                    or _END_PUNCT.search(cur[-1]["w"])
                    or (_PAUSE_PUNCT.search(cur[-1]["w"]) and len(cur) >= 2)):
                flush()
        cur.append({"w": w["w"].strip(), "s": float(w["s"]), "e": float(w["e"])})
    flush()
    return finalize_lines(lines)


def finalize_lines(lines: list[dict], min_dur: float = 0.35, join_gap: float = 0.4) -> list[dict]:
    """Убирает мигание между близкими строками и слишком короткие показы."""
    lines = sorted((dict(l) for l in lines if l.get("text", "").strip()), key=lambda l: l["s"])
    for i, l in enumerate(lines):
        nxt = lines[i + 1]["s"] if i + 1 < len(lines) else None
        if nxt is not None and 0 <= nxt - l["e"] < join_gap:
            l["e"] = nxt
        if l["e"] - l["s"] < min_dur:
            l["e"] = l["s"] + min_dur if nxt is None else min(l["s"] + min_dur, max(nxt, l["e"]))
        if nxt is not None and l["e"] > nxt:
            l["e"] = nxt
        l["s"], l["e"] = round(l["s"], 3), round(l["e"], 3)
    return lines


def _distribute(text: str, s: float, e: float) -> list[dict]:
    toks = text.split()
    if not toks:
        return []
    total = sum(len(t) + 1 for t in toks)
    out, t = [], s
    for tok in toks:
        d = (e - s) * (len(tok) + 1) / total
        out.append({"w": tok, "s": round(t, 3), "e": round(t + d, 3)})
        t += d
    return out


def _norm(t: str) -> str:
    return re.sub(r"\W+", " ", t.lower()).strip()


def apply_edits(edited: list[dict], old_lines: list[dict], clip_dur: float) -> list[dict]:
    """Строки после правки в интерфейсе. Если текст строки не менялся — сохраняем точные
    таймкоды слов (масштабируя под новые границы), иначе раскладываем слова по длине."""
    out = []
    for ed in edited:
        text = re.sub(r"\s+", " ", str(ed.get("text", ""))).strip()
        if not text:
            continue
        s = max(0.0, min(float(ed["s"]), clip_dur))
        e = max(s + 0.1, min(float(ed["e"]), clip_dur))
        match = next((o for o in old_lines if _norm(o["text"]) == _norm(text) and o.get("words")
                      and o["s"] < e and o["e"] > s), None)
        if match and len(match["words"]) == len(text.split()):
            os_, oe = match["s"], max(match["e"], match["s"] + 0.01)
            k = (e - s) / (oe - os_)
            words = [{"w": tok, "s": round(s + (w["s"] - os_) * k, 3), "e": round(s + (w["e"] - os_) * k, 3)}
                     for tok, w in zip(text.split(), match["words"])]
        else:
            words = _distribute(text, s, e)
        out.append({"s": round(s, 3), "e": round(e, 3), "text": text, "words": words})
    return finalize_lines(out, min_dur=0.1, join_gap=0.0)


def words_from_cues(cues: list[dict], start: float, end: float) -> list[dict]:
    """Слова из готовых субтитров (.srt рядом с видео) в пределах отрезка клипа."""
    words = []
    for c in cues:
        if c["e"] <= start or c["s"] >= end:
            continue
        s, e = max(c["s"], start) - start, min(c["e"], end) - start
        words.extend(_distribute(c["text"], s, e))
    return words


# ---------------------------------------------------------------- SRT

def _ts_srt(t: float) -> str:
    ms = int(round(max(0.0, t) * 1000))
    h, ms = divmod(ms, 3600000)
    m, ms = divmod(ms, 60000)
    s, ms = divmod(ms, 1000)
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def to_srt(lines: list[dict]) -> str:
    out = []
    for i, l in enumerate(lines, 1):
        out.append(f"{i}\n{_ts_srt(l['s'])} --> {_ts_srt(l['e'])}\n{l['text']}\n")
    return "\n".join(out)


_SRT_TIME = re.compile(r"(\d+):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d+):(\d{2}):(\d{2})[,.](\d{1,3})")


def parse_srt(text: str) -> list[dict]:
    text = text.lstrip("﻿").replace("\r\n", "\n").replace("\r", "\n")
    cues = []
    for block in re.split(r"\n\s*\n", text):
        m = _SRT_TIME.search(block)
        if not m:
            continue
        g = m.groups()
        s = int(g[0]) * 3600 + int(g[1]) * 60 + int(g[2]) + int(g[3].ljust(3, "0")) / 1000
        e = int(g[4]) * 3600 + int(g[5]) * 60 + int(g[6]) + int(g[7].ljust(3, "0")) / 1000
        body = block[m.end():].strip()
        body = re.sub(r"<[^>]+>", "", body)
        body = re.sub(r"\{[^}]*\}", "", body)
        body = re.sub(r"\s+", " ", body).strip()
        if body:
            cues.append({"s": s, "e": e, "text": body})
    return cues


# ---------------------------------------------------------------- ASS

def _ass_color(rrggbb: str, alpha: int = 0) -> str:
    rrggbb = rrggbb.lstrip("#")
    r, g, b = rrggbb[0:2], rrggbb[2:4], rrggbb[4:6]
    return f"&H{alpha:02X}{b}{g}{r}".upper()


def _ts_ass(t: float) -> str:
    cs = int(round(max(0.0, t) * 100))
    h, cs = divmod(cs, 360000)
    m, cs = divmod(cs, 6000)
    s, cs = divmod(cs, 100)
    return f"{h:d}:{m:02d}:{s:02d}.{cs:02d}"


def _ass_text(t: str) -> str:
    return t.replace("\\", "/").replace("{", "(").replace("}", ")").replace("\n", " ")


def layout_line(words: list[str], style: dict) -> tuple[list[list[str]], float]:
    """Разбивка строки на 1–2 ряда и размер шрифта, чтобы влезть в ширину безопасной зоны."""
    max_w = SAFE_RIGHT - SAFE_LEFT
    size = float(style["size"])
    ol = style["outline"]
    one = " ".join(words)
    w1 = text_width(one, size, ol)
    if w1 <= max_w:
        return [words], size
    if len(words) >= 2 and max_w / w1 < 0.75:
        best = None
        for k in range(1, len(words)):
            rows = [words[:k], words[k:]]
            widest = max(text_width(" ".join(r), size, ol) for r in rows)
            if best is None or widest < best[1]:
                best = (rows, widest)
        rows, widest = best
        return rows, size * min(1.0, max_w / widest) * 0.98
    return [words], size * max_w / w1 * 0.98


def sub_center_y(profile: dict, layout: str) -> float:
    if profile.get("sub_y") is not None:
        return float(profile["sub_y"]) * H
    if layout == "split":
        hs, hg = profile["split_screen_h"], profile["split_gap_h"]
        if hg >= 0.05:
            return (hs + hg / 2) * H
        return (hs + 0.06) * H
    return 0.68 * H


def to_ass(lines: list[dict], style_key: str, center_y: float) -> str:
    style = SUB_STYLES.get(style_key) or SUB_STYLES["classic"]
    upper = style["uppercase"]
    base = _ass_color(style["color"])
    hi = _ass_color(style["highlight"]) if style.get("highlight") else None
    head = [
        "[Script Info]", "ScriptType: v4.00+", f"PlayResX: {W}", f"PlayResY: {H}",
        "WrapStyle: 2", "ScaledBorderAndShadow: yes", "YCbCr Matrix: TV.709", "",
        "[V4+ Styles]",
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, "
        "Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, "
        "Alignment, MarginL, MarginR, MarginV, Encoding",
        f"Style: Main,{FONT_NAME},{style['size']},{base},{base},&H00000000,&H96000000,-1,0,0,0,100,100,0,0,1,"
        f"{style['outline']},{style['shadow']},5,{SAFE_LEFT},{W - SAFE_RIGHT},0,1",
        "", "[Events]",
        "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
    ]
    cx = (SAFE_LEFT + SAFE_RIGHT) // 2
    events = []
    for l in lines:
        words = [w["w"] for w in l.get("words") or []] or l["text"].split()
        if " ".join(words) != l["text"]:
            words = l["text"].split()
        disp = [w.upper() if upper else w for w in words]
        rows, size = layout_line(disp, style)
        height = len(rows) * size * 1.22 + 2 * style["outline"]
        cy = min(max(center_y, SAFE_TOP + height / 2), SAFE_BOTTOM - height / 2)
        pre = f"{{\\pos({cx},{int(round(cy))})\\fs{size:.1f}}}"
        wtimes = l.get("words") if l.get("words") and len(l["words"]) == len(words) else None
        if not hi or not wtimes:
            text = "\\N".join(" ".join(_ass_text(w) for w in r) for r in rows)
            events.append(f"Dialogue: 0,{_ts_ass(l['s'])},{_ts_ass(l['e'])},Main,,0,0,0,,{pre}{text}")
            continue
        n = len(words)
        for k in range(n):
            t0 = l["s"] if k == 0 else max(l["s"], wtimes[k]["s"])
            t1 = l["e"] if k == n - 1 else max(t0, wtimes[k + 1]["s"])
            if t1 - t0 < 0.01:
                continue
            parts, idx = [], 0
            for r in rows:
                rp = []
                for w in r:
                    tw = _ass_text(w)
                    rp.append(f"{{\\c{hi}}}{tw}{{\\c{base}}}" if idx == k else tw)
                    idx += 1
                parts.append(" ".join(rp))
            text = "\\N".join(parts)
            events.append(f"Dialogue: 0,{_ts_ass(t0)},{_ts_ass(t1)},Main,,0,0,0,,{pre}{text}")
    return "\n".join(head + events) + "\n"
