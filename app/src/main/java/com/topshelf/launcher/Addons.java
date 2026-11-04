package com.topshelf.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Stremio-style addons (the same kind Nuvio and Stremio use), from their public protocol: an addon is a web
 * address whose manifest.json says what it offers —
 *   catalog   rows of titles             {base}/catalog/{type}/{id}[/{extra}].json → {metas}
 *   meta      a title's details/episodes {base}/meta/{type}/{id}.json → {meta}
 *   stream    ways to play a title       {base}/stream/{type}/{id}.json → {streams}   (episodes: tt123:1:2)
 *   subtitles                            {base}/subtitles/{type}/{id}.json → {subtitles}
 * Installed addons are kept in prefs ("ui.addons"): [{url, manifest, on, rows}], in order.
 * Streams that are web addresses (a debrid service's links) play in TopShelf's player; torrent-only results
 * (an infoHash and no address) need a torrent engine and are left out.
 */
final class Addons {
    static final String CINEMETA = "https://v3-cinemeta.strem.io/manifest.json";
    private static Addons inst;
    static synchronized Addons get(Context c) { if (inst == null) inst = new Addons(c.getApplicationContext()); return inst; }

    private final SharedPreferences prefs;
    /* (an addon slow to answer for one episode mustn't hold up the next episode's request: no fixed queue) */
    private final ExecutorService pool = Executors.newCachedThreadPool();
    /* what each addon answered for a title, for ten minutes (going back to an episode shows its streams at once) */
    private final java.util.Map<String, Object[]> streamCache = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, Object[]>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Object[]> e) { return size() > 60; }
    });
    volatile String setupKey = ""; volatile long setupUntil;
    /** told when the list changes (Movies & TV rebuild their rows) */
    static volatile Runnable onChange;

    private Addons(Context c) { prefs = c.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE); }

    // ================================================================== the list

    synchronized JSONArray list() {
        try { return new JSONArray(prefs.getString("ui.addons", "[]")); } catch (Exception e) { return new JSONArray(); }
    }
    private synchronized void save(JSONArray a) {
        prefs.edit().putString("ui.addons", a.toString()).apply();
        Runnable r = onChange; if (r != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }
    /** the enabled ones (Cinemeta isn't in the list: it's always there for details) */
    List<JSONObject> enabled() {
        List<JSONObject> out = new ArrayList<>();
        JSONArray a = list();
        for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null && o.optBoolean("on", true)) out.add(o); }
        return out;
    }
    boolean anyStreams() { for (JSONObject o : enabled()) if (offers(o.optJSONObject("manifest"), "stream", null, null)) return true; return false; }

    /* An addon's address can carry its settings after a "?" (…/manifest.json?key=…): that part is kept, and goes
       on every request to it (Nuvio does the same), rather than being cut off with "manifest.json". */
    static String normalize(String url) {
        String u = url == null ? "" : url.trim();
        if (u.startsWith("stremio://")) u = "https://" + u.substring(10);
        if (!u.matches("(?i)^https?://.*")) u = "https://" + u;
        String q = "";
        int qi = u.indexOf('?');
        if (qi >= 0) { q = u.substring(qi); u = u.substring(0, qi); }
        if (!u.endsWith("manifest.json")) u = u.replaceAll("/+$", "") + "/manifest.json";
        return u + q;
    }
    /** the addon's address without "manifest.json" (and without its "?settings") */
    static String base(String manifestUrl) { return manifestUrl.replaceAll("\\?.*$", "").replaceAll("/manifest\\.json$", "").replaceAll("/+$", ""); }
    /** a request to the addon: its base, the path, and its "?settings" if it has them */
    static String res(String manifestUrl, String path) {
        int qi = manifestUrl.indexOf('?');
        return base(manifestUrl) + path + (qi >= 0 ? manifestUrl.substring(qi) : "");
    }

    /** adds (or refreshes) an addon from its manifest's address; "" when it worked, else why not */
    String install(String raw) {
        String url = normalize(raw);
        try {
            JSONObject m = new JSONObject(get(url));
            if (m.optString("id").isEmpty() || m.optString("name").isEmpty()) return "That address isn't an addon (its manifest has no id or name).";
            synchronized (this) {
                JSONArray a = list(), out = new JSONArray();
                boolean had = false;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o.optString("url").equals(url) || o.optJSONObject("manifest") != null && o.optJSONObject("manifest").optString("id").equals(m.optString("id")) && base(o.optString("url")).equals(base(url))) {
                        o.put("manifest", m); o.put("url", url); had = true;
                    }
                    out.put(o);
                }
                if (!had) out.put(new JSONObject().put("url", url).put("manifest", m).put("on", true).put("rows", true).put("added", System.currentTimeMillis()));
                save(out);
            }
            Diag.ev("addons: installed " + m.optString("name") + " " + m.optString("version"));
            return "";
        } catch (Exception e) { return "Couldn't install it: " + e.getMessage(); }
    }
    synchronized void remove(String url) {
        JSONArray a = list(), out = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (!a.optJSONObject(i).optString("url").equals(url)) out.put(a.optJSONObject(i));
        save(out);
    }
    synchronized void set(String url, String key, boolean v) {
        JSONArray a = list();
        for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i).optString("url").equals(url)) try { a.optJSONObject(i).put(key, v); } catch (Exception ignored) { }
        save(a);
    }
    synchronized void move(String url, int dir) {
        JSONArray a = list();
        List<Object> l = new ArrayList<>(); for (int i = 0; i < a.length(); i++) l.add(a.opt(i));
        for (int i = 0; i < l.size(); i++) if (((JSONObject) l.get(i)).optString("url").equals(url)) {
            int j = i + dir; if (j < 0 || j >= l.size()) return;
            Object t = l.get(i); l.set(i, l.get(j)); l.set(j, t); break;
        }
        save(new JSONArray(l));
    }

    // ================================================================== what an addon offers

    /** does the manifest offer this resource (for this type and id)? */
    static boolean offers(JSONObject m, String resource, String type, String id) {
        if (m == null) return false;
        JSONArray rs = m.optJSONArray("resources");
        if (rs == null) return false;
        for (int i = 0; i < rs.length(); i++) {
            Object r = rs.opt(i);
            JSONArray types = m.optJSONArray("types"), pre = m.optJSONArray("idPrefixes");
            String name;
            if (r instanceof JSONObject) {
                name = ((JSONObject) r).optString("name");
                if (((JSONObject) r).optJSONArray("types") != null) types = ((JSONObject) r).optJSONArray("types");
                if (((JSONObject) r).optJSONArray("idPrefixes") != null) pre = ((JSONObject) r).optJSONArray("idPrefixes");
            } else name = String.valueOf(r);
            if (!name.equals(resource)) continue;
            if (type != null && types != null && !has(types, type)) continue;
            if (id != null && pre != null && pre.length() > 0) {
                boolean ok = false;
                for (int k = 0; k < pre.length(); k++) if (id.startsWith(pre.optString(k))) ok = true;
                if (!ok) continue;
            }
            return true;
        }
        return false;
    }
    private static boolean has(JSONArray a, String v) { for (int i = 0; i < a.length(); i++) if (v.equals(a.optString(i))) return true; return false; }

    /** the catalogs to show as rows for movies ("movie") or TV ("series"): [{title, url, addon}] */
    List<JSONObject> catalogs(String type) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject o : enabled()) {
            if (!o.optBoolean("rows", true)) continue;
            JSONObject m = o.optJSONObject("manifest");
            JSONArray cs = m == null ? null : m.optJSONArray("catalogs");
            if (cs == null) continue;
            for (int i = 0; i < cs.length(); i++) {
                JSONObject c = cs.optJSONObject(i);
                if (c == null || !type.equals(c.optString("type"))) continue;
                if (needsExtra(c)) continue;                  // (search-only and similar: not a row)
                try {
                    String name = c.optString("name", c.optString("id"));
                    out.add(new JSONObject().put("title", name + " · " + m.optString("name")).put("addon", m.optString("name"))
                            .put("url", res(o.optString("url"), "/catalog/" + type + "/" + enc(c.optString("id")) + ".json")).put("id", m.optString("id") + "/" + c.optString("id")));
                } catch (Exception ignored) { }
            }
        }
        return out;
    }
    private static boolean needsExtra(JSONObject c) {
        JSONArray req = c.optJSONArray("extraRequired");
        if (req != null && req.length() > 0) return true;
        JSONArray ex = c.optJSONArray("extra");
        if (ex != null) for (int i = 0; i < ex.length(); i++) { JSONObject e = ex.optJSONObject(i); if (e != null && e.optBoolean("isRequired")) return true; }
        return false;
    }

    // ================================================================== a title

    /** a title's details (with episodes for a series): the first addon that has it, then Cinemeta */
    JSONObject meta(String type, String id) {
        List<String> addrs = new ArrayList<>();
        for (JSONObject o : enabled()) if (offers(o.optJSONObject("manifest"), "meta", type, id)) addrs.add(o.optString("url"));
        addrs.add(CINEMETA);
        for (String b : addrs) {
            try {
                JSONObject j = new JSONObject(get(res(b, "/meta/" + type + "/" + enc(id) + ".json")));
                JSONObject m = j.optJSONObject("meta");
                if (m != null && !m.optString("name").isEmpty()) return m;
            } catch (Exception ignored) { }
        }
        return null;
    }

    interface StreamsListener { void addon(String name, List<JSONObject> streams, String error); }
    /** what a request for streams is (so a newer one can call off the older one's waiting) */
    static final class Ask { volatile boolean off; final List<Future<?>> fs = new ArrayList<>(); void cancel() { off = true; for (Future<?> f : fs) f.cancel(true); } }
    /** asks every stream addon at once; each one's answer is passed on as it arrives. Returns once all have answered (or 25 s). */
    void streams(final String type, final String id, final StreamsListener l) { streams(type, id, l, new Ask()); }
    void streams(final String type, final String id, final StreamsListener l, final Ask ask) {
        for (final JSONObject o : enabled()) {
            final JSONObject m = o.optJSONObject("manifest");
            if (!offers(m, "stream", type, id)) continue;
            final String name = m.optString("name"), key = o.optString("url") + "|" + type + "|" + id;
            Object[] hit = streamCache.get(key);
            if (hit != null && System.currentTimeMillis() - (Long) hit[0] < 30 * 60000L) {
                @SuppressWarnings("unchecked") List<JSONObject> got = copy((List<JSONObject>) hit[1]);
                l.addon(name, got, (String) hit[2]);
                continue;
            }
            ask.fs.add(pool.submit(new Runnable() { @Override public void run() {
                if (ask.off) return;                          // (a newer request took over before this one started)
                List<JSONObject> got = new ArrayList<>(); String err = "";
                int all = 0; String firstOther = "";
                java.util.Set<String> seen = new java.util.HashSet<>();
                try {
                    JSONArray s = new JSONObject(get(res(o.optString("url"), "/stream/" + type + "/" + enc(id) + ".json"))).optJSONArray("streams");
                    // nothing from the stream address: some addons list a video's links in its details instead (as Nuvio checks)
                    if ((s == null || s.length() == 0) && offers(m, "meta", type, id)) s = inlineStreams(o.optString("url"), type, id);
                    if (s != null) for (int i = 0; i < s.length(); i++) {
                        JSONObject x = s.optJSONObject(i);
                        if (x == null) continue;
                        all++;
                        String u = x.optString("url");
                        if (!u.matches("(?i)^https?://.*")) {        // torrent-only or external: not playable here
                            if (firstOther.isEmpty()) firstOther = (x.optString("name") + " " + (x.optString("description").isEmpty() ? x.optString("title") : x.optString("description"))).replaceAll("\\s+", " ").trim();
                            continue;
                        }
                        if (!seen.add(u)) continue;                  // (the same link listed twice)
                        x.put("_addon", name);
                        got.add(x);
                    }
                    // nothing playable: say why (an addon's own error message comes as a stream with no link)
                    if (got.isEmpty()) err = all == 0 ? "found no streams for this" : all + " result" + (all == 1 ? "" : "s") + " but none with a direct link" + (firstOther.isEmpty() ? "" : ": " + (firstOther.length() > 140 ? firstOther.substring(0, 140) + "…" : firstOther));
                    else streamCache.put(key, new Object[] { System.currentTimeMillis(), copy(got), err });
                } catch (Exception e) { err = why(e); }
                if (ask.off) return;
                Diag.ev("streams: " + name + " for " + id + ": " + got.size() + " playable of " + all + (err.isEmpty() ? "" : " — " + err));
                l.addon(name, got, err);
            } }));
        }
        for (Future<?> f : ask.fs) try { f.get(25, TimeUnit.SECONDS); } catch (Exception ignored) { }
    }
    /* ------------------------------------------------------------------ instant play
       Asked ahead (the next episode near the end of this one; the first titles of Continue watching while Home
       is up): the addons' answers wait in the cache above, and the link most likely to be played (the same
       source as last time) is resolved ahead too: a debrid link answers with a redirect to the real file, and
       working that out is the slow part of pressing Play. The resolved address is kept 20 minutes. */
    private final java.util.Map<String, Object[]> ready = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, Object[]>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Object[]> e) { return size() > 24; }
    });
    private final java.util.Set<String> preAsked = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    /** the address a link was resolved to ahead of time (null when there isn't one, or it's old) */
    String readyLink(String url) {
        Object[] r = url == null ? null : ready.get(url);
        return r != null && System.currentTimeMillis() - (Long) r[0] < 20 * 60000L ? (String) r[1] : null;
    }
    /** asks the addons for a video's streams now, and resolves the one most like `like` (the stream used last) */
    void prepare(final String type, final String id, final JSONObject like, final boolean resolve, final String why) {
        if (id == null || id.isEmpty() || !anyStreams()) return;
        String key = type + "|" + id + "|" + (System.currentTimeMillis() / (20 * 60000L));
        if (!preAsked.add(key)) return;
        pool.submit(new Runnable() { @Override public void run() {
            final long t0 = System.currentTimeMillis();
            final List<JSONObject> all = java.util.Collections.synchronizedList(new ArrayList<JSONObject>());
            streams(type, id, new StreamsListener() { @Override public void addon(String name, List<JSONObject> got, String err) { all.addAll(got); } });
            if (all.isEmpty()) { Diag.ev("instant play: nothing found ahead for " + why); return; }
            JSONObject pick = like(all, like);
            String extra = "";
            if (resolve && pick != null) {
                long r0 = System.currentTimeMillis();
                String fin = resolve(pick.optString("url"), headersOf(pick));
                if (fin != null) { ready.put(pick.optString("url"), new Object[] { System.currentTimeMillis(), fin }); extra = " · its link resolved in " + (System.currentTimeMillis() - r0) + " ms"; }
            }
            Diag.ev("instant play: " + why + " ready (" + all.size() + " streams in " + (System.currentTimeMillis() - t0) + " ms" + extra + ")");
        } });
    }
    /** of these streams, the one from the same source as `prev` (its binge group, or the same addon and quality) */
    static JSONObject like(List<JSONObject> l, JSONObject prev) {
        if (l.isEmpty()) return null;
        if (prev != null) {
            JSONObject bh = prev.optJSONObject("behaviorHints"); String bg = bh == null ? "" : bh.optString("bingeGroup");
            if (!bg.isEmpty()) for (JSONObject x : l) { JSONObject b = x.optJSONObject("behaviorHints"); if (b != null && bg.equals(b.optString("bingeGroup"))) return x; }
            String a = prev.optString("_addon", prev.optString("addon")), n = prev.optString("name");
            for (JSONObject x : l) if (x.optString("_addon").equals(a) && x.optString("name").equals(n)) return x;
            for (JSONObject x : l) if (x.optString("_addon").equals(a)) return x;
        }
        return l.get(0);
    }
    static java.util.Map<String, String> headersOf(JSONObject s) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        JSONObject bh = s.optJSONObject("behaviorHints"), ph = bh == null ? null : bh.optJSONObject("proxyHeaders"), rq = ph == null ? null : ph.optJSONObject("request");
        if (rq != null) for (java.util.Iterator<String> it = rq.keys(); it.hasNext(); ) { String k = it.next(); m.put(k, rq.optString(k)); }
        return m;
    }
    /** follows a link's redirects (asking for just its first byte) to the address of the file itself */
    private static String resolve(String url, java.util.Map<String, String> headers) {
        String u = url;
        try {
            for (int hop = 0; hop < 6; hop++) {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(8000); c.setReadTimeout(15000);
                c.setRequestProperty("Range", "bytes=0-0");
                for (java.util.Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
                int code = c.getResponseCode();
                String loc = c.getHeaderField("Location");
                try { java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream(); if (in != null) in.close(); } catch (Exception ignored) { }
                c.disconnect();
                if (code >= 300 && code < 400 && loc != null) { u = new java.net.URL(new java.net.URL(u), loc).toString(); continue; }
                return code < 400 ? u : null;
            }
        } catch (Exception e) { return null; }
        return null;
    }

    /** a stream list that can be marked (failed, last used) without touching the remembered one */
    private static List<JSONObject> copy(List<JSONObject> l) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject x : l) try { out.add(new JSONObject(x.toString())); } catch (Exception ignored) { }
        return out;
    }
    /** the streams listed inside the title's details, for this video (series: tt…:season:episode) */
    private static JSONArray inlineStreams(String addon, String type, String videoId) {
        try {
            String[] p = videoId.split(":");
            int trailing = 0; for (int i = p.length - 1; i >= 0 && p[i].matches("\\d+"); i--) trailing++;
            int min = p[0].startsWith("tt") || p[0].matches("\\d+") ? 1 : 2;
            int drop = Math.min(trailing, Math.max(0, p.length - min));
            StringBuilder metaId = new StringBuilder();
            for (int i = 0; i < p.length - drop; i++) { if (i > 0) metaId.append(':'); metaId.append(p[i]); }
            JSONObject meta = new JSONObject(get(res(addon, "/meta/" + type + "/" + enc(metaId.toString()) + ".json"))).optJSONObject("meta");
            JSONArray vs = meta == null ? null : meta.optJSONArray("videos");
            if (vs != null) for (int i = 0; i < vs.length(); i++) { JSONObject v = vs.optJSONObject(i); if (v != null && videoId.equals(v.optString("id")) && v.optJSONArray("streams") != null) return v.optJSONArray("streams"); }
            if (meta != null && videoId.equals(meta.optString("id")) && meta.optJSONArray("streams") != null) return meta.optJSONArray("streams");
        } catch (Exception ignored) { }
        return null;
    }
    /** why an addon couldn't be asked, in plain words */
    static String why(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (e instanceof java.net.UnknownHostException || m.contains("Unable to resolve host")) return "couldn't find its server (is the address right?)";
        if (e instanceof java.net.SocketTimeoutException || m.toLowerCase(Locale.US).contains("timeout")) return "it took too long to answer";
        if (e instanceof java.net.ConnectException || m.contains("Failed to connect")) return "couldn't connect to it";
        if (m.contains("CLEARTEXT")) return "it uses http:// and Android blocked it (use its https:// address)";
        if (m.equals("HTTP 404")) return "found no streams for this (404)";
        if (m.startsWith("HTTP ")) return "its server answered " + m.substring(5);
        if (e instanceof org.json.JSONException) return "its answer wasn't readable (not an addon reply)";
        return "couldn't reach it (" + m + ")";
    }

    /** subtitles from the addons that offer them: [{url, lang, addon}] */
    List<JSONObject> subtitles(String type, String id) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject o : enabled()) {
            JSONObject m = o.optJSONObject("manifest");
            if (!offers(m, "subtitles", type, id)) continue;
            try {
                JSONArray s = new JSONObject(get(res(o.optString("url"), "/subtitles/" + type + "/" + enc(id) + ".json"))).optJSONArray("subtitles");
                if (s != null) for (int i = 0; i < s.length() && out.size() < 120; i++) {
                    JSONObject x = s.optJSONObject(i);
                    if (x == null || !x.optString("url").matches("(?i)^https?://.*")) continue;
                    x.put("_addon", m.optString("name"));
                    out.add(x);
                }
            } catch (Exception ignored) { }
        }
        return out;
    }

    // ================================================================== your Stremio account's addons

    /** signs in to Stremio (only to read your addon list; the password isn't kept) and installs those addons */
    String importStremio(String email, String password) {
        try {
            JSONObject login = new JSONObject(post("https://api.strem.io/api/login",
                    new JSONObject().put("type", "Login").put("email", email.trim()).put("password", password).put("facebook", false).toString()));
            if (login.optJSONObject("error") != null) return "Stremio said: " + login.optJSONObject("error").optString("message", "sign-in failed");
            JSONObject res = login.optJSONObject("result");
            String key = res == null ? "" : res.optString("authKey");
            if (key.isEmpty()) return "Stremio didn't accept that email and password.";
            JSONObject col = new JSONObject(post("https://api.strem.io/api/addonCollectionGet",
                    new JSONObject().put("type", "AddonCollectionGet").put("authKey", key).put("update", true).toString()));
            JSONObject r = col.optJSONObject("result");
            JSONArray as = r == null ? null : r.optJSONArray("addons");
            if (as == null) return "Couldn't read your addons from Stremio.";
            int n = 0, skipped = 0;
            for (int i = 0; i < as.length(); i++) {
                JSONObject a = as.optJSONObject(i);
                String u = a.optString("transportUrl");
                JSONObject m = a.optJSONObject("manifest");
                // (Stremio's own built-in ones: Cinemeta is always here already; the local/official system ones aren't web addons)
                if (u.isEmpty() || u.contains("v3-cinemeta.strem.io") || u.startsWith("http://127.0.0.1") || (m != null && m.optString("id").startsWith("org.stremio.local"))) { skipped++; continue; }
                if (install(u).isEmpty()) n++;
            }
            // sign out again (the key isn't kept either)
            try { post("https://api.strem.io/api/logout", new JSONObject().put("type", "Logout").put("authKey", key).toString()); } catch (Exception ignored) { }
            Diag.ev("addons: imported " + n + " from Stremio");
            return n == 0 ? "No addons to add (Stremio's built-in ones are skipped)." : "";
        } catch (Exception e) { return "Couldn't reach Stremio: " + e.getMessage(); }
    }

    // ================================================================== the phone page

    String newSetupKey() {
        if (!setupKey.isEmpty() && System.currentTimeMillis() < setupUntil) { setupUntil = System.currentTimeMillis() + 30 * 60000L; return setupKey; }
        String a = "abcdefghjkmnpqrstuvwxyz23456789"; StringBuilder b = new StringBuilder();
        java.security.SecureRandom r = new java.security.SecureRandom();
        for (int i = 0; i < 10; i++) b.append(a.charAt(r.nextInt(a.length())));
        setupKey = b.toString(); setupUntil = System.currentTimeMillis() + 30 * 60000L;
        return setupKey;
    }
    boolean setupOk(String k) { return k != null && !setupKey.isEmpty() && setupKey.equals(k) && System.currentTimeMillis() < setupUntil; }

    // ================================================================== bits

    static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (Exception e) { return s; } }
    private static HttpURLConnection conn(URL u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(12000); c.setReadTimeout(25000); c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "TopShelf/1.0 (Android TV)");
        return c;
    }
    static String get(String url) throws Exception {
        URL at = new URL(url);
        HttpURLConnection c = conn(at);
        int code = c.getResponseCode();
        // (redirects between http and https aren't followed by Android on its own; the same headers go with them)
        for (int i = 0; i < 4 && code >= 300 && code < 400; i++) {
            String loc = c.getHeaderField("Location"); c.disconnect();
            if (loc == null) break;
            at = new URL(at, loc);
            c = conn(at);
            code = c.getResponseCode();
        }
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String s = in == null ? "" : slurp(in);
        c.disconnect();
        if (code >= 400) throw new Exception("HTTP " + code);
        return s;
    }
    private static String post(String url, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(12000); c.setReadTimeout(20000);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = c.getOutputStream()) { o.write(json.getBytes("UTF-8")); }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String s = in == null ? "{}" : slurp(in);
        c.disconnect();
        return s;
    }
    private static String slurp(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[16384]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }
    static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.US); }
}
