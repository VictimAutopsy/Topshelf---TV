package com.topshelf.launcher;

import android.content.Context;
import android.util.Xml;
import android.webkit.WebResourceResponse;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Live TV data: downloads and parses an M3U playlist and an XMLTV guide, caches both on
 * disk, answers guide queries for the page, and proxies streams/logos so the page can play
 * plain-http IPTV streams that don't send CORS headers.
 */
final class LiveTv {

    interface Progress { void update(String message); }

    static final String DEFAULT_UA = "VLC/3.0.20 LibVLC/3.0.20";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final File dir;
    /* (one guide in memory however many LiveTv there are: the recorder made its own when TopShelf was started by an
       update or a reminder, and for a while the whole guide was held twice) */
    private static volatile Map<String, List<long[]>> times;        // key -> [start, end] (ms), sorted
    private static volatile Map<String, List<String[]>> texts;      // key -> [title, desc], parallel to times
    private static final Object epgLock = new Object();

    LiveTv(Context ctx) {
        this(new File(ctx.getFilesDir(), "live"));
    }

    LiveTv(File dir) {
        this.dir = dir;
        if (!dir.exists()) dir.mkdirs();
    }

    // ---------------------------------------------------------------- cache access

    /** the saved channel list's file (read in the background by the TV's Live TV screen) */
    File channelsFile() { return new File(dir, "channels.json"); }

    String channelsJson() {
        return readFile(new File(dir, "channels.json"), "[]");
    }

    void clear() {
        new File(dir, "channels.json").delete();
        new File(dir, "epg.json").delete();
        synchronized (epgLock) { times = null; texts = null; }
    }

    /** Programmes for the given guide keys overlapping [from, to] (ms): {key: [[s,e,title,desc],...]}. */
    String programs(String keysJson, long from, long to) {
        ensureEpgLoaded();
        Map<String, List<long[]>> t = times;
        Map<String, List<String[]>> x = texts;
        JSONObject out = new JSONObject();
        if (t == null) return out.toString();
        try {
            JSONArray keys = new JSONArray(keysJson);
            for (int i = 0; i < keys.length(); i++) {
                String k = keys.getString(i);
                List<long[]> lt = t.get(k);
                if (lt == null) continue;
                List<String[]> lx = x.get(k);
                JSONArray arr = new JSONArray();
                for (int j = 0; j < lt.size(); j++) {
                    long[] se = lt.get(j);
                    if (se[1] <= from || se[0] >= to) continue;
                    JSONArray p = new JSONArray();
                    p.put(se[0]); p.put(se[1]); p.put(lx.get(j)[0]); p.put(lx.get(j)[1]);
                    arr.put(p);
                }
                out.put(k, arr);
            }
        } catch (Exception ignored) { }
        return out.toString();
    }

    /** Programmes whose title contains q, ending after `from` and starting before `to`, soonest first:
     *  [[key, start, end, title, desc], ...] (at most `limit`). */
    String searchPrograms(String q, long from, long to, int limit) {
        /* never load the whole guide here: this runs while you type, and reading a big guide in one
           go froze Search for most of a second (the caller loads it in the background and asks again) */
        Map<String, List<long[]>> t = times;
        Map<String, List<String[]>> x = texts;
        JSONArray out = new JSONArray();
        if (t == null || q == null || q.trim().length() < 2) return out.toString();
        final String ql = q.trim().toLowerCase(Locale.ROOT);
        List<Object[]> hits = new ArrayList<>();
        for (Map.Entry<String, List<long[]>> en : t.entrySet()) {
            List<long[]> lt = en.getValue();
            List<String[]> lx = x.get(en.getKey());
            if (lx == null) continue;
            for (int j = 0; j < lt.size(); j++) {
                long[] se = lt.get(j);
                if (se[1] <= from || se[0] >= to) continue;
                String title = lx.get(j)[0];
                if (title == null || !has(title, ql)) continue;      // (no lower-cased copy of every title, every key press)
                hits.add(new Object[]{en.getKey(), se[0], se[1], title, lx.get(j)[1]});
            }
        }
        java.util.Collections.sort(hits, new java.util.Comparator<Object[]>() {
            @Override public int compare(Object[] a, Object[] b) {
                String ta = (String) a[3], tb = (String) b[3];
                int pa = ta.regionMatches(true, 0, ql, 0, ql.length()) ? 0 : 1, pb = tb.regionMatches(true, 0, ql, 0, ql.length()) ? 0 : 1;
                if (pa != pb) return pa - pb;
                return Long.compare((Long) a[1], (Long) b[1]);
            }
        });
        for (int i = 0; i < hits.size() && i < limit; i++) {
            Object[] h = hits.get(i);
            JSONArray p = new JSONArray();
            p.put(h[0]); p.put(h[1]); p.put(h[2]); p.put(h[3]); p.put(h[4]);
            out.put(p);
        }
        return out.toString();
    }

