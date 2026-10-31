package ru.tiktokclipper.core;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

/**
 * Рейтинги IMDb из официального открытого набора данных IMDb (datasets.imdbws.com, title.ratings.tsv.gz,
 * для личного некоммерческого использования). Ключ не нужен. Файл ~8 МБ скачивается раз в неделю;
 * на телефоне хранятся только названия, у которых от 500 голосов: id, рейтинг, голоса (~2 МБ).
 */
public final class ImdbRatings {
    public static final String URL_RATINGS = "https://datasets.imdbws.com/title.ratings.tsv.gz";
    static final int MIN_VOTES = 500;

    public interface Source {
        InputStream open(String url) throws IOException;
    }

    private final File file;
    private int[] ids;
    private short[] r10;
    private int[] votes;

    public ImdbRatings(Store store) {
        this.file = new File(store.dir, "imdb_ratings.bin");
    }

    /** Загружен ли набор и сколько ему дней (-1 — нет). */
    public synchronized long ageDays() {
        return file.isFile() ? (System.currentTimeMillis() - file.lastModified()) / 86_400_000L : -1;
    }

    public synchronized boolean loaded() {
        return file.isFile();
    }

    /** {рейтинг, голоса} по «tt0903747» или null. */
    public synchronized double[] get(String tt) {
        if (tt == null || !tt.startsWith("tt")) return null;
        int id;
        try {
            id = Integer.parseInt(tt.substring(2));
        } catch (NumberFormatException e) {
            return null;
        }
        if (!ensure()) return null;
        int i = Arrays.binarySearch(ids, id);
        return i < 0 ? null : new double[]{r10[i] / 10.0, votes[i]};
    }

    private boolean ensure() {
        if (ids != null) return true;
        if (!file.isFile()) return false;
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new FileInputStream(file), 1 << 16))) {
            int n = in.readInt();
            int[] a = new int[n], v = new int[n];
            short[] r = new short[n];
            for (int i = 0; i < n; i++) {
                a[i] = in.readInt();
                r[i] = in.readShort();
                v[i] = in.readInt();
            }
            ids = a;
            r10 = r;
            votes = v;
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Разбирает title.ratings.tsv.gz потоком и сохраняет компактный файл. */
    public synchronized int update(InputStream gz, JobQueue.Progress p, java.util.concurrent.atomic.AtomicBoolean cancel) throws Exception {
        int cap = 1 << 18, n = 0;
        int[] a = new int[cap], v = new int[cap];
        short[] r = new short[cap];
        long lines = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new GZIPInputStream(gz, 1 << 16), StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = br.readLine()) != null) {
                if ((++lines & 0xFFFF) == 0) {
                    if (cancel != null && cancel.get()) throw new JobQueue.Cancelled();
                    if (p != null) p.set(Math.min(0.95, lines / 1_600_000.0));
                }
                int t1 = line.indexOf('\t'), t2 = line.indexOf('\t', t1 + 1);
                if (t1 < 3 || t2 < 0 || !line.startsWith("tt")) continue; // заголовок и мусор
                int vs;
                try {
                    vs = Integer.parseInt(line.substring(t2 + 1).trim());
                    if (vs < MIN_VOTES) continue;
                    if (n == cap) {
                        cap *= 2;
                        a = Arrays.copyOf(a, cap);
                        v = Arrays.copyOf(v, cap);
                        r = Arrays.copyOf(r, cap);
                    }
                    a[n] = Integer.parseInt(line.substring(2, t1));
                    r[n] = (short) Math.round(Double.parseDouble(line.substring(t1 + 1, t2)) * 10);
                    v[n] = vs;
                    n++;
                } catch (NumberFormatException ignored) {
                    // повреждённая строка
                }
            }
        }
        if (n == 0) throw new ApiError(502, "Файл рейтингов IMDb оказался пустым — попробуйте позже");
        // сортировка по id (в файле и так по порядку, но не полагаемся)
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        final int[] fa = a;
        Arrays.sort(idx, (x, y) -> Integer.compare(fa[x], fa[y]));
        File tmp = new File(file.getPath() + ".part");
        file.getParentFile().mkdirs();
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(tmp), 1 << 16))) {
            out.writeInt(n);
            for (int k = 0; k < n; k++) {
                int i = idx[k];
                out.writeInt(a[i]);
                out.writeShort(r[i]);
                out.writeInt(v[i]);
            }
        }
        file.delete();
        if (!tmp.renameTo(file)) throw new IOException("Не удалось сохранить рейтинги IMDb");
        ids = null; // перечитать при следующем запросе
        if (p != null) p.set(1);
        return n;
    }

    /** Задача очереди «imdb»: скачать и обновить. */
    public org.json.JSONObject job(JobQueue.Ctx ctx, Source src) throws Exception {
        ctx.progress(0.01, "Загружаю рейтинги IMDb (около 8 МБ)");
        InputStream in;
        try {
            in = src != null ? src.open(URL_RATINGS) : realOpen(URL_RATINGS);
        } catch (IOException e) {
            throw new ApiError(502, "IMDb недоступен: нет интернета или сайт не открывается из вашей сети.");
        }
        int n;
        try (InputStream i = in) {
            n = update(i, ctx.stage(0.02, 0.99, "Загружаю рейтинги IMDb"), ctx.cancel);
        } catch (java.util.zip.ZipException e) {
            throw new ApiError(502, "IMDb вернул не тот файл — попробуйте позже.");
        }
        return new org.json.JSONObject().put("titles", n);
    }

    static InputStream realOpen(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("код " + code);
        return c.getInputStream();
    }
}
