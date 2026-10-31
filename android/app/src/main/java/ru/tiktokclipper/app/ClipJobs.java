package ru.tiktokclipper.app;

import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import ru.tiktokclipper.core.Api;
import ru.tiktokclipper.core.ApiError;
import ru.tiktokclipper.core.ChatMoments;
import ru.tiktokclipper.core.JobQueue;
import ru.tiktokclipper.core.Moments;
import ru.tiktokclipper.core.Mp4FastStart;
import ru.tiktokclipper.core.Store;
import ru.tiktokclipper.core.Subs;

/** Задачи очереди: сделать клип, пересобрать, предложить моменты, скачать модель распознавания. */
final class ClipJobs {
    static final double MAX_CLIP_SEC = 600, DUR_TOL = 0.1;
    final Engine e;
    Api api;
    private Typeface font;

    ClipJobs(Engine e) {
        this.e = e;
    }

    private synchronized Typeface font() {
        if (font == null) font = Typeface.createFromAsset(e.ctx.getAssets(), "fonts/Montserrat-ExtraBold.ttf");
        return font;
    }

    private static Object or(JSONObject p, String k, Object def) {
        return p.has(k) && !p.isNull(k) ? p.opt(k) : def;
    }

    // ------------------------------------------------------------ клип

    JSONObject clip(JobQueue.Ctx ctx) throws Exception {
        JSONObject p = ctx.params;
        Store st = e.store;
        JSONObject settings = st.settings();
        JSONObject src = st.getSource(p.optString("source_id", ""));
        JSONObject prof = Store.fullProfile(src);
        String file = p.getString("file");
        ctx.progress(0.01, "Читаю видео");
        Uri uri = e.platform.resolve(file);
        JSONObject info = e.platform.probeUri(uri);
        if (!info.optBoolean("has_video")) throw new ApiError(400, "В файле нет видеодорожки");
        double start = Math.max(0, p.getDouble("start")), end = p.getDouble("end");
        if (info.optDouble("duration") > 0) end = Math.min(end, info.optDouble("duration"));
        double dur = end - start;
        if (dur < 1) throw new ApiError(400, "Отрезок слишком короткий (меньше 1 секунды)");
        if (dur > MAX_CLIP_SEC) throw new ApiError(400, "Отрезок длиннее 10 минут");

        boolean mirror = (Boolean) or(p, "mirror", prof.optBoolean("mirror", true));
        boolean subs = (Boolean) or(p, "subtitles", prof.optBoolean("subtitles", true));
        String style = String.valueOf(or(p, "sub_style", prof.optString("sub_style", "classic")));
        if (!Store.subStyles().has(style)) style = "classic";
        String layout = String.valueOf(or(p, "layout", prof.optString("layout", "single")));
        String language = String.valueOf(or(p, "language", prof.optString("language", settings.optString("asr_language", "auto"))));
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        List<Subs.Word> words = new ArrayList<>();
        JSONObject asrInfo = null;
        if (subs) {
            if ("srt".equals(p.optString("sub_source"))) {
                String srt = e.platform.sidecarSrt(file);
                if (srt != null) {
                    ctx.progress(0.05, "Беру субтитры из файла .srt");
                    words = Subs.wordsFromCues(Subs.parseSrt(srt), start, end);
                    if (words.isEmpty()) ctx.warn("В файле .srt нет реплик на этом отрезке — клип без субтитров.");
                    asrInfo = new JSONObject().put("backend", "srt");
                } else {
                    ctx.warn("Рядом с видео нет файла .srt — пробую распознать речь.");
                }
            }
            if (asrInfo == null) {
                if (!info.optBoolean("has_audio")) {
                    ctx.warn("В исходнике нет звука — клип без субтитров.");
                } else if ("off".equals(settings.optString("asr_backend"))) {
                    ctx.warn("Распознавание речи выключено в настройках. Клип сделан без субтитров.");
                } else {
                    String model = AsrModels.pickModel(st, language, settings);
                    if ("whisper-turbo".equals(model) && WhisperAsr.totalRam(e.ctx) < WhisperAsr.TURBO_MIN_RAM) {
                        // turbo телефону не по силам — берём другую скачанную модель, а не падаем на каждом клипе
                        String alt = null;
                        for (String cand : new String[]{"whisper-small", "whisper-base", "ru-small", "en-small"}) {
                            if (AsrModels.isInstalled(st, cand)) {
                                alt = cand;
                                break;
                            }
                        }
                        ctx.warn(alt != null ? "Whisper turbo не хватает памяти телефона — распознал моделью «" + AsrModels.info(alt).name
                                + "». Выберите её в «Настройки» → «Модель по умолчанию»."
                                : "Whisper turbo не хватает памяти телефона. Скачайте Whisper small: «Настройки» → «Распознавание речи».");
                        model = alt;
                    }
                    AsrModels.ModelInfo mi = model == null ? null : AsrModels.info(model);
                    if (mi == null) {
                        ctx.warn("Модель распознавания речи не скачана: «Настройки» → «Распознавание речи» → «Скачать». "
                                + "Клип сделан без субтитров.");
                    } else {
                        long us0 = (long) (start * 1e6), us1 = (long) (end * 1e6);
                        if (AsrModels.WHISPER.equals(mi.engine)) {
                            JobQueue.Progress pr = ctx.stage(0.03, 0.55, "Распознаю речь (Whisper " + model.replace("whisper-", "") + ")");
                            words = WhisperAsr.transcribe(e.ctx, st, uri, us0, us1, model, language, ctx.cancel, pr, ctx::note);
                        } else {
                            JobQueue.Progress pr = ctx.stage(0.03, 0.40, "Распознаю речь (Vosk, " + model + ")");
                            words = VoskAsr.transcribe(e.ctx, st, uri, us0, us1, model, ctx.cancel, pr);
                        }
                        ctx.check();
                        asrInfo = new JSONObject().put("backend", mi.engine).put("model", model);
                        if (words.isEmpty()) ctx.warn("Речь не найдена — клип сделан без субтитров.");
                    }
                }
            }
        }
        List<Subs.Word> inClip = new ArrayList<>();
        for (Subs.Word w : words) {
            if (w.s < dur) {
                w.e = Math.min(w.e, dur);
                inClip.add(w);
            }
        }
        List<Subs.Line> lines = Subs.groupWords(inClip, Store.subStyle(style).optInt("words", 3));

        JSONObject meta = new JSONObject();
        meta.put("id", id);
        meta.put("created", System.currentTimeMillis() / 1000.0);
        meta.put("source_id", src != null ? src.optString("id") : JSONObject.NULL);
        meta.put("source_title", src != null ? src.optString("title") : "Без источника");
        meta.put("src", file);
        meta.put("start", start);
        meta.put("end", end);
        meta.put("mirror", mirror);
        meta.put("subtitles", subs);
        meta.put("sub_style", style);
        meta.put("language", language);
        meta.put("layout_requested", layout);
        // баннер-картинка поверх клипа
        String banner = p.optString("banner", "");
        if (!banner.isEmpty()) {
            meta.put("banner", banner);
            meta.put("banner_pos", p.optString("banner_pos", "top"));
            meta.put("banner_size", Math.max(0.2, Math.min(1.0, p.optDouble("banner_size", 0.8))));
            meta.put("banner_chroma", p.optBoolean("banner_chroma", false));
            meta.put("banner_plate", p.optBoolean("banner_plate", false));
        }
        if (p.optBoolean("night")) meta.put("night", true);
        meta.put("caption", src != null && "stream".equals(src.optString("type"))
                ? ru.tiktokclipper.core.Captions.stream(src.optString("title"), src.optString("twitch_login"), file)
                : ru.tiktokclipper.core.Captions.make(file));
        meta.put("profile", prof);
        meta.put("words", Subs.wordsToJson(inClip));
        meta.put("lines", Subs.linesToJson(lines));
        meta.put("asr", asrInfo == null ? JSONObject.NULL : asrInfo);
        meta.put("version", 0);
        meta.put("published", false);
        renderMeta(ctx, meta, info, uri, subs ? 0.42 : 0.04);
        return new JSONObject().put("clip_id", id).put("filename", meta.getString("filename")).put("warnings", meta.get("warnings"));
    }

