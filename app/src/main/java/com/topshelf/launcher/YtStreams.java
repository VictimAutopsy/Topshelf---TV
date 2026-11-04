package com.topshelf.launcher;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A trailer's video straight from YouTube's servers, the way Nuvio does it (its InAppYouTubeExtractor,
 * ported): YouTube's player API is asked for the video's streams as one of YouTube's own apps
 * (visionOS, then Android, then iOS), and the best one this TV can decode is picked. The trailer
 * then plays in the app's own video player — from its first frame, with none of the embedded
 * player's title bar and logo, which the preview had to wait out (about 4½ seconds) before it
 * could be shown. When this doesn't work (YouTube changes something, or refuses for a while),
 * the embedded player is used as before.
 */
final class YtStreams {
    private YtStreams() { }

    static final class Source {
        final String video, audio;     // audio null: the video carries its own sound (or none was wanted)
        final boolean hls;
        final String what;             // for the report: "1080p vp09 (visionos)"
        final long expires;            // ms
        Source(String video, String audio, boolean hls, String what, long expires) { this.video = video; this.audio = audio; this.hls = hls; this.what = what; this.expires = expires; }
    }

    private static final String WEB_UA = "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36";
    private static final String FALLBACK_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
    private static final Pattern API_KEY = Pattern.compile("\"INNERTUBE_API_KEY\":\"([^\"]+)\"");
    private static final Pattern VISITOR = Pattern.compile("\"VISITOR_DATA\":\"([^\"]+)\"");
    private static final Pattern CODECS = Pattern.compile("codecs=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUALITY = Pattern.compile("(\\d{2,4})p");
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    static final class Client {
        final String key, id, version, ua; final int priority; final JSONObject ctx;
        Client(String key, String id, String version, String ua, int priority, String... kv) {
            this.key = key; this.id = id; this.version = version; this.ua = ua; this.priority = priority;
            JSONObject c = new JSONObject();
            try {
                for (int i = 0; i + 1 < kv.length; i += 2) {
                    String v = kv[i + 1];
                    if (v.matches("\\d+") && kv[i].equals("androidSdkVersion")) c.put(kv[i], Integer.parseInt(v)); else c.put(kv[i], v);
                }
            } catch (Exception ignored) { }
            ctx = c;
        }
    }
    static final Client[] CLIENTS = {
        new Client("visionos", "101", "1.02", "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15", 0,
                "clientName", "VISIONOS", "clientVersion", "1.02", "deviceMake", "Apple", "deviceModel", "RealityDevice17,1",
                "osName", "visionOS", "osVersion", "26.5.23O471", "hl", "en", "gl", "US"),
        // YouTube's app for VR headsets: no extra proof of being a real app asked of it (yt-dlp's fallback)
        new Client("android_vr", "28", "1.65.10", "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip", 1,
                "clientName", "ANDROID_VR", "clientVersion", "1.65.10", "deviceMake", "Oculus", "deviceModel", "Quest 3",
                "osName", "Android", "osVersion", "12L", "androidSdkVersion", "32", "hl", "en", "gl", "US"),
        new Client("android", "3", "20.10.35", "com.google.android.youtube/20.10.35 (Linux; U; Android 14; en_US) gzip", 1,
                "clientName", "ANDROID", "clientVersion", "20.10.35", "osName", "Android", "osVersion", "14", "platform", "MOBILE",
                "androidSdkVersion", "34", "hl", "en", "gl", "US"),
        new Client("ios", "5", "20.10.1", "com.google.ios.youtube/20.10.1 (iPhone16,2; U; CPU iOS 17_4 like Mac OS X)", 2,
                "clientName", "IOS", "clientVersion", "20.10.1", "deviceModel", "iPhone16,2", "osName", "iPhone",
                "osVersion", "17.4.0.21E219", "platform", "MOBILE", "hl", "en", "gl", "US"),
    };

    static final class Cand {
        String client, url, itag, ext, codecs; int priority, height, width, fps; double score; boolean hasN, hdr, defaultAudio = true;
    }
    static final class Found {
        final List<Cand> progressive = new ArrayList<>(), video = new ArrayList<>(), audio = new ArrayList<>();
        final List<String[]> manifests = new ArrayList<>();      // {client, url}
        boolean isEmpty() { return progressive.isEmpty() && video.isEmpty() && audio.isEmpty() && manifests.isEmpty(); }
    }

    /** can this TV show it? (set by the player; everything passes when unset, as in tests) */
    interface Decodable { boolean ok(String codecs, int w, int h, int fps); }
    static volatile Decodable decodable;

    // ------------------------------------------------------------------ state shared by every lookup

    private static String apiKey, visitor;
    private static long cfgAt, cfgTtl;
    private static final Object cfgLock = new Object();
    private static long blockedUntil; private static int strikes;
    private static final Map<String, Source> cache = new LinkedHashMap<String, Source>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Source> e) { return size() > 40; }
    };
    static volatile String lastWhy = "";

    static synchronized boolean blocked() { return System.currentTimeMillis() < blockedUntil; }
    /* Once YouTube says "prove you're not a bot" it keeps saying it for a while, so then it isn't asked
       again for a good while (YouTube's own player is used meanwhile) */
    private static synchronized long strike() { return strike(false); }
    private static synchronized long strike(boolean bot) {
        strikes++;
        long ms = bot ? Math.min(60 * 60_000L, 10 * 60_000L << Math.min(3, strikes - 1)) : Math.min(15 * 60_000L, 60_000L << Math.min(4, strikes - 1));
        blockedUntil = System.currentTimeMillis() + ms;
        return ms;
    }
    private static synchronized void unstrike() { strikes = 0; blockedUntil = 0; }

    static String key(String id, int maxH, boolean sound) { return id + "|" + maxH + "|" + sound; }
    /* Lookups are kept on the TV too (until YouTube's addresses expire, a few hours), so a title seen
       before plays its trailer at once even after TopShelf restarts, and YouTube is asked less. */
    static volatile java.io.File store;
    private static boolean loaded;
    private static void loadDisk() {
        if (loaded || store == null) return;
        loaded = true;
        try {
            if (!store.exists()) return;
            JSONObject o = new JSONObject(new String(java.nio.file.Files.readAllBytes(store.toPath()), StandardCharsets.UTF_8));
            long now = System.currentTimeMillis();
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next(); JSONObject x = o.optJSONObject(k);
                if (x == null || x.optLong("e") < now) continue;
                cache.put(k, new Source(x.optString("v"), x.has("a") ? x.optString("a") : null, x.optBoolean("h"), x.optString("w"), x.optLong("e")));
            }
        } catch (Throwable ignored) { }
    }
    private static void saveDisk() {
        if (store == null) return;
        try {
            JSONObject o = new JSONObject();
            synchronized (cache) {
                for (Map.Entry<String, Source> e : cache.entrySet()) {
                    Source x = e.getValue();
                    JSONObject j = new JSONObject().put("v", x.video).put("h", x.hls).put("w", x.what).put("e", x.expires);
                    if (x.audio != null) j.put("a", x.audio);
                    o.put(e.getKey(), j);
                }
            }
            java.io.File tmp = new java.io.File(store.getPath() + ".tmp");
            java.nio.file.Files.write(tmp.toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
            tmp.renameTo(store);
        } catch (Throwable ignored) { }
    }
    static Source cached(String id, int maxH, boolean sound) {
        synchronized (cache) {
            loadDisk();
            Source s = cache.get(key(id, maxH, sound));
            if (s != null && System.currentTimeMillis() > s.expires) { cache.remove(key(id, maxH, sound)); return null; }
            return s;
        }
    }
    /** this one didn't play: ask again next time */
    static void forget(String id) {
        synchronized (cache) { for (java.util.Iterator<String> it = cache.keySet().iterator(); it.hasNext(); ) if (it.next().startsWith(id + "|")) it.remove(); }
        new Thread(new Runnable() { @Override public void run() { saveDisk(); } }, "yt-save").start();
    }

    private static class RateLimited extends Exception { RateLimited(String m) { super(m); } }
    private static class Stale extends Exception { }

    /** The best way to play this video on this TV, or null (lastWhy says why). Slow: call off the main thread. */
    static Source get(String id, int maxH, boolean sound) {
        if (id == null || !ID.matcher(id).matches()) { lastWhy = "not a YouTube video"; return null; }
        Source c = cached(id, maxH, sound);
        if (c != null) return c;
        if (blocked()) { lastWhy = "YouTube asked us to wait a few minutes"; return null; }
        Source s = null;
        try {
            try { s = attempt(id, maxH, sound, false); }
            catch (Stale e) { s = attempt(id, maxH, sound, true); }
        } catch (RateLimited e) {
            long ms = strike(String.valueOf(e.getMessage()).contains("bot"));
            lastWhy = "YouTube is refusing for now (" + e.getMessage() + "), waiting " + ms / 60000 + " min";
        } catch (Stale e) {
            lastWhy = "YouTube asked to sign in";
        } catch (Exception e) {
            lastWhy = String.valueOf(e.getMessage());
        }
        if (s != null) {
            unstrike();
            synchronized (cache) { cache.put(key(id, maxH, sound), s); }
            saveDisk();
        } else if (lastWhy == null || lastWhy.isEmpty()) lastWhy = "no stream this TV can play";
        return s;
    }

    /** what each of YouTube's apps answered, and how the TV reaches YouTube (for the report) */
    static final StringBuilder tried = new StringBuilder();
    static volatile String setup = "";
    private static boolean told;
    static String report() {
        String r = "watch page: " + setup + " · " + tried;
        tried.setLength(0);
        return r;
    }
    private static Source attempt(String id, int maxH, boolean sound, boolean fresh) throws Exception {
        String[] cfg = config(fresh);
        Found f = new Found();
        int login = 0, bot = 0, asked = 0;
        lastWhy = "";
        for (Client cl : CLIENTS) {
            if (covers(f, sound, maxH)) break;
            asked++;
            JSONObject pr;
            try { pr = player(cfg[0], cfg[1], id, cl); }
            catch (RateLimited e) { tried.append(tried.length() > 0 ? " · " : "").append(cl.key).append(": 429"); throw e; }
            catch (Exception e) { lastWhy = cl.key + ": " + e.getMessage(); tried.append(tried.length() > 0 ? " · " : "").append(lastWhy); continue; }
            JSONObject ps = pr.optJSONObject("playabilityStatus");
            String st = ps == null ? null : ps.optString("status", null);
            String said = st == null ? "no status" : st.toLowerCase(Locale.US);
            if (ps != null && !ps.optString("reason").isEmpty()) said += " (" + ps.optString("reason") + ")";
            if (pr.optJSONObject("streamingData") != null) said += ", streams";
            tried.append(tried.length() > 0 ? " · " : "").append(cl.key).append(": ").append(said.length() > 90 ? said.substring(0, 90) : said);
            if ("LOGIN_REQUIRED".equals(st)) {
                String why = ps.optString("reason");
                if (Pattern.compile("\\bbot\\b", Pattern.CASE_INSENSITIVE).matcher(why).find()) bot++;
                else if (!Pattern.compile("\\bage\\b", Pattern.CASE_INSENSITIVE).matcher(why).find()) login++;
                continue;
            }
            if (st != null && !st.equals("OK")) { lastWhy = cl.key + ": " + st.toLowerCase(Locale.US); continue; }
            JSONObject sd = pr.optJSONObject("streamingData");
            if (sd != null) collect(cl, sd, f);
        }
        if (f.isEmpty()) {
            if (bot > 0) throw new RateLimited(bot + " of " + CLIENTS.length + " asked to prove we're not a bot");
            if (login == CLIENTS.length) { synchronized (cfgLock) { cfgAt = 0; } throw new Stale(); }
            return null;
        }
        probeWhy = "";
        Source r = pick(f, maxH, sound);
        if (r == null && !probeWhy.isEmpty()) lastWhy = "the streams wouldn't open (" + probeWhy + ")";
        return r;
    }

    /** the first choice is complete: no need to ask the other apps */
    private static boolean covers(Found f, boolean sound, int maxH) {
        return !playable(f.video).isEmpty() && (!sound || !f.audio.isEmpty());
    }

    // ------------------------------------------------------------------ YouTube

    /** {api key, visitor id} from a watch page, kept 3 hours (a fallback key for 10 minutes) */
    private static String[] config(boolean fresh) throws Exception {
        synchronized (cfgLock) {
            long now = System.currentTimeMillis();
            if (!fresh && apiKey != null && now - cfgAt < cfgTtl) return new String[] { apiKey, visitor };
            Map<String, String> hd = new LinkedHashMap<>();
            hd.put("Accept-Language", "en-US,en;q=0.9"); hd.put("User-Agent", WEB_UA);
            int[] code = new int[1];
            String html = request("https://www.youtube.com/watch?v=dQw4w9WgXcQ&hl=en", "GET", hd, null, code);
            String fam = "";
            try { boolean v4 = false, v6 = false; for (java.net.InetAddress a : java.net.InetAddress.getAllByName("www.youtube.com")) { if (a instanceof java.net.Inet4Address) v4 = true; else v6 = true; } fam = (v6 ? "IPv6" : "") + (v6 && v4 ? "+" : "") + (v4 ? "IPv4" : ""); } catch (Throwable ignored) { }
            setup = code[0] + (html != null && VISITOR.matcher(html).find() ? ", visitor id" : ", no visitor id") + (html != null && html.contains("consent.youtube.com") ? ", consent page" : "") + ", youtube.com has " + fam + (v4 ? ", asked over IPv4" : ", asked the TV's usual way");
            if (code[0] == 429) throw new RateLimited("watch page 429");
            if (code[0] != 200 || html == null) {
                if (apiKey != null) return new String[] { apiKey, visitor };
                apiKey = FALLBACK_KEY; visitor = null; cfgAt = now; cfgTtl = 10 * 60_000L;
                return new String[] { apiKey, null };
            }
            Matcher k = API_KEY.matcher(html), v = VISITOR.matcher(html);
            apiKey = k.find() ? k.group(1) : FALLBACK_KEY;
            visitor = v.find() ? v.group(1) : null;
            cfgAt = now; cfgTtl = 3 * 3600_000L;
            return new String[] { apiKey, visitor };
        }
    }

    private static JSONObject player(String key, String visitorId, String id, Client cl) throws Exception {
        Map<String, String> hd = new LinkedHashMap<>();
        hd.put("Accept-Language", "en-US,en;q=0.9");
        hd.put("Content-Type", "application/json");
        hd.put("Origin", "https://www.youtube.com");
        hd.put("X-YouTube-Client-Name", cl.id);
        hd.put("X-YouTube-Client-Version", cl.version);
        hd.put("User-Agent", cl.ua);
        if (visitorId != null && !visitorId.isEmpty()) hd.put("X-Goog-Visitor-Id", visitorId);
        JSONObject body = new JSONObject();
        body.put("videoId", id);
        body.put("contentCheckOk", true);
        body.put("racyCheckOk", true);
        body.put("context", new JSONObject().put("client", cl.ctx));
        body.put("playbackContext", new JSONObject().put("contentPlaybackContext", new JSONObject().put("html5Preference", "HTML5_PREF_WANTS")));
        int[] code = new int[1];
        String r = request("https://www.youtube.com/youtubei/v1/player?key=" + Uri.encode(key), "POST", hd, body.toString(), code);
        if (code[0] == 429) throw new RateLimited(cl.key + " 429");
        if (code[0] != 200 || r == null) throw new Exception("answered " + code[0]);
        return new JSONObject(r);
    }

    static void collect(Client cl, JSONObject sd, Found f) {
        String hls = sd.optString("hlsManifestUrl", "");
        if (!hls.isEmpty()) f.manifests.add(new String[] { cl.key, hls });
        JSONArray fm = sd.optJSONArray("formats");
        if (fm != null) for (int i = 0; i < fm.length(); i++) {
            JSONObject o = fm.optJSONObject(i);
            if (o == null || o.optString("url", "").isEmpty()) continue;      // (a scrambled address: skipped)
            String mt = o.optString("mimeType");
            if (!mt.isEmpty() && !mt.contains("video/")) continue;
            Cand c = base(cl, o, mt);
            c.height = height(o); c.fps = o.optInt("fps");
            c.score = c.height * 1e9 + c.fps * 1e6 + bitrate(o);
            f.progressive.add(c);
        }
        JSONArray af = sd.optJSONArray("adaptiveFormats");
        if (af != null) for (int i = 0; i < af.length(); i++) {
            JSONObject o = af.optJSONObject(i);
            if (o == null || o.optString("url", "").isEmpty()) continue;
            String mt = o.optString("mimeType");
            Cand c = base(cl, o, mt);
            if (mt.contains("video/")) {
                c.height = height(o); c.width = o.optInt("width"); c.fps = o.optInt("fps");
                c.score = c.height * 1e9 + c.fps * 1e6 + bitrate(o);
                Matcher m = CODECS.matcher(mt); c.codecs = m.find() ? m.group(1).trim() : null;
                JSONObject ci = o.optJSONObject("colorInfo");
                String tr = ci == null ? "" : ci.optString("transferCharacteristics");
                c.hdr = tr.contains("SMPTEST2084") || tr.contains("ARIB_STD_B67") || o.optString("qualityLabel").toUpperCase(Locale.US).contains("HDR");
                c.ext = mt.contains("webm") ? "webm" : "mp4";
                f.video.add(c);
            } else if (mt.contains("audio/")) {
                c.score = bitrate(o) * 1e6 + o.optDouble("audioSampleRate", 0);
                JSONObject at = o.optJSONObject("audioTrack");
                if (at != null && at.has("audioIsDefault")) c.defaultAudio = at.optBoolean("audioIsDefault", true);     // (dubbed versions are their own tracks)
                c.ext = mt.contains("webm") ? "webm" : "m4a";
                f.audio.add(c);
            }
        }
    }
    private static Cand base(Client cl, JSONObject o, String mt) {
        Cand c = new Cand();
        c.client = cl.key; c.priority = cl.priority; c.url = o.optString("url"); c.itag = o.optString("itag");
        c.ext = mt.contains("webm") ? "webm" : "mp4";
        try { String n = Uri.parse(c.url).getQueryParameter("n"); c.hasN = n != null && !n.isEmpty(); } catch (Throwable t) { c.hasN = c.url.contains("&n="); }
        return c;
    }
    private static int height(JSONObject o) {
        int h = o.optInt("height");
        if (h > 0) return h;
        Matcher m = QUALITY.matcher(o.optString("qualityLabel"));
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
    private static double bitrate(JSONObject o) { double b = o.optDouble("bitrate", 0); return b > 0 ? b : o.optDouble("averageBitrate", 0); }

    // ------------------------------------------------------------------ choosing

    /** no HDR (not shown properly in a preview) and nothing this TV has no decoder for */
    static List<Cand> playable(List<Cand> in) {
        List<Cand> out = new ArrayList<>();
        Decodable d = decodable;
        for (Cand c : in) {
            if (c.hdr) continue;
            if (d != null && c.codecs != null && !d.ok(c.codecs, c.width, c.height, c.fps)) continue;
            out.add(c);
        }
        return out;
    }
    static List<Cand> capped(List<Cand> in, int maxH) {
        int maxW = maxH * 16 / 9;
        List<Cand> out = new ArrayList<>();
        for (Cand c : in) if (c.height <= maxH && c.width <= maxW) out.add(c);
        if (!out.isEmpty() || in.isEmpty()) return out.isEmpty() ? in : out;
        // nothing that small: the smallest there is (not the biggest)
        int lo = Integer.MAX_VALUE;
        for (Cand c : in) lo = Math.min(lo, c.height);
        for (Cand c : in) if (c.height == lo) out.add(c);
        return out;
    }
    static List<Cand> sorted(List<Cand> in) {
        List<Cand> l = new ArrayList<>(in);
        Collections.sort(l, new Comparator<Cand>() { @Override public int compare(Cand a, Cand b) {
            if (a.defaultAudio != b.defaultAudio) return a.defaultAudio ? -1 : 1;
            int s = Double.compare(b.score, a.score); if (s != 0) return s;
            if (a.hasN != b.hasN) return a.hasN ? 1 : -1;
            int ea = a.ext.equals("mp4") || a.ext.equals("m4a") ? 0 : 1, eb = b.ext.equals("mp4") || b.ext.equals("m4a") ? 0 : 1;
            if (ea != eb) return ea - eb;
            return a.priority - b.priority;
        } });
        return l;
    }
    /** the best of the visionOS app's streams (they play best), else the best of all */
    static Cand best(List<Cand> in, String client) {
        List<Cand> same = new ArrayList<>();
        for (Cand c : in) if (c.client.equals(client)) same.add(c);
        List<Cand> s = sorted(same.isEmpty() ? in : same);
        return s.isEmpty() ? null : s.get(0);
    }

    static Source pick(Found f, int maxH, boolean sound) {
        // 1. the separate video (and sound) streams: the best picture
        Cand v = best(capped(playable(f.video), maxH), "visionos");
        if (v != null) {
            String vu = reachable(v.url);
            Cand a = sound ? best(f.audio, v.client) : null;
            String au = a == null ? null : reachable(a.url);
            if (vu != null && (!sound || au != null || f.audio.isEmpty()))
                return new Source(vu, au, false, v.height + "p " + (v.codecs == null ? v.ext : v.codecs.split("\\.")[0]) + (au != null ? " + sound" : "") + " (" + v.client + ")", expiry(vu));
        }
        // 2. YouTube's own adaptive list (sound included)
        for (String[] m : f.manifests) return new Source(m[1], null, true, "adaptive list (" + m[0] + ")", expiry(m[1]));
        // 3. one file with picture and sound (usually 360p)
        for (Cand c : sorted(f.progressive)) {
            String u = reachable(c.url);
            if (u != null) return new Source(u, null, false, c.height + "p single file (" + c.client + ")", expiry(u));
        }
        return null;
    }

    private static long expiry(String url) {
        long now = System.currentTimeMillis();
        try {
            String e = Uri.parse(url).getQueryParameter("expire");
            if (e == null) { Matcher m = Pattern.compile("/expire/(\\d+)/").matcher(url); if (m.find()) e = m.group(1); }
            if (e != null) return Math.min(now + 3 * 3600_000L, Long.parseLong(e) * 1000L - 5 * 60_000L);
        } catch (Throwable ignored) { }
        return now + 3 * 3600_000L;
    }

    /* A stream's address names one server; when it doesn't answer (403), the other servers named in
       its "mn" list are tried. */
    static volatile boolean probe = true;
    /** YouTube is asked (and its streams read) over IPv4 — see V4Http; off if this network has no IPv4 */
    static volatile boolean v4 = true;
    private static String reachable(String url) {
        if (!probe || !url.contains("googlevideo.com")) return url;
        List<String> tries = new ArrayList<>();
        tries.add(url);
        try {
            Uri u = Uri.parse(url);
            String mn = u.getQueryParameter("mn"), host = u.getHost();
            if (mn != null && host != null) {
                String[] sv = mn.split(",");
                for (int i = 0; i < sv.length && sv.length > 1; i++) {
                    String alt = host.replaceFirst("^rr\\d+---", "rr" + (i + 1) + "---").replaceFirst("sn-[a-z0-9]+-[a-z0-9]+", sv[i].trim());
                    if (!alt.equals(host)) tries.add(url.replace(host, alt));
                }
            }
        } catch (Throwable ignored) { }
        for (int i = 0; i < tries.size() && i < 4; i++) {
            if (v4) {
                try {
                    Map<String, String> hd = new LinkedHashMap<>(); hd.put("Range", "bytes=0-0"); hd.put("User-Agent", WEB_UA);
                    V4Http.Resp r = V4Http.open(tries.get(i), "GET", hd, null, 2500, false);
                    int code = r.code; r.close();
                    if (code == 200 || code == 206) return tries.get(i);
                    probeWhy = "stream answered " + code;
                } catch (Throwable t) { probeWhy = "stream: " + t.getMessage(); }
                continue;
            }
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(tries.get(i)).openConnection();
                c.setConnectTimeout(2500); c.setReadTimeout(2500);
                c.setRequestProperty("Range", "bytes=0-0");
                c.setRequestProperty("User-Agent", WEB_UA);
                int code = c.getResponseCode();
                try { InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream(); if (in != null) in.close(); } catch (Throwable ignored) { }
                c.disconnect();
                if (code == 200 || code == 206) return tries.get(i);
            } catch (Throwable ignored) { }
        }
        return null;
    }

    static volatile String probeWhy = "";
    private static String request(String url, String method, Map<String, String> hd, String body, int[] code) throws Exception {
        if (v4) {
            try { return V4Http.text(url, method, new LinkedHashMap<>(hd), body, 10000, code); }
            catch (java.io.IOException e) {
                // no IPv4 here (or it didn't connect): Android's own way from now on
                try { String r = requestPlain(url, method, hd, body, code); v4 = false; Diag.ev("trailer: YouTube over IPv4 didn't work (" + e.getMessage() + "), using the TV's usual connection"); return r; }
                catch (Exception e2) { throw e; }
            }
        }
        return requestPlain(url, method, hd, body, code);
    }
    private static String requestPlain(String url, String method, Map<String, String> hd, String body, int[] code) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(10000);
        c.setRequestMethod(method);
        c.setInstanceFollowRedirects(true);
        for (Map.Entry<String, String> e : hd.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        if (body != null) {
            c.setDoOutput(true);
            OutputStream os = c.getOutputStream(); os.write(body.getBytes(StandardCharsets.UTF_8)); os.close();
        }
        code[0] = c.getResponseCode();
        InputStream in = code[0] < 400 ? c.getInputStream() : c.getErrorStream();
        if (in == null) return null;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[16384]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ for the page's own video player (phones)

    /* The page's <video> reads a stream through here: whatever part it asks for (its Range header)
       is fetched from YouTube in 10 MB pieces with YouTube's "&range=", which YouTube serves at full
       speed (a plain read of the whole stream is slowed down, then cut). */
    static android.webkit.WebResourceResponse proxy(String url, Map<String, String> rh) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Accept-Ranges", "bytes");
        h.put("Cache-Control", "no-store");
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost();
            if (host == null || !host.endsWith("googlevideo.com")) throw new Exception("not a YouTube stream");
            long total = -1;
            try { total = Long.parseLong(u.getQueryParameter("clen")); } catch (Throwable ignored) { }
            if (total <= 0) total = sizeOf(url);
            if (total <= 0) throw new Exception("size unknown");
            String mime = u.getQueryParameter("mime");
            if (mime == null || mime.isEmpty()) mime = "video/mp4";
            String range = null;
            if (rh != null) for (Map.Entry<String, String> e : rh.entrySet()) if ("range".equalsIgnoreCase(e.getKey())) range = e.getValue();
            long a = 0, b = total - 1;
            if (range != null) {
                Matcher m = Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(range.trim());
                if (m.find()) {
                    if (!m.group(1).isEmpty()) a = Long.parseLong(m.group(1));
                    else if (!m.group(2).isEmpty()) a = Math.max(0, total - Long.parseLong(m.group(2)));    // (the last N bytes)
                    if (!m.group(1).isEmpty() && !m.group(2).isEmpty()) b = Math.min(total - 1, Long.parseLong(m.group(2)));
                }
                if (a >= total) {
                    h.put("Content-Range", "bytes */" + total);
                    return new android.webkit.WebResourceResponse(mime, null, 416, "Range Not Satisfiable", h, new java.io.ByteArrayInputStream(new byte[0]));
                }
                h.put("Content-Range", "bytes " + a + "-" + b + "/" + total);
            }
            h.put("Content-Length", String.valueOf(b - a + 1));
            return new android.webkit.WebResourceResponse(mime, null, range != null ? 206 : 200, range != null ? "Partial Content" : "OK", h, new Pieces(url, a, b));
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            return new android.webkit.WebResourceResponse("text/plain", "utf-8", 502, "Bad Gateway", h, new java.io.ByteArrayInputStream(msg.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** the stream's size, from the answer to its first byte */
    static long sizeOf(String url) {
        if (v4) {
            try {
                Map<String, String> hd = new LinkedHashMap<>(); hd.put("Range", "bytes=0-0");
                V4Http.Resp r = V4Http.open(url + "&range=0-0", "GET", hd, null, 8000, false);
                String cr = r.header("content-range"); r.close();
                if (cr != null && cr.contains("/")) return Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
            } catch (Throwable ignored) { }
            return -1;
        }
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url + "&range=0-0").openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            c.setRequestProperty("Range", "bytes=0-0");
            String cr = c.getHeaderField("Content-Range");
            c.disconnect();
            if (cr != null && cr.contains("/")) return Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
        } catch (Throwable ignored) { }
        return -1;
    }

    /** bytes a..b of a stream, fetched a piece at a time as they're read */
    static final class Pieces extends InputStream {
        private final String url; private long pos; private final long end;
        private InputStream in; private HttpURLConnection c; private V4Http.Resp rs; private long pieceEnd = -1;
        Pieces(String url, long a, long b) { this.url = url; pos = a; end = b; }
        private boolean open() throws java.io.IOException {
            if (pos > end) return false;
            close();
            pieceEnd = Math.min(end, pos + YtChunks.CHUNK - 1);
            if (v4) {
                Map<String, String> hd = new LinkedHashMap<>(); hd.put("User-Agent", WEB_UA);
                rs = V4Http.open(url + (url.contains("?") ? "&" : "?") + "range=" + pos + "-" + pieceEnd, "GET", hd, null, 15000, false);
                if (rs.code >= 400) { int code = rs.code; rs.close(); rs = null; throw new java.io.IOException("YouTube answered " + code); }
                in = rs.body;
                return true;
            }
            c = (HttpURLConnection) new URL(url + (url.contains("?") ? "&" : "?") + "range=" + pos + "-" + pieceEnd).openConnection();
            c.setConnectTimeout(10000); c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", WEB_UA);
            int code = c.getResponseCode();
            if (code >= 400) throw new java.io.IOException("YouTube answered " + code);
            in = c.getInputStream();
            return true;
        }
        @Override public int read() throws java.io.IOException { byte[] one = new byte[1]; int n = read(one, 0, 1); return n <= 0 ? -1 : one[0] & 255; }
        @Override public int read(byte[] buf, int off, int len) throws java.io.IOException {
            if (len == 0) return 0;
            if (pos > end) return -1;
            if (in == null) open();
            int n = in.read(buf, off, (int) Math.min(len, pieceEnd - pos + 1));
            if (n <= 0) { close(); return -1; }        // (a piece that ended early: the stream is shorter than it said)
            pos += n;
            if (pos > pieceEnd) close();
            return n;
        }
        @Override public void close() {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            if (rs != null) { rs.close(); rs = null; }
            if (c != null) c.disconnect();
            in = null; c = null;
        }
    }
}
