import org.json.JSONArray;
import org.json.JSONObject;
import ru.tiktokclipper.core.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.*;

/** Тесты ядра на обычной JVM (без Android). Запуск: tools/run_jvm_tests.sh */
public class CoreTest {
    static int passed = 0, failed = 0;
    static final LocalDate D0 = LocalDate.of(2026, 11, 2);

    interface T { void run() throws Exception; }

    static void test(String name, T t) {
        try {
            t.run();
            passed++;
            System.out.println("✔ " + name);
        } catch (Throwable e) {
            failed++;
            System.out.println("✘ " + name + ": " + e);
            e.printStackTrace(System.out);
        }
    }

    static void check(boolean c, String msg) {
        if (!c) throw new AssertionError(msg);
    }

    static JSONObject cfg(String... srcs) throws Exception {
        JSONObject c = new JSONObject();
        c.put("start_date", D0.toString());
        c.put("interval_days", 3);
        c.put("posts_per_block", 3);
        c.put("type_order", new JSONArray(Arrays.asList("series", "movie", "stream", "blogger")));
        JSONArray a = new JSONArray();
        for (String s : srcs) {
            String[] p = s.split(":");
            a.put(new JSONObject().put("id", p[0]).put("type", p[1]).put("title", p[0]));
        }
        c.put("sources", a);
        return c;
    }

    static JSONObject full() throws Exception {
        return cfg("bb:series", "got:series", "matrix:movie", "s1:stream", "s2:stream", "blog:blogger");
    }

