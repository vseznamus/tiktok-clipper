package ru.tiktokclipper.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Twitch по официальному API Helix (нужны Client ID и Client Secret из dev.twitch.tv — вводятся в «Настройках»):
 * последние записи эфиров стримера и моменты, из которых зрители нарезали больше всего клипов
 * (у клипа известна минута в записи). Видео не скачивается: моменты режутся из записи, которая лежит в папке стримера.
 */
public final class Discover {
    /** Ответ HTTP: код и тело. */
    public static final class Resp {
        public final int code;
        public final String body;

        public Resp(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    public interface Http {
        /** body == null — GET, иначе POST (application/x-www-form-urlencoded). */
        Resp request(String url, Map<String, String> headers, String body) throws IOException;
    }

    private final Store store;
    private final Http http;
    private final Map<String, Object[]> cache = new HashMap<>(); // ключ → {время, JSONObject}
    private String twToken;
    private long twTokenUntil;

    public Discover(Store store, Http http) {
        this.store = store;
        this.http = http != null ? http : Discover::realRequest;
    }

    static final long LIST_TTL = 30 * 60 * 1000L, MOMENTS_TTL = 6 * 3600 * 1000L;

    // ================================================================== Twitch

    public JSONObject twitch(boolean refresh) throws Exception {
        JSONObject se = store.settings();
        String id = se.optString("twitch_client_id").trim(), secret = se.optString("twitch_client_secret").trim();
        JSONObject out = new JSONObject();
        out.put("configured", !id.isEmpty() && !secret.isEmpty());
        JSONArray list = new JSONArray();
        out.put("streamers", list);
        List<JSONObject> srcs = targets("twitch_login", "");
        out.put("has_sources", !srcs.isEmpty());
        if (!out.getBoolean("configured") || srcs.isEmpty()) return out;
        for (JSONObject src : srcs) {
            String login = twitchLogin(src.optString("twitch_login"));
            JSONObject st = cached("tw:" + login, LIST_TTL, refresh);
            if (st == null) {
                st = new JSONObject();
                try {
                    st = twitchStreamer(id, secret, login);
                    put("tw:" + login, st);
                } catch (ApiError e) {
                    st.put("ok", false).put("error", e.getMessage());
                }
            }
            st.put("source_id", src.optString("id")).put("title", src.optString("title")).put("login", login)
                    .put("watch", src.optBoolean("watch"));
            attachLinks(st.optJSONArray("vods"));
            list.put(st);
        }
        return out;
    }

    private JSONObject twitchStreamer(String id, String secret, String login) throws Exception {
        JSONObject out = new JSONObject();
        if (login.isEmpty()) throw new ApiError(400, "Не указан логин Twitch в настройках источника");
        JSONArray users = twGet(id, secret, "users?login=" + enc(login)).optJSONArray("data");
        if (users == null || users.length() == 0) throw new ApiError(404, "Канал Twitch «" + login + "» не найден");
        JSONObject user = users.getJSONObject(0);
        String uid = user.getString("id");
        out.put("ok", true).put("name", user.optString("display_name", login));
        JSONArray vids = twGet(id, secret, "videos?user_id=" + uid + "&type=archive&first=6").optJSONArray("data");
        JSONArray vods = new JSONArray();
        Map<String, JSONObject> byId = new HashMap<>();
        Instant oldest = null; // клипы ищем с начала самой старой записи (без записей — за две недели)
        for (int i = 0; vids != null && i < vids.length(); i++) {
            JSONObject v = vids.getJSONObject(i);
            JSONObject o = new JSONObject();
            o.put("key", "tw:" + v.getString("id"));
            o.put("id", v.getString("id"));
            o.put("title", v.optString("title"));
            o.put("created_at", v.optString("created_at"));
            o.put("duration", twDuration(v.optString("duration")));
            o.put("url", v.optString("url"));
            o.put("views", v.optLong("view_count"));
            o.put("moments", new JSONArray());
            vods.put(o);
            byId.put(o.getString("id"), o);
            try {
                Instant c = Instant.parse(v.optString("created_at"));
                if (oldest == null || c.isBefore(oldest)) oldest = c;
            } catch (Exception ignored) {
                // дата в другом формате — берём последние две недели
            }
        }
        if (oldest == null) oldest = Instant.now().minusSeconds(14 * 86400L);
        // клипы за период записей: зрители сами отмечают лучшие моменты
        List<JSONObject> clips = new ArrayList<>();
        String after = null;
        for (int page = 0; page < 3; page++) {
            JSONObject r = twGet(id, secret, "clips?broadcaster_id=" + uid + "&first=100&started_at=" + enc(oldest.toString())
                    + (after != null ? "&after=" + enc(after) : ""));
            JSONArray d = r.optJSONArray("data");
            for (int i = 0; d != null && i < d.length(); i++) clips.add(d.getJSONObject(i));
            after = r.optJSONObject("pagination") != null ? r.getJSONObject("pagination").optString("cursor", null) : null;
            if (after == null || after.isEmpty() || d == null || d.length() < 100) break;
        }
        Map<String, List<JSONObject>> perVod = new HashMap<>();
        int loose = 0;
        for (JSONObject c : clips) {
            String vid = c.optString("video_id");
            if (byId.containsKey(vid) && !c.isNull("vod_offset") && c.has("vod_offset")) {
                List<JSONObject> l = perVod.get(vid);
                if (l == null) perVod.put(vid, l = new ArrayList<>());
                l.add(c);
            } else loose++;
        }
        for (Map.Entry<String, List<JSONObject>> e : perVod.entrySet()) {
            byId.get(e.getKey()).put("moments", twitchMoments(e.getValue()));
        }
        out.put("vods", vods);
        out.put("clips_total", clips.size());
        out.put("clips_without_vod", loose);
        return out;
    }

    /** Клипы одной записи → моменты: клипы, начатые в пределах 45 с, — один момент; вес — просмотры и число клипов. */
    static JSONArray twitchMoments(List<JSONObject> clips) throws JSONException {
        List<JSONObject> cs = new ArrayList<>(clips);
        Collections.sort(cs, (a, b) -> Double.compare(a.optDouble("vod_offset"), b.optDouble("vod_offset")));
        List<JSONObject> moms = new ArrayList<>();
        JSONObject cur = null;
        for (JSONObject c : cs) {
            double off = c.optDouble("vod_offset"), dur = c.optDouble("duration", 30);
            long views = c.optLong("view_count");
            if (cur == null || off - cur.getDouble("t") > 45) {
                cur = new JSONObject().put("t", off).put("end", off + dur).put("clips", 0).put("views", 0L)
                        .put("title", c.optString("title")).put("clip_url", c.optString("url")).put("_best", -1L);
                moms.add(cur);
            }
            cur.put("clips", cur.getInt("clips") + 1);
            cur.put("views", cur.getLong("views") + views);
            cur.put("end", Math.max(cur.getDouble("end"), off + dur));
            if (views > cur.getLong("_best")) {
                cur.put("_best", views).put("title", c.optString("title")).put("clip_url", c.optString("url"));
            }
        }
        for (JSONObject m : moms) {
            m.remove("_best");
            m.put("score", m.getLong("views") + 30.0 * m.getInt("clips"));
        }
        Collections.sort(moms, (a, b) -> Double.compare(b.optDouble("score"), a.optDouble("score")));
        JSONArray out = new JSONArray();
        for (int i = 0; i < moms.size() && i < 6; i++) out.put(moms.get(i));
        return out;
    }

    /**
     * Моменты клипов зрителей для файла записи: запись Twitch с той же датой эфира (дата из имени файла,
     * иначе дата изменения файла) или та, к которой файл уже привязан. null — Twitch не настроен или запись не нашлась.
     */
    public JSONArray momentsForFile(JSONObject src, String filePath, String fileName, double mtime) throws Exception {
        if (src == null || src.optString("twitch_login").trim().isEmpty()) return null;
        JSONObject se = store.settings();
        if (se.optString("twitch_client_id").trim().isEmpty() || se.optString("twitch_client_secret").trim().isEmpty()) return null;
        JSONObject st = twitchStreamer(se.optString("twitch_client_id").trim(), se.optString("twitch_client_secret").trim(), twitchLogin(src.optString("twitch_login")));
        JSONArray vods = st.optJSONArray("vods");
        if (vods == null) return null;
        JSONObject links = Store.readObject(linksFile());
        String date = fileDate(fileName);
        if (date == null && mtime > 0) date = java.time.Instant.ofEpochMilli((long) (mtime * 1000)).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();
        for (int i = 0; i < vods.length(); i++) {
            JSONObject v = vods.getJSONObject(i);
            boolean linked = filePath.equals(links.optString(v.optString("key"), ""));
            String vd = v.optString("created_at");
            String local = "";
            try {
                local = java.time.Instant.parse(vd).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();
            } catch (Exception ignored) {
                // дата в другом формате
            }
            if (linked || (date != null && (vd.startsWith(date) || local.equals(date)))) {
                JSONArray m = v.optJSONArray("moments");
                return m != null && m.length() > 0 ? m : null;
            }
        }
        return null;
    }

    static final Pattern FILE_DATE = Pattern.compile("(20\\d{2})[-_.]?(\\d{2})[-_.]?(\\d{2})");

    /** «stream_2026-10-28.mp4» → «2026-10-28». */
    static String fileDate(String name) {
        Matcher m = FILE_DATE.matcher(name == null ? "" : name);
        return m.find() ? m.group(1) + "-" + m.group(2) + "-" + m.group(3) : null;
    }

    /** «3h2m10s» → секунды. */
    static double twDuration(String s) {
        Matcher m = Pattern.compile("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?").matcher(s == null ? "" : s);
        if (!m.matches()) return 0;
        double t = 0;
        if (m.group(1) != null) t += 3600 * Integer.parseInt(m.group(1));
        if (m.group(2) != null) t += 60 * Integer.parseInt(m.group(2));
        if (m.group(3) != null) t += Integer.parseInt(m.group(3));
        return t;
    }

    private JSONObject twGet(String id, String secret, String path) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, String> h = new HashMap<>();
            h.put("Client-Id", id);
            h.put("Authorization", "Bearer " + twToken(id, secret, attempt > 0));
            Resp r = call("https://api.twitch.tv/helix/" + path, h, null, "Twitch");
            if (r.code == 401 && attempt == 0) continue; // ключ доступа устарел — получим новый
            if (r.code == 401 || r.code == 403) throw new ApiError(502, "Twitch не принял ключи. Проверьте Client ID и Client Secret в «Настройках».");
            if (r.code == 429) throw new ApiError(502, "Twitch просит подождать (слишком много запросов). Попробуйте через минуту.");
            if (r.code != 200) throw new ApiError(502, "Twitch ответил " + r.code + ". Попробуйте позже.");
            return new JSONObject(r.body);
        }
        throw new ApiError(502, "Twitch не принял ключи");
    }

