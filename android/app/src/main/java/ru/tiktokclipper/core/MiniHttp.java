package ru.tiktokclipper.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Маленький HTTP/1.1-сервер только на 127.0.0.1: keep-alive, Range для видео, JSON. */
public class MiniHttp {
    public interface Handler {
        Response handle(Request r) throws Exception;
    }

    /** Источник данных с произвольным доступом — для отдачи видео кусками (Range). */
    public interface RangeSource {
        long size();

        InputStream open(long offset) throws IOException;
    }

    public static class Request {
        public String method, path, rawQuery = "";
        public final Map<String, String> query = new HashMap<>();
        public final Map<String, String> headers = new HashMap<>();
        public byte[] body = new byte[0];

        public String header(String k) {
            return headers.get(k.toLowerCase(Locale.ROOT));
        }

        public String q(String k, String def) {
            String v = query.get(k);
            return v == null ? def : v;
        }

        public String cookie(String name) {
            String c = header("cookie");
            if (c == null) return null;
            for (String part : c.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(name)) return kv[1];
            }
            return null;
        }

        public JSONObject json() {
            try {
                String s = new String(body, StandardCharsets.UTF_8).trim();
                return s.isEmpty() ? new JSONObject() : new JSONObject(s);
            } catch (JSONException e) {
                return new JSONObject();
            }
        }
    }

    public static class Response {
        public int status = 200;
        public String type = "text/plain; charset=utf-8";
        public final Map<String, String> headers = new LinkedHashMap<>();
        public byte[] body = new byte[0];
        public RangeSource source;
        long from, length;

        public static Response json(Object o) {
            return json(200, o);
        }

        public static Response json(int status, Object o) {
            Response r = new Response();
            r.status = status;
            r.type = "application/json; charset=utf-8";
            r.body = String.valueOf(o).getBytes(StandardCharsets.UTF_8);
            r.headers.put("Cache-Control", "no-store");
            return r;
        }

        public static Response error(int status, String msg) {
            JSONObject o = new JSONObject();
            try {
                o.put("error", msg);
            } catch (JSONException ignored) {
            }
            return json(status, o);
        }

        public static Response bytes(String type, byte[] data) {
            Response r = new Response();
            r.type = type;
            r.body = data;
            return r;
        }

        public static Response text(int status, String text) {
            Response r = new Response();
            r.status = status;
            r.body = text.getBytes(StandardCharsets.UTF_8);
            return r;
        }

        public static Response redirect(String to) {
            Response r = new Response();
            r.status = 302;
            r.headers.put("Location", to);
            return r;
        }

        /** Файл с поддержкой Range (перемотка видео в плеере). */
        public static Response ranged(Request req, RangeSource src, String type) {
            Response r = new Response();
            r.type = type;
            r.source = src;
            long size = src.size();
            r.headers.put("Accept-Ranges", "bytes");
            r.headers.put("Cache-Control", "no-store");
            String range = req.header("range");
            r.from = 0;
            r.length = size;
            if (range != null && range.startsWith("bytes=")) {
                String spec = range.substring(6).split(",")[0].trim();
                try {
                    long a, b;
                    if (spec.startsWith("-")) {
                        long n = Long.parseLong(spec.substring(1));
                        a = Math.max(0, size - n);
                        b = size - 1;
                    } else {
                        String[] ab = spec.split("-", -1);
                        a = Long.parseLong(ab[0]);
                        b = ab[1].isEmpty() ? size - 1 : Math.min(Long.parseLong(ab[1]), size - 1);
                    }
                    if (a >= size || b < a) {
                        Response bad = text(416, "");
                        bad.headers.put("Content-Range", "bytes */" + size);
                        return bad;
                    }
                    r.status = 206;
                    r.from = a;
                    r.length = b - a + 1;
                    r.headers.put("Content-Range", "bytes " + a + "-" + b + "/" + size);
                } catch (NumberFormatException ignored) {
                    // неверный Range — отдаём файл целиком
                }
            }
            return r;
        }
    }

    private final Handler handler;
    private final ExecutorService pool = Executors.newFixedThreadPool(8);
    private ServerSocket server;
    private volatile boolean running;

    public MiniHttp(Handler handler) {
        this.handler = handler;
    }

    /** Запуск на первом свободном порту из диапазона. Возвращает порт. */
    public int start(int firstPort) throws IOException {
        IOException last = null;
        for (int p = firstPort; p < firstPort + 20; p++) {
            try {
                server = new ServerSocket(p, 50, InetAddress.getByName("127.0.0.1"));
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (server == null) throw last;
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    final Socket s = server.accept();
                    pool.execute(() -> serve(s));
                } catch (IOException e) {
                    if (!running) return;
                }
            }
        }, "clipper-http");
        t.setDaemon(true);
        t.start();
        return server.getLocalPort();
    }

    public int port() {
        return server.getLocalPort();
    }

    public void stop() {
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void serve(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            while (true) {
                Request req;
                try {
                    req = read(in);
                } catch (SocketTimeoutException e) {
                    return;
                }
                if (req == null) return;
                Response resp;
                try {
                    resp = handler.handle(req);
                } catch (ApiError e) {
                    resp = Response.error(e.code, e.getMessage());
                } catch (Exception e) {
                    String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    resp = Response.error(500, m);
                }
                boolean close = "close".equalsIgnoreCase(req.header("connection"));
                write(out, req, resp, close);
                if (close) return;
            }
        } catch (IOException ignored) {
            // клиент закрыл соединение
        }
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("Слишком длинная строка");
        }
        if (c == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    static Request read(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null || line.isEmpty()) return null;
        String[] parts = line.split(" ");
        if (parts.length < 2) return null;
        Request r = new Request();
        r.method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];
        int q = target.indexOf('?');
        r.path = decode(q >= 0 ? target.substring(0, q) : target, false);
        if (q >= 0) {
            r.rawQuery = target.substring(q + 1);
            for (String kv : r.rawQuery.split("&")) {
                if (kv.isEmpty()) continue;
                String[] p = kv.split("=", 2);
                r.query.put(decode(p[0], true), p.length > 1 ? decode(p[1], true) : "");
            }
        }
        String h;
        while ((h = readLine(in)) != null && !h.isEmpty()) {
            int i = h.indexOf(':');
            if (i > 0) r.headers.put(h.substring(0, i).trim().toLowerCase(Locale.ROOT), h.substring(i + 1).trim());
        }
        String cl = r.headers.get("content-length");
        if (cl != null) {
            int n = Integer.parseInt(cl.trim());
            if (n > 8 * 1024 * 1024) throw new IOException("Слишком большой запрос");
            byte[] body = new byte[n];
            int off = 0;
            while (off < n) {
                int k = in.read(body, off, n - off);
                if (k < 0) throw new IOException("Обрыв запроса");
                off += k;
            }
            r.body = body;
        }
        return r;
    }

    /** Декодирование %XX (UTF-8); '+' — пробел только в строке запроса. */
    public static String decode(String s, boolean plusIsSpace) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try {
                    b.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                }
            }
            if (c == '+' && plusIsSpace) {
                b.write(' ');
            } else {
                byte[] bs = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                b.write(bs, 0, bs.length);
            }
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 302: return "Found";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 416: return "Range Not Satisfiable";
            default: return code >= 500 ? "Server Error" : "Status";
        }
    }

    static void write(OutputStream out, Request req, Response r, boolean close) throws IOException {
        long len = r.source != null ? r.length : r.body.length;
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n");
        h.append("Content-Type: ").append(r.type).append("\r\n");
        h.append("Content-Length: ").append(len).append("\r\n");
        h.append("X-Content-Type-Options: nosniff\r\n");
        for (Map.Entry<String, String> e : r.headers.entrySet()) h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        h.append(close ? "Connection: close\r\n" : "Connection: keep-alive\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        if (!"HEAD".equals(req.method)) {
            if (r.source != null) {
                try (InputStream src = r.source.open(r.from)) {
                    byte[] buf = new byte[1 << 16];
                    long left = r.length;
                    while (left > 0) {
                        int k = src.read(buf, 0, (int) Math.min(buf.length, left));
                        if (k <= 0) break;
                        out.write(buf, 0, k);
                        left -= k;
                    }
                }
            } else {
                out.write(r.body);
            }
        }
        out.flush();
    }
}
