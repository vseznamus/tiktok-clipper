package ru.tiktokclipper.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Шаблон клипа (сохраняется один раз в «Клипе» → «Сохранить как шаблон»): зеркало, субтитры, стиль, язык,
 * текст из .srt, баннер. По нему собираются «Быстрый клип» и ночная нарезка.
 */
public final class Template {
    private Template() {
    }

    public static JSONObject defaults() {
        try {
            return new JSONObject().put("mirror", true).put("subtitles", true).put("sub_style", "classic")
                    .put("language", "auto").put("use_srt", true).put("banner", JSONObject.NULL);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    /** Шаблон из настроек, дополненный значениями по умолчанию. */
    public static JSONObject of(JSONObject settings) {
        JSONObject t = defaults(), own = settings.optJSONObject("template");
        if (own != null) {
            for (String k : new String[]{"mirror", "subtitles", "sub_style", "language", "use_srt", "banner"}) {
                if (own.has(k)) {
                    try {
                        t.put(k, own.get(k));
                    } catch (JSONException ignored) {
                    }
                }
            }
        }
        return t;
    }

    /** Проверка того, что пришло из интерфейса. */
    public static JSONObject clean(JSONObject in) throws ApiError {
        if (in == null) throw new ApiError(400, "Пустой шаблон");
        JSONObject t = defaults();
        try {
            t.put("mirror", in.optBoolean("mirror", true));
            t.put("subtitles", in.optBoolean("subtitles", true));
            String st = in.optString("sub_style", "classic");
            t.put("sub_style", Store.subStyles().has(st) ? st : "classic");
            t.put("language", in.optString("language", "auto").replaceAll("[^a-z]", ""));
            t.put("use_srt", in.optBoolean("use_srt", true));
            JSONObject b = in.optJSONObject("banner");
            if (b != null && !b.optString("path").isEmpty()) {
                String pos = b.optString("pos", "top");
                t.put("banner", new JSONObject().put("path", b.optString("path"))
                        .put("pos", pos.equals("center") || pos.equals("bottom") ? pos : "top")
                        .put("size", Math.max(0.2, Math.min(1.0, b.optDouble("size", 0.8))))
                        .put("chroma", b.optBoolean("chroma")).put("plate", b.optBoolean("plate")));
            }
        } catch (JSONException e) {
            throw new ApiError(400, "Неверный шаблон");
        }
        return t;
    }

    /** Параметры задачи «clip» по шаблону. */
    public static JSONObject clipParams(JSONObject tpl, String file, double start, double end, boolean hasSrt) throws JSONException {
        JSONObject p = new JSONObject().put("source_id", JSONObject.NULL).put("file", file).put("start", start).put("end", end)
                .put("mirror", tpl.optBoolean("mirror", true)).put("subtitles", tpl.optBoolean("subtitles", true))
                .put("sub_style", tpl.optString("sub_style", "classic")).put("language", tpl.optString("language", "auto"))
                .put("sub_source", tpl.optBoolean("use_srt", true) && hasSrt ? "srt" : "asr"); // раскладка — из профиля стримера
        JSONObject b = tpl.optJSONObject("banner");
        if (b != null && !b.optString("path").isEmpty()) {
            p.put("banner", b.getString("path")).put("banner_pos", b.optString("pos", "top")).put("banner_size", b.optDouble("size", 0.8))
                    .put("banner_chroma", b.optBoolean("chroma")).put("banner_plate", b.optBoolean("plate"));
        }
        return p;
    }

    /** Лучшие моменты из кандидатов: не ближе minGap секунд друг к другу. */
    public static List<JSONObject> best(JSONArray cands, int count, double minGap) throws JSONException {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; cands != null && i < cands.length() && out.size() < count; i++) {
            JSONObject c = cands.getJSONObject(i);
            boolean far = true;
            for (JSONObject o : out) if (Math.abs(o.getDouble("start") - c.getDouble("start")) < minGap) far = false;
            if (far) out.add(c);
        }
        return out;
    }
}
