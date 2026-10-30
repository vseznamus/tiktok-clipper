package ru.tiktokclipper.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Пока в очереди есть задачи, служба держит приложение живым (уведомление с прогрессом)
 * и не даёт процессору уснуть — можно выключить экран или свернуть приложение.
 */
public class ClipperService extends Service {
    static final String CHANNEL = "jobs";
    static final int ID = 42;
    private PowerManager.WakeLock wake;
    private long lastUpdate;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Нарезка клипов", NotificationManager.IMPORTANCE_LOW));
        Notification n = build("Нарезка", "Начинаю…", 0);
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(ID, n);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tiktokclipper:jobs");
        wake.acquire(6 * 60 * 60 * 1000L);
        Engine engine = Engine.get(this);
        engine.serviceListener = (busy, title, progress, stage) -> {
            if (!busy) {
                stopSelf();
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastUpdate < 1000) return;
            lastUpdate = now;
            nm.notify(ID, build(title == null ? "Нарезка" : title, stage == null ? "" : stage, progress));
        };
    }

    private Notification build(String title, String text, double progress) {
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setContentTitle(title)
                .setContentText(text + " · " + Math.round(progress * 100) + "%")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, (int) Math.round(progress * 100), false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Engine.get(this).serviceListener = null;
        if (wake != null && wake.isHeld()) wake.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
