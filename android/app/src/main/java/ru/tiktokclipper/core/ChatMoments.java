package ru.tiktokclipper.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Моменты по чату записи эфира: где зрители писали заметно чаще обычного.
 * Файл чата лежит рядом с видео. Понимает:
 * JSON TwitchDownloader (comments[].content_offset_seconds), JSON-массивы с time_in_seconds,
 * повтор чата YouTube (строки JSON с videoOffsetTimeMsec), текст «[0:01:23] ник: сообщение»
 * или «[2026-10-28 19:01:23 UTC] ник: сообщение». Файл может быть сжат gzip.
 * Читается потоком — большие чаты (сотни тысяч сообщений) не грузятся в память целиком.
 */
public final class ChatMoments {
    private ChatMoments() {
    }

    /** Сообщения чата: время (с от начала записи) и начало текста. */
    public static final class Chat {
        public float[] t = new float[1024];
        public String[] text = new String[1024];
        public int n;

        void add(double time, String msg) {
            if (time < 0 || time > 7 * 86400) return;
            if (n == t.length) {
                t = Arrays.copyOf(t, n * 2);
                text = Arrays.copyOf(text, n * 2);
            }
            t[n] = (float) time;
            String s = msg == null ? "" : msg.trim();
            text[n] = s.length() > 40 ? s.substring(0, 40) : s;
            n++;
        }
    }

    /** Похоже ли имя файла на чат к видео stem (без расширения). */
    public static boolean isChatFor(String fileName, String stem) {
        String low = fileName.toLowerCase(Locale.ROOT), st = stem.toLowerCase(Locale.ROOT);
        if (!(low.startsWith(st + ".") || low.startsWith(st + "_") || low.startsWith(st + "-") || low.startsWith(st + " "))) return false;
        return isChatName(low);
    }

    public static boolean isChatName(String name) {
        String low = name.toLowerCase(Locale.ROOT);
        return low.endsWith(".json") || low.endsWith(".json.gz") || low.endsWith(".jsonl") || low.endsWith(".txt") || low.endsWith(".log");
    }

    public static Chat read(InputStream raw) throws IOException {
        BufferedInputStream in = new BufferedInputStream(raw, 1 << 16);
        in.mark(4);
        int b0 = in.read(), b1 = in.read();
        in.reset();
        InputStream src = b0 == 0x1f && b1 == 0x8b ? new BufferedInputStream(new GZIPInputStream(in, 1 << 16)) : in;
        Reader r = new BufferedReader(new InputStreamReader(src, StandardCharsets.UTF_8), 1 << 16);
        r.mark(4096);
        char[] head = new char[256];
        int hn = Math.max(0, r.read(head));
        r.reset();
        String h = new String(head, 0, hn).replace("\uFEFF", "").trim();
        Chat chat = new Chat();
        // «[0:01:23] ник: …» и «[2026-10-28 …» — текстовый лог, хотя начинается со скобки, как JSON-массив
        boolean json = (h.startsWith("{") || h.startsWith("[")) && !h.matches("(?s)^\\[\\s*\\d.*");
        if (json) new JsonScan(r, chat).run();
        else readText(r, chat);
        return chat;
    }

    // ------------------------------------------------------------ текстовый лог

    static final Pattern LINE = Pattern.compile(
            "^\\s*\\[?\\s*(?:(\\d{4}-\\d{2}-\\d{2})[ T])?(\\d{1,3}):(\\d{2}):(\\d{2})(?:[.,]\\d+)?\\s*(?:UTC|Z)?\\s*\\]?\\s*(.*)$");

    static void readText(Reader r, Chat chat) throws IOException {
        BufferedReader br = r instanceof BufferedReader ? (BufferedReader) r : new BufferedReader(r);
        String line;
        LocalDateTime first = null;
        double clock0 = -1;
        while ((line = br.readLine()) != null) {
            Matcher m = LINE.matcher(line.replace("\uFEFF", ""));
            if (!m.matches()) continue;
            int h = Integer.parseInt(m.group(2)), mi = Integer.parseInt(m.group(3)), s = Integer.parseInt(m.group(4));
            double t;
            if (m.group(1) != null) {
                LocalDateTime at = LocalDateTime.parse(m.group(1) + String.format(Locale.ROOT, "T%02d:%02d:%02d", h % 24, mi, s));
                if (first == null) first = at;
                t = java.time.Duration.between(first, at).getSeconds();
            } else {
                t = h * 3600 + mi * 60 + s;
                // лог по часам (Chatterino: «[19:01:23] ник: …»), а не от начала записи — считаем от первого сообщения
                if (clock0 < 0) clock0 = t > 600 ? t : 0;
                if (clock0 > 0) {
                    t -= clock0;
                    while (t < -3600) t += 86400; // эфир через полночь
                }
            }
            String rest = m.group(5);
            int colon = rest.indexOf(": ");
            chat.add(t, colon >= 0 && colon < 40 ? rest.substring(colon + 2) : rest);
        }
    }

