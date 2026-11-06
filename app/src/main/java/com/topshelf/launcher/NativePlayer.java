package com.topshelf.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Live TV through Android's own video player (Media3 / ExoPlayer) instead of the page's script player.
 * The stream is read, unpacked and decoded off the page's thread, so the guide stays smooth while a
 * channel plays. The picture is a view next to the page:
 *  - the small preview sits on top of the page, in the preview's rounded rectangle;
 *  - full screen sits under the page, which turns transparent there so the channel banner, channel
 *    list and stream info still draw on top of the picture.
 * Anything it can't play is handed back to the page's player.
 */
final class NativePlayer implements Player.Listener {
    /** a recording moved to Google Drive plays from there: its pieces are fetched with the TV's Drive sign-in */
    interface Token { String get(); }
    static volatile Token driveToken;
    static final class DriveAuth implements androidx.media3.datasource.DataSource.Factory {
        private final androidx.media3.datasource.DataSource.Factory base;
        DriveAuth(androidx.media3.datasource.DataSource.Factory b) { base = b; }
        @Override public androidx.media3.datasource.DataSource createDataSource() {
            final androidx.media3.datasource.DataSource d = base.createDataSource();
            return new androidx.media3.datasource.DataSource() {
                @Override public void addTransferListener(androidx.media3.datasource.TransferListener l) { d.addTransferListener(l); }
                @Override public long open(androidx.media3.datasource.DataSpec spec) throws java.io.IOException {
                    Token t = driveToken;
                    if (t != null && spec.uri != null && "www.googleapis.com".equals(spec.uri.getHost())) {
                        java.util.Map<String, String> hd = new java.util.HashMap<>(); hd.put("Authorization", "Bearer " + t.get());
                        spec = spec.withAdditionalHeaders(hd);
                    }
                    return d.open(spec);
                }
                @Override public int read(byte[] b, int off, int len) throws java.io.IOException { return d.read(b, off, len); }
                @Override public Uri getUri() { return d.getUri(); }
                @Override public java.util.Map<String, java.util.List<String>> getResponseHeaders() { return d.getResponseHeaders(); }
                @Override public void close() throws java.io.IOException { d.close(); }
            };
        }
    }

    interface Events { void event(int id, String ev, String data); }

    private final MainActivity act;
    private final FrameLayout root;
    private final View web;
    private final Events out;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private DefaultBandwidthMeter meter;
    private int id;
    private String kind = "";
    private boolean firstFrame;
    private int vw, vh;
    private float par = 1f;
    private String lastCues = "";
    private int retries;

    NativePlayer(MainActivity act, FrameLayout root, View web, Events out) {
        this.act = act; this.root = root; this.web = web; this.out = out;
    }

    // ------------------------------------------------------------------ view

    /* The picture is a SurfaceView: Android shows it on its own plane under the app's window (the TV's
       video hardware composes it — no copying through the graphics chip). The page above it is
       see-through exactly where the picture should show: a rounded hole for the preview, everything
       but the banner/menus in full screen (nativeplayer.js). The view stays on screen (1×1 when not
       needed) so its surface — and the decoder — survive. */
    private String mode = "hide";
    private SurfaceView sv;
    private int bx, by, bw = 1, bh = 1;

    private final Runnable dropPlane = new Runnable() { @Override public void run() {
        if (player != null || sv == null || !"hide".equals(mode)) return;
        try { root.removeView(sv); } catch (Throwable ignored) { }
        sv = null;
        Diag.ev("video layer removed");
    } };
    boolean hasPlane() { return sv != null; }
    /** the picture's plane while it shows a picture (for copying a frame), else null */
    SurfaceView shownPlane() { return sv != null && player != null && firstFrame && !"hide".equals(mode) ? sv : null; }
    /** where the picture is on screen (its box, scaled as it's drawn) */
    android.graphics.RectF planeRect() {
        if (sv == null) return null;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sv.getLayoutParams();
        float sc = sv.getScaleX(), px = sv.getPivotX(), py = sv.getPivotY();
        float l = lp.leftMargin + px * (1 - sc), t = lp.topMargin + py * (1 - sc);
        return new android.graphics.RectF(l, t, l + lp.width * sc, t + lp.height * sc);
    }
    private SurfaceView sv() {
        main.removeCallbacks(dropPlane);
        if (sv != null) return sv;
        Diag.ev("video layer made");
        sv = new SurfaceView(act);
        sv.setFocusable(false);
        root.addView(sv, 0, new FrameLayout.LayoutParams(1, 1));
        return sv;
    }

    /** the picture inside its box like CSS object-fit: contain */
    private void fit() {
        if (sv == null) return;
        if ("hide".equals(mode)) { setBox(sv, 0, 0, 1, 1); return; }
        int x = bx, y = by, w = bw, h = bh;
        if (vw > 0 && vh > 0 && w > 1 && h > 1) {
            float va = vw * par / vh, ba = (float) w / h;
            if (va > ba) { int nh = Math.round(w / va); y += (h - nh) / 2; h = nh; }
            else { int nw = Math.round(h * va); x += (w - nw) / 2; w = nw; }
        }
        setBox(sv, x, y, w, h);
        applyScale();
    }

    /* Live TV's preview grows and shrinks with the top bars: the picture keeps its (grown) size and is
       scaled from its box's bottom-right corner, so its surface is never resized while it moves. */
    private float pvScale = 1f;
    void setPvScale(float s) { if (s == pvScale) return; pvScale = s; applyScale(); }
    private void applyScale() {
        if (sv == null) return;
        float s = "pv".equals(mode) ? pvScale : 1f;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sv.getLayoutParams();
        sv.setPivotX(bx + bw - lp.leftMargin); sv.setPivotY(by + bh - lp.topMargin);
        if (sv.getScaleX() != s) { sv.setScaleX(s); sv.setScaleY(s); }
    }

    /** mode: "pv" | "fs" | "hide"; rect in screen pixels (fs: 0 = whole screen) */
    void layout(String newMode, int x, int y, int w, int h, float r, int animMs) {
        String nm = "top".equals(newMode) ? "fs" : newMode;
        if (!nm.equals(mode)) Diag.ev("video " + mode + " → " + nm + (w > 0 ? " " + w + "x" + h + " at " + x + "," + y : ""));
        mode = "top".equals(newMode) ? "fs" : newMode;
        if ("hide".equals(mode) && player == null && sv == null) return;     // (nothing to show: no picture plane)
        sv();
        if (w <= 0 || h <= 0) { x = 0; y = 0; w = root.getWidth(); h = root.getHeight(); }
        else {
            // the page may be drawn smaller and enlarged (Picture sharpness): its pixels → screen pixels
            float k = 1f / act.renderScale();
            x = Math.round(x * k); y = Math.round(y * k); w = Math.round(w * k); h = Math.round(h * k);
        }
        bx = x; by = y; bw = w; bh = h;
        fit();
        rateRule();
        if ("fs".equals(mode) && !afrAsked && player != null) matchRate();
        // a channel's rate is the TV's only while it's full screen (the guide and menus go back to the usual rate)
        else if (!"fs".equals(mode) && !vod() && afrMode != 0) { unmatchRate(); afrAsked = false; }
    }

