package ru.tiktokclipper.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.File;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import ru.tiktokclipper.core.Api;
import ru.tiktokclipper.core.JobQueue;
import ru.tiktokclipper.core.MiniHttp;
import ru.tiktokclipper.core.Store;

/** Всё «серверное» в одном месте: хранилище, очередь, API и HTTP на 127.0.0.1. Один экземпляр на процесс. */
public final class Engine {
    private static Engine instance;

    public final Context ctx;
    public final Store store;
    public final AndroidPlatform platform;
    public final JobQueue queue;
    public final Api api;
    public final MiniHttp http;
    public final String token;
    public final int port;
    volatile JobQueue.Listener serviceListener;

    public static synchronized Engine get(Context c) {
        if (instance == null) instance = new Engine(c.getApplicationContext());
        return instance;
    }

    private Engine(Context c) {
        ctx = c;
        store = new Store(new File(c.getFilesDir(), "data"));
        token = loadToken(c);
        platform = new AndroidPlatform(c, store);
        ClipJobs jobs = new ClipJobs(this);
        Map<String, JobQueue.Handler> h = new HashMap<>();
        h.put("clip", jobs::clip);
        h.put("rebuild", jobs::rebuild);
        h.put("moments", jobs::moments);
        h.put("auto", jobs::auto);
        h.put("night", jobs::night);
        h.put("model", jobs::downloadModel);
        queue = new JobQueue(store.jobsFile(), h);
        api = new Api(store, platform, queue, token);
        jobs.api = api;
        // ночная нарезка: перепланировать при каждом сохранении настроек
        api.settingsChanged = () -> NightJobService.sync(ctx, store.settings());
        NightJobService.sync(ctx, store.settings());
        http = new MiniHttp(api);
        int p;
        try {
            p = http.start(18080);
        } catch (Exception e) {
            throw new RuntimeException("Не удалось запустить внутренний сервер: " + e.getMessage(), e);
        }
        port = p;
        queue.setListener((busy, title, progress, stage) -> {
            if (busy) ensureService();
            JobQueue.Listener l = serviceListener;
            if (l != null) l.onBusy(busy, title, progress, stage);
        });
        queue.start();
    }

    /** Служба с уведомлением держит процесс живым, пока идут задачи (экран может быть выключен). */
    void ensureService() {
        if (serviceListener != null) return;
        Intent i = new Intent(ctx, ClipperService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (RuntimeException e) {
            // Android 12+ запрещает запуск службы из фона; задача всё равно идёт, пока приложение живо
        }
    }

    public String startUrl() {
        return "http://127.0.0.1:" + port + "/auth?k=" + token;
    }

    private static String loadToken(Context c) {
        SharedPreferences sp = c.getSharedPreferences("engine", Context.MODE_PRIVATE);
        String t = sp.getString("token", null);
        if (t == null) {
            byte[] b = new byte[24];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            t = sb.toString();
            sp.edit().putString("token", t).apply();
        }
        return t;
    }
}
