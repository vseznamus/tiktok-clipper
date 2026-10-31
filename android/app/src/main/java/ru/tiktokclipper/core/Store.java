package ru.tiktokclipper.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Настройки, источники, состояние расписания и метаданные клипов в JSON-файлах.
 * Тот же формат, что и у Python-версии (sources.json, state.json, settings.json).
 */
public class Store {
    public static final List<String> SOURCE_TYPES = Arrays.asList("series", "movie", "stream", "blogger");
    private static final Object LOCK = new Object();
    public final File dir;

    public Store(File dir) {
        this.dir = dir;
        new File(dir, "clips").mkdirs();
        new File(dir, "cache").mkdirs();
        new File(dir, "work").mkdirs();
        new File(dir, "models").mkdirs();
    }

    // ------------------------------------------------------------ файлы JSON

    public static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    public static JSONObject readObject(File f) {
        try {
            if (!f.isFile()) return new JSONObject();
            return new JSONObject(readText(f));
        } catch (IOException | JSONException e) {
            // повреждённый файл не должен ронять приложение — откладываем его
            f.renameTo(new File(f.getPath() + ".broken"));
            return new JSONObject();
        }
    }

    public static JSONArray readArray(File f) {
        try {
            if (!f.isFile()) return new JSONArray();
            return new JSONArray(readText(f));
        } catch (IOException | JSONException e) {
            f.renameTo(new File(f.getPath() + ".broken"));
            return new JSONArray();
        }
    }

