package com.topshelf.launcher;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
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
 * Sports › Standings, from ESPN (where the scores come from).
 *
 * One board per league and view, made to be read from the couch:
 *  - the leagues as text tabs on the left, the views (Division, Conference — League for MLB — and Overall) as one
 *    switch on the right;
 *  - the two conferences side by side (AFC | NFC, East | West, AL | NL), so a whole league is in sight at once:
 *    the rows take the height there is (2.3–2.95 rem) and only scroll when even that won't do (college);
 *  - a thin rail down the left edge says where a team stands: green in a playoff spot, amber in the NBA's
 *    play-in, soccer's zones in ESPN's own colours (with their key beside the table);
 *  - the column the league ranks by (PCT, or PTS) is the bright one; point / run / goal difference is a small bar
 *    either side of zero; clinch letters after the names, eliminated teams faded;
 *  - in a conference view the teams are in seed order with the playoff line (and the play-in line) as a gap with
 *    its name in it, and LINE: how far above or below it each team is;
 *  - the chosen team glows in its own colours (the one place team colour is used) and the glow glides as you
 *    move; the footer says, in a sentence, where that team stands.
 * OK opens the team's page: its colours across the top with its record, places and form, then the next game,
 * the games after it, its results (OK on any: watch on your channel, remind me, the game page) and its leaders.
 * Tables are kept ten minutes, a team's page five.
 */
final class StandingsView extends View {
    // the palette: three greys for text, two surfaces, and colour only where it means something
    private static final int INK = 0xFFF2F5F8, SUB = 0xFFA3AEBC, TER = 0xFF6B7787, PANEL = 0xFF111821, EDGE = 0xFF1F2935,
            TRACK = 0xFF161E28, IN = 0xFF3DDC84, AMBER = 0xFFFFC23D, RED = 0xFFFF6B6B, BG = 0xFF0A0E13;
    /** {id, ESPN sport, ESPN league, name, tab} */
    private static final String[][] LEAGUES = {
        { "nfl", "football", "nfl", "NFL", "NFL" }, { "nba", "basketball", "nba", "NBA", "NBA" }, { "mlb", "baseball", "mlb", "MLB", "MLB" },
        { "nhl", "hockey", "nhl", "NHL", "NHL" }, { "wnba", "basketball", "wnba", "WNBA", "WNBA" },
        { "cfb", "football", "college-football", "College Football", "NCAAF" },
        { "cbb", "basketball", "mens-college-basketball", "Men's College Hoops", "NCAAM" }, { "mls", "soccer", "usa.1", "MLS", "MLS" },
        { "epl", "soccer", "eng.1", "Premier League", "Premier League" }, { "ucl", "soccer", "uefa.champions", "Champions League", "Champions League" },
        { "laliga", "soccer", "esp.1", "La Liga", "La Liga" } };

    private final NativeHome h;
    private final float R;
    private final TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rf = new RectF();
    private final Typeface fMed, fSemi;
    private final boolean motion;

    private final List<String[]> chips = new ArrayList<>();
    private int sel;
    /** where the remote is: 0 the leagues, 1 the views, 2 a team on the board */
    private int focus;
    private float scroll, scrollT;
    private final Map<String, Object[]> cache = new HashMap<>();          // league → {time, groups, root name}
    private final Set<String> loading = new HashSet<>();
    private final Map<String, String> errors = new HashMap<>();
    private final Map<String, Bitmap> logos = new HashMap<>();
    private final Map<String, Integer> colors = new HashMap<>();           // "lg:id" → the team's colour
    private final Set<String> favs = new HashSet<>();
    private final Map<String, Integer> viewSel = new HashMap<>();

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
    private static final class Group { String name = "", parent = "", parentAbbr = ""; final List<Row> rows = new ArrayList<>(); }
    /** a run of teams on the board: a division, a conference, or half of a long table (whole: all of it) */
    private static final class Block {
        String title = ""; final List<Row> rows = new ArrayList<>(); final List<String[]> vals = new ArrayList<>();
        int rank0, cut, cut2; boolean seeded, zoneBySeed; List<Row> whole;
    }
    /** one of the board's columns */
    private static final class Pane {
        String title = "", note = ""; final List<Block> blocks = new ArrayList<>();
        float x, y, w, hh;
        final List<Row> flat = new ArrayList<>(); final List<Float> rowY = new ArrayList<>(); final List<Block> of = new ArrayList<>(); final List<Integer> idx = new ArrayList<>();
    }
    // the board shown, and the team chosen on it
    private final List<Pane> panes = new ArrayList<>();
    private List<Col> cols = new ArrayList<>();
    private int[] fit = new int[0];
    private float rowH, boardH, sideW;
    private double diffMax = 1;
    private String boardKey = "";
    private final List<Object[]> zones = new ArrayList<>();                // {colour, text}: soccer's key, beside the table
    private final List<Object[]> legend = new ArrayList<>();               // {colour or 0, mark, text}: the footer's key
    private int pi, ri;
    private long enterAt;
    // the glides: the chosen team's glow, the views' thumb, the league underline
    private final RectF glow = new RectF();
    private boolean glowSnap = true;
    private float tabX = -1, tabW, thumbX = -1, thumbW;
    private boolean anim;
    private final List<RectF> chipHits = new ArrayList<>(), viewHits = new ArrayList<>();

    StandingsView(NativeHome host) {
        super(host.getContext());
        h = host; R = host.R;
        fMed = host.fMed; fSemi = host.fSemi;
        t.setFontFeatureSettings("tnum");          // figures line up in their columns
        boolean m = true;
        try { m = ValueAnimator.areAnimatorsEnabled(); } catch (Throwable ignored) { }
        motion = m;
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
        boardKey = "";
        load();
        invalidate();
    }
    void hide() { setVisibility(GONE); focus = 0; closeTeam(); }
    boolean shownNow() { return getVisibility() == VISIBLE; }
    void focusIn() { focus = 0; invalidate(); }

    // ------------------------------------------------------------------ views and the board