    /** s contains q (q already lower-case), ignoring case, without making copies */
    static boolean has(String s, String q) {
        int n = q.length(), last = s.length() - n;
        if (n == 0) return true;
        char c0 = q.charAt(0), u0 = Character.toUpperCase(c0);
        for (int i = 0; i <= last; i++) {
            char c = s.charAt(i);
            if ((c == c0 || c == u0 || Character.toLowerCase(c) == c0) && s.regionMatches(true, i, q, 0, n)) return true;
        }
        return false;
    }

    boolean epgReady() { return times != null; }
    /** let go of the guide (TopShelf a long while in the background); it's read again from the TV when next wanted */
    static boolean dropGuide() {
        synchronized (epgLock) { boolean had = times != null; times = null; texts = null; return had; }
    }

    interface ProgVisitor { void visit(String key, long start, long end, String title); }
    /** every programme of these channels overlapping [from, to] (the guide must be loaded) */
    void eachProg(java.util.Collection<String> keys, long from, long to, ProgVisitor v) {
        Map<String, List<long[]>> t = times;
        Map<String, List<String[]>> x = texts;
        if (t == null || x == null) return;
        for (String k : keys) {
            List<long[]> lt = t.get(k); List<String[]> lx = x.get(k);
            if (lt == null || lx == null) continue;
            for (int j = 0; j < lt.size() && j < lx.size(); j++) {
                long[] se = lt.get(j);
                if (se[1] <= from || se[0] >= to) continue;
                String title = lx.get(j)[0];
                if (title != null) v.visit(k, se[0], se[1], title);
            }
        }
    }

    /** one channel's programmes overlapping [from, to] as {start, end} plus {title, desc} (guide must be loaded) */
    void progs(String key, long from, long to, List<long[]> outT, List<String[]> outX) {
        Map<String, List<long[]>> t = times;
        Map<String, List<String[]>> x = texts;
        if (t == null || x == null) return;
        List<long[]> lt = t.get(key);
        if (lt == null) return;
        List<String[]> lx = x.get(key);
        for (int j = 0; j < lt.size(); j++) {
            long[] se = lt.get(j);
            if (se[1] <= from || se[0] >= to) continue;
            outT.add(se); outX.add(lx.get(j));
        }
    }
    void loadEpgNow() { ensureEpgLoaded(); }

