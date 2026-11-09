package com.topshelf.launcher;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Everything the home-screen page can ask Android for. Exposed to JS as window.TopShelf. */
public class Bridge {

    static final String PREFS = "topshelf";
    static final String STOCK_LAUNCHER = "com.google.android.apps.tv.launcherx";

    private final MainActivity act;
    private final PackageManager pm;
    private final SharedPreferences prefs;
    /* (after the screen is closed the pool is shut down; anything still scheduled for it then — the guide
       warm-up, the rescue check — is dropped quietly instead of stopping the whole app) */
    private final ExecutorService io = new java.util.concurrent.ThreadPoolExecutor(2, 2, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.LinkedBlockingQueue<Runnable>(), new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
    private final Map<String, Integer> tintCache = new ConcurrentHashMap<>();
    private volatile String appsJson;
    volatile String browserUA;
    final LiveTv live;

    Bridge(MainActivity act) {
        this.act = act;
        this.pm = act.getPackageManager();
        this.prefs = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.live = new LiveTv(act);
    }

    void shutdown() {
        io.shutdownNow();
    }

    // ---- Updates & reports through GitHub (see Updater) ---------------------------
    private Updater upd;
    private Updater upd() { if (upd == null) upd = new Updater(act, prefs); return upd; }
    private int versionCode() {
        try { return (int) pm.getPackageInfo(act.getPackageName(), 0).getLongVersionCode(); } catch (Exception e) { return 0; }
    }

    @JavascriptInterface
    public String updInfo() {
        try {
            JSONObject o = new JSONObject();
            o.put("repo", upd().repo());
            o.put("token", !upd().token().isEmpty());
            o.put("tokenHint", upd().token().length() > 8 ? "…" + upd().token().substring(upd().token().length() - 4) : "");
            o.put("canInstall", upd().canInstall());
            o.put("code", versionCode());
            o.put("lastError", prefs.getString("updError", ""));
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    @JavascriptInterface
    public void updSetToken(String token, String repo) {
        upd().setToken(token);
        if (repo != null) upd().setRepo(repo);
    }

    @JavascriptInterface
    public void updCheck() {
        io.execute(new Runnable() { @Override public void run() {
            String r = upd().check(versionCode());
            act.js("window.tsUpdate && tsUpdate('check',0," + JSONObject.quote(r) + ")");
        } });
    }

    @JavascriptInterface
    public void updInstall(final String apk) {
        prefs.edit().remove("updError").apply();
        if (!upd().canInstall()) { upd().openInstallPermission(); act.js("window.tsUpdate && tsUpdate('permission',0,'')"); return; }
        prefs.edit().putLong("updStartedAt", System.currentTimeMillis()).apply();
        io.execute(new Runnable() { @Override public void run() {
            upd().install(apk == null || apk.isEmpty() ? "TopShelf.apk" : apk, new Updater.Progress() {
                @Override public void step(String state, int pct, String msg) {
                    act.js("window.tsUpdate && tsUpdate(" + JSONObject.quote(state) + "," + pct + "," + JSONObject.quote(msg == null ? "" : msg) + ")");
                }
            });
        } });
    }

    @JavascriptInterface
    public void updPermission() { upd().openInstallPermission(); }

    /* The page's script reports in once it's running. If it never does (a broken page would leave Settings and
       the updater dead), the TV looks for a newer TopShelf by itself and offers to install it. */
    volatile boolean pageAlive;
    @JavascriptInterface public void pageAlive() { pageAlive = true; }
    void rescueCheck() {
        if (pageAlive) return;
        Diag.ev("page: never started — checking for a fixed version");
        io.execute(new Runnable() { @Override public void run() {
            try {
                final JSONObject r = new JSONObject(upd().check(versionCode()));
                if (!r.optBoolean("newer")) return;
                act.runOnUiThread(new Runnable() { @Override public void run() {
                    NativeHome h = act.nativeHome();
                    if (h == null) return;
                    List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
                    l.add("Install TopShelf " + r.optString("name") + " now"); a.add(new Runnable() { @Override public void run() { rescueInstall(r.optString("apk")); } });
                    l.add("Later"); a.add(new Runnable() { @Override public void run() { } });
                    h.showMenu(l, a, null, 1f, "Part of TopShelf didn't start — a fixed version is ready");
                } });
            } catch (Exception ignored) { }
        } });
    }
    private void rescueInstall(final String apk) {
        if (!upd().canInstall()) { upd().openInstallPermission(); return; }
        prefs.edit().putLong("updStartedAt", System.currentTimeMillis()).apply();
        io.execute(new Runnable() { @Override public void run() {
            upd().install(apk == null || apk.isEmpty() ? "TopShelf.apk" : apk, new Updater.Progress() {
                @Override public void step(final String state, final int pct, final String msg) {
                    act.runOnUiThread(new Runnable() { @Override public void run() { NativeHome h = act.nativeHome(); if (h != null && msg != null && !msg.isEmpty()) h.toast(msg); } });
                }
            });
        } });
    }

    /** uploads a text file (a diagnostic report) to the repository; answers with tsUpdate('uploaded'|'uploadError') */
    @JavascriptInterface
    public void updUpload(final String path, final String text, final String message) {
        io.execute(new Runnable() { @Override public void run() {
            String e = upd().upload(path, text, message == null ? "report" : message);
            act.js("window.tsUpdate && tsUpdate(" + (e.isEmpty() ? "'uploaded'" : "'uploadError'") + ",0," + JSONObject.quote(e.isEmpty() ? path : e) + ")");
        } });
    }

    @JavascriptInterface
    public float getRenderScale() { return prefs.getFloat("renderScale", 1f); }

    @JavascriptInterface
    public void tryRenderScale(final float s) {
        final float prev = prefs.getFloat("renderScale", 1f);
        act.runOnUiThread(new Runnable() { @Override public void run() { act.tryRenderScale(s, prev); } });
    }

    @JavascriptInterface
    public void keepRenderScale() {
        act.runOnUiThread(new Runnable() { @Override public void run() { act.keepRenderScale(); } });
    }

    // ---- Native Live TV player (see NativePlayer) ---------------------------------

    /** Runs on the main thread and waits for the answer (ExoPlayer lives on the main thread). */
    private String onMain(final java.util.concurrent.Callable<String> f, long ms) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) { try { return f.call(); } catch (Throwable t) { return ""; } }
        final java.util.concurrent.atomic.AtomicReference<String> out = new java.util.concurrent.atomic.AtomicReference<>("");
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        act.runOnUiThread(new Runnable() { @Override public void run() {
            try { out.set(f.call()); } catch (Throwable t) { out.set(""); } finally { done.countDown(); }
        } });
        try { done.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
        return out.get();
    }
    private void nvRun(final Runnable r) {
        act.runOnUiThread(new Runnable() { @Override public void run() {
            try { NativePlayer p = act.nativePlayer(); if (p != null) r.run(); }
            catch (Throwable t) { act.nativeBroken(t); }
        } });
    }

    @JavascriptInterface
    public boolean nvOk() {
        if (prefs.getBoolean("nativeOff", false)) return false;
        try { Class.forName("androidx.media3.exoplayer.ExoPlayer"); return true; } catch (Throwable t) { return false; }
    }

    @JavascriptInterface
    public String nvInfo() {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("off", prefs.getBoolean("nativeOff", false));
            o.put("err", prefs.getString("nativeErr", ""));
            boolean lib; try { Class.forName("androidx.media3.exoplayer.ExoPlayer"); lib = true; } catch (Throwable t) { lib = false; }
            o.put("lib", lib);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    @JavascriptInterface
    public void nvSetOff(boolean off) { prefs.edit().putBoolean("nativeOff", off).apply(); if (off) nvStop(); }

    @JavascriptInterface
    public void nvPlay(final int id, final String url, final String ua, final String kind) {
        nvRun(new Runnable() { @Override public void run() { act.nativePlayer().play(id, url, ua, kind); } });
    }

    @JavascriptInterface
    public void nvStop() {
        nvRun(new Runnable() { @Override public void run() { act.nativePlayer().stop(); } });
    }

    /** rect in screen pixels; mode pv | fs | top | hide */
    @JavascriptInterface
    public void nvLayout(final String mode, final double x, final double y, final double w, final double h, final double r, final int anim) {
        nvRun(new Runnable() { @Override public void run() {
            act.nativePlayer().layout(mode, (int) Math.round(x), (int) Math.round(y), (int) Math.round(w), (int) Math.round(h), (float) r, anim);
        } });
    }

    @JavascriptInterface
    public void nvVolume(final double v) {
        nvRun(new Runnable() { @Override public void run() { act.nativePlayer().setVolume((float) v); } });
    }

    @JavascriptInterface
    public String nvTracks() {
        return onMain(new java.util.concurrent.Callable<String>() { @Override public String call() { NativePlayer p = act.nativePlayer(); return p == null ? "{}" : p.tracks(); } }, 600);
    }

    @JavascriptInterface
    public void nvSelect(final String type, final int g, final int t) {
        nvRun(new Runnable() { @Override public void run() { act.nativePlayer().select(type, g, t); } });
    }

    @JavascriptInterface
    public String nvStats() {
        return onMain(new java.util.concurrent.Callable<String>() { @Override public String call() { NativePlayer p = act.nativePlayer(); return p == null ? "{}" : p.stats(); } }, 600);
    }

    @JavascriptInterface
    public String nvSnapshot() {
        return onMain(new java.util.concurrent.Callable<String>() { @Override public String call() { NativePlayer p = act.nativePlayer(); return p == null ? "" : p.snapshot(); } }, 800);
    }

    void invalidateApps() {
        appsJson = null;
    }

    // ---- Apps --------------------------------------------------------------------

    /** JSON array: [{id, pkg, label, banner, tint}], TV (leanback) apps first, then phone-style apps. */
    @JavascriptInterface
    public String getApps() {
        String cached = appsJson;
        if (cached != null) return cached;
        Map<String, JSONObject> byPkg = new LinkedHashMap<>();
        try {
            collect(byPkg, Intent.CATEGORY_LEANBACK_LAUNCHER);
            collect(byPkg, Intent.CATEGORY_LAUNCHER);
        } catch (Exception ignored) { }
        JSONArray out = new JSONArray();
        for (JSONObject o : byPkg.values()) out.put(o);
        appsJson = out.toString();
        return appsJson;
    }

    private void collect(Map<String, JSONObject> byPkg, String category) throws Exception {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
        for (ResolveInfo ri : list) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || act.getPackageName().equals(ai.packageName)) continue;
            if (byPkg.containsKey(ai.packageName)) continue;
            ComponentName cn = new ComponentName(ai.packageName, ai.name);
            JSONObject o = new JSONObject();
            o.put("id", cn.flattenToString());
            o.put("pkg", ai.packageName);
            o.put("label", String.valueOf(ri.loadLabel(pm)).trim());
            boolean hasBanner = ai.banner != 0 || ai.applicationInfo.banner != 0;
            o.put("banner", hasBanner);
            o.put("tint", hex(tintFor(ai)));   // also used by custom icon looks
            byPkg.put(ai.packageName, o);
        }
    }

    /** A darkened average colour of the app's icon, used behind apps that have no TV banner. */
    private int tintFor(ActivityInfo ai) {
        Integer c = tintCache.get(ai.packageName);
        if (c != null) return c;
        int color = 0xFF2A2A2E;
        try {
            Drawable d = ai.loadIcon(pm);
            Bitmap b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            d.setBounds(0, 0, 16, 16);
            d.draw(cv);
            long r = 0, g = 0, bl = 0, n = 0;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int p = b.getPixel(x, y);
                    if ((p >>> 24) < 128) continue;
                    r += (p >> 16) & 255; g += (p >> 8) & 255; bl += p & 255; n++;
                }
            }
            b.recycle();
            if (n > 0) {
                float k = 0.55f; // darken so white icons still read
                color = 0xFF000000 | ((int) (r / n * k) << 16) | ((int) (g / n * k) << 8) | (int) (bl / n * k);
            }
        } catch (Exception ignored) { }
        tintCache.put(ai.packageName, color);
        return color;
    }

    private static String hex(int c) {
        return String.format(Locale.US, "#%06x", c & 0xFFFFFF);
    }

    /** PNG bytes for an app's banner (b=true) or icon, with a small disk cache. */
    byte[] iconPng(String id, boolean banner) {
        ComponentName cn = ComponentName.unflattenFromString(id);
        if (cn == null) return null;
        try {
            long ver = 0;
            try {
                PackageInfo pi = pm.getPackageInfo(cn.getPackageName(), 0);
                ver = pi.lastUpdateTime;
            } catch (Exception ignored) { }
            File dir = new File(act.getCacheDir(), "icons");
            if (!dir.exists()) dir.mkdirs();
            String key = cn.flattenToShortString().replaceAll("[^A-Za-z0-9._-]", "_") + "_" + ver + (banner ? "_b" : "_i") + ".png";
            File f = new File(dir, key);
            if (f.exists()) return Files.readAllBytes(f.toPath());

            ActivityInfo ai = pm.getActivityInfo(cn, 0);
            Drawable d = banner ? ai.loadBanner(pm) : ai.loadIcon(pm);
            if (d == null) d = ai.loadIcon(pm);
            int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
            if (w <= 0 || h <= 0) { w = 192; h = banner ? 108 : 192; }
            int max = banner ? 640 : 256;
            if (w > max) { h = Math.round(h * (max / (float) w)); w = max; }
            if (h > max) { w = Math.round(w * (max / (float) h)); h = max; }
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            d.setBounds(0, 0, w, h);
            d.draw(cv);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bos);
            b.recycle();
            byte[] bytes = bos.toByteArray();
            try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(bytes); } catch (Exception ignored) { }
            return bytes;
        } catch (Exception e) {
            return null;
        }
    }

    @JavascriptInterface
    public boolean launch(String id) {
        ComponentName cn = ComponentName.unflattenFromString(id);
        if (cn == null) return false;
        Intent i = pm.getLeanbackLaunchIntentForPackage(cn.getPackageName());
        if (i == null) i = pm.getLaunchIntentForPackage(cn.getPackageName());
        if (i == null) i = new Intent(Intent.ACTION_MAIN).setComponent(cn);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return start(i);
    }

    @JavascriptInterface
    public void appInfo(String id) {
        ComponentName cn = ComponentName.unflattenFromString(id);
        if (cn == null) return;
        start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + cn.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @JavascriptInterface
    public void openSettings() {
        start(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @JavascriptInterface
    public void openAccessibilitySettings() {
        start(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Opens Google's own home screen once, without the redirect bouncing it straight back. */
    @JavascriptInterface
    public boolean openStockHome() {
        prefs.edit().putLong("bypassUntil", System.currentTimeMillis() + 120_000).apply();
        final Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(STOCK_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        act.runOnMain(new Runnable() {
            @Override public void run() {
                try {
                    act.startActivity(i);
                } catch (Exception e) {
                    act.js("window.tsToast && tsToast('Google TV home is disabled on this device')");
                }
            }
        });
        return true;
    }

    /** Starts an activity on the main thread; failures show a toast in the page. */
    private boolean start(final Intent i) {
        act.runOnMain(new Runnable() {
            @Override public void run() {
                try {
                    act.startActivity(i);
                } catch (ActivityNotFoundException | SecurityException e) {
                    act.js("window.tsToast && tsToast('Couldn\\'t open that')");
                }
            }
        });
        return true;
    }

    // ---- Home-button redirect status -----------------------------------------------

    /** "on" (service enabled + redirect on), "paused" (service on, toggle off), "off", or "default" (we are the real home app). */
    @JavascriptInterface
    public String homeStatus() {
        try {
            Intent h = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = pm.resolveActivity(h, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null && act.getPackageName().equals(ri.activityInfo.packageName)) {
                return "default";
            }
        } catch (Exception ignored) { }
        String enabled = Settings.Secure.getString(act.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean svc = enabled != null && enabled.contains(act.getPackageName() + "/");
        if (!svc && !(HomeWatchService.wanted(act) && HomeWatchService.hasUsageAccess(act) && HomeWatchService.hasOverlay(act))) return "off";
        return prefs.getBoolean("redirect", true) ? "on" : "paused";
    }

    /** how Home reaches TopShelf: "button" (the accessibility service answers the Home button itself),
     *  "watcher" (Google's home screen is noticed and swapped out — it shows for a moment), or "" */
    @JavascriptInterface
    public String homeMode() {
        String enabled = Settings.Secure.getString(act.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled != null && enabled.contains(act.getPackageName() + "/")) return "button";
        if (HomeWatchService.wanted(act) && HomeWatchService.hasUsageAccess(act) && HomeWatchService.hasOverlay(act)) return "watcher";
        return "";
    }

    /**
     * Android 13+ blocks accessibility services of apps installed from a file ("restricted
     * settings"): the switch in Accessibility flips straight back off. JSON:
     * {restricted, source, pkg, svc, ip}
     */
    @JavascriptInterface
    public String homeBlock() {
        JSONObject o = new JSONObject();
        try {
            String pkg = act.getPackageName();
            o.put("pkg", pkg);
            o.put("svc", pkg + "/" + HomeRedirectService.class.getName());
            o.put("ip", LinkServer.localIp());
            o.put("sdk", android.os.Build.VERSION.SDK_INT);
            boolean restricted = false;
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                try {
                    android.app.AppOpsManager ao = (android.app.AppOpsManager) act.getSystemService(Context.APP_OPS_SERVICE);
                    int m = ao.unsafeCheckOpNoThrow("android:access_restricted_settings", android.os.Process.myUid(), pkg);
                    restricted = m == android.app.AppOpsManager.MODE_ERRORED || m == android.app.AppOpsManager.MODE_IGNORED;
                    o.put("op", m);
                } catch (Throwable ignored) { }
                try {
                    int src = pm.getInstallSourceInfo(pkg).getPackageSource();
                    o.put("source", src);   // 3 = local file, 4 = downloaded file
                    if (!o.has("op") && (src == 3 || src == 4)) restricted = true;
                } catch (Throwable ignored) { }
            }
            o.put("restricted", restricted);
        } catch (Exception ignored) { }
        return o.toString();
    }

    /** The no-computer Home button: {usage, overlay, on, running} */
    @JavascriptInterface
    public String homeWatch() {
        try {
            return new JSONObject().put("usage", HomeWatchService.hasUsageAccess(act)).put("overlay", HomeWatchService.hasOverlay(act))
                    .put("on", HomeWatchService.wanted(act)).put("running", HomeWatchService.isRunning())
                    .put("acc", HomeWatchService.accessibilityOn(act)).put("log", new JSONObject(HomeWatchService.status())).toString();
        } catch (Exception e) { return "{}"; }
    }
    /** Settings › System › Run a self-check (see SelfCheck) */
    @JavascriptInterface public void selfCheck() { act.runOnUiThread(new Runnable() { @Override public void run() { SelfCheck.start(act, "asked for in Settings"); } }); }
    /** Settings › Keep TopShelf ready (see ReadyService) */
    @JavascriptInterface public boolean keepReady() { return ReadyService.wanted(act); }
    @JavascriptInterface public void keepReadySet(boolean on) {
        prefs.edit().putBoolean("keepReady", on).apply();
        if (on) ReadyService.startIfWanted(act); else ReadyService.stop(act);
    }
    @JavascriptInterface
    public void homeWatchSet(boolean on) {
        prefs.edit().putBoolean("homewatch", on).putBoolean("redirect", true).commit();
        if (on) HomeWatchService.startIfWanted(act); else HomeWatchService.stop(act);
    }
    /** which = "usage" | "overlay": opens that permission's settings screen */
    @JavascriptInterface
    public void homeWatchGrant(String which) {
        String pkg = act.getPackageName();
        Intent[] tries = "overlay".equals(which)
            ? new Intent[] { new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + pkg)),
                             new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                             new Intent("android.settings.MANAGE_SPECIAL_APP_ACCESSES"), new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)) }
            : new Intent[] { new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + pkg)),
                             new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
                             new Intent("android.settings.MANAGE_SPECIAL_APP_ACCESSES"), new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)) };
        for (Intent i : tries) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { act.startActivity(i); return; } catch (Exception ignored) { }
        }
        start(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @JavascriptInterface
    public void openAppInfo() {
        start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + act.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @JavascriptInterface
    public void setRedirect(boolean on) {
        prefs.edit().putBoolean("redirect", on).apply();
    }

    // ---- Preferences ---------------------------------------------------------------

    @JavascriptInterface
    public String getPref(String key) {
        return prefs.getString("ui." + key, null);
    }

    @JavascriptInterface
    public void setPref(String key, String value) {
        prefs.edit().putString("ui." + key, value).apply();
    }

    @JavascriptInterface
    public String getVersion() {
        try {
            return pm.getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "dev";
        }
    }

    // ---- Reddit backgrounds -----------------------------------------------------------

    /** Cached photo list from the last successful fetch for this subreddit/sort, or null. */
    @JavascriptInterface
    public String getCachedPhotos(String sub, String sort) {
        return prefs.getString("photos." + sub.toLowerCase(Locale.US) + "." + sort, null);
    }

    interface PhotosCb { void done(String json, String err); }

    /** The same fetch for the native Home (answers on a worker or the main thread). */
    /** A short answer from Reddit (it sometimes hands back only a post or two) doesn't replace the
     *  photos you already had: the new ones go first and the rest are kept, up to 100. */
    static String mergePhotos(String fresh, String old) {
        try {
            org.json.JSONArray f = new org.json.JSONArray(fresh);
            if (old == null || f.length() >= 20) return fresh;
            org.json.JSONArray o = new org.json.JSONArray(old), out = new org.json.JSONArray();
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (org.json.JSONArray a : new org.json.JSONArray[] { f, o })
                for (int i = 0; i < a.length() && out.length() < 100; i++) {
                    org.json.JSONObject x = a.optJSONObject(i);
                    if (x != null && seen.add(x.optString("img"))) out.put(x);
                }
            return out.toString();
        } catch (Exception e) { return fresh; }
    }

    void fetchPhotosFor(final String sub, final String sort, final PhotosCb cb) {
        io.execute(new Runnable() { @Override public void run() { photosNow(sub, sort, cb); } });
    }
    /** what to add after "N photos" in Settings (e.g. Reddit refused, so these are from Commons) */
    static volatile String photoNote = "";
    @JavascriptInterface public String photoNote() { return photoNote; }
    @JavascriptInterface public boolean redditSignedIn() { return RedditLogin.signedIn(); }
    @JavascriptInterface public void redditSignIn() { act.runOnMain(new Runnable() { @Override public void run() { act.openRedditLogin(); } }); }
    @JavascriptInterface public void redditSignOut() { RedditLogin.signOut(); Diag.ev("reddit: signed out"); }
    private void keepPhotos(String sub, String sort, String json) {
        prefs.edit().putString("photos." + sub.toLowerCase(Locale.US) + "." + sort, json).apply();
    }
    /* Photos for a subreddit or another source (on a worker thread). Reddit shut its open feeds in 2026:
       when it refuses (directly and through the hidden browser), Wikimedia Commons' landscapes stand in. */
    private void photosNow(final String sub, final String sort, final PhotosCb cb) {
        final String key = "photos." + sub.toLowerCase(Locale.US) + "." + sort;
        if (PhotoSources.is(sub)) {
            try {
                String j = PhotoSources.fetch(sub, sort, getVersion());
                j = mergePhotos(j, prefs.getString(key, null));
                keepPhotos(sub, sort, j); photoNote = ""; cb.done(j, null);
            } catch (Exception e) { Diag.ev("photos: " + sub + " failed: " + e.getMessage()); cb.done(null, PhotoSources.label(sub) + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())); }
            return;
        }
        String json = null, err = null;
        try { json = Reddit.fetch(sub, sort, getVersion(), browserUA, RedditLogin.cookies()); }
        catch (Exception e) { err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
        if (json != null) { json = mergePhotos(json, prefs.getString(key, null)); keepPhotos(sub, sort, json); photoNote = ""; cb.done(json, null); return; }
        final String directErr = err;
        act.runOnMain(new Runnable() { @Override public void run() {
            try {
                new RedditWebLoader(act, sub, sort, new RedditWebLoader.Callback() {
                    @Override public void done(final String j, final String e2) {
                        if (j != null) { String m = mergePhotos(j, prefs.getString(key, null)); keepPhotos(sub, sort, m); photoNote = ""; cb.done(m, null); return; }
                        io.execute(new Runnable() { @Override public void run() { standIn(sub, sort, directErr + "; " + e2, cb); } });
                    }
                });
            } catch (final Exception e) { io.execute(new Runnable() { @Override public void run() { standIn(sub, sort, directErr + "; browser: " + e.getMessage(), cb); } }); }
        } });
    }
    private void standIn(String sub, String sort, String redditErr, PhotosCb cb) {
        Diag.ev("photos: Reddit refused r/" + sub + " (" + redditErr + ") — using Wikimedia Commons");
        String cached = prefs.getString("photos.@landscapes." + sort, null);
        try {
            String j = mergePhotos(PhotoSources.fetch("@landscapes", sort, getVersion()), cached);
            keepPhotos("@landscapes", sort, j);
            photoNote = " · Reddit refused r/" + sub + ", so these are from Wikimedia Commons" + (RedditLogin.signedIn() ? "" : " (signing in under Settings › Reddit account should fix it)");
            cb.done(j, null);
        } catch (Exception e) {
            if (cached != null) { photoNote = " · Reddit refused r/" + sub + " (saved Commons photos)"; cb.done(cached, null); return; }
            Diag.ev("photos: Wikimedia Commons failed too: " + e.getMessage());
            cb.done(null, "Reddit: " + redditErr + "; Wikimedia Commons: " + e.getMessage());
        }
    }

    // ---- Native Home (see NativeHome) ------------------------------------------------
    private void nh(final Runnable r) {
        act.runOnUiThread(new Runnable() { @Override public void run() {
            try { NativeHome h = act.nativeHome(); if (h != null) r.run(); } catch (Throwable t) { act.nativeHomeBroken(t); }
        } });
    }
    @JavascriptInterface public boolean nhAvailable() { return act.nativeHomeWanted(); }
    /** native | overlay | web — who draws Home right now */
    /** a video is playing in the app (or was a moment ago): the page's screensaver stays away */
    @JavascriptInterface public boolean videoBusy() { return MainActivity.videoRecently(); }
    @JavascriptInterface public void nhState(final String state) { nh(new Runnable() { @Override public void run() { act.setHomeOwner(state); } }); }
    /** where TopShelf is, for the score ticker and alerts: away, home, live, quiet ("" without the app's Home) */
    @JavascriptInterface public String nhWhere() { try { NativeHome h = act.nativeHome(); return h == null ? "" : h.tickerWhere(); } catch (Throwable t) { return ""; } }
    @JavascriptInterface public void nhTickerFlash(final double ms) { nh(new Runnable() { @Override public void run() { act.nativeHome().tickerFlash((long) ms); } }); }
    @JavascriptInterface public void nhToast(final String msg) { nh(new Runnable() { @Override public void run() { act.nativeHome().toast(msg); } }); }
    @JavascriptInterface public String driveStatus() { return act.drive == null ? "{}" : act.drive.status().toString(); }
    @JavascriptInterface public void driveSetup() { nh(new Runnable() { @Override public void run() { act.nativeHome().setupShow("drive"); } }); }
    @JavascriptInterface public String driveSetupUrl() { return act.drive == null ? "" : act.drive.setupUrl(); }
    @JavascriptInterface public void driveToggle(String what) { if (act.drive != null) act.drive.toggle(what); }
    @JavascriptInterface public void driveDisconnect() { if (act.drive != null) act.drive.disconnect(); }

    /** recordings, from the phone app: list / add / rule / unrule / cancel / delete / play */
    @JavascriptInterface public String recCmd(String json) {
        try {
            JSONObject c = new JSONObject(json), out = new JSONObject();
            Recorder r = Recorder.get(act);
            String op = c.optString("op");
            if (op.equals("add") || op.equals("rule")) {
                JSONObject ch = new JSONObject();
                for (String k : new String[] { "n", "u", "ua", "k", "l" }) ch.put(k, c.optString(k));
                if (ch.optString("u").isEmpty()) throw new Exception("no channel");
                JSONObject x = op.equals("add")
                        ? r.add(ch, c.optLong("s"), c.optLong("e"), c.optString("title"), c.optString("desc"), "", null)
                        : r.addRule(ch, c.optString("title"), c.optString("mode", "new"));
                if (x == null) throw new Exception("couldn't set it");
                out.put("ok", true).put("rec", x);
            } else if (op.equals("cancel")) { r.cancel(c.optString("id")); out.put("ok", true); }
            else if (op.equals("delete")) { r.delete(c.optString("id")); out.put("ok", true); }
            else if (op.equals("unrule")) { r.removeRule(c.optString("id")); out.put("ok", true); }
            else if (op.equals("play")) {
                final String id = c.optString("id");
                act.bringToFront();
                nh(new Runnable() { @Override public void run() { act.nativeHome().livePlayRecording(id); } });
                out.put("ok", true);
            }
            JSONArray recs = new JSONArray(); for (JSONObject x : r.list()) { x.remove("dir"); x.remove("ua"); recs.put(x); }
            JSONArray rules = new JSONArray(); for (JSONObject x : r.rules()) { x.remove("eps"); x.remove("ua"); rules.put(x); }
            out.put("recs", recs).put("rules", rules).put("storage", r.storageLabel());
            if (act.drive != null) out.put("drive", act.drive.status());
            return out.toString();
        } catch (Exception e) { return "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}"; }
    }

    /** recordings: where they go and how many there are (Settings) */
    @JavascriptInterface public String recInfo() {
        try {
            Recorder r = Recorder.get(act);
            JSONObject o = new JSONObject();
            o.put("storage", r.storageLabel());
            int done = 0, next = 0, now = 0;
            for (JSONObject x : r.list()) { String st = x.optString("status"); if (st.equals("done")) done++; else if (st.equals("scheduled")) next++; else if (st.equals("recording")) now++; }
            o.put("done", done); o.put("next", next); o.put("now", now); o.put("series", r.rules().size());
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }
    @JavascriptInterface public void nhReload(final String what) { nh(new Runnable() { @Override public void run() { act.nativeHome().reload(what); } }); }
    // (the last of each, for a Home made again when a phone turns: the page sends them only when they change)
    private volatile String lastShelf, lastWeather;
    @JavascriptInterface public void nhShelf(final String json) { lastShelf = json; nh(new Runnable() { @Override public void run() { act.nativeHome().setShelf(json); } }); }
    @JavascriptInterface public void nhWeather(final String text) { lastWeather = text; nh(new Runnable() { @Override public void run() { act.nativeHome().setWeather(text); } }); }
    void reapply(NativeHome h) {
        try { if (lastShelf != null) h.setShelf(lastShelf); } catch (Throwable ignored) { }
        try { if (lastWeather != null) h.setWeather(lastWeather); } catch (Throwable ignored) { }
    }
    @JavascriptInterface public void nhLink(final boolean on) { nh(new Runnable() { @Override public void run() { act.nativeHome().setLink(on); } }); }
    @JavascriptInterface public void nhMeasureStart() { nh(new Runnable() { @Override public void run() { act.nativeHome().measureStart(); } }); }
    @JavascriptInterface public String nhMeasureStop() {
        return onMain(new java.util.concurrent.Callable<String>() { @Override public String call() { NativeHome h = act.nativeHome(); return h == null ? "{}" : h.measureStop(); } }, 600);
    }
    @JavascriptInterface public void nhKey(final int code) { nh(new Runnable() { @Override public void run() { act.nativeHome().injectKey(code); } }); }
    @JavascriptInterface public void nhKeys(final String codes, final int gap) { nh(new Runnable() { @Override public void run() { act.nativeHome().injectKeys(codes, gap); } }); }
    @JavascriptInterface public void nhScript(final String script, final String id) { nh(new Runnable() { @Override public void run() { act.nativeHome().runScript(script, id); } }); }
    /** the page's own tab bar chose Home, Movies or TV Shows: the native Home shows it */
    @JavascriptInterface public boolean nhLiveOk() {
        String r = onMain(new java.util.concurrent.Callable<String>() { @Override public String call() { NativeHome h = act.nativeHome(); return h != null && h.liveNativeOk() ? "1" : "0"; } }, 3000);
        if (!r.isEmpty()) return r.equals("1");
        /* (the screen was too busy starting up to answer: it used to count as "no", and Live TV fell back to the
           page's for the whole session — the settings and the player's record decide instead) */
        String engine = "";
        try { engine = new JSONObject(prefs.getString("ui.settings", "{}")).optString("liveEngine"); } catch (Exception ignored) { }
        return !"web".equals(engine) && !prefs.getBoolean("nativeOff", false) && act.nativeHomeWanted();
    }
    @JavascriptInterface public void nhLivePlay(final String u) { nh(new Runnable() { @Override public void run() { act.nativeHome().livePlayUrl(u); } }); }
    @JavascriptInterface public void nhTabTop(final String id) { nh(new Runnable() { @Override public void run() { act.nativeHome().showTabTop(id); } }); }
    @JavascriptInterface public void nhSheet(final String json) { nh(new Runnable() { @Override public void run() { act.nativeHome().sheetShow(json); } }); }
    @JavascriptInterface public void nhFind(final String json) { nh(new Runnable() { @Override public void run() { act.nativeHome().findShow(json); } }); }
    // Search is the app's own: the page (the phone's keyboard, the mic's words) hands it what to look for
    @JavascriptInterface public void nhSearchOpen(final String q) { nh(new Runnable() { @Override public void run() { act.nativeHome().searchOpen(q); } }); }
    @JavascriptInterface public void nhSearchQuery(final String q, final boolean submit) { nh(new Runnable() { @Override public void run() { act.nativeHome().searchQuery(q, submit); } }); }
    @JavascriptInterface public void nhSearchVoice() { nh(new Runnable() { @Override public void run() { act.nativeHome().searchVoice(); } }); }
    @JavascriptInterface public void nhFindClose() { nh(new Runnable() { @Override public void run() { act.nativeHome().findClose(); } }); }
    @JavascriptInterface public String ringStatus() { return act.ring == null ? "{}" : act.ring.status().toString(); }
    @JavascriptInterface public void ringSetup() { nh(new Runnable() { @Override public void run() { act.nativeHome().setupShow("ring"); } }); }
    @JavascriptInterface public void ringDisconnect() { if (act.ring != null) act.ring.disconnect(); }
    @JavascriptInterface public void ringTest() { nh(new Runnable() { @Override public void run() { String id = act.ring == null ? "" : act.ring.idFor("Front Door"); act.nativeHome().doorbell("Front Door", "", "test-" + System.currentTimeMillis(), id); } }); }
    @JavascriptInterface public int cwCount() { try { return Progress.continueCount(act); } catch (Throwable t) { return 0; } }
    @JavascriptInterface public void cwClear() { try { Progress.hideAll(act); } catch (Throwable ignored) { } }
    @JavascriptInterface public void nhLiveGroups() { nh(new Runnable() { @Override public void run() { act.nativeHome().liveChanged(true); } }); }
    /** something is playing (or was a moment ago): the page doesn't pop messages up over it */
    @JavascriptInterface public boolean isWatching() { return MainActivity.videoRecently(); }
    @JavascriptInterface public void nhDialog(final String json) { nh(new Runnable() { @Override public void run() { act.nativeHome().dialogShow(json); } }); }
    @JavascriptInterface public void nhDialogClose() { nh(new Runnable() { @Override public void run() { act.nativeHome().dialogClose(); } }); }
    @JavascriptInterface public void nhHud(final String t) { nh(new Runnable() { @Override public void run() { act.nativeHome().setHud(t); } }); }
    @JavascriptInterface public void nhSheetClose() { nh(new Runnable() { @Override public void run() { act.nativeHome().sheetClose(); } }); }
    // Yahoo Fantasy (Settings › Sports)
    /** your fantasy team as Sports shows it (the page's Sports on a phone asks the TV for this) */
    @JavascriptInterface public String fantasyRow() { try { return act.fantasy == null ? "[]" : act.fantasy.sportsRow().toString(); } catch (Throwable t) { return "[]"; } }
    @JavascriptInterface public String fantasyStatus() { return act.fantasy == null ? "{}" : act.fantasy.status().toString(); }
    /** from the phone app (through the link): the setup page's address; the TV shows its setup screen too */
    @JavascriptInterface public String fantasySetupUrl() {
        if (act.fantasy == null) return "";
        String u = act.fantasy.setupUrl();
        if (!u.isEmpty()) nh(new Runnable() { @Override public void run() { act.nativeHome().fantasySetupShow(); } });
        return u;
    }
    /** a web page in the phone's (or TV's) browser */
    @JavascriptInterface public void openWeb(String url) {
        try { act.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { act.js("window.toast && toast('No web browser to open the page')"); }
    }
    volatile String watchingGame = "";
    /** the game on the channel being watched full screen (the page doesn't pop up news about the game you're watching) */
    @JavascriptInterface public String liveWatchingGame() {
        // asked fresh (a moment at most), so an alert right after you go full screen or change channel knows
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        act.runOnUiThread(new Runnable() { @Override public void run() {
            try { NativeHome h = act.nativeHome(); watchingGame = h == null ? "" : h.liveWatchingGame(); } finally { done.countDown(); }
        } });
        try { done.await(400, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
        return watchingGame;
    }
    // ---- the assistant (Assistant): the key is kept here only, never handed back to the page
    @JavascriptInterface public String aiInfo() {
        try { return new JSONObject().put("key", !Assistant.key(act).isEmpty()).put("voice", prefs.getBoolean("aiVoice", true)).put("speak", prefs.getBoolean("aiSpeak", true)).put("star", prefs.getBoolean("aiStar", true)).put("model", prefs.getString("aiModel", "sonnet")).put("voiceName", Assistant.voiceLabel(act)).put("rate", Assistant.rateLabel(act)).toString(); } catch (Exception e) { return "{}"; }
    }
    @JavascriptInterface public boolean aiSetKey(String k) {
        k = k == null ? "" : k.trim();
        if (!k.isEmpty() && !k.startsWith("sk-ant-")) return false;
        prefs.edit().putString("aiKey", k).apply();
        Diag.ev("assistant: key " + (k.isEmpty() ? "removed" : "added"));
        return true;
    }
    @JavascriptInterface public void aiSetVoice(boolean on) { prefs.edit().putBoolean("aiVoice", on).apply(); }
    @JavascriptInterface public void aiSetModel(String m) { prefs.edit().putString("aiModel", m == null ? "sonnet" : m).apply(); }
    @JavascriptInterface public void aiNextVoice() { act.runOnUiThread(new Runnable() { @Override public void run() { Assistant.nextVoice(act); } }); }
    @JavascriptInterface public void aiNextRate() { act.runOnUiThread(new Runnable() { @Override public void run() { Assistant.nextRate(act); } }); }
    @JavascriptInterface public void aiSetStar(boolean on) { prefs.edit().putBoolean("aiStar", on).apply(); }
    @JavascriptInterface public void aiSetSpeak(boolean on) { prefs.edit().putBoolean("aiSpeak", on).apply(); if (!on) Assistant.hush(); }
    @JavascriptInterface public void aiCatchUp() { nh(new Runnable() { @Override public void run() { Assistant.catchUp(act.nativeHome()); } }); }
    @JavascriptInterface public String traktStatus() { return act.trakt == null ? "{}" : act.trakt.status().toString(); }
    @JavascriptInterface public void traktSetup() { nh(new Runnable() { @Override public void run() { act.nativeHome().setupShow("trakt"); } }); }
    @JavascriptInterface public String traktSetupUrl() {
        if (act.trakt == null) return "";
        String u = act.trakt.setupUrl();
        if (!u.isEmpty()) nh(new Runnable() { @Override public void run() { act.nativeHome().setupShow("trakt"); } });
        return u;
    }
    @JavascriptInterface public void traktDisconnect() { if (act.trakt != null) act.trakt.disconnect(); }
    @JavascriptInterface public void traktSync() { if (act.trakt != null) act.trakt.sync(true); }
    @JavascriptInterface public void addonsOpen() { nh(new Runnable() { @Override public void run() { act.nativeHome().addonsOpen(); } }); }
    @JavascriptInterface public String addonsInfo() {
        try { Addons a = Addons.get(act); JSONArray l = a.list(); int on = 0; for (int i = 0; i < l.length(); i++) if (l.optJSONObject(i).optBoolean("on", true)) on++;
            return new JSONObject().put("count", l.length()).put("on", on).put("streams", a.anyStreams()).toString(); } catch (Exception e) { return "{}"; }
    }
    @JavascriptInterface public String addonsPhoneUrl() {
        if (act.link == null || act.link.port() == 0 || LinkServer.localIp().isEmpty()) return "";
        nh(new Runnable() { @Override public void run() { act.nativeHome().addonsOpen(); } });
        return "http://" + LinkServer.localIp() + ":" + act.link.port() + "/addons?k=" + Addons.get(act).newSetupKey();
    }
    @JavascriptInterface public String fantasyScoring() { return act.fantasy == null ? "half" : act.fantasy.scoring(); }
    @JavascriptInterface public void fantasyScoringNext() { if (act.fantasy != null) act.fantasy.cycleScoring(); }
    @JavascriptInterface public void fantasyPlayers() { nh(new Runnable() { @Override public void run() { act.nativeHome().setupShow("players"); } }); }
    @JavascriptInterface public String fantasyPlayersUrl() {
        if (act.fantasy == null) return "";
        String u = act.fantasy.playersUrl();
        if (!u.isEmpty()) nh(new Runnable() { @Override public void run() { act.nativeHome().setupShow("players"); } });
        return u;
    }
    @JavascriptInterface public void fantasyClearPlayers() { if (act.fantasy != null) for (int i = act.fantasy.mine().length() - 1; i >= 0; i--) act.fantasy.removeMine(act.fantasy.mine().optJSONObject(0).optString("id")); }
    @JavascriptInterface public void fantasySetup() { nh(new Runnable() { @Override public void run() { act.nativeHome().fantasySetupShow(); } }); }
    @JavascriptInterface public void fantasyToggle(String kind) { if (act.fantasy != null) act.fantasy.toggle(kind); }
    @JavascriptInterface public void fantasyDisconnect() { if (act.fantasy != null) act.fantasy.disconnect(); }
    @JavascriptInterface public void fantasyRefresh() { act.runOnUiThread(new Runnable() { @Override public void run() { if (act.fantasy != null) act.fantasy.kick(); } }); }
    @JavascriptInterface public void fantasyWatch(final String req, final String u) { nh(new Runnable() { @Override public void run() { act.nativeHome().fantasyWatch(req, u); } }); }
    @JavascriptInterface public void nhStatsGame(final String req, final String json) { nh(new Runnable() { @Override public void run() { act.nativeHome().liveStatsGame(req, json); } }); }
    @JavascriptInterface public void nhSportsPrefs() { nh(new Runnable() { @Override public void run() { act.nativeHome().sportsPrefsChanged(); } }); }
    @JavascriptInterface public void nhSports(final String json) { nh(new Runnable() { @Override public void run() { act.nativeHome().setSports(json); } }); }
    /** a game alert (starting soon, a score) shown as the app's sports notification at the top middle */
    @JavascriptInterface public void nhGameAlert(final String json) { nh(new Runnable() { @Override public void run() { act.nativeHome().gameAlert(json); } }); }
    @JavascriptInterface public void nhMenu(final String json) { nh(new Runnable() { @Override public void run() { act.nativeHome().pageMenu(json); } }); }
    @JavascriptInterface public void nhTab(final String id) { nh(new Runnable() { @Override public void run() { act.nativeHome().showTab(id); } }); }
    @JavascriptInterface public void nhTest(final String flags) { nh(new Runnable() { @Override public void run() { act.nativeHome().setTest(flags); } }); }
    @JavascriptInterface public String nhPhotoStatus() {
        return onMain(new java.util.concurrent.Callable<String>() { @Override public String call() { NativeHome h = act.nativeHome(); return h == null ? "" : h.photoStatus(); } }, 300);
    }
    @JavascriptInterface public void nhSetOff(final boolean off) {
        prefs.edit().putBoolean("nativeHomeOff", off).commit();
        if (off) act.runOnUiThread(new Runnable() { @Override public void run() { act.removeNativeHome(); } });
    }
    /** a phone held upright: the TV's screens (true) or the page's upright layout */
    @JavascriptInterface public void nhUpright(final boolean on) {
        prefs.edit().putBoolean("uprightNative", on).commit();
        act.runOnUiThread(new Runnable() { @Override public void run() { act.recreate(); } });
    }
    /** {off, err} — a native Home failure is reported once (and switches it off until the next update) */
    @JavascriptInterface public String nhInfo() {
        try { return new JSONObject().put("upright", prefs.getBoolean("uprightNative", false)).put("off", prefs.getBoolean("nativeHomeOff", false)).put("err", prefs.getString("nativeHomeErr", "")).put("errVersion", prefs.getString("nativeHomeErrV", "")).toString(); }
        catch (Exception e) { return "{}"; }
    }
    /** the deeper diagnostics (TV state, event timeline, the app's own log) for the report */
    @JavascriptInterface public String nhDiagExtra() { return Diag.all(act, null, null).toString(); }
    /** right after a glitch: the TV's state, the timeline and the log (and a screenshot) sent at once */
    @JavascriptInterface public void reportGlitch(final String note) {
        Diag.ev("glitch reported" + (note == null || note.isEmpty() ? "" : ": " + note));
        nhShot("glitch");
        io.execute(new Runnable() { @Override public void run() {
            String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(new java.util.Date());
            String e = upd().upload("reports/" + stamp + "-" + getVersion() + "-glitch.json", Diag.all(act, "note", note == null ? "" : note).toString(), "Glitch report " + getVersion());
            act.js("window.toast && toast(" + JSONObject.quote(e.isEmpty() ? "Sent — thanks" : "Couldn't send: " + e) + ")");
        } });
    }
    /** a picture of the screen (TopShelf's window) for the report, uploaded to the updates repository */
    @JavascriptInterface public void nhShot(final String name) {
        act.runOnUiThread(new Runnable() { @Override public void run() {
            act.captureWindow(960, 540, new MainActivity.Shot() { @Override public void done(final byte[] jpg) {
                if (jpg == null) return;
                io.execute(new Runnable() { @Override public void run() {
                    String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US).format(new java.util.Date());
                    upd().uploadBytes("reports/shots/" + stamp + "-" + getVersion() + "-" + name.replaceAll("[^A-Za-z0-9_-]", "_") + ".jpg", jpg, "Screenshot " + name);
                } });
            } });
        } });
    }
    @JavascriptInterface public void nhClearErr() { prefs.edit().remove("nativeHomeErr").apply(); }

    /** Fetches photos in the background, then calls tsPhotos(reqId, json, error) in the page. */
    @JavascriptInterface
    public void fetchPhotos(final String sub, final String sort, final String reqId) {
        io.execute(new Runnable() { @Override public void run() {
            photosNow(sub, sort, new PhotosCb() { @Override public void done(String json, String err) {
                act.js("window.tsPhotos && tsPhotos(" + JSONObject.quote(reqId) + ","
                        + (json == null ? "null" : JSONObject.quote(json)) + ","
                        + (err == null ? "null" : JSONObject.quote(err)) + ")");
            } });
        } });
    }

    // ---- Live TV ------------------------------------------------------------------

    /** {m3u, epg, status, updated} */
    @JavascriptInterface
    public String liveInfo() {
        try {
            JSONObject o = new JSONObject();
            String m = prefs.getString("live.m3u", ""), e = prefs.getString("live.epg", "");
            String cm = LiveTv.cleanUrl(m), ce = LiveTv.cleanUrl(e);
            if (!cm.equals(m) || !ce.equals(e)) prefs.edit().putString("live.m3u", cm).putString("live.epg", ce).apply();
            o.put("m3u", cm);
            o.put("epg", ce);
            o.put("status", prefs.getString("live.status", ""));
            o.put("updated", prefs.getLong("live.updated", 0));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @JavascriptInterface
    public void liveSetUrls(String m3u, String epg) {
        m3u = LiveTv.cleanUrl(m3u);
        epg = LiveTv.cleanUrl(epg);
        String old = prefs.getString("live.m3u", "");
        prefs.edit().putString("live.m3u", m3u == null ? "" : m3u.trim())
                .putString("live.epg", epg == null ? "" : epg.trim()).apply();
        if (m3u == null || m3u.trim().isEmpty()) {
            live.clear();
            prefs.edit().putString("live.status", "").putLong("live.updated", 0).apply();
        } else if (!old.equals(m3u.trim())) {
            prefs.edit().putLong("live.updated", 0).apply();
        }
    }

    @JavascriptInterface
    public String liveChannels() {
        return live.channelsJson();
    }

    @JavascriptInterface
    public String livePrograms(String keysJson, double from, double to) {
        if (!live.epgReady()) { warmEpg(); return "{\"__loading\":true}"; }   // never freeze the screen loading the guide
        return live.programs(keysJson, (long) from, (long) to);
    }

    private final java.util.concurrent.atomic.AtomicBoolean epgWarming = new java.util.concurrent.atomic.AtomicBoolean();
    /** Loads the TV guide into memory on a background thread, then tells the page (tsEpgReady). */
    void warmEpg() {
        if (live.epgReady() || !epgWarming.compareAndSet(false, true)) return;
        io.execute(new Runnable() { @Override public void run() {
            try { live.loadEpgNow(); } finally { epgWarming.set(false); }
            act.js("window.tsEpgReady && tsEpgReady()");
            act.runOnMain(new Runnable() { @Override public void run() { NativeHome h = act.nativeHome(); if (h != null) h.liveChanged(false); } });
        } });
    }

    private volatile boolean liveBusy;
    boolean liveRefreshing() { return liveBusy; }

    /** Re-downloads playlist + guide; reports via tsLiveProgress(msg) and tsLiveDone(ok, status). */
    @JavascriptInterface
    public void liveRefresh() { liveRefresh(false); }

    /** Keeps the playlist and guide fresh: about once an hour while TopShelf is on screen (never in the
     *  middle of a channel you're watching full screen; it waits for that to end), quietly. */
    void liveAutoRefresh() {
        if (liveBusy || LiveTv.cleanUrl(prefs.getString("live.m3u", "")).isEmpty()) return;
        long age = System.currentTimeMillis() - prefs.getLong("live.updated", 0);
        if (age < 60 * 60000L) return;
        NativeHome h = act.nativeHome();
        if (h != null && h.liveWatching()) return;
        liveRefresh(true);
    }

    void liveRefresh(final boolean quiet) {
        if (liveBusy) return;
        liveBusy = true;
        io.execute(new Runnable() { @Override public void run() {
            String status;
            boolean ok;
            try {
                status = live.refresh(LiveTv.cleanUrl(prefs.getString("live.m3u", "")), LiveTv.cleanUrl(prefs.getString("live.epg", "")),
                        new LiveTv.Progress() {
                            @Override public void update(final String m) {
                                act.js("window.tsLiveProgress && tsLiveProgress(" + JSONObject.quote(m) + ")");
                                act.runOnMain(new Runnable() { @Override public void run() { NativeHome h = act.nativeHome(); if (h != null) h.liveProgress(m); } });
                            }
                        });
                ok = true;
                prefs.edit().putLong("live.updated", System.currentTimeMillis()).apply();
                Recorder.guide = live; Recorder.get(act).scan();       // (series episodes in the new guide)
            } catch (Exception e) {
                status = "Failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                ok = false;
            }
            prefs.edit().putString("live.status", status).apply();
            liveBusy = false;
            act.js("window.tsLiveDone && tsLiveDone(" + ok + "," + JSONObject.quote(status) + "," + quiet + ")");
            act.runOnMain(new Runnable() { @Override public void run() { NativeHome h = act.nativeHome(); if (h != null) h.liveChanged(true); } });
        } });
    }

    /** Hands a stream to another installed player (VLC, MX Player…). */
    @JavascriptInterface
    public void openExternal(String url, String title) {
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*")
                .putExtra("title", title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        start(Intent.createChooser(i, "Play with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Drops any live stream connection right away (called when playback stops or changes). */
    @JavascriptInterface
    public void liveStopStreams() {
        io.execute(new Runnable() { @Override public void run() { LiveTv.closeLiveStreams(); } });
    }

    /** Camera-cutout safe insets in CSS px: "left,right,top,bottom". */
    @JavascriptInterface
    public String safeInsets() {
        return act.safeL + "," + act.safeR + "," + act.safeT + "," + act.safeB;
    }

    // ---- Movies / TV Shows tabs ------------------------------------------------------

    /**
     * Opens a title's page in Nuvio using its own link format (nuvio://meta?type=..&id=..), sent
     * straight to the Nuvio app. No NEW_TASK flag: with it, Android just brings an already-running
     * Nuvio to the front and drops the link. Falls back to Stremio's link, then to launching the app.
     */
    @JavascriptInterface
    public String openInStremioApp(String type, String imdbId, String videoId) {
        String t = "series".equals(type) ? "series" : "movie";
        Uri nuvioLink = Uri.parse("nuvio://meta?type=" + t + "&id=" + Uri.encode(imdbId));
        String nuvioPkg = handlerFor(nuvioLink, "nuvio");
        if (nuvioPkg != null) {
            launchLink(new Intent(Intent.ACTION_VIEW, nuvioLink).setPackage(nuvioPkg)
                    .addCategory(Intent.CATEGORY_BROWSABLE));
            return appName(nuvioPkg);
        }
        String vid = videoId != null && !videoId.isEmpty() ? videoId : ("movie".equals(t) ? imdbId : "");
        Uri stremioLink = Uri.parse("stremio:///detail/" + t + "/" + imdbId + (vid.isEmpty() ? "" : "/" + Uri.encode(vid, ":")));
        String stremioPkg = handlerFor(stremioLink, "stremio");
        if (stremioPkg != null) {
            launchLink(new Intent(Intent.ACTION_VIEW, stremioLink).setPackage(stremioPkg));
            return appName(stremioPkg);
        }
        for (String want : new String[]{"nuvio", "stremio"}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                if (String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.US).contains(want)) {
                    Intent i = pm.getLaunchIntentForPackage(ri.activityInfo.packageName);
                    if (i != null) { start(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return appName(ri.activityInfo.packageName) + " (no title links)"; }
                }
            }
        }
        return "";
    }

    /** "Nuvio 0.6.2" — shown on screen so we can tell which app/version took the link. */
    private String appName(String pkg) {
        try {
            String label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            String v = pm.getPackageInfo(pkg, 0).versionName;
            return label + (v != null ? " " + v : "");
        } catch (Exception e) {
            return pkg;
        }
    }

    /** The package that handles a link, preferring one whose name/label contains `prefer`. */
    private String handlerFor(Uri link, String prefer) {
        List<ResolveInfo> all = pm.queryIntentActivities(new Intent(Intent.ACTION_VIEW, link), 0);
        String first = null;
        for (ResolveInfo ri : all) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (act.getPackageName().equals(pkg)) continue;
            if (first == null) first = pkg;
            String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.US);
            if (pkg.toLowerCase(Locale.US).contains(prefer) || label.contains(prefer)) return pkg;
        }
        return first;
    }

    /** Starts a link from the activity itself so the receiving app always gets the link. */
    private void launchLink(final Intent i) {
        act.runOnMain(new Runnable() { @Override public void run() {
            try {
                act.startActivity(i);
            } catch (Exception e) {
                try { act.startActivity(new Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception e2) { act.js("window.tsToast && tsToast('Couldn\\'t open Nuvio')"); }
            }
        } });
    }

    /* Trailer previews on the page (phones): the same as the TV's — the video straight from YouTube,
       shown from its first frame (YtStreams) — played by the page itself through /ytv. Answers
       window.tsTrailerSrc(id, {ok, v, a, what}). */
    private final ExecutorService trLookup = Executors.newSingleThreadExecutor();
    @JavascriptInterface
    public void trailerSource(final String ytId, final int maxH, final boolean sound) {
        TrailerPlayer.initDecodable();
        if (YtStreams.store == null) YtStreams.store = new java.io.File(act.getCacheDir(), "yt-trailers.json");
        trLookup.execute(new Runnable() { @Override public void run() {
            YtStreams.Source src = YtStreams.get(ytId, maxH > 0 ? maxH : 1080, sound);
            JSONObject o = new JSONObject();
            try {
                o.put("ok", src != null && !src.hls);          // (the page plays files, not YouTube's adaptive list)
                if (src != null && !src.hls) {
                    o.put("v", MainActivity.ORIGIN + "ytv?u=" + Uri.encode(src.video));
                    if (src.audio != null) o.put("a", MainActivity.ORIGIN + "ytv?u=" + Uri.encode(src.audio));
                    o.put("what", src.what);
                    Diag.ev("trailer (page): playing straight from YouTube (" + src.what + ")");
                } else Diag.ev("trailer (page): YouTube's player (" + (src != null ? "only an adaptive list" : YtStreams.lastWhy) + ")");
            } catch (Exception ignored) { }
            act.js("window.tsTrailerSrc && tsTrailerSrc(" + JSONObject.quote(ytId) + "," + JSONObject.quote(o.toString()) + ")");
        } });
    }
    @JavascriptInterface
    public void trailerPrefetch(final String ytId, final int maxH, final boolean sound) {
        TrailerPlayer.initDecodable();
        if (YtStreams.cached(ytId, maxH, sound) != null || YtStreams.blocked()) return;
        trLookup.execute(new Runnable() { @Override public void run() { YtStreams.get(ytId, maxH, sound); } });
    }
    /** the page couldn't play it: look it up afresh next time */
    @JavascriptInterface
    public void trailerFailed(String ytId) { YtStreams.forget(ytId); }

    /** Plays a YouTube trailer in the YouTube app (or browser). */
    @JavascriptInterface
    public void openTrailer(String ytId) {
        Intent app = new Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:" + ytId)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (app.resolveActivity(pm) != null) start(app);
        else start(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + ytId)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // ---- Phone & TV extras -----------------------------------------------------------

    @JavascriptInterface
    public boolean isTv() { return act.isTv(); }

    /** "auto" | "portrait" | "landscape" (phones only). */
    @JavascriptInterface
    public void setRotation(final String mode) {
        prefs.edit().putString("rotate", mode).apply();
        act.runOnMain(new Runnable() { @Override public void run() { act.applyOrientation(); } });
    }

    @JavascriptInterface
    public String getRotation() { return prefs.getString("rotate", "auto"); }

    @JavascriptInterface
    public void setVideoLandscape(final boolean on) {
        act.runOnMain(new Runnable() { @Override public void run() { act.setVideoLandscape(on); } });
    }

    @JavascriptInterface
    public void setPip(final boolean ready) {
        act.runOnMain(new Runnable() { @Override public void run() { act.setPipReady(ready); } });
    }

    @JavascriptInterface
    public void haptic() { act.haptic(); }

    // ---- Live TV guide search & reminders ----------------------------------------------

    @JavascriptInterface
    public String liveSearchPrograms(String q, double from, double to) {
        if (!live.epgReady()) { warmEpg(); return "[]"; }
        return live.searchPrograms(q, (long) from, (long) to, 60);
    }

    /** Schedules a reminder notification shortly before `atMs`. */
    @JavascriptInterface
    public void setReminder(String id, double atMs, String title, String text, String channelUrl) {
        ReminderReceiver.schedule(act, id, (long) atMs, title, text, channelUrl);
    }

    @JavascriptInterface
    public void cancelReminder(String id) {
        ReminderReceiver.cancel(act, id);
    }

    /** Asks for permission to show notifications (Android 13+), once. */
    @JavascriptInterface
    public void askNotifications() {
        act.runOnMain(new Runnable() { @Override public void run() { act.askNotifications(); } });
    }

    // ---- Phone ↔ TV link ----------------------------------------------------------------

    /** {name, ip, port, tv, paired: [names]} */
    @JavascriptInterface
    public String linkInfo() {
        try {
            JSONObject o = new JSONObject();
            o.put("name", LinkServer.deviceName(act));
            o.put("ip", LinkServer.localIp());
            o.put("port", act.link != null ? act.link.port() : 0);
            o.put("tv", act.isTv());
            JSONArray p = new JSONArray();
            if (act.link != null) { JSONObject t = act.link.tokens(); java.util.Iterator<String> it = t.keys(); while (it.hasNext()) p.put(t.getString(it.next())); }
            o.put("paired", p);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /** Forgets every device paired with this one. */
    @JavascriptInterface
    public void linkForgetAll() {
        prefs.edit().putString("link.tokens", "{}").apply();
    }

    private android.net.nsd.NsdManager.DiscoveryListener disc;
    private final java.util.ArrayDeque<android.net.nsd.NsdServiceInfo> toResolve = new java.util.ArrayDeque<>();
    private volatile boolean resolving;

    /** Looks for other TopShelfs on the network; each one found arrives as tsLinkFound({name, host, port}). */
    @JavascriptInterface
    public void linkDiscover() {
        act.runOnMain(new Runnable() { @Override public void run() {
            final android.net.nsd.NsdManager nsd = (android.net.nsd.NsdManager) act.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) return;
            try { if (disc != null) nsd.stopServiceDiscovery(disc); } catch (Exception ignored) { }
            disc = new android.net.nsd.NsdManager.DiscoveryListener() {
                @Override public void onStartDiscoveryFailed(String t, int e) { }
                @Override public void onStopDiscoveryFailed(String t, int e) { }
                @Override public void onDiscoveryStarted(String t) { }
                @Override public void onDiscoveryStopped(String t) { }
                @Override public void onServiceLost(android.net.nsd.NsdServiceInfo i) { }
                @Override public void onServiceFound(android.net.nsd.NsdServiceInfo i) {
                    if (act.link != null && i.getServiceName().equals(act.link.serviceName)) return;    // that's us
                    synchronized (toResolve) { toResolve.add(i); }
                    resolveNext(nsd);
                }
            };
            try { nsd.discoverServices(LinkServer.TYPE, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, disc); } catch (Exception ignored) { }
        } });
    }

    @SuppressWarnings("deprecation")
    private void resolveNext(final android.net.nsd.NsdManager nsd) {
        android.net.nsd.NsdServiceInfo next;
        synchronized (toResolve) {
            if (resolving || toResolve.isEmpty()) return;
            resolving = true;
            next = toResolve.poll();
        }
        try {
            nsd.resolveService(next, new android.net.nsd.NsdManager.ResolveListener() {
                @Override public void onResolveFailed(android.net.nsd.NsdServiceInfo i, int e) { resolving = false; resolveNext(nsd); }
                @Override public void onServiceResolved(android.net.nsd.NsdServiceInfo i) {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("name", i.getServiceName().replaceFirst("^TopShelf · ", ""));
                        o.put("host", i.getHost() != null ? i.getHost().getHostAddress() : "");
                        o.put("port", i.getPort());
                        act.js("window.tsLinkFound && tsLinkFound(" + JSONObject.quote(o.toString()) + ")");
                    } catch (Exception ignored) { }
                    resolving = false;
                    resolveNext(nsd);
                }
            });
        } catch (Exception e) { resolving = false; }
    }

    @JavascriptInterface
    public void linkStopDiscover() {
        act.runOnMain(new Runnable() { @Override public void run() {
            try { android.net.nsd.NsdManager nsd = (android.net.nsd.NsdManager) act.getSystemService(Context.NSD_SERVICE); if (nsd != null && disc != null) nsd.stopServiceDiscovery(disc); } catch (Exception ignored) { }
            disc = null;
        } });
    }

    /** POSTs JSON to another TopShelf on the network; the answer arrives as tsLinkResp(id, status, body). */
    @JavascriptInterface
    public void linkPost(final String id, final String url, final String body) {
        io.execute(new Runnable() { @Override public void run() {
            int code = 0; String res = "";
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(8000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                byte[] b = body.getBytes("UTF-8");
                c.setFixedLengthStreamingMode(b.length);
                c.getOutputStream().write(b);
                code = c.getResponseCode();
                java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (in != null) { java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) o.write(buf, 0, n); res = o.toString("UTF-8"); }
                c.disconnect();
            } catch (Exception e) {
                res = "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
            }
            act.js("window.tsLinkResp && tsLinkResp(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(res) + ")");
        } });
    }

    // ---- voice ----------------------------------------------------------------------

    /** Opens Android's speech input; the words arrive as tsVoice(text). */
    @JavascriptInterface
    public void voiceInput(String prompt) { act.startVoice(prompt); }

    /** The channel a tapped reminder asked to open (once), or "". */
    @JavascriptInterface
    public String takeLaunchChannel() {
        String u = act.launchChannel;
        act.launchChannel = null;
        return u == null ? "" : u;
    }

    // ---- backup & restore ------------------------------------------------------------

    /** Everything you've set up: app order, favorites, My List, sports, settings, Live TV links. */
    @JavascriptInterface
    public String backupData() {
        try {
            JSONObject ui = new JSONObject();
            for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
                String k = e.getKey();
                if (!k.startsWith("ui.") || !(e.getValue() instanceof String)) continue;
                String key = k.substring(3);
                if (key.equals("link") || key.startsWith("spTeams.") || key.equals("spScores") || key.equals("spAlerted")) continue;
                ui.put(key, e.getValue());
            }
            JSONObject o = new JSONObject();
            o.put("app", "TopShelf");
            o.put("version", getVersion());
            o.put("device", android.os.Build.MODEL);
            o.put("tv", isTv());
            o.put("at", System.currentTimeMillis());
            o.put("ui", ui);
            o.put("m3u", prefs.getString("live.m3u", ""));
            o.put("epg", prefs.getString("live.epg", ""));
            o.put("rotate", prefs.getString("rotate", "auto"));
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /** Saves a backup to Downloads/TopShelf (and a copy in the app's own folder). Returns where, or "!error". */
    @JavascriptInterface
    public String backupSave(String json) {
        String name = "TopShelf-backup-" + new java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(new java.util.Date()) + ".json";
        byte[] data = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String where = null;
        try {
            File dir = act.getExternalFilesDir("backups");
            if (dir != null) {
                dir.mkdirs();
                try (FileOutputStream out = new FileOutputStream(new File(dir, name))) { out.write(data); }
                where = dir.getAbsolutePath() + "/" + name;
            }
        } catch (Exception ignored) { }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json");
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/TopShelf");
                Uri u = act.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (u != null) {
                    try (java.io.OutputStream out = act.getContentResolver().openOutputStream(u)) { out.write(data); }
                    where = "Downloads/TopShelf/" + name;
                }
            } catch (Exception e) { if (where == null) return "!" + e.getMessage(); }
        }
        return where == null ? "!no storage" : where;
    }

    /** Lets you choose a backup file; the answer comes back through tsBackupFile(text, error). */
    @JavascriptInterface
    public void backupPick() { act.pickBackup(); }

    /** Replaces TopShelf's settings with a backup's (the page reloads afterwards). */
    @JavascriptInterface
    public boolean backupApply(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONObject ui = o.getJSONObject("ui");
            SharedPreferences.Editor ed = prefs.edit();
            for (String k : prefs.getAll().keySet()) {
                if (k.startsWith("ui.") && !k.equals("ui.link") && !k.startsWith("ui.spTeams.")) ed.remove(k);
            }
            java.util.Iterator<String> it = ui.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k.equals("link")) continue;
                ed.putString("ui." + k, ui.getString(k));
            }
            if (o.has("rotate") && !isTv()) ed.putString("rotate", o.getString("rotate"));
            boolean ok = ed.commit();
            Progress.reset();
            return ok;
        } catch (Exception e) { return false; }
    }

    // ---- custom app icons ----------------------------------------------------------------

    IconPacks packs;
    IconPacks packs() { if (packs == null) packs = new IconPacks(act); return packs; }

    /** Icon packs installed on this device: [{pkg, label}]. */
    @JavascriptInterface
    public String iconPacks() { return packs().list(); }

    /** Saves a picture (base64 JPEG/PNG) as an app's tile; returns its file name, or "" on failure. */
    @JavascriptInterface
    public String iconSave(String appId, String b64) {
        try {
            String data = b64.contains(",") ? b64.substring(b64.indexOf(',') + 1) : b64;
            boolean png = b64.startsWith("data:image/png");
            String n = packs().saveCustom(appId, android.util.Base64.decode(data, android.util.Base64.DEFAULT), png ? "png" : "jpg");
            return n == null ? "" : n;
        } catch (Exception e) { return ""; }
    }

    @JavascriptInterface
    public void iconDelete(String appId) { packs().deleteCustom(appId); }

    @JavascriptInterface
    public void iconDeleteAll() { packs().deleteAllCustom(); }

    /** Lets you choose a picture; answers through tsIconPicked(url | null, reason). */
    @JavascriptInterface
    public void iconPickImage() { act.pickImage(); }

    /** True: background photos can be fetched screen-sized through /photo?u=… */
    @JavascriptInterface
    public boolean photoSizing() { return true; }

    /** Screen size and density, for the performance info in Settings. */
    @JavascriptInterface
    public String displayInfo() {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        act.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        return dm.widthPixels + "x" + dm.heightPixels + " @" + dm.density + "x";
    }

    /** Saves a text report to Downloads/TopShelf and opens the share sheet for it. Returns where, or "!error". */
    @JavascriptInterface
    public String saveReport(String name, String text) {
        try {
            byte[] data = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Uri u = null;
            String where;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/TopShelf");
                u = act.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (u == null) return "!no storage";
                try (java.io.OutputStream out = act.getContentResolver().openOutputStream(u)) { out.write(data); }
                where = "Downloads/TopShelf/" + name;
            } else {
                File dir = act.getExternalFilesDir("reports");
                if (dir == null) return "!no storage";
                dir.mkdirs();
                File f = new File(dir, name);
                try (FileOutputStream out = new FileOutputStream(f)) { out.write(data); }
                where = f.getAbsolutePath();
            }
            final Uri share = u;
            final String body = text.length() < 90000 ? text : null;
            act.runOnMain(new Runnable() { @Override public void run() {
                try {
                    Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "TopShelf diagnostic report");
                    if (share != null) { i.putExtra(Intent.EXTRA_STREAM, share); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                    else if (body != null) i.putExtra(Intent.EXTRA_TEXT, body);
                    act.startActivity(Intent.createChooser(i, "Send the report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) { }
            } });
            return where;
        } catch (Exception e) { return "!" + e.getMessage(); }
    }
}