    /* The TV may switch its whole display to the video's frame rate (a 50 fps channel → 50 Hz). That's right
       full screen, but in the small preview it left the menus and every tab running at 50 Hz (and stayed that
       way after), which felt like lag. So only full-screen video may ask for a different refresh rate. */
    private Object rateFor; private int rateSet;
    private void rateRule() {
        boolean fs = "fs".equals(mode);
        int want = fs ? C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS : C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF;
        // told to the player only when it changes (it was told on every placement of the picture), and the
        // picture's surface itself is only touched once nothing is playing on it (never while the decoder uses it)
        if (player != null && (rateFor != player || rateSet != want)) {
            try { player.setVideoChangeFrameRateStrategy(want); rateFor = player; rateSet = want; } catch (Throwable ignored) { }
        }
    }
    private void clearRate() {
        if (sv == null || player != null || android.os.Build.VERSION.SDK_INT < 30) return;
        try {
            android.view.Surface s = sv.getHolder().getSurface();
            if (s != null && s.isValid()) s.setFrameRate(0f, android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
        } catch (Throwable ignored) { }
    }
    /** the display's refresh rate right now (for the test report) */
    float displayHz() { try { return act.getWindowManager().getDefaultDisplay().getRefreshRate(); } catch (Throwable t) { return 0; } }

    private long movedAt;
    private void setBox(View v, int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        if (lp.leftMargin == x && lp.topMargin == y && lp.width == w && lp.height == h) return;
        movedAt = android.os.SystemClock.elapsedRealtime();
        lp.leftMargin = x; lp.topMargin = y; lp.width = w; lp.height = h;
        v.setLayoutParams(lp);
    }

    private boolean webClear;
    private void setWebClear(boolean clear) {
        if (clear == webClear) return;
        webClear = clear;
        // (no video any more: black only where the page itself is the screen. Over the app's own Home and tabs the
        // page stays see-through — a black page over them, showing just its score ticker, was the "black Sports tab")
        web.setBackgroundColor(clear || act.homeIsNative() ? Color.TRANSPARENT : Color.BLACK);
    }

    // ------------------------------------------------------------------ video watchdog
    /* Notices a picture that stops while the sound goes on: tries re-attaching the picture once,
       then reports it (the page then plays that channel itself). */
    private final Runnable watch = new Runnable() { @Override public void run() { watchTick(); } };
    private long lastRendered = -1, stallSince, lastPos;
    private int strategy;
    private String url = "", ua = "";
    private long readyAt;
    private String decoderName = "";
    private String candidates = "", lastStall = "", history = "";

    /* Sound: 0 as the stream sends it (Dolby passed to the TV/receiver as is), 1 turned into plain sound
       here first, 2 off. Some channels' Dolby sound (ESPN U) never starts playing, and since the picture
       keeps time by the sound, everything sits still on the first frame with the buffer full. */
    private int audioMode;
    private final java.util.Set<Integer> audioTried = new java.util.HashSet<>();

    /** the channel has more than one sound track: switch to one not tried yet (no restart needed) */
    private boolean tryOtherAudio() {
        try {
            List<Tracks.Group> gs = player.getCurrentTracks().getGroups();
            int pick = -1;
            for (int i = 0; i < gs.size(); i++) {
                Tracks.Group g = gs.get(i);
                if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
                if (g.isSelected()) { audioTried.add(i); continue; }
                if (pick < 0 && g.isSupported() && !audioTried.contains(i)) pick = i;
            }
            if (pick < 0) return false;
            audioTried.add(pick);
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setOverrideForType(new TrackSelectionOverride(gs.get(pick).getMediaTrackGroup(), 0)).build());
            return true;
        } catch (Throwable t) { return false; }
    }
    private long freezePos = -1, freezeSince;
    private String audioKey() { return "nvAud." + Integer.toHexString(url.hashCode()); }

    /** the whole stream stands still (position not moving) although plenty is downloaded */
    private boolean frozenCheck(long now) {
        int st = player.getPlaybackState();
        if (!player.getPlayWhenReady() || (st != Player.STATE_READY && st != Player.STATE_BUFFERING) || "hide".equals(mode)
                || now - movedAt < 3000) { freezeSince = 0; freezePos = -1; return false; }
        long pos = player.getCurrentPosition(), buf = player.getTotalBufferedDuration();
        if (pos != freezePos || buf < 6000) { freezePos = pos; freezeSince = 0; return false; }
        if (freezeSince == 0) { freezeSince = now; return false; }
        if (now - freezeSince < 5000) return false;
        freezeSince = 0; freezePos = -1;
        JSONObject d = new JSONObject();
        try {
            d.put("frozen", true); d.put("pos", pos); d.put("buf", buf); d.put("state", st); d.put("audioMode", audioMode);
            Format a = player.getAudioFormat(); if (a != null) d.put("audio", a.sampleMimeType + " " + a.channelCount + "ch");
        } catch (Exception ignored) { }
        lastStall = "stood still with " + (buf / 1000) + " s downloaded (sound " + (audioMode == 0 ? "passed through" : audioMode == 1 ? "decoded here" : "off") + ")";
        if (audioMode == 1 && tryOtherAudio()) {
            lastStall += "; trying the channel's other sound track";
            try { d.put("next", "other sound track"); } catch (Exception ignored) { }
            out.event(id, "stall", d.toString());
            return true;
        }
        if (audioMode < 2) {
            audioMode++;
            prefs().edit().putInt(audioKey(), Math.min(1, audioMode)).apply();   // (sound off is only for this time)
            if (audioMode == 2) prefs().edit().putLong(audioKey() + ".off", System.currentTimeMillis()).apply();
            try { d.put("next", "sound " + audioMode); } catch (Exception ignored) { }
            out.event(id, "stall", d.toString());
            build(strategy);
        } else if (strategy < 2) {
            try { d.put("next", strategy + 1); } catch (Exception ignored) { }
            out.event(id, "stall", d.toString());
            build(strategy + 1);
        } else out.event(id, "error", "playback stood still: " + d.toString());
        return true;
    }

