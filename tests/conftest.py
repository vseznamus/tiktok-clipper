"""Общие заготовки: синтетические видео, временное хранилище, синхронный запуск задач."""
from __future__ import annotations

import shutil
import subprocess
import sys
import threading
from pathlib import Path

import numpy as np
import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from tclip.config import Store  # noqa: E402
from tclip.pipeline import Pipeline  # noqa: E402

HAS_ESPEAK = bool(shutil.which("espeak-ng"))


def ff(*args):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


def speech_wav(text: str, out: Path, lang: str = "ru") -> Path | None:
    if not HAS_ESPEAK:
        return None
    subprocess.run(["espeak-ng", "-v", lang, "-s", "150", "-w", str(out), text], check=True)
    return out


@pytest.fixture(scope="session")
def media(tmp_path_factory):
    """Синтетические исходники (генерируются один раз за прогон)."""
    d = tmp_path_factory.mktemp("media")
    src = d / "lib"
    for sub in ("stream", "movie", "misc"):
        (src / sub).mkdir(parents=True)
    speech = speech_wav("Привет, это проверка субтитров для нарезки. Всё работает отлично.", d / "speech.wav")
    audio_in = ["-i", str(speech)] if speech else ["-f", "lavfi", "-i", "sine=f=330:d=10"]

    # «Стрим»: серый экран (testsrc2 без цвета) + вебкамера: пурпурный прямоугольник 480x360
    # в правом нижнем углу, по нему движется жёлтое «лицо».
    ff("-f", "lavfi", "-i", "testsrc2=s=1920x1080:r=30:d=10",
       "-f", "lavfi", "-i", "color=c=magenta:s=480x360:r=30:d=10",
       "-f", "lavfi", "-i", "color=c=yellow:s=120x120:r=30:d=10", *audio_in,
       "-filter_complex", "[0:v]hue=s=0[scr];[1:v][2:v]overlay=x='60+240*abs(sin(t))':y=120[cam];"
                          "[scr][cam]overlay=1400:680,format=yuv420p[v]",
       "-map", "[v]", "-map", "3:a", "-af", "apad", "-t", "10", "-c:v", "libx264", "-preset", "ultrafast",
       "-c:a", "aac", str(src / "stream" / "stream.mp4"))
    # «Фильм» 16:9 с несимметричной картинкой
    ff("-f", "lavfi", "-i", "testsrc2=s=1280x720:r=25:d=8", *audio_in, "-map", "0:v", "-map", "1:a",
       "-af", "apad", "-t", "8", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac",
       str(src / "movie" / "movie.mp4"))
    # Чёрное видео со звуком — для проверки субтитров на чистом фоне
    ff("-f", "lavfi", "-i", "color=c=black:s=1280x720:r=25:d=6", "-f", "lavfi", "-i", "sine=f=440:d=6",
       "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", str(src / "misc" / "black.mp4"))
    # Без звуковой дорожки
    ff("-f", "lavfi", "-i", "testsrc=s=640x360:r=25:d=4", "-c:v", "libx264", "-preset", "ultrafast",
       "-pix_fmt", "yuv420p", str(src / "misc" / "noaudio.mp4"))
    # Тихий шум и громкий участок 50–60 с — для «Предложить моменты»
    ff("-f", "lavfi", "-i", "color=c=gray:s=160x90:r=5:d=90", "-f", "lavfi", "-i", "anoisesrc=a=0.01:d=90",
       "-af", "volume=enable='between(t,50,60)':volume=30", "-c:v", "libx264", "-preset", "ultrafast",
       "-pix_fmt", "yuv420p", "-c:a", "aac", str(src / "misc" / "loud.mp4"))
    return {"root": src, "speech": bool(speech)}


@pytest.fixture()
def store(tmp_path, media):
    s = Store(tmp_path / "data")
    s.save_settings({"media_root": str(media["root"]), "output_dir": str(tmp_path / "out"),
                     "x264_preset": "ultrafast"})
    return s


class SyncCtx:
    """Контекст задачи без очереди."""

    def __init__(self, params):
        self.params = params
        self.cancel = threading.Event()
        self.job = {"warnings": []}

    def progress(self, frac, stage=None):
        pass

    def stage_progress(self, lo, hi, stage=None):
        return lambda f: None

    def warn(self, msg):
        self.job["warnings"].append(msg)

    def check(self):
        pass


class FakeASR:
    """Подменный бэкенд распознавания: возвращает заданные слова и считает вызовы."""
    name = "fake"

    def __init__(self, words):
        self.words, self.calls = words, 0

    def transcribe(self, wav, language, model, progress=None, cancel=None):
        self.calls += 1
        assert Path(wav).is_file()
        return {"language": "ru", "words": [dict(w) for w in self.words]}


@pytest.fixture()
def pipe(store):
    return Pipeline(store)


def make_clip(pipe, asr=None, **params):
    if asr is not None:
        pipe.asr_factory = lambda settings: (asr, "")
    elif "asr_none" in params:
        reason = params.pop("asr_none")
        pipe.asr_factory = lambda settings: (None, reason)
    ctx = SyncCtx(params)
    res = pipe.job_clip(ctx)
    return pipe.load_clip(res["clip_id"]), ctx.job["warnings"]


def frame(path, t: float) -> np.ndarray:
    """Кадр видео как массив HxWx3 (RGB)."""
    out = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-ss", f"{t}", "-i", str(path),
                          "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(out, np.uint8).reshape(1920, 1080, 3)


def psnr(a: np.ndarray, b: np.ndarray) -> float:
    mse = np.mean((a.astype(np.float64) - b.astype(np.float64)) ** 2)
    return 99.0 if mse == 0 else 10 * np.log10(255.0 ** 2 / mse)
