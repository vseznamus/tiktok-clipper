"""Нарезка: формат, длительность, зеркало, split, субтитры, запасные пути."""
from __future__ import annotations

import struct
import subprocess
from pathlib import Path

import numpy as np
import pytest

from conftest import FakeASR, frame, make_clip, psnr
from tclip.media import probe
from tclip.subtitles import SAFE_BOTTOM, SAFE_LEFT, SAFE_RIGHT, SAFE_TOP

CAM = [1400 / 1920, 680 / 1080, 480 / 1920, 360 / 1080]


def add_source(store, sid, type_, folder, **profile):
    return store.upsert_source({"id": sid, "type": type_, "title": sid, "folder": folder, "profile": profile})


def top_level_boxes(path: Path) -> list[str]:
    boxes, data = [], Path(path).read_bytes()
    i = 0
    while i + 8 <= len(data):
        size, typ = struct.unpack(">I4s", data[i:i + 8])
        if size == 1:
            size = struct.unpack(">Q", data[i + 8:i + 16])[0]
        boxes.append(typ.decode("latin1"))
        if size < 8:
            break
        i += size
    return boxes


def ffprobe_stream(path, sel, entries):
    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", sel, "-show_entries", f"stream={entries}",
                          "-of", "default=nw=1", str(path)], capture_output=True, text=True, check=True).stdout
    return dict(line.split("=", 1) for line in out.strip().splitlines())


@pytest.mark.parametrize("layout,file,start,end", [
    ("single", "movie/movie.mp4", 1.3, 6.8),
    ("split", "stream/stream.mp4", 2.25, 9.05),
])
def test_output_format_and_duration(store, pipe, layout, file, start, end):
    add_source(store, "s", "stream" if layout == "split" else "movie", file.split("/")[0], layout=layout, cam_rect=CAM)
    meta, warns = make_clip(pipe, source_id="s", file=file, start=start, end=end, subtitles=False)
    mp4 = Path(meta["mp4"])
    v = ffprobe_stream(mp4, "v:0", "codec_name,width,height,pix_fmt")
    a = ffprobe_stream(mp4, "a:0", "codec_name")
    assert (v["codec_name"], v["width"], v["height"], v["pix_fmt"]) == ("h264", "1080", "1920", "yuv420p")
    assert a["codec_name"] == "aac"
    dur = probe(mp4)["duration"]
    assert abs(dur - (end - start)) <= 0.1, dur
    boxes = top_level_boxes(mp4)
    assert boxes.index("moov") < boxes.index("mdat"), boxes  # +faststart
    assert meta["layout_used"] == layout
    assert not any("Проверка:" in w for w in warns)


def test_mirror_is_hflip_of_composition(store, pipe):
    add_source(store, "m", "movie", "movie")
    a, _ = make_clip(pipe, source_id="m", file="movie/movie.mp4", start=1, end=4, subtitles=False, mirror=False)
    b, _ = make_clip(pipe, source_id="m", file="movie/movie.mp4", start=1, end=4, subtitles=False, mirror=True)
    fa, fb = frame(a["mp4"], 1.0), frame(b["mp4"], 1.0)
    p_flip = psnr(fa[:, ::-1], fb)
    p_same = psnr(fa, fb)
    print(f"PSNR(hflip(A), B) = {p_flip:.1f} dB; PSNR(A, B) = {p_same:.1f} dB")
    assert p_flip > 35
    assert p_same < 22
    # звук не меняется: дорожки совпадают по длительности и кодеку
    assert abs(probe(a["mp4"])["duration"] - probe(b["mp4"])["duration"]) < 0.05


def _zones(img):
    r, g, b = (img[..., i].astype(int) for i in range(3))
    magenta = (r > 180) & (b > 180) & (g < 90)
    yellow = (r > 180) & (g > 180) & (b < 90)
    sat = img.max(axis=2).astype(int) - img.min(axis=2).astype(int)
    return magenta, yellow, sat


