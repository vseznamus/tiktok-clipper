package ru.tiktokclipper.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Фоновая очередь задач с сохранением на диск (перенос tclip/jobs.py).
 * Задачи выполняются по одной; после перезапуска незавершённые выполняются заново.
 */
public class JobQueue {
    public interface Handler {
        JSONObject run(Ctx ctx) throws Exception;
    }

    /** Слушатель для уведомления службы: начало/конец работы и прогресс. */
    public interface Listener {
        void onBusy(boolean busy, String title, double progress, String stage);
    }

    public static class Cancelled extends Exception {
        public Cancelled() {
            super("Задача отменена");
        }
    }

    public class Ctx {
        public final JSONObject job;
        public final JSONObject params;
        public final AtomicBoolean cancel = new AtomicBoolean(false);

        Ctx(JSONObject job) {
            this.job = job;
            this.params = job.optJSONObject("params");
        }

        public void progress(double frac, String stage) {
            synchronized (lock) {
                try {
                    job.put("progress", Math.round(Math.max(0, Math.min(1, frac)) * 10000) / 10000.0);
                    if (stage != null) job.put("stage", stage);
                } catch (JSONException ignored) {
                }
            }
            save(false);
            if (listener != null) listener.onBusy(true, job.optString("title"), frac, job.optString("stage"));
        }

        /** Только подпись этапа (прогресс не меняется): «Распознаю речь · 1:20». */
        public void note(String stage) {
            synchronized (lock) {
                try {
                    job.put("stage", stage);
                } catch (JSONException ignored) {
                }
            }
            save(false);
            if (listener != null) listener.onBusy(true, job.optString("title"), job.optDouble("progress", 0), stage);
        }

        /** Прогресс подэтапа: 0..1 отображается в диапазон lo..hi. */
        public Progress stage(final double lo, final double hi, String stage) {
            if (stage != null) progress(lo, stage);
            return f -> progress(lo + (hi - lo) * f, null);
        }

        public void warn(String msg) {
            synchronized (lock) {
                JSONArray w = job.optJSONArray("warnings");
                if (!Store.contains(w, msg)) w.put(msg);
            }
            save(true);
        }

        /** Копия предупреждений задачи (под блокировкой). */
        public JSONArray warnings() {
            synchronized (lock) {
                try {
                    return new JSONArray(job.optJSONArray("warnings").toString());
                } catch (JSONException e) {
                    return new JSONArray();
                }
            }
        }

        public void check() throws Cancelled {
            if (cancel.get()) throw new Cancelled();
        }
    }

    public interface Progress {
        void set(double frac);
    }

    private static final int KEEP_FINISHED = 60;
    final Object lock = new Object();
    private final File file;
    private final Map<String, Handler> handlers;
    private final Map<String, Ctx> running = new ConcurrentHashMap<>();
    private JSONArray jobs;
    private long lastSave;
    private volatile boolean stop;
    private Thread thread;
    private volatile Listener listener;

    public JobQueue(File file, Map<String, Handler> handlers) {
        this.file = file;
        this.handlers = handlers;
        this.jobs = Store.readArray(file);
        try {
            for (int i = 0; i < jobs.length(); i++) {
                JSONObject j = jobs.getJSONObject(i);
                if ("running".equals(j.optString("status"))) {
                    j.put("status", "queued");
                    j.put("progress", 0);
                    j.getJSONArray("warnings").put("Задача была прервана (приложение закрылось) и запущена заново.");
                }
            }
        } catch (JSONException e) {
            jobs = new JSONArray();
        }
        save(true);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    void save(boolean force) {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            if (!force && now - lastSave < 700) return;
            lastSave = now;
            int done = 0;
            for (int i = 0; i < jobs.length(); i++) if (finished(jobs.optJSONObject(i))) done++;
            JSONArray keep = new JSONArray();
            int toDrop = Math.max(0, done - KEEP_FINISHED);
            for (int i = 0; i < jobs.length(); i++) {
                JSONObject j = jobs.optJSONObject(i);
                if (toDrop > 0 && finished(j)) {
                    toDrop--;
                    continue;
                }
                keep.put(j);
            }
            jobs = keep;
            Store.writeJson(file, jobs);
        }
    }

    private static boolean finished(JSONObject j) {
        String s = j.optString("status");
        return s.equals("done") || s.equals("error") || s.equals("canceled");
    }

