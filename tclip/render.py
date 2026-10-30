"""Сборка вертикального клипа 1080x1920 одним вызовом ffmpeg.

Цепочка: исходник -> раскладка (single | split) -> [hflip, если зеркало] -> субтитры (libass)
-> yuv420p -> H.264 + AAC, +faststart. Зеркало стоит ДО субтитров, поэтому текст не отражается.
"""
from __future__ import annotations

import os
import shutil
from pathlib import Path

from .config import FONTS_DIR
from .media import run_ffmpeg
from .subtitles import H, W

BG_W, BG_H = 216, 384  # фон размываем в маленьком размере — в разы быстрее на телефоне


def _even(x: float) -> int:
    return max(2, int(round(x / 2.0)) * 2)


def _rect_px(rect, sw: int, sh: int) -> tuple[int, int, int, int] | None:
    """[x, y, w, h] в долях кадра -> пиксели (чётные), обрезанные по кадру. None — если рамка негодная."""
    if not rect or len(rect) != 4:
        return None
    try:
        x, y, w, h = (float(v) for v in rect)
    except (TypeError, ValueError):
        return None
    x, y = min(max(x, 0.0), 1.0), min(max(y, 0.0), 1.0)
    w, h = min(w, 1.0 - x), min(h, 1.0 - y)
    if w < 0.03 or h < 0.03:
        return None
    px, py = int(x * sw) // 2 * 2, int(y * sh) // 2 * 2
    pw, ph = min(_even(w * sw), sw - px), min(_even(h * sh), sh - py)
    if pw < 16 or ph < 16:
        return None
    return px, py, pw, ph


def _crop_to_aspect(px, py, pw, ph, tw, th):
    """Центральная обрезка области до пропорции tw:th."""
    a = tw / th
    if pw / ph > a:
        nw = _even(ph * a)
        return px + (pw - nw) // 2 // 2 * 2, py, min(nw, pw), ph
    nh = _even(pw / a)
    return px, py + (ph - nh) // 2 // 2 * 2, pw, min(nh, ph)


def valid_cam_rect(rect, sw: int = 1920, sh: int = 1080) -> bool:
    return _rect_px(rect, sw, sh) is not None


