package com.topshelf.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Yahoo Fantasy Football alerts.
 *
 * Your Yahoo teams' rosters come from the Yahoo Fantasy API (signed in once, from the phone: see the
 * setup page the TV serves). During NFL games the plays come from ESPN's public game data, which is
 * near-instant: when one of your players scores or makes a big play, a banner appears on the TV. Also:
 * injury news for your players (from Yahoo) and each game's final (with your players' lines), and your
 * matchup's result when the week is over.
 *
 * Runs only while TopShelf is on screen; alerts are shown only on TopShelf's own tabs (and queued for a
 * little while if another app is in front).
 */
final class Fantasy {
    interface Alerts { void fantasyAlert(String kind, String title, String detail, String espnGame); }

    private static final String P = "fy.";
    static final String REDIRECT = "https://localhost:8080";
    private static final String YAPI = "https://fantasysports.yahooapis.com/fantasy/v2/";
    private static final String ESPN = "https://site.api.espn.com/apis/site/v2/sports/football/nfl/";

    private final MainActivity act;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean running;

    // the setup page (served by the TV to your phone)
    volatile String setupKey = ""; volatile long setupUntil;
    volatile String setupState = "";

    Fantasy(MainActivity a) {
        act = a;
        prefs = a.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE);
    }

    // ================================================================== settings

    /** signed in AND your team's been read (a sign-in Yahoo won't share fantasy data with doesn't count) */
    boolean connected() { return !prefs.getString(P + "refresh", "").isEmpty() && teams().length() > 0; }
    boolean on(String kind) { return prefs.getBoolean(P + "on." + kind, true); }
    /** alerts are running: with Yahoo, or with the players you picked yourself */
    boolean active() { return connected() || mine().length() > 0; }
    private boolean manual() { return !connected() && mine().length() > 0; }
    void toggle(String kind) { prefs.edit().putBoolean(P + "on." + kind, !on(kind)).apply(); }

    JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            o.put("connected", connected());
            o.put("players", mine().length()); o.put("manual", manual());
            JSONArray teams = teams(); List<String> n = new ArrayList<>();
            for (int i = 0; i < teams.length(); i++) n.add(teams.optJSONObject(i).optString("name"));
            o.put("teams", android.text.TextUtils.join(", ", n));
            o.put("error", prefs.getString(P + "err", ""));
            JSONObject on = new JSONObject();
            for (String k : new String[] { "scoring", "big", "injury", "final" }) on.put(k, on(k));
            o.put("on", on);
        } catch (Exception ignored) { }
        return o;
    }

    void disconnect() {
        SharedPreferences.Editor e = prefs.edit();
        for (String k : new String[] { "id", "secret", "refresh", "access", "exp", "teams", "roster", "err", "seen", "injury" }) e.remove(P + k);
        e.apply();
        Diag.ev("fantasy: disconnected");
    }

    private JSONArray teams() { try { return new JSONArray(prefs.getString(P + "teams", "[]")); } catch (Exception e) { return new JSONArray(); } }

    // ================================================================== setup (from the phone)

    String newSetupKey() {
        // (the same link while it's still good: the TV's QR code and the phone app's button agree)
        if (!setupKey.isEmpty() && System.currentTimeMillis() < setupUntil) { setupUntil = System.currentTimeMillis() + 30 * 60000L; return setupKey; }
        String a = "abcdefghjkmnpqrstuvwxyz23456789"; StringBuilder b = new StringBuilder();
        java.security.SecureRandom r = new java.security.SecureRandom();
        for (int i = 0; i < 10; i++) b.append(a.charAt(r.nextInt(a.length())));
        setupKey = b.toString(); setupUntil = System.currentTimeMillis() + 30 * 60000L;
        setupState = connected() ? "Connected" : "Waiting for your phone";
        return setupKey;
    }
    /** the setup page's address on the home network ("" if the TV isn't on one) */
    String setupUrl() {
        if (act.link == null || act.link.port() == 0 || LinkServer.localIp().isEmpty()) return "";
        return "http://" + LinkServer.localIp() + ":" + act.link.port() + "/fantasy?k=" + newSetupKey();
    }
    String playersUrl() {
        if (act.link == null || act.link.port() == 0 || LinkServer.localIp().isEmpty()) return "";
        return "http://" + LinkServer.localIp() + ":" + act.link.port() + "/fantasy/players?k=" + newSetupKey();
    }
    boolean setupOk(String k) { return k != null && !setupKey.isEmpty() && setupKey.equals(k) && System.currentTimeMillis() < setupUntil; }

    /** step 1: the Yahoo app's Client ID and Client Secret */
    String saveApp(String id, String secret) {
        id = id == null ? "" : id.trim(); secret = secret == null ? "" : secret.trim();
        if (id.length() < 10 || secret.length() < 10) return "That doesn't look like a Client ID and Client Secret — copy them again from your Yahoo app's page.";
        prefs.edit().putString(P + "id", id).putString(P + "secret", secret).apply();
        setupState = "App saved — now sign in to Yahoo on your phone";
        return "";
    }
    String authUrl() {
        return "https://api.login.yahoo.com/oauth2/request_auth?client_id=" + enc(prefs.getString(P + "id", "")) + "&redirect_uri=" + enc(REDIRECT) + "&response_type=code&language=en-us";
    }

    /** step 2: the code Yahoo gives after you sign in (or the whole address it sent you to) */
    String finish(String codeOrUrl) {
        String code = codeOrUrl == null ? "" : codeOrUrl.trim();
        int i = code.indexOf("code=");
        if (i >= 0) { code = code.substring(i + 5); int j = code.indexOf('&'); if (j >= 0) code = code.substring(0, j); }
        try { code = URLDecoder.decode(code, "UTF-8"); } catch (Exception ignored) { }
        if (code.length() < 4) return "Paste the code (or the whole address) Yahoo showed after you allowed access.";
        try {
            JSONObject t = tokenRequest("grant_type=authorization_code&redirect_uri=" + enc(REDIRECT) + "&code=" + enc(code));
            saveTokens(t);
            List<String[]> ts = loadTeams();
            if (ts.isEmpty()) return "Signed in, but no Yahoo Fantasy Football team was found for this season on that account.";
            setupState = "Connected";
            prefs.edit().remove(P + "err").apply();
            Diag.ev("fantasy: connected, " + ts.size() + " team(s)");
            main.post(new Runnable() { @Override public void run() { kick(); } });
            return "";
        } catch (YahooError e) {
            if (e.code == 403 || e.code == 401) {
                prefs.edit().remove(P + "refresh").remove(P + "access").remove(P + "teams")
                        .putString(P + "err", "Yahoo won't share fantasy data with your app (403) — Yahoo now approves Fantasy API access by application").apply();
                setupState = "Yahoo refused (403)";
            }
            if (e.code == 403 || e.code == 401)
                return e.getMessage().contains("not authorized")
                    ? "Yahoo signed you in, but refused your app access to Fantasy data (" + e.getMessage() + "). Since July 2026 Yahoo only allows apps it has approved: apply at sports.yahoo.com/developer/access. Once Yahoo approves you, set this up again."
                    : "Yahoo signed you in, but won't let your Yahoo app read Fantasy (" + e.getMessage() + "). Check that your app at developer.yahoo.com/apps has Fantasy Sports (Read), then tap Sign in to Yahoo again for a new code.";
            return "Yahoo said no: " + e.getMessage();
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage());
            if (m.toLowerCase(Locale.ROOT).contains("invalid_grant") || m.toLowerCase(Locale.ROOT).contains("expired"))
                return "That code was already used or has expired (each works once, for a few minutes). Tap Sign in to Yahoo again and paste the new one.";
            return "Yahoo said no: " + m;
        }
    }

    // ================================================================== Yahoo

    private JSONObject tokenRequest(String body) throws Exception {
        String id = prefs.getString(P + "id", ""), secret = prefs.getString(P + "secret", "");
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.login.yahoo.com/oauth2/get_token").openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(15000); c.setReadTimeout(20000);
        c.setRequestProperty("Authorization", "Basic " + Base64.encodeToString((id + ":" + secret).getBytes("UTF-8"), Base64.NO_WRAP));
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes("UTF-8")); }
        int code = c.getResponseCode();
        String txt = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        c.disconnect();
        if (code >= 400) {
            String msg = txt;
            try { JSONObject e = new JSONObject(txt); msg = e.optString("error_description", e.optString("error", txt)); } catch (Exception ignored) { }
            throw new Exception(msg.length() > 160 ? msg.substring(0, 160) : msg);
        }
        return new JSONObject(txt);
    }
    private void saveTokens(JSONObject t) {
        SharedPreferences.Editor e = prefs.edit();
        e.putString(P + "access", t.optString("access_token"));
        if (!t.optString("refresh_token").isEmpty()) e.putString(P + "refresh", t.optString("refresh_token"));
        e.putLong(P + "exp", System.currentTimeMillis() + Math.max(300, t.optLong("expires_in", 3600) - 120) * 1000L);
        e.apply();
    }
    private String access() throws Exception {
        if (System.currentTimeMillis() < prefs.getLong(P + "exp", 0) && !prefs.getString(P + "access", "").isEmpty()) return prefs.getString(P + "access", "");
        saveTokens(tokenRequest("grant_type=refresh_token&redirect_uri=" + enc(REDIRECT) + "&refresh_token=" + enc(prefs.getString(P + "refresh", ""))));
        return prefs.getString(P + "access", "");
    }
    /** a Yahoo Fantasy API call, as an XML document (its JSON form is awkward to read) */
    private Document yahoo(String path) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection c = (HttpURLConnection) new URL(YAPI + path).openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(20000);
            c.setRequestProperty("Authorization", "Bearer " + access());
            int code = c.getResponseCode();
            if (code == 401 && attempt == 0) { prefs.edit().putLong(P + "exp", 0).apply(); c.disconnect(); continue; }
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (code >= 400) {
                String t = read(in); c.disconnect();
                // Yahoo answers in XML: the useful part is its <description>
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("<description>([\\s\\S]*?)</description>").matcher(t);
                String why = m.find() ? m.group(1).trim() : t.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                Diag.ev("fantasy: Yahoo " + code + " for " + path + ": " + why);
                throw new YahooError(code, why);
            }
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            Document d = f.newDocumentBuilder().parse(in);
            c.disconnect();
            return d;
        }
        throw new Exception("Yahoo sign-in expired");
    }
    static final class YahooError extends Exception {
        final int code;
        YahooError(int code, String why) { super("Yahoo " + code + (why.isEmpty() ? "" : ": " + (why.length() > 200 ? why.substring(0, 200) : why))); this.code = code; }
    }
    private static String tx(Element e, String tag) {
        if (e == null) return "";
        NodeList l = e.getElementsByTagName(tag);
        return l.getLength() == 0 ? "" : l.item(0).getTextContent().trim();
    }
    private static Element child(Element e, String tag) {
        if (e == null) return null;
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element && ((Element) n).getTagName().equals(tag)) return (Element) n;
        return null;
    }

    /** your NFL teams this season: [key, name] */
    private List<String[]> loadTeams() throws Exception {
        Document d = yahoo("users;use_login=1/games;game_keys=nfl/teams");
        NodeList ts = d.getElementsByTagName("team");
        List<String[]> out = new ArrayList<>();
        JSONArray a = new JSONArray();
        for (int i = 0; i < ts.getLength(); i++) {
            Element t = (Element) ts.item(i);
            String key = tx(t, "team_key");
            if (key.isEmpty()) continue;
            Element nm = child(t, "name"); String name = nm == null ? "Your team" : nm.getTextContent().trim();
            out.add(new String[] { key, name });
            a.put(new JSONObject().put("key", key).put("name", name));
        }
        prefs.edit().putString(P + "teams", a.toString()).apply();
        return out;
    }

    // ================================================================== your players

    static final class Pl {
        String key, name, first, last, abbr, pos, slot, status = "", statusFull = "", note = "", team, teamKey;
        boolean starter() { return !slot.equals("BN") && !slot.startsWith("IR"); }
        boolean def() { return pos.equals("DEF"); }
        String espnAbbr() { return espn(abbr); }
    }
    private final List<Pl> roster = new ArrayList<>();
    private long rosterAt; private int week;

    private void loadRosters() throws Exception {
        if (manual()) { loadMine(); return; }
        JSONArray teams = teams();
        if (teams.length() == 0) loadTeams();
        teams = teams();
        List<Pl> all = new ArrayList<>();
        for (int i = 0; i < teams.length(); i++) {
            JSONObject t = teams.optJSONObject(i);
            Document d = yahoo("team/" + t.optString("key") + "/roster");
            NodeList rs = d.getElementsByTagName("roster");
            Element wk = rs.getLength() > 0 ? child((Element) rs.item(0), "week") : null;
            if (wk != null) try { week = Integer.parseInt(wk.getTextContent().trim()); } catch (Exception ignored) { }
            NodeList ps = d.getElementsByTagName("player");
            for (int k = 0; k < ps.getLength(); k++) {
                Element p = (Element) ps.item(k);
                Pl pl = new Pl();
                pl.key = tx(p, "player_key");
                Element nm = child(p, "name");
                pl.name = tx(nm, "full"); pl.first = tx(nm, "first"); pl.last = tx(nm, "last");
                pl.abbr = tx(p, "editorial_team_abbr").toUpperCase(Locale.US);
                pl.pos = tx(p, "display_position");
                pl.slot = tx(child(p, "selected_position"), "position");
                Element st = child(p, "status"); pl.status = st == null ? "" : st.getTextContent().trim();
                Element sf = child(p, "status_full"); pl.statusFull = sf == null ? "" : sf.getTextContent().trim();
                Element inj = child(p, "injury_note"); pl.note = inj == null ? "" : inj.getTextContent().trim();
                pl.team = t.optString("name"); pl.teamKey = t.optString("key");
                if (!pl.key.isEmpty()) all.add(pl);
            }
        }
        synchronized (roster) { roster.clear(); roster.addAll(all); }
        rosterAt = System.currentTimeMillis();
        injuries(all);
    }

    /* injury news: a change in a player's status since we last looked (the first look only notes starters
       who are already out or doubtful, once) */
    private void injuries(List<Pl> all) {
        JSONObject was; try { was = new JSONObject(prefs.getString(P + "injury", "{}")); } catch (Exception e) { was = new JSONObject(); }
        boolean first = !prefs.contains(P + "injury");
        JSONObject now = new JSONObject();
        for (Pl p : all) {
            try { now.put(p.key, p.status); } catch (Exception ignored) { }
            String before = was.optString(p.key, null);
            if (p.status.equals(before == null ? "" : before)) continue;
            if (first && !(p.starter() && (p.status.equals("O") || p.status.equals("D") || p.status.startsWith("IR")))) continue;
            if (before == null && !first) continue;           // a player just added: no news
            if (!on("injury")) continue;
            String what = p.status.isEmpty() ? "Cleared to play" : statusName(p);
            String detail = (p.note.isEmpty() ? "" : p.note + " · ") + (p.starter() ? "In your starting lineup" : "On your bench") + teamSuffix(p);
            alert("injury", p.name + " · " + what, detail, null, "inj:" + p.key + ":" + p.status + ":" + week);
        }
        prefs.edit().putString(P + "injury", now.toString()).apply();
    }
    private static String statusName(Pl p) {
        if (!p.statusFull.isEmpty()) return p.statusFull;
        switch (p.status) { case "Q": return "Questionable"; case "D": return "Doubtful"; case "O": return "Out"; case "IR": return "Injured Reserve"; case "PUP-R": return "PUP list"; case "SUSP": return "Suspended"; default: return p.status; }
    }
    private String teamSuffix(Pl p) { return teams().length() > 1 ? " (" + p.team + ")" : ""; }

    // ================================================================== your players, without Yahoo

    /* Picked on your phone from the NFL's rosters (ESPN's): [{id, name, first, last, abbr, pos}], a defense as
       {id: "def:BUF", name: "Bills", pos: "DEF", abbr: "BUF"}. Injury news comes from ESPN's injury report. */
    JSONArray mine() { try { return new JSONArray(prefs.getString(P + "mine", "[]")); } catch (Exception e) { return new JSONArray(); } }
    synchronized JSONArray addMine(JSONObject pl) {
        JSONArray a = mine();
        for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i).optString("id").equals(pl.optString("id"))) return a;
        a.put(pl);
        prefs.edit().putString(P + "mine", a.toString()).apply();
        rosterAt = 0; main.post(new Runnable() { @Override public void run() { kick(); } });
        return a;
    }
    synchronized JSONArray benchMine(String id, boolean bench) {
        JSONArray a = mine();
        for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i).optString("id").equals(id)) try { a.optJSONObject(i).put("bench", bench); } catch (Exception ignored) { }
        prefs.edit().putString(P + "mine", a.toString()).apply();
        rosterAt = 0; main.post(new Runnable() { @Override public void run() { kick(); } });
        return a;
    }
    synchronized JSONArray removeMine(String id) {
        JSONArray a = mine(), n = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (!a.optJSONObject(i).optString("id").equals(id)) n.put(a.optJSONObject(i));
        prefs.edit().putString(P + "mine", n.toString()).apply();
        rosterAt = 0;
        return n;
    }
    private void loadMine() {
        JSONArray a = mine();
        Map<String, String[]> inj = espnInjuries();
        List<Pl> all = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            Pl pl = new Pl();
            pl.key = "espn:" + o.optString("id"); pl.name = o.optString("name"); pl.first = o.optString("first"); pl.last = o.optString("last");
            pl.abbr = o.optString("abbr").toUpperCase(Locale.US); pl.pos = o.optString("pos"); pl.slot = o.optBoolean("bench") ? "BN" : ""; pl.team = ""; pl.teamKey = "";
            String[] st = inj == null ? null : inj.get(o.optString("id"));
            if (st != null) { pl.statusFull = st[0]; pl.status = code(st[0]); pl.note = st[1]; }
            all.add(pl);
        }
        synchronized (roster) { roster.clear(); roster.addAll(all); }
        rosterAt = System.currentTimeMillis();
        if (inj != null) injuries(all);               // (no injury report: don't take that as everyone cleared)
    }
    private static String code(String status) {
        String t = status.toLowerCase(Locale.US);
        if (t.startsWith("question")) return "Q"; if (t.startsWith("doubt")) return "D"; if (t.startsWith("out")) return "O";
        if (t.contains("reserve")) return "IR"; if (t.contains("suspen")) return "SUSP"; if (t.contains("pup") || t.contains("physically")) return "PUP-R";
        return status.isEmpty() ? "" : status;
    }
    /** ESPN's league-wide injury report: athlete id → {status, what} (null if it couldn't be read) */
    private Map<String, String[]> espnInjuries() {
        try {
            JSONObject o = new JSONObject(get(ESPN + "injuries"));
            JSONArray teams = o.optJSONArray("injuries");
            if (teams == null) return null;
            Map<String, String[]> out = new HashMap<>();
            for (int i = 0; i < teams.length(); i++) {
                JSONArray l = teams.optJSONObject(i).optJSONArray("injuries");
                if (l == null) continue;
                for (int k = 0; k < l.length(); k++) {
                    JSONObject j = l.optJSONObject(k);
                    JSONObject ath = j.optJSONObject("athlete");
                    String id = ath == null ? "" : ath.optString("id");
                    if (id.isEmpty() && ath != null && ath.optJSONArray("links") != null) {
                        // (some versions give only a link: …/player/_/id/3918298/…)
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/id/(\\d+)").matcher(ath.optJSONArray("links").toString());
                        if (m.find()) id = m.group(1);
                    }
                    if (id.isEmpty()) continue;
                    String st = j.optString("status");
                    if (st.isEmpty() && j.optJSONObject("type") != null) st = j.optJSONObject("type").optString("description");
                    JSONObject det = j.optJSONObject("details");
                    String what = det != null ? det.optString("type") : "";
                    if (what.isEmpty()) what = j.optString("shortComment");
                    if (what.length() > 120) what = what.substring(0, 120) + "…";
                    out.put(id, new String[] { st, what });
                }
            }
            return out;
        } catch (Exception e) { Diag.ev("fantasy: injury report " + e.getMessage()); return null; }
    }

    /** every NFL player (and each team's defense), for picking yours: cached on the TV for a day */
    private JSONArray players;
    synchronized JSONArray players() throws Exception {
        java.io.File f = new java.io.File(act.getFilesDir(), "nfl-players.json");
        if (players != null && f.lastModified() > System.currentTimeMillis() - 86400_000L) return players;
        if (f.exists() && f.lastModified() > System.currentTimeMillis() - 86400_000L) {
            try { players = new JSONArray(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8")); return players; } catch (Exception ignored) { }
        }
        JSONArray out = new JSONArray();
        JSONObject t = new JSONObject(get(ESPN + "teams"));
        JSONArray ts = t.getJSONArray("sports").getJSONObject(0).getJSONArray("leagues").getJSONObject(0).getJSONArray("teams");
        for (int i = 0; i < ts.length(); i++) {
            JSONObject tm = ts.getJSONObject(i).getJSONObject("team");
            String abbr = tm.optString("abbreviation").toUpperCase(Locale.US), tid = tm.optString("id");
            String nick = tm.optString("name", tm.optString("shortDisplayName"));
            out.put(new JSONObject().put("id", "def:" + abbr).put("name", nick).put("first", "").put("last", nick).put("abbr", abbr).put("pos", "DEF")
                    .put("team", tm.optString("displayName")).put("tid", tid));
            try {
                JSONObject r = new JSONObject(get(ESPN + "teams/" + tid + "/roster"));
                JSONArray groups = r.optJSONArray("athletes");
                if (groups == null) continue;
                for (int g = 0; g < groups.length(); g++) {
                    JSONObject gr = groups.optJSONObject(g);
                    JSONArray items = gr.optJSONArray("items");
                    if (items == null) { items = new JSONArray().put(gr); }        // (a flat list of players)
                    for (int k = 0; k < items.length(); k++) {
                        JSONObject a = items.optJSONObject(k);
                        if (a == null || a.optString("id").isEmpty()) continue;
                        JSONObject pos = a.optJSONObject("position");
                        String pa = pos == null ? "" : pos.optString("abbreviation");
                        if (!pa.matches("QB|RB|WR|TE|K|PK|FB")) continue;            // the fantasy positions
                        if (pa.equals("PK")) pa = "K";
                        out.put(new JSONObject().put("id", a.optString("id")).put("name", a.optString("fullName", a.optString("displayName")))
                                .put("first", a.optString("firstName")).put("last", a.optString("lastName")).put("abbr", abbr).put("pos", pa)
                                .put("team", tm.optString("displayName")));
                    }
                }
            } catch (Exception e) { Diag.ev("fantasy: roster " + abbr + " " + e.getMessage()); }
        }
        if (out.length() < 100) throw new Exception("couldn't read the NFL's rosters right now");
        players = out;
        try { java.nio.file.Files.write(f.toPath(), out.toString().getBytes("UTF-8")); } catch (Exception ignored) { }
        return out;
    }
    /** players whose name (or team) matches: best matches first */
    JSONArray search(String q) throws Exception {
        String ql = q == null ? "" : q.trim().toLowerCase(Locale.US);
        JSONArray all = players(), out = new JSONArray();
        if (ql.length() < 2) return out;
        List<JSONObject> a = new ArrayList<>(), b = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject p = all.optJSONObject(i);
            String n = p.optString("name").toLowerCase(Locale.US), last = p.optString("last").toLowerCase(Locale.US), tm = p.optString("team").toLowerCase(Locale.US);
            if (n.startsWith(ql) || last.startsWith(ql)) a.add(p);
            else if (n.contains(ql) || (p.optString("pos").equals("DEF") && (tm.contains(ql) || p.optString("abbr").equalsIgnoreCase(ql)))) b.add(p);
        }
        a.addAll(b);
        for (int i = 0; i < a.size() && i < 25; i++) out.put(a.get(i));
        return out;
    }

    // ================================================================== the games

    private final Map<String, Long> summaryAt = new HashMap<>();
    private final Set<String> baselined = new HashSet<>();
    private final Set<String> finals = new HashSet<>();
    private long boardAt, matchAt;
    private boolean anyLive;

    private void games() throws Exception {
        JSONObject sb = new JSONObject(get(ESPN + "scoreboard"));
        JSONArray evs = sb.optJSONArray("events");
        if (sb.optJSONObject("week") != null) nflWeek = sb.optJSONObject("week").optInt("number", nflWeek);
        if (sb.optJSONObject("season") != null) nflYear = sb.optJSONObject("season").optInt("year", nflYear);
        anyLive = false;
        Set<String> abbrs = new HashSet<>();
        synchronized (roster) { for (Pl p : roster) abbrs.add(p.espnAbbr()); }
        if (evs != null) for (int i = 0; i < evs.length(); i++) {
            JSONObject ev = evs.optJSONObject(i);
            JSONObject comp = ev.optJSONArray("competitions").optJSONObject(0);
            JSONArray cs = comp.optJSONArray("competitors");
            remember(ev, comp, cs);
            boolean mine = false;
            String[] ab = new String[2], sc = new String[2];
            for (int k = 0; k < cs.length(); k++) {
                JSONObject c = cs.optJSONObject(k);
                String a = c.optJSONObject("team").optString("abbreviation").toUpperCase(Locale.US);
                int side = "home".equals(c.optString("homeAway")) ? 1 : 0;
                ab[side] = a; sc[side] = c.optString("score");
                if (abbrs.contains(a)) mine = true;
            }
            if (!mine) continue;
            JSONObject type = comp.optJSONObject("status").optJSONObject("type");
            String state = type.optString("state");
            String id = ev.optString("id");
            String line = ab[0] + " " + sc[0] + "–" + sc[1] + " " + ab[1] + (state.equals("in") ? " · " + type.optString("shortDetail") : "");
            if (state.equals("in")) {
                anyLive = true;
                Long at = summaryAt.get(id);
                if (at == null || System.currentTimeMillis() - at > 20000) { summaryAt.put(id, System.currentTimeMillis()); plays(id, ab, line, false); }
            } else if (state.equals("post")) {
                boolean have; synchronized (board) { have = summaries.containsKey(id); }
                if (!have) try { JSONObject s2 = new JSONObject(get(ESPN + "summary?event=" + id)); synchronized (board) { summaries.put(id, s2); } } catch (Exception ignored) { }
            }
            if (state.equals("post") && !finals.contains(id)) {
                finals.add(id);
                // the final: for a game followed live here, or one that ended in the last few hours
                if (baselined.contains(id) || endedRecently(comp)) plays(id, ab, "Final · " + ab[0] + " " + sc[0] + "–" + sc[1] + " " + ab[1], true);
            }
        }
        boardAt = System.currentTimeMillis();
    }
    private static boolean endedRecently(JSONObject comp) {
        // a game's final is only news for a few hours (the scoreboard keeps last week's games around)
        try { long at = iso(comp.optString("date")); return at > 0 && System.currentTimeMillis() - at < 6 * 3600000L; } catch (Exception e) { return false; }
    }

    /** one game's plays: your players' scoring plays and big plays (and its final) */
    private void plays(String id, String[] ab, String line, boolean fin) throws Exception {
        JSONObject s = new JSONObject(get(ESPN + "summary?event=" + id));
        synchronized (board) { summaries.put(id, s); }
        List<Pl> mine = new ArrayList<>();           // your players in this game
        synchronized (roster) { for (Pl p : roster) if (p.espnAbbr().equals(ab[0]) || p.espnAbbr().equals(ab[1])) mine.add(p); }
        boolean first = baselined.add(id);
        long now = System.currentTimeMillis();
        // scoring plays
        JSONArray sp = s.optJSONArray("scoringPlays");
        Set<String> scoredIds = new HashSet<>();
        if (sp != null) for (int i = 0; i < sp.length(); i++) {
            JSONObject p = sp.optJSONObject(i);
            String pid = p.optString("id"); scoredIds.add(pid);
            String text = p.optString("text");
            String team = p.optJSONObject("team") == null ? "" : p.optJSONObject("team").optString("abbreviation").toUpperCase(Locale.US);
            String type = p.optJSONObject("type") == null ? "" : p.optJSONObject("type").optString("text");
            List<Pl> who = involved(mine, text, team, type, true);
            if (who.isEmpty() || !on("scoring")) continue;
            String key = "sc:" + pid;
            if (first && !fresh(p, now)) { markSeen(key); continue; }
            String title = names(who) + " · " + scoreWord(type);
            alert("score", title, clean(text) + " · " + line, id, key);
        }
        // big plays (25+ yards, not already a score) — and for your defense, takeaways
        JSONObject drives = s.optJSONObject("drives");
        List<JSONObject> pl = new ArrayList<>();
        if (drives != null) {
            JSONArray prev = drives.optJSONArray("previous");
            if (prev != null) for (int i = 0; i < prev.length(); i++) collect(prev.optJSONObject(i), pl);
            collect(drives.optJSONObject("current"), pl);
        }
        if (on("big")) for (JSONObject p : pl) {
            if (p.optBoolean("scoringPlay") || scoredIds.contains(p.optString("id"))) continue;
            String text = p.optString("text"), type = p.optJSONObject("type") == null ? "" : p.optJSONObject("type").optString("text");
            int yds = p.optInt("statYardage", 0);
            String offense = p.optString("_team");
            boolean takeaway = type.matches("(?i).*(interception|fumble recovery \\(opponent\\)|sack).*");
            List<Pl> who = new ArrayList<>();
            if (takeaway) { for (Pl m : mine) if (m.def() && !offense.isEmpty() && !m.espnAbbr().equals(offense)) who.add(m); }
            else if (yds >= 25 && type.matches("(?i).*(rush|pass).*")) {
                for (Pl m : involved(mine, text, offense, type, false)) if (offense.isEmpty() || m.espnAbbr().equals(offense)) who.add(m);
            }
            if (who.isEmpty()) continue;
            String key = "big:" + p.optString("id");
            if (first && !fresh(p, now)) { markSeen(key); continue; }
            String title = takeaway ? names(who) + " · " + (type.toLowerCase(Locale.US).contains("sack") ? "Sack" : type.toLowerCase(Locale.US).contains("interception") ? "Interception" : "Fumble recovery")
                    : names(who) + " · " + yds + "-yard " + role(who.get(0), text, type);
            alert("big", title, clean(text) + " · " + line, id, key);
        }
        // the final, with your players' lines
        if (fin && on("final")) {
            String key = "final:" + id;
            List<String> lines = new ArrayList<>();
            for (Pl m : mine) { String l = statLine(s, m); if (!l.isEmpty()) lines.add(l); }
            alert("final", line, lines.isEmpty() ? "None of your players had stats in this game" : android.text.TextUtils.join("   ·   ", lines), id, key);
        }
    }
    // ================================================================== the Sports tab's "My fantasy team" row

    private volatile int nflWeek, nflYear;
    /* Fantasy points, worked out from the box score with Yahoo's default scoring (half a point a catch, or
       full / none: Settings › Sports › Fantasy scoring). Kickers by field goal distance; defenses by sacks,
       takeaways, scores and points allowed. Yahoo's own numbers replace these once Yahoo lets TopShelf in. */
    String scoring() { return prefs.getString(P + "scoring", "half"); }
    void cycleScoring() { String s = scoring(); prefs.edit().putString(P + "scoring", s.equals("half") ? "ppr" : s.equals("ppr") ? "std" : "half").apply(); proj.clear(); projAt = 0; }
    private double recPts() { String s = scoring(); return s.equals("ppr") ? 1 : s.equals("std") ? 0 : .5; }
    private static double n(String v) { try { return Double.parseDouble(v.trim()); } catch (Exception e) { return 0; } }
    private static double first(String v) { if (v == null) return 0; int i = v.indexOf('/'); return n(i >= 0 ? v.substring(0, i) : v); }
    /** a player's fantasy points in a game (NaN: no stats yet) */
    private double points(Pl m, JSONObject s, JSONObject game) {
        if (s == null) return Double.NaN;
        if (m.def()) return defPoints(m, s, game);
        JSONObject box = s.optJSONObject("boxscore");
        JSONArray teams = box == null ? null : box.optJSONArray("players");
        if (teams == null) return Double.NaN;
        String id = m.key.startsWith("espn:") ? m.key.substring(5) : "";
        String full = (m.first + " " + m.last).trim().toLowerCase(Locale.US);
        double pts = 0; boolean any = false;
        for (int i = 0; i < teams.length(); i++) {
            JSONArray groups = teams.optJSONObject(i).optJSONArray("statistics");
            if (groups == null) continue;
            for (int g = 0; g < groups.length(); g++) {
                JSONObject gr = groups.optJSONObject(g);
                String name = gr.optString("name");
                JSONArray labels = gr.optJSONArray("labels"), aths = gr.optJSONArray("athletes");
                if (labels == null || aths == null) continue;
                for (int a = 0; a < aths.length(); a++) {
                    JSONObject at = aths.optJSONObject(a), ath = at.optJSONObject("athlete");
                    if (ath == null) continue;
                    boolean me = !id.isEmpty() ? id.equals(ath.optString("id")) : ath.optString("displayName").toLowerCase(Locale.US).equals(full);
                    if (!me) continue;
                    JSONArray st = at.optJSONArray("stats");
                    Map<String, String> v = new HashMap<>();
                    for (int k = 0; k < labels.length() && st != null; k++) v.put(labels.optString(k), st.optString(k));
                    any = true;
                    switch (name) {
                        case "passing": pts += n(v.get("YDS")) / 25 + 4 * n(v.get("TD")) - n(v.get("INT")); break;
                        case "rushing": pts += n(v.get("YDS")) / 10 + 6 * n(v.get("TD")); break;
                        case "receiving": pts += n(v.get("YDS")) / 10 + 6 * n(v.get("TD")) + recPts() * n(v.get("REC")); break;
                        case "fumbles": pts -= 2 * n(v.get("LOST")); break;
                        case "kickReturns": case "puntReturns": pts += 6 * n(v.get("TD")); break;
                        case "kicking": pts += first(v.get("XP")); break;      // (field goals: by distance, below)
                    }
                }
            }
        }
        // field goals by distance, from the scoring plays ("Tyler Bass 47 Yd Field Goal")
        JSONArray sp = s.optJSONArray("scoringPlays");
        if (m.pos.equals("K") && sp != null) for (int i = 0; i < sp.length(); i++) {
            JSONObject pl = sp.optJSONObject(i);
            String t = pl.optString("text");
            if (!involved(java.util.Collections.singletonList(m), t, "", "", true).isEmpty() || t.toLowerCase(Locale.US).startsWith(full)) {
                java.util.regex.Matcher mm = java.util.regex.Pattern.compile("(\\d+) (?:Yd|yd|Yard|yard) Field Goal").matcher(t);
                if (mm.find()) { int y = Integer.parseInt(mm.group(1)); pts += y >= 50 ? 5 : y >= 40 ? 4 : 3; any = true; }
            }
        }
        return any ? Math.round(pts * 100) / 100.0 : 0;
    }
    private double defPoints(Pl m, JSONObject s, JSONObject game) {
        if (game == null) return Double.NaN;
        String me = m.espnAbbr();
        JSONArray ts = game.optJSONArray("teams");
        if (ts == null || ts.length() < 2) return Double.NaN;
        JSONObject opp = ts.optJSONObject(0).optString("abbr").equals(me) ? ts.optJSONObject(1) : ts.optJSONObject(0);
        double pts = 0;
        // the other team's sacks taken, interceptions thrown and fumbles lost
        JSONObject box = s.optJSONObject("boxscore");
        JSONArray bt = box == null ? null : box.optJSONArray("teams");
        if (bt != null) for (int i = 0; i < bt.length(); i++) {
            JSONObject t = bt.optJSONObject(i);
            if (t.optJSONObject("team") == null || !t.optJSONObject("team").optString("abbreviation").toUpperCase(Locale.US).equals(opp.optString("abbr"))) continue;
            JSONArray st = t.optJSONArray("statistics");
            if (st != null) for (int k = 0; k < st.length(); k++) {
                JSONObject x = st.optJSONObject(k);
                String nm = x.optString("name"), dv = x.optString("displayValue");
                if (nm.equals("sacksYardsLost")) pts += n(dv.split("-")[0]);
                else if (nm.equals("interceptions")) pts += 2 * n(dv);
                else if (nm.equals("fumblesLost")) pts += 2 * n(dv);
            }
        }
        JSONArray sp = s.optJSONArray("scoringPlays");
        if (sp != null) for (int i = 0; i < sp.length(); i++) {
            JSONObject pl = sp.optJSONObject(i);
            String team = pl.optJSONObject("team") == null ? "" : pl.optJSONObject("team").optString("abbreviation").toUpperCase(Locale.US);
            String type = pl.optJSONObject("type") == null ? "" : pl.optJSONObject("type").optString("text").toLowerCase(Locale.US);
            if (!team.equals(me)) continue;
            if (type.contains("safety")) pts += 2;
            else if (type.matches(".*(interception|fumble|blocked|punt return|kickoff return|defensive).*")) pts += 6;
        }
        // points allowed
        double pa = n(opp.optString("score"));
        pts += pa == 0 ? 10 : pa <= 6 ? 7 : pa <= 13 ? 4 : pa <= 20 ? 1 : pa <= 27 ? 0 : pa <= 34 ? -1 : -4;
        return pts;
    }

    /* projections: ESPN fantasy's for this week (full-point scoring; adjusted for half a point or none a catch) */
    private final Map<String, Double> proj = new HashMap<>();
    private volatile long projAt; private volatile int projWeek;
    private void loadProj(List<Pl> l) {
        if (nflWeek == 0 || nflYear == 0) return;
        if (projWeek == nflWeek && System.currentTimeMillis() - projAt < 3 * 3600_000L && !proj.isEmpty()) return;
        projAt = System.currentTimeMillis();
        try {
            JSONArray ids = new JSONArray();
            Map<String, String> idFor = new HashMap<>();           // ESPN fantasy id → our key
            Map<String, String> tid = teamIds();
            for (Pl p : l) {
                if (p.key.startsWith("espn:") && !p.def()) { ids.put(Long.parseLong(p.key.substring(5))); idFor.put(p.key.substring(5), p.key); }
                else if (p.def() && tid.containsKey(p.espnAbbr())) { String d = String.valueOf(-16000 - Integer.parseInt(tid.get(p.espnAbbr()))); ids.put(Long.parseLong(d)); idFor.put(d, p.key); }
            }
            if (ids.length() == 0) return;
            String filter = "{\"players\":{\"filterIds\":{\"value\":" + ids + "},\"filterStatsForTopScoringPeriodIds\":{\"value\":2,\"additionalValue\":[\"00" + nflYear + "\",\"10" + nflYear + "\",\"11" + nflYear + nflWeek + "\"]}}}";
            HttpURLConnection c = (HttpURLConnection) new URL("https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl/seasons/" + nflYear + "/segments/0/leaguedefaults/3?scoringPeriodId=" + nflWeek + "&view=kona_player_info").openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(20000);
            c.setRequestProperty("X-Fantasy-Filter", filter); c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            String txt = read(code >= 400 ? c.getErrorStream() : c.getInputStream()); c.disconnect();
            if (code >= 400) throw new Exception("ESPN projections " + code);
            JSONArray ps = new JSONObject(txt).optJSONArray("players");
            Map<String, Double> got = new HashMap<>();
            if (ps != null) for (int i = 0; i < ps.length(); i++) {
                JSONObject pl = ps.optJSONObject(i).optJSONObject("player");
                if (pl == null) continue;
                String key = idFor.get(pl.optString("id"));
                JSONArray st = pl.optJSONArray("stats");
                if (key == null || st == null) continue;
                for (int k = 0; k < st.length(); k++) {
                    JSONObject x = st.optJSONObject(k);
                    if (x.optInt("statSourceId") != 1 || x.optInt("scoringPeriodId") != nflWeek) continue;
                    double v = x.optDouble("appliedTotal", Double.NaN);
                    JSONObject raw = x.optJSONObject("stats");
                    double rec = raw == null ? 0 : raw.optDouble("53", 0);
                    if (!Double.isNaN(v)) got.put(key, Math.round((v - (1 - recPts()) * rec) * 10) / 10.0);
                }
            }
            synchronized (proj) { proj.clear(); proj.putAll(got); }
            projWeek = nflWeek;
        } catch (Exception e) { Diag.ev("fantasy: projections " + e.getMessage()); }
    }
    /** team abbreviation → ESPN's team number (from the players list made for picking) */
    private Map<String, String> teamIds() {
        Map<String, String> m = new HashMap<>();
        try { JSONArray a = players(); for (int i = 0; i < a.length(); i++) { JSONObject p = a.optJSONObject(i); if (!p.optString("tid").isEmpty()) m.put(p.optString("abbr"), p.optString("tid")); } } catch (Exception ignored) { }
        return m;
    }

    private final Map<String, JSONObject> board = new HashMap<>();          // team → its game this week
    private final Map<String, JSONObject> summaries = new HashMap<>();      // game → its latest box score
    private void remember(JSONObject ev, JSONObject comp, JSONArray cs) {
        try {
            JSONObject type = comp.optJSONObject("status").optJSONObject("type");
            JSONObject g = new JSONObject();
            g.put("id", ev.optString("id")); g.put("state", type.optString("state")); g.put("detail", type.optString("shortDetail")); g.put("date", comp.optString("date"));
            JSONArray teams = new JSONArray(); String[] ab = new String[2];
            JSONObject[] side = new JSONObject[2];
            for (int k = 0; k < cs.length(); k++) {
                JSONObject c = cs.optJSONObject(k), t = c.optJSONObject("team");
                int sd = "home".equals(c.optString("homeAway")) ? 1 : 0;
                JSONArray recs = c.optJSONArray("records");
                String col = t.optString("color");
                side[sd] = new JSONObject().put("abbr", t.optString("abbreviation").toUpperCase(Locale.US)).put("name", t.optString("shortDisplayName", t.optString("name")))
                        .put("full", t.optString("displayName")).put("logo", t.optString("logo")).put("color", col.isEmpty() ? "" : "#" + col)
                        .put("score", c.optString("score")).put("winner", c.optBoolean("winner")).put("rec", recs != null && recs.length() > 0 ? recs.optJSONObject(0).optString("summary") : "");
                ab[sd] = t.optString("abbreviation").toUpperCase(Locale.US);
            }
            if (side[0] == null || side[1] == null) return;
            teams.put(side[0]).put(side[1]);
            g.put("teams", teams);
            JSONArray br = comp.optJSONArray("broadcasts");
            if (br != null && br.length() > 0 && br.optJSONObject(0).optJSONArray("names") != null) g.put("nets", br.optJSONObject(0).optJSONArray("names").optString(0));
            synchronized (board) { board.put(ab[0], g); board.put(ab[1], g); }
        } catch (Exception ignored) { }
    }
    /** your players as cards for the Sports tab: their game, their line so far, any injury */
    JSONArray sportsRow() {
        JSONArray out = new JSONArray();
        List<Pl> l; synchronized (roster) { l = new ArrayList<>(roster); }
        final List<String> order = java.util.Arrays.asList("QB", "RB", "WR", "TE", "K", "DEF");
        java.util.Collections.sort(l, new java.util.Comparator<Pl>() { @Override public int compare(Pl a, Pl b) {
            if (a.starter() != b.starter()) return a.starter() ? -1 : 1;
            int x = order.indexOf(a.pos.split(",")[0]), y = order.indexOf(b.pos.split(",")[0]);
            return (x < 0 ? 9 : x) - (y < 0 ? 9 : y);
        } });
        try {
            if (!l.isEmpty() && System.currentTimeMillis() - projAt > 3 * 3600_000L) {
                final List<Pl> fl = l;
                io.execute(new Runnable() { @Override public void run() { loadProj(fl); } });
            }
            double total = 0, ptotal = 0; int left = 0, playing = 0; boolean anyPts = false, anyProj = false;
            JSONArray cardsOut = new JSONArray();
            for (Pl p : l) {
                JSONObject g; JSONObject sum;
                synchronized (board) { g = board.get(p.espnAbbr()); sum = g == null ? null : summaries.get(g.optString("id")); }
                JSONObject it = new JSONObject();
                it.put("fy", true); it.put("lg", "nfl"); it.put("lgName", "NFL");
                it.put("name", p.def() ? p.name + " D/ST" : p.name); it.put("pos", p.def() ? "D/ST" : p.pos.split(",")[0]); it.put("abbr", p.espnAbbr());
                it.put("bench", !p.starter()); it.put("status", p.status); it.put("statusFull", p.status.isEmpty() ? "" : statusName(p)); it.put("note", p.note);
                if (p.key.startsWith("espn:") && !p.def()) it.put("photo", "https://a.espncdn.com/i/headshots/nfl/players/full/" + p.key.substring(5) + ".png");
                String state = "", line = "";
                if (g != null) {
                    state = g.optString("state");
                    it.put("state", state); it.put("gameId", g.optString("id")); it.put("teams", g.optJSONArray("teams"));
                    long at = iso(g.optString("date"));
                    String when = state.equals("in") ? "LIVE · " + g.optString("detail") : state.equals("post") ? "Final" : at > 0 ? new SimpleDateFormat("EEE h:mm a", Locale.US).format(new java.util.Date(at)) : g.optString("detail");
                    it.put("when", when);
                    JSONArray ts = g.optJSONArray("teams");
                    String a0 = ts.optJSONObject(0).optString("abbr"), a1 = ts.optJSONObject(1).optString("abbr");
                    boolean home = a1.equals(p.espnAbbr());
                    it.put("opp", (home ? "vs " + a0 : "@ " + a1));
                    it.put("score", state.equals("pre") ? "" : ts.optJSONObject(0).optString("score") + "–" + ts.optJSONObject(1).optString("score"));
                    it.put("tv", g.optString("nets"));
                    if (sum != null) { line = statLine(sum, p); int c = line.indexOf(": "); if (c >= 0) line = line.substring(c + 2); }
                }
                it.put("line", line);
                double pt = g == null || "pre".equals(state) ? Double.NaN : points(p, sum, g);
                Double pj; synchronized (proj) { pj = proj.get(p.key); }
                if (!Double.isNaN(pt)) it.put("pts", fmtPts(pt));
                if (pj != null) it.put("proj", fmtPts(pj));
                if (p.starter()) {
                    if (!Double.isNaN(pt)) { total += pt; anyPts = true; }
                    if (pj != null) { ptotal += "post".equals(state) && !Double.isNaN(pt) ? pt : pj; anyProj = true; }
                    if (g == null || "pre".equals(state)) left++; else if ("in".equals(state)) playing++;
                }
                for (JSONObject x : fyAlerts) if (x.optString("who").contains(p.name)) { it.put("last", x.optString("title")); break; }
                it.put("k", "fy:" + p.key + ":" + state + ":" + it.optString("score") + ":" + line.hashCode() + ":" + p.status + ":" + it.optString("when") + ":" + it.optString("last").hashCode() + ":" + it.optString("pts") + ":" + it.optString("proj"));
                cardsOut.put(it);
            }
            if (!l.isEmpty()) {
                // your team: points so far, projected, who's still to play
                JSONObject tm = new JSONObject();
                tm.put("fy", true); tm.put("fyTeam", true); tm.put("lg", "nfl"); tm.put("lgName", "NFL");
                tm.put("name", connected() && teams().length() > 0 ? teams().optJSONObject(0).optString("name") : "My team");
                tm.put("week", nflWeek);
                tm.put("pts", anyPts ? fmtPts(total) : "0");
                if (anyProj) tm.put("proj", fmtPts(ptotal));
                tm.put("left", left); tm.put("playing", playing);
                tm.put("scoring", scoring().equals("ppr") ? "PPR" : scoring().equals("std") ? "Standard" : "Half PPR");
                tm.put("k", "fy:team:" + tm.optString("pts") + ":" + tm.optString("proj") + ":" + left + ":" + playing + ":" + nflWeek + ":" + scoring());
                out.put(tm);
            }
            for (int i = 0; i < cardsOut.length(); i++) out.put(cardsOut.get(i));
            JSONObject ed = new JSONObject();
            ed.put("edit", true); ed.put("fyEdit", true); ed.put("k", "fy:edit:" + l.size());
            ed.put("label", connected() ? "Your Yahoo team" : l.isEmpty() ? "Pick your fantasy players" : "Edit my players");
            if (!connected() || l.isEmpty()) out.put(ed);
        } catch (Exception ignored) { }
        return out;
    }
    private static String fmtPts(double v) { return String.format(Locale.US, "%.1f", v); }
    /** the latest alerts (newest first), for the cards */
    private final List<JSONObject> fyAlerts = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static void collect(JSONObject drive, List<JSONObject> out) {
        if (drive == null) return;
        JSONArray ps = drive.optJSONArray("plays");
        String team = drive.optJSONObject("team") == null ? "" : drive.optJSONObject("team").optString("abbreviation").toUpperCase(Locale.US);
        if (ps != null) for (int i = 0; i < ps.length(); i++) { JSONObject p = ps.optJSONObject(i); if (p != null) { try { p.put("_team", team); } catch (Exception ignored) { } out.add(p); } }
    }
    /** a play counts as news only for a while after it happened (no flood of old plays when the TV comes on) */
    private static boolean fresh(JSONObject p, long now) {
        String w = p.optString("wallclock");
        if (w.isEmpty()) return false;
        long at = iso(w);
        return at > 0 && now - at < 15 * 60000L;
    }

    /** your players a play mentions ("Josh Allen", or "J.Allen" in play-by-play), or your defense's score */
    private static List<Pl> involved(List<Pl> mine, String text, String team, String type, boolean scoring) {
        List<Pl> out = new ArrayList<>();
        // (the extra point in brackets, "(Tyler Bass Kick)", isn't the kicker's score)
        String t = text.toLowerCase(Locale.US).replaceAll("\\([^)]*\\bkick\\)", "").replace(". ", ".");
        for (Pl p : mine) {
            if (p.def()) {
                if (scoring && p.espnAbbr().equals(team) && type.matches("(?i).*(interception|fumble|safety|blocked|punt return|kickoff return|defensive).*")) out.add(p);
                continue;
            }
            String last = p.last.toLowerCase(Locale.US).replaceAll("\\s+(jr\\.?|sr\\.?|ii|iii|iv|v)$", "").replace(". ", ".");
            String full = (p.first + " " + p.last).toLowerCase(Locale.US).replace(". ", ".");
            String ini = p.first.isEmpty() ? "" : p.first.substring(0, 1).toLowerCase(Locale.US) + "." + last;
            if ((!full.trim().isEmpty() && t.contains(full)) || (!ini.isEmpty() && t.matches("(?s).*(^|[^a-z])" + java.util.regex.Pattern.quote(ini) + "([^a-z]|$).*"))) out.add(p);
        }
        return out;
    }
    private static String names(List<Pl> who) {
        Set<String> n = new LinkedHashSet<>(); for (Pl p : who) n.add(p.def() ? p.name + " D/ST" : p.name);
        return android.text.TextUtils.join(" & ", n);
    }
    private static String scoreWord(String type) {
        String t = type.toLowerCase(Locale.US);
        if (t.contains("field goal")) return "Field goal";
        if (t.contains("safety")) return "Safety";
        if (t.contains("two-point") || t.contains("2pt")) return "Two-point conversion";
        if (t.contains("touchdown")) return "Touchdown";
        return type.isEmpty() ? "Score" : type;
    }
    private static String role(Pl p, String text, String type) {
        String t = text.toLowerCase(Locale.US), ty = type.toLowerCase(Locale.US);
        if (ty.contains("rush")) return "run";
        String ini = p.first.isEmpty() ? "" : p.first.substring(0, 1).toLowerCase(Locale.US) + "." + p.last.toLowerCase(Locale.US);
        int to = t.indexOf(" to ");
        if (to >= 0 && (t.indexOf(ini, to) > 0 || t.indexOf(p.last.toLowerCase(Locale.US), to) > 0)) return "catch";
        return "pass";
    }
    private static String clean(String s) { s = s.replaceAll("^\\((Shotgun|No Huddle|No Huddle, Shotgun)\\)\\s*", ""); return s.length() > 150 ? s.substring(0, 150) + "…" : s; }

    /** "Josh Allen 22/31, 284 yds, 3 TD · 9 rush yds" from the game's box score */
    private static String statLine(JSONObject s, Pl m) {
        JSONObject box = s.optJSONObject("boxscore");
        JSONArray teams = box == null ? null : box.optJSONArray("players");
        if (teams == null) return "";
        List<String> parts = new ArrayList<>();
        String full = (m.first + " " + m.last).trim().toLowerCase(Locale.US);
        if (m.def()) return "";
        for (int i = 0; i < teams.length(); i++) {
            JSONObject tm = teams.optJSONObject(i);
            JSONArray groups = tm.optJSONArray("statistics");
            if (groups == null) continue;
            for (int g = 0; g < groups.length(); g++) {
                JSONObject gr = groups.optJSONObject(g);
                String name = gr.optString("name");
                JSONArray labels = gr.optJSONArray("labels"), aths = gr.optJSONArray("athletes");
                if (labels == null || aths == null) continue;
                for (int a = 0; a < aths.length(); a++) {
                    JSONObject at = aths.optJSONObject(a);
                    JSONObject ath = at.optJSONObject("athlete");
                    if (ath == null || !ath.optString("displayName").toLowerCase(Locale.US).equals(full)) continue;
                    JSONArray st = at.optJSONArray("stats");
                    Map<String, String> v = new HashMap<>();
                    for (int k = 0; k < labels.length() && st != null; k++) v.put(labels.optString(k), st.optString(k));
                    if (name.equals("passing")) parts.add(v.get("C/ATT") + ", " + v.get("YDS") + " pass yds, " + v.get("TD") + " TD" + (!"0".equals(v.get("INT")) ? ", " + v.get("INT") + " INT" : ""));
                    else if (name.equals("rushing")) parts.add(v.get("CAR") + " car, " + v.get("YDS") + " rush yds" + (!"0".equals(v.get("TD")) ? ", " + v.get("TD") + " TD" : ""));
                    else if (name.equals("receiving")) parts.add(v.get("REC") + " rec, " + v.get("YDS") + " yds" + (!"0".equals(v.get("TD")) ? ", " + v.get("TD") + " TD" : ""));
                    else if (name.equals("kicking")) parts.add(v.get("FG") + " FG, " + v.get("XP") + " XP");
                }
            }
        }
        return parts.isEmpty() ? "" : m.name + ": " + android.text.TextUtils.join(" · ", parts).replace("null", "–");
    }

    /** when the week's over: your matchup's result */
    private void matchups() throws Exception {
        if (!on("final") || week == 0) return;
        JSONArray teams = teams();
        for (int i = 0; i < teams.length(); i++) {
            JSONObject t = teams.optJSONObject(i);
            String key = "match:" + t.optString("key") + ":" + week;
            if (seen().optBoolean(key, false)) continue;
            Document d = yahoo("team/" + t.optString("key") + "/matchups;weeks=" + week);
            NodeList ms = d.getElementsByTagName("matchup");
            if (ms.getLength() == 0) continue;
            Element m = (Element) ms.item(0);
            if (!tx(m, "status").equals("postevent")) continue;
            NodeList ts = m.getElementsByTagName("team");
            String mine = "", them = "", them_n = "";
            for (int k = 0; k < ts.getLength(); k++) {
                Element te = (Element) ts.item(k);
                String pts = tx(child(te, "team_points"), "total");
                if (tx(te, "team_key").equals(t.optString("key"))) mine = pts; else { them = pts; Element nm = child(te, "name"); them_n = nm == null ? "" : nm.getTextContent().trim(); }
            }
            double a = num(mine), b = num(them);
            boolean won = a > b;
            alert("final", (won ? "You won" : a == b ? "You tied" : "You lost") + " week " + week + (teams.length() > 1 ? " · " + t.optString("name") : ""),
                    mine + " – " + them + " vs " + them_n, null, key);
        }
    }
    private static double num(String s) { try { return Double.parseDouble(s); } catch (Exception e) { return 0; } }

    // ================================================================== running

    /** TopShelf came on screen: look now, then keep looking while it's there */
    void resume() { running = true; kick(); }
    void pause() { running = false; main.removeCallbacks(tick); }
    void kick() { main.removeCallbacks(tick); if (running && active()) main.post(tick); }

    private final Runnable tick = new Runnable() { @Override public void run() {
        if (!running || !active()) return;
        io.execute(new Runnable() { @Override public void run() {
            long delay = 5 * 60000L;
            try {
                long now = System.currentTimeMillis();
                if (roster.isEmpty() || now - rosterAt > (anyLive ? 10 : 30) * 60000L) loadRosters();
                if (!roster.isEmpty() && (anyLive || now - boardAt > 4 * 60000L)) games();
                if (!anyLive && !manual() && now - matchAt > 30 * 60000L) { matchAt = now; matchups(); }
                delay = anyLive ? 25000 : 4 * 60000L;
                prefs.edit().remove(P + "err").apply();
            } catch (Throwable t) {
                String e = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                prefs.edit().putString(P + "err", e).apply();
                Diag.ev("fantasy: " + e);
                delay = 2 * 60000L;
            }
            final long d = delay;
            main.post(new Runnable() { @Override public void run() { main.removeCallbacks(tick); if (running) main.postDelayed(tick, d); } });
        } });
    } };

    // ================================================================== alerts

    private JSONObject seenCache;
    private JSONObject seen() {
        if (seenCache == null) try { seenCache = new JSONObject(prefs.getString(P + "seen", "{}")); } catch (Exception e) { seenCache = new JSONObject(); }
        return seenCache;
    }
    private void markSeen(String key) {
        JSONObject s = seen();
        try {
            s.put(key, true);
            if (s.length() > 600) {                         // keep the newest
                JSONObject n = new JSONObject(); JSONArray names = s.names(); int skip = names.length() - 400;
                for (int i = skip; i < names.length(); i++) n.put(names.getString(i), true);
                seenCache = s = n;
            }
        } catch (Exception ignored) { }
        prefs.edit().putString(P + "seen", s.toString()).apply();
    }
    private void alert(final String kind, final String title, final String detail, final String game, String key) {
        if (seen().optBoolean(key, false)) return;
        markSeen(key);
        try { fyAlerts.add(0, new JSONObject().put("title", title).put("who", title).put("at", System.currentTimeMillis())); while (fyAlerts.size() > 30) fyAlerts.remove(fyAlerts.size() - 1); } catch (Exception ignored) { }
        Diag.ev("fantasy alert: " + title);
        main.post(new Runnable() { @Override public void run() {
            NativeHome h = act.nativeHome();
            if (h != null) h.fantasyAlert(kind, title, detail, game);
        } });
    }

    // ================================================================== bits

    private static String get(String url) throws Exception { byte[] b = HomeImages.http(url, null); if (b == null) throw new Exception("no answer from " + new URL(url).getHost()); return new String(b, "UTF-8"); }
    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }
    private static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; } }
    private static long iso(String s) {
        for (String f : new String[] { "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" }) {
            try { SimpleDateFormat d = new SimpleDateFormat(f, Locale.US); d.setTimeZone(TimeZone.getTimeZone("UTC")); return d.parse(s).getTime(); } catch (Exception ignored) { }
        }
        return 0;
    }
    /** Yahoo's team abbreviations → ESPN's */
    static String espn(String yahoo) {
        String a = yahoo.toUpperCase(Locale.US);
        switch (a) { case "WAS": return "WSH"; case "JAC": return "JAX"; case "LA": return "LAR"; case "OAK": return "LV"; case "SD": return "LAC"; default: return a; }
    }
}
