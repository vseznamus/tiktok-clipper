"""Фоновая очередь тяжёлых задач с сохранением на диск.

Задачи выполняются по одной (телефону хватает одной нарезки за раз). Состояние пишется
в data/jobs.json, поэтому закрытая вкладка или погасший экран ничего не теряют, а после
перезапуска сервера незавершённые задачи запускаются заново.
"""
from __future__ import annotations

import threading
import time
import traceback
import uuid
from pathlib import Path

from . import termux
from .config import read_json, write_json
from .media import Cancelled

KEEP_FINISHED = 60


class JobContext:
    def __init__(self, queue: "JobQueue", job: dict, cancel: threading.Event):
        self._q, self.job, self.cancel = queue, job, cancel
        self.params = job["params"]

    def progress(self, frac: float, stage: str | None = None) -> None:
        self._q._update(self.job["id"], progress=round(max(0.0, min(1.0, frac)), 4),
                        **({"stage": stage} if stage else {}))

    def stage_progress(self, lo: float, hi: float, stage: str | None = None):
        """Функция прогресса для подэтапа, отображающая 0..1 в диапазон lo..hi."""
        if stage:
            self.progress(lo, stage)
        return lambda f: self.progress(lo + (hi - lo) * f)

    def warn(self, msg: str) -> None:
        with self._q._lock:
            w = self.job.setdefault("warnings", [])
            if msg not in w:
                w.append(msg)
        self._q._save(force=True)

    def check(self) -> None:
        if self.cancel.is_set():
            raise Cancelled("Задача отменена")


class JobQueue:
    def __init__(self, path: Path, handlers: dict):
        self.path = Path(path)
        self.handlers = handlers
        self._lock = threading.RLock()
        self._wake = threading.Event()
        self._cancel: dict[str, threading.Event] = {}
        self._last_save = 0.0
        self._stop = False
        self.jobs: list[dict] = read_json(self.path, [])
        for j in self.jobs:
            if j["status"] == "running":
                j["status"] = "queued"
                j["progress"] = 0
                j.setdefault("warnings", []).append("Задача была прервана (сервер остановился) и запущена заново.")
        self._save(force=True)
        self._thread: threading.Thread | None = None

    # --- хранение
    def _save(self, force: bool = False) -> None:
        with self._lock:
            now = time.time()
            if not force and now - self._last_save < 0.7:
                return
            self._last_save = now
            done = [j for j in self.jobs if j["status"] in ("done", "error", "canceled")]
            if len(done) > KEEP_FINISHED:
                drop = {j["id"] for j in done[:-KEEP_FINISHED]}
                self.jobs = [j for j in self.jobs if j["id"] not in drop]
            write_json(self.path, self.jobs)

    def _update(self, job_id: str, **fields) -> None:
        with self._lock:
            j = self.get(job_id)
            if j:
                j.update(fields)
        self._save(force="status" in fields)

    # --- API
    def submit(self, type_: str, params: dict, title: str) -> dict:
        if type_ not in self.handlers:
            raise ValueError("Неизвестный тип задачи")
        job = {"id": uuid.uuid4().hex[:12], "type": type_, "title": title, "params": params,
               "status": "queued", "progress": 0.0, "stage": "В очереди", "warnings": [],
               "result": None, "error": None, "created": time.time(), "started": None, "finished": None}
        with self._lock:
            self.jobs.append(job)
        self._save(force=True)
        self._wake.set()
        return job

    def get(self, job_id: str) -> dict | None:
        with self._lock:
            return next((j for j in self.jobs if j["id"] == job_id), None)

    def list(self) -> list[dict]:
        with self._lock:
            return [dict(j) for j in reversed(self.jobs)]

    def cancel(self, job_id: str) -> bool:
        with self._lock:
            j = self.get(job_id)
            if not j or j["status"] not in ("queued", "running"):
                return False
            if j["status"] == "queued":
                j.update(status="canceled", stage="Отменено", finished=time.time())
            else:
                ev = self._cancel.get(job_id)
                if ev:
                    ev.set()
        self._save(force=True)
        return True

    def start(self) -> None:
        if self._thread is None:
            self._thread = threading.Thread(target=self._worker, name="clipper-jobs", daemon=True)
            self._thread.start()

    def stop(self) -> None:
        self._stop = True
        self._wake.set()

    def wait_idle(self, timeout: float = 120) -> bool:
        end = time.time() + timeout
        while time.time() < end:
            with self._lock:
                if not any(j["status"] in ("queued", "running") for j in self.jobs):
                    return True
            time.sleep(0.1)
        return False

    # --- исполнитель
    def _next(self) -> dict | None:
        with self._lock:
            return next((j for j in self.jobs if j["status"] == "queued"), None)

    def _worker(self) -> None:
        while not self._stop:
            job = self._next()
            if job is None:
                termux.wake_unlock()
                self._wake.wait(timeout=2)
                self._wake.clear()
                continue
            termux.wake_lock()
            ev = threading.Event()
            with self._lock:
                self._cancel[job["id"]] = ev
                job.update(status="running", started=time.time(), stage="Начинаю…", progress=0.0, error=None)
            self._save(force=True)
            ctx = JobContext(self, job, ev)
            try:
                result = self.handlers[job["type"]](ctx)
                self._update(job["id"], status="done", progress=1.0, stage="Готово", result=result,
                             finished=time.time())
            except Cancelled:
                self._update(job["id"], status="canceled", stage="Отменено", finished=time.time())
            except Exception as e:  # ошибка одной задачи не должна останавливать очередь
                msg = str(e) or type(e).__name__
                self._update(job["id"], status="error", stage="Ошибка", error=msg,
                             trace=traceback.format_exc()[-2000:], finished=time.time())
            finally:
                with self._lock:
                    self._cancel.pop(job["id"], None)
