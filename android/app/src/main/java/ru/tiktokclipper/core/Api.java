package ru.tiktokclipper.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * HTTP API для веб-интерфейса внутри приложения — те же адреса и ответы, что у Python-сервера
 * (tclip/server.py), поэтому интерфейс (assets/web) общий.
 * Доступ: только 127.0.0.1 и только с ключом сессии (cookie), который знает само приложение —
 * другие приложения на телефоне не смогут управлять нарезкой.
 */
public class Api implements MiniHttp.Handler {
    public static final String VERSION = "1.1.0-android";
    public static final String COOKIE = "clipper_k";

    private final Store store;
    private final Platform platform;
    private final JobQueue queue;
    private final String token;
    public volatile LocalDate fixedToday; // для тестов

    public Api(Store store, Platform platform, JobQueue queue, String token) {
        this.store = store;
        this.platform = platform;
        this.queue = queue;
        this.token = token;
    }

    LocalDate today() {
        return fixedToday != null ? fixedToday : LocalDate.now();
    }

    // ------------------------------------------------------------ клипы на диске

    public JSONObject loadClip(String id) {
        if (id == null || !id.matches("[A-Za-z0-9]+")) return null;
        File f = store.clipMetaFile(id);
        return f.isFile() ? Store.readObject(f) : null;
    }

    public void saveClip(JSONObject meta) {
        Store.writeJson(store.clipMetaFile(meta.optString("id")), meta);
    }