    private void watchTick() {
        if (player == null) return;
        main.postDelayed(watch, 1000);
        try {
            if (frozenCheck(android.os.SystemClock.elapsedRealtime())) return;
            if (!firstFrame || player.getPlaybackState() != Player.STATE_READY || !player.isPlaying()
                    || "hide".equals(mode) || android.os.SystemClock.elapsedRealtime() - movedAt < 3000) { stallSince = 0; lastRendered = -1; return; }
            DecoderCounters dc = player.getVideoDecoderCounters();
            if (dc == null) return;
            dc.ensureUpdated();
            long r = dc.renderedOutputBufferCount;
            long pos = player.getCurrentPosition();
            long now = android.os.SystemClock.elapsedRealtime();
            if (r != lastRendered || pos == lastPos) {
                lastRendered = r; lastPos = pos; stallSince = 0;
                if (readyAt == 0) readyAt = now;
                else if (now - readyAt > 20000 && strategy < 2 && prefs().getInt("nvStrategy2", 0) != strategy) {
                    prefs().edit().putInt("nvStrategy2", strategy).apply();
                    out.event(id, "strategy", String.valueOf(strategy));
                }
                return;
            }
            lastPos = pos;
            if (stallSince == 0) { stallSince = now; return; }
            if (now - stallSince < 2500) return;
            JSONObject d = new JSONObject();
            d.put("rendered", r); d.put("dropped", dc.droppedBufferCount); d.put("skipped", dc.skippedOutputBufferCount);
            d.put("queued", dc.queuedInputBufferCount); d.put("decoder", decoderName); d.put("size", vw + "x" + vh);
            d.put("pos", pos); d.put("buf", player.getTotalBufferedDuration());
            Format f = player.getVideoFormat();
            if (f != null) { d.put("mime", f.sampleMimeType); d.put("codecs", f.codecs); d.put("fps", f.frameRate); }
            d.put("strategy", strategy);
            lastStall = "way " + strategy + ": " + decoderName + ", rendered " + r + ", dropped " + dc.droppedBufferCount + ", buf " + player.getTotalBufferedDuration() + " ms";
            stallSince = 0;
            if (strategy < 2 && readyAt > 0 && now - readyAt > 10 * 60000) {
                // stalled after playing a long while: start the same way again (resets the decoder's timestamps)
                d.put("restart", true);
                out.event(id, "stall", d.toString());
                build(strategy);
            } else if (strategy < 2) {
                // try the next way of driving the decoder (and remember what works on this TV)
                d.put("next", strategy + 1);
                out.event(id, "stall", d.toString());
                build(strategy + 1);
            } else {
                out.event(id, "error", "picture stopped: " + d.toString());
            }
        } catch (Throwable t) { }
    }

    // ------------------------------------------------------------------ playback

