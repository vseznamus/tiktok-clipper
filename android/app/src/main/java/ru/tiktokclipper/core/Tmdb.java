package ru.tiktokclipper.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TMDB (themoviedb.org, официальный API v3): что популярно сейчас, лучшие по рейтингу,
 * рейтинг серий сериала — чтобы резать самые сильные серии. Видео не скачивается.
 * Ключ — «Ключ API» (32 символа) или «Токен доступа к API» (начинается с eyJ) из настроек TMDB.
 */
public final class Tmdb {
    private final Store store;
    private final Net.Http http;
    private final Map<String, Object[]> cache = new HashMap<>();

    public Tmdb(Store store, Net.Http http) {
        this.store = store;
        this.http = http != null ? http : Net::realRequest;
    }

    static final long LIST_TTL = 6 * 3600 * 1000L, SHOW_TTL = 24 * 3600 * 1000L;

    public boolean configured() {
        return !store.settings().optString("tmdb_key").trim().isEmpty();
    }

    // ================================================================== списки

    /** В тренде за неделю, лучшие сериалы и фильмы; у названий, которые есть среди источников, — source_id. */
    public JSONObject lists(boolean refresh) throws Exception {
        JSONObject out = new JSONObject().put("configured", configured());
        if (!configured()) return out;
        JSONArray srcs = store.sources().getJSONArray("sources");
        out.put("trending", mark(items(get("trending/all/week?language=ru-RU", LIST_TTL, refresh), null, 12), srcs));
        out.put("top_tv", mark(items(get("tv/top_rated?language=ru-RU", LIST_TTL, refresh), "tv", 12), srcs));
        out.put("top_movies", mark(items(get("movie/top_rated?language=ru-RU", LIST_TTL, refresh), "movie", 12), srcs));
        return out;
    }

    /**
     * Русские (и советские) сериалы и фильмы — язык оригинала русский.
     * kind: popular — популярные сейчас, top — лучшие по рейтингу (не меньше 50/100 голосов), new — новинки за год.
     */
    public JSONObject russian(String type, String kind, int page, boolean refresh) throws Exception {
        JSONObject out = new JSONObject().put("configured", configured());
        if (!configured()) return out;
        String t = "movie".equals(type) ? "movie" : "tv";
        StringBuilder q = new StringBuilder("discover/").append(t).append("?language=ru-RU&include_adult=false&with_original_language=ru")
                .append("&page=").append(Math.max(1, Math.min(20, page)));
        switch (kind) {
            case "top":
                q.append("&sort_by=vote_average.desc&vote_count.gte=").append(t.equals("tv") ? 50 : 100);
                break;
            case "new": {
                String since = java.time.LocalDate.now().minusYears(1).toString();
                q.append("&sort_by=popularity.desc&").append(t.equals("tv") ? "first_air_date.gte=" : "primary_release_date.gte=").append(since)
                        .append("&vote_count.gte=5");
                break;
            }
            default:
                q.append("&sort_by=popularity.desc");
        }
        JSONObject r = get(q.toString(), LIST_TTL, refresh);
        out.put("items", mark(items(r, t, 20), store.sources().getJSONArray("sources")));
        out.put("page", r.optInt("page", page)).put("total_pages", r.optInt("total_pages", 1));
        return out;
    }

    public JSONArray search(String q) throws Exception {
        if (q.trim().isEmpty()) return new JSONArray();
        JSONArray all = items(get("search/multi?language=ru-RU&include_adult=false&query=" + Net.enc(q.trim()), LIST_TTL, false), null, 20);
        JSONArray out = new JSONArray();
        for (int i = 0; i < all.length() && out.length() < 8; i++) {
            String t = all.getJSONObject(i).optString("type");
            if (t.equals("tv") || t.equals("movie")) out.put(all.getJSONObject(i));
        }
        return out;
    }

    private static JSONArray items(JSONObject r, String type, int max) throws JSONException {
        JSONArray res = r.optJSONArray("results"), out = new JSONArray();
        for (int i = 0; res != null && i < res.length() && out.length() < max; i++) {
            JSONObject x = res.getJSONObject(i);
            String t = type != null ? type : x.optString("media_type");
            if (!t.equals("tv") && !t.equals("movie")) continue;
            String date = x.optString(t.equals("tv") ? "first_air_date" : "release_date", "");
            String ov = x.optString("overview", "");
            out.put(new JSONObject().put("id", x.optLong("id")).put("type", t)
                    .put("title", x.optString(t.equals("tv") ? "name" : "title"))
                    .put("original", x.optString(t.equals("tv") ? "original_name" : "original_title"))
                    .put("year", date.length() >= 4 ? date.substring(0, 4) : "")
                    .put("rating", Math.round(x.optDouble("vote_average", 0) * 10) / 10.0)
                    .put("votes", x.optLong("vote_count"))
                    .put("overview", ov.length() > 180 ? ov.substring(0, 177) + "…" : ov));
        }
        return out;
    }

