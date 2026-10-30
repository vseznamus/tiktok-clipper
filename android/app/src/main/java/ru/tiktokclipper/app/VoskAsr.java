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
import java.io.OutputStream;
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

    static File dir(Store s, String id) {
        return new File(new File(new File(s.dir, "models"), "vosk"), id);
    }

    static boolean isInstalled(Store s, String id) {
        File d = dir(s, id);
        return new File(d, "am/final.mdl").isFile() || new File(d, "conf/model.conf").isFile();
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
    static void download(Store s, AsrModels.ModelInfo mi, JobQueue.Ctx ctx) throws Exception {
        String id = mi.id;
        File target = dir(s, id), tmpDir = new File(target.getPath() + ".part");
        File zip = new File(new File(s.dir, "cache"), id + ".zip");
        deleteTree(tmpDir);
        JobQueue.Progress p = ctx.stage(0, 0.85, "Скачиваю модель «" + mi.name + "»");
        zip.delete();
        AsrModels.fetchWithResume(mi.url, zip, mi.sizeMb * 1024L * 1024L, p, ctx);
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
    }

    static synchronized void delete(Store s, String id) {
        if (cached != null && id.equals(cachedId)) {
            cached.close();
            cached = null;
            cachedId = null;
        }
        deleteTree(dir(s, id));
    }

    static void deleteTree(File f) {
        AsrModels.deleteTree(f);
    }
}
