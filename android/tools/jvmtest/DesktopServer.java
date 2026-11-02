import org.json.JSONArray;
import org.json.JSONObject;
import ru.tiktokclipper.core.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Сервер приложения (Api + MiniHttp + JobQueue из ядра) на обычной JVM — для UI-теста режима приложения.
 * Платформа — «настольная» заглушка: папки источников — обычные каталоги, видео читает ffprobe/ffmpeg.
 * Сборка клипа здесь упрощённая (ffmpeg), настоящая Android-сборка (MediaCodec + OpenGL) тут не запускается.
 *   java DesktopServer <data> <picked-dir> <port>
 */
public class DesktopServer {
    static final String TOKEN = "testtoken";

    public static void main(String[] a) throws Exception {
        File data = new File(a[0]), pickedDir = new File(a[1]);
        int port = Integer.parseInt(a[2]);
        Store store = new Store(data);
        DesktopPlatform pf = new DesktopPlatform(store, pickedDir);
        Map<String, JobQueue.Handler> h = new HashMap<>();
        Api[] api = new Api[1];
        h.put("clip", ctx -> pf.clip(ctx, api[0]));
        h.put("rebuild", ctx -> pf.rebuild(ctx, api[0]));
        h.put("moments", pf::moments);
        JobQueue[] qa = new JobQueue[1];
        // «Быстрый клип» и ночная нарезка — та же логика, что в ClipJobs (поиск моментов здесь — ffmpeg)
        h.put("auto", ctx -> {
            String file = ctx.params.getString("file");
            int len = store.settings().optInt("moment_len", 30);
            JSONObject res = pf.analyze(ctx, file, len, 0.05, 0.9);
            List<JSONObject> best = Template.best(res.optJSONArray("candidates"), Math.max(1, ctx.params.optInt("count", 1)), 90);
            JSONArray jobs = new JSONArray();
            for (JSONObject c : best) {
                JSONObject cp = Template.clipParams(Template.of(store.settings()), file, c.getDouble("start"), c.getDouble("end"), pf.sidecarSrt(file) != null);
                if (!ctx.params.optString("source_id").isEmpty()) cp.put("source_id", ctx.params.getString("source_id"));
                for (String k : new String[]{"layout", "cam_rect", "screen_rect"}) if (ctx.params.has(k)) cp.put(k, ctx.params.get(k));
                jobs.put(qa[0].submit("clip", cp, "Клип").getString("id"));
            }
            return new JSONObject().put("clip_jobs", jobs);
        });
        h.put("night", ctx -> {
            int per = store.settings().optJSONObject("night") != null ? store.settings().getJSONObject("night").optInt("per_recording", 3) : 3;
            File stf = new File(store.dir, "night.json");
            JSONObject st = Store.readObject(stf);
            JSONArray done = st.optJSONArray("done") != null ? st.getJSONArray("done") : new JSONArray();
            JSONArray jobs = new JSONArray(), names = new JSONArray(), srcs = store.sources().getJSONArray("sources");
            for (int k = 0; k < srcs.length(); k++) {
                JSONObject src = srcs.getJSONObject(k);
                if (src.optString("folder_uri").isEmpty()) continue;
                JSONArray files = pf.listFiles(src);
                JSONObject newest = null;
                for (int i = 0; i < files.length(); i++) {
                    JSONObject f = files.getJSONObject(i);
                    if (!Store.contains(done, f.getString("path")) && (newest == null || f.optDouble("mtime") > newest.optDouble("mtime"))) newest = f;
                }
                if (newest == null) continue;
                String f = newest.getString("path");
                JSONObject res = pf.analyze(ctx, f, store.settings().optInt("moment_len", 30), 0.1, 0.9);
                for (JSONObject c : Template.best(res.getJSONArray("candidates"), per, 10)) {
                    jobs.put(qa[0].submit("clip", Template.clipParams(Template.of(store.settings()), f, c.getDouble("start"), c.getDouble("end"), false)
                            .put("source_id", src.getString("id")).put("night", true), "Ночной клип").getString("id"));
                }
                names.put(src.optString("title") + " — " + newest.getString("name"));
                done.put(f);
            }
            Store.writeJson(stf, st.put("done", done).put("last_run", java.time.LocalDate.now().toString()).put("last_files", names));
            return new JSONObject().put("clip_jobs", jobs).put("files", names);
        });
        h.put("model", pf::model);
        JobQueue q = new JobQueue(store.jobsFile(), h);
        qa[0] = q;
        api[0] = new Api(store, pf, q, TOKEN, DesktopServer::fakeNet);
        q.start();
        MiniHttp http = new MiniHttp(api[0]);
        System.out.println("PORT " + http.start(port));
        Thread.currentThread().join();
    }

