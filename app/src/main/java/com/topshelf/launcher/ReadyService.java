package com.topshelf.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps TopShelf ready while another app is in front, so the Home button comes straight back to it instead of
 * starting it again from nothing (with the loading screen). A launcher that isn't the TV's own home app counts
 * for little with Android: with an app like Stremio in front, it was one of the first things closed when memory
 * ran short. A foreground service (it does nothing, and its notification is the quietest kind) puts it near the
 * back of that queue. Settings › TopShelf › "Keep TopShelf ready" (on unless switched off).
 */
public class ReadyService extends Service {
    private static final String CHANNEL = "ready";
    private static volatile boolean running;

    static boolean isRunning() { return running; }
    static boolean wanted(Context c) { return c.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE).getBoolean("keepReady", true); }

    /** while TopShelf is on screen (or at boot): Android lets a foreground service start then */
    static void startIfWanted(Context c) {
        if (running || !wanted(c)) return;
        try {
            Intent i = new Intent(c, ReadyService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Throwable t) { Diag.ev("keep ready: couldn't start (" + t.getClass().getSimpleName() + ")"); }
    }
    static void stop(Context c) { try { c.stopService(new Intent(c, ReadyService.class)); } catch (Throwable ignored) { } }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Ready in the background", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        int icon = getResources().getIdentifier("ic_notif", "drawable", getPackageName());
        b.setSmallIcon(icon != 0 ? icon : android.R.drawable.ic_menu_view)
         .setContentTitle("TopShelf is ready")
         .setContentText("So the Home button comes straight back")
         .setOngoing(true);
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(8, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(8, b.build());
        } catch (Throwable e) { Diag.ev("keep ready: Android didn't allow it (" + e.getClass().getSimpleName() + ")"); stopSelf(); return; }
        running = true;
        Diag.ev("keep ready: on");
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { running = false; super.onDestroy(); }
}
