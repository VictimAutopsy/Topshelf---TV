package com.topshelf.launcher;

import android.graphics.Bitmap;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.FrameMetrics;
import android.view.KeyEvent;
import android.view.Window;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The self-check: about a minute in which TopShelf goes through its own main screens with its own remote presses —
 * Home, Movies (and a title page), TV Shows, Sports, Live TV, Settings — and back to Home. For each it keeps how long
 * the screen took to come up, how smoothly it drew while being scrolled (frames on time, late, hitches over 0.1 s),
 * memory, and a screenshot; then it all goes (scrubbed) to the private updates repository as
 * reports/selfcheck/<when>-<version>.json and its pictures, for checking a new version before it's called done.
 *
 * It runs once after each update (when nothing's playing and the remote's been left alone for a while) and from
 * Settings › System › Run a self-check. Any press of the remote stops it at once and goes back to Home.
 */
final class SelfCheck {
    private SelfCheck() { }
    private static volatile boolean running, injecting;
    private static MainActivity act;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static JSONArray steps;
    private static final List<Object[]> shots = new ArrayList<>();     // {name, jpeg}
    private static long t0;
    private static String why;
    // frames during a step
    private static long fr, late, hitch;
    /* A frame is late when it took more than twice its slot to make (the rule Android's own JankStats uses): its
       slot is the deadline the TV gives each frame (Android 12+), otherwise one refresh. Counting the gaps between
       frames instead called a 24 fps trailer or a 30 fps glow "late" at every frame. */
    static boolean late(FrameMetrics m, long refresh) {
        long total = m.getMetric(FrameMetrics.TOTAL_DURATION);
        long slot = Build.VERSION.SDK_INT >= 31 ? m.getMetric(FrameMetrics.DEADLINE) : refresh;
        if (slot <= 0) slot = refresh;
        return total > 2 * slot;
    }
    static long refreshNs(android.app.Activity a) { try { float hz = a.getWindowManager().getDefaultDisplay().getRefreshRate(); return hz > 1 ? (long) (1e9 / hz) : 16_666_667L; } catch (Throwable t) { return 16_666_667L; } }
    private static Window.OnFrameMetricsAvailableListener fl;
    private static Handler fh;

    static boolean running() { return running; }
    static boolean injecting() { return injecting; }

    /** once per version, a little after it first starts (tries again later while someone's using it) */
    static void afterUpdate(final MainActivity a) {
        final android.content.SharedPreferences p = a.getSharedPreferences(Bridge.PREFS, android.content.Context.MODE_PRIVATE);
        final int ver = a.versionCodeNow();
        if (p.getInt("selfCheckedVer", 0) == ver) return;
        main.postDelayed(new Runnable() { int tries; @Override public void run() {
            if (running || p.getInt("selfCheckedVer", 0) == ver) return;
            if (!a.readyForSelfCheck()) { if (++tries < 20) main.postDelayed(this, 60000); return; }
            p.edit().putInt("selfCheckedVer", ver).apply();
            start(a, "after the update to " + a.verNameNow());
        } }, 45000);
    }

    static void start(MainActivity a, String reason) {
        if (running) return;
        NativeHome h = a.nativeHome();
        if (h == null) { Diag.ev("self-check: no Home to check"); return; }
        act = a; running = true; why = reason; steps = new JSONArray(); shots.clear(); t0 = SystemClock.uptimeMillis();
        Diag.ev("self-check: started (" + reason + ")");
        h.toast("Self-check — TopShelf is testing itself for about a minute. Press any button to stop.");
        watchFrames(true);
        List<Runnable> plan = new ArrayList<>();
        plan.add(tab("Home", "home", "r6l6"));
        plan.add(tab("Movies", "movies", "r6d2u2"));
        plan.add(title());
        plan.add(tab("TV Shows", "series", "r4"));
        plan.add(tab("Sports", "sports", ""));
        plan.add(standings());
        plan.add(tab("Live TV", "live", "d4u4"));
        plan.add(settings());
        plan.add(tab("Home again", "home", ""));
        run(plan, 0);
    }

    /** a button pressed on the remote: stop, back to Home */
    static void abort(String reason) {
        if (!running) return;
        running = false;
        main.removeCallbacksAndMessages(null);
        watchFrames(false);
        Diag.ev("self-check: stopped (" + reason + ")");
        try { NativeHome h = act.nativeHome(); if (h != null) { h.page("window.closeSettings&&closeSettings()"); if (h.titleView != null && h.titleView.shownNow()) h.titleView.close(); h.showTab("home"); h.toast("Self-check stopped"); } } catch (Throwable ignored) { }
        try { steps.put(new JSONObject().put("stopped", reason)); } catch (Exception ignored) { }
        send(false);
    }

    private static void run(final List<Runnable> plan, final int i) {
        if (!running) return;
        if (i >= plan.size()) { finish(); return; }
        next = new Runnable() { @Override public void run() { SelfCheck.run(plan, i + 1); } };
        try { plan.get(i).run(); } catch (Throwable t) { record("step " + (i + 1), -1, "error: " + t.getClass().getSimpleName()); main.post(next); }
    }
    private static Runnable next;

