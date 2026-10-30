"""Веб-сервер (Flask) для управления с телефона.

Безопасность:
  * по умолчанию слушает только 127.0.0.1;
  * на любом другом адресе (0.0.0.0, IP в локальной сети) без CLIPPER_TOKEN сервер не стартует;
  * с токеном все страницы и API требуют вход (пароль = токен) или заголовок Authorization: Bearer;
  * без токена запросы принимаются только с loopback-адреса и с Host = localhost/127.0.0.1
    (защита от DNS-rebinding и случайного проброса через прокси).
"""
from __future__ import annotations

import datetime as dt
import hashlib
import hmac
import ipaddress
import os
import re
import sys
import threading
import time
from pathlib import Path

from flask import Flask, Response, abort, jsonify, redirect, request, send_file

from . import __version__, facedetect, feeds, schedule
from .asr import MODELS, backends_status
from .config import (SOURCE_TYPES, STATIC_DIR, SUB_STYLES, TYPE_NAMES, VIDEO_EXTS, Store, default_asr_model,
                     default_preset, full_profile, is_termux, read_json, write_json)
from .jobs import JobQueue
from .media import check_ffmpeg, extract_frame, probe
from .pipeline import Pipeline

COOKIE = "clipper_auth"
MIN_TOKEN_LEN = 8
_state_lock = threading.RLock()


# ---------------------------------------------------------------- проверка адреса и токена

def is_loopback(host: str) -> bool:
    h = (host or "").strip().strip("[]").lower()
    if h in ("localhost", "localhost.localdomain"):
        return True
    try:
        return ipaddress.ip_address(h).is_loopback
    except ValueError:
        return False


def bind_problem(host: str, token: str) -> str | None:
    """Причина, по которой сервер нельзя запускать, или None."""
    if token and len(token) < MIN_TOKEN_LEN:
        return f"ОШИБКА: CLIPPER_TOKEN слишком короткий. Нужно не меньше {MIN_TOKEN_LEN} символов."
    if not is_loopback(host) and not token:
        return ("ОШИБКА: сервер не запущен.\n"
                f"Вы просите открыть доступ по адресу {host}, но пароль не задан.\n"
                "Без пароля любой в сети мог бы управлять программой и видеть ваши файлы.\n"
                "Задайте пароль (не короче 8 символов) в переменной CLIPPER_TOKEN, например:\n"
                "  CLIPPER_TOKEN='придумайте-длинный-пароль' bash start.sh --lan\n"
                "или запускайте только для этого устройства (адрес 127.0.0.1).")
    return None


def _session_value(token: str) -> str:
    return hmac.new(token.encode(), b"clipper-session-v1", hashlib.sha256).hexdigest()


LOGIN_HTML = """<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Вход — TikTok-нарезка</title>
<style>body{font:18px system-ui,sans-serif;background:#111118;color:#fff;margin:0;padding:24px 16px}
.box{max-width:420px;margin:12vh auto 0}h1{font-size:26px}input,button{width:100%;box-sizing:border-box;
font-size:20px;padding:16px;border-radius:14px;border:0;margin-top:14px}button{background:#ffd400;color:#000;
font-weight:700;min-height:56px}.err{color:#ff7b7b;margin-top:12px}</style></head><body><div class="box">
<h1>TikTok-нарезка</h1><p>Введите пароль, который вы задали при запуске сервера (CLIPPER_TOKEN).</p>
<form method="post" action="/login"><input type="password" name="password" autocomplete="current-password"
placeholder="Пароль" autofocus required><button type="submit">Войти</button></form>{err}</div></body></html>"""


# ---------------------------------------------------------------- приложение

