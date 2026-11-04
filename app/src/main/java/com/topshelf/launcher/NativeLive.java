package com.topshelf.launcher;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.ComposeShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Live TV, drawn by Android (it was a web page): categories, the TV guide, the channel preview and
 * full-screen watching with its banner, channel list, stream info and menus. Channels play in the
 * TV's own video player (NativePlayer), whose picture sits under the app's window: the screen is
 * see-through exactly where the picture shows (the preview's rounded box, or everything in full
 * screen).
 *
 * For this TV's graphics chip: the studio backdrop, the guide's frosted pane and the preview's frame
 * are painted once into one solid picture, and the guide grid is drawn by one view in one pass.
 * Multiview (several channels at once) is still the page's.
 */
final class NativeLive extends FrameLayout {
    /* Fading in, this pane is drawn piece by piece at the fading strength rather than into one picture
       first: its rows and pictures are still arriving as it fades, and each one spoilt that picture,
       so the whole screen was drawn twice a frame (switching tabs ran at about 20 frames a second). */
    @Override public boolean hasOverlappingRendering() { return false; }
    private static final long SLOT = 30 * 60000L;
    private static final int HOURS = 12;
    /* logos get their own paint: drawn with a tile's see-through colour they came out dark */
    private static final Paint LOGO_P = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final int ACCENT = 0xFFE0352B;       // the red of "on now" and "playing"
    private static final float ROWH = 5.7f, HEAD = 3.3f, CHW = 22f, PER_HOUR = 35f;
    // where things are (rem)
    private static final float GP_X = 3, GP_Y = 8.8f, PV_R = 0;
    /* while the top bars are away (in the guide), the preview grows up into their space: same bottom, flush right, up to the top.
       (A phone held upright: the preview runs across the top, the channel's details under it, then the guide.) */
    private float PV_W = 36.3f, PV_H = 36.3f * 9 / 16f, PV_Y = 6.3f, BIG_H = PV_Y + PV_H, BIG_W = BIG_H * 16 / 9f;
    private static final int ID0 = 1000000;

    private final NativeHome h;
    private final float R;
    private final int W, H;
    private final float SW, SH;
    private boolean shown;

    // ---------------------------------------------------------------- data
    static final class Ch { int i; String n = "", g = "", u = "", k = "", l = "", c = "", ua = "", cu = "", cs = "", sid = ""; int cd, xi = -1; }
    private static final class Cat { String name; boolean fav, recent, all, rec; String group; int count; }
    private final List<Ch> chans = new ArrayList<>();
    private final List<Cat> cats = new ArrayList<>();
    private List<Ch> list = new ArrayList<>();
    private int cat = 2, row = 0;
    private long t, base;
    private String zone = "guide";
    private final Set<String> favs = new HashSet<>();
    private final List<String> recent = new ArrayList<>();
    private boolean loaded;
    private String progress = "";
    private final Map<String, Object[]> prog = new HashMap<>();       // key → {List<long[]>, List<String[]>}

    // ---------------------------------------------------------------- player
    private Ch playing, prevCh, lastFull;
    private boolean full, ready, zap, covered, stats;
    private int pid = ID0;
    private String tried = "", lastErr = "";
    private int stalls;
    private long sleepAt;
    private String cuesText = "";