    // ------------------------------------------------------------ общая сборка

    private void renderMeta(JobQueue.Ctx ctx, JSONObject meta, JSONObject info, Uri uri, double lo) throws Exception {
        JSONObject prof = meta.getJSONObject("profile");
        int sw = info.getInt("width"), sh = info.getInt("height");
        String layoutReq = meta.optString("layout_requested", "single");
        String eff = layoutReq;
        if ("split".equals(layoutReq) && !VideoRenderer.validCam(prof, sw, sh)) {
            ctx.warn("Рамка вебкамеры не задана или слишком мала — клип собран в режиме «один кадр». "
                    + "Задайте рамку в «Настройки» → источник → «Рамка вебкамеры».");
            eff = "single";
        }
        JSONObject style = Store.subStyle(meta.getString("sub_style"));
        List<Subs.Line> lines = Subs.linesFromJson(meta.optJSONArray("lines"));
        boolean hasSubs = meta.optBoolean("subtitles") && !lines.isEmpty();
        SubtitlePainter painter = new SubtitlePainter(font(), style);
        // баннер: кадры (картинка — один, видео — по кругу), положение в безопасной зоне, субтитры не перекрывает
        BannerFrames bf = loadBanner(ctx, meta, lo);
        int[] bRect = null;
        String bPos = meta.optString("banner_pos", "top");
        if (bf != null) {
            Bitmap f0 = bf.frames.get(0);
            bRect = ru.tiktokclipper.core.Banner.rect(bPos, meta.optDouble("banner_size", 0.8), f0.getWidth(), f0.getHeight());
        }
        double subY = ru.tiktokclipper.core.Banner.subCenter(bPos, bRect, Subs.subCenterY(prof, eff));
        List<Subs.Event> events = hasSubs ? Subs.events(lines, style, subY, painter.measurer())
                : new ArrayList<Subs.Event>();
        long startUs = (long) (meta.getDouble("start") * 1e6), endUs = (long) (meta.getDouble("end") * 1e6);

        AudioPipe.AacTrack audio = AudioPipe.encodeAac(e.ctx, uri, startUs, endUs, info.optBoolean("has_audio"),
                ctx.cancel, ctx.stage(lo, lo + 0.08, "Готовлю звук"));
        if (audio.warning != null) ctx.warn(audio.warning);
        ctx.check();

        File work = new File(new File(e.store.dir, "work"), meta.getString("id"));
        work.mkdirs();
        File muxed = new File(work, "clip.mp4");
        int bitrate = e.store.settings().optInt("bitrate_mbps", 10) * 1000000;
        VideoRenderer vr = new VideoRenderer();
        if (bf != null) {
            vr.banner(bf.frames, bf.fps, bRect, meta.optBoolean("banner_plate") ? ru.tiktokclipper.core.Banner.plate(bRect) : null);
        }
        VideoRenderer.Result r = vr.render(e.ctx, uri, sw, sh, info.optDouble("fps", 30), prof, eff,
                meta.getBoolean("mirror"), events, painter, startUs, endUs, bitrate, audio, muxed, ctx.cancel,
                ctx.stage(lo + 0.08, 0.95, meta.optInt("version") == 0 ? "Собираю видео" : "Пересобираю видео"));
        meta.put("layout_used", r.layout);

        ctx.progress(0.96, "Проверяю и сохраняю в галерею");
        File fin = e.platform.clipFile(meta.getString("id"));
        File tmp = new File(fin.getPath() + ".part");
        if (Mp4FastStart.process(muxed, tmp)) {
            if (!tmp.renameTo(fin)) throw new java.io.IOException("Не удалось сохранить клип");
        } else if (!muxed.renameTo(fin)) {
            throw new java.io.IOException("Не удалось сохранить клип");
        }
        AsrModels.deleteTree(work);

        double expected = meta.getDouble("end") - meta.getDouble("start");
        MediaMetadataRetriever mr = new MediaMetadataRetriever();
        try {
            mr.setDataSource(fin.getPath());
            int ow = Integer.parseInt(mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int oh = Integer.parseInt(mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            double od = Long.parseLong(mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000.0;
            meta.put("duration", od);
            if (ow != 1080 || oh != 1920 || Math.abs(od - expected) > DUR_TOL + 1.0 / 30) {
                ctx.warn(String.format(Locale.ROOT, "Проверка: получилось %dx%d, %.2f с (ожидалось 1080x1920, %.2f с).", ow, oh, od, expected));
            }
        } catch (RuntimeException ignored) {
            // проверка — не повод ронять задачу
        } finally {
            AndroidPlatform.release(mr);
        }

        if (!meta.has("filename") || meta.optString("filename").isEmpty()) {
            String stamp = new SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.ROOT).format(new Date());
            String sid = meta.isNull("source_id") ? "clip" : meta.getString("source_id");
            meta.put("filename", stamp + "_" + sid + ".mp4");
        }
        String filename = meta.getString("filename"), base = filename.substring(0, filename.length() - 4);
        Uri media = e.platform.publishToGallery(fin, filename, "video/mp4", true, meta.optString("media_uri"));
        meta.put("media_uri", media.toString());
        meta.put("gallery_path", "Movies/TikTokClips/" + filename);
        if (hasSubs) {
            try {
                File srt = new File(e.store.dir, "cache/" + base + ".srt"), ass = new File(e.store.dir, "cache/" + base + ".ass");
                Store.writeText(srt, Subs.toSrt(lines));
                Store.writeText(ass, Subs.toAss(events, style));
                meta.put("srt_uri", e.platform.publishToGallery(srt, base + ".srt", "application/x-subrip", false, meta.optString("srt_uri")).toString());
                meta.put("ass_uri", e.platform.publishToGallery(ass, base + ".ass", "text/x-ssa", false, meta.optString("ass_uri")).toString());
                meta.put("srt", "Documents/TikTokClips/" + base + ".srt");
                meta.put("ass", "Documents/TikTokClips/" + base + ".ass");
                srt.delete();
                ass.delete();
            } catch (Exception ex) {
                ctx.warn("Файлы .srt/.ass не сохранились в Documents/TikTokClips (" + ex.getMessage() + ").");
            }
        } else {
            for (String k : new String[]{"srt_uri", "ass_uri"}) {
                if (!meta.optString(k).isEmpty()) {
                    try {
                        e.ctx.getContentResolver().delete(Uri.parse(meta.getString(k)), null, null);
                    } catch (RuntimeException ignored) {
                    }
                }
                meta.remove(k);
            }
            meta.put("srt", JSONObject.NULL);
            meta.put("ass", JSONObject.NULL);
        }
        meta.put("warnings", ctx.warnings());
        meta.put("rendered", System.currentTimeMillis() / 1000.0);
        api.saveClip(meta);
    }

    // ------------------------------------------------------------ пересборка

    JSONObject rebuild(JobQueue.Ctx ctx) throws Exception {
        JSONObject p = ctx.params;
        JSONObject meta = api.loadClip(p.optString("clip_id"));
        if (meta == null) throw new ApiError(404, "Клип не найден");
        double dur = meta.getDouble("end") - meta.getDouble("start");
        if (p.has("lines") && !p.isNull("lines")) {
            meta.put("lines", Subs.linesToJson(Subs.applyEdits(p.getJSONArray("lines"), Subs.linesFromJson(meta.optJSONArray("lines")), dur)));
            meta.put("subtitles", true);
        }
        if (p.has("subtitles") && !p.isNull("subtitles")) meta.put("subtitles", p.getBoolean("subtitles"));
        if (p.has("mirror") && !p.isNull("mirror")) meta.put("mirror", p.getBoolean("mirror"));
        if (Store.subStyles().has(p.optString("sub_style"))) meta.put("sub_style", p.getString("sub_style"));
        meta.put("version", meta.optInt("version") + 1);
        ctx.progress(0.02, "Читаю видео");
        Uri uri = e.platform.resolve(meta.getString("src"));
        renderMeta(ctx, meta, e.platform.probeUri(uri), uri, 0.05);
        return new JSONObject().put("clip_id", meta.getString("id")).put("filename", meta.getString("filename")).put("warnings", meta.get("warnings"));
    }

    // ------------------------------------------------------------ моменты

    JSONObject moments(JobQueue.Ctx ctx) throws Exception {
        JSONObject p = ctx.params;
        int length = p.optInt("length", e.store.settings().optInt("moment_len", 30));
        return analyze(ctx, p.getString("file"), length, 0, 1);
    }

    /** «Быстрый клип»: лучший момент (или несколько) по автоподбору → клипы по шаблону. */
    JSONObject auto(JobQueue.Ctx ctx) throws Exception {
        JSONObject p = ctx.params;
        String file = p.getString("file");
        int count = Math.max(1, Math.min(5, p.optInt("count", 1)));
        JSONObject settings = e.store.settings();
        int length = settings.optInt("moment_len", 30);
        JSONObject res = analyze(ctx, file, length, 0.02, 0.9);
        java.util.List<JSONObject> best = ru.tiktokclipper.core.Template.best(res.optJSONArray("candidates"), count, Math.max(90, 3.0 * length));
        if (best.isEmpty()) throw new ApiError(400, "Не нашлось ни одного момента — в видео нет звука");
        ctx.progress(0.95, "Ставлю клипы в очередь");
        JSONObject tpl = ru.tiktokclipper.core.Template.of(settings);
        boolean srt = e.platform.sidecarSrt(file) != null;
        JSONArray jobs = new JSONArray();
        for (JSONObject c : best) {
            JSONObject cp = ru.tiktokclipper.core.Template.clipParams(tpl, file, c.getDouble("start"), c.getDouble("end"), srt);
            if (!p.optString("source_id").isEmpty()) cp.put("source_id", p.getString("source_id")); // рамка вебкамеры стримера
            String name = file.substring(file.lastIndexOf('/') + 1);
            jobs.put(e.queue.submit("clip", cp, "Клип: " + name + " (" + Api.fmt(c.getDouble("start")) + ")").getString("id"));
        }
        return new JSONObject().put("clip_jobs", jobs);
    }

    /**
     * Ночная нарезка: у каждого стримера с папкой — самая свежая ещё не нарезанная запись эфира,
     * из неё N лучших моментов (по чату, если файл чата рядом, иначе по звуку) → клипы по шаблону с рамкой вебкамеры стримера.
     */
    JSONObject night(JobQueue.Ctx ctx) throws Exception {
        JSONObject settings = e.store.settings(), n = settings.optJSONObject("night");
        int per = Math.max(1, Math.min(5, n != null ? n.optInt("per_recording", 3) : 3));
        int length = settings.optInt("moment_len", 30);
        JSONArray srcs = e.store.sources().getJSONArray("sources");
        java.util.List<JSONObject> streamers = new java.util.ArrayList<>();
        for (int i = 0; i < srcs.length(); i++) {
            JSONObject s = srcs.getJSONObject(i);
            if (s.optBoolean("enabled", true) && !s.optString("folder_uri").isEmpty()) streamers.add(s);
        }
        if (streamers.isEmpty()) throw new ApiError(400, "Добавьте стримера с папкой записей на вкладке «Стримеры»");
        File stFile = new File(e.store.dir, "night.json");
        JSONObject st = Store.readObject(stFile);
        JSONArray done = st.optJSONArray("done");
        if (done == null) done = new JSONArray();
        JSONObject tpl = ru.tiktokclipper.core.Template.of(settings);
        JSONArray jobs = new JSONArray(), names = new JSONArray();
        for (int k = 0; k < streamers.size(); k++) {
            ctx.check();
            JSONObject src = streamers.get(k);
            double lo = 0.02 + 0.9 * k / streamers.size(), hi = 0.02 + 0.9 * (k + 1) / streamers.size();
            ctx.progress(lo, "Смотрю записи: " + src.optString("title"));
            JSONArray files;
            try {
                files = e.platform.listFiles(src);
            } catch (ApiError err) {
                ctx.warn(src.optString("title") + ": " + err.getMessage());
                continue;
            }
            JSONObject newest = null;
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                if (Store.contains(done, f.optString("path"))) continue;
                if (newest == null || f.optDouble("mtime") > newest.optDouble("mtime")) newest = f;
            }
            if (newest == null) continue;
            String file = newest.getString("path");
            try {
                // ключи Twitch есть и запись с той же датой нашлась — моменты, которые нарезали сами зрители
                JSONArray tw = null;
                try {
                    tw = api.discover.momentsForFile(src, file, newest.optString("name"), newest.optDouble("mtime"));
                } catch (ApiError err) {
                    ctx.warn("Twitch: " + err.getMessage() + " Ищу моменты по чату/звуку.");
                }
                JSONArray cands;
                if (tw != null) {
                    cands = new JSONArray();
                    for (int i = 0; i < tw.length(); i++) {
                        JSONObject mm = tw.getJSONObject(i);
                        double st0 = Math.max(0, mm.getDouble("t") - 3);
                        cands.put(new JSONObject().put("start", st0).put("end", Math.min(st0 + 180, Math.max(st0 + length, mm.optDouble("end") + 2))));
                    }
                    ctx.progress(hi, "Моменты из клипов зрителей Twitch: " + src.optString("title"));
                } else {
                    cands = analyze(ctx, file, length, lo, hi).optJSONArray("candidates");
                }
                java.util.List<JSONObject> best = ru.tiktokclipper.core.Template.best(cands, per, Math.max(90, 3.0 * length));
                boolean srt = e.platform.sidecarSrt(file) != null;
                for (JSONObject c : best) {
                    JSONObject cp = ru.tiktokclipper.core.Template.clipParams(tpl, file, c.getDouble("start"), c.getDouble("end"), srt)
                            .put("source_id", src.optString("id")).put("night", true);
                    jobs.put(e.queue.submit("clip", cp, "Ночной клип: " + src.optString("title")).getString("id"));
                }
                names.put(src.optString("title") + " — " + newest.optString("name"));
            } catch (ApiError err) {
                ctx.warn(newest.optString("name") + ": " + err.getMessage());
            }
            done.put(file);
        }
        st.put("done", done).put("last_run", java.time.LocalDate.now().toString()).put("last_jobs", jobs).put("last_files", names);
        Store.writeJson(stFile, st);
        if (names.length() == 0) ctx.warn("Новых записей нет — всё уже нарезано.");
        return new JSONObject().put("clip_jobs", jobs).put("files", names);
    }

    /**
     * Моменты в файле: по чату записи, если он лежит рядом, иначе офлайн по звуку, репликам (.srt) и сменам планов.
     * Прогресс задачи — в пределах lo…hi.
     */
    JSONObject analyze(JobQueue.Ctx ctx, String file, int length, double lo, double hi) throws Exception {
        final double span = hi - lo;
        Uri uri = e.platform.resolve(file);
        JSONObject info = e.platform.probeUri(uri);
        // рядом лежит чат записи — моменты по реакции зрителей
        Uri chatUri = e.platform.sidecarChat(file);
        if (chatUri != null) {
            ctx.progress(lo + 0.1 * span, "Читаю чат записи");
            ChatMoments.Chat chat;
            try (java.io.InputStream in = e.ctx.getContentResolver().openInputStream(chatUri)) {
                chat = ChatMoments.read(in);
            }
            ctx.check();
            if (chat.n > 0) {
                ctx.progress(lo + 0.8 * span, "Ищу всплески в чате (" + chat.n + " сообщений)");
                return new JSONObject().put("file", file).put("length", length).put("source", "chat").put("chat_messages", chat.n)
                        .put("candidates", ChatMoments.find(chat, info.optDouble("duration"), length, 8)).put("speech", true);
            }
            ctx.warn("Файл чата рядом с видео не удалось прочитать (формат не распознан) — ищу по звуку.");
        }
        if (!info.optBoolean("has_audio")) throw new ApiError(400, "В файле нет звука — искать громкие моменты не по чему");
        long size = e.platform.uriSource(uri).size();
        String key = sha1(file + "|" + size);
        File cache = new File(e.store.dir, "cache/" + key + ".loud.json");
        double[] levels;
        if (cache.isFile()) {
            levels = readDoubles(cache);
        } else {
            levels = AudioPipe.loudness(e.ctx, uri, info.optDouble("duration"), ctx.cancel,
                    ctx.stage(lo, lo + 0.85 * span, "Слушаю звук всего файла (первый раз долго, потом из кэша)"));
            writeDoubles(cache, levels);
        }
        // смены планов: опорные кадры по секундам (кодировщик ставит их на склейках)
        File cutCache = new File(e.store.dir, "cache/" + key + ".cuts.json");
        double[] cuts = null;
        try {
            if (cutCache.isFile()) cuts = readDoubles(cutCache);
            else {
                ctx.progress(lo + 0.88 * span, "Смотрю смены планов");
                cuts = keyframes(uri, info.optDouble("duration"), ctx.cancel);
                writeDoubles(cutCache, cuts);
            }
        } catch (JobQueue.Cancelled c) {
            throw c;
        } catch (Exception ex) {
            cuts = null; // не страшно — оценим без них
        }
        String srt = e.platform.sidecarSrt(file);
        List<Subs.Line> cues = srt != null ? Subs.parseSrt(srt) : null;
        return new JSONObject().put("file", file).put("length", length)
                .put("candidates", Moments.find(levels, length, cues, cuts, 8)).put("speech", cues != null && !cues.isEmpty());
    }

    static final class BannerFrames {
        final List<Bitmap> frames = new ArrayList<>();
        double fps = 1;
    }

    static final int BANNER_MAX_W = 840, BANNER_MAX_FRAMES = 60;
    static final double BANNER_MAX_SEC = 6;

    /**
     * Баннер: картинка — один кадр, видео (MP4 и др.) — до 60 кадров по кругу (до 6 с, 10–15 кадров/с, без звука).
     * По желанию — «убрать зелёный фон». null — без баннера (если файл недоступен — с предупреждением).
     */
    BannerFrames loadBanner(JobQueue.Ctx ctx, JSONObject meta, double at) {
        String id = meta.optString("banner", "");
        if (id.isEmpty()) return null;
        boolean chroma = meta.optBoolean("banner_chroma");
        BannerFrames out = new BannerFrames();
        try {
            Uri u = e.platform.resolve(id);
            String mime = e.ctx.getContentResolver().getType(u);
            if (mime == null) mime = e.platform.mimeOf(id);
            if (mime.startsWith("video/")) {
                ctx.progress(at, "Готовлю видео-баннер");
                MediaMetadataRetriever mr = new MediaMetadataRetriever();
                try {
                    mr.setDataSource(e.ctx, u);
                    double dur = Math.max(0.1, Long.parseLong(mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000.0);
                    double len = Math.min(dur, BANNER_MAX_SEC);
                    out.fps = Math.min(15, Math.max(1, BANNER_MAX_FRAMES / len));
                    int n = Math.max(1, (int) Math.floor(len * out.fps));
                    for (int i = 0; i < n; i++) {
                        ctx.check();
                        long us = (long) (i / out.fps * 1e6);
                        Bitmap f = mr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST);
                        if (f == null) continue;
                        out.frames.add(prepare(f, chroma));
                    }
                } finally {
                    mr.release();
                }
            } else {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (java.io.InputStream in = e.ctx.getContentResolver().openInputStream(u)) {
                    BitmapFactory.decodeStream(in, null, o);
                }
                int sample = 1;
                while (o.outWidth / (sample * 2) >= BANNER_MAX_W) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                Bitmap b;
                try (java.io.InputStream in = e.ctx.getContentResolver().openInputStream(u)) {
                    b = BitmapFactory.decodeStream(in, null, o2);
                }
                if (b != null) out.frames.add(prepare(b, chroma));
            }
            if (out.frames.isEmpty()) throw new java.io.IOException("не картинка и не видео");
            return out;
        } catch (JobQueue.Cancelled c) {
            throw new RuntimeException(c);
        } catch (Exception ex) {
            ctx.warn("Баннер не удалось открыть (" + ex.getMessage() + ") — клип собран без баннера.");
            return null;
        }
    }

    /** Уменьшить до ширины безопасной зоны и (по желанию) убрать зелёный фон. */
    static Bitmap prepare(Bitmap src, boolean chroma) {
        Bitmap b = src;
        if (b.getWidth() > BANNER_MAX_W) {
            b = Bitmap.createScaledBitmap(src, BANNER_MAX_W, Math.max(1, src.getHeight() * BANNER_MAX_W / src.getWidth()), true);
            if (b != src) src.recycle();
        }
        if (chroma) {
            Bitmap m = b.copy(Bitmap.Config.ARGB_8888, true);
            if (m != b) b.recycle();
            int[] px = new int[m.getWidth() * m.getHeight()];
            m.getPixels(px, 0, m.getWidth(), 0, 0, m.getWidth(), m.getHeight());
            ru.tiktokclipper.core.Chroma.key(px);
            m.setHasAlpha(true);
            m.setPixels(px, 0, m.getWidth(), 0, 0, m.getWidth(), m.getHeight());
            b = m;
        }
        return b;
    }

    static double[] readDoubles(File f) throws JSONException {
        JSONArray a = Store.readArray(f);
        double[] v = new double[a.length()];
        for (int i = 0; i < a.length(); i++) v[i] = a.getDouble(i);
        return v;
    }

    static void writeDoubles(File f, double[] v) {
        JSONArray a = new JSONArray();
        for (double x : v) {
            try {
                a.put(Math.round(x * 100) / 100.0);
            } catch (JSONException ignored) {
            }
        }
        Store.writeJson(f, a);
    }

    /** Число опорных (ключевых) кадров в каждой секунде — без декодирования, только по меткам дорожки. */
    double[] keyframes(Uri uri, double duration, java.util.concurrent.atomic.AtomicBoolean cancel) throws Exception {
        android.media.MediaExtractor ex = new android.media.MediaExtractor();
        try {
            ex.setDataSource(e.ctx, uri, null);
            int track = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    break;
                }
            }
            if (track < 0) return new double[0];
            ex.selectTrack(track);
            int n = (int) Math.ceil(Math.max(1, duration)) + 1;
            double[] c = new double[n];
            int k = 0;
            do {
                long t = ex.getSampleTime();
                if (t < 0) break;
                if ((ex.getSampleFlags() & android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    int s = (int) (t / 1_000_000L);
                    if (s >= 0 && s < n) c[s]++;
                }
                if ((++k & 1023) == 0 && cancel.get()) throw new JobQueue.Cancelled();
            } while (ex.advance());
            return c;
        } finally {
            ex.release();
        }
    }

    JSONObject downloadModel(JobQueue.Ctx ctx) throws Exception {
        String id = ctx.params.optString("model");
        if ("delete".equals(ctx.params.optString("action"))) {
            AsrModels.delete(e.store, id);
            return new JSONObject().put("model", id).put("deleted", true);
        }
        return AsrModels.download(e.store, id, ctx);
    }

    static String sha1(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(String.format("%02x", d[i]));
        return sb.toString();
    }
}
