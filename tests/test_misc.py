"""Очередь, поиск моментов, субтитры, разбор whisper.cpp, RSS, установщик."""
from __future__ import annotations

import os
import stat
import subprocess
import threading
import time
from pathlib import Path

import pytest

from conftest import HAS_ESPEAK, SyncCtx
from tclip import asr, feeds, subtitles
from tclip.jobs import JobQueue
from tclip.moments import find_moments

ROOT = Path(__file__).resolve().parent.parent


# ---------------------------------------------------------------- очередь
def test_queue_persists_and_resumes_after_restart(tmp_path):
    path = tmp_path / "jobs.json"
    started = threading.Event()
    release = threading.Event()

    def slow(ctx):
        started.set()
        ctx.progress(0.5, "Работаю")
        release.wait(5)
        return {"ok": 1}

    q1 = JobQueue(path, {"t": slow})
    j = q1.submit("t", {"a": 1}, "Задача")
    q1.start()
    assert started.wait(5)
    time.sleep(0.2)
    q1.stop()  # «сервер упал» посреди задачи: на диске она в статусе running
    q1._save = lambda force=False: None  # упавший процесс больше ничего не пишет
    import json
    assert json.loads(path.read_text())[0]["status"] == "running"
    release.set()

    done = []
    q2 = JobQueue(path, {"t": lambda ctx: done.append(ctx.params) or {"ok": 2}})
    assert q2.get(j["id"])["status"] == "queued"
    q2.start()
    assert q2.wait_idle(10)
    jj = q2.get(j["id"])
    assert jj["status"] == "done" and jj["result"] == {"ok": 2} and done == [{"a": 1}]
    assert any("запущена заново" in w for w in jj["warnings"])


def test_queue_error_and_cancel(tmp_path):
    gate = threading.Event()

    def boom(ctx):
        raise ValueError("сломалось")

    def waits(ctx):
        while not ctx.cancel.is_set():
            time.sleep(0.05)
        ctx.check()

    q = JobQueue(tmp_path / "j.json", {"boom": boom, "wait": waits})
    a = q.submit("boom", {}, "a")
    b = q.submit("wait", {}, "b")
    c = q.submit("wait", {}, "c")
    q.cancel(c["id"])
    q.start()
    for _ in range(100):
        if q.get(b["id"])["status"] == "running":
            break
        time.sleep(0.05)
    q.cancel(b["id"])
    assert q.wait_idle(10)
    assert q.get(a["id"])["status"] == "error" and "сломалось" in q.get(a["id"])["error"]
    assert q.get(b["id"])["status"] == "canceled"
    assert q.get(c["id"])["status"] == "canceled"
    gate.set()


# ---------------------------------------------------------------- моменты
def test_moments_find_loud_section(store, pipe):
    res = pipe.job_moments(SyncCtx({"file": "misc/loud.mp4", "length": 10}))
    top = res["candidates"][0]
    assert 45 <= top["start"] <= 55 and top["end"] - top["start"] == 10, top
    assert "громко" in top["reasons"]
    # второй запуск берётся из кэша и даёт то же самое
    assert pipe.job_moments(SyncCtx({"file": "misc/loud.mp4", "length": 10}))["candidates"][0] == top


def test_moments_speech_density_and_no_overlap():
    levels = [-40.0] * 300
    cues = [{"s": 200 + i, "e": 200.9 + i, "text": "очень много слов в этой реплике"} for i in range(30)]
    c = find_moments(levels, 30, cues, count=5)
    assert 190 <= c[0]["start"] <= 205 and "много речи" in c[0]["reasons"]
    starts = sorted(x["start"] for x in c)
    assert all(b - a >= 22 for a, b in zip(starts, starts[1:]))


# ---------------------------------------------------------------- субтитры
def test_group_words_limits_and_punctuation():
    words = [{"w": w, "s": i * 0.3, "e": i * 0.3 + 0.25} for i, w in enumerate("раз два три четыре. пять шесть".split())]
    lines = subtitles.group_words(words, max_words=3)
    assert [l["text"] for l in lines] == ["раз два три", "четыре.", "пять шесть"]
    assert all(lines[i]["e"] <= lines[i + 1]["s"] for i in range(len(lines) - 1))


def test_ass_highlight_and_escape():
    lines = subtitles.group_words([{"w": "при{вет}", "s": 0, "e": 0.5}, {"w": "мир", "s": 0.5, "e": 1}])
    ass = subtitles.to_ass(lines, "classic", 1300)
    ev = [l for l in ass.splitlines() if l.startswith("Dialogue")]
    assert len(ev) == 2  # по событию на каждое подсвеченное слово
    assert "{\\c&H0000D4FF}ПРИ(ВЕТ)" in ev[0] and "{\\c&H0000D4FF}МИР" in ev[1]
    assert "{вет}" not in ass and "ПРИ(ВЕТ)" in ass
    assert "Montserrat ExtraBold" in ass


def test_srt_roundtrip_and_edits():
    lines = [{"s": 0.5, "e": 1.7, "text": "Привет мир", "words": [{"w": "Привет", "s": 0.5, "e": 1.0},
                                                                   {"w": "мир", "s": 1.1, "e": 1.7}]}]
    srt = subtitles.to_srt(lines)
    assert "00:00:00,500 --> 00:00:01,700" in srt
    assert subtitles.parse_srt("﻿" + srt.replace("\n", "\r\n"))[0]["text"] == "Привет мир"
    same = subtitles.apply_edits([{"s": 0.5, "e": 1.7, "text": "Привет мир"}], lines, 5)
    assert same[0]["words"][1]["s"] == 1.1  # текст не менялся — точные таймкоды сохранены
    new = subtitles.apply_edits([{"s": 0.5, "e": 1.7, "text": "Здравствуй, дорогой мир"}], lines, 5)
    assert len(new[0]["words"]) == 3 and new[0]["words"][-1]["e"] == pytest.approx(1.7, abs=0.01)