    private void ensureEpgLoaded() {
        if (times != null) return;
        synchronized (epgLock) {
            if (times != null) return;
            Map<String, List<long[]>> t = new HashMap<>();
            Map<String, List<String[]>> x = new HashMap<>();
            Map<String, String> same = new HashMap<>();
            try {
                File f = new File(dir, "epg.json");
                // (read as it goes, not as one string: a big guide is tens of megabytes)
                if (f.exists()) try (android.util.JsonReader jr = new android.util.JsonReader(new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), UTF8), 1 << 16))) {
                    jr.beginObject();
                    while (jr.hasNext()) {
                        String k = jr.nextName();
                        List<long[]> lt = new ArrayList<>();
                        List<String[]> lx = new ArrayList<>();
                        jr.beginArray();
                        while (jr.hasNext()) {
                            jr.beginArray();
                            long a = jr.nextLong(), b = jr.nextLong();
                            String s0 = jr.hasNext() ? jr.nextString() : "", s1 = jr.hasNext() ? jr.nextString() : "", s2 = jr.hasNext() ? jr.nextString() : "";
                            while (jr.hasNext()) jr.skipValue();
                            jr.endArray();
                            lt.add(new long[] { a * 1000L, b * 1000L });
                            lx.add(new String[] { one(same, s0), one(same, s1), one(same, s2) });
                        }
                        jr.endArray();
                        t.put(k, lt);
                        x.put(k, lx);
                    }
                    jr.endObject();
                }
            } catch (Throwable ignored) { }
            texts = x;
            times = t;
            sizeNote(x, same.size());
        }
    }

    /* The whole guide stays in memory once read (Live TV, Search and Sports all look through it): every programme's
       title and description, for every channel, a week back and ahead. The same texts come back again and again
       (repeats, the +1 and HD copies of a channel, "No information"), so each different text is kept once. */
    private static String one(Map<String, String> same, String v) {
        if (v == null || v.isEmpty()) return "";
        String o = same.get(v);
        if (o != null) return o;
        same.put(v, v);
        return v;
    }
    private static void dedupe(Map<String, List<String[]>> x) {
        Map<String, String> same = new HashMap<>();
        for (List<String[]> l : x.values()) for (String[] a : l) for (int i = 0; i < a.length; i++) a[i] = one(same, a[i]);
        sizeNote(x, same.size());
    }
    /** how big the guide is in memory, for the report */
    private static void sizeNote(Map<String, List<String[]>> x, int different) {
        long progs = 0, chars = 0;
        java.util.IdentityHashMap<String, Boolean> seen = new java.util.IdentityHashMap<>();
        for (List<String[]> l : x.values()) for (String[] a : l) { progs++; for (String v : a) if (v != null && !v.isEmpty() && seen.put(v, Boolean.TRUE) == null) chars += v.length(); }
        // (a programme costs ~110 bytes of bookkeeping; text about a byte a character)
        Diag.ev("live: guide in memory · " + x.size() + " channels · " + progs + " programmes · " + different + " different texts · about " + Math.round((progs * 110 + chars + different * 24L) / 1048576.0) + " MB");
    }

    // ---------------------------------------------------------------- refresh

    /** Downloads playlist (and guide), writes the cache, returns a short status line. */
    String refresh(String m3uUrl, String epgUrl, Progress p) throws Exception {
        m3uUrl = cleanUrl(m3uUrl);
        epgUrl = cleanUrl(epgUrl);
        if (m3uUrl.isEmpty()) throw new IOException("No playlist link set");
        // several playlists (links separated by "|"): their channels one after another
        String[] lists = m3uUrl.split("\\|");
        String[] headerEpg = new String[1];
        List<JSONObject> channels = new ArrayList<>();
        List<CatchUp.Xt> services = new ArrayList<>();
        StringBuilder headerGuides = new StringBuilder();
        int catchUps = 0;
        for (int li = 0; li < lists.length; li++) {
            String one = cleanUrl(lists[li]);
            if (one.isEmpty()) continue;
            p.update(lists.length > 1 ? "Downloading playlist " + (li + 1) + " of " + lists.length + "…" : "Downloading playlist…");
            List<JSONObject> got;
            String[] he = new String[1];
            HttpURLConnection c = open(one, null, null);
            try {
                int code = c.getResponseCode();
                if (code != 200) throw new IOException((lists.length > 1 ? "Playlist " + (li + 1) : "Playlist link") + " returned HTTP " + code);
                got = parseM3u(maybeGunzip(c.getInputStream()), he);
            } finally {
                c.disconnect();
            }
            if (he[0] != null && !he[0].isEmpty()) { if (headerGuides.length() > 0) headerGuides.append(','); headerGuides.append(he[0]); }
            // an Xtream service: which channels keep an archive (catch-up), and its clock's time zone
            CatchUp.Xt x = CatchUp.fromPlaylist(one);
            if (x == null) for (JSONObject ch : got) { String[] q = CatchUp.fromChannel(ch.optString("u")); if (q != null) { x = new CatchUp.Xt(); x.base = q[0]; x.user = q[1]; x.pass = q[2]; break; } }
            if (x != null) {
                try {
                    p.update("Checking which channels have catch-up…");
                    Map<String, Integer> arch = new HashMap<>();
                    CatchUp.archives(x, arch);
                    x.tz = CatchUp.timezone(x);
                    int xi = services.size(); services.add(x);
                    for (JSONObject ch : got) {
                        String[] q = CatchUp.fromChannel(ch.optString("u"));
                        if (q == null || !q[1].equals(x.user)) continue;
                        ch.put("sid", q[3]); ch.put("xi", xi);
                        Integer d = arch.get(q[3]);
                        if (d != null && (ch.optString("cu").isEmpty() || ch.optString("cu").equalsIgnoreCase("xc"))) { ch.put("cu", "xc"); ch.put("cd", d); }
                    }
                    Diag.ev("live: Xtream service, " + arch.size() + " channels with catch-up, time zone " + (x.tz.isEmpty() ? "?" : x.tz));
                } catch (Exception e) { Diag.ev("live: catch-up list unavailable (" + e.getMessage() + ")"); }
            }
            int base = channels.size();
            for (JSONObject ch : got) { if (lists.length > 1 && base > 0) ch.put("i", ch.optInt("i") + base); if (!ch.optString("cu").isEmpty()) catchUps++; channels.add(ch); }
        }
        CatchUp.writeServices(dir, services);
        if (headerGuides.length() > 0) headerEpg[0] = headerGuides.toString();
        if (channels.isEmpty()) throw new IOException("No live channels found in that playlist");

        JSONArray arr = new JSONArray();
        Set<String> ids = new HashSet<>();
        Map<String, String> names = new HashMap<>();
        for (JSONObject ch : channels) {
            arr.put(ch);
            String k = ch.optString("k");
            if (k.startsWith("n:")) names.put(k.substring(2), k); else ids.add(k);
        }
        writeFile(new File(dir, "channels.json"), arr.toString());

        String guide = (epgUrl != null && !epgUrl.trim().isEmpty()) ? epgUrl.trim() : headerEpg[0];
        String status = channels.size() + " channels" + (catchUps > 0 ? " · " + catchUps + " with catch-up" : "");
        // channels with catch-up tags of the playlist's own (not an Xtream archive, which is asked for when needed):
        // their past shows are kept from the guide too (up to two days back)
        pastFrom.clear();
        long nowMs = System.currentTimeMillis();
        for (JSONObject ch : channels) {
            String cu = ch.optString("cu");
            if (cu.isEmpty() || (cu.equalsIgnoreCase("xc") && ch.has("xi"))) continue;
            pastFrom.put(ch.optString("k"), nowMs - Math.min(2, Math.max(1, ch.optInt("cd", 1))) * 86400000L);
        }
        if (guide == null || guide.isEmpty()) {
            writeFile(new File(dir, "epg.json"), "{}");
            synchronized (epgLock) { times = null; texts = null; }
            return status + " · no guide link";
        }
        int matched = 0;
        Exception guideError = null;
        for (String g : guide.split(",")) {          // some playlists list several guides
            g = g.trim();
            if (g.isEmpty()) continue;
            p.update("Downloading guide…");
            try {
                matched = loadGuide(g, ids, names, p);
                guideError = null;
                break;
            } catch (Exception e) {
                guideError = e;
            }
        }
        if (guideError != null) return status + " · guide failed: " + guideError.getMessage();
        return status + " · guide for " + matched;
    }

    /** Repairs common paste mistakes: whitespace, doubled or broken "http://" prefixes. */
    static String cleanUrl(String v) {
        if (v == null) return "";
        v = v.replaceAll("\\s+", "");
        v = v.replaceFirst("(?i)^(?:https?:?/*)+(?=https?://)", "");
        v = v.replaceFirst("(?i)^(https?)//", "$1://").replaceFirst("(?i)^(https?):/(?!/)", "$1://");
        if (!v.isEmpty() && !v.matches("(?i)^https?://.*") && v.contains(".")) v = "http://" + v;
        return v.matches("(?i)^https?://$") ? "" : v;
    }

    // ---------------------------------------------------------------- M3U

    private static final Pattern ATTR = Pattern.compile("([A-Za-z0-9_-]+)=\"([^\"]*)\"");

    static List<JSONObject> parseM3u(InputStream in, String[] headerEpg) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, UTF8), 65536);
        List<JSONObject> out = new ArrayList<>();
        Map<String, String> cur = null;
        String name = null, ua = null, grp = null;
        String line;
        int index = 0;
        boolean first = true;
        Map<String, String> hdrCu = new HashMap<>();
        while ((line = r.readLine()) != null) {
            line = line.replace("﻿", "").trim();
            if (line.isEmpty()) continue;
            if (first) {
                first = false;
                String l = line.toLowerCase(Locale.US);
                if (l.startsWith("<!doctype") || l.startsWith("<html")) {
                    throw new IOException("That link opened a web page, not a playlist. If it's a Google Drive file, share it as \"Anyone with the link\".");
                }
            }
            if (line.startsWith("﻿")) line = line.substring(1);
            if (line.startsWith("#EXTM3U")) {
                Map<String, String> a = attrs(line);
                for (String k : new String[] { "catchup", "catchup-type", "catchup-days", "catchup-source", "tvg-rec", "timeshift" }) if (a.get(k) != null && !a.get(k).isEmpty()) hdrCu.put(k, a.get(k));
                String e = a.get("url-tvg");
                if (e == null) e = a.get("x-tvg-url");
                if (e != null && !e.isEmpty()) headerEpg[0] = e;
                continue;
            }
            if (line.startsWith("#EXTINF")) {
                cur = attrs(line);
                name = displayName(line);
                grp = null;
                continue;
            }
            if (line.startsWith("#EXTGRP:")) { grp = line.substring(8).trim(); continue; }
            if (line.toLowerCase(Locale.US).startsWith("#extvlcopt:http-user-agent=")) { ua = line.substring(line.indexOf('=') + 1).trim(); continue; }
            if (line.startsWith("#")) continue;

            String url = line;
            if (cur == null) cur = new HashMap<>();
            String lu = url.toLowerCase(Locale.US);
            boolean vod = lu.contains("/movie/") || lu.contains("/series/")
                    || lu.matches(".*\\.(mp4|mkv|avi|mov|wmv|flv|m4v)(\\?.*)?$");
            if (!vod && (lu.startsWith("http://") || lu.startsWith("https://"))) {
                JSONObject ch = new JSONObject();
                String n = name != null && !name.isEmpty() ? name : (cur.containsKey("tvg-name") ? cur.get("tvg-name") : "Channel " + (index + 1));
                String g = cur.get("group-title");
                if (g == null || g.isEmpty()) g = grp;
                if (g == null || g.isEmpty()) g = "Other";
                String id = cur.get("tvg-id");
                String key = (id != null && !id.trim().isEmpty()) ? id.trim()
                        : "n:" + norm(cur.containsKey("tvg-name") && !cur.get("tvg-name").isEmpty() ? cur.get("tvg-name") : n);
                ch.put("i", index);
                ch.put("n", n);
                ch.put("g", g);
                ch.put("u", url);
                ch.put("k", key);
                String logo = cur.get("tvg-logo");
                if (logo != null && !logo.isEmpty()) ch.put("l", logo);
                String num = cur.get("tvg-chno");
                if (num != null && !num.isEmpty()) ch.put("c", num);
                if (ua != null) ch.put("ua", ua);
                // catch-up, as the playlist tags it (Kodi / TiviMate attributes)
                String cuT = pick(cur, hdrCu, "catchup", "catchup-type"), cuD = pick(cur, hdrCu, "catchup-days", "tvg-rec"), cuS = pick(cur, hdrCu, "catchup-source", null);
                if (cuD == null || cuD.isEmpty()) cuD = pick(cur, hdrCu, "timeshift", null);
                int days = 0; try { days = cuD == null ? 0 : (int) Double.parseDouble(cuD.trim()); } catch (Exception ignored) { }
                if ((cuT == null || cuT.isEmpty()) && days > 0) cuT = cuS != null && !cuS.isEmpty() ? "default" : "shift";
                if (cuT != null && !cuT.isEmpty() && !cuT.equalsIgnoreCase("none") && !cuT.equalsIgnoreCase("disabled")) {
                    ch.put("cu", cuT.toLowerCase(Locale.US)); ch.put("cd", Math.max(1, days));
                    if (cuS != null && !cuS.isEmpty()) ch.put("cs", cuS);
                }
                out.add(ch);
                index++;
            }
            cur = null; name = null; ua = null; grp = null;
        }
        return out;
    }

    private static String pick(Map<String, String> a, Map<String, String> d, String k1, String k2) {
        String v = a.get(k1); if ((v == null || v.isEmpty()) && k2 != null) v = a.get(k2);
        if (v == null || v.isEmpty()) { v = d.get(k1); if ((v == null || v.isEmpty()) && k2 != null) v = d.get(k2); }
        return v;
    }
    private static Map<String, String> attrs(String line) {
        Map<String, String> m = new HashMap<>();
        Matcher mt = ATTR.matcher(line);
        while (mt.find()) m.put(mt.group(1).toLowerCase(Locale.US), mt.group(2).trim());
        return m;
    }

    /** The channel name is everything after the first comma that isn't inside quotes. */
    private static String displayName(String line) {
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') q = !q;
            else if (ch == ',' && !q) return line.substring(i + 1).trim();
        }
        return "";
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
    }

    // ---------------------------------------------------------------- XMLTV

    /** guide key → the oldest programme kept (channels whose catch-up comes from the playlist's own tags) */
    private final Map<String, Long> pastFrom = new HashMap<>();
    /** the Xtream services found in the playlists (for catch-up) */
    List<CatchUp.Xt> services() { return CatchUp.readServices(dir); }

    private int loadGuide(String url, Set<String> ids, Map<String, String> names, Progress p) throws Exception {
        HttpURLConnection c = open(url, null, null);
        Map<String, List<long[]>> t = new HashMap<>();
        Map<String, List<String[]>> x = new HashMap<>();
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            parseXmltv(maybeGunzip(c.getInputStream()), ids, names, t, x, p, pastFrom);
        } finally {
            c.disconnect();
        }
        /* sort each channel's programmes by start time and save — written out as it goes (a big guide made one
           string of 150 MB first, and TopShelf ran out of memory) */
        File tmpF = new File(dir, "epg.json.tmp");
        android.util.JsonWriter jw = new android.util.JsonWriter(new java.io.BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmpF), UTF8), 1 << 16));
        try {
        jw.beginObject();
        for (Map.Entry<String, List<long[]>> e : t.entrySet()) {
            List<long[]> lt = e.getValue();
            List<String[]> lx = x.get(e.getKey());
            Integer[] order = new Integer[lt.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            final List<long[]> flt = lt;
            java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
                @Override public int compare(Integer a, Integer b) { return Long.compare(flt.get(a)[0], flt.get(b)[0]); }
            });
            List<long[]> st = new ArrayList<>(order.length);
            List<String[]> sx = new ArrayList<>(order.length);
            jw.name(e.getKey());
            jw.beginArray();
            for (Integer i : order) {
                long[] se = lt.get(i);
                String[] tx = lx.get(i);
                if (!st.isEmpty() && st.get(st.size() - 1)[0] == se[0]) continue;   // duplicate slot
                st.add(se); sx.add(tx);
                jw.beginArray();
                jw.value(se[0] / 1000L); jw.value(se[1] / 1000L);
                jw.value(tx[0] == null ? "" : tx[0]); jw.value(tx.length > 1 && tx[1] != null ? tx[1] : "");
                if (tx.length > 2 && tx[2] != null && !tx[2].isEmpty()) jw.value(tx[2]);
                jw.endArray();
            }
            jw.endArray();
            lt.clear(); lt.addAll(st);
            lx.clear(); lx.addAll(sx);
        }
        jw.endObject();
        } finally { jw.close(); }
        if (!tmpF.renameTo(new File(dir, "epg.json"))) throw new IOException("Couldn't save epg.json");
        dedupe(x);
        synchronized (epgLock) { texts = x; times = t; }
        return t.size();
    }

    static void parseXmltv(InputStream in, Set<String> ids, Map<String, String> names,
                                   Map<String, List<long[]>> t, Map<String, List<String[]>> x,
                                   Progress p, Map<String, Long> pastFrom) throws Exception {
        XmlPullParser xp = Xml.newPullParser();
        xp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        xp.setInput(in, null);
        long now = System.currentTimeMillis();
        final long recent = now - 3L * 3600_000L;
        long from = recent, to = now + 36L * 3600_000L;
        if (pastFrom != null) for (Long v : pastFrom.values()) if (v < from) from = v;
        Map<String, List<String>> alias = new HashMap<>();
        int count = 0;

        String chanId = null;                 // inside <channel>
        List<String> progKeys = null;         // inside a wanted <programme>
        long ps = 0, pe = 0;
        String title = null, desc = null, sub = null, epn = null, epnSys = null; boolean isNew = false, prevShown = false;
        String textTag = null;
        StringBuilder text = new StringBuilder();

        int ev = xp.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                String tag = xp.getName();
                if ("channel".equals(tag)) {
                    chanId = xp.getAttributeValue(null, "id");
                    if (chanId != null && ids.contains(chanId)) addAlias(alias, chanId, chanId);
                } else if ("display-name".equals(tag) && chanId != null) {
                    textTag = "display-name"; text.setLength(0);
                } else if ("programme".equals(tag)) {
                    String ch = xp.getAttributeValue(null, "channel");
                    List<String> keys = ch == null ? null : alias.get(ch);
                    if (keys == null && ch != null && ids.contains(ch)) { addAlias(alias, ch, ch); keys = alias.get(ch); }
                    ps = parseTime(xp.getAttributeValue(null, "start"));
                    pe = parseTime(xp.getAttributeValue(null, "stop"));
                    if (pe <= 0 && ps > 0) pe = ps + 30 * 60_000L;
                    if (keys != null && ps > 0 && pe > from && ps < to) {
                        progKeys = keys; title = null; desc = null; sub = null; epn = null; epnSys = null; isNew = false; prevShown = false;
                    } else {
                        skipSubtree(xp);
                    }
                } else if (progKeys != null && ("title".equals(tag) || "desc".equals(tag) || "sub-title".equals(tag))) {
                    textTag = tag; text.setLength(0);
                } else if (progKeys != null && "episode-num".equals(tag)) {
                    textTag = tag; text.setLength(0); epnSys = xp.getAttributeValue(null, "system");
                } else if (progKeys != null && "new".equals(tag)) {
                    isNew = true;
                } else if (progKeys != null && "previously-shown".equals(tag)) {
                    prevShown = true;
                }
            } else if (ev == XmlPullParser.TEXT) {
                if (textTag != null) text.append(xp.getText());
            } else if (ev == XmlPullParser.END_TAG) {
                String tag = xp.getName();
                if (textTag != null && textTag.equals(tag)) {
                    String s = text.toString().trim();
                    if ("display-name".equals(tag)) {
                        String k = names.get(norm(s));
                        if (k != null) addAlias(alias, chanId, k);
                    } else if ("title".equals(tag) && title == null) {
                        title = s;
                    } else if ("desc".equals(tag) && desc == null) {
                        desc = s.length() > 400 ? s.substring(0, 400) + "…" : s;
                    } else if ("sub-title".equals(tag) && sub == null) {
                        sub = s;
                    } else if ("episode-num".equals(tag) && !s.isEmpty()) {
                        // the readable form (S2 E5) wins over xmltv_ns ("1.4.0/1")
                        if (epn == null || "onscreen".equals(epnSys)) epn = "xmltv_ns".equals(epnSys) ? ns(s) : s;
                    }
                    textTag = null;
                } else if ("channel".equals(tag)) {
                    chanId = null;
                } else if ("programme".equals(tag) && progKeys != null) {
                    for (String k : progKeys) {
                        Long kf = pastFrom == null ? null : pastFrom.get(k);
                        if (pe <= (kf == null ? recent : Math.min(recent, kf))) continue;
                        List<long[]> lt = t.get(k);
                        if (lt == null) { lt = new ArrayList<>(); t.put(k, lt); x.put(k, new ArrayList<String[]>()); }
                        lt.add(new long[]{ps, pe});
                        x.get(k).add(new String[]{title == null ? "" : title, desc == null ? "" : desc, extra(isNew, prevShown, sub, epn)});
                    }
                    progKeys = null;
                    if (++count % 5000 == 0) p.update("Reading guide… " + count + " programmes");
                }
            }
            ev = xp.next();
        }
    }

    /** a programme's extras: "N" (new) / "P" (a repeat), then the episode's name and number, tab-separated */
    private static String extra(boolean isNew, boolean prev, String sub, String epn) {
        String f = isNew ? "N" : prev ? "P" : "";
        if (f.isEmpty() && sub == null && epn == null) return "";
        return f + "\t" + (sub == null ? "" : sub.replace('\t', ' ')) + "\t" + (epn == null ? "" : epn.replace('\t', ' '));
    }
    /** xmltv_ns "1.4.0/1" (zero-based season.episode.part) as "S2 E5" */
    private static String ns(String v) {
        try {
            String[] p = v.split("\\.", -1);
            String se = p.length > 0 ? p[0].split("/")[0].trim() : "", ep = p.length > 1 ? p[1].split("/")[0].trim() : "";
            String out = (se.isEmpty() ? "" : "S" + (Integer.parseInt(se) + 1)) + (ep.isEmpty() ? "" : (se.isEmpty() ? "" : " ") + "E" + (Integer.parseInt(ep) + 1));
            return out.isEmpty() ? v : out;
        } catch (Exception e) { return v; }
    }

    private static void addAlias(Map<String, List<String>> alias, String xmlId, String key) {
        List<String> l = alias.get(xmlId);
        if (l == null) { l = new ArrayList<>(2); alias.put(xmlId, l); }
        if (!l.contains(key)) l.add(key);
    }

    private static void skipSubtree(XmlPullParser xp) throws Exception {
        int depth = 1;
        while (depth > 0) {
            int ev = xp.next();
            if (ev == XmlPullParser.START_TAG) depth++;
            else if (ev == XmlPullParser.END_TAG) depth--;
            else if (ev == XmlPullParser.END_DOCUMENT) return;
        }
    }

    /** XMLTV time: "20261020013000 +0000" (offset optional). */
    static long parseTime(String s) {
        if (s == null) return 0;
        s = s.trim();
        if (s.length() < 12) return 0;
        try {
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            c.clear();
            c.set(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(4, 6)) - 1,
                    Integer.parseInt(s.substring(6, 8)), Integer.parseInt(s.substring(8, 10)),
                    Integer.parseInt(s.substring(10, 12)), s.length() >= 14 && Character.isDigit(s.charAt(13)) ? Integer.parseInt(s.substring(12, 14)) : 0);
            long ms = c.getTimeInMillis();
            int sp = s.indexOf(' ');
            String off = sp > 0 ? s.substring(sp + 1).trim() : (s.length() > 14 ? s.substring(14).trim() : "");
            if (off.length() >= 5 && (off.charAt(0) == '+' || off.charAt(0) == '-')) {
                int h = Integer.parseInt(off.substring(1, 3)), m = Integer.parseInt(off.substring(3, 5));
                long o = (h * 60L + m) * 60_000L;
                ms += off.charAt(0) == '+' ? -o : o;
            }
            return ms;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- networking

    static HttpURLConnection open(String url, String ua, String range) throws IOException {
        for (int hop = 0; hop < 8; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(12_000);
            c.setReadTimeout(30_000);
            c.setRequestProperty("User-Agent", ua != null && !ua.isEmpty() ? ua : DEFAULT_UA);
            c.setRequestProperty("Accept", "*/*");
            if (range != null) c.setRequestProperty("Range", range);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("Redirect without a location");
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            return c;
        }
        throw new IOException("Too many redirects");
    }

    private static InputStream maybeGunzip(InputStream in) throws IOException {
        BufferedInputStream b = new BufferedInputStream(in, 65536);
        b.mark(4);
        int m1 = b.read(), m2 = b.read();
        b.reset();
        if (m1 == 0x1f && m2 == 0x8b) return new GZIPInputStream(b, 65536);
        return b;
    }

    // Live channel streams currently being proxied. IPTV services usually allow only one or two
    // connections, so a new channel must never wait for the old one to be torn down.
    private static final List<HttpURLConnection> liveConns = new ArrayList<>();

    static void closeLiveStreams() {
        List<HttpURLConnection> old;
        synchronized (liveConns) { old = new ArrayList<>(liveConns); liveConns.clear(); }
        for (HttpURLConnection c : old) { try { c.disconnect(); } catch (Exception ignored) { } }
    }

    /** Wraps a live stream: big read buffer, and closing it frees the connection immediately. */
    private static final class LiveStream extends java.io.FilterInputStream {
        private final HttpURLConnection conn;
        LiveStream(InputStream in, HttpURLConnection conn) { super(new BufferedInputStream(in, 512 * 1024)); this.conn = conn; }
        @Override public void close() throws IOException {
            synchronized (liveConns) { liveConns.remove(conn); }
            try { super.close(); } finally { conn.disconnect(); }
        }
    }

    /** Streams any http(s) URL to the page with CORS allowed; rewrites HLS playlists to absolute links. */
    static WebResourceResponse proxy(String url, String ua, Map<String, String> reqHeaders) {
        return proxy(url, ua, reqHeaders, false, false);
    }

    static WebResourceResponse proxy(String url, String ua, Map<String, String> reqHeaders, boolean live) {
        return proxy(url, ua, reqHeaders, live, live);
    }

    /** live: a never-ending channel stream (tracked so it can be cut off instantly).
     *  exclusive: a new channel replaces whatever was playing; Multiview tiles share instead. */
    static WebResourceResponse proxy(String url, String ua, Map<String, String> reqHeaders, boolean live, boolean exclusive) {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        try {
            if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) throw new IOException("bad url");
            String range = null;
            if (reqHeaders != null) for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
                if ("range".equalsIgnoreCase(e.getKey())) range = e.getValue();
            }
            if (live && exclusive) closeLiveStreams();
            HttpURLConnection c = open(url, ua, range);
            int code = c.getResponseCode();
            String ct = c.getContentType();
            String mime = ct == null ? "application/octet-stream" : ct.split(";")[0].trim().toLowerCase(Locale.US);
            String finalUrl = c.getURL().toString();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) in = new ByteArrayInputStream(new byte[0]);
            String path = finalUrl.toLowerCase(Locale.US);
            int qi = path.indexOf('?');
            if (qi >= 0) path = path.substring(0, qi);
            if (code < 400 && (mime.contains("mpegurl") || path.endsWith(".m3u8"))) {
                String body = new String(readAll(in), UTF8);
                c.disconnect();
                if (body.trim().startsWith("#EXTM3U")) {
                    in = new ByteArrayInputStream(absolutize(body, finalUrl).getBytes(UTF8));
                    mime = "application/vnd.apple.mpegurl";
                } else {
                    in = new ByteArrayInputStream(body.getBytes(UTF8));
                }
                h.put("Cache-Control", "no-cache");
            } else if (mime.startsWith("image/")) {
                h.put("Cache-Control", "max-age=86400");
            } else if (live && code < 400) {
                synchronized (liveConns) { liveConns.add(c); }
                in = new LiveStream(in, c);
                h.put("Cache-Control", "no-cache");
            } else {
                h.put("Cache-Control", "no-cache");
            }
            String cr = c.getHeaderField("Content-Range");
            if (cr != null) h.put("Content-Range", cr);
            String ar = c.getHeaderField("Accept-Ranges");
            if (ar != null) h.put("Accept-Ranges", ar);
            String reason = c.getResponseMessage();
            if (reason == null || reason.isEmpty()) reason = code < 400 ? "OK" : "Error";
            if (code < 100 || code > 599 || (code >= 300 && code < 400)) code = 502;
            return new WebResourceResponse(mime, null, code, reason, h, in);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new WebResourceResponse("text/plain", "utf-8", 502, "Bad Gateway", h,
                    new ByteArrayInputStream(msg.getBytes(UTF8)));
        }
    }

    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");

    static String absolutize(String body, String base) {
        StringBuilder sb = new StringBuilder(body.length() + 256);
        for (String line : body.split("\r?\n")) {
            String l = line.trim();
            if (l.isEmpty()) { sb.append('\n'); continue; }
            if (l.startsWith("#")) {
                Matcher m = URI_ATTR.matcher(l);
                StringBuffer b2 = new StringBuffer();
                while (m.find()) m.appendReplacement(b2, Matcher.quoteReplacement("URI=\"" + resolve(base, m.group(1)) + "\""));
                m.appendTail(b2);
                sb.append(b2).append('\n');
            } else {
                sb.append(resolve(base, l)).append('\n');
            }
        }
        return sb.toString();
    }

    private static String resolve(String base, String rel) {
        try { return new URL(new URL(base), rel).toString(); } catch (Exception e) { return rel; }
    }

    // ---------------------------------------------------------------- files

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[32768];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private static String readFile(File f, String fallback) {
        if (!f.exists()) return fallback;
        try (InputStream in = new java.io.FileInputStream(f)) {
            return new String(readAll(in), UTF8);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void writeFile(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), UTF8)) { w.write(s); }
        if (!tmp.renameTo(f)) throw new IOException("Couldn't save " + f.getName());
    }
}
