package ru.tiktokclipper.app;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import ru.tiktokclipper.core.ApiError;
import ru.tiktokclipper.core.JobQueue;
import ru.tiktokclipper.core.Store;

/** Модели распознавания речи (Whisper и Vosk): список, выбор, скачивание. */
final class AsrModels {
    private AsrModels() {
    }

    static final String WHISPER = "whisper", VOSK = "vosk";
    static final String DEFAULT = "whisper-small";

    static final class ModelInfo {
        final String id, name, url, lang, engine, note;
        final int sizeMb;

        ModelInfo(String id, String engine, String name, String note, String url, String lang, int sizeMb) {
            this.id = id;
            this.engine = engine;
            this.name = name;
            this.note = note;
            this.url = url;
            this.lang = lang;
            this.sizeMb = sizeMb;
        }
    }

    private static final String HF = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

    /** Порядок важен: если выбранной модели нет, берётся первая скачанная из этого списка. */
    static final ModelInfo[] ALL = {
            new ModelInfo("whisper-small", WHISPER, "Whisper small — точная (190 МБ)",
                    "Хорошо понимает русский, ставит знаки препинания. Минута речи — примерно 1–3 минуты работы.",
                    HF + "ggml-small-q5_1.bin", "multi", 190),
            new ModelInfo("whisper-base", WHISPER, "Whisper base — быстрее (60 МБ)",
                    "В 2–3 раза быстрее small, ошибается чаще.",
                    HF + "ggml-base-q5_1.bin", "multi", 60),
            new ModelInfo("whisper-turbo", WHISPER, "Whisper large-v3-turbo — самая точная (550 МБ)",
                    "Только для мощных телефонов (от 8 ГБ памяти). Заметно медленнее small.",
                    HF + "ggml-large-v3-turbo-q5_0.bin", "multi", 550),
            new ModelInfo("ru-small", VOSK, "Vosk, русский — очень быстрая (45 МБ)",
                    "Быстро, но ошибается чаще и пишет без знаков препинания.",
                    "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip", "ru", 45),
            new ModelInfo("en-small", VOSK, "Vosk, English (40 MB)",
                    "Fast, lower quality, no punctuation.",
                    "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", "en", 40),
    };

    static ModelInfo info(String id) {
        for (ModelInfo m : ALL) if (m.id.equals(id)) return m;
        return null;
    }

    static boolean isInstalled(Store s, String id) {
        ModelInfo m = info(id);
        if (m == null) return false;
        return WHISPER.equals(m.engine) ? WhisperAsr.isInstalled(s, m) : VoskAsr.isInstalled(s, id);
    }

    /** Какую модель взять: выбранную в настройках, иначе любую скачанную Whisper, иначе Vosk нужного языка. */
    static String pickModel(Store s, String language, JSONObject settings) {
        boolean anyLang = language == null || language.equals("auto");
        String chosen = settings.optString("asr_model", "");
        ModelInfo c = info(chosen);
        if (c != null && isInstalled(s, chosen) && (WHISPER.equals(c.engine) || anyLang || c.lang.equals(language))) {
            return chosen;
        }
        for (ModelInfo m : ALL) if (WHISPER.equals(m.engine) && isInstalled(s, m.id)) return m.id;
        for (ModelInfo m : ALL) if ((anyLang || m.lang.equals(language)) && isInstalled(s, m.id)) return m.id;
        for (ModelInfo m : ALL) if (isInstalled(s, m.id)) return m.id;
        return null;
    }

    /** Скачивает модель (задача очереди «model»). */
    static JSONObject download(Store s, String id, JobQueue.Ctx ctx) throws Exception {
        ModelInfo mi = info(id);
        if (mi == null) throw new ApiError(400, "Неизвестная модель");
        if (WHISPER.equals(mi.engine)) WhisperAsr.download(s, mi, ctx);
        else VoskAsr.download(s, mi, ctx);
        JSONObject r = new JSONObject();
        r.put("model", id);
        return r;
    }

    static void delete(Store s, String id) throws ApiError {
        ModelInfo mi = info(id);
        if (mi == null) throw new ApiError(400, "Неизвестная модель");
        if (WHISPER.equals(mi.engine)) WhisperAsr.delete(s, mi);
        else VoskAsr.delete(s, id);
    }

    /**
     * Скачивание с повторами: при обрыве связи докачивает с того же места (до 6 попыток).
     * Переадресации (huggingface.co → хранилище файлов) обрабатываются вручную, чтобы докачка не терялась.
     */
    static void fetchWithResume(String url, File dst, long expected, JobQueue.Progress p, JobQueue.Ctx ctx) throws Exception {
        final int attempts = 6;
        for (int attempt = 1; ; attempt++) {
            ctx.check();
            long have = dst.isFile() ? dst.length() : 0;
            try {
                HttpURLConnection c = open(url, have);
                int code = c.getResponseCode();
                if (code == 416 && have > 0) return; // уже скачано целиком
                if (code != 200 && code != 206) {
                    c.disconnect();
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

    private static HttpURLConnection open(String url, long have) throws IOException {
        URL u = new URL(url);
        for (int hop = 0; hop < 8; hop++) {
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "Narezka/" + ru.tiktokclipper.core.Api.VERSION);
            if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && code != 304) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("переадресация без адреса");
                u = new URL(u, loc);
                if (!"https".equals(u.getProtocol())) throw new IOException("небезопасная переадресация");
                continue;
            }
            return c;
        }
        throw new IOException("слишком много переадресаций");
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