@pytest.mark.parametrize("mirror", [False, True])
def test_split_has_screen_and_webcam_in_their_zones(store, pipe, mirror):
    add_source(store, "st", "stream", "stream", layout="split", cam_rect=CAM,
               split_screen_h=0.45, split_gap_h=0.10, split_cam_h=0.45)
    meta, warns = make_clip(pipe, source_id="st", file="stream/stream.mp4", start=1, end=4, subtitles=False, mirror=mirror)
    assert meta["layout_used"] == "split" and not warns
    img = frame(meta["mp4"], 1.5)
    magenta, yellow, sat = _zones(img)
    scr, cam = slice(0, 864), slice(1056, 1920)
    # вебкамера заполняет нижнюю зону: пурпурный фон + жёлтое «лицо»
    assert magenta[cam].mean() > 0.5, magenta[cam].mean()
    assert yellow[cam].mean() > 0.01
    # экран (серый testsrc2) сверху: почти без цвета, но с деталями
    assert magenta[scr].mean() < 0.15
    assert np.median(sat[scr]) < 30
    assert img[scr].std() > 30


def test_split_without_webcam_falls_back_to_single(store, pipe):
    add_source(store, "nocam", "stream", "stream", layout="split", cam_rect=None)
    meta, warns = make_clip(pipe, source_id="nocam", file="stream/stream.mp4", start=0, end=3, subtitles=False)
    assert meta["layout_used"] == "single"
    assert any("вебкамеры" in w for w in warns)
    assert probe(meta["mp4"])["width"] == 1080


def test_source_without_audio_gets_silent_aac(store, pipe):
    meta, warns = make_clip(pipe, file="misc/noaudio.mp4", start=0.5, end=3.5)
    p = probe(meta["mp4"])
    assert p["acodec"] == "aac" and abs(p["duration"] - 3.0) <= 0.1
    assert any("нет звука" in w for w in warns)
    assert not meta["lines"]


LONG_WORDS = [("ДОСТОПРИМЕЧАТЕЛЬНОСТИ", 0.2, 1.2), ("ПРЕВОСХОДНЕЙШИЕ", 1.2, 2.0), ("ВЕЛИКОЛЕПНО", 2.0, 2.9),
              ("ЖЁЛТЫЙ", 3.0, 3.6), ("ЩИТ", 3.6, 4.4)]


def _text_bbox(img, thr=60):
    mask = img.max(axis=2) > thr
    ys, xs = np.nonzero(mask)
    return (xs.min(), ys.min(), xs.max(), ys.max()), mask.sum()


@pytest.mark.parametrize("layout", ["single", "split"])
def test_subtitles_not_mirrored_and_inside_safe_zone(store, pipe, tmp_path, layout):
    """Чёрный исходник + зеркало: на кадре видны только субтитры. Они должны совпадать с той же ASS,
    отрисованной БЕЗ зеркала, и не совпадать с её отражением; рамка текста — в безопасной зоне."""
    add_source(store, "b", "movie", "misc", layout=layout, cam_rect=[0, 0, 0.5, 0.5])
    words = [{"w": w, "s": s, "e": e} for w, s, e in LONG_WORDS]
    meta, _ = make_clip(pipe, asr=FakeASR(words), source_id="b", file="misc/black.mp4", start=0, end=5,
                        mirror=True, sub_style="classic")
    assert meta["lines"], "строки субтитров должны быть"
    assert Path(meta["srt"]).is_file() and Path(meta["ass"]).is_file()
    ref = tmp_path / "ref.mp4"
    fontsdir = Path(__file__).resolve().parent.parent / "fonts"
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                    "color=c=black:s=1080x1920:r=25:d=5", "-vf", f"ass={meta['ass']}:fontsdir={fontsdir},format=yuv420p",
                    "-c:v", "libx264", "-preset", "ultrafast", "-crf", "12", str(ref)], check=True)
    for t in (0.6, 1.6, 2.5, 4.0):
        out, rf = frame(meta["mp4"], t), frame(ref, t)
        (x0, y0, x1, y1), n = _text_bbox(rf)
        assert n > 2000, "на эталоне должен быть текст"
        # область сравнения: объединение рамок текста и его отражения
        xa, xb = min(x0, 1079 - x1), max(x1, 1079 - x0)
        reg = (slice(max(0, y0 - 10), min(1920, y1 + 10)), slice(max(0, xa - 10), min(1080, xb + 10)))
        p_ok = psnr(out[reg], rf[reg])
        p_mirror = psnr(out[reg], rf[:, ::-1][reg])
        print(f"{layout} t={t}: PSNR(эталон)={p_ok:.1f}, PSNR(отражённый эталон)={p_mirror:.1f}, bbox={x0, y0, x1, y1}")
        assert p_ok > 28
        assert p_mirror < p_ok - 8
        (ox0, oy0, ox1, oy1), _ = _text_bbox(out)
        assert SAFE_LEFT <= ox0 and ox1 <= SAFE_RIGHT, (ox0, ox1)
        assert SAFE_TOP <= oy0 and oy1 <= SAFE_BOTTOM, (oy0, oy1)