    private android.content.SharedPreferences prefs() { return act.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE); }

    /** a channel's address for the report, without the login an IPTV service puts in it (…/live/user/pass/123.ts, ?username=…&password=…) */
    static String shortSafe(String url) { String u = safe(url); return u.length() > 60 ? u.substring(0, 60) + "…" : u; }
    static String safe(String url) {
        String u = url == null ? "" : url.replaceAll("(?i)(/(?:live|timeshift|movie|series)/)[^/]+/[^/]+/", "$1…/…/")
                .replaceAll("(?i)^(https?://[^/]+/)(?!live/|timeshift/|movie/|series/)[^/]+/[^/]+/(\\d+(?:\\.\\w+)?)$", "$1…/…/$2")
                .replaceAll("(?i)(username|password|token|key)=[^&]*", "$1=…")
                .replaceAll("//[^/@]*@", "//");
        return u;
    }
    void play(int newId, String url, String ua, String kind) {
        picCues = new java.util.ArrayList<>();
        // (a movie's link often carries a debrid key: only its server goes in the log)
        Diag.ev("play " + kind + " " + ("vod".equals(kind) ? Uri.parse(url).getHost() + " (" + url.replaceAll("[?#].*$", "").replaceAll(".*\\.", ".") + ")" : shortSafe(url)));
        id = newId;
        Flight.playAsked("vod".equals(kind) ? Flight.takeVod() : "live channel");
        if (!url.equals(this.url)) afrAsked = false;      // (a new film or channel: its frame rate is looked at again; an added subtitle isn't new)
        this.kind = kind == null ? "" : kind;
        this.url = url; this.ua = ua == null ? "" : ua;
        // each stream starts on the TV's own (hardware) decoder: Android's software decoder (way 2) is
        // only a stopgap for one stream, never what's remembered — on this TV it drops most frames
        // a channel whose sound wouldn't play keeps it off for a day (then it's tried again)
        audioMode = System.currentTimeMillis() - prefs().getLong(audioKey() + ".off", 0) < 24 * 3600000L ? 2 : prefs().getInt(audioKey(), 0);
        build(Math.min(1, prefs().getInt("nvStrategy2", 0)));
    }

    /* Ways of driving the TV's decoder, tried in turn if the picture stalls:
       0 the TV's decoder with small timestamps (MediaTek decoders stalled after a few frames with
         Media3's usual 11-day timestamp offset, while the page's player — timestamps from 0 — played),
       1 the same without asynchronous buffer queueing, 2 Android's software decoder (always works, uses
         more processor). */
    private void build(int strat) {
        // the saved copy being rebuilt (another way of decoding after a stall): carry on from the same moment, not its live end
        if (local() && player != null && pendingWall <= 0) { long w = wallPos(); if (w > 0) { pendingWall = w; startPaused = !player.getPlayWhenReady(); } }
        if (vod() && player != null && vodStartMs <= 0) { long p = player.getCurrentPosition(); if (p > 0) { vodStartMs = p; startPaused = !player.getPlayWhenReady(); } }
        stopPlayer();
        strategy = Math.max(0, Math.min(2, strat));
        history = (history.length() > 60 ? history.substring(history.length() - 60) : history) + strategy;
        firstFrame = false; vw = vh = 0; par = 1; lastCues = ""; retries = 0;
        lastRendered = -1; stallSince = 0; readyAt = 0; freezeSince = 0; freezePos = -1; audioTried.clear();
        setWebClear(true);
        Context c = act;
        meter = new DefaultBandwidthMeter.Builder(c).build();
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 40000, 1200, "vod".equals(kind) ? 2500 : 4000)
                .build();
        DefaultRenderersFactory rf = new SafeRenderers(c, audioMode >= 1)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);
        androidx.media3.exoplayer.MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US = strategy < 2 ? 60_000_000L : 1_000_000_000_000L;
        if (strategy == 1) rf.forceDisableMediaCodecAsynchronousQueueing();
        /* Ways 0 and 1 use only the TV's own video decoder. The library ranks decoders by what they say
           they support, and this TV's MediaTek decoder under-reports (e.g. H.264 High profile, level 4.x),
           so it was choosing Android's software decoder, which drops most frames here. The TV's decoder
           plays these fine (it did in the page's player). */
        if (strategy < 2) rf.setMediaCodecSelector(new MediaCodecSelector() {
            @Override public List<MediaCodecInfo> getDecoderInfos(String mime, boolean secure, boolean tunneling) throws MediaCodecUtil.DecoderQueryException {
                List<MediaCodecInfo> all = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling);
                if (!mime.startsWith("video/")) return all;
                java.util.ArrayList<MediaCodecInfo> hw = new java.util.ArrayList<>();
                StringBuilder sb = new StringBuilder();
                for (MediaCodecInfo i : all) {
                    boolean soft = i.softwareOnly || i.name.startsWith("c2.android.") || i.name.startsWith("OMX.google.");
                    if (!soft) hw.add(i);
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(i.name).append(soft ? " (sw)" : i.hardwareAccelerated ? " (hw)" : " (?)");
                }
                candidates = mime + ": " + (sb.length() == 0 ? "none" : sb.toString());
                return hw.isEmpty() ? all : hw;
            }
        });
        if (strategy >= 2) rf.setMediaCodecSelector(new MediaCodecSelector() {
            @Override public List<MediaCodecInfo> getDecoderInfos(String mime, boolean secure, boolean tunneling) throws MediaCodecUtil.DecoderQueryException {
                List<MediaCodecInfo> all = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling);
                if (!mime.startsWith("video/")) return all;
                java.util.ArrayList<MediaCodecInfo> sw = new java.util.ArrayList<>(), hw = new java.util.ArrayList<>();
                for (MediaCodecInfo i : all) (i.softwareOnly ? sw : hw).add(i);
                sw.addAll(hw);
                return sw;
            }
        });
        player = new ExoPlayer.Builder(c, rf)
                .setLoadControl(load)
                .setBandwidthMeter(meter)
                .setHandleAudioBecomingNoisy(true)
                .build();
        player.addListener(this);
        player.setVideoSurfaceView(sv());
        rateRule();
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onVideoDecoderInitialized(AnalyticsListener.EventTime t, String name, long initializedTimestampMs, long initializationDurationMs) {
                decoderName = name;
                boolean sw = name != null && (name.startsWith("c2.android.") || name.startsWith("OMX.google."));
                if (sw && strategy < 2) out.event(id, "swdecoder", name);    // the TV's decoder was busy or refused
            }
        });
        main.removeCallbacks(watch);
        main.postDelayed(watch, 1000);
        String lang = act.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE).getString("liveAudLangN", "");
        TrackSelectionParameters.Builder tp = player.getTrackSelectionParameters().buildUpon();
        if (!lang.isEmpty()) tp.setPreferredAudioLanguage(lang);
        tp.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, audioMode >= 2);
        if ("vod".equals(kind)) {
            String sl = act.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE).getString("vodSubLang", "");
            if (!sl.isEmpty()) tp.setPreferredTextLanguage(sl).setSelectUndeterminedTextLanguage(false);
        }
        player.setTrackSelectionParameters(tp.build());

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(20000)
                .setUserAgent(!this.ua.isEmpty() ? this.ua : LiveTv.DEFAULT_UA);
        Map<String, String> hdr = new HashMap<>();
        hdr.put("Accept", "*/*");
        if ("vod".equals(kind)) {
            hdr.putAll(vodHeaders);
            String ua2 = vodHeaders.get("User-Agent");
            if (ua2 != null && !ua2.isEmpty()) http.setUserAgent(ua2);
        }
        http.setDefaultRequestProperties(hdr);
        boolean local = this.url.startsWith("file:");
        MediaItem item = local
                // the TV's own copy (pause & rewind, recordings): played at normal speed wherever you are in it
                // (the library would otherwise speed up a little to catch up with "live")
                ? new MediaItem.Builder().setUri(Uri.parse(this.url)).setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                        .setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).setTargetOffsetMs(6000).build()).build()
                : MediaItem.fromUri(Uri.parse(this.url));
        MediaSource src;
        if ("vod".equals(kind)) {
            // a movie or an episode (an addon's stream: usually an MKV or MP4 file from a debrid service), with any
            // subtitles the addons found added as extra tracks
            MediaItem.Builder b = new MediaItem.Builder().setUri(Uri.parse(this.url));
            List<MediaItem.SubtitleConfiguration> subs = new java.util.ArrayList<>();
            for (String[] sx : vodSubs) {
                String mime = sx[0].toLowerCase(java.util.Locale.US).contains(".vtt") ? androidx.media3.common.MimeTypes.TEXT_VTT : androidx.media3.common.MimeTypes.APPLICATION_SUBRIP;
                subs.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(sx[0])).setMimeType(mime).setLanguage(sx[1]).setLabel(sx[2]).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build());
            }
            b.setSubtitleConfigurations(subs);
            androidx.media3.extractor.DefaultExtractorsFactory ex = new androidx.media3.extractor.DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true);
            src = new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(new androidx.media3.datasource.DefaultDataSource.Factory(act, http), ex).createMediaSource(b.build());
            startAtMs = vodStartMs;
            vodStartMs = 0;
        } else if (local) {
            src = new HlsMediaSource.Factory(new DriveAuth(new androidx.media3.datasource.DefaultDataSource.Factory(act, http))).setExtractorFactory(SafeTs.hls()).setAllowChunklessPreparation(true).createMediaSource(item);
        } else if ("hls".equals(kind)) {
            src = new HlsMediaSource.Factory(http).setExtractorFactory(SafeTs.hls()).setAllowChunklessPreparation(true).setLoadErrorHandlingPolicy(new LiveRetry()).createMediaSource(item);
        } else {
            SafeTs ex = new SafeTs(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                    | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS, 1500 * 188);
            src = new ProgressiveMediaSource.Factory(http, ex).setLoadErrorHandlingPolicy(new LiveRetry()).createMediaSource(item);
        }
        /* (a movie carrying on: the position goes in with the source — a seek made before it was set was
           thrown away when the source replaced the playlist, so Resume started from the beginning) */
        if (startAtMs > 0) player.setMediaSource(src, startAtMs); else player.setMediaSource(src);
        startAtMs = 0;
        player.setPlayWhenReady(!startPaused); startPaused = false;
        player.prepare();
    }

    /* A live channel's piece that a busy provider refuses (509 "too many streams", 429, 5xx) is asked for again
       a moment later, up to a dozen times, while what's buffered keeps playing: the stream used to give up
       after three quick tries (about 3 seconds), and starting the channel over made it worse. */
    static final class LiveRetry extends androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy {
        LiveRetry() { super(12); }
        @Override public long getRetryDelayMsFor(androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo info) {
            java.io.IOException e = info.exception;
            if (e instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                int c = ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) e).responseCode;
                if (c == 509 || c == 458 || c == 429 || c >= 500) return Math.min(800L + 700L * info.errorCount, 4000L);
            }
            return super.getRetryDelayMsFor(info);
        }
    }

    // ------------------------------------------------------------------ movies and episodes (addons' streams)

    private List<String[]> vodSubs = new java.util.ArrayList<>();      // [url, language, label]
    private Map<String, String> vodHeaders = new HashMap<>();
    private long vodStartMs, startAtMs;
    /** plays a movie or an episode from a moment (ms), with subtitles from the addons */
    void playVod(int newId, String url, long startMs, List<String[]> subs, Map<String, String> headers) {
        vodSubs = subs == null ? new java.util.ArrayList<String[]>() : subs;
        vodHeaders = headers == null ? new HashMap<String, String>() : headers;
        vodStartMs = startMs;
        play(newId, url, "", "vod");
    }
    long positionMs() { return player == null ? 0 : Math.max(0, player.getCurrentPosition()); }
    long durationMs() { if (player == null) return 0; long d = player.getDuration(); return d == C.TIME_UNSET ? 0 : d; }
    long bufferedMs() { return player == null ? 0 : player.getBufferedPosition(); }
    void seekMs(long ms) { if (player != null) player.seekTo(Math.max(0, ms)); }
    /** scrubbing: to the nearest key frame (quick), until scrubDone() puts exact seeking back */
    void scrubSeek(long ms) { if (player == null) return; player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC); player.seekTo(Math.max(0, ms)); }
    void scrubDone() { if (player != null) player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.DEFAULT); }
    boolean vod() { return "vod".equals(kind); }

    // ------------------------------------------------------------------ the TV's own copy (pause & rewind)

    private long pendingWall; private boolean startPaused;
    /** play a saved copy (a local playlist) from a moment (wall clock, ms; 0 = its live edge) */
    void playLocal(int newId, String file, long wall, boolean paused) {
        pendingWall = wall; startPaused = paused;
        play(newId, file, "", "hls");
        startPaused = false;
    }
    boolean local() { return url.startsWith("file:"); }
    void setPaused(boolean p) { afrHolding = false; if (player != null) player.setPlayWhenReady(!p); }
    boolean paused() { return player != null && !player.getPlayWhenReady(); }
    /** where playback is, as wall-clock time (ms), or 0 if not known */
    long wallPos() {
        if (player == null) return 0;
        try {
            androidx.media3.common.Timeline tl = player.getCurrentTimeline();
            if (tl.isEmpty()) return 0;
            androidx.media3.common.Timeline.Window w = tl.getWindow(player.getCurrentMediaItemIndex(), new androidx.media3.common.Timeline.Window());
            if (w.windowStartTimeMs == C.TIME_UNSET) return 0;
            return w.windowStartTimeMs + player.getCurrentPosition();
        } catch (Throwable t) { return 0; }
    }
    /** how far behind the stream's live edge playback is (ms), or -1 */
    long liveOffset() {
        try {
            if (player == null) return -1;
            long o = player.getCurrentLiveOffset();
            if (o != C.TIME_UNSET) return o;
            // (a live stream without clock times: how far from the end of what the stream's playlist offers)
            if (player.isCurrentMediaItemLive()) { long d = player.getDuration(), p = player.getCurrentPosition(); if (d != C.TIME_UNSET && d > p) return d - p; }
            return -1;
        } catch (Throwable t) { return -1; }
    }
    /** jump to a moment of the saved copy (wall clock, ms) */
    void seekWall(long wall) {
        if (player == null) return;
        try {
            androidx.media3.common.Timeline tl = player.getCurrentTimeline();
            if (tl.isEmpty()) { pendingWall = wall; return; }
            androidx.media3.common.Timeline.Window w = tl.getWindow(player.getCurrentMediaItemIndex(), new androidx.media3.common.Timeline.Window());
            if (w.windowStartTimeMs == C.TIME_UNSET) { pendingWall = wall; return; }
            long pos = Math.max(0, Math.min(wall - w.windowStartTimeMs, Math.max(0, w.getDurationMs() - 3000)));
            player.seekTo(pos);
        } catch (Throwable ignored) { }
    }
    @Override public void onTimelineChanged(androidx.media3.common.Timeline tl, int reason) {
        if (pendingWall <= 0 || player == null || tl.isEmpty()) return;
        androidx.media3.common.Timeline.Window w = tl.getWindow(player.getCurrentMediaItemIndex(), new androidx.media3.common.Timeline.Window());
        if (w.windowStartTimeMs == C.TIME_UNSET) return;
        long target = pendingWall; pendingWall = 0;
        seekWall(target);
    }

    void stop() {
        Flight.playStopped();
        Diag.ev("video stopped");
        stopPlayer();
        unmatchRate();
        mode = "hide";
        fit();
        clearRate();
        /* The picture's plane is taken away once nothing plays. Even 1×1 and empty, a video plane under the
           app's window made the TV combine the window and the plane for every frame, and every tab ran about
           three times slower for the rest of the session after watching anything. It's made again on play. */
        /* (Taken away a moment later, once the screen has settled: removing it in the middle of a busy change,
           like leaving full screen for Home, seemed to make the TV's display hardware misplace the picture.) */
        main.removeCallbacks(dropPlane);
        main.postDelayed(dropPlane, 2500);
        setWebClear(false);
    }

    private void stopPlayer() {
        if (player != null) {
            try { main.removeCallbacks(watch); player.removeListener(this); player.stop(); player.clearVideoSurface(); player.release(); } catch (Throwable t) {}
            act.keepAwake(false);
            player = null;
        }
        firstFrame = false;
    }

    void setVolume(float v) { if (player != null) player.setVolume(v); }

    // ------------------------------------------------------------------ events

    @Override public void onRenderedFirstFrame() {
        firstFrame = true;
        Flight.firstFrame();
        out.event(id, "ready", vw + "x" + vh);
    }

    @Override public void onVideoSizeChanged(VideoSize s) {
        vw = s.width; vh = s.height; par = s.pixelWidthHeightRatio > 0 ? s.pixelWidthHeightRatio : 1f;
        fit();
        out.event(id, "size", vw + "x" + vh);
    }

    /* While something plays, the TV mustn't dim or start its screensaver (nothing tells it a video is on: we
       draw the picture ourselves, not with a player view that would) */
    @Override public void onIsPlayingChanged(boolean playing) {
        act.keepAwake(playing);
        if (playing) Flight.paused(false); else if (player != null && player.getPlaybackState() == Player.STATE_READY) Flight.paused(true);
    }
    @Override public void onPlaybackStateChanged(int state) {
        if (state == Player.STATE_BUFFERING) { out.event(id, "buffering", ""); Flight.buffering(true); }
        else if (state == Player.STATE_READY) { out.event(id, "playing", ""); Flight.buffering(false); }
        else if (state == Player.STATE_ENDED) out.event(id, vod() ? "ended" : "error", "stream ended");
    }

    @Override public void onPlayerError(PlaybackException e) {
        Diag.ev("video error " + e.getErrorCodeName() + ": " + e.getMessage());
        if (player == null) return;
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && retries++ < 3) {
            player.seekToDefaultPosition();
            player.prepare();
            return;
        }
        // a playing stream that hiccups (a segment that didn't load…) carries on from the live edge
        if (firstFrame && retries++ < 4 && (e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                || e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                || e.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                || e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                || e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)) {
            final ExoPlayer p = player;
            // (a movie carries on from where it was; a live channel from its live edge)
            final long at = vod() ? p.getCurrentPosition() : -1;
            main.postDelayed(new Runnable() { @Override public void run() { if (player == p) { if (at >= 0) p.seekTo(at); else p.seekToDefaultPosition(); p.prepare(); } } }, 1000L * retries);
            return;
        }
        // the TV's Dolby Vision decoder gave up: play the HDR10 picture underneath instead
        if (vod() && !SafeRenderers.dvAsHevc && (e.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED || e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                || e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES || e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
                && String.valueOf(e.getMessage()).contains("dolby-vision") || (vod() && !SafeRenderers.dvAsHevc && describe(e).contains("dvav"))) {
            SafeRenderers.dvAsHevc = true;
            Diag.ev("video: Dolby Vision decoder failed, trying the HDR10 picture");
            final long at = player.getCurrentPosition();
            main.post(new Runnable() { @Override public void run() { if (player != null) { vodStartMs = Math.max(at, vodStartMs); build(strategy); } } });
            return;
        }
        if (vod() && !firstFrame && retries++ < 1 && (e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT || e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)) {
            final ExoPlayer p = player;
            main.postDelayed(new Runnable() { @Override public void run() { if (player == p) p.prepare(); } }, 1500);
            return;
        }
        out.event(id, "error", describe(e));
    }

    /** the error with what actually went wrong underneath (the library wraps it) */
    static String describe(PlaybackException e) {
        StringBuilder sb = new StringBuilder(e.getErrorCodeName());
        Throwable c = e.getCause();
        for (int depth = 0; c != null && depth < 4; depth++, c = c.getCause()) {
            sb.append(" ← ").append(c.getClass().getSimpleName());
            if (c.getMessage() != null) sb.append(": ").append(c.getMessage().length() > 120 ? c.getMessage().substring(0, 120) : c.getMessage());
            StackTraceElement[] st = c.getStackTrace();
            if (st != null && st.length > 0 && c.getCause() == null) sb.append(" @ ").append(st[0].getClassName().replaceAll(".*\\.", "")).append('.').append(st[0].getMethodName()).append(':').append(st[0].getLineNumber());
        }
        return sb.toString();
    }

    @Override public void onTracksChanged(Tracks t) { out.event(id, "tracks", ""); if (!afrAsked) matchRate(); }

    /* Matching the TV's refresh rate to the film (24 fps films at 24 Hz, not 60: no judder), as Nuvio does: Android's
       own setting only switches when it can do so "seamlessly", which HDMI never is, so films played at 60 Hz. The
       screen goes dark for a moment while the TV changes over, so playback waits paused for it; back to the TV's
       usual mode when the film stops. (Settings › Movies & TV shows › Match frame rate.) */
    private boolean afrAsked, afrHolding, afrWas;
    private int afrMode;
    private boolean matchRateOn() {
        try { return new JSONObject(prefs().getString("ui.settings", "{}")).optBoolean("matchRate", true); } catch (Exception e) { return true; }
    }
    private void matchRate() {
        if (player == null || !"fs".equals(mode) || android.os.Build.VERSION.SDK_INT < 23) return;
        if (!matchRateOn()) { afrAsked = true; return; }
        Format f = player.getVideoFormat();
        if (f == null || f.frameRate <= 0) return;               // (not known yet: asked again on the next change)
        afrAsked = true;
        try {
            android.view.Window w = act.getWindow();
            android.view.Display d = w.getDecorView().getDisplay();
            if (d == null) return;
            android.view.Display.Mode cur = d.getMode(), best = null;
            double fps = f.frameRate, bestErr = 9; int bestK = 99;
            for (android.view.Display.Mode m : d.getSupportedModes()) {
                if (m.getPhysicalWidth() != cur.getPhysicalWidth() || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
                double q = m.getRefreshRate() / fps; int k = (int) Math.round(q);
                if (k < 1 || k > 5) continue;
                double e = Math.abs(q - k);
                if (e < bestErr - 1e-4 || (Math.abs(e - bestErr) <= 1e-4 && k < bestK)) { best = m; bestErr = e; bestK = k; }
            }
            double cq = cur.getRefreshRate() / fps; double curErr = Math.round(cq) < 1 ? 9 : Math.abs(cq - Math.round(cq));
            /* a live channel switches only when the TV's rate is clearly wrong for it (50 fps at 60 Hz): every switch
               blanks the screen for a moment, which isn't worth it for 59.94 against 60 while changing channels */
            boolean live = !vod();
            if (best == null || bestErr > .002 || best.getModeId() == cur.getModeId() || curErr <= bestErr + 1e-4 || (live && curErr < .01)) {
                Diag.ev("video: " + String.format(java.util.Locale.US, "%.3f", fps) + " fps at " + String.format(java.util.Locale.US, "%.2f", cur.getRefreshRate()) + " Hz (no better mode)");
                return;
            }
            Diag.ev("video: " + String.format(java.util.Locale.US, "%.3f", fps) + " fps: switching the TV from " + String.format(java.util.Locale.US, "%.2f", cur.getRefreshRate()) + " to " + String.format(java.util.Locale.US, "%.2f", best.getRefreshRate()) + " Hz");
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.preferredDisplayModeId = best.getModeId();
            w.setAttributes(lp);
            afrMode = best.getModeId();
            final ExoPlayer p = player; final boolean was = p.getPlayWhenReady(); final int target = best.getModeId();
            final long t0 = android.os.SystemClock.uptimeMillis();
            afrHolding = true; afrWas = was;
            p.setPlayWhenReady(false);
            main.post(new Runnable() { @Override public void run() {
                if (player != p || !afrHolding) return;
                android.view.Display dd = act.getWindow().getDecorView().getDisplay();
                boolean there = dd != null && dd.getMode().getModeId() == target;
                long t = android.os.SystemClock.uptimeMillis() - t0;
                if ((there && t > 600) || t > 5000) {
                    main.postDelayed(new Runnable() { @Override public void run() { if (player == p && afrHolding) { afrHolding = false; p.setPlayWhenReady(was); } } }, there ? 900 : 0);
                    return;
                }
                main.postDelayed(this, 200);
            } });
        } catch (Throwable t) { Diag.ev("video: frame rate switch failed " + t.getMessage()); }
    }
    /** the TV back to its usual mode */
    private void unmatchRate() {
        if (afrHolding && player != null) player.setPlayWhenReady(afrWas);     // (left mid-switch: playback carries on)
        afrHolding = false;
        if (afrMode == 0) return;
        afrMode = 0;
        try { android.view.WindowManager.LayoutParams lp = act.getWindow().getAttributes(); lp.preferredDisplayModeId = 0; act.getWindow().setAttributes(lp); } catch (Throwable ignored) { }
    }

    /* subtitles that are pictures (a Blu-ray's or DVD's): the player can't draw them itself here, so they're
       kept for the screen to draw ("cuesimg"); they used to be dropped, and those films showed no subtitles */
    private volatile List<Cue> picCues = new java.util.ArrayList<>();
    List<Cue> pictureCues() { return picCues; }
    @Override public void onCues(CueGroup g) {
        List<Cue> pics = new java.util.ArrayList<>();
        for (Cue c : g.cues) if (c.bitmap != null) pics.add(c);
        if (!pics.isEmpty() || !picCues.isEmpty()) { picCues = pics; out.event(id, "cuesimg", String.valueOf(pics.size())); }
        StringBuilder sb = new StringBuilder();
        for (Cue c : g.cues) {
            if (c.text == null) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(c.text.toString());
        }
        String s = sb.toString();
        if (s.equals(lastCues)) return;
        lastCues = s;
        out.event(id, "cues", s);
    }

    // ------------------------------------------------------------------ tracks & info

    /** {audio:[{i,name,lang,on}], text:[...], video:[{i,h,br,on}], auto, textOn} */
    String tracks() {
        JSONObject o = new JSONObject();
        try {
            JSONArray au = new JSONArray(), tx = new JSONArray(), vi = new JSONArray();
            if (player != null) {
                List<Tracks.Group> gs = player.getCurrentTracks().getGroups();
                for (int gi = 0; gi < gs.size(); gi++) {
                    Tracks.Group g = gs.get(gi);
                    for (int ti = 0; ti < g.length; ti++) {
                        if (!g.isTrackSupported(ti)) continue;
                        Format f = g.getTrackFormat(ti);
                        JSONObject t = new JSONObject();
                        t.put("g", gi); t.put("t", ti); t.put("on", g.isTrackSelected(ti));
                        t.put("lang", f.language == null ? "" : f.language);
                        t.put("name", f.label == null ? "" : f.label);
                        if (g.getType() == C.TRACK_TYPE_AUDIO) { t.put("ch", f.channelCount); au.put(t); }
                        else if (g.getType() == C.TRACK_TYPE_TEXT) { t.put("cc", f.sampleMimeType != null && f.sampleMimeType.contains("cea")); tx.put(t); }
                        else if (g.getType() == C.TRACK_TYPE_VIDEO) { t.put("h", f.height); t.put("br", Math.max(f.bitrate, 0)); vi.put(t); }
                    }
                }
                TrackSelectionParameters p = player.getTrackSelectionParameters();
                boolean auto = true;
                for (TrackSelectionOverride ov : p.overrides.values()) if (ov.getType() == C.TRACK_TYPE_VIDEO) auto = false;
                o.put("auto", auto);
                o.put("textOn", !p.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT));
            }
            o.put("audio", au); o.put("text", tx); o.put("video", vi);
        } catch (Exception e) {}
        return o.toString();
    }

    /** type: audio | text | video; g/t = group and track (-1 = off for text, auto for video). */
    void select(String type, int g, int t) {
        if (player == null) return;
        int tt = "audio".equals(type) ? C.TRACK_TYPE_AUDIO : "text".equals(type) ? C.TRACK_TYPE_TEXT : C.TRACK_TYPE_VIDEO;
        TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon();
        if (g < 0) {
            b.clearOverridesOfType(tt);
            if (tt == C.TRACK_TYPE_TEXT) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
        } else {
            List<Tracks.Group> gs = player.getCurrentTracks().getGroups();
            if (g >= gs.size()) return;
            TrackGroup tg = gs.get(g).getMediaTrackGroup();
            b.setTrackTypeDisabled(tt, false);
            b.setOverrideForType(new TrackSelectionOverride(tg, t));
            if (tt == C.TRACK_TYPE_AUDIO) {
                String l = tg.getFormat(t).language;
                if (l != null) { b.setPreferredAudioLanguage(l); act.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE).edit().putString("liveAudLangN", l).apply(); }
            }
        }
        player.setTrackSelectionParameters(b.build());
        if (tt == C.TRACK_TYPE_TEXT && g < 0) { lastCues = ""; out.event(id, "cues", ""); }
    }

    String stats() {
        JSONObject o = new JSONObject();
        try {
            if (player == null) return "{}";
            Format v = player.getVideoFormat(), a = player.getAudioFormat();
            o.put("kind", kind);
            o.put("w", vw); o.put("h", vh);
            if (v != null) {
                o.put("vcodec", v.codecs != null ? v.codecs : v.sampleMimeType);
                if (v.frameRate > 0) o.put("fps", v.frameRate);
                if (v.bitrate > 0) o.put("br", v.bitrate);
            }
            if (a != null) o.put("acodec", (a.codecs != null ? a.codecs : a.sampleMimeType) + (a.channelCount > 0 ? " · " + a.channelCount + "ch" : ""));
            if (meter != null) o.put("bw", meter.getBitrateEstimate());
            o.put("buf", Math.max(0, player.getTotalBufferedDuration()) / 1000.0);
            DecoderCounters dc = player.getVideoDecoderCounters();
            if (dc != null) { dc.ensureUpdated(); o.put("dropped", dc.droppedBufferCount); o.put("total", dc.renderedOutputBufferCount + dc.droppedBufferCount + dc.skippedOutputBufferCount); }
            o.put("decoder", decoderName); o.put("strategy", strategy); o.put("sound", audioMode);
            o.put("cands", candidates); o.put("stall", lastStall); o.put("ways", history);
        } catch (Exception e) {}
        return o.toString();
    }

    /** a still of the current picture (the page shows it while the preview zooms) */
    private android.os.HandlerThread copyThread;
    String snapshot() {
        if (sv == null || !firstFrame || player == null) return "";
        try {
            if (copyThread == null) { copyThread = new android.os.HandlerThread("nvcopy"); copyThread.start(); }
            final Bitmap b = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888);
            final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            final int[] res = { -1 };
            android.view.PixelCopy.request(sv, b, new android.view.PixelCopy.OnPixelCopyFinishedListener() {
                @Override public void onPixelCopyFinished(int r) { res[0] = r; done.countDown(); }
            }, new Handler(copyThread.getLooper()));
            done.await(250, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (res[0] != android.view.PixelCopy.SUCCESS) { b.recycle(); return ""; }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 78, bo);
            b.recycle();
            return "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) { return ""; }
    }

    boolean active() { return player != null; }
    /** for the report: what the player is doing */
    JSONObject state() {
        JSONObject o = new JSONObject();
        try {
            o.put("kind", kind).put("mode", mode).put("firstFrame", firstFrame).put("decoder", decoderName).put("dvAsHevc", SafeRenderers.dvAsHevc);
            if (sv != null) o.put("plane", sv.getWidth() + "x" + sv.getHeight() + (sv.getVisibility() == View.VISIBLE ? "" : " hidden"));
            if (player != null) {
                int st = player.getPlaybackState();
                o.put("state", st == Player.STATE_IDLE ? "idle" : st == Player.STATE_BUFFERING ? "buffering" : st == Player.STATE_READY ? "ready" : "ended")
                 .put("playWhenReady", player.getPlayWhenReady()).put("playing", player.isPlaying())
                 .put("posMs", player.getCurrentPosition()).put("bufferedAheadMs", player.getTotalBufferedDuration())
                 .put("mbps", meter == null ? 0 : Math.round(meter.getBitrateEstimate() / 100000.0) / 10.0);
                Format vf = player.getVideoFormat(), af = player.getAudioFormat();
                if (vf != null) o.put("video", vf.sampleMimeType + " " + vf.codecs + " " + vf.width + "x" + vf.height + " " + (vf.bitrate > 0 ? vf.bitrate / 1000000 + " Mbps" : ""));
                if (af != null) o.put("audio", af.sampleMimeType + " " + af.codecs + " " + af.channelCount + "ch");
                DecoderCounters dc = player.getVideoDecoderCounters();
                if (dc != null) { dc.ensureUpdated(); o.put("frames", "queued " + dc.queuedInputBufferCount + ", rendered " + dc.renderedOutputBufferCount + ", dropped " + dc.droppedBufferCount + ", skipped " + dc.skippedOutputBufferCount); }
                PlaybackException pe = player.getPlayerError();
                if (pe != null) o.put("error", describe(pe));
            }
        } catch (Throwable ignored) { }
        return o;
    }

    /* Some channels (ESPN U) send a format update part-way through that's missing its codec type; the
       library then stops with "Sample MIME type is null". The decoders here keep the codec they already
       know for such an update. */
    static final class SafeRenderers extends DefaultRenderersFactory {
        private final boolean plainSound;
        SafeRenderers(Context c) { this(c, false); }
        SafeRenderers(Context c, boolean plainSound) { super(c); this.plainSound = plainSound; }
        /** plain sound: Dolby and the like are decoded here instead of being passed to the TV as is */
        @SuppressWarnings("deprecation")
        @Override protected androidx.media3.exoplayer.audio.AudioSink buildAudioSink(Context context, boolean enableFloat, boolean enableParams) {
            if (!plainSound) return super.buildAudioSink(context, enableFloat, enableParams);
            return new androidx.media3.exoplayer.audio.DefaultAudioSink.Builder()
                    .setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .setEnableFloatOutput(enableFloat).setEnableAudioTrackPlaybackParams(enableParams).build();
        }
        private static androidx.media3.exoplayer.FormatHolder mend(androidx.media3.exoplayer.FormatHolder fh, Format[] last) {
            Format f = fh.format;
            if (f == null) return fh;
            if (f.sampleMimeType == null && last[0] != null)
                fh.format = f.buildUpon().setSampleMimeType(last[0].sampleMimeType).setCodecs(f.codecs != null ? f.codecs : last[0].codecs).build();
            else if (f.sampleMimeType != null) last[0] = f;
            return fh;
        }
        /* Dolby Vision this TV can't decode (profile 7, the UHD Blu-ray kind; any profile once the TV's
           Dolby Vision decoder has failed): its picture underneath is ordinary HDR10 HEVC, so the TV's HEVC
           decoder plays that (the Dolby Vision layer is skipped). */
        static volatile boolean dvAsHevc;
        static Format hevcFor(Format f) {
            if (f == null || !"video/dolby-vision".equals(f.sampleMimeType) || f.codecs == null) return f;
            String cd = f.codecs.toLowerCase(java.util.Locale.US);
            boolean p7 = cd.matches("dv(he|h1)\\.0?7.*"), hevcBase = cd.matches("dv(he|h1)\\.0?[478].*");
            if (!(p7 || (dvAsHevc && hevcBase))) return f;
            return f.buildUpon().setSampleMimeType("video/hevc").setCodecs(null).build();
        }
        /** removes HEVC NAL units of type 62/63 from an Annex-B sample (true if it removed any) */
        static boolean stripDvNals(java.nio.ByteBuffer d) {
            int n = d.limit() - d.position();
            if (n < 6) return false;
            int p0 = d.position();
            byte[] a = new byte[n];
            d.get(a); d.position(p0);
            byte[] out = null; int len = 0;
            int i = 0, cur = -1; boolean curDrop = false, any = false;
            // walk the start codes
            java.util.ArrayList<int[]> units = new java.util.ArrayList<>();      // {start of start code, nal type}
            while (i + 3 < n) {
                if (a[i] == 0 && a[i + 1] == 0 && a[i + 2] == 1) {
                    int sc = (i > 0 && a[i - 1] == 0) ? i - 1 : i;
                    units.add(new int[] { sc, (a[i + 3] >> 1) & 0x3F });
                    i += 3;
                } else i++;
            }
            if (units.isEmpty() || units.get(0)[0] > 1) return false;            // (not start codes: left alone)
            for (int[] u : units) if (u[1] == 62 || u[1] == 63) { any = true; break; }
            if (!any) return false;
            out = new byte[n];
            for (int k = 0; k < units.size(); k++) {
                int st = units.get(k)[0], en = k + 1 < units.size() ? units.get(k + 1)[0] : n;
                int t = units.get(k)[1];
                if (t == 62 || t == 63) continue;
                System.arraycopy(a, st, out, len, en - st); len += en - st;
            }
            d.position(p0); d.limit(p0 + len);
            d.put(out, 0, len);
            d.position(p0);
            return true;
        }
        @Override protected void buildVideoRenderers(Context context, int extensionRendererMode, MediaCodecSelector selector, boolean fallback,
                Handler handler, androidx.media3.exoplayer.video.VideoRendererEventListener listener, long joiningMs, java.util.ArrayList<androidx.media3.exoplayer.Renderer> out) {
            final Format[] last = new Format[1];
            out.add(new androidx.media3.exoplayer.video.MediaCodecVideoRenderer(context, getCodecAdapterFactory(), selector, joiningMs, fallback, handler, listener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY) {
                @Override protected androidx.media3.exoplayer.DecoderReuseEvaluation onInputFormatChanged(androidx.media3.exoplayer.FormatHolder fh) throws androidx.media3.exoplayer.ExoPlaybackException {
                    mend(fh, last);
                    Format g = hevcFor(fh.format);
                    if (g != fh.format) { if (!dvNoted) { dvNoted = true; Diag.ev("video: Dolby Vision " + fh.format.codecs + " played as HDR10"); } fh.format = g; strip = true; }
                    else if (fh.format != null && fh.format.sampleMimeType != null) strip = false;
                    return super.onInputFormatChanged(fh);
                }
                private boolean strip;
                /* Dolby Vision's own layers (its enhancement layer and metadata, NAL units 62 and 63) are taken out
                   before the TV's HEVC decoder sees them: this TV's decoder stalled on them (no picture, no error) */
                @Override protected void onQueueInputBuffer(androidx.media3.decoder.DecoderInputBuffer b) throws androidx.media3.exoplayer.ExoPlaybackException {
                    if (strip && b.data != null && !b.isEncrypted()) { try { if (stripDvNals(b.data) && !stripNoted) { stripNoted = true; Diag.ev("video: Dolby Vision layers taken out for the HDR10 picture"); } } catch (Throwable ignored) { } }
                    super.onQueueInputBuffer(b);
                }
                private boolean stripNoted;
                private boolean dvNoted;
                @Override protected int supportsFormat(MediaCodecSelector sel, Format f) throws MediaCodecUtil.DecoderQueryException { return super.supportsFormat(sel, hevcFor(f)); }
                @Override protected List<MediaCodecInfo> getDecoderInfos(MediaCodecSelector sel, Format f, boolean secure) throws MediaCodecUtil.DecoderQueryException { return super.getDecoderInfos(sel, hevcFor(f), secure); }
            });
        }
        @Override protected void buildAudioRenderers(Context context, int extensionRendererMode, MediaCodecSelector selector, boolean fallback,
                androidx.media3.exoplayer.audio.AudioSink sink, Handler handler, androidx.media3.exoplayer.audio.AudioRendererEventListener listener, java.util.ArrayList<androidx.media3.exoplayer.Renderer> out) {
            final Format[] last = new Format[1];
            out.add(new androidx.media3.exoplayer.audio.MediaCodecAudioRenderer(context, getCodecAdapterFactory(), selector, fallback, handler, listener, sink) {
                @Override protected androidx.media3.exoplayer.DecoderReuseEvaluation onInputFormatChanged(androidx.media3.exoplayer.FormatHolder fh) throws androidx.media3.exoplayer.ExoPlaybackException {
                    return super.onInputFormatChanged(mend(fh, last));
                }
            });
        }
    }
    String diag() { return "strategy " + strategy + " (saved " + prefs().getInt("nvStrategy2", 0) + ", ways tried " + history + "), decoder " + decoderName + ", mode " + mode + ", candidates " + candidates + ", last stall " + lastStall + ", damaged frames skipped " + SafeTs.dropped; }
}
