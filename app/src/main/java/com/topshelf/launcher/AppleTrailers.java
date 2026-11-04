package com.topshelf.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trailers from the Apple TV app's catalog, tried before YouTube on the featured screen. Apple's copy is the
 * studio's own video (an HLS stream up to 4K), so when it ends, the trailer has ended: no channel end card,
 * no "subscribe" outro. This is the Apple TV app's own catalog service, not a published API, so it can change
 * or stop answering at any time; when it does, everything carries on with YouTube as before.
 *
 * A title only counts as found when exactly one movie (or show) in Apple's search has the same name and a
 * release year within one of ours: two candidates (a remake the same year) or none means no Apple trailer,
 * rather than risking another film's trailer.
 */
final class AppleTrailers {
    private AppleTrailers() { }

    private static final String HOST = "https://tv.apple.com/api/uts/v3";
    private static final String Q = "caller=web&sf=143441&v=76&pfm=appletv&locale=en-US&l=en&utsk=6e3013c6d6fae3c2::::::235656c069bb0efb";

    private static final class Hit { final String url; final long at; Hit(String u, long a) { url = u; at = a; } }
    private static final Map<String, Hit> cache = new ConcurrentHashMap<>();
    private static final long KEEP_HIT = 60 * 60000L, KEEP_MISS = 12 * 60 * 60000L;
    static volatile String lastWhy = "";

    /** the cached answer, without asking: null = not asked yet (or stale), "" = Apple has none, else the stream */
    static String cached(String imdb) {
        Hit h = imdb == null ? null : cache.get(imdb);
        if (h == null) return null;
        if (System.currentTimeMillis() - h.at > (h.url.isEmpty() ? KEEP_MISS : KEEP_HIT)) { cache.remove(imdb); return null; }
        return h.url;
    }
    /** the stream didn't play: don't offer it again this session */
    static void bad(String imdb) { if (imdb != null) cache.put(imdb, new Hit("", System.currentTimeMillis())); }

    /** the title's Apple trailer (an HLS address), "" if Apple has none, null if Apple didn't answer. Blocking. */
    static String find(JSONObject m) {
        if (m == null) return "";
        String imdb = m.optString("id"), name = m.optString("name");
        int year = year(m.optString("year"));
        boolean series = "series".equals(m.optString("type"));
        if (!imdb.startsWith("tt") || name.isEmpty()) { lastWhy = "no IMDb id or name"; return ""; }
        String c = cached(imdb);
        if (c != null) return c;
        if (year <= 0) { lastWhy = "no year to match"; cache.put(imdb, new Hit("", System.currentTimeMillis())); return ""; }
        try {
            String body = get(HOST + "/search?" + Q + "&searchTerm=" + URLEncoder.encode(name, "UTF-8"));
            if (body == null) return null;
            String id = pick(new JSONObject(body), name, year, series);
            String url = "";
            if (id == null) lastWhy = "not in Apple's catalog";
            else if (id.isEmpty()) lastWhy = "more than one match";
            else {
                String page = get(HOST + (series ? "/shows/" : "/movies/") + URLEncoder.encode(id, "UTF-8") + "?" + Q);
                if (page == null) return null;
                url = hls(page);
                lastWhy = url.isEmpty() ? "no trailer on Apple's page" : "found";
            }
            cache.put(imdb, new Hit(url, System.currentTimeMillis()));
            return url;
        } catch (Throwable t) {
            lastWhy = t.getClass().getSimpleName();
            return null;
        }
    }

    private static int year(String s) {
        for (int i = 0; i + 4 <= s.length(); i++) {
            boolean ok = true;
            for (int k = i; k < i + 4; k++) if (!Character.isDigit(s.charAt(k))) { ok = false; break; }
            if (ok) return Integer.parseInt(s.substring(i, i + 4));
        }
        return 0;
    }

    static String norm(String s) {
        String t = s.toLowerCase(Locale.US).replace("&", " and ");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length(); i++) { char ch = t.charAt(i); if (Character.isLetterOrDigit(ch)) b.append(ch); }
        return b.toString();
    }

    /** the one matching item's id; null = none, "" = more than one */
    private static String pick(JSONObject root, String name, int year, boolean series) {
        List<JSONObject> items = new ArrayList<>();
        walk(root, items, 0);
        String want = norm(name), type = series ? "Show" : "Movie", found = null;
        for (JSONObject it : items) {
            if (!type.equals(it.optString("type")) || !want.equals(norm(it.optString("title")))) continue;
            long rd = it.optLong("releaseDate", Long.MIN_VALUE);
            if (rd == Long.MIN_VALUE) continue;
            java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            cal.setTimeInMillis(rd);
            if (Math.abs(cal.get(java.util.Calendar.YEAR) - year) > 1) continue;
            String id = it.optString("id");
            if (id.isEmpty()) continue;
            if (found != null && !found.equals(id)) return "";
            found = id;
        }
        return found;
    }
    private static void walk(Object o, List<JSONObject> out, int depth) {
        if (depth > 12 || out.size() > 400) return;
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            if (j.has("type") && j.has("id") && j.has("title")) out.add(j);
            for (java.util.Iterator<String> k = j.keys(); k.hasNext(); ) walk(j.opt(k.next()), out, depth + 1);
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) walk(a.opt(i), out, depth + 1);
        }
    }

    /* the trailer on a title page: the video Apple plays behind the title first (most often the full trailer,
       and in 4K), else the first clip among the title's own playables. Not "more like this" shelves: those
       are other titles' trailers. */
    private static String hls(String page) throws Exception {
        JSONObject data = new JSONObject(page).optJSONObject("data");
        if (data == null) return "";
        JSONObject content = data.optJSONObject("content");
        JSONObject bg = content == null ? null : content.optJSONObject("backgroundVideo");
        JSONObject assets = bg == null ? null : bg.optJSONObject("assets");
        String u = assets == null ? "" : assets.optString("hlsUrl");
        if (u.startsWith("https://")) return u;
        Object pl = data.opt("playables");
        List<JSONObject> clips = new ArrayList<>();
        clipsIn(pl, clips, 0);
        for (JSONObject c : clips) { JSONObject a = c.optJSONObject("assets"); String h = a == null ? "" : a.optString("hlsUrl"); if (h.startsWith("https://")) return h; }
        return "";
    }
    private static void clipsIn(Object o, List<JSONObject> out, int depth) {
        if (depth > 8 || o == null) return;
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            JSONArray mc = j.optJSONArray("movieClips");
            if (mc != null) for (int i = 0; i < mc.length(); i++) if (mc.optJSONObject(i) != null) out.add(mc.optJSONObject(i));
            for (java.util.Iterator<String> k = j.keys(); k.hasNext(); ) { String key = k.next(); if (!"movieClips".equals(key)) clipsIn(j.opt(key), out, depth + 1); }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) clipsIn(a.opt(i), out, depth + 1);
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Origin", "https://tv.apple.com");
            c.setRequestProperty("Referer", "https://tv.apple.com/");
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36");
            int code = c.getResponseCode();
            if (code >= 400) { lastWhy = "Apple answered " + code; return null; }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) { bo.write(buf, 0, n); if (bo.size() > 8 * 1024 * 1024) break; }
            in.close();
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } finally { c.disconnect(); }
    }
}
