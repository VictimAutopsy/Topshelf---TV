package com.topshelf.launcher;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;

/**
 * The Home button without Accessibility. Android blocks the accessibility switch for apps installed
 * from a file, so instead this watches (with "Usage access") for Google TV's home screen coming to
 * the front and brings TopShelf back ("Display over other apps" is what lets it open from the
 * background). Same effect as the accessibility service, no computer needed to set it up.
 */
public class HomeWatchService extends Service {
    private static final String CHANNEL = "homewatch";
    private static volatile boolean running;
    private Thread thread;
    private android.view.View dot;
    // what the watcher has seen, for the status shown in Settings → Home button
    static final java.util.LinkedList<String> seen = new java.util.LinkedList<>();
    static volatile long startedAt, lastRedirect;
    static volatile String lastResult = "", lastError = "";

    static void note(String pkg) {
        synchronized (seen) {
            if (!seen.isEmpty() && seen.getFirst().startsWith(pkg + " ")) seen.removeFirst();
            seen.addFirst(pkg + " " + new java.text.SimpleDateFormat("h:mm:ss a", java.util.Locale.US).format(new java.util.Date()));
            while (seen.size() > 6) seen.removeLast();
        }
    }
    static String status() {
        try {
            org.json.JSONArray a = new org.json.JSONArray();
            synchronized (seen) { for (String s : seen) a.put(s); }
            return new org.json.JSONObject().put("seen", a).put("startedAt", startedAt).put("lastRedirect", lastRedirect)
                    .put("result", lastResult).put("error", lastError).put("sdk", Build.VERSION.SDK_INT).toString();
        } catch (Exception e) { return "{}"; }
    }

    static boolean isRunning() { return running; }

    /** Google's home screen, and other launchers that take over the Home button (Projectivy etc.) */
    static final String[] HOME_APPS = {
        Bridge.STOCK_LAUNCHER, "com.google.android.tvlauncher", "com.google.android.leanbacklauncher",
        "com.spocky.projengmenu", "me.efesser.flauncher", "ca.dstudio.atvlauncher.free", "ca.dstudio.atvlauncher.pro",
        "com.wolf.firelauncher", "com.amazon.tv.launcher"
    };
    static boolean isHomeApp(String p) {
        if (p == null) return false;
        for (String h : HOME_APPS) if (h.equals(p)) return true;
        return false;
    }

