#!/usr/bin/env python3
"""tiktok-clipper — запасной вариант управления из терминала.

Основной интерфейс — веб-страница (python clipper.py serve). Команды:
  serve [--lan] [--host H] [--port P]   запустить сервер
  check                                 проверить ffmpeg, libass, распознавание речи
  today                                 что публиковать сегодня
  publish | skip | postpone [дней] | undo
  make ФАЙЛ НАЧАЛО КОНЕЦ [--source ID] [--no-mirror] [--no-subs] [--lang ru] [--layout single|split]
  moments ФАЙЛ [--len 30]
Время можно писать как 75.5, 1:15.5 или 0:01:15.5. ФАЙЛ — путь внутри папки с видео.
"""
from __future__ import annotations

import argparse
import datetime as dt
import sys
import threading

from tclip import schedule
from tclip.config import Store, read_json, write_json


def parse_time(s: str) -> float:
    total = 0.0
    for part in s.replace(",", ".").split(":"):
        total = total * 60 + float(part)
    return total


class _Ctx:
    """Простой контекст задачи для запуска без очереди: печатает прогресс в терминал."""

    def __init__(self, params):
        self.params, self.cancel, self.job = params, threading.Event(), {"warnings": []}
        self._last = ""

    def progress(self, frac, stage=None):
        line = f"\r{(stage or self._last)[:50]:<50} {frac * 100:5.1f}%"
        if stage:
            self._last = stage
        print(line, end="", flush=True)

    def stage_progress(self, lo, hi, stage=None):
        if stage:
            self.progress(lo, stage)
        return lambda f: self.progress(lo + (hi - lo) * f)

    def warn(self, msg):
        self.job["warnings"].append(msg)
        print(f"\n⚠ {msg}")

    def check(self):
        pass


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Нарезка клипов для TikTok")
    sub = ap.add_subparsers(dest="cmd")
    s = sub.add_parser("serve", help="запустить веб-интерфейс")
    s.add_argument("--host", default=None)
    s.add_argument("--port", type=int, default=8080)
    s.add_argument("--lan", action="store_true", help="доступ из локальной сети (нужен CLIPPER_TOKEN)")
    sub.add_parser("check")
    sub.add_parser("today")
    sub.add_parser("publish")
    sub.add_parser("skip")
    sub.add_parser("undo")
    p = sub.add_parser("postpone")
    p.add_argument("days", nargs="?", type=int, default=1)
    m = sub.add_parser("make")
    m.add_argument("file")
    m.add_argument("start")
    m.add_argument("end")
    m.add_argument("--source")
    m.add_argument("--no-mirror", action="store_true")
    m.add_argument("--no-subs", action="store_true")
    m.add_argument("--lang", default=None)
    m.add_argument("--layout", choices=["single", "split"])
    mo = sub.add_parser("moments")
    mo.add_argument("file")
    mo.add_argument("--len", type=int, default=30)
    a = ap.parse_args(argv)

    if a.cmd in (None, "serve"):
        import os
        from tclip.server import serve
        host = getattr(a, "host", None) or os.environ.get("CLIPPER_HOST") or ("0.0.0.0" if getattr(a, "lan", False) else "127.0.0.1")
        serve(host, getattr(a, "port", None) or int(os.environ.get("CLIPPER_PORT", 8080)))
        return 0

    st = Store()
    if a.cmd == "check":
        from tclip.asr import backends_status
        from tclip.media import check_ffmpeg
        ff = check_ffmpeg()
        print("ffmpeg:", "есть" if ff["ffmpeg"] else "НЕТ", "|", ff["version"])
        print("libass:", "есть" if ff["libass"] else "НЕТ", "| H.264:", "есть" if ff["libx264"] else "НЕТ",
              "| AAC:", "есть" if ff["aac"] else "НЕТ")
        for k, v in backends_status(st.data / "models", st.settings()).items():
            print(f"{k}: {'есть' if v['ok'] else 'нет'} {v.get('message', '')} {v.get('models', '')}")
        print("Папка с видео:", st.media_root())
        print("Готовые клипы:", st.output_dir())
        for prob in ff["problems"]:
            print("ПРОБЛЕМА:", prob)
        return 1 if ff["problems"] else 0

    cfg = st.sources()
    state = schedule.normalize_state(read_json(st.state_path, {}))
    today = dt.date.today()
    if a.cmd == "today":
        posts = schedule.upcoming(cfg, state, today, 6)
        write_json(st.state_path, state)
        if not posts:
            print("Источников нет. Добавьте их в веб-интерфейсе («Настройки» → «Добавить источник»).")
            return 0
        c = posts[0]
        due = dt.date.fromisoformat(c["date"])
        print(f"Сегодня ({today}): {c['type_name']} «{c['source_title']}», пост {c['pos']}/{c['posts_per_block']}, "
              f"дата {c['date']} — {schedule.due_status(due, today)}")
        src = st.get_source(c["source_id"])
        if src:
            sug = schedule.suggest_files(c["type"], st.list_files(src["folder"]), state["used_files"].get(src["id"], []))
            if sug["next"]:
                print("Файл:", sug["next"]["path"])
        print("Дальше:")
        for p_ in posts[1:]:
            print(f"  {p_['date']}  {p_['type_name']:7} {p_['source_title']} ({p_['pos']}/{p_['posts_per_block']})")
        return 0
    if a.cmd in ("publish", "skip", "postpone", "undo"):
        try:
            if a.cmd == "publish":
                r = schedule.publish(cfg, state, today)
                print(f"Опубликовано: «{r['source_title']}» {r['pos']}/{r['posts_per_block']}")
            elif a.cmd == "skip":
                r = schedule.skip(cfg, state, today)
                print(f"Пропущено: «{r['source_title']}» {r['pos']}/{r['posts_per_block']}")
            elif a.cmd == "postpone":
                print("Перенесено на", schedule.postpone(cfg, state, today, a.days))
            else:
                print("Отменено" if schedule.undo(state) else "Нечего отменять")
        except ValueError as e:
            print("Ошибка:", e)
            return 1
        write_json(st.state_path, state)
        return 0

    from tclip.pipeline import Pipeline
    pipe = Pipeline(st)
    if a.cmd == "make":
        params = {"file": a.file, "start": parse_time(a.start), "end": parse_time(a.end), "source_id": a.source,
                  "mirror": False if a.no_mirror else None, "subtitles": False if a.no_subs else None,
                  "language": a.lang, "layout": a.layout}
        ctx = _Ctx(params)
        try:
            res = pipe.job_clip(ctx)
        except Exception as e:
            print("\nОшибка:", e)
            return 1
        meta = pipe.load_clip(res["clip_id"])
        print(f"\nГотово: {meta['mp4']}")
        return 0
    if a.cmd == "moments":
        ctx = _Ctx({"file": a.file, "length": a.len})
        res = pipe.job_moments(ctx)
        print()
        for i, c in enumerate(res["candidates"], 1):
            print(f"{i}. {c['start']:.1f}–{c['end']:.1f}  оценка {c['score']:.2f}  ({', '.join(c['reasons'])})")
        return 0
    ap.print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main())
