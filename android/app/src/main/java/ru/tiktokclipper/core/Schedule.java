package ru.tiktokclipper.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Расписание и чередование постов (перенос tclip/schedule.py).
 * Пост i относится к блоку i / ppb; блок — ppb постов подряд из одного источника;
 * типы идут по кругу, внутри типа — источники по очереди. Начатые блоки замораживаются в state.
 */
public final class Schedule {
    private Schedule() {
    }

    public static String typeName(String t) {
        switch (t) {
            case "series": return "Сериал";
            case "movie": return "Фильм";
            default: return t;
        }
    }

    public static JSONObject normalizeState(JSONObject st) {
        try {
            if (st == null) st = new JSONObject();
            if (st.optJSONArray("blocks") == null) st.put("blocks", new JSONArray());
            if (st.optJSONArray("history") == null) st.put("history", new JSONArray());
            if (!st.has("due_date")) st.put("due_date", JSONObject.NULL);
            if (st.optJSONObject("used_files") == null) st.put("used_files", new JSONObject());
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return st;
    }

    static List<JSONObject> enabledSources(JSONObject cfg, String type) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray arr = cfg.optJSONArray("sources");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null || !s.optBoolean("enabled", true)) continue;
            if (type == null || type.equals(s.optString("type"))) out.add(s);
        }
        return out;
    }

    static List<String> activeTypes(JSONObject cfg) {
        List<String> out = new ArrayList<>();
        JSONArray order = cfg.optJSONArray("type_order");
        for (int i = 0; i < order.length(); i++) {
            String t = order.optString(i);
            if (!enabledSources(cfg, t).isEmpty()) out.add(t);
        }
        return out;
    }

    static JSONObject nextBlock(JSONObject cfg, List<JSONObject> blocks) throws JSONException {
        List<String> types = activeTypes(cfg);
        if (types.isEmpty()) return null;
        String t;
        if (blocks.isEmpty()) {
            t = types.get(0);
        } else {
            JSONArray order = cfg.getJSONArray("type_order");
            String lastT = blocks.get(blocks.size() - 1).optString("type");
            int start = -1;
            for (int i = 0; i < order.length(); i++) if (order.optString(i).equals(lastT)) start = i;
            t = null;
            for (int k = 1; k <= order.length(); k++) {
                String cand = order.optString(((start + k) % order.length() + order.length()) % order.length());
                if (types.contains(cand)) {
                    t = cand;
                    break;
                }
            }
        }
        List<JSONObject> srcs = enabledSources(cfg, t);
        List<String> ids = new ArrayList<>();
        for (JSONObject s : srcs) ids.add(s.optString("id"));
        List<JSONObject> prev = new ArrayList<>();
        for (JSONObject b : blocks) if (t.equals(b.optString("type"))) prev.add(b);
        int idx;
        if (!prev.isEmpty() && ids.contains(prev.get(prev.size() - 1).optString("source_id"))) {
            idx = (ids.indexOf(prev.get(prev.size() - 1).optString("source_id")) + 1) % ids.size();
        } else {
            idx = prev.size() % ids.size();
        }
        JSONObject b = new JSONObject();
        b.put("type", t);
        b.put("source_id", ids.get(idx));
        return b;
    }

    static List<JSONObject> planBlocks(JSONObject cfg, JSONObject state, int uptoBlock, int freezeUpto) throws JSONException {
        List<JSONObject> blocks = new ArrayList<>();
        JSONArray frozen = state.optJSONArray("blocks");
        for (int i = 0; frozen != null && i < frozen.length(); i++) blocks.add(frozen.getJSONObject(i));
        List<String> ids = new ArrayList<>();
        for (JSONObject s : enabledSources(cfg, null)) ids.add(s.optString("id"));
        for (int bi = Math.min(blocks.size(), freezeUpto); bi < blocks.size(); bi++) {
            if (!ids.contains(blocks.get(bi).optString("source_id"))) {
                blocks = new ArrayList<>(blocks.subList(0, bi));
                break;
            }
        }
        while (blocks.size() <= uptoBlock) {
            JSONObject nb = nextBlock(cfg, blocks);
            if (nb == null) break;
            blocks.add(nb);
        }
        JSONArray keep = new JSONArray();
        for (int i = 0; i < Math.min(blocks.size(), freezeUpto + 1); i++) keep.put(blocks.get(i));
        state.put("blocks", keep);
        return blocks;
    }

    public static int currentIndex(JSONObject state) {
        JSONArray h = state.optJSONArray("history");
        return h == null ? 0 : h.length();
    }

    static LocalDate parse(Object o) {
        if (o == null || o == JSONObject.NULL) return null;
        String s = o.toString();
        if (s.isEmpty() || s.equals("null")) return null;
        return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    }

    public static LocalDate dueDate(JSONObject cfg, JSONObject state, LocalDate today) {
        LocalDate d = parse(state.opt("due_date"));
        if (d == null) d = parse(cfg.opt("start_date"));
        return d == null ? today : d;
    }

    static JSONObject describePost(JSONObject cfg, List<JSONObject> blocks, int index, LocalDate due) throws JSONException {
        int ppb = cfg.getInt("posts_per_block");
        int b = index / ppb;
        if (b >= blocks.size()) return null;
        JSONObject blk = blocks.get(b);
        String sid = blk.optString("source_id");
        JSONObject src = null;
        JSONArray arr = cfg.optJSONArray("sources");
        for (int i = 0; i < arr.length(); i++) if (sid.equals(arr.getJSONObject(i).optString("id"))) src = arr.getJSONObject(i);
        JSONObject p = new JSONObject();
        p.put("index", index);
        p.put("block", b);
        p.put("pos", index % ppb + 1);
        p.put("posts_per_block", ppb);
        p.put("type", blk.optString("type"));
        p.put("type_name", typeName(blk.optString("type")));
        p.put("source_id", sid);
        p.put("source_title", src != null ? src.optString("title") : "(источник удалён)");
        p.put("date", due.toString());
        return p;
    }

    public static List<JSONObject> upcoming(JSONObject cfg, JSONObject state, LocalDate today, int count) {
        try {
            int ppb = cfg.getInt("posts_per_block");
            int cur = currentIndex(state);
            int last = cur + count - 1;
            List<JSONObject> blocks = planBlocks(cfg, state, last / ppb, cur / ppb);
            LocalDate due = dueDate(cfg, state, today);
            List<JSONObject> out = new ArrayList<>();
            for (int k = 0; k < count; k++) {
                JSONObject p = describePost(cfg, blocks, cur + k, due.plusDays((long) k * cfg.getInt("interval_days")));
                if (p == null) break;
                out.add(p);
            }
            return out;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    public static String dueStatus(LocalDate due, LocalDate today) {
        long diff = ChronoUnit.DAYS.between(today, due);
        if (diff == 0) return "сегодня";
        if (diff < 0) return "просрочен на " + (-diff) + " дн.";
        if (diff == 1) return "завтра";
        return "через " + diff + " дн.";
    }

    /** Какой файл брать: первый неиспользованный по имени. */
    public static JSONObject suggestFiles(String type, List<JSONObject> files, JSONArray used) throws JSONException {
        List<JSONObject> unused = new ArrayList<>();
        for (JSONObject f : files) if (!Store.contains(used, f.optString("path"))) unused.add(f);
        String last = used.length() > 0 ? used.optString(used.length() - 1) : null;
        JSONObject cont = null;
        for (JSONObject f : files) if (f.optString("path").equals(last)) cont = f;
        JSONObject r = new JSONObject();
        r.put("next", unused.isEmpty() ? JSONObject.NULL : unused.get(0));
        r.put("continue", cont == null ? JSONObject.NULL : cont);
        return r;
    }

    // ------------------------------------------------------------ действия

    private static JSONObject first(JSONObject cfg, JSONObject state, LocalDate today) throws ApiError {
        List<JSONObject> posts = upcoming(cfg, state, today, 1);
        if (posts.isEmpty()) throw new ApiError(400, "Нет источников — нечего публиковать");
        return posts.get(0);
    }

    public static JSONObject publish(JSONObject cfg, JSONObject state, LocalDate today, String file, String clipId) throws ApiError {
        JSONObject p = first(cfg, state, today);
        LocalDate due = dueDate(cfg, state, today);
        try {
            JSONObject h = new JSONObject();
            h.put("index", p.getInt("index"));
            h.put("action", "published");
            h.put("date", today.toString());
            h.put("source_id", p.getString("source_id"));
            h.put("file", file == null ? JSONObject.NULL : file);
            h.put("clip_id", clipId == null ? JSONObject.NULL : clipId);
            h.put("prev_due", state.opt("due_date"));
            state.getJSONArray("history").put(h);
            if (file != null) {
                JSONObject usedAll = state.getJSONObject("used_files");
                JSONArray used = usedAll.optJSONArray(p.getString("source_id"));
                JSONArray nu = new JSONArray();
                for (int i = 0; used != null && i < used.length(); i++) if (!file.equals(used.optString(i))) nu.put(used.opt(i));
                nu.put(file);
                usedAll.put(p.getString("source_id"), nu);
            }
            LocalDate base = due.isAfter(today) ? due : today;
            state.put("due_date", base.plusDays(cfg.getInt("interval_days")).toString());
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return p;
    }

    public static JSONObject skip(JSONObject cfg, JSONObject state, LocalDate today) throws ApiError {
        JSONObject p = first(cfg, state, today);
        try {
            JSONObject h = new JSONObject();
            h.put("index", p.getInt("index"));
            h.put("action", "skipped");
            h.put("date", today.toString());
            h.put("source_id", p.getString("source_id"));
            h.put("file", JSONObject.NULL);
            h.put("clip_id", JSONObject.NULL);
            h.put("prev_due", state.opt("due_date"));
            state.getJSONArray("history").put(h);
            state.put("due_date", dueDate(cfg, state, today).toString());
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return p;
    }

    public static String postpone(JSONObject cfg, JSONObject state, LocalDate today, int days) {
        LocalDate due = dueDate(cfg, state, today);
        LocalDate base = due.isAfter(today) ? due : today;
        String d = base.plusDays(Math.max(1, days)).toString();
        try {
            state.put("due_date", d);
        } catch (JSONException ignored) {
        }
        return d;
    }

    public static JSONObject undo(JSONObject state) {
        JSONArray h = state.optJSONArray("history");
        if (h == null || h.length() == 0) return null;
        JSONObject last = h.optJSONObject(h.length() - 1);
        h.remove(h.length() - 1);
        try {
            String file = last.isNull("file") ? null : last.optString("file", null);
            if (file != null) {
                JSONArray used = state.getJSONObject("used_files").optJSONArray(last.optString("source_id"));
                for (int i = 0; used != null && i < used.length(); i++) {
                    if (file.equals(used.optString(i))) {
                        used.remove(i);
                        break;
                    }
                }
            }
            state.put("due_date", last.has("prev_due") ? last.get("prev_due") : JSONObject.NULL);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return last;
    }
}
