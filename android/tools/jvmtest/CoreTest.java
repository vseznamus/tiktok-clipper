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
                store.saveSettings(new JSONObject().put("tmdb_key", "SuperSecret123456"));
                String cfg = get(base + "/api/config", ck, null)[1];
                check(!cfg.contains("SuperSecret123456") && cfg.contains("\"tmdb_key_set\":true") && cfg.contains("…3456"), "ключ не отдаётся целиком");
                String[] bad = put(base + "/api/settings", ck, "{\"tmdb_key\":\"ключ с пробелами\"}");
                check(bad[0].equals("400"), "кривой ключ отклоняется: " + bad[0]);
            } finally {
                http.stop();
            }
        });

        // ---------------------------------------------------------------- TMDB, оценка сцен, автоплан
        test("серия из имени файла: S01E05, 1x05, «2 сезон 3 серия», «серия 7»", () -> {
            check(Arrays.equals(Tmdb.episodeOf("Breaking.Bad.S01E05.1080p.mkv"), new int[]{1, 5}), "S01E05");
            check(Arrays.equals(Tmdb.episodeOf("bb s2 e10.mp4"), new int[]{2, 10}), "s2 e10");
            check(Arrays.equals(Tmdb.episodeOf("Lost 3x07.avi"), new int[]{3, 7}), "3x07");
            check(Arrays.equals(Tmdb.episodeOf("Во все тяжкие 2 сезон 3 серия.mp4"), new int[]{2, 3}), "2 сезон 3 серия");
            check(Arrays.equals(Tmdb.episodeOf("Сезон 4 - Серия 12.mkv"), new int[]{4, 12}), "Сезон 4 - Серия 12");
            check(Arrays.equals(Tmdb.episodeOf("серия 7.mp4"), new int[]{1, 7}), "серия 7");
            check(Tmdb.episodeOf("Матрица 1999 1080p.mkv") == null, "фильм — не серия");
        });
        final Net.Http tmdbNet = (url, h, body) -> {
            check(url.startsWith("https://api.themoviedb.org/3/") && url.contains("api_key=k123"), url);
            if (url.contains("/trending/all/week")) return new Net.Resp(200, "{\"results\":[{\"id\":1396,\"media_type\":\"tv\",\"name\":\"Во все тяжкие\",\"original_name\":\"Breaking Bad\",\"first_air_date\":\"2008-01-20\",\"vote_average\":8.9,\"vote_count\":15000},{\"id\":5,\"media_type\":\"person\",\"name\":\"x\"},{\"id\":603,\"media_type\":\"movie\",\"title\":\"Матрица\",\"original_title\":\"The Matrix\",\"release_date\":\"1999-03-31\",\"vote_average\":8.2,\"vote_count\":26000}]}");
            if (url.contains("/tv/top_rated")) return new Net.Resp(200, "{\"results\":[{\"id\":1396,\"name\":\"Во все тяжкие\",\"original_name\":\"Breaking Bad\",\"vote_average\":8.9}]}");
            if (url.contains("/movie/top_rated")) return new Net.Resp(200, "{\"results\":[{\"id\":238,\"title\":\"Крёстный отец\",\"vote_average\":8.7}]}");
            if (url.contains("/search/multi")) {
                if (url.contains("%D0%94%D0%BE%D0%BA%D1%82%D0%BE%D1%80")) // «Доктор» — несколько вариантов
                    return new Net.Resp(200, "{\"results\":[{\"id\":1,\"media_type\":\"tv\",\"name\":\"Доктор Хаус\"},{\"id\":2,\"media_type\":\"tv\",\"name\":\"Доктор Кто\"}]}");
                return new Net.Resp(200, "{\"results\":[{\"id\":1396,\"media_type\":\"tv\",\"name\":\"Во все тяжкие\",\"original_name\":\"Breaking Bad\"}]}");
            }
            if (url.contains("/tv/1396/season/1")) return new Net.Resp(200, "{\"episodes\":[{\"episode_number\":1,\"name\":\"Пилот\",\"vote_average\":7.5,\"vote_count\":50},"
                    + "{\"episode_number\":2,\"name\":\"Кот в мешке\",\"vote_average\":9.0,\"vote_count\":50},{\"episode_number\":3,\"name\":\"…и мешок в реке\",\"vote_average\":8.0,\"vote_count\":50},"
                    + "{\"episode_number\":4,\"name\":\"Нет файла\",\"vote_average\":9.9,\"vote_count\":90}]}");
            if (url.contains("/tv/1396/season/2")) return new Net.Resp(200, "{\"episodes\":[{\"episode_number\":1,\"name\":\"Семь тридцать семь\",\"vote_average\":9.5,\"vote_count\":60}]}");
            if (url.contains("/tv/1396")) return new Net.Resp(200, "{\"name\":\"Во все тяжкие\",\"first_air_date\":\"2008-01-20\",\"vote_average\":8.9,\"vote_count\":15000,"
                    + "\"seasons\":[{\"season_number\":0},{\"season_number\":1},{\"season_number\":2}]}");
            return new Net.Resp(404, "{}");
        };
        test("TMDB: тренды с пометкой «есть у вас», автопривязка сериала, серии по рейтингу с файлами", () -> {
            Store store = new Store(Files.createTempDirectory("tmdb").toFile());
            store.saveSettings(new JSONObject().put("tmdb_key", "k123"));
            store.upsertSource(new JSONObject().put("id", "bb").put("type", "series").put("title", "Во все тяжкие"));
            store.upsertSource(new JSONObject().put("id", "doc").put("type", "series").put("title", "Доктор"));
            Tmdb t = new Tmdb(store, tmdbNet);
            JSONObject l = t.lists(false);
            JSONArray tr = l.getJSONArray("trending");
            check(tr.length() == 2 && tr.getJSONObject(0).getString("source_id").equals("bb") && !tr.getJSONObject(1).has("source_id"), tr.toString());
            check(tr.getJSONObject(1).getString("year").equals("1999") && tr.getJSONObject(1).getString("type").equals("movie"), "фильм");
            JSONArray files = new JSONArray();
            for (String n : new String[]{"Breaking.Bad.S01E01.mkv", "Breaking.Bad.S01E02.mkv", "Breaking.Bad.S01E03.mkv", "Breaking.Bad.S02E01.mkv"})
                files.put(new JSONObject().put("name", n).put("path", "bb/" + n));
            JSONObject info = t.forSource(store.getSource("bb"), files, false);
            check(info.getJSONObject("match").getLong("id") == 1396 && store.getSource("bb").optLong("tmdb_id") == 1396, "автопривязка");
            JSONArray eps = info.getJSONArray("episodes");
            check(eps.getJSONObject(0).getString("file").equals("bb/Breaking.Bad.S02E01.mkv"), "лучшая серия с файлом первой: " + eps.getJSONObject(0));
            check(eps.getJSONObject(1).getString("file").endsWith("S01E02.mkv"), "вторая");
            check(!eps.getJSONObject(4).has("file") && eps.getJSONObject(4).getString("name").equals("Нет файла"), "серии без файла — в конце");
            JSONObject doc = t.forSource(store.getSource("doc"), new JSONArray(), false);
            check(doc.isNull("match") && doc.getJSONArray("candidates").length() == 2, "несколько вариантов — спросить: " + doc);
            Tmdb bad = new Tmdb(store, (url, h, b) -> new Net.Resp(401, "{}"));
            try {
                bad.lists(true);
                check(false, "ожидалась ошибка ключа");
            } catch (ApiError e) {
                check(e.getMessage().contains("не принял ключ"), e.getMessage());
            }
        });
        test("TMDB «Русское»: язык оригинала ru, лучшие — с порогом голосов, новинки — за год, пометка «есть у вас»", () -> {
            Store store = new Store(Files.createTempDirectory("ru").toFile());
            store.upsertSource(new JSONObject().put("id", "kuhnya").put("type", "series").put("title", "Кухня").put("tmdb_id", 63770).put("tmdb_type", "tv"));
            final List<String> urls = new ArrayList<>();
            Tmdb t = new Tmdb(store, (url, h, b) -> {
                urls.add(url);
                return new Net.Resp(200, "{\"page\":1,\"total_pages\":3,\"results\":[{\"id\":63770,\"name\":\"Кухня\",\"first_air_date\":\"2012-11-12\",\"vote_average\":7.9,\"vote_count\":300},"
                        + "{\"id\":7,\"name\":\"Другой сериал\",\"vote_average\":7.1,\"vote_count\":80}]}");
            });
            check(!t.russian("tv", "popular", 1, false).getBoolean("configured"), "без ключа");
            store.saveSettings(new JSONObject().put("tmdb_key", "k123"));
            JSONObject pop = t.russian("tv", "popular", 1, false);
            JSONArray it = pop.getJSONArray("items");
            check(it.length() == 2 && it.getJSONObject(0).getString("source_id").equals("kuhnya") && !it.getJSONObject(1).has("source_id"), it.toString());
            check(pop.getInt("total_pages") == 3, "страницы");
            String u0 = urls.get(0);
            check(u0.contains("discover/tv?") && u0.contains("with_original_language=ru") && u0.contains("sort_by=popularity.desc") && u0.contains("language=ru-RU"), u0);
            t.russian("movie", "top", 2, false);
            String u1 = urls.get(1);
            check(u1.contains("discover/movie?") && u1.contains("sort_by=vote_average.desc") && u1.contains("vote_count.gte=100") && u1.contains("page=2"), u1);
            t.russian("tv", "new", 1, false);
            check(urls.get(2).contains("first_air_date.gte=" + LocalDate.now().minusYears(1)), urls.get(2));
            t.russian("tv", "popular", 1, false);
            check(urls.size() == 3, "кэш списка");
        });
        test("IMDb: разбор title.ratings.tsv.gz, порог голосов, поиск по tt", () -> {
            Store store = new Store(Files.createTempDirectory("imdb").toFile());
            ImdbRatings im = new ImdbRatings(store);
            check(!im.loaded() && im.ageDays() == -1 && im.get("tt0903747") == null, "пусто");
            StringBuilder tsv = new StringBuilder("tconst\taverageRating\tnumVotes\n");
            for (int i = 1; i <= 2000; i++) tsv.append(String.format(Locale.ROOT, "tt%07d\t%.1f\t%d\n", i, 5 + (i % 50) / 10.0, i * 3));
            tsv.append("tt0903747\t9.5\t2300000\nмусор\nttX\t1\t1\n");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bo)) { gz.write(tsv.toString().getBytes(StandardCharsets.UTF_8)); }
            int n = im.update(new ByteArrayInputStream(bo.toByteArray()), null, null);
            check(n == 2000 - 166 + 1, "оставлено " + n); // голосов ≥ 500: id ≥ 167
            double[] bb = im.get("tt0903747");
            check(bb != null && bb[0] == 9.5 && bb[1] == 2300000, "рейтинг");
            check(im.get("tt0000100") == null, "мало голосов — не хранится");
            check(new ImdbRatings(store).get("tt0001000") != null && new ImdbRatings(store).ageDays() == 0, "с диска");
            try {
                im.update(new ByteArrayInputStream("не gzip".getBytes(StandardCharsets.UTF_8)), null, null);
                check(false, "не gzip должен отклоняться");
            } catch (java.util.zip.ZipException e) {
                check(im.get("tt0903747") != null, "старые данные целы");
            }
        });
        test("Подборка: мир — тренды и лучшие, Россия — язык ru; «Лучшее» сортируется по IMDb", () -> {
            Store store = new Store(Files.createTempDirectory("pick").toFile());
            store.saveSettings(new JSONObject().put("tmdb_key", "k123"));
            ImdbRatings im = new ImdbRatings(store);
            String tsv = "tconst\taverageRating\tnumVotes\ntt0000001\t9.1\t9000\ntt0000002\t7.0\t9000\n";
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bo)) { gz.write(tsv.getBytes(StandardCharsets.UTF_8)); }
            im.update(new ByteArrayInputStream(bo.toByteArray()), null, null);
            final List<String> urls = new ArrayList<>();
            Tmdb t = new Tmdb(store, (url, h, b) -> {
                urls.add(url);
                if (url.contains("/external_ids")) {
                    String id = url.contains("/tv/1/") ? "tt0000002" : url.contains("/tv/2/") ? "tt0000001" : "";
                    return new Net.Resp(200, "{\"imdb_id\":" + (id.isEmpty() ? "null" : "\"" + id + "\"") + "}");
                }
                return new Net.Resp(200, "{\"page\":1,\"total_pages\":40,\"results\":[{\"id\":1,\"name\":\"Первый\",\"vote_average\":9.0},"
                        + "{\"id\":2,\"name\":\"Второй\",\"vote_average\":8.0},{\"id\":3,\"name\":\"Третий\",\"vote_average\":8.5}]}");
            });
            t.imdb = im;
            JSONObject best = t.pick("world", "tv", "best", 1, false);
            JSONArray it = best.getJSONArray("items");
            check(urls.get(0).contains("/tv/top_rated?"), urls.get(0));
            check(it.getJSONObject(0).getString("title").equals("Второй") && it.getJSONObject(0).getDouble("imdb_rating") == 9.1, "по IMDb: " + it);
            check(it.getJSONObject(1).getString("title").equals("Первый") && it.getJSONObject(2).getString("title").equals("Третий") && !it.getJSONObject(2).has("imdb_id"), it.toString());
            check(best.getInt("total_pages") == 20 && best.getBoolean("imdb_loaded"), "страницы ограничены 20");
            int before = urls.size();
            t.pick("world", "tv", "popular", 1, false);
            check(urls.get(before).contains("/trending/tv/week?"), urls.get(before));
            check(urls.size() == before + 1, "imdb_id берутся из кэша на телефоне, без повторных запросов");
            t.pick("ru", "movie", "popular", 2, false);
            String ru = urls.get(urls.size() - 4);
            check(ru.contains("discover/movie?") && ru.contains("with_original_language=ru") && ru.contains("page=2"), ru);
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
        test("автоплан: 3 лучших серии по TMDB на 3 дня, клипы в очередь; фильм — 3 момента подальше", () -> {
            Store store = new Store(Files.createTempDirectory("plan").toFile());
            store.saveSettings(new JSONObject().put("tmdb_key", "k123"));
            store.upsertSource(new JSONObject().put("id", "bb").put("type", "series").put("title", "Во все тяжкие"));
            store.upsertSource(new JSONObject().put("id", "mx").put("type", "movie").put("title", "Матрица"));
            JSONObject st = store.loadState();
            st.getJSONObject("used_files").put("bb", new JSONArray().put("bb/Breaking.Bad.S02E01.mkv"));
            store.saveState(st);
            Tmdb tm = new Tmdb(store, tmdbNet);
            Platform pf = new FakePlatform(new byte[10]) {
                public JSONArray listFiles(JSONObject src) {
                    JSONArray a = new JSONArray();
                    try {
                        String[] names = src.optString("id").equals("bb")
                                ? new String[]{"Breaking.Bad.S01E01.mkv", "Breaking.Bad.S01E02.mkv", "Breaking.Bad.S01E03.mkv", "Breaking.Bad.S02E01.mkv"}
                                : new String[]{"Matrix.1999.mkv"};
                        for (String n : names) a.put(new JSONObject().put("name", n).put("path", src.optString("id") + "/" + n).put("mtime", 1));
                    } catch (Exception e) { throw new RuntimeException(e); }
                    return a;
                }
                public JSONObject probe(String id) {
                    try { return new JSONObject().put("has_srt", id.contains("S01E03")); } catch (Exception e) { throw new RuntimeException(e); }
                }
            };
            final List<String> analyzed = new ArrayList<>();
            Planner.Analyzer an = (ctx, file, len, lo, hi) -> {
                analyzed.add(file);
                JSONArray c = new JSONArray();
                for (double s0 : new double[]{100, 110, 400, 900}) c.put(new JSONObject().put("start", s0).put("end", s0 + len).put("score", 0.5).put("reasons", new JSONArray().put("громко")));
                return new JSONObject().put("candidates", c);
            };
            File cf = File.createTempFile("clips", ".json");
            cf.delete();
            Map<String, JobQueue.Handler> ch = new HashMap<>();
            ch.put("clip", ctx -> new JSONObject());
            JobQueue clips = new JobQueue(cf, ch); // не запущена: только принимает задачи
            File pfj = File.createTempFile("pjobs", ".json");
            pfj.delete();
            Map<String, JobQueue.Handler> h = new HashMap<>();
            h.put("plan", ctx -> Planner.run(ctx, store, pf, tm, an, clips, D0));
            JobQueue q = new JobQueue(pfj, h);
            JSONObject j1 = q.submit("plan", new JSONObject().put("source_id", "bb").put("count", 3), "план");
            q.start();
            check(q.waitIdle(5000), "план не выполнился");
            JSONObject done = q.get(j1.getString("id"));
            check(done.getString("status").equals("done"), done.toString());
            JSONArray items = Planner.load(store);
            check(items.length() == 3, "пунктов " + items.length());
            String[] want = {"bb/Breaking.Bad.S01E02.mkv", "bb/Breaking.Bad.S01E03.mkv", "bb/Breaking.Bad.S01E01.mkv"};
            for (int i = 0; i < 3; i++) {
                JSONObject it = items.getJSONObject(i);
                check(it.getString("date").equals(D0.plusDays(i).toString()), "дата " + it.getString("date"));
                check(it.getString("file").equals(want[i]), i + ": " + it.getString("file"));
                check(it.getDouble("start") == 100 && it.getDouble("end") == 130, "лучший момент серии");
                JSONObject cj = clips.get(it.getString("job_id"));
                check(cj != null && cj.getString("type").equals("clip"), "клип в очереди");
            }
            check(items.getJSONObject(0).getString("why").contains("рейтинг 9,0"), items.getJSONObject(0).getString("why"));
            check(clips.get(items.getJSONObject(1).getString("job_id")).getJSONObject("params").getString("sub_source").equals("srt"), "субтитры из .srt");
            // фильм: 3 момента одного файла, далеко друг от друга
            q.submit("plan", new JSONObject().put("source_id", "mx").put("count", 3).put("start", D0.plusDays(3).toString()).put("make_clips", false), "фильм");
            check(q.waitIdle(5000), "фильм");
            JSONArray all = Planner.load(store);
            List<Double> starts = new ArrayList<>();
            for (int i = 0; i < all.length(); i++) if (all.getJSONObject(i).getString("source_id").equals("mx")) {
                starts.add(all.getJSONObject(i).getDouble("start"));
                check(!all.getJSONObject(i).has("job_id"), "без клипов");
            }
            check(starts.equals(Arrays.asList(100.0, 400.0, 900.0)), "моменты фильма: " + starts);
            // повторный план того же источника заменяет неопубликованные пункты, а не добавляет
            q.submit("plan", new JSONObject().put("source_id", "bb").put("count", 3).put("make_clips", false), "ещё раз");
            check(q.waitIdle(5000), "повтор");
            int bb = 0;
            for (int i = 0; i < Planner.load(store).length(); i++) if (Planner.load(store).getJSONObject(i).getString("source_id").equals("bb")) bb++;
            check(bb == 3, "дубли в плане: " + bb);
            q.stop();
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
        public MiniHttp.RangeSource openClip(JSONObject m) { return null; }
        public boolean clipExists(JSONObject m) { return false; }
        public void deleteClip(JSONObject m) { }
        public byte[] asset(String p) { return p.equals("index.html") ? "<html>ok</html>".getBytes() : null; }
    }
}
