package com.topshelf.launcher;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * Trailer previews for the native Movies & TV Shows screens. Like Nuvio, the trailer's video is
 * fetched straight from YouTube (YtStreams) and played in the app's own video player, shown from
 * its very first frame. If that can't be done (YouTube asks some connections to prove they're not a bot),
 * YouTube's own embedded player is used instead: one small web view, made once and moved to wherever
 * a trailer plays, zoomed in enough that its title bar and logo sit outside the frame, so it can be
 * shown about a second after it starts (it used to wait 4½ s for them to go). Muted unless Settings →
 * Trailer sound is on.
 */
@SuppressWarnings("deprecation")
final class TrailerPlayer {
    interface Listener { void playing(); void ended(); }

    private static final String PAGE =
        "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'>" +
        "<style>html,body{margin:0;background:#000;overflow:hidden;height:100%}#p{position:absolute;inset:0}iframe{position:absolute;border:0;pointer-events:none}</style></head>" +
        "<body><div id=p></div><script>" +
        "var P=null,want=null,muted=true,ready=false,gen=0,nlog=0,CUT=0,TAIL=0;function L(m){if(nlog++<14)try{TS.ev(m)}catch(x){}}" +
        "function size(){var f=document.querySelector('iframe');if(!f)return;var W=document.documentElement.clientWidth||innerWidth,H=document.documentElement.clientHeight||innerHeight;if(!W||!H)return;var s=Math.max(W/16,H/9)*1.42;" +
        " f.style.width=16*s+'px';f.style.height=9*s+'px';f.style.left=(W-16*s)/2+'px';f.style.top=(H-9*s)/2+'px';}" +
        "onresize=size;setInterval(function(){if(want)size();},700);" +
        // (YouTube uploads often run on into a channel outro: the trailer counts as over where it starts, or TAIL s from the end)
        "setInterval(function(){try{if(!want||!ready||!P||P.getPlayerState()!==1)return;var d=P.getDuration(),t=P.getCurrentTime(),c=CUT>0?CUT:(TAIL>0&&d>TAIL*4?d-TAIL:0);" +
        " if(c>0&&t>=c){want=null;gen++;P.mute();P.pauseVideo();L('cut at '+Math.round(t)+' of '+Math.round(d)+' s');TS.ended();}}catch(x){}},300);" +
        "function setCut(v,c){if(want===v)CUT=c;}" +
        "function make(v){P=new YT.Player('p',{host:'https://www.youtube-nocookie.com',videoId:v,width:innerWidth,height:innerHeight," +
        " playerVars:{autoplay:1,mute:1,controls:0,disablekb:1,fs:0,iv_load_policy:3,modestbranding:1,playsinline:1,rel:0,cc_load_policy:0,origin:location.origin}," +
        " events:{onReady:function(e){ready=true;L('ready');size();if(!want){e.target.pauseVideo();return;}if(want!==v)e.target.loadVideoById({videoId:want});if(muted)e.target.mute();else e.target.unMute();e.target.playVideo();" +
        "   setTimeout(function(){try{if(want&&P.getPlayerState()!==1){P.mute();P.playVideo();}}catch(x){}},4000);}," +
        "  onStateChange:function(e){L('state '+e.data);if(!want){if(e.data===1)e.target.pauseVideo();return;}if(e.data===1){size();var g=++gen;setTimeout(function(){if(want&&g===gen&&P.getPlayerState()===1){size();TS.playing();}},1300);}else if(e.data===0||e.data===2){gen++;TS.ended();}}," +
        "  onError:function(e){L('error '+e.data);TS.ended();}}});}" +
        "function onYouTubeIframeAPIReady(){if(want)make(want);}" +
        "function play(v,m,c,t){want=v;muted=m;CUT=c||0;TAIL=t||0;L('play '+(window.YT&&YT.Player?(P?(ready?'ready':'starting'):'new'):'no api yet'));if(!window.YT||!YT.Player)return;if(!P){make(v);return;}if(!ready)return;" +
        " try{P.loadVideoById({videoId:v});if(m)P.mute();else P.unMute();}catch(e){}}" +
        "function stop(){want=null;try{if(P&&ready){P.mute();P.pauseVideo();}}catch(e){}}" +
        "</script><script src='https://www.youtube.com/iframe_api' onerror=\"L('api did not load')\"></script></body></html>";