def build_graph(info: dict, prof: dict, layout: str, mirror: bool, ass_name: str | None) -> tuple[str, list[str], str]:
    """Возвращает (filter_complex, предупреждения, фактическая раскладка)."""
    warnings: list[str] = []
    sw, sh = int(info["width"]), int(info["height"])
    if sw < 16 or sh < 16:
        raise ValueError("В файле нет нормальной видеодорожки")
    pre = "scale='trunc(iw*sar/2)*2':'trunc(ih/2)*2',setsar=1"
    if info.get("fps", 0) > 60.5:
        pre += ",fps=60"

    cam = _rect_px(prof.get("cam_rect"), sw, sh) if layout == "split" else None
    if layout == "split" and cam is None:
        warnings.append("Рамка вебкамеры не задана или слишком мала — клип собран в режиме «один кадр». "
                        "Задайте рамку в «Настройки» → источник → «Рамка вебкамеры».")
        layout = "single"

    bg = (f"scale={BG_W}:{BG_H}:force_original_aspect_ratio=increase,crop={BG_W}:{BG_H},"
          f"boxblur=8:2,eq=brightness=-0.08,scale={W}:{H},setsar=1")
    parts = []
    if layout == "single":
        z = min(max(float(prof.get("single_zoom") or 1.0), 1.0), 3.0)
        fit = min(W / sw, H / sh) * z
        fw, fh = _even(sw * fit), _even(sh * fit)
        cw, ch = min(fw, W), min(fh, H)
        parts.append(f"[0:v]{pre},split=2[bgsrc][fgsrc]")
        parts.append(f"[bgsrc]{bg}[bg]")
        parts.append(f"[fgsrc]scale={fw}:{fh},crop={cw}:{ch}[fg]")
        parts.append(f"[bg][fg]overlay={(W - cw) // 2}:{(H - ch) // 2}:shortest=1[comp]")
    else:
        hs_f, hg_f, hc_f = (float(prof.get(k)) for k in ("split_screen_h", "split_gap_h", "split_cam_h"))
        total = hs_f + hg_f + hc_f
        if total > 1.0:
            hs_f, hg_f, hc_f = hs_f / total, hg_f / total, hc_f / total
        HS, HG = _even(H * hs_f), _even(H * hg_f)
        HC = min(_even(H * hc_f), H - HS - HG)
        scr = _rect_px(prof.get("screen_rect"), sw, sh) or (0, 0, sw // 2 * 2, sh // 2 * 2)
        parts.append(f"[0:v]{pre},split=3[bgsrc][scrsrc][camsrc]")
        parts.append(f"[bgsrc]{bg}[bg]")
        if prof.get("screen_fit") == "fit":
            px, py, pw, ph = scr
            k = min(W / pw, HS / ph)
            tw, th = min(_even(pw * k), W), min(_even(ph * k), HS)
            parts.append(f"[scrsrc]crop={pw}:{ph}:{px}:{py},scale={tw}:{th}[scr]")
            sx, sy = (W - tw) // 2, (HS - th) // 2
        else:
            px, py, pw, ph = _crop_to_aspect(*scr, W, HS)
            parts.append(f"[scrsrc]crop={pw}:{ph}:{px}:{py},scale={W}:{HS},setsar=1[scr]")
            sx, sy = 0, 0
        cx, cy, cw, ch = _crop_to_aspect(*cam, W, HC)
        parts.append(f"[camsrc]crop={cw}:{ch}:{cx}:{cy},scale={W}:{HC},setsar=1[cam]")
        parts.append(f"[bg][scr]overlay={sx}:{sy}:shortest=1[t1]")
        parts.append(f"[t1][cam]overlay=0:{HS + HG}:shortest=1[comp]")

    tail = "[comp]"
    chain = []
    if mirror:
        chain.append("hflip")
    if ass_name:
        chain.append(f"ass=filename={ass_name}:fontsdir=fonts")
    chain.append("format=yuv420p")
    parts.append(f"{tail}{','.join(chain)}[v]")
    return ";".join(parts), warnings, layout


def render_clip(src: str | Path, start: float, end: float, out: str | Path, info: dict, prof: dict,
                layout: str, mirror: bool, ass_text: str | None, workdir: str | Path,
                preset: str = "veryfast", crf: int = 21, progress=None, cancel=None) -> dict:
    workdir = Path(workdir)
    workdir.mkdir(parents=True, exist_ok=True)
    ass_name = None
    if ass_text:
        (workdir / "subs.ass").write_text(ass_text, encoding="utf-8")
        fdir = workdir / "fonts"
        fdir.mkdir(exist_ok=True)
        for f in FONTS_DIR.glob("*.ttf"):
            if not (fdir / f.name).exists():
                shutil.copy2(f, fdir / f.name)
        ass_name = "subs.ass"
    graph, warnings, used_layout = build_graph(info, prof, layout, mirror, ass_name)
    dur = end - start
    out = Path(out)
    out.parent.mkdir(parents=True, exist_ok=True)
    part = out.with_name(out.stem + ".part.mp4")
    args = ["-ss", f"{start:.3f}", "-i", str(Path(src).resolve())]
    if not info.get("has_audio"):
        args += ["-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo"]
        warnings.append("В исходнике нет звука — добавлена тишина.")
    args += ["-t", f"{dur:.3f}", "-filter_complex", graph, "-map", "[v]",
             "-map", "0:a:0" if info.get("has_audio") else "1:a:0",
             "-c:v", "libx264", "-preset", preset, "-crf", str(crf), "-pix_fmt", "yuv420p",
             "-profile:v", "high", "-c:a", "aac", "-b:a", "160k", "-ar", "44100", "-ac", "2",
             "-movflags", "+faststart", "-f", "mp4", str(part.resolve())]
    try:
        run_ffmpeg(args, duration=dur, progress=progress, cancel=cancel, cwd=workdir)
        os.replace(part, out)
    finally:
        if part.exists():
            part.unlink()
    return {"layout": used_layout, "warnings": warnings, "graph": graph}