    private synchronized String twToken(String id, String secret, boolean renew) throws Exception {
        if (!renew && twToken != null && System.currentTimeMillis() < twTokenUntil) return twToken;
        Resp r = call("https://id.twitch.tv/oauth2/token", new HashMap<String, String>(),
                "client_id=" + enc(id) + "&client_secret=" + enc(secret) + "&grant_type=client_credentials", "Twitch");
        if (r.code == 400 || r.code == 401 || r.code == 403) {
            throw new ApiError(502, "Twitch не принял ключи. Проверьте Client ID и Client Secret в «Настройках».");
        }
        if (r.code != 200) throw new ApiError(502, "Twitch ответил " + r.code + " при входе. Попробуйте позже.");
        JSONObject o = new JSONObject(r.body);
        twToken = o.getString("access_token");
        twTokenUntil = System.currentTimeMillis() + Math.max(60, o.optLong("expires_in", 3600) - 300) * 1000L;
        return twToken;
    }

    // ================================================================== привязка к файлам

    /** Запоминает, какой файл в папке источника — это запись эфира или видео (ключ «tw:…» / «yt:…»). */
    public void link(String key, String file) throws Exception {
        if (!key.matches("(tw|yt):[A-Za-z0-9_-]{1,40}")) throw new ApiError(400, "Неверный ключ");
        JSONObject links = Store.readObject(linksFile());
        if (file == null || file.isEmpty()) links.remove(key);
        else links.put(key, file);
        Store.writeJson(linksFile(), links);
    }

