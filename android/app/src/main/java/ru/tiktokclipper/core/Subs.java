package ru.tiktokclipper.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Субтитры (перенос tclip/subtitles.py): слова с таймкодами -> строки по 2–3 слова -> события показа.
 * Время — секунды от начала клипа. Слово: {"w","s","e"}; строка: {"s","e","text","words"}.
 */
public final class Subs {
    public static final int W = 1080, H = 1920;
    // Безопасная зона TikTok: сверху вкладки, справа колонка иконок, снизу подпись и музыка.
    public static final int SAFE_LEFT = 60, SAFE_TOP = 220, SAFE_RIGHT = 900, SAFE_BOTTOM = 1440;

    private Subs() {
    }

    /** Ширина текста в пикселях кадра для шрифта заданного размера (без обводки). */
    public interface Measurer {
        float width(String text, float size);
    }

    public static final class Word {
        public String w;
        public double s, e;

        public Word(String w, double s, double e) {
            this.w = w;
            this.s = s;
            this.e = e;
        }

        JSONObject json() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("w", w);
            o.put("s", round3(s));
            o.put("e", round3(e));
            return o;
        }
    }

    public static final class Line {
        public double s, e;
        public String text;
        public List<Word> words = new ArrayList<>();

        public JSONObject json() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("s", round3(s));
            o.put("e", round3(e));
            o.put("text", text);
            JSONArray a = new JSONArray();
            for (Word w : words) a.put(w.json());
            o.put("words", a);
            return o;
        }
    }

    /** Одно событие показа: строка с подсвеченным словом (или без подсветки). */
    public static final class Event {
        public double t0, t1;
        public List<List<String>> rows = new ArrayList<>();
        public int highlight = -1; // номер слова в строке
        public float size;
        public float cx, cy;
    }

    public static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    // ------------------------------------------------------------ JSON <-> объекты

    public static List<Word> wordsFromJson(JSONArray a) {
        List<Word> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(new Word(o.optString("w"), o.optDouble("s"), o.optDouble("e")));
        }
        return out;
    }

    public static JSONArray wordsToJson(List<Word> words) {
        JSONArray a = new JSONArray();
        try {
            for (Word w : words) a.put(w.json());
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return a;
    }

    public static List<Line> linesFromJson(JSONArray a) {
        List<Line> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Line l = new Line();
            l.s = o.optDouble("s");
            l.e = o.optDouble("e");
            l.text = o.optString("text");
            l.words = wordsFromJson(o.optJSONArray("words"));
            out.add(l);
        }
        return out;
    }

    public static JSONArray linesToJson(List<Line> lines) {
        JSONArray a = new JSONArray();
        try {
            for (Line l : lines) a.put(l.json());
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return a;
    }

    // ------------------------------------------------------------ группировка

    private static final Pattern END_PUNCT = Pattern.compile("[.!?…]$");
    private static final Pattern PAUSE_PUNCT = Pattern.compile("[,;:—–]$");

    public static List<Line> groupWords(List<Word> words, int maxWords) {
        return groupWords(words, maxWords, 22, 0.7);
    }

    public static List<Line> groupWords(List<Word> words, int maxWords, int maxChars, double maxGap) {
        List<Line> lines = new ArrayList<>();
        List<Word> cur = new ArrayList<>();
        for (Word w : words) {
            if (w.w == null || w.w.trim().isEmpty()) continue;
            if (!cur.isEmpty()) {
                Word last = cur.get(cur.size() - 1);
                double gap = w.s - last.e;
                int chars = joined(cur).length() + 1 + w.w.trim().length();
                if (cur.size() >= maxWords || chars > maxChars || gap > maxGap || END_PUNCT.matcher(last.w).find()
                        || (PAUSE_PUNCT.matcher(last.w).find() && cur.size() >= 2)) {
                    lines.add(makeLine(cur));
                    cur = new ArrayList<>();
                }
            }
            cur.add(new Word(w.w.trim(), w.s, w.e));
        }
        if (!cur.isEmpty()) lines.add(makeLine(cur));
        return finalizeLines(lines, 0.35, 0.4);
    }

    private static String joined(List<Word> ws) {
        StringBuilder sb = new StringBuilder();
        for (Word w : ws) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(w.w);
        }
        return sb.toString();
    }

    private static Line makeLine(List<Word> ws) {
        Line l = new Line();
        l.s = ws.get(0).s;
        l.e = ws.get(ws.size() - 1).e;
        l.text = joined(ws);
        l.words = new ArrayList<>(ws);
        return l;
    }

    /** Убирает мигание между близкими строками и слишком короткие показы. */
    public static List<Line> finalizeLines(List<Line> in, double minDur, double joinGap) {
        List<Line> lines = new ArrayList<>();
        for (Line l : in) if (l.text != null && !l.text.trim().isEmpty()) lines.add(l);
        Collections.sort(lines, (a, b) -> Double.compare(a.s, b.s));
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            Double nxt = i + 1 < lines.size() ? lines.get(i + 1).s : null;
            if (nxt != null && nxt - l.e >= 0 && nxt - l.e < joinGap) l.e = nxt;
            if (l.e - l.s < minDur) l.e = nxt == null ? l.s + minDur : Math.min(l.s + minDur, Math.max(nxt, l.e));
            if (nxt != null && l.e > nxt) l.e = nxt;
            l.s = round3(l.s);
            l.e = round3(l.e);
        }
        return lines;
    }

    static List<Word> distribute(String text, double s, double e) {
        String[] toks = text.trim().split("\\s+");
        List<Word> out = new ArrayList<>();
        if (text.trim().isEmpty()) return out;
        int total = 0;
        for (String t : toks) total += t.length() + 1;
        double t0 = s;
        for (String tok : toks) {
            double d = (e - s) * (tok.length() + 1) / total;
            out.add(new Word(tok, round3(t0), round3(t0 + d)));
            t0 += d;
        }
        return out;
    }

    private static String norm(String t) {
        return t.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** Строки после правки: если текст не менялся — точные таймкоды слов сохраняются. */
    public static List<Line> applyEdits(JSONArray edited, List<Line> oldLines, double clipDur) {
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < edited.length(); i++) {
            JSONObject ed = edited.optJSONObject(i);
            if (ed == null) continue;
            String text = ed.optString("text").replaceAll("\\s+", " ").trim();
            if (text.isEmpty()) continue;
            double s = Math.max(0, Math.min(ed.optDouble("s", 0), clipDur));
            double e = Math.max(s + 0.1, Math.min(ed.optDouble("e", s + 1), clipDur));
            String[] toks = text.split(" ");
            Line match = null;
            for (Line o : oldLines) {
                if (norm(o.text).equals(norm(text)) && !o.words.isEmpty() && o.s < e && o.e > s) {
                    match = o;
                    break;
                }
            }
            Line l = new Line();
            l.s = round3(s);
            l.e = round3(e);
            l.text = text;
            if (match != null && match.words.size() == toks.length) {
                double os = match.s, oe = Math.max(match.e, match.s + 0.01);
                double k = (e - s) / (oe - os);
                for (int j = 0; j < toks.length; j++) {
                    Word w = match.words.get(j);
                    l.words.add(new Word(toks[j], round3(s + (w.s - os) * k), round3(s + (w.e - os) * k)));
                }
            } else {
                l.words = distribute(text, s, e);
            }
            out.add(l);
        }
        return finalizeLines(out, 0.1, 0.0);
    }

    /** Слова из готовых субтитров (.srt) в пределах отрезка клипа. */
    public static List<Word> wordsFromCues(List<Line> cues, double start, double end) {
        List<Word> out = new ArrayList<>();
        for (Line c : cues) {
            if (c.e <= start || c.s >= end) continue;
            out.addAll(distribute(c.text, Math.max(c.s, start) - start, Math.min(c.e, end) - start));
        }
        return out;
    }

    // ------------------------------------------------------------ SRT

    static String tsSrt(double t) {
        long ms = Math.round(Math.max(0, t) * 1000);
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000);
    }

    public static String toSrt(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Line l : lines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(i++).append('\n').append(tsSrt(l.s)).append(" --> ").append(tsSrt(l.e)).append('\n')
                    .append(l.text).append('\n');
        }
        return sb.toString();
    }

    private static final Pattern SRT_TIME = Pattern.compile(
            "(\\d+):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d+):(\\d{2}):(\\d{2})[,.](\\d{1,3})");

    public static List<Line> parseSrt(String text) {
        text = text.replace("﻿", "").replace("\r\n", "\n").replace('\r', '\n');
        List<Line> cues = new ArrayList<>();
        for (String block : text.split("\\n\\s*\\n")) {
            Matcher m = SRT_TIME.matcher(block);
            if (!m.find()) continue;
            double s = Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60 + Integer.parseInt(m.group(3))
                    + Integer.parseInt(padMs(m.group(4))) / 1000.0;
            double e = Integer.parseInt(m.group(5)) * 3600 + Integer.parseInt(m.group(6)) * 60 + Integer.parseInt(m.group(7))
                    + Integer.parseInt(padMs(m.group(8))) / 1000.0;
            String body = block.substring(m.end()).trim().replaceAll("<[^>]+>", "").replaceAll("\\{[^}]*\\}", "")
                    .replaceAll("\\s+", " ").trim();
            if (body.isEmpty()) continue;
            Line l = new Line();
            l.s = s;
            l.e = e;
            l.text = body;
            cues.add(l);
        }
        return cues;
    }

    private static String padMs(String g) {
        StringBuilder sb = new StringBuilder(g);
        while (sb.length() < 3) sb.append('0');
        return sb.toString();
    }

    // ------------------------------------------------------------ раскладка и события

    /** Разбивка строки на 1–2 ряда и размер шрифта, чтобы ширина влезла в безопасную зону. */
    public static float layoutLine(List<String> words, JSONObject style, Measurer m, List<List<String>> rowsOut) {
        float maxW = SAFE_RIGHT - SAFE_LEFT;
        float size = (float) style.optDouble("size", 84);
        float ol = (float) style.optDouble("outline", 7);
        String one = String.join(" ", words);
        float w1 = m.width(one, size) + 2 * ol;
        rowsOut.clear();
        if (w1 <= maxW) {
            rowsOut.add(new ArrayList<>(words));
            return size;
        }
        if (words.size() >= 2 && maxW / w1 < 0.75f) {
            List<List<String>> best = null;
            float bestW = Float.MAX_VALUE;
            for (int k = 1; k < words.size(); k++) {
                List<String> a = new ArrayList<>(words.subList(0, k)), b = new ArrayList<>(words.subList(k, words.size()));
                float widest = Math.max(m.width(String.join(" ", a), size), m.width(String.join(" ", b), size)) + 2 * ol;
                if (widest < bestW) {
                    bestW = widest;
                    best = new ArrayList<>();
                    best.add(a);
                    best.add(b);
                }
            }
            rowsOut.addAll(best);
            return size * Math.min(1f, maxW / bestW) * 0.98f;
        }
        rowsOut.add(new ArrayList<>(words));
        return size * maxW / w1 * 0.98f;
    }

    public static double subCenterY(JSONObject prof, String layout) {
        if (prof.has("sub_y") && !prof.isNull("sub_y")) return prof.optDouble("sub_y") * H;
        if ("split".equals(layout)) {
            double hs = prof.optDouble("split_screen_h", 0.45), hg = prof.optDouble("split_gap_h", 0.10);
            return hg >= 0.05 ? (hs + hg / 2) * H : (hs + 0.06) * H;
        }
        return 0.68 * H;
    }

    public static List<Event> events(List<Line> lines, JSONObject style, double centerY, Measurer m) {
        List<Event> out = new ArrayList<>();
        boolean upper = style.optBoolean("uppercase", true);
        boolean hi = !style.isNull("highlight");
        float outline = (float) style.optDouble("outline", 7);
        float cx = (SAFE_LEFT + SAFE_RIGHT) / 2f;
        for (Line l : lines) {
            List<String> words = new ArrayList<>();
            boolean wordsOk = !l.words.isEmpty() && joined(l.words).equals(l.text);
            if (wordsOk) for (Word w : l.words) words.add(w.w);
            else for (String t : l.text.trim().split("\\s+")) words.add(t);
            List<String> disp = new ArrayList<>();
            for (String w : words) disp.add(upper ? w.toUpperCase(new Locale("ru")) : w);
            List<List<String>> rows = new ArrayList<>();
            float size = layoutLine(disp, style, m, rows);
            float height = rows.size() * size * 1.22f + 2 * outline;
            float cy = (float) Math.min(Math.max(centerY, SAFE_TOP + height / 2), SAFE_BOTTOM - height / 2);
            if (!hi || !wordsOk) {
                Event ev = new Event();
                ev.t0 = l.s;
                ev.t1 = l.e;
                ev.rows = rows;
                ev.size = size;
                ev.cx = cx;
                ev.cy = cy;
                out.add(ev);
                continue;
            }
            int n = words.size();
            for (int k = 0; k < n; k++) {
                double t0 = k == 0 ? l.s : Math.max(l.s, l.words.get(k).s);
                double t1 = k == n - 1 ? l.e : Math.max(t0, l.words.get(k + 1).s);
                if (t1 - t0 < 0.01) continue;
                Event ev = new Event();
                ev.t0 = t0;
                ev.t1 = t1;
                ev.rows = rows;
                ev.size = size;
                ev.cx = cx;
                ev.cy = cy;
                ev.highlight = k;
                out.add(ev);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ ASS (сохраняется рядом с клипом)

    static String assColor(String rrggbb) {
        return ("&H00" + rrggbb.substring(4, 6) + rrggbb.substring(2, 4) + rrggbb.substring(0, 2)).toUpperCase(Locale.ROOT);
    }

    static String tsAss(double t) {
        long cs = Math.round(Math.max(0, t) * 100);
        return String.format(Locale.ROOT, "%d:%02d:%02d.%02d", cs / 360000, cs / 6000 % 60, cs / 100 % 60, cs % 100);
    }

    public static String toAss(List<Event> events, JSONObject style) {
        String base = assColor(style.optString("color", "FFFFFF"));
        String hi = style.isNull("highlight") ? base : assColor(style.optString("highlight"));
        StringBuilder sb = new StringBuilder();
        sb.append("[Script Info]\nScriptType: v4.00+\nPlayResX: 1080\nPlayResY: 1920\nWrapStyle: 2\n")
                .append("ScaledBorderAndShadow: yes\n\n[V4+ Styles]\n")
                .append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, ")
                .append("Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, ")
                .append("Alignment, MarginL, MarginR, MarginV, Encoding\n")
                .append("Style: Main,Montserrat ExtraBold,").append(style.optInt("size")).append(',').append(base).append(',')
                .append(base).append(",&H00000000,&H96000000,-1,0,0,0,100,100,0,0,1,").append(style.optInt("outline"))
                .append(',').append(style.optInt("shadow")).append(",5,60,180,0,1\n\n[Events]\n")
                .append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
        for (Event ev : events) {
            StringBuilder t = new StringBuilder();
            int idx = 0;
            for (int r = 0; r < ev.rows.size(); r++) {
                if (r > 0) t.append("\\N");
                List<String> row = ev.rows.get(r);
                for (int j = 0; j < row.size(); j++) {
                    if (j > 0) t.append(' ');
                    String w = row.get(j).replace("\\", "/").replace("{", "(").replace("}", ")");
                    if (idx == ev.highlight) t.append("{\\c").append(hi).append('}').append(w).append("{\\c").append(base).append('}');
                    else t.append(w);
                    idx++;
                }
            }
            sb.append("Dialogue: 0,").append(tsAss(ev.t0)).append(',').append(tsAss(ev.t1)).append(",Main,,0,0,0,,{\\pos(")
                    .append(Math.round(ev.cx)).append(',').append(Math.round(ev.cy)).append(")\\fs")
                    .append(String.format(Locale.ROOT, "%.1f", ev.size)).append('}').append(t).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ фильтр «галлюцинаций» распознавания

    private static final Pattern HALL = Pattern.compile(
            "субтитры (сделал|создавал|подготовил|делал)|редактор субтитров|продолжение следует|спасибо за просмотр|"
                    + "подписывайтесь на (канал|наш канал)|dimatorzok|amara\\.org|thanks for watching|subtitles by",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static List<Word> cleanWords(List<Word> words) {
        if (!words.isEmpty() && words.size() <= 12 && HALL.matcher(joined(words)).find()) return new ArrayList<>();
        List<Word> out = new ArrayList<>();
        for (Word w : words) {
            String t = w.w == null ? "" : w.w.replaceAll("\\s+", " ").trim();
            if (t.isEmpty() || t.equals("-") || t.equals("–") || t.equals("—") || t.equals("♪")) continue;
            out.add(new Word(t, round3(w.s), round3(Math.max(w.s, w.e))));
        }
        Collections.sort(out, (a, b) -> Double.compare(a.s, b.s));
        return out;
    }
}
