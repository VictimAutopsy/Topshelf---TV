package com.topshelf.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sports › Standings: each league's tables, from ESPN (the same place the scores come from).
 *
 * The leagues you follow come first in the chips across the top; under them, where the league has them, the views:
 * Division, Conference (MLB: League) and Overall. The tables have the columns that sport uses (records at home and
 * away, in the division, the last ten, point / run / goal difference, the streak…), as many as fit; clinch letters
 * after the names (explained under the tables) and eliminated teams dimmed. In a conference view the teams go in
 * playoff-seed order with the playoff line drawn (and the NBA's play-in line), and a CUT column saying how far each
 * team is above or below it; soccer tables show their zones (Champions League, relegation…) as ESPN marks them.
 *
 * ▼ goes into the tables (to your team, when one is there), ▲ ▼ ◀ ▶ move between teams, OK opens the team's page:
 * its record and place, the last ten in order, the games coming up and the latest results (OK on one: watch it on
 * your channel, remind me, the game page) and its leaders. Kept ten minutes (a team's page five).
 */
final class StandingsView extends View {
    private static final int CARD = 0xFF131A23, EDGE = 0xFF222C38, DIM = 0xFF8E99A8, GOLD = 0xFFFFC23D, OUT = 0xFF6C7684,
            WIN = 0xFF3DDC84, LOSS = 0xFFFF5A5F, LINE = 0xCCFFC23D;
    /** {id, ESPN sport, ESPN league, name} */
    private static final String[][] LEAGUES = {
        { "nfl", "football", "nfl", "NFL" }, { "nba", "basketball", "nba", "NBA" }, { "mlb", "baseball", "mlb", "MLB" },
        { "nhl", "hockey", "nhl", "NHL" }, { "wnba", "basketball", "wnba", "WNBA" }, { "cfb", "football", "college-football", "College Football" },
        { "cbb", "basketball", "mens-college-basketball", "Men's College Hoops" }, { "mls", "soccer", "usa.1", "MLS" },
        { "epl", "soccer", "eng.1", "Premier League" }, { "ucl", "soccer", "uefa.champions", "Champions League" }, { "laliga", "soccer", "esp.1", "La Liga" } };

    private final NativeHome h;
    private final float R;
    private final Typeface cond;
    private final TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rf = new RectF();

    private final List<String[]> chips = new ArrayList<>();
    private int sel;
    /** where the remote is: 0 the leagues, 1 the views, 2 a team in the tables */
    private int focus;
    private float scroll, scrollT, contentH;
    private final Map<String, Object[]> cache = new HashMap<>();          // league → {time, groups}
    private final Set<String> loading = new HashSet<>();
    private final Map<String, String> errors = new HashMap<>();
    private final Map<String, Bitmap> logos = new HashMap<>();
    private final Set<String> favs = new HashSet<>();
    private final Map<String, Integer> viewSel = new HashMap<>();
    // the tables shown (made from the groups for the league and view), and the team chosen in them
    private List<Table> shown = new ArrayList<>();
    private String shownKey = "";
    private int ti, ri;
    private final List<RectF> chipHits = new ArrayList<>(), viewHits = new ArrayList<>();
    private final List<Object[]> legend = new ArrayList<>();               // {color or 0, mark, text}

    private static final class Col {
        final String label; final String[] keys; final float w; final int prio;
        Col(String l, float w, int prio, String... k) { label = l; this.w = w; this.prio = prio; keys = k; }
    }
    private static final class Row {
        String name = "", full = "", abbr = "", logo = "", id = "", clinch = "", noteText = "";
        int noteColor;
        double w, l, tie, pts, pct, seed, rank;
        final Map<String, String> s = new HashMap<>();
    }
    private static final class Group { String name, parent; List<Row> rows = new ArrayList<>(); }
    private static final class Table {
        String name = ""; List<Row> rows = new ArrayList<>(); List<Col> cols = new ArrayList<>(); List<String[]> vals = new ArrayList<>();
        boolean seeded, seedTags; int cut, cut2;
        int[] fit; float x, y, w, hh, rowH;
    }

    StandingsView(NativeHome host) {
        super(host.getContext());
        h = host; R = host.R;
        cond = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    }

    /** the leagues: yours first (Settings › Sports & teams), then the rest */
    private void readLeagues() {
        chips.clear(); favs.clear();
        List<String> mine = new ArrayList<>();
        try {
            JSONObject sp = new JSONObject(h.getContext().getSharedPreferences(Bridge.PREFS, android.content.Context.MODE_PRIVATE).getString("ui.sports", "{}"));
            JSONArray l = sp.optJSONArray("leagues");
            if (l != null) for (int i = 0; i < l.length(); i++) mine.add(l.optString(i));
            JSONObject tm = sp.optJSONObject("teams");
            if (tm != null) for (java.util.Iterator<String> it = tm.keys(); it.hasNext(); ) {
                String lg = it.next(); JSONArray a = tm.optJSONArray(lg);
                if (a != null) for (int i = 0; i < a.length(); i++) favs.add(lg + ":" + a.optJSONObject(i).optString("id"));
            }
        } catch (Exception ignored) { }
        for (String id : mine) for (String[] lg : LEAGUES) if (lg[0].equals(id)) chips.add(lg);
        for (String[] lg : LEAGUES) if (!chips.contains(lg)) chips.add(lg);
    }

    void show() {
        String was = sel < chips.size() ? chips.get(sel)[0] : "";
        readLeagues();
        sel = 0; for (int i = 0; i < chips.size(); i++) if (chips.get(i)[0].equals(was)) sel = i;
        setVisibility(VISIBLE);
        shownKey = "";
        load();
        invalidate();
    }
    void hide() { setVisibility(GONE); focus = 0; closeTeam(); }
    boolean shownNow() { return getVisibility() == VISIBLE; }
    void focusIn() { focus = 0; invalidate(); }

    // ------------------------------------------------------------------ the views

    private String lgId() { return chips.isEmpty() ? "" : chips.get(sel)[0]; }
    /** the views a league has (none for one table, or college conferences) */
    private List<String> views() {
        List<String> v = new ArrayList<>();
        List<Group> gs = groups();
        String id = lgId();
        boolean divs = false;
        if (gs != null) { Set<String> ps = new HashSet<>(); for (Group g : gs) ps.add(g.parent); divs = ps.size() > 1 || (ps.size() == 1 && gs.size() > 1 && !ps.contains(rootName())); }
        switch (id) {
            case "nfl": case "nba": case "nhl": if (divs) v.add("Division"); v.add("Conference"); v.add("Overall"); break;
            case "mlb": if (divs) v.add("Division"); v.add("League"); v.add("Overall"); break;
            case "wnba": case "mls": v.add("Conference"); v.add("Overall"); break;
            default: break;
        }
        return v;
    }
    /** the league's own name in ESPN's answer (groups right under it are conferences, not divisions) */
    private String rootName() { Object[] c = cache.get(lgId()); return c == null || c.length < 3 ? "" : (String) c[2]; }
    private int viewIndex() {
        List<String> v = views();
        Integer i = viewSel.get(lgId());
        if (i == null) { i = 0; if (lgId().equals("nba")) i = v.indexOf("Conference"); if (i < 0) i = 0; }
        return Math.max(0, Math.min(v.size() - 1, i));
    }
    private String view() { List<String> v = views(); return v.isEmpty() ? "" : v.get(viewIndex()); }

    @SuppressWarnings("unchecked")
    private List<Group> groups() { Object[] c = cache.get(lgId()); return c == null ? null : (List<Group>) c[1]; }

    /** the tables for the league and view (made again when either changes or new standings come) */
    private void tables() {
        List<Group> gs = groups();
        Object[] c = cache.get(lgId());
        String key = lgId() + "|" + view() + "|" + (c == null ? 0 : c[0]) + "|" + getWidth();
        if (key.equals(shownKey)) return;
        shownKey = key;
        shown = new ArrayList<>(); legend.clear();
        if (gs == null || chips.isEmpty()) return;
        String[] lg = chips.get(sel);
        String v = view();
        List<Table> out = new ArrayList<>();
        if (v.equals("Overall")) {
            Table tb = new Table(); tb.name = chips.get(sel)[3];
            for (Group g : gs) tb.rows.addAll(g.rows);
            tb.seeded = sortRows(tb.rows, true);
            out.add(tb);
        } else if (v.equals("Conference") || v.equals("League")) {
            boolean merge = false; for (Group g : gs) if (!g.parent.equals(rootName())) merge = true;
            Map<String, Table> by = new LinkedHashMap<>();
            for (Group g : gs) {
                String k = merge ? g.parent : g.name;
                Table tb = by.get(k);
                if (tb == null) { tb = new Table(); tb.name = k; by.put(k, tb); }
                tb.rows.addAll(g.rows);
            }
            for (Table tb : by.values()) { tb.seeded = sortRows(tb.rows, true); out.add(tb); }
        } else {
            for (Group g : gs) { Table tb = new Table(); tb.name = g.name; tb.rows.addAll(g.rows); tb.seeded = sortRows(tb.rows, false); out.add(tb); }
        }
        // the playoff line: where the league's seeds stop (a conference's, or WNBA's whole league)
        int[] cut = cutFor(lg[0]);
        boolean confView = v.equals("Conference") || v.equals("League");
        for (Table tb : out) {
            boolean points = lg[1].equals("hockey") || lg[1].equals("soccer");
            if (cut[0] > 0 && tb.rows.size() > cut[0]) {
                boolean here = lg[0].equals("wnba") ? v.equals("Overall") : confView;
                boolean needSeeds = !lg[0].equals("wnba") && !lg[0].equals("mls");
                if (here && (tb.seeded || !needSeeds)) { tb.cut = cut[0]; tb.cut2 = cut[1] > 0 && tb.rows.size() > cut[1] ? cut[1] : 0; }
            }
            if (cut[0] > 0 && v.equals("Division") && allSeeds(tb.rows)) tb.seedTags = true;
            tb.cols = cols(lg[1], lg[0]);
            if (tb.cut > 0) tb.cols.add(new Col("CUT", 4.4f, 1, "#cut"));
            Row lead = null;
            for (Row r : tb.rows) if (lead == null || (r.w - r.l) > (lead.w - lead.l)) lead = r;
            for (int i = 0; i < tb.rows.size(); i++) {
                Row r = tb.rows.get(i);
                String[] vs = new String[tb.cols.size()];
                for (int k = 0; k < tb.cols.size(); k++) {
                    String ck = tb.cols.get(k).keys[0];
                    if (ck.equals("#gb")) vs[k] = lead == null ? "" : gb(lead, r);
                    else if (ck.equals("#cut")) vs[k] = toCut(tb, i, points);
                    else { String s = r.s.get(tb.cols.get(k).label); vs[k] = s == null ? "" : s; }
                }
                tb.vals.add(vs);
            }
            // a column nobody has a value in (ties in a season without any) goes
            for (int k = tb.cols.size() - 1; k >= 0; k--) {
                boolean any = false;
                for (String[] vs : tb.vals) { String x = vs[k]; if (!x.isEmpty() && !x.equals("-") && !((tb.cols.get(k).label.equals("T") || tb.cols.get(k).label.equals("OTL")) && x.equals("0"))) { any = true; break; } }
                if (!any) {
                    tb.cols.remove(k);
                    for (int i = 0; i < tb.vals.size(); i++) { String[] o = tb.vals.get(i), nv = new String[o.length - 1]; for (int a = 0, b = 0; a < o.length; a++) if (a != k) nv[b++] = o[a]; tb.vals.set(i, nv); }
                }
            }
            if (!tb.rows.isEmpty()) shown.add(tb);
        }
        // what the marks mean (only those shown)
        Set<String> letters = new java.util.TreeSet<>(); Map<String, Integer> zones = new LinkedHashMap<>();
        boolean anyCut = false, anyCut2 = false;
        for (Table tb : shown) {
            if (tb.cut > 0) anyCut = true; if (tb.cut2 > 0) anyCut2 = true;
            for (Row r : tb.rows) { if (!r.clinch.isEmpty()) letters.add(r.clinch); if (r.noteColor != 0 && !r.noteText.isEmpty() && !zones.containsKey(r.noteText)) zones.put(r.noteText, r.noteColor); }
        }
        for (Map.Entry<String, Integer> z : zones.entrySet()) legend.add(new Object[] { z.getValue(), "", z.getKey() });
        if (anyCut) legend.add(new Object[] { LINE, "line", lg[0].equals("nba") ? "the playoff line (top 6)" : "the playoff line" });
        if (anyCut2) legend.add(new Object[] { LINE, "dash", "the play-in line (7–10)" });
        for (String m : letters) legend.add(new Object[] { 0, m, clinchMeaning(lg[0], m) });
    }

    /** {the seeds that make the playoffs, the play-in's last seed} */
    private static int[] cutFor(String lg) {
        switch (lg) {
            case "nfl": return new int[] { 7, 0 };
            case "nba": return new int[] { 6, 10 };
            case "mlb": return new int[] { 6, 0 };
            case "nhl": return new int[] { 8, 0 };
            case "wnba": return new int[] { 8, 0 };
            case "mls": return new int[] { 9, 0 };
            default: return new int[] { 0, 0 };
        }
    }
    private static String clinchMeaning(String lg, String m) {
        switch (m) {
            case "e": return "eliminated";
            case "x": return "clinched a playoff spot";
            case "y": return lg.equals("nfl") || lg.equals("mlb") ? "clinched a wild card" : "clinched the division";
            case "z": return lg.equals("nfl") || lg.equals("mlb") ? "clinched the division" : lg.equals("nba") ? "clinched the top seed" : "clinched the conference";
            case "*": return lg.equals("nfl") ? "clinched the division and a bye" : "clinched a bye";
            case "p": return "Presidents' Trophy";
            case "pb": case "pi": return "clinched a play-in spot";
            case "w": return "clinched a wild card";
            default: return "";
        }
    }

    /** games behind the leader (wins and losses) */
    private static String gb(Row lead, Row r) {
        double g = ((lead.w - r.w) + (r.l - lead.l)) / 2;
        return Math.abs(g) < .01 ? "-" : half(g);
    }
    /** how far above (+) or below (−) the playoff line: games for a won-lost league, points for hockey and soccer */
    private static String toCut(Table tb, int i, boolean points) {
        Row me = tb.rows.get(i), ref = tb.rows.get(i < tb.cut ? tb.cut : tb.cut - 1);
        double d = points ? me.pts - ref.pts : ((me.w + me.tie / 2 - (ref.w + ref.tie / 2)) + (ref.l + ref.tie / 2 - (me.l + me.tie / 2))) / 2;
        if (Math.abs(d) < .01) return "0";
        return (d > 0 ? "+" : "−") + half(Math.abs(d));
    }
    private static String half(double g) {
        double a = Math.abs(g);
        return Math.abs(a - Math.round(a)) < .01 ? String.valueOf(Math.round(g)) : String.format(Locale.US, "%.1f", g);
    }

    /** ◀ ▶ ▲ ▼; false: ▲ from the leagues (back up to the tabs) */
    boolean move(char d) {
        if (team != null) {
            int n = teamItems().size();
            if (d == 'u' && tsel > 0) tsel--;
            else if (d == 'd' && tsel < n - 1) tsel++;
            invalidate();
            return true;
        }
        boolean hasViews = views().size() > 1;
        if (focus == 0) {
            if (d == 'l' || d == 'r') {
                int n = Math.max(0, Math.min(chips.size() - 1, sel + (d == 'r' ? 1 : -1)));
                if (n != sel) { sel = n; scroll = scrollT = 0; ti = ri = 0; shownKey = ""; load(); }
            } else if (d == 'u') { focus = 0; invalidate(); return false; }
            else if (d == 'd') { if (hasViews) focus = 1; else intoTables(); }
        } else if (focus == 1) {
            if (d == 'l' || d == 'r') {
                List<String> v = views();
                int n = Math.max(0, Math.min(v.size() - 1, viewIndex() + (d == 'r' ? 1 : -1)));
                if (n != viewIndex()) { viewSel.put(lgId(), n); shownKey = ""; scroll = scrollT = 0; ti = ri = 0; }
            } else if (d == 'u') focus = 0;
            else if (d == 'd') intoTables();
        } else {
            tables(); layout();
            if (shown.isEmpty()) { focus = hasViews ? 1 : 0; invalidate(); return true; }
            ti = Math.min(ti, shown.size() - 1);
            Table tb = shown.get(ti);
            int per = perLine();
            if (d == 'u') {
                if (ri > 0) ri--;
                else if (ti - per >= 0) { ti -= per; ri = shown.get(ti).rows.size() - 1; }
                else { focus = hasViews ? 1 : 0; scrollT = 0; }
            } else if (d == 'd') {
                if (ri < tb.rows.size() - 1) ri++;
                else if (ti + per < shown.size()) { ti += per; ri = 0; }
                else if (ti + 1 < shown.size() && (ti + 1) / per > ti / per) { ti++; ri = 0; }
            } else if (d == 'l') { if (ti % per > 0) { ti--; ri = Math.min(ri, shown.get(ti).rows.size() - 1); } }
            else if (d == 'r') { if (ti % per < per - 1 && ti + 1 < shown.size()) { ti++; ri = Math.min(ri, shown.get(ti).rows.size() - 1); } }
            follow();
        }
        invalidate();
        return true;
    }
    /** into the tables: at your team when it's in them, else at the top */
    private void intoTables() {
        tables(); layout();
        if (shown.isEmpty()) return;
        focus = 2; ti = 0; ri = 0;
        String lg = lgId();
        outer:
        for (int a = 0; a < shown.size(); a++) for (int b = 0; b < shown.get(a).rows.size(); b++) if (favs.contains(lg + ":" + shown.get(a).rows.get(b).id)) { ti = a; ri = b; break outer; }
        follow();
    }
    /** the chosen team kept in sight */
    private void follow() {
        if (focus != 2 || ti >= shown.size()) return;
        Table tb = shown.get(ti);
        float top = topY(), y0 = tb.y + 4.6f * R + ri * tb.rowH, y1 = y0 + tb.rowH;
        float viewH = getHeight() - top - R;
        if (ti < perLine() && ri < 3) { scrollT = 0; return; }
        if (y0 - scrollT < top + 1.5f * R) scrollT = Math.max(0, y0 - top - 3 * R);
        else if (y1 - scrollT > top + viewH) scrollT = Math.max(0, y1 - top - viewH + 2 * R);
        scrollT = Math.max(0, Math.min(scrollT, Math.max(0, contentH - (getHeight() - top))));
    }

    void ok() {
        if (team != null) {
            List<SportsData.Ev> it = teamItems();
            if (tsel < it.size()) h.sportsData().openEvent(it.get(tsel));
            return;
        }
        if (focus == 0) { if (views().size() > 1) focus = 1; else intoTables(); invalidate(); return; }
        if (focus == 1) { intoTables(); invalidate(); return; }
        if (ti < shown.size() && ri < shown.get(ti).rows.size()) openTeam(shown.get(ti).rows.get(ri), chips.get(sel));
    }
    /** BACK: a team's page closes, the tables go back to the leagues; false at the leagues */
    boolean back() {
        if (team != null) { closeTeam(); invalidate(); return true; }
        if (focus != 0) { focus = 0; scrollT = 0; invalidate(); return true; }
        return false;
    }

    /** a finger (phones): a league, a view, a team or a team's game; "ok" when OK should open what's under it */
    String tapAt(float x, float y) {
        if (team != null) {
            for (int i = 0; i < teamHits.size(); i++) if (teamHits.get(i).contains(x, y)) { tsel = i; invalidate(); return "ok"; }
            return null;
        }
        for (int i = 0; i < chipHits.size(); i++) if (chipHits.get(i).contains(x, y)) {
            focus = 0; if (i != sel) { sel = i; scroll = scrollT = 0; ti = ri = 0; shownKey = ""; load(); }
            invalidate(); return "done";
        }
        for (int i = 0; i < viewHits.size(); i++) if (viewHits.get(i).contains(x, y)) { focus = 1; viewSel.put(lgId(), i); shownKey = ""; scroll = scrollT = 0; ti = ri = 0; invalidate(); return "done"; }
        float cy = y + scroll;
        for (int a = 0; a < shown.size(); a++) {
            Table tb = shown.get(a);
            if (x < tb.x || x > tb.x + tb.w) continue;
            float r0 = tb.y + 4.6f * R;
            if (cy >= r0 && cy < r0 + tb.rows.size() * tb.rowH) { focus = 2; ti = a; ri = (int) ((cy - r0) / tb.rowH); invalidate(); return "ok"; }
        }
        return null;
    }

    // ------------------------------------------------------------------ data

    private void load() {
        if (chips.isEmpty()) return;
        final String[] lg = chips.get(sel);
        Object[] c = cache.get(lg[0]);
        if (c != null && System.currentTimeMillis() - (Long) c[0] < 10 * 60000L) return;
        if (!loading.add(lg[0])) return;
        errors.remove(lg[0]);
        new Thread(new Runnable() { @Override public void run() {
            List<Group> gs = null; String err = "", root = "";
            String[] urls = {
                "https://site.web.api.espn.com/apis/v2/sports/" + lg[1] + "/" + lg[2] + "/standings?region=us&lang=en&type=0&level=" + level(lg[0]),
                "https://site.api.espn.com/apis/v2/sports/" + lg[1] + "/" + lg[2] + "/standings" };
            for (String u : urls) {
                try {
                    byte[] d = HomeImages.http(u, null);
                    if (d == null) { err = "ESPN didn't answer"; continue; }
                    JSONObject j = new JSONObject(new String(d, "UTF-8"));
                    List<Group> got = new ArrayList<>();
                    collect(j, j.optString("name"), got, lg[1]);
                    if (!got.isEmpty()) { gs = got; root = j.optString("name"); break; }
                    err = "no standings yet";
                } catch (Exception e) { err = String.valueOf(e.getMessage()); }
            }
            final List<Group> fg = gs; final String fe = err, fr = root;
            Diag.ev("standings: " + lg[3] + " · " + (fg == null ? "none (" + fe + ")" : fg.size() + " groups"));
            post(new Runnable() { @Override public void run() {
                loading.remove(lg[0]);
                if (fg != null) {
                    cache.put(lg[0], new Object[] { System.currentTimeMillis(), fg, fr });
                    for (Group g : fg) for (Row r : g.rows) wantLogo(r.logo);
                } else errors.put(lg[0], fe);
                shownKey = "";
                invalidate();
            } });
        } }, "standings").start();
    }
    private void wantLogo(final String u) {
        if (u == null || u.isEmpty() || logos.containsKey(u)) return;
        logos.put(u, null);
        final int s = Math.round(2.1f * R);
        h.imgs.load("stand2:" + u, new HomeImages.Source() { @Override public byte[] bytes() throws Exception { return darkFirst(u); } }, s, s,
                new HomeImages.Done() { @Override public void done(Bitmap b) { if (b != null) { logos.put(u, b); invalidate(); } } });
    }

    /** ESPN's logo made for dark backgrounds (…/500-dark/…) when it has one, else the plain one: the plain logos
        are drawn for white pages, and dark ones (Bruins, Penguins, Kings…) all but vanished on the cards */
    static byte[] darkFirst(String u) throws Exception {
        String d = u.contains("/500/") ? u.replace("/500/", "/500-dark/") : null;
        if (d != null) { try { byte[] b = HomeImages.http(d, null); if (b != null && b.length > 200) return b; } catch (Exception ignored) { } }
        return HomeImages.http(u, null);
    }
    /** by division (NFL, NBA, MLB, NHL: their conference views are made from these), by conference (WNBA, MLS,
        college), one table (soccer) */
    private static int level(String id) { return id.equals("nfl") || id.equals("mlb") || id.equals("nhl") || id.equals("nba") ? 3 : 2; }
    /** every part of the answer with a table in it (a league, its conferences, their divisions), the deepest ones */
    private void collect(JSONObject node, String parent, List<Group> out, String sport) {
        int before = out.size();
        JSONArray ch = node.optJSONArray("children");
        if (ch != null) for (int i = 0; i < ch.length(); i++) { JSONObject c = ch.optJSONObject(i); if (c != null) collect(c, node.optString("name", parent), out, sport); }
        if (out.size() > before) return;                       // (the deeper tables are the ones shown)
        JSONObject st = node.optJSONObject("standings");
        JSONArray en = st == null ? null : st.optJSONArray("entries");
        if (en == null || en.length() == 0) return;
        Group g = new Group();
        g.name = node.optString("name", parent);
        g.parent = parent;
        List<Col> all = cols(sport, "");
        for (int i = 0; i < en.length(); i++) {
            JSONObject e = en.optJSONObject(i); if (e == null) continue;
            JSONObject tm = e.optJSONObject("team"); if (tm == null) continue;
            Row r = new Row();
            r.id = tm.optString("id");
            r.name = tm.optString("shortDisplayName", tm.optString("displayName"));
            r.full = tm.optString("displayName", r.name);
            r.abbr = tm.optString("abbreviation");
            JSONArray lg = tm.optJSONArray("logos");
            r.logo = lg != null && lg.length() > 0 ? lg.optJSONObject(0).optString("href") : "";
            JSONArray stats = e.optJSONArray("stats");
            for (Col c : all) if (!c.keys[0].startsWith("#")) r.s.put(c.label, stat(stats, c.keys));
            r.w = z(num(stats, "wins")); r.l = z(num(stats, "losses")); r.tie = z(num(stats, "ties"));
            r.pts = sport.equals("hockey") || sport.equals("soccer") ? z(num(stats, "points")) : 0; r.pct = num(stats, "winPercent"); r.seed = z(num(stats, "playoffSeed")); r.rank = z(num(stats, "rank"));
            String cl = stat(stats, new String[] { "clincher" }).trim().toLowerCase(Locale.US);
            r.clinch = cl.equals("-") || cl.length() > 3 ? "" : cl;
            JSONObject note = e.optJSONObject("note");
            if (note != null) {
                r.noteText = note.optString("description");
                try { String col = note.optString("color"); if (!col.isEmpty()) r.noteColor = Color.parseColor(col.startsWith("#") ? col : "#" + col); } catch (Exception ignored) { }
            }
            g.rows.add(r);
        }
        sortRows(g.rows, false);
        if (!g.rows.isEmpty()) out.add(g);
    }
    /** the columns, most wanted first by prio (1 always, 2 then 3 then 4 as there's room); #… are worked out here */
    private static List<Col> cols(String sport, String lg) {
        List<Col> c = new ArrayList<>();
        switch (sport) {
            case "football":
                c.add(new Col("W", 3f, 1, "wins", "W")); c.add(new Col("L", 3f, 1, "losses", "L")); c.add(new Col("T", 3f, 1, "ties", "T"));
                c.add(new Col("PCT", 4.4f, 1, "winPercent", "PCT"));
                c.add(new Col("HOME", 4.6f, 3, "home", "Home")); c.add(new Col("AWAY", 4.6f, 3, "road", "away", "Road"));
                c.add(new Col("DIV", 4.6f, 3, "vsdiv", "vs. Div.", "divisionRecord")); c.add(new Col("CONF", 4.6f, lg.equals("cfb") ? 2 : 4, "vsconf", "vs. Conf.", "conferenceRecord"));
                c.add(new Col("PF", 4f, 4, "pointsFor", "PF")); c.add(new Col("PA", 4f, 4, "pointsAgainst", "PA"));
                c.add(new Col("DIFF", 4.4f, 2, "pointDifferential", "differential", "DIFF")); c.add(new Col("STRK", 4f, 2, "streak", "STRK"));
                break;
            case "hockey":
                c.add(new Col("GP", 3.4f, 1, "gamesPlayed", "GP")); c.add(new Col("W", 3.2f, 1, "wins", "W")); c.add(new Col("L", 3.2f, 1, "losses", "L"));
                c.add(new Col("OTL", 3.6f, 1, "otLosses", "overtimeLosses", "OTLosses", "OTL")); c.add(new Col("PTS", 3.8f, 1, "points", "PTS"));
                c.add(new Col("HOME", 5.8f, 3, "home", "Home")); c.add(new Col("AWAY", 5.8f, 3, "road", "away", "Road"));
                c.add(new Col("L10", 5.8f, 2, "lasttengames", "Last Ten Games", "L10")); c.add(new Col("DIFF", 4.2f, 3, "differential", "pointDifferential", "DIFF"));
                c.add(new Col("STRK", 4f, 2, "streak", "STRK"));
                break;
            case "soccer":
                c.add(new Col("GP", 3.4f, 1, "gamesPlayed", "GP")); c.add(new Col("W", 3f, 1, "wins", "W")); c.add(new Col("D", 3f, 1, "ties", "D", "T"));
                c.add(new Col("L", 3f, 1, "losses", "L")); c.add(new Col("GF", 3.4f, 3, "pointsFor", "F", "GF")); c.add(new Col("GA", 3.4f, 3, "pointsAgainst", "A", "GA"));
                c.add(new Col("GD", 3.8f, 2, "pointDifferential", "GD")); c.add(new Col("PTS", 3.8f, 1, "points", "P", "PTS"));
                break;
            case "baseball":
                c.add(new Col("W", 3.2f, 1, "wins", "W")); c.add(new Col("L", 3.2f, 1, "losses", "L")); c.add(new Col("PCT", 4.4f, 1, "winPercent", "PCT"));
                c.add(new Col("GB", 3.8f, 1, "#gb")); c.add(new Col("HOME", 4.8f, 3, "home", "Home")); c.add(new Col("AWAY", 4.8f, 3, "road", "away", "Road"));
                c.add(new Col("L10", 4.2f, 2, "lasttengames", "Last Ten Games", "L10")); c.add(new Col("DIFF", 4.2f, 3, "pointDifferential", "differential", "DIFF"));
                c.add(new Col("STRK", 4f, 2, "streak", "STRK"));
                break;
            default:     // basketball
                c.add(new Col("W", 3.2f, 1, "wins", "W")); c.add(new Col("L", 3.2f, 1, "losses", "L")); c.add(new Col("PCT", 4.4f, 1, "winPercent", "PCT"));
                c.add(new Col("GB", 3.8f, 1, "#gb")); c.add(new Col("HOME", 4.6f, 3, "home", "Home")); c.add(new Col("AWAY", 4.6f, 3, "road", "away", "Road"));
                c.add(new Col("CONF", 4.6f, lg.equals("cbb") ? 2 : 4, "vsconf", "vs. Conf.", "conferenceRecord"));
                c.add(new Col("L10", 4.2f, 2, "lasttengames", "Last Ten Games", "L10")); c.add(new Col("DIFF", 4.4f, 3, "differential", "pointDifferential", "DIFF"));
                c.add(new Col("STRK", 4f, 2, "streak", "STRK"));
                break;
        }
        return c;
    }
    private static String stat(JSONArray stats, String[] keys) {
        if (stats == null) return "";
        for (String k : keys) for (int i = 0; i < stats.length(); i++) {
            JSONObject s = stats.optJSONObject(i); if (s == null) continue;
            if (k.equalsIgnoreCase(s.optString("name")) || k.equalsIgnoreCase(s.optString("type")) || k.equals(s.optString("abbreviation"))) {
                String v = s.optString("displayValue", s.optString("summary"));
                if (v.isEmpty() || v.equals("null")) v = s.optString("summary");
                if (!v.isEmpty() && !v.equals("null")) return v;
            }
        }
        return "";
    }
    private static double num(JSONArray st, String... keys) {
        if (st == null) return Double.NaN;
        for (String k : keys) for (int i = 0; i < st.length(); i++) { JSONObject s = st.optJSONObject(i); if (s != null && (k.equalsIgnoreCase(s.optString("name")) || k.equals(s.optString("abbreviation"))) && s.has("value") && !s.isNull("value")) return s.optDouble("value", Double.NaN); }
        return Double.NaN;
    }
    private static boolean allSeeds(List<Row> rs) { for (Row r : rs) if (r.seed <= 0) return false; return !rs.isEmpty(); }
    /** as the league ranks them: playoff seed (or rank) when every team has a different one, else points, else
        win %, then wins; true when it went by seed */
    private static boolean sortRows(List<Row> rs, boolean distinct) {
        boolean seeds = allSeeds(rs), ranks = true;
        for (Row r : rs) if (r.rank <= 0) ranks = false;
        if (seeds && distinct) { Set<Double> seen = new HashSet<>(); for (Row r : rs) if (!seen.add(r.seed)) { seeds = false; break; } }
        final int by = seeds ? 1 : ranks && !distinct ? 2 : 0;
        Collections.sort(rs, new Comparator<Row>() { @Override public int compare(Row a, Row b) {
            if (by == 1) return Double.compare(a.seed, b.seed);
            if (by == 2) return Double.compare(a.rank, b.rank);
            if (a.pts != b.pts && (a.pts > 0 || b.pts > 0)) return Double.compare(b.pts, a.pts);
            double wa = Double.isNaN(a.pct) ? 0 : a.pct, wb = Double.isNaN(b.pct) ? 0 : b.pct;
            if (wa != wb) return Double.compare(wb, wa);
            return Double.compare(b.w, a.w);
        } });
        return seeds;
    }
    private static double z(double v) { return Double.isNaN(v) ? 0 : v; }

    // ------------------------------------------------------------------ a team's page

    private static final class TeamData {
        long at; boolean loading = true; String err = "", summary = "", record = "", leadLabel = "";
        final List<SportsData.Ev> next = new ArrayList<>(), past = new ArrayList<>();
        JSONArray leaders;
    }
    private Row team;
    private String[] teamLg;
    private TeamData td;
    private int tsel;
    private Bitmap teamLogo;
    private final Map<String, TeamData> teamCache = new HashMap<>();
    private final List<RectF> teamHits = new ArrayList<>();

    private List<SportsData.Ev> teamItems() {
        List<SportsData.Ev> l = new ArrayList<>();
        if (td == null) return l;
        for (int i = 0; i < Math.min(4, td.next.size()); i++) l.add(td.next.get(i));
        for (int i = 0; i < Math.min(6, td.past.size()); i++) l.add(td.past.get(i));
        return l;
    }
    private void closeTeam() { team = null; td = null; teamLogo = null; teamHits.clear(); }

    private void openTeam(final Row r, final String[] lg) {
        team = r; teamLg = lg; tsel = 0; teamLogo = null;
        final int s = Math.round(6 * R);
        if (!r.logo.isEmpty()) h.imgs.load("standbig2:" + r.logo, new HomeImages.Source() { @Override public byte[] bytes() throws Exception { return darkFirst(r.logo); } }, s, s,
                new HomeImages.Done() { @Override public void done(Bitmap b) { if (team == r) { teamLogo = b; invalidate(); } } });
        final String key = lg[0] + ":" + r.id;
        TeamData have = teamCache.get(key);
        if (have != null && System.currentTimeMillis() - have.at < 5 * 60000L) { td = have; for (SportsData.Ev e : teamItems()) for (SportsData.Team x : e.teams) wantLogo(x.logo); invalidate(); return; }
        td = new TeamData();
        invalidate();
        Diag.ev("standings: team page · " + lg[3] + " " + r.abbr);
        new Thread(new Runnable() { @Override public void run() {
            final TeamData d = new TeamData();
            try { fill(d, r, lg); } catch (Exception e) { d.err = String.valueOf(e.getMessage()); }
            d.loading = false; d.at = System.currentTimeMillis();
            post(new Runnable() { @Override public void run() {
                teamCache.put(key, d);
                if (team != r) return;
                td = d; tsel = 0;
                for (SportsData.Ev e : teamItems()) for (SportsData.Team x : e.teams) wantLogo(x.logo);
                if (!d.err.isEmpty()) Diag.ev("standings: team page couldn't load · " + d.err);
                invalidate();
            } });
        } }, "standings-team").start();
    }
    /** ESPN's team (its place and record), its schedule (the soccer one in two: results, then fixtures), and the
        leaders from its next game's preview (the season's), else its last game */
    private static void fill(TeamData d, Row r, String[] lg) throws Exception {
        SportsData.Lg L = SportsData.league(lg[0]);
        if (L == null) { d.err = "this league has no schedule here"; return; }
        String base = SportsData.ESPN + "/" + lg[1] + "/" + lg[2] + "/teams/" + r.id;
        JSONObject info = json(base);
        JSONObject tm = info == null ? null : info.optJSONObject("team");
        if (tm != null) {
            d.summary = tm.optString("standingSummary");
            JSONObject rec = tm.optJSONObject("record");
            JSONArray items = rec == null ? null : rec.optJSONArray("items");
            if (items != null && items.length() > 0 && items.optJSONObject(0) != null) d.record = items.optJSONObject(0).optString("summary");
        }
        Map<String, SportsData.Ev> evs = new LinkedHashMap<>();
        String[] scheds = lg[1].equals("soccer") ? new String[] { base + "/schedule", base + "/schedule?fixture=true" } : new String[] { base + "/schedule" };
        for (String u : scheds) {
            JSONObject j = json(u);
            JSONArray a = j == null ? null : j.optJSONArray("events");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i); if (e == null) continue;
                SportsData.Ev x = SportsData.event(L, e);
                if (x != null && x.teams.size() == 2 && !evs.containsKey(x.id)) evs.put(x.id, x);
            }
        }
        if (evs.isEmpty() && tm == null) { d.err = "ESPN didn't answer"; return; }
        for (SportsData.Ev e : evs.values()) (e.state.equals("post") || e.done ? d.past : d.next).add(e);
        Collections.sort(d.next, new Comparator<SportsData.Ev>() { @Override public int compare(SportsData.Ev a, SportsData.Ev b) { return Long.compare(a.at, b.at); } });
        Collections.sort(d.past, new Comparator<SportsData.Ev>() { @Override public int compare(SportsData.Ev a, SportsData.Ev b) { return Long.compare(b.at, a.at); } });
        SportsData.Ev src = !d.next.isEmpty() ? d.next.get(0) : !d.past.isEmpty() ? d.past.get(0) : null;
        if (src != null) {
            JSONObject sum = json(SportsData.ESPN + "/" + lg[1] + "/" + lg[2] + "/summary?event=" + src.id);
            JSONArray ld = sum == null ? null : sum.optJSONArray("leaders");
            if (ld != null) for (int i = 0; i < ld.length(); i++) {
                JSONObject one = ld.optJSONObject(i);
                JSONObject t = one == null ? null : one.optJSONObject("team");
                if (t != null && t.optString("id").equals(r.id)) { d.leaders = one.optJSONArray("leaders"); break; }
            }
            d.leadLabel = src.state.equals("pre") ? "SEASON LEADERS" : src.state.equals("in") ? "LEADERS · THIS GAME" : "LEADERS · LAST GAME";
        }
    }
    private static JSONObject json(String u) {
        try { byte[] b = HomeImages.http(u, null); return b == null ? null : new JSONObject(new String(b, "UTF-8")); } catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------------ drawing

    private float topY() { return views().size() > 1 ? 8.4f * R : 5.2f * R; }
    private int perLine() { return shown.size() <= 1 ? 1 : 2; }
    /** where each table goes, and which of its columns fit */
    private void layout() {
        int w = getWidth();
        if (w == 0) return;
        float pad = 4 * R, gap = 1.6f * R, top = topY();
        int n = perLine();
        float cw = n == 1 ? Math.min(w - 2 * pad, 100 * R) : (w - 2 * pad - gap) / 2;
        // the rows as roomy as the screen allows with everything in sight (2.45–2.85 rem), else a set height and scrolling
        int rowsSum = 0, lines = 0, lineMax = 0;
        for (int i = 0; i < shown.size(); i++) { lineMax = Math.max(lineMax, shown.get(i).rows.size()); if (i % n == n - 1 || i == shown.size() - 1) { rowsSum += lineMax; lines++; lineMax = 0; } }
        float avail = getHeight() - top - 1.5f * R - legendH(w - 2 * pad);
        float fitH = rowsSum == 0 ? 0 : (avail - lines * 5.8f * R - (lines - 1) * gap) / rowsSum;
        float rowH = fitH >= 2.45f * R ? Math.min(2.85f * R, fitH) : 0;
        float y = top, lineH = 0;
        for (int i = 0; i < shown.size(); i++) {
            Table tb = shown.get(i);
            int col = i % n;
            tb.rowH = rowH > 0 ? rowH : tb.rows.size() > 16 ? 2.45f * R : 2.7f * R;
            tb.w = cw; tb.x = n == 1 ? (w - cw) / 2f : pad + col * (cw + gap); tb.y = y;
            tb.hh = 4.6f * R + tb.rows.size() * tb.rowH + 1.2f * R;
            // the columns that fit: the name keeps 11 rem; the least wanted go first, from the right
            float room = cw - 2.4f * R - 4.6f * R - 11 * R;
            List<Integer> keep = new ArrayList<>(); for (int k = 0; k < tb.cols.size(); k++) keep.add(k);
            for (int pr = 4; pr >= 2; pr--) {
                float sum = 0; for (int k : keep) sum += tb.cols.get(k).w * R;
                for (int k = keep.size() - 1; k >= 0 && sum > room; k--) if (tb.cols.get(keep.get(k)).prio == pr) { sum -= tb.cols.get(keep.get(k)).w * R; keep.remove(k); }
            }
            tb.fit = new int[keep.size()]; for (int k = 0; k < keep.size(); k++) tb.fit[k] = keep.get(k);
            lineH = Math.max(lineH, tb.hh);
            if (col == n - 1 || i == shown.size() - 1) { y += lineH + gap; lineH = 0; }
        }
        contentH = y - top + legendH(w - 2 * pad) + 2 * R;
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), hh = getHeight();
        float pad = 4 * R;
        boolean moving = false;
        if (Math.abs(scroll - scrollT) > .5f) { scroll += (scrollT - scroll) * .25f; moving = true; } else scroll = scrollT;
        if (team != null) { drawTeam(c, w, hh); return; }
        // the leagues
        float cy = 1.9f * R;
        t.setTypeface(cond); t.setTextSize(1.35f * R);
        float selX0 = 0, selX1;
        float[] ws = new float[chips.size()];
        for (int i = 0; i < chips.size(); i++) ws[i] = t.measureText(chips.get(i)[3]) + 2.6f * R;
        for (int i = 0; i < sel; i++) selX0 += ws[i] + .8f * R;
        selX1 = selX0 + (sel < ws.length ? ws[sel] : 0);
        float shift = Math.max(0, selX1 - (w - 2 * pad));
        chipHits.clear();
        c.save(); c.clipRect(0, 0, w, 3.8f * R);
        float x = pad - shift;
        for (int i = 0; i < chips.size(); i++) {
            boolean on = i == sel;
            rf.set(x, cy - 1.45f * R, x + ws[i], cy + 1.45f * R);
            chipHits.add(new RectF(rf));
            p.setStyle(Paint.Style.FILL); p.setColor(on ? (focus == 0 ? GOLD : 0xFFE8EDF2) : 0xFF1A222D);
            c.drawRoundRect(rf, 1.45f * R, 1.45f * R, p);
            t.setColor(on ? 0xFF0A0E13 : 0xFFC6CED8); t.setTextAlign(Paint.Align.CENTER);
            c.drawText(chips.get(i)[3], rf.centerX(), cy + .48f * R, t);
            x += ws[i] + .8f * R;
        }
        c.restore();
        t.setTextAlign(Paint.Align.LEFT);
        if (chips.isEmpty()) return;
        // the views
        viewHits.clear();
        List<String> vs = views();
        if (vs.size() > 1) {
            float vy = 5.8f * R; x = pad;
            t.setTypeface(h.fSemi); t.setTextSize(1.15f * R);
            int vi = viewIndex();
            for (int i = 0; i < vs.size(); i++) {
                boolean on = i == vi;
                float tw = t.measureText(vs.get(i)) + 2.4f * R;
                rf.set(x, vy - 1.15f * R, x + tw, vy + 1.15f * R);
                viewHits.add(new RectF(rf));
                if (on) { p.setStyle(Paint.Style.FILL); p.setColor(focus == 1 ? GOLD : 0x33FFFFFF); c.drawRoundRect(rf, 1.15f * R, 1.15f * R, p); }
                else { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1, .08f * R)); p.setColor(0x33FFFFFF); c.drawRoundRect(rf, 1.15f * R, 1.15f * R, p); p.setStyle(Paint.Style.FILL); }
                t.setColor(on && focus == 1 ? 0xFF0A0E13 : on ? Color.WHITE : 0xFFAEB7C2); t.setTextAlign(Paint.Align.CENTER);
                c.drawText(vs.get(i), rf.centerX(), vy + .42f * R, t);
                x += tw + .7f * R;
            }
            t.setTextAlign(Paint.Align.LEFT);
            if (focus == 2) {
                t.setTypeface(h.fMed); t.setTextSize(1.05f * R); t.setColor(DIM); t.setTextAlign(Paint.Align.RIGHT);
                c.drawText("OK on a team: its games and leaders", w - pad, vy + .4f * R, t);
                t.setTextAlign(Paint.Align.LEFT);
            }
        }
        String id = lgId();
        Object[] got = cache.get(id);
        float top = topY();
        if (got == null) {
            t.setTypeface(h.fMed); t.setTextSize(1.5f * R); t.setColor(DIM);
            String e = errors.get(id);
            c.drawText(e != null ? "Couldn't load " + chips.get(sel)[3] + " standings (" + e + ")" : "Loading " + chips.get(sel)[3] + " standings…", pad, top + 3 * R, t);
            if (moving) postInvalidateOnAnimation();
            return;
        }
        tables(); layout();
        c.save(); c.clipRect(0, top - 1.2f * R, w, hh); c.translate(0, -scroll);
        for (int i = 0; i < shown.size(); i++) {
            Table tb = shown.get(i);
            if (tb.y + tb.hh > scroll && tb.y < scroll + hh) drawTable(c, tb, id, focus == 2 && i == ti ? ri : -1);
        }
        float ly = 0; for (Table tb : shown) ly = Math.max(ly, tb.y + tb.hh);
        drawLegend(c, pad, ly + 1.6f * R, w - 2 * pad);
        c.restore();
        if (moving) postInvalidateOnAnimation();
    }

    private void drawTable(Canvas c, Table g, String lg, int chosen) {
        float x = g.x, y = g.y, w = g.w, rowH = g.rowH, headH = 4.6f * R, rad = 1.3f * R;
        // the card: a little lighter at the top, a hairline edge, and a band behind the title and column names
        rf.set(x, y, x + w, y + g.hh);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, y, 0, y + g.hh, 0xFF17202B, 0xFF10161E, Shader.TileMode.CLAMP));
        c.drawRoundRect(rf, rad, rad, p);
        p.setShader(null);
        c.save(); c.clipRect(x, y, x + w, y + headH - .2f * R);
        p.setColor(0x0FFFFFFF); c.drawRoundRect(rf, rad, rad, p);
        c.restore();
        p.setColor(0x14FFFFFF); c.drawRect(x, y + headH - .2f * R, x + w, y + headH - .2f * R + Math.max(1, .07f * R), p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1, .08f * R)); p.setColor(EDGE); c.drawRoundRect(rf, rad, rad, p); p.setStyle(Paint.Style.FILL);
        float in = 1.3f * R;
        // the title, with a small gold mark before it
        if (!g.name.isEmpty()) {
            p.setColor(GOLD); rf.set(x + in, y + 1f * R, x + in + .3f * R, y + 2.4f * R); c.drawRoundRect(rf, .15f * R, .15f * R, p);
            t.setTypeface(cond); t.setTextSize(1.5f * R); t.setColor(Color.WHITE); t.setTextAlign(Paint.Align.LEFT); t.setLetterSpacing(.04f);
            c.drawText(TextUtils.ellipsize(g.name.toUpperCase(Locale.US), t, w - 2 * in - R, TextUtils.TruncateAt.END).toString(), x + in + .9f * R, y + 2.25f * R, t);
            t.setLetterSpacing(0);
        }
        int[] fit = g.fit == null ? new int[0] : g.fit;
        float[] cx = new float[fit.length];
        float rx = x + w - in;
        for (int k = fit.length - 1; k >= 0; k--) { cx[k] = rx; rx -= g.cols.get(fit[k]).w * R; }
        // the column the league ranks by (points, else win %) stands out: a faint band, the figures bright and bold
        String keyCol = lg.equals("nhl") || lg.equals("mls") || lg.equals("epl") || lg.equals("ucl") || lg.equals("laliga") ? "PTS" : "PCT";
        int keyK = -1; for (int k = 0; k < fit.length; k++) if (g.cols.get(fit[k]).label.equals(keyCol)) keyK = k;
        if (keyK >= 0) {
            float kw = g.cols.get(fit[keyK]).w * R;
            p.setColor(0x0CFFFFFF); rf.set(cx[keyK] - kw + .4f * R, y + headH - .2f * R, cx[keyK] + .5f * R, y + g.hh - .6f * R); c.drawRect(rf, p);
        }
        t.setTypeface(h.fSemi); t.setTextSize(1f * R); t.setTextAlign(Paint.Align.RIGHT); t.setLetterSpacing(.06f);
        for (int k = 0; k < fit.length; k++) { Col col = g.cols.get(fit[k]); t.setColor(col.label.equals("CUT") ? LINE : k == keyK ? 0xFFE8EDF2 : DIM); c.drawText(col.label, cx[k], y + 3.95f * R, t); }
        t.setLetterSpacing(0);
        float ls = 2.1f * R, lx = x + in + 2.6f * R;
        float nameMax = rx - (lx + ls + .8f * R) - .8f * R;
        for (int i = 0; i < g.rows.size(); i++) {
            Row r = g.rows.get(i);
            float ry = y + headH + i * rowH;
            boolean fav = favs.contains(lg + ":" + r.id), out = r.clinch.equals("e"), on = i == chosen;
            boolean inNow = g.cut > 0 && i < g.cut, playIn = g.cut2 > 0 && i >= g.cut && i < g.cut2;
            if (fav) { p.setColor(0x22FFC23D); rf.set(x + .35f * R, ry + .1f * R, x + w - .35f * R, ry + rowH - .1f * R); c.drawRoundRect(rf, .7f * R, .7f * R, p); }
            if (on) {
                p.setColor(0x30FFFFFF); rf.set(x + .35f * R, ry + .1f * R, x + w - .35f * R, ry + rowH - .1f * R); c.drawRoundRect(rf, .7f * R, .7f * R, p);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(.18f * R); p.setColor(fav ? GOLD : Color.WHITE); c.drawRoundRect(rf, .7f * R, .7f * R, p); p.setStyle(Paint.Style.FILL);
            }
            // a hairline between teams (none where the playoff lines go)
            boolean lineHere = (g.cut > 0 && i == g.cut - 1) || (g.cut2 > 0 && i == g.cut2 - 1);
            if (i < g.rows.size() - 1 && !lineHere && !on && !fav) { p.setColor(0x0FFFFFFF); c.drawRect(x + in, ry + rowH - Math.max(1, .06f * R), x + w - in, ry + rowH, p); }
            if (r.noteColor != 0) { p.setColor(r.noteColor); rf.set(x + .45f * R, ry + .35f * R, x + .8f * R, ry + rowH - .35f * R); c.drawRoundRect(rf, .17f * R, .17f * R, p); }
            float base = ry + rowH / 2 + .48f * R, mid = ry + rowH / 2;
            // the place (or seed): in a playoff spot, a filled green dot behind it; in the play-in, gold
            int seedNo = g.seeded ? (int) r.seed : i + 1;
            float nx0 = x + in + 1.05f * R;
            if (inNow || playIn) { p.setColor(inNow ? 0x383DDC84 : 0x33FFC23D); c.drawCircle(nx0, mid, 1f * R, p); }
            t.setTypeface(cond); t.setTextSize(1.15f * R); t.setTextAlign(Paint.Align.CENTER);
            t.setColor(inNow ? WIN : playIn ? GOLD : DIM);
            c.drawText(String.valueOf(seedNo), nx0, mid + .42f * R, t);
            Bitmap b = logos.get(r.logo);
            if (b != null) { p.setAlpha(out ? 100 : 255); rf.set(lx, mid - ls / 2, lx + ls, mid + ls / 2); c.drawBitmap(b, null, rf, p); p.setAlpha(255); }
            // the name, then its marks: the clinch letter, and in a division table the seed it holds now
            String mark = r.clinch;
            String seedTag = g.seedTags && r.seed > 0 && r.seed <= cutFor(lg)[0] ? "#" + (int) r.seed : g.seedTags && cutFor(lg)[1] > 0 && r.seed > 0 && r.seed <= cutFor(lg)[1] ? "#" + (int) r.seed : "";
            t.setTypeface(cond); t.setTextSize(1.05f * R);
            float markW = (mark.isEmpty() ? 0 : t.measureText(mark) + .7f * R) + (seedTag.isEmpty() ? 0 : t.measureText(seedTag) + 1f * R);
            t.setTypeface(h.fSemi); t.setTextSize(1.4f * R); t.setColor(out ? OUT : fav ? GOLD : Color.WHITE); t.setTextAlign(Paint.Align.LEFT);
            String nm = TextUtils.ellipsize(r.name, t, Math.max(3 * R, nameMax - markW), TextUtils.TruncateAt.END).toString();
            float nx = lx + ls + .8f * R;
            c.drawText(nm, nx, base, t);
            float mx = nx + t.measureText(nm) + .55f * R;
            t.setTypeface(cond); t.setTextSize(1.05f * R);
            if (!mark.isEmpty()) { t.setColor(out ? OUT : WIN); c.drawText(mark, mx, base - .12f * R, t); mx += t.measureText(mark) + .7f * R; }
            if (!seedTag.isEmpty()) {
                boolean held = r.seed <= cutFor(lg)[0];
                float tw = t.measureText(seedTag);
                rf.set(mx - .35f * R, mid - .8f * R, mx + tw + .35f * R, mid + .8f * R);
                p.setColor(held ? 0x333DDC84 : 0x33FFC23D); c.drawRoundRect(rf, .8f * R, .8f * R, p);
                t.setColor(held ? WIN : GOLD); c.drawText(seedTag, mx, base - .12f * R, t);
            }
            // the figures: the ranking column bright, streaks and differences in green / red, the rest softer
            t.setTextSize(1.35f * R); t.setTextAlign(Paint.Align.RIGHT);
            String[] vs = g.vals.get(i);
            for (int k = 0; k < fit.length; k++) {
                String v = fit[k] < vs.length ? vs[fit[k]] : "";
                String lab = g.cols.get(fit[k]).label;
                int col;
                if (out) col = OUT;
                else if (lab.equals("CUT")) col = v.startsWith("+") ? WIN : v.startsWith("−") ? 0xFFFF8A8E : 0xFFDDE3EA;
                else if (lab.equals("STRK")) col = v.startsWith("W") ? 0xFF7BE3A8 : v.startsWith("L") ? 0xFFFF9196 : 0xFFB8C2CE;
                else if (lab.equals("DIFF") || lab.equals("GD")) col = v.startsWith("+") ? 0xFF7BE3A8 : v.startsWith("-") || v.startsWith("−") ? 0xFFFF9196 : 0xFFB8C2CE;
                else if (k == keyK) col = fav ? GOLD : Color.WHITE;
                else col = fav ? 0xFFFFD77A : 0xFFB8C2CE;
                t.setTypeface(k == keyK ? h.fSemi : cond);
                t.setColor(col);
                c.drawText(v, cx[k], base, t);
            }
            // the playoff line under the last seed in, the play-in line under the last seed in it
            if (g.cut > 0 && i == g.cut - 1) line(c, x + .6f * R, ry + rowH, x + w - .6f * R, false);
            if (g.cut2 > 0 && i == g.cut2 - 1) line(c, x + .6f * R, ry + rowH, x + w - .6f * R, true);
        }
        t.setTextAlign(Paint.Align.LEFT);
    }
    private void line(Canvas c, float x0, float y, float x1, boolean dashed) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2, .16f * R)); p.setColor(LINE);
        if (dashed) p.setPathEffect(new DashPathEffect(new float[] { .8f * R, .6f * R }, 0));
        c.drawLine(x0, y, x1, y, p);
        p.setPathEffect(null); p.setStyle(Paint.Style.FILL);
    }
    private float legendH(float w) { return legend.isEmpty() ? 0 : legendRows(w) * 2.2f * R + R; }
    private int legendRows(float w) {
        t.setTypeface(h.fMed); t.setTextSize(1.1f * R);
        float x = 0; int rows = 1;
        for (Object[] it : legend) { float iw = 3 * R + t.measureText((String) it[2]) + 2 * R; if (x + iw > w && x > 0) { rows++; x = 0; } x += iw; }
        return rows;
    }
    private void drawLegend(Canvas c, float x0, float y, float w) {
        if (legend.isEmpty()) return;
        t.setTextAlign(Paint.Align.LEFT);
        float x = 0, base = y + 1.4f * R;
        for (Object[] it : legend) {
            int col = (Integer) it[0]; String mark = (String) it[1], text = (String) it[2];
            t.setTypeface(h.fMed); t.setTextSize(1.1f * R);
            float iw = 3 * R + t.measureText(text) + 2 * R;
            if (x + iw > w && x > 0) { x = 0; base += 2.2f * R; }
            float lx = x0 + x;
            if (mark.equals("line") || mark.equals("dash")) line(c, lx, base - .4f * R, lx + 2.2f * R, mark.equals("dash"));
            else if (mark.isEmpty()) { p.setColor(col); rf.set(lx + .6f * R, base - 1f * R, lx + 1f * R, base + .2f * R); c.drawRect(rf, p); }
            else { t.setTypeface(cond); t.setTextSize(1.15f * R); t.setColor(mark.equals("e") ? OUT : WIN); c.drawText(mark, lx + .4f * R, base, t); }
            t.setTypeface(h.fMed); t.setTextSize(1.1f * R); t.setColor(DIM);
            c.drawText(text, lx + 3 * R, base, t);
            x += iw;
        }
    }

    private void drawTeam(Canvas c, int w, int hh) {
        float pad = 4 * R, y = 1.2f * R;
        Row r = team;
        teamHits.clear();
        // the team: logo, name, record and place, on a band across the top
        float ls = 6 * R;
        rf.set(pad - 1.2f * R, y - .6f * R, w - pad + 1.2f * R, y + ls + .6f * R);
        p.setShader(new LinearGradient(rf.left, 0, rf.right, 0, 0xFF1B2533, 0xFF10161E, Shader.TileMode.CLAMP));
        c.drawRoundRect(rf, 1.4f * R, 1.4f * R, p); p.setShader(null);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1, .08f * R)); p.setColor(EDGE); c.drawRoundRect(rf, 1.4f * R, 1.4f * R, p); p.setStyle(Paint.Style.FILL);
        if (teamLogo != null) { rf.set(pad, y, pad + ls, y + ls); c.drawBitmap(teamLogo, null, rf, p); }
        float tx = pad + ls + 1.6f * R;
        t.setTextAlign(Paint.Align.LEFT);
        t.setTypeface(h.fSemi); t.setTextSize(2.6f * R); t.setColor(Color.WHITE);
        c.drawText(TextUtils.ellipsize(r.full, t, w * .55f, TextUtils.TruncateAt.END).toString(), tx, y + 2.9f * R, t);
        String rec = td != null && !td.record.isEmpty() ? td.record : recordOf(r);
        String sub = rec + (td != null && !td.summary.isEmpty() ? "  ·  " + td.summary : "") + (r.clinch.isEmpty() || clinchMeaning(teamLg[0], r.clinch).isEmpty() ? "" : "  ·  " + clinchMeaning(teamLg[0], r.clinch));
        t.setTypeface(h.fMed); t.setTextSize(1.45f * R); t.setColor(0xFFC6CED8);
        c.drawText(TextUtils.ellipsize(sub, t, w * .6f, TextUtils.TruncateAt.END).toString(), tx, y + 5.2f * R, t);
        // the last ten, in order (oldest on the left)
        if (td != null && !td.past.isEmpty()) {
            int n = Math.min(10, td.past.size());
            float d = 1.25f * R, gap = .55f * R, x1 = w - pad, x0 = x1 - n * d - (n - 1) * gap;
            t.setTypeface(h.fSemi); t.setTextSize(1f * R); t.setColor(DIM); t.setTextAlign(Paint.Align.RIGHT);
            c.drawText("LAST " + n, x1, y + 2f * R, t);
            for (int i = 0; i < n; i++) {
                SportsData.Ev e = td.past.get(n - 1 - i);
                int res = result(e, r.id);
                p.setColor(res > 0 ? WIN : res < 0 ? LOSS : 0xFF8E99A8);
                c.drawCircle(x0 + i * (d + gap) + d / 2, y + 3.6f * R, d / 2, p);
            }
            t.setTextAlign(Paint.Align.LEFT);
        }
        float top = y + ls + 2 * R;
        if (td == null || td.loading) {
            t.setTypeface(h.fMed); t.setTextSize(1.5f * R); t.setColor(DIM);
            c.drawText("Loading " + r.name + "'s games…", pad, top + 2 * R, t);
            return;
        }
        if (!td.err.isEmpty() && td.next.isEmpty() && td.past.isEmpty()) {
            t.setTypeface(h.fMed); t.setTextSize(1.5f * R); t.setColor(DIM);
            c.drawText("Couldn't load " + r.name + "'s games (" + td.err + ")", pad, top + 2 * R, t);
            return;
        }
        float colW = (w - 2 * pad) * .6f, rx = pad + colW + 2 * R, rw = w - pad - rx;
        float rowH = 3.3f * R;
        List<SportsData.Ev> items = teamItems();
        int nNext = Math.min(4, td.next.size());
        float yy = top;
        c.save(); c.clipRect(0, top - R, w, hh);
        for (int sec = 0; sec < 2; sec++) {
            int from = sec == 0 ? 0 : nNext, to = sec == 0 ? nNext : items.size();
            head(c, sec == 0 ? "COMING UP" : "RESULTS", pad, yy + 1.2f * R);
            yy += 2.2f * R;
            if (from == to) { t.setTypeface(h.fMed); t.setTextSize(1.25f * R); t.setColor(DIM); c.drawText(sec == 0 ? "No games scheduled" : "No results yet this season", pad + R, yy + 2 * R, t); yy += rowH + R; continue; }
            for (int i = from; i < to; i++) {
                SportsData.Ev e = items.get(i);
                rf.set(pad, yy, pad + colW, yy + rowH - .4f * R);
                teamHits.add(new RectF(rf));
                boolean on = i == tsel;
                p.setColor(on ? 0x2EFFFFFF : CARD); c.drawRoundRect(rf, .8f * R, .8f * R, p);
                if (on) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(.16f * R); p.setColor(Color.WHITE); c.drawRoundRect(rf, .8f * R, .8f * R, p); p.setStyle(Paint.Style.FILL); }
                gameRow(c, e, r.id, pad, yy, colW, rowH - .4f * R);
                yy += rowH;
            }
            yy += 1.2f * R;
        }
        // the leaders
        float ly = top;
        if (td.leaders != null && td.leaders.length() > 0) {
            head(c, td.leadLabel, rx, ly + 1.2f * R);
            ly += 2.2f * R;
            int n = Math.min(6, td.leaders.length());
            for (int k = 0; k < n; k++) {
                JSONObject cat = td.leaders.optJSONObject(k);
                JSONArray who = cat == null ? null : cat.optJSONArray("leaders");
                JSONObject one = who == null ? null : who.optJSONObject(0);
                if (one == null) continue;
                JSONObject ath = one.optJSONObject("athlete");
                rf.set(rx, ly, rx + rw, ly + 4.4f * R - .4f * R);
                p.setColor(CARD); c.drawRoundRect(rf, .8f * R, .8f * R, p);
                t.setTypeface(cond); t.setTextSize(1f * R); t.setColor(DIM);
                c.drawText(cat.optString("displayName").toUpperCase(Locale.US), rx + R, ly + 1.5f * R, t);
                t.setTypeface(h.fSemi); t.setTextSize(1.35f * R); t.setColor(Color.WHITE);
                c.drawText(TextUtils.ellipsize(ath == null ? "" : ath.optString("displayName"), t, rw * .48f, TextUtils.TruncateAt.END).toString(), rx + R, ly + 3.3f * R, t);
                t.setTypeface(h.fMed); t.setTextSize(1.25f * R); t.setColor(0xFFDCE3EA); t.setTextAlign(Paint.Align.RIGHT);
                c.drawText(TextUtils.ellipsize(one.optString("displayValue"), t, rw * .48f, TextUtils.TruncateAt.END).toString(), rx + rw - R, ly + 3.3f * R, t);
                t.setTextAlign(Paint.Align.LEFT);
                ly += 4.4f * R;
            }
        }
        c.restore();
        t.setTypeface(h.fMed); t.setTextSize(1.05f * R); t.setColor(DIM); t.setTextAlign(Paint.Align.RIGHT);
        c.drawText("OK on a game: watch it, remind me, the game page  ·  BACK: the standings", w - pad, hh - 1.2f * R, t);
        t.setTextAlign(Paint.Align.LEFT);
    }
    private void head(Canvas c, String s, float x, float y) {
        p.setColor(GOLD); rf.set(x, y - 1.05f * R, x + .3f * R, y + .1f * R); c.drawRoundRect(rf, .15f * R, .15f * R, p);
        t.setTypeface(cond); t.setTextSize(1.25f * R); t.setColor(0xFFC6CED8); t.setTextAlign(Paint.Align.LEFT); t.setLetterSpacing(.08f);
        c.drawText(s, x + .8f * R, y, t);
        t.setLetterSpacing(0);
    }
    /** one game: when (or W/L), vs/@ the other team, then the score or where it's on */
    private void gameRow(Canvas c, SportsData.Ev e, String us, float x, float y, float w, float rh) {
        SportsData.Team me = null, them = null;
        for (SportsData.Team tm : e.teams) if (tm.id.equals(us)) me = tm; else them = tm;
        if (me == null) { me = e.teams.get(0); them = e.teams.get(1); }
        if (them == null) them = me;
        float base = y + rh / 2 + .45f * R, cx = x + R;
        boolean post = e.state.equals("post") || e.done, live = e.state.equals("in");
        t.setTextAlign(Paint.Align.LEFT);
        if (post) {
            int res = result(e, us);
            String b = res > 0 ? "W" : res < 0 ? "L" : e.lg.equals("epl") || e.lg.equals("mls") || e.lg.equals("ucl") || e.lg.equals("laliga") ? "D" : "T";
            rf.set(cx, y + rh / 2 - .95f * R, cx + 1.9f * R, y + rh / 2 + .95f * R);
            p.setColor(res > 0 ? WIN : res < 0 ? LOSS : 0xFF5A6472); c.drawRoundRect(rf, .45f * R, .45f * R, p);
            t.setTypeface(cond); t.setTextSize(1.2f * R); t.setColor(0xFF0A0E13); t.setTextAlign(Paint.Align.CENTER);
            c.drawText(b, rf.centerX(), base - .05f * R, t); t.setTextAlign(Paint.Align.LEFT);
            t.setTypeface(h.fMed); t.setTextSize(1.2f * R); t.setColor(DIM);
            c.drawText(new SimpleDateFormat("EEE M/d", Locale.US).format(new Date(e.at)), cx + 2.8f * R, base, t);
        } else {
            t.setTypeface(h.fSemi); t.setTextSize(1.2f * R); t.setColor(live ? LOSS : 0xFFC6CED8);
            c.drawText(TextUtils.ellipsize(h.sportsData().when(e), t, 11.5f * R, TextUtils.TruncateAt.END).toString(), cx, base, t);
        }
        float ox = x + 13.2f * R;
        t.setTypeface(h.fMed); t.setTextSize(1.2f * R); t.setColor(DIM);
        c.drawText(me.home ? "vs" : "@", ox, base, t);
        Bitmap b = logos.get(them.logo);
        float ls = 1.9f * R, lx = ox + 2.2f * R;
        if (b != null) { rf.set(lx, y + (rh - ls) / 2, lx + ls, y + (rh + ls) / 2); c.drawBitmap(b, null, rf, p); }
        String right = post || live ? me.score + "–" + them.score : SportsData.join(e.nets, 2, ", ");
        t.setTypeface(cond); t.setTextSize(1.35f * R);
        float rwid = right.isEmpty() ? 0 : t.measureText(right);
        t.setTypeface(h.fSemi); t.setTextSize(1.35f * R); t.setColor(Color.WHITE);
        c.drawText(TextUtils.ellipsize(them.name, t, Math.max(4 * R, x + w - R - rwid - 1.4f * R - (lx + ls + .7f * R)), TextUtils.TruncateAt.END).toString(), lx + ls + .7f * R, base, t);
        if (!right.isEmpty()) {
            t.setTypeface(cond); t.setTextSize(1.35f * R); t.setColor(post || live ? Color.WHITE : DIM); t.setTextAlign(Paint.Align.RIGHT);
            c.drawText(right, x + w - R, base, t); t.setTextAlign(Paint.Align.LEFT);
        }
    }
    /** 1 won, -1 lost, 0 a tie / draw (or no score) */
    private static int result(SportsData.Ev e, String us) {
        SportsData.Team me = null, them = null;
        for (SportsData.Team tm : e.teams) if (tm.id.equals(us)) me = tm; else them = tm;
        if (me == null || them == null) return 0;
        if (me.winner) return 1;
        if (them.winner) return -1;
        try { double a = Double.parseDouble(me.score), b = Double.parseDouble(them.score); return a > b ? 1 : a < b ? -1 : 0; } catch (Exception ex) { return 0; }
    }
    private static String recordOf(Row r) {
        String s = (int) r.w + "-" + (int) r.l;
        if (r.tie > 0) s += "-" + (int) r.tie;
        return s;
    }
}
