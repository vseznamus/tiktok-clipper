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
                post(base + "/api/sources", ck, "{\"type\":\"stream\",\"title\":\"Стример Вася\",\"twitch_login\":\"vasya\"}");
                post(base + "/api/sources", ck, "{\"type\":\"stream\",\"title\":\"Стример Петя\"}");
                String t = get(base + "/api/today", ck, null)[1];
                check(t.contains("Стример Вася") && t.contains("\"pos\":1"), t);
                post(base + "/api/schedule/publish", ck, "{}");
                t = get(base + "/api/today", ck, null)[1];
                check(t.contains("\"pos\":2"), t);
                String[] r = get(base + "/media/x/a.mp4", ck, "bytes=1000-1999");
                check(r[0].equals("206") && r[3].equals("1000"), "Range: " + r[0] + " " + r[3]);
                check(get(base + "/", ck, null)[1].contains("<html"), "index.html");
                String[] bad = post2(base + "/api/sources", ck, "{\"type\":\"series\",\"title\":\"Сериал\"}");
                check(bad[0].equals("400"), "в приложении для стримеров сериал не создаётся: " + bad[0]);
            } finally {
                http.stop();
            }
        });

        // ---------------------------------------------------------------- моменты по чату записи
        test("чат: TwitchDownloader JSON — всплески находятся, обычный темп не мешает", () -> {
            StringBuilder j = new StringBuilder("{\"streamer\":{\"name\":\"vasya\"},\"video\":{\"title\":\"Эфир\"},\"comments\":[");
            List<double[]> ts = new ArrayList<>();
            Random rnd = new Random(5);
            for (double t = 0; t < 7200; t += 3 + rnd.nextDouble() * 2) ts.add(new double[]{t, 0});
            for (int i = 0; i < 70; i++) ts.add(new double[]{1800 + i * 0.2, 1}); // KEKW
            for (int i = 0; i < 35; i++) ts.add(new double[]{4000 + i * 0.4, 2}); // ахаха
            ts.sort((a, b) -> Double.compare(a[0], b[0]));
            for (int i = 0; i < ts.size(); i++) {
                double[] x = ts.get(i);
                String msg = x[1] == 1 ? "KEKW KEKW" : x[1] == 2 ? "ахахах \\u0430\\\"" : "привет всем, как дела";
                if (i > 0) j.append(',');
                j.append("{\"_id\":\"").append(i).append("\",\"content_offset_seconds\":").append(String.format(Locale.ROOT, "%.3f", x[0]))
                        .append(",\"commenter\":{\"display_name\":\"Зритель").append(i % 50).append("\",\"name\":\"z\"},")
                        .append("\"message\":{\"body\":\"").append(msg).append("\",\"fragments\":[{\"text\":\"x\",\"emoticon\":null}],\"user_badges\":[]}}");
            }
            j.append("],\"embeddedData\":null}");
            byte[] data = j.toString().getBytes(StandardCharsets.UTF_8);
            ChatMoments.Chat chat = ChatMoments.read(new ByteArrayInputStream(data));
            check(chat.n == ts.size(), "сообщений " + chat.n + " из " + ts.size());
            check(chat.text[0].equals("привет всем, как дела"), "текст: " + chat.text[0]);
            JSONArray c = ChatMoments.find(chat, 7300, 30, 8);
            JSONObject a = c.getJSONObject(0), b = c.getJSONObject(1);
            check(Math.abs(a.getDouble("start") - 1785) <= 3 && a.getJSONArray("reasons").toString().contains("KEKW"), a.toString());
            check(Math.abs(b.getDouble("start") - 3985) <= 6, b.toString());
            check(a.getDouble("score") == 1.0 && a.getDouble("end") - a.getDouble("start") == 30, a.toString());
            // gzip — так же
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bo)) { gz.write(data); }
            check(ChatMoments.read(new ByteArrayInputStream(bo.toByteArray())).n == ts.size(), "gzip");
        });
        test("чат: текстовый лог, повтор чата YouTube, массив с time_in_seconds", () -> {
            String txt = "\uFEFF[0:00:05] vasya: привет\n[0:01:00] petya: ахах\nмусор без времени\n[1:02:03] kolya: KEKW\n";
            ChatMoments.Chat t = ChatMoments.read(new ByteArrayInputStream(txt.getBytes(StandardCharsets.UTF_8)));
            check(t.n == 3 && t.t[2] == 3723 && t.text[1].equals("ахах"), "текст: " + t.n + " " + t.t[2] + " " + t.text[1]);
            String utc = "[2026-10-28 23:59:58 UTC] a: раз\n[2026-10-29 00:00:10 UTC] b: два\n";
            ChatMoments.Chat u = ChatMoments.read(new ByteArrayInputStream(utc.getBytes(StandardCharsets.UTF_8)));
            check(u.n == 2 && u.t[0] == 0 && u.t[1] == 12, "UTC через полночь: " + u.t[1]);
            String clock = "[23:59:50] a: старт\n[23:59:55] b: ахах\n[00:00:20] c: после полуночи\n";
            ChatMoments.Chat ck = ChatMoments.read(new ByteArrayInputStream(clock.getBytes(StandardCharsets.UTF_8)));
            check(ck.n == 3 && ck.t[0] == 0 && ck.t[1] == 5 && ck.t[2] == 30, "лог по часам: " + ck.t[1] + " " + ck.t[2]);
            String yt = "{\"replayChatItemAction\":{\"actions\":[{\"addChatItemAction\":{\"item\":{\"liveChatTextMessageRenderer\":{\"message\":{\"runs\":[{\"text\":\"ору 😂\"}]},"
                    + "\"authorName\":{\"simpleText\":\"Ник\"}}}}}],\"videoOffsetTimeMsec\":\"65500\"}}\n"
                    + "{\"replayChatItemAction\":{\"actions\":[],\"videoOffsetTimeMsec\":\"70000\"}}\n";
            ChatMoments.Chat y = ChatMoments.read(new ByteArrayInputStream(yt.getBytes(StandardCharsets.UTF_8)));
            check(y.n == 2 && Math.abs(y.t[0] - 65.5) < 1e-3 && y.text[0].equals("ору 😂"), "YouTube: " + y.n + " " + y.t[0] + " " + y.text[0]);
            String arr = "[{\"message\":\"гол!!!\",\"author\":{\"name\":\"x\"},\"time_in_seconds\":12.5},{\"time_in_seconds\":-3,\"message\":\"до начала\"}]";
            ChatMoments.Chat ar = ChatMoments.read(new ByteArrayInputStream(arr.getBytes(StandardCharsets.UTF_8)));
            check(ar.n == 1 && ar.t[0] == 12.5f && ar.text[0].equals("гол!!!"), "массив: " + ar.n);
            check(ChatMoments.read(new ByteArrayInputStream("просто текст".getBytes(StandardCharsets.UTF_8))).n == 0, "не чат");
            check(ChatMoments.find(new ChatMoments.Chat(), 100, 30, 8).length() == 0, "пустой чат");
        });
        test("чат: какой файл считается чатом к видео", () -> {
            check(ChatMoments.isChatFor("stream_2026-10-28.json", "stream_2026-10-28"), "json");
            check(ChatMoments.isChatFor("stream_2026-10-28.chat.txt", "stream_2026-10-28"), "chat.txt");
            check(ChatMoments.isChatFor("Stream_2026-10-28_chat.json.gz", "stream_2026-10-28"), "gz, регистр");
            check(!ChatMoments.isChatFor("stream_2026-10-28.srt", "stream_2026-10-28"), "srt — не чат");
            check(!ChatMoments.isChatFor("stream_2026-10-29.json", "stream_2026-10-28"), "чужая дата");
        });

        test("подпись для клипа со стрима", () -> {
            String a = Captions.stream("Стример Вася", "Vasya_Live", "vasya/stream_2026-10-28.mp4");
            check(a.startsWith("Стример Вася · эфир 28.10\n") && a.contains("#стримервася") && a.contains("#vasya_live") && a.contains("#твич"), a);
            check(Captions.stream("", null, "x.mp4").startsWith("Стрим\n"), "без имени");
        });

        test("Twitch: клипы зрителей → моменты в записях эфиров, новый ключ доступа при 401", () -> {
            Store store = new Store(Files.createTempDirectory("tw").toFile());
            store.saveSettings(new JSONObject().put("twitch_client_id", "cid").put("twitch_client_secret", "sec"));
            store.upsertSource(new JSONObject().put("type", "stream").put("title", "Стример").put("twitch_login", "https://www.twitch.tv/Streamer_1"));
            final int[] tokens = {0}, users = {0};
            Discover d = new Discover(store, (url, h, body) -> {
                if (url.startsWith("https://id.twitch.tv/oauth2/token")) {
                    check(body.contains("client_id=cid") && body.contains("grant_type=client_credentials"), body);
                    tokens[0]++;
                    return new Discover.Resp(200, "{\"access_token\":\"T" + tokens[0] + "\",\"expires_in\":3600}");
                }
                check("cid".equals(h.get("Client-Id")), "Client-Id");
                if (url.contains("/users?login=streamer_1")) {
                    if (users[0]++ == 0) return new Discover.Resp(401, "{}"); // ключ доступа устарел
                    check(h.get("Authorization").equals("Bearer T2"), h.get("Authorization"));
                    return new Discover.Resp(200, "{\"data\":[{\"id\":\"42\",\"display_name\":\"Streamer_1\"}]}");
                }
                if (url.contains("/videos?user_id=42")) return new Discover.Resp(200, "{\"data\":["
                        + "{\"id\":\"v1\",\"title\":\"Эфир 1\",\"created_at\":\"2026-10-30T18:00:00Z\",\"duration\":\"3h0m0s\",\"url\":\"u1\",\"view_count\":10},"
                        + "{\"id\":\"v2\",\"title\":\"Эфир 2\",\"created_at\":\"2026-10-28T18:00:00Z\",\"duration\":\"1h2m3s\",\"url\":\"u2\",\"view_count\":5}]}");
                if (url.contains("/clips?broadcaster_id=42")) {
                    check(url.contains("started_at=2026-10-28T18"), url);
                    return new Discover.Resp(200, "{\"data\":["
                            + "{\"video_id\":\"v1\",\"vod_offset\":100,\"duration\":30,\"view_count\":500,\"title\":\"ахах\",\"url\":\"c1\"},"
                            + "{\"video_id\":\"v1\",\"vod_offset\":120,\"duration\":40,\"view_count\":100,\"title\":\"b\",\"url\":\"c2\"},"
                            + "{\"video_id\":\"v1\",\"vod_offset\":3000,\"duration\":20,\"view_count\":2000,\"title\":\"гол\",\"url\":\"c3\"},"
                            + "{\"video_id\":\"v1\",\"vod_offset\":null,\"duration\":20,\"view_count\":9,\"title\":\"x\",\"url\":\"c4\"},"
                            + "{\"video_id\":\"\",\"vod_offset\":null,\"duration\":20,\"view_count\":9,\"title\":\"y\",\"url\":\"c5\"}"
                            + "],\"pagination\":{}}");
                }
                throw new IOException("неожиданный адрес " + url);
            });
            JSONObject r = d.twitch(false);
            check(r.getBoolean("configured") && r.getBoolean("has_sources"), r.toString());
            JSONObject st = r.getJSONArray("streamers").getJSONObject(0);
            check(st.getBoolean("ok") && st.getString("login").equals("streamer_1"), st.toString());
            JSONArray vods = st.getJSONArray("vods");
            JSONObject v1 = vods.getJSONObject(0);
            check(v1.getDouble("duration") == 10800 && vods.getJSONObject(1).getDouble("duration") == 3723, "длительность");
            JSONArray ms = v1.getJSONArray("moments");
            check(ms.length() == 2, ms.toString());
            check(ms.getJSONObject(0).getDouble("t") == 3000 && ms.getJSONObject(0).getString("title").equals("гол"), "лучший момент первым");
            JSONObject m2 = ms.getJSONObject(1);
            check(m2.getDouble("t") == 100 && m2.getInt("clips") == 2 && m2.getLong("views") == 600 && m2.getDouble("end") == 160
                    && m2.getString("title").equals("ахах"), m2.toString());
            check(st.getInt("clips_without_vod") == 2, "клипы без записи");
            check(vods.getJSONObject(1).getJSONArray("moments").length() == 0, "во второй записи моментов нет");
            // привязка файла к записи запоминается; кэш не ходит в сеть повторно
            d.link("tw:v1", "src/efir.mp4");
            JSONObject again = d.twitch(false);
            check(again.getJSONArray("streamers").getJSONObject(0).getJSONArray("vods").getJSONObject(0).getString("file").equals("src/efir.mp4"), "файл");
            check(users[0] == 2 && tokens[0] == 2, "кэш: " + users[0] + " " + tokens[0]);
        });
        test("Twitch: без ключей — подсказка, неверные ключи — понятная ошибка", () -> {
            Store store = new Store(Files.createTempDirectory("tw2").toFile());
            store.upsertSource(new JSONObject().put("type", "stream").put("title", "S").put("twitch_login", "s"));
            Discover d = new Discover(store, (url, h, body) -> new Discover.Resp(400, "{\"message\":\"invalid client\"}"));
            check(!d.twitch(false).getBoolean("configured"), "без ключей");
            store.saveSettings(new JSONObject().put("twitch_client_id", "a").put("twitch_client_secret", "b"));
            JSONObject st = d.twitch(true).getJSONArray("streamers").getJSONObject(0);
            check(!st.getBoolean("ok") && st.getString("error").contains("не принял ключи"), st.toString());
        });
        test("Twitch: моменты для файла записи по дате эфира (для ночной нарезки)", () -> {
            Store store = new Store(Files.createTempDirectory("twf").toFile());
            JSONObject src = new JSONObject().put("id", "v").put("type", "stream").put("title", "Вася").put("twitch_login", "vasya");
            Discover d = new Discover(store, (url, h, body) -> {
                if (url.startsWith("https://id.twitch.tv")) return new Discover.Resp(200, "{\"access_token\":\"T\",\"expires_in\":3600}");
                if (url.contains("/users?")) return new Discover.Resp(200, "{\"data\":[{\"id\":\"1\",\"display_name\":\"Vasya\"}]}");
                if (url.contains("/videos?")) return new Discover.Resp(200, "{\"data\":[{\"id\":\"77\",\"title\":\"Эфир\",\"created_at\":\"2026-10-28T12:00:00Z\",\"duration\":\"2h0m0s\"}]}");
                if (url.contains("/clips?")) return new Discover.Resp(200, "{\"data\":[{\"video_id\":\"77\",\"vod_offset\":600,\"duration\":30,\"view_count\":900,\"title\":\"гол\",\"url\":\"c\"}]}");
                throw new IOException(url);
            });
            check(d.momentsForFile(src, "v/stream_2026-10-28.mp4", "stream_2026-10-28.mp4", 0) == null, "без ключей — null");
            store.saveSettings(new JSONObject().put("twitch_client_id", "a").put("twitch_client_secret", "b"));
            JSONArray m = d.momentsForFile(src, "v/stream_2026-10-28.mp4", "stream_2026-10-28.mp4", 0);
            check(m != null && m.getJSONObject(0).getDouble("t") == 600, "по дате в имени: " + m);
            check(d.momentsForFile(src, "v/stream_2026-10-27.mp4", "stream_2026-10-27.mp4", 0) == null, "другая дата — нет");
            d.link("tw:77", "v/запись без даты.mp4");
            check(d.momentsForFile(src, "v/запись без даты.mp4", "запись без даты.mp4", 0) != null, "по привязке файла");
        });
        // ---------------------------------------------------------------- серии, оценка сцен
        test("серия из имени файла: S01E05, 1x05, «2 сезон 3 серия», «серия 7»", () -> {
            check(Arrays.equals(Captions.episodeOf("Breaking.Bad.S01E05.1080p.mkv"), new int[]{1, 5}), "S01E05");
            check(Arrays.equals(Captions.episodeOf("bb s2 e10.mp4"), new int[]{2, 10}), "s2 e10");
            check(Arrays.equals(Captions.episodeOf("Lost 3x07.avi"), new int[]{3, 7}), "3x07");
            check(Arrays.equals(Captions.episodeOf("Во все тяжкие 2 сезон 3 серия.mp4"), new int[]{2, 3}), "2 сезон 3 серия");
            check(Arrays.equals(Captions.episodeOf("Сезон 4 - Серия 12.mkv"), new int[]{4, 12}), "Сезон 4 - Серия 12");
            check(Arrays.equals(Captions.episodeOf("серия 7.mp4"), new int[]{1, 7}), "серия 7");
            check(Captions.episodeOf("Матрица 1999 1080p.mkv") == null, "фильм — не серия");
        });
        test("баннер: внутри безопасной зоны TikTok, сверху — над видео, снизу — субтитры поднимаются", () -> {
            int[] t = Banner.rect("top", 1.0, 900, 300);
            check(t[0] >= Subs.SAFE_LEFT && t[0] + t[2] <= Subs.SAFE_RIGHT && t[1] >= Subs.SAFE_TOP, "сверху в зоне: " + Arrays.toString(t));
            check(t[1] + t[3] <= Banner.VIDEO_TOP, "сверху не залезает на видео: " + Arrays.toString(t));
            int[] tall = Banner.rect("top", 1.0, 400, 1000);
            check(tall[3] <= Banner.VIDEO_TOP - Subs.SAFE_TOP && tall[2] < 840, "высокая картинка ужата: " + Arrays.toString(tall));
            int[] b = Banner.rect("bottom", 0.6, 600, 200);
            check(b[1] + b[3] == Subs.SAFE_BOTTOM && Math.abs(b[2] - 504) <= 1, "снизу: " + Arrays.toString(b));
            double sc = Banner.subCenter("bottom", b, Subs.subCenterY(Store.defaultProfile(), "single"));
            check(sc + 110 < b[1], "субтитры над баннером: " + sc + " / " + b[1]);
            check(Banner.subCenter("top", t, 1305.6) == 1305.6, "сверху субтитры на месте");
            int[] c = Banner.rect("center", 0.5, 400, 400);
            check(c[1] + c[3] < 1180, "по центру — не до субтитров: " + Arrays.toString(c));
        });
        test("хромакей: зелёный фон прозрачный, остальное не тронуто", () -> {
            int[] px = {0xFF00FF00, 0xFF20D040, 0xFFFF0000, 0xFFFFFFFF, 0xFF406040, 0x8000FF00};
            Chroma.key(px);
            check((px[0] >>> 24) == 0 && (px[1] >>> 24) == 0, "чистый зелёный: " + Integer.toHexString(px[0]) + " " + Integer.toHexString(px[1]));
            check(px[2] == 0xFFFF0000 && px[3] == 0xFFFFFFFF, "красный и белый не тронуты");
            check(px[4] == 0xFF406040, "тёмно-зелёный (не фон) не тронут: " + Integer.toHexString(px[4]));
            check((px[5] >>> 24) == 0, "полупрозрачный зелёный");
        });
        test("подпись для TikTok из имени файла", () -> {
            String a = Captions.make("_picked/Breaking.Bad.S01E05.1080p.WEB-DL.mkv");
            check(a.startsWith("Breaking Bad · 1 сезон, 5 серия\n") && a.contains("#breakingbad") && a.contains("#сериал"), a);
            String b = Captions.make("Брат (1997) BDRip.avi");
            check(b.startsWith("Брат\n") && b.contains("#брат") && b.contains("#фильм"), b);
            String c = Captions.make("Кухня 2 сезон 3 серия.mp4");
            check(c.startsWith("Кухня · 2 сезон, 3 серия"), c);
            check(Captions.make("1080p.mkv").startsWith("Момент"), "пустое название");
        });
        test("оценка сцен: эмоции в репликах поднимают момент при ровном звуке", () -> {
            double[] lv = new double[1200];
            Arrays.fill(lv, -30);
            List<Subs.Line> cues = new ArrayList<>();
            for (int t = 5; t < 1190; t += 6) {
                boolean hot = t >= 600 && t < 625;
                Subs.Line ln = new Subs.Line();
                ln.s = t;
                ln.e = t + 4;
                ln.text = hot ? (t % 12 == 0 ? "Что?! Нет!!!" : "ХА-ХА! СТОЙ!") : "Мы поедем завтра утром на работу";
                cues.add(ln);
            }
            JSONArray c = Moments.find(lv, 30, cues, 4);
            JSONObject top = c.getJSONObject(0);
            check(top.getDouble("start") >= 575 && top.getDouble("start") <= 625 && top.getJSONArray("reasons").toString().contains("эмоции"), top.toString());
            double[] cuts = new double[1200];
            for (int i = 0; i < 1200; i += 4) cuts[i] = 1;
            for (int i = 200; i < 230; i++) cuts[i] = 2; // быстрый монтаж
            JSONArray c2 = Moments.find(lv, 30, null, cuts, 3);
            check(c2.getJSONObject(0).getJSONArray("reasons").toString().contains("быстрый монтаж")
                    && Math.abs(c2.getJSONObject(0).getDouble("start") - 200) <= 30, c2.toString());
        });
        // ---------------------------------------------------------------- автоподбор рамок вебкамеры и экрана
        test("рамка вебкамеры в углу: по неподвижным краям, без лица", () -> {
            int[][] fr = streamFrames(320, 180, new int[]{224, 106, 312, 172}, 0, null);
            Layout.Result r = Layout.detect(fr, 320, 180, new ArrayList<float[]>(), 1920, 1080);
            check("corner".equals(r.how), "как найдена: " + r.how);
            near(r.cam, new float[]{224 / 320f, 106 / 180f, 88 / 320f, 66 / 180f}, 0.015f, "камера");
            // экран: пропорция верха клипа, вебкамеру не захватывает
            float aspect = r.screen[2] * 1920 / (r.screen[3] * 1080);
            check(Math.abs(aspect - Layout.TOP_ASPECT) < 0.02, "пропорция экрана " + aspect);
            check(r.screen[0] + r.screen[2] <= r.cam[0] + 0.01f, "экран залез на камеру: " + Arrays.toString(r.screen));
        });
        test("рамка вокруг лица: камера посреди края, лицо найдено Android", () -> {
            int[] cam = {120, 100, 216, 172};
            int[][] fr = streamFrames(320, 180, cam, 0, null);
            List<float[]> faces = new ArrayList<>();
            for (int k = 0; k < 4; k++) faces.add(new float[]{(156 + k % 2) / 320f, 112 / 180f, 24 / 320f, 28 / 180f});
            Layout.Result r = Layout.detect(fr, 320, 180, faces, 1920, 1080);
            check("frame".equals(r.how), "как найдена: " + r.how + " " + Arrays.toString(r.cam));
            near(r.cam, new float[]{120 / 320f, 100 / 180f, 96 / 320f, 72 / 180f}, 0.015f, "камера");
            check(r.toJson().getString("layout").equals("split"), "раскладка");
        });
        test("лицо без рамки → прямоугольник вокруг лица; крупное лицо → камера на весь кадр", () -> {
            int[][] fr = streamFrames(320, 180, null, 0, null);
            List<float[]> small = new ArrayList<>();
            small.add(new float[]{0.8f, 0.6f, 0.06f, 0.1f});
            Layout.Result r = Layout.detect(fr, 320, 180, small, 1920, 1080);
            check("face".equals(r.how) && r.cam != null, "маленькое лицо: " + r.how);
            check(r.cam[0] <= 0.8f && r.cam[0] + r.cam[2] >= 0.86f, "лицо внутри рамки " + Arrays.toString(r.cam));
            List<float[]> big = new ArrayList<>();
            big.add(new float[]{0.35f, 0.2f, 0.3f, 0.45f});
            Layout.Result f = Layout.detect(fr, 320, 180, big, 1920, 1080);
            check("fullcam".equals(f.how) && f.cam == null, "крупное лицо: " + f.how);
            check(f.toJson().getString("layout").equals("single"), "один кадр");
        });
        test("без вебкамеры и неподвижная плашка интерфейса — не камера; чёрные поля убираются из экрана", () -> {
            int[][] fr = streamFrames(320, 180, null, 20, new int[]{230, 100, 315, 150});
            Layout.Result r = Layout.detect(fr, 320, 180, new ArrayList<float[]>(), 1920, 1080);
            check(r.cam == null && "none".equals(r.how), "нашлась несуществующая камера: " + r.how + " " + Arrays.toString(r.cam));
            near(r.screen, new float[]{0, 20 / 180f, 1, 140 / 180f}, 0.012f, "экран без полей");
        });
        test("камера в углу при чёрных полях сверху и снизу", () -> {
            int[][] fr = streamFrames(320, 180, new int[]{8, 26, 100, 90}, 20, null);
            Layout.Result r = Layout.detect(fr, 320, 180, new ArrayList<float[]>(), 1920, 1080);
            check("corner".equals(r.how), "как найдена: " + r.how);
            near(r.cam, new float[]{8 / 320f, 26 / 180f, 92 / 320f, 64 / 180f}, 0.02f, "камера");
            check(r.screen[1] >= 20 / 180f - 0.01f && r.screen[0] >= r.cam[0] + r.cam[2] - 0.01f, "экран: " + Arrays.toString(r.screen));
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

    /** POST: [код, тело] (без исключения на ошибку) */
    static String[] post2(String url, String cookie, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Cookie", encodeCookie(cookie));
        c.setRequestProperty("Content-Type", "application/json");
        c.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        return new String[]{String.valueOf(code), new String(Store.readAll(in), StandardCharsets.UTF_8)};
    }

    /** PUT: [код, тело] */
    static String[] put(String url, String cookie, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
        c.setRequestMethod("PUT");
        c.setDoOutput(true);
        c.setRequestProperty("Cookie", encodeCookie(cookie));
        c.setRequestProperty("Content-Type", "application/json");
        c.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        return new String[]{String.valueOf(code), new String(Store.readAll(in), StandardCharsets.UTF_8)};
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
        public JSONObject detectLayout(String id) { return new JSONObject(); }
        public MiniHttp.RangeSource openClip(JSONObject m) { return null; }
        public boolean clipExists(JSONObject m) { return false; }
        public void deleteClip(JSONObject m) { }
        public byte[] asset(String p) { return p.equals("index.html") ? "<html>ok</html>".getBytes() : null; }
    }
    /** Кадры «эфира»: шумная движущаяся игра, по желанию — вебкамера с белой рамкой, чёрные поля, неподвижная плашка. */
    static int[][] streamFrames(int w, int h, int[] cam, int bars, int[] hud) {
        Random rnd = new Random(7);
        int[][] out = new int[8][];
        for (int k = 0; k < out.length; k++) {
            float[] f = new float[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    float v = 60 + 50 * (float) Math.sin((x + k * 23) * 0.11) * (float) Math.cos((y - k * 17) * 0.07) + rnd.nextInt(60);
                    if (y < bars || y >= h - bars) v = 4;
                    f[y * w + x] = v;
                }
            }
            if (hud != null) box(f, w, hud, 30, 230, false, k, rnd);
            if (cam != null) box(f, w, cam, 0, 255, true, k, rnd);
            int[] px = new int[f.length];
            for (int i = 0; i < f.length; i++) {
                int v = Math.max(0, Math.min(255, Math.round(f[i])));
                px[i] = v << 16 | v << 8 | v;
            }
            out[k] = px;
        }
        return out;
    }

    /** Прямоугольник [x0,y0,x1,y1) с рамкой в 2 пикселя вокруг (снаружи); внутри — фон и, если moving, «человек», который двигается. */
    static void box(float[] f, int w, int[] b, float fill, float border, boolean moving, int k, Random rnd) {
        for (int y = b[1] - 2; y < b[3] + 2; y++)
            for (int x = b[0] - 2; x < b[2] + 2; x++) {
                if (x < 0 || y < 0 || x >= w || y * w + x >= f.length) continue;
                boolean in = x >= b[0] && x < b[2] && y >= b[1] && y < b[3];
                float v = in ? fill + 20 : border;
                if (in && moving) {
                    int cx = (b[0] + b[2]) / 2 + (k % 3 - 1) * 6, cy = (b[1] + b[3]) / 2 + (k % 2) * 4;
                    if (Math.hypot(x - cx, (y - cy) * 0.8) < (b[3] - b[1]) / 4.0) v = 180 + rnd.nextInt(20);
                    else v = 40 + rnd.nextInt(8);
                }
                f[y * w + x] = v;
            }
    }

    static void near(float[] a, float[] b, float tol, String what) {
        check(a != null, what + ": не найдено");
        for (int i = 0; i < 4; i++)
            check(Math.abs(a[i] - b[i]) <= tol, what + ": " + Arrays.toString(a) + " ≠ " + Arrays.toString(b));
    }

}
