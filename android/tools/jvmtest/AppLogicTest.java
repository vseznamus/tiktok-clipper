package ru.tiktokclipper.app;

import org.json.JSONArray;
import org.json.JSONObject;

import ru.tiktokclipper.core.Store;

/**
 * Проверка «чистой» логики Android-части на JVM: ресемплер, сведение каналов, геометрия раскладок.
 * Геометрию сравниваем с эталоном из Python-версии (tclip/render.py), переданным в аргументах.
 */
public class AppLogicTest {
    static int passed, failed;

    static void check(boolean c, String m) {
        if (!c) throw new AssertionError(m);
    }

    interface T { void run() throws Exception; }

    static void test(String n, T t) {
        try {
            t.run();
            passed++;
            System.out.println("✔ " + n);
        } catch (Throwable e) {
            failed++;
            System.out.println("✘ " + n + ": " + e);
        }
    }

    static double freqByZeroCross(float[] s, int ch, int rate) {
        int z = 0;
        for (int i = 1; i < s.length / ch; i++) if ((s[(i - 1) * ch] < 0) != (s[i * ch] < 0)) z++;
        return z / 2.0 / (s.length / ch / (double) rate);
    }

    public static void main(String[] args) throws Exception {
        test("ресемплер 48000→44100: частота тона сохраняется, куски склеиваются без щелчков", () -> {
            int in = 48000, n = in * 2;
            float[] src = new float[n * 2];
            for (int i = 0; i < n; i++) {
                float v = (float) Math.sin(2 * Math.PI * 1000 * i / in) * 0.8f;
                src[i * 2] = v;
                src[i * 2 + 1] = v;
            }
            AudioPipe.Resampler whole = new AudioPipe.Resampler(in, 44100, 2);
            float[] a = whole.process(src, n);
            AudioPipe.Resampler chunks = new AudioPipe.Resampler(in, 44100, 2);
            java.util.List<Float> b = new java.util.ArrayList<>();
            int step = 1013;
            for (int off = 0; off < n; off += step) {
                int k = Math.min(step, n - off);
                float[] part = new float[k * 2];
                System.arraycopy(src, off * 2, part, 0, k * 2);
                for (float v : chunks.process(part, k)) b.add(v);
            }
            double f = freqByZeroCross(a, 2, 44100);
            check(Math.abs(f - 1000) < 5, "частота " + f);
            check(Math.abs(a.length / 2 - 88200) < 5, "длина " + a.length / 2);
            check(Math.abs(b.size() / 2 - a.length / 2) <= 2, "кусками другая длина: " + b.size() / 2 + " vs " + a.length / 2);
            double maxJump = 0;
            for (int i = 2; i < b.size(); i += 2) maxJump = Math.max(maxJump, Math.abs(b.get(i) - b.get(i - 2)));
            check(maxJump < 0.2, "скачок между отсчётами " + maxJump); // у тона 1 кГц шаг ≤ 0.12
        });
        test("ресемплер 44100→16000 моно для распознавания", () -> {
            int in = 44100;
            float[] src = new float[in];
            for (int i = 0; i < in; i++) src[i] = (float) Math.sin(2 * Math.PI * 440 * i / in);
            float[] out = new AudioPipe.Resampler(in, 16000, 1).process(src, in);
            check(Math.abs(out.length - 16000) < 3, "длина " + out.length);
            check(Math.abs(freqByZeroCross(out, 1, 16000) - 440) < 3, "частота");
        });
        test("отбор кадров: первый кадр рисуется, 30/60 fps проходят, 120 fps прореживаются", () -> {
            long start = 2281_000_000L, end = 2451_000_000L; // 38:01–40:51, как в отчёте с телефона
            check(VideoRenderer.shouldRender(start, -1, start, end), "первый кадр отброшен");
            check(!VideoRenderer.shouldRender(start - 40_000, -1, start, end), "кадр до начала");
            check(!VideoRenderer.shouldRender(end, start, start, end), "кадр после конца");
            int n30 = 0, n120 = 0;
            long last = -1;
            for (long t = start; t < end; t += 33_333) if (VideoRenderer.shouldRender(t, last, start, end)) { last = t; n30++; }
            last = -1;
            for (long t = start; t < start + 1_000_000; t += 8_333) if (VideoRenderer.shouldRender(t, last, start, end)) { last = t; n120++; }
            check(Math.abs(n30 - 170 * 30) <= 2, "30 fps: " + n30);
            check(n120 >= 55 && n120 <= 61, "120 fps → " + n120 + " кадров/с");
        });
        test("сведение 5.1 → стерео и моно без перегруза", () -> {
            float[] six = {1, 1, 1, 1, 1, 1};
            float[] st = AudioPipe.downmix(six, 0, 1, 6, 2);
            check(st.length == 2 && st[0] <= 1 && st[0] > 0.9, "стерео " + st[0]);
            float[] mono = AudioPipe.downmix(new float[]{0.5f, -0.5f}, 0, 1, 2, 1);
            check(mono.length == 1 && Math.abs(mono[0]) < 1e-6, "моно");
        });

        test("экран не ждёт распознавание: проверка Whisper отвечает, пока идёт транскрипция", () -> {
            Class.forName("ru.tiktokclipper.app.WhisperAsr", true, AppLogicTest.class.getClassLoader()); // в приложении класс уже загружен
            final java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
            Thread busy = new Thread(() -> {
                synchronized (WhisperAsr.class) { // так держит блокировку идущее распознавание
                    held.countDown();
                    try { release.await(); } catch (InterruptedException ignored) { }
                }
            });
            busy.start();
            held.await();
            final boolean[] got = {false};
            Thread probe = new Thread(() -> {
                try {
                    WhisperAsr.available(null); // на JVM нет нативной библиотеки и android.util.Log — важно лишь, что вызов не ждёт
                } catch (Throwable ignored) {
                }
                got[0] = true;
            });
            probe.start();
            probe.join(2000);
            release.countDown();
            busy.join();
            check(got[0], "проверка Whisper ждала окончания распознавания — экраны зависали на «Загрузке»");
        });
        test("Whisper: разбор слов, сдвиг времени, кириллица, отсев «титров»", () -> {
            String raw = "L\tru\n"
                    + "S\t0\t0\t250\t0.010\n"
                    + "W\t0\t0\t60\tПривет,\n"
                    + "W\t0\t60\t130\tкак\n"
                    + "W\t0\t130\t250\tдела?\n"
                    + "S\t1\t300\t500\t0.700\n"
                    + "W\t1\t300\t400\tСубтитры\n"
                    + "W\t1\t400\t450\tсделал\n"
                    + "W\t1\t450\t500\tDimaTorzok\n"
                    + "S\t2\t600\t800\t0.020\n"
                    + "W\t2\t700\t700\tНу\n"
                    + "W\t2\t700\t700\tладно.\n"
                    + "мусор\tстрока\n";
            java.util.List<ru.tiktokclipper.core.Subs.Word> w = WhisperAsr.parse(raw, 10.0);
            check(w.size() == 5, "слов " + w.size());
            check(w.get(0).w.equals("Привет,") && Math.abs(w.get(0).s - 10.0) < 1e-6 && Math.abs(w.get(0).e - 10.6) < 1e-6, "первое слово");
            check(w.get(2).w.equals("дела?") && Math.abs(w.get(2).e - 12.5) < 1e-6, "знаки препинания и конец");
            // у последнего сегмента время токенов вырождено — слова делят сегмент 6.0–8.0 по длине
            check(Math.abs(w.get(3).s - 16.0) < 1e-6 && Math.abs(w.get(4).e - 18.0) < 1e-6, "раздача времени " + w.get(3).s + " " + w.get(4).e);
            check(w.get(3).e <= w.get(4).s + 1e-9, "порядок");
        });
        test("Whisper: время слов внутри сегмента, по порядку, без нулевой длины", () -> {
            java.util.List<ru.tiktokclipper.core.Subs.Word> ws = new java.util.ArrayList<>();
            ws.add(new ru.tiktokclipper.core.Subs.Word("а", 0.5, 1.5));
            ws.add(new ru.tiktokclipper.core.Subs.Word("б", 1.0, 1.0));
            ws.add(new ru.tiktokclipper.core.Subs.Word("в", 2.5, 9.0));
            WhisperAsr.fixTimes(ws, 1.0, 3.0);
            double prev = 1.0;
            for (ru.tiktokclipper.core.Subs.Word x : ws) {
                check(x.s >= prev - 1e-9 && x.e > x.s && x.e <= 3.0 + 1e-9, x.w + " " + x.s + "–" + x.e);
                prev = x.e;
            }
        });
        test("модели: выбор Whisper/Vosk, проверка файла ggml", () -> {
            java.io.File dir = java.nio.file.Files.createTempDirectory("asr").toFile();
            Store st = new Store(dir);
            JSONObject se = new JSONObject();
            check(AsrModels.pickModel(st, "auto", se) == null, "ничего не скачано");
            java.io.File vosk = new java.io.File(VoskAsr.dir(st, "ru-small"), "conf");
            vosk.mkdirs();
            new java.io.FileOutputStream(new java.io.File(vosk, "model.conf")).close();
            check("ru-small".equals(AsrModels.pickModel(st, "ru", se)), "только Vosk");
            check(AsrModels.pickModel(st, "en", se).equals("ru-small"), "запасной вариант — любая скачанная");
            java.io.File wf = WhisperAsr.file(st, AsrModels.info("whisper-base"));
            wf.getParentFile().mkdirs();
            try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(wf, "rw")) {
                r.write(new byte[]{'l', 'm', 'g', 'g'});
                r.setLength(11L * 1024 * 1024);
            }
            check(WhisperAsr.isGgml(wf), "ggml");
            check("whisper-base".equals(AsrModels.pickModel(st, "ru", se)), "Whisper важнее Vosk");
            se.put("asr_model", "ru-small");
            check("ru-small".equals(AsrModels.pickModel(st, "ru", se)), "выбор пользователя");
            se.put("asr_model", "whisper-small");
            check("whisper-base".equals(AsrModels.pickModel(st, "ru", se)), "выбранная не скачана → другая Whisper");
            check(wf.getName().equals("ggml-base-q5_1.bin"), wf.getName());
            java.io.File bad = new java.io.File(dir, "bad.bin");
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(bad)) { o.write("<html>".getBytes()); }
            check(!WhisperAsr.isGgml(bad), "страница ошибки вместо модели");
        });

        // эталон из Python: строки "layout sw sh |x y w h u0 v0 u1 v1|..." передаются аргументами
        for (String ref : args) {
            String[] parts = ref.split("\\|");
            String[] head = parts[0].trim().split(" ");
            final String layout = head[0];
            final int sw = Integer.parseInt(head[1]), sh = Integer.parseInt(head[2]);
            final JSONObject prof = Store.defaultProfile();
            prof.put("layout", layout);
            for (int h = 3; h < head.length; h++) {
                if (head[h].startsWith("[")) prof.put("cam_rect", new JSONArray(head[h]));
                else prof.put("single_zoom", Double.parseDouble(head[h]));
            }
            test("раскладка совпадает с Python/ffmpeg: " + parts[0].trim(), () -> {
                VideoRenderer.Plan p = VideoRenderer.plan(sw, sh, prof, layout);
                check(p.parts.length == parts.length - 1, "частей " + p.parts.length);
                for (int i = 0; i < p.parts.length; i++) {
                    String[] v = parts[i + 1].trim().split(" ");
                    VideoRenderer.Draw d = p.parts[i];
                    float[] got = {d.x, d.y, d.w, d.h, d.u0 * sw, d.v0 * sh, (d.u1 - d.u0) * sw, (d.v1 - d.v0) * sh};
                    for (int k = 0; k < 8; k++) {
                        double want = Double.parseDouble(v[k]);
                        check(Math.abs(got[k] - want) <= 3, "часть " + i + " поле " + k + ": java " + got[k] + " python " + want);
                    }
                }
            });
        }
        System.out.println("\nИтого: " + passed + " прошло, " + failed + " упало");
        System.exit(failed == 0 ? 0 : 1);
    }
}