    /** a tab: shown, timed to its first drawn frames, scrolled with the remote (keys: r6 = six to the right…), pictured */
    private static Runnable tab(final String name, final String id, final String keys) {
        return new Runnable() { @Override public void run() {
            final NativeHome h = act.nativeHome(); if (h == null) { abort("Home went away"); return; }
            final long s = SystemClock.uptimeMillis();
            h.showTab(id);
            settled(new Runnable() { @Override public void run() {
                final long appear = SystemClock.uptimeMillis() - s;
                main.postDelayed(new Runnable() { @Override public void run() {
                    h.selfCheckIntoScreen();
                    resetFrames();
                    press(keys, 0, new Runnable() { @Override public void run() {
                        main.postDelayed(new Runnable() { @Override public void run() {
                            record(name, appear, "");
                            shot(name, next);
                        } }, 700);
                    } });
                } }, id.equals("sports") || id.equals("live") ? 2500 : 1500);
            } });
        } };
    }
    /** a title page (the first title in Movies, opened as OK on its poster does — never Play): timed until it's up */
    private static Runnable title() {
        return new Runnable() { @Override public void run() {
            final NativeHome h = act.nativeHome(); if (h == null) { abort("Home went away"); return; }
            JSONObject m = h.anyTitle();
            if (m == null) { record("Title page", -1, "no title in Movies to open"); main.post(next); return; }
            final long s = SystemClock.uptimeMillis();
            h.openTitle(m);
            main.postDelayed(new Runnable() { int n; @Override public void run() {
                if (h.titleView != null && h.titleView.shownNow()) {
                    final long appear = SystemClock.uptimeMillis() - s;
                    main.postDelayed(new Runnable() { @Override public void run() {
                        resetFrames();
                        press("d2u2", 0, new Runnable() { @Override public void run() {
                            record("Title page", appear, "");
                            shot("Title page", new Runnable() { @Override public void run() { key(KeyEvent.KEYCODE_BACK); main.postDelayed(next, 900); } });
                        } });
                    } }, 2000);
                } else if (++n < 60) main.postDelayed(this, 100);
                else { record("Title page", -1, "didn't open within 6 s"); main.post(next); }
            } }, 100);
        } };
    }
    /** Sports › Standings: shown, and pictured once the tables have had a moment to come from ESPN */
    private static Runnable standings() {
        return new Runnable() { @Override public void run() {
            final NativeHome h = act.nativeHome(); if (h == null) { abort("Home went away"); return; }
            NativeSports sp = h.sportsNow();
            if (sp == null || !sp.shownNow()) { record("Standings", -1, "Sports wasn't open"); main.post(next); return; }
            final long s = SystemClock.uptimeMillis();
            resetFrames();
            if (!sp.selfCheckStandings()) { record("Standings", -1, "no Standings tab"); main.post(next); return; }
            settled(new Runnable() { @Override public void run() {
                final long appear = SystemClock.uptimeMillis() - s;
                main.postDelayed(new Runnable() { @Override public void run() {
                    press("d2u2", 0, new Runnable() { @Override public void run() {
                        record("Standings", appear, "");
                        shot("Standings", next);
                    } });
                } }, 3000);
            } });
        } };
    }
    private static Runnable settings() {
        return new Runnable() { @Override public void run() {
            final NativeHome h = act.nativeHome(); if (h == null) { abort("Home went away"); return; }
            final long s = SystemClock.uptimeMillis();
            h.page("openSettings()");
            settled(new Runnable() { @Override public void run() {
                final long appear = SystemClock.uptimeMillis() - s;
                main.postDelayed(new Runnable() { @Override public void run() {
                    resetFrames();
                    press("d4u4", 0, new Runnable() { @Override public void run() {
                        record("Settings", appear, "");
                        shot("Settings", new Runnable() { @Override public void run() { key(KeyEvent.KEYCODE_BACK); main.postDelayed(next, 900); } });
                    } });
                } }, 1200);
            } });
        } };
    }

    // ------------------------------------------------------------------ the pieces