    public JSONArray listClips() {
        List<JSONObject> all = new ArrayList<>();
        File[] fs = new File(store.dir, "clips").listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (!f.getName().endsWith(".json")) continue;
                JSONObject m = Store.readObject(f);
                if (m.length() == 0) continue;
                JSONObject s = new JSONObject();
                try {
                    for (String k : new String[]{"id", "created", "source_id", "source_title", "src", "start", "end",
                            "filename", "version", "published", "warnings"}) s.put(k, m.opt(k));
                    s.put("exists", platform.clipExists(m));
                } catch (JSONException ignored) {
                }
                all.add(s);
            }
        }
        all.sort((a, b) -> Double.compare(b.optDouble("created", 0), a.optDouble("created", 0)));
        return new JSONArray(all);
    }

    // ------------------------------------------------------------ вход

    static boolean eq(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static boolean loopbackHost(String host) {
        if (host == null) return false;
        String h = host.startsWith("[") ? host.substring(1, Math.max(1, host.indexOf(']'))) : host.split(":")[0];
        return h.equals("127.0.0.1") || h.equals("localhost") || h.equals("::1");
    }

    @Override
    public MiniHttp.Response handle(MiniHttp.Request r) throws Exception {
        if (!loopbackHost(r.header("host"))) return MiniHttp.Response.text(403, "Доступ запрещён");
        if (r.path.equals("/auth")) {
            if (!eq(r.q("k", ""), token)) return MiniHttp.Response.text(401, "Неверный ключ");
            MiniHttp.Response resp = MiniHttp.Response.redirect("/" + (r.q("h", "").isEmpty() ? "" : "#" + r.q("h", "")));
            resp.headers.put("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=31536000");
            return resp;
        }
        if (!eq(r.cookie(COOKIE), token)) {
            if (r.path.startsWith("/api/")) return MiniHttp.Response.error(401, "Нет доступа");
            return MiniHttp.Response.text(401, "Откройте программу через значок «Нарезка».");
        }
        MiniHttp.Response resp = route(r);
        if (resp == null) return MiniHttp.Response.error(404, "Не найдено");
        resp.headers.put("Content-Security-Policy", "default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; "
                + "style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'");
        return resp;
    }

    private MiniHttp.Response route(MiniHttp.Request r) throws Exception {
        String p = r.path, m = r.method;
        if (m.equals("GET") && (p.equals("/") || p.equals("/index.html"))) return asset("index.html");
        if (m.equals("GET") && p.startsWith("/static/")) return asset(p.substring(8));
        if (m.equals("GET") && p.equals("/manifest.webmanifest")) return asset("manifest.webmanifest");
        if (m.equals("GET") && p.startsWith("/media/")) return media(r, p.substring(7));
        if (m.equals("GET") && p.startsWith("/clips/") && p.endsWith(".mp4")) return clipFile(r, p.substring(7, p.length() - 4));
        if (!p.startsWith("/api/")) return null;
        String[] seg = p.substring(5).split("/");
        String a = seg[0];
        switch (a) {
            case "status": return MiniHttp.Response.json(platform.status());
            case "config": return MiniHttp.Response.json(config());
            case "schedule":
                if (seg.length == 1 && m.equals("PUT")) return saveSchedule(r.json());
                if (seg.length == 2 && m.equals("POST")) return scheduleAction(seg[1], r.json());
                break;
            case "settings":
                if (m.equals("PUT")) return MiniHttp.Response.json(saveSettings(r.json()));
                break;
            case "sources":
                if (seg.length == 1 && m.equals("POST")) return MiniHttp.Response.json(store.upsertSource(cleanSource(r.json())));
                if (seg.length == 2 && m.equals("DELETE")) {
                    store.deleteSource(seg[1]);
                    return ok();
                }
                if (seg.length == 3 && seg[2].equals("profile") && m.equals("PUT")) {
                    JSONObject b = r.json();
                    if (b.has("cam_rect") && !b.isNull("cam_rect")) {
                        JSONArray rc = b.optJSONArray("cam_rect");
                        if (rc == null || rc.length() != 4) throw new ApiError(400, "Неверная рамка");
                    }
                    return MiniHttp.Response.json(Store.fullProfile(store.updateProfile(seg[1], b)));
                }
                break;
            case "folders": {
                JSONObject o = new JSONObject();
                o.put("root", "");
                o.put("folders", new JSONArray());
                return MiniHttp.Response.json(o);
            }
            case "files": return files(r.q("source", ""));
            case "probe": return MiniHttp.Response.json(platform.probe(r.q("file", "")));
            case "frame": {
                double t = Math.max(0, Double.parseDouble(r.q("t", "0")));
                int w = Math.min(1280, Math.max(160, Integer.parseInt(r.q("w", "720"))));
                MiniHttp.Response resp = MiniHttp.Response.bytes("image/jpeg", platform.frameJpeg(r.q("file", ""), t, w));
                resp.headers.put("Cache-Control", "max-age=3600");
                return resp;
            }
            case "upload":
                throw new ApiError(400, "В приложении видео выбирается кнопкой «Выбрать видео на телефоне».");
            case "today": return todayView();
            case "feed": {
                JSONObject src = store.getSource(seg.length > 1 ? seg[1] : "");
                if (src == null) throw new ApiError(404, "Источник не найден");
                return MiniHttp.Response.json(platform.feed(src));
            }
            case "jobs":
                if (seg.length == 1 && m.equals("GET")) return MiniHttp.Response.json(queue.list());
                if (seg.length == 1 && m.equals("POST")) return submitJob(r.json());
                if (seg.length == 2 && m.equals("GET")) {
                    JSONObject j = queue.get(seg[1]);
                    if (j == null) throw new ApiError(404, "Задача не найдена");
                    return MiniHttp.Response.json(j);
                }
                if (seg.length == 3 && seg[2].equals("cancel")) {
                    JSONObject o = new JSONObject();
                    o.put("ok", queue.cancel(seg[1]));
                    return MiniHttp.Response.json(o);
                }
                break;
            case "clips":
                if (seg.length == 1) return MiniHttp.Response.json(listClips());
                if (seg.length == 2 && m.equals("GET")) return clipInfo(seg[1]);
                if (seg.length == 2 && m.equals("DELETE")) {
                    JSONObject meta = loadClip(seg[1]);
                    if (meta != null) {
                        platform.deleteClip(meta);
                        store.clipMetaFile(seg[1]).delete();
                    }
                    return ok();
                }
                if (seg.length == 3 && seg[2].equals("rebuild")) {
                    JSONObject meta = loadClip(seg[1]);
                    if (meta == null) throw new ApiError(404, "Клип не найден");
                    JSONObject b = r.json(), params = new JSONObject();
                    params.put("clip_id", seg[1]);
                    for (String k : new String[]{"lines", "mirror", "sub_style", "subtitles"}) if (b.has(k)) params.put(k, b.get(k));
                    return MiniHttp.Response.json(queue.submit("rebuild", params, "Пересборка: " + meta.optString("filename")));
                }
                break;
            case "facedetect": {
                JSONObject b = r.json();
                return MiniHttp.Response.json(platform.faceDetect(b.optString("file"), b.optDouble("t", 0)));
            }
            default:
                break;
        }
        return null;
    }

    private MiniHttp.Response ok() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        return MiniHttp.Response.json(o);
    }

    private MiniHttp.Response asset(String path) {
        if (path.contains("..")) return MiniHttp.Response.text(403, "");
        byte[] data = platform.asset(path);
        if (data == null) return null;
        String type = path.endsWith(".html") ? "text/html; charset=utf-8" : path.endsWith(".js") ? "text/javascript; charset=utf-8"
                : path.endsWith(".css") ? "text/css; charset=utf-8" : path.endsWith(".png") ? "image/png"
                : path.endsWith(".json") || path.endsWith(".webmanifest") ? "application/json" : "application/octet-stream";
        MiniHttp.Response r = MiniHttp.Response.bytes(type, data);
        r.headers.put("Cache-Control", "no-cache");
        return r;
    }

    private MiniHttp.Response media(MiniHttp.Request r, String fileId) throws Exception {
        return MiniHttp.Response.ranged(r, platform.openMedia(fileId), platform.mimeOf(fileId));
    }

    private MiniHttp.Response clipFile(MiniHttp.Request r, String id) throws Exception {
        JSONObject meta = loadClip(id);
        if (meta == null || !platform.clipExists(meta)) throw new ApiError(404, "Клип не найден");
        return MiniHttp.Response.ranged(r, platform.openClip(meta), "video/mp4");
    }

    private JSONObject config() throws JSONException {
        JSONObject cfg = store.sources();
        JSONArray srcs = cfg.getJSONArray("sources");
        for (int i = 0; i < srcs.length(); i++) srcs.getJSONObject(i).put("full_profile", Store.fullProfile(srcs.getJSONObject(i)));
        JSONObject out = new JSONObject();
        JSONObject sch = new JSONObject();
        for (String k : new String[]{"start_date", "interval_days", "posts_per_block", "type_order"}) sch.put(k, cfg.opt(k));
        out.put("schedule", sch);
        out.put("sources", srcs);
        out.put("settings", store.settings());
        JSONObject styles = new JSONObject(), all = Store.subStyles();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String k = it.next();
            styles.put(k, all.getJSONObject(k).getString("name"));
        }
        out.put("sub_styles", styles);
        JSONObject tn = new JSONObject();
        for (String t : Store.SOURCE_TYPES) tn.put(t, Schedule.typeName(t));
        out.put("type_names", tn);
        out.put("types", new JSONArray(Store.SOURCE_TYPES));
        out.put("models", platform.status().optJSONArray("model_ids"));
        return out;
    }

    private MiniHttp.Response saveSchedule(JSONObject b) throws Exception {
        JSONObject cfg = store.sources();
        if (b.has("start_date")) {
            if (b.isNull("start_date") || b.optString("start_date").isEmpty()) cfg.put("start_date", JSONObject.NULL);
            else {
                try {
                    cfg.put("start_date", LocalDate.parse(b.getString("start_date")).toString());
                } catch (RuntimeException e) {
                    throw new ApiError(400, "Неверная дата старта");
                }
            }
        }
        if (b.has("interval_days")) cfg.put("interval_days", Math.min(30, Math.max(1, b.optInt("interval_days", 3))));
        if (b.has("posts_per_block")) cfg.put("posts_per_block", Math.min(20, Math.max(1, b.optInt("posts_per_block", 3))));
        if (b.has("type_order")) {
            JSONArray in = b.getJSONArray("type_order"), order = new JSONArray();
            for (int i = 0; i < in.length(); i++) if (Store.SOURCE_TYPES.contains(in.optString(i)) && !Store.contains(order, in.optString(i))) order.put(in.optString(i));
            for (String t : Store.SOURCE_TYPES) if (!Store.contains(order, t)) order.put(t);
            cfg.put("type_order", order);
        }
        store.saveSources(cfg);
        return ok();
    }

    private JSONObject saveSettings(JSONObject b) throws Exception {
        JSONObject patch = new JSONObject();
        for (Iterator<String> it = b.keys(); it.hasNext(); ) {
            String k = it.next();
            switch (k) {
                case "asr_backend":
                case "asr_model":
                case "asr_language":
                case "x264_preset":
                    patch.put(k, b.get(k));
                    break;
                case "moment_len":
                    patch.put(k, Math.min(180, Math.max(5, b.optInt(k, 30))));
                    break;
                case "bitrate_mbps":
                    patch.put(k, Math.min(30, Math.max(2, b.optInt(k, 10))));
                    break;
                default:
                    break;
            }
        }
        return store.saveSettings(patch);
    }

    private JSONObject cleanSource(JSONObject b) throws JSONException {
        JSONObject s = new JSONObject();
        for (String k : new String[]{"id", "type", "title", "folder", "folder_uri", "youtube_channel_id", "twitch_login", "enabled", "profile"}) {
            if (b.has(k) && !(k.equals("id") && b.isNull(k))) s.put(k, b.get(k));
        }
        return s;
    }

    private MiniHttp.Response files(String sid) throws Exception {
        JSONObject src = store.getSource(sid);
        JSONObject out = new JSONObject();
        JSONArray files = sid.isEmpty() ? platform.listFiles(null) : src == null ? new JSONArray() : platform.listFiles(src);
        JSONObject st = store.loadState();
        JSONArray used = st.getJSONObject("used_files").optJSONArray(sid);
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.getJSONObject(i);
            f.put("used", used != null && Store.contains(used, f.optString("path")));
        }
        out.put("folder", src != null ? src.optString("folder") : "Выбранные видео");
        out.put("abs", src != null ? src.optString("folder") : "");
        out.put("has_folder", src == null || !src.optString("folder_uri").isEmpty());
        out.put("files", files);
        return MiniHttp.Response.json(out);
    }

    private JSONObject latestClipFor(String sid) {
        JSONArray all = listClips();
        for (int i = 0; i < all.length(); i++) {
            JSONObject c = all.optJSONObject(i);
            if (sid.equals(c.optString("source_id")) && !c.optBoolean("published") && c.optBoolean("exists")) return c;
        }
        return null;
    }

    private MiniHttp.Response todayView() throws Exception {
        JSONObject cfg = store.sources();
        LocalDate d = today();
        List<JSONObject> posts;
        JSONObject st;
        synchronized (Store.stateLock()) {
            st = store.loadState();
            posts = Schedule.upcoming(cfg, st, d, 9);
            store.saveState(st);
        }
        JSONObject out = new JSONObject();
        out.put("today", d.toString());
        if (posts.isEmpty()) {
            out.put("empty", true);
            return MiniHttp.Response.json(out);
        }
        JSONObject cur = posts.get(0);
        JSONObject src = store.getSource(cur.getString("source_id"));
        List<JSONObject> files = new ArrayList<>();
        if (src != null) {
            try {
                JSONArray fa = platform.listFiles(src);
                for (int i = 0; i < fa.length(); i++) files.add(fa.getJSONObject(i));
            } catch (Exception ignored) {
                // папка недоступна — покажем 0 файлов
            }
        }
        JSONArray used = st.getJSONObject("used_files").optJSONArray(cur.getString("source_id"));
        LocalDate due = LocalDate.parse(cur.getString("date"));
        out.put("empty", false);
        out.put("current", cur);
        out.put("due_status", Schedule.dueStatus(due, d));
        out.put("overdue", due.isBefore(d));
        out.put("is_due", !due.isAfter(d));
        out.put("folder", src != null ? src.optString("folder") : "");
        out.put("files_count", files.size());
        out.put("suggest", Schedule.suggestFiles(cur.getString("type"), files, used == null ? new JSONArray() : used));
        JSONArray up = new JSONArray();
        for (int i = 1; i < posts.size(); i++) up.put(posts.get(i));
        out.put("upcoming", up);
        out.put("has_youtube", src != null && !src.optString("youtube_channel_id").isEmpty());
        out.put("has_twitch", false);
        JSONObject clip = latestClipFor(cur.getString("source_id"));
        out.put("clip", clip == null ? JSONObject.NULL : clip);
        JSONArray h = st.getJSONArray("history"), hist = new JSONArray();
        for (int i = h.length() - 1; i >= Math.max(0, h.length() - 5); i--) hist.put(h.get(i));
        out.put("history", hist);
        return MiniHttp.Response.json(out);
    }

    private MiniHttp.Response scheduleAction(String action, JSONObject b) throws Exception {
        JSONObject cfg = store.sources();
        LocalDate d = today();
        String msg;
        synchronized (Store.stateLock()) {
            JSONObject st = store.loadState();
            switch (action) {
                case "publish": {
                    JSONObject clip = b.isNull("clip_id") ? null : loadClip(b.optString("clip_id", null));
                    String file = clip != null ? clip.optString("src") : (b.isNull("file") ? null : b.optString("file", null));
                    JSONObject p = Schedule.publish(cfg, st, d, file, clip != null ? clip.optString("id") : null);
                    if (clip != null) {
                        clip.put("published", true);
                        saveClip(clip);
                    }
                    msg = "Отмечено: «" + p.getString("source_title") + "» пост " + p.getInt("pos") + "/" + p.getInt("posts_per_block") + " опубликован";
                    break;
                }
                case "skip": {
                    JSONObject p = Schedule.skip(cfg, st, d);
                    msg = "Пост " + p.getInt("pos") + "/" + p.getInt("posts_per_block") + " «" + p.getString("source_title") + "» пропущен";
                    break;
                }
                case "postpone":
                    msg = "Перенесено на " + Schedule.postpone(cfg, st, d, b.optInt("days", 1));
                    break;
                case "undo":
                    msg = Schedule.undo(st) != null ? "Последнее действие отменено" : "Нечего отменять";
                    break;
                default:
                    throw new ApiError(404, "Неизвестное действие");
            }
            store.saveState(st);
        }
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("message", msg);
        return MiniHttp.Response.json(o);
    }

    static String fmt(double t) {
        int s = (int) t;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private MiniHttp.Response submitJob(JSONObject b) throws Exception {
        String t = b.optString("type");
        JSONObject p = b.optJSONObject("params");
        if (p == null) p = new JSONObject();
        String title;
        switch (t) {
            case "clip": {
                if (p.optString("file").isEmpty() || !p.has("start") || !p.has("end")) throw new ApiError(400, "Укажите файл, начало и конец");
                double s = p.getDouble("start"), e = p.getDouble("end");
                if (e - s < 1) throw new ApiError(400, "Конец должен быть хотя бы на 1 секунду позже начала");
                JSONObject src = store.getSource(p.optString("source_id", ""));
                String name = p.getString("file");
                name = name.substring(name.lastIndexOf('/') + 1);
                title = "Клип: " + (src != null ? src.optString("title") : name) + " (" + fmt(s) + "–" + fmt(e) + ")";
                break;
            }
            case "moments":
                if (p.optString("file").isEmpty()) throw new ApiError(400, "Не выбран файл");
                title = "Поиск моментов: " + p.getString("file").substring(p.getString("file").lastIndexOf('/') + 1);
                break;
            case "rebuild": {
                JSONObject meta = loadClip(p.optString("clip_id"));
                if (meta == null) throw new ApiError(404, "Клип не найден");
                title = "Пересборка: " + meta.optString("filename");
                break;
            }
            case "model":
                title = ("delete".equals(p.optString("action")) ? "Удаление модели распознавания: " : "Скачивание модели распознавания: ")
                        + p.optString("model");
                break;
            default:
                throw new ApiError(400, "Неизвестный тип задачи");
        }
        return MiniHttp.Response.json(queue.submit(t, p, title));
    }

    private MiniHttp.Response clipInfo(String id) throws Exception {
        JSONObject m = loadClip(id);
        if (m == null) throw new ApiError(404, "Клип не найден");
        m.remove("profile");
        m.put("exists", platform.clipExists(m));
        m.put("url", "/clips/" + id + ".mp4?v=" + m.optInt("version", 0));
        if (!m.has("gallery_path")) m.put("gallery_path", "Movies/TikTokClips/" + m.optString("filename"));
        return MiniHttp.Response.json(m);
    }
}
