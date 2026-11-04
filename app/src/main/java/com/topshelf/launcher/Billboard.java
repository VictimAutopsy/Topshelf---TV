package com.topshelf.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home as a streaming service's billboard (Settings › Home › Look › Home style › Featured): movies, shows and
 * games take turns full screen, each with Watch now and More details. No ads: everything comes from your own
 * world — today's games in your sports (yours first, live ones first, with a real picture of the game from
 * ESPN), what you're part-way through, new episodes of your shows, your list, and what's popular, new,
 * in theaters or trending in your sources.
 *
 * It doesn't repeat itself: what it showed is remembered (ui.featSeen), and each new round picks what you
 * haven't seen lately. A round is made again after it has gone all the way round (or after half an hour),
 * so the games follow the day and the titles change.
 *
 * Keys: ◀ ▶ between the buttons (▶ past More details: the next one; ◀ past Watch now: the side menu);
 * ▼ the row of what's in the rotation (◀ ▶ jump, rotation paused); ▲ the menu / bar; hold OK: more choices.
 */
final class Billboard extends FrameLayout {
    /** how far Home's words move over for the closed icon rail (rem) */
    static final class Home { static final float RAIL_SHIFT = 4.5f; }
    static final long SLIDE_MS = 10000;
    private static final int DECK = 9;

    static final class Slide {
        String kind = "", key = "", tag = "", title = "", meta = "", desc = "", bg = "", logo = "", go = "Watch now", more = "More details", strip = "", score = "", cutA = "", cutB = "", bgAlt = "";
        int tagCol = 0xFFFFFFFF; boolean live;
        JSONObject m; SportsData.Ev ev;
        String fanA, fanB, sig = "";        // a matchup poster's fan art, and the score it shows
    }

    private final NativeHome h;
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private List<Slide> deck = new ArrayList<>();
    private int cur = -1, row = 0, btn = 0, gen = 0;
    private long builtAt, slideStart, pausedAt = -1;
    private boolean building;
    private int lapsDone;

    // ---- views
    private final ImageView[] bg = new ImageView[2];
    private int bgLayer;
    private final FrameLayout teamArt, fightArt, trHost;
    private TrailerPlayer trailer;
    private boolean trOn;
    private int trFor = -1;
    private long trStartAt, trAskAt;
    private boolean trLoading;
    private final ImageView fighterA, fighterB;
    private final TextView vs;
    private final View teamA, teamB;
    private final ImageView teamLogoA, teamLogoB;
    private final LinearLayout info, eyebrow, scoreRow, buttons, strip;
    private final TextView clock, chip, tag, title, meta, desc, scoreText, bGo, bMore, hint;
    private final ImageView logo, sLogoA, sLogoB;
    private final List<Bar> bars = new ArrayList<>();
    private final List<TextView> barLabels = new ArrayList<>();
    private final Typeface heavy;

    Billboard(NativeHome home) {
        super(home.getContext());
        h = home;
        heavy = Build.VERSION.SDK_INT >= 28 ? Typeface.create(Typeface.SANS_SERIF, 800, false) : Typeface.DEFAULT_BOLD;
        setClipChildren(false);
        setBackgroundColor(0xFF0B0C10);      // (its own dark ground: the photo slideshow isn't shown under it)
        for (int i = 0; i < 2; i++) {
            bg[i] = new ImageView(getContext());
            bg[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            bg[i].setAlpha(0f);
            addView(bg[i], new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        // a game with no picture: the two teams' colours, each with its logo
        teamArt = new FrameLayout(getContext());
        teamArt.setAlpha(0f);
        LinearLayout halves = new LinearLayout(getContext());
        teamA = new View(getContext()); teamB = new View(getContext());
        halves.addView(teamA, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
        halves.addView(teamB, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
        teamArt.addView(halves, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        teamLogoA = new ImageView(getContext()); teamLogoB = new ImageView(getContext());
        for (ImageView v : new ImageView[] { teamLogoA, teamLogoB }) { v.setScaleType(ImageView.ScaleType.FIT_CENTER); v.setAlpha(.9f); }
        int ls = h.px(26);
        FrameLayout.LayoutParams la = new FrameLayout.LayoutParams(ls, ls, Gravity.CENTER_VERTICAL); la.leftMargin = h.W / 2 - ls - h.px(6);
        FrameLayout.LayoutParams lb = new FrameLayout.LayoutParams(ls, ls, Gravity.CENTER_VERTICAL); lb.leftMargin = h.W / 2 + h.px(16);
        teamArt.addView(teamLogoA, la); teamArt.addView(teamLogoB, lb);
        addView(teamArt, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        // a fight card with no picture of the night: a poster, the main event's two fighters face to face
        fightArt = new FrameLayout(getContext());
        fightArt.setAlpha(0f);
        fightArt.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { 0xFF0A0204, 0xFF3A0A12, 0xFF120306 }));
        vs = h.text(heavy, 18f, 0x1FFFFFFF);
        vs.setText("VS");
        FrameLayout.LayoutParams vl = new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL);
        vl.leftMargin = Math.round(h.W * .6f);
        fightArt.addView(vs, vl);
        fighterA = new ImageView(getContext()); fighterB = new ImageView(getContext());
        for (ImageView v : new ImageView[] { fighterA, fighterB }) v.setScaleType(ImageView.ScaleType.FIT_END);
        fightArt.addView(fighterA, fighterLp(.36f));
        fightArt.addView(fighterB, fighterLp(.66f));
        addView(fightArt, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        // a title's trailer, playing silently behind the words after a few seconds' rest
        trHost = new FrameLayout(getContext());
        trHost.setAlpha(0f);
        addView(trHost, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        // the shade that keeps the words readable: from the left, and from the bottom
        View sl = new View(getContext());
        sl.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] { 0xF0000000, 0xB3000000, 0x40000000, 0x00000000 }));
        addView(sl, new LayoutParams(Math.round(h.W * .72f), LayoutParams.MATCH_PARENT));
        View sb = new View(getContext());
        sb.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { 0x00000000, 0x99000000, 0xD9000000 }));
        addView(sb, new LayoutParams(LayoutParams.MATCH_PARENT, Math.round(h.H * .4f), Gravity.BOTTOM));
        View st = new View(getContext());
        st.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { 0x80000000, 0x00000000 }));
        addView(st, new LayoutParams(LayoutParams.MATCH_PARENT, Math.round(h.H * .22f)));

