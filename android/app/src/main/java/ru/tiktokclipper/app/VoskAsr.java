package ru.tiktokclipper.app;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import ru.tiktokclipper.core.ApiError;
import ru.tiktokclipper.core.JobQueue;
import ru.tiktokclipper.core.Store;
import ru.tiktokclipper.core.Subs;

/** Распознавание речи офлайн (Vosk): слова с таймкодами. Модели скачиваются в приложении. */
final class VoskAsr {
    private VoskAsr() {
    }

    static final class ModelInfo {
        final String id, name, url, lang;
        final int sizeMb;

        ModelInfo(String id, String name, String url, String lang, int sizeMb) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.lang = lang;
            this.sizeMb = sizeMb;
        }
    }

    static final ModelInfo[] MODELS = {
            new ModelInfo("ru-small", "Русский (маленькая, 45 МБ)",
                    "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip", "ru", 45),
            new ModelInfo("en-small", "English (small, 40 MB)",
                    "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", "en", 40),
    };

    static ModelInfo info(String id) {
        for (ModelInfo m : MODELS) if (m.id.equals(id)) return m;
        return null;
    }

    static File dir(Store s, String id) {
        return new File(new File(new File(s.dir, "models"), "vosk"), id);
    }

    static boolean isInstalled(Store s, String id) {
        File d = dir(s, id);
        return new File(d, "am/final.mdl").isFile() || new File(d, "conf/model.conf").isFile();
    }

    /** Какую модель взять: по языку, иначе выбранную в настройках, иначе любую скачанную. */
    static String pickModel(Store s, String language, JSONObject settings) {
        if (language != null && !language.equals("auto")) {
            for (ModelInfo m : MODELS) if (m.lang.equals(language) && isInstalled(s, m.id)) return m.id;
        }
        String chosen = settings.optString("asr_model", "");
        if (!chosen.isEmpty() && isInstalled(s, chosen)) return chosen;
        for (ModelInfo m : MODELS) if (isInstalled(s, m.id)) return m.id;
        return null;
    }

    private static Model cached;
    private static String cachedId;

    private static synchronized Model model(Store s, String id) throws IOException {
        if (cached != null && id.equals(cachedId)) return cached;
        if (cached != null) cached.close();
        LibVosk.setLogLevel(LogLevel.WARNINGS);
        cached = new Model(dir(s, id).getPath());
        cachedId = id;
        return cached;
    }

    static List<Subs.Word> transcribe(Context ctx, Store s, Uri uri, long startUs, long endUs, String modelId,
                                      AtomicBoolean cancel, JobQueue.Progress prog) throws Exception {
        final List<Subs.Word> words = new ArrayList<>();
        Model m = model(s, modelId);
        try (final Recognizer rec = new Recognizer(m, 16000f)) {
            rec.setWords(true);
            AudioPipe.pcm16kMono(ctx, uri, startUs, endUs, (data, n) -> {
                if (rec.acceptWaveForm(data, n)) parse(rec.getResult(), words);
            }, cancel, prog);
            parse(rec.getFinalResult(), words);
        }
        return Subs.cleanWords(words);
    }

    static void parse(String json, List<Subs.Word> out) {
        try {
            JSONArray r = new JSONObject(json).optJSONArray("result");
            for (int i = 0; r != null && i < r.length(); i++) {
                JSONObject w = r.getJSONObject(i);
                out.add(new Subs.Word(w.getString("word"), w.getDouble("start"), w.getDouble("end")));
            }
        } catch (Exception ignored) {
            // пустой или неполный результат — пропускаем
        }
    }

    /** Скачивает и распаковывает модель (задача очереди «model»). */
    static JSONObject download(Store s, String id, JobQueue.Ctx ctx) throws Exception {
        ModelInfo mi = info(id);
        if (mi == null) throw new ApiError(400, "Неизвестная модель");
        File target = dir(s, id), tmpDir = new File(target.getPath() + ".part");
        File zip = new File(new File(s.dir, "cache"), id + ".zip");
        deleteTree(tmpDir);
        JobQueue.Progress p = ctx.stage(0, 0.85, "Скачиваю модель «" + mi.name + "»");
        zip.delete();
        fetchWithResume(mi.url, zip, mi.sizeMb * 1024L * 1024L, p, ctx);
        ctx.progress(0.88, "Распаковываю модель");
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(zip)))) {
            ZipEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                int slash = name.indexOf('/');
                String rel = slash >= 0 ? name.substring(slash + 1) : name; // убираем верхнюю папку архива
                if (rel.isEmpty() || rel.contains("..")) continue;
                File f = new File(tmpDir, rel);
                if (e.isDirectory()) {
                    f.mkdirs();
                    continue;
                }
                f.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(f)) {
                    int n;
                    while ((n = zin.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
        } finally {
            zip.delete();
        }
        deleteTree(target);
        if (!tmpDir.renameTo(target)) throw new IOException("Не удалось сохранить модель");
        if (!isInstalled(s, id)) throw new ApiError(500, "Архив модели оказался не тем — файлы модели не найдены");
        JSONObject r = new JSONObject();
        r.put("model", id);
        return r;
    }

    /** Скачивание с повторами: при обрыве связи докачивает с того же места (до 6 попыток). */
    static void fetchWithResume(String url, File dst, long expected, JobQueue.Progress p, JobQueue.Ctx ctx) throws Exception {
        final int attempts = 6;
        for (int attempt = 1; ; attempt++) {
            ctx.check();
            long have = dst.isFile() ? dst.length() : 0;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
                int code = c.getResponseCode();
                if (code == 416 && have > 0) return; // уже скачано целиком
                if (code != 200 && code != 206) {
                    throw new ApiError(502, "Сайт моделей ответил " + code + ". Попробуйте позже.");
                }
                boolean append = code == 206;
                if (!append) have = 0;
                long len = c.getContentLengthLong();
                long total = len > 0 ? have + len : Math.max(expected, have + 1);
                try (InputStream in = new BufferedInputStream(c.getInputStream());
                     OutputStream out = new FileOutputStream(dst, append)) {
                    byte[] buf = new byte[1 << 16];
                    long got = have;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        ctx.check();
                        out.write(buf, 0, n);
                        got += n;
                        p.set(Math.min(1.0, got / (double) total));
                    }
                    if (len > 0 && got < total) throw new IOException("файл скачан не полностью");
                }
                return;
            } catch (IOException e) {
                if (attempt >= attempts) {
                    dst.delete();
                    throw new ApiError(502, "Не удалось скачать модель: связь обрывается. Подключитесь к Wi-Fi и нажмите «Скачать» ещё раз.");
                }
                ctx.progress(0, "Связь оборвалась — повторяю (попытка " + (attempt + 1) + " из " + attempts + ")");
                Thread.sleep(2000L * attempt);
            }
        }
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
