package com.topshelf.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayDeque;

/**
 * Deeper diagnostics for the report:
 *  - a timeline of notable events (tab changes, video layer made/removed, playback start/stop, errors),
 *  - the state of the TV per test section (memory, graphics memory, memory clean-ups, heat, how much
 *    processor time the app's screen thread and drawing thread used),
 *  - the app's own Android log (dropped-frame warnings, graphics and video decoder errors).
 */
final class Diag {
    private Diag() { }

    // ------------------------------------------------------------------ the event timeline
    private static final ArrayDeque<String> EVENTS = new ArrayDeque<>();
    private static final long T0 = SystemClock.elapsedRealtime();
    private static final java.text.SimpleDateFormat CLOCK = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US);

    /* memory over time: a line in the event log every couple of minutes and after each tab change, so a report
       shows where graphics memory grows (reading it takes ~100 ms, so never on the main thread) */
    private static final java.util.concurrent.ExecutorService MEM = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static volatile String memWhere = "";
    static void mem(final String where) {
        memWhere = where;
        MEM.execute(new Runnable() { @Override public void run() {
            try {
                Debug.MemoryInfo mi = new Debug.MemoryInfo();
                Debug.getMemoryInfo(mi);
                String g = mi.getMemoryStat("summary.graphics"), n = mi.getMemoryStat("summary.native-heap"), j = mi.getMemoryStat("summary.java-heap");
                ev("mem · " + where + " · graphics " + mb(g) + " MB · pictures/native " + mb(n) + " MB · java " + mb(j) + " MB · total " + Math.round(mi.getTotalPss() / 1024.0) + " MB");
            } catch (Throwable ignored) { }
        } });
    }
    private static long mb(String kb) { try { return Math.round(Long.parseLong(kb) / 1024.0); } catch (Exception e) { return -1; } }
    static String memWhere() { return memWhere; }
    /** the app's picture caches, emptied when Android asks for memory back */
    static final java.util.List<Runnable> TRIMMERS = new java.util.concurrent.CopyOnWriteArrayList<>();
    static void trim(String why) {
        for (Runnable r : TRIMMERS) try { r.run(); } catch (Throwable ignored) { }
        ev("memory: picture caches emptied (" + why + ")");
    }

    /* Reports never carry anyone's keys or logins: links lose IPTV usernames and passwords, debrid and addon
       keys (any long code in a link's path or query), and sign-in tokens, wherever they turn up in a line. */
    private static final java.util.regex.Pattern URLS = java.util.regex.Pattern.compile("(?i)\\b(?:https?|rtmp|rtsp)://[^\\s\"'<>]+");
    private static final java.util.regex.Pattern LONG = java.util.regex.Pattern.compile("(?<=[/=:,|])[A-Za-z0-9_\\-.%~]{24,}");
    private static final java.util.regex.Pattern SECRET = java.util.regex.Pattern.compile("(?i)(bearer\\s+|\"?(?:access_token|refresh_token|id_token|password|passwd|apikey|api_key|client_secret|token|auth)\"?\\s*[:=]\\s*\"?)[^\"&\\s,;]+");
    static String scrub(String s) {
        if (s == null) return "";
        if (s.indexOf("://") >= 0) {
            java.util.regex.Matcher m = URLS.matcher(s);
            StringBuffer b = new StringBuffer();
            while (m.find()) {
                String u = NativePlayer.safe(m.group());
                int q = u.indexOf("://") + 3, path = u.indexOf('/', q);
                if (path > 0) u = u.substring(0, path) + LONG.matcher(u.substring(path)).replaceAll("…");
                m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(u));
            }
            m.appendTail(b);
            s = b.toString();
        }
        return SECRET.matcher(s).replaceAll("$1…");
    }
    static void ev(String what) {
        what = scrub(what);
        String line;
        synchronized (CLOCK) { line = CLOCK.format(new java.util.Date()) + "  " + what; }
        synchronized (EVENTS) {
            EVENTS.addLast(line);
            while (EVENTS.size() > 400) EVENTS.removeFirst();
        }
    }
    static JSONArray events() {
        JSONArray a = new JSONArray();
        synchronized (EVENTS) { for (String s : EVENTS) a.put(s); }
        return a;
    }

    // ------------------------------------------------------------------ the state of the TV
    /** a snapshot of counters, to compare the start and end of a section */
    static final class Snap {
        long at, gcCount, gcMs, mainCpu, renderCpu, procCpu;
    }
    static Snap snap() {
        Snap s = new Snap();
        s.at = SystemClock.elapsedRealtime();
        s.gcCount = stat("art.gc.gc-count");
        s.gcMs = stat("art.gc.gc-time");
        long[] t = threadCpu();
        s.mainCpu = t[0]; s.renderCpu = t[1]; s.procCpu = t[2];
        return s;
    }
    /** what happened between two snapshots, added to a section's results */
    static void delta(Snap a, Snap b, JSONObject o) {
        try {
            long ms = Math.max(1, b.at - a.at);
            double tick = 1000.0 / 100;   // /proc times are in 1/100 s
            o.put("gcCount", b.gcCount - a.gcCount);
            o.put("gcMs", b.gcMs - a.gcMs);
            o.put("mainCpuPct", Math.round((b.mainCpu - a.mainCpu) * tick * 100.0 / ms));
            o.put("renderCpuPct", Math.round((b.renderCpu - a.renderCpu) * tick * 100.0 / ms));
            o.put("appCpuPct", Math.round((b.procCpu - a.procCpu) * tick * 100.0 / ms));   // (all threads; 400 = all 4 cores)
        } catch (Exception ignored) { }
    }
    private static long stat(String k) { try { String v = Debug.getRuntimeStat(k); return v == null ? -1 : Long.parseLong(v); } catch (Throwable t) { return -1; } }

    /** {screen thread, drawing thread, whole app} processor time (1/100 s) */
    private static long[] threadCpu() {
        long main = 0, render = 0, proc = 0;
        try {
            File[] tasks = new File("/proc/self/task").listFiles();
            if (tasks != null) for (File t : tasks) {
                String st = read(new File(t, "stat"));
                if (st == null) continue;
                int a = st.indexOf('('), b = st.lastIndexOf(')');
                if (a < 0 || b < 0) continue;
                String name = st.substring(a + 1, b);
                String[] f = st.substring(b + 2).split(" ");
                long cpu = Long.parseLong(f[11]) + Long.parseLong(f[12]);    // utime + stime
                proc += cpu;
                if (t.getName().equals(String.valueOf(android.os.Process.myPid()))) main += cpu;
                else if (name.equals("RenderThread")) render += cpu;
            }
        } catch (Throwable ignored) { }
        return new long[] { main, render, proc };
    }
    private static String read(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[1024]; int n = in.read(b); return n > 0 ? new String(b, 0, n) : null;
        } catch (Throwable t) { return null; }
    }

    /** memory, graphics memory, heat, frame of mind of the TV right now */
    static JSONObject system(Activity act) {
        JSONObject o = new JSONObject();
        try {
            Debug.MemoryInfo mi = new Debug.MemoryInfo();
            Debug.getMemoryInfo(mi);
            o.put("appMB", Math.round(mi.getTotalPss() / 1024.0));
            String g = mi.getMemoryStat("summary.graphics"), j = mi.getMemoryStat("summary.java-heap"), n = mi.getMemoryStat("summary.native-heap");
            if (g != null) o.put("graphicsMB", Math.round(Long.parseLong(g) / 1024.0));
            if (j != null) o.put("javaMB", Math.round(Long.parseLong(j) / 1024.0));
            if (n != null) o.put("nativeMB", Math.round(Long.parseLong(n) / 1024.0));
            // every memory figure Android gives (graphics split into its parts, the page's share, ...)
            JSONObject all = new JSONObject();
            for (java.util.Map.Entry<String, String> e : mi.getMemoryStats().entrySet()) all.put(e.getKey(), Math.round(Long.parseLong(e.getValue()) / 1024.0));
            o.put("memoryMB", all);
            o.put("javaUsedMB", javaUsedMB());
            try {
                java.io.File d = act.getFilesDir();
                o.put("storageFreeMB", d.getUsableSpace() / 1048576); o.put("storageTotalMB", d.getTotalSpace() / 1048576);
                java.io.File[] ext = act.getExternalFilesDirs(null);
                org.json.JSONArray usb = new org.json.JSONArray();
                if (ext != null) for (int i = 1; i < ext.length; i++) if (ext[i] != null) usb.put(ext[i].getUsableSpace() / 1048576 + " MB free of " + ext[i].getTotalSpace() / 1048576);
                o.put("usb", usb);
                java.io.File ts = new java.io.File(act.getCacheDir(), "timeshift");
                long tsb = 0; java.io.File[] fs = ts.listFiles(); if (fs != null) for (java.io.File f : fs) tsb += f.length();
                o.put("timeshiftMB", tsb / 1048576);
            } catch (Throwable ignored) { }
            ActivityManager am = (ActivityManager) act.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(m);
            o.put("tvFreeMB", m.availMem / 1048576);
            o.put("tvTotalMB", m.totalMem / 1048576);
            o.put("tvLowMemory", m.lowMemory);
            // how the TV is connected (Wi-Fi / Ethernet, a VPN running, IPv6) — some sites treat these differently
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) act.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
                android.net.Network an = cm.getActiveNetwork();
                android.net.NetworkCapabilities nc = an == null ? null : cm.getNetworkCapabilities(an);
                android.net.LinkProperties lp = an == null ? null : cm.getLinkProperties(an);
                StringBuilder net = new StringBuilder();
                if (nc != null) {
                    if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) net.append("Wi-Fi ");
                    if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) net.append("Ethernet ");
                    if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) net.append("VPN on ");
                }
                boolean v6 = false, v4 = false;
                if (lp != null) for (android.net.LinkAddress la : lp.getLinkAddresses()) { java.net.InetAddress a = la.getAddress(); if (a instanceof java.net.Inet6Address && !a.isLinkLocalAddress()) v6 = true; if (a instanceof java.net.Inet4Address) v4 = true; }
                net.append(v4 ? "IPv4" : "").append(v4 && v6 ? "+" : "").append(v6 ? "IPv6" : "");
                o.put("network", net.toString().trim());
            } catch (Throwable ignored) { }
            o.put("gcCount", stat("art.gc.gc-count"));
            o.put("gcMs", stat("art.gc.gc-time"));
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.os.PowerManager pm = (android.os.PowerManager) act.getSystemService(Context.POWER_SERVICE);
                int th = pm.getCurrentThermalStatus();
                o.put("heat", th == 0 ? "normal" : th == 1 ? "light" : th == 2 ? "moderate (slowing down)" : th == 3 ? "severe (slowing down)" : "critical (" + th + ")");
                if (android.os.Build.VERSION.SDK_INT >= 30) o.put("heatHeadroom", Math.round(pm.getThermalHeadroom(10) * 100) / 100.0);
            }
            android.view.Display d = act.getWindowManager().getDefaultDisplay();
            o.put("displayHz", Math.round(d.getRefreshRate() * 10) / 10.0);
            android.view.Display.Mode md = d.getMode();
            o.put("displayMode", md.getPhysicalWidth() + "x" + md.getPhysicalHeight() + "@" + Math.round(md.getRefreshRate() * 100) / 100.0);
            o.put("uptimeMin", (SystemClock.elapsedRealtime() - T0) / 60000);
            o.put("cpuFreqMHz", cpuFreqs());
        } catch (Throwable t) { try { o.put("error", String.valueOf(t)); } catch (Exception ignored) { } }
        return o;
    }
    /** Java objects in use right now */
    private static long javaUsedMB() { return Runtime.getRuntime().totalMemory() / 1048576 - Runtime.getRuntime().freeMemory() / 1048576; }
    private static JSONArray cpuFreqs() {
        JSONArray a = new JSONArray();
        for (int i = 0; i < 8; i++) {
            String s = read(new File("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq"));
            if (s == null) break;
            try { a.put(Long.parseLong(s.trim()) / 1000); } catch (Exception e) { break; }
        }
        return a;
    }

    // ------------------------------------------------------------------ the app's own log
    /* An app may read its own lines of Android's log. Kept: warnings and errors, plus the lines about
       slow frames ("Davey!", "Skipped N frames"), the drawing engine and the video decoder. */
    static JSONArray log(int max) {
        JSONArray out = new JSONArray();
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[] { "logcat", "-d", "-v", "time", "-t", "3000", "--pid=" + android.os.Process.myPid() });
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            ArrayDeque<String> keep = new ArrayDeque<>();
            String line;
            while ((line = r.readLine()) != null) {
                boolean want = line.contains(" W/") || line.contains(" E/") || line.contains(" F/")
                        || line.contains("Davey") || line.contains("Skipped") || line.contains("OpenGLRenderer")
                        || line.contains("SurfaceView") || line.contains("MediaCodec") || line.contains("ExoPlayer")
                        || line.contains("BLASTBufferQueue") || line.contains("HWUI");
                if (!want) continue;
                line = scrub(line);
                if (line.length() > 300) line = line.substring(0, 300);
                keep.addLast(line);
                while (keep.size() > max) keep.removeFirst();
            }
            for (String s : keep) out.put(s);
        } catch (Throwable t) { out.put("log unavailable: " + t); }
        finally { if (p != null) try { p.destroy(); } catch (Throwable ignored) { } }
        return out;
    }

    /** everything above in one block (for the report, or a capture right after a glitch) */
    static JSONObject all(Activity act, String extraKey, Object extra) {
        JSONObject o = new JSONObject();
        try {
            o.put("system", system(act));
            if (act instanceof MainActivity) o.put("screen", ((MainActivity) act).screenState());
            o.put("events", events());
            o.put("log", log(250));
            if (extraKey != null) o.put(extraKey, extra);
        } catch (Exception ignored) { }
        return o;
    }
}