    // ------------------------------------------------------------ JSON потоком

    /**
     * Потоковый разбор JSON без построения дерева. Объект, в котором прямо лежит ключ времени,
     * — одно сообщение; его текст — первая строка с ключом body/message/text в нём или глубже.
     */
    static final class JsonScan {
        final Reader r;
        final Chat chat;
        int peeked = -2;

        JsonScan(Reader r, Chat chat) {
            this.r = r;
            this.chat = chat;
        }

        static final class Obj {
            double time = Double.NaN;
            String text;
        }

        final List<Obj> stack = new ArrayList<>();

        int next() throws IOException {
            if (peeked != -2) {
                int c = peeked;
                peeked = -2;
                return c;
            }
            return r.read();
        }

        int nextNonSpace() throws IOException {
            int c;
            do {
                c = next();
            } while (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\uFEFF');
            return c;
        }

        void run() throws IOException {
            int c;
            while ((c = nextNonSpace()) != -1) value(c);
        }

        /** Разбирает значение, первый символ которого уже прочитан. Возвращает строку (для строк) или null. */
        String value(int c) throws IOException {
            if (c == '{') {
                object();
                return null;
            }
            if (c == '[') {
                array();
                return null;
            }
            if (c == '"') return string(true);
            // число, true/false/null — до разделителя
            StringBuilder sb = new StringBuilder();
            sb.append((char) c);
            int d;
            while ((d = next()) != -1 && d != ',' && d != '}' && d != ']' && d != ' ' && d != '\n' && d != '\r' && d != '\t') {
                sb.append((char) d);
            }
            peeked = d;
            return "#" + sb;
        }

        void array() throws IOException {
            int c = nextNonSpace();
            if (c == ']') return;
            while (c != -1) {
                value(c);
                c = nextNonSpace();
                if (c == ',') c = nextNonSpace();
                else break; // ']' или конец
            }
        }

        void object() throws IOException {
            Obj o = new Obj();
            stack.add(o);
            int c = nextNonSpace();
            while (c == '"') {
                String key = string(true);
                c = nextNonSpace(); // ':'
                if (c != ':') break;
                c = nextNonSpace();
                String v = value(c);
                if (v != null) {
                    boolean num = v.startsWith("#");
                    String s = num ? v.substring(1) : v;
                    if (key.equals("content_offset_seconds") || key.equals("time_in_seconds")) o.time = parse(s, 1);
                    else if (key.equals("videoOffsetTimeMsec") || key.equals("video_offset_time_msec")) o.time = parse(s, 1000);
                    else if (!num && o.text == null && (key.equals("body") || key.equals("message") || key.equals("text"))) o.text = s;
                }
                c = nextNonSpace();
                if (c == ',') c = nextNonSpace();
            }
            stack.remove(stack.size() - 1);
            if (!Double.isNaN(o.time)) chat.add(o.time, o.text);
            else if (o.text != null && !stack.isEmpty()) {
                Obj parent = stack.get(stack.size() - 1);
                if (parent.text == null) parent.text = o.text; // текст из вложенного message{body}
            }
        }

        static double parse(String s, double div) {
            try {
                return Double.parseDouble(s.trim()) / div;
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }

        String string(boolean keep) throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = next()) != -1 && c != '"') {
                if (c == '\\') {
                    int e = next();
                    switch (e) {
                        case 'n': sb.append(' '); break;
                        case 't': sb.append(' '); break;
                        case 'r': break;
                        case 'b': case 'f': break;
                        case 'u': {
                            char[] h = new char[4];
                            for (int i = 0; i < 4; i++) h[i] = (char) next();
                            try {
                                sb.append((char) Integer.parseInt(new String(h), 16));
                            } catch (NumberFormatException ignored) {
                                // повреждённая последовательность
                            }
                            break;
                        }
                        default: sb.append((char) e);
                    }
                } else if (keep && sb.length() < 200) sb.append((char) c);
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------ всплески

    /** Реакции, которые весят больше обычного сообщения. */
    static final Pattern HYPE = Pattern.compile(
            "(?iu)kekw|lul|lmao|omegalul|pog|pogchamp|ахах|хаха|хах|аха|ор(у|ууу)|лол|жесть|ваа+у|вау|омг|omg|wtf|clip|клип|😂|🤣|😱|🔥|!!!|\\?\\?\\?|ееее|ааааа");

    /**
     * Окна, где чат писал в разы чаще обычного. length — длина клипа, с; duration — длина видео (0 — по чату).
     * Начало момента — за 15 с до всплеска: чат реагирует с опозданием (задержка трансляции + время набрать сообщение).
     */
    public static JSONArray find(Chat chat, double duration, int length, int count) {
        JSONArray out = new JSONArray();
        if (chat.n == 0) return out;
        float last = 0;
        for (int i = 0; i < chat.n; i++) last = Math.max(last, chat.t[i]);
        int n = (int) Math.ceil(duration > 0 ? duration : last) + 1;
        double[] w = new double[n];
        int[] cnt = new int[n];
        for (int i = 0; i < chat.n; i++) {
            int s = (int) chat.t[i];
            if (s < 0 || s >= n) continue;
            cnt[s]++;
            w[s] += HYPE.matcher(chat.text[i]).find() ? 1.6 : 1.0;
        }
        final int W = 15;
        double[] pw = new double[n + 1];
        int[] pc = new int[n + 1];
        for (int i = 0; i < n; i++) {
            pw[i + 1] = pw[i] + w[i];
            pc[i + 1] = pc[i] + cnt[i];
        }
        int m = Math.max(1, n - W + 1);
        double[] rate = new double[m];
        for (int s = 0; s < m; s++) rate[s] = pw[Math.min(n, s + W)] - pw[s];
        double mean = (pw[n]) / Math.max(1, n) * W;
        // обычный темп чата вокруг (±5 минут): зрителей к середине эфира становится больше
        double[] score = new double[m], base = new double[m];
        double[] buf = new double[130];
        for (int s = 0; s < m; s++) {
            int a = Math.max(0, s - 300), b = Math.min(m - 1, s + 300), k = 0;
            for (int j = a; j <= b && k < buf.length; j += Math.max(1, (b - a) / 120)) buf[k++] = rate[j];
            double med = Moments.percentile(Arrays.copyOf(buf, k), 0.5);
            base[s] = Math.max(med, Math.max(1.0, 0.3 * mean));
            score[s] = rate[s] / base[s] * Math.log(2 + rate[s]);
        }
        Integer[] idx = new Integer[m];
        for (int i = 0; i < m; i++) idx[i] = i;
        Arrays.sort(idx, (x, y) -> Double.compare(score[y], score[x]));
        List<Integer> picked = new ArrayList<>();
        List<Integer> onsets = new ArrayList<>();
        double gap = Math.max(length, 30);
        for (int s : idx) {
            if (rate[s] < 3) break;
            // самая плотная секунда окна (по трём секундам) — сам всплеск
            int on = s;
            double bestD = -1;
            for (int i = s; i < Math.min(n, s + W); i++) {
                double d = w[i] + (i + 1 < n ? w[i + 1] : 0) + (i + 2 < n ? w[i + 2] : 0);
                if (d > bestD) {
                    bestD = d;
                    on = i;
                }
            }
            boolean ok = true;
            for (int p : onsets) if (Math.abs(p - on) < gap) ok = false;
            if (!ok) continue;
            picked.add(s);
            onsets.add(on);
            if (picked.size() >= count) break;
        }
        if (picked.isEmpty()) return out;
        double top = score[picked.get(0)];
        try {
            for (int pi = 0; pi < picked.size(); pi++) {
                int s = picked.get(pi), on = onsets.get(pi);
                double start = Math.max(0, on - 15);
                double end = duration > 0 ? Math.min(duration, start + length) : start + length;
                int msgs = pc[Math.min(n, s + W)] - pc[s];
                double ratio = rate[s] / base[s];
                List<String> reasons = new ArrayList<>();
                reasons.add(String.format(Locale.ROOT, "чат ×%.1f", ratio).replace('.', ','));
                reasons.add(msgs + " сообщ. за 15 с");
                String word = topWord(chat, s, s + W);
                if (word != null) reasons.add("«" + word + "»");
                JSONObject o = new JSONObject();
                o.put("start", Math.round(start * 100) / 100.0);
                o.put("end", Math.round(end * 100) / 100.0);
                o.put("score", Math.round(score[s] / top * 1000) / 1000.0);
                o.put("reasons", new JSONArray(reasons));
                o.put("peak", on);
                out.put(o);
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    /** Самое частое слово (или смайл) в окне — подпись момента. */
    static String topWord(Chat chat, int a, int b) {
        Map<String, Integer> f = new HashMap<>();
        for (int i = 0; i < chat.n; i++) {
            if (chat.t[i] < a || chat.t[i] >= b) continue;
            for (String wd : chat.text[i].split("[\\s,.!?:;()\"']+")) {
                if (wd.length() < 2 && !wd.matches("[^\\p{L}\\p{N}]+")) continue;
                if (wd.isEmpty() || wd.startsWith("@")) continue;
                String k = wd.length() > 20 ? wd.substring(0, 20) : wd;
                f.put(k, f.getOrDefault(k, 0) + 1);
            }
        }
        String best = null;
        int bc = 1;
        for (Map.Entry<String, Integer> e : f.entrySet()) {
            if (e.getValue() > bc) {
                bc = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }
}