        clock = h.text(h.fMed, 2.1f, Color.WHITE);
        clock.setShadowLayer(.8f * h.R, 0, .1f * h.R, 0x80000000);
        addView(clock, h.at(6, 4f, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        // ---- the story: why it's here, what it is, two buttons
        info = new LinearLayout(getContext());
        info.setOrientation(LinearLayout.VERTICAL);
        eyebrow = new LinearLayout(getContext());
        eyebrow.setGravity(Gravity.CENTER_VERTICAL);
        chip = h.text(h.fSemi, 1.05f, Color.WHITE);
        chip.setText("LIVE"); chip.setLetterSpacing(.08f);
        GradientDrawable cb = new GradientDrawable(); cb.setColor(0xFFE5484D); cb.setCornerRadius(GlassPanel.look.equals("air") ? 0 : .5f * h.R);
        chip.setBackground(cb); chip.setPadding(h.px(.75f), h.px(.25f), h.px(.75f), h.px(.25f));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); cl.rightMargin = h.px(1f);
        eyebrow.addView(chip, cl);
        tag = h.text(h.fSemi, 1.25f, Color.WHITE);
        tag.setLetterSpacing(.1f); tag.setSingleLine(true); tag.setEllipsize(android.text.TextUtils.TruncateAt.END);
        eyebrow.addView(tag);
        info.addView(eyebrow);
        logo = new ImageView(getContext());
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        logo.setAdjustViewBounds(true);
        logo.setMaxWidth(h.px(44)); logo.setMaxHeight(h.px(13));
        LinearLayout.LayoutParams lol = new LinearLayout.LayoutParams(h.px(44), h.px(13)); lol.topMargin = h.px(1.6f);
        info.addView(logo, lol);
        title = h.text(heavy, 6f, Color.WHITE);
        title.setLetterSpacing(-.02f); title.setMaxLines(2); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setLineSpacing(0, .92f);
        title.setShadowLayer(1.2f * h.R, 0, .15f * h.R, 0x66000000);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(h.px(58), LayoutParams.WRAP_CONTENT); tl.topMargin = h.px(1.2f);
        info.addView(title, tl);
        scoreRow = new LinearLayout(getContext());
        scoreRow.setGravity(Gravity.CENTER_VERTICAL);
        sLogoA = new ImageView(getContext()); sLogoB = new ImageView(getContext());
        for (ImageView v : new ImageView[] { sLogoA, sLogoB }) v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        scoreText = h.text(heavy, 4.4f, Color.WHITE);
        scoreText.setFontFeatureSettings("tnum");
        scoreRow.addView(sLogoA, new LinearLayout.LayoutParams(h.px(6), h.px(6)));
        LinearLayout.LayoutParams sl2 = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); sl2.leftMargin = h.px(1.6f); sl2.rightMargin = h.px(1.6f);
        scoreRow.addView(scoreText, sl2);
        scoreRow.addView(sLogoB, new LinearLayout.LayoutParams(h.px(6), h.px(6)));
        LinearLayout.LayoutParams srl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); srl.topMargin = h.px(1.4f);
        info.addView(scoreRow, srl);
        meta = h.text(h.fMed, 1.55f, 0xF0FFFFFF);
        meta.setSingleLine(true); meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(h.px(58), LayoutParams.WRAP_CONTENT); ml.topMargin = h.px(1.4f);
        info.addView(meta, ml);
        desc = h.text(h.fReg, 1.55f, 0xE6FFFFFF);
        desc.setMaxLines(2); desc.setEllipsize(android.text.TextUtils.TruncateAt.END); desc.setLineSpacing(0, 1.18f);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(h.px(46), LayoutParams.WRAP_CONTENT); dl.topMargin = h.px(.9f);
        info.addView(desc, dl);
        for (TextView t : new TextView[] { tag, meta, desc, scoreText }) t.setShadowLayer(.8f * h.R, 0, .1f * h.R, 0x80000000);
        buttons = new LinearLayout(getContext());
        bGo = button(); bMore = button();
        buttons.addView(bGo);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); bl.leftMargin = h.px(1.2f);
        buttons.addView(bMore, bl);
        LinearLayout.LayoutParams btl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); btl.topMargin = h.px(2.6f);
        info.addView(buttons, btl);
        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(h.px(60), LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT);
        il.leftMargin = h.px(6); il.bottomMargin = h.px(10.5f);
        addView(info, il);

        // ---- what's in the rotation, the current one filling up
        strip = new LinearLayout(getContext());
        FrameLayout.LayoutParams stl = new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT);
        stl.leftMargin = h.px(6); stl.bottomMargin = h.px(4.6f);
        addView(strip, stl);
        hint = h.text(h.fReg, 1.15f, 0xCCFFFFFF);
        hint.setShadowLayer(.6f * h.R, 0, .1f * h.R, 0x99000000);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT);
        hl.rightMargin = h.px(6); hl.bottomMargin = h.px(8.6f);
        addView(hint, hl);

        info.setAlpha(0f);
        drawFocus();
    }

    private FrameLayout.LayoutParams fighterLp(float x) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(h.W * .32f), Math.round(h.H * .9f), Gravity.BOTTOM | Gravity.LEFT);
        lp.leftMargin = Math.round(h.W * x);
        return lp;
    }
    private TextView button() {
        TextView b = h.text(h.fSemi, 1.5f, Color.WHITE);
        b.setPadding(h.px(2.2f), h.px(1f), h.px(2.2f), h.px(1f));
        b.setBackground(GlassPanel.buttonBg(false, 1.3f * h.R, 0x38FFFFFF));
        if (GlassPanel.look.equals("air")) { b.setAllCaps(true); b.setLetterSpacing(.06f); b.setPadding(h.px(2.2f), h.px(1f), h.px(3.2f), h.px(1f)); }
        return b;
    }

    /** a slide's place in the rotation: a short line that fills up as its time runs */
    private final class Bar extends View {
        float fill; boolean on;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        Bar() { super(Billboard.this.getContext()); }
        /* in each look: glass and Spotlight thin white lines; On Air square segments, the one on now filling red with
           a slanted tip; Backlit Shelf lines lit in the shelf's light; Photo Ink flat
           paper-coloured blocks with the hard ink shadow */
        @Override protected void onDraw(Canvas c) {
            float hgt = getHeight(), w = getWidth(), rr = hgt / 2f, f = Math.min(1f, fill);
            String lk = GlassPanel.look;
            if (lk.equals("air")) {
                p.setColor(on ? 0x66FFFFFF : 0x40FFFFFF); c.drawRect(0, 0, w, hgt, p);
                if (f > 0) {
                    p.setColor(on || f < 1 ? GlassPanel.airColor : Color.WHITE);
                    float x = w * f, k = Math.min(hgt * 1.2f, x);
                    android.graphics.Path t = new android.graphics.Path();
                    t.moveTo(0, 0); t.lineTo(x, 0); t.lineTo(Math.max(0, x - k), hgt); t.lineTo(0, hgt); t.close();
                    c.drawPath(t, p);
                }
            } else if (lk.equals("shelf")) {
                int light = GlassPanel.shelfLight();
                p.setColor(0x33FFFFFF); r.set(0, 0, w, hgt); c.drawRoundRect(r, rr, rr, p);
                if (f > 0) {
                    p.setColor(light); r.set(0, 0, w * f, hgt); c.drawRoundRect(r, rr, rr, p);
                }
            } else if (lk.equals("ink")) {
                float o = Math.max(1f, hgt * .45f);
                p.setColor(GlassPanel.inkShadow()); c.drawRect(o, o, w, hgt, p);
                p.setColor(GlassPanel.inkColor | 0xFF000000); c.drawRect(0, 0, w - o, hgt - o, p);
                if (f > 0) { p.setColor(GlassPanel.inkPaper()); c.drawRect(0, 0, (w - o) * f, hgt - o, p); }
            } else {
                p.setColor(on ? 0x59FFFFFF : 0x40FFFFFF); r.set(0, 0, w, hgt); c.drawRoundRect(r, rr, rr, p);
                if (f > 0) { p.setColor(Color.WHITE); r.set(0, 0, w * f, hgt); c.drawRoundRect(r, rr, rr, p); }
            }
        }
    }

    // ================================================================== on screen

    private boolean railRoomOn;
    /** the side menu's icon rail sits at the left edge: the words and the row move over to clear it */
    void railRoom(boolean on) {
        if (on == railRoomOn) return;
        railRoomOn = on;
        float dx = on ? h.px(Home.RAIL_SHIFT) : 0;
        info.animate().translationX(dx).setDuration(220).start();
        strip.animate().translationX(dx).setDuration(220).start();
        clock.setVisibility(h.side && !on ? VISIBLE : GONE);
    }
    private boolean shown;
    /** Home is showing it (or not) */
    void setShown(boolean on) {
        if (on == shown) return;
        shown = on;
        removeCallbacks(ticker);
        if (!on && push != null) push.cancel();          // (the slow push-in doesn't run behind other screens)
        if (!on) { stopTrailer(); if (trailer != null) { trailer.release(); trailer = null; } }     // (frees its video decoder for the screen you went to)
        if (on) {
            if (deck.isEmpty() || System.currentTimeMillis() - builtAt > 30 * 60000L) rebuild();
            else { slideStart = System.currentTimeMillis(); pausedAt = -1; refreshGame(); }
            post(ticker);
        }
    }
    void tick(String time, String wx) {
        clock.setText(wx == null || wx.isEmpty() ? time : time + "   " + wx);
        clock.setVisibility(h.side && !railRoomOn ? VISIBLE : GONE);      // (with the bar across the top, or the icon rail, the clock is up there)
    }

    /* Ten times a second while it shows: the current line fills; at the end the next one. Held still while
       you're in the row below, while something covers Home, and for a moment after each key. */
    private final Runnable ticker = new Runnable() { @Override public void run() {
        if (!shown) return;
        postDelayed(this, 100);
        if (deck.isEmpty() || cur < 0) return;
        long now = System.currentTimeMillis();
        boolean hold = row == 1 || !h.billboardFree();
        if (hold) {
            if (pausedAt < 0) pausedAt = now;
            if (trOn || trFor == gen) { stopTrailer(); trFor = -1; }      // (it starts again once you're back)
            return;
        }
        if (pausedAt >= 0) { slideStart += now - pausedAt; pausedAt = -1; }
        /* resting on a title with a trailer: after a few seconds it plays, and the slide stays until the trailer
           has finished (it used to move on after a minute, mid-trailer). While the trailer is still loading the
           slide waits too (up to 12 s; one that never starts lets the slide carry on). */
        Slide cs = deck.get(cur);
        if (trFor != gen && now - slideStart > 3500 && trWanted(cs)) startTrailer(cs);
        if (trOn) { if (now - trStartAt > 6 * 60000L) { stopTrailer(); next(false); } else slideStart += 100; return; }
        if (trFor == gen && trLoading && now - trAskAt < 12000) { slideStart += 100; return; }
        float f = (now - slideStart) / (float) SLIDE_MS;
        if (cur < bars.size()) { Bar b = bars.get(cur); b.fill = f; b.invalidate(); }
        if (f >= 1f) next(false);
    } };

    private void next(boolean byKey) {
        if (deck.isEmpty()) return;
        int n = cur + 1;
        if (n >= deck.size()) {
            n = 0; lapsDone++;
            // all the way round: a new round (new picks) if this one's been up a while
            // (the old round keeps turning while the new one is made: it takes a few seconds over the network)
            if (!byKey && System.currentTimeMillis() - builtAt > 15 * 60000L) rebuild();
        }
        show(n, true);
    }
    private void prev() { if (!deck.isEmpty()) show((cur - 1 + deck.size()) % deck.size(), true); }

    private void show(int i, boolean animate) {
        if (i < 0 || i >= deck.size()) return;
        cur = i;
        final Slide s = deck.get(i);
        if (s.kind.equals("game")) refreshGame();
        slideStart = System.currentTimeMillis(); pausedAt = -1;
        for (int k = 0; k < bars.size(); k++) { Bar b = bars.get(k); b.fill = k < i ? 1f : 0f; b.on = k == i; b.invalidate(); }
        drawStrip();
        final int g = ++gen;
        if (animate && info.getAlpha() > 0) {
            info.animate().cancel();
            info.animate().alpha(0f).translationX((railRoomOn ? h.px(Home.RAIL_SHIFT) : 0) - h.px(1.2f)).setDuration(200).withEndAction(new Runnable() { @Override public void run() {
                if (g != gen) return;
                fill(s);
                float base = railRoomOn ? h.px(Home.RAIL_SHIFT) : 0;
                info.setTranslationX(base + h.px(1.2f));
                info.animate().alpha(1f).translationX(base).setDuration(420).setInterpolator(TileView.EASE).start();
            } }).start();
        } else { fill(s); info.animate().cancel(); info.setTranslationX(railRoomOn ? h.px(Home.RAIL_SHIFT) : 0); info.animate().alpha(1f).setDuration(animate ? 420 : 0).start(); }
        stopTrailer();
        backdrop(s, g);
        markSeen(s);
        prefetch((i + 1) % deck.size());
        if (trWanted(s)) { if (trailer == null) trailer = new TrailerPlayer(h.act); trailer.prefetch(s.m.optString("yt"), 1080, trMuted()); }
    }
    // ---- trailers
    private boolean trMuted() { return !h.S.optBoolean("trailerSound", false); }
    private boolean trWanted(Slide s) {
        return s != null && s.m != null && !s.kind.equals("game") && !s.m.optString("yt").isEmpty() && !"off".equals(h.S.optString("trailers", "card"));
    }
    private void startTrailer(Slide s) {
        trFor = gen; trAskAt = System.currentTimeMillis(); trLoading = true;
        final int g = gen;
        if (trailer == null) trailer = new TrailerPlayer(h.act);
        trailer.play(trHost, s.m.optString("yt"), trMuted(), 1080, new TrailerPlayer.Listener() {
            @Override public void playing() {
                if (g != gen || !shown) return;
                trLoading = false;
                if (!trOn) trStartAt = System.currentTimeMillis();
                trOn = true;
                trHost.animate().alpha(1f).setDuration(700).start();
            }
            @Override public void ended() {
                if (g != gen) return;
                trOn = false; trLoading = false;
                trHost.animate().alpha(0f).setDuration(500).start();
                slideStart = System.currentTimeMillis() - (SLIDE_MS - 2500);     // (the next one, a moment after)
            }
        });
    }
    private void stopTrailer() {
        if (trailer != null && trailer.playing()) trailer.stop();
        trOn = false; trLoading = false;
        trHost.animate().cancel(); trHost.setAlpha(0f);
    }

    /* the next one's picture and title art fetched while this one shows, so it changes the moment its time is up
       (no blank pause, no title text swapping to the title art) */
    private void prefetch(int i) {
        if (i < 0 || i >= deck.size()) return;
        Slide n = deck.get(i);
        HomeImages.Done none = new HomeImages.Done() { @Override public void done(Bitmap b) { } };
        if (!n.bg.isEmpty()) h.imgs.load("bb:" + n.bg, src(n.bg), h.W, h.H, none);
        if (!n.kind.equals("game") && !n.logo.isEmpty()) h.imgs.load("bbl:" + n.logo, src(n.logo), h.px(44), h.px(13), none);
    }

    private void fill(Slide s) {
        chip.setVisibility(s.live ? VISIBLE : GONE);
        tag.setText(s.tag); tag.setTextColor(s.tagCol);
        title.setText(s.title);
        title.setVisibility(VISIBLE);
        logo.setImageDrawable(null); logo.setVisibility(GONE);
        boolean game = s.kind.equals("game");
        if (!game && !s.logo.isEmpty()) {
            final String u = s.logo; final int g = gen;
            h.imgs.load("bbl:" + u, src(u), h.px(44), h.px(13), new HomeImages.Done() { @Override public void done(Bitmap b) {
                if (g != gen || b == null || b.getWidth() < 40) return;
                logo.setImageBitmap(b); logo.setVisibility(VISIBLE); title.setVisibility(GONE);
            } });
        }
        boolean sc = game && s.live && s.ev != null && s.ev.teams.size() == 2;
        scoreRow.setVisibility(sc ? VISIBLE : GONE);
        if (sc) {
            scoreText.setText(s.score);
            teamLogo(sLogoA, s.ev.teams.get(0).logo, h.px(6));
            teamLogo(sLogoB, s.ev.teams.get(1).logo, h.px(6));
        }
        meta.setText(s.meta); meta.setVisibility(s.meta.isEmpty() ? GONE : VISIBLE);
        desc.setText(s.desc); desc.setVisibility(s.desc.isEmpty() ? GONE : VISIBLE);
        bGo.setText("▶  " + s.go); bMore.setText(s.more);
        drawFocus();
    }

    private void teamLogo(final ImageView v, String u, int size) {
        v.setImageDrawable(null);
        if (u == null || u.isEmpty()) return;
        final int g = gen;
        h.imgs.load("bbt:" + u, src(u), size, size, new HomeImages.Done() { @Override public void done(Bitmap b) { if (g == gen && b != null) v.setImageBitmap(b); } });
    }
    private static HomeImages.Source src(final String u) {
        return new HomeImages.Source() { @Override public byte[] bytes() throws Exception {
            if (u.startsWith("file://")) {          // (a game's matchup art, drawn by TopShelf: see Matchup)
                java.io.File f = new java.io.File(u.substring(7));
                byte[] d = new byte[(int) f.length()];
                java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(f));
                try { in.readFully(d); } finally { in.close(); }
                return d;
            }
            return HomeImages.http(u, null);
        } };
    }

    /** the picture behind: the title's backdrop, the game's photo, or (no photo) the teams' colours */
    private void backdrop(final Slide s, final int g) {
        final ImageView next = bg[1 - bgLayer], was = bg[bgLayer];
        if (s.bg.isEmpty()) { noPicture(s); was.animate().alpha(0f).setDuration(600).start(); return; }
        h.imgs.load("bb:" + s.bg, src(s.bg), h.W, h.H, new HomeImages.Done() { @Override public void done(Bitmap b) {
            if (g != gen) return;
            if (b == null) { noPicture(s); was.animate().alpha(0f).setDuration(600).start(); return; }
            teamArt.animate().alpha(0f).setDuration(600).start();
            fightArt.animate().alpha(0f).setDuration(600).start();
            next.setImageBitmap(b);
            next.animate().cancel(); next.setScaleX(1f); next.setScaleY(1f);
            next.setPivotX(h.W * .7f); next.setPivotY(h.H * .4f);
            next.setAlpha(0f);
            next.animate().alpha(1f).setDuration(700).withEndAction(new Runnable() { @Override public void run() { if (g == gen) h.billboardCover(true); } }).start();
            // a slow push in, as on a big screen in a cinema lobby
            if (push != null) push.cancel();
            push = android.animation.ObjectAnimator.ofPropertyValuesHolder(next,
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f), android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f));
            push.setDuration(SLIDE_MS + 2500); push.setInterpolator(new android.view.animation.LinearInterpolator());
            push.start();
            was.animate().cancel(); was.animate().alpha(0f).setDuration(700).start();
            bgLayer = 1 - bgLayer;
        } });
    }
    private android.animation.ObjectAnimator push;
    /** no picture: a fight card's poster, a game's team colours, or (a title) your photo */
    private void noPicture(Slide s) {
        if (!s.cutA.isEmpty() || !s.cutB.isEmpty()) { teamArt.animate().alpha(0f).setDuration(400).start(); showFight(s); return; }
        fightArt.animate().alpha(0f).setDuration(400).start();
        boolean teams = s.ev != null && s.ev.teams.size() == 2;
        h.billboardCover(teams);
        showTeams(s);
    }
    private void showFight(Slide s) {
        fighter(fighterA, s.cutA);
        fighter(fighterB, s.cutB);
        fightArt.animate().alpha(1f).setDuration(600).withEndAction(new Runnable() { @Override public void run() { h.billboardCover(true); } }).start();
    }
    /** a fighter's picture, no bigger than about one and a half times its own size (it looked soft blown up) */
    private void fighter(final ImageView v, String u) {
        v.setImageDrawable(null);
        if (u == null || u.isEmpty()) return;
        final int g = gen;
        final int boxW = Math.round(h.W * .32f), boxH = Math.round(h.H * .9f);
        h.imgs.load("bbf:" + u, src(u), boxW, boxH, new HomeImages.Done() { @Override public void done(Bitmap b) {
            if (g != gen || b == null) return;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            float k = Math.min(1.5f, Math.min(boxW / (float) b.getWidth(), boxH / (float) b.getHeight()));
            lp.width = Math.round(b.getWidth() * k); lp.height = Math.round(b.getHeight() * k);
            v.setLayoutParams(lp);
            v.setImageBitmap(b);
        } });
    }
    private void showTeams(Slide s) {
        fightArt.animate().alpha(0f).setDuration(400).start();
        if (s.ev == null || s.ev.teams.size() != 2) { teamArt.animate().alpha(0f).setDuration(400).start(); return; }
        teamA.setBackgroundColor(col(s.ev.teams.get(0).color, 0xFF2A3340));
        teamB.setBackgroundColor(col(s.ev.teams.get(1).color, 0xFF3A2A30));
        teamLogo(teamLogoA, s.ev.teams.get(0).logo, h.px(26));
        teamLogo(teamLogoB, s.ev.teams.get(1).logo, h.px(26));
        teamArt.animate().alpha(1f).setDuration(600).start();
    }
    private static int col(String hex, int dflt) {
        try { return hex == null || hex.isEmpty() ? dflt : (0xFF000000 | Integer.parseInt(hex.replace("#", ""), 16)); } catch (Exception e) { return dflt; }
    }

    private void buildStrip() {
        strip.removeAllViews(); bars.clear(); barLabels.clear(); stripRow = -1;
        for (int i = 0; i < deck.size(); i++) {
            LinearLayout cell = new LinearLayout(getContext());
            cell.setOrientation(LinearLayout.VERTICAL);
            TextView l = h.text(h.fSemi, 1.1f, 0xB3FFFFFF);
            l.setText(deck.get(i).strip); l.setSingleLine(true); l.setEllipsize(android.text.TextUtils.TruncateAt.END);
            l.setShadowLayer(.6f * h.R, 0, .1f * h.R, 0x99000000);
            cell.addView(l, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            Bar b = new Bar();
            float bh = GlassPanel.look.equals("air") ? .5f : GlassPanel.look.equals("ink") ? .6f : .3f;     // (On Air's and Photo Ink's are bolder)
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Math.max(3, h.px(bh))); bl.topMargin = h.px(.7f);
            cell.addView(b, bl);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(h.px(3.2f), LayoutParams.WRAP_CONTENT);
            if (i > 0) cl.leftMargin = h.px(.8f);
            strip.addView(cell, cl);
            bars.add(b); barLabels.add(l);
        }
    }
    /* Resting: a row of short lines, the current one filling (no words). Gone down to it: each one named,
       so you can pick. */
    private int stripRow = -1;
    private void drawStrip() {
        boolean open = row == 1;
        for (int i = 0; i < barLabels.size(); i++) {
            TextView l = barLabels.get(i);
            boolean c = i == cur;
            l.setTextColor(c ? Color.WHITE : 0xB3FFFFFF);
            l.setTypeface(c && open ? heavy : h.fSemi);
            l.setVisibility(open ? VISIBLE : INVISIBLE);
        }
        if (stripRow != row) {
            stripRow = row;
            for (int i = 0; i < strip.getChildCount(); i++) {
                View cell = strip.getChildAt(i);
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) cell.getLayoutParams();
                lp.width = h.px(open ? 9.6f : 3.2f); lp.leftMargin = i == 0 ? 0 : h.px(open ? 1.4f : .8f);
                cell.setLayoutParams(lp);
            }
        }
    }
    private void drawFocus() {
        boolean on = row == 0;
        style(bGo, on && btn == 0); style(bMore, on && btn == 1);
        strip.setAlpha(row == 1 ? 1f : .8f);
        hint.setText(deck.isEmpty() || row == 0 ? "" : "◀ ▶ jump to one   ·   ▲ back");
        drawStrip();
    }
    private void style(TextView b, boolean f) {
        b.setBackground(GlassPanel.buttonBg(f, 1.3f * h.R, 0x38FFFFFF));
        b.setTextColor(GlassPanel.buttonText(f));
        float sc = GlassPanel.look.equals("air") ? 1f : f ? 1.06f : 1f;      // (On Air's bars don't grow: like a broadcast's graphics)
        b.animate().scaleX(sc).scaleY(sc).setDuration(160).start();
    }

    // ================================================================== keys

    private void touched() { slideStart = System.currentTimeMillis(); pausedAt = row == 1 ? slideStart : -1; }
    /** false: not used here (the caller takes it: the menu, the bar, All apps) */
    boolean move(char d) {
        if (deck.isEmpty()) return false;
        if (row == 0) {
            if (d == 'l') { if (btn == 1) { btn = 0; drawFocus(); touched(); return true; } if (h.side) return false; prev(); return true; }
            if (d == 'r') { if (btn == 0) { btn = 1; drawFocus(); touched(); return true; } btn = 0; next(true); return true; }
            if (d == 'd') { row = 1; drawFocus(); touched(); return true; }
            return false;
        }
        if (d == 'l') { if (cur == 0 && h.side) return false; prev(); drawFocus(); return true; }
        if (d == 'r') { next(true); drawFocus(); return true; }
        if (d == 'u') { row = 0; btn = 0; drawFocus(); touched(); return true; }
        return false;
    }
    void ok() {
        if (deck.isEmpty() || cur < 0) return;
        if (row == 1) { row = 0; btn = 0; drawFocus(); touched(); return; }
        Slide s = deck.get(cur);
        if (btn == 0) go(s); else more(s);
    }
    boolean back() { if (row == 1) { row = 0; drawFocus(); touched(); return true; } if (btn == 1) { btn = 0; drawFocus(); return true; } return false; }
    void hold() {
        if (deck.isEmpty() || cur < 0) return;
        final Slide s = deck.get(cur);
        List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
        l.add(s.go); a.add(new Runnable() { @Override public void run() { go(s); } });
        l.add(s.more); a.add(new Runnable() { @Override public void run() { more(s); } });
        if (s.m != null && !s.kind.equals("cw")) { l.add("Add to / remove from My List"); a.add(new Runnable() { @Override public void run() { h.toggleMyList(s.m); } }); }
        if (s.ev != null) { l.add("Watch on…, remind me"); a.add(new Runnable() { @Override public void run() { h.sportsData().menuFor(s.ev); } }); }
        l.add("Show something else"); a.add(new Runnable() { @Override public void run() { btn = 0; next(true); } });
        if (!s.kind.equals("game")) { l.add("Don't feature this again"); a.add(new Runnable() { @Override public void run() { block(s.key); h.toast("It won't be featured again"); remove(s); } }); }
        l.add("Pick new ones now"); a.add(new Runnable() { @Override public void run() { rebuild(); } });
        h.showMenu(l, a, null, 1f, s.title);
    }
    /** a tap (phone): a button, or a place in the rotation */
    boolean tap(float x, float y) {
        if (deck.isEmpty()) return false;
        if (h.hit(bGo, x, y)) { row = 0; btn = 0; drawFocus(); ok(); return true; }
        if (h.hit(bMore, x, y)) { row = 0; btn = 1; drawFocus(); ok(); return true; }
        for (int i = 0; i < bars.size(); i++) if (h.hit((View) bars.get(i).getParent(), x, y)) { show(i, true); return true; }
        return false;
    }

    private void go(Slide s) {
        if (s.ev != null) { SportsData.Ev e = h.sportsData().find(s.ev.id); h.sportsData().watch(e != null ? e : s.ev); return; }
        if (s.m != null) h.openTitlePlay(s.m);
    }
    private void more(Slide s) {
        if (s.ev != null) { SportsData.Ev e = h.sportsData().find(s.ev.id); h.sportsData().details(e != null ? e : s.ev); return; }
        if (s.m != null) h.openTitle(s.m);
    }
    private void remove(Slide s) {
        int i = deck.indexOf(s);
        if (i < 0) return;
        List<Slide> d = new ArrayList<>(deck); d.remove(i);
        deck = d;
        buildStrip();
        if (deck.isEmpty()) { rebuild(); return; }
        show(Math.min(i, deck.size() - 1), true);
    }

    // ================================================================== a game's latest

    private void refreshGame() {
        if (cur < 0 || cur >= deck.size()) return;
        Slide s = deck.get(cur);
        if (s.ev == null) return;
        SportsData sd = h.sportsData();
        SportsData.Ev e = sd.find(s.ev.id);
        if (e != null && e != s.ev) { s.ev = e; gameText(sd, s, e); }
        rescore(s);
        if (e != null && e.state.equals("in")) sd.quick(new Runnable() { @Override public void run() {
            if (cur < 0 || cur >= deck.size()) return;
            Slide x = deck.get(cur);
            SportsData.Ev ne = x.ev == null ? null : h.sportsData().find(x.ev.id);
            if (ne != null) { x.ev = ne; gameText(h.sportsData(), x, ne); fill(x); rescore(x); }
        } });
    }
    /** a matchup poster whose score (or period) has moved on: drawn again with the new one, and swapped in */
    private void rescore(final Slide s) {
        if (s.fanA == null || s.ev == null) return;
        final String sig = Matchup.scoreSig(s.ev);
        if (sig.equals(s.sig)) return;
        s.sig = sig;
        final SportsData.Ev ev = s.ev;
        work.execute(new Runnable() { @Override public void run() {
            final String m = Matchup.make(ev, s.fanA, s.fanB, h.act.getCacheDir());
            if (m.isEmpty()) return;
            SIZE.put(m, Matchup.W + "x" + Matchup.H); SportsData.ART_SRC.put(m, "TopShelf · matchup art");
            post(new Runnable() { @Override public void run() {
                s.bg = m;
                if (cur >= 0 && cur < deck.size() && deck.get(cur) == s && shown) backdrop(s, gen);
            } });
        } });
    }

    private void gameText(SportsData sd, Slide s, SportsData.Ev e) {
        s.live = e.state.equals("in");
        String lg = e.lgName.toUpperCase(Locale.US);
        boolean two = e.teams.size() == 2;
        SportsData.Team a = two ? e.teams.get(0) : null, b = two ? e.teams.get(1) : null;
        s.title = e.card || !two ? (e.name.isEmpty() ? e.shortName : e.name) : a.name + " at " + b.name;
        boolean mine = sd.isMine(e);
        if (s.live) s.tag = lg + (mine ? "  ·  YOUR TEAM" : "");
        else {
            Calendar d = Calendar.getInstance(); d.setTimeInMillis(e.at);
            Calendar t0 = Calendar.getInstance();
            boolean today = d.get(Calendar.YEAR) == t0.get(Calendar.YEAR) && d.get(Calendar.DAY_OF_YEAR) == t0.get(Calendar.DAY_OF_YEAR);
            String when = today ? (d.get(Calendar.HOUR_OF_DAY) >= 17 ? "TONIGHT " : "TODAY ") + Reminders.time(h, e.at) : sd.remWhen(e.at).toUpperCase(Locale.US);
            s.tag = when.trim() + "  ·  " + lg + (mine ? "  ·  YOUR TEAM" : "");
        }
        s.tagCol = s.live ? Color.WHITE : mine ? 0xFFFFC23D : 0xFFB9E3FF;
        List<String> m = new ArrayList<>();
        if (s.live) { String pc = (e.per + " " + e.clock).trim(); if (!pc.isEmpty()) m.add(pc); else if (!e.detail.isEmpty()) m.add(e.detail); }
        if (!e.nets.isEmpty()) m.add(e.nets.get(0));
        if (two && !s.live && !a.rec.isEmpty() && !b.rec.isEmpty()) m.add(a.abbr + " " + a.rec + "  ·  " + b.abbr + " " + b.rec);
        s.meta = join(m, "   ·   ");
        List<String> dd = new ArrayList<>();
        if (s.live && !e.down.isEmpty()) dd.add(e.down + (e.spot.isEmpty() ? "" : " at " + e.spot));
        if (s.live && !e.lastPlay.isEmpty()) dd.add(e.lastPlay);
        if (!s.live && !e.note.isEmpty()) dd.add(e.note);
        if (!s.live && !e.headline.isEmpty()) dd.add(e.headline);
        if (!s.live && !e.line.isEmpty()) dd.add(e.line);
        if (dd.isEmpty() && !e.venue.isEmpty()) dd.add(e.venue);
        s.desc = dd.isEmpty() ? "" : dd.get(0);          // (one line of story: more made the screen busy)
        s.score = two ? (s.live ? a.score + "  –  " + b.score : "vs") : "";
        s.go = s.live ? "Watch live" : "Watch";
        s.more = e.card || !two ? "Fight card" : "Game page";
        s.strip = two ? (a.abbr.isEmpty() ? a.name : a.abbr) + " · " + (b.abbr.isEmpty() ? b.name : b.abbr) : e.lgName;
    }
    private static String join(List<String> l, String sep) { StringBuilder b = new StringBuilder(); for (String x : l) { if (x == null || x.isEmpty()) continue; if (b.length() > 0) b.append(sep); b.append(x); } return b.toString(); }

    // ================================================================== making a round

    private int laterTries;
    private final Runnable gamesLater = new Runnable() { @Override public void run() {
        if (!shown) return;
        try { if (!h.sportsData().featuredGames(1).isEmpty()) rebuild(); } catch (Throwable ignored) { }
    } };
    void rebuild() {
        if (building) return;
        building = true;
        final int g0 = gen;
        // what lives on the main thread, taken now
        final SportsData sd = h.sportsData();
        final List<Slide> games = new ArrayList<>();
        try { for (SportsData.Ev e : sd.featuredGames(6)) { Slide s = new Slide(); s.kind = "game"; s.key = "game:" + e.id; s.ev = e; gameText(sd, s, e); games.add(s); } } catch (Throwable ignored) { }
        final List<JSONObject> cw = Progress.continueList(getContext(), null, 8);
        final List<JSONObject> ups = new ArrayList<>(Upcoming.cached());
        final JSONArray mine = arr(h.prefs.getString("ui.mylist", null));
        final boolean tm = Feeds.tmdbOn(h), tk = Feeds.traktOn(h);
        work.execute(new Runnable() { @Override public void run() {
            List<Slide> out = new ArrayList<>();
            try { out = make(games, cw, ups, mine, tm, tk); } catch (Throwable t) { Diag.ev("billboard: " + t); }
            final List<Slide> res = out;
            post(new Runnable() { @Override public void run() {
                building = false;
                builtAt = System.currentTimeMillis();
                if (res.isEmpty() && !deck.isEmpty()) return;          // (offline: keep what's there)
                deck = res; row = 0; btn = 0;
                buildStrip();
                if (!deck.isEmpty()) show(0, info.getAlpha() > 0);
                // just after TopShelf starts the games may not be in yet: once they are, a round with them
                boolean games = false; for (Slide x : deck) if (x.kind.equals("game")) games = true;
                removeCallbacks(gamesLater);
                if (!games && laterTries < 4) { laterTries++; postDelayed(gamesLater, 45000); }
                else { tag.setText(""); title.setText("Getting things ready…"); meta.setText(""); desc.setText(""); info.setAlpha(1f); }
                drawFocus();
            } });
        } });
    }

    /* (worker) the round: the pools, each picked from what you haven't seen lately, then woven together so the
       kinds take turns: a game, what you're watching, a movie, a show, a new episode… */
    private List<Slide> make(List<Slide> games, List<JSONObject> cw, List<JSONObject> ups, JSONArray mine, boolean tm, boolean tk) {
        JSONObject seen = seen();
        Set<String> blocked = blocked();
        Set<String> used = new HashSet<>();
        long now = System.currentTimeMillis();

        // games: art for each, a live one of yours always first
        // a picture of each game, never the same one twice in a round (yours and the live ones choose first)
        Set<String> usedArt = new HashSet<>();
        for (Slide s : games) {
            if (s.ev == null) continue;
            s.bg = "";
            for (String u : SportsData.gameArts(s.ev, h.act.getCacheDir())) {
                if (usedArt.contains(u) || !sharp(u)) continue;
                usedArt.add(u); s.bg = u; break;
            }
            /* no photo of THIS game (only one team's fan art, or the stadium): TopShelf draws the matchup instead —
               both teams' fan art in slanted panels, their colours and logos, VS (see Matchup) */
            String from = s.bg.isEmpty() ? "" : String.valueOf(SportsData.ART_SRC.get(s.bg));
            boolean ofGame = from.contains("the game") || from.contains("both teams");
            if (!ofGame && !s.ev.card && s.ev.teams.size() == 2) {
                try {
                    String[] fan = SportsData.fanArt(s.ev, h.act.getCacheDir());
                    String m = Matchup.make(s.ev, fan[0], fan[1], h.act.getCacheDir());
                    if (!m.isEmpty()) { s.bg = m; s.fanA = fan[0]; s.fanB = fan[1]; s.sig = Matchup.scoreSig(s.ev); SIZE.put(m, Matchup.W + "x" + Matchup.H); SportsData.ART_SRC.put(m, "TopShelf · matchup art"); }
                } catch (Throwable t) { Diag.ev("matchup art: " + t); }
            }
            // a fight card: the main event's two fighters, cut out, for the poster (when there's no picture of the night)
            if (s.ev.card && s.ev.teams.size() == 2) {
                s.cutA = fighterPic(s.ev.teams.get(0)); s.cutB = fighterPic(s.ev.teams.get(1));
            }
            Diag.ev("billboard: " + s.ev.shortName + " ← " + (s.bg.isEmpty() ? (s.cutA.isEmpty() ? "team colours" : "fight poster") : SportsData.ART_SRC.containsKey(s.bg) ? SportsData.ART_SRC.get(s.bg) + " " + SIZE.get(s.bg) : "?"));
        }
        List<Slide> gq = new ArrayList<>();
        Slide lead = null;
        for (Slide s : games) if (s.live && lead == null) lead = s;
        if (lead != null) gq.add(lead);
        for (Slide s : pick(games, 6, 2 * 3600000L, seen)) if (s != lead) gq.add(s);

        // what you're part-way through, and the next episodes
        List<Slide> cq = new ArrayList<>();
        for (JSONObject m : cw) {
            Slide s = titleSlide(m, m.optString("type"));
            s.kind = "cw"; s.key = "cw:" + m.optString("video", m.optString("id"));
            String lab = m.optString("cwLabel");
            boolean next = lab.startsWith("Next");
            double pct = m.optDouble("cwPct", 0);
            s.tag = next ? "UP NEXT" : "CONTINUE WATCHING"; s.tagCol = 0xFFA8D8FF;
            String line = next ? lab.substring(Math.min(lab.length(), 7)) : lab;
            s.meta = join(java.util.Arrays.asList(line, pct > 0.01 ? Math.round(pct * 100) + "% watched" : ""), "   ·   ");
            s.go = pct > 0.01 ? "Resume" : "Play";
            s.more = "series".equals(m.optString("type")) ? "Episodes" : "More details";
            s.strip = next ? "Up next" : "Continue";
            if (!blocked.contains(s.key)) { cq.add(s); used.add(m.optString("id")); }
        }
        cq = pick(cq, 2, 6 * 3600000L, seen);
        for (Slide s : cq) sharpen(s, false);

        // new episodes of your shows (out in the last two days)
        List<Slide> nq = new ArrayList<>();
        for (JSONObject m : ups) {
            long at = m.optLong("calAt");
            if (at > now || at < now - 2 * SportsData.DAY || used.contains(m.optString("id"))) continue;
            try {
                JSONObject t = new JSONObject(m.toString());
                if (!m.optString("calVideo").isEmpty()) t.put("video", m.optString("calVideo"));
                Slide s = titleSlide(t, "series");
                s.kind = "new"; s.key = "new:" + m.optString("calVideo", m.optString("id"));
                s.tag = "NEW EPISODE"; s.tagCol = 0xFFFFC98A;
                s.meta = m.optString("calLabel") + (m.optString("calEp").isEmpty() ? "" : "   ·   " + m.optString("calEp"));
                s.go = "Watch now"; s.more = "Episodes"; s.strip = "New episode";
                if (!blocked.contains(s.key)) { nq.add(s); used.add(m.optString("id")); }
            } catch (Exception ignored) { }
        }
        nq = pick(nq, 2, 12 * 3600000L, seen);
        for (Slide s : nq) sharpen(s, false);

        // your list
        List<Slide> lq = new ArrayList<>();
        for (int i = 0; i < mine.length(); i++) {
            JSONObject m = mine.optJSONObject(i);
            if (m == null || used.contains(m.optString("id"))) continue;
            if ("movie".equals(m.optString("type")) && Progress.watched(getContext(), m.optString("id"))) continue;
            Slide s = titleSlide(m, m.optString("type"));
            s.kind = "mine"; s.key = "t:" + m.optString("id");
            s.tag = ("series".equals(m.optString("type")) ? "TV SHOW" : "MOVIE") + "  ·  FROM YOUR LIST"; s.tagCol = 0xFFFFC23D;
            s.strip = "Your list";
            if (!blocked.contains(s.key) && !s.bg.isEmpty()) lq.add(s);
        }
        lq = sharpOnly(pick(lq, 12, 2 * SportsData.DAY, seen), 2);
        for (Slide s : lq) used.add(s.m.optString("id"));

        // movies and shows: popular, new, in theaters / airing today, trending
        int y = Calendar.getInstance().get(Calendar.YEAR);
        String cm = NativeVod.CINEMETA;
        List<String[]> mv = new ArrayList<>(), tv = new ArrayList<>();
        if (tk) mv.add(new String[] { "TRENDING NOW", "trakt:/movies/trending?limit=40" });
        if (tm) mv.add(new String[] { "IN THEATERS", "tmdb:/movie/now_playing?region=US" });
        mv.add(new String[] { "POPULAR", cm + "/catalog/movie/top.json" });
        mv.add(new String[] { "NEW THIS YEAR", cm + "/catalog/movie/year/genre=" + y + ".json" });
        if (tk) tv.add(new String[] { "TRENDING NOW", "trakt:/shows/trending?limit=40" });
        if (tm) tv.add(new String[] { "AIRING TODAY", "tmdb:/tv/airing_today" });
        tv.add(new String[] { "POPULAR", cm + "/catalog/series/top.json" });
        tv.add(new String[] { "NEW THIS YEAR", cm + "/catalog/series/year/genre=" + y + ".json" });
        List<Slide> mq = sharpOnly(pick(catalogs(mv, "movie", used, blocked), 12, 3 * SportsData.DAY, seen), 3);
        for (Slide s : mq) used.add(s.m.optString("id"));
        List<Slide> sq = sharpOnly(pick(catalogs(tv, "series", used, blocked), 12, 3 * SportsData.DAY, seen), 3);

        // trailers for the chosen titles that came without one (Cinemeta has most; asked once per title)
        if (!"off".equals(h.S.optString("trailers", "card")))
            for (List<Slide> q : java.util.Arrays.asList(cq, nq, lq, mq, sq)) for (Slide s : q) ytFor(s);

        // woven together
        String[] pattern = { "game", "cw", "movie", "series", "new", "game", "mine", "movie", "series", "game", "cw", "new", "movie", "series", "mine", "game" };
        List<Slide> out = new ArrayList<>();
        for (String k : pattern) {
            if (out.size() >= DECK) break;
            List<Slide> q = k.equals("game") ? gq : k.equals("cw") ? cq : k.equals("new") ? nq : k.equals("mine") ? lq : k.equals("movie") ? mq : sq;
            if (!q.isEmpty()) out.add(q.remove(0));
        }
        for (List<Slide> q : java.util.Arrays.asList(mq, sq, gq, cq, nq, lq)) while (out.size() < DECK && !q.isEmpty()) out.add(q.remove(0));
        return out;
    }

    /* (worker) big enough to fill the TV without looking soft: at least about two-thirds of the screen's width
       and wider than tall. Each picture is measured once (its header only is read into memory). */
    private static final java.util.Map<String, String> SIZE = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean sharp(String u) {
        String got = SIZE.get(u);
        if (got == null) {
            int[] wh = HomeImages.size(u);          // (its first bytes only, not the whole picture)
            if (wh[0] == 0) try {                   // (the size wasn't near the start: the whole picture, once)
                byte[] d = HomeImages.http(u, null);
                if (d != null) {
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeByteArray(d, 0, d.length, o);
                    wh = new int[] { Math.max(0, o.outWidth), Math.max(0, o.outHeight) };
                }
            } catch (Throwable ignored) { }
            got = wh[0] + "x" + wh[1];
            SIZE.put(u, got);
        }
        String[] p = got.split("x");
        int w = Integer.parseInt(p[0]), hh = Integer.parseInt(p[1]);
        return w >= Math.min(1280, Math.round(h.W * .66f)) && w >= hh * 1.25f;
    }
    private static final java.util.Map<String, String> YT = new java.util.concurrent.ConcurrentHashMap<>();
    private void ytFor(Slide s) {
        if (s.m == null || !s.m.optString("yt").isEmpty()) return;
        String id = s.m.optString("id"), t = "series".equals(s.m.optString("type")) ? "series" : "movie";
        if (!id.startsWith("tt")) return;
        String y = YT.get(id);
        if (y == null) {
            y = "";
            try {
                byte[] d = HomeImages.http(NativeVod.CINEMETA + "/meta/" + t + "/" + id + ".json", null);
                JSONObject meta = d == null ? null : new JSONObject(new String(d, StandardCharsets.UTF_8)).optJSONObject("meta");
                if (meta != null) y = NativeVod.slim(meta, t).optString("yt");
            } catch (Throwable ignored) { }
            YT.put(id, y);
        }
        if (!y.isEmpty()) try { s.m = new JSONObject(s.m.toString()).put("yt", y); } catch (Exception ignored) { }
    }
    /** the large copy if it's there and sharp, else the usual one if sharp; else (keep=false) no picture: your photo shows */
    private boolean sharpen(Slide s, boolean keep) {
        if (!s.bg.isEmpty() && sharp(s.bg)) return true;
        if (!s.bgAlt.isEmpty() && !s.bgAlt.equals(s.bg) && sharp(s.bgAlt)) { s.bg = s.bgAlt; return true; }
        if (!keep) s.bg = "";
        return false;
    }
    /** the first n with a sharp picture (a few looked at at most) */
    private List<Slide> sharpOnly(List<Slide> l, int n) {
        List<Slide> out = new ArrayList<>();
        int tried = 0;
        for (Slide s : l) { if (out.size() >= n || tried >= n + 5) break; tried++; if (sharpen(s, true)) out.add(s); }
        return out;
    }
    private String fighterPic(SportsData.Team t) {
        String u = TheSportsDb.fighter(t.full, h.act.getCacheDir());
        return u.isEmpty() ? t.head : u;
    }

    private Slide titleSlide(JSONObject m, String type) {
        Slide s = new Slide();
        boolean tvs = "series".equals(type);
        s.kind = tvs ? "series" : "movie"; s.key = "t:" + m.optString("id");
        s.m = m;
        s.title = m.optString("name");
        // the large copies (the medium ones looked soft full screen on a TV)
        s.bgAlt = m.optString("background");
        s.bg = s.bgAlt.replace("/background/medium/", "/background/large/").replace("/t/p/w1280/", "/t/p/original/");
        s.logo = m.optString("logo").replace("/logo/medium/", "/logo/large/");
        List<String> meta = new ArrayList<>();
        String yr = m.optString("year", m.optString("releaseInfo"));
        if (!yr.isEmpty()) meta.add(yr);
        JSONArray gs = m.optJSONArray("genres");
        if (gs != null) { List<String> g = new ArrayList<>(); for (int i = 0; i < gs.length() && i < 2; i++) g.add(gs.optString(i)); String gj = join(g, " · "); if (!gj.isEmpty()) meta.add(gj); }
        if (!m.optString("runtime").isEmpty()) meta.add(m.optString("runtime"));
        String r = m.optString("rating", m.optString("imdbRating"));
        if (!r.isEmpty() && !r.equals("0")) meta.add("★ " + r);
        s.meta = join(meta, "   ·   ");
        String d = m.optString("desc", m.optString("description"));
        s.desc = d;
        s.strip = tvs ? "TV show" : "Movie";
        return s;
    }

    private List<Slide> catalogs(List<String[]> srcs, String type, Set<String> used, Set<String> blocked) {
        List<Slide> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (String[] src : srcs) {
            List<JSONObject> l;
            try { l = catalog(src[1], type); } catch (Throwable t) { continue; }
            int n = 0;
            for (JSONObject m : l) {
                String id = m.optString("id");
                if (id.isEmpty() || used.contains(id) || !ids.add(id) || m.optString("background").isEmpty()) continue;
                if ("movie".equals(type) && Progress.watched(getContext(), id)) continue;
                Slide s = titleSlide(m, type);
                s.tag = ("series".equals(type) ? "TV SHOW" : "MOVIE") + "  ·  " + src[0];
                s.tagCol = src[0].startsWith("TRENDING") ? 0xFFFF9B7A : src[0].startsWith("IN THEATERS") || src[0].startsWith("AIRING") ? 0xFFB9E3FF : 0xFFE8ECF1;
                if (blocked.contains(s.key)) continue;
                out.add(s);
                if (++n >= 20) break;          // (the top of each list: the rest is the tab's)
            }
        }
        return out;
    }

    /** a list, as Movies & TV read it (the same copy on the TV, so neither asks twice) */
    private List<JSONObject> catalog(String url, String type) throws Exception {
        File f = new File(h.act.getCacheDir(), "cm-" + Integer.toHexString(url.hashCode()) + ".json");
        String body = null;
        boolean fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < Feeds.ttl(url);
        if (f.exists()) body = read(f);
        if (!fresh) {
            try {
                String d = Feeds.fetch(h, url, type);
                if (d != null) { body = d; try { FileOutputStream o = new FileOutputStream(f); o.write(d.getBytes(StandardCharsets.UTF_8)); o.close(); } catch (Exception ignored) { } }
            } catch (Throwable ignored) { }
        }
        return body == null ? new ArrayList<JSONObject>() : NativeVod.parse(body, type);
    }
    private static String read(File f) {
        try { byte[] b = new byte[(int) f.length()]; FileInputStream in = new FileInputStream(f); int n = 0; while (n < b.length) { int k = in.read(b, n, b.length - n); if (k < 0) break; n += k; } in.close(); return new String(b, 0, n, StandardCharsets.UTF_8); }
        catch (Exception e) { return null; }
    }

    /* from a pool: what you haven't seen within the window first (in a fresh random order each round), then the
       ones seen longest ago */
    private static List<Slide> pick(List<Slide> pool, int n, final long window, final JSONObject seen) {
        List<Slide> l = new ArrayList<>(pool);
        Collections.shuffle(l, new Random(System.nanoTime()));
        final long now = System.currentTimeMillis();
        Collections.sort(l, new Comparator<Slide>() { @Override public int compare(Slide a, Slide b) {
            long sa = seen.optLong(a.key, 0), sb = seen.optLong(b.key, 0);
            boolean ra = now - sa < window, rb = now - sb < window;
            if (ra != rb) return ra ? 1 : -1;
            return ra ? Long.compare(sa, sb) : 0;
        } });
        return new ArrayList<>(l.subList(0, Math.min(n, l.size())));
    }

    // ---- what was shown when, and what you said not to show
    private JSONObject seen() { try { String s = h.prefs.getString("ui.featSeen", null); return s == null ? new JSONObject() : new JSONObject(s); } catch (Exception e) { return new JSONObject(); } }
    private void markSeen(Slide s) {
        try {
            JSONObject o = seen(), keep = new JSONObject();
            long now = System.currentTimeMillis();
            o.put(s.key, now);
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); if (now - o.optLong(k) < 14 * SportsData.DAY) keep.put(k, o.optLong(k)); }
            h.prefs.edit().putString("ui.featSeen", keep.toString()).apply();
        } catch (Exception ignored) { }
    }
    private Set<String> blocked() {
        Set<String> out = new HashSet<>();
        JSONArray a = arr(h.prefs.getString("ui.featBlock", null));
        for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }
    private void block(String key) {
        JSONArray a = arr(h.prefs.getString("ui.featBlock", null));
        a.put(key);
        h.prefs.edit().putString("ui.featBlock", a.toString()).apply();
    }
    private static JSONArray arr(String s) { try { return s == null ? new JSONArray() : new JSONArray(s); } catch (Exception e) { return new JSONArray(); } }
}
