package com.topshelf.launcher;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.FrameMetrics;
import android.view.Window;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The flight recorder: how TopShelf does in everyday use, measured on the TV itself, a day at a time, and sent once
 * a day (scrubbed like every report) to the private updates repository as reports/flight/<day>-tv.json, for the
 * dashboard. Nothing is asked of whoever's watching.
 *
 * What it keeps: timings (Home coming back, a fresh start, a stream's first picture — a movie, Continue watching /
 * Up next, a Live TV channel), buffering against watch time, smoothness (every frame Android draws, by screen:
 * on time, late, and hitches over 0.1 s), memory (on screen and in the background), how often TopShelf was closed
 * in the background, crashes and freezes, and which screens and features are used and for how long. Never what's
 * watched: no titles, channels, links or accounts.
 */
final class Flight {
    private Flight() { }
    private static final Object L = new Object();
    private static Context app;
    private static String day = "";
    private static final Map<String, List<Long>> times = new HashMap<>();      // timing name → samples (ms)
    private static final Map<String, Long> counts = new HashMap<>();
    private static final Map<String, Long> screenMs = new HashMap<>();
    private static final Map<String, long[]> frames = new HashMap<>();        // screen → {frames, late, hitches}
    private static final Map<String, long[]> watch = new HashMap<>();         // kind → {watched ms, stalled ms, stalls}
    private static final List<Long> memFg = new ArrayList<>(), memBg = new ArrayList<>();
    private static boolean dirty;

    // ------------------------------------------------------------------ recording

    static void time(String name, long ms) {
        if (ms < 0 || ms > 10 * 60000L) return;
        synchronized (L) { roll(); List<Long> l = times.get(name); if (l == null) { l = new ArrayList<>(); times.put(name, l); } if (l.size() < 400) l.add(ms); dirty = true; }
    }
    static void count(String name) { add(counts, name, 1); }
    private static void add(Map<String, Long> m, String k, long v) { synchronized (L) { roll(); Long o = m.get(k); m.put(k, (o == null ? 0 : o) + v); dirty = true; } }
    private static long[] arr(Map<String, long[]> m, String k, int n) { long[] a = m.get(k); if (a == null) { a = new long[n]; m.put(k, a); } return a; }

    // a stream: from asking to play it until its first picture; then watch time and buffering until it stops
    private static long playAt, stallAt, playingAt;
    private static String playKind = "";
    private static boolean firstSeen;
    /** a stream is asked for (kind: movie | continue | next | live | trailer) */
    static void playAsked(String kind) { synchronized (L) { playAt = SystemClock.uptimeMillis(); playKind = kind; firstSeen = false; stallAt = 0; playingAt = 0; } }
    private static String nextKind = ""; private static long nextKindAt;
    /** the next stream asked for is the next episode (TitleView.afterEpisode) */
    static void nextIsEpisode() { nextKind = "next episode"; nextKindAt = SystemClock.uptimeMillis(); }
    static String vodKind(boolean resuming) {
        if (!nextKind.isEmpty() && SystemClock.uptimeMillis() - nextKindAt < 90000) { nextKind = ""; return "next episode"; }
        return resuming ? "continue watching" : "movie or episode";
    }
    private static String pendingVod = "movie or episode";
    static void vodNext(String k) { pendingVod = k; }
    static String takeVod() { return pendingVod; }
    static void firstFrame() {
        String k; long ms;
        synchronized (L) { if (firstSeen || playAt == 0) return; firstSeen = true; k = playKind; ms = SystemClock.uptimeMillis() - playAt; playingAt = SystemClock.uptimeMillis(); }
        time("first picture · " + k, ms);
    }
    static void buffering(boolean on) {
        synchronized (L) {
            if (!firstSeen) return;
            long now = SystemClock.uptimeMillis();
            roll();
            long[] w = arr(watch, playKind, 3);
            if (on && stallAt == 0) { stallAt = now; if (playingAt > 0) { w[0] += now - playingAt; playingAt = 0; } }
            else if (!on && stallAt > 0) { long d = now - stallAt; if (d > 150) { w[1] += d; w[2]++; } stallAt = 0; playingAt = now; }
            dirty = true;
        }
    }
    static void playStopped() {
        synchronized (L) {
            long now = SystemClock.uptimeMillis();
            if (firstSeen) { roll(); long[] w = arr(watch, playKind, 3); if (playingAt > 0) w[0] += now - playingAt; if (stallAt > 0) { w[1] += now - stallAt; w[2]++; } dirty = true; }
            playAt = 0; playingAt = 0; stallAt = 0; firstSeen = false;
        }
    }
    /** paused (no watch time while paused) */
    static void paused(boolean p) {
        synchronized (L) {
            if (!firstSeen) return;
            long now = SystemClock.uptimeMillis();
            if (p && playingAt > 0) { roll(); arr(watch, playKind, 3)[0] += now - playingAt; playingAt = 0; dirty = true; }
            else if (!p && playingAt == 0 && stallAt == 0) playingAt = now;
        }
    }