    private static JSONArray mark(JSONArray items, JSONArray srcs) throws JSONException {
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            for (int k = 0; k < srcs.length(); k++) {
                JSONObject s = srcs.getJSONObject(k);
                boolean same = s.optLong("tmdb_id") == it.optLong("id") && it.optString("type").equals(s.optString("tmdb_type"));
                if (same || sameTitle(s.optString("title"), it.optString("title")) || sameTitle(s.optString("title"), it.optString("original"))) {
                    it.put("source_id", s.optString("id"));
                    break;
                }
            }
        }
        return items;
    }

    static String norm(String s) {
        return (s == null ? "" : s).toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    static boolean sameTitle(String a, String b) {
        String x = norm(a), y = norm(b);
        return !x.isEmpty() && x.equals(y);
    }

    // ================================================================== источник ↔ TMDB

    /**
     * Сведения для источника-сериала или фильма: совпадение в TMDB (или варианты для выбора),
     * у сериала — серии с рейтингом и файлом из папки, лучшие первыми.
     */
    public JSONObject forSource(JSONObject src, JSONArray files, boolean refresh) throws Exception {
        JSONObject out = new JSONObject().put("source_id", src.optString("id")).put("title", src.optString("title"))
                .put("type", src.optString("type"));
        long id = src.optLong("tmdb_id", 0);
        String type = src.optString("tmdb_type", "");
        if (id == 0) {
            JSONArray cands = search(src.optString("title"));
            String want = "series".equals(src.optString("type")) ? "tv" : "movie".equals(src.optString("type")) ? "movie" : "";
            JSONArray filtered = new JSONArray();
            for (int i = 0; i < cands.length(); i++) {
                JSONObject c = cands.getJSONObject(i);
                if (want.isEmpty() || want.equals(c.optString("type"))) filtered.put(c);
            }
            // одно точное совпадение названия — привязываем сами
            JSONObject only = null;
            int exact = 0;
            for (int i = 0; i < filtered.length(); i++) {
                JSONObject c = filtered.getJSONObject(i);
                if (sameTitle(src.optString("title"), c.optString("title")) || sameTitle(src.optString("title"), c.optString("original"))) {
                    exact++;
                    only = c;
                }
            }
            if (exact == 1) {
                id = only.getLong("id");
                type = only.getString("type");
                store.updateSourceFields(src.optString("id"), new JSONObject().put("tmdb_id", id).put("tmdb_type", type));
            } else {
                return out.put("match", JSONObject.NULL).put("candidates", filtered);
            }
        }
        JSONObject d = get(type + "/" + id + "?language=ru-RU", SHOW_TTL, refresh);
        String date = d.optString(type.equals("tv") ? "first_air_date" : "release_date", "");
        out.put("match", new JSONObject().put("id", id).put("type", type)
                .put("title", d.optString(type.equals("tv") ? "name" : "title"))
                .put("year", date.length() >= 4 ? date.substring(0, 4) : "")
                .put("rating", Math.round(d.optDouble("vote_average", 0) * 10) / 10.0)
                .put("votes", d.optLong("vote_count")));
        if (type.equals("tv")) out.put("episodes", episodes(id, d, files, refresh));
        return out;
    }

    /** Серии с рейтингом TMDB и файлом из папки (если нашёлся): сначала те, что есть, по рейтингу. */
    JSONArray episodes(long id, JSONObject show, JSONArray files, boolean refresh) throws Exception {
        Map<String, JSONObject> byEp = new HashMap<>();
        for (int i = 0; files != null && i < files.length(); i++) {
            JSONObject f = files.getJSONObject(i);
            int[] se = episodeOf(f.optString("name"));
            if (se != null) byEp.put(se[0] + "x" + se[1], f);
        }
        List<JSONObject> eps = new ArrayList<>();
        JSONArray seasons = show.optJSONArray("seasons");
        int fetched = 0;
        for (int i = 0; seasons != null && i < seasons.length() && fetched < 12; i++) {
            int sn = seasons.getJSONObject(i).optInt("season_number");
            if (sn <= 0) continue; // «спецвыпуски»
            fetched++;
            JSONObject season = get("tv/" + id + "/season/" + sn + "?language=ru-RU", SHOW_TTL, refresh);
            JSONArray es = season.optJSONArray("episodes");
            for (int k = 0; es != null && k < es.length(); k++) {
                JSONObject e = es.getJSONObject(k);
                int en = e.optInt("episode_number");
                JSONObject f = byEp.get(sn + "x" + en);
                JSONObject o = new JSONObject().put("season", sn).put("episode", en).put("name", e.optString("name"))
                        .put("rating", Math.round(e.optDouble("vote_average", 0) * 10) / 10.0).put("votes", e.optLong("vote_count"));
                if (f != null) o.put("file", f.optString("path")).put("file_name", f.optString("name")).put("used", f.optBoolean("used"));
                eps.add(o);
            }
        }
        Collections.sort(eps, (a, b) -> {
            int fa = a.has("file") ? 0 : 1, fb = b.has("file") ? 0 : 1;
            if (fa != fb) return fa - fb;
            return Double.compare(score(b), score(a));
        });
        JSONArray out = new JSONArray();
        for (JSONObject e : eps) out.put(e);
        return out;
    }

    /** Рейтинг с поправкой на малое число голосов (у серий их бывает 2–3). */
    static double score(JSONObject e) {
        double r = e.optDouble("rating"), v = e.optLong("votes");
        return (r * v + 7.0 * 5) / (v + 5);
    }

    static final Pattern[] EP = {
            Pattern.compile("s(\\d{1,2})[\\s._-]*e(\\d{1,3})"),
            Pattern.compile("(?<!\\d)(\\d{1,2})x(\\d{2,3})(?!\\d)"),
            Pattern.compile("(\\d{1,2})\\s*сезон\\D{0,12}?(\\d{1,3})\\s*сери"),
            Pattern.compile("сезон\\s*(\\d{1,2})\\D{0,12}?сери[яи]\\s*(\\d{1,3})"),
    };
    static final Pattern EP_ONLY = Pattern.compile("(?:сери[яи]\\s*(\\d{1,3}))|(?:(\\d{1,3})\\s*сери)|(?:(?<![a-z])e(?:p|pisode)?\\s*(\\d{1,3})(?!\\d))");

    /** «Breaking.Bad.S01E05.mkv» → {1, 5}; «Во все тяжкие 2 сезон 3 серия» → {2, 3}; «серия 7» → {1, 7}; иначе null. */
    public static int[] episodeOf(String name) {
        String n = (name == null ? "" : name).toLowerCase(Locale.ROOT);
        for (Pattern p : EP) {
            Matcher m = p.matcher(n);
            if (m.find()) return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        }
        Matcher m = EP_ONLY.matcher(n);
        if (m.find()) {
            for (int g = 1; g <= 3; g++) if (m.group(g) != null) return new int[]{1, Integer.parseInt(m.group(g))};
        }
        return null;
    }

    // ================================================================== запросы

    private JSONObject get(String path, long ttl, boolean refresh) throws Exception {
        synchronized (this) {
            Object[] hit = cache.get(path);
            if (!refresh && hit != null && System.currentTimeMillis() - (Long) hit[0] < ttl) return new JSONObject((String) hit[1]);
        }
        String key = store.settings().optString("tmdb_key").trim();
        if (key.isEmpty()) throw new ApiError(400, "Нет ключа TMDB — добавьте его в «Настройках»");
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json");
        String url = "https://api.themoviedb.org/3/" + path;
        if (key.startsWith("eyJ")) h.put("Authorization", "Bearer " + key);
        else url += (path.contains("?") ? "&" : "?") + "api_key=" + Net.enc(key);
        Net.Resp r;
        try {
            r = http.request(url, h, null);
        } catch (java.io.IOException e) {
            throw new ApiError(502, "TMDB недоступен: нет интернета или сайт не открывается из вашей сети.");
        }
        if (r.code == 401) throw new ApiError(502, "TMDB не принял ключ. Проверьте ключ TMDB в «Настройках».");
        if (r.code == 404) throw new ApiError(404, "TMDB: не найдено");
        if (r.code == 429) throw new ApiError(502, "TMDB просит подождать (много запросов). Попробуйте через минуту.");
        if (r.code != 200) throw new ApiError(502, "TMDB ответил " + r.code + ". Попробуйте позже.");
        synchronized (this) {
            cache.put(path, new Object[]{System.currentTimeMillis(), r.body});
        }
        return new JSONObject(r.body);
    }

    public synchronized void clearCache() {
        cache.clear();
    }
}