    /** two frames drawn after a change: the screen is up */
    private static void settled(final Runnable then) {
        android.view.Choreographer.getInstance().postFrameCallback(new android.view.Choreographer.FrameCallback() { int n;
            @Override public void doFrame(long f) { if (!running) return; if (++n < 2) android.view.Choreographer.getInstance().postFrameCallback(this); else then.run(); } });
    }
    private static void press(final String keys, final int at, final Runnable then) {
        if (!running) return;
        if (at >= keys.length()) { then.run(); return; }
        char d = keys.charAt(at); int n = 0, j = at + 1;
        while (j < keys.length() && Character.isDigit(keys.charAt(j))) { n = n * 10 + (keys.charAt(j) - '0'); j++; }
        final int code = d == 'r' ? KeyEvent.KEYCODE_DPAD_RIGHT : d == 'l' ? KeyEvent.KEYCODE_DPAD_LEFT : d == 'u' ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN;
        final int count = Math.max(1, n), nextAt = j;
        main.post(new Runnable() { int k; @Override public void run() {
            if (!running) return;
            if (k++ >= count) { press(keys, nextAt, then); return; }
            key(code);
            main.postDelayed(this, 260);
        } });
    }
    private static void key(int code) {
        injecting = true;
        try {
            long t = SystemClock.uptimeMillis();
            act.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
            act.dispatchKeyEvent(new KeyEvent(t, t + 40, KeyEvent.ACTION_UP, code, 0));
        } catch (Throwable ignored) {
        } finally { injecting = false; }
    }
    private static void record(String name, long appearMs, String note) {
        try {
            JSONObject o = new JSONObject().put("screen", name);
            if (appearMs >= 0) o.put("appearMs", appearMs);
            o.put("frames", fr).put("late", late).put("hitches", hitch);
            if (fr > 0) o.put("onTimePct", Math.round(1000.0 * (fr - late) / fr) / 10.0);
            o.put("memoryMB", Debug.getPss() / 1024);
            if (!note.isEmpty()) o.put("note", note);
            steps.put(o);
        } catch (Exception ignored) { }
    }
    private static void resetFrames() { synchronized (SelfCheck.class) { fr = 0; late = 0; hitch = 0; } }
    private static void watchFrames(boolean on) {
        if (Build.VERSION.SDK_INT < 24 || act == null) return;
        try {
            if (on) {
                if (fh == null) { android.os.HandlerThread t = new android.os.HandlerThread("selfcheck-frames"); t.start(); fh = new Handler(t.getLooper()); }
                final long refresh = refreshNs(act);
                fl = new Window.OnFrameMetricsAvailableListener() { @Override public void onFrameMetricsAvailable(Window w, FrameMetrics m, int dropped) {
                    long ns = m.getMetric(FrameMetrics.TOTAL_DURATION);
                    boolean lt = late(m, refresh);
                    synchronized (SelfCheck.class) { fr++; if (lt) late++; if (ns > 100_000_000L) hitch++; }
                } };
                act.getWindow().addOnFrameMetricsAvailableListener(fl, fh);
            } else if (fl != null) { act.getWindow().removeOnFrameMetricsAvailableListener(fl); fl = null; }
        } catch (Throwable ignored) { }
    }
    /** the screen as it is (half size, JPEG) */
    private static void shot(final String name, final Runnable then) {
        if (Build.VERSION.SDK_INT < 26) { main.post(then); return; }
        try {
            android.view.View d = act.getWindow().getDecorView();
            final Bitmap b = Bitmap.createBitmap(Math.max(16, d.getWidth() / 2), Math.max(16, d.getHeight() / 2), Bitmap.Config.ARGB_8888);
            android.view.PixelCopy.request(act.getWindow(), b, new android.view.PixelCopy.OnPixelCopyFinishedListener() {
                @Override public void onPixelCopyFinished(int r) {
                    if (r == android.view.PixelCopy.SUCCESS) {
                        ByteArrayOutputStream o = new ByteArrayOutputStream();
                        b.compress(Bitmap.CompressFormat.JPEG, 72, o);
                        shots.add(new Object[] { name, o.toByteArray() });
                    }
                    b.recycle();
                    main.post(then);
                }
            }, main);
        } catch (Throwable t) { main.post(then); }
    }
    private static void finish() {
        running = false;
        watchFrames(false);
        Diag.ev("self-check: done in " + (SystemClock.uptimeMillis() - t0) / 1000 + " s");
        NativeHome h = act.nativeHome();
        if (h != null) h.toast("Self-check done — thanks");
        send(true);
    }
    private static void send(final boolean done) {
        final JSONArray st = steps; final List<Object[]> sh = new ArrayList<>(shots); final String reason = why; final MainActivity a = act;
        new Thread(new Runnable() { @Override public void run() {
            try {
                Updater u = new Updater(a, a.getSharedPreferences(Bridge.PREFS, android.content.Context.MODE_PRIVATE));
                if (u.token().isEmpty()) return;
                String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm", java.util.Locale.US).format(new java.util.Date()) + "-" + a.verNameNow();
                JSONObject o = new JSONObject().put("version", a.verNameNow()).put("why", reason).put("finished", done)
                        .put("seconds", (SystemClock.uptimeMillis() - t0) / 1000).put("steps", st).put("events", Diag.events());
                JSONArray pics = new JSONArray();
                for (Object[] s : sh) {
                    String path = "reports/selfcheck/" + stamp + "-" + String.valueOf(s[0]).replaceAll("[^A-Za-z0-9]+", "-").toLowerCase(java.util.Locale.US) + ".jpg";
                    if (u.uploadBytes(path, (byte[]) s[1], "Self-check picture").isEmpty()) pics.put(path);
                }
                o.put("pictures", pics);
                u.upload("reports/selfcheck/" + stamp + ".json", Diag.scrub(o.toString(1)), "Self-check " + a.verNameNow());
            } catch (Throwable ignored) { }
        } }, "selfcheck-send").start();
    }
}
