package com.topshelf.launcher;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sports, drawn by Android, laid out like a game-day broadcast ("Gameday"): a score strip running across
 * the top, the game that matters most as a big scoreboard split in the two teams' colours (the clock, the
 * down and distance and where the ball is, the count and the runners, the last play, the chance each side
 * wins), your teams down the right, the leagues as chips, and that league's games as scorebugs.
 *
 * Every card, the scoreboard, your-teams column and the strip are painted into one picture each (off the
 * main thread) whenever their games change, so moving around is just moving pictures.
 */
final class NativeSports extends FrameLayout {
    /* Fading in, this pane is drawn piece by piece at the fading strength rather than into one picture
       first: its rows and pictures are still arriving as it fades, and each one spoilt that picture,
       so the whole screen was drawn twice a frame (switching tabs ran at about 20 frames a second). */
    @Override public boolean hasOverlappingRendering() { return false; }
    private static final float PAD = 4, CW = 21.3f, CH = 15.6f, GAP = 1.4f, ROW_H = 22f, HERO_Y = 10.4f, SIDE_GAP = 2.2f, TABS_Y = 5.4f, RIB_H = 4.2f;
    /* (a phone held upright stacks them: the scoreboard full width, your teams under it, then the leagues and the games) */
    private float ROWS_Y = 43.2f, HERO_H = 27f, SIDE_W = 33.4f, RAIL_Y = 38.8f, sideY = HERO_Y;
    /* a broadcast's colours: live is a hot orange, "still to come" a gold, done a grey; cards are a dark slate */
    private static final int LIVE_C = 0xFFFF7A59, GOLD = 0xFFFFC23D, DIMTXT = 0xFF8E99A8, CARD = 0xFF131A23, EDGE = 0xFF222C38, GROUND = 0xFF0A0E13;

    private final NativeHome h;
    private final float R;
    private final int W, H;
    private final float SW;
    private boolean shown;
    /* condensed type for the scores, names and labels (the TV's own Roboto Condensed), at bold and extra bold */
    private final Typeface cond, heavy;

    private JSONObject data = new JSONObject();
    private final List<JSONArray> rows = new ArrayList<>();
    private final List<String> titles = new ArrayList<>(), notes = new ArrayList<>();
    private int r = 0;
    private final Map<Integer, Integer> cols = new HashMap<>();
    private final Map<Integer, Float> scrolls = new HashMap<>();