def test_layout_line_fits_safe_width():
    st = {"size": 84, "outline": 7}
    for words in (["А"], ["ДОСТОПРИМЕЧАТЕЛЬНОСТИ"], ["ОЧЕНЬ", "ДЛИННЫЕ", "СЛОВА-ПРЕДЛОЖЕНИЯ"]):
        rows, size = subtitles.layout_line(words, st)
        for r in rows:
            assert subtitles.text_width(" ".join(r), size, 7) <= subtitles.SAFE_RIGHT - subtitles.SAFE_LEFT


# ---------------------------------------------------------------- распознавание
def test_parse_whisper_cpp_json():
    data = {"result": {"language": "ru"}, "transcription": [
        {"offsets": {"from": 0, "to": 320}, "text": " Привет"},
        {"offsets": {"from": 320, "to": 400}, "text": ","},
        {"offsets": {"from": 400, "to": 900}, "text": " как"},
        {"offsets": {"from": 900, "to": 1500}, "text": " дела?"},
        {"offsets": {"from": 1500, "to": 2000}, "text": " [музыка]"},
    ]}
    res = asr.parse_whisper_cpp_json(data)
    assert res["language"] == "ru"
    assert [w["w"] for w in res["words"]] == ["Привет,", "как", "дела?"]
    assert res["words"][0]["e"] == 0.4


def test_hallucinations_filtered():
    words = [{"w": w, "s": i, "e": i + 0.5} for i, w in enumerate("Субтитры сделал DimaTorzok".split())]
    assert asr.clean_words(words) == []


def test_backend_selection(tmp_path):
    b, why = asr.get_backend(tmp_path, {"asr_backend": "off"})
    assert b is None and "выключено" in why
    b, why = asr.get_backend(tmp_path, {"asr_backend": "whisper.cpp", "whisper_cpp_bin": "/nonexistent"})
    assert b is None and "whisper.cpp" in why


def test_real_recognition_faster_whisper(tmp_path):
    """Настоящее распознавание: нужна модель tiny. В песочнице без доступа к huggingface.co — пропуск."""
    ok, _ = asr.FasterWhisperBackend.available()
    if not ok or not HAS_ESPEAK:
        pytest.skip("нет faster-whisper или espeak-ng")
    wav = tmp_path / "s.wav"
    subprocess.run(["espeak-ng", "-v", "ru", "-w", str(wav), "Привет, это проверка"], check=True)
    try:
        res = asr.FasterWhisperBackend(tmp_path).transcribe(wav, "ru", "tiny")
    except asr.ASRError as e:
        pytest.skip(f"модель недоступна: {str(e)[:120]}")
    assert res["words"]


# ---------------------------------------------------------------- RSS
RSS = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
 <title>Канал</title>
 <entry><yt:videoId>abc123</yt:videoId><title>Новый стрим: финал</title>
  <link rel="alternate" href="https://www.youtube.com/watch?v=abc123"/><published>2026-10-29T18:00:00+00:00</published></entry>
 <entry><yt:videoId>old</yt:videoId><title>Старое</title><link rel="alternate" href="https://www.youtube.com/watch?v=old"/>
  <published>2026-10-01T18:00:00+00:00</published></entry>
</feed>"""


def test_parse_youtube_rss():
    items = feeds.parse_youtube_rss(RSS)
    assert items[0] == {"title": "Новый стрим: финал", "url": "https://www.youtube.com/watch?v=abc123",
                        "published": "2026-10-29T18:00:00+00:00", "video_id": "abc123"}


def test_twitch_without_keys_is_friendly(monkeypatch):
    monkeypatch.delenv("TWITCH_CLIENT_ID", raising=False)
    r = feeds.twitch_latest("someone")
    assert r["ok"] is False and "TWITCH_CLIENT_ID" in r["error"]


# ---------------------------------------------------------------- установщик
def test_install_fails_clearly_without_libass(tmp_path):
    fake = tmp_path / "bin"
    fake.mkdir()
    for name, out in (("ffmpeg", " ... hflip  V->V  Flip\n V....D libx264  H.264\n"), ("ffprobe", "")):
        p = fake / name
        p.write_text(f"#!/bin/sh\nprintf '{out}'\n")
        p.chmod(p.stat().st_mode | stat.S_IEXEC)
    env = dict(os.environ, PATH=f"{fake}:/usr/bin:/bin", PREFIX="/usr")
    r = subprocess.run(["bash", str(ROOT / "install.sh")], env=env, capture_output=True, text=True, timeout=60)
    assert r.returncode != 0
    assert "libass" in r.stdout


def test_install_fails_clearly_without_ffmpeg(tmp_path):
    env = dict(os.environ, PATH=str(tmp_path), PREFIX="/usr")
    r = subprocess.run(["/bin/bash", str(ROOT / "install.sh")], env=env, capture_output=True, text=True, timeout=60)
    assert r.returncode != 0 and "ffmpeg не найден" in r.stdout


# ---------------------------------------------------------------- автопоиск вебкамеры
def test_facedetect_without_face_is_graceful(tmp_path):
    from tclip import facedetect
    img = tmp_path / "f.jpg"
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                    "testsrc2=s=960x540:d=1", "-frames:v", "1", str(img)], check=True)
    r = facedetect.detect_cam_rect([img])
    assert r["ok"] is False and "вручную" in r["error"]