    static boolean hasUsageAccess(Context c) {
        try {
            AppOpsManager ao = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int m = Build.VERSION.SDK_INT >= 29
                    ? ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                    : ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
            return m == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable e) { return false; }
    }
    static boolean hasOverlay(Context c) {
        try { return Settings.canDrawOverlays(c); } catch (Throwable e) { return false; }
    }
    static boolean accessibilityOn(Context c) {
        String en = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return en != null && en.contains(c.getPackageName() + "/");
    }
    static boolean wanted(Context c) {
        return c.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE).getBoolean("homewatch", false);
    }
    /** Starts the watcher if it's switched on and allowed (and the accessibility service isn't doing the job). */
    static void startIfWanted(Context c) {
        if (!wanted(c) || running || !hasUsageAccess(c) || !hasOverlay(c) || accessibilityOn(c)) return;
        try {
            Intent i = new Intent(c, HomeWatchService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Throwable ignored) { }
    }
    static void stop(Context c) {
        try { c.stopService(new Intent(c, HomeWatchService.class)); } catch (Throwable ignored) { }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Home button", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        int icon = getResources().getIdentifier("ic_notif", "drawable", getPackageName());
        b.setSmallIcon(icon != 0 ? icon : android.R.drawable.ic_menu_view)
         .setContentTitle("TopShelf home button")
         .setContentText("Opens TopShelf when you press Home")
         .setOngoing(true);
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(7, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(7, b.build());
        } catch (Throwable e) { stopSelf(); return; }
        running = true;
        startedAt = System.currentTimeMillis();
        addDot();
        thread = new Thread(new Runnable() { @Override public void run() { loop(); } }, "home-watch");
        thread.start();
    }

    private void addDotOnMain() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() { @Override public void run() { if (dot == null) addDot(); } });
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        running = false;
        try { if (dot != null) ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).removeView(dot); } catch (Throwable ignored) { }
        dot = null;
        if (thread != null) thread.interrupt();
        super.onDestroy();
    }

    /* Newer Android only lets an app with "Display over other apps" open itself from the background
       while it actually has an overlay window showing: a 1-pixel invisible one does it. */
    private void addDot() {
        if (!hasOverlay(this)) return;
        try {
            android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(1, 1,
                    Build.VERSION.SDK_INT >= 26 ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : android.view.WindowManager.LayoutParams.TYPE_PHONE,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            lp.x = 0; lp.y = 0;
            dot = new android.view.View(this);
            dot.setBackgroundColor(0x00000000);
            wm.addView(dot, lp);
        } catch (Throwable e) { lastError = "overlay: " + e.getMessage(); dot = null; }
    }

    // ================================================================== the curtain
    /* Without the accessibility service, Google's home screen has to show up before TopShelf can be swapped
       back in. A picture of TopShelf's Home (taken a moment earlier) is put over everything the instant that
       starts, so what you see is TopShelf, not Google's screen; it's lifted when TopShelf is back (or at once
       if it turns out another app came forward, and always within a few seconds — it never lets keys go
       astray: it can't be focused or touched). */
    static volatile android.graphics.Bitmap homeShot;
    private static android.view.View curtainView;
    private static long curtainUntil;
    private static final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Runnable curtainCheck = new Runnable() { @Override public void run() {
        if (curtainView == null) return;
        if (MainActivity.inFront || android.os.SystemClock.uptimeMillis() >= curtainUntil) { lift(); return; }
        ui.postDelayed(this, 80);
    } };
    /** shows the curtain for up to ms (or extends one that's up) */
    static void curtain(final Context c, final long ms) {
        ui.post(new Runnable() { @Override public void run() {
            if (!hasOverlay(c) || accessibilityOn(c)) return;
            curtainUntil = Math.max(curtainUntil, android.os.SystemClock.uptimeMillis() + ms);
            if (curtainView == null) {
                try {
                    android.view.WindowManager wm = (android.view.WindowManager) c.getApplicationContext().getSystemService(WINDOW_SERVICE);
                    android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                            android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT,
                            Build.VERSION.SDK_INT >= 26 ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : android.view.WindowManager.LayoutParams.TYPE_PHONE,
                            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                    | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            android.graphics.PixelFormat.OPAQUE);
                    lp.windowAnimations = 0;
                    android.widget.ImageView iv = new android.widget.ImageView(c.getApplicationContext());
                    iv.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
                    iv.setBackgroundColor(0xFF000000);
                    android.graphics.Bitmap b = homeShot;
                    if (b != null && !b.isRecycled()) iv.setImageBitmap(b);
                    wm.addView(iv, lp);
                    curtainView = iv;
                } catch (Throwable e) { lastError = "curtain: " + e.getMessage(); curtainView = null; return; }
            }
            ui.removeCallbacks(curtainCheck);
            ui.postDelayed(curtainCheck, 80);
        } });
    }
    static void lift() {
        ui.post(new Runnable() { @Override public void run() {
            ui.removeCallbacks(curtainCheck);
            if (curtainView == null) return;
            final android.view.View v = curtainView;
            curtainView = null;
            // (a few frames late, so TopShelf's own first frame is already up underneath)
            ui.postDelayed(new Runnable() { @Override public void run() {
                try { ((android.view.WindowManager) v.getContext().getSystemService(WINDOW_SERVICE)).removeView(v); } catch (Throwable ignored) { }
            } }, 120);
        } });
    }
    static boolean curtainUp() { return curtainView != null; }

    private void loop() {
        UsageStatsManager us = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        PowerManager pw = (PowerManager) getSystemService(POWER_SERVICE);
        SharedPreferences prefs = getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE);
        long last = System.currentTimeMillis();
        long lastRedirect = 0;
        UsageEvents.Event ev = new UsageEvents.Event();
        while (running) {
            try {
                if (!pw.isInteractive()) { Thread.sleep(1500); last = System.currentTimeMillis(); continue; }
                Thread.sleep(curtainUp() ? 60 : 120);
                if (!wanted(this) || accessibilityOn(this)) { stopSelf(); return; }
                long now = System.currentTimeMillis();
                UsageEvents events = us.queryEvents(last - 1000, now + 1000);
                long newest = last;
                boolean home = false, sawOther = false;
                while (events != null && events.hasNextEvent()) {
                    events.getNextEvent(ev);
                    if (ev.getTimeStamp() <= last) continue;
                    newest = Math.max(newest, ev.getTimeStamp());
                    if (ev.getEventType() != UsageEvents.Event.MOVE_TO_FOREGROUND) continue;   // = ACTIVITY_RESUMED
                    String p = ev.getPackageName();
                    note(p);
                    // the last app to come forward decides: Google's home → bring TopShelf back
                    home = isHomeApp(p);
                    sawOther = !home && !p.equals(getPackageName());
                }
                last = newest;
                // another app came forward while the curtain was up (TopShelf didn't leave for its Home): lift it
                if (sawOther && !home && curtainUp()) lift();
                if (!home || MainActivity.inFront) continue;
                curtain(this, 2500);
                if (!prefs.getBoolean("redirect", true) || now < prefs.getLong("bypassUntil", 0)) continue;
                if (now - lastRedirect < 900) continue;
                lastRedirect = now;
                HomeWatchService.lastRedirect = now;
                Intent i = new Intent(this, MainActivity.class)
                        .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).putExtra("redirect", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                try { startActivity(i); lastError = ""; } catch (Throwable e) { lastError = "start: " + e.getMessage(); }
                // did Android let it through? (it refuses silently when it doesn't)
                Thread.sleep(1500);
                lastResult = MainActivity.inFront ? "opened" : "blocked";
                if (!MainActivity.inFront && dot == null) addDotOnMain();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable ignored) { }
        }
    }
}
