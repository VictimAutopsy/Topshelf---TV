package com.topshelf.launcher;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Live stats over a game you're watching full screen: a panel on the right over the picture.
 * Team sports: the score, then each team's players and their stats in this game (◀ ▶ switches team,
 * ▲▼ scrolls). Combat sports: the two fighters of the bout on now (or the main event) side by side —
 * record, age, height, reach, stance… — with the night's card underneath (▲▼ picks a bout).
 * The game comes from the page (which matched the channel to it); the stats come from ESPN's public
 * game summary, fetched here every 20 seconds while the panel is open. Drawn as one view.
 */
final class NativeStats extends View {
    private final NativeHome h;
    private final float R;
    private final TextPaint tTiny = tp(), tSmall = tp(), tBody = tp(), tBold = tp(), tBig = tp(), tScore = tp();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();

    // what's shown
    private JSONObject game;                  // from the page: id, lg, lgName, sport, league, teams, card, bouts…
    private Model model;                      // team sports, from the summary
    private final Map<String, List<String[]>> profiles = new HashMap<>();   // fighter id → [label, value]
    private String status = "";
    private int side = 0, bout = 0;
    private float scroll, scrollTo, contentH, viewH;
    private long updatedAt;
    private int gen;
    boolean shown;

    NativeStats(NativeHome host) {
        super(host.getContext());
        h = host; R = host.R;
        tTiny.setTypeface(h.fMed); tTiny.setTextSize(.88f * R); tTiny.setColor(Ui.WHITE_60); tTiny.setLetterSpacing(.06f);
        tSmall.setTypeface(h.fReg); tSmall.setTextSize(.95f * R); tSmall.setColor(Ui.WHITE_60);
        tBody.setTypeface(h.fMed); tBody.setTextSize(1.08f * R); tBody.setColor(Color.WHITE);
        tBold.setTypeface(h.fSemi); tBold.setTextSize(1.3f * R); tBold.setColor(Color.WHITE);
        tBig.setTypeface(h.fSemi); tBig.setTextSize(1.75f * R); tBig.setColor(Color.WHITE);
        tScore.setTypeface(h.fSemi); tScore.setTextSize(2.3f * R); tScore.setColor(Color.WHITE); tScore.setTextAlign(Paint.Align.RIGHT);
        setVisibility(GONE);
    }
    private static TextPaint tp() { return new TextPaint(Paint.ANTI_ALIAS_FLAG); }

    // ================================================================== showing

    void open(JSONObject g, boolean searching) {
        boolean same = g != null && game != null && g.optString("id").equals(game.optString("id"));
        game = g;
        if (!same) { model = null; side = 0; scroll = scrollTo = 0; bout = defaultBout(); }
        status = g == null ? (searching ? "Looking for a game on this channel…" : "No game found on this channel right now.\nLive stats appear when the channel is showing a game the Sports tab knows about.")
                : model == null && !g.optBoolean("card") ? "Loading live stats…" : "";
        if (!shown) {
            shown = true;
            setVisibility(VISIBLE);
            setAlpha(0f); setTranslationX(3 * R);
            animate().alpha(1f).translationX(0).setDuration(Ui.BASE).setInterpolator(TileView.EASE).start();
        }
        gen++;
        refresh(gen);
        invalidate();
    }
    void close() {
        if (!shown) return;
        shown = false; gen++;
        animate().alpha(0f).translationX(3 * R).setDuration(Ui.FAST).withEndAction(new Runnable() { @Override public void run() { if (!shown) setVisibility(GONE); } }).start();
    }
    /** the page found (or didn't find) the game while the panel is open */
    void gameFound(JSONObject g) { if (shown) open(g, false); }

