package ru.tiktokclipper.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Автоплан: N моментов на N дней (по одному в день) из одного источника.
 * Сериал — лучшие по рейтингу TMDB серии, которые есть в папке (без TMDB — следующие по порядку), по моменту из серии.
 * Фильм — N лучших моментов фильма, далеко друг от друга. Стрим/блогер — свежие записи (по чату, если он есть, иначе по звуку).
 * Моменты оцениваются офлайн: громкость, речь и эмоции в репликах (.srt), смены планов, всплески чата.
 */
public final class Planner {
    private Planner() {
    }

    /** Поиск моментов в файле (на телефоне — звук/чат/опорные кадры). Возвращает {candidates:[{start,end,score,reasons}], source}. */
    public interface Analyzer {
        JSONObject analyze(JobQueue.Ctx ctx, String file, int length, double lo, double hi) throws Exception;
    }

    public static File planFile(Store s) {
        return new File(s.dir, "plan.json");
    }

    public static JSONArray load(Store s) {
        JSONArray a = Store.readObject(planFile(s)).optJSONArray("items");
        return a != null ? a : new JSONArray();
    }

    public static void save(Store s, JSONArray items) throws JSONException {
        Store.writeJson(planFile(s), new JSONObject().put("items", items));
    }

    static final Object LOCK = new Object();

    public static JSONObject run(JobQueue.Ctx ctx, Store store, Platform pf, Tmdb tmdb, Analyzer an, JobQueue queue,
                                 LocalDate today) throws Exception {
        JSONObject p = ctx.params;
        String sid = p.optString("source_id");
        JSONObject src = store.getSource(sid);
        if (src == null) throw new ApiError(404, "Источник не найден");
        int count = Math.max(1, Math.min(7, p.optInt("count", 3)));
        LocalDate start = p.optString("start", "").isEmpty() ? today : LocalDate.parse(p.getString("start"));
        boolean makeClips = p.optBoolean("make_clips", true);
        int length = Math.max(5, Math.min(180, store.settings().optInt("moment_len", 30)));
        String type = src.optString("type");

        ctx.progress(0.01, "Смотрю файлы в папке «" + src.optString("title") + "»");
        JSONArray files = pf.listFiles(src);
        if (files.length() == 0) throw new ApiError(400, "В папке источника нет видео — положите туда файлы");

        // что уже публиковалось или стоит в плане — в конец очереди
        Set<String> used = new HashSet<>();
        JSONArray uf = store.loadState().optJSONObject("used_files") != null
                ? store.loadState().getJSONObject("used_files").optJSONArray(sid) : null;
        for (int i = 0; uf != null && i < uf.length(); i++) used.add(uf.optString(i));
        JSONArray existing = load(store);
        for (int i = 0; i < existing.length(); i++) {
            JSONObject it = existing.getJSONObject(i);
            if (sid.equals(it.optString("source_id")) && LocalDate.parse(it.getString("date")).isBefore(start)) used.add(it.optString("file"));
        }
        for (int i = 0; i < files.length(); i++) files.getJSONObject(i).put("used", used.contains(files.getJSONObject(i).optString("path")));

        Map<String, String> why = new LinkedHashMap<>(); // файл → почему выбран
        List<String> order = new ArrayList<>();
        if (type.equals("series") && tmdb != null && tmdb.configured()) {
            try {
                ctx.progress(0.03, "Беру рейтинг серий из TMDB");
                JSONObject info = tmdb.forSource(src, files, false);
                JSONArray eps = info.optJSONArray("episodes");
                List<JSONObject> rated = new ArrayList<>();
                for (int i = 0; eps != null && i < eps.length(); i++) if (eps.getJSONObject(i).has("file")) rated.add(eps.getJSONObject(i));
                Collections.sort(rated, (a, b) -> {
                    int ua = a.optBoolean("used") ? 1 : 0, ub = b.optBoolean("used") ? 1 : 0;
                    return ua != ub ? ua - ub : Double.compare(Tmdb.score(b), Tmdb.score(a));
                });
                for (JSONObject e : rated) {
                    order.add(e.getString("file"));
                    why.put(e.getString("file"), String.format(java.util.Locale.ROOT, "S%02dE%02d · рейтинг %s (TMDB, %d голосов)",
                            e.getInt("season"), e.getInt("episode"), String.valueOf(e.optDouble("rating")).replace('.', ','), e.optLong("votes")));
                }
                if (info.isNull("match") || info.optJSONObject("match") == null) {
                    ctx.warn("Сериал не привязан к TMDB (несколько вариантов названия) — выберите его во вкладке «Моменты» → «Фильмы и сериалы». Пока беру серии по порядку.");
                }
            } catch (ApiError e) {
                ctx.warn(e.getMessage() + " Беру серии по порядку.");
            }
        }
        List<JSONObject> rest = new ArrayList<>();
        for (int i = 0; i < files.length(); i++) rest.add(files.getJSONObject(i));
        if (type.equals("stream") || type.equals("blogger")) {
            Collections.sort(rest, (a, b) -> Double.compare(b.optDouble("mtime"), a.optDouble("mtime")));
        }
        for (boolean wantUsed : new boolean[]{false, true}) {
            for (JSONObject f : rest) {
                String path = f.optString("path");
                if (f.optBoolean("used") != wantUsed || order.contains(path)) continue;
                order.add(path);
                why.put(path, wantUsed ? "уже использован раньше" : type.equals("series") ? "следующая серия по порядку"
                        : type.equals("movie") ? "фильм" : "свежая запись");
            }
        }
        int nFiles = type.equals("movie") ? 1 : Math.min(count, order.size());
        List<String> chosen = order.subList(0, nFiles);

        // моменты в выбранных файлах
        List<JSONArray> cands = new ArrayList<>();
        List<String> how = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            ctx.check();
            double lo = 0.05 + 0.85 * i / nFiles, hi = 0.05 + 0.85 * (i + 1) / nFiles;
            String name = chosen.get(i).substring(chosen.get(i).indexOf('/') + 1);
            ctx.progress(lo, "Ищу моменты: " + name + " (" + (i + 1) + " из " + nFiles + ")");
            JSONObject r = an.analyze(ctx, chosen.get(i), length, lo, hi);
            cands.add(r.optJSONArray("candidates") != null ? r.getJSONArray("candidates") : new JSONArray());
            how.add("chat".equals(r.optString("source")) ? "по чату" : "по звуку и репликам");
        }