    public static void writeText(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + "." + Thread.currentThread().getId() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("Не удалось записать " + f);
        }
    }

    public static void writeJson(File f, Object json) {
        try {
            String s = json instanceof JSONObject ? ((JSONObject) json).toString(2) : ((JSONArray) json).toString(2);
            writeText(f, s);
        } catch (IOException | JSONException e) {
            throw new RuntimeException(e);
        }
    }

    public File sourcesFile() { return new File(dir, "sources.json"); }
    public File stateFile() { return new File(dir, "state.json"); }
    public File settingsFile() { return new File(dir, "settings.json"); }
    public File jobsFile() { return new File(dir, "jobs.json"); }
    public File clipMetaFile(String id) { return new File(new File(dir, "clips"), id + ".json"); }

    // ------------------------------------------------------------ настройки

    public static JSONObject defaultSettings() {
        JSONObject s = new JSONObject();
        try {
            s.put("asr_backend", "auto");
            s.put("asr_model", JSONObject.NULL);
            s.put("asr_language", "auto");
            s.put("moment_len", 30);
            s.put("x264_preset", JSONObject.NULL);
            s.put("bitrate_mbps", 10);
        } catch (JSONException ignored) {
        }
        return s;
    }

    public JSONObject settings() {
        synchronized (LOCK) {
            JSONObject s = defaultSettings();
            merge(s, readObject(settingsFile()));
            return s;
        }
    }

    public JSONObject saveSettings(JSONObject patch) {
        synchronized (LOCK) {
            JSONObject cur = readObject(settingsFile());
            merge(cur, patch);
            writeJson(settingsFile(), cur);
            return settings();
        }
    }

    // ------------------------------------------------------------ профили

    public static JSONObject defaultProfile() {
        JSONObject p = new JSONObject();
        try {
            p.put("layout", "single");
            p.put("mirror", true);
            p.put("subtitles", true);
            p.put("sub_style", "classic");
            p.put("language", "auto");
            p.put("single_zoom", 1.0);
            p.put("cam_rect", JSONObject.NULL);
            p.put("screen_rect", JSONObject.NULL);
            p.put("screen_fit", "crop");
            p.put("split_screen_h", 0.45);
            p.put("split_gap_h", 0.10);
            p.put("split_cam_h", 0.45);
            p.put("sub_y", JSONObject.NULL);
        } catch (JSONException ignored) {
        }
        return p;
    }

    public static JSONObject fullProfile(JSONObject source) {
        JSONObject p = defaultProfile();
        if (source == null) return p;
        String t = source.optString("type");
        try {
            if (t.equals("stream") || t.equals("blogger")) p.put("layout", "split");
        } catch (JSONException ignored) {
        }
        JSONObject own = source.optJSONObject("profile");
        if (own != null) {
            for (Iterator<String> it = own.keys(); it.hasNext(); ) {
                String k = it.next();
                if (p.has(k)) {
                    try {
                        p.put(k, own.get(k));
                    } catch (JSONException ignored) {
                    }
                }
            }
        }
        return p;
    }

    public static JSONObject subStyles() {
        JSONObject o = new JSONObject();
        try {
            o.put("classic", style("Классика: белый, текущее слово жёлтое", 84, "FFFFFF", "FFD400", 7, 2, true, 3));
            o.put("neon", style("Неон: белый, текущее слово зелёное", 84, "FFFFFF", "3DFF5A", 7, 2, true, 3));
            o.put("calm", style("Спокойный: без подсветки, по 2 слова", 76, "FFFFFF", null, 6, 1, false, 2));
        } catch (JSONException ignored) {
        }
        return o;
    }

    private static JSONObject style(String name, int size, String color, String hi, int outline, int shadow,
                                    boolean upper, int words) throws JSONException {
        JSONObject s = new JSONObject();
        s.put("name", name);
        s.put("size", size);
        s.put("color", color);
        s.put("highlight", hi == null ? JSONObject.NULL : hi);
        s.put("outline", outline);
        s.put("shadow", shadow);
        s.put("uppercase", upper);
        s.put("words", words);
        return s;
    }

    public static JSONObject subStyle(String key) {
        JSONObject all = subStyles();
        JSONObject s = all.optJSONObject(key);
        return s != null ? s : all.optJSONObject("classic");
    }

    // ------------------------------------------------------------ источники

    public JSONObject sources() {
        synchronized (LOCK) {
            JSONObject cfg = readObject(sourcesFile());
            try {
                if (!cfg.has("start_date")) cfg.put("start_date", JSONObject.NULL);
                cfg.put("interval_days", Math.max(1, cfg.optInt("interval_days", 3)));
                cfg.put("posts_per_block", Math.max(1, cfg.optInt("posts_per_block", 3)));
                JSONArray order = cfg.optJSONArray("type_order");
                JSONArray clean = new JSONArray();
                if (order != null) {
                    for (int i = 0; i < order.length(); i++) {
                        String t = order.optString(i);
                        if (SOURCE_TYPES.contains(t) && !contains(clean, t)) clean.put(t);
                    }
                }
                for (String t : SOURCE_TYPES) if (!contains(clean, t)) clean.put(t);
                cfg.put("type_order", clean);
                if (cfg.optJSONArray("sources") == null) cfg.put("sources", new JSONArray());
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
            return cfg;
        }
    }

    public void saveSources(JSONObject cfg) {
        synchronized (LOCK) {
            writeJson(sourcesFile(), cfg);
        }
    }

    public JSONObject getSource(String id) {
        if (id == null) return null;
        JSONArray arr = sources().optJSONArray("sources");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s != null && id.equals(s.optString("id"))) return s;
        }
        return null;
    }

    public JSONObject upsertSource(JSONObject src) throws ApiError {
        synchronized (LOCK) {
            JSONObject cfg = sources();
            JSONArray arr = cfg.optJSONArray("sources");
            String type = src.optString("type");
            if (!SOURCE_TYPES.contains(type)) throw new ApiError(400, "Неизвестный тип источника");
            String title = src.optString("title").trim();
            if (title.isEmpty()) throw new ApiError(400, "Укажите название источника");
            String id = src.optString("id", "");
            try {
                if (id.isEmpty() || src.isNull("id")) {
                    String base = slugify(title);
                    id = base;
                    int n = 2;
                    while (getSource(id) != null) id = base + "_" + (n++);
                    src.put("id", id);
                }
                if (!src.has("enabled")) src.put("enabled", true);
                JSONObject prof = src.optJSONObject("profile");
                JSONObject cleanProf = new JSONObject();
                if (prof != null) {
                    JSONObject def = defaultProfile();
                    for (Iterator<String> it = prof.keys(); it.hasNext(); ) {
                        String k = it.next();
                        if (def.has(k)) cleanProf.put(k, prof.get(k));
                    }
                }
                src.put("profile", cleanProf);
                if (!src.has("folder") || src.optString("folder").isEmpty()) src.put("folder", "");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject s = arr.getJSONObject(i);
                    if (id.equals(s.optString("id"))) {
                        merge(s, src);
                        saveSources(cfg);
                        return s;
                    }
                }
                arr.put(src);
            } catch (JSONException e) {
                throw new ApiError(400, "Неверные данные источника");
            }
            saveSources(cfg);
            return getSource(id);
        }
    }

    /** Служебные поля источника (привязка к TMDB) без пересохранения всего источника. */
    public void updateSourceFields(String id, JSONObject patch) throws JSONException {
        synchronized (LOCK) {
            JSONObject cfg = sources();
            JSONArray arr = cfg.optJSONArray("sources");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject s = arr.getJSONObject(i);
                if (!id.equals(s.optString("id"))) continue;
                for (Iterator<String> it = patch.keys(); it.hasNext(); ) {
                    String k = it.next();
                    if (patch.isNull(k)) s.remove(k);
                    else s.put(k, patch.get(k));
                }
                saveSources(cfg);
                return;
            }
        }
    }

    public JSONObject updateProfile(String id, JSONObject patch) throws ApiError {
        synchronized (LOCK) {
            JSONObject cfg = sources();
            JSONArray arr = cfg.optJSONArray("sources");
            JSONObject def = defaultProfile();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && id.equals(s.optString("id"))) {
                    try {
                        JSONObject prof = s.optJSONObject("profile");
                        if (prof == null) {
                            prof = new JSONObject();
                            s.put("profile", prof);
                        }
                        for (Iterator<String> it = patch.keys(); it.hasNext(); ) {
                            String k = it.next();
                            if (def.has(k)) prof.put(k, patch.get(k));
                        }
                    } catch (JSONException e) {
                        throw new ApiError(400, "Неверный профиль");
                    }
                    saveSources(cfg);
                    return s;
                }
            }
            throw new ApiError(404, "Источник не найден");
        }
    }

    public void deleteSource(String id) {
        synchronized (LOCK) {
            JSONObject cfg = sources();
            JSONArray arr = cfg.optJSONArray("sources");
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && !id.equals(s.optString("id"))) out.put(s);
            }
            try {
                cfg.put("sources", out);
            } catch (JSONException ignored) {
            }
            saveSources(cfg);
        }
    }

    // ------------------------------------------------------------ состояние расписания

    public JSONObject loadState() {
        synchronized (LOCK) {
            return Schedule.normalizeState(readObject(stateFile()));
        }
    }

    public void saveState(JSONObject st) {
        synchronized (LOCK) {
            writeJson(stateFile(), st);
        }
    }

    public static Object stateLock() { return LOCK; }

    // ------------------------------------------------------------ утилиты

    public static void merge(JSONObject into, JSONObject from) {
        for (Iterator<String> it = from.keys(); it.hasNext(); ) {
            String k = it.next();
            try {
                into.put(k, from.get(k));
            } catch (JSONException ignored) {
            }
        }
    }

    public static boolean contains(JSONArray a, String v) {
        for (int i = 0; i < a.length(); i++) if (v.equals(a.optString(i))) return true;
        return false;
    }

    public static String slugify(String text) {
        String[][] t = {{"а", "a"}, {"б", "b"}, {"в", "v"}, {"г", "g"}, {"д", "d"}, {"е", "e"}, {"ё", "e"}, {"ж", "zh"},
                {"з", "z"}, {"и", "i"}, {"й", "y"}, {"к", "k"}, {"л", "l"}, {"м", "m"}, {"н", "n"}, {"о", "o"},
                {"п", "p"}, {"р", "r"}, {"с", "s"}, {"т", "t"}, {"у", "u"}, {"ф", "f"}, {"х", "h"}, {"ц", "c"},
                {"ч", "ch"}, {"ш", "sh"}, {"щ", "sch"}, {"ъ", ""}, {"ы", "y"}, {"ь", ""}, {"э", "e"}, {"ю", "yu"},
                {"я", "ya"}};
        String s = text.toLowerCase();
        for (String[] p : t) s = s.replace(p[0], p[1]);
        s = s.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (s.length() > 40) s = s.substring(0, 40);
        return s.isEmpty() ? "source" : s;
    }
}