    // ---------------------------------------------------------------- views
    private final BgView bg;
    private final TextView label;
    private final View dot;
    private final LinearLayout info;
    private final TextView iChan, iTitle, iWhen, iDesc;
    private final View iBar, iBarFill;
    private final GuideView guide;
    private final View guideDim;
    private final FrameLayout catsPanel;
    private static final float CAT_W = 23, CAT_TOP = 3.2f;
    private float GUIDE_X = GP_X + 2, GUIDE_Y = GP_Y + 17.4f;
    private final float GUIDE_H;
    private final CatView catView;
    private final TextView empty;
    private final FrameLayout pv;                 // over the preview box until the picture comes
    private final FrameLayout fs;                 // full-screen layer (banner, channel list, info)
    private Tune pvTune, fsTune;
    /** the preview's place: top right, flush with the screen's right edge */
    private LayoutParams pvLp() { int w = h.px(BIG_W), hh = h.px(BIG_H); LayoutParams l = new LayoutParams(w, hh); l.leftMargin = W - w; l.topMargin = 0; return l; }
    /* How big the preview is: 0 = its usual size under the top bars, 1 = grown into their space while they're away. */
    private float grow; private ValueAnimator growAnim; private final RectF pvRect = new RectF();
    void barsHidden(boolean hidden) {
        float to = hidden ? 1f : 0f;
        if (growAnim != null) growAnim.cancel();
        if (!shown || full || grow == to) { grow = to; applyGrow(); return; }
        growAnim = ValueAnimator.ofFloat(grow, to);
        growAnim.setDuration(hidden ? 420 : 340); growAnim.setInterpolator(TileView.EASE);
        growAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { @Override public void onAnimationUpdate(ValueAnimator a) { grow = (Float) a.getAnimatedValue(); applyGrow(); } });
        growAnim.addListener(new android.animation.AnimatorListenerAdapter() { @Override public void onAnimationEnd(android.animation.Animator a) { place(); } });
        growAnim.start();
        place();
    }
    /** the preview's box right now (screen px) */
    private RectF pvBox() {
        float w = (PV_W + (BIG_W - PV_W) * grow) * R, hh = w * 9 / 16f, b = BIG_H * R;
        pvRect.set(W - w, b - hh, W, b);
        return pvRect;
    }
    private void applyGrow() {
        float s = (PV_W + (BIG_W - PV_W) * grow) / BIG_W;
        for (View v : new View[] { idle, pv }) {
            if (v == null) continue;
            v.setPivotX(h.px(BIG_W)); v.setPivotY(h.px(BIG_H)); v.setScaleX(s); v.setScaleY(s);
        }
        bg.invalidate();
        if (growing()) { NativePlayer np = h.act.nativePlayer(); if (np != null) try { np.setPvScale(s); } catch (Throwable ignored) { } }
    }
    private boolean growing() { return growAnim != null && growAnim.isRunning(); }
    private FrameLayout idle;
    private ImageView idleLogo;
    private TextView idleName, idleHint;
    private String idleKey = "";
    /** the preview's window while nothing plays: the focused channel's logo (or name) */
    private void drawIdle() {
        boolean on = shown && !full && playing == null && !covered && !list.isEmpty();
        idle.setVisibility(on ? VISIBLE : INVISIBLE);
        if (!on) return;
        final Ch ch = row < list.size() ? list.get(row) : null;
        if (ch == null || ch.u.equals(idleKey)) return;
        idleKey = ch.u;
        boolean prev = !"external".equals(h.S.optString("livePlayer")) && h.S.optBoolean("livePreview", true);
        idleHint.setText(prev ? "OK · Preview" : "OK · Watch");
        idleLogo.setImageDrawable(null);
        idleName.setText(ch.n);
        idleName.setVisibility(VISIBLE);
        if (!ch.l.isEmpty()) logo(ch.l, h.px(BIG_W * .42f), h.px(BIG_H * .42f), new HomeImages.Done() { @Override public void done(Bitmap b) {
            if (b != null && idleKey.equals(ch.u)) { idleLogo.setImageBitmap(b); idleName.setVisibility(INVISIBLE); }
        } });
    }
    private final TextView pvErr, fsErr;
    private TextView iHint;
    private final FrameLayout osd;
    private ImageView oLogo; private TextView oPh, oChan, oTitle, oWhen, oNext, oHint; private View oBarFill;
    private final ZapView zapView;
    private final LinearLayout statsBox;
    private NativeStats statsView;
    /* Live stats: the game on the channel you're watching full screen, found by the page (the Sports
       tab's matching) when the channel starts; the panel opens from the watching menu (hold OK). */
    private JSONObject watchGame; private int gameReq; private String gameFor = ""; private boolean gamePending;
    private void findGame() {
        if (playing == null || recPlay != null) return;
        if (playing.u.equals(gameFor) && (watchGame != null || gamePending)) return;
        gameFor = playing.u; watchGame = null; gamePending = true; gameReq++;
        oHint.setText("▲▼ change channel · hold OK for more · Back for guide");
        h.page("window.spGameFor && spGameFor(" + JSONObject.quote(playing.u) + "," + gameReq + ")");
    }
    /** the page's answer: the game on that channel (or none) */
    void statsGame(String req, String json) {
        if (!String.valueOf(gameReq).equals(req)) return;
        gamePending = false;
        try { watchGame = json == null || json.isEmpty() ? null : new JSONObject(json); } catch (Exception e) { watchGame = null; }
        Diag.ev("live stats: " + (watchGame == null ? "no game on this channel" : "game " + watchGame.optString("short", watchGame.optString("name"))));
        oHint.setText(watchGame != null ? "▲▼ change channel · hold OK · Live stats · Back for guide" : "▲▼ change channel · hold OK for more · Back for guide");
        if (statsView != null) statsView.gameFound(watchGame);
        pushWatching();
        if (watchGame != null && full && osd.getAlpha() < .5f) showOsd();     // (so the hint is seen)
    }
    private void openStats() {
        if (playing == null) return;
        main().removeCallbacks(osdHide); osdHide.run();
        if (watchGame == null && !gamePending) { gameFor = ""; findGame(); }
        statsView.open(watchGame, gamePending);
    }
    private final TextView cues;
    private Bitmap bgBmp, glassLight, glassFrost;

    NativeLive(NativeHome host) {
        super(host.getContext());
        h = host; R = host.R; W = host.W; H = host.H; SW = host.SW; SH = host.SH;
        if (h.portrait) { PV_W = BIG_W = SW; PV_H = BIG_H = SW * 9 / 16f; PV_Y = 0; GUIDE_X = GP_X; GUIDE_Y = BIG_H + 15.5f; }
        GUIDE_H = SH - GUIDE_Y - h.BOT;               // (runs to the bottom edge, like the rows on the other tabs)
        setClipChildren(false);
        android.content.Context c = getContext();

        bg = new BgView(c);
        addView(bg, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // "● Live TV"
        LinearLayout lab = new LinearLayout(c);
        lab.setGravity(Gravity.CENTER_VERTICAL);
        dot = new View(c);
        GradientDrawable dd = new GradientDrawable(); dd.setShape(GradientDrawable.OVAL); dd.setColor(0xFFFF453A);
        dot.setBackground(dd);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(h.px(1.1f), h.px(1.1f)); dl.rightMargin = h.px(.9f);
        lab.addView(dot, dl);
        label = h.text(h.fSemi, 2.8f, Color.WHITE);
        label.setText("Live TV");
        label.setLetterSpacing(-.02f);
        label.setShadowLayer(.8f * R, 0, .1f * R, 0x73000000);
        lab.addView(label);
        addView(lab, h.at(5, 2.5f, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        if (h.portrait) lab.setVisibility(GONE);
        // the tab's name shaded in its colour, like Movies and TV Shows
        label.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r - l == or - ol && label.getPaint().getShader() != null) return;
                if (r - l <= 0) return;
                label.getPaint().setShader(new LinearGradient(0, 0, r - l, 0, Color.WHITE, 0xFFFF8A80, Shader.TileMode.CLAMP));
                label.invalidate();
            }
        });

        // what's on the focused channel
        info = new LinearLayout(c);
        info.setOrientation(LinearLayout.VERTICAL);
        iChan = h.text(h.fSemi, 1.05f, 0xFFFF8A80); iChan.setSingleLine(true); iChan.setEllipsize(TextUtils.TruncateAt.END); iChan.setLetterSpacing(.04f);
        info.addView(iChan);
        iTitle = h.text(h.fSemi, 3.2f, Color.WHITE); iTitle.setSingleLine(true); iTitle.setEllipsize(TextUtils.TruncateAt.END); iTitle.setLetterSpacing(-.02f);
        iTitle.setShadowLayer(1.2f * R, 0, .2f * R, 0x80000000);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); tl.topMargin = h.px(.6f);
        info.addView(iTitle, tl);
        iWhen = h.text(h.fMed, 1.2f, 0xDBFFDCD8);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); wl.topMargin = h.px(.4f);
        info.addView(iWhen, wl);
        FrameLayout bar = new FrameLayout(c);
        GradientDrawable bb = new GradientDrawable(); bb.setCornerRadius(R); bb.setColor(0x33FFFFFF); bar.setBackground(bb);
        iBarFill = new View(c);
        GradientDrawable bf = new GradientDrawable(); bf.setCornerRadius(R); bf.setColor(Color.WHITE); iBarFill.setBackground(bf);
        bar.addView(iBarFill, new LayoutParams(0, LayoutParams.MATCH_PARENT));
        iBar = bar;
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(h.px(22), h.px(.35f)); bl.topMargin = h.px(.7f);
        info.addView(bar, bl);
        iDesc = h.text(h.fReg, 1.15f, 0xC7FFFFFF); iDesc.setMaxLines(2); iDesc.setEllipsize(TextUtils.TruncateAt.END); iDesc.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams ddl = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); ddl.topMargin = h.px(.8f);
        info.addView(iDesc, ddl);
        iHint = h.text(h.fMed, 1f, 0x99FF8A80);
        iHint.setLetterSpacing(.02f);
        LinearLayout.LayoutParams hl2 = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); hl2.topMargin = h.px(.9f);
        info.addView(iHint, hl2);
        if (h.portrait) addView(info, h.at(3, BIG_H + 1.2f, h.px(SW - 6), h.px(14f)));
        else addView(info, h.at(5, 9.4f, h.px(Math.min(80, SW - BIG_W - 5 - 1f)), h.px(16.4f)));

        // the guide grid
        guide = new GuideView(c);
        addView(guide, h.at(0, GUIDE_Y, W, h.px(GUIDE_H)));
        recView = new RecView(c);
        recView.setVisibility(INVISIBLE);
        addView(recView, h.at(0, GUIDE_Y, W, h.px(GUIDE_H)));
        empty = h.text(h.fMed, 1.25f, 0xBFFFFFFF);
        empty.setGravity(Gravity.CENTER);
        empty.setLineSpacing(0, 1.3f);
        empty.setPadding(h.px(8), 0, h.px(8), 0);
        empty.setVisibility(GONE);
        addView(empty, h.at(GP_X + 2, GP_Y + 2, h.px(SW - 2 * GP_X - 4), h.px(SH - GP_Y - 2.2f - 4)));
        guideDim = new View(c);
        guideDim.setBackgroundColor(0x8C07060A);
        guideDim.setAlpha(0f);
        addView(guideDim, h.at(0, GUIDE_Y, W, h.px(GUIDE_H)));

        /* Categories: a plain list in the guide's place at the left (the guide steps aside to the right),
           its rows like the channel tiles — no floating glass panel */
        catsPanel = new FrameLayout(c);
        catsPanel.setClipChildren(false);
        TextView ch = h.text(h.fSemi, 1f, 0x99FFFFFF);
        ch.setText("CATEGORIES"); ch.setLetterSpacing(.08f);
        catsPanel.addView(ch, h.at(.4f, .75f, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        catView = new CatView(c);
        catsPanel.addView(catView, h.at(-.9f, CAT_TOP - .5f, h.px(CAT_W + 1.8f), h.px(GUIDE_H - CAT_TOP + .5f)));
        catsPanel.setTranslationX(-h.px(3));
        catsPanel.setAlpha(0f);
        catsPanel.setVisibility(INVISIBLE);
        addView(catsPanel, h.at(GUIDE_X, GUIDE_Y, h.px(CAT_W), h.px(GUIDE_H)));

        // the preview's window while nothing plays in it: the channel you're on, and how to watch it
        idle = new FrameLayout(c);
        idle.setBackground(null);
        idleLogo = new ImageView(c); idleLogo.setScaleType(ImageView.ScaleType.FIT_CENTER); idleLogo.setAlpha(.9f);
        idle.addView(idleLogo, new LayoutParams(h.px(BIG_W * .42f), h.px(BIG_H * .42f), Gravity.CENTER));
        idleName = h.text(h.fSemi, 1.6f, 0x99FFFFFF); idleName.setGravity(Gravity.CENTER);
        idle.addView(idleName, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        idleHint = h.text(h.fMed, .95f, 0x8CFFFFFF); idleHint.setText("OK · Preview");
        LayoutParams ihl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); ihl.bottomMargin = h.px(1);
        idle.addView(idleHint, ihl);
        idle.setOutlineProvider(new android.view.ViewOutlineProvider() { @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), PV_R * R); } });
        idle.setClipToOutline(true);
        idle.setVisibility(INVISIBLE);
        addView(idle, pvLp());

        // over the preview until its picture arrives
        pv = new FrameLayout(c);
        pv.setBackgroundColor(Color.BLACK);
        pv.setVisibility(INVISIBLE);
        pvTune = new Tune(BIG_W / 120f);
        pv.addView(pvTune.box, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        pvErr = h.text(h.fMed, .85f, 0xBFFFFFFF);
        pvErr.setGravity(Gravity.CENTER); pvErr.setPadding(h.px(.6f), 0, h.px(.6f), 0); pvErr.setBackgroundColor(0x99000000); pvErr.setVisibility(GONE);
        pv.addView(pvErr, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        pv.setOutlineProvider(new android.view.ViewOutlineProvider() { @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), PV_R * R); } });
        pv.setClipToOutline(true);
        addView(pv, pvLp());
        applyGrow();

        // full screen
        fs = new FrameLayout(c);
        fs.setVisibility(INVISIBLE);
        fsTune = new Tune(1f);
        fs.addView(fsTune.box, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        fsErr = h.text(h.fMed, 1.1f, 0xBFFFFFFF);
        fsErr.setGravity(Gravity.CENTER); fsErr.setBackgroundColor(0x99000000); fsErr.setLineSpacing(0, 1.3f); fsErr.setVisibility(GONE);
        fs.addView(fsErr, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        cues = h.text(h.fMed, 2.3f, Color.WHITE);
        cues.setGravity(Gravity.CENTER);
        cues.setVisibility(GONE);
        LayoutParams cl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cl.bottomMargin = h.px(4.7f);
        fs.addView(cues, cl);
        osd = new FrameLayout(c);
        osd.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { 0x00000000, 0x8C000000, 0xE6000000 }));
        buildOsd();
        osd.setAlpha(0f);
        fs.addView(osd, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        zapView = new ZapView(c);
        zapView.setVisibility(INVISIBLE);
        fs.addView(zapView, new LayoutParams(h.px(Math.min(45.6f, SW - 4)), LayoutParams.MATCH_PARENT));
        statsBox = new LinearLayout(c);
        statsBox.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sb = new GradientDrawable(); sb.setCornerRadius(1.2f * R); sb.setColor(0xC70A0A0E);
        statsBox.setBackground(sb);
        statsBox.setPadding(h.px(1.7f), h.px(1.1f), h.px(1.7f), h.px(1.1f));
        statsBox.setVisibility(GONE);
        LayoutParams stl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT);
        stl.rightMargin = h.px(2.9f); stl.topMargin = h.px(2);
        fs.addView(statsBox, stl);
        statsView = new NativeStats(h);
        fs.addView(statsView, h.at(Math.max(2, SW - 2.5f - 46f), 2.5f, h.px(Math.min(46f, SW - 4)), h.px(SH - 5f)));
        addView(fs, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        h.imgs.make("livebg:" + W + "x" + H, new HomeImages.Maker() { @Override public Bitmap make() { return bakeBg(); } }, new HomeImages.Done() {
            @Override public void done(Bitmap b) {
                if (b == null) return;
                bgBmp = b;
                bg.invalidate();
                Bitmap fr = Blur.glass(b, 8, 3, 1.7f, 1f);
                glassLight = Blur.glass(b, 4, 2, 1.9f, 1.06f); glassFrost = fr;
                if (shown) h.setTabGlass(glassLight, glassFrost);
            }
        });
    }

    // ================================================================== show / hide

    boolean shownNow() { return shown; }

    void show() {
        shown = true;
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(350).start();     // (no ready-made picture: see hasOverlappingRendering)
        if (!loaded) load();
        Recorder.onChange = recChanged; recChanged.run();
        JSONObject inf = liveInfo();
        if (!inf.optString("m3u").isEmpty() && !h.bridge.liveRefreshing() && (chans.isEmpty() || System.currentTimeMillis() - inf.optLong("updated") > 12 * 3600000L)) {
            progress = "Loading channels…";
            h.bridge.liveRefresh();
        }
        t = System.currentTimeMillis();
        rebase();
        zone = list.isEmpty() && !chans.isEmpty() ? "cats" : "guide";
        guide.build();
        drawAll();
        main().removeCallbacks(pulse); pulse.run();
        main().removeCallbacks(minute); main().postDelayed(minute, 30000);
        if (glassLight != null) h.setTabGlass(glassLight, glassFrost);
        schedulePreview();
        resume();
    }

    /* Coming back to Live TV picks up where you left: the channel that was playing when you left (the
       tab, or the app) is chosen again and starts playing in the preview. Nothing playing → nothing starts. */
    private boolean keepResume;                // left the app with it playing: leaving the tab later doesn't forget it
    private void remember() { if (keepResume || recPlay != null) return; putPref("liveResume", JSONObject.quote(playing != null ? playing.u : "")); }
    void appResumed() { if (shown) resume(); }
    private void resume() {
        keepResume = false;
        String u = prefStr("liveResume");
        if (u == null || u.isEmpty() || playing != null) return;
        if ("external".equals(h.S.optString("livePlayer")) || !h.S.optBoolean("livePreview", true)) return;
        Ch ch = null;
        for (int k = 0; k < list.size(); k++) if (list.get(k).u.equals(u)) { row = k; ch = list.get(k); }
        if (ch == null) for (Ch x : chans) if (x.u.equals(u)) ch = x;
        if (ch == null) return;
        putPref("liveResume", JSONObject.quote(""));     // (used once; leaving again remembers afresh)
        guide.build(); drawAll(); guide.reveal(true);
        final Ch c = ch;
        main().postDelayed(new Runnable() { @Override public void run() {
            if (shown && !full && playing == null && !covered) play(c, false);
        } }, 350);
    }

    // ================================================================== for Search (drawn and worked out by the app)

    /** channels whose name has q (hidden categories left out), best matches first: {u, n, l, g, now, fav} */
    JSONArray searchChannels(String q, int max) {
        if (!loaded && !quickLoad()) return new JSONArray();
        final String ql = q.toLowerCase(java.util.Locale.ROOT);
        Set<String> hid = hiddenCats();
        List<Object[]> hits = new ArrayList<>();
        for (Ch c : chans) {
            if (hid.contains(c.g) || !LiveTv.has(c.n, ql)) continue;
            String nl = c.n.toLowerCase(java.util.Locale.ROOT);
            int score = nl.startsWith(ql) ? 0 : wordStart(nl, ql) ? 1 : 2;
            hits.add(new Object[] { score, c });
            if (hits.size() > 400) break;
        }
        java.util.Collections.sort(hits, new java.util.Comparator<Object[]>() { @Override public int compare(Object[] a, Object[] b) { return (Integer) a[0] - (Integer) b[0]; } });
        JSONArray out = new JSONArray();
        long now = System.currentTimeMillis();
        for (int i = 0; i < hits.size() && i < max; i++) {
            Ch c = (Ch) hits.get(i)[1];
            out.put(chJson(c, now));
        }
        return out;
    }
    private static boolean wordStart(String s, String q) {
        for (String w : s.split("[\\s\\-:]+")) if (w.startsWith(q)) return true;
        return false;
    }
    private JSONObject chJson(Ch c, long now) {
        JSONObject o = new JSONObject();
        try {
            o.put("u", c.u).put("n", c.n).put("l", c.l).put("g", c.g).put("fav", favs.contains(c.u));
            long[] pr = progAt(c, now);
            o.put("now", pr == null ? "" : progText(c, pr)[0]);
        } catch (Exception ignored) { }
        return o;
    }
    /** a guide key's channel (a favourite one first, hidden categories left out) */
    JSONObject channelForKey(String key) {
        if (!loaded && !quickLoad()) return null;
        Set<String> hid = hiddenCats();
        Ch best = null;
        for (Ch c : chans) if (key.equals(c.k) && !hid.contains(c.g) && (best == null || favs.contains(c.u) && !favs.contains(best.u))) best = c;
        return best == null ? null : chJson(best, System.currentTimeMillis());
    }
    boolean isFav(String u) { return favs.contains(u); }
    void toggleFavUrl(String u) { Ch c = byUrl.get(u); if (c == null) for (Ch x : chans) if (x.u.equals(u)) c = x; if (c != null) toggleFav(c); }
    boolean hasReminder(String u, long at) { return remHas(u, at); }

    // ================================================================== for Sports (finding a game on your channels)

    /** what the channel list is now: the count, the first channel and the hidden categories (changes when any of them does) */
    String sportsKey() { if (!loaded && !quickLoad()) return "loading"; return chans.size() + ":" + (chans.isEmpty() ? "" : chans.get(0).u) + ":" + pref("liveHiddenCats").toString() + ":" + pref("liveHiddenChans").length(); }
    /** the channels, hidden categories left out (the list itself isn't changed afterwards: a new load makes new ones) */
    List<Ch> channelsForSports() {
        if (!loaded && !quickLoad()) return new ArrayList<>();
        Set<String> hid = hiddenCats();
        List<Ch> out = new ArrayList<>(chans.size());
        for (Ch c : chans) if (!hid.contains(c.g)) out.add(c);
        return out;
    }
    Set<String> favSet() { return new HashSet<>(favs); }

    /** watch this channel full screen (from search, a reminder, Multiview…) */
    void playUrl(String u) {
        if (!loaded) load();
        int i = -1;
        for (int k = 0; k < list.size(); k++) if (list.get(k).u.equals(u)) i = k;
        if (i < 0) { cat = 2; buildList(); for (int k = 0; k < list.size(); k++) if (list.get(k).u.equals(u)) i = k; }
        Ch ch = null;
        if (i >= 0) { row = i; ch = list.get(i); }
        else for (Ch x : chans) if (x.u.equals(u)) ch = x;
        if (ch == null) { h.toast("That channel isn't in your playlist any more"); return; }
        zone = "guide";
        t = System.currentTimeMillis();
        guide.build(); drawAll();
        play(ch, true);
    }

    void hide() {
        if (moving) { moving = false; saveOrder(); }
        remember();
        shown = false;
        if (full) exitFull();
        stopPlayback();
        main().removeCallbacks(pulse); main().removeCallbacks(minute);
        animate().alpha(0f).setDuration(250).withLayer().withEndAction(new Runnable() { @Override public void run() { if (!shown) setVisibility(INVISIBLE); } }).start();
    }

    /** a panel of the page (Settings…) is over this: the picture steps aside */
    void setCovered(boolean c) {
        if (covered == c) return;
        covered = c;
        place();
    }

    /** the app was left (Home button, screen off): stop the stream */
    void appPaused() {
        if (!shown) return;
        remember(); keepResume = true;
        boolean wasFull = full;
        // the TV going off while a channel is on full screen: it comes back on to that channel (Settings › Live TV)
        boolean off = false;
        try { off = !((android.os.PowerManager) getContext().getSystemService(android.content.Context.POWER_SERVICE)).isInteractive(); } catch (Throwable ignored) { }
        if (wasFull && off && playing != null && recPlay == null) {
            try { putPref("liveWake", new JSONObject().put("u", playing.u).put("n", playing.n).put("at", System.currentTimeMillis()).toString()); } catch (Exception ignored) { }
            Diag.ev("live: the TV went off on " + playing.n + " · it comes back to it");
        }
        stopPlayback();
        if (wasFull) exitFull();
    }

    private android.os.Handler main() { return h.main; }

    private final Runnable pulse = new Runnable() {
        private int k;
        @Override public void run() {
            if (!shown) return;
            // the on-air light breathes, stepped a few times a second (not a redraw every frame)
            k = (k + 1) % 16;
            float a = .55f + .45f * (float) Math.abs(Math.cos(k / 16.0 * Math.PI));
            dot.setAlpha(a);
            main().postDelayed(this, 120);
        }
    };
    private final Runnable minute = new Runnable() { @Override public void run() {
        if (!shown) return;
        if (rebase()) guide.build();
        guide.invalidate();
        drawInfo();
        if (full && osd.getAlpha() > 0) drawOsd();
        main().postDelayed(this, 30000);
    } };

    // ================================================================== data

    private JSONObject liveInfo() { try { return new JSONObject(h.bridge.liveInfo()); } catch (Exception e) { return new JSONObject(); } }
    private JSONArray pref(String k) { try { String s = h.prefs.getString("ui." + k, null); return s == null ? new JSONArray() : new JSONArray(s); } catch (Exception e) { return new JSONArray(); } }
    private String prefStr(String k) { try { String s = h.prefs.getString("ui." + k, null); return s == null ? null : new JSONArray("[" + s + "]").optString(0, null); } catch (Exception e) { return null; } }
    private void putPref(String k, String json) { h.prefs.edit().putString("ui." + k, json).apply(); }

    /** channels you've hidden one by one (hold OK › Hide this channel): {u, n} each */
    /* Search, Sports and the guide's lookups asking for channels before Live TV has been opened: the list is
       used if it has already been read in the background; if not (just after a playlist refresh), it's read in
       the background now and they get nothing this once — it was read on the screen's thread, which froze
       Search for most of a second as it opened. */
    private boolean quickBusy;
    private boolean quickLoad() {
        if (prepared(h.bridge.live.channelsFile()) != null) { load(); return true; }
        if (!quickBusy) {
            quickBusy = true;
            prepare(h.bridge, main(), new Runnable() { @Override public void run() { quickBusy = false; if (!loaded) load(); } });
        }
        return false;
    }
    private Set<String> hiddenChans() { Set<String> s = new HashSet<>(); JSONArray a = pref("liveHiddenChans"); for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null) s.add(o.optString("u")); } return s; }
    private Set<String> hiddenCats() { Set<String> s = new HashSet<>(); JSONArray a = pref("liveHiddenCats"); for (int i = 0; i < a.length(); i++) s.add(a.optString(i)); return s; }

    /* The channel list (thousands of channels, several MB) is read in the background — shortly after the
       app starts, and again when the playlist is refreshed — with a streaming reader, so opening Live TV
       doesn't freeze the screen while it's read (it took the best part of a second, every first visit). */
    private static final Object PREP_LOCK = new Object();
    private static List<Ch> prepList; private static long prepStamp = -1;
    static List<Ch> readChannels(java.io.File f) {
        List<Ch> out = new ArrayList<>();
        if (f == null || !f.exists()) return out;
        try (android.util.JsonReader r = new android.util.JsonReader(new java.io.InputStreamReader(new java.io.BufferedInputStream(new java.io.FileInputStream(f), 65536), "UTF-8"))) {
            r.beginArray();
            int idx = 0;
            while (r.hasNext()) {
                if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) { r.skipValue(); idx++; continue; }
                Ch c = new Ch(); c.i = idx;
                r.beginObject();
                while (r.hasNext()) {
                    String k = r.nextName();
                    if (r.peek() == android.util.JsonToken.NULL) { r.nextNull(); continue; }
                    switch (k) {
                        case "i": c.i = r.nextInt(); break;
                        case "n": c.n = r.nextString(); break;
                        case "g": c.g = r.nextString(); break;
                        case "u": c.u = r.nextString(); break;
                        case "k": c.k = r.nextString(); break;
                        case "l": c.l = r.nextString(); break;
                        case "c": c.c = r.nextString(); break;
                        case "ua": c.ua = r.nextString(); break;
                        case "cu": c.cu = r.nextString(); break;
                        case "cs": c.cs = r.nextString(); break;
                        case "sid": c.sid = r.nextString(); break;
                        case "cd": c.cd = r.nextInt(); break;
                        case "xi": c.xi = r.nextInt(); break;
                        default: r.skipValue();
                    }
                }
                r.endObject();
                out.add(c); idx++;
            }
            r.endArray();
        } catch (Throwable ignored) { }
        return out;
    }
    /** read the channel list in the background (then `then` on the screen's thread) */
    static void prepare(final Bridge b, final android.os.Handler main, final Runnable then) {
        new Thread(new Runnable() { @Override public void run() {
            try {
                java.io.File f = b.live.channelsFile();
                long st = f.exists() ? f.lastModified() + f.length() : 0;
                boolean fresh;
                synchronized (PREP_LOCK) { fresh = prepList != null && prepStamp == st; }
                if (!fresh) { List<Ch> l = readChannels(f); synchronized (PREP_LOCK) { prepList = l; prepStamp = st; } }
                Diag.ev("live: channel list read in the background");
            } catch (Throwable ignored) { }
            if (then != null) main.post(then);
        } }, "channels").start();
    }
    private static List<Ch> prepared(java.io.File f) {
        long st = f.exists() ? f.lastModified() + f.length() : 0;
        synchronized (PREP_LOCK) { return prepList != null && prepStamp == st ? prepList : null; }
    }

    void load() {
        chans.clear(); byUrl.clear();
        java.io.File chFile = h.bridge.live.channelsFile();
        List<Ch> pre = prepared(chFile);
        if (pre == null) { Diag.ev("live: channel list read on the screen's thread"); pre = readChannels(chFile); }
        Set<String> hidCh = hiddenChans();
        if (hidCh.isEmpty()) chans.addAll(pre); else for (Ch c : pre) if (!hidCh.contains(c.u)) chans.add(c);
        favs.clear(); JSONArray f = pref("favs"); for (int i = 0; i < f.length(); i++) favs.add(f.optString(i));
        recent.clear(); JSONArray r = pref("liveRecent"); for (int i = 0; i < r.length(); i++) recent.add(r.optString(i));
        Set<String> hid = hiddenCats();
        LinkedHashMap<String, Integer> groups = new LinkedHashMap<>();
        int all = 0;
        for (Ch c : chans) {
            if (hid.contains(c.g)) continue;
            all++;
            Integer n = groups.get(c.g); groups.put(c.g, n == null ? 1 : n + 1);
        }
        cats.clear();
        Cat cf = new Cat(); cf.name = "Favorites"; cf.fav = true; cats.add(cf);
        Cat cr = new Cat(); cr.name = "Recent"; cr.recent = true; cats.add(cr);
        Cat ca = new Cat(); ca.name = "All channels"; ca.all = true; ca.count = all; cats.add(ca);
        Cat cv = new Cat(); cv.name = "Recordings"; cv.rec = true; cv.count = recCount(); cats.add(cv);
        List<Cat> gl = new ArrayList<>();
        for (Map.Entry<String, Integer> e : groups.entrySet()) { Cat g = new Cat(); g.name = e.getKey(); g.group = e.getKey(); g.count = e.getValue(); gl.add(g); }
        // your own order of the groups (hold OK on one › Move): the ones you've placed first, in your order;
        // groups you haven't placed (new in the playlist) keep the playlist's order after them
        final Map<String, Integer> rank = new HashMap<>();
        JSONArray ord = pref("liveCatOrder");
        for (int i = 0; i < ord.length(); i++) if (!rank.containsKey(ord.optString(i))) rank.put(ord.optString(i), i);
        if (!rank.isEmpty()) {
            final Map<Cat, Integer> nat = new HashMap<>();
            for (int i = 0; i < gl.size(); i++) nat.put(gl.get(i), i);
            java.util.Collections.sort(gl, new java.util.Comparator<Cat>() { @Override public int compare(Cat x, Cat y) {
                Integer rx = rank.get(x.group), ry = rank.get(y.group);
                if (rx != null && ry != null) return rx - ry;
                if (rx != null) return -1;
                if (ry != null) return 1;
                return nat.get(x) - nat.get(y);
            } });
        }
        cats.addAll(gl);
        String saved = prefStr("liveCat");
        cat = 2;
        if (saved != null) for (int i = 0; i < cats.size(); i++) if (cats.get(i).name.equals(saved)) cat = i;
        buildList();
        String last = prefStr("liveLast");
        row = 0;
        if (last != null) for (int i = 0; i < list.size(); i++) if (list.get(i).u.equals(last)) row = i;
        if (prevCh == null) { String pu = prefStr("livePrev"); if (pu != null) for (Ch x : chans) if (x.u.equals(pu)) { prevCh = x; break; } }
        prog.clear();
        loaded = true;
        drawCats();
    }

    private final Map<String, Ch> byUrl = new HashMap<>();
    private void buildList() {
        Cat c = cat < cats.size() ? cats.get(cat) : null;
        list = new ArrayList<>();
        if (c == null) return;
        Set<String> hid = hiddenCats();
        if (byUrl.size() != chans.size()) { byUrl.clear(); for (Ch x : chans) byUrl.put(x.u, x); }
        Map<String, Ch> byU = byUrl;
        cats.get(0).count = 0; for (Ch x : chans) if (favs.contains(x.u)) cats.get(0).count++;
        int rc = 0; for (String u : recent) if (byU.containsKey(u)) rc++;
        cats.get(1).count = rc;
        if (c.fav) { for (Ch x : chans) if (favs.contains(x.u)) list.add(x); }
        else if (c.recent) { for (String u : recent) { Ch x = byU.get(u); if (x != null) list.add(x); } }
        else if (c.all) { for (Ch x : chans) if (!hid.contains(x.g)) list.add(x); }
        else { for (Ch x : chans) if (x.g.equals(c.group)) list.add(x); }
    }

    /** the guide starts half an hour before the current half hour and reaches 12 hours ahead */
    private boolean rebase() {
        long b = floor(System.currentTimeMillis()) - SLOT;
        if (b == base) return false;
        base = b;
        prog.clear();
        return true;
    }
    private static long floor(long ms) { return ms / SLOT * SLOT; }
    private long end() { return base + HOURS * 3600000L; }

    /** programmes for a channel (null while the guide is still loading in the background) */
    @SuppressWarnings("unchecked")
    private Object[] progs(Ch ch) {
        if (ch == null) return null;
        Object[] p = prog.get(ch.k);
        if (p != null) return p;
        if (!h.bridge.live.epgReady()) { h.bridge.warmEpg(); return null; }
        List<long[]> tt = new ArrayList<>(); List<String[]> xx = new ArrayList<>();
        h.bridge.live.progs(ch.k, base, end() + SLOT, tt, xx);
        p = new Object[] { tt, xx };
        prog.put(ch.k, p);
        return p;
    }
    /** {start, end, index} of the programme at time t, or null */
    @SuppressWarnings("unchecked")
    private long[] progAt(Ch ch, long at) {
        Object[] p = progs(ch);
        if (p == null) return null;
        List<long[]> tt = (List<long[]>) p[0];
        for (int i = 0; i < tt.size(); i++) { long[] se = tt.get(i); if (se[0] <= at && at < se[1]) return new long[] { se[0], se[1], i }; }
        return null;
    }
    @SuppressWarnings("unchecked")
    private long[] nextAfter(Ch ch, long at) {
        Object[] p = progs(ch);
        if (p == null) return null;
        List<long[]> tt = (List<long[]>) p[0];
        for (int i = 0; i < tt.size(); i++) { long[] se = tt.get(i); if (se[0] >= at) return new long[] { se[0], se[1], i }; }
        return null;
    }
    @SuppressWarnings("unchecked")
    private String[] progText(Ch ch, long[] at) {
        Object[] p = progs(ch);
        if (p == null || at == null) return new String[] { "", "" };
        List<String[]> xx = (List<String[]>) p[1];
        int i = (int) at[2];
        return i < xx.size() ? xx.get(i) : new String[] { "", "" };
    }

    /** guide data changed: the playlist was refreshed (or the guide finished loading) */
    private boolean prepDone;
    void changed(boolean playlist) {
        prog.clear();
        if (playlist && !prepDone) {
            // read the new list in the background first, then come back here
            prepare(h.bridge, main(), new Runnable() { @Override public void run() { prepDone = true; try { changed(true); } finally { prepDone = false; } } });
            return;
        }
        if (playlist) {
            String cur = cat < cats.size() ? cats.get(cat).name : null;
            String u = row < list.size() ? list.get(row).u : null;
            load();
            if (cur != null) for (int i = 0; i < cats.size(); i++) if (cats.get(i).name.equals(cur)) cat = i;
            buildList();
            row = 0;
            if (u != null) for (int i = 0; i < list.size(); i++) if (list.get(i).u.equals(u)) row = i;
            progress = "";
        }
        if (!shown) return;
        if (playlist) guide.build(); else guide.invalidate();     // (the guide arriving doesn't move the view)
        drawAll();
        if (full) drawOsd();
    }
    /** a channel is on full screen (a refresh in the background waits for that to end) */
    boolean watching() { return shown && full && playing != null; }
    boolean fullNow() { return full; }
    String playingUrl() { return playing == null ? "" : playing.u; }
    /** the game on the channel playing full screen, as found for Live stats ("" if none) */
    private void pushWatching() { try { h.bridge.watchingGame = watchingGameId(); } catch (Throwable ignored) { } }
    String watchingGameId() { JSONObject g = watchGame; return full && playing != null && recPlay == null && g != null ? g.optString("id") : ""; }
    void progressMsg(String m) { progress = m; if (shown) drawEmpty(); }

    static String chNum(Ch ch) { String c = ch.c == null ? "" : ch.c.trim(); return c.matches("\\d+(\\.\\d+)?") ? c : String.valueOf(ch.i + 1); }

    private final SimpleDateFormat f24 = new SimpleDateFormat("HH:mm", Locale.US), f12 = new SimpleDateFormat("h:mm a", Locale.US),
            fDay = new SimpleDateFormat("yyyyMMdd", Locale.US), fWd = new SimpleDateFormat("EEE", Locale.US);
    private final Date tmpDate = new Date();
    private String fmt(long ms) {
        tmpDate.setTime(ms);
        return ("24".equals(h.S.optString("clock", "12")) ? f24 : f12).format(tmpDate);
    }
    private String fmtShort(long ms) { return fmt(ms).replace(" AM", "").replace(" PM", ""); }
    private static String dur(long ms) { long m = Math.round(ms / 60000.0); return m < 60 ? m + " min" : (m / 60) + " h" + (m % 60 != 0 ? " " + (m % 60) + " min" : ""); }

    // reminders (kept by the app: Reminders)
    private boolean remHas(String u, long at) { return Reminders.has(h, u, at); }
    /** remind me (or, if already set, cancel it): from the guide, Search, Sports */
    void toggleReminder(String u, long at, long end, String title) {
        if (Reminders.has(h, u, at)) { Reminders.remove(h, Reminders.id(u, at), false); return; }
        if (!loaded) load();
        Ch c = byUrl.get(u);
        if (c == null) for (Ch x : chans) if (x.u.equals(u)) c = x;
        if (c == null) { h.toast("That channel isn't in your playlist any more"); return; }
        Reminders.add(h, c.u, c.n, chNum(c), at, end, title);
    }

    // ================================================================== drawing

    private void drawAll() {
        drawIdle();
        /* full screen: the guide stays hidden under the picture (a playlist or guide refresh finishing in the
           background used to bring it back, see-through over the channel) — it's brought up again on leaving */
        if (full) { guide.setVisibility(INVISIBLE); recView.setVisibility(INVISIBLE); info.setVisibility(INVISIBLE); return; }
        drawCats();
        boolean e = drawEmpty();
        boolean rc = recCat();
        guide.setVisibility(e || rc ? INVISIBLE : VISIBLE);
        recView.setVisibility(!e && rc ? VISIBLE : INVISIBLE);
        if (rc) recView.rebuild();
        info.setVisibility(e ? INVISIBLE : VISIBLE);
        guide.invalidate();
        drawInfo();
    }

    private boolean drawEmpty() {
        JSONObject inf = liveInfo();
        String msg = "";
        if (h.bridge.liveRefreshing() && chans.isEmpty()) msg = (progress.isEmpty() ? "Loading channels…" : progress);
        else if (inf.optString("m3u").isEmpty()) msg = "Add your IPTV playlist\n\nOpen Settings (the gear, top right) → Live TV playlist and paste your M3U link.\nPress OK to open Settings now.";
        else if (chans.isEmpty()) msg = "No channels yet\n\n" + (inf.optString("status").isEmpty() ? "Press OK to load your playlist." : inf.optString("status"));
        else if (list.isEmpty()) {
            Cat c = cats.get(cat);
            msg = c.fav ? "No favorites yet\n\nIn the guide, hold OK on a channel and choose Add to Favorites."
                : c.recent ? "Nothing watched yet\n\nChannels you watch show up here." : "Nothing in this category";
        }
        empty.setText(msg);
        empty.setVisibility(msg.isEmpty() ? GONE : VISIBLE);
        return !msg.isEmpty();
    }

    private Boolean catsOpen = null;
    private void drawCats() {
        catView.reveal();
        catView.invalidate();
        boolean open = zone.equals("cats") && shown;
        if (catsOpen == null || open != catsOpen) { catsOpen = open;
        if (open) catsPanel.setVisibility(VISIBLE);
        catsPanel.animate().translationX(open ? 0 : -h.px(3)).alpha(open ? 1f : 0f).setDuration(open ? 380 : 260).setInterpolator(TileView.EASE)
                .withEndAction(new Runnable() { @Override public void run() { if (!Boolean.TRUE.equals(catsOpen)) catsPanel.setVisibility(INVISIBLE); } }).start();
        // the guide steps aside to the right while the list is out
        float gx = open ? h.px(GUIDE_X + CAT_W + 1.6f) : 0;
        guide.animate().translationX(gx).setDuration(400).setInterpolator(TileView.EASE).start();
        recView.animate().translationX(gx).setDuration(400).setInterpolator(TileView.EASE).start();
        guideDim.animate().translationX(gx).alpha(open ? 1f : 0f).setDuration(400).setInterpolator(TileView.EASE).start();
        guide.invalidate(); }
    }

    /** A soft shadow or glow around a rounded box, blurred once into a small picture and then stretched to any
        size (nine pieces). Live blurs (setShadowLayer) get re-made every frame when the box's size or the blur
        changes, which is what made the guide stutter while scrolling. */
    static final class Soft {
        final Bitmap bm; final int pad, k; final Rect src = new Rect(); final RectF dst = new RectF();
        final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        Soft(float rad, float blur, int color) {
            pad = (int) Math.ceil(blur * 1.15f) + 1; k = pad + (int) Math.ceil(rad) + 1;
            int n = 2 * k + 2;
            bm = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            Paint q = new Paint(Paint.ANTI_ALIAS_FLAG); q.setColor(color);
            if (blur > 0) q.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
            c.drawRoundRect(new RectF(pad, pad, n - pad, n - pad), rad, rad, q);
        }
        /** around box r (shifted down by dy), at the given strength 0..1 */
        void draw(Canvas c, RectF r, float dy, float a) {
            if (a <= 0) return;
            p.setAlpha(Math.round(255 * Math.min(1f, a)));
            float L = r.left - pad, T = r.top - pad + dy, Rr = r.right + pad, B = r.bottom + pad + dy;
            float kx = Math.min(k, (Rr - L) / 2), ky = Math.min(k, (B - T) / 2);
            int n = bm.getWidth();
            int[] sx = {0, k, n - k, n}, sy = sx;
            float[] dx = {L, L + kx, Rr - kx, Rr}, dyy = {T, T + ky, B - ky, B};
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
                if (dx[i + 1] <= dx[i] || dyy[j + 1] <= dyy[j]) continue;
                src.set(sx[i], sy[j], sx[i + 1], sy[j + 1]); dst.set(dx[i], dyy[j], dx[i + 1], dyy[j + 1]);
                c.drawBitmap(bm, src, dst, p);
            }
        }
    }

    /** the categories, drawn as one view: rows like the guide's tiles, kept inside its own box */
    private final class CatView extends View {
        private float scroll, target;
        private ValueAnimator anim;
        private final float itemPx = 3.9f * R, rowH = 3.5f * R, padX = .9f * R, padY = .5f * R, rad = 1.2f * R;
        private final TextPaint tn = new TextPaint(Paint.ANTI_ALIAS_FLAG), tc = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();
        CatView(android.content.Context c) {
            super(c);
            tn.setTypeface(h.fMed); tn.setTextSize(1.25f * R);
            tc.setTypeface(h.fMed); tc.setTextSize(1f * R); tc.setTextAlign(Paint.Align.RIGHT);
            glow.setColor(0xFF34333D);
            ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(Math.max(2, .14f * R)); ring.setColor(0xD9FFFFFF);
        }
        private int liftCat = -1; private float lift = 1f; private ValueAnimator liftAnim;
        private final Soft shade = new Soft(1.2f * R, 1.8f * R, 0xB3000000);
        private void startLift() {
            lift = 0f;
            if (liftAnim != null) liftAnim.cancel();
            liftAnim = ValueAnimator.ofFloat(0f, 1f);
            liftAnim.setDuration(200); liftAnim.setInterpolator(TileView.EASE);
            liftAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { @Override public void onAnimationUpdate(ValueAnimator a) { lift = (Float) a.getAnimatedValue(); invalidate(); } });
            liftAnim.start();
        }
        /** keep the chosen category in view (smoothly) */
        void reveal() {
            float viewH = getHeight() > 0 ? getHeight() : (GUIDE_H - CAT_TOP + .5f) * R;
            float top = padY + cat * itemPx, to = target;
            if (top - itemPx < to) to = top - itemPx;
            else if (top + 2 * itemPx > to + viewH) to = top + 2 * itemPx - viewH;
            to = Math.max(0, Math.min(to, cats.size() * itemPx + 2 * padY - viewH));
            if (to == target) return;
            target = to;
            if (anim != null) anim.cancel();
            final float from = scroll;
            anim = ValueAnimator.ofFloat(0, 1);
            anim.setDuration(220); anim.setInterpolator(TileView.EASE);
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { @Override public void onAnimationUpdate(ValueAnimator a) {
                scroll = from + (target - from) * (Float) a.getAnimatedValue(); invalidate();
            } });
            anim.start();
        }
        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), hh = getHeight();
            c.clipRect(0, 0, w, hh);
            int first = Math.max(0, (int) ((scroll - padY) / itemPx) - 1), last = Math.min(cats.size() - 1, (int) ((scroll + hh) / itemPx) + 1);
            boolean inCats = zone.equals("cats");
            if (!inCats) liftCat = -1;
            for (int pass = 0; pass < 2; pass++) for (int i = first; i <= last; i++) {
                // the one you're on is drawn last, so it sits over the rows next to it
                if ((pass == 1) != (i == cat && inCats)) continue;
                Cat ct = cats.get(i);
                float y = padY + i * itemPx - scroll;
                boolean cur = i == cat, f = cur && inCats;
                rf.set(padX, y, w - padX, y + rowH);
                if (f) {
                    // raised, like the guide's show you're on (it eases up each time you move)
                    if (liftCat != i) { liftCat = i; startLift(); }
                    float up = .22f * R * lift;
                    rf.inset(-.45f * R * lift, -.28f * R * lift); rf.offset(0, -up); y -= up;
                    shade.draw(c, rf, (.2f + .7f * lift) * R, .3f + .7f * lift);
                    float cr = rad;
                    c.drawRoundRect(rf, cr, cr, glow);
                    ring.setColor(moving ? 0xFFFFC23D : 0xD9FFFFFF);
                    float o = ring.getStrokeWidth() / 2; rf.inset(o, o); c.drawRoundRect(rf, Math.max(0, cr - o), Math.max(0, cr - o), ring); rf.inset(-o, -o);
                } else {
                    fill.setColor(cur ? 0x38FF453A : 0x14FFFFFF); c.drawRoundRect(rf, rad, rad, fill);
                }
                float cy = y + rowH / 2;
                boolean mv = f && moving;
                String cnt = mv ? "▲▼" : String.valueOf(ct.count);
                tc.setColor(mv ? 0xFFFFC23D : f ? 0xB3FFFFFF : 0x8CFFFFFF);
                c.drawText(cnt, w - padX - 1.2f * R, cy + tc.getTextSize() * .35f, tc);
                float avail = w - 2 * padX - 2.4f * R - tc.measureText(cnt) - .8f * R;
                tn.setColor(f || cur ? Color.WHITE : 0xD1FFFFFF);
                String name = (ct.fav ? "★ " : ct.rec ? "● " : "") + ct.name;
                c.drawText(TextUtils.ellipsize(name, tn, Math.max(1, avail), TextUtils.TruncateAt.END).toString(), padX + 1.2f * R, cy + tn.getTextSize() * .35f, tn);
            }
        }
    }

    private void drawInfo() {
        drawIdle();
        if (recCat()) { drawRecInfo(); return; }
        Ch ch = row < list.size() ? list.get(row) : null;
        if (ch == null) { info.setVisibility(INVISIBLE); return; }
        long now = System.currentTimeMillis();
        long[] p = progAt(ch, t);
        String[] tx = progText(ch, p);
        boolean onNow = p != null && p[0] <= now && now < p[1];
        boolean prev = !"external".equals(h.S.optString("livePlayer")) && h.S.optBoolean("livePreview", true);
        boolean playingThis = playing != null && playing.u.equals(ch.u);
        String more = hasCatchUp(ch) ? "hold OK · More & catch-up" : "hold OK · More";
        iHint.setText(!prev ? "OK · Watch  ·  " + more
                : playingThis ? "OK · Full screen  ·  " + more : "OK · Preview  ·  OK again · Full screen  ·  " + more);
        iChan.setText((onNow ? "● ON NOW   " : "") + chNum(ch) + "  ·  " + ch.n + (ch.g.isEmpty() ? "" : "  ·  " + ch.g) + (hasCatchUp(ch) ? "  ·  ⟲ CATCH-UP " + ch.cd + (ch.cd == 1 ? " DAY" : " DAYS") : ""));
        iTitle.setText(p != null ? (tx[0].isEmpty() ? "Untitled" : tx[0]) : ch.n);
        String when = "";
        if (p != null) {
            if (p[0] <= now && now < p[1]) when = fmt(p[0]) + " – " + fmt(p[1]) + " · " + Math.max(1, Math.round((p[1] - now) / 60000.0)) + " min left";
            else if (p[0] > now) when = fmt(p[0]) + " – " + fmt(p[1]) + " · starts in " + dur(p[0] - now);
            else when = fmt(p[0]) + " – " + fmt(p[1]);
        }
        iWhen.setText(when.isEmpty() ? "No guide information" : when);
        float pr = p != null && p[0] <= now && now < p[1] ? (now - p[0]) / (float) (p[1] - p[0]) : -1;
        iBar.setVisibility(pr >= 0 ? VISIBLE : GONE);
        if (pr >= 0) { LayoutParams lp = (LayoutParams) iBarFill.getLayoutParams(); lp.width = Math.round(h.px(22) * pr); iBarFill.setLayoutParams(lp); }
        String dsc = p != null ? tx[1] : ""; iDesc.setText(dsc); iDesc.setVisibility(dsc.trim().isEmpty() ? GONE : VISIBLE);
    }

    // ================================================================== keys (from NativeHome)

    private long lastKeyAt;

    boolean move(char d) {
        long now = SystemClock.uptimeMillis();
        if (now - lastKeyAt < 60) return true;      // held keys: not faster than the screen can follow
        lastKeyAt = now;
        if (full && statsView.shown) { statsView.key(d); return true; }
        if (full && barFocus && barShown()) { barKey(d); return true; }
        if (full) { playerKey(d); if (barFocus) showOsd(); return true; }
        if (zone.equals("cats")) { catsKey(d); return true; }
        if (recCat()) { recView.key(d); return true; }
        guideKey(d);
        return true;
    }

    void ok() {
        if (full && statsView.shown) { statsView.close(); return; }
        if (full) {
            if (zap) { zapPick(); return; }
            // OK: the toolbar (Play/Pause chosen); OK on it presses the chosen button
            if (barShown() && barFocus && barRow == 0) { if (scrubbing) commitScrub(); else togglePause(); showOsd(); }
            else if (barShown() && barFocus) { List<String[]> b = buttons(); if (barSel < b.size()) press(b.get(barSel)[0]); showOsd(); }
            else barFocusOn();
            return;
        }
        if (drawEmpty()) {
            JSONObject inf = liveInfo();
            if (inf.optString("m3u").isEmpty()) h.page("openSettings();typeof lvFocusSettingsRow==='function'&&lvFocusSettingsRow('playlist')");
            else if (chans.isEmpty()) { progress = "Loading channels…"; h.bridge.liveRefresh(); h.toast("Refreshing Live TV…"); drawEmpty(); }
            else if (zone.equals("guide")) { zone = "cats"; drawCats(); }
            else { zone = "guide"; drawCats(); }
            return;
        }
        if (moving) { moveDone(); return; }
        if (zone.equals("cats")) { catSettle(); zone = "guide"; drawCats(); if (recCat()) drawInfo(); return; }
        if (recCat()) { recView.ok(); return; }
        Ch ch = row < list.size() ? list.get(row) : null;
        if (ch == null) return;
        boolean previews = !"external".equals(h.S.optString("livePlayer")) && h.S.optBoolean("livePreview", true);
        // OK plays the channel in the preview; OK again on it goes full screen
        if (!previews || (playing != null && playing.u.equals(ch.u))) play(ch, true);
        else play(ch, false);
    }

    /* a finger (phones): "ok" when OK should act on what was tapped (a channel: it plays; a category: it opens;
       anything while watching full screen: the controls), "done" when choosing it was all, null for nothing */
    String focusAt(float x, float y) {
        if (full && !zap && !statsView.shown && barShown()) {
            // the bar's up: a tap on a button presses it; elsewhere the bar goes away
            float[] l = h.local(controls, x, y);
            float w = controls.getWidth(), th = (barFocus && barRow == 0 ? .62f : barFocus ? .42f : .3f) * R;
            float cy = 3f * R + th + 2f * R + 3.6f * R, mid = w / 2f;
            List<String[]> bs = buttons();
            for (int i = 0; i < bs.size(); i++) {
                String id = bs.get(i)[0];
                float bx = id.equals("back") ? mid - 6 * R : id.equals("play") ? mid : id.equals("fwd") ? mid + 6 * R : id.equals("more") ? w - 1.9f * R : w - 5f * R - 3.5f * R;
                if (Math.abs(l[0] - bx) < (id.equals("back") || id.equals("play") || id.equals("fwd") || id.equals("more") ? 2.6f : 4.2f) * R && Math.abs(l[1] - cy) < 2.6f * R) {
                    barFocus = true; barRow = 1; barSel = i; press(id); showOsd(); return "done";
                }
            }
            barFocus = false; barRow = 1; main().removeCallbacks(osdHide); osd.animate().alpha(0f).translationY(h.px(2)).setDuration(250).start();
            return "done";
        }
        if (full || zap) return "ok";
        if (empty.getVisibility() == VISIBLE) return "ok";
        if (catsPanel.getVisibility() == VISIBLE && catsPanel.getAlpha() > .5f && h.hit(catView, x, y, 0)) {
            float ly = h.local(catView, x, y)[1];
            int i = (int) Math.floor((ly + catView.scroll - catView.padY) / catView.itemPx);
            if (i < 0 || i >= cats.size()) return null;
            cat = i; zone = "cats";
            return "ok";
        }
        if (recCat()) return h.hit(recView, x, y, 0) ? "ok" : null;
        if (!h.hit(guide, x, y, 0) || list.isEmpty()) return null;
        float ly = h.local(guide, x, y)[1];
        if (ly < guide.head) return null;
        int i = (int) Math.floor((ly - guide.head + guide.sy) / guide.rowPx);
        if (i < 0 || i >= list.size()) return null;
        if (!zone.equals("guide")) { zone = "guide"; drawCats(); }
        if (i != row) { row = i; afterRowMove(); }
        return "ok";
    }
    void hold() {
        if (full && recPlay != null) { recMoreMenu(); return; }
        if (full) { if (playing != null && !zap) playerMenu(); return; }
        if (zone.equals("cats")) { if (moving) moveDone(); else catMenu(); return; }
        if (recCat()) { recView.menu(); return; }
        channelMenu();
    }

    boolean back() {
        if (full && statsView.shown) { statsView.close(); return true; }
        if (zap) { zapClose(); return true; }
        if (full && barShown() && barFocus && scrubbing) { scrubbing = false; main().removeCallbacks(scrubCommit); controls.invalidate(); return true; }   // (Back: don't jump)
        if (full && barShown()) { barFocus = false; barRow = 1; main().removeCallbacks(osdHide); osd.animate().alpha(0f).translationY(h.px(2)).setDuration(250).start(); return true; }
        if (full) { exitFull(); return true; }
        if (moving) { moveDone(); return true; }
        if (zone.equals("cats")) catSettle();
        if (zone.equals("cats") && (!list.isEmpty() || recCat())) { zone = "guide"; drawCats(); return true; }
        return false;
    }

    private void catsKey(char d) {
        if (moving) { moveKey(d); return; }
        if (d == 'l') { catSettle(); h.leftEdge(); return; }       // (the categories are the leftmost: then the menu)
        if (d == 'u') { if (cat == 0) return; cat--; catChanged(); }
        else if (d == 'd') { if (cat < cats.size() - 1) { cat++; catChanged(); } }
        else if (d == 'r') { catSettle(); if (recCat()) { zone = "guide"; drawCats(); drawInfo(); } else if (!list.isEmpty()) { zone = "guide"; drawCats(); guide.reveal(true); schedulePreview(); } }
    }
    /* Moving through the categories only moves the highlight; the channel list, the guide and the
       remembered choice are worked out once the remote settles on one. */
    private boolean catPending;
    private final Runnable catBuild = new Runnable() { @Override public void run() {
        catPending = false;
        buildList();
        row = 0;
        t = System.currentTimeMillis();
        if (cat < cats.size()) putPref("liveCat", JSONObject.quote(cats.get(cat).name));
        guide.build(); drawAll();
    } };
    private void catChanged() {
        drawCats();
        catPending = true;
        main().removeCallbacks(catBuild);
        main().postDelayed(catBuild, 180);
    }
    private void catSettle() { if (catPending) { main().removeCallbacks(catBuild); catBuild.run(); } }

    private void guideKey(char d) {
        if (list.isEmpty()) { if (d == 'l') { zone = "cats"; drawCats(); } else if (d == 'u') h.enterTopFromVod(); return; }
        Ch ch = list.get(row);
        long now = System.currentTimeMillis();
        long[] p = progAt(ch, t);
        if (d == 'u') { if (row == 0) { h.enterTopFromVod(); return; } row--; afterRowMove(); }
        else if (d == 'd') { if (row < list.size() - 1) { row++; afterRowMove(); } }
        else if (d == 'r') {
            long nt = p != null ? p[1] : t + SLOT;
            long[] nx = nextAfter(ch, t + 1);
            if (p == null && nx != null && nx[0] < nt) nt = nx[0];
            if (nt >= end()) return;
            t = nt;
            guide.invalidate(); drawInfo(); guide.reveal(true);
        } else if (d == 'l') {
            long curStart = p != null ? p[0] : t;
            if (curStart <= now) { zone = "cats"; t = now; drawCats(); guide.reveal(true); return; }
            t = Math.max(now, curStart - 1);
            long[] pp = progAt(ch, t);
            if (pp != null) t = Math.max(pp[0], now);
            guide.invalidate(); drawInfo(); guide.reveal(true);
        }
    }
    private void afterRowMove() {
        long now = System.currentTimeMillis();
        if (t < now) t = now;
        guide.reveal(true);
        guide.invalidate();
        drawInfo();
        schedulePreview();
    }

    // ================================================================== menus

    private void channelMenu() {
        if (row >= list.size()) return;
        final Ch ch = list.get(row);
        final long[] pr = progAt(ch, t);
        final String[] tx = progText(ch, pr);
        final boolean future = pr != null && pr[0] > System.currentTimeMillis();
        final boolean fav = favs.contains(ch.u);
        List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
        if (future) {
            final boolean has = remHas(ch.u, pr[0]);
            l.add(has ? "Cancel reminder" : "Remind me at " + fmt(pr[0]));
            a.add(new Runnable() { @Override public void run() {
                toggleReminder(ch.u, pr[0], pr[1], tx[0]);
                main().postDelayed(new Runnable() { @Override public void run() { guide.invalidate(); } }, 400);
            } });
        }
        recItems(ch, pr != null ? pr[0] + 1000 : System.currentTimeMillis(), l, a);
        catchUpItems(ch, pr, tx, l, a);
        l.add(future ? "Watch this channel now" : "Watch"); a.add(new Runnable() { @Override public void run() { play(ch, true); } });
        l.add(playing != null && !playing.u.equals(ch.u) ? "Multiview with " + playing.n : "Watch in Multiview");
        a.add(new Runnable() { @Override public void run() { multiview(ch, playing != null && !playing.u.equals(ch.u) ? playing : null); } });
        if (h.linkOn) { l.add("Watch on your other TV"); a.add(new Runnable() { @Override public void run() {
            h.page("lkSend({type:'open',what:'channel',u:" + JSONObject.quote(ch.u) + ",n:" + JSONObject.quote(ch.n) + "}).then(r=>{if(r)toast('Sent to '+lkTvName())})");
        } }); }
        l.add(fav ? "Remove from Favorites" : "Add to Favorites"); a.add(new Runnable() { @Override public void run() { toggleFav(ch); } });
        l.add("Open in another player"); a.add(new Runnable() { @Override public void run() { h.bridge.openExternal(ch.u, ch.n); } });
        l.add("Hide this channel"); a.add(new Runnable() { @Override public void run() { hideChannel(ch); } });
        h.showMenu(l, a, null, 1f, future ? tx[0] + " · " + ch.n : ch.n);
    }

    // ---------------------------------------------------------------- your own order of the groups

    /** moving a group: ▲▼ carry it up and down the list, OK / Back put it down (and the order is kept) */
    private boolean moving;
    private static final int FIRST_GROUP = 4;      // (Favorites, Recent, All channels, Recordings stay on top)
    private void moveStart() {
        catSettle();
        moving = true;
        drawCats();
        h.toast("▲ ▼ move “" + cats.get(cat).name + "” · OK when it's where you want it");
    }
    private void moveKey(char d) {
        if (d == 'u' && cat > FIRST_GROUP) { java.util.Collections.swap(cats, cat, cat - 1); cat--; drawCats(); }
        else if (d == 'd' && cat < cats.size() - 1 && cat >= FIRST_GROUP) { java.util.Collections.swap(cats, cat, cat + 1); cat++; drawCats(); }
        else if (d == 'l' || d == 'r') moveDone();
    }
    private void moveDone() {
        moving = false;
        saveOrder();
        drawCats();
        h.toast("Order saved");
    }
    /** the groups as they are now, in order; groups that are hidden keep their places after them */
    private void saveOrder() {
        JSONArray out = new JSONArray(); Set<String> in = new HashSet<>();
        for (int i = FIRST_GROUP; i < cats.size(); i++) { String g = cats.get(i).group; if (g != null && in.add(g)) out.put(g); }
        JSONArray old = pref("liveCatOrder");
        for (int i = 0; i < old.length(); i++) { String g = old.optString(i); if (in.add(g)) out.put(g); }
        putPref("liveCatOrder", out.toString());
        guide.invalidate();
        try { h.page("typeof LV!=='undefined'&&(LV.loaded=false)"); } catch (Throwable ignored) { }
    }
    private void moveTo(int to) {
        Cat c = cats.remove(cat);
        to = Math.max(FIRST_GROUP, Math.min(to, cats.size()));
        cats.add(to, c); cat = to;
        saveOrder(); drawCats();
        h.toast("“" + c.name + "” moved to the " + (to == FIRST_GROUP ? "top" : "bottom"));
    }

    private void hiddenChansMenu() {
        final JSONArray hc = pref("liveHiddenChans");
        List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
        l.add("Show all " + hc.length() + " hidden channels"); a.add(new Runnable() { @Override public void run() {
            putPref("liveHiddenChans", "[]"); reloadKeep(); h.toast("Hidden channels are back");
        } });
        for (int i = hc.length() - 1; i >= 0 && l.size() < 9; i--) {
            final JSONObject o = hc.optJSONObject(i); if (o == null) continue;
            l.add("Show “" + o.optString("n") + "”"); a.add(new Runnable() { @Override public void run() {
                JSONArray cur = pref("liveHiddenChans"), next = new JSONArray();
                for (int k = 0; k < cur.length(); k++) { JSONObject x = cur.optJSONObject(k); if (x != null && !x.optString("u").equals(o.optString("u"))) next.put(x); }
                putPref("liveHiddenChans", next.toString()); reloadKeep(); h.toast("“" + o.optString("n") + "” is back");
            } });
        }
        h.showMenu(l, a, null, 1f, "Hidden channels");
    }
    private void hideChannel(Ch ch) {
        JSONArray hc = pref("liveHiddenChans");
        try { hc.put(new JSONObject().put("u", ch.u).put("n", ch.n)); } catch (Exception ignored) { }
        putPref("liveHiddenChans", hc.toString());
        reloadKeep();
        h.toast("“" + ch.n + "” hidden · hold OK on a category › Hidden channels brings it back");
    }
    /** read the list again (after hiding or bringing back), staying on the same category and near the same row */
    private void reloadKeep() {
        String cn = cat < cats.size() ? cats.get(cat).name : null; int r = row;
        load();
        if (cn != null) for (int i = 0; i < cats.size(); i++) if (cats.get(i).name.equals(cn)) cat = i;
        buildList(); row = Math.max(0, Math.min(r, list.size() - 1));
        guide.build(); drawAll();
    }

    private void catMenu() {
        if (cat >= cats.size()) return;
        final Cat c = cats.get(cat);
        List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
        int nh = pref("liveHiddenChans").length();
        if (c.group == null) {
            if (nh > 0) { l.add("Hidden channels (" + nh + ")"); a.add(new Runnable() { @Override public void run() { hiddenChansMenu(); } }); }
            if (pref("liveCatOrder").length() > 0) { l.add("Put the groups back in the playlist's order"); a.add(new Runnable() { @Override public void run() {
                putPref("liveCatOrder", "[]"); reloadKeep(); h.toast("Groups are back in the playlist's order");
            } }); }
            if (l.isEmpty()) return;
            h.showMenu(l, a, null, 1f, c.name);
            return;
        }
        l.add("Move (▲ ▼, then OK)"); a.add(new Runnable() { @Override public void run() { moveStart(); } });
        if (cat > FIRST_GROUP) { l.add("Move to the top"); a.add(new Runnable() { @Override public void run() { moveTo(FIRST_GROUP); } }); }
        if (cat < cats.size() - 1) { l.add("Move to the bottom"); a.add(new Runnable() { @Override public void run() { moveTo(cats.size()); } }); }
        l.add("Hide this category"); a.add(new Runnable() { @Override public void run() {
            JSONArray hid = pref("liveHiddenCats"); hid.put(c.group); putPref("liveHiddenCats", hid.toString());
            load();
            cat = 2; buildList(); row = 0;
            guide.build(); drawAll();
            h.toast("“" + c.name + "” hidden · Settings → Hidden categories brings it back");
        } });
        if (nh > 0) { l.add("Hidden channels (" + nh + ")"); a.add(new Runnable() { @Override public void run() { hiddenChansMenu(); } }); }
        h.showMenu(l, a, null, 1f, c.name + " · " + c.count + " channels");
    }

    private void toggleFav(Ch ch) {
        if (favs.contains(ch.u)) { favs.remove(ch.u); h.toast("Removed " + ch.n + " from Favorites"); }
        else { favs.add(ch.u); h.toast("Added " + ch.n + " to Favorites"); }
        JSONArray a = new JSONArray(); for (String u : favs) a.put(u);
        putPref("favs", a.toString());
        h.page("LV.favs=new Set(readJSON('favs',[]))");
        boolean viewingFavs = cats.get(cat).fav;
        if (viewingFavs) { buildList(); row = Math.min(row, Math.max(0, list.size() - 1)); guide.build(); }
        else { cats.get(0).count = 0; for (Ch x : chans) if (favs.contains(x.u)) cats.get(0).count++; }
        drawAll();
        if (full) drawOsd();
    }

    /** Multiview is still the page's: hand it the channels (and come back here when it closes) */
    private void multiview(Ch ch, Ch with) {
        stopPlayback();
        if (full) exitFull();
        String js = with != null
            ? "mvOpen([lvChanByUrl(" + JSONObject.quote(with.u) + "),lvChanByUrl(" + JSONObject.quote(ch.u) + ")].filter(Boolean),false)"
            : "mvOpen([lvChanByUrl(" + JSONObject.quote(ch.u) + ")].filter(Boolean),true)";
        h.page(js);
    }

    // ================================================================== catch-up (the provider's archive)

    private static boolean hasCatchUp(Ch ch) { return ch != null && !ch.cu.isEmpty(); }
    /** "Watch from the start" (the show on now) and "Catch up · past shows", for a channel that keeps an archive */
    private void catchUpItems(final Ch ch, final long[] pr, final String[] tx, List<String> l, List<Runnable> a) {
        if (!hasCatchUp(ch)) return;
        long now = System.currentTimeMillis();
        if (pr != null && pr[0] <= now && now < pr[1]) {
            l.add("Watch from the start · " + fmt(pr[0]));
            a.add(new Runnable() { @Override public void run() { playCatchUp(ch, pr[0], pr[1], tx[0], tx[1], null); } });
        }
        l.add("Catch up · past shows (" + ch.cd + (ch.cd == 1 ? " day)" : " days)"));
        a.add(new Runnable() { @Override public void run() { later(new Runnable() { @Override public void run() { catchUpMenu(ch); } }); } });
    }
    /** the channel's past shows, newest first (from the service's own guide for an Xtream archive, else this guide) */
    private void catchUpMenu(final Ch ch) {
        final CatchUp.Xt x = xtFor(ch);
        h.toast("Finding " + ch.n + "'s past shows…");
        new Thread(new Runnable() { @Override public void run() {
            final List<CatchUp.Show> shows = new ArrayList<>();
            String err = "";
            try {
                if (x != null && !ch.sid.isEmpty()) shows.addAll(CatchUp.pastShows(x, ch.sid, ch.cd));
            } catch (Exception e) { err = e.getMessage() == null ? "offline" : e.getMessage(); }
            if (shows.isEmpty()) {
                // (from this guide: what it has of the past)
                long now = System.currentTimeMillis();
                List<long[]> tt = new ArrayList<>(); List<String[]> xx = new ArrayList<>();
                h.bridge.live.loadEpgNow();
                h.bridge.live.progs(ch.k, now - Math.max(1, ch.cd) * 86400000L, now, tt, xx);
                for (int i = tt.size() - 1; i >= 0; i--) { if (tt.get(i)[0] > now) continue; CatchUp.Show s = new CatchUp.Show(); s.start = tt.get(i)[0]; s.end = tt.get(i)[1]; s.title = xx.get(i)[0]; s.desc = xx.get(i)[1]; shows.add(s); }
            }
            final String er = err;
            main().post(new Runnable() { @Override public void run() {
                if (shows.isEmpty()) { h.toast(er.isEmpty() ? "No past shows listed for " + ch.n : "Couldn't get " + ch.n + "'s past shows (" + er + ")"); return; }
                List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
                long now = System.currentTimeMillis();
                for (int i = 0; i < shows.size() && i < 80; i++) {
                    final CatchUp.Show s = shows.get(i);
                    boolean on = s.start <= now && now < s.end;
                    l.add(dayLabel(s.start) + " " + fmt(s.start) + "  ·  " + (s.title.isEmpty() ? "Untitled" : s.title) + (on ? "  (on now)" : ""));
                    a.add(new Runnable() { @Override public void run() { playCatchUp(ch, s.start, s.end, s.title, s.desc, s.local); } });
                }
                h.showMenu(l, a, null, 1f, ch.n + " · catch-up");
            } });
        } }, "catchup").start();
    }
    private CatchUp.Xt xtFor(Ch ch) {
        if (ch.xi < 0) return null;
        List<CatchUp.Xt> sv = h.bridge.live.services();
        return ch.xi < sv.size() ? sv.get(ch.xi) : null;
    }
    private String dayLabel(long ms) {
        java.util.Calendar a = java.util.Calendar.getInstance(), b = java.util.Calendar.getInstance(); b.setTimeInMillis(ms);
        int d = (a.get(java.util.Calendar.YEAR) - b.get(java.util.Calendar.YEAR)) * 366 + a.get(java.util.Calendar.DAY_OF_YEAR) - b.get(java.util.Calendar.DAY_OF_YEAR);
        if (d == 0) return "Today";
        if (d == 1) return "Yesterday";
        tmpDate.setTime(ms);
        return fWd.format(tmpDate);
    }
    /** plays [start, end) from the archive, in the movie player (seek bar, pause, ±10/30 s); Back returns to the guide */
    private void playCatchUp(final Ch ch, final long start, final long end, final String title, final String desc, final String local) {
        final CatchUp.Xt x = xtFor(ch);
        final String first = CatchUp.url(ch.u, ch.cu, ch.cs, x, ch.sid, start, end, local, true);
        if (first == null) { h.toast(ch.n + "'s catch-up address isn't known"); return; }
        // (an Xtream archive also comes as plain MPEG-TS: tried if the HLS form doesn't play)
        final String second = (x != null || "xc".equals(ch.cu)) ? CatchUp.url(ch.u, ch.cu, ch.cs, x, ch.sid, start, end, local, false) : null;
        stopPlayback();
        if (full) exitFull();
        final String label = ch.n + "   ·   " + dayLabel(start) + " " + fmt(start) + " – " + fmt(end);
        Diag.ev("live: catch-up " + ch.n + " " + dayLabel(start) + " " + fmt(start) + " (" + ch.cu + ")");
        try {
            final JSONObject item = new JSONObject().put("name", title == null || title.isEmpty() ? ch.n : title).put("type", "catchup").put("id", "").put("desc", desc == null ? "" : desc);
            final JSONObject hdr = new JSONObject().put("User-Agent", ch.ua.isEmpty() ? LiveTv.DEFAULT_UA : ch.ua);
            final JSONObject stream = new JSONObject().put("url", first).put("_addon", "Catch-up")
                    .put("behaviorHints", new JSONObject().put("proxyHeaders", new JSONObject().put("request", hdr)));
            h.vodPlayer().playOther(item, label, stream, end - start, new VodPlayer.Fallback() {
                boolean again;
                @Override public void failed(String why) {
                    if (!again && second != null) {
                        again = true;
                        try { stream.put("url", second); } catch (Exception ignored) { }
                        Diag.ev("live: catch-up as HLS didn't play (" + why + "), trying MPEG-TS");
                        h.vodPlayer().playOther(item, label, stream, end - start, this);
                        return;
                    }
                    h.toast("That show didn't play from the archive: " + why);
                }
            });
        } catch (Exception e) { h.toast("Couldn't start that show"); }
    }

    // ================================================================== channel keys (CH+ / CH−, numbers, last channel)

    /** CH+ / CH− on the remote: the next channel (full screen: switches; in the guide: moves) */
    void chKey(int dd) {
        if (!shown || list.isEmpty()) return;
        if (full && !zap && recPlay == null) zapStep(dd);
        else if (!full) { guideKey(dd > 0 ? 'd' : 'u'); }
    }
    /** the remote's "last channel" key */
    void lastChannel() {
        if (!shown) return;
        if (full) { prevChannel(); return; }
        Ch p = prevCh != null ? prevCh : lastFull;
        if (p == null) { h.toast("No previous channel yet"); return; }
        playUrl(p.u);
    }
    /** typing a channel number (on remotes that have numbers): it plays once you stop typing */
    private final StringBuilder digits = new StringBuilder();
    void digit(int d) {
        if (!shown || chans.isEmpty()) return;
        if (digits.length() >= 5) digits.setLength(0);
        digits.append(d);
        h.toast("Channel " + digits + "…");
        main().removeCallbacks(digitsGo); main().postDelayed(digitsGo, 1600);
    }
    private final Runnable digitsGo = new Runnable() { @Override public void run() {
        String want = digits.toString(); digits.setLength(0);
        if (want.isEmpty()) return;
        String w = want.replaceFirst("^0+(?=\\d)", "");
        for (Ch c : chans) if (chNum(c).equals(w) || chNum(c).equals(want)) { playUrl(c.u); return; }
        h.toast("No channel " + want);
    } };

    // ================================================================== playing

    private final Runnable previewLater = new Runnable() { @Override public void run() {
        if (!shown || full || !zone.equals("guide") || covered) return;
        Ch ch = row < list.size() ? list.get(row) : null;
        if (ch != null && (playing == null || !playing.u.equals(ch.u))) play(ch, false);
    } };
    private void schedulePreview() {
        main().removeCallbacks(previewLater);
        if (true) return;           // the preview changes only when you choose a channel (OK)
        if ("external".equals(h.S.optString("livePlayer")) || !h.S.optBoolean("livePreview", true)) return;
        Ch ch = row < list.size() ? list.get(row) : null;
        if (ch == null || (playing != null && playing.u.equals(ch.u))) return;
        main().postDelayed(previewLater, 1200);
    }

    private static String kindOf(String url) {
        String u = url.toLowerCase(Locale.US); int q = u.indexOf('?'); if (q >= 0) u = u.substring(0, q);
        if (u.endsWith(".m3u8") || u.endsWith(".m3u")) return "hls";
        if (u.matches(".*\\.(mp4|webm|mkv|mov)$")) return "file";
        return "ts";
    }

    void play(Ch ch, boolean goFull) {
        if (goFull && "external".equals(h.S.optString("livePlayer"))) {
            stopPlayback();
            addRecent(ch);
            putPref("liveLast", JSONObject.quote(ch.u));
            h.bridge.openExternal(ch.u, ch.n);
            return;
        }
        Ch before = playing;
        if (playing == null || !playing.u.equals(ch.u)) startBest(ch);
        putPref("liveLast", JSONObject.quote(ch.u));
        if (goFull) {
            if (full && before != null && !before.u.equals(ch.u)) setPrev(before);
            else if (!full && lastFull != null && !lastFull.u.equals(ch.u)) setPrev(lastFull);
            lastFull = ch;
            addRecent(ch);
            enterFull();
            bedtime();
        }
        place();
        guide.invalidate();
    }

    private void addRecent(Ch ch) {
        recent.remove(ch.u); recent.add(0, ch.u);
        while (recent.size() > 20) recent.remove(recent.size() - 1);
        JSONArray a = new JSONArray(); for (String u : recent) a.put(u);
        putPref("liveRecent", a.toString());
    }

    private final Runnable noPicture = new Runnable() { @Override public void run() { if (playing != null && !ready && recPlay == null) { if (shiftLocal) { Diag.ev("timeshift: the saved copy stalled, going live"); goLive(); } else fail("no picture after 12 s"); } } };

    /* A plain MPEG-TS channel is played as its HLS version first (Xtream-style servers offer both): on
       this TV the TV's decoder plays HLS smoothly, while the plain stream stalled it after a few frames. */
    private boolean hlsFirst, triedAlt, wasReady;
    private int reconnects;
    private static String altHls(String u) { return u.matches("(?i).*\\.ts(\\?.*)?$") ? u.replaceFirst("(?i)\\.ts(\\?|$)", ".m3u8$1") : u + ".m3u8"; }
    private void startBest(Ch ch) {
        String k = kindOf(ch.u);
        hlsFirst = false; triedAlt = false;
        if (k.equals("ts")) { hlsFirst = true; triedAlt = true; start(ch, "hls", altHls(ch.u)); }
        else start(ch, k, null);
    }
    private int swRetries;
    private String lastUrl;
    private void start(Ch ch, String kind, String url) {
        NativePlayer np = h.act.nativePlayer();
        boolean same = playing != null && playing.u.equals(ch.u);
        if (!same) { swRetries = 0; wasReady = false; reconnects = 0; stopShift(); }
        lastUrl = url;
        playing = ch; tried = kind; ready = false;
        if (np == null) { fail("the TV's video player isn't available"); return; }
        if (!same || pvTune.box.getVisibility() == VISIBLE || fsTune.box.getVisibility() == VISIBLE) tune(ch);
        pvErr.setVisibility(GONE); fsErr.setVisibility(GONE);
        showCues("");
        pid++;
        try { np.play(pid, url != null ? url : ch.u, ch.ua == null ? "" : ch.ua, kind.equals("hls") ? "hls" : "prog"); }
        catch (Throwable t) { fail(String.valueOf(t.getMessage())); return; }
        main().removeCallbacks(noPicture);
        main().postDelayed(noPicture, 12000);
        place();
    }

    void stopPlayback() {
        stopShift();
        main().removeCallbacks(previewLater); main().removeCallbacks(noPicture);
        if (playing == null) { place(); return; }
        playing = null; ready = false;
        NativePlayer np = h.act.nativePlayer();
        if (np != null) try { np.stop(); } catch (Throwable ignored) { }
        h.bridge.liveStopStreams();
        pvTune.box.setVisibility(GONE); fsTune.box.setVisibility(GONE);
        pvErr.setVisibility(GONE); fsErr.setVisibility(GONE);
        showCues("");
        place();
        guide.invalidate();
    }

    /** events from the TV's player (ids from ID0 are ours) */
    void event(int id, String ev, String data) {
        if (id != pid || playing == null) return;
        if (ev.equals("ready")) {
            if (full && !shiftLocal) main().postDelayed(new Runnable() { @Override public void run() { startShift(); } }, 1500);
            main().removeCallbacks(noPicture);
            ready = true; stalls = 0;
            wasReady = true; reconnects = 0;     // this way of playing the channel works: keep to it
            pvTune.box.setVisibility(GONE); fsTune.box.setVisibility(GONE);
            pvErr.setVisibility(GONE); fsErr.setVisibility(GONE);
            place();
        } else if (ev.equals("stall")) {
            stalls++;
            ready = false; place();
            main().removeCallbacks(noPicture); main().postDelayed(noPicture, 12000);
        } else if (ev.equals("swdecoder")) {
            /* The TV's own decoder wasn't free (usually the previous channel's is still being let go):
               Android's software decoder can't keep up on this TV, so try again in a moment. */
            if (swRetries < 3) {
                swRetries++;
                final Ch ch = playing; final String k = tried, u = lastUrl;
                main().postDelayed(new Runnable() { @Override public void run() { if (playing == ch) start(ch, k, u); } }, 600L * swRetries);
            }
        } else if (ev.equals("tracks")) {
            applyTracks();
        } else if (ev.equals("cues")) {
            showCues(data);
        } else if (ev.equals("error")) {
            // the saved copy failed to play: back to the channel itself
            if (recPlay != null) { Diag.ev("record: playback failed (" + data + ")"); h.toast("Couldn't play that recording: " + data); if (full) exitFull(); else endRec(); return; }
            if (shiftLocal) { Diag.ev("timeshift: the saved copy failed (" + data + "), going live"); goLive(); return; }
            fail(data);
        }
    }

    /** the provider refusing a stream because the account's streams are all in use */
    static boolean limitError(String msg) {
        String m = String.valueOf(msg).toLowerCase(Locale.US);
        return m.contains("response code: 509") || m.contains("response code: 458") || m.contains("response code: 429") || m.contains("http 509") || m.contains("max connections") || m.contains("too many");
    }
    /** a one-stream account: stop saving the channel (its connection is the one the picture needs) */
    private void connLimitHit() {
        if (oneConnection) return;
        oneConnection = true;
        Diag.ev("live: the provider refused a second stream: pause & rewind off for now so the picture keeps its connection");
        if (shift != null) stopShift();
        shiftWhy = "your provider allows one stream at a time (saving the channel would interrupt the picture)";
    }
    /** a channel that won't play one way is tried the other ways (Xtream services offer HLS too) */
    private void fail(String msg) {
        Diag.ev("live: couldn't play — " + msg);
        Ch ch = playing;
        if (ch == null) return;
        lastErr = String.valueOf(msg);
        /* A channel that was playing and hit a network hiccup reconnects the same way (it isn't the
           stream's format that's at fault), a few times, a little later each time. */
        boolean busy = limitError(msg);
        if (busy) connLimitHit();
        if (wasReady && reconnects < (busy ? 10 : 6)) {
            reconnects++;
            final Ch c = ch; final String k = tried, u = lastUrl;
            ready = false; place();
            main().removeCallbacks(noPicture);
            // (a provider that said "too many streams" needs a moment to let the old one go: longer waits)
            long wait = busy ? Math.min(1500L * reconnects, 6000L) : 700L * reconnects;
            main().postDelayed(new Runnable() { @Override public void run() { if (playing == c) { boolean keep = wasReady; int n = reconnects; start(c, k, u); wasReady = keep; reconnects = n; } } }, wait);
            return;
        }
        if (tried.equals("hls") && hlsFirst) { hlsFirst = false; start(ch, "ts", null); return; }   // no HLS version: the plain stream
        if (tried.equals("ts") && !triedAlt) { triedAlt = true; start(ch, "hls", altHls(ch.u)); return; }
        if (tried.equals("hls") && kindOf(ch.u).equals("hls")) { start(ch, "file", null); return; }
        main().removeCallbacks(noPicture);
        NativePlayer np = h.act.nativePlayer();
        if (np != null) try { np.stop(); } catch (Throwable ignored) { }
        ready = false;
        pvTune.box.setVisibility(GONE); fsTune.box.setVisibility(GONE);
        String m = String.valueOf(msg); if (m.length() > 160) m = m.substring(0, 160);
        pvErr.setText("Couldn't play " + ch.n);
        fsErr.setText("Couldn't play " + ch.n + "\n" + m + "\nHold OK to open it in another player app");
        pvErr.setVisibility(VISIBLE); fsErr.setVisibility(VISIBLE);
        place();
    }

    /** where the picture goes: the preview box, the whole screen, or nowhere */
    private void place() {
        drawIdle();
        NativePlayer np = h.act.nativePlayer();
        boolean pvMode = shown && !full && playing != null && !covered;
        pv.setVisibility(pvMode && !ready ? VISIBLE : pvMode && pvErr.getVisibility() == VISIBLE ? VISIBLE : INVISIBLE);
        bg.setHole(pvMode && ready);
        if (np == null) return;
        try {
            if (playing == null) return;
            float k = h.act.renderScale();
            if (full && !covered) np.layout("fs", 0, 0, 0, 0, 0, 0);
            else if (pvMode) {
                /* While it grows or shrinks (under half a second) the picture is laid out at its grown size and
                   scaled; at rest it sits at its exact size, unscaled (a scaled video plane at rest seemed to
                   upset how the TV puts the screen together: shifted or mirrored pictures, black boxes). */
                if (growing()) {
                    int w = h.px(BIG_W), x = W - w, y = 0, hh = h.px(BIG_H);
                    np.layout("pv", Math.round(x * k), Math.round(y * k), Math.round(w * k), Math.round(hh * k), 0, 0);
                    np.setPvScale((PV_W + (BIG_W - PV_W) * grow) / BIG_W);
                } else {
                    RectF r = pvBox();
                    np.setPvScale(1f);
                    np.layout("pv", Math.round(r.left * k), Math.round(r.top * k), Math.round(r.width() * k), Math.round(r.height() * k), 0, 0);
                }
            } else np.layout("hide", 0, 0, 0, 0, 0, 0);
        } catch (Throwable ignored) { }
    }

    private void tune(Ch ch) {
        rebase();
        long[] pr = progAt(ch, System.currentTimeMillis());
        String title = pr != null ? progText(ch, pr)[0] : "";
        pvTune.set(ch, title); fsTune.set(ch, title);
        pvTune.showNow(); fsTune.showNow();
    }

    private void enterFull() {
        Diag.ev("live: full screen");
        full = true;
        zone = "guide";
        h.updateBars();
        bg.setVisibility(INVISIBLE); info.setVisibility(INVISIBLE); guide.setVisibility(INVISIBLE); empty.setVisibility(GONE);
        catsPanel.setAlpha(0f); catsPanel.setVisibility(INVISIBLE); guideDim.setAlpha(0f); guide.setTranslationX(0); guideDim.setTranslationX(0); catsOpen = null;
        ((View) label.getParent()).setVisibility(INVISIBLE);
        fs.setVisibility(VISIBLE);
        statsBox.setVisibility(stats ? VISIBLE : GONE);
        showOsd();
        place();
        findGame();
        if (ready) startShift();
    }

    private void exitFull() {
        Diag.ev("live: back to the guide");
        if (zap) zapClose();
        statsView.close();
        // the guide's preview is always live: drop the saved copy (going back to live if you'd rewound)
        boolean wasRec = recPlay != null;
        boolean wasBehind = behind() && !wasRec; barFocus = false;
        if (wasRec) endRec();
        stopShift();
        if (wasBehind && playing != null) start(playing, tried, lastUrl);
        full = false;
        pushWatching();
        h.updateBars();
        fs.setVisibility(INVISIBLE);
        osd.animate().cancel(); osd.setAlpha(0f);
        bg.setVisibility(VISIBLE);
        ((View) label.getParent()).setVisibility(VISIBLE);
        if (cats.get(cat).recent) {
            String u = playing != null ? playing.u : null;
            buildList(); guide.build();
            if (u != null) for (int i = 0; i < list.size(); i++) if (list.get(i).u.equals(u)) row = i;
        }
        zone = "guide";
        drawAll();
        guide.reveal(false);
        place();
    }

    // ---- full-screen keys

    private void playerKey(char d) {
        if (zap) { zapKey(d); return; }
        if (recPlay != null) { if (d == 'l') skip(-10000); else if (d == 'r') skip(30000); showOsd(); return; }
        if (d == 'r') { zapOpen(); return; }
        if (d == 'l') { prevChannel(); return; }
        if (d == 'u') { zapStep(-1); return; }
        if (d == 'd') { zapStep(1); return; }
        showOsd();
    }
    private void zapStep(int dd) {
        if (list.isEmpty()) return;
        if (playing != null) setPrev(playing);
        row = (row + dd + list.size()) % list.size();
        t = System.currentTimeMillis();
        play(list.get(row), true);
    }
    /** the channel before this one, kept across restarts (◀ in full screen, or the remote's last-channel key, goes back to it) */
    private void setPrev(Ch c) { prevCh = c; putPref("livePrev", JSONObject.quote(c.u)); }
    private void prevChannel() {
        Ch p = prevCh;
        if (p == null) { showOsd(); h.toast("No previous channel yet"); return; }
        int i = -1; for (int k = 0; k < list.size(); k++) if (list.get(k).u.equals(p.u)) i = k;
        if (i < 0) { cat = 2; buildList(); guide.build(); for (int k = 0; k < list.size(); k++) if (list.get(k).u.equals(p.u)) i = k; }
        row = Math.max(0, i);
        t = System.currentTimeMillis();
        play(row < list.size() ? list.get(row) : p, true);
    }

    // ---- the banner

    private void buildOsd() {
        LinearLayout col = new LinearLayout(getContext()); col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(h.px(5), h.px(7), h.px(5), h.px(2.6f));
        // what's on: the channel's logo, its name and the show, and the show's times on the right
        LinearLayout r = new LinearLayout(getContext());
        r.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout lg = new FrameLayout(getContext());
        oLogo = new ImageView(getContext()); oLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        lg.addView(oLogo, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        oPh = h.text(h.fSemi, 1.8f, Color.WHITE); oPh.setGravity(Gravity.CENTER);
        GradientDrawable pb = new GradientDrawable(); pb.setCornerRadius(.8f * R); pb.setColor(0x1FFFFFFF); oPh.setBackground(pb);
        lg.addView(oPh, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(h.px(6.4f), h.px(4.2f)); ll.rightMargin = h.px(1.6f);
        r.addView(lg, ll);
        LinearLayout b = new LinearLayout(getContext());
        b.setOrientation(LinearLayout.VERTICAL);
        oChan = h.text(h.fMed, 1.05f, 0xB3FFFFFF); oChan.setSingleLine(true); oChan.setEllipsize(TextUtils.TruncateAt.END); b.addView(oChan);
        oTitle = h.text(h.fSemi, 2.1f, Color.WHITE); oTitle.setSingleLine(true); oTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); tl.topMargin = h.px(.15f);
        b.addView(oTitle, tl);
        r.addView(b, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        oWhen = h.text(h.fMed, 1.1f, 0xB3FFFFFF); oWhen.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); wl.leftMargin = h.px(2);
        r.addView(oWhen, wl);
        col.addView(r, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        controls = new Controls(getContext());
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, h.px(12f)); cl.topMargin = h.px(-1.2f);
        col.addView(controls, cl);
        osd.addView(col, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        // (kept for older bits that set them; not shown)
        oNext = h.text(h.fReg, 1f, 0); oHint = h.text(h.fReg, 1f, 0);
        View bar = new FrameLayout(getContext()); oBarFill = new View(getContext()); ((FrameLayout) bar).addView(oBarFill, new LayoutParams(0, 1));
    }

    private final Runnable osdHide = new Runnable() { @Override public void run() {
        if (paused && barFocus) return;                       // (paused: the toolbar stays until you hide it)
        if (scrubbing) commitScrub();
        barFocus = false; barRow = 1;
        osd.animate().alpha(0f).translationY(h.px(2)).setDuration(300).start();
    } };
    private void toggleOsd() { if (osd.getAlpha() > .5f) { main().removeCallbacks(osdHide); osdHide.run(); } else showOsd(); }
    private void showOsd() {
        if (playing == null || zap) return;
        drawOsd();
        osd.animate().alpha(1f).translationY(0).setDuration(300).setInterpolator(TileView.EASE).start();
        main().removeCallbacks(osdHide); main().postDelayed(osdHide, barFocus ? 7000 : 5000);
        main().removeCallbacks(barTick); main().postDelayed(barTick, 500);
    }
    private boolean barShown() { return full && osd.getAlpha() > .5f; }
    private final Runnable barTick = new Runnable() { @Override public void run() {
        if (!full || osd.getAlpha() < .05f) return;
        controls.invalidate();
        main().postDelayed(this, 500);
    } };

    // ================================================================== recordings

    private RecView recView;
    private final Set<String> recMarks = new HashSet<>();
    private JSONObject recPlay;                 // the recording playing full screen (null: a channel)
    private Recorder rec() { return Recorder.get(getContext()); }
    private boolean recCat() { return cat < cats.size() && cats.get(cat).rec; }
    private int recCount() { int n = 0; for (JSONObject r : rec().list()) if (!"failed".equals(r.optString("status")) || Recorder.hasData(r)) n++; return n; }
    /** the recordings changed (a new one, one finished…): marks in the guide, the list, the count */
    private final Runnable recChanged = new Runnable() { @Override public void run() {
        recMarks.clear();
        for (JSONObject r : rec().list()) {
            String st = r.optString("status");
            if (st.equals("scheduled") || st.equals("recording")) recMarks.add(r.optString("u") + "|" + r.optLong("start"));
        }
        for (Cat c : cats) if (c.rec) c.count = recCount();
        if (!shown) return;
        catView.invalidate(); guide.invalidate();
        if (recCat()) { recView.rebuild(); drawInfo(); }
        if (full) controls.invalidate();
    } };
    private JSONObject chJson(Ch ch) {
        JSONObject o = new JSONObject();
        try { o.put("n", ch.n); o.put("u", ch.u); o.put("ua", ch.ua == null ? "" : ch.ua); o.put("k", ch.k); o.put("l", ch.l); } catch (Exception ignored) { }
        return o;
    }
    private Ch chByUrl(String u) { if (byUrl.size() != chans.size()) { byUrl.clear(); for (Ch x : chans) byUrl.put(x.u, x); } return byUrl.get(u); }

    /** record the show on at `at` on this channel (or, with no guide, the next hour) */
    private void recordShow(Ch ch, long at) {
        long now = System.currentTimeMillis();
        long[] p = progAt(ch, at);
        String[] tx = progText(ch, p);
        JSONObject r = p != null
                ? rec().add(chJson(ch), p[0], p[1], tx[0], tx[1], tx.length > 2 ? tx[2] : "", null)
                : rec().add(chJson(ch), now, now + 3600_000L, ch.n, "", "", null);
        if (r == null) { h.toast("Couldn't set that recording"); return; }
        long st = r.optLong("start");
        h.toast(st <= now ? "● Recording “" + r.optString("title") + "” until " + fmt(r.optLong("end")) : "● “" + r.optString("title") + "” will record at " + fmt(st) + " · " + day(st));
        recChanged.run();
    }
    private void recordSeries(Ch ch, String title, String mode) {
        JSONObject r = rec().addRule(chJson(ch), title, mode);
        if (r == null) { h.toast("Couldn't set that up"); return; }
        h.toast(mode.equals("new") ? "● Recording new episodes of “" + title + "” on " + ch.n : "● Recording every episode of “" + title + "” on " + ch.n);
        main().postDelayed(recChanged, 1500);
    }
    private String day(long ms) {
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance(); b.setTimeInMillis(ms);
        if (a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)) return "Today";
        a.add(Calendar.DAY_OF_YEAR, 1);
        if (a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)) return "Tomorrow";
        a.add(Calendar.DAY_OF_YEAR, -2);
        if (a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)) return "Yesterday";
        return new SimpleDateFormat("EEE MMM d", Locale.US).format(new Date(ms));
    }
    private static String size(long b) { return b >= 1L << 30 ? String.format(Locale.US, "%.1f GB", b / (double) (1L << 30)) : (b >> 20) + " MB"; }

    /** the record items for a channel's menu (the show at `at`) */
    private void recItems(final Ch ch, final long at, List<String> l, List<Runnable> a) {
        final long[] p = progAt(ch, at);
        final String[] tx = progText(ch, p);
        long now = System.currentTimeMillis();
        if (p != null && p[1] <= now) return;
        final JSONObject r = rec().at(ch.u, p != null ? Math.max(p[0], now) + 1000 : now);
        if (r == null) {
            l.add(p == null ? "● Record the next hour" : p[0] <= now ? "● Record “" + tx[0] + "” (from now)" : "● Record “" + tx[0] + "”");
            a.add(new Runnable() { @Override public void run() { recordShow(ch, at); } });
        } else {
            final boolean going = "recording".equals(r.optString("status"));
            l.add(going ? "■ Stop recording (keep what's recorded)" : "Cancel this recording");
            a.add(new Runnable() { @Override public void run() { rec().cancel(r.optString("id")); h.toast(going ? "Recording stopped" : "Recording cancelled"); recChanged.run(); } });
        }
        if (p != null && !tx[0].trim().isEmpty()) {
            final JSONObject rule = rec().ruleFor(ch.u, tx[0]);
            if (rule == null) {
                l.add("● Record all new episodes of “" + tx[0] + "”"); a.add(new Runnable() { @Override public void run() { recordSeries(ch, tx[0], "new"); } });
                l.add("● Record every airing of “" + tx[0] + "”"); a.add(new Runnable() { @Override public void run() { recordSeries(ch, tx[0], "all"); } });
            } else {
                l.add("Stop recording “" + tx[0] + "” (series)");
                a.add(new Runnable() { @Override public void run() { rec().removeRule(rule.optString("id")); h.toast("Series recording stopped"); recChanged.run(); } });
            }
        }
    }

    /** the focused recording's details, at the top (where a channel's show is described) */
    private void drawRecInfo() {
        if (full) return;
        JSONObject r = recView.focused();
        info.setVisibility(VISIBLE);
        iBar.setVisibility(GONE);
        if (r == null) {
            iChan.setText("RECORDINGS  ·  " + rec().storageLabel());
            iTitle.setText(rec().list().isEmpty() && rec().rules().isEmpty() ? "Nothing recorded yet" : "Recordings");
            iWhen.setText("");
            iDesc.setText("In the guide, hold OK on a show and choose Record — or record every new episode of a series. While watching, OK opens the toolbar with ● Record.");
            iDesc.setVisibility(VISIBLE); iHint.setText("");
            return;
        }
        if (r.has("mode")) {          // a series rule
            iChan.setText("SERIES  ·  " + r.optString("n"));
            iTitle.setText(r.optString("title"));
            int n = 0; for (JSONObject x : rec().list()) if (x.optString("rule").equals(r.optString("id"))) n++;
            iWhen.setText((r.optString("mode").equals("new") ? "New episodes" : "Every airing") + " · " + n + (n == 1 ? " recording" : " recordings"));
            iDesc.setText("New airings in the guide are scheduled automatically (the guide is checked every few hours)."); iDesc.setVisibility(VISIBLE);
            iHint.setText("hold OK · Stop this series");
            return;
        }
        String st = r.optString("status");
        long s0 = r.optLong("start"), e0 = r.optLong("end");
        iChan.setText((st.equals("recording") ? "● RECORDING NOW   " : st.equals("scheduled") ? "COMING UP   " : st.equals("failed") ? "FAILED   " : "RECORDED   ") + r.optString("n"));
        String ep = r.optString("ep"); String[] ex = ep.split("\t", -1);
        String epName = (ex.length > 2 && !ex[2].isEmpty() ? ex[2] + "  " : "") + (ex.length > 1 ? ex[1] : "");
        iTitle.setText(r.optString("title"));
        String when = day(s0) + " · " + fmt(s0) + " – " + fmt(e0);
        if (r.optLong("bytes") > 0) when += " · " + Math.max(1, r.optLong("secs") / 60) + " min · " + size(r.optLong("bytes"));
        if (!epName.trim().isEmpty()) when = epName.trim() + "  ·  " + when;
        iWhen.setText(when);
        String d = r.optString("desc");
        if (st.equals("failed") || !r.optString("err").isEmpty()) d = "⚠ " + r.optString("err") + (d.isEmpty() ? "" : "\n" + d);
        else if (r.optInt("drops") > 0) d = "The channel dropped " + r.optInt("drops") + (r.optInt("drops") == 1 ? " time" : " times") + " while recording (there may be a skip there).\n" + d;
        if (!Recorder.hasData(r) && !r.optString("dir").isEmpty() && (st.equals("done"))) d = "⚠ The files aren't here — is the USB drive plugged in?\n" + d;
        iDesc.setText(d); iDesc.setVisibility(d.trim().isEmpty() ? GONE : VISIBLE);
        iHint.setText(Recorder.hasData(r) ? (r.optLong("pos") > r.optLong("from") + 30000 ? "OK · Resume  ·  hold OK · More" : "OK · Play  ·  hold OK · More") : "hold OK · More");
    }

    /** the recordings list, in the guide's place: recording now, recorded, coming up, then series */
    private final class RecView extends View {
        private final List<JSONObject> items = new ArrayList<>();
        private final List<String> heads = new ArrayList<>();      // a section heading before item i (or null)
        private int sel; private float scroll, target; private ValueAnimator anim;
        private final float rowH = 5.2f * R, gapH = 3.0f * R, padX = (GUIDE_X) * R;
        private final TextPaint tT = new TextPaint(Paint.ANTI_ALIAS_FLAG), tS = new TextPaint(Paint.ANTI_ALIAS_FLAG), tH = new TextPaint(Paint.ANTI_ALIAS_FLAG), tC = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), raise = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();
        private final Soft shade = new Soft(1.2f * R, 1.8f * R, 0xB3000000);
        RecView(android.content.Context c) {
            super(c);
            tT.setTypeface(h.fSemi); tT.setTextSize(1.35f * R); tT.setColor(Color.WHITE);
            tS.setTypeface(h.fReg); tS.setTextSize(1.02f * R); tS.setColor(0x99FFFFFF);
            tH.setTypeface(h.fSemi); tH.setTextSize(.95f * R); tH.setColor(0x99FFFFFF); tH.setLetterSpacing(.08f);
            tC.setTypeface(h.fSemi); tC.setTextSize(.92f * R); tC.setTextAlign(Paint.Align.CENTER);
            ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(Math.max(2, .14f * R)); ring.setColor(0xD9FFFFFF);
            raise.setColor(0xFF34333D);
        }
        JSONObject focused() { return sel < items.size() && zone.equals("guide") ? items.get(sel) : null; }
        void rebuild() {
            String keep = sel < items.size() ? items.get(sel).optString("id") : null;
            items.clear(); heads.clear();
            List<JSONObject> now = new ArrayList<>(), done = new ArrayList<>(), next = new ArrayList<>();
            for (JSONObject r : rec().list()) {
                String st = r.optString("status");
                if (st.equals("recording")) now.add(r);
                else if (st.equals("scheduled")) next.add(r);
                else if (st.equals("done") || Recorder.hasData(r) || st.equals("failed")) done.add(r);
            }
            java.util.Comparator<JSONObject> newest = new java.util.Comparator<JSONObject>() { @Override public int compare(JSONObject a, JSONObject b) { return Long.compare(b.optLong("start"), a.optLong("start")); } };
            java.util.Comparator<JSONObject> soonest = new java.util.Comparator<JSONObject>() { @Override public int compare(JSONObject a, JSONObject b) { return Long.compare(a.optLong("start"), b.optLong("start")); } };
            java.util.Collections.sort(done, newest); java.util.Collections.sort(next, soonest);
            add("RECORDING NOW", now); add("RECORDED", done); add("COMING UP", next); add("SERIES", rec().rules());
            sel = 0;
            if (keep != null) for (int i = 0; i < items.size(); i++) if (items.get(i).optString("id").equals(keep)) sel = i;
            reveal(false);
            invalidate();
        }
        private void add(String head, List<JSONObject> l) {
            for (int i = 0; i < l.size(); i++) { heads.add(i == 0 ? head : null); items.add(l.get(i)); }
        }
        private float top(int i) { float y = 0; for (int k = 0; k <= i && k < items.size(); k++) { if (heads.get(k) != null) y += gapH; if (k < i) y += rowH; } return y; }
        private void reveal(boolean animate) {
            float viewH = getHeight() > 0 ? getHeight() : GUIDE_H * R;
            float y = top(sel), to = target;
            if (y - gapH < to) to = Math.max(0, y - gapH);
            else if (y + 2 * rowH > to + viewH) to = y + 2 * rowH - viewH;
            if (to == target) return;
            target = to;
            if (anim != null) anim.cancel();
            if (!animate) { scroll = to; return; }
            final float from = scroll;
            anim = ValueAnimator.ofFloat(0, 1); anim.setDuration(200); anim.setInterpolator(TileView.EASE);
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { @Override public void onAnimationUpdate(ValueAnimator a) { scroll = from + (target - from) * (Float) a.getAnimatedValue(); invalidate(); } });
            anim.start();
        }
        void key(char d) {
            if (d == 'l') { zone = "cats"; drawCats(); drawInfo(); invalidate(); return; }
            if (d == 'u') { if (sel == 0) { h.enterTopFromVod(); return; } sel--; }
            else if (d == 'd') { if (sel < items.size() - 1) sel++; }
            else return;
            reveal(true); invalidate(); drawInfo();
        }
        void ok() {
            JSONObject r = focused();
            if (r == null) return;
            if (!r.has("mode") && Recorder.hasData(r)) playRec(r, false);
            else menu();
        }
        void menu() {
            final JSONObject r = focused();
            if (r == null) return;
            List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
            if (r.has("mode")) {
                l.add("Stop this series (keeps what's recorded)");
                a.add(new Runnable() { @Override public void run() { rec().removeRule(r.optString("id")); recChanged.run(); } });
                l.add(r.optString("mode").equals("new") ? "Record every airing instead" : "Record only new episodes");
                a.add(new Runnable() { @Override public void run() {
                    Ch ch = chByUrl(r.optString("u"));
                    rec().removeRule(r.optString("id"));
                    if (ch != null) recordSeries(ch, r.optString("title"), r.optString("mode").equals("new") ? "all" : "new");
                    recChanged.run();
                } });
                h.showMenu(l, a, null, 1f, r.optString("title") + " · series");
                return;
            }
            final String id = r.optString("id"), st = r.optString("status");
            if (Recorder.hasData(r)) {
                l.add("Play from the start"); a.add(new Runnable() { @Override public void run() { playRec(rec().find(id), true); } });
                if (r.optLong("pos") > r.optLong("from") + 30000) { l.add("Resume at " + clock(r.optLong("pos") - r.optLong("from"))); a.add(new Runnable() { @Override public void run() { playRec(rec().find(id), false); } }); }
            }
            if (st.equals("recording")) { l.add("■ Stop recording"); a.add(new Runnable() { @Override public void run() { rec().cancel(id); h.toast("Recording stopped"); } }); }
            if (st.equals("scheduled")) { l.add("Cancel this recording"); a.add(new Runnable() { @Override public void run() { rec().cancel(id); recChanged.run(); } }); }
            final Ch ch = chByUrl(r.optString("u"));
            if (ch != null) { l.add("Watch " + ch.n + " live"); a.add(new Runnable() { @Override public void run() { cat = 2; buildList(); for (int i = 0; i < list.size(); i++) if (list.get(i).u.equals(ch.u)) row = i; guide.build(); drawAll(); play(ch, true); } }); }
            if (!st.equals("scheduled")) {
                l.add("Delete this recording"); a.add(new Runnable() { @Override public void run() {
                    List<String> l2 = new ArrayList<>(); List<Runnable> a2 = new ArrayList<>();
                    l2.add("Delete “" + r.optString("title") + "”"); a2.add(new Runnable() { @Override public void run() { rec().delete(id); h.toast("Deleted"); recChanged.run(); } });
                    l2.add("Keep it"); a2.add(new Runnable() { @Override public void run() { } });
                    later(new Runnable() { @Override public void run() { h.showMenu(l2, a2, null, 1f, "Delete " + size(r.optLong("bytes")) + "?"); } });
                } });
            }
            h.showMenu(l, a, null, 1f, r.optString("title"));
        }
        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), hh = getHeight();
            float x0 = padX, x1 = w - 3 * R;
            boolean f0 = zone.equals("guide");
            if (items.isEmpty()) {
                tS.setTextAlign(Paint.Align.LEFT);
                c.drawText("Nothing here yet — hold OK on a show in the guide and choose ● Record.", x0, 3 * R, tS);
                return;
            }
            c.save(); c.clipRect(0, 0, w, hh);
            float y = -scroll;
            for (int i = 0; i < items.size(); i++) {
                if (heads.get(i) != null) { y += gapH; c.drawText(heads.get(i), x0 + .4f * R, y - .9f * R, tH); }
                if (y + rowH < 0) { y += rowH; continue; }
                if (y > hh) break;
                JSONObject r = items.get(i);
                boolean f = f0 && i == sel;
                rf.set(x0, y + .25f * R, x1, y + rowH - .25f * R);
                if (f) {
                    rf.inset(-.4f * R, -.2f * R); rf.offset(0, -.18f * R);
                    shade.draw(c, rf, .8f * R, 1f);
                    float cr = R;
                    c.drawRoundRect(rf, cr, cr, raise);
                    float o = ring.getStrokeWidth() / 2; rf.inset(o, o); c.drawRoundRect(rf, Math.max(0, cr - o), Math.max(0, cr - o), ring); rf.inset(-o, -o);
                } else { fill.setColor(0x14FFFFFF); c.drawRoundRect(rf, R, R, fill); }
                // the status chip on the right
                String chip; int chipC;
                boolean rule = r.has("mode");
                String st = r.optString("status");
                if (rule) { chip = r.optString("mode").equals("new") ? "NEW EPISODES" : "EVERY AIRING"; chipC = 0xFF8E7BFF; }
                else if (st.equals("recording")) { chip = "● REC"; chipC = 0xFFFF453A; }
                else if (st.equals("scheduled")) { chip = day(r.optLong("start")).toUpperCase(Locale.US) + " " + fmt(r.optLong("start")); chipC = 0xFFFFD60A; }
                else if (!Recorder.hasData(r)) { chip = st.equals("failed") ? "FAILED" : "NOT HERE"; chipC = 0x80FFFFFF; }
                else { chip = Math.max(1, r.optLong("secs") / 60) + " MIN"; chipC = 0xB3FFFFFF; }
                float cw = tC.measureText(chip) + 1.6f * R, ch2 = 1.9f * R;
                RectF cr = new RectF(rf.right - 1.2f * R - cw, rf.centerY() - ch2 / 2, rf.right - 1.2f * R, rf.centerY() + ch2 / 2);
                fill.setColor((chipC & 0x00FFFFFF) | 0x2E000000); c.drawRoundRect(cr, ch2 / 2, ch2 / 2, fill);
                tC.setColor(chipC); c.drawText(chip, cr.centerX(), cr.centerY() + tC.getTextSize() * .36f, tC);
                // title and the line under it
                float tx = rf.left + 1.4f * R, avail = cr.left - tx - 1.2f * R;
                String title = r.optString("title");
                String sub;
                if (rule) sub = r.optString("n");
                else {
                    String[] ex = r.optString("ep").split("\t", -1);
                    String epn = ((ex.length > 2 ? ex[2] : "") + " " + (ex.length > 1 ? ex[1] : "")).trim();
                    sub = r.optString("n") + "  ·  " + day(r.optLong("start")) + " " + fmt(r.optLong("start")) + (epn.isEmpty() ? "" : "  ·  " + epn)
                            + (r.optLong("bytes") > 0 ? "  ·  " + size(r.optLong("bytes")) : "")
                            + (r.optBoolean("cloud") ? "  ·  ☁ On Google Drive" : !r.optString("cloudErr").isEmpty() ? "  ·  ☁ Upload waiting: " + r.optString("cloudErr") : r.optInt("cloudPieces") > 0 ? "  ·  ☁ Uploading" : "");
                    if (Recorder.hasData(r) && r.optLong("pos") > r.optLong("from") + 30000 && r.optLong("to") > r.optLong("from")) {
                        // how far you've watched
                        float pr = Math.min(1, (r.optLong("pos") - r.optLong("from")) / (float) (r.optLong("to") - r.optLong("from")));
                        fill.setColor(0x33FFFFFF); c.drawRect(tx, rf.bottom - .55f * R, cr.left - 1.2f * R, rf.bottom - .35f * R, fill);
                        fill.setColor(0xFFFF8A80); c.drawRect(tx, rf.bottom - .55f * R, tx + (cr.left - 1.2f * R - tx) * pr, rf.bottom - .35f * R, fill);
                    }
                }
                tT.setColor(f || st.equals("recording") || rule || Recorder.hasData(r) || st.equals("scheduled") ? Color.WHITE : 0x99FFFFFF);
                c.drawText(TextUtils.ellipsize(title, tT, Math.max(1, avail), TextUtils.TruncateAt.END).toString(), tx, rf.centerY() - .15f * R, tT);
                tS.setTextAlign(Paint.Align.LEFT); tS.setColor(f ? 0xD9FFD9D6 : 0x99FFFFFF);
                c.drawText(TextUtils.ellipsize(sub, tS, Math.max(1, avail), TextUtils.TruncateAt.END).toString(), tx, rf.centerY() + 1.45f * R, tS);
                y += rowH;
            }
            c.restore();
        }
    }

    // ------------------------------------------------------------------ watching a recording

    void playRecording(String id) { playRec(rec().find(id), false); }
    private void playRec(JSONObject r, boolean fromStart) {
        if (r == null || !Recorder.hasData(r)) { h.toast("That recording's files aren't here"); return; }
        NativePlayer np = h.act.nativePlayer();
        if (np == null) { h.toast("The TV's video player isn't available"); return; }
        stopShift();
        if (playing != null && recPlay == null) stopPlayback();
        Ch ps = new Ch(); ps.n = r.optString("title"); ps.u = "rec:" + r.optString("id"); ps.l = r.optString("l"); ps.g = ""; ps.k = ""; ps.c = "";
        recPlay = r; playing = ps; tried = "hls"; lastUrl = null; ready = false;
        Recorder.watching = r.optString("id");
        long from = r.optLong("from", r.optLong("start")), to = recEnd();
        long pos = r.optLong("pos");
        long wall = fromStart || pos <= from + 30000 || pos >= to - 60000 ? from : pos;
        shiftLocal = true; paused = false; pausedWall = wall;
        pid++;
        np.playLocal(pid, "file://" + new java.io.File(r.optString("dir"), "index.m3u8").getAbsolutePath(), wall, false);
        Diag.ev("record: playing " + r.optString("title"));
        enterFull();
        place();
    }
    /** the end of what's recorded (still growing while it records) */
    private long recEnd() {
        if (recPlay == null) return 0;
        Capture c = rec().capture(recPlay.optString("id"));
        if (c != null && c.hasData()) return c.endWall();
        JSONObject r = rec().find(recPlay.optString("id"));
        long to = r != null ? r.optLong("to") : recPlay.optLong("to");
        return to > 0 ? to : recPlay.optLong("end");
    }
    private void endRec() {
        if (recPlay == null) return;
        long p = posWall();
        if (p > 0) rec().setPos(recPlay.optString("id"), p);
        recPlay = null; shiftLocal = false; paused = false;
        Recorder.watching = "";
        stopPlayback();
        main().post(recChanged);
    }

    // ================================================================== pause & rewind (the toolbar)

    /* While a channel plays full screen, a copy of it is saved to the TV as it arrives (Capture): the last
       30 minutes. Pausing pauses the picture; carrying on, rewinding or skipping plays from that copy;
       "Go live" (or skipping up to the live edge) goes back to the channel itself. */
    private Capture shift; private String shiftFor = "", shiftWhy = "";
    private boolean shiftAlt;          // (the other form of the channel's link has been tried)
    private boolean shiftLocal, paused;
    private long pausedWall;
    private Controls controls;
    private boolean barFocus; private int barSel;
    private boolean timeshiftOn() { return h.S.optBoolean("timeshift", true); }
    private int shiftFails; private long shiftKeep;
    /* Pause & rewind saves the channel over a SECOND connection to the provider, beside the one the picture
       plays from. An account that allows one stream at a time then has the provider refusing one of them
       (HTTP 509, "too many connections"): on a busy game day the picture froze over and over. So the account's
       limit is asked once (Xtream services say it), and on a one-stream account pause & rewind stays off. */
    private static final java.util.Map<String, Integer> MAX_CONS = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile boolean oneConnection;          // (a provider refused a second stream this session)
    private void startShift() {
        if (!full || playing == null || !timeshiftOn() || shiftLocal) return;
        if (oneConnection) { shiftWhy = "your provider allows one stream at a time (saving the channel would interrupt the picture)"; return; }
        final String[] xc = CatchUp.fromChannel(playing.u);
        if (xc != null) {
            final String key = xc[0] + "|" + xc[1];
            Integer mc = MAX_CONS.get(key);
            if (mc == null) {
                final Ch forCh = playing;
                MAX_CONS.put(key, -1);          // (asking)
                new Thread(new Runnable() { @Override public void run() {
                    CatchUp.Xt x = new CatchUp.Xt(); x.base = xc[0]; x.user = xc[1]; x.pass = xc[2];
                    final int n = CatchUp.maxConnections(x);
                    MAX_CONS.put(key, n);
                    Diag.ev("live: the provider allows " + (n <= 0 ? "an unknown number of" : String.valueOf(n)) + " stream" + (n == 1 ? "" : "s") + " at once");
                    main().post(new Runnable() { @Override public void run() { if (playing == forCh) startShift(); } });
                } }, "maxcons").start();
                return;
            }
            if (mc == -1) return;               // (still asking: it calls back)
            if (mc == 1) { shiftWhy = "your provider allows one stream at a time (saving the channel would interrupt the picture)"; return; }
        }
        if (shift != null && shiftFor.equals(playing.u)) {
            // the same channel: keep what's saved (carry the copy on if it had stopped)
            if (!shift.running()) spawnShift(true);
            return;
        }
        stopShift();
        java.io.File dir = new java.io.File(getContext().getCacheDir(), "timeshift");
        long free = dir.getParentFile() == null ? 0 : dir.getParentFile().getUsableSpace();
        // the last 30 minutes (about 1–1.5 GB); less when the TV is short of space
        shiftKeep = free > 6L << 30 ? 30 * 60000L : free > 4L << 30 ? 10 * 60000L : 0;
        if (shiftKeep == 0) { shiftWhy = "the TV has only " + (free >> 20) + " MB free"; Diag.ev("timeshift: not enough free space (" + (free >> 20) + " MB)"); return; }
        shiftFails = 0;
        spawnShift(false);
    }
    /** (re)starts saving the playing channel; carrying on keeps what's already saved */
    private void spawnShift(boolean carryOn) {
        if (playing == null) return;
        java.io.File dir = new java.io.File(getContext().getCacheDir(), "timeshift");
        String src = lastUrl != null ? lastUrl : playing.u;
        boolean hls = "hls".equals(tried) || src.toLowerCase(Locale.US).contains(".m3u8");
        if (shiftAlt) { src = playing.u; hls = playing.u.toLowerCase(Locale.US).contains(".m3u8"); }    // (the other form of the link)
        final Ch forCh = playing;
        if (!carryOn) shiftWhy = "starting";
        Capture old = shift;
        if (old != null) old.stop();
        shift = makeCapture(dir, src, hls, forCh);
        shift.append = carryOn;
        shift.start(); shiftFor = playing.u;
        Diag.ev("timeshift: " + (carryOn ? "carrying on saving " : "saving " + (shiftKeep / 60000) + " min of ") + playing.n + " from " + (hls ? "HLS " : "TS ") + NativePlayer.safe(src));
    }
    private Capture makeCapture(java.io.File dir, String src, boolean hls, final Ch forCh) {
        return new Capture(dir, src, forCh.ua, hls, shiftKeep, new Capture.Listener() {
            @Override public void onCapture(final Capture cap, String event) {
                if (!event.equals("error")) return;
                final String err = cap.error();
                Diag.ev("timeshift: saving the channel failed (" + err + ")" + (cap.hasData() ? ", " + Math.round(cap.seconds()) + " s saved" : ""));
                if (limitError(err)) { main().post(new Runnable() { @Override public void run() { if (shift == cap) connLimitHit(); } }); return; }
                main().postDelayed(new Runnable() { @Override public void run() {
                    if (shift != cap || playing != forCh) return;
                    shiftWhy = err;
                    // carry on into the same copy (so what's saved stays), every other time from the channel's other link form
                    if (++shiftFails > 12) { Diag.ev("timeshift: giving up on this channel"); return; }
                    if (lastUrl != null && !lastUrl.equals(forCh.u)) shiftAlt = shiftFails % 2 == 0 ? !shiftAlt : shiftAlt;
                    spawnShift(true);
                } }, 3000);
            }
        });
    }
    private void stopShift() {
        if (shift != null) { shift.discard(); shift = null; }
        shiftAlt = false; shiftWhy = ""; shiftFails = 0;
        shiftFor = ""; shiftLocal = false; paused = false;
    }
    private long posWall() {
        long now = System.currentTimeMillis();
        NativePlayer np = h.act.nativePlayer();
        if (paused && !shiftLocal) return pausedWall;
        if (shiftLocal && np != null) { long w = np.wallPos(); return w > 0 ? w : pausedWall > 0 ? pausedWall : now; }
        // live, the picture runs behind the stream's newest moment by the player's offset (often 10–30 s on IPTV):
        // "back 10 s" means 10 s before what's on screen, not before the newest piece
        if (np != null && recPlay == null) { long off = np.liveOffset(); if (off > 0 && off < 120000) return now - off; }
        return now;
    }
    private long liveWall() {
        if (recPlay != null) return recEnd();
        return shift != null && shift.hasData() ? Math.max(shift.endWall(), System.currentTimeMillis() - 2000) : System.currentTimeMillis();
    }
    private boolean hasBuf() { return recPlay != null || (shift != null && shift.hasData()); }
    private long bufStart() { return recPlay != null ? recPlay.optLong("from", recPlay.optLong("start")) : shift.startWall(); }
    private boolean behind() { return shiftLocal || paused; }
    private void togglePause() {
        NativePlayer np = h.act.nativePlayer();
        if (np == null || playing == null) return;
        if (!paused) {
            // where the picture is now (before marking it paused: posWall answers pausedWall once paused).
            // Live, the picture runs a little behind the stream's live point, by the player's offset.
            long at = posWall();
            if (!shiftLocal) { long off = np.liveOffset(); if (off > 0 && off < 120000) at = System.currentTimeMillis() - off; }
            paused = true; pausedWall = at; np.setPaused(true);
            Diag.ev("timeshift: paused at " + ((System.currentTimeMillis() - at) / 1000) + " s behind live" + (shift == null || !shift.hasData() ? " (nothing saved yet: carrying on means going live)" : ""));
        } else {
            paused = false;
            if (shiftLocal) np.setPaused(false);
            else if (recPlay == null && shift != null && shift.hasData() && pausedWall >= shift.startWall()) toLocal(pausedWall, false);
            else if (recPlay == null && shift != null && shift.hasData()) toLocal(shift.startWall() + 500, false);    // (paused longer than what's kept: from the oldest moment)
            else np.setPaused(false);
        }
        drawOsd(); controls.invalidate();
    }
    private void skip(long ms) { seekTo(posWall() + ms); }
    /** can't rewind (yet): says why */
    private boolean noBuf() {
        if (hasBuf()) return false;
        String why = !timeshiftOn() ? "Pause & rewind is off (Settings › Live TV)"
                : shift != null && shift.running() ? "Rewind works a few seconds after the channel starts"
                : shiftWhy.isEmpty() || shiftWhy.equals("starting") ? "Rewind isn't ready for this channel yet"
                : "Rewind isn't working on this channel: " + shiftWhy;
        h.toast(why);
        if (shift == null && timeshiftOn() && !shiftLocal) startShift();
        return true;
    }
    /** jump to a moment (wall clock): from the saved copy, or live if it's at the live point */
    private void seekTo(long target) {
        if (playing == null) return;
        if (!hasBuf()) {
            String why = !timeshiftOn() ? "Pause & rewind is off (Settings › Live TV)"
                    : shift != null && shift.running() ? "Rewind works a few seconds after the channel starts"
                    : shiftWhy.isEmpty() || shiftWhy.equals("starting") ? "Rewind isn't ready for this channel yet"
                    : "Rewind isn't working on this channel: " + shiftWhy;
            h.toast(why);
            if (shift == null && timeshiftOn() && !shiftLocal) startShift();      // (try again)
            return;
        }
        if (recPlay != null) target = Math.min(target, liveWall() - 3000);
        else if (target >= liveWall() - 5000) { goLive(); return; }
        target = Math.max(target, bufStart() + 500);
        if (shiftLocal) { NativePlayer np = h.act.nativePlayer(); if (np != null) np.seekWall(target); if (paused) pausedWall = target; }
        else toLocal(target, paused);
        drawOsd(); controls.invalidate();
    }
    private void toLocal(long wall, boolean keepPaused) {
        NativePlayer np = h.act.nativePlayer();
        if (np == null || shift == null) return;
        shiftLocal = true; paused = keepPaused; pausedWall = wall;
        pid++;
        np.playLocal(pid, "file://" + shift.playlist().getAbsolutePath(), wall, keepPaused);
        Diag.ev("timeshift: playing from the saved copy, " + ((System.currentTimeMillis() - wall) / 1000) + " s behind");
        place();
    }
    private void goLive() {
        if (playing == null || recPlay != null) return;
        boolean wasLocal = shiftLocal;
        shiftLocal = false; paused = false;
        if (wasLocal) start(playing, tried, lastUrl);
        else { NativePlayer np = h.act.nativePlayer(); if (np != null) np.setPaused(false); }
        drawOsd(); controls.invalidate();
    }

    /** the toolbar's buttons, as they are right now: [id, label] */
    private List<String[]> buttons() {
        List<String[]> b = new ArrayList<>();
        b.add(new String[] { "back", "Back 10 s" });
        b.add(new String[] { "play", paused ? "Play" : "Pause" });
        b.add(new String[] { "fwd", "Forward 30 s" });
        if (recPlay == null && behind()) b.add(new String[] { "live", "Go live" });
        b.add(new String[] { "more", "More" });
        return b;
    }
    private void press(String id) {
        final Ch ch = playing;
        if (ch == null) return;
        switch (id) {
            case "back": skip(-10000); break;
            case "fwd": skip(30000); break;
            case "play": togglePause(); break;
            case "live": goLive(); break;
            case "stats": barFocus = false; openStats(); break;
            case "audio": { JSONArray au = tracks().optJSONArray("audio"); if (au == null || au.length() < 2) h.toast("This channel has one soundtrack"); else audioMenu(au); break; }
            case "subs": subMenu(tracks()); break;
            case "multi": multiview(ch, null); break;
            case "more": if (recPlay != null) recMoreMenu(); else playerMenu(); break;
            case "rec": {
                List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
                recItems(ch, System.currentTimeMillis(), l, a);
                if (l.size() == 1) a.get(0).run(); else h.showMenu(l, a, null, 1f, "Record");
                break;
            }
            case "recstop": { JSONObject rr = rec().at(ch.u, System.currentTimeMillis() + 1000); if (rr != null) { rec().cancel(rr.optString("id")); h.toast("Recording stopped"); } break; }
            case "restart": if (recPlay != null) { NativePlayer np = h.act.nativePlayer(); long w = bufStart(); if (np != null) np.seekWall(w); if (paused) pausedWall = w; } break;
            case "delrec": if (recPlay != null) {
                final String rid = recPlay.optString("id"), title = recPlay.optString("title");
                List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
                l.add("Delete “" + title + "”"); a.add(new Runnable() { @Override public void run() { recPlay = null; Recorder.watching = ""; shiftLocal = false; stopPlayback(); exitFull(); rec().delete(rid); h.toast("Deleted"); } });
                l.add("Keep it"); a.add(new Runnable() { @Override public void run() { } });
                h.showMenu(l, a, null, 1f, "Delete this recording?");
            } break;
        }
    }
    /* The bar has two rows: the seek bar, and the buttons under it. ▲▼ move between them (they don't change
       channel while the bar's up); on the seek bar ◀ ▶ move the point you'll jump to — held, it speeds up —
       and it jumps there when you stop (or press OK). */
    private int barRow = 1;                       // 0: the seek bar, 1: the buttons
    private boolean scrubbing; private long scrubWall, lastScrubAt; private int scrubRun;
    private final Runnable scrubCommit = new Runnable() { @Override public void run() { commitScrub(); } };
    private void commitScrub() {
        main().removeCallbacks(scrubCommit);
        if (!scrubbing) return;
        scrubbing = false;
        seekTo(scrubWall);
        controls.invalidate();
    }
    private void barKey(char d) {
        List<String[]> b = buttons();
        if (d == 'u') { if (barRow == 1) { barRow = 0; scrubbing = false; } }
        else if (d == 'd') { if (barRow == 0) { commitScrub(); barRow = 1; } }
        else if (barRow == 1) {
            if (d == 'l') barSel = Math.max(0, barSel - 1);
            else if (d == 'r') barSel = Math.min(b.size() - 1, barSel + 1);
        } else if (!noBuf()) {
            long now = SystemClock.uptimeMillis();
            scrubRun = now - lastScrubAt < 450 ? scrubRun + 1 : 0;
            lastScrubAt = now;
            long step = scrubRun < 4 ? 10000 : scrubRun < 12 ? 30000 : 60000;
            if (!scrubbing) { scrubbing = true; scrubWall = posWall(); }
            long lo = bufStart(), hi = liveWall();
            scrubWall = Math.max(lo, Math.min(hi, scrubWall + (d == 'r' ? step : -step)));
            main().removeCallbacks(scrubCommit); main().postDelayed(scrubCommit, 900);
        }
        controls.invalidate();
        main().removeCallbacks(osdHide); main().postDelayed(osdHide, 7000);
    }
    private void barFocusOn() {
        barFocus = true; barRow = 1; scrubbing = false;
        List<String[]> b = buttons();
        barSel = 0; for (int i = 0; i < b.size(); i++) if (b.get(i)[0].equals("play")) barSel = i;
        showOsd();
    }

    /** the timeline (the show, what's saved, where you are, live) and the buttons under the banner */
    private final class Controls extends View {
        private final TextPaint tTime = new TextPaint(Paint.ANTI_ALIAS_FLAG), tNum = new TextPaint(Paint.ANTI_ALIAS_FLAG), tPill = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), st = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path path = new Path();
        Controls(android.content.Context c) {
            super(c);
            tTime.setTypeface(h.fMed); tTime.setTextSize(1.05f * R); tTime.setColor(0xD9FFFFFF);
            tNum.setTypeface(h.fSemi); tNum.setTextSize(.78f * R); tNum.setTextAlign(Paint.Align.CENTER);
            tPill.setTypeface(h.fSemi); tPill.setTextSize(1f * R); tPill.setTextAlign(Paint.Align.CENTER);
            st.setStyle(Paint.Style.STROKE); st.setStrokeCap(Paint.Cap.ROUND); st.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override protected void onDraw(Canvas c) {
            Ch ch = playing; if (ch == null) return;
            int w = getWidth();
            float x0 = 0, x1 = w;
            long now = System.currentTimeMillis(), pos = posWall(), live = liveWall();
            long[] pr = recPlay != null ? new long[] { bufStart(), Math.max(recPlay.optLong("end"), live) } : progAt(ch, pos);
            long a = pr != null ? pr[0] : now - 30 * 60000L, b = pr != null ? pr[1] : now + 5 * 60000L;
            if (pos < a) a = pos - 60000; if (live > b) b = live;
            float span = Math.max(1, b - a);
            // the timeline: a thin line; what's been on is lighter, where you are is white
            boolean seekRow = barFocus && barRow == 0;
            float ty = 3f * R, th = (seekRow ? .62f : barFocus ? .42f : .3f) * R;      // (room above for the seek bubble)
            p.setColor(0x33FFFFFF); r.set(x0, ty, x1, ty + th); c.drawRoundRect(r, th, th, p);
            float liveX = x0 + (x1 - x0) * Math.min(1, (live - a) / span);
            float sx = recPlay != null ? x0 : shift != null && shift.hasData() ? x0 + (x1 - x0) * Math.max(0, (shift.startWall() - a) / span) : liveX;
            p.setColor(0x59FFFFFF); r.set(sx, ty, liveX, ty + th); c.drawRoundRect(r, th, th, p);
            float posX = x0 + (x1 - x0) * Math.max(0, Math.min(1, (pos - a) / span));
            p.setColor(Color.WHITE); r.set(x0, ty, posX, ty + th); c.drawRoundRect(r, th, th, p);
            if (seekRow) {
                long tw = scrubbing ? scrubWall : pos;
                float kx = x0 + (x1 - x0) * Math.max(0, Math.min(1, (tw - a) / span));
                if (scrubbing) { p.setColor(0x80FFFFFF); r.set(Math.min(kx, posX), ty, Math.max(kx, posX), ty + th); c.drawRect(r, p); }
                p.setColor(0x40FFFFFF); c.drawCircle(kx, ty + th / 2, 1.25f * R, p);
                p.setColor(Color.WHITE); c.drawCircle(kx, ty + th / 2, .8f * R, p);
                // the time you'd jump to, in a bubble above the knob
                String lab = recPlay != null ? clock(Math.max(0, tw - a)) : (live - tw < 5000 ? "LIVE" : fmt(tw) + "  ·  −" + clock(live - tw));
                float lw = tTime.measureText(lab) + 1.4f * R, lx = Math.max(x0, Math.min(x1 - lw, kx - lw / 2));
                // (the bubble sits over the info row's bottom edge: drawn into the space above the line)
                p.setColor(0xF2FFFFFF); r.set(lx, ty - 2.9f * R, lx + lw, ty - 1.05f * R); c.drawRoundRect(r, .5f * R, .5f * R, p);
                tTime.setColor(0xFF111111); tTime.setTextAlign(Paint.Align.CENTER); c.drawText(lab, lx + lw / 2, ty - 1.55f * R, tTime); tTime.setColor(0xD9FFFFFF);
            } else if (barFocus) c.drawCircle(posX, ty + th / 2, .62f * R, p);
            // under it: where you are, and the end (or LIVE / how far behind)
            long lag = Math.max(0, live - pos);
            boolean atLive = recPlay == null && (!behind() || lag < 5000) && !paused;
            float ly = ty + th + 2.0f * R;
            tTime.setTextAlign(Paint.Align.LEFT);
            c.drawText(recPlay != null ? clock(Math.max(0, pos - a)) : fmt(pos), x0, ly, tTime);
            tTime.setTextAlign(Paint.Align.RIGHT);
            if (recPlay != null) {
                boolean going = rec().capture(recPlay.optString("id")) != null;
                c.drawText((going ? "● REC  " : "") + clock(Math.max(0, live - a)), x1, ly, tTime);
            } else if (atLive) {
                // a red LIVE badge
                float bw = tPill.measureText("LIVE") + 1.4f * R, bh = 1.7f * R;
                r.set(x1 - bw, ly - bh + .35f * R, x1, ly + .35f * R);
                p.setColor(0xFFE5322D); c.drawRoundRect(r, .4f * R, .4f * R, p);
                tPill.setColor(Color.WHITE); c.drawText("LIVE", r.centerX(), r.centerY() + tPill.getTextSize() * .36f, tPill);
            } else c.drawText("−" + clock(lag), x1, ly, tTime);
            // the buttons: back 10, play/pause, forward 30 in the middle; Go live and More on the right
            List<String[]> bs = buttons();
            float cy = ly + 3.6f * R, mid = w / 2f;
            for (int i = 0; i < bs.size(); i++) {
                String id = bs.get(i)[0];
                boolean f = barFocus && barRow == 1 && i == barSel;
                float x, rad;
                if (id.equals("back")) { x = mid - 6 * R; rad = 1.9f * R; }
                else if (id.equals("play")) { x = mid; rad = 2.4f * R; }
                else if (id.equals("fwd")) { x = mid + 6 * R; rad = 1.9f * R; }
                else if (id.equals("more")) { x = x1 - 1.9f * R; rad = 1.9f * R; }
                else {                                                     // Go live: a text button left of More
                    float bw = tPill.measureText("Go live") + 2.6f * R, bh = 2.8f * R, rx = x1 - 3.8f * R - 1.2f * R;
                    r.set(rx - bw, cy - bh / 2, rx, cy + bh / 2);
                    p.setColor(f ? Color.WHITE : 0x26FFFFFF); c.drawRoundRect(r, bh / 2, bh / 2, p);
                    tPill.setColor(f ? 0xFF111111 : Color.WHITE); c.drawText("Go live", r.centerX(), r.centerY() + tPill.getTextSize() * .36f, tPill);
                    continue;
                }
                if (f) { p.setColor(Color.WHITE); c.drawCircle(x, cy, rad, p); }
                int ic = f ? 0xFF111111 : Color.WHITE;
                if (id.equals("play")) {
                    p.setColor(ic);
                    if (paused) { path.reset(); float k = .95f * R; path.moveTo(x - k * .7f, cy - k); path.lineTo(x + k, cy); path.lineTo(x - k * .7f, cy + k); path.close(); c.drawPath(path, p); }
                    else { float bw2 = .38f * R, bh2 = 1.9f * R; r.set(x - .75f * R, cy - bh2 / 2, x - .75f * R + bw2, cy + bh2 / 2); c.drawRoundRect(r, .1f * R, .1f * R, p); r.offset(1.5f * R - bw2, 0); c.drawRoundRect(r, .1f * R, .1f * R, p); }
                } else if (id.equals("more")) {
                    p.setColor(ic); for (int k = -1; k <= 1; k++) c.drawCircle(x + k * .62f * R, cy, .2f * R, p);
                } else {
                    // a circular arrow with the seconds inside it
                    boolean back = id.equals("back"), cw = !back;     // (the arrow: anticlockwise to go back, clockwise forward)
                    st.setColor(ic); st.setStrokeWidth(.17f * R);
                    float ar = 1.05f * R;
                    r.set(x - ar, cy - ar, x + ar, cy + ar);
                    c.drawArc(r, cw ? -60 : -120, cw ? -290 : 290, false, st);
                    // the arrowhead at the arc's start
                    double ang = Math.toRadians(cw ? -60 : -120);
                    float ax = x + (float) (ar * Math.cos(ang)), ay = cy + (float) (ar * Math.sin(ang));
                    path.reset();
                    float d = .45f * R, dir = cw ? 1 : -1;
                    path.moveTo(ax + dir * d * .2f, ay - d); path.lineTo(ax + dir * d * .9f, ay + d * .15f); path.lineTo(ax - dir * d * .5f, ay + d * .35f); path.close();
                    p.setColor(ic); c.drawPath(path, p);
                    tNum.setColor(ic); c.drawText(back ? "10" : "30", x, cy + tNum.getTextSize() * .36f, tNum);
                }
            }
        }
    }
    private static String clock(long ms) { long s = ms / 1000; return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) : String.format(Locale.US, "%d:%02d", s / 60, s % 60); }
    private void drawOsd() {
        final Ch ch = playing;
        if (ch == null) return;
        if (recPlay != null) { drawRecOsd(); return; }
        rebase();
        long now = posWall();
        long[] p = progAt(ch, now), n = nextAfter(ch, p != null ? p[1] : now);
        String[] tx = progText(ch, p);
        Ch pv = prevCh != null && !prevCh.u.equals(ch.u) ? prevCh : null;
        oHint.setText(barFocus ? "◀ ▶ choose · OK select · ▲▼ channels · Back hide"
                : "OK · controls · ▲▼ change channel" + (pv != null ? " · ◀ back to " + (pv.n.length() > 22 ? pv.n.substring(0, 21) + "…" : pv.n) : "") + " · Back for guide");
        String sl = sleepAt > 0 ? "   Sleep in " + Math.max(0, Math.round((sleepAt - now) / 60000.0)) + " min" : "";
        oChan.setText(chNum(ch) + "   " + ch.n + (favs.contains(ch.u) ? "  ★" : "") + sl);
        oTitle.setText(p != null ? (tx[0].isEmpty() ? "Untitled" : tx[0]) : "No guide information");
        if (p != null) {
            oWhen.setVisibility(VISIBLE);
            oWhen.setText(fmt(p[0]) + " – " + fmt(p[1]));
        } else oWhen.setVisibility(GONE);
        if (n != null) { oNext.setVisibility(VISIBLE); oNext.setText("Next · " + fmt(n[0]) + " " + progText(ch, n)[0]); } else oNext.setVisibility(GONE);
        oPh.setText(ch.n.isEmpty() ? "" : ch.n.substring(0, 1));
        oLogo.setImageDrawable(null); oPh.setVisibility(VISIBLE);
        if (!ch.l.isEmpty()) logo(ch.l, h.px(9), h.px(6), new HomeImages.Done() { @Override public void done(Bitmap b) {
            if (b != null && playing == ch) { oLogo.setImageBitmap(b); oPh.setVisibility(INVISIBLE); }
        } });
    }

    /** More, while watching a recording */
    private void recMoreMenu() {
        main().removeCallbacks(osdHide);
        List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
        l.add("From the start"); a.add(new Runnable() { @Override public void run() { press("restart"); showOsd(); } });
        final JSONObject tr = tracks();
        final JSONArray au = tr.optJSONArray("audio");
        if (au != null && au.length() > 1) { l.add("Audio"); a.add(new Runnable() { @Override public void run() { later(new Runnable() { @Override public void run() { audioMenu(au); } }); } }); }
        l.add("Subtitles"); a.add(new Runnable() { @Override public void run() { later(new Runnable() { @Override public void run() { subMenu(tracks()); } }); } });
        l.add("Delete this recording"); a.add(new Runnable() { @Override public void run() { later(new Runnable() { @Override public void run() { press("delrec"); } }); } });
        h.showMenu(l, a, null, 1f, recPlay != null ? recPlay.optString("title") : "Recording");
    }
    private void drawRecOsd() {
        final JSONObject r = recPlay; final Ch ch = playing;
        oHint.setText(barFocus ? "◀ ▶ choose · OK select · Back hide" : "OK · controls · Back for recordings");
        oChan.setText("RECORDING  ·  " + r.optString("n"));
        oTitle.setText(r.optString("title"));
        oWhen.setVisibility(VISIBLE);
        oWhen.setText(day(r.optLong("start")) + " · " + fmt(r.optLong("start")));
        oNext.setVisibility(GONE);
        oPh.setText(r.optString("n").isEmpty() ? "" : r.optString("n").substring(0, 1));
        oLogo.setImageDrawable(null); oPh.setVisibility(VISIBLE);
        if (!r.optString("l").isEmpty()) logo(r.optString("l"), h.px(9), h.px(6), new HomeImages.Done() { @Override public void done(Bitmap b) {
            if (b != null && playing == ch) { oLogo.setImageBitmap(b); oPh.setVisibility(INVISIBLE); }
        } });
    }

    private final Set<String> logoAsked = new HashSet<>();

    /* Channel logos for the guide and the channel list. They're kept on the TV once fetched (a small picture
       each, in the app's cache folder), so they show at once next time. Fetching takes 4 at a time, the most
       recently asked first; when you scroll fast, the ones you've already scrolled past are dropped from the
       queue (and asked again if they come back into view), so the rows on screen don't wait behind them. */
    private static final int LOGO_QUEUE = 48;
    private static final class LogoQueue extends java.util.concurrent.LinkedBlockingDeque<Runnable> {
        @Override public boolean offer(Runnable r) {
            boolean ok = super.offerFirst(r);
            while (size() > LOGO_QUEUE) { Runnable old = pollLast(); if (old instanceof LogoJob) ((LogoJob) old).dropped(); }
            return ok;
        }
    }
    private static final java.util.concurrent.ThreadPoolExecutor LOGO_POOL =
            new java.util.concurrent.ThreadPoolExecutor(4, 4, 30, java.util.concurrent.TimeUnit.SECONDS, new LogoQueue());
    /* (Only the most recently shown few hundred are kept in memory: flying through thousands of channels
       used to keep every logo it passed, which ran the TV short of memory for pictures. The rest are on disk.) */
    private final Map<String, Bitmap> chLogos = new java.util.LinkedHashMap<String, Bitmap>(256, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) {
            if (size() <= 240) return false;
            chLogoAsked.remove(e.getKey());
            return true;
        }
    };
    private final Set<String> chLogoAsked = new HashSet<>(), chLogoFailed = new HashSet<>();
    private java.io.File logoDir;
    private abstract static class LogoJob implements Runnable { abstract void dropped(); }
    /** a channel's logo if it's ready (else it's fetched, and the guide redrawn when it arrives) */
    Bitmap chLogo(final String url, final View redraw) {
        if (url == null || url.isEmpty()) return null;
        Bitmap b = chLogos.get(url);
        if (b != null || chLogoFailed.contains(url) || !chLogoAsked.add(url)) return b;
        if (logoDir == null) { logoDir = new java.io.File(getContext().getCacheDir(), "logos"); logoDir.mkdirs(); }
        final java.io.File f = new java.io.File(logoDir, Integer.toHexString(url.hashCode()) + "-" + Integer.toHexString(url.length()) + ".png");
        final int mw = Math.round(8f * R), mh = Math.round(5.6f * R);
        LOGO_POOL.execute(new LogoJob() {
            @Override void dropped() { main().post(new Runnable() { @Override public void run() { chLogoAsked.remove(url); } }); }
            @Override public void run() {
                Bitmap out = null; boolean fetched = false;
                try {
                    if (f.exists()) out = android.graphics.BitmapFactory.decodeFile(f.getPath());
                    if (out == null) {
                        byte[] d = HomeImages.http(url, null); fetched = true;
                        Bitmap raw = d != null && d.length > 0 ? HomeImages.decode(d, mw, mh) : null;
                        if (raw != null) {
                            float sc = Math.min(1f, Math.min(mw / (float) raw.getWidth(), mh / (float) raw.getHeight()));
                            out = sc < 1f ? Bitmap.createScaledBitmap(raw, Math.max(1, Math.round(raw.getWidth() * sc)), Math.max(1, Math.round(raw.getHeight() * sc)), true) : raw;
                            if (out != raw) raw.recycle();
                            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { out.compress(Bitmap.CompressFormat.PNG, 100, o); } catch (Throwable ignored) { }
                        }
                    }
                } catch (Throwable ignored) { }
                final Bitmap res = out; final boolean failed = out == null && fetched;
                main().post(new Runnable() { @Override public void run() {
                    if (res != null) { chLogos.put(url, res); if (redraw != null) redraw.invalidate(); }
                    else if (failed) chLogoFailed.add(url);
                    else chLogoAsked.remove(url);
                } });
            }
        });
        return null;
    }
    private void logoOnce(String url, HomeImages.Done d) { if (logoAsked.add(url)) logo(url, h.px(3.4f), h.px(2.5f), d); }
    private void logo(final String url, int w, int hh, HomeImages.Done d) {
        h.imgs.load("lg:" + url, new HomeImages.Source() { @Override public byte[] bytes() throws Exception { return HomeImages.http(url, null); } }, w, hh, d);
    }

    // ---- subtitles

    private void showCues(String s) {
        cuesText = s == null ? "" : s;
        if (cuesText.isEmpty()) { cues.setVisibility(GONE); return; }
        android.text.SpannableString sp = new android.text.SpannableString(cuesText);
        sp.setSpan(new android.text.style.BackgroundColorSpan(0xB8000000), 0, cuesText.length(), 0);
        cues.setText(sp);
        cues.setVisibility(VISIBLE);
    }
    /* Each channel remembers its own soundtrack and subtitles (a Spanish feed on one channel, captions on
       another): chosen once from hold OK, they come back every time that channel plays. Channels you haven't
       set fall back to the last choice you made anywhere. */
    private JSONObject chTracks(String u) { try { JSONObject m = new JSONObject(h.prefs.getString("ui.liveChTracks", "{}")); return m.optJSONObject(u); } catch (Exception e) { return null; } }
    private void saveChTrack(String key, String val) {
        if (playing == null) return;
        try {
            JSONObject m = new JSONObject(h.prefs.getString("ui.liveChTracks", "{}"));
            JSONObject o = m.optJSONObject(playing.u); if (o == null) o = new JSONObject();
            o.put(key, val); o.put("at", System.currentTimeMillis());
            m.put(playing.u, o);
            if (m.length() > 300) {        // (the oldest choices go once there are a lot)
                String old = null; long oa = Long.MAX_VALUE;
                java.util.Iterator<String> it = m.keys();
                while (it.hasNext()) { String k = it.next(); long a = m.optJSONObject(k) == null ? 0 : m.optJSONObject(k).optLong("at"); if (a < oa) { oa = a; old = k; } }
                if (old != null) m.remove(old);
            }
            putPref("liveChTracks", m.toString());
        } catch (Exception ignored) { }
    }
    private static String trackId(JSONObject t) { return t.optString("lang") + "|" + t.optString("name"); }
    private void applyTracks() {
        JSONObject want = playing == null ? null : chTracks(playing.u);
        if (want == null) { applySubs(); return; }
        NativePlayer np = h.act.nativePlayer(); if (np == null) return;
        try {
            JSONObject tr = new JSONObject(np.tracks());
            String wa = want.optString("a");
            JSONArray au = tr.optJSONArray("audio");
            if (!wa.isEmpty() && au != null && au.length() > 1) {
                JSONObject pick = null;
                for (int i = 0; i < au.length() && pick == null; i++) if (trackId(au.getJSONObject(i)).equals(wa)) pick = au.getJSONObject(i);
                String wl = wa.substring(0, wa.indexOf('|'));
                for (int i = 0; i < au.length() && pick == null; i++) if (!wl.isEmpty() && au.getJSONObject(i).optString("lang").equals(wl)) pick = au.getJSONObject(i);
                if (pick != null && !pick.optBoolean("on")) { Diag.ev("live: this channel's soundtrack · " + wa); np.select("audio", pick.optInt("g"), pick.optInt("t")); }
            }
            if (!want.has("s")) { applySubs(); return; }
            String ws = want.optString("s");
            JSONArray tx = tr.optJSONArray("text");
            if (tx == null || tx.length() == 0) return;
            boolean on = false; for (int i = 0; i < tx.length(); i++) if (tr.optBoolean("textOn") && tx.getJSONObject(i).optBoolean("on")) on = true;
            if (ws.isEmpty()) { if (on) np.select("text", -1, -1); return; }
            if (on) return;
            for (int i = 0; i < tx.length(); i++) {
                JSONObject o = tx.getJSONObject(i);
                String id = !o.optString("lang").isEmpty() ? o.optString("lang") : !o.optString("name").isEmpty() ? o.optString("name") : "on";
                if (id.equals(ws) || ws.equals("on")) { np.select("text", o.optInt("g"), o.optInt("t")); return; }
            }
        } catch (Exception ignored) { }
    }
    private void applySubs() {
        String want = prefStr("liveSubLang");
        if (want == null || want.isEmpty()) return;
        NativePlayer np = h.act.nativePlayer(); if (np == null) return;
        try {
            JSONObject tr = new JSONObject(np.tracks());
            JSONArray tx = tr.optJSONArray("text");
            if (tx == null || tx.length() == 0) return;
            if (tr.optBoolean("textOn")) for (int i = 0; i < tx.length(); i++) if (tx.getJSONObject(i).optBoolean("on")) return;
            for (int i = 0; i < tx.length(); i++) {
                JSONObject o = tx.getJSONObject(i);
                String id = !o.optString("lang").isEmpty() ? o.optString("lang") : !o.optString("name").isEmpty() ? o.optString("name") : "on";
                if (id.equals(want) || want.equals("on")) { np.select("text", o.optInt("g"), o.optInt("t")); return; }
            }
        } catch (Exception ignored) { }
    }

    // ---- hold OK while watching

    private static final Map<String, String> LANGS = new HashMap<>();
    static {
        String[] l = { "en", "English", "eng", "English", "es", "Spanish", "spa", "Spanish", "fr", "French", "fre", "French", "fra", "French", "de", "German", "ger", "German", "deu", "German",
            "it", "Italian", "ita", "Italian", "pt", "Portuguese", "por", "Portuguese", "ar", "Arabic", "ara", "Arabic", "hi", "Hindi", "hin", "Hindi", "ru", "Russian", "rus", "Russian",
            "zh", "Chinese", "chi", "Chinese", "zho", "Chinese", "ja", "Japanese", "jpn", "Japanese", "ko", "Korean", "kor", "Korean", "nl", "Dutch", "dut", "Dutch", "nld", "Dutch",
            "pl", "Polish", "pol", "Polish", "tr", "Turkish", "tur", "Turkish", "sv", "Swedish", "swe", "Swedish", "el", "Greek", "gre", "Greek", "ell", "Greek" };
        for (int i = 0; i < l.length; i += 2) LANGS.put(l[i], l[i + 1]);
    }
    static String langName(String code, String fallback) {
        String c = code == null ? "" : code.toLowerCase(Locale.US).split("[-_]")[0];
        String n = LANGS.get(c);
        return n != null ? n : fallback != null ? fallback : c.toUpperCase(Locale.US);
    }
    private JSONObject tracks() { NativePlayer np = h.act.nativePlayer(); try { return np == null ? new JSONObject() : new JSONObject(np.tracks()); } catch (Exception e) { return new JSONObject(); } }
    private String trackName(JSONObject t, int i, String kind) {
        String n = t.optString("name");
        if (!n.isEmpty() && !n.matches("(?i)^(audio|stereo|default|subtitles|captions|\\d+)$")) return n;
        return langName(t.optString("lang"), kind.equals("audio") ? "Track " + (i + 1) : t.optBoolean("cc") ? "CC" + (i + 1) : "Subtitles " + (i + 1))
            + (kind.equals("audio") && t.optInt("ch") > 2 ? " · surround" : "") + (kind.equals("text") && t.optBoolean("cc") ? " (CC)" : "");
    }

    private void playerMenu() {
        final Ch ch = playing;
        if (ch == null) return;
        main().removeCallbacks(osdHide);
        List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
        if (statsView.shown) { l.add("Hide live stats"); a.add(new Runnable() { @Override public void run() { statsView.close(); } }); }
        else { l.add(watchGame != null ? "Live stats · " + watchGame.optString("short", watchGame.optString("name")) : "Live stats"); a.add(new Runnable() { @Override public void run() { openStats(); } }); }
        final boolean fav = favs.contains(ch.u);
        recItems(ch, System.currentTimeMillis(), l, a);
        { long[] np = progAt(ch, System.currentTimeMillis()); catchUpItems(ch, np, progText(ch, np), l, a); }
        l.add(fav ? "Remove from Favorites" : "Add to Favorites"); a.add(new Runnable() { @Override public void run() { toggleFav(ch); showOsd(); } });
        final JSONObject tr = tracks();
        final JSONArray au = tr.optJSONArray("audio"), tx = tr.optJSONArray("text"), vi = tr.optJSONArray("video");
        if (au != null && au.length() > 1) {
            String cur = ""; for (int i = 0; i < au.length(); i++) if (au.optJSONObject(i).optBoolean("on")) cur = trackName(au.optJSONObject(i), i, "audio");
            l.add("Audio · " + (cur.isEmpty() ? trackName(au.optJSONObject(0), 0, "audio") : cur));
            a.add(new Runnable() { @Override public void run() { later(new Runnable() { @Override public void run() { audioMenu(au); } }); } });
        }
        String sub = "none";
        if (tx != null && tx.length() > 0) { sub = "Off"; if (tr.optBoolean("textOn")) for (int i = 0; i < tx.length(); i++) if (tx.optJSONObject(i).optBoolean("on")) sub = trackName(tx.optJSONObject(i), i, "text"); }
        l.add("Subtitles · " + sub); a.add(new Runnable() { @Override public void run() { later(new Runnable() { @Override public void run() { subMenu(tracks()); } }); } });
        if (vi != null && vi.length() > 1) {
            l.add("Quality · " + (tr.optBoolean("auto", true) ? "Auto" : "Fixed"));
            a.add(new Runnable() { @Override public void run() { later(new Runnable() { @Override public void run() { qualityMenu(tracks()); } }); } });
        }
        l.add(stats ? "Hide stream info" : "Stream info"); a.add(new Runnable() { @Override public void run() { toggleStats(); } });
        l.add("Multiview · add channels"); a.add(new Runnable() { @Override public void run() { multiview(ch, null); } });
        l.add(sleepAt > 0 ? "Sleep timer · " + Math.max(0, Math.round((sleepAt - System.currentTimeMillis()) / 60000.0)) + " min left" : "Sleep timer");
        a.add(new Runnable() { @Override public void run() { later(new Runnable() { @Override public void run() { sleepMenu(); } }); } });
        if (h.linkOn) { l.add("Watch on your other TV"); a.add(new Runnable() { @Override public void run() {
            h.page("lkSend({type:'open',what:'channel',u:" + JSONObject.quote(ch.u) + ",n:" + JSONObject.quote(ch.n) + "}).then(r=>{if(r)toast('Sent to '+lkTvName())})");
        } }); }
        l.add("Open in another player"); a.add(new Runnable() { @Override public void run() { h.bridge.openExternal(ch.u, ch.n); } });
        h.showMenu(l, a, null, 1f, ch.n);
    }
    private void later(Runnable r) { main().postDelayed(r, 60); }

    private void audioMenu(JSONArray au) {
        List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
        for (int i = 0; i < au.length(); i++) {
            final JSONObject t = au.optJSONObject(i); final String name = trackName(t, i, "audio");
            l.add((t.optBoolean("on") ? "✓ " : "") + name);
            a.add(new Runnable() { @Override public void run() {
                NativePlayer np = h.act.nativePlayer(); if (np != null) np.select("audio", t.optInt("g"), t.optInt("t"));
                putPref("liveAudLang", JSONObject.quote(t.optString("lang")));
                saveChTrack("a", trackId(t));
                h.toast("Audio: " + name);
            } });
        }
        h.showMenu(l, a, null, 1f, "Audio");
    }
    private void subMenu(JSONObject tr) {
        final JSONArray tx = tr.optJSONArray("text");
        List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
        if (tx == null || tx.length() == 0) { l.add("No subtitles or captions on this channel right now"); a.add(new Runnable() { @Override public void run() { } }); h.showMenu(l, a, null, 1f, "Subtitles & captions"); return; }
        boolean anyOn = false; for (int i = 0; i < tx.length(); i++) if (tr.optBoolean("textOn") && tx.optJSONObject(i).optBoolean("on")) anyOn = true;
        l.add((anyOn ? "" : "✓ ") + "Off"); a.add(new Runnable() { @Override public void run() {
            NativePlayer np = h.act.nativePlayer(); if (np != null) np.select("text", -1, -1);
            putPref("liveSubLang", "\"\""); saveChTrack("s", ""); h.toast("Subtitles off"); showCues("");
        } });
        for (int i = 0; i < tx.length(); i++) {
            final JSONObject t = tx.optJSONObject(i); final String name = trackName(t, i, "text");
            l.add((tr.optBoolean("textOn") && t.optBoolean("on") ? "✓ " : "") + name);
            a.add(new Runnable() { @Override public void run() {
                NativePlayer np = h.act.nativePlayer(); if (np != null) np.select("text", t.optInt("g"), t.optInt("t"));
                String id = !t.optString("lang").isEmpty() ? t.optString("lang") : !t.optString("name").isEmpty() ? t.optString("name") : "on";
                putPref("liveSubLang", JSONObject.quote(id)); saveChTrack("s", id); h.toast("Subtitles: " + name + " · remembered for " + (playing != null ? playing.n : "this channel"));
            } });
        }
        h.showMenu(l, a, null, 1f, "Subtitles & captions");
    }
    private void qualityMenu(JSONObject tr) {
        JSONArray vi = tr.optJSONArray("video");
        List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
        boolean auto = tr.optBoolean("auto", true);
        l.add((auto ? "✓ " : "") + "Auto"); a.add(new Runnable() { @Override public void run() { NativePlayer np = h.act.nativePlayer(); if (np != null) np.select("video", -1, -1); h.toast("Quality: Auto"); } });
        List<JSONObject> vs = new ArrayList<>(); if (vi != null) for (int i = 0; i < vi.length(); i++) vs.add(vi.optJSONObject(i));
        java.util.Collections.sort(vs, new java.util.Comparator<JSONObject>() { @Override public int compare(JSONObject x, JSONObject y) { return y.optInt("h") != x.optInt("h") ? y.optInt("h") - x.optInt("h") : Long.compare(y.optLong("br"), x.optLong("br")); } });
        for (final JSONObject v : vs) {
            final String name = (v.optInt("h") > 0 ? v.optInt("h") + "p" : "Level") + (v.optLong("br") > 0 ? String.format(Locale.US, " · %.1f Mbps", v.optLong("br") / 1e6) : "");
            l.add((!auto && v.optBoolean("on") ? "✓ " : "") + name);
            a.add(new Runnable() { @Override public void run() { NativePlayer np = h.act.nativePlayer(); if (np != null) np.select("video", v.optInt("g"), v.optInt("t")); h.toast("Quality: " + name); } });
        }
        h.showMenu(l, a, null, 1f, "Quality");
    }

    private final Runnable statsTick = new Runnable() { @Override public void run() { if (stats && full) { drawStats(); main().postDelayed(this, 1000); } } };
    private void toggleStats() {
        stats = !stats;
        statsBox.setVisibility(stats && full ? VISIBLE : GONE);
        main().removeCallbacks(statsTick);
        if (stats) statsTick.run();
    }
    private void drawStats() {
        statsBox.removeAllViews();
        if (playing == null) return;
        TextView hd = h.text(h.fSemi, 1.15f, 0x99FFFFFF); hd.setText("STREAM INFO"); hd.setLetterSpacing(.08f);
        statsBox.addView(hd);
        JSONObject s; NativePlayer np = h.act.nativePlayer();
        try { s = np == null ? new JSONObject() : new JSONObject(np.stats()); } catch (Exception e) { s = new JSONObject(); }
        statRow("Channel", playing.n);
        statRow("Stream", tried.equals("hls") && !kindOf(playing.u).equals("hls") ? "HLS (the channel's HLS version)" : tried.equals("hls") ? "HLS" : tried.equals("file") ? "Direct" : "MPEG-TS");
        if (reconnects > 0) statRow("Reconnects", String.valueOf(reconnects));
        statRow("Player", "TV's video player" + (!s.optString("decoder").isEmpty() ? " · " + s.optString("decoder") : ""));
        statRow("Resolution", s.optInt("w") > 0 ? s.optInt("w") + "×" + s.optInt("h") : "waiting…");
        String codecs = s.optString("vcodec") + (s.optString("acodec").isEmpty() ? "" : " / " + s.optString("acodec"));
        if (!codecs.trim().isEmpty()) statRow("Codecs", codecs);
        if (s.optDouble("fps", 0) > 0) statRow("Frame rate", Math.round(s.optDouble("fps")) + " fps");
        if (s.optLong("br") > 0) statRow("Bitrate", String.format(Locale.US, "%.2f Mbps", s.optLong("br") / 1e6));
        if (s.optLong("bw") > 0) statRow("Connection", String.format(Locale.US, "%.1f Mbps", s.optLong("bw") / 1e6));
        if (s.has("buf")) statRow("Buffer", String.format(Locale.US, "%.1f s", s.optDouble("buf")));
        if (s.optLong("total") > 0) statRow("Dropped frames", s.optLong("dropped") + " of " + s.optLong("total"));
        statRow("Decoding way", s.optInt("strategy") + " (tried " + s.optString("ways") + ")");
        if (s.optInt("sound") > 0) statRow("Sound", s.optInt("sound") == 1 ? "decoded by TopShelf" : "off · this channel's sound won't play");
        if (SafeTs.dropped > 0) statRow("Damaged frames skipped", String.valueOf(SafeTs.dropped));
        try { statRow("App", h.act.getPackageManager().getPackageInfo(h.act.getPackageName(), 0).versionName); } catch (Exception ignored) { }
        if (!s.optString("cands").isEmpty()) statLine("Decoders: " + s.optString("cands"));
        if (!s.optString("stall").isEmpty()) statLine("Last stall: " + s.optString("stall"));
        if (!lastErr.isEmpty()) statLine("Last error: " + lastErr);
    }
    private void statLine(String v) {
        TextView t = h.text(h.fReg, 1f, 0xB3FFFFFF); t.setText(v); t.setMaxLines(7);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(h.px(28.8f), LayoutParams.WRAP_CONTENT); l.topMargin = h.px(.4f);
        statsBox.addView(t, l);
    }
    private void statRow(String k, String v) {
        LinearLayout r = new LinearLayout(getContext());
        TextView a = h.text(h.fReg, 1.15f, 0x99FFFFFF); a.setText(k);
        r.addView(a, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        TextView b = h.text(h.fSemi, 1.15f, Color.WHITE); b.setText(v); b.setSingleLine(true); b.setEllipsize(TextUtils.TruncateAt.END); b.setMaxWidth(h.px(19)); b.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); bl.leftMargin = h.px(2.4f);
        r.addView(b, bl);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(h.px(28.8f), LayoutParams.WRAP_CONTENT); rl.topMargin = h.px(.2f);
        statsBox.addView(r, rl);
    }

    // ---- sleep timer

    private final Runnable sleepNow = new Runnable() { @Override public void run() {
        sleepAt = 0;
        stopPlayback();
        if (full) exitFull();
        h.showTab("home");
        h.toast("Sleep timer: Live TV stopped");
    } };
    private void setSleep(int min) {
        main().removeCallbacks(sleepNow);
        sleepAt = min > 0 ? System.currentTimeMillis() + min * 60000L : 0;
        if (min > 0) main().postDelayed(sleepNow, min * 60000L);
        h.toast(min > 0 ? "Live TV will stop in " + (min >= 60 ? (min % 60 == 0 ? (min / 60) + (min == 60 ? " hour" : " hours") : (min / 60.0) + " hours") : min + " minutes") : "Sleep timer off");
        if (full) showOsd();
    }
    private void sleepMenu() {
        List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
        if (sleepAt > 0) { l.add("Turn off sleep timer"); a.add(new Runnable() { @Override public void run() { setSleep(0); } }); }
        final int[] opts = { 15, 30, 60, 90, 120 };
        final String[] names = { "15 minutes", "30 minutes", "1 hour", "1½ hours", "2 hours" };
        for (int i = 0; i < opts.length; i++) { final int m = opts[i]; l.add(names[i]); a.add(new Runnable() { @Override public void run() { setSleep(m); } }); }
        h.showMenu(l, a, null, 1f, "Stop Live TV after…");
    }
    /** at bedtime (Settings), watching starts a one-hour sleep timer */
    private void bedtime() {
        int b = h.S.optInt("bedtime", 0);
        if (b <= 0 || sleepAt > 0) return;
        Calendar c = Calendar.getInstance();
        float hr = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60f;
        boolean bed = b == 24 ? hr < 6 : (hr >= b || hr < 6);
        if (!bed) return;
        sleepAt = System.currentTimeMillis() + 60 * 60000L;
        main().removeCallbacks(sleepNow); main().postDelayed(sleepNow, 60 * 60000L);
        h.toast("Bedtime: Live TV will stop in 1 hour (hold OK to change)");
    }

    // ---- the channel list over full-screen video

    private int zapSel;
    private final Runnable zapIdle = new Runnable() { @Override public void run() { if (zap) zapClose(); } };
    private void zapOpen() {
        if (list.isEmpty()) return;
        zap = true;
        zapSel = 0;
        if (playing != null) for (int i = 0; i < list.size(); i++) if (list.get(i).u.equals(playing.u)) zapSel = i;
        main().removeCallbacks(osdHide); osd.animate().alpha(0f).setDuration(150).start();
        zapView.setVisibility(VISIBLE);
        zapView.setTranslationX(-h.px(45.6f));
        zapView.animate().translationX(0).setDuration(400).setInterpolator(TileView.EASE).start();
        zapView.invalidate();
        main().removeCallbacks(zapIdle); main().postDelayed(zapIdle, 9000);
    }
    private void zapClose() {
        zap = false;
        main().removeCallbacks(zapIdle);
        zapView.animate().translationX(-h.px(45.6f)).setDuration(300).withEndAction(new Runnable() { @Override public void run() { if (!zap) zapView.setVisibility(INVISIBLE); } }).start();
    }
    private void zapKey(char d) {
        main().removeCallbacks(zapIdle); main().postDelayed(zapIdle, 9000);
        int n = list.size();
        if (d == 'u') zapSel = (zapSel - 1 + n) % n;
        else if (d == 'd') zapSel = (zapSel + 1) % n;
        else if (d == 'l') { zapClose(); return; }
        zapView.invalidate();
    }
    private void zapPick() {
        Ch ch = zapSel < list.size() ? list.get(zapSel) : null;
        zapClose();
        if (ch != null && (playing == null || !playing.u.equals(ch.u))) { row = zapSel; t = System.currentTimeMillis(); play(ch, true); }
    }

    // ================================================================== the tune-in card

    /** shown until a channel's picture arrives: logo, number and name, what's on (k: size of its box vs. the screen) */
    private final class Tune {
        final LinearLayout box;
        final ImageView logo; final TextView ph, chan, title;
        final View dash;
        String key = "";
        Tune(float k) {
            box = new LinearLayout(getContext());
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setBackgroundColor(0xFF000000);
            box.setVisibility(GONE);
            FrameLayout lb = new FrameLayout(getContext());
            logo = new ImageView(getContext()); logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            lb.addView(logo, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            ph = h.text(h.fSemi, 7.2f * k, Color.WHITE); ph.setGravity(Gravity.CENTER);
            GradientDrawable pb = new GradientDrawable(); pb.setCornerRadius(3.1f * k * R); pb.setColor(0x1FFFFFFF); ph.setBackground(pb);
            lb.addView(ph, new LayoutParams(h.px(15.6f * k), h.px(15.6f * k), Gravity.CENTER));
            box.addView(lb, new LinearLayout.LayoutParams(h.px(31.2f * k), h.px(15.6f * k)));
            chan = h.text(h.fSemi, 2.52f * k, 0xB3FFFFFF);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); cl.topMargin = h.px(2f * k);
            box.addView(chan, cl);
            title = h.text(h.fSemi, 3.84f * k, Color.WHITE); title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END); title.setMaxWidth(h.px(96 * k));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); tl.topMargin = h.px(.8f * k);
            box.addView(title, tl);
            FrameLayout bar = new FrameLayout(getContext());
            GradientDrawable bb = new GradientDrawable(); bb.setCornerRadius(R); bb.setColor(0x26FFFFFF); bar.setBackground(bb);
            bar.setClipChildren(true);
            dash = new View(getContext());
            GradientDrawable dd = new GradientDrawable(); dd.setCornerRadius(R); dd.setColor(0xD9FFFFFF); dash.setBackground(dd);
            bar.addView(dash, new LayoutParams(h.px(6.5f * k), LayoutParams.MATCH_PARENT));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(h.px(21.6f * k), Math.max(2, h.px(.54f * k))); bl.topMargin = h.px(2.2f * k);
            box.addView(bar, bl);
            final float span = 21.6f * k * R;
            va = ValueAnimator.ofFloat(-6.5f * k * R, span);
            va.setDuration(1100); va.setRepeatCount(ValueAnimator.INFINITE);
            va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { @Override public void onAnimationUpdate(ValueAnimator a) {
                if (box.getVisibility() != VISIBLE || !box.isShown()) { a.cancel(); return; }
                dash.setTranslationX((Float) a.getAnimatedValue());
            } });
        }
        private final ValueAnimator va;
        void showNow() { box.setVisibility(VISIBLE); if (!va.isRunning()) va.start(); }
        void set(final Ch ch, String prog) {
            chan.setText(chNum(ch) + " · " + ch.n);
            title.setText(prog);
            title.setVisibility(prog.isEmpty() ? GONE : VISIBLE);
            ph.setText(ch.n.isEmpty() ? "" : ch.n.substring(0, 1));
            key = ch.u;
            logo.setImageDrawable(null); ph.setVisibility(VISIBLE);
            if (!ch.l.isEmpty()) logo(ch.l, logo.getLayoutParams() == null ? h.px(31) : h.px(31), h.px(16), new HomeImages.Done() { @Override public void done(Bitmap b) {
                if (b != null && key.equals(ch.u)) { logo.setImageBitmap(b); ph.setVisibility(INVISIBLE); }
            } });
        }
    }

    // ================================================================== the backdrop (painted once)

    private Bitmap bakeBg() {
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // the on-air studio
        p.setShader(new LinearGradient(0, 0, 0, H, new int[] { 0xFF0B0A10, 0xFF060509, 0xFF040306 }, new float[] { 0, .6f, 1 }, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        glow(c, W * .08f, -H * .10f, W * .45f, H * .5f, 0x38FF453A, .7f);
        glow(c, W * 1f, H * 1.1f, W * .55f, H * .55f, 0x386E3CE6, .7f);
        glow(c, W * .5f, H * .45f, W * .3f, H * .35f, 0x1AFF453A, 1f);
        out.setHasAlpha(false);
        return out;
    }
    private static void glow(Canvas c, float cx, float cy, float rx, float ry, int col, float stop) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(0, 0, 1, new int[] { col, col & 0xFFFFFF }, new float[] { 0, stop }, Shader.TileMode.CLAMP));
        c.save(); c.translate(cx, cy); c.scale(rx, ry); c.drawCircle(0, 0, 1, p); c.restore();
    }

    /** the backdrop: one solid picture, drawn around the preview while its picture shows through.
        The preview isn't a boxed window: like the artwork behind Movies' featured title, the picture sits at
        the top right, flush with the screen's edge, and melts into the page on its left, bottom and top edges
        (the backdrop drawn over those edges, fading out towards the picture). */
    private final class BgView extends View {
        private boolean hole;
        private BitmapShader bgSh; private Bitmap shFor;
        private final Paint fp = new Paint();
        BgView(android.content.Context c) { super(c); }
        void setHole(boolean on) { if (on != hole) { hole = on; invalidate(); } }
        @Override protected void onDraw(Canvas c) {
            if (bgBmp == null) { c.drawColor(0xFF070609); return; }
            if (!hole) { c.drawBitmap(bgBmp, 0, 0, null); return; }
            RectF r = pvBox();
            int x = (int) Math.floor(r.left), y = (int) Math.floor(r.top), x1 = (int) Math.ceil(r.right), y1 = (int) Math.ceil(r.bottom);
            // four solid pieces around the box (plain rectangles: cheap)
            draw(c, 0, 0, W, y); draw(c, 0, y1, W, H); draw(c, 0, y, x, y1); draw(c, x1, y, W, y1);
            // the melting edges
            float k = r.width() / (PV_W * R);       // (the fades grow with the picture)
            /* the three fades are made again only when the picture or the box changes (they were made on every
               frame, six shaders each time, while the preview grew or shrank) */
            if (shFor != bgBmp || x != ex || y != ey || x1 != ex1 || y1 != ey1) {
                if (shFor != bgBmp) { shFor = bgBmp; bgSh = new BitmapShader(bgBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP); }
                ex = x; ey = y; ex1 = x1; ey1 = y1;
                eL = new ComposeShader(bgSh, new LinearGradient(x, 0, x + 11 * R * k, 0, new int[] { 0xFF000000, 0x8C000000, 0 }, new float[] { 0, .45f, 1 }, Shader.TileMode.CLAMP), PorterDuff.Mode.DST_IN);
                eB = new ComposeShader(bgSh, new LinearGradient(0, y1, 0, y1 - 6.5f * R * k, new int[] { 0xFF000000, 0x8C000000, 0 }, new float[] { 0, .45f, 1 }, Shader.TileMode.CLAMP), PorterDuff.Mode.DST_IN);
                eT = new ComposeShader(bgSh, new LinearGradient(0, y, 0, y + 2.2f * R * k, 0x99000000, 0, Shader.TileMode.CLAMP), PorterDuff.Mode.DST_IN);
            }
            if (!h.portrait) edge(c, x, y, x + 11 * R * k, y1, eL);
            edge(c, x, y1 - 6.5f * R * k, x1, y1, eB);
            edge(c, x, y, x1, y + 2.2f * R * k, eT);
        }
        private int ex = -1, ey = -1, ex1 = -1, ey1 = -1;
        private Shader eL, eB, eT;
        private void edge(Canvas c, float l, float t, float r, float b, Shader sh) {
            fp.setShader(sh);
            c.drawRect(l, t, r, b, fp);
        }
        private void draw(Canvas c, int l, int t, int r, int b) { if (r <= l || b <= t) return; c.save(); c.clipRect(l, t, r, b); c.drawBitmap(bgBmp, 0, 0, null); c.restore(); }
    }

    // ================================================================== the guide grid (one view)

    private final class GuideView extends View {
        private final Paint recDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float sx, sy;                       // scroll (px): time across, channels down
        private ValueAnimator anim;
        private final TextPaint tTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG), tSmall = new TextPaint(Paint.ANTI_ALIAS_FLAG),
                tNum = new TextPaint(Paint.ANTI_ALIAS_FLAG), tName = new TextPaint(Paint.ANTI_ALIAS_FLAG), tSlot = new TextPaint(Paint.ANTI_ALIAS_FLAG),
                tCat = new TextPaint(Paint.ANTI_ALIAS_FLAG), tNow = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint nowP = new Paint(Paint.ANTI_ALIAS_FLAG), barP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), focusP = new Paint(Paint.ANTI_ALIAS_FLAG), ln = new Paint(Paint.ANTI_ALIAS_FLAG), ringP = new Paint(Paint.ANTI_ALIAS_FLAG), focusFill = new Paint(Paint.ANTI_ALIAS_FLAG), raiseP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();
        private final Map<String, StaticLayout> names = new HashMap<>();
        private final Set<String> asked = new HashSet<>();
        private final float rowPx, head, chw, cellH, perMs, padL;
        private Shader liveShader;
        private final Soft raiseSh = new Soft(1f * R, 1.8f * R, 0xB3000000), barGlow = new Soft(.11f * R, .5f * R, 0x8CE0352B), nowGlow = new Soft(.8f * R, .8f * R, 0x80E0352B);

        GuideView(android.content.Context c) {
            super(c);
            // the channel tiles start at the screen's left edge, their contents line up with the rest of the tab
            padL = GUIDE_X * R;
            rowPx = ROWH * R; head = HEAD * R; chw = padL + CHW * R; cellH = (ROWH - .4f) * R; perMs = PER_HOUR * R / 3600000f;
            tTitle.setTypeface(h.fSemi); tTitle.setTextSize(1.12f * R); tTitle.setColor(Color.WHITE);
            tSmall.setTypeface(h.fReg); tSmall.setTextSize(.92f * R); tSmall.setColor(0x99FFFFFF);
            tNum.setTypeface(h.fReg); tNum.setTextSize(.92f * R); tNum.setColor(0x99FFFFFF); tNum.setTextAlign(Paint.Align.RIGHT);
            tName.setTypeface(h.fMed); tName.setTextSize(1.05f * R); tName.setColor(Color.WHITE);
            tSlot.setTypeface(h.fReg); tSlot.setTextSize(1.05f * R); tSlot.setColor(0x99FFFFFF);
            tSlot.setColor(0x80FFFFFF); tSlot.setTextSize(1f * R);
            tNow.setTypeface(h.fSemi); tNow.setTextSize(.85f * R); tNow.setColor(Color.WHITE); tNow.setTextAlign(Paint.Align.CENTER); tNow.setLetterSpacing(.04f);
            nowP.setColor(ACCENT);
            barP.setColor(ACCENT);
            tCat.setTypeface(h.fSemi); tCat.setTextSize(1f * R); tCat.setColor(Color.WHITE);
            focusP.setColor(0xFFFF453A);
            focusFill.setColor(0x33FFFFFF);
            ringP.setStyle(Paint.Style.STROKE); ringP.setStrokeWidth(Math.max(2, .14f * R)); ringP.setColor(0xD9FFFFFF);
            raiseP.setColor(0xFF34333D);
            setLayerType(LAYER_TYPE_NONE, null);
        }

        float totalW() { return HOURS * PER_HOUR * R; }

        void build() {
            if (getWidth() == 0) { post(new Runnable() { @Override public void run() { if (getWidth() > 0) build(); } }); return; }
            names.clear();
            sx = (floor(System.currentTimeMillis()) - base) * perMs;
            sy = Math.max(0, (row - 1) * rowPx);
            clampScroll();
            invalidate();
        }
        private void clampScroll() {
            float maxX = Math.max(0, totalW() - (getWidth() - chw)), maxY = Math.max(0, list.size() * rowPx - (getHeight() - head));
            sx = Math.max(0, Math.min(sx, maxX)); sy = Math.max(0, Math.min(sy, maxY));
        }

        /** keep the focused channel and programme on screen */
        void reveal(boolean smooth) {
            if (getHeight() == 0 || list.isEmpty()) return;
            float y = sy, x = sx, viewH = getHeight() - head, viewW = getWidth() - chw;
            float top = row * rowPx, bot = top + rowPx;
            if (top < y + rowPx * .6f) y = Math.max(0, top - rowPx);
            else if (bot > y + viewH - rowPx * .6f) y = bot + rowPx - viewH;
            float slot = SLOT * perMs;
            long[] p = progAt(list.get(row), t);
            float cs = (Math.max(p != null ? Math.max(p[0], floor(t)) : t, base) - base) * perMs;
            float ce = p != null ? (Math.min(p[1], end()) - base) * perMs : cs + slot;
            if (cs < x) x = Math.max(0, cs - slot * .5f);
            else if (Math.min(ce, cs + viewW * .8f) > x + viewW) x = Math.min(ce, cs + viewW * .8f) - viewW + slot * .3f;
            float maxX = Math.max(0, totalW() - viewW), maxY = Math.max(0, list.size() * rowPx - viewH);
            x = Math.max(0, Math.min(x, maxX)); y = Math.max(0, Math.min(y, maxY));
            if (Math.abs(x - sx) < 1 && Math.abs(y - sy) < 1) return;
            if (anim != null) anim.cancel();
            if (!smooth) { sx = x; sy = y; invalidate(); return; }
            final float x0 = sx, y0 = sy, x1 = x, y1 = y;
            anim = ValueAnimator.ofFloat(0, 1);
            anim.setDuration(260);
            anim.setInterpolator(TileView.EASE);
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { @Override public void onAnimationUpdate(ValueAnimator a) {
                float f = (Float) a.getAnimatedValue();
                sx = x0 + (x1 - x0) * f; sy = y0 + (y1 - y0) * f;
                invalidate();
            } });
            anim.start();
        }

        private Bitmap logoFor(final Ch ch) {
            if (ch.l.isEmpty()) return null;
            return chLogo(ch.l, this);
        }

        @SuppressWarnings({ "unchecked", "deprecation" })
        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), hh = getHeight();
            if (w == 0 || list.isEmpty()) return;
            c.clipRect(0, 0, w, hh);
            long now = System.currentTimeMillis(), endT = end();
            boolean focusGuide = zone.equals("guide") && !full;
            float gr = 1.4f * R;
            fill.setShader(null);
            int first = Math.max(0, (int) Math.floor(sy / rowPx) - 1), last = Math.min(list.size() - 1, (int) Math.ceil((sy + hh - head) / rowPx) + 1);
            // logos a little beyond what's on screen are fetched ahead (asked before the visible ones, which go first)
            for (int i = Math.min(list.size() - 1, last + 8); i > last; i--) chLogo(list.get(i).l, this);
            for (int i = Math.max(0, first - 8); i < first; i++) chLogo(list.get(i).l, this);
            // programmes
            c.save();
            c.clipRect(chw, head - .5f * R, w, hh);       // (a little above the rows: room for the raised show)
            long leftT = base + (long) (sx / perMs);      // (a long: a float can't hold today's time to the minute)
            for (int i = first; i <= last; i++) {
                Ch ch = list.get(i);
                float y = head + i * rowPx - sy;
                Object[] pg = progs(ch);
                boolean focusRow = focusGuide && i == row;
                long[] fp = focusRow ? progAt(ch, t) : null;
                if (pg == null || ((List<long[]>) pg[0]).isEmpty()) {
                    for (long s = base; s < endT; s += 2 * 3600000L) cell(c, y, s, s + 2 * 3600000L, ch.n, "No guide information", 3, focusRow && fp == null && Math.abs(s - t) < 2 * 3600000L && t >= s, leftT, false, now);
                    continue;
                }
                List<long[]> tt = (List<long[]>) pg[0]; List<String[]> xx = (List<String[]>) pg[1];
                boolean focusedOne = false;
                long[] nearest = null; long nd = Long.MAX_VALUE;
                if (focusRow && fp == null) for (long[] se : tt) { long d = Math.abs(se[0] - t); if (d < nd) { nd = d; nearest = se; } }
                long gapFrom = base;
                float tiny = 2.4f * R;
                for (int j = 0; j < tt.size(); j++) {
                    long[] se = tt.get(j);
                    if (se[1] <= base || se[0] >= endT) continue;
                    // nothing listed between two programmes: a quiet tile instead of a hole
                    if (se[0] - gapFrom > 60000L) gap(c, y, gapFrom, se[0], leftT, now);
                    // a run of very short programmes reads as one tile
                    int k = j;
                    if ((se[1] - se[0]) * perMs < tiny) {
                        while (k + 1 < tt.size() && (tt.get(k + 1)[1] - tt.get(k + 1)[0]) * perMs < tiny && tt.get(k + 1)[0] - tt.get(k)[1] <= 60000L && tt.get(k + 1)[0] < endT) k++;
                    }
                    if (k > j) {
                        long rs = se[0], re = tt.get(k)[1];
                        gapFrom = Math.max(gapFrom, re);
                        float x0 = chw + (Math.max(rs, base) - base) * perMs - sx, x1 = chw + (Math.min(re, endT) - base) * perMs - sx;
                        if (!(x1 < chw || x0 > w)) {
                            boolean f = false;
                            for (int q = j; q <= k; q++) { long[] o = tt.get(q); if (focusRow && (fp != null ? fp[0] == o[0] : nearest == o)) f = true; }
                            if (f) focusedOne = true;
                            int state = re <= now ? 0 : rs <= now ? 2 : 1;
                            cell(c, y, rs, re, (k - j + 1) + " short programmes", fmtShort(rs) + " – " + fmtShort(re), state, f, leftT, false, now);
                        }
                        j = k;
                        continue;
                    }
                    gapFrom = Math.max(gapFrom, se[1]);
                    float x0 = chw + (Math.max(se[0], base) - base) * perMs - sx, x1 = chw + (Math.min(se[1], endT) - base) * perMs - sx;
                    if (x1 < chw || x0 > w) continue;
                    int state = se[1] <= now ? 0 : se[0] <= now ? 2 : 1;          // past · live · later
                    boolean f = focusRow && (fp != null ? fp[0] == se[0] : nearest == se);
                    if (f) focusedOne = true;
                    String title = xx.get(j)[0].isEmpty() ? "Untitled" : xx.get(j)[0];
                    boolean bell = state == 1 && remHas(ch.u, se[0]);
                    recMark = recMarks.contains(ch.u + "|" + se[0]);
                    cell(c, y, se[0], se[1], title, fmtShort(se[0]) + " – " + fmtShort(se[1]), state, f, leftT, bell, now);
                }
                if (endT - gapFrom > 60000L) gap(c, y, gapFrom, endT, leftT, now);
            }
            drawPendingFocus(c);
            c.restore();
            // channel names (pinned)
            c.save();
            c.clipRect(0, head, chw, hh);
            for (int i = first; i <= last; i++) {
                Ch ch = list.get(i);
                float y = head + i * rowPx - sy;
                boolean cur = i == row, on = playing != null && playing.u.equals(ch.u);
                if (cur) { fill.setColor(0x1AFFFFFF); rf.set(-2 * R, y, chw - .6f * R, y + cellH); c.drawRoundRect(rf, 1.2f * R, 1.2f * R, fill); }
                tNum.setColor(on ? 0xFFFF8A80 : 0x99FFFFFF); tName.setColor(on ? 0xFFFF8A80 : Color.WHITE);
                float cx = padL + .9f * R, cy = y + cellH / 2f;
                String num = chNum(ch);
                // the whole number (long ones shrink a little to fit rather than losing digits)
                tNum.setTextSize(.92f * R);
                float nw = tNum.measureText(num), room = 3.4f * R;
                if (nw > room) tNum.setTextSize(Math.max(.6f * R, .92f * R * room / nw));
                c.drawText(num, cx + 2.6f * R, cy + .32f * R, tNum);
                float lx = cx + 3.3f * R;
                Bitmap lg = logoFor(ch);
                if (lg != null) {
                    float bw = 3.4f * R, bh = 2.5f * R, s = Math.min(bw / lg.getWidth(), bh / lg.getHeight());
                    float dw = lg.getWidth() * s, dh = lg.getHeight() * s;
                    rf.set(lx + (bw - dw) / 2, cy - dh / 2, lx + (bw + dw) / 2, cy + dh / 2);
                    c.drawBitmap(lg, null, rf, LOGO_P);
                } else {
                    fill.setColor(0x1FFFFFFF); rf.set(lx, cy - 1.25f * R, lx + 3.4f * R, cy + 1.25f * R); c.drawRoundRect(rf, .6f * R, .6f * R, fill);
                    tTitle.setTextAlign(Paint.Align.CENTER); c.drawText(ch.n.isEmpty() ? "" : ch.n.substring(0, 1), lx + 1.7f * R, cy + tTitle.getTextSize() * .35f, tTitle); tTitle.setTextAlign(Paint.Align.LEFT);
                }
                float tx = lx + 3.4f * R + .7f * R;
                boolean fav = favs.contains(ch.u);
                float tw = chw - .6f * R - tx - .9f * R - (fav ? 1.4f * R : 0);
                StaticLayout sl = names.get(ch.u + "|" + (int) tw);
                if (sl == null) {
                    sl = new StaticLayout(ch.n, 0, ch.n.length(), tName, Math.max(1, (int) tw), Layout.Alignment.ALIGN_NORMAL, 1.05f, 0, false, TextUtils.TruncateAt.END, Math.max(1, (int) tw));
                    if (sl.getLineCount() > 2) {
                        int e = sl.getLineEnd(1);
                        String two = ch.n.substring(0, Math.max(0, e - 1)).trim() + "…";
                        sl = new StaticLayout(two, tName, Math.max(1, (int) tw), Layout.Alignment.ALIGN_NORMAL, 1.05f, 0, false);
                    }
                    if (names.size() > 600) names.clear();      // (kept for the rows around you, not all of them)
                    names.put(ch.u + "|" + (int) tw, sl);
                }
                c.save(); c.translate(tx, cy - sl.getHeight() / 2f); sl.draw(c); c.restore();
                if (fav) { tSmall.setColor(0xFFFFD60A); c.drawText("★", chw - .6f * R - 1.9f * R, cy + tSmall.getTextSize() * .35f, tSmall); tSmall.setColor(0x99FFFFFF); }
            }
            c.restore();
            // the times along the top
            c.save(); c.clipRect(chw, 0, w, head);
            tmpDate.setTime(now); String today = fDay.format(tmpDate);
            // where the NOW chip goes (times it would cover aren't drawn; no half-cut times at the left edge)
            tNow.setTypeface(h.fSemi); tNow.setTextSize(.9f * R);
            float chipW = tNow.measureText("NOW") + 1.6f * R, chipX = chw + (now - base) * perMs - sx;
            boolean chip = chipX >= chw - 4 * R && chipX <= w;
            chipX = Math.max(chipX, chw + chipW / 2 + .6f * R);
            for (long s = base; s < endT; s += SLOT) {
                float x = chw + (s - base) * perMs - sx;
                if (x < chw || x > w) continue;
                tmpDate.setTime(s);
                String lab = fDay.format(tmpDate).equals(today) ? fmt(s) : fWd.format(tmpDate) + " " + fmt(s);
                if (chip && x < chipX + chipW / 2 + .5f * R && x + tSlot.measureText(lab) > chipX - chipW / 2 - .5f * R) continue;
                c.drawText(lab, x, .6f * R + tSlot.getTextSize(), tSlot);
            }
            float nx = chw + (now - base) * perMs - sx;
            if (nx >= chw - 4 * R && nx <= w) {
                tNow.setTypeface(h.fSemi); tNow.setTextSize(.9f * R); tNow.setColor(Color.WHITE); tNow.setTextAlign(Paint.Align.CENTER);
                float tw = tNow.measureText("NOW"), pw = tw + 1.6f * R, ph = 1.6f * R, py = .45f * R;
                nx = Math.max(nx, chw + pw / 2 + .6f * R);     // near the left edge it stays whole, pinned at the edge
                rf.set(nx - pw / 2, py, nx + pw / 2, py + ph);
                nowGlow.draw(c, rf, 0, 1f); c.drawRoundRect(rf, ph / 2, ph / 2, nowP);
                c.drawText("NOW", nx, py + ph / 2 + tNow.getTextSize() * .36f, tNow);
            }
            c.restore();
            // the category button in the corner
            String cn = cat < cats.size() ? cats.get(cat).name : "";
            float bw = Math.min(20.8f * R, tCat.measureText(cn) + 3.6f * R), by = (head - 2.3f * R) / 2;
            fill.setColor(0x38FF453A);
            c.save(); c.translate(padL, 0);
            rf.set(0, by, bw, by + 2.3f * R); c.drawRoundRect(rf, 1.2f * R, 1.2f * R, fill);
            tCat.setColor(Color.WHITE);
            ln.setColor(tCat.getColor()); ln.setStrokeWidth(.2f * R); ln.setStrokeCap(Paint.Cap.ROUND);
            float ix = .75f * R, iy = by + 2.3f * R / 2;
            c.drawLine(ix, iy - .42f * R, ix + 1.15f * R, iy - .42f * R, ln); c.drawLine(ix, iy, ix + 1.15f * R, iy, ln); c.drawLine(ix, iy + .42f * R, ix + .72f * R, iy + .42f * R, ln);
            c.drawText(TextUtils.ellipsize(cn, tCat, bw - 2.9f * R, TextUtils.TruncateAt.END).toString(), 2.45f * R, iy + tCat.getTextSize() * .35f, tCat);
            c.restore();
        }

        /** how far into a show that's on now: a thin red bar along the tile's bottom (like the hero's) */
        private void liveBar(Canvas c, float x0, float x1, float y, long s, long e, long now) {
            float frac = Math.max(.02f, Math.min(1f, (now - s) / (float) Math.max(1, e - s)));
            // the whole show's span (it may start before the left edge): the bar is its elapsed part, where visible
            float xs = chw + (s - base) * perMs - sx + 1f * R, xe = chw + (e - base) * perMs - sx - .4f * R - 1f * R;
            if (xe <= xs) return;
            float l = Math.max(xs, x0 + 1f * R), rr = Math.min(xs + frac * (xe - xs), x1 - 1f * R);
            if (rr <= l) return;
            float bh = .22f * R, by = y + cellH - .55f * R - bh;
            rf.set(l, by, rr, by + bh);
            barGlow.draw(c, rf, 0, 1f); c.drawRoundRect(rf, bh / 2, bh / 2, barP);
        }

        /** the red edge of a show that's on now: a sliver that follows the tile's rounded left side */
        private Path crescentPath; private float crescentR;
        private Path crescent(float r) {
            if (crescentPath != null && crescentR == r) return crescentPath;
            float t = .3f * R, w = 6 * r;
            Path a = new Path(); a.addRoundRect(new RectF(0, 0, w, cellH), r, r, Path.Direction.CW);
            Path b = new Path(); b.addRoundRect(new RectF(t, 0, w + t, cellH), r, r, Path.Direction.CW);
            a.op(b, Path.Op.DIFFERENCE);
            Path keep = new Path(); keep.addRect(new RectF(0, 0, 2 * r, cellH), Path.Direction.CW);
            a.op(keep, Path.Op.INTERSECT);
            crescentPath = a; crescentR = r;
            return a;
        }

        /** a stretch with nothing listed */
        private void gap(Canvas c, float y, long s, long e, long leftT, long now) {
            float wRem = (Math.min(e, end()) - Math.max(s, base)) * perMs / R;
            if (wRem < .8f) return;
            cell(c, y, s, e, wRem > 7 ? "No information" : "", "", 3, false, leftT, false, now);
        }

        /** one programme */
        /* The show you're on is drawn last, raised: it grows a little, lifts, and casts a shadow, like a
           card picked up off the page (no glow). The lift eases in each time the focus moves. */
        private boolean pendF, drawingF; private float pY; private long pS, pE, pLeft, pNow; private String pTitle, pSub; private int pState; private boolean pBell, pRec, recMark;
        private String focusKey = ""; private float lift = 1f; private ValueAnimator liftAnim;
        private void drawPendingFocus(Canvas c) {
            if (!pendF) return;
            pendF = false;
            String key = row + ":" + pS;
            if (!key.equals(focusKey)) {
                focusKey = key; lift = 0f;
                if (liftAnim != null) liftAnim.cancel();
                liftAnim = ValueAnimator.ofFloat(0f, 1f);
                liftAnim.setDuration(200); liftAnim.setInterpolator(TileView.EASE);
                liftAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { @Override public void onAnimationUpdate(ValueAnimator a) { lift = (Float) a.getAnimatedValue(); invalidate(); } });
                liftAnim.start();
            }
            drawingF = true;
            recMark = pRec;
            cell(c, pY, pS, pE, pTitle, pSub, pState, true, pLeft, pBell, pNow);
            drawingF = false;
        }

        private void cell(Canvas c, float y, long s, long e, String title, String sub, int state, boolean f, long leftT, boolean bell, long now) {
            if (f && !drawingF) { pendF = true; pY = y; pS = s; pE = e; pTitle = title; pSub = sub; pState = state; pLeft = leftT; pBell = bell; pRec = recMark; pNow = now; return; }
            long endT = end();
            float x0 = chw + (Math.max(s, base) - base) * perMs - sx, x1 = chw + (Math.min(e, endT) - base) * perMs - sx - .4f * R;
            if (x1 <= x0) return;
            float r = 1f * R;
            rf.set(x0, y, x1, y + cellH);
            int alpha = (state == 0 || state == 3) && !f ? 115 : state == 1 && !f ? 219 : 255;
            if (f) {
                float grow = .45f * R * lift, swell = .28f * R * lift, up = .22f * R * lift;
                y -= up;
                rf.set(x0 - grow, y - swell, x1 + grow, y + cellH + swell);
                raiseSh.draw(c, rf, (.2f + .7f * lift) * R, .3f + .7f * lift);
                float cr = r;
                c.drawRoundRect(rf, cr, cr, raiseP);
                if (state == 2) liveBar(c, x0, x1, y, s, e, now);
                float o = ringP.getStrokeWidth() / 2;
                rf.inset(o, o); c.drawRoundRect(rf, Math.max(0, cr - o), Math.max(0, cr - o), ringP); rf.inset(-o, -o);
                x0 -= grow; x1 += grow;
            } else if (state == 2) {
                fill.setColor(0x1FFFFFFF);
                c.drawRoundRect(rf, r, r, fill);
                liveBar(c, x0, x1, y, s, e, now);
            } else {
                fill.setColor(state == 3 ? 0x09FFFFFF : 0x0FFFFFFF);
                c.drawRoundRect(rf, r, r, fill);
            }
            float wRem = (x1 - x0) / R;
            if (wRem <= 4 && !bell && !recMark) return;
            // a long show that began before the left edge: its title slides into view
            // (measured from where the tile is drawn from: a show that began before the guide's start is drawn from its start)
            long sv = Math.max(s, base);
            float shift = leftT > sv ? Math.min((leftT - sv) * perMs, Math.max(0, (x1 - x0) - 9 * R)) : 0;
            float tx = x0 + 1f * R + shift, avail = x1 - tx - 1f * R;
            if (avail <= R) return;
            tTitle.setColor(Color.WHITE); tTitle.setAlpha(f ? 255 : alpha);
            tSmall.setColor(f ? 0xD9FFD9D6 : 0x99FFFFFF); if (!f && state == 0) tSmall.setAlpha(77);
            c.save(); c.clipRect(x0, y, x1, y + cellH);
            float ty = y + .75f * R + tTitle.getTextSize();
            float bx = tx;
            if (bell) { tTitle.setColor(0xFFFFD60A); c.drawText("🔔", bx, ty, tTitle); bx += tTitle.measureText("🔔 "); tTitle.setColor(Color.WHITE); }
            if (recMark) { recDot.setColor(0xFFFF453A); float rr = .42f * R; c.drawCircle(bx + rr, ty - tTitle.getTextSize() * .36f, rr, recDot); bx += 2 * rr + .55f * R; }
            if (wRem > 4) {
                c.drawText(TextUtils.ellipsize(title, tTitle, Math.max(1, x1 - bx - 1f * R), TextUtils.TruncateAt.END).toString(), bx, ty, tTitle);
                c.drawText(TextUtils.ellipsize(sub, tSmall, Math.max(1, avail), TextUtils.TruncateAt.END).toString(), tx, ty + .25f * R + tSmall.getTextSize(), tSmall);
            }
            c.restore();
            tTitle.setAlpha(255); tSmall.setColor(0x99FFFFFF);
        }
    }

    // ================================================================== the channel list (full screen)

    private final class ZapView extends View {
        private final TextPaint tName = new TextPaint(Paint.ANTI_ALIAS_FLAG), tProg = new TextPaint(Paint.ANTI_ALIAS_FLAG), tNum = new TextPaint(Paint.ANTI_ALIAS_FLAG), tHead = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();
        private Shader bgShader;
        ZapView(android.content.Context c) {
            super(c);
            tName.setTypeface(h.fSemi); tName.setTextSize(1.62f * R);
            tProg.setTypeface(h.fReg); tProg.setTextSize(1.32f * R);
            tNum.setTypeface(h.fReg); tNum.setTextSize(1.32f * R); tNum.setTextAlign(Paint.Align.RIGHT);
            tHead.setTypeface(h.fSemi); tHead.setTextSize(1.8f * R); tHead.setColor(0x99FFFFFF); tHead.setLetterSpacing(.08f);
        }
        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), hh = getHeight();
            if (bgShader == null) bgShader = new LinearGradient(0, 0, w, 0, new int[] { 0xEB08080C, 0xEB08080C, 0x0008080C }, new float[] { 0, .7f, 1 }, Shader.TileMode.CLAMP);
            fill.setShader(bgShader); c.drawRect(0, 0, w, hh, fill); fill.setShader(null);
            float padL = 2.9f * R, padR = 4.8f * R, top = 2f * R;
            String head = (cat < cats.size() ? cats.get(cat).name : "Channels").toUpperCase(Locale.US);
            c.drawText(TextUtils.ellipsize(head, tHead, w - padL - padR, TextUtils.TruncateAt.END).toString(), padL + .7f * R, top + tHead.getTextSize(), tHead);
            float listTop = top + tHead.getTextSize() + 1f * R, hintH = 2.6f * R, listBot = hh - hintH - 1.5f * R;
            float rowH = 6.2f * R;
            int n = list.size();
            if (n == 0) return;
            int visible = (int) ((listBot - listTop) / rowH);
            int firstIdx = Math.max(0, Math.min(zapSel - visible / 2, n - visible));
            long now = System.currentTimeMillis();
            rebase();
            c.save(); c.clipRect(0, listTop, w, listBot);
            for (int k = 0; k < visible + 1 && firstIdx + k < n; k++) {
                int i = firstIdx + k;
                Ch ch = list.get(i);
                float y = listTop + k * rowH;
                boolean f = i == zapSel, on = playing != null && playing.u.equals(ch.u);
                rf.set(padL, y, w - padR, y + rowH - .4f * R);
                if (f) { fill.setColor(Color.WHITE); c.drawRoundRect(rf, 1.2f * R, 1.2f * R, fill); }
                if (on) { fill.setColor(0xFFFF453A); c.drawRect(rf.left, rf.top + .3f * R, rf.left + .3f * R, rf.bottom - .3f * R, fill); }
                float cy = y + (rowH - .4f * R) / 2;
                tNum.setColor(f ? 0x8C000000 : 0x99FFFFFF);
                c.drawText(chNum(ch), padL + 4.6f * R, cy + tNum.getTextSize() * .35f, tNum);
                float lx = padL + 5.6f * R;
                Bitmap lg = chLogo(ch.l, this);
                if (lg != null) {
                    float bw = 5.28f * R, bh = 3.6f * R, s = Math.min(bw / lg.getWidth(), bh / lg.getHeight());
                    rf.set(lx + (bw - lg.getWidth() * s) / 2, cy - lg.getHeight() * s / 2, lx + (bw + lg.getWidth() * s) / 2, cy + lg.getHeight() * s / 2);
                    c.drawBitmap(lg, null, rf, LOGO_P);
                }
                float tx = lx + 6.4f * R, tw = w - padR - tx - R;
                long[] p = progAt(ch, now);
                String pt = p != null ? progText(ch, p)[0] : "";
                tName.setColor(f ? 0xFF111111 : Color.WHITE);
                String nm = ch.n + (favs.contains(ch.u) ? " ★" : "");
                c.drawText(TextUtils.ellipsize(nm, tName, tw, TextUtils.TruncateAt.END).toString(), tx, cy - (pt.isEmpty() ? -tName.getTextSize() * .35f : .3f * R), tName);
                if (!pt.isEmpty()) {
                    tProg.setColor(f ? 0x99000000 : 0x99FFFFFF);
                    c.drawText(TextUtils.ellipsize(pt, tProg, tw, TextUtils.TruncateAt.END).toString(), tx, cy + tProg.getTextSize() * .95f, tProg);
                    float pr = (now - p[0]) / (float) (p[1] - p[0]);
                    float by = cy + tProg.getTextSize() * 1.45f;
                    fill.setColor(0x59808080); rf.set(tx, by, tx + 14.4f * R, by + .36f * R); c.drawRoundRect(rf, R, R, fill);
                    fill.setColor(f ? 0xFF111111 : Color.WHITE); rf.set(tx, by, tx + 14.4f * R * Math.max(0, Math.min(1, pr)), by + .36f * R); c.drawRoundRect(rf, R, R, fill);
                }
            }
            c.restore();
            tProg.setColor(0x99FFFFFF);
            c.drawText("▲▼ choose · OK watch · ◀ close", padL + .7f * R, hh - 1.5f * R, tProg);
        }
    }

    // ================================================================== for the diagnostic

    String playerDiag() {
        NativePlayer np = h.act.nativePlayer();
        String st = ""; try { st = np == null ? "" : np.stats(); } catch (Throwable ignored) { }
        return status() + " | " + (np == null ? "no player" : np.diag()) + " | stalls " + stalls + " | last error " + lastErr + " | " + st;
    }
    String status() { return chans.size() + " channels, " + list.size() + " in " + (cat < cats.size() ? cats.get(cat).name : "?") + (playing != null ? ", playing" + (ready ? "" : " (tuning)") : ""); }
}
