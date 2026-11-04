package com.topshelf.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Keeps TopShelf running (and the TV's network awake) while something's being recorded, even with the TV
 * in standby or another app on screen. Stops itself when the last recording ends.
 */
public class RecordService extends Service {
    private static final String CHANNEL = "recording";
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private static volatile boolean running;

    static void begin(Context c, String id) {
        Intent i = new Intent(c, RecordService.class).putExtra("id", id);
        try { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i); }
        catch (Throwable t) {
            // not allowed to start from here (rare): record without it while TopShelf is running
            Diag.ev("record: service refused (" + t.getClass().getSimpleName() + "), recording without it");
            new Thread(new Runnable() { @Override public void run() { Recorder.get(c).begin(id); } }).start();
        }
    }
    static void idleCheck(Context c) {
        if (running && Recorder.get(c).activeCount() == 0) try { c.stopService(new Intent(c, RecordService.class)); } catch (Throwable ignored) { }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent in, int flags, int startId) {
        foreground();
        running = true;
        if (wake == null) {
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "topshelf:record"); wake.acquire(12 * 3600_000L);
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm != null) { wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "topshelf:record"); wifi.acquire(); }
            } catch (Throwable ignored) { }
        }
        final String id = in == null ? null : in.getStringExtra("id");
        new Thread(new Runnable() { @Override public void run() {
            Recorder r = Recorder.get(RecordService.this);
            if (id != null) r.begin(id);
            if (r.activeCount() == 0) stopSelf();
            else update();
        } }, "rec-begin").start();
        return START_NOT_STICKY;
    }

    private Notification.Builder note() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Live TV recording", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        int icon = getResources().getIdentifier("ic_notif", "drawable", getPackageName());
        int n = Recorder.get(this).activeCount();
        return b.setSmallIcon(icon != 0 ? icon : android.R.drawable.presence_video_online)
                .setContentTitle(n > 1 ? "Recording " + n + " programmes" : "Recording")
                .setContentText("TopShelf Live TV").setOngoing(true);
    }
    private void foreground() {
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(11, note().build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(11, note().build());
        } catch (Throwable t) { Diag.ev("record: foreground refused " + t); }
    }
    private void update() {
        try { NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE); nm.notify(11, note().build()); } catch (Throwable ignored) { }
    }

    @Override public void onDestroy() {
        running = false;
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Throwable ignored) { }
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