    // ------------------------------------------------------------------ the screen, frames and memory

    interface Screen { String now(); }
    private static Screen screen;
    private static String cur = "";
    private static long curAt;
    private static Handler bg;
    private static boolean front;

    /** started once (MainActivity.onCreate): frames from the window, and a tick for the screen and memory */
    private static boolean started;
    static void start(Activity act, Screen s) {
        app = act.getApplicationContext();
        screen = s;
        if (bg == null) { HandlerThread t = new HandlerThread("flight"); t.start(); bg = new Handler(t.getLooper()); }
        // (the window's frames: again for each new window — a change of look makes the screen anew)
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                // late: took more than twice its slot (see SelfCheck.late)
                final long refresh = SelfCheck.refreshNs(act);
                act.getWindow().addOnFrameMetricsAvailableListener(new Window.OnFrameMetricsAvailableListener() {
                    @Override public void onFrameMetricsAvailable(Window w, FrameMetrics m, int dropped) {
                        long ns = m.getMetric(FrameMetrics.TOTAL_DURATION);
                        String sc = cur.isEmpty() ? "other" : cur;
                        boolean missed = SelfCheck.late(m, refresh);
                        synchronized (L) {
                            roll();
                            long[] f = arr(frames, sc, 3);
                            f[0]++;
                            if (missed) f[1]++;
                            if (ns > 100_000_000L) f[2]++;
                            dirty = true;
                        }
                    }
                }, bg);
            } catch (Throwable ignored) { }
        }
        if (started) return;
        started = true;
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.post(new Runnable() { @Override public void run() {
            if (front) {
                String n = "";
                try { n = screen == null ? "" : screen.now(); } catch (Throwable ignored) { }
                long now = SystemClock.uptimeMillis();
                if (!cur.isEmpty() && curAt > 0) add(screenMs, cur, now - curAt);
                if (!n.equals(cur) && !n.isEmpty()) count("opened · " + n);
                cur = n; curAt = now;
            }
            main.postDelayed(this, 1000);
        } });
        // memory every ten minutes (on screen or not: in the background is what decides whether Android closes it)
        bg.postDelayed(new Runnable() { @Override public void run() {
            try {
                long mb = Debug.getPss() / 1024;
                synchronized (L) { roll(); List<Long> l = front ? memFg : memBg; if (l.size() < 300) l.add(mb); dirty = true; }
            } catch (Throwable ignored) { }
            save();
            upload();          // (a day that ended while TopShelf stayed on screen: sent now, not only on leaving)
            bg.postDelayed(this, 10 * 60000L);
        } }, 60000);
    }
    static void inFront(boolean f) {
        long now = SystemClock.uptimeMillis();
        if (!f && front && !cur.isEmpty() && curAt > 0) add(screenMs, cur, now - curAt);
        front = f; curAt = now; cur = "";
        if (f) count("sessions");
        else if (bg != null) bg.post(new Runnable() { @Override public void run() { save(); upload(); } });
    }

    // ------------------------------------------------------------------ a day on disk, and sent

    private static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); }
    private static File dir() { File d = new File(app.getFilesDir(), "flight"); d.mkdirs(); return d; }
    /** (under L) a new day: the last one saved, today's begun empty */
    private static void roll() {
        String t = today();
        if (t.equals(day)) return;
        if (!day.isEmpty() && dirty) write(day, json());
        day = t;
        times.clear(); counts.clear(); screenMs.clear(); frames.clear(); watch.clear(); memFg.clear(); memBg.clear();
        // (today's file so far, if TopShelf was started again today)
        if (app != null) try {
            File f = new File(dir(), t + ".json");
            if (f.exists()) merge(new JSONObject(new String(readAll(f), "UTF-8")));
        } catch (Throwable ignored) { }
        dirty = false;
    }
    static void save() {
        if (app == null) return;
        String d; JSONObject o;
        synchronized (L) { roll(); if (!dirty) return; d = day; o = json(); dirty = false; }
        write(d, o);
    }
    private static void write(String d, JSONObject o) {
        try {
            File f = new File(dir(), d + ".json"), tmp = new File(dir(), d + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(o.toString().getBytes("UTF-8")); }
            tmp.renameTo(f);
        } catch (Throwable ignored) { }
    }
    /** (under L) today as JSON: each timing's samples with its median, 90th percentile and worst */
    private static JSONObject json() {
        JSONObject o = new JSONObject();
        try {
            o.put("day", day).put("version", BuildConfigLite.version(app)).put("device", Build.MODEL);
            JSONObject tm = new JSONObject();
            for (Map.Entry<String, List<Long>> e : times.entrySet()) {
                List<Long> s = new ArrayList<>(e.getValue()); Collections.sort(s);
                JSONObject x = new JSONObject().put("n", s.size());
                if (!s.isEmpty()) x.put("median", s.get(s.size() / 2)).put("p90", s.get(Math.min(s.size() - 1, (int) Math.floor(s.size() * .9)))).put("worst", s.get(s.size() - 1));
                x.put("samples", new JSONArray(e.getValue()));
                tm.put(e.getKey(), x);
            }
            o.put("times", tm);
            o.put("counts", new JSONObject(counts));
            o.put("screenMs", new JSONObject(screenMs));
            JSONObject fr = new JSONObject();
            for (Map.Entry<String, long[]> e : frames.entrySet()) fr.put(e.getKey(), new JSONObject().put("frames", e.getValue()[0]).put("late", e.getValue()[1]).put("hitches", e.getValue()[2]));
            o.put("frames", fr);
            JSONObject w = new JSONObject();
            for (Map.Entry<String, long[]> e : watch.entrySet()) w.put(e.getKey(), new JSONObject().put("watchedMs", e.getValue()[0]).put("stalledMs", e.getValue()[1]).put("stalls", e.getValue()[2]));
            o.put("watch", w);
            o.put("memOnScreenMB", new JSONArray(memFg)).put("memBackgroundMB", new JSONArray(memBg));
        } catch (Exception ignored) { }
        return o;
    }
    /** (under L) adds a saved day back in (TopShelf started again on the same day) */
    private static void merge(JSONObject o) {
        JSONObject tm = o.optJSONObject("times");
        if (tm != null) for (java.util.Iterator<String> it = tm.keys(); it.hasNext(); ) {
            String k = it.next(); JSONArray s = tm.optJSONObject(k) == null ? null : tm.optJSONObject(k).optJSONArray("samples");
            List<Long> l = new ArrayList<>(); if (s != null) for (int i = 0; i < s.length(); i++) l.add(s.optLong(i)); times.put(k, l);
        }
        for (String[] m : new String[][] { { "counts" }, { "screenMs" } }) {
            JSONObject c = o.optJSONObject(m[0]); if (c == null) continue;
            Map<String, Long> into = m[0].equals("counts") ? counts : screenMs;
            for (java.util.Iterator<String> it = c.keys(); it.hasNext(); ) { String k = it.next(); into.put(k, c.optLong(k)); }
        }
        JSONObject fr = o.optJSONObject("frames");
        if (fr != null) for (java.util.Iterator<String> it = fr.keys(); it.hasNext(); ) { String k = it.next(); JSONObject x = fr.optJSONObject(k); frames.put(k, new long[] { x.optLong("frames"), x.optLong("late"), x.optLong("hitches") }); }
        JSONObject w = o.optJSONObject("watch");
        if (w != null) for (java.util.Iterator<String> it = w.keys(); it.hasNext(); ) { String k = it.next(); JSONObject x = w.optJSONObject(k); watch.put(k, new long[] { x.optLong("watchedMs"), x.optLong("stalledMs"), x.optLong("stalls") }); }
        for (String[] m : new String[][] { { "memOnScreenMB" }, { "memBackgroundMB" } }) {
            JSONArray a = o.optJSONArray(m[0]); if (a == null) continue;
            List<Long> into = m[0].equals("memOnScreenMB") ? memFg : memBg;
            for (int i = 0; i < a.length(); i++) into.add(a.optLong(i));
        }
    }
    /** every finished day not sent yet goes up (when TopShelf goes to the background: nothing waits on it) */
    static void upload() {
        if (app == null) return;
        File[] fs = dir().listFiles();
        if (fs == null) return;
        String t = today();
        android.content.SharedPreferences pr = app.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE);
        Updater u = null;
        for (File f : fs) {
            String n = f.getName();
            if (!n.endsWith(".json") || n.startsWith(t)) continue;
            String d = n.substring(0, n.length() - 5);
            if (pr.getBoolean("flightSent." + d, false)) { if (System.currentTimeMillis() - f.lastModified() > 30L * 86400000L) f.delete(); continue; }
            try {
                if (u == null) { u = new Updater(app, pr); if (u.token().isEmpty()) return; }
                String body = Diag.scrub(new String(readAll(f), "UTF-8"));
                if (u.upload("reports/flight/" + d + "-tv.json", body, "Flight " + d).isEmpty()) pr.edit().putBoolean("flightSent." + d, true).apply();
            } catch (Throwable ignored) { }
        }
    }
    private static byte[] readAll(File f) throws java.io.IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        }
    }

    /** the version, without BuildConfig (the build here makes none) */
    static final class BuildConfigLite {
        static String version(Context c) { try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Throwable t) { return "?"; } }
    }
}