    private final MainActivity act;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView wv;
    private Listener listener;
    private String current;
    private boolean loaded;
    private String pending;                 // a play() asked for before the page was ready

    TrailerPlayer(MainActivity act) {
        this.act = act;
        initDecodable();
        if (YtStreams.store == null) YtStreams.store = new java.io.File(act.getCacheDir(), "yt-trailers.json");
    }
    /** YtStreams only picks streams this device has a decoder for */
    static void initDecodable() {
        if (YtStreams.decodable == null) YtStreams.decodable = new YtStreams.Decodable() {
            private final java.util.Map<String, Boolean> seen = new java.util.concurrent.ConcurrentHashMap<>();
            @Override public boolean ok(String codecs, int w, int h, int fps) {
                String k = codecs + "|" + w + "|" + h + "|" + fps;
                Boolean b = seen.get(k);
                if (b == null) { b = canDecode(codecs, w, h, fps); seen.put(k, b); }
                return b;
            }
        };
    }

    /** is there a decoder on this TV for this codec, profile and size? (as the player itself would ask) */
    static boolean canDecode(String codecs, int w, int h, int fps) {
        String c = codecs.trim().toLowerCase(java.util.Locale.US), mime;
        if (c.startsWith("av01")) mime = androidx.media3.common.MimeTypes.VIDEO_AV1;
        else if (c.startsWith("vp09") || c.startsWith("vp9")) mime = androidx.media3.common.MimeTypes.VIDEO_VP9;
        else if (c.startsWith("avc1")) mime = androidx.media3.common.MimeTypes.VIDEO_H264;
        else if (c.startsWith("hev1") || c.startsWith("hvc1")) mime = androidx.media3.common.MimeTypes.VIDEO_H265;
        else return true;
        try {
            androidx.media3.common.Format.Builder fb = new androidx.media3.common.Format.Builder().setSampleMimeType(mime).setCodecs(codecs);
            if (w > 0 && h > 0) fb.setWidth(w).setHeight(h);
            if (fps > 0) fb.setFrameRate(fps);
            androidx.media3.common.Format f = fb.build();
            for (androidx.media3.exoplayer.mediacodec.MediaCodecInfo i : androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, false, false))
                if (i.isFormatSupported(f)) return true;
            return false;
        } catch (Throwable t) { return true; }
    }

    // ------------------------------------------------------------------ straight from YouTube (the app's own player)

    private static final java.util.concurrent.ExecutorService LOOKUP = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.ExecutorService SIDE = java.util.concurrent.Executors.newSingleThreadExecutor();   // (Apple and SponsorBlock: never queued behind a slow YouTube lookup)

    // ------------------------------------------------------------------ where a YouTube trailer really ends
    /* Many YouTube uploads run on past the trailer into the channel's outro (an end card, "subscribe", other
       videos). SponsorBlock (a free shared list people mark these on) says where the outro starts when someone
       has marked it; otherwise the last tailSec seconds are skipped (Settings → Trailer outro). Apple's
       trailers have no outro, so none of this applies to them. */
    private int tailSec = 10;
    /** seconds cut from the end of YouTube trailers when SponsorBlock doesn't know the outro (0 = none) */
    TrailerPlayer tail(int sec) { tailSec = Math.max(0, Math.min(60, sec)); return this; }
    private static final java.util.Map<String, Long> outros = new java.util.concurrent.ConcurrentHashMap<>();   // id → outro start (ms), -1 = none marked
    private long cutAt = -1;                 // the current YouTube trailer's outro start (ms), from SponsorBlock
    private boolean endSent;                 // ended() already said for this trailer

    private void outro(final String ytId) {
        if (ytId == null || ytId.isEmpty()) return;
        Long k = outros.get(ytId);
        if (k != null) { if (ytId.equals(current)) setCut(ytId, k); return; }
        outros.put(ytId, -2L);           // (asked; no answer yet)
        SIDE.execute(new Runnable() { @Override public void run() {
            long at = -1;
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://sponsor.ajay.app/api/skipSegments?videoID="
                        + java.net.URLEncoder.encode(ytId, "UTF-8") + "&categories=%5B%22outro%22%5D").openConnection();
                c.setConnectTimeout(5000); c.setReadTimeout(6000);
                c.setRequestProperty("User-Agent", "TopShelf");
                if (c.getResponseCode() == 200) {
                    java.io.InputStream in = c.getInputStream();
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
                    while ((n = in.read(b)) > 0 && bo.size() < 256 * 1024) bo.write(b, 0, n);
                    in.close();
                    org.json.JSONArray a = new org.json.JSONArray(new String(bo.toByteArray(), "UTF-8"));
                    for (int i = 0; i < a.length(); i++) {
                        org.json.JSONObject o = a.optJSONObject(i);
                        org.json.JSONArray sg = o == null ? null : o.optJSONArray("segment");
                        if (sg == null || sg.length() < 2 || !"outro".equals(o.optString("category"))) continue;
                        double st = sg.optDouble(0), en = sg.optDouble(1), dur = o.optDouble("videoDuration", 0);
                        // (an outro that runs to the end, after at least 20 s of trailer)
                        if (st < 20 || dur > 0 && en < dur - 3) continue;
                        long ms = Math.round(st * 1000);
                        if (at < 0 || ms < at) at = ms;
                    }
                }
                c.disconnect();
            } catch (Throwable ignored) { }
            final long got = at;
            outros.put(ytId, got);
            main.post(new Runnable() { @Override public void run() { if (ytId.equals(current)) setCut(ytId, got); } });
        } });
    }
    private void setCut(String ytId, long ms) {
        cutAt = ms >= 0 ? ms : -1;
        if (cutAt > 0 && wv != null && loaded && !direct) wv.evaluateJavascript("setCut(" + org.json.JSONObject.quote(ytId) + "," + (cutAt / 1000.0) + ")", null);
    }
    /** a YouTube trailer playing in the app's own player stops where its outro starts */
    private final Runnable tailWatch = new Runnable() { @Override public void run() {
        if (!direct || apple || exo == null || endSent) return;
        main.postDelayed(this, 250);
        if (!framed) return;
        long d = exo.getDuration(), t = exo.getCurrentPosition();
        if (d <= 0 || d == androidx.media3.common.C.TIME_UNSET) return;
        long c = cutAt > 0 ? cutAt : tailSec > 0 && d > tailSec * 4000L ? d - tailSec * 1000L : -1;
        if (c > 0 && t >= c) {
            endSent = true;
            Diag.ev("trailer: ended " + Math.round(t / 1000f) + " of " + Math.round(d / 1000f) + " s, before YouTube's outro (" + (cutAt > 0 ? "SponsorBlock" : "last " + tailSec + " s") + ")");
            exo.setVolume(0f); exo.setPlayWhenReady(false);
            if (listener != null) listener.ended();
        }
    } };

    // ------------------------------------------------------------------ Apple TV first (the featured screen)
    private boolean apple;                   // the current trailer is Apple's
    private String appleImdb, fbYt = "";     // (and the YouTube one to fall back to)
    private boolean fbMuted;
    private static int appleLogs;

    /** start finding a title's trailer now: Apple's first, YouTube's only if Apple has none */
    void prefetchTitle(final org.json.JSONObject m, final int maxH, final boolean muted) {
        if (m == null) return;
        final String imdb = m.optString("id"), yt = m.optString("yt");
        String have = imdb.startsWith("tt") ? AppleTrailers.cached(imdb) : "";
        if (have == null) SIDE.execute(new Runnable() { @Override public void run() {
            String u = AppleTrailers.find(m);
            if (u != null && u.isEmpty() && !yt.isEmpty()) main.post(new Runnable() { @Override public void run() { prefetch(yt, maxH, muted); } });
        } });
        else if (have.isEmpty()) prefetch(yt, maxH, muted);
    }

    /** a title's trailer: Apple TV's when it has one (no outro), else YouTube's (stopped before its outro) */
    void playTitle(final ViewGroup host, final org.json.JSONObject m, final boolean muted, final int maxH, final Listener l) {
        final String imdb = m.optString("id"), yt = m.optString("yt");
        String have = imdb.startsWith("tt") ? AppleTrailers.cached(imdb) : "";
        if (have != null && have.isEmpty()) { fallback(host, yt, muted, maxH, l, null); return; }
        directMax = maxH;
        stopQuiet();
        listener = l;
        current = "apple:" + imdb;
        fbYt = yt; fbMuted = muted;
        if (have != null) { playApple(host, imdb, have, muted); return; }
        final int my = ++tok;
        final Runnable giveUp = new Runnable() { @Override public void run() {
            if (my != tok) return;
            fallback(host, yt, muted, maxH, l, "Apple TV took too long");
        } };
        main.postDelayed(giveUp, 4000);
        SIDE.execute(new Runnable() { @Override public void run() {
            final String u = AppleTrailers.find(m);
            final String why = AppleTrailers.lastWhy;
            main.post(new Runnable() { @Override public void run() {
                if (my != tok) return;
                main.removeCallbacks(giveUp);
                if (u != null && !u.isEmpty()) playApple(host, imdb, u, muted);
                else fallback(host, yt, muted, maxH, l, "no Apple TV trailer: " + (u == null ? "Apple didn't answer, " : "") + why);
            } });
        } });
    }
    private void fallback(ViewGroup host, String yt, boolean muted, int maxH, Listener l, String why) {
        if (why != null && appleLogs++ < 6) Diag.ev("trailer: " + why + (yt.isEmpty() ? " (and none on YouTube)" : " — YouTube instead"));
        if (!yt.isEmpty()) { play(host, yt, muted, maxH, l); return; }
        stop();
        if (l != null) l.ended();
    }

    private void playApple(ViewGroup host, String imdb, String url, boolean muted) {
        direct = true; apple = true; appleImdb = imdb; endSent = false; cutAt = -1;
        framed = false; directAt = android.os.SystemClock.uptimeMillis(); directHost = host; directMuted = muted;
        main.removeCallbacks(watch);
        main.postDelayed(watch, 8000);
        note(imdb, "playing Apple TV's trailer");
        androidx.media3.exoplayer.ExoPlayer p = exo();
        // (the sharpest version that fits from the first frame, not stepping up from a tiny one; plain HD before
        //  HDR / Dolby Vision, so a silent preview never switches the TV's picture mode)
        p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon()
                .setMaxVideoSize(directMax >= 2160 ? 3840 : directMax >= 1080 ? 1920 : 1280, directMax >= 2160 ? 2160 : directMax >= 1080 ? 1080 : 720)
                .setForceHighestSupportedBitrate(true)
                .setPreferredVideoMimeTypes(androidx.media3.common.MimeTypes.VIDEO_H264, androidx.media3.common.MimeTypes.VIDEO_H265).build());
        tv = new android.view.TextureView(act);
        tv.setFocusable(false);
        tv.addOnLayoutChangeListener(new View.OnLayoutChangeListener() { @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) { crop(); } });
        host.addView(tv, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        if (host.getAlpha() < .01f) host.setAlpha(.01f);
        vw = vh = 0;
        p.setVideoTextureView(tv);
        androidx.media3.datasource.DefaultHttpDataSource.Factory ds = new androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setUserAgent("AppleTV/tvOS").setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(8000).setReadTimeoutMs(10000);
        p.setVolume(muted ? 0f : 1f);
        p.setMediaSource(new androidx.media3.exoplayer.hls.HlsMediaSource.Factory(ds).createMediaSource(androidx.media3.common.MediaItem.fromUri(url)));
        p.prepare();
        p.setPlayWhenReady(true);
    }
    /** Apple's stream wouldn't play: YouTube's trailer instead (and Apple's isn't offered again this session) */
    private void appleFailed(String why) {
        final ViewGroup host = directHost; final Listener l = listener; final String yt = fbYt; final boolean muted = fbMuted;
        AppleTrailers.bad(appleImdb);
        stopDirect();
        if (host != null) fallback(host, yt, muted, directMax, l, "Apple TV's trailer didn't play (" + why + ")");
    }
    private androidx.media3.exoplayer.ExoPlayer exo;
    private android.view.TextureView tv;
    private int tok;                         // which play() an answer belongs to
    private int vw, vh; private float par = 1f;
    private boolean direct;                  // the current trailer plays in the app's own player
    private static final java.util.Set<String> logged = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** start finding the video's streams now (resting on a title), so it can start the moment it's wanted */
    void prefetch(final String ytId, final int maxH, final boolean muted) {
        if (ytId == null || ytId.isEmpty() || isBad(ytId)) return;
        if (!outros.containsKey(ytId)) outro(ytId);
        if (YtStreams.cached(ytId, maxH, !muted) != null || YtStreams.blocked()) return;
        LOOKUP.execute(new Runnable() { @Override public void run() { YtStreams.get(ytId, maxH, !muted); } });
    }

    /* YouTube trailers that can't be played here (removed, private, or their owner allows them only on YouTube): kept
       for a week, so the banner moves straight on instead of a few seconds of black while YouTube is asked again */
    private static final long BAD_FOR = 7L * 86400000L;
    private android.content.SharedPreferences badPrefs() { return act.getSharedPreferences(Bridge.PREFS, android.content.Context.MODE_PRIVATE); }
    boolean isBad(String ytId) {
        try { org.json.JSONObject o = new org.json.JSONObject(badPrefs().getString("ytBad", "{}")); return System.currentTimeMillis() - o.optLong(ytId, 0) < BAD_FOR; }
        catch (Exception e) { return false; }
    }
    private void markBad(String ytId) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(badPrefs().getString("ytBad", "{}"));
            long now = System.currentTimeMillis();
            org.json.JSONObject keep = new org.json.JSONObject();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) { String k = it.next(); if (now - o.optLong(k) < BAD_FOR) keep.put(k, o.optLong(k)); }
            keep.put(ytId, now);
            badPrefs().edit().putString("ytBad", keep.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** plays ytId filling host (a frame that's invisible until listener.playing() says the picture moves) */
    void play(final ViewGroup host, final String ytId, final boolean muted, final int maxH, Listener l) {
        if (isBad(ytId)) {
            note(ytId, "YouTube trailer skipped (it couldn't be played here before)");
            stop();
            final Listener fl = l;
            if (fl != null) main.post(new Runnable() { @Override public void run() { fl.ended(); } });
            return;
        }
        if (!ytId.equals(current)) retried = false;
        directMax = maxH;
        stopQuiet();
        listener = l;
        current = ytId;
        cutAt = -1; endSent = false;
        outro(ytId);
        final int my = ++tok;
        YtStreams.Source have = YtStreams.cached(ytId, maxH, !muted);
        if (have != null) { playDirect(host, ytId, have, muted); return; }
        if (YtStreams.blocked()) { note(ytId, "YouTube's player (YouTube refused the direct way a few minutes ago; not asking again yet)"); playWeb(host, ytId, muted); return; }
        // (it doesn't wait long: after a few seconds YouTube's own player takes over)
        final Runnable giveUp = new Runnable() { @Override public void run() {
            if (my != tok) return;
            tok++;
            note(ytId, "YouTube's player (the video's address took too long)");
            playWeb(host, ytId, muted);
        } };
        main.postDelayed(giveUp, 7000);
        final long asked = android.os.SystemClock.uptimeMillis();
        LOOKUP.execute(new Runnable() { @Override public void run() {
            final YtStreams.Source s = YtStreams.get(ytId, maxH, !muted);
            final String why = YtStreams.lastWhy;
            if (firstLookup || s == null && !YtStreams.tried.toString().isEmpty() && failLogs++ < 3) { firstLookup = false; Diag.ev("trailer: looking up the video took " + (android.os.SystemClock.uptimeMillis() - asked) + " ms" + (s == null ? " (" + why + ")" : "") + " — " + YtStreams.report()); }
            main.post(new Runnable() { @Override public void run() {
                if (my != tok) return;
                main.removeCallbacks(giveUp);
                if (s != null) playDirect(host, ytId, s, muted);
                else { note(ytId, "YouTube's player (" + why + ")"); playWeb(host, ytId, muted); }
            } });
        } });
    }
    void play(ViewGroup host, String ytId, boolean muted, Listener l) { play(host, ytId, muted, 1080, l); }

    private static String errName(String c) {
        switch (c.trim()) { case "2": return "bad video id"; case "5": return "can't play in this player"; case "100": return "video not found / private";
            case "101": case "150": case "152": return "the owner doesn't allow it in other apps"; default: return "unknown"; }
    }
    private static String stateName(String c) {
        switch (c.trim()) { case "-1": return "not started"; case "0": return "ended"; case "1": return "playing"; case "2": return "paused"; case "3": return "loading"; case "5": return "cued"; default: return "?"; }
    }
    private static boolean firstLookup = true, webLog = true;
    private static int failLogs;
    private void note(String id, String how) {
        if (logged.add(id + how)) Diag.ev("trailer: " + how);
    }

    private androidx.media3.exoplayer.ExoPlayer exo() {
        if (exo != null) return exo;
        androidx.media3.exoplayer.DefaultLoadControl load = new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(3000, 20000, 700, 1500).build();      // (starts after 0.7 s of picture)
        exo = new androidx.media3.exoplayer.ExoPlayer.Builder(act).setLoadControl(load).build();
        exo.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_OFF);
        exo.addListener(new androidx.media3.common.Player.Listener() {
            @Override public void onRenderedFirstFrame() {
                if (!direct) return;
                if (!framed) { framed = true; main.removeCallbacks(watch); if (firstLog) { firstLog = false; Diag.ev("trailer: first picture " + (android.os.SystemClock.uptimeMillis() - directAt) + " ms after it was asked for"); } }
                if (listener != null) listener.playing();
            }
            @Override public void onPlaybackStateChanged(int st) {
                if (st == androidx.media3.common.Player.STATE_ENDED && direct && listener != null && !endSent) { endSent = true; listener.ended(); }
            }
            @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize v) {
                vw = v.width; vh = v.height; par = v.pixelWidthHeightRatio > 0 ? v.pixelWidthHeightRatio : 1f;
                crop();
            }
            @Override public void onPlayerError(androidx.media3.common.PlaybackException e) {
                if (!direct || current == null) return;
                if (apple) { appleFailed(e.getErrorCodeName()); return; }
                // the stream didn't play: YouTube's own player takes over (and the address is asked for afresh next time)
                final String id = current; final ViewGroup host = tv != null && tv.getParent() instanceof ViewGroup ? (ViewGroup) tv.getParent() : null;
                final boolean muted = exo.getVolume() == 0f;
                YtStreams.forget(id);
                if (host != null && !retried) { retried = true; Diag.ev("trailer: the stream didn't play (" + e.getErrorCodeName() + "), asking YouTube again"); stopDirect(); final Listener l = listener; play(host, id, muted, directMax, l); retried = true; return; }
                String why = e.getErrorCodeName();
                Throwable c = e.getCause();
                if (c != null && c.getMessage() != null) why += ": " + c.getMessage().replaceAll("https?://\\S+", "…");
                note(id, "YouTube's player (the stream wouldn't play: " + (why.length() > 140 ? why.substring(0, 140) : why) + ")");
                stopDirect();
                if (host != null) playWeb(host, id, muted);
            }
        });
        return exo;
    }

    /* If the stream hasn't shown a picture after a while (no error, just nothing), YouTube's own
       player takes over, and the report says what the stream was doing. */
    private long directAt; private boolean framed, firstLog = true;
    private ViewGroup directHost; private boolean directMuted; private int directMax = 1080;
    private boolean retried;           // one fresh lookup after a stream that wouldn't play (an old address)
    private final Runnable watch = new Runnable() { @Override public void run() {
        if (!direct || framed || current == null || directHost == null) return;
        if (apple) { appleFailed("no picture after 8 s"); return; }
        String st = "?";
        try { int ps = exo.getPlaybackState(); st = ps == 1 ? "idle" : ps == 2 ? "loading" : ps == 3 ? "ready" : "ended"; } catch (Throwable ignored) { }
        long buf = 0; try { buf = exo.getBufferedPosition(); } catch (Throwable ignored) { }
        final String id = current; final ViewGroup host = directHost; final boolean muted = directMuted;
        Diag.ev("trailer: no picture after 8 s (" + st + ", " + buf + " ms loaded, picture surface " + (tv != null && tv.isAvailable() ? "ready" : "not ready") + ") — YouTube's player takes over");
        YtStreams.forget(id);
        stopDirect();
        if (!retried) { retried = true; final Listener l = listener; play(host, id, muted, directMax, l); retried = true; return; }
        playWeb(host, id, muted);
    } };

    private void playDirect(ViewGroup host, String id, YtStreams.Source s, boolean muted) {
        direct = true;
        framed = false; directAt = android.os.SystemClock.uptimeMillis(); directHost = host; directMuted = muted;
        main.removeCallbacks(watch);
        main.postDelayed(watch, 8000);
        note(id, "playing straight from YouTube (" + s.what + ")");
        apple = false; endSent = false;
        androidx.media3.exoplayer.ExoPlayer p = exo();
        p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon().clearVideoSizeConstraints().setForceHighestSupportedBitrate(false).setPreferredVideoMimeTypes().build());
        main.removeCallbacks(tailWatch); main.postDelayed(tailWatch, 250);
        tv = new android.view.TextureView(act);
        tv.setFocusable(false);
        tv.addOnLayoutChangeListener(new View.OnLayoutChangeListener() { @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) { crop(); } });
        host.addView(tv, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        // (a frame that's completely see-through may not be drawn at all, and then the picture never
        // gets a surface to go to: it's kept just short of invisible until the first picture arrives)
        if (host.getAlpha() < .01f) host.setAlpha(.01f);
        vw = vh = 0;
        p.setVideoTextureView(tv);
        androidx.media3.datasource.DataSource.Factory ds = new YtChunks.Factory();
        androidx.media3.exoplayer.source.MediaSource ms;
        if (s.hls) ms = new androidx.media3.exoplayer.hls.HlsMediaSource.Factory(ds)
                .createMediaSource(androidx.media3.common.MediaItem.fromUri(s.video));
        else ms = new androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(ds).createMediaSource(androidx.media3.common.MediaItem.fromUri(s.video));
        if (s.audio != null)
            ms = new androidx.media3.exoplayer.source.MergingMediaSource(ms, new androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(ds).createMediaSource(androidx.media3.common.MediaItem.fromUri(s.audio)));
        p.setVolume(muted ? 0f : 1f);
        p.setMediaSource(ms);
        p.prepare();
        p.setPlayWhenReady(true);
    }

    /** the trailer's picture while it plays in TopShelf's own player (null in YouTube's web player) */
    android.view.TextureView texture() { return direct ? tv : null; }
    /** the picture fills its frame (cropped at the sides or top and bottom, never stretched) */
    private void crop() {
        if (tv == null || vw <= 0 || vh <= 0) return;
        int w = tv.getWidth(), h = tv.getHeight();
        if (w == 0 || h == 0) return;
        float va = vw * par / vh, ba = (float) w / h;
        float sx = 1, sy = 1;
        if (va > ba) sx = va / ba; else sy = ba / va;
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(sx, sy, w / 2f, h / 2f);
        tv.setTransform(m);
    }

    private void stopDirect() {
        direct = false; apple = false;
        main.removeCallbacks(watch); main.removeCallbacks(tailWatch);
        if (exo != null) { exo.setPlayWhenReady(false); exo.stop(); exo.clearMediaItems(); exo.clearVideoTextureView(tv); }
        if (tv != null && tv.getParent() != null) ((ViewGroup) tv.getParent()).removeView(tv);
        tv = null;
    }

    // ------------------------------------------------------------------ YouTube's embedded player (when the above can't)

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private WebView view() {
        if (wv != null) return wv;
        wv = new WebView(act);
        wv.setBackgroundColor(Color.BLACK);
        wv.setFocusable(false);
        wv.setFocusableInTouchMode(false);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        wv.setWebViewClient(new WebViewClient() {
            // (all of the app's web views share one helper process: one that doesn't answer this takes the whole app down with it)
            @Override public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail d) {
                if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
                try { v.destroy(); } catch (Throwable ignored) { }
                if (v == wv) { wv = null; loaded = false; }
                return true;
            }
            @Override public void onPageFinished(WebView v, String url) {
                loaded = true;
                if (pending != null) { v.evaluateJavascript(pending, null); pending = null; }
            }
        });
        wv.setWebChromeClient(new WebChromeClient() {
            private int n;
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR && n++ < 4) Diag.ev("trailer · YouTube's player said: " + String.valueOf(m.message()).replaceAll("https?://\\S+", "…"));
                return true;
            }
        });
        wv.addJavascriptInterface(new Object() {
            @JavascriptInterface public void playing() { main.post(new Runnable() { @Override public void run() { if (listener != null && !direct) { webShown = true; if (webLog) { webLog = false; Diag.ev("trailer: YouTube's player is showing it"); } listener.playing(); } } }); }
            @JavascriptInterface public void ev(String m) {
                if (m.startsWith("error ")) { String c = m.substring(6).trim(); String id = current; if (id != null && (c.equals("100") || c.equals("101") || c.equals("150") || c.equals("152"))) markBad(id); }
                Diag.ev("trailer · YouTube's player: " + m + (m.startsWith("error ") ? " (" + errName(m.substring(6)) + ")" : m.startsWith("state ") ? " (" + stateName(m.substring(6)) + ")" : "")); }
            @JavascriptInterface public void ended() { main.post(new Runnable() { @Override public void run() { if (listener != null && !direct) listener.ended(); } }); }
        }, "TS");
        wv.loadDataWithBaseURL(MainActivity.ORIGIN, PAGE, "text/html", "utf-8", null);
        return wv;
    }

    private boolean webShown; private int webChecks;
    private final Runnable webWatch = new Runnable() { @Override public void run() {
        if (direct || current == null || webShown || webChecks++ > 4) return;
        Diag.ev("trailer · YouTube's player: still no picture 15 s after it was asked to play");
    } };
    private void playWeb(ViewGroup host, String ytId, boolean muted) {
        webShown = false;
        main.removeCallbacks(webWatch); main.postDelayed(webWatch, 15000);
        WebView w = view();
        if (w.getParent() != host) {
            if (w.getParent() != null) ((ViewGroup) w.getParent()).removeView(w);
            host.addView(w, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        w.setVisibility(View.VISIBLE);
        w.onResume();
        endSent = false;
        String js = "play(" + org.json.JSONObject.quote(ytId) + "," + muted + "," + (cutAt > 0 ? cutAt / 1000.0 : 0) + "," + tailSec + ")";
        if (loaded) w.evaluateJavascript(js, null); else pending = js;
    }

    void stop() {
        listener = null;
        current = null;
        stopQuiet();
    }
    private void stopQuiet() {
        tok++;
        stopDirect();
        pending = null;
        if (wv == null) return;
        if (loaded) wv.evaluateJavascript("stop()", null);
        if (wv.getParent() != null) ((ViewGroup) wv.getParent()).removeView(wv);
    }

    boolean playing() { return current != null; }

    /** gone for good (frees the video decoder it was holding) */
    void release() {
        stop();
        if (exo != null) { try { exo.release(); } catch (Throwable ignored) { } exo = null; }
        if (wv == null) return;
        try { wv.loadUrl("about:blank"); wv.onPause(); wv.destroy(); } catch (Throwable ignored) { }
        wv = null; loaded = false;
    }
}