    private java.io.File linksFile() {
        return new java.io.File(store.dir, "discover_links.json");
    }

    private void attachLinks(JSONArray items) throws JSONException {
        if (items == null) return;
        JSONObject links = Store.readObject(linksFile());
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.getJSONObject(i);
            String f = links.optString(o.optString("key"), "");
            if (!f.isEmpty()) o.put("file", f);
            else o.remove("file");
        }
    }

    // ================================================================== общее

    /** «https://www.twitch.tv/Streamer_1» → «streamer_1». */
    public static String twitchLogin(String s) {
        return (s == null ? "" : s).trim().toLowerCase(java.util.Locale.ROOT).replaceAll("^.*twitch\\.tv/", "")
                .replaceAll("[/?#].*$", "").replaceAll("[^a-z0-9_]", "");
    }

    /**
     * Кого показывать: источники с заполненным полем (можно несколько через запятую) и список «Слежу без источника».
     * У записей из списка source_id пустой — файл для нарезки выбирается кнопкой «Выбрать видео на телефоне».
     */
    private List<JSONObject> targets(String field, String watch) throws JSONException {
        List<JSONObject> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        JSONArray s = store.sources().getJSONArray("sources");
        for (int i = 0; i < s.length(); i++) {
            JSONObject o = s.getJSONObject(i);
            if (!o.optBoolean("enabled", true)) continue;
            String[] vals = o.optString(field).split("[,;\\s]+");
            for (String v : vals) {
                v = v.trim();
                if (v.isEmpty() || !seen.add(v.toLowerCase(java.util.Locale.ROOT))) continue;
                out.add(new JSONObject().put("id", o.optString("id")).put("title", vals.length > 1 ? v : o.optString("title")).put(field, v));
            }
        }
        for (String v : (watch == null ? "" : watch).split("[,;\\s]+")) {
            v = v.trim();
            if (v.isEmpty() || !seen.add(v.toLowerCase(java.util.Locale.ROOT))) continue;
            out.add(new JSONObject().put("id", "").put("title", v).put(field, v).put("watch", true));
        }
        return out;
    }

    private synchronized JSONObject cached(String key, long ttl, boolean refresh) throws JSONException {
        Object[] hit = cache.get(key);
        if (refresh || hit == null || System.currentTimeMillis() - (Long) hit[0] > ttl) return null;
        return new JSONObject(hit[1].toString());
    }

    private synchronized void put(String key, JSONObject v) {
        cache.put(key, new Object[]{System.currentTimeMillis(), v.toString()});
    }

    /** Сбрасывает кэш (например, после смены ключей). */
    public synchronized void clearCache() {
        cache.clear();
        twToken = null;
    }

    private Resp call(String url, Map<String, String> h, String body, String service) throws ApiError {
        try {
            return http.request(url, h, body);
        } catch (IOException e) {
            throw new ApiError(502, service + " недоступен: нет интернета или сайт не отвечает.");
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static Resp realRequest(String url, Map<String, String> headers, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        if (body != null) {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) {
            try (InputStream i = in) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = i.read(buf)) > 0) bo.write(buf, 0, n);
            }
        }
        return new Resp(code, new String(bo.toByteArray(), StandardCharsets.UTF_8));
    }
}
