package com.topshelf.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Trakt: what you watch here is scrobbled (start / pause / stop with how far in — Trakt marks it watched past
 * 80%), and what you've watched elsewhere comes back: part-watched movies and episodes join Continue watching
 * (carrying on at the same point), and watched episodes get their ticks. Signed in with Trakt's TV sign-in
 * (a code at trakt.tv/activate) using a Trakt app of your own (made on the phone page).
 */
final class Trakt {
    private static final String P = "tk.", API = "https://api.trakt.tv";
    private final MainActivity act;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    volatile String setupKey = ""; volatile long setupUntil;
    volatile String userCode = "", verifyUrl = "", setupState = "";
    private volatile int pollGen;
    private long syncedAt;

    Trakt(MainActivity a) {
        act = a;
        prefs = a.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE);
    }

    boolean connected() { return !prefs.getString(P + "refresh", "").isEmpty(); }
    /** Trakt's public data (ratings, related titles, comments): needs only the app key, not a sign-in. null if there's no key or it failed */
    /** the Trakt app ID is set up (public lists can be read, signed in or not) */
    boolean hasApp() { return !prefs.getString(P + "id", "").isEmpty(); }
    String pub(String path) {
        if (prefs.getString(P + "id", "").isEmpty()) return null;
        try {
            HttpURLConnection c = open(API + path, "GET", false);
            int code = c.getResponseCode();
            String s = code < 400 ? slurp(c.getInputStream()) : null;
            c.disconnect();
            return s;
        } catch (Exception e) { return null; }
    }
    /** marks a movie watched / not watched on Trakt too (when signed in) */
    void history(final boolean add, final String imdb) {
        if (!connected() || imdb == null || imdb.isEmpty()) return;
        new Thread(new Runnable() { @Override public void run() {
            try { post(API + "/sync/history" + (add ? "" : "/remove"), "{\"movies\":[{\"ids\":{\"imdb\":\"" + imdb + "\"}}]}", true); } catch (Exception ignored) { }
        } }, "trakt-history").start();
    }
    /** marks a show's episodes (or the whole show, when eps is null) watched / not watched on Trakt too */
    void historyShow(final boolean add, final String imdb, final java.util.List<int[]> eps) {
        if (!connected() || imdb == null || !imdb.startsWith("tt")) return;
        new Thread(new Runnable() { @Override public void run() {
            try {
                JSONObject show = new JSONObject().put("ids", new JSONObject().put("imdb", imdb));
                if (eps != null) {
                    java.util.Map<Integer, JSONArray> by = new java.util.TreeMap<>();
                    for (int[] se : eps) { JSONArray a = by.get(se[0]); if (a == null) { a = new JSONArray(); by.put(se[0], a); } a.put(new JSONObject().put("number", se[1])); }
                    JSONArray ss = new JSONArray();
                    for (java.util.Map.Entry<Integer, JSONArray> en : by.entrySet()) ss.put(new JSONObject().put("number", en.getKey()).put("episodes", en.getValue()));
                    show.put("seasons", ss);
                }
                post(API + "/sync/history" + (add ? "" : "/remove"), new JSONObject().put("shows", new JSONArray().put(show)).toString(), true);
            } catch (Exception ignored) { }
        } }, "trakt-history").start();
    }
    JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            o.put("connected", connected()).put("user", prefs.getString(P + "user", "")).put("hasApp", !prefs.getString(P + "id", "").isEmpty())
             .put("state", setupState).put("code", userCode).put("url", verifyUrl).put("err", prefs.getString(P + "err", ""))
             .put("synced", prefs.getLong(P + "synced", 0)).put("pkce", pkce()).put("authUrl", authUrl);
        } catch (Exception ignored) { }
        return o;
    }
    void disconnect() {
        final String tok = prefs.getString(P + "access", "");
        final String id = prefs.getString(P + "id", ""), sec = prefs.getString(P + "secret", "");
        SharedPreferences.Editor e = prefs.edit();
        for (String k : new String[] { "access", "refresh", "exp", "user", "err", "synced" }) e.remove(P + k);
        e.apply();
        if (!tok.isEmpty()) io.execute(new Runnable() { @Override public void run() {
            try { JSONObject b = new JSONObject().put("token", tok).put("client_id", id); if (!sec.isEmpty()) b.put("client_secret", sec); post(API + "/oauth/revoke", b.toString(), false); } catch (Exception ignored) { }
        } });
        Diag.ev("trakt: disconnected");
    }

    // ================================================================== setup (the phone page)

    String newSetupKey() {
        if (!setupKey.isEmpty() && System.currentTimeMillis() < setupUntil) { setupUntil = System.currentTimeMillis() + 30 * 60000L; return setupKey; }
        String a = "abcdefghjkmnpqrstuvwxyz23456789"; StringBuilder b = new StringBuilder();
        java.security.SecureRandom r = new java.security.SecureRandom();
        for (int i = 0; i < 10; i++) b.append(a.charAt(r.nextInt(a.length())));
        setupKey = b.toString(); setupUntil = System.currentTimeMillis() + 30 * 60000L;
        setupState = connected() ? "Connected" : "Waiting for your phone";
        return setupKey;
    }
    String setupUrl() {
        if (act.link == null || act.link.port() == 0 || LinkServer.localIp().isEmpty()) return "";
        Diag.ev("trakt: a setup code was made (port " + act.link.port() + ")");
        return "http://" + LinkServer.localIp() + ":" + act.link.port() + "/trakt?k=" + newSetupKey();
    }
    boolean setupOk(String k) { return k != null && !setupKey.isEmpty() && setupKey.equals(k) && System.currentTimeMillis() < setupUntil; }

    String saveApp(String id, String secret) {
        id = id == null ? "" : id.trim(); secret = secret == null ? "" : secret.trim();
        if (id.length() < 20) return "Copy the Client ID from your Trakt app's page (it's a long code).";
        if (!secret.isEmpty() && secret.length() < 20) return "That Client Secret looks too short — copy it again, or leave it empty if Trakt says it wasn't issued.";
        prefs.edit().putString(P + "id", id).putString(P + "secret", secret).apply();
        return startSignIn();
    }
    /* Newer Trakt apps have no Client Secret: they sign in with PKCE. You allow it on Trakt (on the phone), Trakt shows a
       code, and you paste that code back into the phone page; the TV turns it into a sign-in. */
    boolean pkce() { return !prefs.getString(P + "id", "").isEmpty() && prefs.getString(P + "secret", "").isEmpty(); }
    private String verifier = "", authUrl = "";
    private String startPkce() {
        java.security.SecureRandom r = new java.security.SecureRandom();
        byte[] v = new byte[48]; r.nextBytes(v);
        verifier = android.util.Base64.encodeToString(v, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("US-ASCII"));
            String ch = android.util.Base64.encodeToString(h, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
            authUrl = "https://trakt.tv/oauth/authorize?response_type=code&client_id=" + android.net.Uri.encode(prefs.getString(P + "id", ""))
                    + "&redirect_uri=" + android.net.Uri.encode("urn:ietf:wg:oauth:2.0:oob") + "&code_challenge=" + ch + "&code_challenge_method=S256";
        } catch (Exception e) { return "Couldn't prepare the sign-in: " + e.getMessage(); }
        userCode = ""; verifyUrl = "";
        setupState = "Open Trakt, allow TopShelf, then paste the code Trakt shows";
        // a second way, if Trakt allows it for this app: the TV-style code at trakt.tv/activate
        new Thread(new Runnable() { @Override public void run() {
            try {
                JSONObject o = new JSONObject(post(API + "/oauth/device/code", new JSONObject().put("client_id", prefs.getString(P + "id", "")).toString(), false));
                if (o.optString("user_code").isEmpty()) return;
                userCode = o.optString("user_code"); verifyUrl = o.optString("verification_url", "https://trakt.tv/activate");
                Diag.ev("trakt: Trakt gave a code for " + verifyUrl.replace("https://", "") + " (" + userCode.length() + " characters, good for " + o.optLong("expires_in", 0) + " s)");
                final String dc = o.optString("device_code");
                final long interval = Math.max(5, o.optLong("interval", 5)) * 1000L, until = System.currentTimeMillis() + o.optLong("expires_in", 600) * 1000L;
                final int gen = ++pollGen;
                refreshUi();
                poll(gen, dc, interval, until);
            } catch (Exception e) { Diag.ev("trakt: no TV code for this app (" + e.getMessage() + ")"); }
        } }, "trakt-device").start();
        refreshUi();
        return "";
    }
    /** the code Trakt showed after you allowed it */
    String finishPkce(String code) {
        code = code == null ? "" : code.trim();
        if (code.isEmpty()) return "Paste the code Trakt showed you.";
        if (verifier.isEmpty()) return "Start again: tap Open Trakt first.";
        try {
            HttpURLConnection c = open(API + "/oauth/token", "POST", false);
            c.setDoOutput(true);
            try (OutputStream o = c.getOutputStream()) {
                o.write(new JSONObject().put("code", code).put("client_id", prefs.getString(P + "id", "")).put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob")
                        .put("grant_type", "authorization_code").put("code_verifier", verifier).toString().getBytes("UTF-8"));
            }
            int rc = c.getResponseCode();
            String body = rc < 400 ? slurp(c.getInputStream()) : (c.getErrorStream() == null ? "" : slurp(c.getErrorStream()));
            c.disconnect();
            if (rc != 200) { Diag.ev("trakt: code refused " + rc + " " + (body.length() > 120 ? body.substring(0, 120) : body)); return rc == 400 || rc == 401 ? "Trakt didn't accept that code — tap Open Trakt again and paste the new code." : "Trakt said " + rc + " — try again."; }
            saveTokens(new JSONObject(body));
            verifier = ""; authUrl = "";
            try { JSONObject me = new JSONObject(get(API + "/users/me")); prefs.edit().putString(P + "user", me.optString("username")).apply(); } catch (Exception ignored) { }
            setupState = "Connected" + (prefs.getString(P + "user", "").isEmpty() ? "" : " · " + prefs.getString(P + "user", ""));
            Diag.ev("trakt: connected (PKCE)");
            refreshUi();
            sync(true);
            return "";
        } catch (Exception e) { return "Couldn't reach Trakt: " + e.getMessage(); }
    }
    String startSignIn() {
        if (pkce()) return startPkce();
        try {
            JSONObject o = new JSONObject(post(API + "/oauth/device/code", new JSONObject().put("client_id", prefs.getString(P + "id", "")).toString(), false));
            if (o.optString("user_code").isEmpty()) return "Trakt didn't accept that Client ID.";
            userCode = o.optString("user_code"); verifyUrl = o.optString("verification_url", "https://trakt.tv/activate");
                Diag.ev("trakt: Trakt gave a code for " + verifyUrl.replace("https://", "") + " (" + userCode.length() + " characters, good for " + o.optLong("expires_in", 0) + " s)");
            setupState = "Enter the code " + userCode + " at " + verifyUrl.replace("https://", "");
            final String dc = o.optString("device_code");
            final long interval = Math.max(5, o.optLong("interval", 5)) * 1000L, until = System.currentTimeMillis() + o.optLong("expires_in", 600) * 1000L;
            final int gen = ++pollGen;
            new Thread(new Runnable() { @Override public void run() { poll(gen, dc, interval, until); } }, "trakt-signin").start();
            refreshUi();
            return "";
        } catch (Exception e) { return "Couldn't reach Trakt: " + e.getMessage(); }
    }
    private void poll(int gen, String dc, long interval, long until) {
        long wait = interval;
        while (gen == pollGen && System.currentTimeMillis() < until) {
            try { Thread.sleep(wait); } catch (InterruptedException e) { return; }
            try {
                HttpURLConnection c = open(API + "/oauth/device/token", "POST", false);
                c.setDoOutput(true);
                try (OutputStream o = c.getOutputStream()) { JSONObject b = new JSONObject().put("code", dc).put("client_id", prefs.getString(P + "id", "")); if (!prefs.getString(P + "secret", "").isEmpty()) b.put("client_secret", prefs.getString(P + "secret", "")); o.write(b.toString().getBytes("UTF-8")); }
                int code = c.getResponseCode();
                String body = code < 400 ? slurp(c.getInputStream()) : "";
                c.disconnect();
                if (code == 400) continue;                         // still waiting for you
                if (code == 429) { wait += 2000; continue; }
                if (code != 200 && pkce()) { Diag.ev("trakt: TV code refused " + code); userCode = ""; refreshUi(); return; }
                if (code != 200) Diag.ev("trakt: waiting for the code ended with " + code);
                if (code != 200) { setupState = code == 418 ? "You chose not to allow it — start again" : code == 410 ? "The code expired — start again" : "Trakt said " + code + " — start again"; userCode = ""; refreshUi(); return; }
                saveTokens(new JSONObject(body));
                userCode = "";
                try { JSONObject me = new JSONObject(get(API + "/users/me")); prefs.edit().putString(P + "user", me.optString("username")).apply(); } catch (Exception ignored) { }
                setupState = "Connected" + (prefs.getString(P + "user", "").isEmpty() ? "" : " · " + prefs.getString(P + "user", ""));
                Diag.ev("trakt: connected");
                refreshUi();
                sync(true);
                return;
            } catch (Exception e) { /* (a blip: keep asking) */ }
        }
        if (gen == pollGen && !connected()) { if (!pkce()) setupState = "The code expired — start again"; userCode = ""; refreshUi(); }
    }
    private void refreshUi() { main.post(new Runnable() { @Override public void run() { NativeHome h = act.nativeHome(); if (h != null) h.setupRefresh(); } }); }

    private void saveTokens(JSONObject t) {
        prefs.edit().putString(P + "access", t.optString("access_token")).putString(P + "refresh", t.optString("refresh_token"))
                .putLong(P + "exp", System.currentTimeMillis() + Math.max(600, t.optLong("expires_in", 86400) - 600) * 1000L).remove(P + "err")
                /* written before going on (it was left to be written a moment later). Trakt gives a new renewal key each
                   day and the old one stops working at once: if TopShelf was closed in that moment (an update installing,
                   say) the new key was lost and Trakt signed it out. That's the likely cause of "sign-in expired" on 8 Nov,
                   a day of many updates. */
                .commit();
    }
    private synchronized String token() throws Exception {
        if (!connected()) throw new Exception("not connected to Trakt");
        if (System.currentTimeMillis() < prefs.getLong(P + "exp", 0)) return prefs.getString(P + "access", "");
        HttpURLConnection c = open(API + "/oauth/token", "POST", false);
        c.setDoOutput(true);
        try (OutputStream o = c.getOutputStream()) {
            JSONObject b = new JSONObject().put("refresh_token", prefs.getString(P + "refresh", "")).put("client_id", prefs.getString(P + "id", ""))
                    .put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob").put("grant_type", "refresh_token");
            if (!prefs.getString(P + "secret", "").isEmpty()) b.put("client_secret", prefs.getString(P + "secret", ""));
            o.write(b.toString().getBytes("UTF-8"));
        }
        int code = c.getResponseCode();
        String body = code < 400 ? slurp(c.getInputStream()) : "";
        c.disconnect();
        if (code == 400 || code == 401) { prefs.edit().remove(P + "refresh").putString(P + "err", "Trakt signed TopShelf out — set it up again").apply(); throw new Exception("Trakt sign-in expired"); }
        if (code != 200) throw new Exception("Trakt " + code);
        saveTokens(new JSONObject(body));
        return prefs.getString(P + "access", "");
    }

    // ================================================================== scrobbling

    /** start / pause / stop for a movie (video = its id) or an episode (video = show:season:episode) */
    void scrobble(final String what, final String type, final String titleId, final String video, final double pct) {
        if (!connected() || !titleId.startsWith("tt")) return;
        io.execute(new Runnable() { @Override public void run() {
            try {
                JSONObject b = new JSONObject().put("progress", Math.round(pct * 100) / 100.0);
                if ("series".equals(type)) {
                    String[] p = video.split(":");
                    if (p.length < 3) return;
                    b.put("show", new JSONObject().put("ids", new JSONObject().put("imdb", titleId)));
                    b.put("episode", new JSONObject().put("season", Integer.parseInt(p[p.length - 2])).put("number", Integer.parseInt(p[p.length - 1])));
                } else b.put("movie", new JSONObject().put("ids", new JSONObject().put("imdb", titleId)));
                post(API + "/scrobble/" + what, b.toString(), true);
                Diag.ev("trakt: " + what + " " + video + " at " + Math.round(pct) + "%");
            } catch (Exception e) { Diag.ev("trakt: scrobble " + what + " failed " + e.getMessage()); }
        } });
    }

    // ================================================================== what you watched elsewhere

    /** Trakt's paused movies/episodes into Continue watching, and watched episodes as ticks (every 15 minutes at most unless forced) */
    private final Map<String, Long> nextFor = new java.util.concurrent.ConcurrentHashMap<>();     // show → when you last watched it, as of asking for its next episode
    void sync(boolean force) {
        if (!connected()) return;
        if (!force && System.currentTimeMillis() - syncedAt < 15 * 60000L) return;
        syncedAt = System.currentTimeMillis();
        io.execute(new Runnable() { @Override public void run() {
            try {
                // part-watched, newest first
                JSONArray pb = new JSONArray(get(API + "/sync/playback?limit=30"));
                SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US); iso.setTimeZone(TimeZone.getTimeZone("UTC"));
                for (int i = 0; i < pb.length(); i++) {
                    JSONObject x = pb.optJSONObject(i);
                    long at = 0; try { at = iso.parse(x.optString("paused_at")).getTime(); } catch (Exception ignored) { }
                    double pct = x.optDouble("progress", 0);
                    if ("movie".equals(x.optString("type"))) {
                        JSONObject mv = x.optJSONObject("movie");
                        String imdb = mv == null || mv.optJSONObject("ids") == null ? "" : mv.optJSONObject("ids").optString("imdb");
                        if (imdb.isEmpty()) continue;
                        Progress.putRemote(act, imdb, pct, at, "", 0, 0, title("movie", imdb, mv.optString("title")), "");
                    } else if ("episode".equals(x.optString("type"))) {
                        JSONObject ep = x.optJSONObject("episode"), sh = x.optJSONObject("show");
                        String imdb = sh == null || sh.optJSONObject("ids") == null ? "" : sh.optJSONObject("ids").optString("imdb");
                        if (imdb.isEmpty() || ep == null) continue;
                        int s = ep.optInt("season"), n = ep.optInt("number");
                        Progress.putRemote(act, imdb + ":" + s + ":" + n, pct, at, imdb, s, n, title("series", imdb, sh.optString("title")),
                                "S" + s + " · E" + n + "  " + ep.optString("title"));
                    }
                }
                // watched episodes (ticks in the episode lists)
                JSONArray ws = new JSONArray(get(API + "/sync/watched/shows"));
                Set<String> ids = new HashSet<>();
                List<JSONObject> lately = new ArrayList<>();
                long twoMonths = System.currentTimeMillis() - 60L * 86400000L;
                for (int i = 0; i < ws.length(); i++) {
                    JSONObject x = ws.optJSONObject(i);
                    JSONObject sh = x.optJSONObject("show");
                    String imdb = sh == null || sh.optJSONObject("ids") == null ? "" : sh.optJSONObject("ids").optString("imdb");
                    // (shows you've watched lately: their next episodes, below)
                    try { long lw = iso.parse(x.optString("last_watched_at")).getTime(); if (!imdb.isEmpty() && lw > twoMonths) lately.add(new JSONObject().put("imdb", imdb).put("at", lw).put("name", sh.optString("title"))); } catch (Exception ignored) { }
                    JSONArray ss = x.optJSONArray("seasons");
                    if (imdb.isEmpty() || ss == null) continue;
                    for (int k = 0; k < ss.length(); k++) {
                        JSONObject se = ss.optJSONObject(k); JSONArray es = se == null ? null : se.optJSONArray("episodes");
                        if (es != null) for (int j = 0; j < es.length(); j++) { JSONObject e = es.optJSONObject(j); if (e != null) ids.add(imdb + ":" + se.optInt("number") + ":" + e.optInt("number")); }
                    }
                }
                // watched movies too (a movie seen on another device shows as watched here)
                try {
                    JSONArray wm = new JSONArray(get(API + "/sync/watched/movies"));
                    for (int i = 0; i < wm.length(); i++) {
                        JSONObject mv = wm.optJSONObject(i) == null ? null : wm.optJSONObject(i).optJSONObject("movie");
                        String imdb = mv == null || mv.optJSONObject("ids") == null ? "" : mv.optJSONObject("ids").optString("imdb");
                        if (!imdb.isEmpty()) ids.add(imdb);
                    }
                } catch (Exception e) { Diag.ev("trakt: watched movies failed " + e.getMessage()); }
                Progress.markWatched(act, ids);
                /* The next episode of each show you've been watching, wherever you watched it (Continue watching
                   only had what was paused part-way, and next episodes of shows finished on this TV: a show
                   you'd been watching in another app never appeared). Trakt is asked once per show each time
                   you watch more of it; the fifteen most recent. */
                Collections.sort(lately, new Comparator<JSONObject>() { @Override public int compare(JSONObject a, JSONObject b) { return Long.compare(b.optLong("at"), a.optLong("at")); } });
                int nexts = 0, asked = 0;
                for (int i = 0; i < lately.size() && i < 15; i++) {
                    JSONObject sh = lately.get(i);
                    String imdb = sh.optString("imdb"); long at = sh.optLong("at");
                    Long had = nextFor.get(imdb);
                    if (had != null && had == at) continue;
                    try {
                        asked++;
                        JSONObject pr = new JSONObject(get(API + "/shows/" + imdb + "/progress/watched?hidden=false&specials=false&count_specials=false"));
                        JSONObject nx = pr.optJSONObject("next_episode"), last = pr.optJSONObject("last_episode");
                        nextFor.put(imdb, at);
                        if (nx == null || last == null) continue;                 // (caught up, or nothing watched)
                        int ls = last.optInt("season"), le = last.optInt("number"), ns = nx.optInt("season"), ne = nx.optInt("number");
                        Progress.putRemoteNext(act, imdb + ":" + ls + ":" + le, at, imdb, ls, le, title("series", imdb, sh.optString("name")),
                                "S" + ls + " · E" + le + "  " + last.optString("title"), imdb + ":" + ns + ":" + ne, "S" + ns + " · E" + ne + "  " + nx.optString("title"));
                        nexts++;
                    } catch (Exception e) { Diag.ev("trakt: a show's next episode failed " + e.getMessage()); }
                }
                prefs.edit().putLong(P + "synced", System.currentTimeMillis()).remove(P + "err").apply();
                Diag.ev("trakt: synced (" + pb.length() + " in progress, " + ids.size() + " episodes watched, " + Math.min(15, lately.size()) + " shows watched lately: " + asked + " asked, " + nexts + " with a next episode)");
            } catch (Exception e) {
                prefs.edit().putString(P + "err", String.valueOf(e.getMessage())).apply();
                Diag.ev("trakt: sync failed " + e.getMessage());
            }
        } });
    }
    /** a title's poster and name for Continue watching (from Cinemeta, remembered) */
    private final java.util.Map<String, JSONObject> titles = new java.util.HashMap<>();
    private JSONObject title(String type, String imdb, String name) {
        JSONObject t = titles.get(imdb);
        if (t != null) return t;
        try {
            t = new JSONObject().put("id", imdb).put("type", type).put("name", name);
            JSONObject m = new JSONObject(Addons.get("https://v3-cinemeta.strem.io/meta/" + type + "/" + imdb + ".json")).optJSONObject("meta");
            if (m != null) {
                JSONObject s = NativeVod.slim(m, type);
                s.put("id", imdb); s.put("type", type);
                t = s;
            }
        } catch (Exception ignored) { }
        titles.put(imdb, t);
        return t;
    }

    // ================================================================== bits

    private HttpURLConnection open(String url, String method, boolean auth) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(12000); c.setReadTimeout(20000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("trakt-api-version", "2");
        c.setRequestProperty("trakt-api-key", prefs.getString(P + "id", ""));
        c.setRequestProperty("User-Agent", "TopShelf/1.0");
        if (auth) c.setRequestProperty("Authorization", "Bearer " + token());
        return c;
    }
    private String get(String url) throws Exception {
        HttpURLConnection c = open(url, "GET", true);
        int code = c.getResponseCode();
        String s = code < 400 ? slurp(c.getInputStream()) : "";
        c.disconnect();
        if (code == 401) { prefs.edit().putLong(P + "exp", 0).apply(); throw new Exception("Trakt 401"); }
        if (code >= 400) throw new Exception("Trakt " + code);
        return s;
    }
    private String post(String url, String json, boolean auth) throws Exception {
        HttpURLConnection c = open(url, "POST", auth);
        c.setDoOutput(true);
        try (OutputStream o = c.getOutputStream()) { o.write(json.getBytes("UTF-8")); }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String s = in == null ? "" : slurp(in);
        c.disconnect();
        if (code >= 400 && code != 409) throw new Exception("Trakt " + code);       // (409: already scrobbled — fine)
        return s.isEmpty() ? "{}" : s;
    }
    private static String slurp(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[16384]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }
}
