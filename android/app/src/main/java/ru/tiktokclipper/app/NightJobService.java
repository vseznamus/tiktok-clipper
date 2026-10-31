package ru.tiktokclipper.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalTime;

import ru.tiktokclipper.core.Store;

/**
 * Ночная нарезка: Android сам запускает задачу, когда телефон на зарядке и им не пользуются (обычно ночью).
 * Работает с 21:00 до 9:00 и не чаще раза в сутки. Утром — уведомление «Готово N клипов».
 */
public class NightJobService extends JobService {
    static final int JOB_ID = 4242;
    static final String CHANNEL = "night";

    /** Включить или выключить по настройкам (вызывается при запуске и после сохранения настроек). */
    static void sync(Context c, JSONObject settings) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        JSONObject n = settings.optJSONObject("night");
        boolean on = n != null && n.optBoolean("enabled") && !n.optString("folder_uri").isEmpty();
        if (!on) {
            js.cancel(JOB_ID);
            return;
        }
        for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return; // уже запланировано
        JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(c, NightJobService.class))
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .setPeriodic(3 * 60 * 60 * 1000L)
                .setPersisted(true)
                .build();
        js.schedule(info);
    }

    static boolean nightTime(LocalTime t) {
        return t.getHour() >= 21 || t.getHour() < 9;
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Engine e = Engine.get(this);
        JSONObject st = Store.readObject(new File(e.store.dir, "night.json"));
        if (!nightTime(LocalTime.now()) || LocalDate.now().toString().equals(st.optString("last_run"))) return false;
        new Thread(() -> {
            try {
                JSONObject job = e.queue.submit("night", new JSONObject(), "Ночная нарезка");
                String id = job.getString("id");
                // ждём, пока очередь (поиск моментов и сборка клипов) не опустеет
                while (true) {
                    Thread.sleep(5000);
                    JSONArray all = e.queue.list();
                    boolean busy = false;
                    for (int i = 0; i < all.length(); i++) {
                        String s = all.getJSONObject(i).optString("status");
                        if (s.equals("queued") || s.equals("running")) busy = true;
                    }
                    if (!busy) break;
                }
                JSONObject res = e.queue.get(id);
                int n = res != null && res.optJSONObject("result") != null ? res.getJSONObject("result").optJSONArray("clip_jobs").length() : 0;
                if (n > 0) notifyDone(n);
                jobFinished(params, false);
            } catch (Exception ex) {
                jobFinished(params, true);
            }
        }, "night").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // система прервала — продолжим в следующий раз (очередь сохраняется)
    }

    private void notifyDone(int n) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Ночная нарезка", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .setData(android.net.Uri.parse("narezka://clip"));
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification note = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_slideshow)
                .setContentTitle("Ночная нарезка: готово клипов — " + n)
                .setContentText("Откройте «Нарезку» → «Клип» → «Готовые клипы» → «Поделиться»")
                .setContentIntent(pi).setAutoCancel(true).build();
        nm.notify(JOB_ID, note);
    }
}
