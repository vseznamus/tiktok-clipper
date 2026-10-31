package ru.tiktokclipper.app;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import ru.tiktokclipper.core.ApiError;
import ru.tiktokclipper.core.JobQueue;
import ru.tiktokclipper.core.Store;
import ru.tiktokclipper.core.Subs;

/** Распознавание речи офлайн моделью Whisper (whisper.cpp через JNI): слова с таймкодами и знаками препинания. */
final class WhisperAsr {
    private WhisperAsr() {
    }

    interface Listener {
        /** Прогресс 0–100 (или −1 — «ещё жив»); false — отменить. Вызывается из нативного кода. */
        boolean progress(int percent);
    }

    private static native String nativeInitBackends(String libDir);

    private static native long nativeLoad(String path);

    private static native void nativeFree(long handle);

    private static native byte[] nativeTranscribe(long handle, float[] pcm, String lang, int threads, Listener l);

    /** Прервать идущее распознавание (из любого потока). */
    private static native void nativeAbort();

    private static volatile Boolean libOk;
    private static String libError = "", sysInfo = "";
    /** Отдельная блокировка для загрузки библиотеки: распознавание (минуты) держит WhisperAsr.class,
     *  и проверка «доступен ли Whisper» из экрана настроек не должна его ждать. */
    private static final Object LIB = new Object();

    /** Загружает нативную библиотеку один раз. false — Whisper на этом телефоне недоступен. */
    static boolean available(Context ctx) {
        Boolean ok = libOk;
        if (ok != null) return ok;
        synchronized (LIB) {
            return load(ctx);
        }
    }

    private static boolean load(Context ctx) {
        if (libOk != null) return libOk;
        try {
            System.loadLibrary("narezka_whisper");
            sysInfo = nativeInitBackends(ctx.getApplicationInfo().nativeLibraryDir);
            Log.i("narezka", "whisper: " + sysInfo);
            libOk = true;
        } catch (Throwable t) {
            libError = String.valueOf(t.getMessage());
            Log.w("narezka", "whisper недоступен", t);
            libOk = false;
        }
        return libOk;
    }

    static String unavailableReason() {
        return "Whisper работает только на 64-битных телефонах (ARM64). Выберите модель Vosk." +
                (libError.isEmpty() ? "" : " [" + libError + "]");
    }

    static File file(Store s, AsrModels.ModelInfo m) {
        String name = m.url.substring(m.url.lastIndexOf('/') + 1);
        return new File(new File(new File(s.dir, "models"), "whisper"), name);
    }

    static boolean isInstalled(Store s, AsrModels.ModelInfo m) {
        File f = file(s, m);
        return f.isFile() && f.length() > 10L * 1024 * 1024;
    }

    static void download(Store s, AsrModels.ModelInfo mi, JobQueue.Ctx ctx) throws Exception {
        File dst = file(s, mi), part = new File(dst.getPath() + ".part");
        dst.getParentFile().mkdirs();
        JobQueue.Progress p = ctx.stage(0, 0.97, "Скачиваю модель «" + mi.name + "»");
        // .part остаётся после обрыва — докачиваем с того же места
        AsrModels.fetchWithResume(mi.url, part, mi.sizeMb * 1024L * 1024L, p, ctx);
        ctx.progress(0.98, "Проверяю файл модели");
        if (!isGgml(part)) {
            part.delete();
            throw new ApiError(502, "Скачался не тот файл (сайт моделей вернул ошибку). Попробуйте ещё раз позже.");
        }
        dst.delete();
        if (!part.renameTo(dst)) throw new IOException("Не удалось сохранить модель");
    }

    static void delete(Store s, AsrModels.ModelInfo mi) {
        synchronized (WhisperAsr.class) {
            if (mi.id.equals(cachedId)) {
                nativeFree(cached);
                cached = 0;
                cachedId = null;
            }
        }
        File f = file(s, mi);
        f.delete();
        new File(f.getPath() + ".part").delete();
    }

    /** Файл модели whisper.cpp начинается с «lmgg» (0x67676d6c). */
    static boolean isGgml(File f) {
        byte[] b = new byte[4];
        try (InputStream in = new FileInputStream(f)) {
            if (in.read(b) != 4) return false;
        } catch (IOException e) {
            return false;
        }
        return b[0] == 'l' && b[1] == 'm' && b[2] == 'g' && b[3] == 'g';
    }

    private static long cached;
    private static String cachedId;

    private static long model(Store s, AsrModels.ModelInfo m) throws ApiError {
        if (cached != 0 && m.id.equals(cachedId)) return cached;
        if (cached != 0) nativeFree(cached);
        cached = 0;
        cachedId = null;
        long h = nativeLoad(file(s, m).getPath());
        if (h == 0) {
            throw new ApiError(500, "Не удалось открыть модель «" + m.name + "»: файл повреждён или не хватает памяти. "
                    + "Удалите модель в настройках и скачайте снова или выберите модель поменьше.");
        }
        cached = h;
        cachedId = m.id;
        return h;
    }

