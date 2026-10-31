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
 * «Моменты»: где зрители реагировали сильнее всего.
 * Twitch — клипы, которые нарезали зрители (официальный API Helix): у каждого клипа известна минута в записи эфира.
 * YouTube — таймкоды в комментариях (официальный YouTube Data API v3): какие минуты зрители упоминают чаще всего.
 * Видео не скачивается: моменты открываются в файле, который уже лежит в папке источника.
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
        List<JSONObject> srcs = sourcesWith("twitch_login");
        out.put("has_sources", !srcs.isEmpty());
        if (!out.getBoolean("configured") || srcs.isEmpty()) return out;
        for (JSONObject src : srcs) {
            String login = src.optString("twitch_login").trim().toLowerCase().replaceAll("^.*twitch\\.tv/", "").replaceAll("[^a-z0-9_]", "");
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
            st.put("source_id", src.optString("id")).put("title", src.optString("title")).put("login", login);
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

    // ================================================================== YouTube

    /** kind: "streams" — прошедшие трансляции, "videos" — обычные видео (без коротких). */
    public JSONObject youtube(String kind, boolean refresh) throws Exception {
        boolean streams = "streams".equals(kind);
        String key = store.settings().optString("youtube_api_key").trim();
        JSONObject out = new JSONObject();
        out.put("configured", !key.isEmpty());
        JSONArray list = new JSONArray();
        out.put("channels", list);
        List<JSONObject> srcs = sourcesWith("youtube_channel_id");
        out.put("has_sources", !srcs.isEmpty());
        if (key.isEmpty() || srcs.isEmpty()) return out;
        for (JSONObject src : srcs) {
            String ch = src.optString("youtube_channel_id").trim();
            JSONObject all = cached("yt:" + ch, LIST_TTL, refresh);
            JSONObject res = new JSONObject();
            if (all == null) {
                try {
                    all = ytChannel(key, ch);
                    put("yt:" + ch, all);
                } catch (ApiError e) {
                    all = new JSONObject().put("ok", false).put("error", e.getMessage());
                }
            }
            res.put("ok", all.optBoolean("ok")).put("error", all.optString("error"));
            JSONArray vids = new JSONArray(), src2 = all.optJSONArray("videos");
            for (int i = 0; src2 != null && i < src2.length() && vids.length() < 6; i++) {
                JSONObject v = new JSONObject(src2.getJSONObject(i).toString());
                if (v.optBoolean("live") != streams) continue;
                if (!streams && v.optDouble("duration") <= 70) continue; // короткие видео (Shorts) резать незачем
                JSONObject mom = cached("ytm:" + v.optString("id"), MOMENTS_TTL, false);
                if (mom != null) v.put("moments", mom.optJSONArray("moments")).put("comments", mom.optInt("comments"));
                vids.put(v);
            }
            attachLinks(vids);
            res.put("videos", vids);
            res.put("source_id", src.optString("id")).put("title", src.optString("title")).put("channel_id", ch);
            list.put(res);
        }
        return out;
    }

    private JSONObject ytChannel(String key, String ch) throws Exception {
        if (!ch.startsWith("UC") || ch.length() < 20) throw new ApiError(400, "ID канала YouTube должен начинаться с UC");
        String uploads = "UU" + ch.substring(2); // плейлист «все загрузки» канала
        JSONObject pl = ytGet(key, "playlistItems?part=contentDetails&maxResults=30&playlistId=" + enc(uploads));
        JSONArray items = pl.optJSONArray("items");
        StringBuilder ids = new StringBuilder();
        for (int i = 0; items != null && i < items.length(); i++) {
            String vid = items.getJSONObject(i).getJSONObject("contentDetails").optString("videoId");
            if (vid.isEmpty()) continue;
            if (ids.length() > 0) ids.append(',');
            ids.append(vid);
        }
        JSONArray videos = new JSONArray();
        if (ids.length() > 0) {
            JSONObject vr = ytGet(key, "videos?part=snippet,contentDetails,statistics,liveStreamingDetails&id=" + enc(ids.toString()));
            JSONArray vs = vr.optJSONArray("items");
            for (int i = 0; vs != null && i < vs.length(); i++) {
                JSONObject v = vs.getJSONObject(i), sn = v.optJSONObject("snippet");
                JSONObject live = v.optJSONObject("liveStreamingDetails");
                if (live != null && !live.has("actualEndTime")) continue; // идёт сейчас или ещё не начиналась
                if (sn != null && "live".equals(sn.optString("liveBroadcastContent"))) continue;
                JSONObject st = v.optJSONObject("statistics");
                JSONObject o = new JSONObject();
                o.put("key", "yt:" + v.getString("id"));
                o.put("id", v.getString("id"));
                o.put("title", sn != null ? sn.optString("title") : "");
                o.put("created_at", live != null ? live.optString("actualStartTime", sn != null ? sn.optString("publishedAt") : "")
                        : sn != null ? sn.optString("publishedAt") : "");
                o.put("duration", isoDuration(v.optJSONObject("contentDetails") != null ? v.getJSONObject("contentDetails").optString("duration") : ""));
                o.put("live", live != null);
                o.put("views", st != null ? st.optLong("viewCount") : 0);
                o.put("comments", st != null ? st.optLong("commentCount") : 0);
                o.put("url", "https://www.youtube.com/watch?v=" + v.getString("id"));
                videos.put(o);
            }
        }
        return new JSONObject().put("ok", true).put("videos", videos);
    }

    /** Моменты видео по таймкодам в комментариях (до 500 комментариев: 5 запросов к API). */
    public JSONObject youtubeMoments(String videoId, double duration, boolean refresh) throws Exception {
        if (!videoId.matches("[A-Za-z0-9_-]{6,20}")) throw new ApiError(400, "Неверный ID видео");
        JSONObject hit = cached("ytm:" + videoId, MOMENTS_TTL, refresh);
        if (hit != null) return hit;
        String key = store.settings().optString("youtube_api_key").trim();
        if (key.isEmpty()) throw new ApiError(400, "Нет ключа YouTube Data API — добавьте его в «Настройках»");
        List<String[]> comments = new ArrayList<>(); // {текст, лайки}
        String page = null;
        for (int i = 0; i < 5; i++) {
            Resp r = call("https://www.googleapis.com/youtube/v3/commentThreads?part=snippet&maxResults=100&order=relevance"
                    + "&textFormat=plainText&videoId=" + enc(videoId) + "&key=" + enc(key)
                    + (page != null ? "&pageToken=" + enc(page) : ""), new HashMap<String, String>(), null, "YouTube");
            if (r.code == 403 && r.body.contains("commentsDisabled")) break;
            JSONObject o = ytCheck(r);
            JSONArray items = o.optJSONArray("items");
            for (int k = 0; items != null && k < items.length(); k++) {
                JSONObject sn = items.getJSONObject(k).getJSONObject("snippet").getJSONObject("topLevelComment").getJSONObject("snippet");
                comments.add(new String[]{sn.optString("textDisplay", sn.optString("textOriginal")), String.valueOf(sn.optLong("likeCount"))});
            }
            page = o.optString("nextPageToken", null);
            if (page == null || page.isEmpty()) break;
        }
        JSONObject res = new JSONObject().put("ok", true).put("comments", comments.size())
                .put("moments", commentMoments(comments, duration));
        put("ytm:" + videoId, res);
        return res;
    }

    static final Pattern TS = Pattern.compile("(?<![\\d:.])(?:(\\d{1,2}):)?([0-5]?\\d):([0-5]\\d)(?![\\d:])");

    /** Таймкоды из комментариев → моменты. Вес комментария — 1 + ln(1 + лайки); список из многих таймкодов (оглавление) весит меньше. */
    static JSONArray commentMoments(List<String[]> comments, double duration) throws JSONException {
        List<double[]> marks = new ArrayList<>(); // {время, вес, номер комментария}
        for (int ci = 0; ci < comments.size(); ci++) {
            String text = comments.get(ci)[0];
            long likes = 0;
            try {
                likes = Long.parseLong(comments.get(ci)[1]);
            } catch (NumberFormatException ignored) {
            }
            List<Double> ts = new ArrayList<>();
            Matcher m = TS.matcher(text);
            while (m.find()) {
                double t = (m.group(1) != null ? 3600 * Integer.parseInt(m.group(1)) : 0)
                        + 60 * Integer.parseInt(m.group(2)) + Integer.parseInt(m.group(3));
                if (duration > 0 && t > duration) continue;
                ts.add(t);
            }
            if (ts.isEmpty()) continue;
            double w = (1 + Math.log1p(Math.max(0, likes))) / (ts.size() > 3 ? ts.size() : 1);
            for (double t : ts) marks.add(new double[]{t, w, ci});
        }
        Collections.sort(marks, (a, b) -> Double.compare(a[0], b[0]));
        List<JSONObject> moms = new ArrayList<>();
        int i = 0;
        while (i < marks.size()) {
            double t0 = marks.get(i)[0], score = 0, wsum = 0, tsum = 0, bestW = -1;
            java.util.Set<Integer> who = new java.util.HashSet<>();
            int best = -1, j = i;
            for (; j < marks.size() && marks.get(j)[0] - t0 <= 20; j++) {
                double[] mk = marks.get(j);
                score += mk[1];
                wsum += mk[1];
                tsum += mk[0] * mk[1];
                who.add((int) mk[2]);
                if (mk[1] > bestW) {
                    bestW = mk[1];
                    best = (int) mk[2];
                }
            }
            double t = tsum / wsum; // «Резать» начинает на 3 с раньше: зрители пишут время чуть позже начала
            String quote = comments.get(best)[0].replaceAll("\\s+", " ").trim();
            if (quote.length() > 140) quote = quote.substring(0, 137) + "…";
            moms.add(new JSONObject().put("t", Math.round(t * 10) / 10.0).put("mentions", who.size())
                    .put("score", Math.round(score * 100) / 100.0).put("quote", quote));
            i = j;
        }
        Collections.sort(moms, (a, b) -> Double.compare(b.optDouble("score"), a.optDouble("score")));
        JSONArray out = new JSONArray();
        for (int k = 0; k < moms.size() && k < 8; k++) out.put(moms.get(k));
        return out;
    }

    /** ISO 8601 «PT1H2M3S» → секунды. */
    static double isoDuration(String s) {
        Matcher m = Pattern.compile("P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?").matcher(s == null ? "" : s);
        if (!m.matches()) return 0;
        double t = 0;
        if (m.group(1) != null) t += 86400 * Integer.parseInt(m.group(1));
        if (m.group(2) != null) t += 3600 * Integer.parseInt(m.group(2));
        if (m.group(3) != null) t += 60 * Integer.parseInt(m.group(3));
        if (m.group(4) != null) t += Integer.parseInt(m.group(4));
        return t;
    }

    private JSONObject ytGet(String key, String path) throws Exception {
        return ytCheck(call("https://www.googleapis.com/youtube/v3/" + path + "&key=" + enc(key), new HashMap<String, String>(), null, "YouTube"));
    }

    private static JSONObject ytCheck(Resp r) throws Exception {
        if (r.code == 200) return new JSONObject(r.body);
        String b = r.body == null ? "" : r.body;
        if (b.contains("quotaExceeded") || b.contains("dailyLimitExceeded")) {
            throw new ApiError(502, "Дневной лимит запросов YouTube исчерпан. Он обновится завтра (около 10:00 по Москве).");
        }
        if (b.contains("API_KEY_INVALID") || b.contains("keyInvalid") || r.code == 400 && b.contains("API key")) {
            throw new ApiError(502, "YouTube не принял ключ. Проверьте ключ YouTube Data API в «Настройках».");
        }
        if (b.contains("accessNotConfigured") || b.contains("SERVICE_DISABLED")) {
            throw new ApiError(502, "Для ключа не включён «YouTube Data API v3». Включите его в Google Cloud (см. инструкцию).");
        }
        if (r.code == 404) throw new ApiError(404, "YouTube: канал или видео не найдены");
        throw new ApiError(502, "YouTube ответил " + r.code + ". Попробуйте позже.");
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

    private List<JSONObject> sourcesWith(String field) throws JSONException {
        List<JSONObject> out = new ArrayList<>();
        JSONArray s = store.sources().getJSONArray("sources");
        for (int i = 0; i < s.length(); i++) {
            JSONObject o = s.getJSONObject(i);
            if (!o.optString(field).trim().isEmpty() && o.optBoolean("enabled", true)) out.add(o);
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