def create_app(store: Store | None = None, token: str | None = None, start_worker: bool = True) -> Flask:
    store = store or Store()
    token = (os.environ.get("CLIPPER_TOKEN", "") if token is None else token).strip()
    pipe = Pipeline(store)
    queue = JobQueue(store.jobs_path, {"clip": pipe.job_clip, "rebuild": pipe.job_rebuild,
                                       "moments": pipe.job_moments})
    if start_worker:
        queue.start()

    app = Flask(__name__, static_folder=str(STATIC_DIR), static_url_path="/static")
    app.config["MAX_CONTENT_LENGTH"] = int(store.settings().get("upload_max_mb") or 300) * 1024 * 1024 + 1024 * 1024
    app.clipper = {"store": store, "pipeline": pipe, "queue": queue, "token": token}
    failures: dict[str, list[float]] = {}
    ffcheck: dict = {}

    def authorized() -> bool:
        if not token:
            return True
        auth = request.headers.get("Authorization", "")
        if auth.startswith("Bearer ") and hmac.compare_digest(auth[7:].strip().encode(), token.encode()):
            return True
        c = request.cookies.get(COOKIE, "")
        return bool(c) and hmac.compare_digest(c.encode(), _session_value(token).encode())

    @app.before_request
    def _guard():
        if not token:
            host = request.host.rsplit(":", 1)[0] if not request.host.startswith("[") else request.host.split("]")[0]
            if (not is_loopback(host) or not is_loopback(request.remote_addr or "")
                    or request.headers.get("X-Forwarded-For")):
                return Response("Доступ запрещён: сервер без пароля работает только на этом устройстве "
                                "(127.0.0.1). Задайте CLIPPER_TOKEN для доступа по сети.", 403,
                                mimetype="text/plain; charset=utf-8")
            return None
        if request.path == "/login":
            return None
        if authorized():
            return None
        if request.path == "/" or request.path.endswith(".html"):
            return redirect("/login")
        return jsonify({"error": "Нужен вход по паролю"}), 401

    @app.after_request
    def _headers(resp):
        resp.headers["X-Content-Type-Options"] = "nosniff"
        resp.headers["Referrer-Policy"] = "no-referrer"
        resp.headers["X-Frame-Options"] = "DENY"
        resp.headers["Content-Security-Policy"] = (
            "default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; "
            "style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'")
        if request.path.startswith("/api/"):
            resp.headers["Cache-Control"] = "no-store"
        return resp

    @app.errorhandler(413)
    def _too_big(_e):
        mb = store.settings().get("upload_max_mb")
        return jsonify({"error": f"Файл слишком большой для загрузки с телефона (больше {mb} МБ). "
                                 "Большие файлы положите в папку источника на устройстве с сервером."}), 413

    # ------------------------------------------------------------ вход
    @app.route("/login", methods=["GET", "POST"])
    def login():
        if not token:
            return redirect("/")
        ip = request.remote_addr or "?"
        now = time.time()
        recent = [t for t in failures.get(ip, []) if now - t < 600]
        failures[ip] = recent
        if request.method == "GET":
            return Response(LOGIN_HTML.replace("{err}", ""), mimetype="text/html")
        if len(recent) >= 8:
            return Response(LOGIN_HTML.replace("{err}", '<p class="err">Слишком много попыток. Подождите 10 минут.</p>'),
                            429, mimetype="text/html")
        pw = (request.form.get("password") or "").strip()
        if not hmac.compare_digest(pw.encode(), token.encode()):
            recent.append(now)
            return Response(LOGIN_HTML.replace("{err}", '<p class="err">Неверный пароль.</p>'), 401,
                            mimetype="text/html")
        resp = redirect("/")
        secure = request.is_secure or request.headers.get("X-Forwarded-Proto") == "https"
        resp.set_cookie(COOKIE, _session_value(token), max_age=180 * 86400, httponly=True,
                        samesite="Strict", secure=secure)
        return resp

    @app.post("/logout")
    def logout():
        resp = jsonify({"ok": True})
        resp.delete_cookie(COOKIE)
        return resp

    # ------------------------------------------------------------ статика PWA
    @app.get("/")
    def index():
        return send_file(STATIC_DIR / "index.html", max_age=0)

    @app.get("/sw.js")
    def sw():
        r = send_file(STATIC_DIR / "sw.js", mimetype="text/javascript", max_age=0)
        r.headers["Service-Worker-Allowed"] = "/"
        return r

    @app.get("/manifest.webmanifest")
    def manifest():
        return send_file(STATIC_DIR / "manifest.webmanifest", mimetype="application/manifest+json", max_age=0)

    # ------------------------------------------------------------ состояние расписания
    def load_state() -> dict:
        return schedule.normalize_state(read_json(store.state_path, {}))

    def save_state(st: dict) -> None:
        write_json(store.state_path, st)

    def today() -> dt.date:
        return dt.date.fromisoformat(os.environ["CLIPPER_TODAY"]) if os.environ.get("CLIPPER_TODAY") else dt.date.today()

    def bad(msg: str, code: int = 400):
        return jsonify({"error": msg}), code

    def body() -> dict:
        return request.get_json(silent=True) or {}

    # ------------------------------------------------------------ API: статус и настройки
    @app.get("/api/status")
    def api_status():
        if not ffcheck:
            ffcheck.update(check_ffmpeg())
        s = store.settings()
        return jsonify({
            "version": __version__, "termux": is_termux(), "ffmpeg": ffcheck,
            "asr": backends_status(store.data / "models", s), "opencv": facedetect.available(),
            "media_root": str(store.media_root()), "output_dir": str(store.output_dir()),
            "auth": bool(token), "default_model": default_asr_model(), "default_preset": default_preset(),
        })

    @app.get("/api/config")
    def api_config():
        cfg = store.sources()
        for s in cfg["sources"]:
            s["full_profile"] = full_profile(s)
        return jsonify({"schedule": {k: cfg.get(k) for k in ("start_date", "interval_days", "posts_per_block",
                                                              "type_order")},
                        "sources": cfg["sources"], "settings": store.settings(),
                        "sub_styles": {k: v["name"] for k, v in SUB_STYLES.items()},
                        "type_names": TYPE_NAMES, "types": SOURCE_TYPES, "models": MODELS})

    @app.put("/api/schedule")
    def api_schedule_save():
        b = body()
        cfg = store.sources()
        if "start_date" in b:
            try:
                cfg["start_date"] = dt.date.fromisoformat(b["start_date"]).isoformat() if b["start_date"] else None
            except ValueError:
                return bad("Неверная дата старта")
        for k, lo, hi in (("interval_days", 1, 30), ("posts_per_block", 1, 20)):
            if k in b:
                try:
                    cfg[k] = min(hi, max(lo, int(b[k])))
                except (TypeError, ValueError):
                    return bad("Нужно целое число")
        if "type_order" in b:
            order = [t for t in b["type_order"] if t in SOURCE_TYPES]
            cfg["type_order"] = order + [t for t in SOURCE_TYPES if t not in order]
        store.save_sources(cfg)
        return jsonify({"ok": True})

    @app.put("/api/settings")
    def api_settings():
        b = body()
        allowed = {"asr_backend": ("auto", "faster-whisper", "whisper.cpp", "off"), "asr_model": tuple(MODELS) + (None,),
                   "x264_preset": ("ultrafast", "superfast", "veryfast", "faster", "fast", "medium", None)}
        patch = {}
        for k, v in b.items():
            if k in allowed and v not in allowed[k]:
                return bad(f"Недопустимое значение {k}")
            if k in ("asr_language",):
                v = str(v or "auto")[:8]
            if k in ("moment_len",):
                v = min(180, max(5, int(v)))
            if k in allowed or k in ("asr_language", "moment_len"):
                patch[k] = v
        return jsonify(store.save_settings(patch))

    @app.post("/api/sources")
    def api_source_save():
        b = body()
        try:
            src = store.upsert_source({k: b.get(k) for k in ("id", "type", "title", "folder", "youtube_channel_id",
                                                              "twitch_login", "enabled", "profile") if k in b})
        except ValueError as e:
            return bad(str(e))
        return jsonify(src)

    @app.delete("/api/sources/<sid>")
    def api_source_delete(sid):
        store.delete_source(sid)
        return jsonify({"ok": True})

    @app.put("/api/sources/<sid>/profile")
    def api_profile(sid):
        b = body()
        if "cam_rect" in b and b["cam_rect"] is not None:
            r = b["cam_rect"]
            if not (isinstance(r, list) and len(r) == 4 and all(isinstance(x, (int, float)) for x in r)):
                return bad("Неверная рамка")
        try:
            s = store.update_profile(sid, b)
        except KeyError:
            return bad("Источник не найден", 404)
        return jsonify(full_profile(s))

    # ------------------------------------------------------------ API: файлы
    @app.get("/api/folders")
    def api_folders():
        root = store.media_root()
        root.mkdir(parents=True, exist_ok=True)
        return jsonify({"root": str(root), "folders": sorted(p.name for p in root.iterdir()
                                                             if p.is_dir() and not p.name.startswith("."))})

    @app.get("/api/files")
    def api_files():
        sid = request.args.get("source", "")
        src = store.get_source(sid)
        folder = src["folder"] if src else "_uploads"
        try:
            files = store.list_files(folder)
        except PermissionError:
            return bad("Папка вне общей папки", 403)
        st = load_state()
        used = st["used_files"].get(sid, [])
        for f in files:
            f["used"] = f["path"] in used
        return jsonify({"folder": folder, "abs": str(store.media_root() / folder), "files": files})

    @app.get("/api/probe")
    def api_probe():
        try:
            p = store.safe_media_path(request.args.get("file", ""))
            info = probe(p)
        except PermissionError:
            return bad("Нет доступа", 403)
        except Exception as e:
            return bad(str(e))
        srt = p.with_suffix(".srt").is_file() or any(p.parent.glob(p.stem + ".*.srt"))
        info["has_srt"] = srt
        return jsonify(info)

    @app.get("/media/<path:rel>")
    def media(rel):
        try:
            p = store.safe_media_path(rel)
        except PermissionError:
            abort(403)
        if not p.is_file() or p.suffix.lower() not in VIDEO_EXTS:
            abort(404)
        mt = "video/mp4" if p.suffix.lower() in (".mp4", ".m4v", ".mov") else None
        return send_file(p, mimetype=mt, conditional=True, max_age=0)

    @app.get("/api/frame")
    def api_frame():
        rel = request.args.get("file", "")
        try:
            t = max(0.0, float(request.args.get("t", 0)))
            w = min(1280, max(160, int(request.args.get("w", 720))))
            p = store.safe_media_path(rel)
        except (ValueError, PermissionError):
            abort(400)
        if not p.is_file():
            abort(404)
        key = hashlib.sha1(f"{p}|{p.stat().st_mtime}|{t:.2f}|{w}".encode()).hexdigest()[:24]
        fdir = store.data / "cache" / "frames"
        out = fdir / f"{key}.jpg"
        if not out.exists():
            try:
                extract_frame(p, t, out, w)
            except Exception as e:
                return bad(str(e), 500)
            frames = sorted(fdir.glob("*.jpg"), key=lambda f: f.stat().st_mtime)
            for old in frames[:-400]:
                old.unlink(missing_ok=True)
        return send_file(out, mimetype="image/jpeg", max_age=3600)

    @app.post("/api/upload")
    def api_upload():
        f = request.files.get("file")
        if not f or not f.filename:
            return bad("Файл не выбран")
        name = re.sub(r"[^\w.\- ()]+", "_", Path(f.filename).name, flags=re.U).strip() or "video.mp4"
        if Path(name).suffix.lower() not in VIDEO_EXTS:
            return bad("Это не видеофайл")
        src = store.get_source(request.form.get("source_id", ""))
        folder = src["folder"] if src else "_uploads"
        dest = store.safe_media_path(f"{folder}/{name}")
        dest.parent.mkdir(parents=True, exist_ok=True)
        if dest.exists():
            dest = dest.with_name(f"{dest.stem}_{int(time.time())}{dest.suffix}")
        f.save(dest)
        try:
            info = probe(dest)
        except Exception:
            dest.unlink(missing_ok=True)
            return bad("Не удалось прочитать видео")
        limit = int(store.settings().get("upload_max_minutes") or 15) * 60
        if info["duration"] > limit:
            dest.unlink(missing_ok=True)
            return bad(f"С телефона можно загружать только короткие видео (до {limit // 60} мин). "
                       "Длинные файлы положите в папку источника.")
        return jsonify({"ok": True, "path": f"{folder}/{dest.name}", "duration": info["duration"]})

    # ------------------------------------------------------------ API: расписание
    def latest_clip_for(source_id: str) -> dict | None:
        for c in pipe.list_clips():
            if c["source_id"] == source_id and not c.get("published") and c.get("exists"):
                return c
        return None

    @app.get("/api/today")
    def api_today():
        cfg = store.sources()
        d = today()
        with _state_lock:
            st = load_state()
            posts = schedule.upcoming(cfg, st, d, 9)
            save_state(st)
        if not posts:
            return jsonify({"empty": True, "today": d.isoformat()})
        cur = posts[0]
        src = store.get_source(cur["source_id"]) or {}
        files = store.list_files(src["folder"]) if src.get("folder") else []
        sugg = schedule.suggest_files(cur["type"], files, st["used_files"].get(cur["source_id"], []))
        due = dt.date.fromisoformat(cur["date"])
        return jsonify({
            "empty": False, "today": d.isoformat(), "current": cur, "due_status": schedule.due_status(due, d),
            "overdue": due < d, "is_due": due <= d, "folder": src.get("folder"), "files_count": len(files),
            "suggest": sugg, "upcoming": posts[1:], "has_youtube": bool(src.get("youtube_channel_id")),
            "has_twitch": bool(src.get("twitch_login")), "clip": latest_clip_for(cur["source_id"]),
            "history": st["history"][-5:][::-1],
        })

    @app.post("/api/schedule/<action>")
    def api_schedule_action(action):
        cfg = store.sources()
        d = today()
        b = body()
        with _state_lock:
            st = load_state()
            try:
                if action == "publish":
                    clip = pipe.load_clip(b["clip_id"]) if b.get("clip_id") else None
                    p = schedule.publish(cfg, st, d, file=clip["src"] if clip else b.get("file"),
                                         clip_id=clip["id"] if clip else None)
                    if clip:
                        clip["published"] = True
                        pipe.save_clip(clip)
                    msg = f"Отмечено: «{p['source_title']}» пост {p['pos']}/{p['posts_per_block']} опубликован"
                elif action == "skip":
                    p = schedule.skip(cfg, st, d)
                    msg = f"Пост {p['pos']}/{p['posts_per_block']} «{p['source_title']}» пропущен"
                elif action == "postpone":
                    nd = schedule.postpone(cfg, st, d, int(b.get("days") or 1))
                    msg = f"Перенесено на {nd}"
                elif action == "undo":
                    last = schedule.undo(st)
                    msg = "Последнее действие отменено" if last else "Нечего отменять"
                else:
                    return bad("Неизвестное действие", 404)
            except ValueError as e:
                return bad(str(e))
            save_state(st)
        return jsonify({"ok": True, "message": msg})

    @app.get("/api/feed/<sid>")
    def api_feed(sid):
        src = store.get_source(sid)
        if not src:
            return bad("Источник не найден", 404)
        out = {}
        if src.get("youtube_channel_id"):
            out["youtube"] = feeds.youtube_latest(src["youtube_channel_id"])
        if src.get("twitch_login"):
            out["twitch"] = feeds.twitch_latest(src["twitch_login"])
        return jsonify(out)

    # ------------------------------------------------------------ API: задачи
    def public_job(j: dict) -> dict:
        return {k: v for k, v in j.items() if k != "trace"}

    @app.get("/api/jobs")
    def api_jobs():
        return jsonify([public_job(j) for j in queue.list()])

    @app.get("/api/jobs/<jid>")
    def api_job(jid):
        j = queue.get(jid)
        return jsonify(public_job(j)) if j else bad("Задача не найдена", 404)

    @app.post("/api/jobs")
    def api_job_submit():
        b = body()
        t = b.get("type")
        p = b.get("params") or {}
        if t == "clip":
            try:
                store.safe_media_path(p.get("file", ""))
                start, end = float(p["start"]), float(p["end"])
            except (KeyError, TypeError, ValueError, PermissionError):
                return bad("Укажите файл, начало и конец")
            if end - start < 1:
                return bad("Конец должен быть хотя бы на 1 секунду позже начала")
            src = store.get_source(p.get("source_id") or "")
            title = f"Клип: {src['title'] if src else Path(p['file']).name} ({_fmt(start)}–{_fmt(end)})"
        elif t == "moments":
            if not p.get("file"):
                return bad("Не выбран файл")
            title = f"Поиск моментов: {Path(p['file']).name}"
        elif t == "rebuild":
            m = pipe.load_clip(p.get("clip_id", ""))
            if not m:
                return bad("Клип не найден", 404)
            title = f"Пересборка: {m['filename']}"
        else:
            return bad("Неизвестный тип задачи")
        return jsonify(public_job(queue.submit(t, p, title)))

    @app.post("/api/jobs/<jid>/cancel")
    def api_job_cancel(jid):
        return jsonify({"ok": queue.cancel(jid)})

    # ------------------------------------------------------------ API: клипы
    @app.get("/api/clips")
    def api_clips():
        return jsonify(pipe.list_clips())

    @app.get("/api/clips/<cid>")
    def api_clip(cid):
        m = pipe.load_clip(cid)
        if not m:
            return bad("Клип не найден", 404)
        m.pop("profile", None)
        m["exists"] = Path(m["mp4"]).is_file()
        m["url"] = f"/clips/{cid}.mp4?v={m.get('version', 0)}"
        m["gallery_path"] = m.pop("mp4")
        return jsonify(m)

    @app.post("/api/clips/<cid>/rebuild")
    def api_clip_rebuild(cid):
        m = pipe.load_clip(cid)
        if not m:
            return bad("Клип не найден", 404)
        b = body()
        params = {"clip_id": cid, **{k: b[k] for k in ("lines", "mirror", "sub_style", "subtitles") if k in b}}
        return jsonify(public_job(queue.submit("rebuild", params, f"Пересборка: {m['filename']}")))

    @app.delete("/api/clips/<cid>")
    def api_clip_delete(cid):
        pipe.delete_clip(cid)
        return jsonify({"ok": True})

    @app.get("/clips/<cid>.mp4")
    def clip_file(cid):
        m = pipe.load_clip(cid)
        if not m or not Path(m["mp4"]).is_file():
            abort(404)
        dl = request.args.get("download") == "1"
        return send_file(m["mp4"], mimetype="video/mp4", conditional=True, as_attachment=dl,
                         download_name=m["filename"], max_age=0)

    # ------------------------------------------------------------ API: автоопределение вебкамеры
    @app.post("/api/facedetect")
    def api_facedetect():
        b = body()
        try:
            p = store.safe_media_path(b.get("file", ""))
            info = probe(p)
        except Exception:
            return bad("Файл не найден")
        if not facedetect.available():
            return jsonify({"ok": False, "error": "OpenCV не установлен на этом устройстве — задайте рамку вручную"})
        t0 = float(b.get("t") or 0)
        dur = info["duration"] or 1
        times = sorted({min(dur - 0.1, max(0.0, t0 + k)) for k in (-20, -10, 0, 10, 20)})
        frames = []
        for i, t in enumerate(times):
            out = store.data / "cache" / "face" / f"f{i}.jpg"
            try:
                frames.append(extract_frame(p, t, out, 960))
            except Exception:
                pass
        return jsonify(facedetect.detect_cam_rect(frames))

    return app


def _fmt(t: float) -> str:
    t = int(t)
    return f"{t // 3600}:{t % 3600 // 60:02d}:{t % 60:02d}" if t >= 3600 else f"{t // 60}:{t % 60:02d}"


def serve(host: str = "127.0.0.1", port: int = 8080) -> None:
    token = os.environ.get("CLIPPER_TOKEN", "").strip()
    problem = bind_problem(host, token)
    if problem:
        print(problem, file=sys.stderr)
        sys.exit(2)
    app = create_app(token=token)
    shown = "127.0.0.1" if host in ("0.0.0.0", "::") else host
    print(f"\n  Сервер запущен. Откройте в браузере: http://{shown}:{port}")
    if not is_loopback(host):
        print("  Доступ по сети включён, вход по паролю (CLIPPER_TOKEN).")
    print("  Чтобы остановить: нажмите Ctrl+C (в Termux: кнопка CTRL, затем C).\n", flush=True)
    try:
        from waitress import serve as wserve
        wserve(app, host=host, port=port, threads=8, channel_timeout=600, ident="tiktok-clipper")
    except ImportError:
        app.run(host=host, port=port, threaded=True)
