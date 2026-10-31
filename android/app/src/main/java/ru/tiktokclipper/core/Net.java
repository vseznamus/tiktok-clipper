package ru.tiktokclipper.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Простые HTTP-запросы к внешним API (TMDB). В тестах подменяется имитацией. */
public final class Net {
    private Net() {
    }

    /** Ответ HTTP: код и тело. */
    public static final class Resp {
        public final int code;
        public final String body;

        public Resp(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    public interface Http {
        /** body == null — GET, иначе POST (application/x-www-form-urlencoded). */
        Resp request(String url, Map<String, String> headers, String body) throws IOException;
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static Resp realRequest(String url, Map<String, String> headers, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        if (body != null) {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) {
            try (InputStream i = in) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = i.read(buf)) > 0) bo.write(buf, 0, n);
            }
        }
        return new Resp(code, new String(bo.toByteArray(), StandardCharsets.UTF_8));
    }
}