    /** Имитация Twitch для «Эфиры и клипы зрителей» (в тесте нет интернета и ключей). */
    static Discover.Resp fakeNet(String url, Map<String, String> h, String body) {
        String j;
        if (url.contains("id.twitch.tv")) j = "{\"access_token\":\"t\",\"expires_in\":3600}";
        else if (url.contains("/helix/users")) j = "{\"data\":[{\"id\":\"7\",\"display_name\":\"Vasya_Live\"}]}";
        else if (url.contains("/helix/videos")) j = "{\"data\":[{\"id\":\"900\",\"title\":\"Проходим хоррор до утра\",\"created_at\":\"2026-10-28T19:00:00Z\","
                + "\"duration\":\"4h12m5s\",\"url\":\"https://www.twitch.tv/videos/900\",\"view_count\":15400}]}";
        else if (url.contains("/helix/clips")) j = "{\"data\":["
                + "{\"video_id\":\"900\",\"vod_offset\":12,\"duration\":28,\"view_count\":8200,\"title\":\"Вася испугался скримера\",\"url\":\"https://clips.twitch.tv/a\"},"
                + "{\"video_id\":\"900\",\"vod_offset\":20,\"duration\":30,\"view_count\":1300,\"title\":\"АААААА\",\"url\":\"https://clips.twitch.tv/b\"},"
                + "{\"video_id\":\"900\",\"vod_offset\":7340,\"duration\":25,\"view_count\":2100,\"title\":\"Пасхалка в подвале\",\"url\":\"https://clips.twitch.tv/c\"}],\"pagination\":{}}";
        else return new Discover.Resp(404, "{}");
        return new Discover.Resp(200, j);
    }

