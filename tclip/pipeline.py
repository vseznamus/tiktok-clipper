"""Обработчики задач очереди: сделать клип, пересобрать клип, предложить моменты."""
from __future__ import annotations

import datetime as dt
import shutil
import time
import uuid
from pathlib import Path

from . import asr as asr_mod
from . import termux
from .config import SUB_STYLES, Store, default_asr_model, default_preset, full_profile, read_json, write_json
from .media import extract_audio_wav, probe
from .moments import cached_loudness, find_moments, load_cues, sidecar_srt
from .render import render_clip, valid_cam_rect
from .subtitles import apply_edits, group_words, parse_srt, sub_center_y, to_ass, to_srt, words_from_cues

MAX_CLIP_SEC = 600
DUR_TOL = 0.1


class Pipeline:
    def __init__(self, store: Store):
        self.store = store
        # можно подменить в тестах: функция (settings) -> (backend|None, причина)
        self.asr_factory = lambda settings: asr_mod.get_backend(self.store.data / "models", settings)

    # ------------------------------------------------------------ клипы на диске
    def clip_meta_path(self, clip_id: str) -> Path:
        return self.store.data / "clips" / f"{clip_id}.json"

    def load_clip(self, clip_id: str) -> dict | None:
        if not clip_id.isalnum():
            return None
        return read_json(self.clip_meta_path(clip_id), None)

    def save_clip(self, meta: dict) -> None:
        write_json(self.clip_meta_path(meta["id"]), meta)

    def list_clips(self) -> list[dict]:
        out = []
        for p in (self.store.data / "clips").glob("*.json"):
            m = read_json(p, None)
            if m:
                m["exists"] = Path(m["mp4"]).is_file()
                out.append({k: m.get(k) for k in ("id", "created", "source_id", "source_title", "src", "start",
                                                  "end", "filename", "exists", "version", "published", "warnings")})
        out.sort(key=lambda m: -(m.get("created") or 0))
        return out

    def delete_clip(self, clip_id: str) -> None:
        m = self.load_clip(clip_id)
        if not m:
            return
        for k in ("mp4", "srt", "ass"):
            if m.get(k):
                Path(m[k]).unlink(missing_ok=True)
        self.clip_meta_path(clip_id).unlink(missing_ok=True)

    # ------------------------------------------------------------ задача «клип»
    def job_clip(self, ctx) -> dict:
        p = ctx.params
        st = self.store
        settings = st.settings()
        src_cfg = st.get_source(p.get("source_id") or "") if p.get("source_id") else None
        prof = full_profile(src_cfg)
        src = st.safe_media_path(p["file"])
        if not src.is_file():
            raise FileNotFoundError(f"Файл не найден: {p['file']}")
        ctx.progress(0.01, "Читаю видео")
        info = probe(src)
        if not info["has_video"]:
            raise ValueError("В файле нет видеодорожки")
        start = max(0.0, float(p["start"]))
        end = float(p["end"])
        if info["duration"]:
            end = min(end, info["duration"])
        dur = round(end - start, 3)
        if dur < 1.0:
            raise ValueError("Отрезок слишком короткий (меньше 1 секунды)")
        if dur > MAX_CLIP_SEC:
            raise ValueError("Отрезок длиннее 10 минут")

        mirror = prof["mirror"] if p.get("mirror") is None else bool(p["mirror"])
        want_subs = prof["subtitles"] if p.get("subtitles") is None else bool(p["subtitles"])
        sub_style = p.get("sub_style") or prof["sub_style"]
        if sub_style not in SUB_STYLES:
            sub_style = "classic"
        layout = p.get("layout") or prof["layout"]
        language = p.get("language") or prof.get("language") or settings.get("asr_language") or "auto"
        clip_id = uuid.uuid4().hex[:12]
        work = st.data / "work" / clip_id
        work.mkdir(parents=True, exist_ok=True)

        words: list[dict] = []
        asr_info = None
        if want_subs:
            words, asr_info = self._get_words(ctx, src, start, dur, info, language, p.get("sub_source"), work, settings)
        lines = group_words(words, max_words=SUB_STYLES[sub_style]["words"]) if words else []

        meta = {
            "id": clip_id, "created": time.time(), "source_id": p.get("source_id"),
            "source_title": src_cfg["title"] if src_cfg else "Без источника", "src": p["file"],
            "start": start, "end": end, "mirror": mirror, "subtitles": want_subs, "sub_style": sub_style,
            "language": language, "layout_requested": layout, "profile": prof, "words": words, "lines": lines,
            "asr": asr_info, "version": 0, "published": False, "warnings": [],
        }
        self._render(ctx, meta, info, work, settings, lo=0.45 if want_subs else 0.05)
        shutil.rmtree(work, ignore_errors=True)
        return {"clip_id": clip_id, "filename": meta["filename"], "warnings": meta["warnings"]}

    def _get_words(self, ctx, src: Path, start: float, dur: float, info: dict, language: str,
                   sub_source: str | None, work: Path, settings: dict):
        if sub_source == "srt":
            srt = sidecar_srt(src)
            if srt:
                ctx.progress(0.05, "Беру субтитры из файла .srt")
                cues = parse_srt(srt.read_text(encoding="utf-8", errors="replace"))
                words = words_from_cues(cues, start, start + dur)
                if not words:
                    ctx.warn("В файле .srt нет реплик на этом отрезке — клип без субтитров.")
                return words, {"backend": "srt", "file": srt.name}
            ctx.warn("Рядом с видео нет файла .srt — пробую распознать речь.")
        if not info["has_audio"]:
            ctx.warn("В исходнике нет звука — клип без субтитров.")
            return [], None
        backend, why = self.asr_factory(settings)
        if backend is None:
            ctx.warn(why)
            return [], None
        model = settings.get("asr_model") or default_asr_model()
        ctx.progress(0.03, "Извлекаю звук")
        wav = extract_audio_wav(src, start, dur, work / "audio.wav", cancel=ctx.cancel)
        ctx.check()
        prog = ctx.stage_progress(0.05, 0.45, f"Распознаю речь ({backend.name}, модель {model})")
        lang = None if language in (None, "", "auto") else language
        try:
            res = backend.transcribe(wav, lang, model, progress=prog, cancel=ctx.cancel)
        except asr_mod.ASRError as e:
            ctx.warn(f"{e} Клип сделан без субтитров.")
            return [], None
        ctx.check()
        words = [w for w in res["words"] if w["s"] < dur]
        for w in words:
            w["e"] = min(w["e"], dur)
        if not words:
            ctx.warn("Речь не найдена — клип сделан без субтитров.")
        return words, {"backend": backend.name, "model": model, "language": res.get("language")}

    def _render(self, ctx, meta: dict, info: dict, work: Path, settings: dict, lo: float) -> None:
        st = self.store
        prof = meta["profile"]
        layout_req = meta["layout_requested"]
        has_subs = meta["subtitles"] and bool(meta["lines"])
        eff_layout = layout_req
        if layout_req == "split" and not valid_cam_rect(prof.get("cam_rect"), info["width"], info["height"]):
            eff_layout = "single"
        ass = to_ass(meta["lines"], meta["sub_style"], sub_center_y(prof, eff_layout)) if has_subs else None
        outdir = st.output_dir()
        outdir.mkdir(parents=True, exist_ok=True)
        if not meta.get("filename"):
            stamp = dt.datetime.now().strftime("%Y-%m-%d_%H%M%S")
            meta["filename"] = f"{stamp}_{meta.get('source_id') or 'clip'}.mp4"
        mp4 = outdir / meta["filename"]
        prog = ctx.stage_progress(lo, 0.97, "Собираю видео" if meta["version"] == 0 else "Пересобираю видео")
        res = render_clip(st.safe_media_path(meta["src"]), meta["start"], meta["end"], mp4, info, prof,
                          layout_req, meta["mirror"], ass, work,
                          preset=settings.get("x264_preset") or default_preset(), crf=int(settings.get("crf") or 21),
                          progress=prog, cancel=ctx.cancel)
        for w in res["warnings"]:
            ctx.warn(w)
        meta["layout_used"] = res["layout"]
        ctx.progress(0.98, "Проверяю результат")
        out = probe(mp4)
        expected = meta["end"] - meta["start"]
        if (out["width"], out["height"]) != (1080, 1920) or abs(out["duration"] - expected) > DUR_TOL:
            ctx.warn(f"Проверка: получилось {out['width']}x{out['height']}, {out['duration']:.2f} с "
                     f"(ожидалось 1080x1920, {expected:.2f} с).")
        base = mp4.with_suffix("")
        meta["mp4"] = str(mp4)
        meta["srt"] = str(base.with_suffix(".srt")) if has_subs else None
        meta["ass"] = str(base.with_suffix(".ass")) if has_subs else None
        for k in ("srt", "ass"):
            p = base.with_suffix("." + k)
            if has_subs:
                p.write_text(to_srt(meta["lines"]) if k == "srt" else ass, encoding="utf-8")
            else:
                p.unlink(missing_ok=True)
        meta["duration"] = out["duration"]
        meta["warnings"] = list(ctx.job.get("warnings") or [])
        meta["rendered"] = time.time()
        self.save_clip(meta)
        termux.media_scan(str(mp4))

    # ------------------------------------------------------------ задача «пересобрать»
    def job_rebuild(self, ctx) -> dict:
        p = ctx.params
        meta = self.load_clip(p["clip_id"])
        if not meta:
            raise FileNotFoundError("Клип не найден")
        dur = meta["end"] - meta["start"]
        if p.get("lines") is not None:
            meta["lines"] = apply_edits(p["lines"], meta.get("lines") or [], dur)
            meta["subtitles"] = True
        if p.get("subtitles") is not None:
            meta["subtitles"] = bool(p["subtitles"])
        if p.get("mirror") is not None:
            meta["mirror"] = bool(p["mirror"])
        if p.get("sub_style") in SUB_STYLES:
            meta["sub_style"] = p["sub_style"]
        meta["version"] = int(meta.get("version") or 0) + 1
        ctx.progress(0.02, "Читаю видео")
        info = probe(self.store.safe_media_path(meta["src"]))
        work = self.store.data / "work" / f"{meta['id']}_r{meta['version']}"
        self._render(ctx, meta, info, work, self.store.settings(), lo=0.05)
        shutil.rmtree(work, ignore_errors=True)
        return {"clip_id": meta["id"], "filename": meta["filename"], "warnings": meta["warnings"]}

    # ------------------------------------------------------------ задача «моменты»
    def job_moments(self, ctx) -> dict:
        p = ctx.params
        src = self.store.safe_media_path(p["file"])
        info = probe(src)
        if not info["has_audio"]:
            raise ValueError("В файле нет звука — искать громкие моменты не по чему")
        length = int(p.get("length") or self.store.settings().get("moment_len") or 30)
        prog = ctx.stage_progress(0.0, 0.95, "Слушаю звук всего файла (первый раз долго, потом из кэша)")
        levels = cached_loudness(src, info["duration"], self.store.data / "cache", progress=prog, cancel=ctx.cancel)
        cues = load_cues(src, self.store.data / "cache")
        cands = find_moments(levels, length, cues or None, count=8)
        return {"file": p["file"], "length": length, "candidates": cands, "speech": bool(cues)}
