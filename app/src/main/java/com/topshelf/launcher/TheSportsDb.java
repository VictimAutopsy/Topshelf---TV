package com.topshelf.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pictures for Home's billboard from TheSportsDB (thesportsdb.com, a free, community-made sports database
 * meant for apps like Kodi and Plex): a game's own picture when it has one, each team's fan art (wide photos
 * made for TV backgrounds) and the team's stadium.
 *
 * Its free key is public and has a limit of about 30 questions a minute, so what it says is kept: each
 * team's pictures for a week (on the TV), a day's games for an hour. All on a worker thread.
 */
final class TheSportsDb {
    private TheSportsDb() { }

    private static final String BASE = "https://www.thesportsdb.com/api/v1/json/";
    /* The free key. (The older "3" key is switched off for good: it answers 403.) The free key allows about 30
       questions a minute: a refused one is asked again once, a couple of seconds later, and after a second
       refusal TheSportsDB is left alone for a minute (nothing refused is remembered, so it's asked again later). */
    private static final String KEY = "123";
    private static volatile long quietUntil;

    private static int logged;
    private static JSONObject ask(String path) {
        String why = "";
        if (System.currentTimeMillis() < quietUntil) return null;
        for (int t = 0; t < 2; t++) {
            try {
                JSONObject j = SportsData.get(BASE + KEY + "/" + path);
                if (j != null) {
                    if (logged < 25) { logged++; Diag.ev("TheSportsDB " + path.split("\\?")[0] + " (" + shortQ(path) + "): " + summary(j)); }
                    return j;
                }
            } catch (Throwable e) { why = String.valueOf(e.getMessage()); }
            if (t == 0) try { Thread.sleep(2500); } catch (InterruptedException ie) { break; }
        }
        quietUntil = System.currentTimeMillis() + 60000;
        if (logged < 25) { logged++; Diag.ev("TheSportsDB " + path.split("\\?")[0] + " (" + shortQ(path) + ") failed: " + why + " · resting a minute"); }
        return null;
    }
    private static String shortQ(String path) { int q = path.indexOf('?'); String x = q < 0 ? "" : path.substring(q + 1); try { x = java.net.URLDecoder.decode(x, "UTF-8"); } catch (Exception ignored) { } return x.length() > 60 ? x.substring(0, 60) : x; }
    private static String summary(JSONObject j) {
        StringBuilder b = new StringBuilder();
        for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) { String k = it.next(); Object v = j.opt(k); b.append(k).append('=').append(v instanceof JSONArray ? ((JSONArray) v).length() + " found" : v == JSONObject.NULL ? "none" : "…").append(' '); }
        return b.toString().trim();
    }

    static String sportName(String espnSport) {
        if (espnSport.equals("football")) return "American Football";
        if (espnSport.equals("basketball")) return "Basketball";
        if (espnSport.equals("baseball")) return "Baseball";
        if (espnSport.equals("hockey")) return "Ice Hockey";
        if (espnSport.equals("soccer")) return "Soccer";
        if (espnSport.equals("mma")) return "Fighting";
        return "";
    }
    private static String norm(String s) { return s == null ? "" : s.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", ""); }
    private static boolean wide(String u) { return u != null && u.startsWith("http"); }

    // ================================================================== a team's pictures

    private static final Map<String, String[][]> TEAMS = new ConcurrentHashMap<>();
    private static JSONObject disk;

    /** {fan art (in today's order), stadium} for a team; null when TheSportsDB doesn't know it */
    static String[][] teamArt(String espnSport, boolean college, SportsData.Team t, File dir) {
        String sport = sportName(espnSport);
        if (sport.isEmpty() || t.full.isEmpty()) return null;
        String k = sport + "|" + norm(t.full);
        String[][] got = TEAMS.get(k);
        if (got == null) {
            got = fromDisk(dir, k);
            if (got == null) {
                got = lookTeam(sport, t);
                if (got == null) return null;               // (TheSportsDB didn't answer: asked again next time, not remembered as "none")
                toDisk(dir, k, got);
            }
            // (kept in memory only when found: "nothing" is asked again after a few hours, from the disk copy's age)
            if (got[0].length > 0 || got[1].length > 0) TEAMS.put(k, got);
        }
        if (got[0].length == 0 && got[1].length == 0) return null;
        // a different order each day, so a team you see often doesn't always bring the same picture
        List<String> fan = new ArrayList<>();
        Collections.addAll(fan, got[0]);
        Calendar c = Calendar.getInstance();
        Collections.shuffle(fan, new Random(c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR) + k.hashCode()));
        return new String[][] { fan.toArray(new String[0]), got[1] };
    }

    private static String[][] lookTeam(String sport, SportsData.Team t) {
        List<String> fan = new ArrayList<>(), ground = new ArrayList<>();
        try {
            JSONObject j = ask("searchteams.php?t=" + URLEncoder.encode(t.full, "UTF-8"));
            if (j == null) return null;
            JSONArray a = j.optJSONArray("teams");
            if (a == null || a.length() == 0) { j = ask("searchteams.php?t=" + URLEncoder.encode(t.full.replace(' ', '_'), "UTF-8")); a = j == null ? null : j.optJSONArray("teams"); }
            JSONObject best = null;
            // only the team itself: its name, or one of its other names, exactly (a near name was another team)
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.optJSONObject(i);
                if (x == null || !sport.equalsIgnoreCase(x.optString("strSport"))) continue;
                if (norm(x.optString("strTeam")).equals(norm(t.full))) { best = x; break; }
                for (String alt : x.optString("strTeamAlternate", "").split(",")) if (!alt.trim().isEmpty() && norm(alt).equals(norm(t.full))) best = x;
                if (best != null) break;
            }
            if (best != null) {
                for (String f : new String[] { "strFanart1", "strFanart2", "strFanart3", "strFanart4", "strTeamFanart1", "strTeamFanart2", "strTeamFanart3", "strTeamFanart4" }) {
                    String u = best.optString(f, "");
                    if (wide(u) && !fan.contains(u)) fan.add(u);
                }
                for (String f : new String[] { "strStadiumThumb", "strStadiumThumbnail" }) {
                    String u = best.optString(f, "");
                    if (wide(u)) { ground.add(u); break; }
                }
            }
        } catch (Throwable ignored) { }
        return new String[][] { fan.toArray(new String[0]), ground.toArray(new String[0]) };
    }

    private static synchronized String[][] fromDisk(File dir, String k) {
        try {
            if (disk == null) {
                File f = new File(dir, "tsdb-teams2.json");
                disk = new JSONObject();
                if (f.exists()) {
                    byte[] b = new byte[(int) f.length()];
                    FileInputStream in = new FileInputStream(f); int n = 0; while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; } in.close();
                    disk = new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8));
                }
            }
            JSONObject o = disk.optJSONObject(k);
            if (o == null) return null;
            long age = System.currentTimeMillis() - o.optLong("at");
            boolean empty = o.optJSONArray("fan") == null || o.optJSONArray("fan").length() == 0;
            if (age > 7 * SportsData.DAY || (empty && age > 6 * 3600000L)) return null;      // (nothing found: asked again after a few hours)
            return new String[][] { strs(o.optJSONArray("fan")), strs(o.optJSONArray("ground")) };
        } catch (Throwable e) { return null; }
    }
    private static synchronized void toDisk(File dir, String k, String[][] v) {
        try {
            if (disk == null) disk = new JSONObject();
            JSONArray fa = new JSONArray(), ga = new JSONArray();
            for (String u : v[0]) fa.put(u);
            for (String u : v[1]) ga.put(u);
            disk.put(k, new JSONObject().put("at", System.currentTimeMillis()).put("fan", fa).put("ground", ga));
            FileOutputStream o = new FileOutputStream(new File(dir, "tsdb-teams2.json"));
            o.write(disk.toString().getBytes(StandardCharsets.UTF_8)); o.close();
        } catch (Throwable ignored) { }
    }
    private static String[] strs(JSONArray a) {
        if (a == null) return new String[0];
        String[] out = new String[a.length()];
        for (int i = 0; i < a.length(); i++) out[i] = a.optString(i);
        return out;
    }

    // ================================================================== a game's own picture

    private static final Map<String, Object[]> DAYS = new ConcurrentHashMap<>();

    /** the game's own pictures (its thumbnail, its fan art) when TheSportsDB has the game */
    static List<String> eventArt(String espnSport, SportsData.Ev e) {
        List<String> out = new ArrayList<>();
        String sport = sportName(espnSport);
        if (sport.isEmpty() || (e.teams.size() != 2 && !e.card)) return out;
        // its date in UTC (how TheSportsDB files games), and the day before / after in case
        Calendar u = Calendar.getInstance(TimeZone.getTimeZone("UTC")); u.setTimeInMillis(e.at);
        for (int d : new int[] { 0, -1, 1 }) {
            if (!out.isEmpty()) break;
            Calendar c = (Calendar) u.clone(); c.add(Calendar.DAY_OF_MONTH, d);
            String day = String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
            JSONArray evs = day(sport, day);
            if (evs == null) continue;
            for (int i = 0; i < evs.length(); i++) {
                JSONObject x = evs.optJSONObject(i);
                if (x == null || !(e.card ? sameCard(x, e) : sameGame(x, e))) continue;
                for (String f : new String[] { "strThumb", "strFanart" }) { String p = x.optString(f, ""); if (wide(p) && !out.contains(p)) out.add(p); }
                break;
            }
        }
        return out;
    }
    private static JSONArray day(String sport, String day) {
        String k = sport + "|" + day;
        Object[] got = DAYS.get(k);
        if (got != null && System.currentTimeMillis() - (Long) got[0] < 3600000L) return (JSONArray) got[1];
        JSONArray a = null;
        try {
            JSONObject j = ask("eventsday.php?d=" + day + "&s=" + URLEncoder.encode(sport, "UTF-8"));
            a = j == null ? null : j.optJSONArray("events");
        } catch (Throwable ignored) { }
        DAYS.put(k, new Object[] { System.currentTimeMillis(), a == null ? new JSONArray() : a });
        return a;
    }
    /** both teams, by their full names (a nickname alone matched other schools' games) */
    private static boolean sameGame(JSONObject x, SportsData.Ev e) {
        String h = norm(x.optString("strHomeTeam")), a = norm(x.optString("strAwayTeam"));
        if (h.isEmpty() || a.isEmpty() || e.teams.size() != 2) return false;
        int hit = 0;
        for (SportsData.Team t : e.teams) {
            String full = norm(t.full), loc = norm(t.loc), nick = norm(t.nick);
            boolean m = (!full.isEmpty() && (h.equals(full) || a.equals(full)))
                    || (!loc.isEmpty() && !nick.isEmpty() && ((h.contains(loc) && h.contains(nick)) || (a.contains(loc) && a.contains(nick))));
            if (m) hit++;
        }
        return hit == 2;
    }
    /** a fight card: the same promotion that day, with the main event's two fighters in its name */
    private static boolean sameCard(JSONObject x, SportsData.Ev e) {
        String lg = (x.optString("strLeague") + " " + x.optString("strEvent")).toLowerCase(Locale.US);
        if (!lg.contains(e.lg.toLowerCase(Locale.US))) return false;
        String ev = norm(x.optString("strEvent"));
        int hit = 0;
        for (SportsData.Team t : e.teams) if (t.nick.length() >= 3 && ev.contains(norm(t.nick))) hit++;
        return hit >= 1;
    }

    // ================================================================== a fighter's cut-out

    /** a fighter's full cut-out picture (see-through around them), else their cut-out portrait; "" if none */
    static String fighter(String name, File dir) {
        if (name == null || name.isEmpty() || name.equals("?")) return "";
        String k = "fighter|" + norm(name);
        String[][] got = fromDisk(dir, k);
        if (got == null) {
            List<String> l = new ArrayList<>();
            try {
                JSONObject j = ask("searchplayers.php?p=" + URLEncoder.encode(name, "UTF-8"));
                JSONArray a = j == null ? null : j.optJSONArray("player");
                if (a == null || a.length() == 0) { j = ask("searchplayers.php?p=" + URLEncoder.encode(name.replace(' ', '_'), "UTF-8")); a = j == null ? null : j.optJSONArray("player"); }
                if (a != null) for (int i = 0; i < a.length(); i++) {
                    JSONObject x = a.optJSONObject(i);
                    if (x == null || !"Fighting".equalsIgnoreCase(x.optString("strSport")) || !norm(x.optString("strPlayer")).equals(norm(name))) continue;
                    for (String f : new String[] { "strRender", "strCutout" }) { String u = x.optString(f, ""); if (wide(u)) l.add(u); }
                    break;
                }
            } catch (Throwable ignored) { }
            got = new String[][] { l.toArray(new String[0]), new String[0] };
            toDisk(dir, k, got);
        }
        return got[0].length > 0 ? got[0][0] : "";
    }
}