    static String sh(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(Store.readAll(p.getInputStream()), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IOException("Команда не удалась: " + out);
        return out;
    }

    static class DesktopPlatform implements Platform {
        final Store store;
        final File picked;

        DesktopPlatform(Store s, File picked) {
            store = s;
            this.picked = picked;
        }

        File resolve(String id) throws ApiError {
            int i = id.indexOf('/');
            if (i <= 0) throw new ApiError(404, "Файл не найден");
            String sid = id.substring(0, i), name = id.substring(i + 1);
            File dir = sid.equals("_picked") || sid.equals("_img") ? picked
                    : sid.equals("_night") ? new File(store.settings().optJSONObject("night").optString("folder_uri"))
                    : new File(store.getSource(sid).optString("folder_uri"));
            File f = new File(dir, name);
            if (!f.isFile() || name.contains("..")) throw new ApiError(404, "Файл не найден");
            return f;
        }

        public JSONArray listFiles(JSONObject src) throws Exception {
            File dir = src == null ? picked : new File(src.optString("folder_uri"));
            JSONArray out = new JSONArray();
            File[] fs = dir.listFiles();
            if (fs == null || (src != null && src.optString("folder_uri").isEmpty())) return out;
            Arrays.sort(fs);
            for (File f : fs) {
                if (!f.getName().matches("(?i).*\\.(mp4|mkv|mov|webm)$")) continue;
                JSONObject o = new JSONObject().put("name", f.getName()).put("path", (src == null ? "_picked" : src.getString("id")) + "/" + f.getName())
                        .put("size", f.length()).put("mtime", f.lastModified() / 1000.0);
                if (chatFile(f) != null) o.put("chat", true);
                out.put(o);
            }
            return out;
        }

        public MiniHttp.RangeSource openMedia(String id) throws Exception {
            File f = resolve(id);
            return fileSource(f);
        }

        static MiniHttp.RangeSource fileSource(File f) {
            return new MiniHttp.RangeSource() {
                public long size() { return f.length(); }
                public InputStream open(long off) throws IOException {
                    FileInputStream in = new FileInputStream(f);
                    in.getChannel().position(off);
                    return in;
                }
            };
        }

        public String mimeOf(String id) { return id.toLowerCase().endsWith(".png") ? "image/png" : "video/mp4"; }

        public JSONObject probe(String id) throws Exception {
            File f = resolve(id);
            JSONObject j = new JSONObject(sh("ffprobe", "-v", "error", "-print_format", "json", "-show_format", "-show_streams", f.getPath()));
            JSONObject o = new JSONObject();
            o.put("duration", j.getJSONObject("format").getDouble("duration"));
            o.put("has_audio", false);
            JSONArray st = j.getJSONArray("streams");
            for (int i = 0; i < st.length(); i++) {
                JSONObject s = st.getJSONObject(i);
                if (s.getString("codec_type").equals("video")) {
                    o.put("width", s.getInt("width")).put("height", s.getInt("height")).put("has_video", true);
                }
                if (s.getString("codec_type").equals("audio")) o.put("has_audio", true);
            }
            o.put("has_srt", sidecarSrt(id) != null);
            File chat = chatFile(f);
            o.put("has_chat", chat != null);
            if (chat != null && id.startsWith("_picked/")) o.put("chat_name", chat.getName());
            return o;
        }

        static File chatFile(File video) {
            String n = video.getName(), stem = n.contains(".") ? n.substring(0, n.lastIndexOf('.')) : n;
            File[] fs = video.getParentFile().listFiles();
            if (fs == null) return null;
            for (File c : fs) if (ChatMoments.isChatFor(c.getName(), stem)) return c;
            return null;
        }

        public byte[] frameJpeg(String id, double t, int w) throws Exception {
            File out = File.createTempFile("frame", ".jpg");
            sh("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-ss", String.valueOf(t), "-i", resolve(id).getPath(),
                    "-frames:v", "1", "-vf", "scale=" + w + ":-2", out.getPath());
            byte[] b = Files.readAllBytes(out.toPath());
            out.delete();
            return b;
        }

        public String sidecarSrt(String id) {
            try {
                File f = resolve(id);
                File s = new File(f.getParent(), f.getName().replaceAll("\\.[^.]+$", ".srt"));
                return s.isFile() ? new String(Files.readAllBytes(s.toPath()), StandardCharsets.UTF_8) : null;
            } catch (Exception e) {
                return null;
            }
        }

        static final String[][] MODELS = {
                {"whisper-small", "whisper", "Whisper small — точная (190 МБ)", "Хорошо понимает русский, ставит знаки препинания."},
                {"whisper-base", "whisper", "Whisper base — быстрее (60 МБ)", "В 2–3 раза быстрее small, ошибается чаще."},
                {"ru-small", "vosk", "Vosk, русский — очень быстрая (45 МБ)", "Быстро, но ошибается чаще и пишет без знаков препинания."}};

        File modelMark(String id) { return new File(store.dir, "models/mock/" + id); }

        boolean modelInstalled() {
            for (String[] m : MODELS) if (modelMark(m[0]).isFile()) return true;
            return false;
        }

        public JSONObject status() {
            try {
                JSONObject o = new JSONObject().put("version", Api.VERSION).put("platform", "android-app").put("auth", false)
                        .put("termux", false).put("opencv", false).put("default_model", "whisper-small")
                        .put("media_root", "папки, выбранные для источников").put("output_dir", "Галерея: Movies/TikTokClips");
                o.put("checks", new JSONArray().put(new JSONObject().put("name", "Кодировщик видео H.264").put("ok", true))
                        .put(new JSONObject().put("name", "Кодировщик звука AAC").put("ok", true)));
                o.put("ffmpeg", new JSONObject().put("ffmpeg", true).put("libass", true).put("libx264", true).put("problems", new JSONArray()));
                JSONArray ms = new JSONArray(), ids = new JSONArray();
                for (String[] m : MODELS) {
                    ms.put(new JSONObject().put("id", m[0]).put("engine", m[1]).put("name", m[2]).put("note", m[3])
                            .put("installed", modelMark(m[0]).isFile()).put("supported", true).put("recommended", m[0].equals("whisper-small")));
                    ids.put(m[0]);
                }
                o.put("asr_models", ms);
                o.put("model_ids", ids);
                o.put("asr", new JSONObject().put("vosk", new JSONObject().put("ok", modelInstalled()).put("models", new JSONArray()).put("message", "")));
                return o;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public JSONObject faceDetect(String id, double t) throws Exception { return new JSONObject().put("ok", false).put("error", "нет"); }

        /** Та же логика, что в приложении (ядро Layout), кадры — из ffmpeg; лиц здесь не ищем. */
        public JSONObject detectLayout(String id) throws Exception {
            File f = resolve(id);
            JSONObject info = probe(id);
            double dur = info.getDouble("duration");
            int sw = info.getInt("width"), shh = info.getInt("height"), w = 320, h = Math.round(320f * shh / sw) / 2 * 2;
            List<int[]> frames = new ArrayList<>();
            for (int k = 0; k < 8; k++) {
                double t = dur * (0.08 + 0.84 * k / 7);
                File out = File.createTempFile("gray", ".raw");
                sh("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-ss", String.valueOf(t), "-i", f.getPath(), "-frames:v", "1",
                        "-vf", "scale=" + w + ":" + h, "-f", "rawvideo", "-pix_fmt", "rgb24", out.getPath());
                byte[] b = Files.readAllBytes(out.toPath());
                out.delete();
                if (b.length != w * h * 3) continue;
                int[] px = new int[w * h];
                for (int i = 0; i < px.length; i++) px[i] = (b[3 * i] & 255) << 16 | (b[3 * i + 1] & 255) << 8 | (b[3 * i + 2] & 255);
                frames.add(px);
            }
            return Layout.detect(frames.toArray(new int[0][]), w, h, new ArrayList<float[]>(), sw, shh).toJson();
        }

        File clipFile(JSONObject m) { return new File(store.dir, "clips/" + m.optString("id") + ".mp4"); }
        public MiniHttp.RangeSource openClip(JSONObject m) { return fileSource(clipFile(m)); }
        public boolean clipExists(JSONObject m) { return clipFile(m).isFile(); }
        public void deleteClip(JSONObject m) { clipFile(m).delete(); }

        public byte[] asset(String p) {
            File f = new File("app/src/main/assets/web/" + p);
            try { return f.isFile() ? Files.readAllBytes(f.toPath()) : null; } catch (IOException e) { return null; }
        }

        // ---------------- задачи (упрощённые: сборка через ffmpeg, логика субтитров — настоящая из ядра)
        JSONObject clip(JobQueue.Ctx ctx, Api api) throws Exception {
            JSONObject p = ctx.params;
            String file = p.getString("file");
            File src = resolve(file);
            double s = p.getDouble("start"), e = p.getDouble("end");
            List<Subs.Word> words = new ArrayList<>();
            String srt = "srt".equals(p.optString("sub_source")) ? sidecarSrt(file) : null;
            if (srt != null) words = Subs.wordsFromCues(Subs.parseSrt(srt), s, e);
            else ctx.warn("Модель распознавания речи не скачана: «Настройки» → «Распознавание речи» → «Скачать». Клип сделан без субтитров.");
            List<Subs.Line> lines = Subs.groupWords(words, 3);
            String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            JSONObject meta = new JSONObject().put("id", id).put("created", System.currentTimeMillis() / 1000.0)
                    .put("source_id", p.optString("source_id", "")).put("source_title", "Стример Вася").put("src", file)
                    .put("start", s).put("end", e).put("mirror", p.optBoolean("mirror", true)).put("subtitles", true)
                    .put("sub_style", "classic").put("lines", Subs.linesToJson(lines)).put("version", 0).put("published", false)
                    .put("filename", "2026-10-30_120000_clip.mp4").put("gallery_path", "Movies/TikTokClips/2026-10-30_120000_clip.mp4")
                    .put("media_uri", "content://media/external/video/media/1").put("caption", store.getSource(p.optString("source_id", "")) != null
                            ? Captions.stream(store.getSource(p.optString("source_id")).optString("title"), store.getSource(p.optString("source_id")).optString("twitch_login"), file)
                            : !store.settings().optString("caption_name").isEmpty()
                            ? Captions.stream(store.settings().optString("caption_name"), store.settings().optString("caption_login"), file) // как в ClipJobs
                            : Captions.make(file));
            if (p.optBoolean("night")) meta.put("night", true);
            for (String k : new String[]{"banner", "banner_pos", "banner_size", "banner_chroma", "banner_plate"}) if (p.has(k)) meta.put(k, p.get(k));
            ctx.progress(0.5, "Собираю видео");
            render(src, s, e, clipFile(meta));
            meta.put("warnings", ctx.warnings());
            api.saveClip(meta);
            return new JSONObject().put("clip_id", id);
        }

        void render(File src, double s, double e, File out) throws Exception {
            out.getParentFile().mkdirs();
            sh("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-ss", String.valueOf(s), "-i", src.getPath(), "-t", String.valueOf(e - s),
                    "-vf", "scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:-1:-1", "-c:v", "libx264",
                    "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-movflags", "+faststart", out.getPath());
        }

        JSONObject rebuild(JobQueue.Ctx ctx, Api api) throws Exception {
            JSONObject m = api.loadClip(ctx.params.getString("clip_id"));
            double dur = m.getDouble("end") - m.getDouble("start");
            if (ctx.params.has("lines")) m.put("lines", Subs.linesToJson(Subs.applyEdits(ctx.params.getJSONArray("lines"), Subs.linesFromJson(m.optJSONArray("lines")), dur)));
            m.put("version", m.optInt("version") + 1);
            render(resolve(m.getString("src")), m.getDouble("start"), m.getDouble("end"), clipFile(m));
            api.saveClip(m);
            return new JSONObject().put("clip_id", m.getString("id"));
        }

        JSONObject moments(JobQueue.Ctx ctx) throws Exception {
            return analyze(ctx, ctx.params.getString("file"), ctx.params.optInt("length", 10), 0, 1);
        }

        JSONObject analyze(JobQueue.Ctx ctx, String fileId, int length, double lo, double hi) throws Exception {
            ctx.params.put("file", fileId).put("length", length);
            File f = resolve(ctx.params.getString("file"));
            File chatF = chatFile(f);
            if (chatF != null) {
                ChatMoments.Chat chat;
                try (InputStream in = new FileInputStream(chatF)) { chat = ChatMoments.read(in); }
                double dur = probe(ctx.params.getString("file")).getDouble("duration");
                return new JSONObject().put("file", ctx.params.getString("file")).put("length", ctx.params.optInt("length", 10)).put("source", "chat")
                        .put("chat_messages", chat.n).put("candidates", ChatMoments.find(chat, dur, ctx.params.optInt("length", 10), 8)).put("speech", true);
            }

            String out = sh("ffmpeg", "-hide_banner", "-nostdin", "-i", f.getPath(), "-vn", "-af",
                    "aresample=8000,asetnsamples=n=8000:p=0,astats=metadata=1:reset=1,ametadata=mode=print:key=lavfi.astats.Overall.RMS_level:file=-",
                    "-f", "null", "-");
            List<Double> lv = new ArrayList<>();
            for (String line : out.split("\n")) {
                if (!line.contains("RMS_level=")) continue;
                String v = line.split("=", 2)[1].trim();
                try { double d = Double.parseDouble(v); lv.add(Double.isInfinite(d) ? -90 : d); } catch (NumberFormatException ex) { lv.add(-90.0); }
            }
            double[] levels = new double[lv.size()];
            for (int i = 0; i < levels.length; i++) levels[i] = lv.get(i);
            String srt = sidecarSrt(ctx.params.getString("file"));
            int len = ctx.params.optInt("length", 10);
            return new JSONObject().put("file", ctx.params.getString("file")).put("length", len)
                    .put("candidates", Moments.find(levels, len, srt == null ? null : Subs.parseSrt(srt), 8)).put("speech", srt != null);
        }

        JSONObject model(JobQueue.Ctx ctx) throws Exception {
            File mark = modelMark(ctx.params.getString("model"));
            if ("delete".equals(ctx.params.optString("action"))) {
                mark.delete();
                return new JSONObject().put("model", ctx.params.getString("model")).put("deleted", true);
            }
            for (int i = 1; i <= 10; i++) {
                ctx.progress(i / 10.0, "Скачиваю модель «Whisper small — точная (190 МБ)»");
                Thread.sleep(150);
            }
            mark.getParentFile().mkdirs();
            Files.write(mark.toPath(), new byte[0]);
            return new JSONObject().put("model", ctx.params.getString("model"));
        }
    }
}