    /** Сколько потоков: по числу быстрых ядер (у телефонов часть ядер — медленные «энергосберегающие»). */
    static int threads() {
        int n = Runtime.getRuntime().availableProcessors();
        long[] f = new long[n];
        long max = 0;
        for (int i = 0; i < n; i++) {
            f[i] = readLong("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
            max = Math.max(max, f[i]);
        }
        int fast = 0;
        for (long v : f) if (max > 0 && v >= max * 0.7) fast++;
        if (fast == 0) fast = n / 2;
        return Math.max(2, Math.min(6, Math.max(fast, Math.min(4, n))));
    }

    private static long readLong(String path) {
        try (InputStream in = new FileInputStream(path)) {
            byte[] b = new byte[32];
            int k = in.read(b);
            return k > 0 ? Long.parseLong(new String(b, 0, k, StandardCharsets.US_ASCII).trim()) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Окно, которым режется длинный звук (чтобы не держать в памяти час аудио). */
    static final int CHUNK_SEC = 300;

    /** Большой модели нужно много памяти: на телефоне с меньшим объёмом она не загрузится или будет «висеть». */
    static final long TURBO_MIN_RAM = 7L * 1024 * 1024 * 1024;

    static long totalRam(Context ctx) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.totalMem;
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    static String mmss(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    static List<Subs.Word> transcribe(Context ctx, Store s, Uri uri, long startUs, long endUs, String modelId,
                                      String language, final AtomicBoolean cancel, final JobQueue.Progress prog,
                                      final java.util.function.Consumer<String> note) throws Exception {
        if (!available(ctx)) throw new ApiError(500, unavailableReason());
        AsrModels.ModelInfo m = AsrModels.info(modelId);
        if ("whisper-turbo".equals(modelId) && totalRam(ctx) < TURBO_MIN_RAM) {
            throw new ApiError(400, "Whisper large-v3-turbo нужно от 8 ГБ памяти, а у телефона меньше — модель не потянет. "
                    + "Выберите Whisper small: «Настройки» → «Распознавание речи» → «Модель по умолчанию».");
        }
        final String label = "Whisper " + modelId.replace("whisper-", "");
        // наблюдатель: отмена доходит до Whisper сразу, а пока считается окно — подпись с таймером
        final long t0 = System.currentTimeMillis();
        final boolean[] finished = {false};
        Thread watch = new Thread(() -> {
            long lastNote = 0;
            while (!finished[0]) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    return;
                }
                if (cancel.get()) nativeAbort();
                long now = System.currentTimeMillis();
                if (note != null && now - lastNote >= 2000 && !finished[0]) {
                    lastNote = now;
                    note.accept("Распознаю речь (" + label + ") · " + mmss(now - t0)
                            + ("whisper-turbo".equals(modelId) ? " — большая модель, первое окно может идти несколько минут" : ""));
                }
            }
        }, "whisper-watch");
        watch.setDaemon(true);
        watch.start();
        try {
            return transcribeLocked(ctx, s, uri, startUs, endUs, m, language, cancel, prog, note, label);
        } finally {
            finished[0] = true;
            watch.interrupt();
        }
    }

    private static List<Subs.Word> transcribeLocked(Context ctx, Store s, Uri uri, long startUs, long endUs, AsrModels.ModelInfo m,
                                                    String language, final AtomicBoolean cancel, final JobQueue.Progress prog,
                                                    java.util.function.Consumer<String> note, String label) throws Exception {
        final double total = Math.max(1, (endUs - startUs) / 1e6);
        final FloatBuf pcm = new FloatBuf(16000 * (int) Math.min(total + 1, CHUNK_SEC + 1));
        final List<Subs.Word> words = new ArrayList<>();
        final String lang = language == null || language.isEmpty() ? "auto" : language;
        final int threads = threads();
        final double[] done = {0}; // секунд уже распознано
        synchronized (WhisperAsr.class) {
            if (note != null && !m.id.equals(cachedId)) note.accept("Загружаю модель " + label + " в память…");
            final long h = model(s, m);
            if (cancel.get()) throw new JobQueue.Cancelled();
            AudioPipe.pcm16kMonoFloat(ctx, uri, startUs, endUs, (data, n) -> {
                pcm.add(data, n);
                if (pcm.n >= 16000 * CHUNK_SEC) {
                    run(h, pcm, done, lang, threads, total, cancel, prog, words);
                }
            }, cancel, frac -> prog.set(0.1 * frac * Math.min(1, CHUNK_SEC / total)));
            if (pcm.n > 16000 / 2) run(h, pcm, done, lang, threads, total, cancel, prog, words);
        }
        return Subs.cleanWords(words);
    }

    private static void run(long h, FloatBuf pcm, double[] done, String lang, int threads, final double total,
                            final AtomicBoolean cancel, final JobQueue.Progress prog, List<Subs.Word> out) throws Exception {
        final double off = done[0], len = pcm.n / 16000.0;
        float[] chunk = pcm.take();
        byte[] res = nativeTranscribe(h, chunk, lang, threads, pct -> {
            if (pct >= 0) prog.set(Math.min(1, 0.1 + 0.9 * (off + len * pct / 100.0) / total));
            return !cancel.get();
        });
        if (cancel.get()) throw new JobQueue.Cancelled();
        if (res == null) throw new ApiError(500, "Whisper не смог распознать речь (не хватило памяти?). Попробуйте модель поменьше.");
        out.addAll(parse(new String(res, StandardCharsets.UTF_8), off));
        done[0] = off + len;
    }

    /** Разбор ответа нативной части (формат — в whisper_jni.c). Время сдвигается на offset секунд. */
    static List<Subs.Word> parse(String text, double offset) {
        Map<Integer, double[]> segs = new LinkedHashMap<>();
        Map<Integer, List<Subs.Word>> segWords = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            String[] f = line.split("\t", 5);
            try {
                if (f[0].equals("S") && f.length >= 4) {
                    int i = Integer.parseInt(f[1]);
                    segs.put(i, new double[]{Long.parseLong(f[2]) / 100.0, Long.parseLong(f[3]) / 100.0});
                    segWords.put(i, new ArrayList<Subs.Word>());
                } else if (f[0].equals("W") && f.length == 5) {
                    List<Subs.Word> l = segWords.get(Integer.parseInt(f[1]));
                    String w = f[4].trim();
                    if (l != null && !w.isEmpty()) {
                        l.add(new Subs.Word(w, Long.parseLong(f[2]) / 100.0, Long.parseLong(f[3]) / 100.0));
                    }
                }
            } catch (NumberFormatException ignored) {
                // повреждённая строка — пропускаем
            }
        }
        List<Subs.Word> out = new ArrayList<>();
        for (Map.Entry<Integer, List<Subs.Word>> e : segWords.entrySet()) {
            List<Subs.Word> ws = e.getValue();
            if (ws.isEmpty()) continue;
            StringBuilder sb = new StringBuilder();
            for (Subs.Word w : ws) sb.append(w.w).append(' ');
            if (Subs.isHallucination(sb.toString())) continue;
            double[] st = segs.get(e.getKey());
            fixTimes(ws, st[0], st[1]);
            for (Subs.Word w : ws) {
                w.s += offset;
                w.e += offset;
                out.add(w);
            }
        }
        return out;
    }

    /**
     * Время слов внутри сегмента: в границах сегмента, по порядку, без нулевой длины.
     * Если время токенов не получилось (все слова в одной точке), делим сегмент по длине слов.
     */
    static void fixTimes(List<Subs.Word> ws, double t0, double t1) {
        if (t1 <= t0) t1 = t0 + 0.3 * ws.size();
        double span = t1 - t0, covered = 0;
        for (Subs.Word w : ws) covered += Math.max(0, Math.min(w.e, t1) - Math.max(w.s, t0));
        if (covered < 0.3 * span) {
            int chars = 0;
            for (Subs.Word w : ws) chars += w.w.length() + 1;
            double t = t0;
            for (Subs.Word w : ws) {
                double d = span * (w.w.length() + 1) / chars;
                w.s = t;
                w.e = t + d;
                t += d;
            }
            return;
        }
        double prev = t0;
        for (Subs.Word w : ws) {
            double s = Math.max(prev, Math.min(Math.max(w.s, t0), t1));
            double e = Math.min(t1, Math.max(w.e, s + 0.05));
            w.s = s;
            w.e = Math.max(e, s);
            prev = w.e;
        }
    }

    /** Растущий буфер float без лишних копий на каждый кусок звука. */
    static final class FloatBuf {
        float[] a;
        int n;

        FloatBuf(int cap) {
            a = new float[Math.max(16000, cap)];
        }

        void add(float[] d, int k) {
            if (n + k > a.length) {
                float[] b = new float[Math.max(n + k, a.length * 2)];
                System.arraycopy(a, 0, b, 0, n);
                a = b;
            }
            System.arraycopy(d, 0, a, n, k);
            n += k;
        }

        float[] take() {
            float[] r = new float[n];
            System.arraycopy(a, 0, r, 0, n);
            n = 0;
            return r;
        }
    }
}
