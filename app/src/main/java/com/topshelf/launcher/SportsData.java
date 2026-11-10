package com.topshelf.launcher;

import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Sports tab's data, worked out by the app (it was the hidden page's job): your sports and teams,
 * their schedules and scores from ESPN's public scoreboards, and which of your Live TV channels is
 * showing each game — from the TV guide, from event channels named after the teams, and from the
 * game's TV networks. A line-for-line port of the page's sports.js (data, matching, rows, the game's
 * menu); the team picker and the score alerts are still the page's.
 *
 * Threads: the state lives on the main thread. ESPN is asked, and the rows and channel matches are
 * worked out, on a worker; results come back to the main thread.
 */
final class SportsData {
    static final String ESPN = "https://site.api.espn.com/apis/site/v2/sports";
    static final long DAY = 86400000L;

    static final class Lg {
        final String id, sport, league, name, icon, q; final int d0, d1; final boolean college, cards;
        Lg(String id, String sport, String league, String name, String icon, int d0, int d1, String q, boolean college, boolean cards) {
            this.id = id; this.sport = sport; this.league = league; this.name = name; this.icon = icon; this.d0 = d0; this.d1 = d1; this.q = q; this.college = college; this.cards = cards;
        }
    }
    static final Lg[] LEAGUES = {
        new Lg("nfl", "football", "nfl", "NFL", "🏈", -1, 7, "", false, false),
        new Lg("nba", "basketball", "nba", "NBA", "🏀", -1, 4, "", false, false),
        new Lg("mlb", "baseball", "mlb", "MLB", "⚾", -1, 4, "", false, false),
        new Lg("nhl", "hockey", "nhl", "NHL", "🏒", -1, 4, "", false, false),
        new Lg("wnba", "basketball", "wnba", "WNBA", "🏀", -1, 4, "", false, false),
        new Lg("cfb", "football", "college-football", "College Football", "🏈", -1, 7, "groups=80&limit=400", true, false),
        new Lg("cbb", "basketball", "mens-college-basketball", "Men's College Hoops", "🏀", -1, 3, "groups=50&limit=400", true, false),
        new Lg("mls", "soccer", "usa.1", "MLS", "⚽", -1, 7, "", false, false),
        new Lg("epl", "soccer", "eng.1", "Premier League", "⚽", -1, 7, "", false, false),
        new Lg("ucl", "soccer", "uefa.champions", "Champions League", "⚽", -1, 7, "", false, false),
        new Lg("laliga", "soccer", "esp.1", "La Liga", "⚽", -1, 7, "", false, false),
        new Lg("ufc", "mma", "ufc", "UFC", "🥊", -2, 21, "", false, true),
    };
    static Lg league(String id) { for (Lg l : LEAGUES) if (l.id.equals(id)) return l; return null; }

    static final class Team {
        String id = "", name = "?", color = "", alt = "", full = "", nick = "", loc = "", abbr = "", logo = "", score = "", rec = "", head = "";
        boolean home, winner; int rank;
    }
    static final class Bout { String a = "?", b = "?", cls = "", result = ""; boolean done, live; }
    static final class Ev {
        String id = "", lg = "", lgName = "", name = "", shortName = "", state = "pre", detail = "", venue = "";
        long at, mainAt, end; boolean done, card, hasMainAt;
        final List<Team> teams = new ArrayList<>();
        final List<String> nets = new ArrayList<>();
        final List<Bout> bouts = new ArrayList<>();
        /* the game as a fan wants it (from the same scoreboard): the period and clock, the situation (down and
           distance and where the ball is; the count, outs and runners), the last play, the chance each side wins,
           and before / after the game the line, a note ("World Series · Game 3") and the recap's headline */
        String per = "", clock = "", down = "", spot = "", lastPlay = "", note = "", headline = "", line = "", batter = "", pitcher = "";
        int week, poss = -1, balls = -1, strikes = -1, outs = -1; boolean redZone; boolean[] bases;
        float ball = -1, firstDown = -1, homeWin = -1;
    }
    /** a channel that should be showing a game */
    static final class Hit { NativeLive.Ch ch; double score; String why; Object[] prog; }

    private final NativeHome h;
    private final Handler main;
    private final ExecutorService net = Executors.newFixedThreadPool(8);
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    void dispose() { work.shutdownNow(); }

    // ---- state (main thread)
    private final Map<String, List<Ev>> ev = new HashMap<>();
    private final Map<String, Long> t = new HashMap<>(), tFull = new HashMap<>();
    private final Set<String> busy = new HashSet<>();
    private final Map<String, String> errs = new HashMap<>();
    private JSONObject prefs = new JSONObject();
    private boolean on;
    /** the rows last handed to the tab (their games, by row and column) */
    private List<List<Ev>> shownRows = new ArrayList<>();

    // ---- the channels, for matching (made on the main thread, read on the worker)
    private static final class Chan { final NativeLive.Ch ch; final String raw; volatile String compact; Chan(NativeLive.Ch c) { ch = c; raw = c.n.toLowerCase(Locale.ROOT); } }
    private volatile List<Chan> netIdx = new ArrayList<>();
    private volatile Map<String, List<NativeLive.Ch>> byKey = new HashMap<>();
    private volatile Set<String> favs = new HashSet<>();
    private String chanKey = "";
    private final Map<String, Object[]> match = new ConcurrentHashMap<>();     // ev id → {time, List<Hit>}

    SportsData(NativeHome h) { this.h = h; this.main = h.main; }

    // ================================================================== prefs

    private void readPrefs() {
        try { String s = h.prefs.getString("ui.sports", null); prefs = s == null ? new JSONObject() : new JSONObject(s); } catch (Exception e) { prefs = new JSONObject(); }
    }
    private List<String> leagues() {
        List<String> out = new ArrayList<>();
        JSONArray a = prefs.optJSONArray("leagues");
        if (a != null) for (int i = 0; i < a.length(); i++) if (league(a.optString(i)) != null) out.add(a.optString(i));
        return out;
    }
    private boolean isFav(String lg, String id) {
        JSONObject tm = prefs.optJSONObject("teams");
        JSONArray l = tm == null ? null : tm.optJSONArray(lg);
        if (l == null) return false;
        for (int i = 0; i < l.length(); i++) { JSONObject o = l.optJSONObject(i); if (o != null && str(o, "id").equals(id)) return true; }
        return false;
    }
    private boolean anyTeams() {
        JSONObject tm = prefs.optJSONObject("teams");
        if (tm == null) return false;
        for (java.util.Iterator<String> it = tm.keys(); it.hasNext(); ) { JSONArray l = tm.optJSONArray(it.next()); if (l != null && l.length() > 0) return true; }
        return false;
    }
    private boolean favGame(Ev e) { if (e.card) return true; for (Team x : e.teams) if (isFav(e.lg, x.id)) return true; return false; }

    // ================================================================== on screen

    /* The tab is showing (or not). Straight away: the rows as they were last time (kept on the TV), or from
       what was fetched in the background. Then yesterday's and today's games first (a couple of quick
       questions per league, shown as soon as they're in), then the rest of the week. While it's open: every
       30 seconds while a game's on, otherwise every 4 minutes. */
    void setOn(boolean o) {
        on = o;
        main.removeCallbacks(loop);
        if (!o) return;
        readPrefs();
        if (ev.isEmpty() && shownRows.isEmpty()) showSaved(); else push();
        refresh();
    }
    private void refresh() {
        loadAll(false, true, new Runnable() { @Override public void run() {
            push();
            loadAll(false, false, new Runnable() { @Override public void run() { push(); schedule(); loadPast(); } });
        } });
    }
    /* Results: the games already played. Yesterday's and today's come with the scores above; each day before
       that (back a week) is fetched once per league, a day at a time (ESPN doesn't take date ranges for every
       league), and kept while TopShelf runs: a finished day doesn't change. */
    private static final int PAST_DAYS = 7;
    private final Map<String, List<Ev>> pastDays = new HashMap<>();          // "league|yyyymmdd" → that day's games
    private final Set<String> pastBusy = new HashSet<>();
    private void loadPast() {
        final int[] left = { 0 };
        Set<String> keep = new HashSet<>();
        for (final String id : leagues()) {
            final Lg lg = league(id);
            if (lg == null || lg.cards) continue;
            for (int n = -PAST_DAYS; n <= -2; n++) {
                final String day = ymd(dayOffset(n)), key = id + "|" + day;
                keep.add(key);
                if (pastDays.containsKey(key) || !pastBusy.add(key)) continue;
                left[0]++;
                net.execute(new Runnable() { @Override public void run() {
                    List<Ev> l = null;
                    try { l = events(lg, get(scoreUrl(lg, day))); } catch (Throwable ignored) { }
                    final List<Ev> res = l;
                    main.post(new Runnable() { @Override public void run() {
                        pastBusy.remove(key);
                        if (res != null) pastDays.put(key, res);
                        if (--left[0] == 0) { Diag.ev("sports: results for the last " + PAST_DAYS + " days are in (" + pastDays.size() + " league-days)"); push(); }
                    } });
                } });
            }
        }
        pastDays.keySet().retainAll(keep);
    }
    /** the team picker changed your sports or teams */
    void prefsChanged() {
        readPrefs();
        match.clear();
        if (!on) return;
        push();
        refresh();
    }
    /** a few seconds after TopShelf starts, and every 10 minutes while it's on screen: the games fetched in the
        background, so the tab has them the moment it opens */
    void warm() {
        main.removeCallbacks(warmer);
        main.postDelayed(warmer, 10 * 60000);
        if (on || !MainActivity.inFront) return;
        readPrefs();
        if (leagues().isEmpty()) return;
        loadAll(false, false, new Runnable() { @Override public void run() { } });
    }
    private final Runnable warmer = new Runnable() { @Override public void run() { warm(); } };
    private final Runnable loop = new Runnable() { @Override public void run() {
        if (!on) return;
        loadAll(false, false, new Runnable() { @Override public void run() { push(); schedule(); } });
    } };
    private boolean anyLive() { for (String id : leagues()) for (Ev e : list(id)) if (e.state.equals("in")) return true; return false; }
    private void schedule() {
        main.removeCallbacks(loop);
        if (!on) return;
        main.postDelayed(loop, anyLive() ? 30000 : 4 * 60000);
    }