def test_no_speech_makes_clip_without_subtitles(store, pipe):
    meta, warns = make_clip(pipe, asr=FakeASR([]), file="movie/movie.mp4", start=0, end=3)
    assert meta["lines"] == [] and meta["srt"] is None
    assert any("Речь не найдена" in w for w in warns)
    assert probe(meta["mp4"])["height"] == 1920


def test_asr_unavailable_is_warning_not_error(store, pipe):
    meta, warns = make_clip(pipe, asr_none="Распознавание речи не установлено", file="movie/movie.mp4", start=0, end=2)
    assert Path(meta["mp4"]).is_file()
    assert any("не установлено" in w for w in warns)


def test_rebuild_uses_edited_lines_without_new_recognition(store, pipe):
    from conftest import SyncCtx
    asr = FakeASR([{"w": "привет", "s": 0.2, "e": 0.6}, {"w": "мир", "s": 0.6, "e": 1.0},
                   {"w": "тест", "s": 1.5, "e": 2.0}])
    meta, _ = make_clip(pipe, asr=asr, file="movie/movie.mp4", start=1, end=4)
    assert asr.calls == 1
    lines = [{"s": l["s"], "e": l["e"], "text": l["text"]} for l in meta["lines"]]
    lines[0]["text"] = "Здравствуй мир"
    pipe.job_rebuild(SyncCtx({"clip_id": meta["id"], "lines": lines}))
    assert asr.calls == 1, "пересборка не должна заново распознавать речь"
    m2 = pipe.load_clip(meta["id"])
    assert m2["version"] == 1
    srt = Path(m2["srt"]).read_text(encoding="utf-8")
    assert "Здравствуй мир" in srt and "привет" not in srt
    assert "ЗДРАВСТВУЙ" in Path(m2["ass"]).read_text(encoding="utf-8")
    assert abs(probe(m2["mp4"])["duration"] - 3.0) <= 0.1
    # выключение субтитров при пересборке
    pipe.job_rebuild(SyncCtx({"clip_id": meta["id"], "subtitles": False}))
    m3 = pipe.load_clip(meta["id"])
    assert m3["srt"] is None and not Path(m2["srt"]).exists()


def test_sidecar_srt_as_subtitle_source(store, pipe, media):
    srt = media["root"] / "movie" / "movie.srt"
    srt.write_text("1\n00:00:01,000 --> 00:00:03,000\nТекст из файла\n\n", encoding="utf-8")
    try:
        meta, _ = make_clip(pipe, asr=FakeASR([]), file="movie/movie.mp4", start=0.5, end=4, sub_source="srt")
        assert [l["text"] for l in meta["lines"]] == ["Текст из файла"]
        assert meta["asr"]["backend"] == "srt"
    finally:
        srt.unlink()
