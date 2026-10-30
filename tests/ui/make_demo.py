"""Готовит демо-папку для UI-прогона: синтетические видео, источники, расписание.

    python tests/ui/make_demo.py /путь/к/demo
"""
from __future__ import annotations

import datetime as dt
import json
import shutil
import subprocess
import sys
from pathlib import Path


def ff(*a):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *a], check=True)


def main(out: Path):
    if out.exists():
        shutil.rmtree(out)
    media, data = out / "media", out / "data"
    for d in (media / "breaking_bad", media / "streamer", media / "matrix", data):
        d.mkdir(parents=True)
    text = "Привет, это проверка субтитров для нарезки. Всё работает отлично."
    wav = out / "speech.wav"
    if shutil.which("espeak-ng"):
        subprocess.run(["espeak-ng", "-v", "ru", "-s", "150", "-w", str(wav), text], check=True)
        ain = ["-i", str(wav)]
    else:
        ain = ["-f", "lavfi", "-i", "sine=f=330:d=12"]
    # стрим с «вебкамерой» в правом нижнем углу и громким местом
    ff("-f", "lavfi", "-i", "testsrc2=s=1280x720:r=25:d=40",
       "-f", "lavfi", "-i", "color=c=0x6b4bff:s=320x240:r=25:d=40",
       "-f", "lavfi", "-i", "color=c=0xffcc88:s=90x110:r=25:d=40", *ain,
       "-f", "lavfi", "-i", "anoisesrc=c=pink:a=0.5:d=40",
       "-filter_complex", "[1:v][2:v]overlay=x='115+60*sin(t)':y=70[cam];[0:v][cam]overlay=940:460,format=yuv420p[v];"
                          "[4:a]volume=0:enable='not(between(t,20,30))'[n];[3:a]apad[sp];"
                          "[sp][n]amix=inputs=2:duration=longest:normalize=0[a]",
       "-map", "[v]", "-map", "[a]", "-t", "40", "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac",
       str(media / "streamer" / "stream_2026-10-28.mp4"))
    (media / "streamer" / "stream_2026-10-28.srt").write_text(
        "1\n00:00:00,300 --> 00:00:02,200\nПривет, это проверка субтитров\n\n"
        "2\n00:00:02,300 --> 00:00:04,800\nдля нарезки. Всё работает отлично.\n\n"
        "3\n00:00:20,200 --> 00:00:22,600\nА вот и самый громкий момент!\n\n"
        "4\n00:00:23,000 --> 00:00:26,000\nЧат, вы это видели? Невероятно!\n\n"
        "5\n00:00:26,500 --> 00:00:29,500\nСтавьте лайк, если тоже кричали\n\n", encoding="utf-8")
    for i in (1, 2, 3):
        ff("-f", "lavfi", "-i", f"testsrc=s=1280x720:r=25:d=20", "-f", "lavfi", "-i", f"sine=f={300 + i * 60}:d=20",
           "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac",
           str(media / "breaking_bad" / f"S01E0{i}.mp4"))
    ff("-f", "lavfi", "-i", "smptebars=s=1280x544:r=25:d=20", "-f", "lavfi", "-i", "sine=f=500:d=20",
       "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", str(media / "matrix" / "matrix.mp4"))
    # AVI (MPEG-4 Part 2) браузер не проигрывает — проверка режима «кадры»
    ff("-f", "lavfi", "-i", "testsrc2=s=640x360:r=25:d=15", "-f", "lavfi", "-i", "sine=f=500:d=15",
       "-c:v", "mpeg4", "-c:a", "libmp3lame", str(media / "matrix" / "old_rip.avi"))
    today = dt.date.today().isoformat()
    sources = {"start_date": today, "interval_days": 3, "posts_per_block": 3,
               "type_order": ["stream", "series", "movie", "blogger"],
               "sources": [
                   {"id": "streamer", "type": "stream", "title": "Стример Вася", "folder": "streamer",
                    "youtube_channel_id": "", "enabled": True, "profile": {"layout": "split", "sub_style": "classic"}},
                   {"id": "breaking_bad", "type": "series", "title": "Во все тяжкие", "folder": "breaking_bad",
                    "enabled": True, "profile": {}},
                   {"id": "matrix", "type": "movie", "title": "Матрица", "folder": "matrix", "enabled": True,
                    "profile": {}},
               ]}
    (data / "sources.json").write_text(json.dumps(sources, ensure_ascii=False, indent=2), encoding="utf-8")
    (data / "settings.json").write_text(json.dumps({"asr_backend": "off", "x264_preset": "ultrafast",
                                                    "moment_len": 10}), encoding="utf-8")
    print(out)


if __name__ == "__main__":
    main(Path(sys.argv[1]).resolve())
