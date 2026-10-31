package ru.tiktokclipper.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Поиск моментов: громкие окна + плотность речи (перенос tclip/moments.py). */
public final class Moments {
    private Moments() {
    }

    static double percentile(double[] v, double p) {
        if (v.length == 0) return 0;
        double[] s = v.clone();
        Arrays.sort(s);
        double k = (s.length - 1) * p;
        int lo = (int) k, hi = Math.min(lo + 1, s.length - 1);
        return s[lo] + (s[hi] - s[lo]) * (k - lo);
    }

    static double[] speechDensity(List<Subs.Line> cues, int n) {
        double[] d = new double[n];
        for (Subs.Line c : cues) {
            int s = (int) c.s, e = Math.min(n - 1, (int) c.e);
            if (e < s || s >= n) continue;
            double per = c.text.length() / (double) (e - s + 1);
            for (int i = s; i <= e; i++) d[i] += per;
        }
        for (int i = 0; i < n; i++) d[i] = Math.min(1.0, d[i] / 15.0);
        return d;
    }

    static final java.util.regex.Pattern LAUGH = java.util.regex.Pattern.compile(
            "(?iu)ха-?ха|хах|аха|хи-?хи|ha-?ha|\\(смеё?тся\\)|\\[смех]|смеётся|кричит|\\(крик|орёт|ору");

    /**
     * Эмоциональность реплик по секундам (0…1): восклицания, вопросы, смех и крики, быстрые короткие реплики.
     * Бесплатная офлайн-оценка сцены по субтитрам — без интернета и без нейросетей.
     */
    static double[] emotion(List<Subs.Line> cues, int n) {
        double[] d = new double[n];
        double prevStart = -100;
        for (Subs.Line c : cues) {
            int s = (int) c.s, e = Math.min(n - 1, (int) c.e);
            if (e < s || s >= n) continue;
            String t = c.text;
            double v = 0;
            for (int i = 0; i < t.length(); i++) {
                char ch = t.charAt(i);
                if (ch == '!') v += 0.45;
                else if (ch == '?') v += 0.25;
            }
            if (LAUGH.matcher(t).find()) v += 0.8;
            if (t.length() <= 18) v += 0.15;                  // короткая реплика
            if (c.s - prevStart < 2.5) v += 0.25;             // перепалка: реплики одна за другой
            if (t.replaceAll("[^\\p{Lu}]", "").length() >= 6 && t.equals(t.toUpperCase(java.util.Locale.ROOT))) v += 0.4; // КРИК
            prevStart = c.s;
            for (int i = s; i <= e; i++) d[i] += v;
        }
        double hi = Math.max(0.5, percentile(d, 0.95));
        for (int i = 0; i < n; i++) d[i] = Math.min(1.0, d[i] / hi);
        return d;
    }

    /** levels — громкость (дБ) по секундам; cues — реплики (.srt) или null. */
    public static JSONArray find(double[] levels, int length, List<Subs.Line> cues, int count) {
        return find(levels, length, cues, null, count);
    }

