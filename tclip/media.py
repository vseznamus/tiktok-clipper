"""Обёртки над ffmpeg/ffprobe: проверка установки, probe, запуск с прогрессом, кадры, громкость."""
from __future__ import annotations

import json
import math
import re
import shutil
import subprocess
import threading
from pathlib import Path


class FFmpegError(RuntimeError):
    pass


class Cancelled(RuntimeError):
    pass


def ffmpeg_bin() -> str:
    return shutil.which("ffmpeg") or "ffmpeg"


def ffprobe_bin() -> str:
    return shutil.which("ffprobe") or "ffprobe"


def check_ffmpeg() -> dict:
    """Что есть в установленном ffmpeg. Сообщения — для экрана «Настройки» и install.sh."""
    res = {"ffmpeg": False, "ffprobe": bool(shutil.which("ffprobe")), "libass": False,
           "libx264": False, "aac": False, "version": "", "problems": []}
    if not shutil.which("ffmpeg"):
        res["problems"].append("ffmpeg не найден. Termux: pkg install ffmpeg; Ubuntu: sudo apt install ffmpeg")
        return res
    res["ffmpeg"] = True
    try:
        out = subprocess.run([ffmpeg_bin(), "-hide_banner", "-version"], capture_output=True, text=True, timeout=20).stdout
        res["version"] = out.splitlines()[0] if out else ""
        filters = subprocess.run([ffmpeg_bin(), "-hide_banner", "-filters"], capture_output=True, text=True, timeout=20).stdout
        encoders = subprocess.run([ffmpeg_bin(), "-hide_banner", "-encoders"], capture_output=True, text=True, timeout=20).stdout
    except (OSError, subprocess.TimeoutExpired) as e:
        res["problems"].append(f"ffmpeg не запускается: {e}")
        return res
    res["libass"] = bool(re.search(r"^\s*\S+\s+ass\s", filters, re.M))
    res["libx264"] = bool(re.search(r"^\s*\S+\s+libx264\s", encoders, re.M))
    res["aac"] = bool(re.search(r"^\s*\S+\s+aac\s", encoders, re.M))
    if not res["ffprobe"]:
        res["problems"].append("ffprobe не найден (обычно ставится вместе с ffmpeg)")
    if not res["libass"]:
        res["problems"].append("ffmpeg собран без libass — субтитры вшить нельзя")
    if not res["libx264"]:
        res["problems"].append("ffmpeg собран без libx264 — нет кодека H.264")
    if not res["aac"]:
        res["problems"].append("в ffmpeg нет кодека AAC")
    return res


def probe(path: str | Path) -> dict:
    try:
        cp = subprocess.run(
            [ffprobe_bin(), "-v", "error", "-print_format", "json", "-show_format", "-show_streams", str(path)],
            capture_output=True, text=True, timeout=60)
    except subprocess.TimeoutExpired as e:
        raise FFmpegError("ffprobe не ответил за 60 секунд") from e
    if cp.returncode != 0:
        raise FFmpegError(f"Не удалось прочитать видео: {cp.stderr.strip()[-300:]}")
    info = json.loads(cp.stdout or "{}")
    streams = info.get("streams", [])
    v = next((s for s in streams if s.get("codec_type") == "video" and not (s.get("disposition") or {}).get("attached_pic")), None)
    a = next((s for s in streams if s.get("codec_type") == "audio"), None)
    fmt = info.get("format", {})
    dur = _f(fmt.get("duration")) or (_f(v.get("duration")) if v else 0) or 0.0
    res = {"duration": dur, "has_video": v is not None, "has_audio": a is not None,
           "vcodec": v.get("codec_name") if v else None, "acodec": a.get("codec_name") if a else None,
           "width": 0, "height": 0, "fps": 0.0, "pix_fmt": v.get("pix_fmt") if v else None,
           "format": fmt.get("format_name", "")}
    if v:
        w, h = int(v.get("width") or 0), int(v.get("height") or 0)
        sar = v.get("sample_aspect_ratio") or "1:1"
        try:
            sn, sd = (int(x) for x in sar.split(":"))
            if sn > 0 and sd > 0 and sn != sd:
                w = int(round(w * sn / sd))
        except ValueError:
            pass
        rot = 0
        for sd_ in v.get("side_data_list") or []:
            if "rotation" in sd_:
                rot = int(float(sd_["rotation"]))
        rot = rot or int(float((v.get("tags") or {}).get("rotate", 0) or 0))
        if abs(rot) % 180 == 90:
            w, h = h, w
        res["width"], res["height"] = w, h
        res["fps"] = _rate(v.get("avg_frame_rate")) or _rate(v.get("r_frame_rate"))
    return res