    private String lgId() { return chips.isEmpty() ? "" : chips.get(sel)[0]; }
    @SuppressWarnings("unchecked")
    private List<Group> groups() { Object[] c = cache.get(lgId()); return c == null ? null : (List<Group>) c[1]; }
    /** the league's own name in ESPN's answer (groups right under it are conferences, not divisions) */
    private String rootName() { Object[] c = cache.get(lgId()); return c == null || c.length < 3 ? "" : (String) c[2]; }
    private boolean hasDivisions() {
        List<Group> gs = groups();
        if (gs == null) return false;
        for (Group g : gs) if (!g.parent.equals(rootName())) return true;
        return false;
    }
    private List<String> views() {
        List<String> v = new ArrayList<>();
        boolean divs = hasDivisions();
        switch (lgId()) {
            case "nfl": case "nba": case "nhl": if (divs) v.add("Division"); v.add("Conference"); v.add("Overall"); break;
            case "mlb": if (divs) v.add("Division"); v.add("League"); v.add("Overall"); break;
            case "wnba": case "mls": v.add("Conference"); v.add("Overall"); break;
            default: break;
        }
        return v;
    }
    private int viewIndex() {
        List<String> v = views();
        Integer i = viewSel.get(lgId());
        if (i == null) { i = lgId().equals("nba") ? v.indexOf("Conference") : 0; if (i < 0) i = 0; }
        return Math.max(0, Math.min(v.size() - 1, i));
    }
    private String view() { List<String> v = views(); return v.isEmpty() ? "" : v.get(viewIndex()); }

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
            case "p": return "won the Presidents' Trophy";
            case "pb": case "pi": return "clinched a play-in spot";
            case "w": return "clinched a wild card";
            default: return "";
        }
    }
    private static boolean pointsSport(String sport) { return sport.equals("hockey") || sport.equals("soccer"); }

    /** the board for the league and view (made again when either changes, new standings come, or the size does) */
    private void board() {
        Object[] c = cache.get(lgId());
        String key = lgId() + "|" + view() + "|" + (c == null ? 0 : c[0]) + "|" + getWidth() + "x" + getHeight();
        if (key.equals(boardKey)) return;
        boolean sameBoard = boardKey.startsWith(lgId() + "|" + view() + "|");
        boardKey = key;
        panes.clear(); legend.clear(); zones.clear();
        List<Group> gs = groups();
        if (gs == null || chips.isEmpty() || getWidth() == 0) return;
        if (!sameBoard) { enterAt = SystemClock.uptimeMillis(); glowSnap = true; }
        String[] lg = chips.get(sel);
        String v = view(), sport = lg[1];
        int[] cut = cutFor(lg[0]);
        boolean needSeeds = !lg[0].equals("wnba") && !lg[0].equals("mls");
        Map<String, List<Group>> confs = new LinkedHashMap<>();
        for (Group g : gs) { List<Group> l = confs.get(g.parent); if (l == null) { l = new ArrayList<>(); confs.put(g.parent, l); } l.add(g); }
        boolean divs = hasDivisions();
        boolean leagueSeeds = true; for (Group g : gs) if (!allSeeds(g.rows)) leagueSeeds = false;
        if (v.equals("Division") && divs) {
            // each conference a column, its divisions in it; the rail marks who holds a playoff seed today
            for (List<Group> cg : confs.values()) {
                Pane pn = new Pane();
                for (Group g : cg) {
                    Block b = new Block(); b.title = g.name; b.rows.addAll(g.rows);
                    b.zoneBySeed = leagueSeeds && cut[0] > 0;
                    pn.blocks.add(b);
                }
                panes.add(pn);
            }
        } else if (v.equals("Conference") || v.equals("League")) {
            List<List<Group>> parts = new ArrayList<>();
            if (divs) parts.addAll(confs.values());
            else for (Group g : gs) { List<Group> one = new ArrayList<>(); one.add(g); parts.add(one); }
            for (List<Group> part : parts) {
                Pane pn = new Pane(); pn.title = divs ? confTitle(part.get(0), lg[0]) : part.get(0).name;
                Block b = new Block();
                for (Group g : part) b.rows.addAll(g.rows);
                b.seeded = sortRows(b.rows, true);
                if (cut[0] > 0 && b.rows.size() > cut[0] && (b.seeded || !needSeeds) && !lg[0].equals("wnba")) { b.cut = cut[0]; b.cut2 = cut[1] > 0 && b.rows.size() > cut[1] ? cut[1] : 0; }
                pn.blocks.add(b); pn.note = playoffNote(b);
                panes.add(pn);
            }
        } else if (v.equals("Overall")) {
            List<Row> all = new ArrayList<>(); for (Group g : gs) all.addAll(g.rows);
            sortRows(all, true);
            split(all, lg[0].equals("wnba") && all.size() > cut[0] ? cut[0] : 0);
        } else if (gs.size() == 1) {
            List<Row> all = new ArrayList<>(gs.get(0).rows);
            sortRows(all, false);
            if (all.size() <= 22) { Pane pn = new Pane(); Block b = new Block(); b.rows.addAll(all); pn.blocks.add(b); panes.add(pn); }
            else split(all, 0);
        } else {
            // many conferences (college): two columns, each conference to the shorter one
            Pane a = new Pane(), b = new Pane(); int na = 0, nb = 0;
            for (Group g : gs) {
                Block bl = new Block(); bl.title = g.name; bl.rows.addAll(g.rows);
                if (na <= nb) { a.blocks.add(bl); na += g.rows.size() + 2; } else { b.blocks.add(bl); nb += g.rows.size() + 2; }
            }
            panes.add(a); if (!b.blocks.isEmpty()) panes.add(b);
        }
        // the columns: the sport's, LINE where there's a playoff line; then the figures
        cols = cols(sport, lg[0]);
        boolean anyCut = false; for (Pane pn : panes) for (Block b : pn.blocks) if (b.cut > 0) anyCut = true;
        if (anyCut) cols.add(new Col("LINE", 4.4f, 1, "#line"));
        diffMax = 1;
        for (Pane pn : panes) for (Block b : pn.blocks) {
            Row lead = null; for (Row r : b.rows) if (lead == null || (r.w - r.l) > (lead.w - lead.l)) lead = r;
            for (int i = 0; i < b.rows.size(); i++) {
                Row r = b.rows.get(i);
                String[] vs = new String[cols.size()];
                for (int k = 0; k < cols.size(); k++) {
                    String ck = cols.get(k).keys[0], lab = cols.get(k).label;
                    if (ck.equals("#gb")) vs[k] = lead == null ? "" : gb(lead, r);
                    else if (ck.equals("#line")) vs[k] = b.cut > 0 ? toLine(b, i, pointsSport(sport)) : "";
                    else { String s = r.s.get(lab); vs[k] = s == null ? "" : s; }
                    if (lab.equals("DIFF") || lab.equals("GD")) diffMax = Math.max(diffMax, Math.abs(numOf(vs[k])));
                }
                b.vals.add(vs);
            }
        }
        // a column nobody has a value in (ties in a season without any) goes
        for (int k = cols.size() - 1; k >= 0; k--) {
            boolean any = false;
            String lab = cols.get(k).label;
            outer:
            for (Pane pn : panes) for (Block b : pn.blocks) for (String[] vs : b.vals) {
                String x = vs[k];
                if (!x.isEmpty() && !x.equals("-") && !((lab.equals("T") || lab.equals("OTL")) && x.equals("0"))) { any = true; break outer; }
            }
            if (!any) {
                cols.remove(k);
                for (Pane pn : panes) for (Block b : pn.blocks) for (int i = 0; i < b.vals.size(); i++) {
                    String[] o = b.vals.get(i), nv = new String[o.length - 1];
                    for (int a = 0, z = 0; a < o.length; a++) if (a != k) nv[z++] = o[a];
                    b.vals.set(i, nv);
                }
            }
        }
        // what the marks mean (only those on the board)
        Set<String> letters = new java.util.TreeSet<>();
        Map<String, Integer> zn = new LinkedHashMap<>();
        boolean inZone = false, playIn = false;
        for (Pane pn : panes) for (Block b : pn.blocks) for (int i = 0; i < b.rows.size(); i++) {
            Row r = b.rows.get(i);
            if (!r.clinch.isEmpty() && !clinchMeaning(lg[0], r.clinch).isEmpty()) letters.add(r.clinch);
            if (r.noteColor != 0 && !r.noteText.isEmpty() && !zn.containsKey(r.noteText)) zn.put(r.noteText, r.noteColor);
            int z = zone(b, i, lg[0]);
            if (z == IN && r.noteColor == 0) inZone = true; else if (z == AMBER && r.noteColor == 0) playIn = true;
        }
        if (inZone) legend.add(new Object[] { IN, "rail", "In a playoff spot" });
        if (playIn) legend.add(new Object[] { AMBER, "rail", "Play-in" });
        for (String m : letters) legend.add(new Object[] { 0, m, clinchMeaning(lg[0], m) });
        for (Map.Entry<String, Integer> z : zn.entrySet()) zones.add(new Object[] { z.getValue(), z.getKey() });
        if (panes.size() > 1) { for (Object[] z : zones) legend.add(new Object[] { z[0], "rail", z[1] }); zones.clear(); }
        layout();
    }
    /** a long table in two columns, ranked straight through (1–16 | 17–32) */
    private void split(List<Row> all, int cut) {
        int half = (all.size() + 1) / 2;
        for (int part = 0; part < 2; part++) {
            Pane pn = new Pane(); Block b = new Block();
            int from = part == 0 ? 0 : half, to = part == 0 ? half : all.size();
            b.rows.addAll(all.subList(from, to)); b.rank0 = from; b.cut = cut; b.whole = all;
            pn.blocks.add(b);
            if (!b.rows.isEmpty()) panes.add(pn);
        }
    }
    private static String confTitle(Group g, String lg) { return lg.equals("nfl") && !g.parentAbbr.isEmpty() ? g.parentAbbr : g.parent; }
    private static String playoffNote(Block b) {
        if (b.cut <= 0) return "";
        if (b.cut2 > 0) return b.cut + " make the playoffs, " + (b.cut + 1) + "–" + b.cut2 + " the play-in";
        return b.cut + " make the playoffs";
    }
    /** the rail's colour for a team: soccer's zone, in a playoff spot, in the play-in, or none */
    private int zone(Block b, int i, String lg) {
        Row r = b.rows.get(i);
        if (r.noteColor != 0) return r.noteColor;
        int g = b.rank0 + i;
        if (b.cut > 0) return g < b.cut ? IN : b.cut2 > 0 && g < b.cut2 ? AMBER : 0;
        if (b.zoneBySeed && r.seed > 0) { int[] c = cutFor(lg); if (r.seed <= c[0]) return IN; if (c[1] > 0 && r.seed <= c[1]) return AMBER; }
        return 0;
    }
    /** games behind the leader (wins and losses) */
    private static String gb(Row lead, Row r) {
        double g = ((lead.w - r.w) + (r.l - lead.l)) / 2;
        return Math.abs(g) < .01 ? "-" : half(g);
    }
    /** how far above (+) or below (−) the playoff line: games, or points for hockey and soccer */
    private static String toLine(Block b, int i, boolean points) {
        List<Row> rows = b.whole != null ? b.whole : b.rows;
        int at = b.rank0 + i, cut = b.cut;
        if (cut <= 0 || cut >= rows.size()) return "";
        Row me = rows.get(at), ref = rows.get(at < cut ? cut : cut - 1);
        double d = gap(me, ref, points);
        if (Math.abs(d) < .01) return "0";
        return (d > 0 ? "+" : "−") + half(Math.abs(d));
    }
    /** how far a is ahead of b: games (ties count half) or points */
    private static double gap(Row a, Row b, boolean points) {
        if (points) return a.pts - b.pts;
        return ((a.w + a.tie / 2 - (b.w + b.tie / 2)) + (b.l + b.tie / 2 - (a.l + a.tie / 2))) / 2;
    }
    private static String half(double g) {
        double a = Math.abs(g);
        return Math.abs(a - Math.round(a)) < .01 ? String.valueOf(Math.round(g)) : String.format(Locale.US, "%.1f", g);
    }
    private static double numOf(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Double.parseDouble(s.replace("+", "").replace("−", "-").replace(",", "")); } catch (Exception e) { return 0; }
    }
    private static String ordinal(int n) {
        int m = n % 100;
        return n + (m >= 11 && m <= 13 ? "th" : n % 10 == 1 ? "st" : n % 10 == 2 ? "nd" : n % 10 == 3 ? "rd" : "th");
    }

    // ------------------------------------------------------------------ the remote

    /** ◀ ▶ ▲ ▼; false: ▲ from the leagues (back up to the tabs) */
    boolean move(char d) {
        if (team != null) { teamMove(d); invalidate(); return true; }
        boolean hasViews = views().size() > 1;
        if (focus == 0) {
            if (d == 'l' || d == 'r') {
                int n = Math.max(0, Math.min(chips.size() - 1, sel + (d == 'r' ? 1 : -1)));
                if (n != sel) { sel = n; scroll = scrollT = 0; pi = ri = 0; boardKey = ""; load(); }
            } else if (d == 'u') { invalidate(); return false; }
            else if (d == 'd') { if (hasViews) focus = 1; else intoBoard(); }
        } else if (focus == 1) {
            if (d == 'l' || d == 'r') {
                List<String> v = views();
                int n = Math.max(0, Math.min(v.size() - 1, viewIndex() + (d == 'r' ? 1 : -1)));
                if (n != viewIndex()) { viewSel.put(lgId(), n); boardKey = ""; scroll = scrollT = 0; pi = ri = 0; }
            } else if (d == 'u') focus = 0;
            else if (d == 'd') intoBoard();
        } else {
            board();
            if (panes.isEmpty()) { focus = hasViews ? 1 : 0; invalidate(); return true; }
            pi = Math.min(pi, panes.size() - 1);
            Pane pn = panes.get(pi);
            ri = Math.min(ri, pn.flat.size() - 1);
            if (d == 'u') {
                if (ri > 0) ri--;
                else { focus = hasViews ? 1 : 0; scrollT = 0; }
            } else if (d == 'd') { if (ri < pn.flat.size() - 1) ri++; }
            else {
                int to = pi + (d == 'r' ? 1 : -1);
                if (to >= 0 && to < panes.size()) {
                    // the team level with this one in the other column
                    float y = pn.rowY.get(ri);
                    Pane o = panes.get(to); int best = 0; float bd = Float.MAX_VALUE;
                    for (int k = 0; k < o.rowY.size(); k++) { float dd = Math.abs(o.rowY.get(k) - y); if (dd < bd) { bd = dd; best = k; } }
                    pi = to; ri = best;
                }
            }
            follow();
        }
        invalidate();
        return true;
    }
    /** onto the board: at your team when it's there, else the top */
    private void intoBoard() {
        board();
        if (panes.isEmpty()) return;
        focus = 2; pi = 0; ri = 0; glowSnap = true;
        String lg = lgId();
        outer:
        for (int a = 0; a < panes.size(); a++) for (int b = 0; b < panes.get(a).flat.size(); b++) if (favs.contains(lg + ":" + panes.get(a).flat.get(b).id)) { pi = a; ri = b; break outer; }
        follow();
    }
    private float boardTop() { return 4.9f * R; }
    private float footTop() { return getHeight() - 3.1f * R; }
    /** the chosen team kept in sight */
    private void follow() {
        if (focus != 2 || pi >= panes.size()) return;
        Pane pn = panes.get(pi);
        if (ri >= pn.rowY.size()) return;
        float y0 = pn.rowY.get(ri), y1 = y0 + rowH, top = boardTop(), bot = footTop() - .6f * R;
        if (boardH <= bot - top) { scrollT = 0; return; }
        if (y0 - scrollT < top + 2 * R) scrollT = Math.max(0, y0 - top - 4 * R);
        else if (y1 - scrollT > bot - R) scrollT = y1 - bot + 4 * R;
        scrollT = Math.max(0, Math.min(scrollT, boardH - (bot - top)));
    }

    void ok() {
        if (team != null) { List<SportsData.Ev> it = teamList(tcol); if (tsel < it.size()) h.sportsData().openEvent(it.get(tsel)); return; }
        if (cache.get(lgId()) == null && errors.containsKey(lgId())) { load(); invalidate(); return; }
        if (focus == 0) { if (views().size() > 1) focus = 1; else intoBoard(); invalidate(); return; }
        if (focus == 1) { intoBoard(); invalidate(); return; }
        if (pi < panes.size() && ri < panes.get(pi).flat.size()) openTeam(panes.get(pi).flat.get(ri), chips.get(sel));
    }
    /** BACK: a team's page closes, the board goes back to the leagues; false at the leagues */
    boolean back() {
        if (team != null) { closeTeam(); invalidate(); return true; }
        if (focus != 0) { focus = 0; scrollT = 0; invalidate(); return true; }
        return false;
    }

    /** a finger (phones): a league, a view, a team or a team's game; "ok" when OK should open what's under it */
    String tapAt(float x, float y) {
        if (team != null) {
            for (Object[] hit : teamHits) if (((RectF) hit[0]).contains(x, y)) { tcol = (Integer) hit[1]; tsel = (Integer) hit[2]; invalidate(); return "ok"; }
            return null;
        }
        for (int i = 0; i < chipHits.size(); i++) if (chipHits.get(i).contains(x, y)) {
            focus = 0; if (i != sel) { sel = i; scroll = scrollT = 0; pi = ri = 0; boardKey = ""; load(); }
            invalidate(); return "done";
        }
        for (int i = 0; i < viewHits.size(); i++) if (viewHits.get(i).contains(x, y)) { focus = 1; viewSel.put(lgId(), i); boardKey = ""; scroll = scrollT = 0; pi = ri = 0; invalidate(); return "done"; }
        float cy = y + scroll;
        for (int a = 0; a < panes.size(); a++) {
            Pane pn = panes.get(a);
            if (x < pn.x || x > pn.x + pn.w) continue;
            for (int k = 0; k < pn.rowY.size(); k++) if (cy >= pn.rowY.get(k) && cy < pn.rowY.get(k) + rowH) { focus = 2; pi = a; ri = k; invalidate(); return "ok"; }
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
                    collect(j, j.optString("name"), j.optString("abbreviation"), got, lg[1]);
                    if (!got.isEmpty()) { gs = got; root = j.optString("name"); break; }
                    err = "no standings yet";
                } catch (Exception e) { err = String.valueOf(e.getMessage()); }
            }
            // the teams' colours (one call: every team in the league), for the chosen row's glow and team pages
            final Map<String, Integer> cl = new HashMap<>();
            if (gs != null) {
                JSONObject tj = json(SportsData.ESPN + "/" + lg[1] + "/" + lg[2] + "/teams?limit=500");
                try {
                    JSONArray ts = tj.getJSONArray("sports").getJSONObject(0).getJSONArray("leagues").getJSONObject(0).getJSONArray("teams");
                    for (int i = 0; i < ts.length(); i++) {
                        JSONObject tm = ts.getJSONObject(i).optJSONObject("team"); if (tm == null) continue;
                        int col = hex(tm.optString("color")), alt = hex(tm.optString("alternateColor"));
                        if (col != 0 || alt != 0) cl.put(lg[0] + ":" + tm.optString("id"), pick(col, alt));
                    }
                } catch (Exception ignored) { }
            }
            final List<Group> fg = gs; final String fe = err, fr = root;
            Diag.ev("standings: " + lg[3] + " · " + (fg == null ? "none (" + fe + ")" : fg.size() + " groups, " + cl.size() + " team colours"));
            post(new Runnable() { @Override public void run() {
                loading.remove(lg[0]);
                if (fg != null) {
                    cache.put(lg[0], new Object[] { System.currentTimeMillis(), fg, fr });
                    colors.putAll(cl);
                    for (Group g : fg) for (Row r : g.rows) wantLogo(r.logo);
                } else errors.put(lg[0], fe);
                boardKey = "";
                invalidate();
            } });
        } }, "standings").start();
    }
    private static int hex(String s) {
        if (s == null || !s.matches("[0-9a-fA-F]{6}")) return 0;
        return 0xFF000000 | Integer.parseInt(s, 16);
    }
    private static double lum(int c) { return (.2126 * Color.red(c) + .7152 * Color.green(c) + .0722 * Color.blue(c)) / 255; }
    /** the team colour that will show on a dark screen: its main one, else the other when the main is black */
    private static int pick(int col, int alt) {
        if (col == 0) return alt;
        if (alt == 0) return col;
        if (lum(col) < .06 && lum(alt) > lum(col) && lum(alt) < .9) return alt;
        return col;
    }
    /** lifted until it glows on the dark board (navy and black would otherwise disappear) */
    private static int lift(int c, double to) {
        double l = lum(c);
        if (l >= to) return c;
        float k = (float) Math.min(1, (to - l) / Math.max(.01, 1 - l) * 1.15);
        return Color.rgb((int) (Color.red(c) + (255 - Color.red(c)) * k), (int) (Color.green(c) + (255 - Color.green(c)) * k), (int) (Color.blue(c) + (255 - Color.blue(c)) * k));
    }
    private void wantLogo(final String u) {
        if (u == null || u.isEmpty() || logos.containsKey(u)) return;
        logos.put(u, null);
        final int s = Math.round(2.1f * R);
        h.imgs.load("stand2:" + u, new HomeImages.Source() { @Override public byte[] bytes() throws Exception { return darkFirst(u); } }, s, s,
                new HomeImages.Done() { @Override public void done(Bitmap b) { if (b != null) { logos.put(u, b); invalidate(); } } });
    }
    /** ESPN's logo made for dark backgrounds (…/500-dark/…) when it has one, else the plain one */
    static byte[] darkFirst(String u) throws Exception {
        String d = u.contains("/500/") ? u.replace("/500/", "/500-dark/") : null;
        if (d != null) { try { byte[] b = HomeImages.http(d, null); if (b != null && b.length > 200) return b; } catch (Exception ignored) { } }
        return HomeImages.http(u, null);
    }
    /** by division (NFL, NBA, MLB, NHL: their conference views are made from these), by conference (WNBA, MLS,
        college), one table (soccer) */
    private static int level(String id) { return id.equals("nfl") || id.equals("mlb") || id.equals("nhl") || id.equals("nba") ? 3 : 2; }
    /** every part of the answer with a table in it (a league, its conferences, their divisions), the deepest ones */
    private void collect(JSONObject node, String parent, String parentAbbr, List<Group> out, String sport) {
        int before = out.size();
        JSONArray ch = node.optJSONArray("children");
        if (ch != null) for (int i = 0; i < ch.length(); i++) { JSONObject c = ch.optJSONObject(i); if (c != null) collect(c, node.optString("name", parent), node.optString("abbreviation", ""), out, sport); }
        if (out.size() > before) return;
        JSONObject st = node.optJSONObject("standings");
        JSONArray en = st == null ? null : st.optJSONArray("entries");
        if (en == null || en.length() == 0) return;
        Group g = new Group();
        g.name = node.optString("name", parent);
        g.parent = parent; g.parentAbbr = parentAbbr;
        List<Col> all = cols(sport, "");
        boolean pts = pointsSport(sport);
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
            r.pts = pts ? z(num(stats, "points")) : 0; r.pct = num(stats, "winPercent"); r.seed = z(num(stats, "playoffSeed")); r.rank = z(num(stats, "rank"));
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
    /** the columns: the must-haves at prio 1, then 2, 3, 4 as there's room; #… are worked out here */
    private static List<Col> cols(String sport, String lg) {
        List<Col> c = new ArrayList<>();
        switch (sport) {
            case "football":
                c.add(new Col("W", 3f, 1, "wins", "W")); c.add(new Col("L", 3f, 1, "losses", "L")); c.add(new Col("T", 2.8f, 1, "ties", "T"));
                c.add(new Col("PCT", 4.4f, 1, "winPercent", "PCT"));
                c.add(new Col("HOME", 5.2f, 3, "home", "Home")); c.add(new Col("AWAY", 5.2f, 3, "road", "away", "Road"));
                c.add(new Col("DIV", 5.2f, 3, "vsdiv", "vs. Div.", "divisionRecord")); c.add(new Col("CONF", 5.2f, lg.equals("cfb") ? 2 : 4, "vsconf", "vs. Conf.", "conferenceRecord"));
                c.add(new Col("PF", 4f, 4, "pointsFor", "PF")); c.add(new Col("PA", 4f, 4, "pointsAgainst", "PA"));
                c.add(new Col("DIFF", 7.6f, 2, "pointDifferential", "differential", "DIFF")); c.add(new Col("STRK", 3.8f, 2, "streak", "STRK"));
                break;
            case "hockey":
                c.add(new Col("GP", 3.2f, 1, "gamesPlayed", "GP")); c.add(new Col("W", 3f, 1, "wins", "W")); c.add(new Col("L", 3f, 1, "losses", "L"));
                c.add(new Col("OTL", 3.4f, 1, "otLosses", "overtimeLosses", "OTLosses", "OTL")); c.add(new Col("PTS", 3.8f, 1, "points", "PTS"));
                c.add(new Col("HOME", 5.8f, 4, "home", "Home")); c.add(new Col("AWAY", 5.8f, 4, "road", "away", "Road"));
                c.add(new Col("L10", 5.8f, 2, "lasttengames", "Last Ten Games", "L10")); c.add(new Col("DIFF", 7.6f, 2, "differential", "pointDifferential", "DIFF"));
                c.add(new Col("STRK", 3.8f, 3, "streak", "STRK"));
                break;
            case "soccer":
                c.add(new Col("GP", 3.2f, 1, "gamesPlayed", "GP")); c.add(new Col("W", 3f, 1, "wins", "W")); c.add(new Col("D", 3f, 1, "ties", "D", "T"));
                c.add(new Col("L", 3f, 1, "losses", "L")); c.add(new Col("GF", 3.4f, 3, "pointsFor", "F", "GF")); c.add(new Col("GA", 3.4f, 3, "pointsAgainst", "A", "GA"));
                c.add(new Col("GD", 7.6f, 2, "pointDifferential", "GD")); c.add(new Col("PTS", 3.8f, 1, "points", "P", "PTS"));
                break;
            case "baseball":
                c.add(new Col("W", 3.2f, 1, "wins", "W")); c.add(new Col("L", 3.2f, 1, "losses", "L")); c.add(new Col("PCT", 4.4f, 1, "winPercent", "PCT"));
                c.add(new Col("GB", 3.6f, 1, "#gb")); c.add(new Col("HOME", 5.2f, 3, "home", "Home")); c.add(new Col("AWAY", 5.2f, 3, "road", "away", "Road"));
                c.add(new Col("L10", 4.4f, 2, "lasttengames", "Last Ten Games", "L10")); c.add(new Col("DIFF", 7.6f, 2, "pointDifferential", "differential", "DIFF"));
                c.add(new Col("STRK", 3.8f, 2, "streak", "STRK"));
                break;
            default:     // basketball
                c.add(new Col("W", 3.2f, 1, "wins", "W")); c.add(new Col("L", 3.2f, 1, "losses", "L")); c.add(new Col("PCT", 4.4f, 1, "winPercent", "PCT"));
                c.add(new Col("GB", 3.6f, 1, "#gb")); c.add(new Col("HOME", 5.2f, 3, "home", "Home")); c.add(new Col("AWAY", 5.2f, 3, "road", "away", "Road"));
                c.add(new Col("CONF", 5.2f, lg.equals("cbb") ? 2 : 4, "vsconf", "vs. Conf.", "conferenceRecord"));
                c.add(new Col("L10", 4.4f, 2, "lasttengames", "Last Ten Games", "L10")); c.add(new Col("DIFF", 7.6f, 3, "differential", "pointDifferential", "DIFF"));
                c.add(new Col("STRK", 3.8f, 2, "streak", "STRK"));
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
    /** as the league ranks them: playoff seed (or rank) when every team has one (a different one, merging
        divisions), else points, else win %, then wins; true when it went by seed */
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
    private static JSONObject json(String u) {
        try { byte[] b = HomeImages.http(u, null); return b == null ? null : new JSONObject(new String(b, "UTF-8")); } catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------------ where a team stands, in words

    private Group groupOf(Row r) { List<Group> gs = groups(); if (gs != null) for (Group g : gs) if (g.rows.contains(r)) return g; return null; }
    /** the team's conference (or its table, without divisions), ranked as the league ranks it */
    private List<Row> confRows(Group g) {
        List<Row> rs = new ArrayList<>();
        List<Group> gs = groups(); if (gs == null || g == null) return rs;
        if (!hasDivisions()) rs.addAll(g.rows);
        else for (Group x : gs) if (x.parent.equals(g.parent)) rs.addAll(x.rows);
        sortRows(rs, true);
        return rs;
    }
    private String confName(Group g) {
        if (g == null) return "";
        if (!hasDivisions()) return g.name;
        if (!g.parentAbbr.isEmpty() && g.parentAbbr.length() <= 4) return g.parentAbbr;
        return g.parent;
    }
    /** "Cowboys are 2nd in the NFC East and 10th in the NFC, 1.5 games out of a playoff spot." */
    private String sentence(Row r) {
        String lg = lgId(), sport = chips.get(sel)[1];
        Group g = groupOf(r);
        if (g == null) return r.name;
        List<String> places = new ArrayList<>();
        String status = "";
        if (sport.equals("soccer")) {
            List<Row> rs = new ArrayList<>(g.rows); sortRows(rs, false);
            int at = rs.indexOf(r);
            places.add(ordinal(at + 1));
            if (at == 0 && rs.size() > 1) { double d = r.pts - rs.get(1).pts; status = d > 0 ? half(d) + (d == 1 ? " point" : " points") + " clear at the top" : "top on goal difference"; }
            else if (at > 0) { double d = rs.get(0).pts - r.pts; status = half(d) + (d == 1 ? " point" : " points") + " behind the leaders"; }
            if (!r.noteText.isEmpty()) status += (status.isEmpty() ? "" : ", ") + "in the " + r.noteText + " places";
        } else {
            boolean divs = hasDivisions();
            if (divs) places.add(ordinal(g.rows.indexOf(r) + 1) + " in the " + g.name);
            List<Row> conf = confRows(g);
            int at = conf.indexOf(r);
            String cn = confName(g);
            if (at >= 0) places.add(ordinal(at + 1) + " in the " + cn);
            int[] cut = cutFor(lg);
            if (cut[0] > 0 && at >= 0 && conf.size() > cut[0] && (allSeeds(conf) || lg.equals("mls")) && !lg.equals("wnba")) {
                if (at < cut[0]) status = "the " + (cn.length() <= 4 ? cn + "'s " : "") + ordinal(at + 1) + " seed";
                else if (cut[1] > 0 && at < cut[1]) status = "in the play-in as the " + ordinal(at + 1) + " seed";
                else {
                    boolean pts = pointsSport(sport);
                    double d = gap(conf.get(cut[0] - 1), r, pts);
                    status = d > 0 ? half(d) + " " + (pts ? (d == 1 ? "point" : "points") : (d == 1 ? "game" : "games")) + " out of a playoff spot" : "level with the last team in, outside on tiebreakers";
                }
            }
        }
        StringBuilder b = new StringBuilder(r.name).append(" are ");
        for (int i = 0; i < places.size(); i++) { if (i > 0) b.append(" and "); b.append(places.get(i)); }
        if (!status.isEmpty()) b.append(", ").append(status);
        b.append('.');
        String cm = clinchMeaning(lg, r.clinch);
        if (r.clinch.equals("e")) b.append(" Out of playoff contention.");
        else if (!cm.isEmpty()) b.append(" They've ").append(cm).append('.');
        return b.toString();
    }

    // ------------------------------------------------------------------ a team's page

    private static final class TeamData {
        long at; boolean loading = true; String err = "", summary = "", record = "", leadLabel = "";
        int color;
        final List<SportsData.Ev> next = new ArrayList<>(), past = new ArrayList<>();
        JSONArray leaders;
    }
    private Row team;
    private String[] teamLg;
    private TeamData td;
    private int tcol, tsel;
    private long teamAt;
    private Bitmap teamLogo, featLogo;
    private String featFor = "";
    private final Map<String, TeamData> teamCache = new HashMap<>();
    private final List<Object[]> teamHits = new ArrayList<>();             // {rect, column, index}

    /** column 0: the next game and the ones after it; column 1: results, newest first */
    private List<SportsData.Ev> teamList(int col) {
        List<SportsData.Ev> l = new ArrayList<>();
        if (td == null) return l;
        if (col == 0) for (int i = 0; i < Math.min(4, td.next.size()); i++) l.add(td.next.get(i));
        else for (int i = 0; i < Math.min(7, td.past.size()); i++) l.add(td.past.get(i));
        return l;
    }
    private void teamMove(char d) {
        int n = teamList(tcol).size();
        if (d == 'u' && tsel > 0) tsel--;
        else if (d == 'd' && tsel < n - 1) tsel++;
        else if (d == 'l' && tcol == 1 && !teamList(0).isEmpty()) { tcol = 0; tsel = Math.min(tsel, teamList(0).size() - 1); }
        else if (d == 'r' && tcol == 0 && !teamList(1).isEmpty()) { tcol = 1; tsel = Math.min(tsel, teamList(1).size() - 1); }
    }
    private void closeTeam() { team = null; td = null; teamLogo = null; featLogo = null; featFor = ""; teamHits.clear(); glowSnap = true; }

    private void openTeam(final Row r, final String[] lg) {
        team = r; teamLg = lg; tcol = 0; tsel = 0; teamLogo = null; featLogo = null; featFor = "";
        teamAt = SystemClock.uptimeMillis();
        final int s = Math.round(8 * R);
        if (!r.logo.isEmpty()) h.imgs.load("standbig3:" + r.logo, new HomeImages.Source() { @Override public byte[] bytes() throws Exception { return darkFirst(r.logo); } }, s, s,
                new HomeImages.Done() { @Override public void done(Bitmap b) { if (team == r) { teamLogo = b; invalidate(); } } });
        final String key = lg[0] + ":" + r.id;
        TeamData have = teamCache.get(key);
        if (have != null && System.currentTimeMillis() - have.at < 5 * 60000L) { td = have; tcol = have.next.isEmpty() && !have.past.isEmpty() ? 1 : 0; afterTeam(); invalidate(); return; }
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
                td = d; tcol = d.next.isEmpty() && !d.past.isEmpty() ? 1 : 0; tsel = 0;
                afterTeam();
                if (!d.err.isEmpty()) Diag.ev("standings: team page couldn't load · " + d.err);
                invalidate();
            } });
        } }, "standings-team").start();
    }
    /** the logos the page needs: every opponent small, the next one big */
    private void afterTeam() {
        for (int c = 0; c < 2; c++) for (SportsData.Ev e : teamList(c)) for (SportsData.Team x : e.teams) wantLogo(x.logo);
        if (td == null || td.next.isEmpty()) return;
        final SportsData.Team opp = other(td.next.get(0), team.id);
        if (opp == null || opp.logo.isEmpty()) return;
        final Row was = team; featFor = opp.logo;
        final int s = Math.round(5 * R);
        h.imgs.load("standfeat:" + opp.logo, new HomeImages.Source() { @Override public byte[] bytes() throws Exception { return darkFirst(opp.logo); } }, s, s,
                new HomeImages.Done() { @Override public void done(Bitmap b) { if (team == was) { featLogo = b; invalidate(); } } });
    }
    /** ESPN's team (its place, record and colour), its schedule (soccer's in two: results, then fixtures), and
        the leaders from its next game's preview (the season's), else its last game */
    private static void fill(TeamData d, Row r, String[] lg) throws Exception {
        SportsData.Lg L = SportsData.league(lg[0]);
        if (L == null) { d.err = "this league has no schedule here"; return; }
        String base = SportsData.ESPN + "/" + lg[1] + "/" + lg[2] + "/teams/" + r.id;
        JSONObject info = json(base);
        JSONObject tm = info == null ? null : info.optJSONObject("team");
        if (tm != null) {
            d.summary = tm.optString("standingSummary");
            d.color = pick(hex(tm.optString("color")), hex(tm.optString("alternateColor")));
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
            d.leadLabel = src.state.equals("pre") ? "Season leaders" : src.state.equals("in") ? "Leaders this game" : "Leaders last game";
        }
    }
    private static SportsData.Team other(SportsData.Ev e, String us) { for (SportsData.Team x : e.teams) if (!x.id.equals(us)) return x; return null; }
    private static SportsData.Team ours(SportsData.Ev e, String us) { for (SportsData.Team x : e.teams) if (x.id.equals(us)) return x; return null; }
    /** 1 won, -1 lost, 0 a tie / draw (or no score) */
    private static int result(SportsData.Ev e, String us) {
        SportsData.Team me = ours(e, us), them = other(e, us);
        if (me == null || them == null) return 0;
        if (me.winner) return 1;
        if (them.winner) return -1;
        try { double a = Double.parseDouble(me.score), b = Double.parseDouble(them.score); return a > b ? 1 : a < b ? -1 : 0; } catch (Exception ex) { return 0; }
    }
    private static String recordOf(Row r) { String s = (int) r.w + "-" + (int) r.l; if (r.tie > 0) s += "-" + (int) r.tie; return s; }
    private int teamColor(Row r) {
        Integer c = colors.get(lgId() + ":" + r.id);
        if (c != null) return c;
        return td != null && td.color != 0 && r == team ? td.color : 0xFF3A4A60;
    }

    // ------------------------------------------------------------------ layout

    /** where each column and team goes, how tall the rows are, and which columns fit */
    private void layout() {
        int w = getWidth();
        if (w == 0 || panes.isEmpty()) return;
        float pad = 4 * R, gap = 2 * R, top = boardTop(), avail = footTop() - .6f * R - top;
        boolean side = panes.size() == 1 && !zones.isEmpty();
        sideW = side ? 26 * R : 0;
        float pw = panes.size() >= 2 ? (w - 2 * pad - gap) / 2 : side ? w - 2 * pad - gap - sideW : Math.min(w - 2 * pad, 86 * R);
        // the row height: as roomy as the screen allows with every team in sight
        float best = Float.MAX_VALUE;
        for (Pane pn : panes) {
            int rows = 0; for (Block b : pn.blocks) rows += b.rows.size();
            if (rows > 0) best = Math.min(best, (avail - fixedH(pn)) / rows);
        }
        rowH = best >= 2.3f * R ? Math.min(2.95f * R, best) : 2.45f * R;
        boardH = 0;
        for (int i = 0; i < panes.size(); i++) {
            Pane pn = panes.get(i);
            pn.w = pw; pn.x = panes.size() >= 2 ? pad + i * (pw + gap) : pad;
            pn.y = top;
            pn.flat.clear(); pn.rowY.clear(); pn.of.clear(); pn.idx.clear();
            float y = top + (pn.title.isEmpty() ? 0 : 3.4f * R) + 2.4f * R;
            for (Block b : pn.blocks) {
                if (!b.title.isEmpty()) y += 2.3f * R;
                for (int k = 0; k < b.rows.size(); k++) {
                    pn.flat.add(b.rows.get(k)); pn.rowY.add(y); pn.of.add(b); pn.idx.add(k);
                    y += rowH;
                    if (lineAfter(b, k)) y += 1.5f * R;
                }
            }
            pn.hh = y + .8f * R - top;
            boardH = Math.max(boardH, pn.hh);
        }
        // the columns that fit: the least wanted go first, from the right
        float room = pw - 1.3f * R - 6.8f * R - 9 * R;
        List<Integer> keep = new ArrayList<>(); for (int k = 0; k < cols.size(); k++) keep.add(k);
        for (int pr = 4; pr >= 2; pr--) {
            float sum = 0; for (int k : keep) sum += cols.get(k).w * R;
            for (int k = keep.size() - 1; k >= 0 && sum > room; k--) if (cols.get(keep.get(k)).prio == pr) { sum -= cols.get(keep.get(k)).w * R; keep.remove(k); }
        }
        // home without away (or the other way round) reads as a mistake: both or neither
        int home = -1, away = -1;
        for (int k = 0; k < keep.size(); k++) { String l = cols.get(keep.get(k)).label; if (l.equals("HOME")) home = k; else if (l.equals("AWAY")) away = k; }
        if ((home < 0) != (away < 0)) keep.remove(Integer.valueOf(keep.get(Math.max(home, away))));
        fit = new int[keep.size()]; for (int k = 0; k < keep.size(); k++) fit[k] = keep.get(k);
        wide = pw >= 60 * R;
    }
    /** a wide single table has room for full names (Manchester United, not Man United) */
    private boolean wide;
    /** a playoff (or play-in) line after this team, inside the block */
    private static boolean lineAfter(Block b, int k) {
        int g = b.rank0 + k;
        if (k >= b.rows.size() - 1) return false;
        return (b.cut > 0 && g == b.cut - 1) || (b.cut2 > 0 && g == b.cut2 - 1);
    }
    private float fixedH(Pane pn) {
        float f = (pn.title.isEmpty() ? 0 : 3.4f * R) + 2.4f * R + .8f * R;
        for (Block b : pn.blocks) {
            if (!b.title.isEmpty()) f += 2.3f * R;
            for (int k = 0; k < b.rows.size(); k++) if (lineAfter(b, k)) f += 1.5f * R;
        }
        return f;
    }

    // ------------------------------------------------------------------ drawing the board

    private float ease(float a) { a = Math.max(0, Math.min(1, a)); return 1 - (1 - a) * (1 - a) * (1 - a); }

    @Override protected void onDraw(Canvas c) {
        anim = false;
        if (Math.abs(scroll - scrollT) > .5f && motion) { scroll += (scrollT - scroll) * .25f; anim = true; } else scroll = scrollT;
        if (team != null) drawTeam(c, getWidth(), getHeight());
        else drawBoard(c, getWidth(), getHeight());
        if (anim) postInvalidateOnAnimation();
    }

    private void drawBoard(Canvas c, int w, int hh) {
        float pad = 4 * R;
        drawTop(c, w);
        if (chips.isEmpty()) return;
        Object[] got = cache.get(lgId());
        if (got == null) {
            String e = errors.get(lgId());
            if (e != null) {
                text(c, "ESPN didn't send the " + chips.get(sel)[3] + " standings (" + e + ").", pad, boardTop() + 3 * R, fMed, 1.4f, SUB, Paint.Align.LEFT);
                text(c, "Press OK to try again.", pad, boardTop() + 5.2f * R, fMed, 1.2f, TER, Paint.Align.LEFT);
            } else skeleton(c, w);
            return;
        }
        board();
        if (panes.isEmpty()) { text(c, "No " + chips.get(sel)[3] + " standings yet.", pad, boardTop() + 3 * R, fMed, 1.4f, SUB, Paint.Align.LEFT); return; }
        long now = SystemClock.uptimeMillis();
        float top = boardTop(), bot = footTop() - .3f * R;
        c.save(); c.clipRect(0, top - .2f * R, w, bot); c.translate(0, -scroll);
        float[][] cx = new float[panes.size()][];
        for (int i = 0; i < panes.size(); i++) cx[i] = paneBack(c, panes.get(i));
        drawGlow(c);
        for (int i = 0; i < panes.size(); i++) paneRows(c, panes.get(i), i, cx[i], now);
        c.restore();
        if (!zones.isEmpty() && panes.size() == 1) drawZones(c, panes.get(0).x + panes.get(0).w + 2 * R, top);
        drawFoot(c, w);
    }

    /** the leagues (text tabs, the one shown underlined) and the views (one switch with a sliding thumb) */
    private void drawTop(Canvas c, int w) {
        float pad = 4 * R, by = 2.1f * R;
        boolean here = team == null;
        List<String> vs = views();
        viewHits.clear();
        float segL = w - pad;
        if (vs.size() > 1) {
            t.setTypeface(fSemi); t.setTextSize(1.15f * R);
            float[] ws = new float[vs.size()]; float tw = .5f * R;
            for (int i = 0; i < vs.size(); i++) { ws[i] = t.measureText(vs.get(i)) + 2.8f * R; tw += ws[i]; }
            segL = w - pad - tw;
            rf.set(segL, by - 1.35f * R, w - pad, by + 1.35f * R);
            p.setColor(TRACK); c.drawRoundRect(rf, 1.35f * R, 1.35f * R, p);
            stroke(c, rf, 1.35f * R, EDGE, Math.max(1, .07f * R));
            int vi = viewIndex();
            float ax = segL + .25f * R, tx = 0;
            for (int i = 0; i < vs.size(); i++) { if (i == vi) tx = ax; viewHits.add(new RectF(ax, by - 1.35f * R, ax + ws[i], by + 1.35f * R)); ax += ws[i]; }
            if (thumbX < 0 || !motion) { thumbX = tx; thumbW = ws[vi]; }
            thumbX += (tx - thumbX) * .3f; thumbW += (ws[vi] - thumbW) * .3f;
            if (Math.abs(thumbX - tx) > .5f || Math.abs(thumbW - ws[vi]) > .5f) anim = true; else { thumbX = tx; thumbW = ws[vi]; }
            rf.set(thumbX, by - 1.1f * R, thumbX + thumbW, by + 1.1f * R);
            p.setColor(focus == 1 && here ? AMBER : 0xFFE9EEF3); c.drawRoundRect(rf, 1.1f * R, 1.1f * R, p);
            ax = segL + .25f * R;
            for (int i = 0; i < vs.size(); i++) {
                boolean under = Math.abs(thumbX + thumbW / 2 - (ax + ws[i] / 2)) < ws[i] / 2;
                text(c, vs.get(i), ax + ws[i] / 2, by + .42f * R, fSemi, 1.15f, under ? BG : SUB, Paint.Align.CENTER);
                ax += ws[i];
            }
        } else thumbX = -1;
        // the leagues, shifted along when the one shown would be past the room
        chipHits.clear();
        if (chips.isEmpty()) return;
        float room = segL - 3 * R - pad;
        float[] ws = new float[chips.size()];
        t.setTextSize(1.3f * R);
        for (int i = 0; i < chips.size(); i++) { t.setTypeface(i == sel ? fSemi : fMed); ws[i] = t.measureText(chips.get(i)[4]); }
        float selX0 = 0; for (int i = 0; i < sel; i++) selX0 += ws[i] + 2.2f * R;
        float shift = Math.max(0, selX0 + ws[sel] - room + R);
        c.save(); c.clipRect(pad - 1.4f * R, 0, segL - 1.5f * R, 4.4f * R);
        float x = pad - shift, ux = 0;
        boolean pill = focus == 0 && here;
        for (int i = 0; i < chips.size(); i++) {
            boolean on = i == sel;
            chipHits.add(new RectF(x - 1.1f * R, 0, x + ws[i] + 1.1f * R, 4.2f * R));
            if (on) {
                ux = x;
                if (pill) { rf.set(x - 1.1f * R, by - 1.35f * R, x + ws[i] + 1.1f * R, by + 1.35f * R); p.setColor(AMBER); c.drawRoundRect(rf, 1.35f * R, 1.35f * R, p); }
            }
            text(c, chips.get(i)[4], x, by + .45f * R, on ? fSemi : fMed, 1.3f, on ? (pill ? BG : INK) : TER, Paint.Align.LEFT);
            x += ws[i] + 2.2f * R;
        }
        // the underline glides to the league shown
        if (tabX < 0 || !motion) { tabX = ux; tabW = ws[sel]; }
        tabX += (ux - tabX) * .3f; tabW += (ws[sel] - tabW) * .3f;
        if (Math.abs(tabX - ux) > .5f || Math.abs(tabW - ws[sel]) > .5f) anim = true; else { tabX = ux; tabW = ws[sel]; }
        if (!pill) { rf.set(tabX, by + 1.25f * R, tabX + tabW, by + 1.47f * R); p.setColor(INK); c.drawRoundRect(rf, .11f * R, .11f * R, p); }
        c.restore();
    }

    /** a column's panel, its title and column names; returns where each figure column ends (its right edge) */
    private float[] paneBack(Canvas c, Pane pn) {
        float x = pn.x, y = pn.y, w = pn.w, in = 1.3f * R;
        rf.set(x, y, x + w, y + pn.hh);
        p.setColor(PANEL); c.drawRoundRect(rf, 1.2f * R, 1.2f * R, p);
        stroke(c, rf, 1.2f * R, EDGE, Math.max(1, .07f * R));
        float yy = y;
        if (!pn.title.isEmpty()) {
            text(c, pn.title, x + 1.4f * R, yy + 2.45f * R, fSemi, 1.7f, INK, Paint.Align.LEFT);
            if (!pn.note.isEmpty()) { t.setTypeface(fSemi); t.setTextSize(1.7f * R); float tw = t.measureText(pn.title); text(c, pn.note, x + 1.4f * R + tw + R, yy + 2.45f * R, fMed, 1.15f, TER, Paint.Align.LEFT); }
            yy += 3.4f * R;
        }
        float[] cx = new float[fit.length];
        float rx = x + w - in;
        for (int k = fit.length - 1; k >= 0; k--) { cx[k] = rx; rx -= cols.get(fit[k]).w * R; }
        // the column the league ranks by: a faint band down it, its name brighter
        int keyK = keyCol();
        if (keyK >= 0) { float kw = cols.get(fit[keyK]).w * R; p.setColor(0x09FFFFFF); c.drawRect(cx[keyK] - kw + .6f * R, yy + .4f * R, cx[keyK] + .5f * R, y + pn.hh - .5f * R, p); }
        boolean seeded = false; for (Block b : pn.blocks) if (b.seeded) seeded = true;
        if (seeded) text(c, "Seed", x + 1.1f * R, yy + 1.6f * R, fSemi, .95f, TER, Paint.Align.LEFT);
        for (int k = 0; k < fit.length; k++) text(c, cols.get(fit[k]).label, cx[k], yy + 1.6f * R, fSemi, .95f, k == keyK ? SUB : TER, Paint.Align.RIGHT);
        p.setColor(EDGE); c.drawRect(x, yy + 2.2f * R, x + w, yy + 2.2f * R + Math.max(1, .07f * R), p);
        int k0 = 0;
        for (Block b : pn.blocks) {
            if (!b.title.isEmpty() && k0 < pn.rowY.size()) text(c, b.title, x + 1.4f * R, pn.rowY.get(k0) - .7f * R, fSemi, 1.1f, SUB, Paint.Align.LEFT);
            k0 += b.rows.size();
        }
        return cx;
    }
    private void paneRows(Canvas c, Pane pn, int pIndex, float[] cx, long now) {
        String lg = lgId();
        float x = pn.x, w = pn.w, in = 1.3f * R;
        float logoS = Math.min(2.1f * R, rowH - .45f * R), lx = x + 3 * R + (2.1f * R - logoS) / 2, nx = x + 3 * R + 2.1f * R + .9f * R;
        float nameMax = (fit.length > 0 ? cx[0] - cols.get(fit[0]).w * R : x + w - in) - nx - .8f * R;
        int keyK = keyCol();
        for (int k = 0; k < pn.flat.size(); k++) {
            Block b = pn.of.get(k); int i = pn.idx.get(k);
            float ry = pn.rowY.get(k);
            boolean chosen = focus == 2 && pIndex == pi && k == ri;
            // the entrance: each row fades and slides in a moment after the one above
            float a = motion ? ease((now - enterAt - k * 16) / 260f) : 1;
            int save = -1;
            if (a < 1) { anim = true; save = c.saveLayerAlpha(x, ry, x + w, ry + rowH, (int) (a * 255)); c.translate((1 - a) * 1.4f * R, 0); }
            drawRow(c, b, i, x, w, ry, cx, keyK, lx, logoS, nx, nameMax, chosen, lg, k == pn.flat.size() - 1);
            if (save >= 0) c.restoreToCount(save);
            // the playoff line (and the play-in's): a gap with its name in it
            if (lineAfter(b, i)) {
                boolean playIn = b.cut2 > 0 && b.rank0 + i == b.cut2 - 1;
                sep(c, x, w, ry + rowH + .75f * R, playIn ? "Play-in line" : "Playoff line", playIn ? 0xB3FFC23D : AMBER, playIn);
            }
        }
    }
    private int keyCol() {
        String want = pointsSport(chips.get(sel)[1]) ? "PTS" : "PCT";
        for (int k = 0; k < fit.length; k++) if (cols.get(fit[k]).label.equals(want)) return k;
        return -1;
    }
    private void drawRow(Canvas c, Block b, int i, float x, float w, float ry, float[] cx, int keyK,
                         float lx, float logoS, float nx, float nameMax, boolean chosen, String lg, boolean last) {
        Row r = b.rows.get(i);
        float mid = ry + rowH / 2, base = mid + .46f * R;
        boolean fav = favs.contains(lg + ":" + r.id), out = r.clinch.equals("e");
        if (fav && !chosen) { p.setColor(0x14FFC23D); c.drawRect(x + .3f * R, ry, x + w - .3f * R, ry + rowH, p); }
        if (!last && i < b.rows.size() - 1 && !lineAfter(b, i) && !chosen) { p.setColor(0x0BFFFFFF); c.drawRect(x + 1.3f * R, ry + rowH - Math.max(1, .06f * R), x + w - 1.3f * R, ry + rowH, p); }
        // the rail: where this team stands (one run per zone)
        int z = zone(b, i, lg);
        if (z != 0) {
            boolean prev = i > 0 && zone(b, i - 1, lg) == z && !lineAfter(b, i - 1), next = i < b.rows.size() - 1 && zone(b, i + 1, lg) == z && !lineAfter(b, i);
            p.setColor(z); p.setAlpha(230);
            c.drawRect(x, ry + (prev ? 0 : .3f * R), x + .28f * R, ry + rowH - (next ? 0 : .3f * R), p);
            p.setAlpha(255);
        }
        int dimA = out ? 115 : 255;
        int place = b.seeded ? (int) r.seed : b.rank0 + i + 1;
        text(c, String.valueOf(place), x + 2.25f * R, base, fMed, 1.15f, alpha(chosen ? Color.WHITE : z != 0 ? INK : TER, dimA), Paint.Align.RIGHT);
        Bitmap bm = logos.get(r.logo);
        if (bm != null) { p.setAlpha(dimA); rf.set(lx, mid - logoS / 2, lx + logoS, mid + logoS / 2); c.drawBitmap(bm, null, rf, p); p.setAlpha(255); }
        // the name, its clinch letter raised after it
        String mark = r.clinch;
        float markW = 0;
        t.setTypeface(fSemi);
        if (!mark.isEmpty()) { t.setTextSize(.95f * R); markW = t.measureText(mark) + .6f * R; }
        t.setTextSize(1.4f * R);
        String nm = TextUtils.ellipsize(wide ? r.full : r.name, t, Math.max(3 * R, nameMax - markW), TextUtils.TruncateAt.END).toString();
        float nw = t.measureText(nm);
        text(c, nm, nx, base, fSemi, 1.4f, alpha(chosen ? Color.WHITE : fav ? AMBER : INK, dimA), Paint.Align.LEFT);
        if (!mark.isEmpty()) text(c, mark, nx + nw + .45f * R, base - .5f * R, fSemi, .95f, chosen ? Color.WHITE : out ? TER : IN, Paint.Align.LEFT);
        // the figures
        String[] vs = b.vals.get(i);
        for (int k = 0; k < fit.length; k++) {
            String v = fit[k] < vs.length ? vs[fit[k]] : "";
            String lab = cols.get(fit[k]).label;
            if (lab.equals("DIFF") || lab.equals("GD")) { diff(c, v, cx[k], cols.get(fit[k]).w * R, mid, base, chosen, dimA); continue; }
            int col = chosen ? 0xEBFFFFFF : k == keyK ? INK : SUB;
            if (lab.equals("LINE") && !chosen) col = v.startsWith("+") ? 0xFF86E8B4 : v.startsWith("−") ? 0xFFFF9A9A : SUB;
            text(c, v, cx[k], base, k == keyK ? fSemi : fMed, 1.35f, alpha(col, dimA), Paint.Align.RIGHT);
        }
    }
    /** the difference: a small bar either side of zero on a faint track, then the figure */
    private void diff(Canvas c, String v, float right, float colW, float mid, float base, boolean chosen, int dimA) {
        double d = numOf(v);
        float bw = 3.4f * R, cxm = right - colW + .6f * R + bw / 2;
        rf.set(cxm - bw / 2, mid - .22f * R, cxm + bw / 2, mid + .22f * R);
        p.setColor(chosen ? 0x2EFFFFFF : 0x0FFFFFFF); c.drawRoundRect(rf, .22f * R, .22f * R, p);
        float len = (float) Math.min(1, Math.abs(d) / diffMax) * bw / 2;
        if (len > .5f) {
            rf.set(d >= 0 ? cxm : cxm - len, mid - .22f * R, d >= 0 ? cxm + len : cxm, mid + .22f * R);
            p.setColor(chosen ? Color.WHITE : d >= 0 ? IN : RED); p.setAlpha(Math.min(dimA, chosen ? 235 : 215)); c.drawRoundRect(rf, .22f * R, .22f * R, p); p.setAlpha(255);
        }
        p.setColor(chosen ? 0x80FFFFFF : 0x33FFFFFF); float hw = Math.max(.5f, .03f * R); c.drawRect(cxm - hw, mid - .45f * R, cxm + hw, mid + .45f * R, p);
        String s = v.isEmpty() ? "" : d > 0 && !v.startsWith("+") ? "+" + v : v.replace("-", "−");
        text(c, s, right, base, fMed, 1.3f, alpha(chosen ? 0xEBFFFFFF : SUB, dimA), Paint.Align.RIGHT);
    }
    /** a line across a column with its name in a gap in the middle */
    private void sep(Canvas c, float x, float w, float y, String label, int col, boolean thin) {
        t.setTypeface(fSemi); t.setTextSize(.95f * R);
        float lw = t.measureText(label), mx = x + w / 2, sw = thin ? Math.max(1, .07f * R) : Math.max(1.5f, .12f * R);
        p.setColor(col); p.setAlpha(thin ? 110 : 185);
        c.drawRect(x + 1.3f * R, y - sw / 2, mx - lw / 2 - .8f * R, y + sw / 2, p);
        c.drawRect(mx + lw / 2 + .8f * R, y - sw / 2, x + w - 1.3f * R, y + sw / 2, p);
        p.setAlpha(255);
        text(c, label, mx, y + .33f * R, fSemi, .95f, col, Paint.Align.CENTER);
    }
    /** the chosen team's glow: its own colour, strongest at the left, gliding from row to row (under the text) */
    private void drawGlow(Canvas c) {
        if (focus != 2 || pi >= panes.size() || ri >= panes.get(pi).flat.size()) { glowSnap = true; return; }
        Pane pn = panes.get(pi);
        float ry = pn.rowY.get(ri);
        float l = pn.x + .3f * R, tp = ry + .08f * R, rt = pn.x + pn.w - .3f * R, bt = ry + rowH - .08f * R;
        if (glowSnap || !motion) { glow.set(l, tp, rt, bt); glowSnap = false; }
        else {
            glow.left += (l - glow.left) * .32f; glow.right += (rt - glow.right) * .32f;
            glow.top += (tp - glow.top) * .32f; glow.bottom += (bt - glow.bottom) * .32f;
            if (Math.abs(glow.top - tp) > .5f || Math.abs(glow.left - l) > .5f) anim = true; else glow.set(l, tp, rt, bt);
        }
        int col = lift(teamColor(pn.flat.get(ri)), .2);
        p.setShader(new LinearGradient(glow.left, 0, glow.right, 0, new int[] { withA(col, 245), withA(col, 120), withA(col, 46) }, new float[] { 0, .55f, 1 }, Shader.TileMode.CLAMP));
        c.drawRoundRect(glow, .7f * R, .7f * R, p);
        p.setShader(null);
        stroke(c, glow, .7f * R, 0xD9FFFFFF, .12f * R);
    }
    /** soccer: what the coloured rails mean, beside the table */
    private void drawZones(Canvas c, float x, float y) {
        text(c, "Zones", x, y + 2.45f * R, fSemi, 1.4f, INK, Paint.Align.LEFT);
        float yy = y + 5.2f * R;
        Pane pn = panes.get(0);
        for (Object[] z : zones) {
            int col = (Integer) z[0]; String name = (String) z[1];
            int from = -1, to = -1;
            for (int k = 0; k < pn.flat.size(); k++) if (name.equals(pn.flat.get(k).noteText)) { if (from < 0) from = k; to = k; }
            p.setColor(col); c.drawRect(x, yy - 1.1f * R, x + .28f * R, yy + 1.6f * R, p);
            text(c, from < 0 ? "" : from == to ? ordinal(from + 1) : (from + 1) + "–" + (to + 1), x + 1.1f * R, yy - .05f * R, fSemi, 1.05f, SUB, Paint.Align.LEFT);
            t.setTypeface(fMed); t.setTextSize(1.15f * R);
            text(c, TextUtils.ellipsize(name, t, sideW - 1.4f * R, TextUtils.TruncateAt.END).toString(), x + 1.1f * R, yy + 1.4f * R, fMed, 1.15f, INK, Paint.Align.LEFT);
            yy += 4.3f * R;
        }
    }
    /** the footer: where the chosen team stands, and what the marks mean */
    private void drawFoot(Canvas c, int w) {
        float pad = 4 * R, fy = footTop();
        p.setColor(EDGE); c.drawRect(pad, fy + .2f * R, w - pad, fy + .2f * R + Math.max(1, .07f * R), p);
        float base = fy + 2.05f * R;
        // the key, right to left, as much as fits beside the sentence
        float x = w - pad, minX = w * .56f;
        for (int i = legend.size() - 1; i >= 0; i--) {
            Object[] it = legend.get(i);
            int col = (Integer) it[0]; String mark = (String) it[1], label = (String) it[2];
            t.setTypeface(fMed); t.setTextSize(1.05f * R); float lw = t.measureText(label);
            float mw;
            if (mark.equals("rail")) mw = .28f * R; else { t.setTypeface(fSemi); mw = t.measureText(mark); }
            float iw = mw + .7f * R + lw;
            if (x - iw < minX) break;
            text(c, label, x, base, fMed, 1.05f, TER, Paint.Align.RIGHT);
            float mx = x - lw - .7f * R;
            if (mark.equals("rail")) { p.setColor(col); c.drawRect(mx - .28f * R, base - R, mx, base + .2f * R, p); }
            else text(c, mark, mx, base, fSemi, 1.05f, mark.equals("e") ? TER : IN, Paint.Align.RIGHT);
            x -= iw + 2.2f * R;
        }
        float maxW = x - pad - 2 * R;
        if (focus == 2 && pi < panes.size() && ri < panes.get(pi).flat.size()) {
            Row r = panes.get(pi).flat.get(ri);
            t.setTypeface(fMed); t.setTextSize(1.2f * R);
            String s = TextUtils.ellipsize(sentence(r), t, maxW, TextUtils.TruncateAt.END).toString();
            if (s.startsWith(r.name)) {
                text(c, r.name, pad, base, fSemi, 1.2f, INK, Paint.Align.LEFT);
                t.setTypeface(fSemi); t.setTextSize(1.2f * R); float nw = t.measureText(r.name);
                text(c, s.substring(r.name.length()), pad + nw, base, fMed, 1.2f, SUB, Paint.Align.LEFT);
            } else text(c, s, pad, base, fMed, 1.2f, SUB, Paint.Align.LEFT);
        } else {
            String hint = focus == 0 ? "Left and right change the league. Down to choose a team." : "Left and right change the view. Down to choose a team.";
            text(c, hint, pad, base, fMed, 1.1f, TER, Paint.Align.LEFT);
        }
    }
    /** while the standings load: the board's shape, gently breathing */
    private void skeleton(Canvas c, int w) {
        float pad = 4 * R, gap = 2 * R, pw = (w - 2 * pad - gap) / 2, top = boardTop();
        float k = (float) (.5 + .5 * Math.sin(SystemClock.uptimeMillis() / 380.0));
        for (int pn = 0; pn < 2; pn++) {
            float x = pad + pn * (pw + gap);
            rf.set(x, top, x + pw, top + 40 * R); p.setColor(PANEL); c.drawRoundRect(rf, 1.2f * R, 1.2f * R, p);
            for (int i = 0; i < 12; i++) {
                float y = top + 3.2f * R + i * 3 * R;
                p.setColor(TRACK); p.setAlpha((int) (150 + 80 * k));
                rf.set(x + 3 * R, y, x + 5.1f * R, y + 2.1f * R); c.drawOval(rf, p);
                rf.set(x + 6 * R, y + .6f * R, x + 6 * R + (8 + (i * 7 % 5)) * R, y + 1.5f * R); c.drawRoundRect(rf, .45f * R, .45f * R, p);
                rf.set(x + pw - 22 * R, y + .6f * R, x + pw - 1.4f * R, y + 1.5f * R); c.drawRoundRect(rf, .45f * R, .45f * R, p);
                p.setAlpha(255);
            }
        }
        anim = true;
    }

    // ------------------------------------------------------------------ drawing the team page

    private void drawTeam(Canvas c, int w, int hh) {
        float pad = 4 * R;
        Row r = team;
        teamHits.clear();
        float a = motion ? ease((SystemClock.uptimeMillis() - teamAt) / 300f) : 1;
        int save;
        if (a < 1) { anim = true; save = c.saveLayerAlpha(0, 0, w, hh, (int) (a * 255)); c.translate(0, (1 - a) * 1.5f * R); }
        else save = c.save();
        // the header: the team's colour from the left, its logo and name, and the numbers that say where it is
        float hy = .6f * R, hH = 12 * R;
        int col = lift(teamColor(r), .14);
        rf.set(pad, hy, w - pad, hy + hH);
        p.setShader(new LinearGradient(pad, 0, w - pad, 0, new int[] { withA(col, 255), withA(col, 95), PANEL }, new float[] { 0, .55f, 1 }, Shader.TileMode.CLAMP));
        c.drawRoundRect(rf, 1.4f * R, 1.4f * R, p);
        p.setShader(new LinearGradient(0, hy, 0, hy + hH, 0x0FFFFFFF, 0x40000000, Shader.TileMode.CLAMP));
        c.drawRoundRect(rf, 1.4f * R, 1.4f * R, p);
        p.setShader(null);
        stroke(c, rf, 1.4f * R, 0x14FFFFFF, Math.max(1, .07f * R));
        float ls = 8 * R, lx = pad + 2 * R;
        if (teamLogo != null) { rf.set(lx, hy + (hH - ls) / 2, lx + ls, hy + (hH + ls) / 2); c.drawBitmap(teamLogo, null, rf, p); }
        float nx = lx + ls + 2.2f * R;
        t.setTypeface(fSemi); t.setTextSize(3 * R);
        text(c, TextUtils.ellipsize(r.full, t, w * .5f, TextUtils.TruncateAt.END).toString(), nx, hy + 4.6f * R, fSemi, 3f, INK, Paint.Align.LEFT);
        Group g = groupOf(r);
        String sub = td != null && !td.summary.isEmpty() ? td.summary : g != null ? g.name : "";
        text(c, sub, nx, hy + 6.6f * R, fMed, 1.3f, 0xB3FFFFFF, Paint.Align.LEFT);
        float sx = nx;
        for (String[] st : headline(r)) {
            t.setTypeface(fSemi); t.setTextSize(1.9f * R); float vw = t.measureText(st[0]);
            t.setTypeface(fMed); t.setTextSize(1f * R); float lw = t.measureText(st[1]);
            if (sx + Math.max(vw, lw) > w * .72f) break;
            text(c, st[0], sx, hy + 10 * R, fSemi, 1.9f, INK, Paint.Align.LEFT);
            text(c, st[1], sx, hy + 11.3f * R, fMed, 1f, 0x9EFFFFFF, Paint.Align.LEFT);
            sx += Math.max(vw, lw) + 3.2f * R;
        }
        // the form: the last results in order, oldest on the left
        if (td != null && !td.past.isEmpty()) {
            int n = Math.min(10, td.past.size());
            float d = 1.6f * R, gp = .5f * R, x1 = w - pad - 2 * R, x0 = x1 - n * d - (n - 1) * gp;
            text(c, "Last " + n, x1, hy + 4.3f * R, fMed, 1.05f, 0x9EFFFFFF, Paint.Align.RIGHT);
            boolean soccer = teamLg[1].equals("soccer");
            for (int i = 0; i < n; i++) {
                int res = result(td.past.get(n - 1 - i), r.id);
                rf.set(x0 + i * (d + gp), hy + 5.4f * R, x0 + i * (d + gp) + d, hy + 5.4f * R + d);
                p.setColor(res > 0 ? IN : res < 0 ? RED : 0xFF8E99A8); p.setAlpha(235); c.drawRoundRect(rf, .4f * R, .4f * R, p); p.setAlpha(255);
                text(c, res > 0 ? "W" : res < 0 ? "L" : soccer ? "D" : "T", rf.centerX(), rf.top + 1.15f * R, fSemi, .9f, BG, Paint.Align.CENTER);
            }
        }
        float top = hy + hH + 2.4f * R;
        if (td == null || td.loading) {
            text(c, "Getting " + r.name + "'s games…", pad, top + 1.2f * R, fSemi, 1.35f, SUB, Paint.Align.LEFT);
            float kk = (float) (.5 + .5 * Math.sin(SystemClock.uptimeMillis() / 380.0));
            for (int i = 0; i < 3; i++) { float x = pad + i * 38 * R; rf.set(x, top + 2.4f * R, x + 35.6f * R, top + 12.9f * R); p.setColor(PANEL); p.setAlpha((int) (170 + 85 * kk)); c.drawRoundRect(rf, 1.2f * R, 1.2f * R, p); p.setAlpha(255); }
            anim = true;
            c.restoreToCount(save);
            return;
        }
        float aw = 44 * R, ax = pad, bx = ax + aw + 2.4f * R, bw = 38 * R, cx = bx + bw + 2.4f * R, cw = w - pad - cx;
        float fy = top + 2.4f * R, fh = 10.5f * R;
        // the next game, large; the games after it
        List<SportsData.Ev> nx0 = teamList(0);
        text(c, "Next game", ax, top + 1.2f * R, fSemi, 1.35f, INK, Paint.Align.LEFT);
        if (nx0.isEmpty()) {
            rf.set(ax, fy, ax + aw, fy + 4 * R); p.setColor(PANEL); c.drawRoundRect(rf, 1.2f * R, 1.2f * R, p);
            text(c, td.err.isEmpty() ? "No games scheduled." : "ESPN didn't send the schedule (" + td.err + ").", ax + 1.4f * R, fy + 2.5f * R, fMed, 1.2f, SUB, Paint.Align.LEFT);
        } else {
            SportsData.Ev e = nx0.get(0);
            rf.set(ax, fy, ax + aw, fy + fh);
            teamHits.add(new Object[] { new RectF(rf), 0, 0 });
            p.setColor(PANEL); c.drawRoundRect(rf, 1.2f * R, 1.2f * R, p);
            if (tcol == 0 && tsel == 0) chosenFill(c, rf, 1.2f * R, r);
            SportsData.Team opp = other(e, r.id), me = ours(e, r.id);
            if (featLogo != null && opp != null && opp.logo.equals(featFor)) { rf.set(ax + 2.4f * R, fy + (fh - 5 * R) / 2, ax + 7.4f * R, fy + (fh + 5 * R) / 2); c.drawBitmap(featLogo, null, rf, p); }
            float tx = ax + 9.6f * R;
            t.setTypeface(fSemi); t.setTextSize(2.1f * R);
            String vs = (me != null && me.home ? "vs " : "at ") + (opp == null ? "" : opp.name);
            boolean live = e.state.equals("in");
            float scoreW = 0;
            if (live && me != null && opp != null) { t.setTypeface(fSemi); t.setTextSize(2.4f * R); scoreW = t.measureText(me.score + "–" + opp.score) + 2 * R; t.setTextSize(2.1f * R); }
            text(c, TextUtils.ellipsize(vs, t, aw - 11 * R - scoreW, TextUtils.TruncateAt.END).toString(), tx, fy + 3.7f * R, fSemi, 2.1f, INK, Paint.Align.LEFT);
            if (live) {
                rf.set(tx, fy + 4.9f * R, tx + 4.6f * R, fy + 6.7f * R); p.setColor(RED); c.drawRoundRect(rf, .9f * R, .9f * R, p);
                text(c, "Live", rf.centerX(), fy + 6.15f * R, fSemi, 1.05f, Color.WHITE, Paint.Align.CENTER);
                text(c, e.detail, tx + 5.4f * R, fy + 6.2f * R, fMed, 1.25f, SUB, Paint.Align.LEFT);
                if (me != null && opp != null) text(c, me.score + "–" + opp.score, ax + aw - 1.6f * R, fy + 3.9f * R, fSemi, 2.4f, INK, Paint.Align.RIGHT);
            } else {
                String when = new SimpleDateFormat("EEEE, MMMM d", Locale.US).format(new Date(e.at)) + " at " + Reminders.time(h, e.at);
                text(c, when, tx, fy + 6f * R, fMed, 1.25f, SUB, Paint.Align.LEFT);
            }
            if (!e.nets.isEmpty()) text(c, SportsData.join(e.nets, 3, ", "), tx, fy + 8.1f * R, fSemi, 1.15f, TER, Paint.Align.LEFT);
            float gy = fy + fh + 3 * R;
            if (nx0.size() > 1) text(c, "Coming up", ax, gy, fSemi, 1.35f, INK, Paint.Align.LEFT);
            for (int i = 1; i < nx0.size(); i++) {
                float y = gy + 1.2f * R + (i - 1) * 3.4f * R;
                teamHits.add(new Object[] { new RectF(ax, y, ax + aw, y + 3 * R), 0, i });
                listRow(c, nx0.get(i), r, ax, y, aw, tcol == 0 && tsel == i, false);
            }
        }
        // results
        List<SportsData.Ev> ps = teamList(1);
        text(c, "Results", bx, top + 1.2f * R, fSemi, 1.35f, INK, Paint.Align.LEFT);
        if (ps.isEmpty()) { rf.set(bx, fy, bx + bw, fy + 4 * R); p.setColor(PANEL); c.drawRoundRect(rf, 1.2f * R, 1.2f * R, p); text(c, "No results yet this season.", bx + 1.4f * R, fy + 2.5f * R, fMed, 1.2f, SUB, Paint.Align.LEFT); }
        for (int i = 0; i < ps.size(); i++) {
            float y = fy + i * 3.4f * R;
            teamHits.add(new Object[] { new RectF(bx, y, bx + bw, y + 3 * R), 1, i });
            listRow(c, ps.get(i), r, bx, y, bw, tcol == 1 && tsel == i, true);
        }
        // leaders
        if (td.leaders != null && td.leaders.length() > 0 && cw > 18 * R) {
            text(c, td.leadLabel, cx, top + 1.2f * R, fSemi, 1.35f, INK, Paint.Align.LEFT);
            int shown = 0;
            for (int k = 0; k < td.leaders.length() && shown < 5; k++) {
                JSONObject cat = td.leaders.optJSONObject(k);
                JSONArray who = cat == null ? null : cat.optJSONArray("leaders");
                JSONObject one = who == null ? null : who.optJSONObject(0);
                if (one == null) continue;
                JSONObject ath = one.optJSONObject("athlete");
                float y = fy + shown * 4.8f * R;
                rf.set(cx, y, cx + cw, y + 4.4f * R); p.setColor(PANEL); c.drawRoundRect(rf, .8f * R, .8f * R, p);
                text(c, cat.optString("displayName"), cx + 1.2f * R, y + 1.7f * R, fMed, 1.05f, TER, Paint.Align.LEFT);
                t.setTypeface(fMed); t.setTextSize(1.15f * R);
                String val = TextUtils.ellipsize(one.optString("displayValue"), t, cw * .45f, TextUtils.TruncateAt.END).toString();
                float vw = t.measureText(val);
                t.setTypeface(fSemi); t.setTextSize(1.3f * R);
                text(c, TextUtils.ellipsize(ath == null ? "" : ath.optString("displayName"), t, Math.max(4 * R, cw - vw - 3.4f * R), TextUtils.TruncateAt.END).toString(), cx + 1.2f * R, y + 3.4f * R, fSemi, 1.3f, INK, Paint.Align.LEFT);
                text(c, val, cx + cw - 1.2f * R, y + 3.4f * R, fMed, 1.15f, SUB, Paint.Align.RIGHT);
                shown++;
            }
        }
        // the footer
        float fty = footTop();
        p.setColor(EDGE); c.drawRect(pad, fty + .2f * R, w - pad, fty + .2f * R + Math.max(1, .07f * R), p);
        text(c, "OK on a game to watch it, set a reminder or open the game page", pad, fty + 2.05f * R, fMed, 1.1f, TER, Paint.Align.LEFT);
        text(c, "Back returns to the standings", w - pad, fty + 2.05f * R, fMed, 1.1f, TER, Paint.Align.RIGHT);
        c.restoreToCount(save);
    }
    /** the chosen game: the team's colour from the left and a white edge, like the board's chosen row */
    private void chosenFill(Canvas c, RectF box, float rad, Row r) {
        int gc = lift(teamColor(r), .2);
        p.setShader(new LinearGradient(box.left, 0, box.right, 0, withA(gc, 150), withA(gc, 22), Shader.TileMode.CLAMP));
        c.drawRoundRect(box, rad, rad, p); p.setShader(null);
        stroke(c, box, rad, 0xD9FFFFFF, .14f * R);
    }
    /** the header's numbers: record, places, the gap to the line (soccer: points and goal difference), streak */
    private List<String[]> headline(Row r) {
        List<String[]> out = new ArrayList<>();
        String sport = teamLg[1], lg = teamLg[0];
        out.add(new String[] { td != null && !td.record.isEmpty() ? td.record : recordOf(r), "Record" });
        Group g = groupOf(r);
        if (g == null) return out;
        if (sport.equals("soccer")) {
            List<Row> rs = new ArrayList<>(g.rows); sortRows(rs, false);
            out.add(new String[] { ordinal(rs.indexOf(r) + 1), "Position" });
            out.add(new String[] { String.valueOf((int) r.pts), "Points" });
            String gd = r.s.get("GD"); if (gd != null && !gd.isEmpty()) out.add(new String[] { gd, "Goal difference" });
            return out;
        }
        if (hasDivisions()) out.add(new String[] { ordinal(g.rows.indexOf(r) + 1), g.name });
        List<Row> conf = confRows(g);
        int at = conf.indexOf(r);
        String cn = confName(g);
        if (at >= 0) out.add(new String[] { ordinal(at + 1), cn.length() > 18 ? "Conference" : cn });
        int[] cut = cutFor(lg);
        if (cut[0] > 0 && at >= cut[0] && conf.size() > cut[0] && allSeeds(conf) && !lg.equals("wnba")) {
            boolean pts = pointsSport(sport);
            double d = gap(conf.get(cut[0] - 1), r, pts);
            if (d > 0) out.add(new String[] { half(d), pts ? "Points out" : "Games out" });
        }
        String st = r.s.get("STRK"); if (st != null && !st.isEmpty()) out.add(new String[] { st, "Streak" });
        return out;
    }
    /** a game in a list: the date (or W / L), vs / at, the other team, then the time and network, or the score */
    private void listRow(Canvas c, SportsData.Ev e, Row us, float x, float y, float w, boolean on, boolean result) {
        float rh = 3 * R, mid = y + rh / 2, base = mid + .45f * R;
        rf.set(x, y, x + w, y + rh);
        p.setColor(PANEL); c.drawRoundRect(rf, .8f * R, .8f * R, p);
        if (on) chosenFill(c, new RectF(rf), .8f * R, us);
        SportsData.Team me = ours(e, us.id), them = other(e, us.id);
        if (me == null || them == null) return;
        float cx = x + 1.2f * R;
        if (result) {
            int res = result(e, us.id);
            rf.set(cx, mid - .85f * R, cx + 1.7f * R, mid + .85f * R);
            p.setColor(res > 0 ? IN : res < 0 ? RED : 0xFF8E99A8); c.drawRoundRect(rf, .45f * R, .45f * R, p);
            text(c, res > 0 ? "W" : res < 0 ? "L" : teamLg[1].equals("soccer") ? "D" : "T", rf.centerX(), mid + .38f * R, fSemi, 1f, BG, Paint.Align.CENTER);
            cx += 2.6f * R;
        } else {
            text(c, new SimpleDateFormat("EEE, MMM d", Locale.US).format(new Date(e.at)), cx, base, fMed, 1.15f, on ? INK : SUB, Paint.Align.LEFT);
            cx += 9.8f * R;
        }
        text(c, me.home ? "vs" : "at", cx, base, fMed, 1.15f, on ? SUB : TER, Paint.Align.LEFT);
        Bitmap b = logos.get(them.logo);
        float ls = 1.9f * R, lx = cx + 2.6f * R;
        if (b != null) { rf.set(lx, mid - ls / 2, lx + ls, mid + ls / 2); c.drawBitmap(b, null, rf, p); }
        String right = result || e.state.equals("in") ? me.score + "–" + them.score : Reminders.time(h, e.at) + (e.nets.isEmpty() ? "" : "  " + e.nets.get(0));
        String date = result ? new SimpleDateFormat("MMM d", Locale.US).format(new Date(e.at)) : "";
        t.setTypeface(result ? fSemi : fMed); t.setTextSize((result ? 1.3f : 1.1f) * R);
        float rw = t.measureText(right);
        t.setTypeface(fMed); t.setTextSize(1.1f * R);
        float dw = date.isEmpty() ? 0 : t.measureText(date) + 1.6f * R;
        t.setTypeface(fSemi); t.setTextSize(1.3f * R);
        String nm = TextUtils.ellipsize(them.name, t, Math.max(4 * R, x + w - 1.2f * R - rw - dw - 1.2f * R - (lx + ls + .8f * R)), TextUtils.TruncateAt.END).toString();
        text(c, nm, lx + ls + .8f * R, base, fSemi, 1.3f, INK, Paint.Align.LEFT);
        if (!date.isEmpty()) text(c, date, x + w - 1.2f * R - rw - 1.6f * R, base, fMed, 1.1f, on ? SUB : TER, Paint.Align.RIGHT);
        text(c, right, x + w - 1.2f * R, base, result ? fSemi : fMed, result ? 1.3f : 1.1f, result ? INK : on ? SUB : TER, Paint.Align.RIGHT);
    }

    // ------------------------------------------------------------------ small helpers

    private void text(Canvas c, String s, float x, float y, Typeface tf, float size, int col, Paint.Align al) {
        if (s == null || s.isEmpty()) return;
        t.setTypeface(tf); t.setTextSize(size * R); t.setColor(col); t.setTextAlign(al);
        c.drawText(s, x, y, t);
        t.setTextAlign(Paint.Align.LEFT);
    }
    private void stroke(Canvas c, RectF r, float rad, int col, float wdt) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(wdt); p.setColor(col);
        c.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.FILL);
    }
    private static int withA(int c, int a) { return (c & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24); }
    private static int alpha(int c, int a) { return a >= 255 ? c : withA(c, Color.alpha(c) * a / 255); }
}