        // по кругу: лучший момент каждого файла, потом вторые и т. д.; внутри файла — подальше друг от друга
        List<JSONObject> picked = new ArrayList<>();
        double minGap = Math.max(90, 3.0 * length);
        for (int round = 0; picked.size() < count && round < 12; round++) {
            boolean any = false;
            for (int i = 0; i < chosen.size() && picked.size() < count; i++) {
                JSONArray c = cands.get(i);
                for (int k = 0; k < c.length(); k++) {
                    JSONObject cand = c.getJSONObject(k);
                    if (cand.optBoolean("_taken")) continue;
                    boolean far = true;
                    int inFile = 0;
                    for (JSONObject pk : picked) {
                        if (!pk.getString("file").equals(chosen.get(i))) continue;
                        inFile++;
                        if (Math.abs(pk.getDouble("start") - cand.getDouble("start")) < minGap) far = false;
                    }
                    if (!far || inFile > round) continue;
                    cand.put("_taken", true);
                    picked.add(new JSONObject().put("file", chosen.get(i)).put("start", cand.getDouble("start"))
                            .put("end", cand.getDouble("end")).put("score", cand.optDouble("score"))
                            .put("reasons", cand.optJSONArray("reasons")).put("how", how.get(i)).put("why", why.get(chosen.get(i))));
                    any = true;
                    break;
                }
            }
            if (!any) break;
        }
        if (picked.isEmpty()) throw new ApiError(400, "Не нашлось ни одного момента — в файлах нет звука или чата");
        if (picked.size() < count) ctx.warn("Нашлось только " + picked.size() + " из " + count + " моментов.");