    // views
    private final ImageView bg;
    private final Rail rail;
    /* What comes in is every row (live now, my teams, each league); the screen shows one set at a time, picked
       with the tabs (Live Now · Today · My Teams · Fantasy) and, for Today, the league chips. */
    private final List<JSONArray> allRows = new ArrayList<>();
    private final List<String> allTitles = new ArrayList<>(), allNotes = new ArrayList<>();
    private int liveIdx = -1, mineIdx = -1, curSet = -1;
    private final List<String> tabs = new ArrayList<>();
    private String tab = "", zone = "grid";          // zone: tabs | hero | side | rail | grid
    private int leagueSel = 0;
    private JSONObject featured;                      // the game on the scoreboard
    private final LinearLayout tabRow;
    private final View heroRing, sideRing;
    private final TextView liveChip, updated;
    private final ImageView[] hero = new ImageView[2];
    private int heroTop = -1;
    private final FrameLayout rowsBox, rowsIn;
    private final View glow;
    private final StandingsView standings;
    private final TextView setupTitle, setupSub;
    private final Map<String, CardView> cardViews = new HashMap<>();
    private CardView focused;
    // the score strip (two copies of one picture, side by side, sliding left) and the tabs: they step aside while the app's bars are up
    private final FrameLayout chrome, ribBox;
    private final LinearLayout ribRun;
    private final ImageView[] rib = new ImageView[2];
    private ObjectAnimator ribAnim;
    private int ribW;
    // your teams, down the right of the scoreboard
    private final ImageView side;
    private static final class Side {
        final String kind; final JSONObject team, game; final int idx;     // kind: team | fy | edit
        Side(String k, JSONObject t, JSONObject g, int i) { kind = k; team = t; game = g; idx = i; }
    }
    private final List<Side> sideItems = new ArrayList<>();
    private int sideSel = 0;
    private final java.util.concurrent.ExecutorService painter = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final android.util.LruCache<String, Bitmap> cards = new android.util.LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    NativeSports(NativeHome host) {
        super(host.getContext());
        h = host; R = host.R; W = host.W; H = host.H; SW = host.SW;
        if (h.portrait) { HERO_H = 31f; SIDE_W = SW - 2 * PAD; sideY = HERO_Y + HERO_H + 1.6f; }
        setClipChildren(true);
        setBackgroundColor(GROUND);
        android.content.Context c = getContext();
        cond = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        heavy = Build.VERSION.SDK_INT >= 28 ? Typeface.create(Typeface.create("sans-serif-condensed", Typeface.NORMAL), 800, false) : cond;

        bg = new ImageView(c);
        bg.setScaleType(ImageView.ScaleType.FIT_XY);
        addView(bg, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // the score strip and the tabs
        chrome = new FrameLayout(c);
        chrome.setClipChildren(true);
        chrome.setAlpha(0f);
        addView(chrome, new LayoutParams(LayoutParams.MATCH_PARENT, h.px(TABS_Y + 3.8f)));
        ribBox = new FrameLayout(c);
        ribBox.setClipChildren(true);
        ribBox.setBackgroundColor(0xFF05080B);
        ribBox.setVisibility(GONE);
        chrome.addView(ribBox, new LayoutParams(LayoutParams.MATCH_PARENT, h.px(RIB_H)));
        ribRun = new LinearLayout(c);
        for (int i = 0; i < 2; i++) {
            rib[i] = new ImageView(c);
            rib[i].setScaleType(ImageView.ScaleType.FIT_XY);
            ribRun.addView(rib[i], new LinearLayout.LayoutParams(1, h.px(RIB_H)));
        }
        ribBox.addView(ribRun, new LayoutParams(1, h.px(RIB_H)));
        TextView ribLab = h.text(heavy, 1.2f, GROUND);
        ribLab.setText("SCORES"); ribLab.setLetterSpacing(.1f); ribLab.setGravity(Gravity.CENTER_VERTICAL);
        ribLab.setPadding(h.px(1.6f), 0, h.px(1.6f), 0);
        ribLab.setBackgroundColor(GOLD);
        ribBox.addView(ribLab, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        View ribEdge = new View(c); ribEdge.setBackgroundColor(0xFF1C2430);
        ribBox.addView(ribEdge, new LayoutParams(LayoutParams.MATCH_PARENT, Math.max(1, h.px(.08f)), Gravity.BOTTOM));
        LinearLayout tline = new LinearLayout(c);
        tline.setGravity(Gravity.CENTER_VERTICAL);
        tabRow = new LinearLayout(c);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        tline.addView(tabRow, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        liveChip = h.text(heavy, 1.15f, 0xFF160400);
        liveChip.setLetterSpacing(.1f);
        liveChip.setPadding(h.px(.8f), h.px(.2f), h.px(.8f), h.px(.2f));
        GradientDrawable lc = new GradientDrawable(); lc.setCornerRadius(.4f * R); lc.setColor(LIVE_C); liveChip.setBackground(lc);
        liveChip.setVisibility(GONE);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); ll.leftMargin = h.px(1.2f);
        tline.addView(liveChip, ll);
        updated = h.text(h.fReg, 1.15f, 0xFF8E99A8);
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); ul.leftMargin = h.px(1.4f);
        tline.addView(updated, ul);
        chrome.addView(tline, h.at(PAD, TABS_Y, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        // the scoreboard (two pictures crossfading)
        for (int i = 0; i < 2; i++) {
            hero[i] = new ImageView(c);
            hero[i].setScaleType(ImageView.ScaleType.FIT_XY);
            hero[i].setAlpha(0f);
            addView(hero[i], h.at(PAD, HERO_Y, h.px(SW - 2 * PAD), h.px(HERO_H)));
        }
        heroRing = new View(c);
        GradientDrawable hr = new GradientDrawable(); hr.setCornerRadius(2.2f * R); hr.setStroke(Math.max(3, h.px(.22f)), Color.WHITE);
        heroRing.setBackground(hr); heroRing.setVisibility(GONE);
        addView(heroRing, h.at(PAD, HERO_Y, h.px(SW - 2 * PAD), h.px(HERO_H)));
        // your teams (and your fantasy team), beside the scoreboard
        side = new ImageView(c);
        side.setScaleType(ImageView.ScaleType.FIT_XY);
        side.setVisibility(GONE);
        addView(side, h.at(SW - PAD - SIDE_W, sideY, h.px(SIDE_W), h.px(HERO_H)));
        sideRing = new View(c);
        GradientDrawable sr = new GradientDrawable(); sr.setCornerRadius(1.4f * R); sr.setStroke(Math.max(3, h.px(.22f)), Color.WHITE);
        sideRing.setBackground(sr); sideRing.setVisibility(GONE);
        addView(sideRing, h.at(SW - PAD - SIDE_W, sideY, h.px(SIDE_W), h.px(6)));
        // the leagues, as chips (the one you're in is lit)
        rail = new Rail(c);
        addView(rail, h.at(0, RAIL_Y, LayoutParams.MATCH_PARENT, h.px(4.4f)));

        setupTitle = h.text(heavy, 3.2f, Color.WHITE);
        setupTitle.setText("FOLLOW YOUR TEAMS"); setupTitle.setPadding(0, 0, h.px(.7f), 0); setupTitle.setVisibility(GONE);
        addView(setupTitle, h.at(PAD, 14, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        setupSub = h.text(h.fReg, 1.3f, 0xBFFFFFFF);
        setupSub.setText("Pick the sports and teams you follow. TopShelf shows their games, live scores, and which of your Live TV channels is showing each one.");
        setupSub.setLineSpacing(0, 1.3f); setupSub.setVisibility(GONE);
        addView(setupSub, h.at(PAD, 18.6f, h.px(60), LayoutParams.WRAP_CONTENT));

        rowsBox = new FrameLayout(c);
        rowsBox.setClipChildren(true);
        addView(rowsBox, h.at(0, ROWS_Y, LayoutParams.MATCH_PARENT, H - h.px(ROWS_Y)));
        rowsIn = new FrameLayout(c);
        rowsIn.setClipChildren(false);
        rowsBox.addView(rowsIn, new LayoutParams(LayoutParams.MATCH_PARENT, h.px(400)));
        glow = new View(c);
        glow.setAlpha(0f);
        // Standings (its own tab): over the scoreboard, cards and leagues, under the tabs
        standings = new StandingsView(h);
        standings.setBackgroundColor(GROUND);
        standings.setVisibility(GONE);
        LayoutParams stl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT); stl.topMargin = h.px(HERO_Y - 1.2f);
        addView(standings, stl);
        chrome.bringToFront();

        h.imgs.make("sportsbg:" + W + "x" + H, new HomeImages.Maker() { @Override public Bitmap make() { return bakeBg(); } }, new HomeImages.Done() {
            @Override public void done(Bitmap b) {
                if (b == null) return;
                glassL = Blur.glass(b, 4, 2, 1.9f, 1.06f); glassF = Blur.glass(b, 8, 3, 1.7f, 1f);
                if (shown) h.setTabGlass(glassL, glassF);
            }
        });
    }

    // ================================================================== showing

    private Bitmap glassL, glassF;
    /* (Fading out, the tab is drawn once into a layer and that layer faded. Fading in, it's drawn piece by
       piece at the fading strength: its cards are still arriving then, and each spoilt a layer.) */
    void show() {
        shown = true;
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(350).start();     // (no ready-made picture: see hasOverlappingRendering)
        chrome.animate().cancel();
        chrome.setAlpha(h.barsHiddenNow() || h.portrait ? 1f : 0f);
        h.sportsData().setOn(true);
        removeCallbacks(fyTick); postDelayed(fyTick, 2000);
        ribStart();
        // the bars' glass: blurred once when the backdrop was made (not again on every visit), and changed over
        // once the tab has faded in (both at once made the switch the heaviest moment on the graphics chip)
        postDelayed(new Runnable() { @Override public void run() { if (shown && glassL != null) h.setTabGlass(glassL, glassF); } }, 400);
    }
    void hide() {
        shown = false;
        removeCallbacks(fyTick);
        ribStop();
        h.sportsData().setOn(false);
        animate().alpha(0f).setDuration(250).withLayer().withEndAction(new Runnable() { @Override public void run() { if (!shown) setVisibility(INVISIBLE); } }).start();
    }
    boolean shownNow() { return shown; }
    /** the self-check: the Standings tab, as choosing it with the remote does */
    boolean selfCheckStandings() { if (!tabs.contains("Standings")) return false; tab = "Standings"; zone = "grid"; applySet(false); return true; }
    boolean standingsShown() { return standings.shownNow(); }
    /** the app's bars went up (or stepped aside): the score strip and the tabs take their place while you're in the tab */
    void barsHidden(boolean hide) {
        if (h.portrait) return;            // (upright, the tab bar is at the bottom: the strip and tabs stay)
        chrome.animate().cancel();
        chrome.animate().alpha(hide ? 1f : 0f).setDuration(hide ? 400 : 180).start();
    }


    /* "My fantasy team": your players' cards, first on the tab (made here, from the fantasy alerts' data);
       the page's rows come after it, so a page row's number is one less than ours */
    private String pageJson = "{}", fyKeys = "";
    private int off;                  // 1 while the fantasy row is there
    private JSONArray fyRow() { Fantasy f = h.act.fantasy; return f == null ? new JSONArray() : f.sportsRow(); }
    private static String keys(JSONArray a) { StringBuilder b = new StringBuilder(); for (int i = 0; i < a.length(); i++) b.append(a.optJSONObject(i).optString("k")).append('|'); return b.toString(); }
    private final Runnable fyTick = new Runnable() { @Override public void run() {
        if (!shown) return;
        String k = keys(fyRow());
        if (!k.equals(fyKeys)) setData(pageJson);
        postDelayed(this, 15000);
    } };

    /** the rows, from the page */
    void setData(String json) {
        try { data = new JSONObject(json); pageJson = json; } catch (Exception e) { return; }
        allRows.clear(); allTitles.clear(); allNotes.clear();
        JSONArray fy = data.optBoolean("setup") ? new JSONArray() : fyRow();
        fyKeys = keys(fy);
        off = fy.length() > 0 ? 1 : 0;
        if (off == 1) { allRows.add(fy); allTitles.add("My fantasy team"); allNotes.add(""); }
        JSONArray rs = data.optJSONArray("rows");
        liveIdx = -1; mineIdx = -1;
        if (rs != null) for (int i = 0; i < rs.length(); i++) {
            JSONObject o = rs.optJSONObject(i);
            String t = o.optString("title"), tl = t.toLowerCase(java.util.Locale.US);
            if (tl.startsWith("live now")) liveIdx = allRows.size();
            else if (tl.startsWith("my teams")) mineIdx = allRows.size();
            allRows.add(o.optJSONArray("items") == null ? new JSONArray() : o.optJSONArray("items"));
            allTitles.add(t); allNotes.add(o.optString("note"));
        }
        int live = data.optInt("live");
        liveChip.setText("● " + live + " LIVE");
        liveChip.setVisibility(live > 0 ? VISIBLE : GONE);
        updated.setText(data.optString("updated"));
        boolean setup = data.optBoolean("setup");
        setupTitle.setVisibility(setup ? VISIBLE : GONE); setupSub.setVisibility(setup ? VISIBLE : GONE);
        playersIn.clear();
        if (off == 1) { JSONArray f = allRows.get(0); for (int i = 0; i < f.length(); i++) { JSONObject x = f.optJSONObject(i); if (x != null && !x.optString("gameId").isEmpty() && !x.optBoolean("bench")) { Integer n = playersIn.get(x.optString("gameId")); playersIn.put(x.optString("gameId"), n == null ? 1 : n + 1); } } }
        // your teams beside the scoreboard (which is narrower while they're there)
        buildSide(setup);
        boolean sideOn = !sideItems.isEmpty();
        float heroW = sideOn && !h.portrait ? SW - 2 * PAD - SIDE_W - SIDE_GAP : SW - 2 * PAD;
        if (h.portrait) {
            // stacked: your teams under the scoreboard, then the leagues, then the games
            float sh = sideOn ? sideH() : 0;
            LayoutParams sl = (LayoutParams) side.getLayoutParams(); if (sl.height != h.px(Math.max(1, sh))) { sl.height = h.px(Math.max(1, sh)); side.setLayoutParams(sl); sideKey = ""; }
            RAIL_Y = sideY + sh + (sideOn ? 1f : 0);
            ROWS_Y = RAIL_Y + 4.8f;
            LayoutParams rl = (LayoutParams) rail.getLayoutParams(); rl.topMargin = h.px(RAIL_Y); rail.setLayoutParams(rl);
            LayoutParams bl = (LayoutParams) rowsBox.getLayoutParams(); bl.topMargin = h.px(ROWS_Y); bl.height = H - h.px(ROWS_Y); rowsBox.setLayoutParams(bl);
        }
        for (View v : new View[] { hero[0], hero[1], heroRing }) { LayoutParams lp = (LayoutParams) v.getLayoutParams(); int nw = h.px(heroW); if (lp.width != nw) { lp.width = nw; v.setLayoutParams(lp); heroKey = ""; } }
        heroWidth = heroW;
        side.setVisibility(sideOn ? VISIBLE : GONE);
        if (sideOn) paintSide();
        // the tabs there's something for
        tabs.clear();
        if (liveIdx >= 0) tabs.add("Live Now");
        tabs.add("Today");
        if (mineIdx >= 0) tabs.add("My Teams");
        if (off == 1) tabs.add("Fantasy");
        tabs.add("Standings");
        if (!tabs.contains(tab)) tab = tabs.get(0);
        rail.rebuild();
        if (leagueSel >= rail.count()) leagueSel = 0;
        paintRibbon();
        applySet(false);
    }

    /* your teams: each team you follow once, at its most interesting game (live, then the next one, then the
       last result), up to four (three when your fantasy team takes the last place) */
    private void buildSide(boolean setup) {
        sideItems.clear();
        if (setup) return;
        JSONObject fyTeam = null;
        if (off == 1) { JSONArray f = allRows.get(0); for (int i = 0; i < f.length(); i++) { JSONObject x = f.optJSONObject(i); if (x != null && x.optBoolean("fyTeam")) fyTeam = x; } }
        int max = off == 1 ? 3 : 4;
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (mineIdx >= 0) {
            JSONArray a = allRows.get(mineIdx);
            for (int i = 0; i < a.length() && sideItems.size() < max; i++) {
                JSONObject g = a.optJSONObject(i);
                JSONArray ts = g == null ? null : g.optJSONArray("teams");
                if (ts == null || ts.length() < 2 || g.optBoolean("edit") || g.optBoolean("card")) continue;
                for (int k = 0; k < ts.length() && sideItems.size() < max; k++) {
                    JSONObject t = ts.optJSONObject(k);
                    if (t != null && t.optBoolean("fav") && seen.add(g.optString("lg") + "|" + t.optString("full"))) sideItems.add(new Side("team", t, g, k));
                }
            }
        }
        if (off == 1) sideItems.add(new Side("fy", fyTeam, null, 0));
        if (sideItems.isEmpty()) sideItems.add(new Side("edit", null, null, 0));
        if (sideSel >= sideItems.size()) sideSel = sideItems.size() - 1;
    }

    // ================================================================== the score strip

    private String ribKey = "";
    /** the strip's games: live ones (your teams' first), then today's still to come, then the latest finals */
    private void paintRibbon() {
        List<JSONObject> live = new ArrayList<>(), pre = new ArrayList<>(), fin = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        long now = System.currentTimeMillis();
        for (int k = off; k < allRows.size(); k++) {
            JSONArray a = allRows.get(k);
            for (int i = 0; i < a.length(); i++) {
                JSONObject g = a.optJSONObject(i);
                JSONArray ts = g == null ? null : g.optJSONArray("teams");
                if (ts == null || ts.length() < 2 || g.optBoolean("edit") || g.optBoolean("fy")) continue;
                if (!seen.add(g.optString("k").split(":")[0])) continue;
                String s = g.optString("state");
                long at = g.optLong("at");
                if (s.equals("in")) live.add(g);
                else if (s.equals("pre") && at - now < 20 * 3600000L) pre.add(g);
                else if (s.equals("post") && now - at < 16 * 3600000L) fin.add(g);
            }
        }
        java.util.Collections.sort(pre, new java.util.Comparator<JSONObject>() { @Override public int compare(JSONObject a, JSONObject b) { return Long.compare(a.optLong("at"), b.optLong("at")); } });
        java.util.Collections.sort(fin, new java.util.Comparator<JSONObject>() { @Override public int compare(JSONObject a, JSONObject b) { return Long.compare(b.optLong("at"), a.optLong("at")); } });
        final List<JSONObject> all = new ArrayList<>(live); all.addAll(pre); all.addAll(fin);
        while (all.size() > 40) all.remove(all.size() - 1);
        StringBuilder kb = new StringBuilder();
        for (JSONObject g : all) kb.append(g.optString("k")).append('|');
        final String key = kb.toString();
        if (key.equals(ribKey)) return;
        ribKey = key;
        if (all.isEmpty()) { ribStop(); ribBox.setVisibility(GONE); ribW = 0; return; }
        painter.execute(new Runnable() { @Override public void run() {
            final Bitmap b = paintRib(all);
            main().post(new Runnable() { @Override public void run() { if (key.equals(ribKey) && b != null) ribApply(b); } });
        } });
    }
    private float ribLabelW() { return measure("SCORES", heavy, 1.2f, .1f) + 3.2f * R; }
    private Bitmap paintRib(List<JSONObject> items) {
        int hh = h.px(RIB_H);
        float x = ribLabelW();
        for (JSONObject g : items) { float nx = ribSeg(null, g, x, hh); if (nx > 7800) break; x = nx; }
        Bitmap b = Bitmap.createBitmap(Math.max(1, Math.round(x)), hh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF05080B);
        float x2 = ribLabelW();
        for (JSONObject g : items) { float nx = ribSeg(null, g, x2, hh); if (nx > x + 1) break; ribSeg(c, g, x2, hh); x2 = nx; }
        b.setHasAlpha(false);
        return b;
    }
    /** one game on the strip ("NFL  ● CAR 10  ● GB 14  Q2 4:12"); measures only when there's no canvas */
    private float ribSeg(Canvas c, JSONObject g, float x, int hh) {
        float y = hh / 2f + .5f * R;
        JSONArray ts = g.optJSONArray("teams");
        String st = g.optString("state");
        boolean live = st.equals("in"), post = st.equals("post"), fight = g.optBoolean("card");
        x += 1.7f * R;
        String lg = g.optString("lgName");
        if (lg.length() > 8) lg = g.optString("lg");
        lg = lg.toUpperCase(java.util.Locale.US);
        if (c != null) text(c, lg, cond, 1.0f, DIMTXT, x, y - .05f * R, Paint.Align.LEFT, .06f);
        x += measure(lg, cond, 1.0f, .06f) + 1f * R;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < 2; i++) {
            JSONObject t = ts.optJSONObject(i);
            String ab = fight ? t.optString("name") : abbr(t);
            if (c != null) { p.setColor(teamCol(t, 0xFF3A4A66)); c.drawCircle(x + .38f * R, y - .45f * R, .38f * R, p); }
            x += 1.1f * R;
            boolean dim = post && !t.optBoolean("winner") && !fight;
            if (c != null) text(c, ab, cond, 1.4f, t.optBoolean("fav") ? GOLD : dim ? 0xFFAEB8C4 : Color.WHITE, x, y, Paint.Align.LEFT, 0);
            x += measure(ab, cond, 1.4f, 0) + .55f * R;
            String sc = t.optString("score");
            if ((live || post) && !fight && !sc.isEmpty()) {
                if (c != null) text(c, sc, heavy, 1.45f, dim ? 0xFFAEB8C4 : Color.WHITE, x, y, Paint.Align.LEFT, 0);
                x += measure(sc, heavy, 1.45f, 0) + 1.1f * R;
            } else x += .5f * R;
        }
        String s = live ? (g.optString("per") + " " + g.optString("clock")).trim() : post ? "FINAL" : g.optString("when").replaceFirst("^Today ", "");
        if (live && s.isEmpty()) s = "LIVE";
        s = s.toUpperCase(java.util.Locale.US);
        if (c != null) text(c, s, cond, 1.15f, live ? LIVE_C : post ? DIMTXT : 0xFFC6CED8, x, y, Paint.Align.LEFT, .03f);
        x += measure(s, cond, 1.15f, .03f) + 1.7f * R;
        if (c != null) { p.setColor(0xFF1C2430); c.drawRect(x - Math.max(1, .08f * R), 0, x, hh, p); }
        return x;
    }
    private void ribApply(Bitmap b) {
        ribW = b.getWidth();
        boolean scroll = ribW > W;
        for (int i = 0; i < 2; i++) {
            rib[i].setImageBitmap(b);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) rib[i].getLayoutParams(); lp.width = ribW; rib[i].setLayoutParams(lp);
        }
        rib[1].setVisibility(scroll ? VISIBLE : GONE);
        LayoutParams rl = (LayoutParams) ribRun.getLayoutParams(); rl.width = scroll ? 2 * ribW : ribW; ribRun.setLayoutParams(rl);
        ribBox.setVisibility(VISIBLE);
        ribStart();
    }
    /* the strip slides left at a reading pace and wraps round (it carries on where it was when the scores change) */
    private void ribStart() {
        long at = ribAnim != null ? ribAnim.getCurrentPlayTime() : 0;
        ribStop();
        if (!shown || ribW <= W) { ribRun.setTranslationX(0); return; }
        ribAnim = ObjectAnimator.ofFloat(ribRun, "translationX", 0f, -ribW);
        long dur = (long) (ribW / (4.4f * R) * 1000);
        ribAnim.setDuration(dur);
        ribAnim.setInterpolator(new LinearInterpolator());
        ribAnim.setRepeatCount(ValueAnimator.INFINITE);
        ribAnim.start();
        if (at > 0) ribAnim.setCurrentPlayTime(at % dur);
    }
    private void ribStop() { if (ribAnim != null) { ribAnim.cancel(); ribAnim = null; } }

    /** the games the tab (and league) picks, as the one row on screen */
    private void applySet(boolean smooth) {
        if (tab.equals("Standings")) { standings.show(); standings.bringToFront(); chrome.bringToFront(); } else standings.hide();
        String curKey = curItem() != null ? curItem().optString("k").split(":")[0] : null;
        int idx = tab.equals("Live Now") ? liveIdx : tab.equals("My Teams") ? mineIdx : tab.equals("Fantasy") ? (off == 1 ? 0 : -1) : rail.rowOf(leagueSel);
        boolean same = idx == curSet;
        curSet = idx;
        rows.clear(); titles.clear(); notes.clear();
        if (idx >= 0 && idx < allRows.size()) {
            rows.add(allRows.get(idx));
            String t = tab.equals("Live Now") ? "Live now" : tab.equals("My Teams") ? "My teams" : tab.equals("Fantasy") ? "My fantasy team" : allTitles.get(idx);
            titles.add(t); notes.add(allNotes.get(idx));
        } else if (tab.equals("Today")) { rows.add(new JSONArray()); titles.add("Today"); notes.add("Pick your sports and teams — OK on Today"); }
        r = 0;
        if (!same) { cols.clear(); scrolls.clear(); }
        else if (curKey != null && !rows.isEmpty()) {
            JSONArray it = rows.get(0);
            for (int c = 0; c < it.length(); c++) if (it.optJSONObject(c).optString("k").split(":")[0].equals(curKey)) { cols.put(0, c); break; }
        }
        if (!rows.isEmpty()) { Integer c = cols.get(0); if (c != null && c >= rows.get(0).length()) cols.put(0, Math.max(0, rows.get(0).length() - 1)); }
        // the scoreboard's game: the one you're on, or the best live one
        if (featured == null || !stillThere(featured)) featured = bestGame();
        drawTabs();
        rebuildRows();
        focus(smooth);
    }
    private boolean stillThere(JSONObject g) {
        String id = g.optString("k").split(":")[0];
        for (JSONArray a : allRows) for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i).optString("k").split(":")[0].equals(id)) { featured = a.optJSONObject(i); return true; }
        return false;
    }
    /** a live game (your teams' first), else the next game coming up */
    private JSONObject bestGame() {
        for (int idx : new int[] { liveIdx, mineIdx }) if (idx >= 0) { JSONArray a = allRows.get(idx); for (int i = 0; i < a.length(); i++) { JSONObject g = a.optJSONObject(i); if (g.optJSONArray("teams") != null && !g.optBoolean("edit")) return g; } }
        for (int k = off; k < allRows.size(); k++) { JSONArray a = allRows.get(k); for (int i = 0; i < a.length(); i++) { JSONObject g = a.optJSONObject(i); if (g.optJSONArray("teams") != null && !g.optBoolean("edit") && !"post".equals(g.optString("state"))) return g; } }
        return null;
    }
    private void drawTabs() {
        tabRow.removeAllViews();
        for (int i = 0; i < tabs.size(); i++) {
            String t = tabs.get(i);
            boolean sel = t.equals(tab), foc = sel && zone.equals("tabs");
            TextView v = h.text(sel ? h.fSemi : h.fMed, 1.4f, sel ? (GlassPanel.look.equals("glass") || GlassPanel.look.equals("spot") ? 0xFF0A0E13 : GlassPanel.selText()) : 0xFFC6CED8);
            if (GlassPanel.look.equals("air")) { v.setAllCaps(true); v.setLetterSpacing(.05f); }
            v.setText(t);
            v.setPadding(h.px(1.5f), h.px(.55f), h.px(1.5f), h.px(.55f));
            GradientDrawable g = new GradientDrawable(); g.setCornerRadius(GlassPanel.corner(h.px(2))); g.setColor(sel ? GlassPanel.selFill() : 0x00000000);
            if (foc) g.setStroke(Math.max(2, h.px(.18f)), GOLD);
            else if (!sel) g.setStroke(Math.max(1, h.px(.08f)), 0xFF2A3442);
            v.setBackground(g);
            if (foc) { v.setScaleX(1.08f); v.setScaleY(1.08f); }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); lp.rightMargin = h.px(.8f);
            tabRow.addView(v, lp);
        }
    }
    private float heroWidth;
    private String bgFor = "";
    private void paintBg(final JSONObject g) {
        final String key = g == null ? "" : g.optString("k").split(":")[0];
        if (key.equals(bgFor)) return;
        bgFor = key;
        painter.execute(new Runnable() { @Override public void run() {
            int w = 320, hh = 180;
            int c1 = 0xFF0B3A52, c2 = 0xFF5A1020;
            JSONArray ts = g == null ? null : g.optJSONArray("teams");
            if (ts != null && ts.length() >= 2) { c1 = parse(ts.optJSONObject(0).optString("color"), c1); c2 = parse(ts.optJSONObject(1).optString("color"), c2); }
            Bitmap b = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(GROUND);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new RadialGradient(w * .18f, hh * .3f, w * .5f, alpha(dark(c1), .42f), alpha(dark(c1), 0), Shader.TileMode.CLAMP)); c.drawRect(0, 0, w, hh, p);
            p.setShader(new RadialGradient(w * .82f, hh * .2f, w * .42f, alpha(dark(c2), .36f), alpha(dark(c2), 0), Shader.TileMode.CLAMP)); c.drawRect(0, 0, w, hh, p);
            p.setShader(new LinearGradient(0, hh * .45f, 0, hh, 0x0006070C, 0xF206070C, Shader.TileMode.CLAMP)); c.drawRect(0, 0, w, hh, p);
            final Bitmap out = b;
            main().post(new Runnable() { @Override public void run() {
                if (!key.equals(bgFor)) return;
                bg.setImageBitmap(out);
                bg.setAlpha(.0f); bg.animate().alpha(1f).setDuration(500).start();
            } });
        } });
    }
    /** a team colour taken down to a deep tone for the backdrop */
    private static int dark(int c) { float[] hsv = new float[3]; Color.colorToHSV(c, hsv); hsv[2] = Math.min(hsv[2], .38f); hsv[1] = Math.min(1f, hsv[1] * .9f); return Color.HSVToColor(hsv); }
    /** your starters in each game (ESPN's id → how many) */
    private final Map<String, Integer> playersIn = new HashMap<>();

    private JSONObject curItem() {
        if (r >= rows.size()) return null;
        JSONArray it = rows.get(r);
        int c = col(r);
        return c < it.length() ? it.optJSONObject(c) : null;
    }
    private int col(int row) { Integer c = cols.get(row); return c == null ? 0 : c; }
    private float scroll(int row) { Float s = scrolls.get(row); return s == null ? 0 : s; }

    // ================================================================== rows

    private final Map<Integer, FrameLayout> stripOf = new HashMap<>();
    private void rebuildRows() {
        rowsIn.removeAllViews();
        stripOf.clear();
        cardViews.clear();
        focused = null;
        for (int i = 0; i < rows.size(); i++) {
            FrameLayout row = new FrameLayout(getContext());
            row.setClipChildren(false);
            rowsIn.addView(row, h.at(0, i * ROW_H, LayoutParams.MATCH_PARENT, h.px(ROW_H)));
            if (!titles.get(i).isEmpty()) {
                TextView t = h.text(heavy, 2.2f, Color.WHITE);
                t.setLetterSpacing(.02f);
                int live = 0; JSONArray ri = rows.get(i); for (int k = 0; k < ri.length(); k++) if ("in".equals(ri.optJSONObject(k).optString("state"))) live++;
                int games = 0; for (int k = 0; k < ri.length(); k++) if (ri.optJSONObject(k).optJSONArray("teams") != null && !ri.optJSONObject(k).optBoolean("fy")) games++;
                String sub = games > 0 ? "   " + games + " game" + (games == 1 ? "" : "s") + (live > 0 && live < games ? " · " + live + " live" : "") : "";
                String tt = titles.get(i).toUpperCase(java.util.Locale.US);
                android.text.SpannableString ss = new android.text.SpannableString(tt + sub);
                if (!sub.isEmpty()) { ss.setSpan(new android.text.style.AbsoluteSizeSpan(Math.round(1.3f * R)), tt.length(), ss.length(), 0); ss.setSpan(new android.text.style.ForegroundColorSpan(0xFF9AA5B3), tt.length(), ss.length(), 0); }
                t.setText(ss); t.setPadding(0, 0, h.px(.5f), 0);
                row.addView(t, h.at(PAD, .4f, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            }
            if (rows.get(i).length() == 0) {
                TextView n = h.text(h.fMed, 1.1f, 0x99FFFFFF); n.setText(notes.get(i));
                row.addView(n, h.at(PAD, 3.2f, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
                continue;
            }
            FrameLayout strip = new FrameLayout(getContext());
            strip.setClipChildren(false);
            row.addView(strip, h.at(0, 3.4f, h.px(PAD * 2 + rows.get(i).length() * (CW + GAP)), h.px(CH + 2)));
            strip.setTranslationX(-h.px(scroll(i)));
            stripOf.put(i, strip);
        }
        LayoutParams lp = (LayoutParams) rowsIn.getLayoutParams(); lp.height = h.px(rows.size() * ROW_H + 30); rowsIn.setLayoutParams(lp);
        syncCards();
    }

    /** cards exist for what's near the screen */
    private void syncCards() {
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (int i = Math.max(0, r - 1); i <= Math.min(rows.size() - 1, r + 2); i++) {
            FrameLayout strip = stripOf.get(i);
            if (strip == null) continue;
            JSONArray it = rows.get(i);
            int first = (int) Math.floor(scroll(i) / (CW + GAP));
            for (int c = Math.max(0, first - 1); c <= Math.min(it.length() - 1, first + 5); c++) {
                String key = i + ":" + c;
                keep.add(key);
                if (cardViews.containsKey(key)) continue;
                CardView v = new CardView(it.optJSONObject(c));
                cardViews.put(key, v);
                strip.addView(v, h.at(PAD + c * (CW + GAP), .6f, h.px(CW), h.px(CH)));
            }
        }
        for (String k : new ArrayList<>(cardViews.keySet())) if (!keep.contains(k)) {
            CardView v = cardViews.remove(k);
            if (v.getParent() != null) ((FrameLayout) v.getParent()).removeView(v);
            if (v == focused) focused = null;
        }
    }

    // ================================================================== focus

    private void focus(boolean smooth) {
        if (zone.equals("side") && sideSel < sideItems.size() && sideItems.get(sideSel).game != null) featured = sideItems.get(sideSel).game;
        heroRing.setVisibility(zone.equals("hero") ? VISIBLE : GONE);
        placeSideRing();
        if (rows.isEmpty()) { showHero(featured); paintBg(featured); return; }
        float ty = -h.px(r * ROW_H);
        if (smooth) rowsIn.animate().translationY(ty).setDuration(360).setInterpolator(TileView.EASE).start(); else rowsIn.setTranslationY(ty);
        int c = col(r);
        float left = PAD + c * (CW + GAP);
        if (left < scroll(r) + PAD || left + CW > scroll(r) + SW - PAD) {
            float s = Math.max(0, left - PAD);
            scrolls.put(r, s);
            FrameLayout strip = stripOf.get(r);
            if (strip != null) { if (smooth) strip.animate().translationX(-h.px(s)).setDuration(360).setInterpolator(TileView.EASE).start(); else strip.setTranslationX(-h.px(s)); }
        }
        syncCards();
        CardView v = zone.equals("grid") ? cardViews.get(r + ":" + c) : null;
        if (zone.equals("grid") && curItem() != null && curItem().optJSONArray("teams") != null && !curItem().optBoolean("fy")) featured = curItem();
        if (focused != v) {
            if (focused != null) focused.setFocus(false, true);
            focused = v;
            if (v != null) v.setFocus(true, smooth);
            placeGlow(smooth);
        }
        main().removeCallbacks(heroLater);
        main().postDelayed(heroLater, smooth ? 120 : 0);
        rail.invalidate();
    }
    /** the ring round the team you're on in the your-teams column */
    private void placeSideRing() {
        if (!zone.equals("side") || sideItems.isEmpty() || side.getVisibility() != VISIBLE) { sideRing.setVisibility(GONE); return; }
        float[] cr = sideCard(sideSel);
        LayoutParams lp = (LayoutParams) sideRing.getLayoutParams();
        lp.topMargin = h.px(sideY + cr[0]); lp.height = h.px(cr[1]);
        sideRing.setLayoutParams(lp);
        sideRing.setVisibility(VISIBLE);
    }
    private final Runnable heroLater = new Runnable() { @Override public void run() {
        JSONObject cur = curItem();
        // a fantasy player's card shows their game; the team / edit cards keep the scoreboard on the last game
        JSONObject show = zone.equals("grid") && cur != null && cur.optBoolean("fy") && !cur.optString("gameId").isEmpty() ? cur : featured;
        showHero(show);
        paintBg(show);
    } };
    private android.os.Handler main() { return h.main; }

    private void placeGlow(boolean smooth) {
        if (glow.getParent() != null) ((FrameLayout) glow.getParent()).removeView(glow);
        if (focused == null || focused.getParent() == null) return;
        JSONObject it = focused.item;
        int col = 0xFF34C759;
        JSONArray ts = it.optJSONArray("teams");
        if (ts != null && ts.length() == 2 && !ts.optJSONObject(1).optString("color").isEmpty()) col = parse(ts.optJSONObject(1).optString("color"), col);
        if (it.optString("state").equals("in")) col = LIVE_C;
        glow.setBackground(new android.graphics.drawable.BitmapDrawable(getResources(), glowBmp()));
        glow.getBackground().setColorFilter(new android.graphics.PorterDuffColorFilter(col, android.graphics.PorterDuff.Mode.SRC_IN));
        FrameLayout strip = (FrameLayout) focused.getParent();
        LayoutParams fl = (LayoutParams) focused.getLayoutParams();
        int m = h.px(3.4f);
        LayoutParams gl = new LayoutParams(h.px(CW) + 2 * m, h.px(CH) + 2 * m);
        gl.leftMargin = fl.leftMargin - m; gl.topMargin = fl.topMargin - m;
        strip.addView(glow, strip.indexOfChild(focused), gl);
        glow.setScaleX(1.06f); glow.setScaleY(1.06f);
        glow.animate().cancel();
        if (smooth) { glow.setAlpha(0f); glow.animate().alpha(.75f).setDuration(220).start(); } else glow.setAlpha(.75f);
    }
    private Bitmap glowCache;
    private Bitmap glowBmp() {
        if (glowCache != null) return glowCache;
        int m = h.px(3.4f), w = h.px(CW), hh = h.px(CH);
        Bitmap b = Bitmap.createBitmap(w + 2 * m, hh + 2 * m, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setMaskFilter(new BlurMaskFilter(1.6f * R, BlurMaskFilter.Blur.OUTER));
        c.drawRoundRect(new RectF(m, m, m + w, m + hh), 1.6f * R, 1.6f * R, p);
        glowCache = b;
        return b;
    }

    // ================================================================== keys

    boolean move(char d) {
        boolean railOn = rail.getVisibility() == VISIBLE && rail.count() > 0;
        boolean heroOn = featured != null;
        boolean sideOn = side.getVisibility() == VISIBLE && !sideItems.isEmpty();
        String below = railOn ? "rail" : "grid";
        if (zone.equals("tabs")) {
            int i = tabs.indexOf(tab);
            if (d == 'l' && i == 0) { h.leftEdge(); return true; }
            if (d == 'l' && i > 0) { tab = tabs.get(i - 1); applySet(true); }
            else if (d == 'r' && i < tabs.size() - 1) { tab = tabs.get(i + 1); applySet(true); }
            else if (d == 'u') { h.enterTopFromVod(); return true; }
            else if (d == 'd') {
                if (tab.equals("Standings")) { zone = "grid"; drawTabs(); standings.focusIn(); return true; }
                zone = heroOn ? "hero" : sideOn ? "side" : below; drawTabs(); focus(true);
            }
            return true;
        }
        if (tab.equals("Standings")) { if (!standings.move(d)) { zone = "tabs"; drawTabs(); } return true; }
        if (zone.equals("hero")) {
            if (d == 'u') { zone = "tabs"; drawTabs(); }
            else if (d == 'd') zone = below;
            else if (d == 'r' && sideOn) { zone = "side"; sideSel = sideOf(featured); }
            else if (d == 'l') { h.leftEdge(); return true; }
            focus(true);
            return true;
        }
        if (zone.equals("side")) {
            if (d == 'u') { if (sideSel > 0) sideSel--; else { zone = "tabs"; drawTabs(); } }
            else if (d == 'd') { if (sideSel < sideItems.size() - 1) sideSel++; else zone = below; }
            else if (d == 'l') { if (heroOn) zone = "hero"; }
            focus(true);
            return true;
        }
        if (zone.equals("rail")) {
            if (d == 'l' && leagueSel == 0) { h.leftEdge(); return true; }
            if (d == 'l' && leagueSel > 0) { leagueSel--; tab = "Today"; applySet(true); }
            else if (d == 'r' && leagueSel < rail.count() - 1) { leagueSel++; tab = "Today"; applySet(true); }
            else if (d == 'u') { zone = heroOn ? "hero" : sideOn ? "side" : "tabs"; drawTabs(); focus(true); }
            else if (d == 'd') { zone = "grid"; focus(true); }
            rail.invalidate();
            return true;
        }
        // the games
        if (rows.isEmpty()) { if (d == 'u') { zone = railOn ? "rail" : heroOn ? "hero" : "tabs"; drawTabs(); focus(true); } return true; }
        JSONArray it = rows.get(r);
        int c = col(r);
        if (d == 'u') { zone = railOn ? "rail" : heroOn ? "hero" : "tabs"; drawTabs(); focus(true); rail.invalidate(); return true; }
        else if (d == 'd') return true;
        else if (d == 'l') { if (c <= 0) { h.leftEdge(); return true; } cols.put(r, c - 1); }
        else if (d == 'r') { if (c >= it.length() - 1) return true; cols.put(r, c + 1); }
        focus(true);
        return true;
    }
    /* a finger (phones): "ok" when a game (or your-teams card) is chosen and OK should open it, "done" when the
       tap did all there was to do (a tab, a league), null for nothing there */
    String focusAt(float x, float y) {
        if (chrome.getAlpha() > .5f) for (int i = 0; i < tabRow.getChildCount() && i < tabs.size(); i++) if (h.hit(tabRow.getChildAt(i), x, y, .4f)) {
            tab = tabs.get(i); zone = "grid"; applySet(true); drawTabs(); return "done";
        }
        if (standings.shownNow() && h.hit(standings, x, y, 0)) {
            float[] l = h.local(standings, x, y);
            String got = standings.tapAt(l[0], l[1]);
            if (got != null) { zone = "grid"; drawTabs(); }
            return got;
        }
        if (heroTop >= 0 && featured != null && h.hit(hero[heroTop], x, y, 0)) { zone = "hero"; focus(true); return "ok"; }
        if (side.getVisibility() == VISIBLE && h.hit(side, x, y, 0)) {
            float ly = h.local(side, x, y)[1] / R;
            for (int i = 0; i < sideItems.size(); i++) { float[] cr = sideCard(i); if (ly >= cr[0] && ly <= cr[0] + cr[1]) { zone = "side"; sideSel = i; focus(true); return "ok"; } }
            return null;
        }
        if (rail.getVisibility() == VISIBLE && h.hit(rail, x, y, 0)) {
            float[] l = h.local(rail, x, y);
            for (int i = 0; i < rail.chips.size(); i++) if (rail.chips.get(i).contains(l[0], l[1])) { leagueSel = i; tab = "Today"; zone = "grid"; applySet(true); rail.invalidate(); return "done"; }
            return null;
        }
        for (Map.Entry<String, CardView> en : cardViews.entrySet()) {
            if (!h.hit(en.getValue(), x, y, 0)) continue;
            String[] k = en.getKey().split(":");
            r = Integer.parseInt(k[0]); cols.put(r, Integer.parseInt(k[1]));
            zone = "grid"; drawTabs(); focus(true);
            return "ok";
        }
        return null;
    }
    /** the your-teams card for the game on the scoreboard (else the first) */
    private int sideOf(JSONObject g) {
        if (g != null) { String id = g.optString("k").split(":")[0]; for (int i = 0; i < sideItems.size(); i++) { JSONObject sg = sideItems.get(i).game; if (sg != null && sg.optString("k").split(":")[0].equals(id)) return i; } }
        return 0;
    }
    void ok() {
        if (tab.equals("Standings")) { if (zone.equals("tabs")) { zone = "grid"; drawTabs(); standings.focusIn(); } else standings.ok(); return; }
        if (zone.equals("tabs") || zone.equals("rail")) { zone = "grid"; drawTabs(); focus(true); rail.invalidate(); return; }
        if (zone.equals("hero")) { openGame(featured); return; }
        if (zone.equals("side")) { sideOk(); return; }
        if (focused != null) focused.animate().scaleX(1.02f).scaleY(1.02f).setDuration(90).withEndAction(new Runnable() { @Override public void run() {
            if (focused != null) focused.animate().scaleX(1.06f).scaleY(1.06f).setDuration(180).setInterpolator(TileView.EASE).start();
        } }).start();
        if (allRows.isEmpty() || data.optBoolean("setup")) { h.page("spPickOpen()"); return; }
        JSONObject cur = curItem();
        if (cur == null) { if (tab.equals("Today")) h.page("spPickOpen()"); return; }
        if (curSet < off) { fyOk(cur); return; }
        h.sportsData().open(curSet - off, col(r));
    }
    /** a your-teams card: its game's menu; your fantasy team: the Fantasy tab; "follow your teams": the team picker */
    private void sideOk() {
        if (sideSel >= sideItems.size()) return;
        Side s = sideItems.get(sideSel);
        if (s.kind.equals("team")) openGame(s.game);
        else if (s.kind.equals("fy")) {
            if (s.team == null) { if (h.act.fantasy != null && !h.act.fantasy.connected()) h.setupShow("players"); return; }
            tab = "Fantasy"; zone = "grid"; applySet(true);
        } else h.page("spPickOpen()");
    }
    /** the scoreboard's game: its menu (watch on your channel, remind me…) */
    private void openGame(JSONObject g) {
        if (g == null) return;
        String id = g.optString("k").split(":")[0];
        for (int k = off; k < allRows.size(); k++) { JSONArray a = allRows.get(k); for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i).optString("k").split(":")[0].equals(id)) { h.sportsData().open(k - off, i); return; } }
        if (g.optBoolean("fy")) fyOk(g);
    }
    private void fyOk(JSONObject it) {
        if (it == null) return;
        if (it.optBoolean("fyEdit")) { if (h.act.fantasy != null && h.act.fantasy.connected()) h.toast("Your players come from Yahoo"); else h.setupShow("players"); return; }
        if (it.optBoolean("fyTeam")) { if (h.act.fantasy != null && !h.act.fantasy.connected()) h.setupShow("players"); return; }
        // the player's game: its menu (watch it on your channel, remind me…)
        if (!it.optString("gameId").isEmpty()) h.sportsData().openGame(it.optString("gameId"));
        else h.toast(it.optString("name") + " has no game this week");
    }
    void hold() { ok(); }
    boolean back() {
        if (tab.equals("Standings") && zone.equals("grid") && standings.back()) return true;
        if (!zone.equals("grid")) { zone = "grid"; drawTabs(); focus(true); rail.invalidate(); return true; }
        if (!tabs.isEmpty() && !tab.equals(tabs.get(0))) { tab = tabs.get(0); applySet(true); return true; }
        return false;
    }

    // ================================================================== a card

    private final class CardView extends NativeVod.RoundFrame {
        final JSONObject item;
        final ImageView img;
        CardView(JSONObject it) {
            super(NativeSports.this.getContext(), 1.6f * R);
            item = it;
            img = new ImageView(getContext());
            img.setScaleType(ImageView.ScaleType.FIT_XY);
            addView(img, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            setPivotX(h.px(CW) / 2f); setPivotY(h.px(CH) / 2f);
            GradientDrawable ph = new GradientDrawable(); ph.setCornerRadius(1.6f * R); ph.setColor(0xFF101826);
            setBackground(ph);
            paint();
        }
        void paint() {
            Integer mine = playersIn.get(item.optString("k").split(":")[0]);
            final String key = "card:" + item.optString("k") + ":" + item.optString("lg") + ":" + (mine == null ? 0 : mine);
            Bitmap b = cards.get(key);
            if (b != null) { img.setImageBitmap(b); setBackground(null); return; }
            /* first with the logos already here (it shows at once: teams, times, channel), then again with any logo
               still to come from ESPN — a card used to stay an empty box until all its logos had downloaded */
            painter.execute(new Runnable() { @Override public void run() {
                missing = false; offlineT.set(true);
                final Bitmap quick;
                try { quick = paintCard(item); } finally { offlineT.set(false); }
                final boolean again = missing;
                main().post(new Runnable() { @Override public void run() {
                    if (quick == null) return;
                    if (!again) cards.put(key, quick);
                    img.setImageBitmap(quick); setBackground(null);
                } });
                if (!again) return;
                final Bitmap out = paintCard(item);
                main().post(new Runnable() { @Override public void run() {
                    if (out == null) return;
                    cards.put(key, out);
                    img.setImageBitmap(out); setBackground(null);
                } });
            } });
        }
        void setFocus(boolean f, boolean smooth) {
            float s = f ? 1.06f : 1f;
            if (smooth) animate().scaleX(s).scaleY(s).setDuration(220).setInterpolator(TileView.EASE).start(); else { setScaleX(s); setScaleY(s); }
            if (f) { GradientDrawable rg = new GradientDrawable(); rg.setCornerRadius(1.6f * R); rg.setStroke(Math.max(2, h.px(.16f)), Color.WHITE); setForeground(rg); }
            else setForeground(null);
            setZ(f ? 3 : 0);
            setElevation(f ? 1.4f * R : 0);
            setOutlineProvider(new android.view.ViewOutlineProvider() { @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 1.6f * R); } });
        }
    }

    private static int parse(String hex, int def) { try { return Color.parseColor(hex); } catch (Exception e) { return def; } }
    private static int alpha(int c, float a) { return (Math.round(a * 255) << 24) | (c & 0xFFFFFF); }

    /** logos, fetched once (on the painter's thread) */
    // (bounded: team logos and players' photos — without a limit they piled up all session)
    private final Map<String, Bitmap> logos = new java.util.LinkedHashMap<String, Bitmap>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) { return size() > 120; }
    };
    private final Runnable trimmer = new Runnable() { @Override public void run() { cards.evictAll(); synchronized (logos) { logos.clear(); } } };
    { Diag.TRIMMERS.add(trimmer); }
    void dispose() { Diag.TRIMMERS.remove(trimmer); painter.shutdownNow(); cards.evictAll(); synchronized (logos) { logos.clear(); } }
    /* A logo is decoded at (about twice) the size it's drawn, and kept per size: the small card logo used to be
       reused, stretched, for the big scoreboard. ESPN's "-dark" versions are made for dark backgrounds. */
    private Bitmap logo(String url, int size) { return logo(url, size, true); }
    // (a quick paint with no downloads, on the painter's thread only; something wasn't here yet)
    private final ThreadLocal<Boolean> offlineT = new ThreadLocal<>();
    private volatile boolean missing;
    private static volatile boolean logosPruned;
    /** the logos kept on the TV: at most 800 (the oldest go) */
    private static void pruneLogos(java.io.File dir) {
        logosPruned = true;
        java.io.File[] fs = dir.listFiles();
        if (fs == null || fs.length <= 800) return;
        java.util.Arrays.sort(fs, new java.util.Comparator<java.io.File>() { @Override public int compare(java.io.File a, java.io.File b) { return Long.compare(a.lastModified(), b.lastModified()); } });
        for (int i = 0; i < fs.length - 800; i++) //noinspection ResultOfMethodCallIgnored
            fs[i].delete();
    }
    private Bitmap logo(String url, int size, boolean dark) {
        if (url == null || url.isEmpty()) return null;
        int b = size <= 72 ? 128 : size <= 180 ? 260 : 420;
        String key = url + "@" + b + (dark ? "" : "L");
        synchronized (logos) { if (logos.containsKey(key)) return logos.get(key); }
        // logos are kept on the TV too (they were downloaded again every time TopShelf started)
        java.io.File df = new java.io.File(new java.io.File(getContext().getCacheDir(), "logos"), Integer.toHexString(key.hashCode()) + "-" + key.length() + ".png");
        if (df.exists()) {
            Bitmap c = android.graphics.BitmapFactory.decodeFile(df.getPath());
            if (c != null) { synchronized (logos) { logos.put(key, c); } return c; }
        }
        if (Boolean.TRUE.equals(offlineT.get())) { missing = true; return null; }
        if (!logosPruned) pruneLogos(df.getParentFile());
        Bitmap bm = null;
        List<String> tries = new ArrayList<>();
        if (dark && url.contains("espncdn.com") && url.contains("/500/")) tries.add(url.replace("/500/", "/500-dark/"));
        tries.add(url);
        for (String u : tries) {
            try { byte[] d = HomeImages.http(u, null); if (d != null) bm = HomeImages.decode(d, b, b); } catch (Throwable ignored) { }
            if (bm != null) break;
        }
        if (bm != null && (bm.getWidth() > b * 1.5f || bm.getHeight() > b * 1.5f)) {
            float sc = Math.min(b * 1f / bm.getWidth(), b * 1f / bm.getHeight());
            Bitmap scd = Bitmap.createScaledBitmap(bm, Math.max(1, Math.round(bm.getWidth() * sc)), Math.max(1, Math.round(bm.getHeight() * sc)), true);
            if (scd != bm) bm.recycle(); bm = scd;
        }
        if (bm != null) try {
            df.getParentFile().mkdirs();
            java.io.File tmp = new java.io.File(df.getPath() + ".tmp");
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(tmp)) { bm.compress(Bitmap.CompressFormat.PNG, 100, o); }
            if (!tmp.renameTo(df)) tmp.delete();
        } catch (Throwable ignored) { }
        synchronized (logos) { logos.put(key, bm); }
        return bm;
    }
    /** each league's own logo (the page only has an emoji for it) */
    static String leagueLogo(String lg) {
        switch (lg) {
            case "nfl": case "nba": case "mlb": case "nhl": case "wnba": return "https://a.espncdn.com/i/teamlogos/leagues/500/" + lg + ".png";
            case "epl": return "https://a.espncdn.com/i/leaguelogos/soccer/500/23.png";
            case "laliga": return "https://a.espncdn.com/i/leaguelogos/soccer/500/15.png";
            case "mls": return "https://a.espncdn.com/i/leaguelogos/soccer/500/19.png";
            case "ucl": return "https://a.espncdn.com/i/leaguelogos/soccer/500/2.png";
            default: return "";
        }
    }

    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    /* team colours, made to work on a dark screen: one too pale for white writing on it takes the team's other
       colour (or is deepened), and the second of two near-matching colours takes its other one */
    private static float lum(int c) { return (.2126f * Color.red(c) + .7152f * Color.green(c) + .0722f * Color.blue(c)) / 255f; }
    private static int shade(int c, float f) { return Color.rgb(Math.round(Color.red(c) * f), Math.round(Color.green(c) * f), Math.round(Color.blue(c) * f)); }
    private static int teamCol(JSONObject t, int def) {
        int c = parse(t.optString("color"), def);
        if (lum(c) > .5f) { int a = parse(t.optString("alt"), 0); c = a != 0 && lum(a) < .5f ? a : shade(c, .45f); }
        return c;
    }
    private static int[] pairCols(JSONArray ts, boolean fight) {
        if (fight) return new int[] { 0xFFB3261E, 0xFF0A63C9 };
        int a = teamCol(ts.optJSONObject(0), 0xFF3A4A66), b = teamCol(ts.optJSONObject(1), 0xFF4A3A66);
        int dr = Color.red(a) - Color.red(b), dg = Color.green(a) - Color.green(b), db = Color.blue(a) - Color.blue(b);
        if (dr * dr + dg * dg + db * db < 60 * 60) {
            int alt = parse(ts.optJSONObject(1).optString("alt"), 0);
            b = alt != 0 && lum(alt) < .5f && lum(alt) > .04f ? alt : shade(b, .55f);
        }
        return new int[] { a, b };
    }
    /** a team's short name for tight spots ("GB"), its name when ESPN gave no abbreviation */
    private static String abbr(JSONObject t) { String a = t.optString("abbr"); return a.isEmpty() ? t.optString("name") : a; }
    private static String sportOf(JSONObject it) { SportsData.Lg l = SportsData.league(it.optString("lg")); return l == null ? "" : l.sport; }
    /** a logo in a disc of the team's colour */
    private void disc(Canvas c, Paint p, JSONObject t, int col, float cx, float cy, float d) {
        p.setColor(col); c.drawCircle(cx, cy, d / 2, p);
        Bitmap lgB = logo(t.optString("logo"), Math.round(d));
        if (lgB != null) {
            float sc0 = Math.min(d * .68f / lgB.getWidth(), d * .68f / lgB.getHeight());
            float dw = lgB.getWidth() * sc0, dh = lgB.getHeight() * sc0;
            p.setAlpha(255);
            c.drawBitmap(lgB, null, new RectF(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2), p);
        } else {
            String ab = abbr(t); if (ab.length() > 4) ab = ab.substring(0, 3);
            text(c, ab.toUpperCase(java.util.Locale.US), heavy, d / R * .3f, Color.WHITE, cx, cy + d * .11f, Paint.Align.CENTER, 0);
        }
    }
    /** what's on the clock: "Q2 4:12", "BOT 7TH", "FINAL", or the start time */
    private static String status(JSONObject it) {
        String st = it.optString("state");
        if (st.equals("in")) { String s = (it.optString("per") + " " + it.optString("clock")).trim(); return s.isEmpty() ? it.optString("when").replaceFirst("^LIVE · ", "") : s; }
        if (st.equals("post")) return it.optString("per").isEmpty() ? "FINAL" : it.optString("per");
        return it.optString("when");
    }

    /** one game as a scorebug: the teams' colours across the top, league and clock, two lines of teams with their scores, and where to watch */
    private Bitmap paintCard(JSONObject it) {
        int w = h.px(CW), hh = h.px(CH);
        Bitmap out = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (it.optBoolean("fy")) { paintPlayer(c, it, w, hh, p); out.setHasAlpha(false); return out; }
        if (it.optBoolean("edit")) {
            c.drawColor(0xFF0E1A16);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(.12f * R); p.setColor(0x59A8FF60);
            p.setPathEffect(new android.graphics.DashPathEffect(new float[] { .6f * R, .4f * R }, 0));
            c.drawRoundRect(new RectF(.4f * R, .4f * R, w - .4f * R, hh - .4f * R), 1.3f * R, 1.3f * R, p);
            p.setPathEffect(null); p.setStyle(Paint.Style.FILL);
            text(c, "+", h.fReg, 3.4f, 0xFFA8FF60, w / 2f, hh / 2f - .2f * R, Paint.Align.CENTER, 0);
            text(c, it.optString("label"), h.fSemi, 1.2f, Color.WHITE, w / 2f, hh / 2f + 2.2f * R, Paint.Align.CENTER, 0);
            out.setHasAlpha(false);
            return out;
        }
        JSONArray ts = it.optJSONArray("teams");
        if (ts == null || ts.length() < 2) return null;
        String state = it.optString("state");
        boolean live = state.equals("in"), post = state.equals("post"), fight = it.optBoolean("card");
        int[] cs = pairCols(ts, fight);
        float rad = 1.6f * R;
        c.drawColor(GROUND);
        Path clip = new Path(); clip.addRoundRect(new RectF(0, 0, w, hh), rad, rad, Path.Direction.CW);
        c.save(); c.clipPath(clip);
        p.setColor(CARD); c.drawRect(0, 0, w, hh, p);
        // the two teams' colours across the top
        p.setColor(cs[0]); c.drawRect(0, 0, w / 2f, .42f * R, p);
        p.setColor(cs[1]); c.drawRect(w / 2f, 0, w, .42f * R, p);
        c.restore();
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1, (live ? .14f : .08f) * R)); p.setColor(live ? LIVE_C : EDGE);
        c.drawRoundRect(new RectF(p.getStrokeWidth() / 2, p.getStrokeWidth() / 2, w - p.getStrokeWidth() / 2, hh - p.getStrokeWidth() / 2), rad, rad, p);
        p.setStyle(Paint.Style.FILL);
        float px = 1.4f * R, y = 2.65f * R;
        // league · clock
        String lg = it.optString("lgName").toUpperCase(java.util.Locale.US);
        String st = status(it).toUpperCase(java.util.Locale.US);
        float stw = measure(st, cond, 1.2f, .04f);
        text(c, ellip(lg, cond, 1.1f, w - 2 * px - stw - 2 * R), cond, 1.1f, DIMTXT, px, y, Paint.Align.LEFT, .06f);
        text(c, st, cond, 1.2f, live ? LIVE_C : post ? DIMTXT : GOLD, w - px, y, Paint.Align.RIGHT, .04f);
        if (live) { p.setColor(LIVE_C); c.drawCircle(w - px - stw - .7f * R, y - .42f * R, .3f * R, p); }
        else if (it.optBoolean("bell")) text(c, "🔔", h.fReg, .95f, GOLD, w - px - stw - .5f * R, y, Paint.Align.RIGHT, 0);
        // the two teams: logo, name, then the score (or the record before the game)
        int poss = it.optInt("poss", -1);
        for (int i = 0; i < 2; i++) {
            JSONObject t = ts.optJSONObject(i);
            float cy = 5.75f * R + i * 3.75f * R;
            boolean dim = (post && !t.optBoolean("winner") && !fight) || (live && !lead(ts, i));
            float d = 2.8f * R;
            disc(c, p, t, cs[i], px + d / 2, cy, d);
            float nx = px + d + 1f * R;
            String sc = t.optString("score");
            boolean showScore = !fight && (live || post) && !sc.isEmpty();
            String right = showScore ? sc : fight && post && t.optBoolean("winner") ? "W" : t.optString("rec");
            Typeface rf = showScore ? heavy : cond;
            float rs = showScore ? 2.5f : 1.15f;
            float rw = right.isEmpty() ? 0 : measure(right, rf, rs, 0) + 1f * R;
            String nm = (t.optInt("rank") > 0 ? "#" + t.optInt("rank") + " " : "") + t.optString("name");
            float nmW = w - px - nx - rw - (t.optBoolean("fav") ? 1.5f * R : 0) - (poss == i ? 1.4f * R : 0);
            String shown = ellip(nm, cond, 1.75f, nmW);
            text(c, shown, cond, 1.75f, dim ? 0xFFAEB8C4 : Color.WHITE, nx, cy + .62f * R, Paint.Align.LEFT, 0);
            float ex = nx + measure(shown, cond, 1.75f, 0) + .5f * R;
            if (poss == i) { football(c, p, ex + .55f * R, cy, .55f * R); ex += 1.4f * R; }
            if (t.optBoolean("fav")) text(c, "★", h.fSemi, 1.1f, GOLD, ex, cy + .5f * R, Paint.Align.LEFT, 0);
            if (!right.isEmpty()) text(c, right, rf, rs, showScore ? (dim ? 0xFFAEB8C4 : Color.WHITE) : right.equals("W") ? GOLD : DIMTXT, w - px, cy + (showScore ? .85f : .45f) * R, Paint.Align.RIGHT, 0);
        }
        // foot: the down and distance / the count while it's on, else where to watch (green when it's one of your channels); your fantasy players in it
        String foot;
        boolean tvLine = false;
        if (live && !it.optString("down").isEmpty()) foot = it.optString("down") + (it.optString("spot").isEmpty() ? "" : " · " + it.optString("spot"));
        else if (live && it.has("outs")) foot = it.optInt("balls") + "–" + it.optInt("strikes") + " · " + it.optInt("outs") + (it.optInt("outs") == 1 ? " out" : " outs");
        else { String tv = it.optString("tv"); foot = !it.optString("extra").isEmpty() && tv.isEmpty() ? it.optString("extra") : tv; tvLine = !tv.isEmpty(); }
        if (post && foot.equals(it.optString("tv"))) foot = "";
        float by = hh - 1.3f * R;
        Integer mine = playersIn.get(it.optString("k").split(":")[0]);
        float starW = 0;
        if (mine != null && mine > 0) {
            String sm = "★ " + mine;
            starW = measure(sm, h.fSemi, 1.1f, 0) + 1 * R;
            text(c, sm, h.fSemi, 1.1f, GOLD, w - px, by, Paint.Align.RIGHT, 0);
        }
        if (!foot.isEmpty()) {
            boolean on = tvLine && it.optBoolean("tvOn");
            float fx = px;
            if (tvLine) {
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(.14f * R); p.setColor(on ? 0xFFA8FF60 : DIMTXT);
                c.drawRoundRect(new RectF(px, by - 1.05f * R, px + 1.3f * R, by - .2f * R), .25f * R, .25f * R, p);
                c.drawLine(px + .35f * R, by + .1f * R, px + .95f * R, by + .1f * R, p);
                p.setStyle(Paint.Style.FILL);
                fx += 1.8f * R;
            }
            boolean sit = live && !tvLine;
            Typeface ff = sit ? cond : on ? h.fSemi : h.fReg;
            int fc = sit ? (it.optBoolean("red") ? LIVE_C : 0xFFE6EBF1) : on ? 0xFFA8FF60 : DIMTXT;
            text(c, ellip(sit ? foot.toUpperCase(java.util.Locale.US) : foot, ff, sit ? 1.2f : 1.1f, w - fx - px - starW), ff, sit ? 1.2f : 1.1f, fc, fx, by, Paint.Align.LEFT, sit ? .03f : 0);
        }
        out.setHasAlpha(false);
        return out;
    }
    /** a little football (who has the ball) */
    private static void football(Canvas c, Paint p, float cx, float cy, float rx) {
        p.setColor(GOLD);
        c.drawOval(new RectF(cx - rx, cy - rx * .66f, cx + rx, cy + rx * .66f), p);
        p.setColor(0xFF3A2A00); p.setStrokeWidth(Math.max(1, rx * .14f));
        c.drawLine(cx - rx * .45f, cy, cx + rx * .45f, cy, p);
        for (int k = -1; k <= 1; k++) c.drawLine(cx + k * rx * .22f, cy - rx * .2f, cx + k * rx * .22f, cy + rx * .2f, p);
    }
    private static boolean lead(JSONArray ts, int i) {
        try { double a = Double.parseDouble(ts.optJSONObject(i).optString("score")), b = Double.parseDouble(ts.optJSONObject(1 - i).optString("score")); return a >= b; } catch (Exception e) { return true; }
    }

    /* The big scoreboard (Gameday): the board split corner to corner in the two teams' colours with their logos
       large and faint behind, LIVE · league · week · venue, where to watch, the logos and names at each side, big
       scores and the clock in the middle; along the bottom a dark band with what's happening: the down and
       distance on a little field, the count and the runners, the last play and the chance each side wins (before
       the game: the start, the line and where it's on; after it: the recap's headline). */
    private Bitmap paintGameHero(JSONObject it) {
        if (h.portrait) return paintGameHeroTall(it);
        int w = Math.round(heroWidth * R), hh = Math.round(HERO_H * R);
        if (w <= 0) w = Math.round((SW - 2 * PAD) * R);
        Bitmap out = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float rad = 2.2f * R;
        Path clip = new Path(); clip.addRoundRect(new RectF(0, 0, w, hh), rad, rad, Path.Direction.CW);
        c.clipPath(clip);
        JSONArray ts = it.optJSONArray("teams");
        String state = it.optString("state"), sport = sportOf(it);
        boolean live = state.equals("in"), post = state.equals("post"), fight = it.optBoolean("card");
        int[] cs = pairCols(ts, fight);
        // the split
        p.setColor(cs[0]); c.drawRect(0, 0, w, hh, p);
        Path sp = new Path(); sp.moveTo(w * .545f, 0); sp.lineTo(w, 0); sp.lineTo(w, hh); sp.lineTo(w * .455f, hh); sp.close();
        p.setColor(cs[1]); c.drawPath(sp, p);
        // the logos, large and faint, cut by the edges
        if (!fight) for (int i = 0; i < 2; i++) {
            float sz = hh * 1.15f;
            Bitmap lgB = logo(ts.optJSONObject(i).optString("logo"), Math.round(sz * .5f));
            if (lgB == null) continue;
            float s = Math.min(sz / lgB.getWidth(), sz / lgB.getHeight()), dw = lgB.getWidth() * s, dh = lgB.getHeight() * s;
            float lx = i == 0 ? -dw * .3f : w - dw * .7f, ly = (hh - dh) / 2f - hh * .06f;
            p.setAlpha(34); c.drawBitmap(lgB, null, new RectF(lx, ly, lx + dw, ly + dh), p); p.setAlpha(255);
        }
        p.setColor(0x6B04070A); c.drawRect(0, 0, w, hh, p);
        p.setShader(new RadialGradient(w / 2f, hh * .4f, w * .2f, 0x66000000, 0x00000000, Shader.TileMode.CLAMP)); c.drawRect(0, 0, w, hh, p); p.setShader(null);
        float px = 2.6f * R, cx = w / 2f;
        // top: LIVE · league · week · note · venue, and where to watch
        float tx = px;
        if (live) {
            float cw0 = measure("LIVE", heavy, 1.15f, .1f) + 2.6f * R;
            p.setColor(LIVE_C); c.drawRoundRect(new RectF(px, 1.45f * R, px + cw0, 3.75f * R), .45f * R, .45f * R, p);
            p.setColor(0xFF160400); c.drawCircle(px + 1f * R, 2.6f * R, .32f * R, p);
            text(c, "LIVE", heavy, 1.15f, 0xFF160400, px + 1.7f * R, 3.05f * R, Paint.Align.LEFT, .1f);
            tx += cw0 + 1.1f * R;
        }
        String tv = it.optString("tv");
        boolean on = it.optBoolean("tvOn");
        float ww = 0;
        if (!tv.isEmpty() && !post) {
            String lab = (on ? "WATCH ON " : "ON ") + tv.toUpperCase(java.util.Locale.US);
            lab = ellip(lab, cond, 1.25f, w * .34f);
            ww = measure(lab, cond, 1.25f, .05f) + (on ? 4.4f : 2.8f) * R;
            RectF b = new RectF(w - px - ww, 1.2f * R, w - px, 4f * R);
            p.setColor(on ? Color.WHITE : 0x40000000); c.drawRoundRect(b, 1.4f * R, 1.4f * R, p);
            if (on) {
                Path tri = new Path(); float tl = b.left + 1.4f * R, tc = b.centerY();
                tri.moveTo(tl, tc - .55f * R); tri.lineTo(tl + .95f * R, tc); tri.lineTo(tl, tc + .55f * R); tri.close();
                p.setColor(GROUND); c.drawPath(tri, p);
            }
            text(c, lab, cond, 1.25f, on ? GROUND : Color.WHITE, b.right - 1.4f * R, b.centerY() + .45f * R, Paint.Align.RIGHT, .05f);
            ww += 1.4f * R;
        }
        StringBuilder top = new StringBuilder(it.optString("lgName"));
        if (it.optInt("week") > 0) top.append(" · Week ").append(it.optInt("week"));
        if (!it.optString("note").isEmpty()) top.append(" · ").append(it.optString("note"));
        if (!it.optString("venue").isEmpty()) top.append(" · ").append(it.optString("venue"));
        text(c, ellip(top.toString().toUpperCase(java.util.Locale.US), cond, 1.3f, w - tx - px - ww), cond, 1.3f, 0xFFE6EBF1, tx, 3.05f * R, Paint.Align.LEFT, .08f);

        // the teams: logo, name (ranked, a star for yours), record
        float ld = 7f * R, ly = 5.4f * R;
        int poss = it.optInt("poss", -1);
        String s0 = ts.optJSONObject(0).optString("score"), s1 = ts.optJSONObject(1).optString("score");
        boolean scores = (live || post) && !fight && !s0.isEmpty();
        float ss = scores && Math.max(s0.length(), s1.length()) >= 3 ? 7.2f : 9.6f;
        float scoreW = scores ? Math.max(measure(s0, heavy, ss, -.02f), measure(s1, heavy, ss, -.02f)) : 0;
        float gapC = 6.8f * R;
        for (int i = 0; i < 2; i++) {
            JSONObject t = ts.optJSONObject(i);
            boolean left = i == 0;
            boolean dim = post && !t.optBoolean("winner") && !fight;
            float lx = left ? px : w - px - ld;
            if (fight) { p.setColor(0x33FFFFFF); c.drawCircle(lx + ld / 2, ly + ld / 2, ld / 2, p); Bitmap fl = logo(t.optString("logo"), Math.round(ld)); if (fl != null) { float s = Math.min(ld * .6f / fl.getWidth(), ld * .6f / fl.getHeight()); c.drawBitmap(fl, null, new RectF(lx + ld / 2 - fl.getWidth() * s / 2, ly + ld / 2 - fl.getHeight() * s / 2, lx + ld / 2 + fl.getWidth() * s / 2, ly + ld / 2 + fl.getHeight() * s / 2), p); } }
            else {
                p.setColor(Color.WHITE); c.drawCircle(lx + ld / 2, ly + ld / 2, ld / 2, p);
                Bitmap lgB = logo(t.optString("logo"), Math.round(ld), false);
                if (lgB != null) {
                    float s = Math.min(ld * .7f / lgB.getWidth(), ld * .7f / lgB.getHeight()), dw = lgB.getWidth() * s, dh = lgB.getHeight() * s;
                    p.setAlpha(dim ? 170 : 255);
                    c.drawBitmap(lgB, null, new RectF(lx + (ld - dw) / 2, ly + (ld - dh) / 2, lx + (ld + dw) / 2, ly + (ld + dh) / 2), p);
                    p.setAlpha(255);
                } else text(c, abbr(t).toUpperCase(java.util.Locale.US), heavy, 2f, cs[i], lx + ld / 2, ly + ld / 2 + .7f * R, Paint.Align.CENTER, 0);
            }
            float nmax = cx - gapC - scoreW - 1.6f * R - px;
            if (!scores) nmax = cx - 9 * R - px;
            String nm = (t.optInt("rank") > 0 ? "#" + t.optInt("rank") + " " : "") + t.optString(fight ? "full" : "name");
            nm = nm.toUpperCase(java.util.Locale.US);
            float ns = measure(nm, heavy, 2.7f, .01f) > nmax ? 2.2f : 2.7f;
            float nx = left ? px : w - px;
            Paint.Align al = left ? Paint.Align.LEFT : Paint.Align.RIGHT;
            text(c, ellip(nm, heavy, ns, nmax), heavy, ns, dim ? 0xFFC9D1DA : Color.WHITE, nx, ly + ld + 2.75f * R, al, .01f);
            String sub = t.optString("rec");
            if (t.optBoolean("fav")) sub = sub.isEmpty() ? "★ YOUR TEAM" : sub + "  ·  ★ YOUR TEAM";
            if (!sub.isEmpty()) text(c, ellip(sub, h.fMed, 1.3f, nmax), h.fMed, 1.3f, t.optBoolean("fav") ? 0xFFFFD873 : 0xFFDCE3EA, nx, ly + ld + 4.75f * R, al, 0);
        }
        // the middle: the scores and the clock, the start time, or VS
        float sy = 14.4f * R;
        if (scores) {
            boolean w0 = post && ts.optJSONObject(0).optBoolean("winner"), w1 = post && ts.optJSONObject(1).optBoolean("winner");
            boolean d0 = (post && !w0) || (live && !lead(ts, 0)), d1 = (post && !w1) || (live && !lead(ts, 1));
            text(c, s0, heavy, ss, d0 ? 0xFFC9D1DA : Color.WHITE, cx - gapC, sy, Paint.Align.RIGHT, -.02f);
            text(c, s1, heavy, ss, d1 ? 0xFFC9D1DA : Color.WHITE, cx + gapC, sy, Paint.Align.LEFT, -.02f);
            float sh = ss * R * .36f;
            if (poss == 0) football(c, p, cx - gapC - scoreW - 1.6f * R, sy - sh, .75f * R);
            if (poss == 1) football(c, p, cx + gapC + scoreW + 1.6f * R, sy - sh, .75f * R);
            // the winner's arrow
            if (w0 || w1) {
                Path tri = new Path(); float ax = w0 ? cx - gapC + .9f * R : cx + gapC - .9f * R, ay = sy - sh, sgn = w0 ? -1 : 1;
                tri.moveTo(ax, ay - .7f * R); tri.lineTo(ax, ay + .7f * R); tri.lineTo(ax + sgn * .9f * R, ay); tri.close();
                p.setColor(GOLD); c.drawPath(tri, p);
            }
            String per = live ? it.optString("per") : post ? (it.optString("per").isEmpty() ? "FINAL" : it.optString("per")) : "";
            if (per.isEmpty() && live) per = "LIVE";
            String clock = live ? it.optString("clock") : "";
            float pz = measure(per, heavy, 2.6f, .02f) > 2 * gapC - 1.2f * R ? 1.8f : 2.6f;
            text(c, per, heavy, pz, Color.WHITE, cx, clock.isEmpty() ? sy - 2.6f * R : sy - 4.2f * R, Paint.Align.CENTER, .02f);
            if (!clock.isEmpty()) text(c, clock, cond, 2.3f, GOLD, cx, sy - 1.3f * R, Paint.Align.CENTER, 0);
        } else if (fight) {
            text(c, "VS", heavy, 4.4f, Color.WHITE, cx, sy - 2 * R, Paint.Align.CENTER, 0);
        } else {
            String when = it.optString("when");
            int spc = when.indexOf(' ');
            String day = spc > 0 && !Character.isDigit(when.charAt(0)) ? when.substring(0, spc) : "", time = spc > 0 && !day.isEmpty() ? when.substring(spc + 1) : when;
            if (!day.isEmpty()) text(c, day.toUpperCase(java.util.Locale.US), cond, 1.5f, 0xFFDCE3EA, cx, sy - 6.4f * R, Paint.Align.CENTER, .12f);
            float tz = measure(time, heavy, 3.6f, 0) > 2 * 9 * R ? 2.4f : 3.6f;
            text(c, time, heavy, tz, Color.WHITE, cx, sy - 2.8f * R, Paint.Align.CENTER, 0);
        }

        // the band along the bottom
        float bandY = hh - 7.4f * R;
        p.setColor(0xD9030508); c.drawRect(0, bandY, w, hh, p);
        float c1w = 15.5f * R, c3w = Math.min(25 * R, w * .33f);
        float x2 = px + c1w + 1.6f * R, x2e = w - px - c3w - 2f * R;
        float c3x = w - px - c3w;
        String a0 = abbr(ts.optJSONObject(0)), a1 = abbr(ts.optJSONObject(1));
        float hw = (float) it.optDouble("homeWin", -1);
        Integer mine = playersIn.get(it.optString("k").split(":")[0]);
        String fyNote = mine != null && mine > 0 ? "★ " + mine + " of your fantasy players" + (mine == 1 ? " is" : " are") + " in this game" : "";
        if (live && (!it.optString("down").isEmpty() || it.optDouble("ball", -1) >= 0)) {
            // football: the down and distance, and the field
            boolean red = it.optBoolean("red");
            String dn = it.optString("down").toUpperCase(java.util.Locale.US);
            text(c, ellip(dn.isEmpty() ? "LIVE" : dn, heavy, 2.7f, c1w), heavy, 2.7f, red ? LIVE_C : Color.WHITE, px, bandY + 3.5f * R, Paint.Align.LEFT, 0);
            String spot = it.optString("spot");
            String who = poss == 0 ? a0 : poss == 1 ? a1 : "";
            String sl = (red ? "RED ZONE · " : "") + (who.isEmpty() ? "" : who + " ball") + (spot.isEmpty() ? "" : (who.isEmpty() ? "Ball on " : " on ") + spot);
            text(c, ellip(sl, h.fMed, 1.2f, c1w), h.fMed, 1.2f, red ? 0xFFFFB4A1 : 0xFFAEB8C4, px, bandY + 5.6f * R, Paint.Align.LEFT, 0);
            drawField(c, p, it, cs, x2, bandY + 1.5f * R, x2e - x2, 2.6f * R);
            if (hw >= 0) winLine(c, x2, x2e, bandY + 5.9f * R, a0, a1, hw, false, cs, p);
        } else if (live && it.has("outs")) {
            // baseball: the runners, the count and the outs; who's up
            float dx = px + 2.2f * R, dy = bandY + 3.7f * R;
            JSONArray bs = it.optJSONArray("bases");
            for (int b = 0; b < 3; b++) {
                boolean onb = bs != null && bs.optBoolean(b);
                float bx = b == 0 ? dx + 1.5f * R : b == 1 ? dx : dx - 1.5f * R, byy = b == 1 ? dy - 1.5f * R : dy;
                Path dia = new Path(); float q = .85f * R;
                dia.moveTo(bx, byy - q); dia.lineTo(bx + q, byy); dia.lineTo(bx, byy + q); dia.lineTo(bx - q, byy); dia.close();
                if (onb) { p.setColor(GOLD); c.drawPath(dia, p); }
                else { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1, .14f * R)); p.setColor(0xFF5B6675); c.drawPath(dia, p); p.setStyle(Paint.Style.FILL); }
            }
            int outs = it.optInt("outs");
            text(c, it.optInt("balls") + "–" + it.optInt("strikes"), heavy, 2.5f, Color.WHITE, px + 5.6f * R, bandY + 3.4f * R, Paint.Align.LEFT, 0);
            text(c, outs + (outs == 1 ? " OUT" : " OUTS"), cond, 1.35f, 0xFFAEB8C4, px + 5.6f * R, bandY + 5.5f * R, Paint.Align.LEFT, .06f);
            String ab = it.optString("batter"), pi = it.optString("pitcher");
            if (!ab.isEmpty()) labelled(c, "AT BAT", ab, x2, bandY + 3.1f * R, x2e - x2);
            if (!pi.isEmpty()) labelled(c, "PITCHING", pi, x2, bandY + 5.6f * R, x2e - x2);
        } else if (live) {
            String clk = (it.optString("per") + " " + it.optString("clock")).trim();
            text(c, ellip(clk.isEmpty() ? "LIVE" : clk, heavy, 2.7f, c1w), heavy, 2.7f, Color.WHITE, px, bandY + 3.5f * R, Paint.Align.LEFT, 0);
            text(c, ellip(it.optString("lgName"), h.fMed, 1.2f, c1w), h.fMed, 1.2f, 0xFFAEB8C4, px, bandY + 5.6f * R, Paint.Align.LEFT, 0);
            if (hw >= 0) winLine(c, x2, x2e, bandY + 4.4f * R, a0, a1, hw, true, cs, p);
            else if (!fyNote.isEmpty()) { text(c, ellip(fyNote, h.fSemi, 1.25f, x2e - x2), h.fSemi, 1.25f, GOLD, x2, bandY + 4.3f * R, Paint.Align.LEFT, 0); fyNote = ""; }
            else if (fight && !it.optString("extra").isEmpty()) text(c, ellip(it.optString("extra"), h.fMed, 1.25f, x2e - x2), h.fMed, 1.25f, 0xFFDCE3EA, x2, bandY + 4.3f * R, Paint.Align.LEFT, 0);
        } else if (post) {
            text(c, it.optString("per").isEmpty() ? "FINAL" : it.optString("per"), heavy, 2.7f, Color.WHITE, px, bandY + 3.5f * R, Paint.Align.LEFT, 0);
            JSONObject wt = ts.optJSONObject(0).optBoolean("winner") ? ts.optJSONObject(0) : ts.optJSONObject(1).optBoolean("winner") ? ts.optJSONObject(1) : null;
            if (wt != null) text(c, ellip(wt.optString("name") + " win", h.fMed, 1.2f, c1w), h.fMed, 1.2f, 0xFFAEB8C4, px, bandY + 5.6f * R, Paint.Align.LEFT, 0);
            String hl = it.optString("headline");
            if (!hl.isEmpty()) { label(c, "RECAP", x2, bandY + 2.3f * R); wrap(c, hl, h.fMed, 1.25f, 0xFFE6EBF1, x2, bandY + 2.9f * R, x2e - x2, 2); }
            else if (fight && !it.optString("extra").isEmpty()) wrap(c, it.optString("extra"), h.fMed, 1.25f, 0xFFE6EBF1, x2, bandY + 2.9f * R, x2e - x2, 2);
        } else {
            // before the game: the start, the line, the venue
            String word = sport.equals("baseball") ? "FIRST PITCH" : sport.equals("hockey") ? "PUCK DROP" : sport.equals("basketball") ? "TIP-OFF" : fight ? "MAIN CARD" : "KICKOFF";
            label(c, word, px, bandY + 2.3f * R);
            long left = it.optLong("at") - System.currentTimeMillis();
            String cd = left <= 0 ? "ANY MINUTE" : left < 3600000L ? "IN " + Math.max(1, left / 60000) + " MIN" : left < 24 * 3600000L ? "IN " + left / 3600000L + "H " + (left % 3600000L) / 60000 + "M" : it.optString("when").toUpperCase(java.util.Locale.US);
            text(c, ellip(cd, heavy, 2.4f, c1w), heavy, 2.4f, Color.WHITE, px, bandY + 5.3f * R, Paint.Align.LEFT, 0);
            if (!it.optString("line").isEmpty()) labelled(c, "LINE", it.optString("line"), x2, bandY + 3.1f * R, x2e - x2);
            else if (fight && !it.optString("extra").isEmpty()) labelled(c, "CARD", it.optString("extra"), x2, bandY + 3.1f * R, x2e - x2);
            if (!it.optString("venue").isEmpty()) labelled(c, "VENUE", it.optString("venue"), x2, bandY + 5.6f * R, x2e - x2);
        }
        // the right of the band: the last play, else where to watch / your fantasy players
        String last = it.optString("last");
        if (live && !last.isEmpty()) { label(c, "LAST PLAY", c3x, bandY + 2.3f * R); wrap(c, last, h.fMed, 1.2f, 0xFFD5DCE4, c3x, bandY + 2.8f * R, c3w, 3); }
        else if (!fyNote.isEmpty()) { label(c, "FANTASY", c3x, bandY + 2.3f * R); wrap(c, fyNote, h.fSemi, 1.2f, 0xFFFFD873, c3x, bandY + 2.8f * R, c3w, 2); }
        else if (!post) {
            label(c, "WATCH", c3x, bandY + 2.3f * R);
            String wt = tv.isEmpty() ? "No TV listing yet" : on ? tv + " · on your channels" : tv;
            wrap(c, wt, on ? h.fSemi : h.fMed, 1.25f, on ? 0xFFA8FF60 : 0xFFD5DCE4, c3x, bandY + 2.8f * R, c3w, 2);
        }
        return out;
    }
    /* The scoreboard on a phone held upright: the two teams one above the other (logo, name, record, score) on
       their colours, the clock at the top right, and under them the same band of what's happening, stacked. */
    private Bitmap paintGameHeroTall(JSONObject it) {
        int w = Math.round(heroWidth * R), hh = Math.round(HERO_H * R);
        Bitmap out = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float rad = 2f * R;
        Path clip = new Path(); clip.addRoundRect(new RectF(0, 0, w, hh), rad, rad, Path.Direction.CW);
        c.clipPath(clip);
        JSONArray ts = it.optJSONArray("teams");
        String state = it.optString("state"), sport = sportOf(it);
        boolean live = state.equals("in"), post = state.equals("post"), fight = it.optBoolean("card");
        int[] cs = pairCols(ts, fight);
        float bandY = hh - 11.6f * R;
        p.setColor(cs[0]); c.drawRect(0, 0, w, bandY, p);
        Path sp = new Path(); sp.moveTo(0, 12.9f * R); sp.lineTo(w, 11.3f * R); sp.lineTo(w, bandY); sp.lineTo(0, bandY); sp.close();
        p.setColor(cs[1]); c.drawPath(sp, p);
        p.setColor(0x5904070A); c.drawRect(0, 0, w, bandY, p);
        float px = 2f * R;
        // top: LIVE · league · venue, and the clock (or the start)
        float tx = px;
        if (live) {
            float cw0 = measure("LIVE", heavy, 1.05f, .1f) + 2.4f * R;
            p.setColor(LIVE_C); c.drawRoundRect(new RectF(px, 1.2f * R, px + cw0, 3.3f * R), .45f * R, .45f * R, p);
            p.setColor(0xFF160400); c.drawCircle(px + .95f * R, 2.25f * R, .3f * R, p);
            text(c, "LIVE", heavy, 1.05f, 0xFF160400, px + 1.6f * R, 2.65f * R, Paint.Align.LEFT, .1f);
            tx += cw0 + 1f * R;
        }
        String clock = live ? (it.optString("per") + "  " + it.optString("clock")).trim() : post ? (it.optString("per").isEmpty() ? "FINAL" : it.optString("per")) : it.optString("when").toUpperCase(java.util.Locale.US);
        if (live && clock.isEmpty()) clock = "LIVE";
        float clw = measure(clock, heavy, 1.6f, .02f);
        text(c, clock, heavy, 1.6f, live ? GOLD : Color.WHITE, w - px, 2.85f * R, Paint.Align.RIGHT, .02f);
        StringBuilder top = new StringBuilder(it.optString("lgName"));
        if (it.optInt("week") > 0) top.append(" · Week ").append(it.optInt("week"));
        if (!it.optString("note").isEmpty()) top.append(" · ").append(it.optString("note"));
        text(c, ellip(top.toString().toUpperCase(java.util.Locale.US), cond, 1.15f, w - tx - px - clw - 1.5f * R), cond, 1.15f, 0xFFE6EBF1, tx, 2.75f * R, Paint.Align.LEFT, .08f);
        // the teams, one above the other
        int poss = it.optInt("poss", -1);
        String s0 = ts.optJSONObject(0).optString("score"), s1 = ts.optJSONObject(1).optString("score");
        boolean scores = (live || post) && !fight && !s0.isEmpty();
        for (int i = 0; i < 2; i++) {
            JSONObject t = ts.optJSONObject(i);
            float cy = (i == 0 ? 7.9f : 15.4f) * R, d = 5.4f * R;
            boolean dim = (post && !t.optBoolean("winner") && !fight) || (live && !lead(ts, i));
            p.setColor(fight ? 0x33FFFFFF : Color.WHITE); c.drawCircle(px + d / 2, cy, d / 2, p);
            Bitmap lgB = logo(t.optString("logo"), Math.round(d), fight);
            if (lgB != null) {
                float s = Math.min(d * .7f / lgB.getWidth(), d * .7f / lgB.getHeight()), dw = lgB.getWidth() * s, dh = lgB.getHeight() * s;
                c.drawBitmap(lgB, null, new RectF(px + d / 2 - dw / 2, cy - dh / 2, px + d / 2 + dw / 2, cy + dh / 2), p);
            } else text(c, abbr(t).toUpperCase(java.util.Locale.US), heavy, 1.5f, cs[i], px + d / 2, cy + .55f * R, Paint.Align.CENTER, 0);
            String sc = scores ? t.optString("score") : "";
            float scW = sc.isEmpty() ? 0 : measure(sc, heavy, 4.6f, -.02f);
            if (!sc.isEmpty()) text(c, sc, heavy, 4.6f, dim ? 0xFFC9D1DA : Color.WHITE, w - px, cy + 1.65f * R, Paint.Align.RIGHT, -.02f);
            if (poss == i) football(c, p, w - px - scW - 1.6f * R, cy, .7f * R);
            if (post && t.optBoolean("winner")) { Path tri = new Path(); float ax = w - px - scW - 1.2f * R; tri.moveTo(ax, cy - .7f * R); tri.lineTo(ax, cy + .7f * R); tri.lineTo(ax - .9f * R, cy); tri.close(); p.setColor(GOLD); c.drawPath(tri, p); }
            float nx = px + d + 1.3f * R, nmax = w - nx - px - scW - 3f * R;
            String nm = ((t.optInt("rank") > 0 ? "#" + t.optInt("rank") + " " : "") + t.optString(fight ? "full" : "name")).toUpperCase(java.util.Locale.US);
            float ns = measure(nm, heavy, 2.3f, .01f) > nmax ? 1.8f : 2.3f;
            text(c, ellip(nm, heavy, ns, nmax), heavy, ns, dim ? 0xFFC9D1DA : Color.WHITE, nx, cy + .15f * R, Paint.Align.LEFT, .01f);
            String sub = t.optString("rec") + (t.optBoolean("fav") ? (t.optString("rec").isEmpty() ? "" : "  ·  ") + "★ YOUR TEAM" : "");
            if (!sub.isEmpty()) text(c, ellip(sub, h.fMed, 1.15f, nmax), h.fMed, 1.15f, t.optBoolean("fav") ? 0xFFFFD873 : 0xFFDCE3EA, nx, cy + 2f * R, Paint.Align.LEFT, 0);
        }
        // the band: what's happening, stacked
        p.setColor(0xE6030508); c.drawRect(0, bandY, w, hh, p);
        float bw = w - 2 * px, y1 = bandY + 2.7f * R, y2 = bandY + 4.1f * R, y3 = bandY + 8.2f * R;
        String a0 = abbr(ts.optJSONObject(0)), a1 = abbr(ts.optJSONObject(1));
        float hw = (float) it.optDouble("homeWin", -1);
        String tv = it.optString("tv"); boolean on = it.optBoolean("tvOn");
        Integer mine = playersIn.get(it.optString("k").split(":")[0]);
        boolean usedSecond = false;
        if (live && (!it.optString("down").isEmpty() || it.optDouble("ball", -1) >= 0)) {
            boolean red = it.optBoolean("red");
            String dn = it.optString("down").toUpperCase(java.util.Locale.US);
            text(c, dn.isEmpty() ? "LIVE" : dn, heavy, 2.1f, red ? LIVE_C : Color.WHITE, px, y1, Paint.Align.LEFT, 0);
            String who = poss == 0 ? a0 : poss == 1 ? a1 : "", spot = it.optString("spot");
            String sl = (red ? "RED ZONE · " : "") + (who.isEmpty() ? "" : who + " ball") + (spot.isEmpty() ? "" : (who.isEmpty() ? "Ball on " : " on ") + spot);
            float dw0 = measure(dn.isEmpty() ? "LIVE" : dn, heavy, 2.1f, 0) + 1.2f * R;
            text(c, ellip(sl, h.fMed, 1.1f, bw - dw0), h.fMed, 1.1f, red ? 0xFFFFB4A1 : 0xFFAEB8C4, px + dw0, y1 - .1f * R, Paint.Align.LEFT, 0);
            drawField(c, p, it, cs, px, y2 - .2f * R, bw, 1.9f * R);
            if (hw >= 0) winLine(c, px, w - px, y2 + 3.6f * R, a0, a1, hw, false, cs, p);
            usedSecond = true;
        } else if (live && it.has("outs")) {
            JSONArray bs = it.optJSONArray("bases");
            float dx = px + 1.8f * R, dy = y1 - .5f * R;
            for (int b = 0; b < 3; b++) {
                boolean onb = bs != null && bs.optBoolean(b);
                float bx = b == 0 ? dx + 1.2f * R : b == 1 ? dx : dx - 1.2f * R, byy = b == 1 ? dy - 1.2f * R : dy;
                Path dia = new Path(); float q = .7f * R;
                dia.moveTo(bx, byy - q); dia.lineTo(bx + q, byy); dia.lineTo(bx, byy + q); dia.lineTo(bx - q, byy); dia.close();
                if (onb) { p.setColor(GOLD); c.drawPath(dia, p); }
                else { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1, .12f * R)); p.setColor(0xFF5B6675); c.drawPath(dia, p); p.setStyle(Paint.Style.FILL); }
            }
            int outs = it.optInt("outs");
            text(c, it.optInt("balls") + "–" + it.optInt("strikes") + "  ·  " + outs + (outs == 1 ? " OUT" : " OUTS"), heavy, 1.8f, Color.WHITE, px + 4.4f * R, y1, Paint.Align.LEFT, 0);
            String ab = it.optString("batter"), pi = it.optString("pitcher");
            if (!ab.isEmpty() || !pi.isEmpty()) labelled(c, "AT BAT", ab + (pi.isEmpty() ? "" : "   ·   P " + pi), px, y2 + 1.9f * R, bw);
            usedSecond = true;
        } else if (live) {
            if (hw >= 0) { winLine(c, px, w - px, y1, a0, a1, hw, true, cs, p); usedSecond = true; }
            else if (fight && !it.optString("extra").isEmpty()) text(c, ellip(it.optString("extra"), h.fMed, 1.2f, bw), h.fMed, 1.2f, 0xFFDCE3EA, px, y1, Paint.Align.LEFT, 0);
        } else if (post) {
            JSONObject wt = ts.optJSONObject(0).optBoolean("winner") ? ts.optJSONObject(0) : ts.optJSONObject(1).optBoolean("winner") ? ts.optJSONObject(1) : null;
            text(c, wt == null ? "FINAL" : (wt.optString("name") + " win").toUpperCase(java.util.Locale.US), heavy, 1.9f, Color.WHITE, px, y1, Paint.Align.LEFT, 0);
            String hl = it.optString("headline");
            if (!hl.isEmpty()) { wrap(c, hl, h.fMed, 1.2f, 0xFFE6EBF1, px, y2 - .2f * R, bw, 2); usedSecond = true; }
        } else {
            String word = sport.equals("baseball") ? "FIRST PITCH" : sport.equals("hockey") ? "PUCK DROP" : sport.equals("basketball") ? "TIP-OFF" : fight ? "MAIN CARD" : "KICKOFF";
            long left = it.optLong("at") - System.currentTimeMillis();
            String cd = left <= 0 ? "ANY MINUTE" : left < 3600000L ? "IN " + Math.max(1, left / 60000) + " MIN" : left < 24 * 3600000L ? "IN " + left / 3600000L + "H " + (left % 3600000L) / 60000 + "M" : it.optString("when").toUpperCase(java.util.Locale.US);
            float lw = measure(word, cond, 1.05f, .1f) + 1f * R;
            label(c, word, px, y1 - .15f * R);
            text(c, cd, heavy, 1.9f, Color.WHITE, px + lw, y1, Paint.Align.LEFT, 0);
            if (!it.optString("line").isEmpty()) { labelled(c, "LINE", it.optString("line"), px, y2 + 1.4f * R, bw); usedSecond = true; }
            if (!it.optString("venue").isEmpty()) { labelled(c, "VENUE", it.optString("venue"), px, y2 + (usedSecond ? 3.4f : 1.4f) * R, bw); usedSecond = true; }
        }
        // last: the last play, else your fantasy players / where to watch
        String last = it.optString("last");
        float ly = usedSecond ? y3 : y2 + .3f * R;
        if (live && !last.isEmpty()) { label(c, "LAST PLAY", px, ly); wrap(c, last, h.fMed, 1.1f, 0xFFD5DCE4, px + measure("LAST PLAY", cond, 1.05f, .1f) + 1f * R, ly - 1f * R, bw - measure("LAST PLAY", cond, 1.05f, .1f) - 1f * R, 2); }
        else if (mine != null && mine > 0) text(c, ellip("★ " + mine + " of your fantasy players in this game", h.fSemi, 1.15f, bw), h.fSemi, 1.15f, 0xFFFFD873, px, ly, Paint.Align.LEFT, 0);
        else if (!post) {
            String wt = tv.isEmpty() ? "No TV listing yet" : (on ? "▶  OK · Watch on " : "On ") + tv;
            text(c, ellip(wt, on ? h.fSemi : h.fMed, 1.2f, bw), on ? h.fSemi : h.fMed, 1.2f, on ? 0xFFA8FF60 : 0xFFD5DCE4, px, ly, Paint.Align.LEFT, 0);
        }
        return out;
    }
    private void label(Canvas c, String s, float x, float y) { text(c, s, cond, 1.05f, GOLD, x, y, Paint.Align.LEFT, .1f); }
    private void labelled(Canvas c, String lab, String val, float x, float y, float maxW) {
        float lw = measure(lab, cond, 1.05f, .1f) + 1f * R;
        text(c, lab, cond, 1.05f, GOLD, x, y, Paint.Align.LEFT, .1f);
        text(c, ellip(val, h.fMed, 1.3f, maxW - lw), h.fMed, 1.3f, 0xFFE6EBF1, x + lw, y, Paint.Align.LEFT, 0);
    }
    /** text wrapped onto a few lines below y (the top of the first line) */
    private void wrap(Canvas c, String s, Typeface tf, float sizeRem, int color, float x, float y, float width, int maxLines) {
        TextPaint wp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        wp.setTypeface(tf); wp.setTextSize(sizeRem * R); wp.setColor(color);
        StaticLayout sl = StaticLayout.Builder.obtain(s, 0, s.length(), wp, Math.max(1, Math.round(width)))
                .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.12f).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        c.save(); c.translate(x, y); sl.draw(c); c.restore();
    }
    /** "CAR 36%   WIN CHANCE   GB 64%" (with a bar split in the teams' colours when there's no field above it) */
    private void winLine(Canvas c, float x, float xe, float y, String a0, String a1, float home, boolean bar, int[] cs, Paint p) {
        int hp = Math.round(home * 100), ap = 100 - hp;
        text(c, a0 + " " + ap + "%", cond, 1.3f, ap > hp ? Color.WHITE : 0xFFAEB8C4, x, y, Paint.Align.LEFT, 0);
        text(c, a1 + " " + hp + "%", cond, 1.3f, hp > ap ? Color.WHITE : 0xFFAEB8C4, xe, y, Paint.Align.RIGHT, 0);
        text(c, "WIN CHANCE", cond, 1.0f, DIMTXT, (x + xe) / 2, y - .05f * R, Paint.Align.CENTER, .1f);
        if (bar) {
            float by = y + 1.1f * R, bh = .6f * R, split = x + (xe - x) * ap / 100f;
            p.setColor(cs[0]); c.drawRoundRect(new RectF(x, by, split, by + bh), bh / 2, bh / 2, p);
            p.setColor(cs[1]); c.drawRoundRect(new RectF(split, by, xe, by + bh), bh / 2, bh / 2, p);
            p.setColor(Color.WHITE); c.drawRect(split - .1f * R, by - .2f * R, split + .1f * R, by + bh + .2f * R, p);
        }
    }
    /** the field: end zones in the teams' colours, the yard lines, the line of scrimmage (blue) and the line to gain (gold), and the ball */
    private void drawField(Canvas c, Paint p, JSONObject it, int[] cs, float x, float y, float w, float fh) {
        float ez = w * .08f, play = w - 2 * ez;
        RectF f = new RectF(x, y, x + w, y + fh);
        c.save();
        Path cl = new Path(); cl.addRoundRect(f, .45f * R, .45f * R, Path.Direction.CW); c.clipPath(cl);
        p.setColor(0xFF2A6B3F); c.drawRect(f, p);
        p.setColor(cs[0]); c.drawRect(x, y, x + ez, y + fh, p);
        p.setColor(cs[1]); c.drawRect(x + w - ez, y, x + w, y + fh, p);
        for (int yd = 10; yd < 100; yd += 10) { p.setColor(yd == 50 ? 0x80FFFFFF : 0x40FFFFFF); float lx = x + ez + play * yd / 100f; c.drawRect(lx - Math.max(1, .05f * R), y, lx + Math.max(1, .05f * R), y + fh, p); }
        float ball = (float) it.optDouble("ball", -1), first = (float) it.optDouble("first", -1);
        if (ball >= 0) {
            float bx = x + ez + play * ball / 100f;
            p.setColor(0xFF4FC3F7); c.drawRect(bx - .12f * R, y, bx + .12f * R, y + fh, p);
            if (first >= 0) { float fx = x + ez + play * first / 100f; p.setColor(GOLD); c.drawRect(fx - .14f * R, y, fx + .14f * R, y + fh, p); }
            p.setColor(0xFF8A4B1F); RectF bo = new RectF(bx - .8f * R, y + fh / 2 - .5f * R, bx + .8f * R, y + fh / 2 + .5f * R); c.drawOval(bo, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1, .12f * R)); p.setColor(Color.WHITE); c.drawOval(bo, p); p.setStyle(Paint.Style.FILL);
        }
        c.restore();
    }

    /* your teams down the right: each a dark card with the team's short name huge and faint in its colour, its logo
       in a disc of that colour, the name and record, and the game's state; your fantasy team's points last */
    private float[] sideCard(int i) {
        int n = Math.max(1, sideItems.size());
        float top = 2.6f, gap = .8f, ch = h.portrait ? 6.4f : Math.min(6.4f, (HERO_H - top - (n - 1) * gap) / n);
        return new float[] { top + i * (ch + gap), ch };
    }
    private String sideKey = "";
    /** the your-teams column's height (rem) when it's stacked under the scoreboard */
    private float sideH() { int n = Math.max(1, sideItems.size()); return 2.6f + n * 6.4f + (n - 1) * .8f; }
    private void paintSide() {
        final List<Side> items = new ArrayList<>(sideItems);
        StringBuilder kb = new StringBuilder();
        for (Side s : items) kb.append(s.kind).append(s.team == null ? "" : s.team.optString("full") + s.team.optString("pts") + s.team.optString("proj") + s.team.optInt("left")).append(s.game == null ? "" : s.game.optString("k")).append('|');
        final String key = kb.toString();
        if (key.equals(sideKey)) return;
        sideKey = key;
        final int w = h.px(SIDE_W), hh = h.px(h.portrait ? sideH() : HERO_H);
        painter.execute(new Runnable() { @Override public void run() {
            final Bitmap out = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            text(c, "YOUR TEAMS", heavy, 1.15f, GOLD, 0, 1.7f * R, Paint.Align.LEFT, .14f);
            for (int i = 0; i < items.size(); i++) paintSideCard(c, p, items.get(i), sideCard(i), w);
            main().post(new Runnable() { @Override public void run() { if (key.equals(sideKey)) side.setImageBitmap(out); } });
        } });
    }
    private void paintSideCard(Canvas c, Paint p, Side s, float[] cr, int w) {
        float top = cr[0] * R, ch = cr[1] * R, rad = 1.4f * R;
        RectF rc = new RectF(0, top, w, top + ch);
        boolean live = s.game != null && "in".equals(s.game.optString("state"));
        c.save();
        Path cl = new Path(); cl.addRoundRect(rc, rad, rad, Path.Direction.CW); c.clipPath(cl);
        p.setColor(CARD); c.drawRect(rc, p);
        int col; String mark, name, rec, st; int stc;
        if (s.kind.equals("team")) {
            JSONObject t = s.team, g = s.game;
            col = teamCol(t, 0xFF3A4A66);
            mark = abbr(t).toUpperCase(java.util.Locale.US);
            name = (t.optInt("rank") > 0 ? "#" + t.optInt("rank") + " " : "") + t.optString("name");
            rec = t.optString("rec");
            JSONArray ts = g.optJSONArray("teams");
            JSONObject o = ts.optJSONObject(1 - s.idx);
            String vs = (s.idx == 1 ? "vs " : "at ") + abbr(o);
            String state = g.optString("state");
            String my = t.optString("score"), their = o.optString("score");
            if (state.equals("in")) {
                double a = num(my), b = num(their);
                st = "LIVE · " + (a > b ? "Up " : a < b ? "Down " : "Tied ") + my + "–" + their + " " + vs + " · " + status(g);
                stc = LIVE_C;
            } else if (state.equals("post")) {
                st = (t.optBoolean("winner") ? "W " : o.optBoolean("winner") ? "L " : "") + my + "–" + their + " " + vs + " · Final";
                stc = 0xFFAEB8C4;
            } else {
                st = g.optString("when") + " " + vs + (g.optString("tv").isEmpty() ? "" : " · " + g.optString("tv"));
                stc = GOLD;
            }
        } else if (s.kind.equals("fy")) {
            col = 0xFF5B4BD6; mark = "FANTASY";
            JSONObject t = s.team;
            if (t == null) { name = "My fantasy team"; rec = ""; st = "OK to pick your players"; stc = 0xFFAEB8C4; }
            else {
                name = t.optString("name").isEmpty() ? "My fantasy team" : t.optString("name");
                rec = t.optInt("week") > 0 ? "Week " + t.optInt("week") : "";
                int left = t.optInt("left"), pl = t.optInt("playing");
                st = t.optString("pts") + " pts" + (t.optString("proj").isEmpty() ? "" : " · Proj " + t.optString("proj")) + (pl > 0 ? " · " + pl + " playing" : left > 0 ? " · " + left + " to play" : "");
                stc = pl > 0 ? LIVE_C : GOLD;
            }
        } else { col = 0xFF2E7D4F; mark = "TEAMS"; name = "Follow your teams"; rec = ""; st = "OK to pick your sports and teams"; stc = 0xFFAEB8C4; }
        // the big faint mark
        int mc = lum(col) < .1f ? Color.rgb((Color.red(col) + 90) / 2 + 20, (Color.green(col) + 90) / 2 + 20, (Color.blue(col) + 90) / 2 + 20) : col;
        text(c, mark, heavy, ch / R * 1.45f, alpha(mc, .17f), w + .5f * R, top + ch * 1.08f, Paint.Align.RIGHT, -.02f);
        // the disc
        float d = ch * .64f, dcx = 1.2f * R + d / 2, dcy = top + ch / 2;
        if (s.kind.equals("team")) disc(c, p, s.team, col, dcx, dcy, d);
        else { p.setColor(col); c.drawCircle(dcx, dcy, d / 2, p); text(c, s.kind.equals("fy") ? "★" : "+", h.fSemi, d / R * .5f, Color.WHITE, dcx, dcy + d * .18f, Paint.Align.CENTER, 0); }
        float nx = dcx + d / 2 + 1.1f * R, maxW = w - nx - 1f * R;
        String nm = ellip(name, cond, 1.75f, maxW - (rec.isEmpty() ? 0 : measure(rec, h.fReg, 1.15f, 0) + .8f * R));
        text(c, nm, cond, 1.75f, Color.WHITE, nx, top + ch * .46f, Paint.Align.LEFT, 0);
        if (!rec.isEmpty()) text(c, rec, h.fReg, 1.15f, 0xFF9AA5B3, nx + measure(nm, cond, 1.75f, 0) + .7f * R, top + ch * .46f, Paint.Align.LEFT, 0);
        text(c, ellip(st, h.fSemi, 1.15f, maxW), h.fSemi, 1.15f, stc, nx, top + ch * .78f, Paint.Align.LEFT, 0);
        c.restore();
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1, (live ? .14f : .08f) * R)); p.setColor(live ? LIVE_C : EDGE);
        float sw = p.getStrokeWidth() / 2;
        c.drawRoundRect(new RectF(sw, top + sw, w - sw, top + ch - sw), rad, rad, p);
        p.setStyle(Paint.Style.FILL);
    }
    private static double num(String s) { try { return Double.parseDouble(s); } catch (Exception e) { return -1; } }

    /** the leagues you follow, as chips (logo, name, how many games, how many live): the one you're in is lit */
    private final class Rail extends View {
        private final List<int[]> rowsOf = new ArrayList<>();       // {row}
        private final List<String> names = new ArrayList<>(), icons = new ArrayList<>(), lgs = new ArrayList<>(), emoji = new ArrayList<>();
        private final List<int[]> counts = new ArrayList<>();       // {games, live}
        private final Paint rp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Map<String, Bitmap> got = new HashMap<>();
        final List<RectF> chips = new ArrayList<>();               // where each chip was drawn (for a finger)
        Rail(android.content.Context c) { super(c); }
        int count() { return rowsOf.size(); }
        int rowOf(int i) { return i >= 0 && i < rowsOf.size() ? rowsOf.get(i)[0] : -1; }
        void rebuild() {
            rowsOf.clear(); names.clear(); icons.clear(); lgs.clear(); emoji.clear(); counts.clear();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = off; i < allRows.size(); i++) {
                JSONArray it = allRows.get(i);
                String t = allTitles.get(i);
                if (t.toLowerCase(java.util.Locale.US).startsWith("live now") || t.toLowerCase(java.util.Locale.US).startsWith("my teams") || it.length() == 0) continue;
                JSONObject f = it.optJSONObject(0);
                String lg = f.optString("lg");
                if (lg.isEmpty() || !seen.add(lg)) continue;
                int games = 0, live = 0;
                for (int k = 0; k < it.length(); k++) { JSONObject g = it.optJSONObject(k); if (g.optJSONArray("teams") == null) continue; games++; if ("in".equals(g.optString("state"))) live++; }
                rowsOf.add(new int[] { i }); names.add(f.optString("lgName")); icons.add(f.optString("lgLogo").isEmpty() ? leagueLogo(lg) : f.optString("lgLogo")); lgs.add(lg); emoji.add(f.optString("icon"));
                counts.add(new int[] { games, live });
            }
            for (final String u : icons) if (!u.isEmpty() && !got.containsKey(u)) {
                got.put(u, null);
                painter.execute(new Runnable() { @Override public void run() {
                    final Bitmap b = logo(u, h.px(3f), false);     // (drawn on a white disc: the light-background version)
                    main().post(new Runnable() { @Override public void run() { got.put(u, b); invalidate(); } });
                } });
            }
            setVisibility(rowsOf.isEmpty() ? GONE : VISIBLE);
            invalidate();
        }
        @Override protected void onDraw(Canvas c) {
            float hh = 3.6f * R, top = .4f * R, x = PAD * R, d = 2.5f * R;
            int cur = tab.equals("Today") ? leagueSel : -1;
            boolean foc = zone.equals("rail");
            // (slid along so the league you're on is on screen)
            float[] ws = new float[rowsOf.size()], nws = new float[rowsOf.size()];
            float selR = 0;
            for (int i = 0; i < rowsOf.size(); i++) {
                int[] n = counts.get(i);
                nws[i] = NativeSports.this.measure(names.get(i), cond, 1.35f, .02f);
                ws[i] = .5f * R + d + .9f * R + nws[i] + .9f * R + NativeSports.this.measure(n[1] > 0 ? n[1] + " LIVE" : String.valueOf(n[0]), cond, 1.15f, .04f) + 1.3f * R;
                if (i <= leagueSel) selR += ws[i] + (i > 0 ? 1f * R : 0);
            }
            x -= Math.max(0, x + selR - (getWidth() - PAD * R));
            chips.clear();
            for (int i = 0; i < rowsOf.size(); i++) {
                if (x > getWidth()) break;
                boolean sel = i == cur, f = foc && i == leagueSel;
                String nm = names.get(i);
                int[] n = counts.get(i);
                String cnt = n[1] > 0 ? n[1] + " LIVE" : String.valueOf(n[0]);
                float nw = nws[i], w = ws[i];
                RectF chip = new RectF(x, top, x + w, top + hh);
                chips.add(new RectF(chip));
                float cr = GlassPanel.corner(hh / 2);
                if (f) { rp.setColor((GlassPanel.selFill() & 0x00FFFFFF) | 0x59000000); c.drawRoundRect(new RectF(chip.left - .35f * R, chip.top - .35f * R, chip.right + .35f * R, chip.bottom + .35f * R), cr + .35f * R, cr + .35f * R, rp); }
                rp.setColor(sel || f ? GlassPanel.selFill() : 0x17FFFFFF); c.drawRoundRect(chip, cr, cr, rp);
                if (!sel && !f) { rp.setStyle(Paint.Style.STROKE); rp.setStrokeWidth(Math.max(1, .08f * R)); rp.setColor(0xFF2A3442); c.drawRoundRect(chip, cr, cr, rp); rp.setStyle(Paint.Style.FILL); }
                float dcx = x + .5f * R + d / 2, dcy = top + hh / 2;
                Bitmap b = got.get(icons.get(i));
                rp.setColor(sel || f ? 0xFFEEF1F5 : Color.WHITE); c.drawCircle(dcx, dcy, d / 2, rp);
                if (b != null) {
                    float s0 = Math.min(d * .66f / b.getWidth(), d * .66f / b.getHeight());
                    float dw = b.getWidth() * s0, dh = b.getHeight() * s0;
                    c.drawBitmap(b, null, new RectF(dcx - dw / 2, dcy - dh / 2, dcx + dw / 2, dcy + dh / 2), rp);
                } else if (!emoji.get(i).isEmpty()) text(c, emoji.get(i), h.fReg, 1.3f, Color.BLACK, dcx, dcy + .45f * R, Paint.Align.CENTER, 0);
                float tx = x + .5f * R + d + .9f * R;
                boolean plain = GlassPanel.look.equals("glass") || GlassPanel.look.equals("spot"), lit = sel || f;
                int st = GlassPanel.selText();
                text(c, nm, cond, 1.35f, lit ? (plain ? GROUND : st) : 0xFFDCE3EA, tx, dcy + .48f * R, Paint.Align.LEFT, .02f);
                text(c, cnt, cond, 1.15f, lit && !plain ? (st & 0x00FFFFFF) | 0xC7000000 : n[1] > 0 ? (lit ? 0xFFD9452B : LIVE_C) : lit ? 0xFF5B6675 : DIMTXT, tx + nw + .9f * R, dcy + .42f * R, Paint.Align.LEFT, .04f);
                x += w + 1f * R;
            }
        }
    }

    /** a fantasy player's card: position, name, their game and score, their line so far (or injury) */
    private void paintPlayer(Canvas c, JSONObject it, int w, int hh, Paint p) {
        if (it.optBoolean("fyTeam")) { paintTeam(c, it, w, hh, p); return; }
        JSONArray ts = it.optJSONArray("teams");
        String abbr = it.optString("abbr");
        int tc = 0xFF3A4A66;
        String logoUrl = "";
        if (ts != null) for (int i = 0; i < ts.length(); i++) if (ts.optJSONObject(i).optString("abbr").equals(abbr)) { tc = parse(ts.optJSONObject(i).optString("color"), tc); logoUrl = ts.optJSONObject(i).optString("logo"); }
        boolean live = "in".equals(it.optString("state")), post = "post".equals(it.optString("state"));
        p.setShader(new LinearGradient(0, 0, 0, hh, 0xFF141C2B, 0xFF0B111C, Shader.TileMode.CLAMP)); c.drawRect(0, 0, w, hh, p);
        p.setShader(new LinearGradient(w, 0, w * .35f, hh, alpha(tc, .55f), alpha(tc, 0), Shader.TileMode.CLAMP)); c.drawRect(0, 0, w, hh, p);
        p.setShader(null);
        // the player's photo (or the team's logo) on the right, fading in
        Bitmap ph = it.optString("photo").isEmpty() ? null : logo(it.optString("photo"), h.px(11));
        if (ph == null && !logoUrl.isEmpty()) ph = logo(logoUrl, h.px(8));
        if (ph != null) {
            float bh = hh * (it.optString("photo").isEmpty() ? .55f : .86f), s = bh / ph.getHeight(), bw = ph.getWidth() * s;
            float x = w - bw - (it.optString("photo").isEmpty() ? 1.2f * R : 0), y = hh - bh - (it.optString("photo").isEmpty() ? 2.2f * R : 0);
            p.setAlpha(it.optBoolean("bench") ? 120 : 235); c.drawBitmap(ph, null, new RectF(x, y, x + bw, y + bh), p); p.setAlpha(255);
            p.setShader(new LinearGradient(x - .5f * R, 0, x + bw * .55f, 0, 0xFF101826, 0x00101826, Shader.TileMode.CLAMP)); c.drawRect(x - R, 0, x + bw, hh, p); p.setShader(null);
        }
        p.setColor(live ? 0xFFFF453A : alpha(tc, .9f)); c.drawRect(0, 0, w, live ? .22f * R : .14f * R, p);
        float px = 1.3f * R;
        // position chip · team, and the game's state
        String pos = it.optString("pos");
        int pc = pos.equals("QB") ? 0xFFFF5A5F : pos.equals("RB") ? 0xFF30D158 : pos.equals("WR") ? 0xFF0A84FF : pos.equals("TE") ? 0xFFFF9F0A : pos.equals("K") ? 0xFFBF5AF2 : 0xFF8E8E93;
        float cw = measure(pos, h.fSemi, .85f, .04f) + 1.1f * R;
        p.setColor(alpha(pc, .3f)); c.drawRoundRect(new RectF(px, 1.1f * R, px + cw, 2.6f * R), .4f * R, .4f * R, p);
        text(c, pos, h.fSemi, .85f, pc, px + cw / 2, 2.15f * R, Paint.Align.CENTER, .04f);
        text(c, abbr + (it.optBoolean("bench") ? " · BENCH" : ""), h.fSemi, .9f, 0x99FFFFFF, px + cw + .7f * R, 2.15f * R, Paint.Align.LEFT, .06f);
        // fantasy points (and the projection) at the top right
        String pts = it.optString("pts"), pj = it.optString("proj");
        if (!pts.isEmpty() || !pj.isEmpty()) {
            float rx = w - px;
            if (!pts.isEmpty()) {
                p.setColor(0xB3000000); float tw = measure(pts, Typeface.create(h.fSemi, Typeface.BOLD), 1.9f, 0) + 1.2f * R;
                c.drawRoundRect(new RectF(rx - tw, .9f * R, rx, 3.4f * R), .6f * R, .6f * R, p);
                text(c, pts, Typeface.create(h.fSemi, Typeface.BOLD), 1.9f, live ? 0xFFFFE066 : Color.WHITE, rx - .6f * R, 2.85f * R, Paint.Align.RIGHT, 0);
                if (!pj.isEmpty()) text(c, "proj " + pj, h.fMed, .85f, 0xCCFFFFFF, rx - .2f * R, 4.5f * R, Paint.Align.RIGHT, 0);
            } else {
                p.setColor(0x99000000); float tw = measure("proj " + pj, h.fSemi, 1f, 0) + 1.2f * R;
                c.drawRoundRect(new RectF(rx - tw, .9f * R, rx, 2.9f * R), .6f * R, .6f * R, p);
                text(c, "proj " + pj, h.fSemi, 1f, 0xE6FFFFFF, rx - .6f * R, 2.3f * R, Paint.Align.RIGHT, 0);
            }
        }
        // the name
        float nameMax = w - 2 * px - (ph != null ? 5 * R : 0) - (!pts.isEmpty() || !pj.isEmpty() ? 2 * R : 0);
        text(c, ellip(it.optString("name"), h.fSemi, 1.55f, nameMax), h.fSemi, 1.55f, it.optBoolean("bench") ? 0xB3FFFFFF : Color.WHITE, px, 4.9f * R, Paint.Align.LEFT, 0);
        // the game
        String game = it.optString("opp") + (it.optString("score").isEmpty() ? "" : "   " + it.optString("score"));
        String when = it.optString("when");
        if (!it.optString("opp").isEmpty()) {
            text(c, ellip(game, h.fMed, 1.05f, nameMax), h.fMed, 1.05f, 0xD9FFFFFF, px, 6.9f * R, Paint.Align.LEFT, 0);
            if (live) { float tw = measure(when.replaceFirst("^LIVE · ", ""), h.fSemi, .95f, 0); p.setColor(0xFFFF453A); c.drawCircle(px + .27f * R, 8.5f * R, .27f * R, p);
                text(c, ellip(when.replaceFirst("^LIVE · ", ""), h.fSemi, .95f, nameMax - R), h.fSemi, .95f, 0xFFFF6B60, px + .8f * R, 8.85f * R, Paint.Align.LEFT, 0); }
            else text(c, ellip(when + (it.optString("tv").isEmpty() || post ? "" : " · " + it.optString("tv")), h.fSemi, .95f, nameMax), h.fSemi, .95f, post ? 0x99FFFFFF : 0xFFA8FF60, px, 8.85f * R, Paint.Align.LEFT, 0);
        } else text(c, "No game this week", h.fMed, 1.05f, 0x99FFFFFF, px, 6.9f * R, Paint.Align.LEFT, 0);
        // the line so far, an injury, or the last alert
        String st = it.optString("status");
        float by = hh - 1.1f * R;
        if (!st.isEmpty() && !(live && !it.optString("line").isEmpty())) {
            boolean bad = st.equals("O") || st.startsWith("IR") || st.equals("D") || st.equals("SUSP");
            String lab = it.optString("statusFull") + (it.optString("note").isEmpty() ? "" : " · " + it.optString("note"));
            float bw = Math.min(w - 2 * px, measure(lab, h.fSemi, .9f, 0) + 1.4f * R);
            p.setColor(bad ? 0x40FF453A : 0x40FFD60A); c.drawRoundRect(new RectF(px, by - 1.35f * R, px + bw, by + .45f * R), .5f * R, .5f * R, p);
            text(c, ellip(lab, h.fSemi, .9f, bw - 1.4f * R), h.fSemi, .9f, bad ? 0xFFFF8A80 : 0xFFFFE066, px + .7f * R, by - .15f * R, Paint.Align.LEFT, 0);
        } else if (!it.optString("line").isEmpty()) {
            text(c, ellip(it.optString("line"), h.fSemi, 1f, w - 2 * px), h.fSemi, 1f, Color.WHITE, px, by, Paint.Align.LEFT, 0);
        } else if (!it.optString("last").isEmpty()) {
            text(c, ellip("Last: " + it.optString("last"), h.fReg, .95f, w - 2 * px), h.fReg, .95f, 0xB3FFFFFF, px, by, Paint.Align.LEFT, 0);
        }
    }

    /** your team: points so far, the projection, who's still to play */
    private void paintTeam(Canvas c, JSONObject it, int w, int hh, Paint p) {
        p.setShader(new LinearGradient(0, 0, w, hh, 0xFF2A1F4D, 0xFF0E0B1E, Shader.TileMode.CLAMP)); c.drawRect(0, 0, w, hh, p); p.setShader(null);
        p.setColor(0x14FFFFFF); for (float x = -hh; x < w; x += .7f * R) c.drawLine(x, hh, x + hh, 0, p);
        boolean live = it.optInt("playing") > 0;
        p.setColor(live ? 0xFFFF453A : 0xFF8E7BFF); c.drawRect(0, 0, w, live ? .22f * R : .14f * R, p);
        float px = 1.3f * R;
        text(c, ellip(it.optString("name").toUpperCase(java.util.Locale.US), h.fSemi, .95f, w - 2 * px - 6 * R), h.fSemi, .95f, 0xB3FFFFFF, px, 2.1f * R, Paint.Align.LEFT, .06f);
        if (it.optInt("week") > 0) text(c, "WEEK " + it.optInt("week"), h.fSemi, .95f, 0x8CFFFFFF, w - px, 2.1f * R, Paint.Align.RIGHT, .06f);
        text(c, it.optString("pts"), Typeface.create(h.fSemi, Typeface.BOLD_ITALIC), 4.2f, Color.WHITE, px, 7.2f * R, Paint.Align.LEFT, 0);
        float pw = measure(it.optString("pts"), Typeface.create(h.fSemi, Typeface.BOLD_ITALIC), 4.2f, 0);
        text(c, "pts", h.fSemi, 1.2f, 0xB3FFFFFF, px + pw + .6f * R, 7.2f * R, Paint.Align.LEFT, 0);
        if (!it.optString("proj").isEmpty()) text(c, "Projected " + it.optString("proj"), h.fSemi, 1.15f, 0xFFB9A8FF, px, 9.4f * R, Paint.Align.LEFT, 0);
        int left = it.optInt("left"), pl = it.optInt("playing");
        String st = (pl > 0 ? pl + " playing now" : "") + (pl > 0 && left > 0 ? " · " : "") + (left > 0 ? left + " yet to play" : "") + (pl == 0 && left == 0 ? "All done this week" : "");
        if (pl > 0) { p.setColor(0xFFFF453A); c.drawCircle(px + .27f * R, hh - 1.45f * R, .27f * R, p); }
        text(c, st + "  ·  " + it.optString("scoring"), h.fMed, .95f, 0xCCFFFFFF, px + (pl > 0 ? .8f * R : 0), hh - 1.1f * R, Paint.Align.LEFT, 0);
    }

    private void text(Canvas c, String s, Typeface tf, float sizeRem, int color, float x, float y, Paint.Align a, float spacing) {
        synchronized (tp) {
            tp.setTypeface(tf); tp.setTextSize(sizeRem * R); tp.setColor(color); tp.setTextAlign(a); tp.setLetterSpacing(spacing);
            tp.setShadowLayer(0, 0, 0, 0);
            c.drawText(s, x, y, tp);
            tp.setLetterSpacing(0);
        }
    }
    private float measure(String s, Typeface tf, float sizeRem, float spacing) { synchronized (tp) { tp.setTypeface(tf); tp.setTextSize(sizeRem * R); tp.setLetterSpacing(spacing); float w = tp.measureText(s); tp.setLetterSpacing(0); return w; } }
    private String ellip(String s, Typeface tf, float sizeRem, float max) { synchronized (tp) { tp.setTypeface(tf); tp.setTextSize(sizeRem * R); return TextUtils.ellipsize(s, tp, Math.max(1, max), TextUtils.TruncateAt.END).toString(); } }

    // ================================================================== the scoreboard

    private String heroKey = "";
    private void showHero(final JSONObject it) {
        Integer mine = it == null ? null : playersIn.get(it.optString("k").split(":")[0]);
        final String key = it == null ? "" : "hero:" + it.optString("k") + ":" + Math.round(heroWidth) + ":" + (mine == null ? 0 : mine);
        if (key.equals(heroKey)) return;
        heroKey = key;
        if (it == null || it.optBoolean("edit") && rows.size() <= 1) { for (ImageView v : hero) v.animate().alpha(0f).setDuration(250).start(); return; }
        Bitmap have = cards.get(key);
        if (have != null) { swapHero(have); return; }
        painter.execute(new Runnable() { @Override public void run() {
            if (!key.equals(heroKey)) return;
            final Bitmap b = paintHero(it);
            main().post(new Runnable() { @Override public void run() {
                if (b == null) return;
                cards.put(key, b);
                if (key.equals(heroKey)) swapHero(b);
            } });
        } });
    }
    private void swapHero(Bitmap b) {
        int next = heroTop == 0 ? 1 : 0;
        final ImageView nv = hero[next];
        nv.animate().cancel();
        nv.setImageBitmap(b);
        nv.bringToFront();
        nv.setAlpha(0f); nv.setTranslationY(.4f * R);
        final int under = heroTop;
        heroTop = next;
        // (Standings covers the scoreboard: a new scoreboard brought forward went over the tables)
        if (standings != null && standings.shownNow()) { standings.bringToFront(); chrome.bringToFront(); }
        nv.animate().alpha(1f).translationY(0).setDuration(300).setInterpolator(TileView.EASE).withEndAction(new Runnable() { @Override public void run() {
            if (heroTop == 1 - under || under < 0) { } if (under >= 0 && heroTop != under) hero[under].setAlpha(0f);
        } }).start();
    }

    /** the big scoreboard for the game you're on (a broadcast-style graphic, painted into one picture) */
    private Bitmap paintHero(JSONObject it) {
        if (!it.optBoolean("fy") && !it.optBoolean("edit") && it.optJSONArray("teams") != null && it.optJSONArray("teams").length() >= 2) return paintGameHero(it);
        if (it.optBoolean("fy") && !it.optString("gameId").isEmpty()) {
            for (int i = off; i < allRows.size(); i++) { JSONArray a = allRows.get(i); for (int k = 0; k < a.length(); k++) { JSONObject g = a.optJSONObject(k); if (g.optString("k").split(":")[0].equals(it.optString("gameId"))) return paintGameHero(g); } }
        }
        int w = Math.round(heroWidth * R), hh = Math.round(HERO_H * R);
        if (w <= 0) w = Math.round((SW - 2 * PAD) * R);
        Bitmap out = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float rad = 2.4f * R;
        Path clip = new Path(); clip.addRoundRect(new RectF(0, 0, w, hh), rad, rad, Path.Direction.CW);
        c.clipPath(clip);
        if (it.optBoolean("fyEdit")) {
            p.setShader(new LinearGradient(0, 0, w, 0, 0xE61A1430, 0xB30E0A1C, Shader.TileMode.CLAMP)); c.drawRect(0, 0, w, hh, p); p.setShader(null);
            text(c, "YOUR FANTASY TEAM", Typeface.create(h.fSemi, Typeface.BOLD_ITALIC), 2.6f, Color.WHITE, 3 * R, 6.5f * R, Paint.Align.LEFT, .03f);
            text(c, "Pick your players on your phone: their games, live stat lines and injury news show here, and alerts pop up during games.", h.fReg, 1.3f, 0xBFFFFFFF, 3 * R, 9.5f * R, Paint.Align.LEFT, 0);
            text(c, "OK · Pick my players", h.fSemi, 1.1f, 0xFFB9A8FF, 3 * R, 12.6f * R, Paint.Align.LEFT, .04f);
            return out;
        }
        if (it.optBoolean("fyTeam")) {
            p.setShader(new LinearGradient(0, 0, w, 0, 0xE62A1F4D, 0xB30E0B1E, Shader.TileMode.CLAMP)); c.drawRect(0, 0, w, hh, p); p.setShader(null);
            text(c, (it.optString("name") + (it.optInt("week") > 0 ? " · WEEK " + it.optInt("week") : "")).toUpperCase(java.util.Locale.US), h.fSemi, 1.1f, 0xB3FFFFFF, 3 * R, 4 * R, Paint.Align.LEFT, .08f);
            text(c, it.optString("pts") + " pts", Typeface.create(h.fSemi, Typeface.BOLD_ITALIC), 4.6f, Color.WHITE, 3 * R, 9.4f * R, Paint.Align.LEFT, 0);
            if (!it.optString("proj").isEmpty()) text(c, "Projected " + it.optString("proj"), h.fSemi, 1.5f, 0xFFB9A8FF, 3 * R, 12.2f * R, Paint.Align.LEFT, 0);
            text(c, "Points worked out with " + it.optString("scoring") + " scoring (Settings › Sports › Fantasy scoring) · projections from ESPN", h.fReg, 1f, 0x99FFFFFF, 3 * R, 14.6f * R, Paint.Align.LEFT, 0);
            return out;
        }
        if (it.optBoolean("fy") && (it.optJSONArray("teams") == null || it.optJSONArray("teams").length() < 2)) {
            p.setShader(new LinearGradient(0, 0, w, 0, 0xE6141C2B, 0xB30B111C, Shader.TileMode.CLAMP)); c.drawRect(0, 0, w, hh, p); p.setShader(null);
            text(c, it.optString("name").toUpperCase(java.util.Locale.US), Typeface.create(h.fSemi, Typeface.BOLD_ITALIC), 2.6f, Color.WHITE, 3 * R, 7 * R, Paint.Align.LEFT, .03f);
            text(c, it.optString("pos") + " · " + it.optString("abbr") + " · no game on this week's schedule", h.fReg, 1.3f, 0xBFFFFFFF, 3 * R, 10 * R, Paint.Align.LEFT, 0);
            return out;
        }
        if (it.optBoolean("edit")) {
            p.setShader(new LinearGradient(0, 0, w, 0, 0xE6102018, 0xB30A1410, Shader.TileMode.CLAMP)); c.drawRect(0, 0, w, hh, p); p.setShader(null);
            text(c, "YOUR TEAMS, EVERYWHERE", Typeface.create(h.fSemi, Typeface.BOLD_ITALIC), 2.6f, Color.WHITE, 3 * R, 6.5f * R, Paint.Align.LEFT, .03f);
            text(c, "Pick the sports and teams you follow: their games, live scores and the channel each one is on.", h.fReg, 1.3f, 0xBFFFFFFF, 3 * R, 9.5f * R, Paint.Align.LEFT, 0);
            text(c, "OK · Edit sports & teams", h.fSemi, 1.1f, 0xFFA8FF60, 3 * R, 12.6f * R, Paint.Align.LEFT, .04f);
            return out;
        }
        JSONArray ts = it.optJSONArray("teams");
        return ts == null || ts.length() < 2 ? null : paintGameHero(it);
    }

    // ================================================================== the backdrop: a stadium at night

    private Bitmap bakeBg() {
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, H, new int[] { 0xFF050A16, 0xFF071426, 0xFF07170F }, new float[] { 0, .55f, 1 }, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        // floodlights
        glowAt(c, W * .12f, -H * .08f, W * .42f, H * .5f, 0x52DCEBFF);
        glowAt(c, W * .88f, -H * .08f, W * .42f, H * .5f, 0x52DCEBFF);
        glowAt(c, W * .12f, -H * .02f, W * .06f, H * .05f, 0x99FFFFFF);
        glowAt(c, W * .88f, -H * .02f, W * .06f, H * .05f, 0x99FFFFFF);
        // the field glowing up from below
        glowAt(c, W * .5f, H * 1.12f, W * .9f, H * .55f, 0x6628A050);
        // a perspective grid running across the turf (the "high tech" part)
        float horizon = H * .5f, vx = W * .5f;
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG); g.setStrokeWidth(Math.max(1, .06f * R));
        for (int i = -14; i <= 14; i++) {
            float bx = vx + i * W * .09f;
            g.setShader(new LinearGradient(vx, horizon, bx, H, 0x0060FF9A, 0x2E60FF9A, Shader.TileMode.CLAMP));
            c.drawLine(vx + (bx - vx) * .02f, horizon + (H - horizon) * .02f, bx, H, g);
        }
        g.setShader(null);
        for (int k = 1; k <= 12; k++) {
            float t = (float) Math.pow(k / 12.0, 1.8);
            float y = horizon + (H - horizon) * t;
            g.setColor(alpha(0xFF60FF9A, .02f + .14f * t));
            c.drawLine(0, y, W, y, g);
        }
        // the horizon line, lit
        Paint hl = new Paint(Paint.ANTI_ALIAS_FLAG);
        hl.setShader(new LinearGradient(0, 0, W, 0, new int[] { 0x0060FF9A, 0x6660FF9A, 0x0060FF9A }, new float[] { 0, .5f, 1 }, Shader.TileMode.CLAMP));
        c.drawRect(0, horizon - 1, W, horizon + Math.max(1, .08f * R), hl);
        // mown stripes fading up from the bottom
        for (float x = 0; x < W; x += 14 * R) {
            Paint st = new Paint();
            st.setShader(new LinearGradient(0, H, 0, H * .45f, 0x0EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(x, H * .45f, x + 7 * R, H, st);
        }
        // shade behind the title and rows, so text reads on top of it
        Paint sh = new Paint();
        sh.setShader(new LinearGradient(0, 0, 0, H, new int[] { 0x66000000, 0x00000000, 0x00000000, 0x8C000000 }, new float[] { 0, .2f, .5f, 1 }, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, sh);
        out.setHasAlpha(false);
        return out;
    }
    private static void glowAt(Canvas c, float cx, float cy, float rx, float ry, int col) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(0, 0, 1, new int[] { col, col & 0xFFFFFF }, new float[] { 0, 1 }, Shader.TileMode.CLAMP));
        c.save(); c.translate(cx, cy); c.scale(rx, ry); c.drawCircle(0, 0, 1, p); c.restore();
    }
}