    boolean key(char d) {
        boolean card = game != null && game.optBoolean("card");
        if (card) {
            JSONArray bs = game.optJSONArray("bouts");
            int n = bs == null ? 0 : bs.length();
            if (d == 'u' && bout > 0) { bout--; fetchFighters(gen); }
            else if (d == 'd' && bout < n - 1) { bout++; fetchFighters(gen); }
            invalidate();
            return true;
        }
        if (d == 'l' || d == 'r') { int ns = d == 'l' ? 0 : 1; if (ns != side) { side = ns; scroll = scrollTo = 0; } }
        else if (d == 'u') scrollBy(-1);
        else if (d == 'd') scrollBy(1);
        invalidate();
        return true;
    }
    private void scrollBy(int dir) {
        float max = Math.max(0, contentH - viewH);
        scrollTo = Math.max(0, Math.min(max, scrollTo + dir * 6.9f * R));
        final float from = scroll;
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0, 1);
        a.setDuration(Ui.FAST); a.setInterpolator(TileView.EASE);
        a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() { @Override public void onAnimationUpdate(android.animation.ValueAnimator v) { scroll = from + (scrollTo - from) * (Float) v.getAnimatedValue(); invalidate(); } });
        a.start();
    }
    private int defaultBout() {
        JSONArray bs = game == null ? null : game.optJSONArray("bouts");
        if (bs == null) return 0;
        for (int i = 0; i < bs.length(); i++) if ("in".equals(bs.optJSONObject(i).optString("state"))) return i;
        return 0;   // the main event (the list is latest first)
    }

    // ================================================================== data

    private static final String ESPN = "https://site.api.espn.com/apis/site/v2/sports/";
    private static JSONObject getJson(String url) throws Exception {
        byte[] b = HomeImages.http(url, null);
        if (b == null || b.length == 0) throw new Exception("no answer");
        return new JSONObject(new String(b, "UTF-8"));
    }

    private void refresh(final int g) {
        if (!shown || game == null || g != gen) return;
        if (game.optBoolean("card")) { fetchFighters(g); return; }
        final JSONObject gm = game;
        h.imgs.run(new Runnable() { @Override public void run() {
            Model m = null; String err = "";
            try { m = Model.from(getJson(ESPN + gm.optString("sport") + "/" + gm.optString("league") + "/summary?event=" + gm.optString("id")), gm); }
            catch (Throwable t) { err = "Couldn't get live stats (" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()) + ")"; Diag.ev("live stats: " + err); }
            final Model mm = m; final String e = err;
            post(new Runnable() { @Override public void run() {
                if (g != gen) return;
                if (mm != null) { model = mm; status = ""; updatedAt = SystemClock.elapsedRealtime(); }
                else if (model == null) status = e;
                invalidate();
                postDelayed(new Runnable() { @Override public void run() { refresh(g); } }, 20000);
            } });
        } });
    }

    private void fetchFighters(final int g) {
        JSONArray bs = game == null ? null : game.optJSONArray("bouts");
        if (bs == null || bout >= bs.length()) return;
        JSONArray fs = bs.optJSONObject(bout).optJSONArray("fighters");
        if (fs == null) return;
        for (int i = 0; i < fs.length(); i++) {
            final String id = fs.optJSONObject(i).optString("id");
            if (id.isEmpty() || profiles.containsKey(id)) continue;
            profiles.put(id, new ArrayList<String[]>());          // (asked once)
            h.imgs.run(new Runnable() { @Override public void run() {
                final List<String[]> rows = new ArrayList<>();
                try { rows.addAll(fighterRows(getJson("https://site.web.api.espn.com/apis/common/v3/sports/mma/ufc/athletes/" + id).optJSONObject("athlete"))); }
                catch (Throwable t) { Diag.ev("live stats: fighter " + id + ": " + t); }
                post(new Runnable() { @Override public void run() { profiles.put(id, rows); invalidate(); } });
            } });
        }
        invalidate();
    }

    /** a fighter's profile as label/value rows (whatever ESPN has for them) */
    private static List<String[]> fighterRows(JSONObject a) {
        List<String[]> out = new ArrayList<>();
        if (a == null) return out;
        add(out, "Age", a.optString("age"));
        add(out, "Height", a.optString("displayHeight"));
        add(out, "Weight", a.optString("displayWeight"));
        add(out, "Reach", a.optString("displayReach", a.optString("reach")));
        JSONObject st = a.optJSONObject("stance"); if (st != null) add(out, "Stance", st.optString("text", st.optString("displayValue")));
        JSONObject wc = a.optJSONObject("weightClass"); if (wc != null) add(out, "Division", wc.optString("text", wc.optString("shortName")));
        JSONObject as = a.optJSONObject("association"); if (as != null) add(out, "Team", as.optString("name"));
        JSONObject ct = a.optJSONObject("flag"); if (ct != null) add(out, "Country", ct.optString("alt"));
        JSONObject ss = a.optJSONObject("statsSummary");
        JSONArray sl = ss == null ? null : ss.optJSONArray("statistics");
        if (sl != null) for (int i = 0; i < sl.length() && i < 6; i++) {
            JSONObject s = sl.optJSONObject(i); if (s == null) continue;
            add(out, s.optString("displayName", s.optString("abbreviation")), s.optString("displayValue"));
        }
        return out;
    }
    private static void add(List<String[]> out, String k, String v) { if (k != null && !k.isEmpty() && v != null && !v.isEmpty() && !v.equals("null") && !v.equals("0")) out.add(new String[] { k, v }); }

    // ================================================================== the summary, made drawable

    static final class Model {
        String head = "";
        final Side[] sides = { new Side(), new Side() };
        final List<String> periods = new ArrayList<>();
        static final class Side { String abbr = "", name = "", score = "", color = ""; final List<String> lines = new ArrayList<>(); final List<Group> groups = new ArrayList<>(); String id = ""; }
        static final class Group { String title; String[] cols; final List<Row> rows = new ArrayList<>(); }
        static final class Row { String name, sub; String[] vals; boolean active, out; }

        static Model from(JSONObject s, JSONObject game) {
            Model m = new Model();
            String sport = game.optString("sport");
            JSONObject comp = null;
            JSONObject hd = s.optJSONObject("header");
            JSONArray comps = hd == null ? null : hd.optJSONArray("competitions");
            if (comps != null && comps.length() > 0) comp = comps.optJSONObject(0);
            String detail = "";
            if (comp != null) {
                JSONObject st = comp.optJSONObject("status"); JSONObject ty = st == null ? null : st.optJSONObject("type");
                if (ty != null) detail = ty.optString("shortDetail", ty.optString("detail"));
                JSONArray cs = comp.optJSONArray("competitors");
                if (cs != null) for (int i = 0; i < cs.length(); i++) {
                    JSONObject c = cs.optJSONObject(i);
                    Side sd = m.sides["home".equals(c.optString("homeAway")) ? 1 : 0];
                    JSONObject t = c.optJSONObject("team");
                    if (t != null) { sd.id = t.optString("id"); sd.abbr = t.optString("abbreviation"); sd.name = t.optString("displayName", t.optString("name")); sd.color = t.optString("color"); }
                    sd.score = c.optString("score");
                    JSONArray ls = c.optJSONArray("linescores");
                    if (ls != null) for (int k = 0; k < ls.length(); k++) { JSONObject l = ls.optJSONObject(k); sd.lines.add(l == null ? "" : l.optString("displayValue", l.optString("value"))); }
                }
            }
            m.head = (game.optString("lgName").isEmpty() ? "" : game.optString("lgName").toUpperCase(Locale.US) + "  ·  ") + detail;
            // players, by team
            JSONObject box = s.optJSONObject("boxscore");
            JSONArray pl = box == null ? null : box.optJSONArray("players");
            if (pl != null) for (int i = 0; i < pl.length(); i++) {
                JSONObject tp = pl.optJSONObject(i); if (tp == null) continue;
                JSONObject t = tp.optJSONObject("team");
                Side sd = sideFor(m, t == null ? "" : t.optString("id"), i);
                JSONArray groups = tp.optJSONArray("statistics");
                if (groups != null) for (int gi = 0; gi < groups.length(); gi++) {
                    JSONObject g = groups.optJSONObject(gi); if (g == null) continue;
                    JSONArray labels = g.optJSONArray("labels"), aths = g.optJSONArray("athletes");
                    if (labels == null || aths == null || aths.length() == 0) continue;
                    String gname = g.optString("name", g.optString("text"));
                    int[] pick = pickCols(sport, gname, labels);
                    Group out = new Group();
                    out.title = groups.length() == 1 ? "Players" : nice(g.optString("text", gname));
                    out.cols = new String[pick.length];
                    for (int k = 0; k < pick.length; k++) out.cols[k] = labels.optString(pick[k]);
                    for (int a = 0; a < aths.length(); a++) {
                        JSONObject at = aths.optJSONObject(a); if (at == null) continue;
                        JSONObject ath = at.optJSONObject("athlete");
                        JSONArray st = at.optJSONArray("stats");
                        Row r = new Row();
                        r.name = ath == null ? "?" : ath.optString("displayName", ath.optString("shortName"));
                        String jer = ath == null ? "" : ath.optString("jersey"); JSONObject pos = ath == null ? null : ath.optJSONObject("position");
                        r.sub = ((jer.isEmpty() ? "" : "#" + jer) + (pos == null ? "" : "  " + pos.optString("abbreviation"))).trim();
                        r.out = at.optBoolean("didNotPlay") || st == null || st.length() == 0;
                        r.active = at.optBoolean("active") && !r.out;
                        r.vals = new String[pick.length];
                        for (int k = 0; k < pick.length; k++) r.vals[k] = st == null ? "" : st.optString(pick[k], "");
                        out.rows.add(r);
                    }
                    sd.groups.add(out);
                }
            }
            // soccer: line-ups with their numbers
            JSONArray rosters = s.optJSONArray("rosters");
            if (rosters != null) for (int i = 0; i < rosters.length(); i++) {
                JSONObject ro = rosters.optJSONObject(i); if (ro == null) continue;
                JSONObject t = ro.optJSONObject("team");
                Side sd = sideFor(m, t == null ? "" : t.optString("id"), i);
                if (!sd.groups.isEmpty()) continue;
                JSONArray rs = ro.optJSONArray("roster"); if (rs == null) continue;
                String[] want = { "G", "A", "SH", "ST", "YC", "RC" };
                Group starters = new Group(), subs = new Group();
                starters.title = "Starting line-up"; subs.title = "Substitutes";
                List<String> cols = new ArrayList<>();
                for (String w : want) for (int a = 0; a < rs.length(); a++) { if (statOf(rs.optJSONObject(a), w) != null) { cols.add(w); break; } }
                starters.cols = subs.cols = cols.toArray(new String[0]);
                for (int a = 0; a < rs.length(); a++) {
                    JSONObject p = rs.optJSONObject(a); if (p == null) continue;
                    JSONObject ath = p.optJSONObject("athlete");
                    Row r = new Row();
                    r.name = ath == null ? "?" : ath.optString("displayName");
                    JSONObject pos = p.optJSONObject("position");
                    r.sub = (("#" + p.optString("jersey")).replace("#null", "").replace("#", p.optString("jersey").isEmpty() ? "" : "#") + (pos == null ? "" : "  " + pos.optString("abbreviation"))).trim();
                    r.vals = new String[cols.size()];
                    for (int k = 0; k < cols.size(); k++) { String v = statOf(p, cols.get(k)); r.vals[k] = v == null ? "" : v; }
                    r.active = p.optBoolean("starter") ? !p.optBoolean("subbedOut") : p.optBoolean("subbedIn");
                    (p.optBoolean("starter") ? starters : subs).rows.add(r);
                }
                if (!starters.rows.isEmpty()) sd.groups.add(starters);
                if (!subs.rows.isEmpty()) sd.groups.add(subs);
            }
            return m;
        }
        private static String statOf(JSONObject p, String abbr) {
            JSONArray st = p == null ? null : p.optJSONArray("stats");
            if (st != null) for (int i = 0; i < st.length(); i++) { JSONObject s = st.optJSONObject(i); if (s != null && abbr.equalsIgnoreCase(s.optString("abbreviation"))) return s.optString("displayValue"); }
            return null;
        }
        private static Side sideFor(Model m, String teamId, int i) {
            for (Side s : m.sides) if (!teamId.isEmpty() && teamId.equals(s.id)) return s;
            return m.sides[Math.min(1, i)];
        }
        private static String nice(String s) { if (s == null || s.isEmpty()) return ""; return Character.toUpperCase(s.charAt(0)) + s.substring(1); }
        /* which columns fit, by sport and kind of stats: the ones fans look at first */
        private static int[] pickCols(String sport, String group, JSONArray labels) {
            String g = group == null ? "" : group.toLowerCase(Locale.US);
            String[] want;
            if (sport.equals("basketball")) want = new String[] { "MIN", "PTS", "REB", "AST", "FG", "3PT" };
            else if (sport.equals("football")) {
                if (g.startsWith("pass")) want = new String[] { "C/ATT", "YDS", "TD", "INT", "QBR" };
                else if (g.startsWith("rush")) want = new String[] { "CAR", "YDS", "AVG", "TD", "LONG" };
                else if (g.startsWith("receiv")) want = new String[] { "REC", "YDS", "TD", "TGTS", "LONG" };
                else if (g.startsWith("defens")) want = new String[] { "TOT", "SOLO", "SACKS", "TFL", "PD" };
                else if (g.startsWith("kick") && !g.contains("return")) want = new String[] { "FG", "PCT", "LONG", "XP", "PTS" };
                else want = new String[0];
            } else if (sport.equals("baseball")) {
                if (g.startsWith("pitch")) want = new String[] { "IP", "H", "R", "ER", "BB", "K" };
                else want = new String[] { "AB", "R", "H", "RBI", "HR", "BB" };
            } else if (sport.equals("hockey")) {
                if (g.contains("goal")) want = new String[] { "SA", "GA", "SV", "SV%", "TOI" };
                else want = new String[] { "G", "A", "+/-", "S", "TOI" };
            } else want = new String[0];
            List<Integer> idx = new ArrayList<>();
            for (String w : want) for (int i = 0; i < labels.length(); i++) if (w.equalsIgnoreCase(labels.optString(i)) && !idx.contains(i)) { idx.add(i); break; }
            if (idx.isEmpty()) for (int i = 0; i < Math.min(5, labels.length()); i++) idx.add(i);
            int[] out = new int[Math.min(6, idx.size())];
            for (int i = 0; i < out.length; i++) out[i] = idx.get(i);
            return out;
        }
    }

    // ================================================================== drawing

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), hh = getHeight();
        if (w == 0) return;
        rf.set(0, 0, w, hh);
        fill.setColor(0xEB0D0E13); c.drawRoundRect(rf, 2 * R, 2 * R, fill);
        fill.setStyle(Paint.Style.STROKE); fill.setStrokeWidth(Math.max(1, .1f * R)); fill.setColor(Ui.WHITE_12);
        rf.inset(.05f * R, .05f * R); c.drawRoundRect(rf, 2 * R, 2 * R, fill); fill.setStyle(Paint.Style.FILL);
        float pad = 2.2f * R, x0 = pad, x1 = w - pad;
        float y = pad;
        // the title line
        tTiny.setColor(SportsLook.LIVE);
        c.drawText("LIVE STATS", x0, y + tTiny.getTextSize(), tTiny);
        tTiny.setColor(Ui.WHITE_60);
        y += 1.9f * R;
        if (game != null && game.optBoolean("card")) { drawCard(c, x0, x1, y, hh, pad); return; }
        Model m = model;
        String head = m != null && !m.head.isEmpty() ? m.head : game != null ? game.optString("lgName") : "";
        if (!head.isEmpty()) { c.drawText(ell(head, tSmall, x1 - x0), x0, y + tSmall.getTextSize(), tSmall); y += 2 * R; }
        // the two teams
        for (int i = 0; i < 2; i++) {
            String abbr, name, score, col;
            if (m != null && !m.sides[i].abbr.isEmpty()) { Model.Side s = m.sides[i]; abbr = s.abbr; name = s.name; score = s.score; col = s.color; }
            else {
                JSONArray ts = game == null ? null : game.optJSONArray("teams");
                JSONObject t = ts != null && ts.length() > i ? ts.optJSONObject(i) : null;
                abbr = t == null ? "" : t.optString("abbr"); name = t == null ? "" : t.optString("full", t.optString("name")); score = t == null ? "" : t.optString("score"); col = t == null ? "" : t.optString("color").replace("#", "");
            }
            if (abbr.isEmpty() && name.isEmpty()) continue;
            fill.setColor(parse(col, 0xFF5A5A66));
            rf.set(x0, y + .35f * R, x0 + .4f * R, y + 2.7f * R); c.drawRoundRect(rf, .2f * R, .2f * R, fill);
            c.drawText(abbr, x0 + 1.2f * R, y + 1.9f * R, tBig);
            float aw = tBig.measureText(abbr) + 1.9f * R;
            tSmall.setColor(Ui.WHITE_60);
            c.drawText(ell(name, tSmall, x1 - x0 - aw - 6 * R), x0 + aw, y + 1.85f * R, tSmall);
            tSmall.setColor(Ui.WHITE_60);
            c.drawText(score == null ? "" : score, x1, y + 2.2f * R, tScore);
            y += 3.2f * R;
        }
        if (game == null || m == null) { drawStatus(c, x0, x1, y + 2 * R); return; }
        // whose players: ◀ ▶
        y += .6f * R;
        float tabW = (x1 - x0 - R) / 2;
        for (int i = 0; i < 2; i++) {
            Model.Side s = m.sides[i];
            rf.set(x0 + i * (tabW + R), y, x0 + i * (tabW + R) + tabW, y + 2.6f * R);
            boolean on = i == side;
            fill.setColor(on ? Color.WHITE : Ui.WHITE_7); c.drawRoundRect(rf, 1.3f * R, 1.3f * R, fill);
            tBody.setColor(on ? 0xFF111111 : Ui.WHITE_85);
            String lab = (i == 0 ? "◀  " : "") + (s.abbr.isEmpty() ? (i == 0 ? "Away" : "Home") : s.abbr) + " players" + (i == 1 ? "  ▶" : "");
            tBody.setTextAlign(Paint.Align.CENTER);
            c.drawText(lab, rf.centerX(), rf.centerY() + tBody.getTextSize() * .36f, tBody);
            tBody.setTextAlign(Paint.Align.LEFT); tBody.setColor(Color.WHITE);
        }
        y += 3.6f * R;
        // the players (scrolls)
        float top = y, bottom = hh - 3.2f * R;
        viewH = bottom - top;
        c.save(); c.clipRect(0, top, w, bottom);
        float cy = top - scroll;
        Model.Side s = m.sides[side];
        if (s.groups.isEmpty()) { tSmall.setColor(Ui.WHITE_60); c.drawText("No player stats yet — they appear once the game starts.", x0, cy + 1.2f * R, tSmall); tSmall.setColor(Ui.WHITE_60); }
        float colW = 4.3f * R;
        for (Model.Group g : s.groups) {
            int nc = g.cols.length;
            float valX0 = x1 - nc * colW;
            // the group's title and column names
            tTiny.setColor(Ui.WHITE_75);
            c.drawText(g.title.toUpperCase(Locale.US), x0, cy + 1.5f * R, tTiny);
            tTiny.setTextAlign(Paint.Align.RIGHT);
            for (int k = 0; k < nc; k++) c.drawText(g.cols[k], valX0 + (k + 1) * colW, cy + 1.5f * R, tTiny);
            tTiny.setTextAlign(Paint.Align.LEFT); tTiny.setColor(Ui.WHITE_60);
            cy += 2.3f * R;
            fill.setColor(Ui.WHITE_12); c.drawRect(x0, cy - .45f * R, x1, cy - .45f * R + Math.max(1, .08f * R), fill);
            for (Model.Row r : g.rows) {
                float rh = 2.3f * R;
                if (cy + rh > top - rh && cy < bottom + rh) {
                    if (r.active) { fill.setColor(SportsLook.LIVE); c.drawCircle(x0 + .3f * R, cy + 1.05f * R, .26f * R, fill); }
                    tBody.setColor(r.out ? Ui.WHITE_40 : Color.WHITE);
                    float nameW = valX0 - x0 - 1.2f * R - 4.2f * R;
                    c.drawText(ell(r.name, tBody, nameW), x0 + 1.1f * R, cy + 1.35f * R, tBody);
                    tSmall.setColor(0x73FFFFFF);
                    c.drawText(ell(r.sub, tSmall, 4.2f * R), x0 + 1.1f * R + Math.min(nameW, tBody.measureText(ell(r.name, tBody, nameW))) + .7f * R, cy + 1.35f * R, tSmall);
                    tSmall.setColor(Ui.WHITE_60);
                    tBody.setTextAlign(Paint.Align.RIGHT);
                    if (r.out) { tBody.setColor(Ui.WHITE_40); c.drawText("DNP", x1, cy + 1.35f * R, tBody); }
                    else for (int k = 0; k < nc; k++) c.drawText(r.vals[k] == null ? "" : r.vals[k], valX0 + (k + 1) * colW, cy + 1.35f * R, tBody);
                    tBody.setTextAlign(Paint.Align.LEFT); tBody.setColor(Color.WHITE);
                }
                cy += rh;
            }
            cy += 1.4f * R;
        }
        contentH = cy + scroll - top;
        c.restore();
        // a hint of more below / above
        if (scroll > 1) { fill.setColor(0x400D0E13); c.drawRect(0, top, w, top + .1f * R, fill); }
        drawFoot(c, x0, x1, hh, "◀ ▶  team   ·   ▲▼  scroll   ·   Back  close");
    }

    private void drawCard(Canvas c, float x0, float x1, float y, int hh, float pad) {
        JSONArray bs = game.optJSONArray("bouts");
        tSmall.setColor(Ui.WHITE_60);
        c.drawText(ell(game.optString("name", game.optString("short")), tSmall, x1 - x0), x0, y + tSmall.getTextSize(), tSmall);
        y += 2.2f * R;
        JSONObject b = bs != null && bout < bs.length() ? bs.optJSONObject(bout) : null;
        if (b == null) { drawStatus(c, x0, x1, y + R); return; }
        JSONArray fs = b.optJSONArray("fighters");
        String st = b.optString("state");
        tTiny.setColor(st.equals("in") ? SportsLook.LIVE : Ui.WHITE_60);
        c.drawText((st.equals("in") ? "● ON NOW" : st.equals("post") ? "RESULT" : "UP NEXT") + "  ·  " + b.optString("cls") + (b.optString("detail").isEmpty() ? "" : "  ·  " + b.optString("detail")), x0, y + tTiny.getTextSize(), tTiny);
        tTiny.setColor(Ui.WHITE_60);
        y += 2 * R;
        // the two fighters, side by side
        float colW = (x1 - x0) / 2;
        List<String[]> pa = null, pb = null;
        for (int i = 0; i < 2 && fs != null && i < fs.length(); i++) {
            JSONObject f = fs.optJSONObject(i);
            float cx = i == 0 ? x0 : x1;
            Paint.Align al = i == 0 ? Paint.Align.LEFT : Paint.Align.RIGHT;
            tBold.setTextAlign(al); tSmall.setTextAlign(al);
            String nm = f.optString("name");
            int sp = nm.lastIndexOf(' ');
            tSmall.setColor(Ui.WHITE_60);
            c.drawText(ell(sp > 0 ? nm.substring(0, sp) : "", tSmall, colW - R), cx, y + tSmall.getTextSize(), tSmall);
            c.drawText(ell(sp > 0 ? nm.substring(sp + 1) : nm, tBig, colW - R), cx, y + 3.2f * R, setAlign(tBig, al));
            tSmall.setColor(f.optBoolean("winner") ? SportsLook.GREEN : Ui.WHITE_60);
            c.drawText((f.optBoolean("winner") ? "Winner · " : "") + f.optString("rec"), cx, y + 4.8f * R, tSmall);
            tBold.setTextAlign(Paint.Align.LEFT); tSmall.setTextAlign(Paint.Align.LEFT); tBig.setTextAlign(Paint.Align.LEFT);
            List<String[]> p = profiles.get(f.optString("id"));
            if (i == 0) pa = p; else pb = p;
        }
        y += 6.4f * R;
        // the tale of the tape: label in the middle, each fighter's value on their side
        List<String> labels = new ArrayList<>();
        for (List<String[]> p : new List[] { pa, pb }) if (p != null) for (String[] r : p) if (!labels.contains(r[0])) labels.add(r[0]);
        float cardTop = hh - 3.2f * R - Math.min(bs.length(), 6) * 2.2f * R - 2.4f * R;
        if (labels.isEmpty()) {
            tSmall.setColor(Ui.WHITE_60); c.drawText(pa == null && pb == null ? "" : "Loading the fighters' profiles…", x0, y + R, tSmall);
        }
        for (String lab : labels) {
            if (y + 2.2f * R > cardTop) break;
            fill.setColor(0x0FFFFFFF); rf.set(x0 - .6f * R, y, x1 + .6f * R, y + 2f * R); c.drawRoundRect(rf, .6f * R, .6f * R, fill);
            tTiny.setTextAlign(Paint.Align.CENTER); c.drawText(lab.toUpperCase(Locale.US), (x0 + x1) / 2, y + 1.3f * R, tTiny); tTiny.setTextAlign(Paint.Align.LEFT);
            tBody.setColor(Color.WHITE);
            c.drawText(ell(valOf(pa, lab), tBody, colW - 6 * R), x0 + .2f * R, y + 1.4f * R, tBody);
            tBody.setTextAlign(Paint.Align.RIGHT); c.drawText(ell(valOf(pb, lab), tBody, colW - 6 * R), x1 - .2f * R, y + 1.4f * R, tBody); tBody.setTextAlign(Paint.Align.LEFT);
            y += 2.3f * R;
        }
        // the card (▲▼ picks a bout)
        float cy = cardTop;
        tTiny.setColor(Ui.WHITE_60); c.drawText("THE CARD", x0, cy + 1.2f * R, tTiny);
        cy += 2.2f * R;
        int first = Math.max(0, Math.min(bout - 2, bs.length() - 6));
        for (int i = first; i < Math.min(bs.length(), first + 6); i++) {
            JSONObject bb = bs.optJSONObject(i);
            JSONArray ff = bb.optJSONArray("fighters");
            String vs = ff != null && ff.length() == 2 ? last(ff.optJSONObject(0).optString("name")) + " vs " + last(ff.optJSONObject(1).optString("name")) : "?";
            boolean on = i == bout;
            if (on) { fill.setColor(Ui.WHITE_12); rf.set(x0 - .6f * R, cy, x1 + .6f * R, cy + 2f * R); c.drawRoundRect(rf, .6f * R, .6f * R, fill); }
            String s2 = bb.optString("state");
            tBody.setColor(on ? Color.WHITE : Ui.WHITE_75);
            c.drawText(ell(vs, tBody, (x1 - x0) * .62f), x0 + .2f * R, cy + 1.4f * R, tBody);
            tSmall.setTextAlign(Paint.Align.RIGHT); tSmall.setColor(s2.equals("in") ? SportsLook.LIVE : Ui.WHITE_50);
            c.drawText(s2.equals("in") ? "● Live" : s2.equals("post") ? "Final" : bb.optString("cls"), x1 - .2f * R, cy + 1.4f * R, tSmall);
            tSmall.setTextAlign(Paint.Align.LEFT); tSmall.setColor(Ui.WHITE_60);
            cy += 2.2f * R;
        }
        tBody.setColor(Color.WHITE);
        drawFoot(c, x0, x1, hh, "▲▼  pick a bout   ·   Back  close");
    }
    private static String valOf(List<String[]> p, String lab) { if (p != null) for (String[] r : p) if (r[0].equals(lab)) return r[1]; return "—"; }
    private static String last(String n) { int i = n.lastIndexOf(' '); return i > 0 ? n.substring(i + 1) : n; }
    private static TextPaint setAlign(TextPaint p, Paint.Align a) { p.setTextAlign(a); return p; }

    private void drawStatus(Canvas c, float x0, float x1, float y) {
        if (status.isEmpty()) return;
        tBody.setColor(Ui.WHITE_85);
        for (String line : status.split("\n")) {
            android.text.StaticLayout sl = new android.text.StaticLayout(line, tBody, (int) (x1 - x0), android.text.Layout.Alignment.ALIGN_NORMAL, 1.15f, 0, false);
            c.save(); c.translate(x0, y); sl.draw(c); c.restore();
            y += sl.getHeight() + .8f * R;
        }
        tBody.setColor(Color.WHITE);
    }
    private void drawFoot(Canvas c, float x0, float x1, int hh, String hint) {
        tTiny.setColor(Ui.WHITE_50);
        c.drawText(hint, x0, hh - 1.4f * R, tTiny);
        if (updatedAt > 0) {
            tTiny.setTextAlign(Paint.Align.RIGHT);
            long s = (SystemClock.elapsedRealtime() - updatedAt) / 1000;
            c.drawText(s < 5 ? "Updated just now" : "Updated " + s + " s ago", x1, hh - 1.4f * R, tTiny);
            tTiny.setTextAlign(Paint.Align.LEFT);
        }
        tTiny.setColor(Ui.WHITE_60);
    }
    private static String ell(String s, TextPaint p, float w) { return s == null ? "" : TextUtils.ellipsize(s, p, Math.max(1, w), TextUtils.TruncateAt.END).toString(); }
    private static int parse(String hex, int def) { try { return hex == null || hex.length() != 6 ? def : 0xFF000000 | Integer.parseInt(hex, 16); } catch (Exception e) { return def; } }
}