        ctx.progress(0.92, "Составляю план");
        JSONArray items = new JSONArray();
        for (int k = 0; k < picked.size(); k++) {
            JSONObject m = picked.get(k);
            String file = m.getString("file");
            JSONObject it = new JSONObject();
            it.put("id", UUID.randomUUID().toString().replace("-", "").substring(0, 10));
            it.put("date", start.plusDays(k).toString());
            it.put("source_id", sid).put("source_title", src.optString("title")).put("type", type);
            it.put("file", file).put("file_name", file.substring(file.indexOf('/') + 1));
            it.put("start", m.getDouble("start")).put("end", m.getDouble("end"));
            it.put("reasons", m.opt("reasons")).put("how", m.getString("how")).put("why", m.optString("why"));
            it.put("published", false);
            if (makeClips) it.put("job_id", submitClip(queue, pf, it));
            items.put(it);
        }
        synchronized (LOCK) {
            JSONArray all = load(store), keep = new JSONArray();
            for (int i = 0; i < all.length(); i++) {
                JSONObject it = all.getJSONObject(i);
                boolean replaced = sid.equals(it.optString("source_id")) && !it.optBoolean("published")
                        && !LocalDate.parse(it.getString("date")).isBefore(start);
                if (!replaced) keep.put(it);
            }
            for (int i = 0; i < items.length(); i++) keep.put(items.get(i));
            save(store, sorted(keep));
        }
        return new JSONObject().put("items", items).put("source_title", src.optString("title"));
    }

    /** Ставит сборку клипа по пункту плана в очередь; субтитры из .srt, если файл есть рядом. */
    public static String submitClip(JobQueue queue, Platform pf, JSONObject it) throws Exception {
        JSONObject params = new JSONObject().put("source_id", it.getString("source_id")).put("file", it.getString("file"))
                .put("start", it.getDouble("start")).put("end", it.getDouble("end"));
        boolean srt = false;
        try {
            srt = pf.probe(it.getString("file")).optBoolean("has_srt");
        } catch (Exception ignored) {
            // не прочитали — распознаем речь
        }
        params.put("sub_source", srt ? "srt" : "asr");
        JSONObject job = queue.submit("clip", params, "Клип по плану на " + it.getString("date") + ": " + it.optString("source_title"));
        return job.getString("id");
    }

    static JSONArray sorted(JSONArray a) throws JSONException {
        List<JSONObject> l = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) l.add(a.getJSONObject(i));
        Collections.sort(l, (x, y) -> x.optString("date").compareTo(y.optString("date")));
        JSONArray out = new JSONArray();
        for (JSONObject o : l) out.put(o);
        return out;
    }

    public static void update(Store store, String id, JSONObject patch) throws Exception {
        synchronized (LOCK) {
            JSONArray all = load(store);
            for (int i = 0; i < all.length(); i++) {
                JSONObject it = all.getJSONObject(i);
                if (!id.equals(it.optString("id"))) continue;
                for (java.util.Iterator<String> k = patch.keys(); k.hasNext(); ) {
                    String key = k.next();
                    it.put(key, patch.get(key));
                }
                save(store, all);
                return;
            }
        }
        throw new ApiError(404, "Пункт плана не найден");
    }

    public static void remove(Store store, String id) throws Exception {
        synchronized (LOCK) {
            JSONArray all = load(store), keep = new JSONArray();
            for (int i = 0; i < all.length(); i++) if (!id.equals(all.getJSONObject(i).optString("id"))) keep.put(all.get(i));
            save(store, keep);
        }
    }

    public static JSONObject get(Store store, String id) {
        JSONArray all = load(store);
        for (int i = 0; i < all.length(); i++) if (id.equals(all.optJSONObject(i).optString("id"))) return all.optJSONObject(i);
        return null;
    }
}
