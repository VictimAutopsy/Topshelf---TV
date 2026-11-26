package com.topshelf.launcher;

import android.net.Uri;
import android.os.SystemClock;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/* Live TV reaches the provider's server ahead of a channel change (1.0.478): when Live TV opens, the guide's
   highlight moves, or the channel bar or list comes up in full screen, the server of the highlighted channel
   (and the one the last channel's video really came from, after redirects) gets a bare HEAD request for "/".
   That's no channel and no stream (a one-stream account isn't touched: Chad's provider allows one), only the
   name looked up and a connection opened, which Android keeps ready and the channel's own request then uses.
   At most once in 20 s a server; never logged (only scheme, server and port are kept, no logins). The
   flight report's "live start · server answered · reached ahead" against "… server answered" shows if it helps. */
final class LiveWarm {
    private LiveWarm() { }

    private static final Map<String, Long> asked = new HashMap<>();   // server → when it was last reached ahead (ok)
    private static final Map<String, Long> tried = new HashMap<>();   // server → when it was last asked
    private static volatile String lastVideo = "";

    /** scheme://server:port of an address, or null (no logins: Uri's host leaves out user:pass@) */
    static String origin(Uri u) {
        if (u == null) return null;
        String s = u.getScheme(), h = u.getHost();
        if (s == null || h == null || h.isEmpty()) return null;
        s = s.toLowerCase(java.util.Locale.US);
        if (!s.equals("http") && !s.equals("https")) return null;
        int p = u.getPort();
        return s + "://" + (h.indexOf(':') >= 0 ? "[" + h + "]" : h) + (p > 0 ? ":" + p : "");
    }

    /** a request of a channel was answered from this address (where its video really comes from) */
    static void seen(Uri u) { String o = origin(u); if (o != null) lastVideo = o; }

    /** the channel's server was reached ahead in the last minute */
    static boolean fresh(String channelUrl) {
        String o = origin(Uri.parse(channelUrl == null ? "" : channelUrl));
        if (o == null) return false;
        synchronized (asked) { Long t = asked.get(o); return t != null && SystemClock.elapsedRealtime() - t < 60000; }
    }

    /** reach this channel's server (and the last video's) ahead, in the background */
    static void warm(String channelUrl, String ua) {
        String a = origin(Uri.parse(channelUrl == null ? "" : channelUrl)), b = lastVideo;
        go(a, ua);
        if (b != null && !b.isEmpty() && !b.equals(a)) go(b, ua);
    }

    private static void go(final String origin, final String ua) {
        if (origin == null) return;
        long now = SystemClock.elapsedRealtime();
        synchronized (asked) { Long t = tried.get(origin); if (t != null && now - t < 20000) return; tried.put(origin, now); }
        new Thread(new Runnable() { @Override public void run() {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(origin + "/").openConnection();
                c.setRequestMethod("HEAD");
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(4000); c.setReadTimeout(4000);
                c.setRequestProperty("User-Agent", ua != null && !ua.isEmpty() ? ua : LiveTv.DEFAULT_UA);
                int code = c.getResponseCode();
                // (closing what came back, not disconnecting: the connection goes back to Android's pool for the channel)
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (in != null) in.close();
                synchronized (asked) { asked.put(origin, SystemClock.elapsedRealtime()); }
            } catch (Throwable ignored) {
                if (c != null) try { c.disconnect(); } catch (Throwable ignored2) { }
            }
        } }, "livewarm").start();
    }
}