    // ---- the rows as last shown, kept on the TV (so the tab isn't empty while ESPN answers after a restart)
    private java.io.File savedFile() { return new java.io.File(h.act.getCacheDir(), "sports-rows.json"); }
    private void showSaved() {
        try {
            java.io.File f = savedFile();
            if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 2 * DAY) { push(); return; }
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f); int n = 0; while (n < b.length) { int k = in.read(b, n, b.length - n); if (k < 0) break; n += k; } in.close();
            JSONObject o = new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8));
            o.put("updated", "Updating…");
            h.setSports(o.toString());
        } catch (Throwable e) { push(); }
    }
    private void save(final String json) {
        work.execute(new Runnable() { @Override public void run() {
            try { java.io.FileOutputStream o = new java.io.FileOutputStream(savedFile()); o.write(json.getBytes(StandardCharsets.UTF_8)); o.close(); } catch (Throwable ignored) { }
        } });
    }
    private List<Ev> list(String id) { List<Ev> l = ev.get(id); return l == null ? Collections.<Ev>emptyList() : l; }

    // ================================================================== ESPN

    private static String scoreUrl(Lg lg, String ymd) {
        StringBuilder qs = new StringBuilder();
        if (!ymd.isEmpty()) qs.append("dates=").append(ymd);
        if (!lg.q.isEmpty()) { if (qs.length() > 0) qs.append('&'); qs.append(lg.q); }
        return ESPN + "/" + lg.sport + "/" + lg.league + "/scoreboard" + (qs.length() > 0 ? "?" + qs : "");
    }
    private static final Pattern ERR_MSG = Pattern.compile("\"(?:message|detail|error)\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    /** ESPN's answer, or an error carrying ESPN's own message */
    static JSONObject get(String url) throws Exception {
        HttpURLConnection c = LiveTv.open(url, null, null);
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = "";
            if (in != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[32768]; int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                in.close();
                body = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            }
            if (code >= 400) {
                String tx = body.replaceAll("\\s+", " ").trim();
                Matcher m = ERR_MSG.matcher(tx);
                String msg = m.find() ? m.group(1) : tx;
                throw new Exception("HTTP " + code + (tx.isEmpty() ? "" : ": " + (msg.length() > 70 ? msg.substring(0, 70) : msg)));
            }
            return new JSONObject(body);
        } finally { c.disconnect(); }
    }

    /* Only some parts of a big answer, read as it arrives: a game's summary is mostly the box score and every
       play (a megabyte or two), and the billboard wants its story, videos and stadium. Reading it whole for
       each of a round's games made the app's memory jump by a hundred megabytes. */
    static JSONObject getParts(String url, String... keys) throws Exception {
        java.util.Set<String> want = new HashSet<>(java.util.Arrays.asList(keys));
        HttpURLConnection c = LiveTv.open(url, null, null);
        try {
            if (c.getResponseCode() >= 400) throw new Exception("HTTP " + c.getResponseCode());
            android.util.JsonReader r = new android.util.JsonReader(new java.io.InputStreamReader(new java.io.BufferedInputStream(c.getInputStream(), 32768), "UTF-8"));
            r.setLenient(true);
            JSONObject out = new JSONObject();
            r.beginObject();
            while (r.hasNext()) {
                String k = r.nextName();
                if (want.contains(k)) out.put(k, readJson(r)); else r.skipValue();
            }
            r.close();
            return out;
        } finally { c.disconnect(); }
    }
    private static Object readJson(android.util.JsonReader r) throws Exception {
        switch (r.peek()) {
            case BEGIN_OBJECT: { JSONObject o = new JSONObject(); r.beginObject(); while (r.hasNext()) { String k = r.nextName(); o.put(k, readJson(r)); } r.endObject(); return o; }
            case BEGIN_ARRAY: { JSONArray a = new JSONArray(); r.beginArray(); while (r.hasNext()) a.put(readJson(r)); r.endArray(); return a; }
            case STRING: return r.nextString();
            case NUMBER: { String n = r.nextString(); try { return n.contains(".") || n.contains("e") || n.contains("E") ? (Object) Double.parseDouble(n) : (Object) Long.parseLong(n); } catch (Exception e) { return n; } }
            case BOOLEAN: return r.nextBoolean();
            default: r.nextNull(); return JSONObject.NULL;
        }
    }
    static String ymd(Calendar c) { return String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)); }
    static Calendar dayOffset(int n) { Calendar c = Calendar.getInstance(); c.set(Calendar.HOUR_OF_DAY, 12); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0); c.add(Calendar.DAY_OF_MONTH, n); return c; }
    static String etYmd(long ms) { Calendar c = Calendar.getInstance(TimeZone.getTimeZone("America/New_York")); c.setTimeInMillis(ms); return ymd(c); }

    /* each league's own logo, from ESPN's answer (UFC, college football… have no fixed address we know) */
    private void noteLogo(final String id, JSONObject j) {
        try {
            JSONArray lgs = j.optJSONArray("leagues");
            JSONObject l0 = lgs == null ? null : lgs.optJSONObject(0);
            JSONArray ls = l0 == null ? null : l0.optJSONArray("logos");
            if (ls == null) return;
            JSONObject pick = null, nonDark = null, first = null;
            for (int i = 0; i < ls.length(); i++) {
                JSONObject l = ls.optJSONObject(i);
                if (l == null || str(l, "href").isEmpty()) continue;
                if (first == null) first = l;
                JSONArray rel = l.optJSONArray("rel");
                boolean def = false, dark = false;
                if (rel != null) for (int k = 0; k < rel.length(); k++) { if ("default".equals(rel.optString(k))) def = true; if ("dark".equals(rel.optString(k))) dark = true; }
                if (pick == null && def && !dark) pick = l;
                if (nonDark == null && !dark) nonDark = l;
            }
            if (pick == null) pick = nonDark != null ? nonDark : first;
            if (pick == null) return;
            final String href = str(pick, "href");
            main.post(new Runnable() { @Override public void run() {
                try {
                    String s = h.prefs.getString("ui.spLgLogo", null);
                    JSONObject o = s == null ? new JSONObject() : new JSONObject(s);
                    if (href.equals(o.optString(id))) return;
                    o.put(id, href);
                    h.prefs.edit().putString("ui.spLgLogo", o.toString()).apply();
                } catch (Exception ignored) { }
            } });
        } catch (Throwable ignored) { }
    }
    private String lgLogo(String id) {
        try { String s = h.prefs.getString("ui.spLgLogo", null); return s == null ? "" : new JSONObject(s).optString(id); } catch (Exception e) { return ""; }
    }

    private void loadAll(boolean force, boolean quick, final Runnable done) {
        List<String> ids = leagues();
        if (ids.isEmpty()) { done.run(); return; }
        final int[] left = { ids.size() };
        Runnable one = new Runnable() { @Override public void run() { if (--left[0] == 0) done.run(); } };
        for (String id : ids) loadLeague(id, force, quick, one);
    }

    /* One request per day (date ranges aren't accepted by every league); football's whole week in one (ESPN's
       own "this week"), plus yesterday and today. quick: only yesterday and today. Otherwise the whole window
       every 15 minutes, and in between just yesterday and today. */
    private void loadLeague(final String id, boolean force, boolean quick, final Runnable done) {
        final Lg lg = league(id);
        if (lg == null || busy.contains(id)) { done.run(); return; }
        List<Ev> cur = list(id);
        boolean have = !cur.isEmpty(), live = false;
        for (Ev e : cur) if (e.state.equals("in")) live = true;
        long now = System.currentTimeMillis();
        long age = now - (t.containsKey(id) ? t.get(id) : 0), fullAge = now - (tFull.containsKey(id) ? tFull.get(id) : 0);
        boolean needFull = !have || fullAge >= 15 * 60000;
        if (!force && have && age < (live ? 25000 : 4 * 60000) && (quick || !needFull)) { done.run(); return; }
        final boolean partial = quick || (!force && !needFull);
        busy.add(id);
        if (lg.cards) {
            net.execute(new Runnable() { @Override public void run() {
                List<Ev> out = null; String err = "";
                try { out = loadCards(lg); } catch (Throwable e) { err = e.getMessage() == null ? "offline" : e.getMessage(); }
                final List<Ev> res = out; final String er = err;
                main.post(new Runnable() { @Override public void run() {
                    if (res != null) { ev.put(id, res); long n = System.currentTimeMillis(); t.put(id, n); tFull.put(id, n); errs.put(id, ""); }
                    else { errs.put(id, er.isEmpty() ? "offline" : er); if (!ev.containsKey(id)) ev.put(id, new ArrayList<Ev>()); Diag.ev("sports: " + id + " couldn't load · " + errs.get(id)); }
                    busy.remove(id);
                    done.run();
                } });
            } });
            return;
        }
        final List<String> days = new ArrayList<>();
        boolean football = lg.sport.equals("football");
        if (!partial && football) days.add("");                              // (this week, whole)
        if (partial || football) { days.add(ymd(dayOffset(-1))); days.add(ymd(dayOffset(0))); }
        else for (int n = lg.d0; n <= lg.d1; n++) days.add(ymd(dayOffset(n)));
        final Object[] got = new Object[days.size()];
        final List<String> errList = Collections.synchronizedList(new ArrayList<String>());
        final int[] left = { days.size() };
        for (int i = 0; i < days.size(); i++) {
            final int k = i; final String url = scoreUrl(lg, days.get(i));
            net.execute(new Runnable() { @Override public void run() {
                List<Ev> l = null;
                try { JSONObject j = get(url); noteLogo(id, j); l = events(lg, j); }
                catch (Throwable e) { errList.add(e.getMessage() == null ? "error" : e.getMessage()); }
                final List<Ev> res = l;
                main.post(new Runnable() { @Override public void run() {
                    got[k] = res;
                    if (--left[0] > 0) return;
                    boolean all = true;
                    for (Object g : got) if (g != null) all = false;
                    if (all) {
                        // fall back to ESPN's default "today"
                        net.execute(new Runnable() { @Override public void run() {
                            List<Ev> fb = null; String er = errList.isEmpty() ? "error" : errList.get(0);
                            try { fb = events(lg, get(scoreUrl(lg, ""))); } catch (Throwable e) { if (errList.isEmpty()) er = e.getMessage() == null ? "error" : e.getMessage(); }
                            final List<Ev> f = fb; final String e2 = er;
                            main.post(new Runnable() { @Override public void run() { finishLeague(id, partial, f == null ? null : Collections.<Object>singletonList(f), e2, done); } });
                        } });
                        return;
                    }
                    List<Object> parts = new ArrayList<>();
                    for (Object g : got) if (g != null) parts.add(g);
                    finishLeague(id, partial, parts, "", done);
                } });
            } });
        }
    }
    @SuppressWarnings("unchecked")
    private void finishLeague(String id, boolean partial, List<Object> parts, String err, Runnable done) {
        if (parts != null) {
            LinkedHashMap<String, Ev> byId = new LinkedHashMap<>();
            if (partial) for (Ev e : list(id)) byId.put(e.id, e);
            for (Object p : parts) for (Ev e : (List<Ev>) p) byId.put(e.id, e);      // (keeps a game's first place, as the page's Map did)
            List<Ev> all = new ArrayList<>(byId.values());
            Collections.sort(all, new Comparator<Ev>() { @Override public int compare(Ev a, Ev b) { return Long.compare(a.at, b.at); } });
            ev.put(id, all);
            t.put(id, System.currentTimeMillis());
            if (!partial) tFull.put(id, System.currentTimeMillis());
            errs.put(id, "");
        } else {
            errs.put(id, err == null || err.isEmpty() ? "offline" : err);
            if (!ev.containsKey(id)) ev.put(id, new ArrayList<Ev>());
            Diag.ev("sports: " + id + " couldn't load · " + errs.get(id));
        }
        busy.remove(id);
        done.run();
    }

    private static List<Ev> events(Lg lg, JSONObject j) {
        List<Ev> out = new ArrayList<>();
        JSONArray a = j.optJSONArray("events");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if (e == null) continue;
            Ev x = event(lg, e);
            if (x != null && x.teams.size() == 2) out.add(x);
        }
        return out;
    }

    private static final Pattern HEX6 = Pattern.compile("(?i)^[0-9a-f]{6}$");
    static Ev event(Lg lg, JSONObject e) {
        JSONObject c = first(e.optJSONArray("competitions"));
        if (c == null) c = new JSONObject();
        JSONObject stw = c.optJSONObject("status") != null ? c.optJSONObject("status") : e.optJSONObject("status");
        JSONObject st = stw == null ? null : stw.optJSONObject("type");
        if (st == null) st = new JSONObject();
        Ev x = new Ev();
        JSONArray comps = c.optJSONArray("competitors");
        if (comps != null) for (int i = 0; i < comps.length(); i++) {
            JSONObject o = comps.optJSONObject(i);
            if (o == null) continue;
            JSONObject tm = o.optJSONObject("team");
            if (tm == null) tm = new JSONObject();
            Team y = new Team();
            Object sc = o.opt("score");
            y.score = sc == null || sc == JSONObject.NULL ? "" : sc instanceof JSONObject ? str((JSONObject) sc, "displayValue") : jsString(sc);
            JSONObject r0 = first(o.optJSONArray("records")), rr0 = first(o.optJSONArray("record"));
            y.rec = r0 != null ? str(r0, "summary") : rr0 != null ? str(rr0, "displayValue") : "";
            String tid = str(tm, "id"); if (tid.isEmpty()) tid = str(o, "id");
            y.id = tid.isEmpty() ? "undefined" : tid;
            y.home = "home".equals(str(o, "homeAway"));
            y.name = or(str(tm, "shortDisplayName"), str(tm, "name"), str(tm, "displayName"), "?");
            y.color = HEX6.matcher(str(tm, "color")).matches() ? "#" + str(tm, "color") : "";
            y.alt = HEX6.matcher(str(tm, "alternateColor")).matches() ? "#" + str(tm, "alternateColor") : "";
            y.full = str(tm, "displayName"); y.nick = str(tm, "name"); y.loc = str(tm, "location"); y.abbr = str(tm, "abbreviation");
            JSONObject lg0 = first(tm.optJSONArray("logos"));
            y.logo = or(str(tm, "logo"), lg0 == null ? "" : str(lg0, "href"), "");
            y.winner = o.optBoolean("winner");
            JSONObject cr = o.optJSONObject("curatedRank");
            if (cr != null) { int rk = cr.optInt("current", 99); if (rk >= 1 && rk <= 25) y.rank = rk; }
            x.teams.add(y);
        }
        sortAwayFirst(x.teams);
        JSONArray bc = c.optJSONArray("broadcasts");
        if (bc != null) for (int i = 0; i < bc.length(); i++) {
            JSONObject b = bc.optJSONObject(i); if (b == null) continue;
            JSONArray names = b.optJSONArray("names");
            if (names != null) for (int k = 0; k < names.length(); k++) addNet(x.nets, jsString(names.opt(k)));
            JSONObject media = b.optJSONObject("media");
            if (media != null) addNet(x.nets, str(media, "shortName"));
        }
        JSONArray gb = c.optJSONArray("geoBroadcasts");
        if (gb != null) for (int i = 0; i < gb.length(); i++) { JSONObject b = gb.optJSONObject(i); JSONObject media = b == null ? null : b.optJSONObject("media"); if (media != null) addNet(x.nets, str(media, "shortName")); }
        if (x.nets.isEmpty() && !str(c, "broadcast").isEmpty()) addNet(x.nets, str(c, "broadcast"));
        x.id = jsString(e.opt("id")); x.lg = lg.id; x.lgName = lg.name;
        Long at = parseDate(str(e, "date"));
        if (at == null) return null;
        x.at = at;
        x.name = str(e, "name"); x.shortName = str(e, "shortName");
        x.state = or(str(st, "state"), "pre"); x.detail = str(st, "shortDetail"); x.done = st.optBoolean("completed");
        JSONObject v = c.optJSONObject("venue");
        x.venue = v == null ? "" : str(v, "fullName");
        try { fanDetail(lg, x, e, c, stw, st); } catch (Throwable ignored) { }
        return x;
    }
    private static String ord(int n) { return n + (n % 100 >= 11 && n % 100 <= 13 ? "TH" : n % 10 == 1 ? "ST" : n % 10 == 2 ? "ND" : n % 10 == 3 ? "RD" : "TH"); }
    /** the period, clock, situation, last play and odds (whatever ESPN has for the game) */
    private static void fanDetail(Lg lg, Ev x, JSONObject e, JSONObject c, JSONObject stw, JSONObject st) {
        JSONObject wk = e.optJSONObject("week");
        if (wk != null && lg.sport.equals("football")) x.week = wk.optInt("number");
        JSONObject nt = first(c.optJSONArray("notes"));
        if (nt != null) x.note = str(nt, "headline");
        int period = stw == null ? 0 : stw.optInt("period");
        String dc = stw == null ? "" : str(stw, "displayClock"), name = str(st, "name"), sd = str(st, "shortDetail");
        if (x.state.equals("post")) x.per = sd.isEmpty() ? "FINAL" : sd.toUpperCase(Locale.US);
        else if (x.state.equals("in")) {
            String sp = lg.sport;
            if (name.contains("HALFTIME")) x.per = "HALF";
            else if (name.contains("END_PERIOD") || sd.toLowerCase(Locale.US).startsWith("end")) x.per = sd.toUpperCase(Locale.US).replace("END OF ", "END ");
            else if (sp.equals("baseball")) x.per = sd.toUpperCase(Locale.US);
            else if (sp.equals("soccer")) { x.per = dc.isEmpty() ? sd.toUpperCase(Locale.US) : dc; x.clock = period == 1 ? "1ST HALF" : period == 2 ? "2ND HALF" : period > 2 ? "EXTRA TIME" : ""; }
            else if (sp.equals("football") || (sp.equals("basketball") && !lg.college)) { x.per = period <= 0 ? "" : period <= 4 ? "Q" + period : period == 5 ? "OT" : (period - 4) + "OT"; x.clock = dc; }
            else if (sp.equals("basketball")) { x.per = period == 1 ? "1ST HALF" : period == 2 ? "2ND HALF" : period == 3 ? "OT" : (period - 2) + "OT"; x.clock = dc; }
            else if (sp.equals("hockey")) { x.per = period <= 0 ? "" : period <= 3 ? ord(period) : period == 4 ? "OT" : sd.toUpperCase(Locale.US).contains("SO") ? "SO" : (period - 3) + "OT"; x.clock = dc; }
            if (x.per.isEmpty()) x.per = sd.toUpperCase(Locale.US);
        }
        JSONObject sit = c.optJSONObject("situation");
        if (sit != null) {
            JSONObject lp = sit.optJSONObject("lastPlay");
            if (lp != null) {
                x.lastPlay = str(lp, "text");
                JSONObject pr = lp.optJSONObject("probability");
                if (pr != null && pr.has("homeWinPercentage")) x.homeWin = (float) pr.optDouble("homeWinPercentage", -1);
            }
            if (lg.sport.equals("football") && x.state.equals("in")) {
                x.down = or(str(sit, "shortDownDistanceText"), str(sit, "downDistanceText"));
                x.spot = str(sit, "possessionText");
                x.redZone = sit.optBoolean("isRedZone");
                String pid = str(sit, "possession");
                for (int i = 0; i < x.teams.size(); i++) if (!pid.isEmpty() && x.teams.get(i).id.equals(pid)) x.poss = i;
                // where the ball is, measured from the away side's goal line ("CAR 34" is 34 yards out from Carolina's)
                java.util.regex.Matcher m = Pattern.compile("^\\s*([A-Za-z&.]+)?\\s*(\\d{1,2})\\s*$").matcher(x.spot);
                if (x.teams.size() == 2 && m.matches()) {
                    int n = Integer.parseInt(m.group(2));
                    String side = m.group(1) == null ? "" : m.group(1);
                    float b = n == 50 ? 50 : side.equalsIgnoreCase(x.teams.get(0).abbr) ? n : side.equalsIgnoreCase(x.teams.get(1).abbr) ? 100 - n : -1;
                    if (b >= 0) {
                        x.ball = b;
                        int dist = sit.optInt("distance", 0);
                        if (dist > 0 && x.poss >= 0) x.firstDown = Math.max(0, Math.min(100, x.poss == 0 ? b + dist : b - dist));
                    }
                }
            }
            if (lg.sport.equals("baseball") && x.state.equals("in") && sit.has("outs")) {
                x.balls = sit.optInt("balls", 0); x.strikes = sit.optInt("strikes", 0); x.outs = sit.optInt("outs", 0);
                x.bases = new boolean[] { sit.optBoolean("onFirst"), sit.optBoolean("onSecond"), sit.optBoolean("onThird") };
                x.batter = athlete(sit.optJSONObject("batter")); x.pitcher = athlete(sit.optJSONObject("pitcher"));
            }
        }
        if (x.state.equals("pre")) {
            JSONObject od = first(c.optJSONArray("odds"));
            if (od != null) {
                String d = str(od, "details"), ou = str(od, "overUnder");
                x.line = d + (!ou.isEmpty() && !d.isEmpty() ? " · " : "") + (ou.isEmpty() ? "" : "O/U " + ou);
            }
        }
        if (x.state.equals("post")) {
            JSONObject hl = first(c.optJSONArray("headlines"));
            if (hl != null) x.headline = or(str(hl, "shortLinkText"), str(hl, "description"));
        }
    }
    private static String athlete(JSONObject o) { JSONObject a = o == null ? null : o.optJSONObject("athlete"); return a == null ? "" : or(str(a, "shortName"), str(a, "displayName")); }
    private static void addNet(List<String> nets, String n) { n = n == null ? "" : n.trim(); if (!n.isEmpty() && !nets.contains(n)) nets.add(n); }
    private static void sortAwayFirst(List<Team> l) {
        Collections.sort(l, new Comparator<Team>() { @Override public int compare(Team a, Team b) { return (a.home ? 1 : 0) - (b.home ? 1 : 0); } });
    }

    /* UFC and other fight leagues: one "card" per event (prelims → main event). */
    private List<Ev> loadCards(final Lg lg) throws Exception {
        long now = System.currentTimeMillis();
        JSONObject base = get(scoreUrl(lg, ""));
        noteLogo(lg.id, base);
        JSONObject l0 = first(base.optJSONArray("leagues"));
        JSONArray calA = l0 == null ? null : l0.optJSONArray("calendar");
        List<long[]> cal = new ArrayList<>();
        if (calA != null) for (int i = 0; i < calA.length(); i++) {
            Object o = calA.opt(i);
            if (!(o instanceof JSONObject) || str((JSONObject) o, "startDate").isEmpty()) continue;
            JSONObject co = (JSONObject) o;
            Long a = parseDate(str(co, "startDate"));
            Long en = parseDate(or(str(co, "endDate"), str(co, "startDate")));
            if (a == null || en == null) continue;
            if (en > now + lg.d0 * DAY && a < now + lg.d1 * DAY) cal.add(new long[] { a, en });
        }
        JSONArray bev = base.optJSONArray("events");
        Set<String> have = new HashSet<>();
        if (bev != null) for (int i = 0; i < bev.length(); i++) { JSONObject e = bev.optJSONObject(i); Long d = e == null ? null : parseDate(str(e, "date")); if (d != null) have.add(etYmd(d)); }
        LinkedHashSet<String> dates = new LinkedHashSet<>();
        for (long[] c : cal) dates.add(etYmd(c[0]));
        List<String> want = new ArrayList<>();
        for (String d : dates) if (!have.contains(d) && want.size() < 6) want.add(d);
        LinkedHashMap<String, JSONObject> byId = new LinkedHashMap<>();
        if (bev != null) for (int i = 0; i < bev.length(); i++) { JSONObject e = bev.optJSONObject(i); if (e != null && !jsString(e.opt("id")).isEmpty()) byId.put(jsString(e.opt("id")), e); }
        for (String d : want) {
            try {
                JSONArray ex = get(scoreUrl(lg, d)).optJSONArray("events");
                if (ex != null) for (int i = 0; i < ex.length(); i++) { JSONObject e = ex.optJSONObject(i); if (e != null && !jsString(e.opt("id")).isEmpty()) byId.put(jsString(e.opt("id")), e); }
            } catch (Throwable ignored) { }
        }
        List<Ev> out = new ArrayList<>();
        for (JSONObject e : byId.values()) {
            Ev c = card(lg, e);
            if (c != null && c.end > now + lg.d0 * DAY && c.at < now + lg.d1 * DAY) out.add(c);
        }
        Collections.sort(out, new Comparator<Ev>() { @Override public int compare(Ev a, Ev b) { return Long.compare(a.at, b.at); } });
        return out;
    }
    private static Team fighter(JSONObject x) {
        Team y = new Team();
        if (x == null) x = new JSONObject();
        JSONObject a = x.optJSONObject("athlete");
        if (a == null) a = new JSONObject();
        String full = or(str(a, "displayName"), str(a, "fullName"), "?");
        y.id = or(jsString(a.opt("id")), jsString(x.opt("id")), full);
        y.home = x.optInt("order", 0) == 2 && x.opt("order") instanceof Number;
        y.name = or(str(a, "shortName"), full);
        y.full = full;
        String[] w = full.split(" ");
        y.nick = w.length == 0 ? "" : w[w.length - 1];
        JSONObject flag = a.optJSONObject("flag");
        y.logo = flag == null ? "" : str(flag, "href");
        // the fighter's photo (ESPN's headshot; the billboard's fight poster prefers a full cut-out from TheSportsDB)
        JSONObject hs = a.optJSONObject("headshot");
        String aid = jsString(a.opt("id"));
        y.head = hs != null && str(hs, "href").startsWith("http") ? str(hs, "href") : aid.matches("\\d+") ? "https://a.espncdn.com/i/headshots/mma/players/full/" + aid + ".png" : "";
        JSONObject r0 = first(x.optJSONArray("records"));
        y.rec = r0 == null ? "" : str(r0, "summary");
        y.winner = x.optBoolean("winner");
        return y;
    }
    private static JSONObject stType(JSONObject c) { JSONObject s = c == null ? null : c.optJSONObject("status"); JSONObject ty = s == null ? null : s.optJSONObject("type"); return ty == null ? new JSONObject() : ty; }
    private static long compAt(JSONObject c, String fallback) { Long a = parseDate(or(str(c, "startDate"), str(c, "date"), fallback)); return a == null ? Long.MIN_VALUE : a; }
    static Ev card(Lg lg, JSONObject e) {
        JSONArray ca = e.optJSONArray("competitions");
        final List<JSONObject> comps = new ArrayList<>();
        if (ca != null) for (int i = 0; i < ca.length(); i++) if (ca.optJSONObject(i) != null) comps.add(ca.optJSONObject(i));
        if (comps.isEmpty()) return null;
        Collections.sort(comps, new Comparator<JSONObject>() { @Override public int compare(JSONObject a, JSONObject b) { return Long.compare(compAt(a, ""), compAt(b, "")); } });
        String nm = str(e, "name").toLowerCase(Locale.ROOT);
        // the main event: the bout named in the event title, else the last bout of the night
        JSONObject mainC = null;
        for (int i = comps.size() - 1; i >= 0 && mainC == null; i--) {
            JSONArray cs = comps.get(i).optJSONArray("competitors");
            if (cs == null || cs.length() != 2) continue;
            boolean every = true;
            for (int k = 0; k < 2; k++) {
                JSONObject x = cs.optJSONObject(k), a = x == null ? null : x.optJSONObject("athlete");
                String[] w = (a == null ? "" : str(a, "displayName")).split(" ");
                String n = w.length == 0 ? "" : w[w.length - 1].toLowerCase(Locale.ROOT);
                if (n.isEmpty() || !nm.contains(n)) every = false;
            }
            if (every) mainC = comps.get(i);
        }
        if (mainC == null) mainC = comps.get(comps.size() - 1);
        List<String> states = new ArrayList<>();
        for (JSONObject c : comps) states.add(or(str(stType(c), "state"), "pre"));
        JSONObject evSt = e.optJSONObject("status") == null ? new JSONObject() : (e.optJSONObject("status").optJSONObject("type") == null ? new JSONObject() : e.optJSONObject("status").optJSONObject("type"));
        boolean allPost = true; for (String s : states) if (!s.equals("post")) allPost = false;
        String state = states.contains("in") || (states.contains("post") && states.contains("pre")) ? "in" : allPost ? "post" : ("in".equals(str(evSt, "state")) ? "in" : "pre");
        JSONObject cur = null;
        for (JSONObject c : comps) if ("in".equals(str(stType(c), "state"))) { cur = c; break; }
        Ev x = new Ev();
        for (JSONObject c : comps) {
            JSONArray bc = c.optJSONArray("broadcasts");
            if (bc != null) for (int i = 0; i < bc.length(); i++) { JSONObject b = bc.optJSONObject(i); JSONArray names = b == null ? null : b.optJSONArray("names"); if (names != null) for (int k = 0; k < names.length(); k++) { String n = jsString(names.opt(k)); if (!n.isEmpty() && !x.nets.contains(n)) x.nets.add(n); } }
            JSONArray gb = c.optJSONArray("geoBroadcasts");
            if (gb != null) for (int i = 0; i < gb.length(); i++) { JSONObject b = gb.optJSONObject(i); JSONObject m = b == null ? null : b.optJSONObject("media"); String n = m == null ? "" : str(m, "shortName"); if (!n.isEmpty() && !x.nets.contains(n)) x.nets.add(n); }
        }
        for (int i = comps.size() - 1; i >= 0 && x.bouts.size() < 8; i--) {
            JSONObject c = comps.get(i);
            List<Team> f = new ArrayList<>();
            JSONArray cs = c.optJSONArray("competitors");
            if (cs != null) for (int k = 0; k < cs.length(); k++) f.add(fighter(cs.optJSONObject(k)));
            Team w = null; for (Team y : f) if (y.winner) { w = y; break; }
            Bout b = new Bout();
            b.a = f.size() > 0 ? f.get(0).full : "?"; b.b = f.size() > 1 ? f.get(1).full : "?";
            JSONObject ty = c.optJSONObject("type");
            b.cls = ty == null ? "" : or(str(ty, "abbreviation"), str(ty, "text"), "");
            JSONObject s = stType(c);
            b.done = "post".equals(str(s, "state")); b.live = "in".equals(str(s, "state"));
            String sd = str(s, "shortDetail");
            b.result = w == null ? "" : w.full + " won" + (!sd.isEmpty() && !sd.toLowerCase(Locale.ROOT).contains("final") ? " · " + sd : "");
            x.bouts.add(b);
        }
        JSONArray mcs = mainC.optJSONArray("competitors");
        if (mcs != null) for (int k = 0; k < mcs.length(); k++) x.teams.add(fighter(mcs.optJSONObject(k)));
        sortAwayFirst(x.teams);
        if (x.teams.size() != 2) return null;
        long at = compAt(comps.get(0), str(e, "date")), lastAt = compAt(comps.get(comps.size() - 1), str(e, "date"));
        if (at == Long.MIN_VALUE || lastAt == Long.MIN_VALUE) return null;
        x.id = jsString(e.opt("id")); x.lg = lg.id; x.lgName = or(str(e, "shortName"), lg.name);
        x.at = at; x.mainAt = lastAt; x.hasMainAt = true; x.end = lastAt + 3 * 3600000L;
        x.name = str(e, "name"); x.shortName = or(str(e, "shortName"), lg.name);
        x.state = state;
        if (state.equals("in")) {
            if (cur != null) {
                JSONArray cc = cur.optJSONArray("competitors");
                Team a = fighter(cc == null ? null : cc.optJSONObject(0)), b = fighter(cc == null ? null : cc.optJSONObject(1));
                x.detail = or(str(stType(cur), "shortDetail"), "Live") + " · " + a.nick + " vs " + b.nick;
            } else x.detail = "Live";
        } else x.detail = state.equals("post") ? "Final" : "";
        x.done = state.equals("post");
        JSONObject v = mainC.optJSONObject("venue");
        x.venue = v == null ? "" : str(v, "fullName");
        x.card = true;
        return x;
    }

    // ================================================================== matching games to your channels

    private static final Set<String> NOISE = new HashSet<>(java.util.Arrays.asList("hd", "fhd", "uhd", "sd", "4k", "hevc", "h265", "east", "west", "backup", "alt", "1080p", "720p", "1080", "720", "raw", "vip", "feed", "hq", "50fps", "60fps"));
    private static final Pattern STREAMING = Pattern.compile("(?i)^(espn\\+|peacock|prime video|amazon prime|apple tv\\+?|paramount\\+|max|netflix|mlb\\.tv|nba league pass|nhl\\.tv|nfl\\+|youtube|dazn|fubo|mls season pass|espn app|tnt sports app)$");
    private static final Map<String, String> NET_ALIAS = new HashMap<>();
    static {
        String[] a = { "fs1", "foxsports1", "fs2", "foxsports2", "nflnet", "nflnetwork", "mlbnet", "mlbnetwork", "nhlnet", "nhlnetwork", "usanet", "usanetwork",
            "cbssn", "cbssportsnetwork", "nbatv", "nbatv", "truetv", "trutv", "bsn", "bigtennetwork", "btn", "bigtennetwork", "secn", "secnetwork", "accn", "accnetwork", "pac12", "pac12network" };
        for (int i = 0; i < a.length; i += 2) NET_ALIAS.put(a[i], a[i + 1]);
    }
    private static final Pattern PFX1 = Pattern.compile("^\\s*[a-z]{2,3}\\s*[|:]\\s*"), PFX2 = Pattern.compile("^\\s*[a-z]{2,3}\\s+-\\s+"),
            BRACKETS = Pattern.compile("\\(.*?\\)|\\[.*?\\]"), NONWORD = Pattern.compile("[^a-z0-9+]+"),
            COUNTRY = Pattern.compile("^(us|usa|uk|ca|en)$"), NETWORDS = Pattern.compile("^(network|net)$");
    /** "US: ESPN 2 HD" → "espn2" (country prefix, brackets and quality tags dropped) */
    static String compact(String s) {
        String x = s.toLowerCase(Locale.ROOT);
        x = PFX1.matcher(x).replaceFirst("");
        x = PFX2.matcher(x).replaceFirst("");
        x = BRACKETS.matcher(x).replaceAll(" ");
        x = NONWORD.matcher(x).replaceAll(" ").trim();
        List<String> toks = new ArrayList<>();
        for (String tk : x.split(" ")) if (!tk.isEmpty()) toks.add(tk);
        if (toks.size() > 1 && COUNTRY.matcher(toks.get(0)).matches() && !NETWORDS.matcher(toks.get(1)).matches()) toks.remove(0);
        StringBuilder b = new StringBuilder();
        for (String tk : toks) if (!NOISE.contains(tk)) b.append(tk);
        return b.toString();
    }

    /** the channels to match against (main thread: Live TV's list, hidden categories left out) */
    private void index() {
        NativeLive lv = h.liveData();
        if (lv == null) { netIdx = new ArrayList<>(); byKey = new HashMap<>(); return; }
        String key = lv.sportsKey();
        if (key.equals(chanKey) && !netIdx.isEmpty()) return;
        chanKey = key;
        List<NativeLive.Ch> cs = lv.channelsForSports();
        List<Chan> idx = new ArrayList<>(cs.size());
        Map<String, List<NativeLive.Ch>> bk = new HashMap<>();
        for (NativeLive.Ch c : cs) {
            idx.add(new Chan(c));
            List<NativeLive.Ch> l = bk.get(c.k);
            if (l == null) { l = new ArrayList<>(); bk.put(c.k, l); }
            l.add(c);
        }
        netIdx = idx; byKey = bk; favs = lv.favSet();
        match.clear(); netCache.clear(); gIdx = null;
    }

    private static final Map<String, Pattern> wordRe = new ConcurrentHashMap<>();
    private static Pattern wordRe(String w) {
        String k = w.toLowerCase(Locale.ROOT);
        Pattern p = wordRe.get(k);
        if (p == null) { p = Pattern.compile("(^|[^a-z0-9])" + Pattern.quote(k) + "([^a-z0-9]|$)"); if (wordRe.size() < 4000) wordRe.put(k, p); }
        return p;
    }
    private static boolean mentions(String text, Team t) {
        // (a plain "is it in there at all" first: the word-edge check only for the few that are)
        for (String w : new String[] { t.nick, t.name, t.full }) if (w != null && w.length() >= 3 && text.contains(w.toLowerCase(Locale.ROOT)) && wordRe(w).matcher(text).find()) return true;
        return false;
    }
    private static final Pattern BROADCAST = Pattern.compile("^(abc|cbs|nbc|fox|cw|pbs|telemundo|univision)$"), REM_NET = Pattern.compile("^(network|net|sports|sportsnetwork|channel)$"),
            DIGITS = Pattern.compile("^\\d+$"), LOCAL = Pattern.compile("^\\d+[a-z].*");
    private final Map<String, List<Object[]>> netCache = new ConcurrentHashMap<>();
    private List<Object[]> netChans(String net) {
        List<Object[]> c = netCache.get(net);
        if (c == null) { c = netChans0(net); netCache.put(net, c); }
        return c;
    }
    private List<Object[]> netChans0(String net) {
        List<Object[]> out = new ArrayList<>();
        if (STREAMING.matcher(net.trim()).matches()) return out;
        String want = compact(net);
        if (NET_ALIAS.containsKey(want)) want = NET_ALIAS.get(want);
        if (want.isEmpty()) return out;
        boolean broadcast = BROADCAST.matcher(want).matches();
        for (Chan x : netIdx) {
            String c = x.compact;
            if (c == null) c = x.compact = compact(x.ch.n);
            String cc = c;
            if (!cc.startsWith(want)) { String a = NET_ALIAS.get(cc); if (a != null) cc = a; else continue; }
            if (!cc.startsWith(want)) continue;
            String rem = cc.substring(want.length());
            if (rem.isEmpty()) out.add(new Object[] { x.ch, 3.5 });
            else if (REM_NET.matcher(rem).matches()) out.add(new Object[] { x.ch, 3.0 });
            else if (DIGITS.matcher(rem).matches()) continue;                                // ESPN vs ESPN2, FS1 vs FS2…
            else if (broadcast && LOCAL.matcher(rem).matches()) out.add(new Object[] { x.ch, 1.5 });   // a local station: "FOX 5 New York"
            else if (rem.length() >= 3) out.add(new Object[] { x.ch, .8 });
        }
        return out;
    }
    /* The guide, searched for many teams at once. Searching the whole guide once per team took seconds for a
       tab full of games (every team's name, every channel's programmes, again and again). Instead the guide is
       read once — only your channels, the next three days — and every programme whose title has one of the
       teams' words is noted under that word; looking a team up is then one lookup. Kept for ten minutes. */
    private volatile Object[] gIdx;
    private volatile boolean idxAgain;          // {time, signature, Map<word, List<Object[]{key, start, end, title}>>}
    private final Set<String> words = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static List<String> wordsOf(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i <= s.length(); i++) {
            char c = i < s.length() ? Character.toLowerCase(s.charAt(i)) : ' ';
            if (Character.isLetterOrDigit(c)) b.append(c);
            else { if (b.length() >= 3) out.add(b.toString()); b.setLength(0); }
        }
        return out;
    }
    private void noteWords(Collection<List<Ev>> all) {
        words.add("ufc");
        for (List<Ev> l : all) for (Ev e : l) for (Team t : e.teams) for (String w : new String[] { t.nick, t.name }) words.addAll(wordsOf(w));
    }
    @SuppressWarnings("unchecked")
    private Map<String, List<Object[]>> guideIndex() {
        long now = System.currentTimeMillis();
        String sig = chanKey + "|" + words.size();
        Object[] g = gIdx;
        if (g != null && now - (Long) g[0] < 10 * 60000L && sig.equals(g[1])) return (Map<String, List<Object[]>>) g[2];
        /* (reading the guide takes up to a second: an index made in the last few hours is used straight away and a
           new one is made after, so the tab doesn't wait; the rows are made again if the new one finds more) */
        if (g != null && now - (Long) g[0] < 4 * 3600000L && h.bridge.live.epgReady() && ((String) g[1]).startsWith(chanKey + "|")) {
            if (!idxAgain) {
                idxAgain = true;
                work.execute(new Runnable() { @Override public void run() {
                    gIdx = null;
                    try { guideIndex(); } finally { idxAgain = false; }
                    match.clear();
                    main.post(new Runnable() { @Override public void run() { push(); } });
                } });
            }
            return (Map<String, List<Object[]>>) g[2];
        }
        if (!h.bridge.live.epgReady()) { h.bridge.warmEpg(); return new HashMap<>(); }
        final Map<String, List<Object[]>> idx = new HashMap<>();
        final Set<String> want = new HashSet<>(words);
        final long t0 = android.os.SystemClock.uptimeMillis();
        h.bridge.live.eachProg(new ArrayList<>(byKey.keySet()), now - 6 * 3600000L, now + 3 * DAY, new LiveTv.ProgVisitor() {
            private final StringBuilder b = new StringBuilder();
            @Override public void visit(String key, long s, long e, String title) {
                Object[] row = null;
                for (int i = 0; i <= title.length(); i++) {
                    char c = i < title.length() ? Character.toLowerCase(title.charAt(i)) : ' ';
                    if (Character.isLetterOrDigit(c)) { b.append(c); continue; }
                    if (b.length() >= 3) {
                        String w = b.toString();
                        if (want.contains(w)) {
                            if (row == null) row = new Object[] { key, s, e, title };
                            List<Object[]> l = idx.get(w);
                            if (l == null) { l = new ArrayList<>(); idx.put(w, l); }
                            if (l.isEmpty() || l.get(l.size() - 1) != row) l.add(row);
                        }
                    }
                    b.setLength(0);
                }
            }
        });
        gIdx = new Object[] { now, sig, idx };
        long ms = android.os.SystemClock.uptimeMillis() - t0;
        if (ms > 300) Diag.ev("sports: read the guide for " + want.size() + " team words in " + ms + " ms");
        return idx;
    }
    /** programmes overlapping [from, to] whose title has q: [key, start, end, title] (as the guide search gave them) */
    private JSONArray programs(String q, long from, long to) {
        JSONArray out = new JSONArray();
        try {
            List<String> ws = wordsOf(q);
            if (ws.isEmpty()) return out;
            String best = ws.get(0); for (String w : ws) if (w.length() > best.length()) best = w;
            words.add(best);
            List<Object[]> l = guideIndex().get(best);
            if (l == null) return out;
            String ql = q.trim().toLowerCase(Locale.ROOT);
            for (Object[] r : l) {
                long s = (Long) r[1], e = (Long) r[2];
                if (e <= from || s >= to || !LiveTv.has((String) r[3], ql)) continue;
                out.put(new JSONArray().put(r[0]).put(s).put(e).put(r[3]));
                if (out.length() >= 60) break;
            }
        } catch (Throwable e) { }
        return out;
    }
    private static void add(Map<String, Hit> out, NativeLive.Ch ch, double score, String why, Object[] prog) {
        Hit o = out.get(ch.u);
        if (o == null || o.score < score) {
            Hit x = new Hit(); x.ch = ch; x.score = score; x.why = why; x.prog = prog;
            out.put(ch.u, x);       // (a LinkedHashMap keeps the first place, as the page's Map did)
        }
    }
    private static final Pattern THREE = Pattern.compile("\\d{3}"), UFC_WORD = Pattern.compile("(^|[^a-z])ufc([^a-z]|$)"), PPV_WORD = Pattern.compile("(^|[^a-z])ppv([^a-z]|$)"),
            FIGHTPASS = Pattern.compile("fight ?pass"), VS = Pattern.compile("(^|[^a-z])(vs?|@|at)([^a-z]|$)");

    /** channels that should be showing this game, best first (worker thread) */
    @SuppressWarnings("unchecked")
    List<Hit> match(final Ev ev) {
        Object[] m = match.get(ev.id);
        if (m != null && System.currentTimeMillis() - (Long) m[0] < 10 * 60000) return (List<Hit>) m[1];
        if (netIdx.isEmpty()) return new ArrayList<>();
        Map<String, Hit> out = new LinkedHashMap<>();
        Map<String, List<NativeLive.Ch>> bk = byKey;
        if (ev.card) {
            // fight night: guide listings that say "UFC" around the card, UFC / PPV event channels, then networks
            Matcher nm = THREE.matcher(ev.shortName);
            final String num = nm.find() ? nm.group() : "";
            final List<String> names = new ArrayList<>();
            for (Team x : ev.teams) { String n = x.nick.toLowerCase(Locale.ROOT); if (n.length() > 2) names.add(n); }
            long mainAt = ev.hasMainAt ? ev.mainAt : ev.at;
            JSONArray rows = programs("UFC", ev.at - 2 * 3600000L, mainAt + 4 * 3600000L);
            for (int i = 0; i < rows.length(); i++) {
                JSONArray r = rows.optJSONArray(i); if (r == null) continue;
                long s = r.optLong(1), e2 = r.optLong(2); String title = r.optString(3);
                if (s > mainAt + 30 * 60000L || e2 < ev.at) continue;
                List<NativeLive.Ch> l = bk.get(r.optString(0));
                if (l != null) for (NativeLive.Ch ch : l) add(out, ch, strong(title.toLowerCase(Locale.ROOT), num, names) ? 5 : 3, "guide", new Object[] { s, e2, title });
            }
            if (Math.abs(ev.at - System.currentTimeMillis()) < DAY) for (Chan x : netIdx) {
                if (!UFC_WORD.matcher(x.raw).find() && !PPV_WORD.matcher(x.raw).find()) continue;
                add(out, x.ch, strong(x.raw, num, names) ? 4 : x.raw.contains("ufc") && !FIGHTPASS.matcher(x.raw).find() ? 2.2 : 1.3, "event", null);
            }
            for (String n : ev.nets) for (Object[] c : netChans(n)) add(out, (NativeLive.Ch) c[0], (Double) c[1], "network", null);
            List<Hit> list = new ArrayList<>(out.values());
            Collections.sort(list, new Comparator<Hit>() { @Override public int compare(Hit a, Hit b) { return Double.compare(b.score, a.score); } });
            List<Hit> every = list;
            list = keepStrong(list);
            if (list.size() > 6) list = new ArrayList<>(list.subList(0, 6));
            match.put(ev.id, new Object[] { System.currentTimeMillis(), list, every });
            return list;
        }
        // 1. the TV guide: a programme around kick-off that names the teams
        for (Team tm : ev.teams) {
            String q = tm.nick != null && tm.nick.length() >= 3 ? tm.nick : tm.name;
            if (q == null || q.length() < 3) continue;
            JSONArray rows = programs(q, ev.at - 3 * 3600000L, ev.at + 4 * 3600000L);
            for (int i = 0; i < rows.length(); i++) {
                JSONArray r = rows.optJSONArray(i); if (r == null) continue;
                long s = r.optLong(1), e = r.optLong(2); String title = r.optString(3);
                if (s > ev.at + 45 * 60000L || e < ev.at + 10 * 60000L) continue;     // must be on around the start
                String tl = title.toLowerCase(Locale.ROOT);
                boolean both = true; for (Team x : ev.teams) if (!mentions(tl, x)) both = false;
                if (!both && !mentions(tl, tm)) continue;
                List<NativeLive.Ch> l = bk.get(r.optString(0));
                if (l != null) for (NativeLive.Ch ch : l) add(out, ch, both ? 5 : 3, "guide", new Object[] { s, e, title });
            }
        }
        // 2. event channels named after the game ("NFL 03: Bills vs Jets") — they're renamed daily, so only near game time
        boolean soon = Math.abs(ev.at - System.currentTimeMillis()) < 12 * 3600000L;
        if (soon) for (Chan x : netIdx) {
            int hits = 0; for (Team tm : ev.teams) if (mentions(x.raw, tm)) hits++;
            if (hits == 2) add(out, x.ch, 4, "event", null);
            else if (hits == 1 && VS.matcher(x.raw).find()) add(out, x.ch, 2.5, "event", null);
        }
        // 3. the game's TV networks
        for (String n : ev.nets) for (Object[] c : netChans(n)) add(out, (NativeLive.Ch) c[0], (Double) c[1], "network", null);
        final Set<String> fv = favs;
        List<Hit> list = new ArrayList<>(out.values());
        Collections.sort(list, new Comparator<Hit>() { @Override public int compare(Hit a, Hit b) {
            int c = Double.compare(b.score, a.score);
            return c != 0 ? c : (fv.contains(b.ch.u) ? 1 : 0) - (fv.contains(a.ch.u) ? 1 : 0);
        } });
        List<Hit> every = list;
        list = keepStrong(list);
        if (list.size() > 6) list = new ArrayList<>(list.subList(0, 6));
        match.put(ev.id, new Object[] { System.currentTimeMillis(), list, every });
        return list;
    }
    /** how a game matched one channel, out of every channel it matched (not just the six offered): a big game is
        on more than six channels, and the one you're on dropped out, so a smaller game that only shared a
        nickname with it was taken instead (MNF's Vikings → Cleveland State's) (worker thread) */
    @SuppressWarnings("unchecked")
    Hit matchOn(Ev ev, String url) {
        List<Hit> top = match(ev);
        Object[] m = match.get(ev.id);
        List<Hit> all = m != null && m.length > 2 ? (List<Hit>) m[2] : top;
        for (Hit x : all) if (x.ch.u.equals(url)) return x;
        return null;
    }
    private static boolean strong(String t, String num, List<String> names) {
        if (!num.isEmpty() && t.contains(num)) return true;
        for (String n : names) if (t.contains(n)) return true;
        return false;
    }
    private static List<Hit> keepStrong(List<Hit> l) {
        boolean any = false; for (Hit x : l) if (x.score >= 1.2) any = true;
        if (!any) return l;
        List<Hit> out = new ArrayList<>(); for (Hit x : l) if (x.score >= 1.2) out.add(x);
        return out;
    }

    /** for Home's widget: your team's game that's on now, else the next one — {label, "Panthers at Packers", "8:15 PM · Sky Sports NFL"} — or null */
    String[] nextMine() {
        Ev best = null;
        long now = System.currentTimeMillis();
        for (List<Ev> l : ev.values()) if (l != null) for (Ev e : l) {
            if (e.teams.size() != 2 || e.state.equals("post") || !favGame(e) || e.at > now + 7 * DAY) continue;
            boolean live = e.state.equals("in");
            if (best == null || (live && !best.state.equals("in")) || (live == best.state.equals("in") && e.at < best.at)) best = e;
        }
        if (best == null) return null;
        Team a = best.teams.get(0), b = best.teams.get(1);
        String title = best.card ? best.shortName : a.name + " at " + b.name;
        if (best.state.equals("in")) return new String[] { "LIVE · " + best.lgName.toUpperCase(Locale.US), title + "   " + a.score + "–" + b.score, (best.per + " " + best.clock).trim() + (best.nets.isEmpty() ? "" : " · " + best.nets.get(0)) };
        Calendar d = Calendar.getInstance(); d.setTimeInMillis(best.at);
        Calendar t0 = Calendar.getInstance();
        String lab = sameDay(d, t0) ? (d.get(Calendar.HOUR_OF_DAY) >= 17 ? "TONIGHT" : "TODAY") : "NEXT GAME";
        return new String[] { lab, title, remWhen(best.at) + (best.nets.isEmpty() ? "" : " · " + best.nets.get(0)) };
    }

    // ================================================================== Home's billboard

    /* Today's games for Home's featured rotation: live ones first (yours before the rest), then today's still to
       come by start time (yours first again), up to max. Nothing today: your next game in the coming days. */
    List<Ev> featuredGames(int max) {
        if (prefs.length() == 0) readPrefs();
        final long now = System.currentTimeMillis();
        Calendar today = Calendar.getInstance();
        List<Ev> live = new ArrayList<>(), later = new ArrayList<>(), soon = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (String id : leagues()) for (Ev e : list(id)) {
            if (!seenIds.add(e.id)) continue;
            if (e.state.equals("in")) { live.add(e); continue; }
            if (!e.state.equals("pre") || OFF.matcher(e.detail).find()) continue;
            Calendar d = Calendar.getInstance(); d.setTimeInMillis(e.at);
            if (sameDay(d, today) && e.at > now - 3600000L) later.add(e);
            else if (e.at > now && e.at < now + 3 * DAY && favGame(e)) soon.add(e);
        }
        Comparator<Ev> byFavThenTime = new Comparator<Ev>() { @Override public int compare(Ev a, Ev b) {
            boolean fa = favGame(a), fb = favGame(b);
            if (fa != fb) return fa ? -1 : 1;
            return Long.compare(a.at, b.at);
        } };
        Collections.sort(live, byFavThenTime); Collections.sort(later, byFavThenTime); Collections.sort(soon, byFavThenTime);
        List<Ev> out = new ArrayList<>();
        for (Ev e : live) if (out.size() < max) out.add(e);
        for (Ev e : later) if (out.size() < max) out.add(e);
        if (out.isEmpty() && !soon.isEmpty()) out.add(soon.get(0));
        return out;
    }
    boolean isMine(Ev e) { return favGame(e); }
    /** Home's billboard is showing a live game: today's scores asked again (at most every 2 minutes, not while the tab does it) */
    private long quickAt;
    void quick(final Runnable done) {
        long now = System.currentTimeMillis();
        if (on || now - quickAt < 120000) return;
        quickAt = now;
        if (prefs.length() == 0) readPrefs();
        loadAll(false, true, done);
    }
    /** the latest copy of a game (the scores move on) */
    Ev find(String id) {
        for (List<Ev> l : ev.values()) if (l != null) for (Ev e : l) if (e.id.equals(id)) return e;
        return null;
    }
    /** the game in the form Live TV keeps for the channel you're watching (as the page's spGameFor gives it): picked
        from Sports, Live TV then knows which game is on even when the guide doesn't say (so no alerts about it) */
    JSONObject gameJson(Ev e) {
        try {
            Lg lg = league(e.lg);
            JSONArray ts = new JSONArray();
            for (Team t : e.teams) ts.put(new JSONObject().put("id", t.id).put("name", t.name).put("full", t.full).put("abbr", t.abbr).put("color", t.color).put("score", t.score).put("rec", t.rec).put("logo", t.logo));
            return new JSONObject().put("id", e.id).put("lg", e.lg).put("lgName", e.lgName).put("sport", lg == null ? "" : lg.sport).put("league", lg == null ? "" : lg.league)
                    .put("name", e.name).put("short", e.shortName).put("card", e.card).put("state", e.state).put("teams", ts).put("picked", true);
        } catch (Exception x) { return null; }
    }
    /** today's games (on now, or starting in the next 18 hours, or ended in the last 3), one line each, for the
        assistant: "id | league | away @ home | state | start" (main thread) */
    JSONArray gamesForAssistant() {
        JSONArray out = new JSONArray();
        long now = System.currentTimeMillis();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("EEE h:mm a", Locale.US);
        for (List<Ev> l : ev.values()) if (l != null) for (Ev e : l) {
            boolean near = e.state.equals("in") || (e.state.equals("pre") && e.at - now < 18 * 3600000L && e.at > now - 3600000L) || (e.state.equals("post") && now - e.at < 6 * 3600000L);
            if (!near || out.length() >= 60) continue;
            StringBuilder g = new StringBuilder();
            if (e.teams.size() == 2) g.append(e.teams.get(0).full.isEmpty() ? e.teams.get(0).name : e.teams.get(0).full).append(" @ ").append(e.teams.get(1).full.isEmpty() ? e.teams.get(1).name : e.teams.get(1).full);
            else g.append(e.name);
            out.put(e.id + " | " + e.lgName + " | " + g + " | " + (e.state.equals("in") ? "live" : e.state.equals("pre") ? "not started" : "ended") + " | " + f.format(new java.util.Date(e.at)));
        }
        return out;
    }
    interface GameOn { void found(JSONObject game); }
    /** which game (of those on now or about to be) the Sports tab's own matching puts on this channel (main thread in,
        answer on the main thread; null if none) — Live TV asks this when the page's matching finds nothing */
    void gameOn(final String url, final GameOn cb) {
        index();
        final List<Ev> all = new ArrayList<>();
        for (List<Ev> l : ev.values()) all.addAll(l);
        work.execute(new Runnable() { @Override public void run() {
            Ev best = null; double bestScore = -1;
            long now = System.currentTimeMillis();
            for (Ev e : all) {
                boolean near = e.state.equals("in") || (e.state.equals("pre") && e.at - now < 45 * 60000L);
                if (!near) continue;
                Hit x = matchOn(e, url);
                if (x == null) continue;
                double sc = x.score + (e.state.equals("in") ? 2 : 0);     // the best match wins, as on the page
                if (best == null || sc > bestScore) { best = e; bestScore = sc; }
            }
            final JSONObject g = best == null ? null : gameJson(best);
            main.post(new Runnable() { @Override public void run() { cb.found(g); } });
        } });
    }
    /** Watch: straight to the channel that's showing it, or the game's menu when none is found */
    void watch(final Ev e) {
        index();
        work.execute(new Runnable() { @Override public void run() {
            final List<Hit> m = e.state.equals("post") ? new ArrayList<Hit>() : match(e);
            main.post(new Runnable() { @Override public void run() {
                if (!m.isEmpty()) h.livePlayUrl(m.get(0).ch.u, gameJson(e)); else menu(e, m);
            } });
        } });
    }
    /** More: the game's page (a fight card: its menu) */
    void details(Ev e) { details(e, null); }
    /** …growing out of somewhere on the screen (the scoreboard) */
    void details(Ev e, android.graphics.RectF from) {
        Lg lg = league(e.lg);
        if (e.card || e.teams.size() != 2 || lg == null) { openEv(e); return; }
        JSONArray ts = new JSONArray();
        try {
            for (Team t : e.teams) ts.put(new JSONObject().put("name", t.name).put("abbr", t.abbr).put("id", t.id).put("color", t.color).put("alt", t.alt)
                    .put("logo", t.logo).put("score", t.score).put("rec", t.rec).put("winner", t.winner).put("lines", new JSONArray()));
        } catch (Exception ignored) { }
        h.openGamePage(lg.sport, lg.league, e.id, ts, e, from);
    }
    /** the game's menu (watch on…, remind me, game page) */
    void menuFor(Ev e) { openEv(e); }

    /* Pictures of THIS game for the billboard (worker thread), best first. Only what's about this game or its
       two teams counts (ESPN's summary also carries the league's news, the same for every game: that's what
       made every game show the same photo):
        1. the game's own story (its preview, or the recap) and videos about it (they name both teams)
        2. the latest stories about each team (home first), and the summary's stories that name one of them
        3. the stadium
       The billboard takes the first one no other game in the round is using. Kept for two hours (previews
       and recaps appear as the day goes on). */
    private static final Map<String, Object[]> ART = new ConcurrentHashMap<>();
    /** where each picture came from (for the report) */
    static final Map<String, String> ART_SRC = new ConcurrentHashMap<>();
    static List<String> gameArts(Ev e, java.io.File cacheDir) {
        String k = e.lg + ":" + e.id;
        Object[] got = ART.get(k);
        if (got != null && System.currentTimeMillis() - (Long) got[0] < 2 * 3600000L) { @SuppressWarnings("unchecked") List<String> l = (List<String>) got[1]; return l; }
        Lg lg = league(e.lg);
        /* Only pictures that are surely of THIS game: ESPN's story of the game, ESPN's videos / stories tagged
           with both teams, TheSportsDB's picture of the game; then TheSportsDB's fan art of each team (an exact
           name match only); then the stadium. (A story found by a word in its headline, or tagged with one team,
           brought pictures of other games: "Tigers", "Bulldogs"…) */
        List<String> game = new ArrayList<>(), teams = new ArrayList<>(), venue = new ArrayList<>();
        if (lg != null) {
            Set<String> ids = new HashSet<>();
            for (Team t : e.teams) if (!t.id.isEmpty()) ids.add(t.id);
            JSONObject j = null;
            if (!e.card) try { j = getParts(ESPN + "/" + lg.sport + "/" + lg.league + "/summary?event=" + e.id, "article", "videos", "news", "gameInfo"); } catch (Throwable ignored) { }
            if (j != null) {
                JSONObject art = j.optJSONObject("article");
                if (art != null) tag(game, images(art), "ESPN · the game's story");
                JSONArray vs = j.optJSONArray("videos");
                if (vs != null) for (int i = 0; i < vs.length(); i++) {
                    JSONObject v = vs.optJSONObject(i);
                    if (both(v, ids)) { String t = still(v); if (!t.isEmpty()) tag(game, java.util.Collections.singletonList(t), "ESPN · a video of the game"); }
                }
                JSONObject news = j.optJSONObject("news");
                JSONArray arts = news == null ? null : news.optJSONArray("articles");
                if (arts != null) for (int i = 0; i < arts.length(); i++) {
                    JSONObject a = arts.optJSONObject(i);
                    if (both(a, ids)) tag(game, images(a), "ESPN · a story on both teams");
                }
                JSONObject gi = j.optJSONObject("gameInfo"), ve = gi == null ? null : gi.optJSONObject("venue");
                JSONArray vi = ve == null ? null : ve.optJSONArray("images");
                if (vi != null) for (int i = 0; i < vi.length(); i++) {
                    JSONObject x = vi.optJSONObject(i);
                    String u = x == null ? "" : or(str(x, "href"), str(x, "url"));
                    if (!u.startsWith("http")) continue;
                    ART_SRC.put(u, "ESPN · the stadium");
                    if (u.contains("/day/") || u.contains("interior")) venue.add(0, u); else venue.add(u);
                }
            }
            try {
                tag(game, TheSportsDb.eventArt(lg.sport, e), "TheSportsDB · the game");
                if (!e.card) {
                    List<Team> ts = new ArrayList<>(e.teams);
                    Collections.reverse(ts);          // (home team first)
                    boolean first = true;
                    for (Team t : ts) {
                        String[][] a = TheSportsDb.teamArt(lg.sport, lg.college, t, cacheDir);
                        if (a == null) continue;
                        tag(teams, java.util.Arrays.asList(a[0]), "TheSportsDB · " + t.full + " fan art");
                        if (first && a[1].length > 0) { ART_SRC.put(a[1][0], "TheSportsDB · " + t.full + "'s stadium"); venue.add(a[1][0]); }
                        first = false;
                    }
                }
            } catch (Throwable t) { Diag.ev("TheSportsDB: " + t); }
        }
        List<String> out = new ArrayList<>();
        for (List<String> l : java.util.Arrays.asList(game, teams, venue)) for (String u : l) if (!out.contains(u)) out.add(u);
        Diag.ev("billboard pictures for " + e.shortName + ": " + game.size() + " of the game, " + teams.size() + " team fan art, " + venue.size() + " of the stadium");
        ART.put(k, new Object[] { System.currentTimeMillis(), out });
        return out;
    }
    /** each team's fan art (away, home) for the matchup art: one picture each, or "" */
    static String[] fanArt(Ev e, java.io.File cacheDir) {
        String[] out = { "", "" };
        Lg lg = league(e.lg);
        if (lg == null || e.teams.size() != 2) return out;
        for (int i = 0; i < 2; i++) {
            try {
                String[][] a = TheSportsDb.teamArt(lg.sport, lg.college, e.teams.get(i), cacheDir);
                if (a != null && a[0].length > 0) out[i] = a[0][0];
            } catch (Throwable ignored) { }
        }
        return out;
    }
    private static void tag(List<String> to, List<String> l, String src) { for (String u : l) if (!to.contains(u)) { to.add(u); if (!ART_SRC.containsKey(u)) ART_SRC.put(u, src); } }
    /** a story / video tagged with both of this game's teams */
    private static boolean both(JSONObject o, Set<String> ids) {
        if (o == null || ids.size() < 2) return false;
        Set<String> t = teamIds(o);
        for (String id : ids) if (!t.contains(id)) return false;
        return true;
    }
    private static Set<String> teamIds(JSONObject o) {
        Set<String> out = new HashSet<>();
        JSONArray cs = o == null ? null : o.optJSONArray("categories");
        if (cs != null) for (int i = 0; i < cs.length(); i++) {
            JSONObject c = cs.optJSONObject(i);
            if (c == null) continue;
            if (!"team".equals(str(c, "type")) && c.optJSONObject("team") == null) continue;
            String id = str(c, "teamId");
            if (id.isEmpty() && c.optJSONObject("team") != null) id = str(c.optJSONObject("team"), "id");
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }
    private static String still(JSONObject v) {
        if (v == null) return "";
        String t = str(v, "thumbnail");
        if (t.startsWith("http")) return t;
        JSONObject pic = v.optJSONObject("posterImages");
        JSONObject def = pic == null ? null : pic.optJSONObject("default");
        return def != null && str(def, "href").startsWith("http") ? str(def, "href") : "";
    }
    private static List<String> images(JSONObject a) {
        List<String> out = new ArrayList<>();
        String b = bigImage(a == null ? null : a.optJSONArray("images"));
        if (!b.isEmpty()) out.add(b);
        if (a != null && a.optJSONObject("video") != null) { String v = still(a.optJSONObject("video")); if (!v.isEmpty()) out.add(v); }
        return out;
    }
    private static void addAll(List<String> to, List<String> l) { for (String u : l) if (!to.contains(u)) to.add(u); }
    /** the widest landscape picture in a list of ESPN images */
    private static String bigImage(JSONArray im) {
        if (im == null) return "";
        String best = ""; int bw = 0;
        for (int i = 0; i < im.length(); i++) {
            JSONObject x = im.optJSONObject(i);
            if (x == null) continue;
            String u = or(str(x, "url"), str(x, "href"));
            if (!u.startsWith("http")) continue;
            int w = x.optInt("width"), hh = x.optInt("height");
            if (hh > 0 && w > 0 && w < hh) continue;          // (head shots and other upright pictures)
            if (w == 0) w = 1;
            if (w > bw) { bw = w; best = u; }
        }
        return best;
    }

    // ================================================================== rows

    private static final Pattern OFF = Pattern.compile("(?i)postponed|canceled|cancelled|delayed|suspended");
    String when(Ev e) {
        if (e.state.equals("in")) return "LIVE · " + e.detail;
        if (e.state.equals("post")) return e.detail.isEmpty() ? "Final" : e.detail;
        if (OFF.matcher(e.detail).find()) return e.detail;
        return remWhen(e.at);
    }
    /** "Today 7:30 PM", "Tomorrow …", "Sat …" (as the page's remWhen) */
    String remWhen(long at) {
        Calendar d = Calendar.getInstance(); d.setTimeInMillis(at);
        Calendar today = Calendar.getInstance(), tom = Calendar.getInstance(); tom.add(Calendar.DAY_OF_MONTH, 1);
        String day = sameDay(d, today) ? "Today" : sameDay(d, tom) ? "Tomorrow" : new java.text.SimpleDateFormat("EEE", Locale.US).format(d.getTime());
        return day + " " + Reminders.time(h, at);
    }
    private static boolean sameDay(Calendar a, Calendar b) { return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR); }
    private static int stOrder(String s) { return s.equals("in") ? 0 : s.equals("pre") ? 1 : 2; }

    private static final class Row { String id, title, note = ""; List<Ev> items = new ArrayList<>(); boolean editAtEnd, res; }

    private List<Row> buildRows(Map<String, List<Ev>> evs, List<String> lgs, Set<String> busyNow, Map<String, String> errNow, Map<String, List<Ev>> pastNow) {
        List<Ev> all = new ArrayList<>();
        for (String id : lgs) { List<Ev> l = evs.get(id); if (l != null) all.addAll(l); }
        List<Row> rows = new ArrayList<>();
        Calendar ds = Calendar.getInstance(); ds.set(Calendar.HOUR_OF_DAY, 0); ds.set(Calendar.MINUTE, 0); ds.set(Calendar.SECOND, 0); ds.set(Calendar.MILLISECOND, 0);
        long dayStart = ds.getTimeInMillis(), yest = dayStart - DAY, dayEnd = dayStart + DAY;
        List<Ev> live = new ArrayList<>();
        for (Ev e : all) if (e.state.equals("in")) live.add(e);
        Collections.sort(live, new Comparator<Ev>() { @Override public int compare(Ev a, Ev b) {
            int c = (favGame(b) ? 1 : 0) - (favGame(a) ? 1 : 0); return c != 0 ? c : Long.compare(a.at, b.at);
        } });
        if (!live.isEmpty()) { Row r = new Row(); r.id = "live"; r.title = "Live now"; r.items = new ArrayList<>(live.subList(0, Math.min(30, live.size()))); rows.add(r); }
        boolean anyFav = anyTeams(); for (String id : lgs) if (league(id).cards) anyFav = true;
        List<Ev> mine = new ArrayList<>();
        for (Ev e : all) if (favGame(e) && (!e.state.equals("post") || e.at > yest)) mine.add(e);
        Collections.sort(mine, new Comparator<Ev>() { @Override public int compare(Ev a, Ev b) {
            int c = stOrder(a.state) - stOrder(b.state);
            if (c != 0) return c;
            return a.state.equals("post") ? Long.compare(b.at, a.at) : Long.compare(a.at, b.at);
        } });
        Row mr = new Row(); mr.id = "mine"; mr.title = anyFav ? "My teams" : "My teams · pick your teams";
        mr.items = new ArrayList<>(mine.subList(0, Math.min(30, mine.size()))); mr.editAtEnd = true; rows.add(mr);
        for (String id : lgs) {
            Lg lg = league(id);
            List<Ev> l = evs.get(id); if (l == null) l = new ArrayList<>();
            List<Ev> items = new ArrayList<>();
            for (Ev e : l) if (e.at >= dayStart && e.at < dayEnd) items.add(e);
            String title = lg.name + " · Today";
            if (items.isEmpty()) {
                for (Ev e : l) if (!e.state.equals("post") && items.size() < 20) items.add(e);
                title = lg.name + " · Coming up";
            }
            if (items.isEmpty()) for (Ev e : l) if (e.at > yest) items.add(e);
            Row r = new Row(); r.id = "lg-" + id;
            if (items.isEmpty()) {
                String er = errNow.get(id);
                r.title = lg.name; r.note = busyNow.contains(id) ? "Loading…" : er != null && !er.isEmpty() ? "Couldn't load (" + er + ")" : "No games in the next few days";
                rows.add(r); continue;
            }
            Collections.sort(items, new Comparator<Ev>() { @Override public int compare(Ev a, Ev b) {
                int c = stOrder(a.state) - stOrder(b.state); return c != 0 ? c : Long.compare(a.at, b.at);
            } });
            r.title = title; r.items = new ArrayList<>(items.subList(0, Math.min(40, items.size())));
            rows.add(r);
        }
        /* Results (its own tab): each league's finished games of the last week, the newest first. After every
           other row, so those keep their places. */
        long since = System.currentTimeMillis() - (PAST_DAYS + 1) * DAY;
        for (String id : lgs) {
            Lg lg = league(id);
            LinkedHashMap<String, Ev> done = new LinkedHashMap<>();
            for (Map.Entry<String, List<Ev>> en : pastNow.entrySet()) if (en.getKey().startsWith(id + "|")) for (Ev e : en.getValue()) if (e.state.equals("post") && e.at > since) done.put(e.id, e);
            List<Ev> l = evs.get(id);
            if (l != null) for (Ev e : l) if (e.state.equals("post") && e.at > since) done.put(e.id, e);      // (the newest copy wins)
            if (done.isEmpty()) continue;
            List<Ev> items = new ArrayList<>(done.values());
            Collections.sort(items, new Comparator<Ev>() { @Override public int compare(Ev a, Ev b) { return Long.compare(b.at, a.at); } });
            Row r = new Row(); r.id = "res-" + id; r.res = true; r.title = lg.name + " · Results";
            r.items = new ArrayList<>(items.subList(0, Math.min(60, items.size())));
            rows.add(r);
        }
        return rows;
    }
    /** the day a finished game was played: Today, Yesterday, or "Sun, Nov 8" */
    private static String dayOf(long at) {
        Calendar d = Calendar.getInstance(); d.setTimeInMillis(at);
        Calendar today = Calendar.getInstance(), y = Calendar.getInstance(); y.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(d, today)) return "Today";
        if (sameDay(d, y)) return "Yesterday";
        return new java.text.SimpleDateFormat("EEE, MMM d", Locale.US).format(d.getTime());
    }

    private JSONObject itemOut(Ev e, String rowId, int c) throws Exception {
        boolean post = e.state.equals("post");
        Lg lg = league(e.lg);
        List<Hit> m = !post && (rowId.equals("live") || rowId.equals("mine") || c < 12) ? match(e) : null;
        boolean has = m != null && !m.isEmpty();
        String tv = has ? m.get(0).ch.n : !e.nets.isEmpty() && !post ? join(e.nets, 3, " · ") : "";
        StringBuilder sc = new StringBuilder();
        for (int i = 0; i < e.teams.size(); i++) { if (i > 0) sc.append('-'); sc.append(e.teams.get(i).score); }
        String when = when(e);
        JSONObject o = new JSONObject();
        String sitKey = e.down + e.spot + e.balls + e.strikes + e.outs + e.lastPlay.hashCode();
        o.put("k", e.id + ":" + e.state + ":" + sc + ":" + when + ":" + (has ? 1 : 0) + ":" + Integer.toHexString(sitKey.hashCode()));
        o.put("lg", e.lg).put("lgName", e.lgName).put("icon", lg == null ? "" : lg.icon).put("lgLogo", lgLogo(e.lg)).put("state", e.state)
         .put("when", when).put("detail", e.detail).put("at", e.at).put("card", e.card).put("venue", e.venue).put("tv", tv).put("tvOn", has);
        JSONArray nets = new JSONArray(); for (int i = 0; i < e.nets.size() && i < 4; i++) nets.put(e.nets.get(i));
        o.put("nets", nets);
        if (post) o.put("day", dayOf(e.at));
        o.put("bell", !post && has && Reminders.has(h, m.get(0).ch.u, e.at));
        JSONArray teams = new JSONArray();
        for (Team x : e.teams) teams.put(new JSONObject().put("id", x.id).put("name", x.name).put("full", x.full.isEmpty() ? x.name : x.full).put("rec", x.rec).put("score", x.score)
                .put("logo", x.logo).put("color", x.color).put("alt", x.alt).put("abbr", x.abbr).put("rank", x.rank).put("winner", x.winner).put("fav", isFav(e.lg, x.id)));
        o.put("teams", teams);
        // what a fan wants to see of the game (whatever ESPN had)
        o.put("per", e.per).put("clock", e.clock).put("week", e.week).put("note", e.note).put("headline", e.headline).put("line", e.line);
        if (!e.down.isEmpty() || e.ball >= 0) o.put("down", e.down).put("spot", e.spot).put("ball", e.ball).put("first", e.firstDown).put("poss", e.poss).put("red", e.redZone);
        if (e.outs >= 0) o.put("balls", e.balls).put("strikes", e.strikes).put("outs", e.outs).put("bases", new JSONArray().put(e.bases[0]).put(e.bases[1]).put(e.bases[2])).put("batter", e.batter).put("pitcher", e.pitcher);
        if (!e.lastPlay.isEmpty()) o.put("last", e.lastPlay);
        if (e.homeWin >= 0) o.put("homeWin", e.homeWin);
        if (e.card && !e.bouts.isEmpty()) {
            Bout b = e.bouts.get(0);
            int more = e.bouts.size() - 1;
            o.put("extra", "Main event" + (b.cls.isEmpty() ? "" : " · " + b.cls) + (more > 0 ? " · " + more + " more fight" + (more > 1 ? "s" : "") : ""));
        }
        return o;
    }
    static String join(List<String> l, int max, String sep) { StringBuilder b = new StringBuilder(); for (int i = 0; i < l.size() && i < max; i++) { if (i > 0) b.append(sep); b.append(l.get(i)); } return b.toString(); }

    private int pushTok;
    private boolean loggedOnce;
    /** the rows, worked out on the worker and handed to the tab */
    private void push() {
        if (!on) return;
        index();
        final List<String> lgs = leagues();
        final Map<String, List<Ev>> evs = new HashMap<>(ev);
        final Set<String> busyNow = new HashSet<>(busy);
        final Map<String, String> errNow = new HashMap<>(errs);
        final Map<String, Long> tNow = new HashMap<>(t);
        final Map<String, List<Ev>> pastNow = new HashMap<>(pastDays);
        final int tok = ++pushTok;
        final long t0 = android.os.SystemClock.uptimeMillis();
        work.execute(new Runnable() { @Override public void run() {
            noteWords(evs.values());
            final List<List<Ev>> shown = new ArrayList<>();
            String json;
            try {
                JSONObject data = new JSONObject();
                JSONArray rowsOut = new JSONArray();
                boolean setup = lgs.isEmpty();
                if (setup) {
                    rowsOut.put(new JSONObject().put("title", "").put("note", "").put("items", new JSONArray().put(editItem())));
                    shown.add(Collections.<Ev>singletonList(null));
                } else for (Row r : buildRows(evs, lgs, busyNow, errNow, pastNow)) {
                    JSONArray items = new JSONArray();
                    List<Ev> sh = new ArrayList<>();
                    for (int c = 0; c < r.items.size(); c++) { items.put(itemOut(r.items.get(c), r.id, c)); sh.add(r.items.get(c)); }
                    if (r.editAtEnd) { items.put(editItem()); sh.add(null); }
                    rowsOut.put(new JSONObject().put("title", r.title).put("note", r.note).put("items", items).put("res", r.res));
                    shown.add(sh);
                }
                long tmax = 0; int failed = 0, live = 0; boolean anyBusy = false;
                for (String id : lgs) {
                    Long x = tNow.get(id); if (x != null && x > tmax) tmax = x;
                    String er = errNow.get(id); if (er != null && !er.isEmpty()) failed++;
                    if (busyNow.contains(id)) anyBusy = true;
                    List<Ev> l = evs.get(id); if (l != null) for (Ev e : l) if (e.state.equals("in")) live++;
                }
                String updated = tmax > 0 ? "Updated " + Reminders.time(h, tmax) + (failed > 0 ? " · " + failed + " couldn't load" : "")
                        : anyBusy ? "Loading…" : failed > 0 ? "Couldn't reach ESPN" : "Loading…";
                data.put("setup", setup).put("live", live).put("updated", updated).put("rows", rowsOut);
                json = data.toString();
            } catch (Throwable e) { Diag.ev("sports: rows failed · " + e); return; }
            final String js = json;
            final long ms = android.os.SystemClock.uptimeMillis() - t0;
            main.post(new Runnable() { @Override public void run() {
                if (tok != pushTok || !on) return;
                shownRows = shown;
                h.setSports(js);
                save(js);
                if (!loggedOnce) { loggedOnce = true; Diag.ev("sports: rows made by the app (" + shown.size() + " rows)"); }
                if (ms > 300) Diag.ev("sports: rows took " + ms + " ms");
            } });
        } });
    }
    private JSONObject editItem() throws Exception {
        return new JSONObject().put("edit", true).put("label", anyTeams() ? "Edit sports & teams" : "Pick your teams").put("k", "edit");
    }

    // ================================================================== a game's menu

    /** OK on a game (by its row and column as handed to the tab) */
    void open(int r, int c) {
        if (r < 0 || r >= shownRows.size()) return;
        List<Ev> row = shownRows.get(r);
        if (c < 0 || c >= row.size()) return;
        Ev e = row.get(c);
        if (e == null) { h.page("spPickOpen()"); return; }
        openEv(e);
    }
    /** OK on the scoreboard: the game's page, growing out of it (a fight card: its menu) */
    void openPage(int r, int c, android.graphics.RectF from) {
        if (r < 0 || r >= shownRows.size()) return;
        List<Ev> row = shownRows.get(r);
        if (c < 0 || c >= row.size()) return;
        Ev e = row.get(c);
        if (e == null) { h.page("spPickOpen()"); return; }
        details(e, from);
    }
    /** what a game's menu offers, for the game's page: watch on each channel showing it (the first `watch` of
        them), remind me / cancel the reminder; and a note when it isn't on any of your channels */
    interface Actions { void got(List<String> labels, List<Runnable> runs, int watch, String note); }
    void actions(Ev game, final Actions cb) {
        Ev now = find(game.id);
        final Ev e = now != null ? now : game;
        index();
        work.execute(new Runnable() { @Override public void run() {
            final List<Hit> m = e.state.equals("post") ? new ArrayList<Hit>() : match(e);
            main.post(new Runnable() { @Override public void run() {
                List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
                int watch = 0;
                for (final Hit x : m) {
                    if (watch >= 2) break;
                    l.add("Watch on " + x.ch.n); a.add(new Runnable() { @Override public void run() { h.livePlayUrl(x.ch.u, gameJson(e)); } });
                    watch++;
                }
                if (e.state.equals("pre") && !m.isEmpty() && e.teams.size() == 2) {
                    final NativeLive.Ch best = m.get(0).ch;
                    if (Reminders.has(h, best.u, e.at)) { l.add("Cancel reminder"); a.add(new Runnable() { @Override public void run() { Reminders.remove(h, Reminders.id(best.u, e.at), false); push(); } }); }
                    else {
                        l.add("Remind me at " + Reminders.time(h, e.at));
                        final String title = e.teams.get(0).name + " @ " + e.teams.get(1).name;
                        a.add(new Runnable() { @Override public void run() { Reminders.add(h, best.u, best.n, NativeLive.chNum(best), e.at, e.at + 3 * 3600000L, title); push(); } });
                    }
                }
                String note = "";
                if (m.isEmpty() && !e.state.equals("post")) note = !e.nets.isEmpty() ? "Not found in your channels · TV: " + join(e.nets, 99, ", ") : "No TV listing yet";
                cb.got(l, a, watch, note);
            } });
        } });
    }
    /** a fantasy player's game (by ESPN's id) */
    void openGame(String id) {
        for (List<Ev> l : ev.values()) for (Ev e : l) if (e.id.equals(id)) { openEv(e); return; }
        h.toast("Add the NFL in Sports & teams to watch games from here");
    }
    /** a game from elsewhere (a team's page in Standings): its menu, as on the scoreboard */
    void openEvent(Ev e) { openEv(e); }
    private void openEv(final Ev e) {
        index();
        work.execute(new Runnable() { @Override public void run() {
            final List<Hit> m = e.state.equals("post") ? new ArrayList<Hit>() : match(e);
            main.post(new Runnable() { @Override public void run() { menu(e, m); } });
        } });
    }
    private void menu(final Ev e, final List<Hit> m) {
        List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
        final Runnable nothing = new Runnable() { @Override public void run() { } };
        for (final Hit x : m) { l.add("Watch on " + x.ch.n + ("guide".equals(x.why) ? "  ·  in the guide" : "")); a.add(new Runnable() { @Override public void run() { h.livePlayUrl(x.ch.u, gameJson(e)); } }); }
        if (e.state.equals("pre") && !m.isEmpty()) {
            final NativeLive.Ch best = m.get(0).ch;
            final boolean has = Reminders.has(h, best.u, e.at);
            if (has) { l.add("Cancel reminder"); a.add(new Runnable() { @Override public void run() { Reminders.remove(h, Reminders.id(best.u, e.at), false); push(); } }); }
            else {
                l.add("Remind me at " + Reminders.time(h, e.at));
                final long end = e.card ? e.end : e.at + 3 * 3600000L;
                final String title = e.card ? (e.name.isEmpty() ? e.shortName : e.name) : e.teams.get(0).name + " @ " + e.teams.get(1).name;
                a.add(new Runnable() { @Override public void run() { Reminders.add(h, best.u, best.n, NativeLive.chNum(best), e.at, end, title); push(); } });
            }
        }
        if (!e.card && e.teams.size() == 2) {
            l.add(e.state.equals("pre") ? "Game page · preview" : "Game page · box score, leaders, plays");
            a.add(new Runnable() { @Override public void run() {
                Lg lg = league(e.lg);
                if (lg == null) return;
                JSONArray ts = new JSONArray();
                try {
                    for (Team t : e.teams) ts.put(new JSONObject().put("name", t.name).put("abbr", t.abbr).put("id", t.id).put("color", t.color).put("alt", t.alt)
                            .put("logo", t.logo).put("score", t.score).put("rec", t.rec).put("winner", t.winner).put("lines", new JSONArray()));
                } catch (Exception ignored) { }
                h.openGamePage(lg.sport, lg.league, e.id, ts, e, null);
            } });
        }
        if (m.isEmpty() && !e.state.equals("post")) {
            l.add(!e.nets.isEmpty() ? (e.card ? "Not found in your channels · " : "Not found in your channels · TV: ") + join(e.nets, 99, ", ") : "No TV listing yet"); a.add(nothing);
        }
        String title;
        if (e.card) {
            for (int i = 0; i < e.bouts.size(); i++) {
                Bout b = e.bouts.get(i);
                l.add((i == 0 ? "Main event" : b.cls.isEmpty() ? "Bout" : b.cls) + (i == 0 && !b.cls.isEmpty() ? " · " + b.cls : "") + ":  " + b.a + " vs " + b.b
                        + (b.live ? "  ·  LIVE" : !b.result.isEmpty() ? "  ·  " + b.result : ""));
                a.add(nothing);
            }
            if (!e.venue.isEmpty()) { l.add(e.venue); a.add(nothing); }
            Lg lg = league(e.lg);
            title = (lg == null ? "" : lg.icon + " ") + (e.name.isEmpty() ? e.shortName : e.name) + " · " + when(e);
        } else {
            if (e.state.equals("post")) { l.add("Final" + (e.venue.isEmpty() ? "" : " · " + e.venue)); a.add(nothing); }
            Team away = e.teams.get(0), home = e.teams.get(1);
            boolean pre = e.state.equals("pre");
            title = (away.name + " " + (pre ? "@" : away.score + " –") + " " + (pre ? "" : home.score + " ") + home.name + " · " + when(e)).replaceAll("\\s+", " ");
        }
        if (l.isEmpty()) return;
        h.showMenu(l, a, null, 1f, title);
    }

    // ================================================================== small helpers

    /** a value as the page's String(x) would give it ("" for none) */
    static String jsString(Object o) {
        if (o == null || o == JSONObject.NULL) return "";
        if (o instanceof Double) { double d = (Double) o; if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) return String.valueOf((long) d); }
        return String.valueOf(o);
    }
    /** a field as text, "" when it's missing or null (never "null") */
    static String str(JSONObject o, String k) { return o == null ? "" : jsString(o.opt(k)); }
    private static String or(String... v) { for (String s : v) if (s != null && !s.isEmpty()) return s; return v.length == 0 ? "" : v[v.length - 1]; }
    private static JSONObject first(JSONArray a) { return a == null || a.length() == 0 ? null : a.optJSONObject(0); }

    private static final Pattern ISO = Pattern.compile("^(\\d{4})-(\\d\\d)-(\\d\\d)(?:[T ](\\d\\d):(\\d\\d)(?::(\\d\\d)(?:\\.(\\d+))?)?)?\\s*(Z|[+-]\\d\\d:?\\d\\d)?$");
    /** ESPN's dates ("2026-10-26T00:20Z", with or without seconds); null when it isn't one (as the page's NaN) */
    static Long parseDate(String s) {
        if (s == null) return null;
        Matcher m = ISO.matcher(s.trim());
        if (!m.matches()) return null;
        boolean timeGiven = m.group(4) != null;
        String z = m.group(8);
        TimeZone tz;
        if (z == null) tz = timeGiven ? TimeZone.getDefault() : TimeZone.getTimeZone("UTC");     // (as Date.parse: a date alone is UTC, a time without a zone is local)
        else if (z.equals("Z")) tz = TimeZone.getTimeZone("UTC");
        else tz = TimeZone.getTimeZone("GMT" + (z.contains(":") ? z : z.substring(0, 3) + ":" + z.substring(3)));
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)),
              timeGiven ? Integer.parseInt(m.group(4)) : 0, timeGiven ? Integer.parseInt(m.group(5)) : 0, m.group(6) != null ? Integer.parseInt(m.group(6)) : 0);
        long ms = c.getTimeInMillis();
        if (m.group(7) != null) { String f = (m.group(7) + "00").substring(0, 3); ms += Integer.parseInt(f); }
        return ms;
    }
}