def _f(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def _rate(r):
    try:
        n, d = r.split("/")
        return float(n) / float(d) if float(d) else 0.0
    except (AttributeError, ValueError, ZeroDivisionError):
        return 0.0


def run_ffmpeg(args: list[str], duration: float | None = None, progress=None,
               cancel: threading.Event | None = None, cwd: str | Path | None = None) -> None:
    """Запускает ffmpeg, сообщает прогресс 0..1 через progress(frac), прерывается по cancel."""
    cmd = [ffmpeg_bin(), "-hide_banner", "-nostdin", "-y", "-progress", "pipe:1", "-nostats", *args]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                            cwd=str(cwd) if cwd else None, errors="replace")
    err_tail: list[str] = []

    def _drain():
        for line in proc.stderr:
            err_tail.append(line)
            if len(err_tail) > 60:
                del err_tail[:20]

    t = threading.Thread(target=_drain, daemon=True)
    t.start()
    try:
        for line in proc.stdout:
            if cancel is not None and cancel.is_set():
                proc.terminate()
                break
            if line.startswith("out_time_us=") or line.startswith("out_time_ms="):
                try:
                    us = int(line.split("=", 1)[1])
                except ValueError:
                    continue
                if progress and duration:
                    progress(max(0.0, min(1.0, us / 1e6 / duration)))
        proc.wait()
    finally:
        if proc.poll() is None:
            proc.kill()
        t.join(timeout=5)
    if cancel is not None and cancel.is_set():
        raise Cancelled("Задача отменена")
    if proc.returncode != 0:
        tail = "".join(err_tail[-12:]).strip()
        raise FFmpegError(f"ffmpeg завершился с ошибкой ({proc.returncode}): {tail[-800:]}")


def extract_frame(src: str | Path, t: float, out: str | Path, width: int = 720) -> Path:
    out = Path(out)
    out.parent.mkdir(parents=True, exist_ok=True)
    cmd = [ffmpeg_bin(), "-hide_banner", "-nostdin", "-y", "-ss", f"{max(0.0, t):.3f}", "-i", str(src),
           "-frames:v", "1", "-vf", f"scale={width}:-2", "-q:v", "4", str(out)]
    cp = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    if cp.returncode != 0 or not out.exists():
        raise FFmpegError("Не удалось получить кадр: " + cp.stderr.strip()[-300:])
    return out


def extract_audio_wav(src: str | Path, start: float, dur: float, out: str | Path, cancel=None) -> Path:
    run_ffmpeg(["-ss", f"{start:.3f}", "-i", str(src), "-t", f"{dur:.3f}", "-vn", "-ac", "1", "-ar", "16000",
                "-c:a", "pcm_s16le", str(out)], duration=dur, cancel=cancel)
    return Path(out)


def loudness_per_second(src: str | Path, duration: float, progress=None, cancel=None) -> list[float]:
    """RMS-громкость (дБ) по секундам всего файла. Считает сам ffmpeg — быстро даже на телефоне."""
    cmd = [ffmpeg_bin(), "-hide_banner", "-nostdin", "-i", str(src), "-vn", "-sn", "-dn",
           "-af", "aresample=8000,asetnsamples=n=8000:p=0,astats=metadata=1:reset=1,"
                  "ametadata=mode=print:key=lavfi.astats.Overall.RMS_level:file=-",
           "-f", "null", "-"]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, errors="replace")
    levels: list[float] = []
    try:
        for line in proc.stdout:
            if cancel is not None and cancel.is_set():
                proc.terminate()
                raise Cancelled("Задача отменена")
            if "RMS_level=" in line:
                val = line.split("=", 1)[1].strip()
                try:
                    db = float(val)
                except ValueError:
                    db = -90.0
                if math.isinf(db) or math.isnan(db):
                    db = -90.0
                levels.append(max(-90.0, db))
                if progress and duration and len(levels) % 30 == 0:
                    progress(min(1.0, len(levels) / duration))
        proc.wait()
    finally:
        if proc.poll() is None:
            proc.kill()
    return levels
