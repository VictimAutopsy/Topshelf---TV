package com.topshelf.launcher;

import android.content.SharedPreferences;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.PictureInPictureParams;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.util.Rational;
import android.view.HapticFeedbackConstants;
import android.speech.RecognizerIntent;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.DisplayCutout;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * The home screen. A full-screen WebView renders assets/index.html, served from a
 * private https origin (https://topshelf.local/) so the page behaves like a normal site.
 * Everything the page needs from Android goes through {@link Bridge}.
 */
public class MainActivity extends Activity {

    static final String HOST = "topshelf.local";
    static final String ORIGIN = "https://" + HOST + "/";

    private WebView web;
    private FrameLayout root;
    private Bridge bridge;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BroadcastReceiver packageReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyOrientation();
        readLaunchChannel(getIntent());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 28) {
            // draw into the camera cutout area instead of leaving a black strip there
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        bridge = new Bridge(this);
        wasWanted = nativeHomeWanted();
        sCurrent = new java.lang.ref.WeakReference<>(this);
        try {   // a new version gets another chance at the native Home if the last one failed
            SharedPreferences sp = getSharedPreferences(Bridge.PREFS, MODE_PRIVATE);
            long vc = getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
            if (sp.getLong("nhSeenVersion", 0) != vc) sp.edit().putLong("nhSeenVersion", vc).remove("nativeHomeOff").apply();
        } catch (Exception ignored) { }
        guardNativeCrashes();
        crashLoopCheck();
        whyLastStopped();
        SelfCheck.afterUpdate(this);
        // load the TV guide in the background a few seconds after start, so opening Live TV is instant
        main.postDelayed(new Runnable() { @Override public void run() { bridge.warmEpg(); } }, 6000);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                readSafeInsets(insets);
                return insets;
            }
        });
        hideSystemBars();
        getWindow().setBackgroundDrawable(null);         // the page or the native Home always covers the screen
        buildWebView();
        showCover();
        // the flight recorder (Flight): timings, smoothness, memory and screen use, sent once a day
        Flight.start(this, new Flight.Screen() { @Override public String now() { return home == null ? "start-up" : home.flightScreen(); } });
        registerPackageReceiver();
        link = new LinkServer(this);
        link.start();
        fantasy = new Fantasy(this);
        drive = new Drive(this);
        trakt = new Trakt(this);
        // the Ring doorbell (Settings › Ring doorbell): a press shows its picture over whatever's playing
        if (isTv()) {
            ring = new Ring(this);
            ring.listener = new Ring.Listener() { @Override public void ding(final String device, final String snap, final String id, final String devId) {
                runOnUiThread(new Runnable() { @Override public void run() { NativeHome h = nativeHome(); if (h != null) h.doorbell(device, snap, id, devId); } });
            } };
            ring.start();
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() { @Override public void run() { bridge.rescueCheck(); } }, 40000);
        Recorder.guide = bridge.live;
        try { Recorder.get(this).rearm(); Recorder.get(this).scan(); } catch (Throwable t) { Diag.ev("record: rearm failed " + t); }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void buildWebView() {
        // lets a computer connected over ADB (USB/Wi-Fi debugging, which you have to allow on the TV)
        // inspect and time the page with Chrome's developer tools — how performance gets measured on the TV itself
        WebView.setWebContentsDebuggingEnabled(true);
        web = new WebView(this);
        /* The page is hidden while the app draws Home, and by default a hidden web view's helper process
           counts for little: with a big app in front (TizenTube…) Android closed it, and TopShelf had to
           rebuild it on the way back — it looked like a restart. It now counts as much as TopShelf itself. */
        if (Build.VERSION.SDK_INT >= 26) try { web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false); } catch (Throwable ignored) { }
        web.setBackgroundColor(Color.BLACK);
        web.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        // Holding OK is the page's "long press" (context menu); stop WebView's own selection UI.
        web.setLongClickable(false);
        web.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { return true; }
        });
        web.setHapticFeedbackEnabled(false);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setTextZoom(100);              // the layout is sized to the screen; don't let system font scale distort it
        s.setMinimumFontSize(1);         // on phones 1rem can be under 8px; the default minimum would distort sizing
        s.setMinimumLogicalFontSize(1);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        bridge.browserUA = s.getUserAgentString();
        s.setUserAgentString(s.getUserAgentString() + " TopShelf/" + bridge.getVersion());

        web.addJavascriptInterface(bridge, "TopShelf");
        web.setWebViewClient(new LocalClient());
        root.addView(web);
        /* rebuilt after its helper process was lost while the app draws Home: it starts up out of sight
           (it used to show its start-up screen — logo and loading bar — over Home, like a restart) */
        if (home != null && !homeBroken) { web.setBackgroundColor(Color.TRANSPARENT); web.setVisibility(View.INVISIBLE); }
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r - l != or - ol || b - t != ob - ot) main.post(new Runnable() { @Override public void run() { applyRenderScale(); } });
            }
        });
        applyRenderScale();
        web.requestFocus();
        web.loadUrl(ORIGIN + "index.html");
    }

    // ---- Render scale ----------------------------------------------------------------
    /* "Picture sharpness": the page can be drawn at a lower resolution and enlarged to fill the
       screen. The page is drawn into one layer at that size (every effect, every layer of glass
       costs the graphics chip proportionally less), then that single picture is scaled up. */
    private float renderScale = 1f;
    float renderScale() { return renderScale; }

    void applyRenderScale() {
        if (web == null || root.getWidth() == 0) return;
        float s = getSharedPreferences(Bridge.PREFS, MODE_PRIVATE).getFloat("renderScale", 1f);
        if (s < 0.5f || s > 1f) s = 1f;
        renderScale = s;
        int w = Math.round(root.getWidth() * s), h = Math.round(root.getHeight() * s);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s < 1f ? w : ViewGroup.LayoutParams.MATCH_PARENT, s < 1f ? h : ViewGroup.LayoutParams.MATCH_PARENT);
        web.setLayoutParams(lp);
        web.setPivotX(0); web.setPivotY(0);
        web.setScaleX(s < 1f ? (float) root.getWidth() / w : 1f);
        web.setScaleY(s < 1f ? (float) root.getHeight() / h : 1f);
        web.setLayerType(s < 1f ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE, null);
    }

    private Runnable revertScale;
    /** try a render scale; it goes back by itself unless the page confirms it (in case the screen goes blank) */
    void tryRenderScale(final float s, final float previous) {
        getSharedPreferences(Bridge.PREFS, MODE_PRIVATE).edit().putFloat("renderScale", s).commit();
        applyRenderScale();
        if (revertScale != null) main.removeCallbacks(revertScale);
        revertScale = new Runnable() { @Override public void run() {
            revertScale = null;
            getSharedPreferences(Bridge.PREFS, MODE_PRIVATE).edit().putFloat("renderScale", previous).commit();
            applyRenderScale();
            js("window.tsRenderScale && tsRenderScale('reverted')");
        } };
        main.postDelayed(revertScale, 16000);
    }
    void keepRenderScale() { if (revertScale != null) { main.removeCallbacks(revertScale); revertScale = null; } }

    // ---- Native Home ----------------------------------------------------------------
    private NativeHome home;
    private boolean homeBroken;
    private String homeOwner = "native";
    /** the app draws Home and its tabs (the page only lays things over them, if anything) */
    boolean homeIsNative() { return home != null && !homeBroken && !"web".equals(homeOwner); }

    /* The app draws the screens on TVs, and on phones held sideways (the same screens, driven by touch); a
       phone held upright keeps the page's upright layout. */
    boolean nativeHomeWanted() {
        SharedPreferences sp = getSharedPreferences(Bridge.PREFS, MODE_PRIVATE);
        return (isTv() || sideways() || sp.getBoolean("uprightNative", false)) && !homeBroken && !sp.getBoolean("nativeHomeOff", false);
    }
    private boolean sideways() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return dm.widthPixels > dm.heightPixels;
    }
    /* A phone turned between upright and sideways: the screens change hands (the page's upright layout, or the
       app's), so TopShelf starts its screen again — not while a video has the screen turned. */
    private boolean wasWanted;
    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (isTv() || web == null) return;
        boolean want = nativeHomeWanted();
        if (want == wasWanted) return;
        if (videoLandscape || (nplayer != null && nplayer.active()) || (home != null && (home.vodPlayingNow() || home.liveWatching()))) return;
        wasWanted = want;
        main.post(new Runnable() { @Override public void run() { recreate(); } });
    }
    /* On a phone the app's screens take the finger themselves (the page underneath is idle); typing in a box
       goes on to the box. */
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (!isTv() && homeHasKeys()) {
            try { if (home.touch(ev)) return true; } catch (Throwable t) { nativeHomeBroken(t); return true; }
        }
        return super.dispatchTouchEvent(ev);
    }
    NativeHome nativeHome() {
        if (!nativeHomeWanted() || web == null) return null;
        if (home == null) {
            home = new NativeHome(this, bridge);
            root.addView(home, 0, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        return home;
    }
    void nativeHomeBroken(Throwable t) {
        homeBroken = true;
        coverOff("Home broke");
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        String st = sw.toString();
        getSharedPreferences(Bridge.PREFS, MODE_PRIVATE).edit().putString("nativeHomeErr", st.length() > 3000 ? st.substring(0, 3000) : st).apply();
        try { if (home != null) root.removeView(home); } catch (Throwable ignored) { }
        home = null;
        if (web != null) { web.setVisibility(View.VISIBLE); web.setBackgroundColor(Color.BLACK); web.requestFocus(); }
        js("window.nhBroken && nhBroken(" + org.json.JSONObject.quote(String.valueOf(t)) + ")");
    }
    /** native: Home is drawn and driven here, the page is see-through and idle;
     *  overlay: the page shows a panel (Settings, Search…) over Home and has the remote;
     *  web: another tab — the page is everything. */
    void setHomeOwner(String state) {
        NativeHome h = nativeHome();
        if (h == null) return;
        boolean ticker = state.endsWith("+t");
        if (ticker) state = state.substring(0, state.length() - 2);
        homeOwner = state;
        boolean onHome = !state.equals("web");
        if (!(nplayer != null && nplayer.active())) web.setBackgroundColor(onHome ? Color.TRANSPARENT : Color.BLACK);
        // while Home is native and nothing of the page shows over it, the page isn't drawn at all
        // (its score ticker only shows where the app says it may: see tickerCheck)
        pageTicker = ticker;
        web.setVisibility(state.equals("native") && !(ticker && h.tickerWanted()) ? View.INVISIBLE : View.VISIBLE);
        main.removeCallbacks(tickerCheck);
        if (state.equals("native") && ticker) main.postDelayed(tickerCheck, 400);
        // nothing needs painting behind the native Home (each full-screen layer costs the graphics chip)
        root.setBackground(onHome ? null : new android.graphics.drawable.ColorDrawable(Color.BLACK));
        h.setOwner(state);
        if (state.equals("native")) { h.requestFocus(); }
        else web.requestFocus();
    }
    /* The page's score ticker (your teams' live games) sits at the top left. It's wanted on Home; on Live
       TV only for a few seconds when something happens (a score, a quarter ending, the final); never over
       Movies, TV Shows, a title's page or a film or episode playing. Checked a couple of times a second
       while the page has one to show. */
    private boolean pageTicker;
    private final Runnable tickerCheck = new Runnable() { @Override public void run() {
        NativeHome h = nativeHome();
        if (h == null || web == null || !"native".equals(homeOwner) || !pageTicker) return;
        int v = h.tickerWanted() ? View.VISIBLE : View.INVISIBLE;
        if (web.getVisibility() != v) web.setVisibility(v);
        main.postDelayed(this, 400);
    } };
    void removeNativeHome() {
        try { if (home != null) root.removeView(home); } catch (Throwable ignored) { }
        home = null;
        homeOwner = "web";
        if (web != null) { web.setBackgroundColor(Color.BLACK); web.requestFocus(); }
    }
    void focusWeb() { if (web != null) web.requestFocus(); }
    /** for the report: who has the screen and the remote, and the video player (read on the main thread) */
    org.json.JSONObject screenState() {
        final org.json.JSONObject o = new org.json.JSONObject();
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        Runnable r = new Runnable() { @Override public void run() {
            try {
                o.put("homeOwner", homeOwner).put("homeHasKeys", homeHasKeys()).put("redditLogin", redditLogin != null);
                if (web != null) o.put("web", (web.getVisibility() == View.VISIBLE ? "visible" : "hidden") + ", focus " + web.hasFocus());
                View f = getCurrentFocus(); o.put("focus", f == null ? "none" : f.getClass().getSimpleName());
                if (home != null) o.put("home", home.screenState());
                if (nplayer != null) o.put("video", nplayer.state());
            } catch (Throwable ignored) { }
            done.countDown();
        } };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else { main.post(r); try { done.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { } }
        return o;
    }
    boolean homeHasKeys() { return home != null && !homeBroken && homeOwner.equals("native") && home.ownsKeys(); }

    private static java.lang.ref.WeakReference<MainActivity> sCurrent;
    /** the running TopShelf screen, if any (for broadcast receivers) */
    static MainActivity current() { return sCurrent == null ? null : sCurrent.get(); }

    // ---- Native Live TV player ----------------------------------------------------
    private NativePlayer nplayer;
    private boolean nplayerBroken;

    /** whether a video plane exists right now (for the test report) */
    boolean nativePlayerPlane() { return nplayer != null && nplayer.hasPlane(); }

    /** created on first use; null if the video library can't run on this TV */
    NativePlayer nativePlayer() {
        if (nplayerBroken || web == null) return null;
        if (nplayer == null) {
            try {
                nplayer = new NativePlayer(this, root, web, new NativePlayer.Events() {
                    @Override public void event(int id, String ev, String data) {
                        if (id >= 2000000) { if (home != null) home.vodEvent(id, ev, data == null ? "" : data); return; }    // a movie or episode (addons)
                        if (id >= 1000000) { if (home != null) home.liveEvent(id, ev, data == null ? "" : data); return; }   // Live TV drawn by the app
                        js("window.tsNative && tsNative(" + id + "," + org.json.JSONObject.quote(ev) + "," + org.json.JSONObject.quote(data == null ? "" : data) + ")");
                    }
                });
            } catch (Throwable t) { nativeBroken(t); return null; }
        }
        return nplayer;
    }

    /** the native player failed in a way the page can't recover from: use the page's player from now on */
    void nativeBroken(Throwable t) {
        nplayerBroken = true;
        getSharedPreferences(Bridge.PREFS, MODE_PRIVATE).edit().putBoolean("nativeOff", true).putString("nativeErr", String.valueOf(t)).commit();
        try { if (nplayer != null) nplayer.stop(); } catch (Throwable ignored) { }
        nplayer = null;
        js("window.tsNative && tsNative(-1,'broken'," + org.json.JSONObject.quote(String.valueOf(t)) + ")");
    }

    private long lastKeyAt;
    int versionCodeNow() { return versionCode(); }
    String verNameNow() { return verName(); }
    /** the self-check may run: TopShelf on screen, its own Home, nothing playing, the remote left alone a while */
    boolean readyForSelfCheck() {
        if (!inFront || inPip() || home == null || homeBroken) return false;
        if (android.os.SystemClock.uptimeMillis() - lastKeyAt < 25000) return false;
        if (home.vodPlayingNow() || videoRecently()) return false;
        String s = home.flightScreen();
        return s.equals("Home") || s.equals("Movies") || s.equals("TV Shows") || s.equals("screensaver");
    }
    private String verName() { try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable t) { return "?"; } }

    /* Why TopShelf wasn't running (Android 11+ keeps the reason): a crash, Android needing the memory for
       another app, an update… It goes into the report's events, and a crash is sent on its own (with the
       details kept when it happened) so it can be fixed. */
    private void whyLastStopped() {
        final android.content.SharedPreferences pr = getSharedPreferences(Bridge.PREFS, MODE_PRIVATE);
        String reason = "";
        boolean bad = false;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                // (the newest exit of TopShelf itself: the web view's helper processes have their own, which hid it)
                java.util.List<android.app.ApplicationExitInfo> all = am.getHistoricalProcessExitReasons(null, 0, 16);
                java.util.List<android.app.ApplicationExitInfo> l = new java.util.ArrayList<>();
                for (android.app.ApplicationExitInfo e : all) if (getPackageName().equals(e.getProcessName())) { l.add(e); break; }
                if (!l.isEmpty()) {
                    android.app.ApplicationExitInfo x = l.get(0);
                    long seen = pr.getLong("lastExitSeen", 0);
                    if (x.getTimestamp() != seen) {
                        pr.edit().putLong("lastExitSeen", x.getTimestamp()).apply();
                        int r = x.getReason();
                        String name = r == android.app.ApplicationExitInfo.REASON_CRASH ? "crashed" : r == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ? "crashed (native code)"
                                : r == android.app.ApplicationExitInfo.REASON_ANR ? "stopped responding" : r == android.app.ApplicationExitInfo.REASON_LOW_MEMORY ? "closed by Android for memory (low memory)"
                                : r == android.app.ApplicationExitInfo.REASON_OTHER ? "closed by Android (other)" : r == android.app.ApplicationExitInfo.REASON_SIGNALED ? "killed (signal " + x.getStatus() + ")"
                                : r == android.app.ApplicationExitInfo.REASON_EXIT_SELF ? "exited itself" : r == android.app.ApplicationExitInfo.REASON_USER_REQUESTED ? "force-stopped"
                                : r == android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED || r == 14 ? "updated" : r == android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ? "closed for using too much" : "reason " + r;
                        long ago = (System.currentTimeMillis() - x.getTimestamp()) / 1000;
                        reason = "last time TopShelf stopped: " + name + (x.getDescription() != null ? " · " + x.getDescription() : "") + " · " + (ago < 120 ? ago + " s" : ago / 60 + " min") + " before this start"
                                + " · it was using " + (x.getPss() / 1024) + " MB" + (x.getImportance() >= 300 ? " (in the background)" : " (on screen)");
                        bad = r == android.app.ApplicationExitInfo.REASON_CRASH || r == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE || r == android.app.ApplicationExitInfo.REASON_ANR;
                        if (r == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE && x.getTimestamp() - prevStartedAt < 60000 && x.getTimestamp() > prevStartedAt) noteEarlyCrash(x.getTimestamp());
                        if (r == android.app.ApplicationExitInfo.REASON_ANR) Flight.count("freeze");
                        else if (r == android.app.ApplicationExitInfo.REASON_CRASH || r == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) Flight.count("crash");
                        else if (x.getImportance() >= 300 && (r == android.app.ApplicationExitInfo.REASON_LOW_MEMORY || r == android.app.ApplicationExitInfo.REASON_OTHER || r == android.app.ApplicationExitInfo.REASON_SIGNALED)) Flight.count("closed by Android in the background");
                        if (r == android.app.ApplicationExitInfo.REASON_ANR || r == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) {
                            try { java.io.InputStream in = x.getTraceInputStream(); if (in != null) { byte[] b = new byte[8000]; int n = in.read(b); in.close(); if (n > 0) reason += "\n" + new String(b, 0, n, "UTF-8"); } } catch (Throwable ignored) { }
                        }
                    }
                }
            }
        } catch (Throwable ignored) { }
        if (!reason.isEmpty()) Diag.ev(reason.split("\n")[0]);
        noteStart(reason.isEmpty() ? "" : reason.split("\n")[0].replace("last time TopShelf stopped: ", ""));
        final String crash = pr.getString("lastCrash", "");
        if (!(bad || !crash.isEmpty())) return;
        final String text = reason + (crash.isEmpty() ? "" : "\n\n" + crash);
        main.postDelayed(new Runnable() { @Override public void run() {
            new Thread(new Runnable() { @Override public void run() {
                try {
                    Updater u = new Updater(MainActivity.this, pr);
                    if (u.token().isEmpty()) return;
                    String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm", java.util.Locale.US).format(new java.util.Date());
                    if (u.upload("reports/" + stamp + "-" + verName() + "-crash.txt", text, "TopShelf crash").isEmpty()) pr.edit().remove("lastCrash").apply();
                } catch (Throwable ignored) { }
            } }, "crash-report").start();
        } }, 20000);
    }

    /* ------------------------------------------------------------------ the safety net
       A version that crashes within a minute of starting, twice in fifteen minutes, is gone back from by itself:
       the previous version (rebuilt with a newer number: TopShelf-fallback.apk, see tools/publish-update.sh) is
       installed, once per version. Settings › Updates › Go back to the previous version does the same by hand. */
    private long prevStartedAt;
    private void noteEarlyCrash(long at) {
        SharedPreferences p = getSharedPreferences(Bridge.PREFS, MODE_PRIVATE);
        if (at - p.getLong("startedAt", 0) > 60000) return;
        String key = "earlyCrashes." + versionCode();
        p.edit().putString(key, p.getString(key, "") + at + ",").commit();
    }
    private int versionCode() { try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Exception e) { return 0; } }
    private void crashLoopCheck() {
        final SharedPreferences p = getSharedPreferences(Bridge.PREFS, MODE_PRIVATE);
        prevStartedAt = p.getLong("startedAt", 0);
        p.edit().putLong("startedAt", System.currentTimeMillis()).apply();
        final int ver = versionCode();
        int recent = 0;
        for (String t : p.getString("earlyCrashes." + ver, "").split(",")) { try { if (System.currentTimeMillis() - Long.parseLong(t) < 15 * 60000L) recent++; } catch (Exception ignored) { } }
        if (recent < 2 || p.getBoolean("rolledBack." + ver, false)) return;
        p.edit().putBoolean("rolledBack." + ver, true).commit();
        Diag.ev("safety net: " + verName() + " crashed as it started twice · going back to the previous version");
        Flight.count("rolled back by itself");
        android.widget.Toast.makeText(this, "TopShelf " + verName() + " kept closing — going back to the previous version", android.widget.Toast.LENGTH_LONG).show();
        new Thread(new Runnable() { @Override public void run() {
            try {
                Updater u = new Updater(MainActivity.this, p);
                org.json.JSONObject r = new org.json.JSONObject(u.check(ver));
                org.json.JSONObject fb = r.optJSONObject("fallback");
                if (fb == null) { Diag.ev("safety net: no previous version to go back to"); return; }
                u.install(fb.optString("apk", "TopShelf-fallback.apk"), new Updater.Progress() { @Override public void step(String state, int pct, String msg) { if ("error".equals(state)) Diag.ev("safety net: going back didn't work (" + msg + ")"); } });
            } catch (Throwable t) { Diag.ev("safety net: going back didn't work (" + t.getMessage() + ")"); }
        } }, "safety-net").start();
    }

    /** a crash inside the video library switches it off for next time, so Live TV never gets stuck crashing */
    private void guardNativeCrashes() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread th, Throwable e) {
                try {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(sw));
                    String st = sw.toString();
                    if (st.contains("NativeHome") || st.contains("TileView") || st.contains("GlassPanel") || st.contains("TileArt") || st.contains("HomeImages") || st.contains("HomeIcons") || st.contains("NativeVod") || st.contains("TrailerPlayer") || st.contains("NativeLive") || st.contains("NativeSports") || st.contains("NativeSheet") || st.contains("NativeFind") || st.contains("NativeDialog"))
                        getSharedPreferences(Bridge.PREFS, MODE_PRIVATE).edit().putBoolean("nativeHomeOff", true).putString("nativeHomeErr", st.length() > 3000 ? st.substring(0, 3000) : st).commit();
                    else if (st.contains("androidx.media3") || st.contains("NativePlayer"))
                        getSharedPreferences(Bridge.PREFS, MODE_PRIVATE).edit().putBoolean("nativeOff", true).putString("nativeErr", st.length() > 1500 ? st.substring(0, 1500) : st).commit();
                    noteEarlyCrash(System.currentTimeMillis());
                    // every crash is kept, to be sent with the next start (whatever part of the app it was in)
                    getSharedPreferences(Bridge.PREFS, MODE_PRIVATE).edit().putString("lastCrash", "TopShelf " + verName() + " · " + new java.util.Date() + " · thread " + th.getName() + "\n" + (st.length() > 6000 ? st.substring(0, 6000) : st)).commit();
                } catch (Throwable ignored) { }
                if (prev != null) prev.uncaughtException(th, e);
            }
        });
    }

    /** Runs a snippet of JavaScript in the page, from any thread. */
    void js(final String code) {
        main.post(new Runnable() {
            @Override public void run() {
                if (web != null) web.evaluateJavascript(code, null);
            }
        });
    }

    LinkServer link;
    Fantasy fantasy;
    Drive drive;
    Trakt trakt;
    Ring ring;

    /** Runs JavaScript and waits (up to timeoutMs) for its result, as a plain string. */
    String jsResult(final String code, long timeoutMs) {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> out = new AtomicReference<>("");
        main.post(new Runnable() {
            @Override public void run() {
                if (web == null) { done.countDown(); return; }
                web.evaluateJavascript(code, new android.webkit.ValueCallback<String>() {
                    @Override public void onReceiveValue(String v) {
                        try {
                            if (v != null && v.startsWith("\"")) v = new org.json.JSONArray("[" + v + "]").getString(0);
                            else if ("null".equals(v) || "undefined".equals(v)) v = "";
                        } catch (Exception ignored) { }
                        out.set(v == null ? "" : v);
                        done.countDown();
                    }
                });
            }
        });
        try { done.await(timeoutMs, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
        return out.get();
    }

    /** Brings TopShelf to the screen (e.g. a phone asked the TV to play something). */
    void bringToFront() {
        if (inFront) return;
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    startActivity(new Intent(MainActivity.this, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra("fromLink", true));
                } catch (Exception ignored) { }
            }
        });
    }

    // ---- voice ----------------------------------------------------------------------
    volatile PhotoCache photos;
    static final int REQ_VOICE = 42, REQ_BACKUP = 43, REQ_IMAGE = 44;
    volatile String pickedMime = "image/jpeg";

    /* a picture for a custom app icon: copied into the cache and handed to the page as a URL */
    void pickImage() {
        main.post(new Runnable() {
            @Override public void run() {
                Intent[] tries = {
                    new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),
                    new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),
                    new Intent(Intent.ACTION_PICK).setType("image/*")
                };
                for (Intent i : tries) {
                    if (i.resolveActivity(getPackageManager()) == null) continue;
                    try { startActivityForResult(i, REQ_IMAGE); return; } catch (Exception ignored) { }
                }
                js("window.tsIconPicked && tsIconPicked(null, 'nopicker')");
            }
        });
    }

    /* choose a backup: the system file picker if there is one (phones), otherwise the newest backup
       this TopShelf saved (TVs usually have no file picker) */
    void pickBackup() {
        main.post(new Runnable() {
            @Override public void run() {
                Intent[] tries = {
                    new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "application/json", "text/plain", "application/octet-stream" }),
                    new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                };
                for (Intent i : tries) {
                    if (i.resolveActivity(getPackageManager()) == null) continue;
                    try { startActivityForResult(i, REQ_BACKUP); return; } catch (Exception ignored) { }
                }
                String text = newestOwnBackup();
                js("window.tsBackupFile && tsBackupFile(" + (text == null ? "null, 'nopicker'" : org.json.JSONObject.quote(text) + ", 'latest'") + ")");
            }
        });
    }
    private String newestOwnBackup() {
        if (Build.VERSION.SDK_INT >= 29) {
            try (android.database.Cursor c = getContentResolver().query(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[] { android.provider.MediaStore.MediaColumns._ID },
                    android.provider.MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?", new String[] { "TopShelf-backup%" },
                    android.provider.MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
                if (c != null && c.moveToFirst()) {
                    Uri u = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0));
                    String t = readText(u);
                    if (t != null) return t;
                }
            } catch (Exception ignored) { }
        }
        try {
            java.io.File dir = getExternalFilesDir("backups");
            java.io.File[] fs = dir == null ? null : dir.listFiles();
            java.io.File best = null;
            if (fs != null) for (java.io.File f : fs) if (f.getName().endsWith(".json") && (best == null || f.lastModified() > best.lastModified())) best = f;
            if (best != null) return readText(Uri.fromFile(best));
        } catch (Exception ignored) { }
        return null;
    }
    private String readText(Uri u) {
        try (java.io.InputStream in = getContentResolver().openInputStream(u)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) { b.write(buf, 0, n); if (b.size() > 4 * 1024 * 1024) return null; }
            return new String(b.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }
    void startVoice(final String prompt) {
        main.post(new Runnable() {
            @Override public void run() {
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt == null || prompt.isEmpty() ? "Search TopShelf" : prompt)
                        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                try { startActivityForResult(i, REQ_VOICE); }
                catch (Exception e) { js("window.tsVoice && tsVoice(null, 'unavailable')"); }
            }
        });
    }
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_IMAGE) {
            String url = null;
            if (res == RESULT_OK && data != null && data.getData() != null) {
                try (java.io.InputStream in = getContentResolver().openInputStream(data.getData());
                     java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(getCacheDir(), "picked.img"))) {
                    byte[] buf = new byte[65536];
                    int n; long total = 0;
                    while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; if (total > 40L * 1024 * 1024) throw new java.io.IOException("too big"); }
                    String m = getContentResolver().getType(data.getData());
                    pickedMime = m == null ? "image/jpeg" : m;
                    url = "https://topshelf.local/picked?t=" + System.currentTimeMillis();
                } catch (Exception ignored) { }
            }
            js("window.tsIconPicked && tsIconPicked(" + (url == null ? "null, " + (res == RESULT_OK ? "'unreadable'" : "'cancelled'") : "'" + url + "'") + ")");
            return;
        }
        if (req == REQ_BACKUP) {
            String text = res == RESULT_OK && data != null && data.getData() != null ? readText(data.getData()) : null;
            js("window.tsBackupFile && tsBackupFile(" + (text == null ? "null, " + (res == RESULT_OK ? "'unreadable'" : "'cancelled'") : org.json.JSONObject.quote(text) + ", 'picked'") + ")");
            return;
        }
        if (req != REQ_VOICE) return;
        String text = null;
        if (res == RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) text = r.get(0);
        }
        js("window.tsVoice && tsVoice(" + (text == null ? "null" : org.json.JSONObject.quote(text)) + ")");
    }

    void runOnMain(Runnable r) {
        main.post(r);
    }

    /** A 1x1 view tucked behind the home screen (used for the hidden Reddit browser). */
    // ---- signing in to Reddit (for the photos)
    private RedditLogin redditLogin;
    void openRedditLogin() {
        if (redditLogin != null) return;
        redditLogin = new RedditLogin(this, new RedditLogin.Done() { @Override public void done(boolean ok) {
            redditLogin = null;
            Diag.ev("reddit: sign-in " + (ok ? "done" : "closed without signing in"));
            if (ok) {
                js("window.tsRedditSignedIn && tsRedditSignedIn()");
                if (home != null) { try { home.reload("photos"); home.toast("Signed in to Reddit — loading your subreddit"); } catch (Throwable t) { nativeHomeBroken(t); } }
            } else js("window.refreshSettings && refreshSettings()");
        } });
        root.addView(redditLogin, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        redditLogin.bringToFront();
        redditLogin.requestFocus();
    }

    /** keeps the screen on (no screensaver, no dimming) while a video plays */
    private boolean awake;
    static volatile boolean videoPlaying;
    /** when a video last stopped playing (a live stream pauses for a moment whenever it buffers or reconnects) */
    static volatile long videoStoppedAt;
    /** a video is playing, or was a moment ago (the screensaver mustn't start in a stream's short pause) */
    static boolean videoRecently() { return videoPlaying || android.os.SystemClock.uptimeMillis() - videoStoppedAt < 3 * 60000L; }
    void keepAwake(final boolean on) {
        if (videoPlaying && !on) videoStoppedAt = android.os.SystemClock.uptimeMillis();
        videoPlaying = on;
        runOnUiThread(new Runnable() { @Override public void run() {
            if (on == awake) return;
            awake = on;
            if (on) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } });
    }

    void addHiddenView(View v) {
        root.addView(v, 0, new FrameLayout.LayoutParams(1, 1));
    }

    void removeHiddenView(WebView v) {
        root.removeView(v);
        v.destroy();
    }

    private void registerPackageReceiver() {
        packageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                bridge.invalidateApps();
                js("window.tsAppsChanged && tsAppsChanged()");
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(packageReceiver, f);
        }
    }

    // ---- Rotation, picture-in-picture, haptics -----------------------------------

    private Boolean tv;
    boolean isTv() {
        if (tv == null) { PackageManager pm = getPackageManager(); tv = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || pm.hasSystemFeature("android.software.leanback_only"); }
        return tv;
    }

    /** TVs stay landscape; phones follow the Screen rotation setting (auto / portrait / landscape). */
    void applyOrientation() {
        if (isTv()) { setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE); return; }
        if (videoLandscape) { setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE); return; }
        String r = getSharedPreferences(Bridge.PREFS, MODE_PRIVATE).getString("rotate", "auto");
        setRequestedOrientation("portrait".equals(r) ? ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                : "landscape".equals(r) ? ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
    }

    /** Full-screen video on a phone turns the screen sideways, like YouTube; leaving puts it back. */
    volatile boolean videoLandscape;
    void setVideoLandscape(boolean on) {
        videoLandscape = on;
        applyOrientation();
    }

    /** While Live TV is playing full screen, leaving the app shrinks it into a floating window. */
    volatile boolean pipReady;
    private PictureInPictureParams pipParams(boolean ready) {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder().setAspectRatio(new Rational(16, 9));
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(ready).setSeamlessResizeEnabled(false);
        return b.build();
    }
    void setPipReady(boolean ready) {
        pipReady = ready;
        if (Build.VERSION.SDK_INT >= 26 && hasPip()) {
            try { setPictureInPictureParams(pipParams(ready)); } catch (Exception ignored) { }
        }
    }
    private boolean hasPip() {
        return Build.VERSION.SDK_INT >= 26 && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (pipReady && hasPip() && Build.VERSION.SDK_INT < 31 && !isInPictureInPictureMode()) {
            try { enterPictureInPictureMode(pipParams(true)); } catch (Exception ignored) { }
        }
    }
    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration cfg) {
        super.onPictureInPictureModeChanged(inPip, cfg);
        js("window.tsPip && tsPip(" + inPip + ")");
    }
    private boolean inPip() {
        return Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode();
    }

    interface Shot { void done(byte[] jpg); }
    private android.os.HandlerThread shotThread;
    /** a small JPEG of what this window shows (for diagnostic reports) */
    void captureWindow(int w, int h, final Shot cb) {
        try {
            if (shotThread == null) { shotThread = new android.os.HandlerThread("shot"); shotThread.start(); }
            final android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            android.view.PixelCopy.request(getWindow(), b, new android.view.PixelCopy.OnPixelCopyFinishedListener() {
                @Override public void onPixelCopyFinished(int r) {
                    if (r != android.view.PixelCopy.SUCCESS) { cb.done(null); return; }
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, bo);
                    b.recycle();
                    cb.done(bo.toByteArray());
                }
            }, new Handler(shotThread.getLooper()));
        } catch (Throwable t) { cb.done(null); }
    }

    PhotoCache photoCache() {
        if (photos == null) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            photos = new PhotoCache(getCacheDir(), Math.max(dm.widthPixels, dm.heightPixels));
        }
        return photos;
    }

    void haptic() {
        main.post(new Runnable() {
            @Override public void run() {
                if (web != null) web.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
            }
        });
    }

    // ---- Remote keys -------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        // the self-check: a real press of the remote stops it (and does nothing else)
        if (SelfCheck.running() && !SelfCheck.injecting()) { if (e.getAction() == KeyEvent.ACTION_DOWN) SelfCheck.abort("a button was pressed"); return true; }
        if (!SelfCheck.injecting()) lastKeyAt = android.os.SystemClock.uptimeMillis();
        if (redditLogin != null) { if (redditLogin.key(e)) return true; return super.dispatchKeyEvent(e); }
        if (homeHasKeys()) {
            try { if (home.key(e)) return true; } catch (Throwable t) { nativeHomeBroken(t); }
            if (code == KeyEvent.KEYCODE_BACK) return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            // A launcher never closes itself: the page decides what Back means.
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) {
                js("window.tsBack && tsBack()");
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_SEARCH) {
            if (e.getAction() == KeyEvent.ACTION_UP) js("window.tsVoiceSearch && tsVoiceSearch()");
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS) {
            if (e.getAction() == KeyEvent.ACTION_UP) js("window.tsMenu && tsMenu()");
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra("fromLink", false)) return;   // just brought forward for the phone
        if (readLaunchChannel(intent)) {                  // a reminder was tapped: go watch it
            js("window.tsLaunchChannel && tsLaunchChannel()");
            return;
        }
        // Home pressed while we're already up (or the redirect service brought us back).
        Diag.ev("home asked for: " + (intent == null ? "?" : intent.getBooleanExtra("homeKey", false) ? "the Home button" : intent.getBooleanExtra("redirect", false) ? "Google's home screen came up" : "Android"));
        js("window.tsHome && tsHome()");
        if (home != null) { try { home.goHome(); } catch (Throwable t) { nativeHomeBroken(t); } }
    }

    /** Set when a reminder notification opened us; the page picks it up with takeLaunchChannel(). */
    volatile String launchChannel;
    private boolean readLaunchChannel(Intent i) {
        String u = i != null ? i.getStringExtra(ReminderReceiver.EXTRA_CHANNEL) : null;
        if (u == null || u.isEmpty()) return false;
        launchChannel = u;
        i.removeExtra(ReminderReceiver.EXTRA_CHANNEL);
        return true;
    }

    static volatile boolean inFront;

    void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7); } catch (Exception ignored) { }
        }
    }

    /** True full screen: hide status and navigation bars (swipe from an edge shows them briefly). */
    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    /** Where the camera cutout is (CSS px), so the page keeps controls clear of it. */
    volatile float safeL, safeR, safeT, safeB;

    private void readSafeInsets(WindowInsets insets) {
        float l = 0, r = 0, t = 0, b = 0;
        if (Build.VERSION.SDK_INT >= 28) {
            DisplayCutout dc = insets.getDisplayCutout();
            if (dc != null) {
                l = dc.getSafeInsetLeft(); r = dc.getSafeInsetRight(); t = dc.getSafeInsetTop(); b = dc.getSafeInsetBottom();
            }
        }
        float d = getResources().getDisplayMetrics().density;
        safeL = l / d; safeR = r / d; safeT = t / d; safeB = b / d;
        js("window.tsInsets && tsInsets(" + safeL + "," + safeR + "," + safeT + "," + safeB + ")");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // coming back to TopShelf (not starting it): from here to Home's next picture on screen
        if (home != null && !freshProcess() && !inPip()) {
            final long t0 = android.os.SystemClock.uptimeMillis();
            android.view.Choreographer.getInstance().postFrameCallback(new android.view.Choreographer.FrameCallback() { int n;
                @Override public void doFrame(long f) { if (++n < 2) { android.view.Choreographer.getInstance().postFrameCallback(this); return; } Flight.time("back to TopShelf", android.os.SystemClock.uptimeMillis() - t0); } });
        }
        Flight.inFront(true);
        inFront = true;
        HomeWatchService.lift();
        snapHomeSoon();
        hideSystemBars();
        HomeWatchService.startIfWanted(this);
        if (isTv()) ReadyService.startIfWanted(this);
        if (web != null) {
            web.onResume();
            web.requestFocus();
        }
        js("window.tsResume && tsResume()");
        main.removeCallbacks(liveTick);
        main.postDelayed(liveTick, 20000);
        if (home != null) { try { home.hideLaunch(); } catch (Throwable ignored) { } }
        if (home != null && !inPip()) { try { home.appResumed(); } catch (Throwable ignored) { } }
        if (fantasy != null) fantasy.resume();
        if (trakt != null) trakt.sync(false);
        main.removeCallbacks(memTick); main.postDelayed(memTick, 60000);
    }

    /* memory: a sample every two minutes while TopShelf is on screen (for the reports), and the picture caches
       given back when Android runs short */
    private final Runnable memTick = new Runnable() { @Override public void run() {
        if (!inFront) return;
        Diag.mem("every 2 min");
        main.postDelayed(this, 120000);
    } };
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Diag.ev("memory: Android asked for memory back (level " + level + ")");
        if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_BACKGROUND) Diag.trim("level " + level);
        // (in the background: does graphics memory fall once Android's drawing has let go? that says whose it is)
        if (level == TRIM_MEMORY_UI_HIDDEN) {
            /* another app is in front: let go of what's quick to get back (picture caches, Movies & TV's
               backdrops), so Android is less likely to close TopShelf to make room for it */
            Diag.trim("left TopShelf");
            try { if (home != null) home.leftApp(); } catch (Throwable ignored) { }
            main.postDelayed(new Runnable() { @Override public void run() { Diag.mem("left TopShelf (3 s later)"); } }, 3000);
        }
    }

    /* TopShelf leaving on purpose (an app opened from it, Android's settings, the mic…): no curtain then */
    private long leftOnPurposeAt;
    @Override public void startActivity(Intent intent) { leftOnPurposeAt = android.os.SystemClock.uptimeMillis(); super.startActivity(intent); }
    @Override public void startActivity(Intent intent, Bundle options) { leftOnPurposeAt = android.os.SystemClock.uptimeMillis(); super.startActivity(intent, options); }
    @Override public void startActivityForResult(Intent intent, int requestCode, Bundle options) { leftOnPurposeAt = android.os.SystemClock.uptimeMillis(); super.startActivityForResult(intent, requestCode, options); }

    /* Start-up from nothing (Android closed TopShelf while another app was in front): the last picture of Home is
       on screen at once, while the page and Home get going underneath; it fades as soon as Home has drawn itself.
       The picture is taken whenever Home is at rest on screen (at most once a minute). */
    private android.widget.ImageView cover;
    private long coverAt;
    private java.io.File coverFile() { return new java.io.File(getCacheDir(), "home-cover.jpg"); }
    private long coverSaved;
    private void saveCover(final android.graphics.Bitmap b) {
        if (!isTv() || android.os.SystemClock.uptimeMillis() - coverSaved < 60000) return;
        coverSaved = android.os.SystemClock.uptimeMillis();
        new Thread(new Runnable() { @Override public void run() {
            java.io.File f = coverFile(), tmp = new java.io.File(getCacheDir(), "home-cover.tmp");
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(tmp)) { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, o); } catch (Throwable t) { return; }
            tmp.renameTo(f);
            if (!HomeWatchService.isRunning()) b.recycle();
        } }, "home-cover").start();
    }
    /** TopShelf's process itself just started (not the screen made again, as a change of look does) */
    private static boolean freshProcess() { return Build.VERSION.SDK_INT < 24 || android.os.SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis() < 8000; }
    private static boolean startNoted;
    private void showCover() {
        if (!isTv() || !nativeHomeWanted() || !freshProcess()) return;
        java.io.File f = coverFile();
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 7 * 86400000L) return;
        try {
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getPath());
            if (b == null) return;
            cover = new android.widget.ImageView(this);
            cover.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
            cover.setImageBitmap(b);
            root.addView(cover, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            coverAt = android.os.SystemClock.uptimeMillis();
            main.postDelayed(new Runnable() { @Override public void run() { coverOff("Home took too long"); } }, 15000);
        } catch (Throwable ignored) { cover = null; }
    }
    /** Home has drawn itself (NativeHome), or it isn't coming */
    void coverOff(final String why) {
        if (startMs < 0 && startNoted) {
            startMs = android.os.SystemClock.uptimeMillis() - (Build.VERSION.SDK_INT >= 24 ? android.os.Process.getStartUptimeMillis() : coverAt);
            Diag.ev("start-up: Home on screen " + String.format(java.util.Locale.US, "%.1f", startMs / 1000f) + " s after TopShelf started" + (cover != null ? " (its last picture showed meanwhile)" : "") + (why.isEmpty() ? "" : " · " + why));
            noteStartReady(startMs);
            Flight.time("fresh start", startMs);
        }
        final android.widget.ImageView c = cover;
        if (c == null) return;
        cover = null;
        c.animate().alpha(0f).setDuration(220).withEndAction(new Runnable() { @Override public void run() {
            root.removeView(c);
            android.graphics.drawable.Drawable d = c.getDrawable();
            if (d instanceof android.graphics.drawable.BitmapDrawable) { android.graphics.Bitmap bm = ((android.graphics.drawable.BitmapDrawable) d).getBitmap(); c.setImageDrawable(null); if (bm != null) bm.recycle(); }
        } }).start();
    }
    private long startMs = -1;
    /* Each start from nothing, kept (the last 12) so a report shows how often it happens and why: the events
       themselves only cover the time since the last start. */
    private void noteStart(String lastStop) {
        if (startNoted || !freshProcess()) return;
        startNoted = true;
        try {
            SharedPreferences p = getSharedPreferences(Bridge.PREFS, MODE_PRIVATE);
            org.json.JSONArray a = new org.json.JSONArray(p.getString("startLog", "[]"));
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, a.length() - 6); i < a.length(); i++) {
                org.json.JSONObject o = a.optJSONObject(i); if (o == null) continue;
                sb.append("\n    ").append(new java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.US).format(new java.util.Date(o.optLong("at"))))
                  .append(" · ").append(o.optString("stop", "?")).append(o.has("ms") ? " · Home after " + String.format(java.util.Locale.US, "%.1f", o.optLong("ms") / 1000f) + " s" : "");
            }
            if (sb.length() > 0) Diag.ev("start-ups before this one:" + sb);
            boolean ready = ReadyService.isRunning() || HomeWatchService.isRunning();
            a.put(new org.json.JSONObject().put("at", System.currentTimeMillis()).put("stop", lastStop.isEmpty() ? "unknown" : lastStop.replaceAll(" · \\d+ (s|min) before this start", "")));
            while (a.length() > 12) a.remove(0);
            p.edit().putString("startLog", a.toString()).apply();
            Diag.ev("start-up: from nothing · keep ready " + (ReadyService.wanted(this) ? "on" : "off") + " · Home button by " + (HomeWatchService.accessibilityOn(this) ? "accessibility" : HomeWatchService.wanted(this) ? "the watcher" : "nothing") + (isDefaultHome() ? " · TopShelf is the TV's home app" : ""));
        } catch (Throwable ignored) { }
    }
    private void noteStartReady(long ms) {
        try {
            SharedPreferences p = getSharedPreferences(Bridge.PREFS, MODE_PRIVATE);
            org.json.JSONArray a = new org.json.JSONArray(p.getString("startLog", "[]"));
            if (a.length() > 0) a.optJSONObject(a.length() - 1).put("ms", ms);
            p.edit().putString("startLog", a.toString()).apply();
        } catch (Throwable ignored) { }
    }
    private boolean isDefaultHome() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN); i.addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo r = getPackageManager().resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            return r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
        } catch (Throwable t) { return false; }
    }

    /** a picture of Home as it is now (half size), for the curtain that hides Google's home screen */
    void snapHomeSoon() {
        main.removeCallbacks(snapHome);
        main.postDelayed(snapHome, 2500);
    }
    private final Runnable snapHome = new Runnable() { @Override public void run() {
        if (!inFront || Build.VERSION.SDK_INT < 26 || !(HomeWatchService.isRunning() || isTv())) return;
        if (home == null || !home.onHomeAtRest()) return;
        try {
            View d = getWindow().getDecorView();
            int w = Math.max(16, d.getWidth() / 2), hh = Math.max(16, d.getHeight() / 2);
            final android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, hh, android.graphics.Bitmap.Config.ARGB_8888);
            android.view.PixelCopy.request(getWindow(), b, new android.view.PixelCopy.OnPixelCopyFinishedListener() {
                @Override public void onPixelCopyFinished(int result) {
                    if (result != android.view.PixelCopy.SUCCESS) return;
                    if (HomeWatchService.isRunning()) HomeWatchService.homeShot = b;
                    saveCover(b);
                }
            }, main);
        } catch (Throwable ignored) { }
    } };

    @Override
    protected void onPause() {
        inFront = false;
        // left without opening anything (Home pressed): cover Google's home screen until TopShelf is back
        if (HomeWatchService.isRunning() && !inPip() && android.os.SystemClock.uptimeMillis() - leftOnPurposeAt > 2500) HomeWatchService.curtain(this, 900);
        if (fantasy != null) fantasy.pause();
        if (home != null) try { home.vodPause(); } catch (Throwable ignored) { }
        if (!inPip()) {                       // a floating Live TV window keeps playing
            js("window.tsPause && tsPause()");
            if (web != null) web.onPause();
        }
        super.onPause();
    }

    /* the Live TV playlist and guide stay fresh while TopShelf is on screen */
    private final Runnable liveTick = new Runnable() { @Override public void run() {
        main.removeCallbacks(this);
        if (!inFront) return;
        try { if (bridge != null) bridge.liveAutoRefresh(); } catch (Throwable ignored) { }
        main.postDelayed(this, 10 * 60000L);
    } };

    @Override
    protected void onStop() {
        Flight.inFront(false);
        js("window.tsPause && tsPause()");    // also when the floating window is closed
        try { if (home != null && !inPip()) home.appPaused(); } catch (Throwable ignored) { }
        try { if (nplayer != null && !inPip()) nplayer.stop(); } catch (Throwable ignored) { }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        try { if (nplayer != null) nplayer.stop(); } catch (Throwable ignored) { }
        // (if Android makes the screen again in the same process, the new one starts its own: two doorbell connections
        // with the same identity would keep pushing each other off)
        try { if (ring != null) ring.stop(); } catch (Throwable ignored) { }
        try { if (link != null) link.stop(); } catch (Throwable ignored) { }
        if (packageReceiver != null) {
            try { unregisterReceiver(packageReceiver); } catch (Exception ignored) { }
        }
        if (web != null) {
            web.destroy();
            web = null;
        }
        bridge.shutdown();
        if (link != null) link.stop();
        super.onDestroy();
    }

    // ---- Serving the page and app icons ------------------------------------------

    private class LocalClient extends WebViewClient {

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (!HOST.equals(u.getHost())) return null;
            String path = u.getPath();
            if (path == null || path.equals("/")) path = "/index.html";

            if (path.equals("/proxy")) {
                return LiveTv.proxy(u.getQueryParameter("u"), u.getQueryParameter("ua"), req.getRequestHeaders(),
                        "1".equals(u.getQueryParameter("live")) || "2".equals(u.getQueryParameter("live")),
                        "1".equals(u.getQueryParameter("live")));
            }

            if (path.equals("/ytv")) return YtStreams.proxy(u.getQueryParameter("u"), req.getRequestHeaders());

            if (path.equals("/photo")) {
                if (photos == null) {
                    android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                    getWindowManager().getDefaultDisplay().getRealMetrics(dm);
                    photos = new PhotoCache(getCacheDir(), Math.max(dm.widthPixels, dm.heightPixels));
                }
                byte[] bytes = photos.get(u.getQueryParameter("u"), bridge.browserUA);
                if (bytes == null) return notFound();
                WebResourceResponse r = new WebResourceResponse("image/jpeg", null, new java.io.ByteArrayInputStream(bytes));
                Map<String, String> h = new HashMap<>();
                h.put("Cache-Control", "max-age=86400");
                h.put("Access-Control-Allow-Origin", "*");
                r.setResponseHeaders(h);
                return r;
            }

            if (path.equals("/picked") || path.startsWith("/customicon/") || path.startsWith("/packicon/")) {
                byte[] bytes = null;
                String mime = "image/png";
                try {
                    if (path.equals("/picked")) {
                        bytes = java.nio.file.Files.readAllBytes(new java.io.File(getCacheDir(), "picked.img").toPath());
                        mime = pickedMime;
                    } else if (path.startsWith("/customicon/")) {
                        String n = Uri.decode(path.substring("/customicon/".length()));
                        bytes = bridge.packs().readCustom(n);
                        mime = n.endsWith(".png") ? "image/png" : "image/jpeg";
                    } else {
                        String rest = path.substring("/packicon/".length());
                        int sl = rest.indexOf('/');
                        if (sl > 0) bytes = bridge.packs().packIcon(Uri.decode(rest.substring(0, sl)), Uri.decode(rest.substring(sl + 1)));
                    }
                } catch (Exception ignored) { }
                if (bytes == null) return notFound();
                WebResourceResponse r = new WebResourceResponse(mime, null, new java.io.ByteArrayInputStream(bytes));
                Map<String, String> h = new HashMap<>();
                h.put("Cache-Control", path.equals("/picked") ? "no-store" : "max-age=86400");
                h.put("Access-Control-Allow-Origin", "*");
                r.setResponseHeaders(h);
                return r;
            }

            if (path.startsWith("/icon/")) {
                String id = Uri.decode(path.substring("/icon/".length()));
                boolean banner = "1".equals(u.getQueryParameter("b"));
                byte[] png = bridge.iconPng(id, banner);
                if (png == null) return notFound();
                WebResourceResponse r = new WebResourceResponse("image/png", null,
                        new java.io.ByteArrayInputStream(png));
                Map<String, String> h = new HashMap<>();
                h.put("Cache-Control", "max-age=86400");
                h.put("Access-Control-Allow-Origin", "*");
                r.setResponseHeaders(h);
                return r;
            }

            try {
                InputStream in = getAssets().open(path.substring(1));
                WebResourceResponse r = new WebResourceResponse(mime(path), mime(path).startsWith("text") ? "utf-8" : null, in);
                if (path.endsWith(".html")) {
                    // allows the built-in profiler used by Settings → Diagnostic report
                    Map<String, String> h = new HashMap<>();
                    h.put("Document-Policy", "js-profiling");
                    r.setResponseHeaders(h);
                }
                return r;
            } catch (Exception e) {
                return notFound();
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            // Never navigate away from the home screen.
            return !HOST.equals(req.getUrl().getHost());
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // If the page's renderer dies, rebuild it instead of crashing the launcher.
            try { Diag.ev("page: its helper process " + (detail.didCrash() ? "crashed" : "was closed by Android (memory)") + (inFront ? " while TopShelf was on screen" : " while another app was in front") + " — rebuilt"); } catch (Throwable ignored) { }
            ViewGroup parent = (ViewGroup) view.getParent();
            if (parent != null) parent.removeView(view);
            view.destroy();
            if (view == web) {
                web = null;
                main.post(new Runnable() {
                    @Override public void run() { buildWebView(); }
                });
            }
            return true;
        }
    }

    private static WebResourceResponse notFound() {
        WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8",
                new java.io.ByteArrayInputStream(new byte[0]));
        r.setStatusCodeAndReasonPhrase(404, "Not Found");
        return r;
    }

    private static String mime(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "text/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".otf")) return "font/otf";
        if (p.endsWith(".ttf")) return "font/ttf";
        return "application/octet-stream";
    }
}
