package com.topshelf.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sports › Standings: each league's table, from ESPN (the same place the scores come from). The leagues you
 * follow come first in the chips across the top, then the rest; ◀ ▶ changes league, ▲ ▼ scrolls. A league's
 * groups (NFL divisions, NBA conferences, a soccer league's one table…) are cards side by side, each team with
 * its logo, ranked as the league ranks them, the columns that sport uses (W L PCT GB, GP W L OTL PTS,
 * GP W D L GD PTS…), and your teams lit in gold. Kept ten minutes.
 */
final class StandingsView extends View {
    private static final int CARD = 0xFF131A23, EDGE = 0xFF222C38, DIM = 0xFF8E99A8, GOLD = 0xFFFFC23D;
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
    private boolean chipsFocused;
    private float scroll, scrollT, contentH;
    private final Map<String, Object[]> cache = new HashMap<>();          // league → {time, groups}
    private final Set<String> loading = new HashSet<>();
    private final Map<String, String> errors = new HashMap<>();
    private final Map<String, Bitmap> logos = new HashMap<>();
    private final Set<String> favs = new HashSet<>();

    private static final class Col { String label; String[] keys; float w; Col(String l, float w, String... k) { label = l; this.w = w; keys = k; } }
    private static final class Row { String name, logo, id; String[] vals; }
    private static final class Group { String name; List<Row> rows = new ArrayList<>(); List<Col> cols = new ArrayList<>(); }

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
        load();
        invalidate();
    }
    void hide() { setVisibility(GONE); chipsFocused = false; }
    boolean shownNow() { return getVisibility() == VISIBLE; }
    void focusIn() { chipsFocused = true; invalidate(); }

    /** ◀ ▶ league, ▲ ▼ scroll; false: ▲ from the leagues (back up to the tabs) */
    boolean move(char d) {
        if (d == 'l' || d == 'r') {
            int n = Math.max(0, Math.min(chips.size() - 1, sel + (d == 'r' ? 1 : -1)));
            if (n != sel) { sel = n; scroll = scrollT = 0; load(); }
            chipsFocused = true;
        } else if (d == 'u') {
            if (chipsFocused) { chipsFocused = false; invalidate(); return false; }
            if (scrollT <= 0) chipsFocused = true; else scrollT = Math.max(0, scrollT - 14 * R);
        } else if (d == 'd') {
            if (chipsFocused) chipsFocused = false;
            else scrollT = Math.min(Math.max(0, contentH - (getHeight() - 7 * R)), scrollT + 14 * R);
        }
        invalidate();
        return true;
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
            List<Group> gs = null; String err = "";
            String[] urls = {
                "https://site.web.api.espn.com/apis/v2/sports/" + lg[1] + "/" + lg[2] + "/standings?region=us&lang=en&type=0&level=" + level(lg[0]),
                "https://site.api.espn.com/apis/v2/sports/" + lg[1] + "/" + lg[2] + "/standings" };
            for (String u : urls) {
                try {
                    byte[] d = HomeImages.http(u, null);
                    if (d == null) { err = "ESPN didn't answer"; continue; }
                    List<Group> got = parse(new JSONObject(new String(d, "UTF-8")), lg[1]);
                    if (!got.isEmpty()) { gs = got; break; }
                    err = "no standings yet";
                } catch (Exception e) { err = String.valueOf(e.getMessage()); }
            }
            final List<Group> fg = gs; final String fe = err;
            Diag.ev("standings: " + lg[3] + " · " + (fg == null ? "none (" + fe + ")" : fg.size() + " groups"));
            post(new Runnable() { @Override public void run() {
                loading.remove(lg[0]);
                if (fg != null) { cache.put(lg[0], new Object[] { System.currentTimeMillis(), fg }); for (Group g : fg) for (Row r : g.rows) wantLogo(r.logo); }
                else errors.put(lg[0], fe);
                invalidate();
            } });
        } }, "standings").start();
    }
    private void wantLogo(final String u) {
        if (u == null || u.isEmpty() || logos.containsKey(u)) return;
        logos.put(u, null);
        final int s = Math.round(1.9f * R);
        h.imgs.load("stand:" + u, new HomeImages.Source() { @Override public byte[] bytes() throws Exception { return HomeImages.http(u, null); } }, s, s,
                new HomeImages.Done() { @Override public void done(Bitmap b) { if (b != null) { logos.put(u, b); invalidate(); } } });
    }

    /** by division (NFL, MLB, NHL), by conference (NBA, WNBA, college), one table (soccer) */
    private static int level(String id) { return id.equals("nfl") || id.equals("mlb") || id.equals("nhl") ? 3 : 2; }
    /** every part of the answer with a table in it (a league, its conferences, their divisions), the deepest ones */
    private List<Group> parse(JSONObject root, String sport) {
        List<Group> out = new ArrayList<>();
        collect(root, root.optString("name"), out, sport);
        return out;
    }
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
        g.cols = cols(sport);
        List<JSONObject> es = new ArrayList<>();
        for (int i = 0; i < en.length(); i++) if (en.optJSONObject(i) != null) es.add(en.optJSONObject(i));
        sort(es);
        for (JSONObject e : es) {
            JSONObject tm = e.optJSONObject("team"); if (tm == null) continue;
            Row r = new Row();
            r.id = tm.optString("id");
            r.name = tm.optString("shortDisplayName", tm.optString("displayName"));
            JSONArray lg = tm.optJSONArray("logos");
            r.logo = lg != null && lg.length() > 0 ? lg.optJSONObject(0).optString("href") : "";
            JSONArray stats = e.optJSONArray("stats");
            r.vals = new String[g.cols.size()];
            for (int k = 0; k < g.cols.size(); k++) r.vals[k] = stat(stats, g.cols.get(k).keys);
            g.rows.add(r);
        }
        // a column nobody has a value in (ties in a season without any, games behind in a points league) goes
        for (int k = g.cols.size() - 1; k >= 0; k--) {
            boolean any = false;
            for (Row r : g.rows) { String v = r.vals[k]; if (!v.isEmpty() && !v.equals("-") && !(g.cols.get(k).label.equals("T") && v.equals("0"))) { any = true; break; } }
            if (!any) { g.cols.remove(k); for (Row r : g.rows) { String[] nv = new String[r.vals.length - 1]; for (int a = 0, b = 0; a < r.vals.length; a++) if (a != k) nv[b++] = r.vals[a]; r.vals = nv; } }
        }
        if (!g.rows.isEmpty()) out.add(g);
    }
    private static List<Col> cols(String sport) {
        List<Col> c = new ArrayList<>();
        switch (sport) {
            case "football": c.add(new Col("W", 3.2f, "wins", "W")); c.add(new Col("L", 3.2f, "losses", "L")); c.add(new Col("T", 3f, "ties", "T"));
                c.add(new Col("PCT", 4.4f, "winPercent", "PCT")); c.add(new Col("STRK", 4.4f, "streak", "STRK")); break;
            case "hockey": c.add(new Col("GP", 3.4f, "gamesPlayed", "GP")); c.add(new Col("W", 3.2f, "wins", "W")); c.add(new Col("L", 3.2f, "losses", "L"));
                c.add(new Col("OTL", 3.6f, "otLosses", "overtimeLosses", "OTLosses", "OTL")); c.add(new Col("PTS", 3.8f, "points", "PTS")); break;
            case "soccer": c.add(new Col("GP", 3.4f, "gamesPlayed", "GP")); c.add(new Col("W", 3f, "wins", "W")); c.add(new Col("D", 3f, "ties", "D", "T"));
                c.add(new Col("L", 3f, "losses", "L")); c.add(new Col("GD", 3.8f, "pointDifferential", "GD")); c.add(new Col("PTS", 3.8f, "points", "P", "PTS")); break;
            default: c.add(new Col("W", 3.2f, "wins", "W")); c.add(new Col("L", 3.2f, "losses", "L")); c.add(new Col("PCT", 4.4f, "winPercent", "PCT"));
                c.add(new Col("GB", 3.6f, "gamesBehind", "GB")); c.add(new Col("STRK", 4.4f, "streak", "STRK")); break;
        }
        return c;
    }
    private static String stat(JSONArray stats, String[] keys) {
        if (stats == null) return "";
        for (String k : keys) for (int i = 0; i < stats.length(); i++) {
            JSONObject s = stats.optJSONObject(i); if (s == null) continue;
            if (k.equalsIgnoreCase(s.optString("name")) || k.equalsIgnoreCase(s.optString("type")) || k.equals(s.optString("abbreviation"))) {
                String v = s.optString("displayValue", s.optString("summary"));
                if (!v.isEmpty()) return v;
            }
        }
        return "";
    }
    private static double num(JSONObject e, String... keys) {
        JSONArray st = e.optJSONArray("stats"); if (st == null) return Double.NaN;
        for (String k : keys) for (int i = 0; i < st.length(); i++) { JSONObject s = st.optJSONObject(i); if (s != null && (k.equalsIgnoreCase(s.optString("name")) || k.equals(s.optString("abbreviation"))) && s.has("value")) return s.optDouble("value", Double.NaN); }
        return Double.NaN;
    }
    /** as the league ranks them: playoff seed or rank when every team has one, else points, else win %, then wins */
    private static void sort(List<JSONObject> es) {
        boolean seeds = true, ranks = true;
        for (JSONObject e : es) { double s = num(e, "playoffSeed"); if (Double.isNaN(s) || s <= 0) seeds = false; double r = num(e, "rank"); if (Double.isNaN(r) || r <= 0) ranks = false; }
        final String by = seeds ? "playoffSeed" : ranks ? "rank" : null;
        Collections.sort(es, new Comparator<JSONObject>() { @Override public int compare(JSONObject a, JSONObject b) {
            if (by != null) return Double.compare(num(a, by), num(b, by));
            double pa = num(a, "points", "PTS"), pb = num(b, "points", "PTS");
            if (!Double.isNaN(pa) && !Double.isNaN(pb) && pa != pb) return Double.compare(pb, pa);
            double wa = num(a, "winPercent"), wb = num(b, "winPercent");
            if (!Double.isNaN(wa) && !Double.isNaN(wb) && wa != wb) return Double.compare(wb, wa);
            return Double.compare(z(num(b, "wins")), z(num(a, "wins")));
        } });
    }
    private static double z(double v) { return Double.isNaN(v) ? 0 : v; }

    // ------------------------------------------------------------------ drawing

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), hh = getHeight();
        float pad = 4 * R;
        boolean moving = false;
        if (Math.abs(scroll - scrollT) > .5f) { scroll += (scrollT - scroll) * .25f; moving = true; } else scroll = scrollT;
        // the leagues
        float x = pad, cy = 1.9f * R;
        t.setTypeface(cond); t.setTextSize(1.35f * R);
        float selX0 = 0, selX1 = 0;
        float[] ws = new float[chips.size()];
        for (int i = 0; i < chips.size(); i++) ws[i] = t.measureText(chips.get(i)[3]) + 2.6f * R;
        for (int i = 0; i < sel; i++) selX0 += ws[i] + .8f * R;
        selX1 = selX0 + (sel < ws.length ? ws[sel] : 0);
        float shift = Math.max(0, selX1 - (w - 2 * pad));
        c.save(); c.clipRect(0, 0, w, 3.8f * R);
        x = pad - shift;
        for (int i = 0; i < chips.size(); i++) {
            boolean on = i == sel;
            rf.set(x, cy - 1.45f * R, x + ws[i], cy + 1.45f * R);
            p.setStyle(Paint.Style.FILL); p.setColor(on ? (chipsFocused ? GOLD : 0xFFE8EDF2) : 0xFF1A222D);
            c.drawRoundRect(rf, 1.45f * R, 1.45f * R, p);
            t.setColor(on ? 0xFF0A0E13 : 0xFFC6CED8); t.setTextAlign(Paint.Align.CENTER);
            c.drawText(chips.get(i)[3], rf.centerX(), cy + .48f * R, t);
            x += ws[i] + .8f * R;
        }
        c.restore();
        t.setTextAlign(Paint.Align.LEFT);
        if (chips.isEmpty()) return;
        String id = chips.get(sel)[0];
        Object[] got = cache.get(id);
        float top = 5.2f * R;
        if (got == null) {
            t.setTypeface(h.fMed); t.setTextSize(1.5f * R); t.setColor(DIM);
            String e = errors.get(id);
            c.drawText(e != null ? "Couldn't load " + chips.get(sel)[3] + " standings (" + e + ")" : "Loading " + chips.get(sel)[3] + " standings…", pad, top + 3 * R, t);
            if (moving) postInvalidateOnAnimation();
            return;
        }
        @SuppressWarnings("unchecked") List<Group> gs = (List<Group>) got[1];
        // one table split in two when it's long (a soccer league), else the groups side by side
        List<Group> show = new ArrayList<>();
        if (gs.size() == 1 && gs.get(0).rows.size() > 12) {
            Group a = gs.get(0), b = new Group(), d = new Group();
            int half = (a.rows.size() + 1) / 2;
            b.name = a.name; b.cols = a.cols; b.rows = a.rows.subList(0, half);
            d.name = ""; d.cols = a.cols; d.rows = a.rows.subList(half, a.rows.size());
            show.add(b); show.add(d);
        } else show.addAll(gs);
        int maxRows = 0; for (Group g : show) maxRows = Math.max(maxRows, g.rows.size());
        int n = show.size() == 1 ? 1 : show.size() <= 3 ? show.size() : maxRows <= 8 ? 4 : 3;
        float gap = 1.6f * R, cw = n == 1 ? Math.min(w - 2 * pad, 64 * R) : (w - 2 * pad - (n - 1) * gap) / n;
        float rowH = 2.55f * R, headH = 4.6f * R;
        c.save(); c.clipRect(0, 4.2f * R, w, hh); c.translate(0, -scroll);
        float y = top; int col = 0; float lineH = 0;
        int rankBase = 0;
        for (int gi = 0; gi < show.size(); gi++) {
            Group g = show.get(gi);
            float gx = n == 1 ? (w - cw) / 2f : pad + col * (cw + gap);
            float gh = headH + g.rows.size() * rowH + 1.2f * R;
            if (y + gh > scroll && y < scroll + hh) drawGroup(c, g, gx, y, cw, rowH, headH, id, g.name.isEmpty() ? rankBase : 0);
            rankBase = g.name.isEmpty() ? 0 : g.rows.size();
            lineH = Math.max(lineH, gh);
            if (++col >= n) { col = 0; y += lineH + gap; lineH = 0; }
        }
        if (col > 0) y += lineH;
        contentH = y - top + 2 * R;
        c.restore();
        if (moving) postInvalidateOnAnimation();
    }
    private void drawGroup(Canvas c, Group g, float x, float y, float w, float rowH, float headH, String lg, int rankFrom) {
        rf.set(x, y, x + w, y + headH + g.rows.size() * rowH + 1.2f * R);
        p.setStyle(Paint.Style.FILL); p.setColor(CARD); c.drawRoundRect(rf, 1.2f * R, 1.2f * R, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1, .08f * R)); p.setColor(EDGE); c.drawRoundRect(rf, 1.2f * R, 1.2f * R, p); p.setStyle(Paint.Style.FILL);
        float in = 1.2f * R;
        // the name, and the columns' labels (right-aligned, from the right edge)
        t.setTypeface(cond); t.setTextSize(1.45f * R); t.setColor(Color.WHITE); t.setTextAlign(Paint.Align.LEFT);
        if (!g.name.isEmpty()) c.drawText(TextUtils.ellipsize(g.name.toUpperCase(Locale.US), t, w - 2 * in, TextUtils.TruncateAt.END).toString(), x + in, y + 2.1f * R, t);
        float[] cx = new float[g.cols.size()];
        float rx = x + w - in;
        for (int k = g.cols.size() - 1; k >= 0; k--) { cx[k] = rx; rx -= g.cols.get(k).w * R; }
        t.setTypeface(h.fSemi); t.setTextSize(1f * R); t.setColor(DIM); t.setTextAlign(Paint.Align.RIGHT);
        for (int k = 0; k < g.cols.size(); k++) c.drawText(g.cols.get(k).label, cx[k], y + 3.9f * R, t);
        float nameMax = rx - (x + in + 4.6f * R) - .6f * R;
        for (int i = 0; i < g.rows.size(); i++) {
            Row r = g.rows.get(i);
            float ry = y + headH + i * rowH;
            boolean fav = favs.contains(lg + ":" + r.id);
            if (fav) { p.setColor(0x24FFC23D); rf.set(x + .4f * R, ry, x + w - .4f * R, ry + rowH); c.drawRoundRect(rf, .6f * R, .6f * R, p); }
            else if (i % 2 == 1) { p.setColor(0x08FFFFFF); rf.set(x + .4f * R, ry, x + w - .4f * R, ry + rowH); c.drawRect(rf, p); }
            float base = ry + rowH / 2 + .45f * R;
            t.setTypeface(h.fMed); t.setTextSize(1.1f * R); t.setColor(DIM); t.setTextAlign(Paint.Align.RIGHT);
            c.drawText(String.valueOf(rankFrom + i + 1), x + in + 1.6f * R, base, t);
            Bitmap b = logos.get(r.logo);
            float ls = 1.9f * R, lx = x + in + 2.2f * R;
            if (b != null) { rf.set(lx, ry + (rowH - ls) / 2, lx + ls, ry + (rowH + ls) / 2); c.drawBitmap(b, null, rf, p); }
            t.setTypeface(h.fSemi); t.setTextSize(1.3f * R); t.setColor(fav ? GOLD : Color.WHITE); t.setTextAlign(Paint.Align.LEFT);
            c.drawText(TextUtils.ellipsize(r.name, t, nameMax, TextUtils.TruncateAt.END).toString(), lx + ls + .7f * R, base, t);
            t.setTypeface(cond); t.setTextSize(1.3f * R); t.setColor(fav ? GOLD : 0xFFDDE3EA); t.setTextAlign(Paint.Align.RIGHT);
            for (int k = 0; k < g.cols.size() && k < r.vals.length; k++) c.drawText(r.vals[k], cx[k], base, t);
        }
        t.setTextAlign(Paint.Align.LEFT);
    }
}