    /** Публикует n постов с шагом days (по умолчанию — по плану); каждый skipEvery-й пропускается. */
    static List<String> run(JSONObject c, int n, int days, int skipEvery, JSONObject[] stOut) throws Exception {
        JSONObject st = Schedule.normalizeState(null);
        LocalDate today = D0;
        List<String> seq = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            JSONObject p = Schedule.upcoming(c, st, today, 1).get(0);
            if (skipEvery > 0 && i % skipEvery == skipEvery - 1) Schedule.skip(c, st, today);
            else Schedule.publish(c, st, today, null, null);
            seq.add(p.getInt("block") + "|" + p.getInt("pos") + "|" + p.getString("type") + "|" + p.getString("source_id"));
            today = today.plusDays(days > 0 ? days : c.getInt("interval_days"));
        }
        if (stOut != null) stOut[0] = st;
        return seq;
    }

    public static void main(String[] args) throws Exception {
        // ---------------------------------------------------------------- расписание
        test("блок — один источник, типы чередуются, источники по очереди", () -> {
            List<String> seq = run(full(), 36, 0, 0, null);
            Map<Integer, List<String[]>> blocks = new TreeMap<>();
            for (String s : seq) {
                String[] p = s.split("\\|");
                blocks.computeIfAbsent(Integer.parseInt(p[0]), k -> new ArrayList<>()).add(p);
            }
            List<String> types = new ArrayList<>(), series = new ArrayList<>(), streams = new ArrayList<>();
            for (List<String[]> b : blocks.values()) {
                check(b.size() == 3, "в блоке 3 поста");
                check(b.get(0)[3].equals(b.get(1)[3]) && b.get(1)[3].equals(b.get(2)[3]), "в блоке один источник");
                types.add(b.get(0)[2]);
                if (b.get(0)[2].equals("series")) series.add(b.get(0)[3]);
                if (b.get(0)[2].equals("stream")) streams.add(b.get(0)[3]);
            }
            for (int i = 0; i + 1 < types.size(); i++) check(!types.get(i).equals(types.get(i + 1)), "два блока одного типа подряд: " + types);
            check(types.subList(0, 4).equals(Arrays.asList("series", "movie", "stream", "blogger")), "порядок типов " + types);
            check(series.equals(Arrays.asList("bb", "got", "bb")), "сериалы по очереди " + series);
            check(streams.equals(Arrays.asList("s1", "s2", "s1")), "стримы по очереди " + streams);
        });
        test("даты: пн → чт → вс", () -> {
            List<JSONObject> posts = Schedule.upcoming(full(), Schedule.normalizeState(null), D0, 4);
            String days = "";
            for (JSONObject p : posts) days += LocalDate.parse(p.getString("date")).getDayOfWeek().toString().substring(0, 3) + " ";
            check(days.equals("MON THU SUN WED "), days);
        });
        test("пропущенные дни не ломают порядок", () -> {
            List<String> ideal = run(full(), 20, 0, 0, null), late = run(full(), 20, 5, 0, null), early = run(full(), 20, 1, 0, null);
            check(ideal.equals(late) && ideal.equals(early), "порядок изменился");
        });
        test("без публикации текущий пост тот же и просрочен", () -> {
            JSONObject c = full(), st = Schedule.normalizeState(null);
            JSONObject a = Schedule.upcoming(c, st, D0, 1).get(0);
            LocalDate later = D0.plusDays(10);
            JSONObject b = Schedule.upcoming(c, st, later, 1).get(0);
            check(a.getInt("index") == b.getInt("index"), "пост сменился");
            check(Schedule.dueStatus(LocalDate.parse(b.getString("date")), later).equals("просрочен на 10 дн."), "статус");
            Schedule.publish(c, st, later, null, null);
            JSONObject n = Schedule.upcoming(c, st, later, 1).get(0);
            check(n.getInt("pos") == 2 && n.getString("date").equals(later.plusDays(3).toString()), n.toString());
        });
        test("«Пропустить» не ломает последовательность", () -> {
            JSONObject[] st = new JSONObject[1];
            check(run(full(), 18, 0, 0, null).equals(run(full(), 18, 0, 4, st)), "порядок");
            int skipped = 0;
            JSONArray h = st[0].getJSONArray("history");
            for (int i = 0; i < h.length(); i++) if (h.getJSONObject(i).getString("action").equals("skipped")) skipped++;
            check(skipped == 4, "пропусков " + skipped);
        });
        test("перенос и отмена", () -> {
            JSONObject c = full(), st = Schedule.normalizeState(null);
            Schedule.postpone(c, st, D0, 1);
            check(Schedule.upcoming(c, st, D0, 1).get(0).getString("date").equals(D0.plusDays(1).toString()), "перенос");
            Schedule.publish(c, st, D0, "bb/e1.mkv", null);
            check(st.getJSONObject("used_files").getJSONArray("bb").length() == 1, "used");
            Schedule.undo(st);
            check(st.getJSONArray("history").length() == 0 && st.getJSONObject("used_files").getJSONArray("bb").length() == 0, "undo");
            check(Schedule.upcoming(c, st, D0, 1).get(0).getString("date").equals(D0.plusDays(1).toString()), "дата после undo");
        });
        test("новый источник посреди блока не меняет текущий блок", () -> {
            JSONObject c = cfg("bb:series", "m:movie"), st = Schedule.normalizeState(null);
            Schedule.upcoming(c, st, D0, 1);
            Schedule.publish(c, st, D0, null, null);
            JSONArray a = new JSONArray();
            a.put(new JSONObject().put("id", "new").put("type", "series").put("title", "new"));
            for (int i = 0; i < c.getJSONArray("sources").length(); i++) a.put(c.getJSONArray("sources").get(i));
            c.put("sources", a);
            JSONObject p = Schedule.upcoming(c, st, D0, 1).get(0);
            check(p.getString("source_id").equals("bb") && p.getInt("pos") == 2, p.toString());
        });
        test("удалённый источник — блок переназначается", () -> {
            JSONObject c = full(), st = Schedule.normalizeState(null);
            Schedule.upcoming(c, st, D0, 1);
            c.getJSONArray("sources").remove(0);
            JSONObject p = Schedule.upcoming(c, st, D0, 1).get(0);
            check(p.getString("source_id").equals("got"), p.toString());
        });

        // ---------------------------------------------------------------- субтитры
        Subs.Measurer approx = (t, size) -> t.length() * size * 0.72f;
        test("группировка слов и знаки препинания", () -> {
            List<Subs.Word> ws = new ArrayList<>();
            String[] t = "раз два три четыре. пять шесть".split(" ");
            for (int i = 0; i < t.length; i++) ws.add(new Subs.Word(t[i], i * 0.3, i * 0.3 + 0.25));
            List<Subs.Line> ls = Subs.groupWords(ws, 3);
            List<String> texts = new ArrayList<>();
            for (Subs.Line l : ls) texts.add(l.text);
            check(texts.equals(Arrays.asList("раз два три", "четыре.", "пять шесть")), texts.toString());
        });
        test("правка строк сохраняет таймкоды неизменённых слов", () -> {
            Subs.Line l = new Subs.Line();
            l.s = 0.5; l.e = 1.7; l.text = "Привет мир";
            l.words.add(new Subs.Word("Привет", 0.5, 1.0));
            l.words.add(new Subs.Word("мир", 1.1, 1.7));
            JSONArray ed = new JSONArray().put(new JSONObject().put("s", 0.5).put("e", 1.7).put("text", "Привет мир"));
            check(Subs.applyEdits(ed, Collections.singletonList(l), 5).get(0).words.get(1).s == 1.1, "таймкод");
            JSONArray ed2 = new JSONArray().put(new JSONObject().put("s", 0.5).put("e", 1.7).put("text", "Здравствуй, дорогой мир"));
            List<Subs.Line> r = Subs.applyEdits(ed2, Collections.singletonList(l), 5);
            check(r.get(0).words.size() == 3 && Math.abs(r.get(0).words.get(2).e - 1.7) < 0.01, "распределение");
        });
        test("SRT туда и обратно", () -> {
            Subs.Line l = new Subs.Line();
            l.s = 0.5; l.e = 1.7; l.text = "Привет мир";
            String srt = Subs.toSrt(Collections.singletonList(l));
            check(srt.contains("00:00:00,500 --> 00:00:01,700"), srt);
            check(Subs.parseSrt("﻿" + srt.replace("\n", "\r\n")).get(0).text.equals("Привет мир"), "parse");
        });
        test("события: подсветка по словам, текст внутри безопасной зоны", () -> {
            List<Subs.Word> ws = Arrays.asList(new Subs.Word("ДОСТОПРИМЕЧАТЕЛЬНОСТИ", 0.2, 1.2),
                    new Subs.Word("превосходнейшие", 1.2, 2.0), new Subs.Word("великолепно", 2.0, 2.9));
            JSONObject style = Store.subStyle("classic");
            List<Subs.Word> shortW = Arrays.asList(new Subs.Word("раз", 0, 0.4), new Subs.Word("два", 0.4, 0.8), new Subs.Word("три", 0.8, 1.2));
            List<Subs.Event> hl = Subs.events(Subs.groupWords(shortW, 3), style, 1300, approx);
            check(hl.size() == 3 && hl.get(1).highlight == 1 && hl.get(1).rows.get(0).size() == 3, "подсветка");
            List<Subs.Event> ev = Subs.events(Subs.groupWords(ws, 3), style, 1900, approx);
            check(ev.size() == 3, "событий " + ev.size());
            for (Subs.Event e : ev) {
                for (List<String> row : e.rows) {
                    float w = approx.width(String.join(" ", row), e.size) + 14;
                    check(w <= Subs.SAFE_RIGHT - Subs.SAFE_LEFT + 0.5, "ширина " + w);
                }
                float h = e.rows.size() * e.size * 1.22f;
                check(e.cy - h / 2 >= Subs.SAFE_TOP && e.cy + h / 2 <= Subs.SAFE_BOTTOM, "высота");
            }
            check(Subs.toAss(hl, style).contains("{\\c&H0000D4FF}ДВА"), "ASS");
        });
        test("фильтр галлюцинаций", () -> {
            List<Subs.Word> ws = Arrays.asList(new Subs.Word("Субтитры", 0, 1), new Subs.Word("сделал", 1, 2), new Subs.Word("DimaTorzok", 2, 3));
            check(Subs.cleanWords(ws).isEmpty(), "не отфильтровано");
        });

        // ---------------------------------------------------------------- моменты
        test("моменты: громкий участок и плотность речи", () -> {
            double[] lv = new double[90];
            Arrays.fill(lv, -40);
            for (int i = 50; i < 60; i++) lv[i] = -10;
            JSONObject top = Moments.find(lv, 10, null, 5).getJSONObject(0);
            check(top.getDouble("start") >= 45 && top.getDouble("start") <= 55, top.toString());
            double[] flat = new double[300];
            Arrays.fill(flat, -40);
            List<Subs.Line> cues = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                Subs.Line c = new Subs.Line();
                c.s = 200 + i; c.e = 200.9 + i; c.text = "очень много слов в этой реплике";
                cues.add(c);
            }
            JSONObject t2 = Moments.find(flat, 30, cues, 5).getJSONObject(0);
            check(t2.getDouble("start") >= 190 && t2.getJSONArray("reasons").toString().contains("много речи"), t2.toString());
        });

        // ---------------------------------------------------------------- MP4 faststart
        test("moov переносится в начало, видео не портится", () -> {
            File dir = Files.createTempDirectory("fs").toFile();
            File in = new File(dir, "in.mp4"), out = new File(dir, "out.mp4");
            sh("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=s=320x240:d=3",
                    "-f", "lavfi", "-i", "sine=d=3", "-c:v", "libx264", "-c:a", "aac", "-pix_fmt", "yuv420p", in.getPath());
            check(!Mp4FastStart.isFastStart(in), "исходник уже faststart");
            check(Mp4FastStart.process(in, out), "не переписан");
            check(Mp4FastStart.isFastStart(out), "moov не в начале");
            String a = sh("ffmpeg", "-hide_banner", "-loglevel", "error", "-i", in.getPath(), "-f", "md5", "-");
            String b = sh("ffmpeg", "-hide_banner", "-loglevel", "error", "-i", out.getPath(), "-f", "md5", "-");
            check(a.equals(b) && a.startsWith("MD5="), "содержимое отличается: " + a + " / " + b);
        });

        // ---------------------------------------------------------------- очередь
        test("очередь переживает перезапуск", () -> {
            File f = File.createTempFile("jobs", ".json");
            f.delete();
            Map<String, JobQueue.Handler> h = new HashMap<>();
            h.put("t", ctx -> new JSONObject().put("ok", 1));
            JobQueue q1 = new JobQueue(f, h);
            JSONObject j = q1.submit("t", new JSONObject().put("a", 1), "x");
            JSONArray raw = new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            raw.getJSONObject(0).put("status", "running");
            Files.write(f.toPath(), raw.toString().getBytes(StandardCharsets.UTF_8));
            JobQueue q2 = new JobQueue(f, h);
            check(q2.get(j.getString("id")).getString("status").equals("queued"), "не вернулась в очередь");
            q2.start();
            check(q2.waitIdle(5000), "не выполнилась");
            JSONObject jj = q2.get(j.getString("id"));
            check(jj.getString("status").equals("done") && jj.getJSONArray("warnings").toString().contains("заново"), jj.toString());
            q2.stop();
        });
        test("ошибка и отмена задачи", () -> {
            File f = File.createTempFile("jobs", ".json");
            f.delete();
            Map<String, JobQueue.Handler> h = new HashMap<>();
            h.put("boom", ctx -> { throw new IllegalStateException("сломалось"); });
            h.put("wait", ctx -> { while (true) { ctx.check(); Thread.sleep(20); } });
            JobQueue q = new JobQueue(f, h);
            JSONObject a = q.submit("boom", new JSONObject(), "a"), b = q.submit("wait", new JSONObject(), "b");
            q.start();
            for (int i = 0; i < 100 && !q.get(b.getString("id")).getString("status").equals("running"); i++) Thread.sleep(20);
            q.cancel(b.getString("id"));
            check(q.waitIdle(5000), "не завершилось");
            check(q.get(a.getString("id")).getString("error").equals("сломалось"), "ошибка");
            check(q.get(b.getString("id")).getString("status").equals("canceled"), "отмена");
            q.stop();
        });

        // ---------------------------------------------------------------- HTTP + API
        test("HTTP: ключ сессии, Host, Range, расписание через API", () -> {
            File dir = Files.createTempDirectory("api").toFile();
            Store store = new Store(dir);
            JobQueue q = new JobQueue(store.jobsFile(), new HashMap<>());
            byte[] video = new byte[100000];
            new Random(1).nextBytes(video);
            Api api = new Api(store, new FakePlatform(video), q, "sekret-klyuch-123");
            api.fixedToday = D0;
            MiniHttp http = new MiniHttp(api);
            int port = http.start(18555);
            String base = "http://127.0.0.1:" + port;
            try {
                check(get(base + "/api/config", null, null)[0].equals("401"), "без ключа должен быть 401");
                check(get(base + "/api/today", "clipper_k=неверный", null)[0].equals("401"), "неверный ключ");
                check(get(base + "/auth?k=wrong", null, null)[0].equals("401"), "auth с неверным ключом");
                String[] au = get(base + "/auth?k=" + java.net.URLEncoder.encode("sekret-klyuch-123", "UTF-8"), null, null);
                check(au[0].equals("302") && au[2].contains("HttpOnly") && au[2].contains("SameSite=Strict"), Arrays.toString(au));
                String ck = "clipper_k=sekret-klyuch-123";
                check(getHost(port, "/api/config", ck, "evil.com").startsWith("HTTP/1.1 403"), "чужой Host");
                check(get(base + "/api/today", ck, null)[1].contains("\"empty\":true"), "пустое расписание");
                post(base + "/api/sources", ck, "{\"type\":\"series\",\"title\":\"Во все тяжкие\"}");
                post(base + "/api/sources", ck, "{\"type\":\"movie\",\"title\":\"Матрица\"}");
                String t = get(base + "/api/today", ck, null)[1];
                check(t.contains("Во все тяжкие") && t.contains("\"pos\":1"), t);
                post(base + "/api/schedule/publish", ck, "{}");
                t = get(base + "/api/today", ck, null)[1];
                check(t.contains("\"pos\":2"), t);
                String[] r = get(base + "/media/x/a.mp4", ck, "bytes=1000-1999");
                check(r[0].equals("206") && r[3].equals("1000"), "Range: " + r[0] + " " + r[3]);
                check(get(base + "/", ck, null)[1].contains("<html"), "index.html");
            } finally {
                http.stop();
            }
        });

        System.out.println("\nИтого: " + passed + " прошло, " + failed + " упало");
        System.exit(failed == 0 ? 0 : 1);
    }

    static String sh(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(Store.readAll(p.getInputStream()), StandardCharsets.UTF_8).trim();
        if (p.waitFor() != 0) throw new IOException(String.join(" ", cmd) + ": " + out);
        return out;
    }

    /** [код, тело, Set-Cookie, длина тела] */
    static String[] get(String url, String cookie, String range) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
        c.setInstanceFollowRedirects(false);
        if (cookie != null) c.setRequestProperty("Cookie", encodeCookie(cookie));
        if (range != null) c.setRequestProperty("Range", range);
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        byte[] body = in == null ? new byte[0] : Store.readAll(in);
        String sc = c.getHeaderField("Set-Cookie");
        return new String[]{String.valueOf(code), new String(body, StandardCharsets.UTF_8), sc == null ? "" : sc, String.valueOf(body.length)};
    }

    // Cookie с кириллицей HttpURLConnection не пропустит — шлём как есть сырыми байтами через сокет ниже,
    // а здесь тест использует ASCII-совместимое кодирование: сервер сравнивает байты UTF-8.
    static String encodeCookie(String c) {
        return new String(c.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    static String post(String url, String cookie, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Cookie", encodeCookie(cookie));
        c.setRequestProperty("Content-Type", "application/json");
        c.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = new String(Store.readAll(in), StandardCharsets.UTF_8);
        if (code != 200) throw new IOException(code + " " + body);
        return body;
    }

    static String getHost(int port, String path, String cookie, String host) throws Exception {
        try (java.net.Socket s = new java.net.Socket("127.0.0.1", port)) {
            String req = "GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nCookie: " + cookie + "\r\nConnection: close\r\n\r\n";
            s.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
            return new String(Store.readAll(s.getInputStream()), StandardCharsets.UTF_8);
        }
    }

    static class FakePlatform implements Platform {
        final byte[] video;
        FakePlatform(byte[] v) { video = v; }
        public JSONArray listFiles(JSONObject source) { return new JSONArray(); }
        public MiniHttp.RangeSource openMedia(String id) {
            return new MiniHttp.RangeSource() {
                public long size() { return video.length; }
                public InputStream open(long off) { return new ByteArrayInputStream(video, (int) off, video.length - (int) off); }
            };
        }
        public String mimeOf(String id) { return "video/mp4"; }
        public JSONObject probe(String id) { return new JSONObject(); }
        public byte[] frameJpeg(String id, double t, int w) { return new byte[0]; }
        public String sidecarSrt(String id) { return null; }
        public JSONObject status() {
            try { return new JSONObject().put("model_ids", new JSONArray()); } catch (Exception e) { throw new RuntimeException(e); }
        }
        public JSONObject faceDetect(String id, double t) { return new JSONObject(); }
        public MiniHttp.RangeSource openClip(JSONObject m) { return null; }
        public boolean clipExists(JSONObject m) { return false; }
        public void deleteClip(JSONObject m) { }
        public JSONObject feed(JSONObject s) { return new JSONObject(); }
        public byte[] asset(String p) { return p.equals("index.html") ? "<html>ok</html>".getBytes() : null; }
    }
}