    public JSONObject submit(String type, JSONObject params, String title) throws ApiError {
        if (!handlers.containsKey(type)) throw new ApiError(400, "Неизвестный тип задачи");
        JSONObject j = new JSONObject();
        try {
            j.put("id", UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            j.put("type", type);
            j.put("title", title);
            j.put("params", params);
            j.put("status", "queued");
            j.put("progress", 0.0);
            j.put("stage", "В очереди");
            j.put("warnings", new JSONArray());
            j.put("result", JSONObject.NULL);
            j.put("error", JSONObject.NULL);
            j.put("created", System.currentTimeMillis() / 1000.0);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        JSONObject snapshot;
        synchronized (lock) {
            jobs.put(j);
            snapshot = copy(j); // копия под блокировкой: рабочий поток может сразу начать менять задачу
            lock.notifyAll();
        }
        save(true);
        return snapshot;
    }

    public JSONObject get(String id) {
        synchronized (lock) {
            for (int i = 0; i < jobs.length(); i++) {
                JSONObject j = jobs.optJSONObject(i);
                if (id.equals(j.optString("id"))) return copy(j);
            }
            return null;
        }
    }

    private JSONObject find(String id) {
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject j = jobs.optJSONObject(i);
            if (id.equals(j.optString("id"))) return j;
        }
        return null;
    }

    public JSONArray list() {
        synchronized (lock) {
            JSONArray out = new JSONArray();
            for (int i = jobs.length() - 1; i >= 0; i--) out.put(copy(jobs.optJSONObject(i)));
            return out;
        }
    }

    public int activeCount() {
        synchronized (lock) {
            int n = 0;
            for (int i = 0; i < jobs.length(); i++) {
                String s = jobs.optJSONObject(i).optString("status");
                if (s.equals("queued") || s.equals("running")) n++;
            }
            return n;
        }
    }

    public boolean cancel(String id) {
        synchronized (lock) {
            JSONObject j = find(id);
            if (j == null) return false;
            String s = j.optString("status");
            try {
                if (s.equals("queued")) {
                    j.put("status", "canceled");
                    j.put("stage", "Отменено");
                } else if (s.equals("running")) {
                    Ctx c = running.get(id);
                    if (c != null) c.cancel.set(true);
                } else {
                    return false;
                }
            } catch (JSONException ignored) {
            }
        }
        save(true);
        return true;
    }

    private static JSONObject copy(JSONObject j) {
        try {
            return new JSONObject(j.toString());
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized void start() {
        if (thread != null) return;
        thread = new Thread(this::loop, "clipper-jobs");
        thread.start();
    }

    public void stop() {
        stop = true;
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    public boolean waitIdle(long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (activeCount() == 0) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private void set(JSONObject j, String k, Object v) {
        synchronized (lock) {
            try {
                j.put(k, v);
            } catch (JSONException ignored) {
            }
        }
    }

    private void loop() {
        while (!stop) {
            JSONObject job = null;
            synchronized (lock) {
                for (int i = 0; i < jobs.length(); i++) {
                    JSONObject j = jobs.optJSONObject(i);
                    if ("queued".equals(j.optString("status"))) {
                        job = j;
                        break;
                    }
                }
                if (job == null) {
                    if (listener != null) listener.onBusy(false, null, 0, null);
                    try {
                        lock.wait(2000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
                set(job, "status", "running");
                set(job, "started", System.currentTimeMillis() / 1000.0);
                set(job, "stage", "Начинаю…");
                set(job, "progress", 0.0);
            }
            save(true);
            Ctx ctx = new Ctx(job);
            running.put(job.optString("id"), ctx);
            if (listener != null) listener.onBusy(true, job.optString("title"), 0, "Начинаю…");
            try {
                JSONObject res = handlers.get(job.optString("type")).run(ctx);
                set(job, "result", res == null ? JSONObject.NULL : res);
                set(job, "progress", 1.0);
                set(job, "stage", "Готово");
                set(job, "status", "done");
            } catch (Cancelled e) {
                set(job, "stage", "Отменено");
                set(job, "status", "canceled");
            } catch (Throwable e) { // ошибка одной задачи не останавливает очередь
                String msg = e.getMessage() == null || e.getMessage().isEmpty() ? e.getClass().getSimpleName() : e.getMessage();
                set(job, "error", msg);
                set(job, "stage", "Ошибка");
                set(job, "status", "error");
            } finally {
                set(job, "finished", System.currentTimeMillis() / 1000.0);
                running.remove(job.optString("id"));
                save(true);
            }
        }
    }
}