    /** cuts — число опорных кадров по секундам (частые смены планов = динамичная сцена) или null. */
    public static JSONArray find(double[] levels, int length, List<Subs.Line> cues, double[] cuts, int count) {
        JSONArray out = new JSONArray();
        int n = levels.length;
        length = Math.max(5, length);
        try {
            if (n == 0) return out;
            if (n <= length) {
                out.put(cand(0, n, 1.0, Arrays.asList("весь файл короче окна")));
                return out;
            }
            double lo = percentile(levels, 0.10), hi = percentile(levels, 0.98), rng = Math.max(hi - lo, 1e-6);
            double[] loud = new double[n], smooth = new double[n];
            for (int i = 0; i < n; i++) loud[i] = Math.min(1, Math.max(0, (levels[i] - lo) / rng));
            for (int i = 0; i < n; i++) {
                int a = Math.max(0, i - 1), b = Math.min(n, i + 2);
                double sum = 0;
                for (int j = a; j < b; j++) sum += loud[j];
                smooth[i] = sum / (b - a);
            }
            boolean hasSpeech = cues != null && !cues.isEmpty();
            double[] speech = hasSpeech ? speechDensity(cues, n) : null;
            double[] pl = prefix(loud), ps = hasSpeech ? prefix(speech) : null;
            double[] pe = hasSpeech ? prefix(emotion(cues, n)) : null;
            double[] pcut = null;
            if (cuts != null && cuts.length > 0) {
                double[] cn = new double[n];
                double[] win = new double[n];
                for (int i = 0; i < n; i++) {
                    double sum = 0;
                    for (int j = Math.max(0, i - 2); j <= Math.min(n - 1, i + 2); j++) sum += j < cuts.length ? cuts[j] : 0;
                    win[i] = sum;
                }
                double lo2 = percentile(win, 0.2), hi2 = percentile(win, 0.95);
                if (hi2 - lo2 > 0.5) { // при опорных кадрах через равные промежутки смысла нет
                    for (int i = 0; i < n; i++) cn[i] = Math.min(1, Math.max(0, (win[i] - lo2) / (hi2 - lo2)));
                    pcut = prefix(cn);
                }
            }
            List<double[]> cands = new ArrayList<>();
            for (int s = 0; s + length <= n; s++) {
                int e = s + length;
                double mean = (pl[e] - pl[s]) / length;
                double peak = 0;
                for (int i = s; i < e; i++) peak = Math.max(peak, smooth[i]);
                double sp = hasSpeech ? (ps[e] - ps[s]) / length : -1;
                double emo = hasSpeech ? (pe[e] - pe[s]) / length : -1;
                double cut = pcut != null ? (pcut[e] - pcut[s]) / length : -1;
                double score = hasSpeech ? 0.40 * mean + 0.15 * peak + 0.25 * sp + 0.20 * emo : 0.7 * mean + 0.3 * peak;
                if (cut >= 0) score = 0.85 * score + 0.15 * cut;
                cands.add(new double[]{score, s, mean, peak, sp, emo, cut});
            }
            cands.sort((x, y) -> Double.compare(y[0], x[0]));
            List<Double> starts = new ArrayList<>();
            double minGap = length * 0.75;
            for (double[] c : cands) {
                boolean ok = true;
                for (double st : starts) if (Math.abs(c[1] - st) < minGap) ok = false;
                if (!ok) continue;
                List<String> reasons = new ArrayList<>();
                if (c[2] > 0.55) reasons.add("громко");
                if (c[3] > 0.85) reasons.add("всплеск звука");
                if (c[4] > 0.45) reasons.add("много речи");
                if (c[5] > 0.35) reasons.add("эмоции в репликах");
                if (c[6] > 0.5) reasons.add("быстрый монтаж");
                if (reasons.isEmpty()) reasons.add("выше среднего");
                double start = c[1];
                if (hasSpeech) {
                    Double best = null;
                    for (Subs.Line cue : cues) {
                        if (cue.s >= c[1] - 3 && cue.s <= c[1] + 1 && (best == null || Math.abs(cue.s - c[1]) < Math.abs(best - c[1]))) best = cue.s;
                    }
                    if (best != null) start = Math.max(0, best - 0.2);
                }
                starts.add(c[1]);
                out.put(cand(start, start + length, c[0], reasons));
                if (out.length() >= count) break;
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    private static double[] prefix(double[] a) {
        double[] p = new double[a.length + 1];
        for (int i = 0; i < a.length; i++) p[i + 1] = p[i] + a[i];
        return p;
    }

    private static JSONObject cand(double s, double e, double score, List<String> reasons) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("start", Math.round(s * 100) / 100.0);
        o.put("end", Math.round(e * 100) / 100.0);
        o.put("score", Math.round(score * 1000) / 1000.0);
        o.put("reasons", new JSONArray(reasons));
        return o;
    }
}
