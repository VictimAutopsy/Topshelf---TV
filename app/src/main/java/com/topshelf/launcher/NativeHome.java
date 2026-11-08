package com.topshelf.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Home, drawn by Android: the photo slideshow with its slow zoom, the glass dock, tab bar and top
 * bar, the app tiles and the All-apps drawer, the Top Shelf row, photo mode and the screensaver.
 * Every movement is a view-property animation that Android runs on its own render thread, so it
 * stays smooth whatever else is happening. Settings, search, menus of other screens and the other
 * tabs still come from the page (shown over or instead of this) until they are rebuilt too.
 */
final class NativeHome extends FrameLayout {
    private int COLS = 5;
    private static final float TW_REM = 19.4f;
    private float GAP_REM = 2.75f;
    /* A phone held upright: the screen is 56 rem wide (and as tall as it is), apps in three columns, the tabs in a
       bar along the bottom that stays up (the panes keep BOT rem clear above it). */
    boolean portrait;
    float BOT;
    /* Settings › Home › Menu: the side menu (glass down the left), or the tab bar across the top with the round
       buttons at the top right, as before */
    boolean side = true;
    /* Settings › Home › Bar style (with the bar across the top): classic (the tab pill and the round-button
       pill), float (words on the photo, no box), strip (one glass band across the top) or capsule (one
       glass capsule holding the tabs and the buttons). Upright on a phone it's always classic. */
    String barStyle = "classic";
    /* Settings › Home › Look › Home style: classic (widgets, Continue watching, the app bar if it's on),
       gallery (just the photo, with a small label: the time, the weather, what's next) or billboard (movies,
       shows and games taking turns full screen, Billboard). The last two have no app bar (All apps: ▼). */
    String homeStyle = "classic";
    /* Settings › Home › Look › Side menu style (with the side menu): panel (the glass panel), rail (a slim strip
       of icons always at Home's left edge that opens with the names), card (a compact floating card, the tools
       in one row) or glance (a wider panel with what's on right now: your team's game, what you're watching). */
    String menuStyle = "panel";
    static final float RAIL_CLOSED = 7f, RAIL_OPEN = 25f;
    private LinearLayout menuHead;                 // card: the time and weather above the card
    private int rowStart = -1;                     // card / glance: where the row of buttons starts (◀ ▶ along it)
    private final List<String> topLabels = new ArrayList<>();
    private TextView nowHead, nowGame, nowCw;
    private SportsData.Ev nowGameEv; private JSONObject nowCwM;
    private boolean railOpen;
    /* Settings › Home › Look: glass (as before) or one of TopShelf's own — shelf (Backlit Shelf), ink (Photo Ink),
       air (On Air), spot (Spotlight). See GlassPanel. */
    static String lookPref(JSONObject S) {
        String v = S.optString("look", "glass");
        return v.equals("shelf") || v.equals("ink") || v.equals("air") || v.equals("spot") ? v : "glass";
    }
    /** Photo Ink: the photo's own deep colour — the hue its colourful pixels lean to, made dark enough for white words */
    static int inkOf(Bitmap b) {
        try {
            Bitmap sm = Bitmap.createScaledBitmap(b, 32, 18, true);
            double cx = 0, cy = 0, wsum = 0, satSum = 0; int n = 0;
            float[] hsv = new float[3];
            for (int y = 0; y < 18; y++) for (int x = 0; x < 32; x++) {
                Color.colorToHSV(sm.getPixel(x, y), hsv);
                double w = hsv[1] * (.35 + hsv[2]);
                cx += Math.cos(Math.toRadians(hsv[0])) * w; cy += Math.sin(Math.toRadians(hsv[0])) * w; wsum += w;
                satSum += hsv[1]; n++;
            }
            if (sm != b) sm.recycle();
            float hue = wsum < 1e-3 ? 220 : (float) ((Math.toDegrees(Math.atan2(cy, cx)) + 360) % 360);
            float sat = Math.max(.32f, Math.min(.62f, (float) (satSum / Math.max(1, n)) * 1.3f));
            return Color.HSVToColor(new float[] { hue, Math.max(.45f, Math.min(.8f, sat * 1.25f)), .4f });
        } catch (Throwable t) { return 0xFF1F2E45; }
    }
    static String menuStylePref(JSONObject S) {
        String b = S.optString("menuStyle", "panel");
        return b.equals("rail") || b.equals("card") || b.equals("glance") ? b : "panel";
    }
    boolean rail() { return side && menuStyle.equals("rail"); }
    private Billboard billboard;
    private LinearLayout gallery, galleryCap;
    private TextView gTime, gDate, gNext, gCapT, gCapB;
    private Runnable gAct;
    private String wxLine = "";
    static String homeStylePref(JSONObject S) {
        String b = S.optString("homeStyle", "classic");
        return b.equals("gallery") || b.equals("billboard") ? b : "classic";
    }
    private View barScrim;
    private LinearLayout cornerL, cornerR;
    private final List<View> barExtras = new ArrayList<>();
    private static String barStylePref(JSONObject S) {
        String b = S.optString("barStyle", "classic");
        return b.equals("float") || b.equals("strip") || b.equals("capsule") ? b : "classic";
    }
    private static final String[] TAB_IDS = { "home", "movies", "series", "sports", "live" };
    private static final String[] TAB_NAMES = { "Home", "Movies", "TV Shows", "Sports", "Live TV" };
    private static final String[] DOCK_HINTS = { "youtube", "nuvio", "netflix", "plex", "disney", "max", "prime video", "hulu", "stremio",
            "apple tv", "jellyfin", "kodi", "spotify", "paramount", "peacock", "twitch" };

    final MainActivity act;
    final Bridge bridge;
    final SharedPreferences prefs;
    final HomeImages imgs = new HomeImages();
    final Handler main = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    final Typeface fReg, fMed, fSemi;

    // geometry
    float R, SW, SH; private float TW, TH, PITCH, M, DOCK_PANEL_Y, DOCK_Y, DOCK_PANEL_H;
    int W, H;

    // layers
    private FrameLayout photoBox;
    private final ImageView[] photo = new ImageView[2];
    private View aurora, dim, scrim;
    private FrameLayout content, dockCells, drawerGrid, tshelf, tsStrip;
    private GlassPanel dock, drawer, tabs, topbar, pause;
    private LinearLayout widgets;            // Home's widgets (the time, the weather, what's on next)
    private View glow;
    private LinearLayout tabsRow, topRow, caption, bigClock;
    private TextView[] tabViews;
    private final List<View> topItems = new ArrayList<>();   // tabs then the round buttons
    private final List<String> topActs = new ArrayList<>();
    private TextView bcNext;
    private String nextLine;
    private TextView clock, wx, hint, tsHead, capSub, capTitle, capBy, shelfHint, bcTime, bcDate, tip;
    private FrameLayout launchOv;
    private GlassPanel menuPanel;
    private LinearLayout menuRows, menuBox;
    private TextView menuTitle;
    private String menuFrom = "grid";
    /** a menu's title (a game, a channel…) above its choices; long choices get a wider menu */
    private void menuHead(String title, List<String> labels) {
        menuTitle.setText(title == null ? "" : title);
        menuTitle.setVisibility(title == null || title.isEmpty() ? GONE : VISIBLE);
        menuTop = 0; menuTitleText = title == null ? "" : title;
        int longest = 0; for (String l : labels) longest = Math.max(longest, l.length());
        LayoutParams lp = (LayoutParams) menuBox.getLayoutParams();
        lp.width = px(longest > 26 || (title != null && title.length() > 34) ? 32 : 22);
        menuBox.setLayoutParams(lp);
        menuFrom = mode.equals("menu") ? menuFrom : mode;
    }
    private final List<GlassPanel> panels = new ArrayList<>();

    // state
    static final class App { String id, label, tint = "#2a2a2e"; boolean banner, special; }
    private final List<App> items = new ArrayList<>();
    private final Map<String, TileView> tiles = new HashMap<>();
    private int focus = 0, topSel = 0, tsSel = 0, menuSel = 0;
    private String mode = "grid";      // grid | top | shelf | tshelf | move | menu
    private String tab = "home";       // home | movies | series (drawn here); sports and live are still the page's
    private NativeVod vod;
    private NativeLive live;
    private NativeSports sports;
    private NativeSheet sheet;
    /* Movies and TV Shows each keep their own screen, so going between them (resting on the tab in the
       top bar) shows rows that are already made instead of rebuilding them */
    private NativeVod vodM, vodS;
    private NativeVod vodFor(String id) {
        if (!id.equals("series")) return vodM;
        if (vodS == null) { vodS = new NativeVod(this); vodS.setVisibility(INVISIBLE); addView(vodS, indexOfChild(vodM) + 1, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)); }
        return vodS;
    }
    private NativeFind find;
    private NativeDialog dlg;
    private String dlgFrom = "grid";
    private String findFrom = "grid";
    private String owner = "native";
    private float drawerScroll = 0;
    private boolean deep, paused, saver; boolean linkOn;
    /* Without the app bar (Settings › App bar: Hidden, the default) Home is just the photo: "rest" is that state —
       nothing focused, ◀ ▶ change the photo, ▼ or OK opens the app drawer (which then holds every app), ▲ the bars. */
    private boolean rest = true;
    private boolean noDock() { return !"classic".equals(homeStyle) || !S.optBoolean("dock", false); }
    private boolean inDock(int i) { return !noDock() && i < COLS; }
    private int drawerRow(int i) { return noDock() ? i / COLS : i / COLS - 1; }
    private float tileX(int i) { int cc = i % COLS; return inDock(i) ? M + cc * (TW + GAP_REM) : M - 3 + cc * (TW + GAP_REM); }
    private float tileY(int i) { return inDock(i) ? DOCK_Y : 3 + drawerRow(i) * PITCH; }
    JSONObject S = new JSONObject();
    private JSONObject icons = new JSONObject();

    // photos
    private JSONArray photos = new JSONArray();
    private int pIndex = -1, layer = 0, showToken = 0;
    private String photoStatus = "Loading…";
    private final Bitmap[][] glassBmp = new Bitmap[2][];   // per photo layer: {light, frosted}
    private long lastKey = SystemClock.uptimeMillis();
    private final List<Runnable> menuActs = new ArrayList<>();
    private JSONArray tsItems = new JSONArray();
    private String tsHeadText = "Popular now";

    NativeHome(MainActivity act, Bridge bridge) {
        super(act);
        this.act = act; this.bridge = bridge;
        prefs = act.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE);
        String lk = "glass";
        try { lk = lookPref(new JSONObject(prefs.getString("ui.settings", "{}"))); } catch (Exception ignored) { }
        GlassPanel.look = lk;
        if (lk.equals("air")) {
            // On Air: the condensed type of a broadcast's on-screen graphics
            fReg = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
            fMed = Typeface.create("sans-serif-condensed-medium", Typeface.NORMAL);
            fSemi = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        } else {
            fReg = Typeface.createFromAsset(act.getAssets(), "fonts/Inter-Regular.otf");
            fMed = Typeface.createFromAsset(act.getAssets(), "fonts/Inter-Medium.otf");
            fSemi = Typeface.createFromAsset(act.getAssets(), "fonts/Inter-SemiBold.otf");
        }
        setClipChildren(false);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    // ================================================================== build

    private boolean built;

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w == 0 || h == 0 || (w == W && h == H && built)) return;
        W = w; H = h;
        main.post(new Runnable() { @Override public void run() { try { build(); } catch (Throwable t) { act.nativeHomeBroken(t); } } });
    }

    int px(float rem) { return Math.round(rem * R); }
    FrameLayout.LayoutParams at(float xRem, float yRem, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = px(xRem); lp.topMargin = px(yRem);
        return lp;
    }
    /* On a phone the hints speak of the finger, not the remote: "OK" is a tap, "hold OK" a hold, ◀ ▶ and ▲ ▼ a swipe. */
    private static final java.util.regex.Pattern OK_W = java.util.regex.Pattern.compile("\\bOK(?= ·| on | again| to | \\()");
    static String touchWords(String s) {
        if (s == null || s.isEmpty()) return s;
        String t = s.replace("Press OK", "Tap").replace("press OK", "tap").replace("hold OK", "hold").replace("◀ ▶", "swipe").replace("▲ ▼", "swipe up/down");
        return OK_W.matcher(t).replaceAll("Tap");
    }
    TextView text(Typeface tf, float sizeRem, int color) {
        TextView t = act.isTv() ? new TextView(getContext()) : new TextView(getContext()) {
            @Override public void setText(CharSequence text, BufferType type) {
                if (text instanceof String && (((String) text).contains("OK") || ((String) text).contains("◀") || ((String) text).contains("▲"))) text = touchWords((String) text);
                super.setText(text, type);
            }
        };
        t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeRem * R);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private void build() {
        removeAllViews(); panels.clear(); tiles.clear();
        portrait = H > W;
        R = portrait ? W / 56f : Math.min(W / 120f, H / 67.5f);
        SW = W / R; SH = H / R;
        readSettings();
        side = !"top".equals(S.optString("nav", "side"));
        barStyle = side || portrait ? "classic" : barStylePref(S);
        homeStyle = homeStylePref(S);
        menuStyle = side ? menuStylePref(S) : "panel";
        menuHead = null; rowStart = -1; railOpen = false; nowHead = nowGame = nowCw = null;
        barExtras.clear(); cornerL = cornerR = null; barScrim = null;
        COLS = portrait ? 3 : 5; GAP_REM = portrait ? 2.2f : 2.75f; BOT = portrait && !side ? 8.6f : 0;
        TW = portrait ? (SW - 7 - (COLS - 1) * GAP_REM) / COLS : TW_REM; TH = TW * 9 / 16f; PITCH = TH + 5.8f; DOCK_PANEL_H = TH + 3.6f;     // (no names under the dock's icons: just room for the focused one to grow)
        M = (SW - COLS * TW - (COLS - 1) * GAP_REM) / 2f;
        DOCK_PANEL_Y = SH - 2.8f - DOCK_PANEL_H - BOT; DOCK_Y = DOCK_PANEL_Y + 1.8f;
        // Live TV's channel list is read in the background a few seconds after start (ready before it's opened)
        main.postDelayed(new Runnable() { @Override public void run() { try { NativeLive.prepare(bridge, main, null); } catch (Throwable ignored) { } } }, 4000);
        // your sports fetched in the background, so the Sports tab has them when it opens
        main.postDelayed(new Runnable() { @Override public void run() { try { if (built) sportsData().warm(); } catch (Throwable ignored) { } } }, 20000);
        // TV Shows' pane is made while nothing's happening, not on the first visit (its first opening stuttered)
        main.postDelayed(new Runnable() { @Override public void run() {
            if (!built || vodM == null) return;
            if (SystemClock.uptimeMillis() - lastKey < 3000) { main.postDelayed(this, 3000); return; }
            try { vodFor("series"); } catch (Throwable ignored) { }
        } }, 9000);
        Context c = getContext();

        // --- photos (6% larger than the screen, like the page: room for the slow zoom)
        /* While the drawer is open its glass (an opaque blurred copy of the photo) hides the photo
           behind it, so the photo isn't drawn there at all: only around the drawer's edges. */
        photoBox = new FrameLayout(c) {
            @Override protected void dispatchDraw(Canvas cv) {
                if (photoHole == null) { super.dispatchDraw(cv); return; }
                cv.save(); cv.clipOutRect(photoHole); super.dispatchDraw(cv); cv.restore();
            }
        };
        addView(photoBox, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        aurora = new View(c);
        aurora.setBackground(new Aurora());
        photoBox.addView(aurora, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        for (int i = 0; i < 2; i++) {
            photo[i] = new ImageView(c);
            photo[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            photo[i].setAlpha(0f);
            photoBox.addView(photo[i], photoLp());
        }
        dim = new View(c);
        dim.setBackgroundColor(Color.BLACK);
        dim.setAlpha(0f);
        addView(dim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        // the shade at the top and bottom (only where it's not clear: the middle quarter is left alone)
        scrim = new FrameLayout(c) { @Override public boolean hasOverlappingRendering() { return false; } };
        View sTop = new View(c), sBot = new View(c);
        sTop.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { 0x6B000000, 0x00000000 }));
        sBot.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { 0x00000000, 0x14000000, 0x73000000 }));
        ((FrameLayout) scrim).addView(sTop, new LayoutParams(LayoutParams.MATCH_PARENT, Math.round(H * .25f)));
        LayoutParams sbl = new LayoutParams(LayoutParams.MATCH_PARENT, H - Math.round(H * .5f));
        sbl.topMargin = Math.round(H * .5f);
        ((FrameLayout) scrim).addView(sBot, sbl);
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // --- the dock and its tiles
        // fading the dock away draws each piece faded, rather than the whole layer off-screen first
        content = new FrameLayout(c) { @Override public boolean hasOverlappingRendering() { return false; } };
        content.setClipChildren(false);
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        billboard = null; gallery = null; galleryCap = null;
        if (homeStyle.equals("billboard")) {
            billboard = new Billboard(this);
            billboard.setVisibility(INVISIBLE);
            addView(billboard, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        } else if (homeStyle.equals("gallery")) buildGallery(c);
        dock = glass(3.4f, 0x0DFFFFFF);
        content.addView(dock, at(3, DOCK_PANEL_Y, px(SW - 6), px(DOCK_PANEL_H)));
        glow = new View(c);
        glow.setBackground(new Glow());
        dock.addView(glow, new LayoutParams(px(60), px(22)));
        glow.setTranslationY(-px(11) - px(DOCK_PANEL_H * 0.1f));
        dockCells = new FrameLayout(c);
        dockCells.setClipChildren(false);
        content.addView(dockCells, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        // a small handle under the dock: the app drawer is down there (Down opens it)
        hint = text(fMed, .95f, 0xB8FFFFFF);
        GradientDrawable hb = new GradientDrawable(); hb.setColor(0x8CFFFFFF); hb.setCornerRadius(px(.3f));
        hint.setBackground(hb);
        hint.setElevation(px(.2f));
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(px(5.2f), Math.max(3, px(.42f)), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hl.bottomMargin = px(1.2f);
        content.addView(hint, hl);

        // --- Home's widgets, top left: the time, the weather, and what's on next (your team's game, a new episode)
        widgets = new LinearLayout(c);
        widgets.setOrientation(portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        content.addView(widgets, at(3, side ? 3 : 9, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        buildWidgets();

        // --- Top Shelf row
        tshelf = new FrameLayout(c);
        tshelf.setClipChildren(true);
        tshelf.setAlpha(0f);
        content.addView(tshelf, at(3, DOCK_PANEL_Y - 17.5f, px(SW - 6), px(16.6f)));
        tsHead = text(fSemi, 1.2f, 0xE0FFFFFF);
        tsHead.setShadowLayer(.6f * R, 0, .1f * R, 0x99000000);
        tshelf.addView(tsHead, at(3, 0, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        tsStrip = new FrameLayout(c);
        tsStrip.setClipChildren(false);
        tshelf.addView(tsStrip, at(0, 2.2f, LayoutParams.MATCH_PARENT, px(14.4f)));

        // --- All-apps drawer (below the screen until you go down)
        drawer = glass(3.4f, 0x4D141418);
        drawer.setLiquidAllowed(false);
        addView(drawer, at(3, 8.8f, px(SW - 6), px(SH - 8.8f - 2.2f - BOT)));
        // the grid scrolls by moving up inside the drawer; the drawer's rounded shape does the clipping,
        // not the grid's own box (which moves up with it and was cutting off the bottom row)
        drawer.setClipChildren(false);
        drawerGrid = new FrameLayout(c);
        drawerGrid.setClipChildren(false);
        drawer.addView(drawerGrid, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        drawer.setRectClipChild(drawerGrid);
        drawer.setTranslationY(px(SH - 8.8f - 2.2f + 5));
        drawer.setVisibility(INVISIBLE);

        // --- Movies & TV Shows (over Home's layers, under the bars)
        vod = vodM = new NativeVod(this);
        vodS = null;
        // Continue watching changed (you watched something, Trakt synced): the rows and the top shelf again
        // addons added, removed or switched on/off: Movies & TV Shows make their rows again (addons' own catalogs are rows)
        Addons.onChange = new Runnable() { @Override public void run() {
            if (vodM != null) vodM.addonsChanged();
            if (vodS != null) vodS.addonsChanged();
        } };
        Progress.onChange = new Runnable() { @Override public void run() {
            if (vodM != null) vodM.cwChanged();
            if (vodS != null) vodS.cwChanged();
            cwRefresh();
        } };
        vod.setVisibility(INVISIBLE);
        addView(vod, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        tab = "home";

        if (side) {
        /* --- the side menu (it was a tab bar across the top and a bar of round buttons at the top right):
           glass down the left, with the time and weather at its head, the tabs, then Search, Photo mode, the phone
           remote and Settings. It slides in when you go left (or up) off the edge of a screen, and away again with
           ▶ or Back; the tab you rest on opens behind it. */
        boolean mCard = menuStyle.equals("card"), mGl = menuStyle.equals("glance"), mRail = menuStyle.equals("rail");
        tabs = mCard ? glass(2.6f, 0xDB1E1F26) : mGl ? glass(0f, 0xF0121318) : mRail ? glass(0f, 0x8C0E0F14) : glass(2.6f, 0x33141418);
        tabs.setLiquidAllowed(false);
        tabsRow = new LinearLayout(c);
        tabsRow.setOrientation(LinearLayout.VERTICAL);
        if (mCard) tabsRow.setPadding(px(1f), px(1f), px(1f), px(1f));
        else if (mGl) tabsRow.setPadding(px(2f), px(3.2f), px(2f), px(2.4f));
        else if (mRail) tabsRow.setPadding(px(1.1f), px(2.6f), px(1.1f), px(2.4f));
        else tabsRow.setPadding(px(1f), px(1.6f), px(1f), px(1.4f));
        tabs.addView(tabsRow, new LayoutParams(LayoutParams.MATCH_PARENT, mCard ? LayoutParams.WRAP_CONTENT : LayoutParams.MATCH_PARENT));
        if (mCard) {
            addView(tabs, at(3, 10.5f, px(24), LayoutParams.WRAP_CONTENT));
            menuHead = new LinearLayout(c);
            menuHead.setAlpha(0f);
            addView(menuHead, at(3.8f, 6.6f, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        } else if (mGl) addView(tabs, at(0, 0, px(32), px(SH)));
        else if (mRail) addView(tabs, at(0, 0, px(RAIL_CLOSED), px(SH)));
        else addView(tabs, at(1.6f, 1.6f, px(Math.min(22f, SW - 4)), px(SH - 3.2f)));
        tabs.setAlpha(0f); if (!mRail) tabs.setTranslationX(-px(3));
        // (the round-button bar is gone: kept, empty and hidden, for the code that still mentions it)
        topbar = glass(3f, 0x12FFFFFF);
        topRow = new LinearLayout(c);
        topbar.addView(topRow, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        addView(topbar, new LayoutParams(1, 1));
        topbar.setVisibility(GONE);
        tip = text(fMed, 1.05f, Color.WHITE);
        tip.setAlpha(0f);
        addView(tip, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        } else if (!barStyle.equals("classic")) {
        buildStyledBar(c);
        } else {
        // --- tab bar
        tabs = glass(3f, 0x12FFFFFF);
        tabsRow = new LinearLayout(c);
        tabsRow.setPadding(px(.55f), px(.55f), px(.55f), px(.55f));
        tabViews = new TextView[TAB_IDS.length];
        for (int i = 0; i < TAB_IDS.length; i++) {
            TextView t = text(fSemi, portrait ? 1.3f : 1.35f, 0xC7FFFFFF);
            t.setText(TAB_NAMES[i]);
            float tp = portrait ? 1.2f : 2.1f;
            t.setPadding(px(tp), px(.95f), px(tp), px(.95f));
            t.setBackground(pill(Color.TRANSPARENT));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = px(.3f);
            tabsRow.addView(t, lp);
            tabViews[i] = t;
        }
        tabs.addView(tabsRow, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, (portrait ? Gravity.BOTTOM : Gravity.TOP) | Gravity.CENTER_HORIZONTAL);
        if (portrait) tl.bottomMargin = px(2f); else tl.topMargin = px(2.3f);
        addView(tabs, tl);

        // --- top bar (round buttons, weather, clock)
        topbar = glass(3f, 0x12FFFFFF);
        topRow = new LinearLayout(c);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.setPadding(px(.55f), px(.55f), px(1.4f), px(.55f));
        topbar.addView(topRow, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT);
        bl.topMargin = px(2.3f); bl.rightMargin = px(portrait ? 2.4f : 4.6f);
        addView(topbar, bl);
        tip = text(fMed, 1.05f, Color.WHITE);
        tip.setShadowLayer(.6f * R, 0, .1f * R, 0x99000000);
        tip.setAlpha(0f);
        addView(tip, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }
        buildTopRow();

        // --- photo mode: caption, hint, pause badge, screensaver clock
        caption = new LinearLayout(c);
        caption.setOrientation(LinearLayout.VERTICAL);
        capSub = text(fSemi, 1.05f, 0x9EFFFFFF); capSub.setLetterSpacing(.08f); capSub.setAllCaps(true);
        capTitle = text(fSemi, 3f, Color.WHITE); capTitle.setMaxLines(2); capTitle.setLetterSpacing(-.015f);
        capBy = text(fReg, 1.1f, 0x9EFFFFFF);
        for (TextView t : new TextView[] { capSub, capTitle, capBy }) t.setShadowLayer(R, 0, .1f * R, 0x8C000000);
        caption.addView(capSub);
        LinearLayout.LayoutParams ct = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); ct.topMargin = px(.45f);
        caption.addView(capTitle, ct);
        LinearLayout.LayoutParams cb = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); cb.topMargin = px(.5f);
        caption.addView(capBy, cb);
        caption.setAlpha(0f);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(px(Math.min(62, SW - 10)), LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT);
        cl.leftMargin = px(6); cl.bottomMargin = px(SH - 60);
        addView(caption, cl);
        shelfHint = text(fReg, 1.1f, 0x9EFFFFFF);
        shelfHint.setText("◀ ▶ browse photos  ·  OK pause  ·  ▼ or Back to return");
        shelfHint.setShadowLayer(.6f * R, 0, .1f * R, 0x99000000);
        shelfHint.setAlpha(0f);
        FrameLayout.LayoutParams sh = new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT);
        sh.rightMargin = px(6); sh.bottomMargin = px(5.4f);
        addView(shelfHint, sh);
        pause = glass(3f, 0x4D141418);
        TextView pt = text(fSemi, 1.1f, Color.WHITE); pt.setText("Slideshow paused");
        pt.setPadding(px(1), px(.5f), px(1), px(.5f));
        pause.addView(pt, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        pause.setAlpha(0f);
        addView(pause, at(6, 3.2f, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        bigClock = new LinearLayout(c);
        bigClock.setOrientation(LinearLayout.VERTICAL);
        bcTime = text(fMed, 7f, Color.WHITE); bcDate = text(fMed, 1.5f, 0xDDFFFFFF);
        bcNext = text(fSemi, 1.45f, Color.WHITE);
        bcNext.setSingleLine(true); bcNext.setEllipsize(android.text.TextUtils.TruncateAt.END); bcNext.setMaxWidth(px(60));
        for (TextView t : new TextView[] { bcTime, bcDate, bcNext }) t.setShadowLayer(1.6f * R, 0, .2f * R, 0x80000000);
        bigClock.addView(bcTime); bigClock.addView(bcDate);
        LinearLayout.LayoutParams bnl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); bnl.topMargin = px(1f);
        bigClock.addView(bcNext, bnl);
        bcNext.setVisibility(GONE);
        bigClock.setAlpha(0f);
        addView(bigClock, at(6, 4.2f, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        // --- app menu and launch screen
        menuPanel = glass(2.2f, 0x94262629);
        menuPanel.setLiquidAllowed(false);
        menuRows = new LinearLayout(c);
        menuRows.setOrientation(LinearLayout.VERTICAL);
        menuRows.setPadding(px(.8f), px(.8f), px(.8f), px(.8f));
        menuBox = new LinearLayout(c);
        menuBox.setOrientation(LinearLayout.VERTICAL);
        menuTitle = text(fSemi, 1.05f, 0x99FFFFFF);
        menuTitle.setPadding(px(2.1f), px(1.4f), px(2.1f), px(.1f));
        menuTitle.setMaxLines(2); menuTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        menuBox.addView(menuTitle, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        menuBox.addView(menuRows, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        menuPanel.addView(menuBox, new LayoutParams(px(22), LayoutParams.WRAP_CONTENT));
        menuPanel.setAlpha(0f); menuPanel.setVisibility(GONE);
        addView(menuPanel, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        launchOv = new FrameLayout(c);
        launchOv.setBackgroundColor(0xE6000000);
        launchOv.setAlpha(0f); launchOv.setVisibility(GONE);
        addView(launchOv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        int pw = Math.round(W * 1.06f), ph = Math.round(H * 1.06f);
        for (GlassPanel g : panels) g.setPhotoBox(-Math.round(W * .03f), -Math.round(H * .03f), pw, ph);
        applyGlassMode();
        built = true;
        loadApps();
        tick();
        drawTop();
        applyFocus(false, ' ');
        applyMode(false);
        if (photos.length() == 0) loadPhotos(true); else restartPhoto();
        mergeShelf();
        updateTshelf(false);
        // the start-up picture (MainActivity.showCover) goes once Home has drawn itself (and its photo has a moment)
        getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                getViewTreeObserver().removeOnPreDrawListener(this);
                postDelayed(new Runnable() { @Override public void run() { act.coverOff(""); } }, 700);
                return true;
            }
        });
    }

    private LayoutParams photoLp() {
        LayoutParams lp = new LayoutParams(Math.round(W * 1.06f), Math.round(H * 1.06f));
        lp.leftMargin = -Math.round(W * .03f); lp.topMargin = -Math.round(H * .03f);
        return lp;
    }

    GlassPanel glass(float radiusRem, int tint) {
        GlassPanel g = new GlassPanel(getContext(), radiusRem * R, tint, .13f * R);
        g.rim.setRadius(radiusRem * R);
        /* a pane with square corners (the icon rail, the wide menu, the strip across the top) has no rim of light:
           the rim's "no radius" means fully round ends, which drew a pill outline around the rail */
        if (radiusRem <= 0) g.setForeground(null);
        g.tag = tint;
        panels.add(g);
        GlassPanel.rem = R;
        g.applyLook();
        return g;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable d = new GradientDrawable() {
            // always fully round at the ends, whatever the height
            @Override protected void onBoundsChange(android.graphics.Rect b) { super.onBoundsChange(b); setCornerRadius(b.height() / 2f); }
        };
        d.setCornerRadius(2.4f * R);
        d.setColor(color);
        return d;
    }

    /* the three newer looks for the bar across the top (see barStyle) */
    private void buildStyledBar(Context c) {
        boolean fl = barStyle.equals("float"), st = barStyle.equals("strip"), cap = barStyle.equals("capsule");
        if (fl) {
            // a soft shade behind the words so they read over a bright photo (not a box)
            barScrim = new View(c);
            barScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { 0x9906080E, 0x4706080E, 0x0006080E }));
            addView(barScrim, new LayoutParams(LayoutParams.MATCH_PARENT, px(13)));
            barExtras.add(barScrim);
        }
        tabsRow = new LinearLayout(c);
        tabsRow.setGravity(Gravity.CENTER_VERTICAL);
        tabViews = new TextView[TAB_IDS.length];
        for (int i = 0; i < TAB_IDS.length; i++) {
            TextView t = text(fSemi, fl ? 1.5f : 1.35f, 0xBFFFFFFF);
            t.setText(TAB_NAMES[i]);
            if (fl) { t.setShadowLayer(.8f * R, 0, .1f * R, 0x8C000000); t.setPadding(px(1.4f), px(.75f), px(1.4f), px(1.35f)); }
            else t.setPadding(px(1.7f), px(.85f), px(1.7f), px(.85f));
            TabBg bg = new TabBg();
            bg.rad = st ? 1.1f * R : cap ? 1.3f * R : -1; bg.ringW = Math.max(2f, .2f * R);
            bg.lineW = 2f * R; bg.lineH = Math.max(2f, .28f * R); bg.lineGap = .42f * R;
            t.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = px(fl ? 1f : .25f);
            tabsRow.addView(t, lp);
            tabViews[i] = t;
        }
        topRow = new LinearLayout(c);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        if (st) {
            // one band of the same glass as the side menu, edge to edge: name, tabs, then buttons + weather + clock
            tabs = glass(0f, 0x33141418);
            tabs.setLiquidAllowed(false);
            FrameLayout row = new FrameLayout(c);
            TextView brand = text(fSemi, 1.55f, Color.WHITE);
            brand.setText("TopShelf");
            FrameLayout.LayoutParams b = new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL);
            b.leftMargin = px(4.6f);
            row.addView(brand, b);
            row.addView(tabsRow, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            FrameLayout.LayoutParams r = new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            r.rightMargin = px(3.4f);
            row.addView(topRow, r);
            tabs.addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            addView(tabs, new LayoutParams(LayoutParams.MATCH_PARENT, px(6.6f)));
        } else {
            tabs = glass(cap ? 1.9f : 3f, cap ? 0x33141418 : 0x12FFFFFF);
            if (fl) { tabs.setBare(); tabs.setLiquidAllowed(false); tabs.addView(tabsRow, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)); }
            else {
                // one capsule: the tabs, a thin divider, then the buttons
                LinearLayout row = new LinearLayout(c);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(px(.5f), px(.5f), px(.5f), px(.5f));
                row.addView(tabsRow);
                View div = new View(c);
                div.setBackgroundColor(0x38FFFFFF);
                LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Math.max(1, px(.1f)), px(2.2f));
                dl.leftMargin = px(.9f); dl.rightMargin = px(.6f);
                row.addView(div, dl);
                row.addView(topRow);
                tabs.addView(row, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            }
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            tl.topMargin = px(2.1f);
            addView(tabs, tl);
        }
        topbar = glass(3f, 0x12FFFFFF);
        if (fl) {
            // the buttons and the weather straight on the photo, at the right
            topbar.setBare(); topbar.setLiquidAllowed(false);
            topbar.addView(topRow, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT);
            bl.topMargin = px(2.3f); bl.rightMargin = px(4.2f);
            addView(topbar, bl);
        } else {
            // (no second bar: kept, empty and hidden, for the code that still mentions it)
            addView(topbar, new LayoutParams(1, 1));
            topbar.setVisibility(GONE);
        }
        if (fl || cap) {
            cornerL = new LinearLayout(c); cornerL.setGravity(Gravity.CENTER_VERTICAL);
            cornerR = new LinearLayout(c); cornerR.setGravity(Gravity.CENTER_VERTICAL);
            addView(cornerL, at(4.6f, fl ? 3f : 3.2f, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT);
            rl.topMargin = px(3.35f); rl.rightMargin = px(4.6f);
            addView(cornerR, rl);
            barExtras.add(cornerL); barExtras.add(cornerR);
        }
        tip = text(fMed, 1.05f, Color.WHITE);
        tip.setShadowLayer(.6f * R, 0, .1f * R, 0x99000000);
        tip.setAlpha(0f);
        addView(tip, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    /** a tab's (or a round button's) look in the newer bar styles: a fill, a focus ring, an underline */
    static final class TabBg extends android.graphics.drawable.Drawable {
        int fill, ring, line;
        float rad = -1, ringW, lineW, lineH, lineGap;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        void set(int f, int rg, int l) { if (f != fill || rg != ring || l != line) { fill = f; ring = rg; line = l; invalidateSelf(); } }
        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float rr = rad < 0 ? b.height() / 2f : Math.min(rad, b.height() / 2f);
            if ((fill >>> 24) != 0) { p.setStyle(Paint.Style.FILL); p.setColor(fill); r.set(b); c.drawRoundRect(r, rr, rr, p); }
            if ((ring >>> 24) != 0) {
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(ringW); p.setColor(ring);
                r.set(b); r.inset(ringW / 2f, ringW / 2f);
                float q = Math.max(0, rr - ringW / 2f);
                c.drawRoundRect(r, q, q, p);
            }
            if ((line >>> 24) != 0) {
                p.setStyle(Paint.Style.FILL); p.setColor(line);
                float cx = b.exactCenterX(), y = b.bottom - lineGap;
                r.set(cx - lineW / 2f, y - lineH, cx + lineW / 2f, y);
                c.drawRoundRect(r, lineH / 2f, lineH / 2f, p);
            }
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private void buildTopRowTop() {
        topRow.removeAllViews();
        if (cornerL != null) { cornerL.removeAllViews(); cornerR.removeAllViews(); }
        boolean styled = !barStyle.equals("classic"), fl = barStyle.equals("float"), st = barStyle.equals("strip");
        topItems.clear(); topActs.clear();
        for (TextView t : tabViews) { topItems.add(t); topActs.add("tab"); }
        String[] acts = linkOn ? new String[] { "remote", "search", "photos", "settings" } : new String[] { "search", "photos", "settings" };
        for (String a : acts) {
            ImageView b = new ImageView(getContext());
            int kind = a.equals("remote") ? HomeIcons.REMOTE : a.equals("search") ? HomeIcons.SEARCH : a.equals("photos") ? HomeIcons.PHOTOS : HomeIcons.SETTINGS;
            HomeIcons ic = new HomeIcons(kind, a.equals("search") ? 2f : 1.8f);
            b.setImageDrawable(ic);
            b.setPadding(px(.95f), px(.95f), px(.95f), px(.95f));
            if (styled) { TabBg tb = new TabBg(); tb.ringW = Math.max(2f, .2f * R); b.setBackground(tb); }
            else { GradientDrawable bg = new GradientDrawable(); bg.setShape(GradientDrawable.OVAL); bg.setColor(Color.TRANSPARENT); b.setBackground(bg); }
            if (fl) ic.setColor(Color.WHITE);
            b.setTag(ic);
            float bs = styled && !fl ? 3.5f : 3.8f;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(bs), px(bs));
            if (topRow.getChildCount() > 0) lp.leftMargin = px(styled ? .3f : .5f);
            topRow.addView(b, lp);
            topItems.add(b); topActs.add(a);
        }
        wx = text(fMed, 1.35f, Color.WHITE);
        wx.setShadowLayer(.8f * R, 0, .1f * R, 0x73000000);
        clock = text(fMed, styled && !st ? 1.9f : 1.75f, Color.WHITE);
        clock.setShadowLayer(.8f * R, 0, .1f * R, 0x73000000);
        clock.setFontFeatureSettings("tnum");
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); wl.leftMargin = px(.8f);
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); kl.leftMargin = px(.6f);
        if (fl) {
            /* the weather first, then the buttons, then the clock: all at the right (the clock in the top-left
               corner sat on the other screens' own titles, Movies, Live TV…, which are up there) */
            wl.leftMargin = 0; wl.rightMargin = px(.9f);
            topRow.addView(wx, 0, wl);
            topRow.addView(clock, kl);
        } else if (barStyle.equals("capsule")) {
            cornerR.addView(wx);
            LinearLayout.LayoutParams ck = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); ck.leftMargin = px(1.2f);
            cornerR.addView(clock, ck);
        } else {
            if (st) {
                View div = new View(getContext());
                div.setBackgroundColor(0x38FFFFFF);
                LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Math.max(1, px(.1f)), px(2f));
                dl.leftMargin = px(1f); dl.rightMargin = px(.2f);
                topRow.addView(div, dl);
            }
            topRow.addView(wx, wl);
            topRow.addView(clock, kl);
        }
        wx.setVisibility(GONE);
        tick();
    }
    private void buildTopRow() {
        buildTopRowInner();
        // On Air: the menu's names in broadcast capitals
        if (GlassPanel.look.equals("air")) for (View v : topItems) if (v instanceof TextView && ((TextView) v).getText().length() > 0) { ((TextView) v).setAllCaps(true); ((TextView) v).setLetterSpacing(.05f); }
    }
    private void buildTopRowInner() {
        if (!side) { buildTopRowTop(); return; }
        tabsRow.removeAllViews();
        if (menuHead != null) menuHead.removeAllViews();
        topItems.clear(); topActs.clear(); topLabels.clear(); rowStart = -1; nowHead = nowGame = nowCw = null;
        Context c = getContext();
        boolean panel = menuStyle.equals("panel"), card = menuStyle.equals("card"), glance = menuStyle.equals("glance"), rl = menuStyle.equals("rail");
        // the head: the time, and the weather beside it (the card has them above it)
        LinearLayout hd = new LinearLayout(c);
        hd.setGravity(Gravity.CENTER_VERTICAL);
        clock = text(fSemi, card ? 1.9f : rl ? 1.7f : 2.2f, Color.WHITE);
        clock.setFontFeatureSettings("tnum");
        wx = text(fMed, 1.3f, 0xD9FFFFFF);
        wx.setVisibility(GONE);
        if (card) {
            for (TextView t : new TextView[] { clock, wx }) t.setShadowLayer(.8f * R, 0, .1f * R, 0x99000000);
            hd.addView(clock);
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); wl.leftMargin = px(1.2f);
            hd.addView(wx, wl);
            menuHead.addView(hd);
        } else {
            hd.setPadding(px(rl ? 1f : 1.1f), 0, px(1.1f), px(1.6f));
            hd.addView(clock);
            View gap = new View(c);
            hd.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
            hd.addView(wx);
            tabsRow.addView(hd, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
        int[] tabCol = { 0xFF5A5F6E, 0xFFC2792E, 0xFF2E7FC2, 0xFF2E9A5A, 0xFFC23A2E };
        String[] tabMark = { "⌂", "M", "TV", "S", "L" };
        tabViews = new TextView[TAB_IDS.length];
        for (int i = 0; i < TAB_IDS.length; i++) {
            if (panel) tabViews[i] = menuRow(TAB_NAMES[i], new Mark(tabCol[i], tabMark[i]));
            else {
                String id = TAB_IDS[i];
                HomeIcons ic = new HomeIcons(id.equals("home") ? HomeIcons.HOME : id.equals("movies") ? HomeIcons.FILM : id.equals("series") ? HomeIcons.TV : id.equals("live") ? HomeIcons.LIVE : HomeIcons.TROPHY, 1.9f);
                tabViews[i] = menuRow(TAB_NAMES[i], ic);
                tabViews[i].setTag(ic);
            }
            topItems.add(tabViews[i]); topActs.add("tab");
        }
        // your apps: the panel lists the first three; the rail and the card the first one (TizenTube, say)
        int na = 0, max = panel ? 3 : (rl || card) ? 1 : 0;
        for (int i = 0; i < items.size() && na < max; i++) {
            App ap = items.get(i);
            if (ap.special) continue;
            if (na == 0) {
                if (panel) { TextView hl = text(fSemi, 1f, 0xB3FFFFFF); hl.setText("APPS"); hl.setLetterSpacing(.1f); hl.setPadding(px(1.1f), px(1.2f), 0, px(.3f)); tabsRow.addView(hl); }
                else divider(tabsRow);
            }
            TextView t = menuRow(ap.label, appMark(ap));
            topItems.add(t); topActs.add("app:" + ap.id);
            na++;
        }
        // menu + what's on: your team's game and what you're watching, a press away
        if (glance) {
            nowHead = text(fSemi, 1f, 0xB3FFFFFF); nowHead.setText("RIGHT NOW"); nowHead.setLetterSpacing(.12f); nowHead.setPadding(px(1.3f), px(2f), 0, px(.6f));
            tabsRow.addView(nowHead);
            nowGame = nowRow(); topItems.add(nowGame); topActs.add("now:game"); topLabels.add("");
            nowCw = nowRow(); topItems.add(nowCw); topActs.add("now:cw"); topLabels.add("");
            refreshGlance();
        }
        // the tools: rows (the panel, the rail), or one row of buttons at the foot (the card, the menu + what's on)
        if (rl || glance) tabsRow.addView(new View(c), new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        else divider(tabsRow);
        String[] acts = linkOn ? new String[] { "search", "photos", "remote", "settings" } : new String[] { "search", "photos", "settings" };
        if (panel || rl) {
            for (String a0 : acts) {
                HomeIcons ic = toolIcon(a0);
                TextView t = menuRow(toolName(a0), ic);
                t.setTag(ic);
                topItems.add(t); topActs.add(a0);
            }
        } else {
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            rowStart = topItems.size();
            if (glance) {
                for (int i = 0; i < items.size(); i++) {
                    App ap = items.get(i);
                    if (ap.special) continue;
                    TextView t = menuRow(ap.label, appMark(ap), row, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, px(4.4f)));
                    t.setPadding(px(1.2f), 0, px(1.4f), 0);
                    topItems.add(t); topActs.add("app:" + ap.id);
                    break;
                }
                row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1));
            }
            for (String a0 : acts) {
                HomeIcons ic = toolIcon(a0);
                TextView t = text(fMed, 1.2f, Color.WHITE);
                ic.setBounds(0, 0, px(2.2f), px(2.2f));
                t.setCompoundDrawables(null, ic, null, null);
                t.setGravity(Gravity.CENTER);
                t.setPadding(0, px(1.1f), 0, 0);
                GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(1.3f * R); bg.setColor(Color.TRANSPARENT);
                t.setBackground(bg);
                t.setTag(ic);
                t.setContentDescription(toolName(a0));
                LinearLayout.LayoutParams lp = card ? new LinearLayout.LayoutParams(0, px(4.6f), 1) : new LinearLayout.LayoutParams(px(4.4f), px(4.6f));
                lp.leftMargin = px(.3f);
                row.addView(t, lp);
                topItems.add(t); topActs.add(a0); topLabels.add("");
            }
            tabsRow.addView(row, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
        if (rl) railLabels(railOpen);
        tick();
    }
    private void divider(LinearLayout to) {
        View div = new View(getContext()); div.setBackgroundColor(0x24FFFFFF);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Math.max(1, px(.08f))); dl.setMargins(px(1.1f), px(1f), px(1.1f), px(.8f));
        to.addView(div, dl);
    }
    private android.graphics.drawable.Drawable appMark(App ap) {
        int tc; try { tc = Color.parseColor(ap.tint); } catch (Exception e) { tc = 0xFF3A3F4C; }
        String lt = ap.label.isEmpty() ? "?" : ap.label.substring(0, 1).toUpperCase(Locale.US);
        return new Mark(tc | 0xFF000000, lt);
    }
    private static HomeIcons toolIcon(String a0) {
        int kind = a0.equals("remote") ? HomeIcons.REMOTE : a0.equals("search") ? HomeIcons.SEARCH : a0.equals("photos") ? HomeIcons.PHOTOS : HomeIcons.SETTINGS;
        return new HomeIcons(kind, a0.equals("search") ? 2f : 1.8f);
    }
    private static String toolName(String a0) { return a0.equals("remote") ? "TV remote" : a0.equals("search") ? "Search" : a0.equals("photos") ? "Photo mode" : "Settings"; }
    /** a "right now" row: two lines (what, when) */
    private TextView nowRow() {
        TextView t = text(fMed, 1.45f, Color.WHITE);
        t.setMaxLines(2); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(px(1.3f), 0, px(1f), 0);
        t.setLineSpacing(0, 1.05f);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(1.3f * R); bg.setColor(Color.TRANSPARENT);
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, px(5.6f)); lp.topMargin = px(.3f);
        tabsRow.addView(t, lp);
        return t;
    }
    private static CharSequence twoLines(String a, String b) {
        android.text.SpannableString s = new android.text.SpannableString(a + "\n" + b);
        s.setSpan(new android.text.style.RelativeSizeSpan(.82f), a.length() + 1, s.length(), 0);
        return s;
    }
    /** the menu + what's on: your team's next (or live) game, and what you're part-way through */
    private void refreshGlance() {
        if (nowGame == null) return;
        nowGameEv = null; nowCwM = null;
        try {
            if (sportsData != null) for (SportsData.Ev e : sportsData.featuredGames(8)) if (sportsData.isMine(e)) { nowGameEv = e; break; }
        } catch (Throwable ignored) { }
        if (nowGameEv != null) {
            SportsData.Ev e = nowGameEv;
            boolean two = e.teams.size() == 2, live = e.state.equals("in");
            String what = e.card || !two ? (e.name.isEmpty() ? e.shortName : e.name) : e.teams.get(0).name + " at " + e.teams.get(1).name;
            String when = live ? "LIVE" + (two ? " · " + e.teams.get(0).score + "–" + e.teams.get(1).score : "") : sportsData.remWhen(e.at);
            nowGame.setText(twoLines(what, when));
        }
        try { List<JSONObject> cw = Progress.continueList(getContext(), null, 1); if (!cw.isEmpty()) nowCwM = cw.get(0); } catch (Throwable ignored) { }
        if (nowCwM != null) {
            String lab = nowCwM.optString("cwLabel");
            nowCw.setText(twoLines(nowCwM.optString("name"), lab.isEmpty() ? "Continue" : (lab.startsWith("Next") ? lab : "Continue · " + lab)));
        }
        nowGame.setVisibility(nowGameEv != null ? VISIBLE : GONE);
        nowCw.setVisibility(nowCwM != null ? VISIBLE : GONE);
        nowHead.setVisibility(nowGameEv != null || nowCwM != null ? VISIBLE : GONE);
    }
    /** one of the menu's rows: its mark, then its name */
    private TextView menuRow(String label, android.graphics.drawable.Drawable icon) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, px(3.9f)); lp.topMargin = px(.2f);
        return menuRow(label, icon, tabsRow, lp);
    }
    private TextView menuRow(String label, android.graphics.drawable.Drawable icon, LinearLayout parent, LinearLayout.LayoutParams lp) {
        TextView t = text(fMed, 1.5f, Color.WHITE);
        t.setText(label);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER_VERTICAL);
        icon.setBounds(0, 0, px(2.3f), px(2.3f));
        t.setCompoundDrawables(icon, null, null, null);
        t.setCompoundDrawablePadding(px(1.1f));
        t.setPadding(px(1f), 0, px(1f), 0);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(1.2f * R); bg.setColor(Color.TRANSPARENT);
        t.setBackground(bg);
        parent.addView(t, lp);
        topLabels.add(label);
        return t;
    }
    // ---- the icon rail: always at Home's left edge as icons; opens with the names
    /** the rail shows (closed) on Home itself: not over All apps, photo mode, the screensaver, or the app bar */
    boolean railShown() { return rail() && built && tab.equals("home") && !deep && !mode.equals("shelf") && !saver && noDock(); }
    private ValueAnimator railAnim;
    private void railSet(final boolean open) {
        railOpen = open;
        final LayoutParams lp = (LayoutParams) tabs.getLayoutParams();
        int to = px(open ? RAIL_OPEN : RAIL_CLOSED);
        if (railAnim != null) railAnim.cancel();
        if (open) railLabels(true);
        railAnim = ValueAnimator.ofInt(lp.width, to);
        railAnim.setDuration(open ? 240 : 200);
        railAnim.setInterpolator(TileView.EASE);
        railAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { @Override public void onAnimationUpdate(ValueAnimator va) { lp.width = (Integer) va.getAnimatedValue(); tabs.setLayoutParams(lp); } });
        railAnim.addListener(new android.animation.AnimatorListenerAdapter() { @Override public void onAnimationEnd(android.animation.Animator a) { if (!railOpen) railLabels(false); } });
        railAnim.start();
        tabs.setTintColor(open ? 0xEB0E0F14 : 0x8C0E0F14);
        updateRail();
    }
    /** the names show only while it's open (closed, just the icons) */
    private void railLabels(boolean on) {
        for (int i = 0; i < topItems.size() && i < topLabels.size(); i++) {
            View v = topItems.get(i);
            if (v instanceof TextView && !topLabels.get(i).isEmpty()) ((TextView) v).setText(on ? topLabels.get(i) : "");
        }
        if (wx != null) wx.setAlpha(on ? 1f : 0f);
    }
    /** the rail on Home (closed) or not; Home's own things make room for it */
    private void updateRail() {
        if (!rail() || tabs == null) return;
        boolean show = railOpen || railShown();
        tabs.animate().cancel();
        tabs.animate().alpha(show ? 1f : 0f).setDuration(200).start();
        boolean room = railShown();
        if (billboard != null) billboard.railRoom(room);
        if (gallery != null) gallery.animate().translationX(room ? px(RAIL_CLOSED - 2.5f) : 0).setDuration(220).start();
    }

    /** a tab's mark in the menu: a rounded square of its colour with a letter */
    private final class Mark extends android.graphics.drawable.Drawable {
        private final int col; private final String s;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Mark(int col, String s) { this.col = col; this.s = s; p.setTypeface(fSemi); p.setTextAlign(Paint.Align.CENTER); }
        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            p.setColor(col); c.drawRoundRect(new android.graphics.RectF(b), b.width() * .28f, b.width() * .28f, p);
            p.setColor(Color.WHITE); p.setTextSize(b.height() * (s.length() > 1 ? .4f : .5f));
            c.drawText(s, b.exactCenterX(), b.exactCenterY() + p.getTextSize() * .36f, p);
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    // ================================================================== settings & apps

    private void readSettings() {
        try { String s = prefs.getString("ui.settings", null); S = s != null ? new JSONObject(s) : new JSONObject(); } catch (Exception e) { S = new JSONObject(); }
        try { String s = prefs.getString("ui.icons", null); icons = s != null ? new JSONObject(s) : new JSONObject(); } catch (Exception e) { icons = new JSONObject(); }
    }
    private String sub() { return S.optString("sub", "@landscapes"); }
    private String sort() { return S.optString("sort", "top-week"); }
    private int every() { return Math.max(10, S.optInt("every", 30)); }
    private boolean motion() { return S.optBoolean("motion", true); }
    private String glassMode() { return S.optString("glass", "liquid"); }

    private static JSONArray arr(String s) { try { return s == null ? new JSONArray() : new JSONArray(s); } catch (Exception e) { return new JSONArray(); } }

    private void loadApps() {
        List<App> apps = new ArrayList<>();
        JSONArray a = arr(bridge.getApps());
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            App x = new App();
            x.id = o.optString("id"); x.label = o.optString("label"); x.banner = o.optBoolean("banner"); x.tint = o.optString("tint", "#2a2a2e");
            apps.add(x);
        }
        JSONArray order = arr(prefs.getString("ui.order", null));
        if (order.length() == 0 && !apps.isEmpty()) {
            final Map<String, Integer> rank = new HashMap<>();
            for (App x : apps) {
                String l = x.label.toLowerCase(Locale.US);
                int r = 999;
                for (int i = 0; i < DOCK_HINTS.length; i++) if (l.startsWith(DOCK_HINTS[i])) { r = i; break; }
                rank.put(x.id, r);
            }
            Collections.sort(apps, new java.util.Comparator<App>() { @Override public int compare(App p, App q) {
                int d = rank.get(p.id) - rank.get(q.id); return d != 0 ? d : p.label.compareToIgnoreCase(q.label);
            } });
            order = new JSONArray();
            for (App x : apps) order.put(x.id);
            prefs.edit().putString("ui.order", order.toString()).apply();
        }
        Set<String> hid = new HashSet<>();
        JSONArray h = arr(prefs.getString("ui.hidden", null));
        for (int i = 0; i < h.length(); i++) hid.add(h.optString(i));
        Map<String, App> byId = new LinkedHashMap<>();
        for (App x : apps) byId.put(x.id, x);
        items.clear();
        for (int i = 0; i < order.length(); i++) {
            App x = byId.remove(order.optString(i));
            if (x != null && !hid.contains(x.id)) items.add(x);
        }
        for (App x : byId.values()) if (!hid.contains(x.id)) items.add(x);
        App gear = new App(); gear.id = "__settings"; gear.label = "Settings"; gear.special = true;
        items.add(gear);
        renderTiles();
        if (tabsRow != null) { buildTopRow(); drawTop(); }      // (the menu's Apps: the first of yours)
    }

    private void saveOrder() {
        JSONArray old = arr(prefs.getString("ui.order", null));
        JSONArray out = new JSONArray();
        Set<String> shown = new HashSet<>();
        for (App a : items) if (!a.special) { out.put(a.id); shown.add(a.id); }
        for (int i = 0; i < old.length(); i++) if (!shown.contains(old.optString(i))) out.put(old.optString(i));
        prefs.edit().putString("ui.order", out.toString()).apply();
        page("window.nhReloaded && nhReloaded('order')");
    }

    private void renderTiles() {
        Set<String> keep = new HashSet<>();
        for (App a : items) keep.add(a.id);
        for (String id : new ArrayList<>(tiles.keySet())) if (!keep.contains(id)) { TileView t = tiles.remove(id); ((FrameLayout) t.getParent()).removeView(t); }
        for (int i = 0; i < items.size(); i++) {
            App a = items.get(i);
            TileView t = tiles.get(a.id);
            if (t == null) { t = new TileView(getContext(), R, fMed); t.setLabel(a.label); tiles.put(a.id, t); }
            FrameLayout home = inDock(i) ? dockCells : drawerGrid;
            if (t.getParent() != home) {
                if (t.getParent() != null) ((FrameLayout) t.getParent()).removeView(t);
                home.addView(t, new LayoutParams(px(TW), px(TH + 4)));
            }
            t.setRestShadow(home == dockCells);
            t.setLabelOn(home != dockCells);
            t.setX(px(tileX(i))); t.setY(px(tileY(i)));
            loadArt(a, t);
        }
        dock.setVisibility(noDock() ? GONE : VISIBLE);
        tshelf.setTranslationY(noDock() ? px(DOCK_PANEL_H) : 0);      // (no app bar: the Top Shelf row sits at the foot)
        hint.setVisibility(items.size() > (noDock() ? 0 : COLS) ? VISIBLE : GONE);
        focus = Math.max(0, Math.min(focus, items.size() - 1));
    }

    // ---- tile artwork (made once per look, drawn into one picture)

    private void loadArt(final App a, final TileView t) {
        JSONObject lk = a.special ? null : icons.optJSONObject(a.id);
        if (lk == null && !a.special && !a.banner && !S.optString("iconPack", "").isEmpty()) {
            try { lk = new JSONObject().put("k", "pack").put("p", S.optString("iconPack")); } catch (Exception ignored) { }
        }
        final JSONObject look = lk;
        final String key = "tile:" + a.id + ":" + (look == null ? "" : look.toString()) + ":" + a.banner;
        if (key.equals(t.artKey)) return;
        final int w = Math.round(TW * R * 1.12f), h = Math.round(TH * R * 1.12f);
        imgs.make(key, new HomeImages.Maker() { @Override public Bitmap make() {
            return TileView.bake(TileArt.render(bridge, a, look, w, h, R * 1.12f, fSemi), R);
        } }, new HomeImages.Done() { @Override public void done(Bitmap b) { if (b != null) t.setArt(b, key); } });
    }

    // ================================================================== focus & modes

    private boolean tshelfWanted() {
        if (side || !homeStyle.equals("classic")) return false;       // (with the side menu, the Top Shelf row is gone from Home: its widgets show what's next)
        if (onVod()) return false;
        String s = S.optString("topShelf", "focus");
        if (s.equals("off") || deep || !(mode.equals("grid") || mode.equals("tshelf")) || tsItems.length() == 0) return false;
        if (mode.equals("tshelf") || s.equals("always")) return true;
        if (noDock()) return false;                 // (no app bar: ▲ from the photo brings it up)
        if (cwCount > 0 && focus < COLS) return true;
        App a = focus < COLS && focus < items.size() ? items.get(focus) : null;
        return a != null && !a.special && a.label.toLowerCase(Locale.US).matches(".*(nuvio|stremio).*");
    }

    private void applyFocus(boolean animate, char dir) {
        int row = focus / COLS;
        boolean gm = mode.equals("grid") || mode.equals("move") || mode.equals("menu");
        boolean nowDeep = noDock() ? gm && !rest
                : row >= 1 && mode.equals("grid") || (mode.equals("move") && row >= 1) || (mode.equals("menu") && focus >= COLS);
        /* While All apps slides in or out it's drawn as one ready-made picture. A tile in it growing,
           shrinking or shining during the slide spoilt that picture on every frame (the whole pane and
           all its tiles drawn again, then the picture drawn: the slide ran at about 30 frames a second).
           So the drawer's tiles take their new look at once, before the slide starts. */
        boolean sliding = animate && nowDeep != deep;
        for (int i = 0; i < items.size(); i++) {
            TileView t = tiles.get(items.get(i).id);
            if (t != null) t.setFocused(i == focus && gm && !(noDock() && rest), animate && !(sliding && !inDock(i)), dir == 'l');
        }
        if (nowDeep != deep) { deep = nowDeep; applyMode(animate); }
        if (deep) revealRow(drawerRow(focus), animate);
        moveGlow(animate);
        updateTshelf(animate);
    }

    private void moveGlow(boolean animate) {
        int col = focus % COLS;
        float fx = (10 + col * 80f / (COLS - 1)) / 100f;
        float x = fx * dock.getWidth() - glow.getLayoutParams().width / 2f;
        if (animate) glow.animate().translationX(x).setDuration(500).setInterpolator(TileView.EASE).start(); else glow.setTranslationX(x);
        // the light on the glass edges stays put (top-left), like light from the room, whatever is focused
        final float la = 120f;
        if (rimAnim != null) rimAnim.cancel();
        if (!animate || rimAngle == la) { setRimAngle(la); return; }
        rimAnim = ValueAnimator.ofFloat(rimAngle, la);
        rimAnim.setDuration(500);
        rimAnim.setInterpolator(TileView.EASE);
        rimAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator va) { setRimAngle((Float) va.getAnimatedValue()); }
        });
        rimAnim.start();
    }
    private float rimAngle = -1;
    private ValueAnimator rimAnim;
    private void setRimAngle(float a) {
        rimAngle = a;
        dock.rim.setAngle(a); tabs.rim.setAngle(a); topbar.rim.setAngle(a);
    }

    private void revealRow(int rr, boolean animate) {
        float visible = drawer.getHeight() > 0 ? drawer.getHeight() / R : SH - 11;
        float top = 3 + rr * PITCH - 1.5f, bot = 3 + rr * PITCH + PITCH;
        float s = drawerScroll;
        if (top < s) s = Math.max(0, top);
        else if (bot > s + visible) s = bot - visible;
        if (s == drawerScroll) return;
        drawerScroll = s;
        if (animate) drawerGrid.animate().translationY(-px(s)).setDuration(380).setInterpolator(TileView.EASE).start();
        else drawerGrid.setTranslationY(-px(s));
    }

    /** what's showing for the current mode: drawer, photo mode, top-bar selection */
    private void applyMode(boolean animate) {
        if (onVod()) return;
        long d = animate ? 550 : 0;
        boolean shelf = mode.equals("shelf");
        /* All apps slides up over the dock, which stays where it is (fading it out meant blending all of
           it on every frame of the slide); once covered it isn't drawn at all */
        if (!(deep && !shelf)) content.setVisibility(VISIBLE);
        content.animate().alpha(shelf ? 0f : 1f).translationY(shelf ? px(10) : 0).setDuration(d).setInterpolator(TileView.EASE).start();
        /* the drawer slides up from below the screen; it isn't faded as well, because fading a pane
           full of tiles means drawing all of it off-screen first, every frame of the slide */
        final boolean open = deep && !shelf;
        if (open) drawer.setVisibility(VISIBLE);
        setPhotoHole(false);
        // (slid as one picture: the glass and every tile drawn once, not on each frame of the slide)
        /* (a ready-made layer only when it really slides on screen: asked for on a hidden drawer, the graphics
           system made it and threw it away, "Incorrectly called buildLayer on View: GlassPanel" in the log) */
        android.view.ViewPropertyAnimator da = drawer.animate().translationY(open ? 0 : px(SH - 11 + 5 - BOT)).setDuration(d).setInterpolator(TileView.EASE);
        if (d > 0 && drawer.getVisibility() == VISIBLE && drawer.getTranslationY() != (open ? 0 : px(SH - 11 + 5 - BOT))) da.withLayer();
        da.withEndAction(new Runnable() {
            @Override public void run() {
                boolean still = deep && !mode.equals("shelf");
                if (!still) drawer.setVisibility(INVISIBLE);
                content.setVisibility(still ? INVISIBLE : VISIBLE);
                setPhotoHole(still);
                // the panes that were left alone during the slide take their final shade now
                float z = deep ? .4f : 0f;
                drawer.dimTo(z, 0);
                if (still) dock.dimTo(z, 0);
            }
        }).start();
        dimPhotos(deep ? .4f : 0f, animate ? 600 : 0);
        /* The drawer slides as one ready-made picture; changing its shade on every frame meant
           redrawing the whole pane and all its tiles into that picture each frame (the slide ran at
           about 50 frames a second). So the drawer takes its shade before it slides in, and keeps it
           until it has slid away; the dock it covers is shaded once it's hidden. */
        float dz = deep ? .4f : 0f; long dms = animate ? 600 : 0;
        for (GlassPanel g : panels) {
            if (g == drawer) { if (open || !animate) g.dimTo(dz, 0); }
            else if (g == dock && open && animate) { /* covered by the drawer: shaded when the slide ends */ }
            else if (g.getVisibility() != VISIBLE || g.getAlpha() <= 0f) g.dimTo(dz, 0);
            else g.dimTo(dz, dms);
        }
        // behind the open drawer the photo is already darkened, so the top/bottom shade isn't drawn
        scrim.animate().alpha(shelf ? .55f : deep ? 0f : 1f).setDuration(animate ? 600 : 0).start();
        if (widgets != null) widgets.animate().alpha(!deep && !shelf && !saver && tab.equals("home") ? 1f : 0f).setDuration(animate ? 400 : 0).start();
        syncHomeStyle(animate);
        if (!side) {
            tabs.animate().alpha(shelf || barsHidden ? 0f : 1f).setDuration(animate ? 400 : 0).start();
            topbar.animate().alpha(shelf || barsHidden ? 0f : 1f).setDuration(animate ? 400 : 0).start();
            for (View x : barExtras) x.animate().alpha(shelf || barsHidden ? 0f : 1f).setDuration(animate ? 400 : 0).start();
        }
        caption.animate().alpha(shelf ? 1f : 0f).setDuration(animate ? 500 : 0).start();
        shelfHint.animate().alpha(shelf && !saver ? 1f : 0f).setDuration(animate ? 500 : 0).start();
        pause.animate().alpha(shelf && paused ? 1f : 0f).setDuration(300).start();
        bigClock.animate().alpha(saver ? 1f : 0f).setDuration(animate ? 600 : 0).start();
        hint.animate().alpha(!deep && !shelf && items.size() > (noDock() ? 0 : COLS) ? 1f : 0f).setDuration(400).start();
        if (!deep) { drawerScroll = 0; drawerGrid.setTranslationY(0); }
        updateBars();          // (All apps: the bars step aside)
    }

    /* The photo is darkened behind the drawer as it's drawn (a colour formula on the picture) rather
       than by a black layer over the whole screen; the layer is only used when there's no photo. */
    private float photoDim;
    private android.animation.ValueAnimator photoDimAnim;
    private void dimPhotos(float to, long ms) {
        if (photoDimAnim != null) photoDimAnim.cancel();
        if (ms <= 0 || to == photoDim) { setPhotoDim(to); return; }
        photoDimAnim = android.animation.ValueAnimator.ofFloat(photoDim, to);
        photoDimAnim.setDuration(ms);
        photoDimAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator va) { setPhotoDim((Float) va.getAnimatedValue()); }
        });
        photoDimAnim.start();
    }
    private void setPhotoDim(float v) {
        photoDim = v;
        boolean havePhoto = photo[0].getDrawable() != null || photo[1].getDrawable() != null;
        android.graphics.ColorFilter f = null;
        if (v > 0.001f) {
            float k = 1 - v;
            f = new android.graphics.ColorMatrixColorFilter(new float[] { k, 0, 0, 0, 0,  0, k, 0, 0, 0,  0, 0, k, 0, 0,  0, 0, 0, 1, 0 });
        }
        for (ImageView iv : photo) iv.setColorFilter(f);
        dim.setAlpha(havePhoto ? 0f : v);
    }

    private android.graphics.Rect photoHole;
    private void refreshHole() { if (drawer != null) setPhotoHole(deep && !mode.equals("shelf")); }
    private final Runnable holeLater = new Runnable() { @Override public void run() { refreshHole(); } };
    private void setPhotoHole(boolean on) {
        android.graphics.Rect r = null;
        on = false;   // not needed now the glass is drawn solid (the chip skips what it hides); the cut-out cost more than it saved
        if (on && drawer.isOpaqueGlass() && drawer.getTranslationY() == 0 && drawer.getWidth() > 0) {
            int rad = Math.round(drawer.radius) + 2;
            r = new android.graphics.Rect(drawer.getLeft(), drawer.getTop() + rad, drawer.getRight(), drawer.getBottom() - rad);
        }
        if (r == null ? photoHole == null : r.equals(photoHole)) return;
        photoHole = r;
        photoBox.invalidate();
    }

    private void drawTopStyled() {
        boolean top = mode.equals("top"), fl = barStyle.equals("float"), st = barStyle.equals("strip");
        for (int i = 0; i < topItems.size(); i++) {
            View v = topItems.get(i);
            boolean sel = top && i == topSel;
            if (!(v.getBackground() instanceof TabBg)) continue;
            TabBg bg = (TabBg) v.getBackground();
            if (i < TAB_IDS.length) {
                TextView t = (TextView) v;
                boolean active = TAB_IDS[i].equals(tab);
                if (fl) { bg.set(sel ? 0x40FFFFFF : 0, 0, active ? Color.WHITE : 0); t.setTextColor(sel || active ? Color.WHITE : 0xBFFFFFFF); }
                else if (st) { bg.set(sel ? GlassPanel.selFill() : active ? 0x2EFFFFFF : 0, 0, 0); t.setTextColor(sel ? GlassPanel.selText() : active ? Color.WHITE : 0xBFFFFFFF); }
                else { bg.set(active ? 0x2EFFFFFF : 0, sel ? GlassPanel.selFill() : 0, 0); t.setTextColor(sel || active ? Color.WHITE : 0xBFFFFFFF); }
            } else {
                HomeIcons ic = (HomeIcons) v.getTag();
                if (fl) { bg.set(sel ? 0x40FFFFFF : 0, 0, 0); ic.setColor(Color.WHITE); }
                else if (st) { bg.set(sel ? GlassPanel.selFill() : 0, 0, 0); ic.setColor(sel ? GlassPanel.selText() : Color.WHITE); }
                else { bg.set(0, sel ? GlassPanel.selFill() : 0, 0); ic.setColor(Color.WHITE); }
            }
        }
        drawTip(top, st ? 1.4f : .9f);
    }
    private void drawTip(boolean top, float gapRem) {
        if (top && topSel >= TAB_IDS.length) {
            final View v = topItems.get(topSel);
            String a = topActs.get(topSel);
            tip.setText(a.equals("remote") ? "TV remote" : a.equals("search") ? "Search" : a.equals("photos") ? "Photo mode" : "Settings");
            tip.measure(0, 0);
            int[] p = new int[2], me = new int[2];
            v.getLocationInWindow(p); getLocationInWindow(me);
            tip.setX(p[0] - me[0] + v.getWidth() / 2f - tip.getMeasuredWidth() / 2f);
            tip.setY(p[1] - me[1] + v.getHeight() + px(gapRem));
            tip.animate().alpha(1f).setDuration(200).start();
        } else tip.animate().alpha(0f).setDuration(150).start();
    }
    private void drawTopTop() {
        if (!barStyle.equals("classic")) { drawTopStyled(); return; }
        boolean top = mode.equals("top");
        for (int i = 0; i < topItems.size(); i++) {
            View v = topItems.get(i);
            boolean sel = top && i == topSel;
            if (i < TAB_IDS.length) {
                TextView t = (TextView) v;
                boolean active = TAB_IDS[i].equals(tab);
                int on = tab.equals("movies") ? 0x42FFB040 : tab.equals("series") ? 0x3D64D2FF : tab.equals("live") ? 0x47FF453A : tab.equals("sports") ? 0x4234C759 : 0x33FFFFFF;
                int onText = tab.equals("movies") ? 0xFFFFE2B3 : tab.equals("series") ? 0xFFD2F2FF : tab.equals("live") ? 0xFFFFD9D6 : tab.equals("sports") ? 0xFFD4FFE0 : Color.WHITE;
                t.setBackground(pill(sel ? GlassPanel.selFill() : active ? on : Color.TRANSPARENT));
                t.setTextColor(sel ? GlassPanel.selText() : active ? onText : 0xC7FFFFFF);
                t.setScaleX(1f); t.setScaleY(1f);
            } else {
                GradientDrawable bg = (GradientDrawable) v.getBackground();
                bg.setColor(sel ? GlassPanel.selFill() : Color.TRANSPARENT);
                ((HomeIcons) v.getTag()).setColor(sel ? GlassPanel.selText() : Color.WHITE);
                v.setScaleX(1f); v.setScaleY(1f);     // no growing: it pushed the circle into the bar's rounded edge
            }
        }
        if (top && topSel >= TAB_IDS.length) {
            final View v = topItems.get(topSel);
            String a = topActs.get(topSel);
            tip.setText(a.equals("remote") ? "TV remote" : a.equals("search") ? "Search" : a.equals("photos") ? "Photo mode" : "Settings");
            tip.measure(0, 0);
            int[] p = new int[2], me = new int[2];
            v.getLocationInWindow(p); getLocationInWindow(me);
            tip.setX(p[0] - me[0] + v.getWidth() / 2f - tip.getMeasuredWidth() / 2f);
            tip.setY(p[1] - me[1] + px(4.7f));
            tip.animate().alpha(1f).setDuration(200).start();
        } else tip.animate().alpha(0f).setDuration(150).start();
    }
    private void drawTop() {
        if (topSel >= topItems.size()) topSel = 0;
        if (!side) { drawTopTop(); return; }
        boolean top = mode.equals("top");
        for (int i = 0; i < topItems.size(); i++) {
            TextView t = (TextView) topItems.get(i);
            boolean sel = top && i == topSel, active = i < TAB_IDS.length && TAB_IDS[i].equals(tab);
            boolean btn = rowStart >= 0 && i >= rowStart && t.getText().length() == 0, now = topActs.get(i).startsWith("now:");
            int fillC = sel ? GlassPanel.selFill() : active ? 0x2EFFFFFF : btn || now ? 0x14FFFFFF : Color.TRANSPARENT;
            if (GlassPanel.look.equals("air")) t.setBackground(new GlassPanel.Cut(fillC));       // (On Air: bars with a cut end)
            else ((GradientDrawable) t.getBackground()).setColor(fillC);
            t.setTextColor(sel ? GlassPanel.selText() : Color.WHITE);
            t.setTypeface(sel || active ? fSemi : fMed);
            if (t.getTag() instanceof HomeIcons) ((HomeIcons) t.getTag()).setColor(sel ? GlassPanel.selText() : Color.WHITE);
        }
        menuIn(top && !saver);
    }
    private boolean menuShown;
    /** the side menu slides in (or away); Home's widgets make room for it */
    private void menuIn(boolean on) {
        if (tabs == null || on == menuShown) return;
        menuShown = on;
        if (on && nowGame != null) refreshGlance();
        if (rail()) railSet(on);
        else {
            tabs.animate().cancel();
            tabs.animate().alpha(on ? 1f : 0f).translationX(on ? 0 : -px(3)).setDuration(on ? 240 : 180).setInterpolator(TileView.EASE).start();
            if (menuHead != null) menuHead.animate().alpha(on ? 1f : 0f).setDuration(on ? 240 : 180).start();
        }
        if (widgets != null) {
            float mw = menuStyle.equals("glance") ? 32f : menuStyle.equals("card") ? 27f : menuStyle.equals("rail") ? RAIL_OPEN : Math.min(22f, SW - 4);
            float shift = mw + .6f, room = SW - 3 - widgets.getWidth() / R - 2;
            boolean fits = !portrait && shift <= room;
            float base = !deep && !mode.equals("shelf") && !saver && tab.equals("home") ? 1f : 0f;
            widgets.animate().translationX(on && fits ? px(shift) : 0).alpha(base * (on && !fits ? .25f : 1f)).setDuration(on ? 240 : 180).setInterpolator(TileView.EASE).start();
        }
    }

    // ================================================================== keys

    private boolean okDown, okLong;
    private final Runnable okHold = new Runnable() { @Override public void run() { okLong = true; longPress(); } };

    boolean key(KeyEvent e) {
        if (!built) return false;
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        /* the system's own keys go on to Android: volume and mute (the player and the title page used to take
           every key, so the remote's volume did nothing during a movie), power, the TV's input and app keys */
        if (systemKey(code)) return false;
        lastKey = SystemClock.uptimeMillis();
        // the doorbell's card (Ring): OK full screen, Back closes (before anything else, even a movie playing)
        if (doorbellView != null && doorbellView.shownNow() && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            if (!down) { if (code == KeyEvent.KEYCODE_BACK) doorbellView.back(); else doorbellView.ok(); }
            return true;
        }
        if (saver) { if (down) stopSaver(); return true; }
        if (launchOv.getVisibility() == VISIBLE && down) hideLaunch();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
        if (fySetupOpen()) { if (down && code == KeyEvent.KEYCODE_BACK) fySetupClose(); return true; }
        if (addonsOpenNow() && !mode.equals("menu")) return addonsView.key(e);
        if (vodPlayingNow() && !mode.equals("menu")) return vodPlayer.key(e);
        if (titleOpenNow() && !mode.equals("menu")) return titleView.key(e);
        if (gameOpenNow() && !mode.equals("menu")) return gamePage.key(e);
        if (ok && alertTakesOk()) { if (!down) alertView.ok(); return true; }
        if (code == KeyEvent.KEYCODE_BACK && alertView != null && alertView.shown()) { if (!down) alertView.hide(); return true; }      // (Back: the alert goes)
        // typing in Settings: the keys go to the text box (Back cancels)
        if (mode.equals("find") && find != null && find.typingNow()) return find.typingKey(e);
        if (mode.equals("sheet") && sheet != null && sheet.editingNow()) {
            if (code == KeyEvent.KEYCODE_BACK) { if (down) sheet.back(); return true; }
            return false;
        }
        if (ok) {
            if (down) {
                if (e.getRepeatCount() > 0 || okDown) return true;
                okDown = true; okLong = false;
                if (mode.equals("grid") || mode.equals("tshelf") || mode.equals("find")) main.postDelayed(okHold, 600);
            } else {
                main.removeCallbacks(okHold);
                // only a press that started here counts: a dialog (Android's "Install" confirmation…)
                // takes the press, and its release used to land on whatever was focused here
                if (!okDown) return true;
                boolean wasLong = okLong;
                okDown = false; okLong = false;
                if (!wasLong) ok();
            }
            return true;
        }
        if (!down) return true;
        // Live TV's channel keys (remotes that have them): CH+ / CH−, numbers, last channel, the guide
        if (isLive() && mode.equals("grid")) {
            if (code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_PAGE_UP) { live.chKey(1); return true; }
            if (code == KeyEvent.KEYCODE_CHANNEL_DOWN || code == KeyEvent.KEYCODE_PAGE_DOWN) { live.chKey(-1); return true; }
            if (code == KeyEvent.KEYCODE_LAST_CHANNEL) { live.lastChannel(); return true; }
            if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) { live.digit(code - KeyEvent.KEYCODE_0); return true; }
            if (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9) { live.digit(code - KeyEvent.KEYCODE_NUMPAD_0); return true; }
        }
        if (code == KeyEvent.KEYCODE_BACK) { back(); return true; }
        if (code == KeyEvent.KEYCODE_MENU) { if (mode.equals("grid")) { if (isLive()) live.hold(); else if (isSports()) sports.hold(); else if (onVod()) vod.hold(); else openMenu(); } return true; }
        char dir = code == KeyEvent.KEYCODE_DPAD_LEFT ? 'l' : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 'r' : code == KeyEvent.KEYCODE_DPAD_UP ? 'u' : code == KeyEvent.KEYCODE_DPAD_DOWN ? 'd' : 0;
        if (dir == 0) return false;
        move(dir);
        return true;
    }

    /** not on Home (Movies, TV Shows or Live TV, drawn here) */
    private boolean onVod() { return !tab.equals("home") && vod != null; }
    private boolean isLive() { return tab.equals("live") && live != null; }
    private boolean isSports() { return tab.equals("sports") && sports != null; }
    boolean vodHasKeys() { return onVod() && !isLive() && !isSports() && mode.equals("grid") && owner.equals("native") && !titleOpenNow() && !vodPlayingNow(); }

    private void move(char dir) {
        if (isLive() && mode.equals("grid")) { live.move(dir); return; }
        if (isSports() && mode.equals("grid")) { sports.move(dir); return; }
        if (onVod() && mode.equals("grid")) { vod.move(dir); return; }
        if (mode.equals("sheet") && sheet != null) { sheet.key(dir); return; }
        if (mode.equals("find") && find != null) { find.key(dir); return; }
        if (mode.equals("dialog") && dlg != null) { dlg.key(dir); return; }
        if (mode.equals("menu")) { if (dir == 'u' || dir == 'd') { menuSel = (menuSel + (dir == 'd' ? 1 : -1) + menuActs.size()) % menuActs.size(); drawMenu(); } return; }
        if (mode.equals("shelf")) {
            if (dir == 'l') showPhoto(-1); else if (dir == 'r') showPhoto(1); else exitShelf();
            return;
        }
        if (mode.equals("top") && !side) {
            if (dir == 'l' && topSel > 0) topSel--;
            else if (dir == 'r' && topSel < topItems.size() - 1) topSel++;
            else if (dir == 'd') { main.removeCallbacks(hoverTab); exitTop(); return; }
            drawTop();
            // resting on a tab opens it (the top bar keeps the remote), as before
            main.removeCallbacks(hoverTab);
            if (topSel < TAB_IDS.length && !TAB_IDS[topSel].equals(tab)) main.postDelayed(hoverTab, 520);
            return;
        }
        if (mode.equals("top")) {
            int n = topItems.size();
            if (rowStart >= 0 && topSel >= rowStart) {          // the row of buttons at the foot: ◀ ▶ along it
                if (dir == 'l') { if (topSel > rowStart) topSel--; else return; }
                else if (dir == 'r') { if (topSel < n - 1) topSel++; else { main.removeCallbacks(hoverTab); exitTop(); return; } }
                else if (dir == 'u') { int k = rowStart - 1; while (k > 0 && topItems.get(k).getVisibility() != VISIBLE) k--; topSel = Math.max(0, k); }
                else return;
            }
            else if (dir == 'u') { int k = topSel - 1; while (k >= 0 && topItems.get(k).getVisibility() != VISIBLE) k--; if (k < 0) return; topSel = k; }
            else if (dir == 'd') { int k = topSel + 1; while (k < n && topItems.get(k).getVisibility() != VISIBLE) k++; if (k >= n) return; topSel = k; }
            else if (dir == 'r') { main.removeCallbacks(hoverTab); exitTop(); return; }
            else return;
            drawTop();
            // (moving through the menu only moves the highlight: OK switches tabs)
            main.removeCallbacks(hoverTab);
            return;
        }
        if (mode.equals("tshelf")) {
            int n = tsItems.length();
            if (dir == 'l' && tsSel == 0 && side) { mode = "grid"; enterTop(); return; }
            if (dir == 'l' && tsSel > 0) tsSel--;
            else if (dir == 'r' && tsSel < n - 1) tsSel++;
            else if (dir == 'd') { mode = "grid"; applyFocus(true, ' '); return; }
            else if (dir == 'u') { mode = "grid"; enterTop(); return; }
            markTshelf(true);
            return;
        }
        if (mode.equals("move")) { moveItem(dir); return; }
        if (billboard != null && rest && tab.equals("home")) {
            if (billboard.move(dir)) return;
            if (dir == 'd') { rest = false; focus = Math.max(0, Math.min(focus, items.size() - 1)); applyFocus(true, ' '); }
            else enterTop();
            return;
        }
        if (noDock() && rest) {
            if (dir == 'd') { rest = false; focus = Math.max(0, Math.min(focus, items.size() - 1)); applyFocus(true, ' '); }
            else if (dir == 'u') enterTop();                // (straight to the bars: no Continue watching row on Home)
            else if (dir == 'l' && side) enterTop();               // (left: the menu; right: the next photo)
            else showPhoto(dir == 'l' ? -1 : 1);
            return;
        }
        int n = items.size(), c = focus % COLS, next = focus;
        if (dir == 'l') { if (c > 0) next = focus - 1; else if (side) { enterTop(); return; } }
        else if (dir == 'r') { if (c < COLS - 1 && focus + 1 < n) next = focus + 1; }
        else if (dir == 'u') {
            if (focus >= COLS) next = focus - COLS;
            else if (noDock()) { rest = true; applyFocus(true, 'u'); return; }
            else { if (tshelfWanted() && tsItems.length() > 0) { mode = "tshelf"; applyFocus(true, ' '); markTshelf(true); } else enterTop(); return; }
        } else if (dir == 'd') {
            if (focus + COLS < n) next = focus + COLS;
            else if (focus / COLS < (n - 1) / COLS) next = n - 1;
        }
        TileView cur = tiles.get(items.get(focus).id);
        if (next == focus) { if (cur != null) cur.bump(dir); return; }
        focus = next;
        applyFocus(true, dir);
        TileView t = tiles.get(items.get(focus).id);
        // the 3-D tilt only on the bottom bar: in the drawer (many tiles over the big glass) it cost a third of each frame
        if (t != null && inDock(focus)) t.tilt(dir);
    }

    private void ok() {
        if (mode.equals("sheet") && sheet != null) { sheet.ok(); return; }
        if (mode.equals("find") && find != null) { find.ok(); return; }
        if (mode.equals("dialog") && dlg != null) { dlg.ok(); return; }
        if (mode.equals("menu")) { Runnable r = menuActs.get(menuSel); closeMenu(); r.run(); return; }
        if (mode.equals("shelf")) { paused = !paused; scheduleSlide(); applyMode(true); return; }
        if (mode.equals("top")) { topActivate(); return; }
        if (mode.equals("tshelf")) {
            if (tsSel < cwCount) { openTitle(tsItems.optJSONObject(tsSel)); return; }
            int pi = tsSel - cwCount;
            page("window.TS && TS.items[" + pi + "] && vOpen(TS.items[" + pi + "])"); return;
        }
        if (mode.equals("move")) { endMove(); return; }
        if (isLive()) { live.ok(); return; }
        if (isSports()) { sports.ok(); return; }
        if (onVod()) { vod.ok(); return; }
        if (billboard != null && rest && tab.equals("home")) { billboard.ok(); return; }
        if (gallery != null && rest && tab.equals("home") && gAct != null) { gAct.run(); return; }
        if (noDock() && rest) { rest = false; applyFocus(true, ' '); return; }
        App a = items.get(focus);
        if (a.special) { page("openSettings()"); return; }
        TileView t = tiles.get(a.id);
        if (t != null) t.press();
        openApp(a);
    }

    private void longPress() {
        okDown = false;
        if (mode.equals("find") && find != null) find.hold();
        else if (mode.equals("grid") && isLive()) live.hold();
        else if (mode.equals("grid") && isSports()) sports.hold();
        else if (mode.equals("grid") && onVod()) vod.hold();
        else if (mode.equals("grid") && billboard != null && rest && tab.equals("home")) billboard.hold();
        else if (mode.equals("grid") && !(noDock() && rest)) openMenu();
        else if (mode.equals("tshelf")) {
            if (tsSel < cwCount) {
                final JSONObject m = tsItems.optJSONObject(tsSel);
                List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
                l.add("Choose a stream"); a.add(new Runnable() { @Override public void run() { openTitle(m); } });
                l.add("Remove from Continue watching"); a.add(new Runnable() { @Override public void run() { Progress.hide(getContext(), m.optString("video")); } });
                l.add("Clear all of Continue watching…"); a.add(new Runnable() { @Override public void run() { clearContinue(); } });
                showMenu(l, a, null, 1f, m.optString("name"));
            } else { int pi = tsSel - cwCount; page("window.TS && TS.items[" + pi + "] && vMenu(TS.items[" + pi + "], null)"); }
        }
    }

    private void back() {
        if (mode.equals("menu")) { closeMenu(); return; }
        if (mode.equals("sheet") && sheet != null) { sheet.back(); return; }
        if (mode.equals("find") && find != null) { find.back(); return; }
        if (mode.equals("dialog") && dlg != null) { dlg.back(); return; }
        if (mode.equals("move")) { endMove(); return; }
        if (mode.equals("shelf")) { exitShelf(); return; }
        if (mode.equals("top")) { if (!tab.equals("home")) { exitTopQuiet(); showTab("home"); } else exitTop(); return; }
        if (mode.equals("tshelf")) { mode = "grid"; applyFocus(true, ' '); return; }
        if (isLive()) { if (!live.back()) enterTop(); return; }
        if (isSports()) { if (!sports.back()) enterTop(); return; }
        if (onVod()) { if (!vod.back()) enterTop(); return; }
        if (noDock()) { if (!rest) { rest = true; applyFocus(true, 'u'); } else if (billboard != null && tab.equals("home")) billboard.back(); return; }
        if (focus >= COLS) { focus = focus % COLS; applyFocus(true, 'u'); }
    }

    void enterTopFromVod() { enterTop(); }
    /** the self-check: its presses go to the screen itself, not the top bar (where ◀ ▶ changes tab) */
    NativeSports sportsNow() { return sports; }
    String tabNow() { return tab; }
    /* A phone turned with the app's screens in both directions: this Home is replaced by one laid out for the new
       shape (MainActivity.reshapeHome), instead of the whole app starting over behind its loading picture. Everything
       this one runs stops here: its timers, frame callbacks, threads and picture caches. */
    void dispose() {
        main.removeCallbacksAndMessages(null);
        Choreographer.getInstance().removeFrameCallback(fcb);
        Choreographer.getInstance().removeFrameCallback(redrawCb);
        try { if (billboard != null) billboard.dispose(); } catch (Throwable ignored) { }
        try { if (vodM != null) vodM.dispose(); if (vodS != null) vodS.dispose(); } catch (Throwable ignored) { }
        try { if (sports != null) sports.dispose(); } catch (Throwable ignored) { }
        try { if (sportsData != null) sportsData.dispose(); } catch (Throwable ignored) { }
        try { imgs.dispose(); } catch (Throwable ignored) { }
    }
    void selfCheckIntoScreen() { if (mode.equals("top")) exitTop(); }
    /** left off the left edge of a screen: the side menu (nothing, with the bar across the top) */
    void leftEdge() { if (side) enterTop(); }
    boolean menuOpenNow() { return mode.equals("menu"); }

    // ================================================================== a finger (TopShelf on a phone)
    /* On a phone the same screens are driven by touch: a tap chooses what's under the finger and acts on it
       (as OK would), holding opens its menu (as holding OK would), and sliding a finger moves through things
       the way the remote's arrows do — a step for each card's width the finger travels, and a few more for a
       flick. A tap at the very top brings the tabs back while they're tucked away. */
    private float tDownX, tDownY, tLastX, tLastY, tAcc;
    private boolean tDragging, tLongFired, tFromTop;
    private char tAxis;
    private android.view.VelocityTracker tVel;
    private final Runnable tLong = new Runnable() { @Override public void run() {
        if (tDragging) return;
        tLongFired = true;
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        touchHold(tDownX, tDownY);
    } };
    private final int[] winLoc = new int[2];
    /** a touch on the screen while the app has it (from MainActivity); false sends it on to a text box being typed in */
    boolean touch(android.view.MotionEvent e) {
        if (!built) return false;
        getLocationInWindow(winLoc);
        float x = e.getX() - winLoc[0], y = e.getY() - winLoc[1];
        int a = e.getActionMasked();
        // typing (Search, a setting): the box and the phone's keyboard have the finger; a tap elsewhere stops typing
        if ((mode.equals("find") && find != null && find.typingNow()) || (mode.equals("sheet") && sheet != null && sheet.editingNow())) {
            if (a == android.view.MotionEvent.ACTION_DOWN && !overTextBox(x, y)) { if (mode.equals("find")) find.stopTyping(false); else sheet.back(); }
            return false;
        }
        lastKey = SystemClock.uptimeMillis();
        switch (a) {
            case android.view.MotionEvent.ACTION_DOWN:
                tDownX = tLastX = x; tDownY = tLastY = y; tAcc = 0; tAxis = 0; tDragging = false; tLongFired = false;
                tFromTop = y < px(3f);
                if (tVel != null) tVel.recycle();
                tVel = android.view.VelocityTracker.obtain(); tVel.addMovement(e);
                main.removeCallbacks(tLong); main.postDelayed(tLong, 460);
                break;
            case android.view.MotionEvent.ACTION_MOVE: {
                if (tVel != null) tVel.addMovement(e);
                if (tLongFired) break;
                float dx = x - tDownX, dy = y - tDownY;
                if (!tDragging && Math.hypot(dx, dy) > px(1.1f)) {
                    tDragging = true; main.removeCallbacks(tLong);
                    tAxis = Math.abs(dx) > Math.abs(dy) ? 'h' : 'v';
                    tLastX = x; tLastY = y;
                    // a swipe in from the left edge: the menu
                    if (side && tDownX < px(2.6f) && tAxis == 'h' && dx > 0 && !titleOpenNow() && !vodPlayingNow() && !(isLive() && live.fullNow()) && (mode.equals("grid") || mode.equals("tshelf"))) { enterTop(); tLongFired = true; break; }
                    // a pull down from the top edge: the tabs
                    if (tFromTop && tAxis == 'v' && dy > 0 && barsHidden && !titleOpenNow() && !vodPlayingNow() && !(isLive() && live.fullNow()) && !mode.equals("sheet") && !mode.equals("find") && !mode.equals("dialog")) { enterTop(); tLongFired = true; break; }
                }
                if (tDragging) {
                    float step = px(tStep(tAxis));
                    tAcc += tAxis == 'h' ? x - tLastX : y - tLastY;
                    tLastX = x; tLastY = y;
                    int guard = 0;
                    while (tAcc <= -step && guard++ < 6) { tKey(tAxis == 'h' ? 'r' : 'd'); tAcc += step; }
                    while (tAcc >= step && guard++ < 6) { tKey(tAxis == 'h' ? 'l' : 'u'); tAcc -= step; }
                }
                break;
            }
            case android.view.MotionEvent.ACTION_UP: {
                main.removeCallbacks(tLong);
                if (tVel != null) tVel.addMovement(e);
                if (tLongFired) break;
                if (!tDragging) { tap(x, y); break; }
                // a flick carries on a few more steps
                if (tVel != null) {
                    tVel.computeCurrentVelocity(1000);
                    float v = tAxis == 'h' ? tVel.getXVelocity() : tVel.getYVelocity();
                    int n = (int) Math.min(8, Math.abs(v) / (px(tStep(tAxis)) * 4.5f));
                    final char d = tAxis == 'h' ? (v < 0 ? 'r' : 'l') : (v < 0 ? 'd' : 'u');
                    for (int i = 0; i < n; i++) main.postDelayed(new Runnable() { @Override public void run() { tKey(d); } }, 70L * (i + 1));
                }
                break;
            }
            case android.view.MotionEvent.ACTION_CANCEL:
                main.removeCallbacks(tLong); tDragging = false;
                break;
        }
        return true;
    }
    /** how far a finger travels for one step (rem): a card's width across, a row's height down */
    private float tStep(char axis) {
        if (axis == 'h') return vodPlayingNow() ? 5f : 8f;
        if (mode.equals("sheet") || mode.equals("menu") || (isLive() && mode.equals("grid"))) return 4.4f;
        return 7f;
    }
    private void tKey(char d) {
        int code = d == 'l' ? KeyEvent.KEYCODE_DPAD_LEFT : d == 'r' ? KeyEvent.KEYCODE_DPAD_RIGHT : d == 'u' ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN;
        injectPress(code);
    }
    /** a key pressed and let go, as if from the remote */
    private void injectPress(int code) {
        long now = SystemClock.uptimeMillis();
        key(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        key(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0));
    }
    private void okPress() { injectPress(KeyEvent.KEYCODE_DPAD_CENTER); }
    private boolean overTextBox(float x, float y) {
        if (mode.equals("find") && find != null) return find.overBox(x, y);
        if (mode.equals("sheet") && sheet != null) return sheet.overInput(x, y);
        return false;
    }
    /** whether (x, y) — this screen's pixels — is on a view as it's drawn now (moved, scaled), with a little give (rem) */
    boolean hit(View v, float x, float y) { return hit(v, x, y, .3f); }
    boolean hit(View v, float x, float y, float give) {
        if (v == null || !v.isShown()) return false;
        for (View q = v; q != null && q != this; q = q.getParent() instanceof View ? (View) q.getParent() : null) if (q.getAlpha() < .1f) return false;
        android.graphics.Rect r = new android.graphics.Rect();
        if (!v.getGlobalVisibleRect(r)) return false;
        int[] me = new int[2]; getLocationInWindow(me);
        r.offset(-me[0], -me[1]);
        int g = px(give);
        return x >= r.left - g && x <= r.right + g && y >= r.top - g && y <= r.bottom + g;
    }
    /** (x, y) in a view's own pixels */
    float[] local(View v, float x, float y) {
        int[] a = new int[2], b = new int[2]; v.getLocationInWindow(a); getLocationInWindow(b);
        return new float[] { x - (a[0] - b[0]), y - (a[1] - b[1]) };
    }
    private void tap(float x, float y) {
        if (saver) { stopSaver(); return; }
        if (launchOv.getVisibility() == VISIBLE) { hideLaunch(); return; }
        if (fySetupOpen()) { fySetupClose(); return; }
        if (alertTakesOk() && x > W * .2f && x < W * .8f && y < px(16)) { alertView.ok(); return; }       // (a tap on the game alert)
        if (mode.equals("menu")) {
            for (int i = 0; i < menuRows.getChildCount(); i++) if (hit(menuRows.getChildAt(i), x, y, 0)) { menuSel = i; drawMenu(); ok(); return; }
            if (!hit(menuPanel, x, y, 0)) closeMenu();
            return;
        }
        if (vodPlayingNow()) { if (vodPlayer.focusAt(x, y) == null) okPress(); return; }
        if (addonsOpenNow()) { okPress(); return; }
        if (titleOpenNow()) { if ("ok".equals(titleView.focusAt(x, y))) okPress(); return; }
        if (gameOpenNow()) return;
        if (mode.equals("sheet") && sheet != null) { int r = sheet.focusAt(x, y); if (r == 1) ok(); else if (r < 0) back(); return; }
        if (mode.equals("dialog") && dlg != null) { if (dlg.focusAt(x, y)) ok(); return; }
        if (mode.equals("find") && find != null) { String r = find.focusAt(x, y); if ("ok".equals(r)) ok(); else if ("back".equals(r)) back(); return; }
        if (mode.equals("shelf")) { if (x < W * .25f) showPhoto(-1); else if (x > W * .75f) showPhoto(1); else exitShelf(); return; }
        if (mode.equals("move")) { endMove(); return; }
        // the tabs and the top bar's buttons
        if (!barsHidden || mode.equals("top")) for (int i = 0; i < topItems.size(); i++) if (hit(topItems.get(i), x, y)) { main.removeCallbacks(hoverTab); mode = "top"; topSel = i; drawTop(); topActivate(); return; }
        if (mode.equals("top")) exitTopQuiet();
        // tucked-away tabs: a tap at the very top brings them back
        if (barsHidden && y < px(3.4f) && !(isLive() && live.fullNow())) { enterTop(); return; }
        if (isLive()) { String r = live.focusAt(x, y); if ("ok".equals(r)) live.ok(); return; }
        if (isSports()) { String r = sports.focusAt(x, y); if ("ok".equals(r)) sports.ok(); return; }
        if (onVod()) { if (vod.focusAt(x, y)) vod.ok(); return; }
        // the icon rail at Home's edge: a tap opens it
        if (railShown() && !mode.equals("top") && x < px(RAIL_CLOSED)) { enterTop(); return; }
        // Home: the billboard, the Top Shelf row, then the apps
        if (billboard != null && rest && tab.equals("home") && billboard.tap(x, y)) return;
        for (int i = 0; i < tsCards.size(); i++) if (hit(tsCards.get(i), x, y, 0)) { mode = "tshelf"; tsSel = i; applyFocus(true, ' '); markTshelf(true); ok(); return; }
        for (int i = 0; i < items.size(); i++) {
            TileView t = tiles.get(items.get(i).id);
            if (hit(t, x, y, 0)) { mode = "grid"; rest = false; focus = i; applyFocus(true, ' '); ok(); return; }
        }
    }
    /** a finger held still: what's under it, then its menu (as holding OK) */
    private void touchHold(float x, float y) {
        if (saver || mode.equals("menu") || mode.equals("sheet") || mode.equals("dialog") || addonsOpenNow()) return;
        if (vodPlayingNow()) { injectPress(KeyEvent.KEYCODE_MENU); return; }
        if (titleOpenNow()) { if ("ok".equals(titleView.focusAt(x, y))) injectPress(KeyEvent.KEYCODE_MENU); return; }
        if (mode.equals("find") && find != null) { if ("ok".equals(find.focusAt(x, y))) find.hold(); return; }
        if (mode.equals("top")) exitTopQuiet();
        if (isLive()) { String r = live.focusAt(x, y); if (r != null) live.hold(); return; }
        if (isSports()) { String r = sports.focusAt(x, y); if (r != null) sports.hold(); return; }
        if (onVod()) { if (vod.focusAt(x, y)) vod.hold(); return; }
        if (billboard != null && rest && tab.equals("home") && mode.equals("grid")) { billboard.hold(); return; }
        for (int i = 0; i < tsCards.size(); i++) if (hit(tsCards.get(i), x, y, 0)) { mode = "tshelf"; tsSel = i; applyFocus(true, ' '); markTshelf(true); longPress(); return; }
        for (int i = 0; i < items.size(); i++) {
            TileView t = tiles.get(items.get(i).id);
            if (hit(t, x, y, 0)) { mode = "grid"; rest = false; focus = i; applyFocus(true, ' '); openMenu(); return; }
        }
    }

    // ================================================================== tabs drawn here

    /** Home, Movies or TV Shows (Sports and Live TV are still the page's) */
    /** a tab of the page (Sports, or Live TV without the TV's player) takes over: stop what plays here */
    private void leaveForPage() {
        if (live != null && live.shownNow()) live.hide();
        if (sports != null && sports.shownNow()) sports.hide();
        if (vodM != null) vodM.pause();
        if (vodS != null) vodS.pause();
    }

    /** Live TV is drawn here when the TV's own video player works (otherwise it's the page's) */
    boolean liveNativeOk() {
        if (!"web".equals(S.optString("liveEngine"))) return act.nativePlayer() != null;
        return false;
    }

    /** tabs drawn here (Live TV only when the TV's player works; Sports when the page can hand its games over) */
    boolean nativeTab(String id) {
        return id.equals("home") || id.equals("movies") || id.equals("series") || (id.equals("live") && liveNativeOk()) || (id.equals("sports") && sportsOk);
    }
    boolean sportsOk = true;

    void showTab(final String id) {
        final long t0 = SystemClock.uptimeMillis();
        try { showTab0(id); }
        finally { Diag.ev("tab work " + id + " · " + (SystemClock.uptimeMillis() - t0) + " ms"); }
    }
    private void showTab0(final String id) {
        Diag.ev("tab " + tab + " → " + id);
        settleMemory();
        final String toTab = id;
        main.postDelayed(new Runnable() { @Override public void run() { Diag.mem("on " + toTab); } }, 3000);
        if (!built || vod == null) return;
        if (id.equals(tab)) { drawTop(); return; }
        if (id.equals("live") && !liveNativeOk()) { leaveForPage(); mode = "grid"; exitTopQuiet(); page("switchTab('live', false)"); return; }
        String was = tab;
        tab = id;
        if (was.equals("live") && live != null) live.hide();
        if (was.equals("sports") && sports != null) sports.hide();
        if ((was.equals("movies") || was.equals("series")) && !(id.equals("movies") || id.equals("series"))) vod.hide();
        if (id.equals("home")) {
            photoBox.setVisibility(VISIBLE); scrim.setVisibility(VISIBLE); content.setVisibility(VISIBLE);
            restoreHomeGlass();
            if (focus >= COLS) focus = focus % COLS;
            deep = false; rest = true;
            applyFocus(false, ' ');
            applyMode(false);
            updateTshelf(false);
            restartPhoto();
        } else {
            if (slideTimer != null) main.removeCallbacks(slideTimer);
            syncHomeStyle(false);
            if (focus >= COLS) focus = focus % COLS;
            if (deep) { deep = false; drawer.setVisibility(INVISIBLE); dimPhotos(0, 0); for (GlassPanel g : panels) g.dimTo(0, 0); }
            tshelf.setAlpha(0f);
            if (id.equals("live")) {
                if (live == null) { live = new NativeLive(this); live.setVisibility(INVISIBLE); addView(live, indexOfChild(vod) + 1, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)); }
                live.show();
            } else if (id.equals("sports")) {
                if (sports == null) { sports = new NativeSports(this); sports.setVisibility(INVISIBLE); addView(sports, indexOfChild(vod) + 1, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)); }
                sports.show();
            } else {
                NativeVod want = vodFor(id);
                if (want != vod && (was.equals("movies") || was.equals("series"))) vod.hide();
                vod = want;
                vod.show(id.equals("series") ? "series" : "movie");
            }
            main.postDelayed(new Runnable() { @Override public void run() {
                if (!onVod()) return;
                photoBox.setVisibility(INVISIBLE); scrim.setVisibility(INVISIBLE); content.setVisibility(INVISIBLE); drawer.setVisibility(INVISIBLE);
            } }, 380);
        }
        drawTop();
        updateBars();
    }

    /* On Movies & TV Shows the glass of the bars shows a blurred copy of the title's backdrop (the
       backdrop is painted into one picture, so its blurred copy is made with it). */
    private int vodGlassLayer = -1;
    void setTabGlass(Bitmap light, Bitmap frost) { setVodGlass(light, frost); }
    /** a trailer is playing behind the title: the panes see through to it (and back to glass after) */
    void vodGlassClear(boolean on) {
        if (on && !onVod()) return;
        for (GlassPanel g : panels) g.setClear(on, on ? 500 : 300);
    }

    void setVodGlass(Bitmap light, Bitmap frost) {
        if (!onVod()) return;
        final int next = vodGlassLayer == 0 ? 1 : 0, old = vodGlassLayer;
        vodGlassLayer = next;
        for (GlassPanel g : panels) {
            boolean frosted = !glassMode().equals("liquid") || !g.liquidAllowed;
            g.setPhotoBox(0, 0, W, H);
            for (ImageView iv : g.layer) setKb(iv, 1, 0, 0);
            ImageView nv = g.layer[next];
            nv.animate().cancel();
            nv.setImageBitmap(frosted ? frost : light);
            ((android.view.ViewGroup) nv.getParent()).bringChildToFront(nv);
            nv.setAlpha(0f);
            final ImageView ov = old >= 0 ? g.layer[old] : null;
            final GlassPanel gg = g;
            nv.animate().alpha(1f).setDuration(old < 0 ? 300 : 900).withEndAction(new Runnable() { @Override public void run() {
                if (ov != null && vodGlassLayer == next) ov.setAlpha(0f);
                gg.refreshTint();
            } }).start();
        }
    }
    private void restoreHomeGlass() {
        vodGlassLayer = -1;
        int pw = Math.round(W * 1.06f), ph = Math.round(H * 1.06f);
        String g = glassMode();
        for (GlassPanel p : panels) {
            p.setPhotoBox(-Math.round(W * .03f), -Math.round(H * .03f), pw, ph);
            for (int i = 0; i < 2; i++) {
                p.layer[i].animate().cancel();
                if (glassBmp[i] != null) p.layer[i].setImageBitmap(g.equals("liquid") && p.liquidAllowed ? glassBmp[i][0] : glassBmp[i][1]);
                else p.layer[i].setImageDrawable(null);
                p.layer[i].setAlpha(i == layer && photo[layer].getDrawable() != null ? 1f : 0f);
            }
            p.refreshTint();
        }
        applyKenBurns(layer);
    }

    /** full-screen Live TV: the bars step aside */
    /* Which bars show: on Live TV they step aside while you're in the guide (Back brings them back with
       the top bar selected); Search and full-screen video hide them too. */
    private boolean barsHidden;
    /* Which bars show: they step aside while you browse a tab — Live TV's guide, Movies, TV Shows, Sports, and
       Home's All apps — and come back when you go up to them (▲ from the top row) or press Back. Search and
       full-screen video hide them too. */
    private void updateBarsTop() {
        boolean browsing = (mode.equals("grid") || mode.equals("menu")) && !portrait;
        boolean hide = (find != null && find.shownNow()) || (portrait && (titleOpenNow() || vodPlayingNow()))
                || (isLive() && live.shownNow() && (live.fullNow() || browsing))
                || (browsing && (tab.equals("movies") || tab.equals("series") || isSports()))
                || (browsing && tab.equals("home") && deep);
        // upright, the round buttons and the clock are Home's (the other tabs have their own things up there)
        if (portrait && topbar != null) { float ta = !hide && tab.equals("home") ? 1f : 0f; if (topbar.getAlpha() != ta) topbar.animate().alpha(ta).setDuration(250).start(); }
        if (hide == barsHidden) return;
        barsHidden = hide;
        setBarsHidden(hide);
        if (live != null) live.barsHidden(hide);
        if (sports != null) sports.barsHidden(hide);
    }
    private void setBarsHiddenTop(boolean hide) {
        Diag.ev(hide ? "top bars hidden" : "top bars shown");
        tabs.animate().alpha(hide ? 0f : 1f).setDuration(hide ? 200 : 400).start();
        for (View x : barExtras) x.animate().alpha(hide ? 0f : 1f).setDuration(hide ? 200 : 400).start();
        topbar.animate().alpha(hide || (portrait && !tab.equals("home")) ? 0f : 1f).setDuration(hide ? 200 : 400).start();
        if (hide) tip.setAlpha(0f);
    }
    private boolean liveTold, sportsTold;
    void updateBars() {
        if (!side) { updateBarsTop(); return; }
        // (no bars across the top any more: every screen has that room all the time)
        if (!barsHidden) { barsHidden = true; Diag.ev("side menu (no top bars)"); }
        if (live != null && !liveTold) { liveTold = true; live.barsHidden(true); }
        if (sports != null && !sportsTold) { sportsTold = true; sports.barsHidden(true); }
        boolean busy = (find != null && find.shownNow()) || (isLive() && live.shownNow() && live.fullNow()) || titleOpenNow() || vodPlayingNow();
        menuIn(mode.equals("top") && !busy && !saver);
    }
    boolean barsHiddenNow() { return side || barsHidden; }
    void setBarsHidden(boolean hide) { if (!side) setBarsHiddenTop(hide); }
    /** the TV's video player reporting on a stream Live TV started here */
    void liveEvent(int id, String ev, String data) { if (live != null) live.event(id, ev, data); }
    void liveChanged(boolean playlist) { if (live != null) live.changed(playlist); }
    void liveProgress(String m) { if (live != null) live.progressMsg(m); }
    /** from the page (search, a reminder, Multiview closing): watch this channel full screen here */
    /** a channel picked for a game (Sports): Live TV knows that game is the one on it */
    void livePlayUrl(String u, JSONObject game) {
        if (live != null && game != null) live.chooseGame(u, game);
        livePlayUrl(u);
    }
    void livePlayUrl(String u) {
        if (!liveNativeOk()) return;
        if (mode.equals("menu")) closeMenu();
        if (mode.equals("top")) exitTopQuiet();
        if (mode.equals("shelf")) exitShelf();
        mode = "grid";
        showTab("live");
        if (live != null) live.playUrl(u);
    }
    boolean liveWatching() { return live != null && live.watching(); }
    /** play a recording full screen (from the phone app) */
    void livePlayRecording(String id) {
        if (!liveNativeOk()) return;
        if (mode.equals("menu")) closeMenu();
        if (mode.equals("top")) exitTopQuiet();
        if (mode.equals("shelf")) exitShelf();
        mode = "grid";
        showTab("live");
        if (live != null) live.playRecording(id);
    }
    // ================================================================== Yahoo Fantasy

    /* An alert about one of your players: a card at the top right for a few seconds. Shown only while
       TopShelf is on screen (any of its tabs, Live TV included); otherwise it waits (up to 20 minutes)
       and is shown when you come back. "OK · Watch" tunes the channel showing that game, if there is one. */
    private static final class FyAlert { String kind, title, detail, game, watch; long at; int req; }
    private final java.util.ArrayDeque<FyAlert> fyQueue = new java.util.ArrayDeque<>();
    private FyAlert fyShowing;
    private int fyReq;
    /** the game on the channel you're watching full screen ("" if none) */
    String liveWatchingGame() { if (live == null) return ""; live.lookAgainForGame(); return live.watchingGameId(); }
    void fantasyAlert(String kind, String title, String detail, String game) {
        // (you're watching that game: you've seen it happen)
        if (game != null && !game.isEmpty() && game.equals(liveWatchingGame())) { Diag.ev("fantasy alert not shown (watching that game): " + title); return; }
        FyAlert a = new FyAlert(); a.kind = kind; a.title = title; a.detail = detail; a.game = game; a.at = System.currentTimeMillis();
        fyQueue.addLast(a);
        while (fyQueue.size() > 12) fyQueue.removeFirst();
        fyNext();
    }
    private boolean fyCanShow() { return built && MainActivity.inFront && !saver; }
    private void fyNext() {
        if (fyShowing != null || !fyCanShow()) return;
        while (!fyQueue.isEmpty() && System.currentTimeMillis() - fyQueue.peekFirst().at > 20 * 60000L) fyQueue.removeFirst();
        if (fyQueue.isEmpty()) return;
        // many waiting (you were away): the latest two, and how many more
        if (fyQueue.size() > 3) {
            int more = fyQueue.size() - 2;
            FyAlert sum = new FyAlert(); sum.kind = "final"; sum.title = more + " more fantasy alerts while you were away"; sum.detail = "Your players' plays are also in Settings › Sports › Yahoo Fantasy"; sum.at = System.currentTimeMillis();
            while (fyQueue.size() > 2) fyQueue.removeFirst();
            fyQueue.addFirst(sum);
        }
        fyShow(fyQueue.removeFirst());
    }
    // ---- game alerts: one sports notification from the top middle (see SportsAlert)
    private SportsAlert alertView;
    SportsAlert alertView() {
        if (alertView == null) { alertView = new SportsAlert(this); addView(alertView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)); }
        return alertView;
    }
    private void fyShow(final FyAlert a) {
        fyShowing = a;
        int col = a.kind.equals("score") ? 0xFF1F9D57 : a.kind.equals("big") ? 0xFFD98A0B : a.kind.equals("injury") ? 0xFFC8423A : 0xFF4A5060;
        String word = a.kind.equals("score") ? "SCORE" : a.kind.equals("big") ? "BIG PLAY" : a.kind.equals("injury") ? "INJURY" : "FINAL";
        alertView().show("FANTASY  ·  " + word, 0xFFB9A8FF, a.title, a.detail, null, null, word, "FANTASY", col, "", null,
                a.kind.equals("injury") || a.kind.equals("final") ? 12000 : 9000);
        alertView.onHidden(new Runnable() { @Override public void run() {
            fyShowing = null;
            main.postDelayed(new Runnable() { @Override public void run() { fyNext(); } }, 600);
        } });
        // is the game on one of your channels? (the Sports tab's matching)
        if (a.game != null && !a.kind.equals("final")) { a.req = ++fyReq; page("window.spChannelForGame && spChannelForGame('nfl'," + JSONObject.quote(a.game) + "," + a.req + ")"); }
    }
    private final Runnable fyHide = new Runnable() { @Override public void run() { if (alertView != null) alertView.hide(); } };
    /** the page found the channel showing the alert's game */
    void fantasyWatch(String req, String u) {
        if (fyShowing == null || !String.valueOf(fyShowing.req).equals(req) || u == null || u.isEmpty()) return;
        if (isLive() && live.playingUrl().equals(u)) return;          // (already watching it)
        fyShowing.watch = u;
        final String url = u;
        if (alertView != null) alertView.setHint("OK  ·  Watch", new Runnable() { @Override public void run() {
            if (mode.equals("sheet") && sheet != null) sheetClose();
            livePlayUrl(url);
        } });
    }
    /* The Ring doorbell was pressed (see Ring, Doorbell): TopShelf comes to the front if another app had the screen,
       the screensaver goes, whatever's playing pauses, and the card shows (its picture a few seconds later). Closing it
       carries on playing. */
    private Doorbell doorbellView;
    private boolean doorbellPaused;
    void doorbell(String device, final String snapshot, String dingId, final String deviceId) {
        final long rangAt = System.currentTimeMillis();
        if (!built) return;
        if (!MainActivity.inFront) act.bringToFront();
        if (saver) stopSaver();
        NativePlayer np = act.nativePlayer();
        if (np != null && !np.paused() && !doorbellPaused) { np.setPaused(true); doorbellPaused = true; }
        if (doorbellView == null) { doorbellView = new Doorbell(this); addView(doorbellView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)); }
        doorbellView.show(device, deviceId, new Runnable() { @Override public void run() {
            NativePlayer p = act.nativePlayer();
            if (doorbellPaused && p != null) p.setPaused(false);
            doorbellPaused = false;
        } });
        Diag.ev("doorbell shown" + (doorbellPaused ? " · playback paused" : ""));
        if ((snapshot == null || snapshot.isEmpty()) && (deviceId == null || deviceId.isEmpty()) || act.ring == null) { doorbellView.picture(null); return; }
        final Doorbell dv = doorbellView;
        new Thread(new Runnable() { @Override public void run() {
            byte[] d = act.ring.snapshot(snapshot, deviceId, rangAt);
            final Bitmap b = d == null ? null : HomeImages.decode(d, 1280, 720);
            main.post(new Runnable() { @Override public void run() { dv.picture(b); } });
        } }, "doorbell").start();
    }
    private boolean alertTakesOk() { return alertView != null && alertView.takesOk(); }
    /** a game alert from the Sports tab's data (the page): starting soon, or one of your teams scoring */
    void gameAlert(String json) {
        JSONObject o;
        try { o = new JSONObject(json); } catch (Exception e) { return; }
        if (!built || !MainActivity.inFront || saver) return;
        Diag.ev("game alert: " + o.optString("kicker") + " · " + o.optString("headline"));
        SportsAlert.End a = null, b = null;
        JSONArray ts = o.optJSONArray("teams");
        if (ts != null && ts.length() == 2) {
            a = new SportsAlert.End(); b = new SportsAlert.End();
            for (int i = 0; i < 2; i++) {
                JSONObject t = ts.optJSONObject(i); SportsAlert.End e = i == 0 ? a : b;
                if (t == null) continue;
                e.abbr = t.optString("abbr", t.optString("name")); e.color = t.optString("color"); e.logo = t.optString("logo");
            }
        }
        boolean score = "score".equals(o.optString("kind"));
        String hintText = o.optString("hint");
        Runnable ok = hintText.isEmpty() ? null : new Runnable() { @Override public void run() { page("window.nhAlertOk && nhAlertOk()"); } };
        int kc = score ? 0xFF5BE38F : 0xFFFFC23D;
        try { if (!o.optString("kickerColor").isEmpty()) kc = Color.parseColor(o.optString("kickerColor")); } catch (Exception ignored) { }
        boolean red = "red".equals(o.optString("kind"));
        alertView().show(o.optString("kicker"), kc, o.optString("headline"), o.optString("detail"), a, b,
                o.optString("word", ""), "", 0xFF2A3340, hintText, ok, score || red ? 12000 : 20000);
        alertView.onHidden(new Runnable() { @Override public void run() { main.postDelayed(new Runnable() { @Override public void run() { fyNext(); } }, 600); } });
    }

    /* Setup: a QR code for the phone, which opens a page the TV serves (LinkServer), with the steps. */
    private FrameLayout fySetup; private ImageView fyQr; private TextView fyUrl, fyState;
    void fantasySetupShow() { setupShow("fantasy"); }
    void fantasySetupRefresh() { setupRefresh(); }
    private String setupKind = "fantasy";
    private TextView suTitle, suSteps;
    /** a QR code for setting something up on your phone (Yahoo Fantasy, Google Drive) */
    void setupShow(String kind) {
        setupKind = kind;
        boolean drive = kind.equals("drive");
        boolean pls = kind.equals("players"), trk = kind.equals("trakt"), rng = kind.equals("ring");
        String url = rng ? (act.ring == null ? "" : act.ring.setupUrl()) : trk ? (act.trakt == null ? "" : act.trakt.setupUrl()) : drive ? (act.drive == null ? "" : act.drive.setupUrl()) : act.fantasy == null ? "" : pls ? act.fantasy.playersUrl() : act.fantasy.setupUrl();
        if (url.isEmpty()) { toast("The TV isn't on your home network right now"); return; }
        if (fySetup == null) {
            fySetup = new FrameLayout(getContext());
            fySetup.setBackgroundColor(0xF20A0B10);
            fySetup.setClickable(true);
            LinearLayout card = new LinearLayout(getContext()); card.setOrientation(LinearLayout.HORIZONTAL); card.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable cb = new GradientDrawable(); cb.setCornerRadius(2.4f * R); cb.setColor(0xFF17181F); cb.setStroke(Math.max(1, px(.1f)), 0x24FFFFFF);
            card.setBackground(cb); card.setPadding(px(3), px(3), px(3.4f), px(3));
            FrameLayout qb = new FrameLayout(getContext());
            GradientDrawable wb = new GradientDrawable(); wb.setCornerRadius(1.4f * R); wb.setColor(Color.WHITE); qb.setBackground(wb);
            qb.setPadding(px(1.2f), px(1.2f), px(1.2f), px(1.2f));
            fyQr = new ImageView(getContext()); fyQr.setScaleType(ImageView.ScaleType.FIT_CENTER);
            qb.addView(fyQr, new LayoutParams(px(20), px(20)));
            card.addView(qb, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            LinearLayout col = new LinearLayout(getContext()); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(px(3), 0, 0, 0);
            TextView t = text(fSemi, 2.2f, Color.WHITE); suTitle = t;
            TextView s1 = text(fReg, 1.15f, 0xD9FFFFFF); s1.setLineSpacing(0, 1.3f); suSteps = s1;
            fyUrl = text(fReg, .95f, 0x8CFFFFFF);
            fyState = text(fMed, 1.1f, 0xFFB9A8FF);
            TextView back = text(fReg, .95f, 0x80FFFFFF); back.setText("Back · close");
            col.addView(t);
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); gl.topMargin = px(1.4f);
            col.addView(s1, gl);
            LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); ul.topMargin = px(1.8f);
            col.addView(fyUrl, ul);
            col.addView(fyState, gl);
            col.addView(back, gl);
            card.addView(col, new LinearLayout.LayoutParams(px(48), LayoutParams.WRAP_CONTENT));
            fySetup.addView(card, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            addView(fySetup, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        suTitle.setText(rng ? "Connect your Ring doorbell" : trk ? "Connect Trakt" : drive ? "Connect Google Drive" : pls ? "Pick your fantasy players" : "Connect Yahoo Fantasy");
        suSteps.setText(rng ? "1.  Scan the code with your phone (on the same Wi-Fi as the TV).\n2.  Sign in with your Ring email and password, then the code Ring texts you. The password goes to Ring once and isn't kept.\n3.  That's it — when someone rings, their picture shows here and what's playing pauses."
                : trk ? "1.  Scan the code with your phone (on the same network as the TV).\n2.  Make a free Trakt app (2 minutes), then allow it with the code Trakt gives.\n3.  What you watch here goes to Trakt, and what you watched elsewhere shows up in Continue watching."
                : pls ? "1.  Scan the code with your phone (on the same network as the TV).\n2.  Search for each player on your team (and your defense) and tap Add.\n3.  Alerts appear here during games: scores, big plays, injuries and finals."
                : drive
                ? "1.  Scan the code with your phone (on the same network as the TV).\n2.  Follow the steps on your phone: make a free Google Cloud app (about 5 minutes), then sign in.\n3.  That's it — recordings go to your Drive and play from there."
                : "1.  Scan the code with your phone (on the same Wi-Fi as the TV).\n2.  Follow the steps on your phone: create a free Yahoo app, then sign in.\n3.  That's it — alerts appear here during games.");
        fyQr.setImageBitmap(qrBitmap(url));
        fyUrl.setText("Or type this on your phone:  " + url);
        setupRefresh();
        fySetup.setVisibility(VISIBLE); fySetup.bringToFront();
        fySetup.setAlpha(0f); fySetup.animate().alpha(1f).setDuration(250).start();
    }
    void setupRefresh() {
        if (fySetup == null) return;
        if (setupKind.equals("ring")) {
            if (act.ring == null) return;
            JSONObject st = act.ring.status();
            boolean ok = st.optBoolean("connected");
            String s = st.optString("state");
            fyState.setText(ok ? "✓  Connected" + (st.optInt("doorbells") > 0 ? " · " + st.optString("name") : "") + (s.isEmpty() ? "" : " · " + s) : s.isEmpty() ? "Waiting for your phone…" : s + "…");
            fyState.setTextColor(ok ? 0xFF3CCF7A : 0xFFB9A8FF);
            if (ok) page("typeof refreshSettings === 'function' && refreshSettings()");
            return;
        }
        if (setupKind.equals("trakt")) {
            if (act.trakt == null) return;
            JSONObject st = act.trakt.status();
            boolean ok = st.optBoolean("connected");
            fyState.setText(ok ? "✓  Connected" + (st.optString("user").isEmpty() ? "" : " as " + st.optString("user"))
                    : !act.trakt.userCode.isEmpty() ? "On your phone, go to " + act.trakt.verifyUrl.replace("https://", "") + " and enter  " + act.trakt.userCode
                    : act.trakt.setupState.isEmpty() ? "Waiting for your phone…" : act.trakt.setupState + "…");
            fyState.setTextColor(ok ? 0xFF3CCF7A : 0xFFB9A8FF);
            if (ok) page("typeof refreshSettings === 'function' && refreshSettings()");
            return;
        }
        if (setupKind.equals("drive")) {
            if (act.drive == null) return;
            JSONObject st = act.drive.status();
            String s = act.drive.setupState;
            boolean ok = st.optBoolean("connected");
            fyState.setText(ok ? "✓  Connected" + (st.optString("email").isEmpty() ? "" : " · " + st.optString("email"))
                    : !act.drive.userCode.isEmpty() ? "On your phone, go to " + act.drive.verifyUrl.replace("https://", "") + " and enter  " + act.drive.userCode
                    : s.isEmpty() ? "Waiting for your phone…" : s + "…");
            fyState.setTextColor(ok ? 0xFF3CCF7A : 0xFFB9A8FF);
            if (ok) page("typeof refreshSettings === 'function' && refreshSettings()");
            return;
        }
        if (act.fantasy == null) return;
        if (setupKind.equals("players")) {
            int n = act.fantasy.mine().length();
            fyState.setText(n == 0 ? "Waiting for your phone…" : "✓  " + n + (n == 1 ? " player" : " players") + " · alerts are on");
            fyState.setTextColor(n > 0 ? 0xFF3CCF7A : 0xFFB9A8FF);
            return;
        }
        JSONObject st = act.fantasy.status();
        String s = act.fantasy.setupState;
        fyState.setText(st.optBoolean("connected") ? "✓  Connected · " + st.optString("teams") : s.isEmpty() ? "Waiting for your phone…" : s + "…");
        fyState.setTextColor(st.optBoolean("connected") ? 0xFF3CCF7A : 0xFFB9A8FF);
        if (st.optBoolean("connected")) page("typeof refreshSettings === 'function' && refreshSettings()");
    }
    private boolean fySetupOpen() { return fySetup != null && fySetup.getVisibility() == VISIBLE; }
    private void fySetupClose() { fySetup.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() { @Override public void run() { fySetup.setVisibility(GONE); } }).start(); }
    static Bitmap qrBitmapOf(String text) { return qrBitmap(text); }

    // ================================================================== a title's streams, and playing them (addons)
    TitleView titleView; VodPlayer vodPlayer;
    /** a movie or show from Movies & TV Shows: its details and the streams the addons find */
    /** a game's own page (box score, leaders, scoring, win chance) */
    private GameView gamePage;
    void openGamePage(String sport, String league, String id, JSONArray teams) {
        if (gamePage == null) { gamePage = new GameView(this); addView(gamePage, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)); }
        gamePage.open(sport, league, id, teams);
    }
    private boolean gameOpenNow() { return gamePage != null && gamePage.shownNow(); }
    /** a title on Movies (else Home's Movies & TV rows), for the self-check */
    JSONObject anyTitle() { NativeVod v = vodM != null ? vodM : vod; return v == null ? null : v.firstTitle(); }
    void openTitle(JSONObject m) {
        if (titleView == null) { titleView = new TitleView(this); addView(titleView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)); }
        if (vod != null) vod.stopTrailer();
        titleView.open(m);
    }
    /** open a title and play it (the best stream) */
    void openTitlePlay(JSONObject m) {
        if (titleView == null) { titleView = new TitleView(this); addView(titleView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)); }
        if (vod != null) vod.stopTrailer();
        titleView.openPlay(m);
    }
    void stopTrailers() { if (vod != null) vod.stopTrailer(); if (titleView != null) titleView.stopTrailer(); }
    /** adds to / removes from My List (Movies & TV's rows follow) */
    void toggleMyList(JSONObject m) {
        if (vod != null) { vod.toggleList(m); return; }
        try {
            JSONArray a = arr(prefs.getString("ui.mylist", null)), out = new JSONArray();
            boolean had = false;
            for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null && o.optString("id").equals(m.optString("id")) && o.optString("type").equals(m.optString("type"))) had = true; }
            if (!had) out.put(m);
            for (int i = 0; i < a.length() && out.length() < 200; i++) { JSONObject o = a.optJSONObject(i); if (o == null || had && o.optString("id").equals(m.optString("id")) && o.optString("type").equals(m.optString("type"))) continue; out.put(o); }
            prefs.edit().putString("ui.mylist", out.toString()).apply();
            toast(had ? "Removed “" + m.optString("name") + "” from My List" : "Added “" + m.optString("name") + "” to My List");
            page("V.mine=readJSON('mylist',[]);window.tshelfRebuild&&tshelfRebuild();typeof searchRefresh==='function'&&searchRefresh()");
        } catch (Exception ignored) { }
    }
    VodPlayer vodPlayer() {
        if (vodPlayer == null) { vodPlayer = new VodPlayer(this); addView(vodPlayer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)); }
        return vodPlayer;
    }
    /** the app's own pop-ups (menus, messages): kept over a playing movie */
    boolean overlay(View v) { return v == toastView || v == menuPanel || v == hud || v == dlg || v == launchOv; }
    /** TopShelf left the screen: a playing movie stops (where you were is kept) */
    void vodPause() { if (vodPlayer != null && vodPlayer.shownNow()) vodPlayer.close(); stopTrailers(); }
    void vodEvent(int id, String ev, String data) { if (vodPlayer != null) vodPlayer.event(id, ev, data); }
    private boolean titleOpenNow() { return titleView != null && titleView.shownNow(); }
    boolean vodPlayingNow() { return vodPlayer != null && vodPlayer.shownNow(); }
    /** which screen is up, for the flight recorder (Flight): the names of screens, never what's on them */
    String flightScreen() {
        if (doorbellView != null && doorbellView.shownNow()) return "doorbell";
        if (vodPlayingNow()) return "movie & TV player";
        if (titleView != null && titleView.shownNow()) return "title page";
        if (sheet != null && sheet.shownNow()) return "settings";
        if (find != null && find.shownNow()) return "search";
        if (live != null && live.shownNow()) return live.fullNow() ? "Live TV · full screen" : "Live TV · guide";
        if (sports != null && sports.shownNow()) return "sports";
        if (saver) return "screensaver";
        return tab.equals("movies") ? "Movies" : tab.equals("series") ? "TV Shows" : "Home";
    }
    /** for the report: Home's state in a few words */
    String screenWhy() { return "tab " + tab + ", mode " + mode + ", owner " + owner + (saver ? ", screensaver on" : "") + ", rest " + rest; }

    // ================================================================== Settings › Addons (a full-screen window)
    private AddonsView addonsView;
    void addonsOpen() {
        if (mode.equals("sheet") && sheet != null) sheetClose();
        if (addonsView == null) { addonsView = new AddonsView(this); addView(addonsView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)); }
        addonsView.open();
    }
    void addonsRefresh() { if (addonsView != null && addonsView.shownNow()) addonsView.refresh(); }
    private boolean addonsOpenNow() { return addonsView != null && addonsView.shownNow(); }

    private static Bitmap qrBitmap(String text) {
        io.nayuki.qrcodegen.QrCode q = io.nayuki.qrcodegen.QrCode.encodeText(text, io.nayuki.qrcodegen.QrCode.Ecc.MEDIUM);
        int n = q.size, sc = 10, border = 2, size = (n + 2 * border) * sc;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        Paint p = new Paint(); p.setColor(0xFF000000);
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) if (q.getModule(x, y)) c.drawRect((x + border) * sc, (y + border) * sc, (x + border + 1) * sc, (y + border + 1) * sc, p);
        return b;
    }

    /** live stats: the page's answer to which game a channel is showing */
    void liveStatsGame(String req, String json) { if (live != null) live.statsGame(req, json); }
    /** another app took the screen: Movies & TV let go of their backdrops and posters (made again on the way back) */
    void leftApp() {
        if (!built) return;
        for (NativeVod v : new NativeVod[] { vodM, vodS }) if (v != null && !v.isShown2()) v.releaseHidden();
        if (titleView != null && !titleView.shownNow()) titleView.stopTrailer();
        settleMemory();
    }
    /** Home, still, nothing open over it (a picture of it now is what you'd come back to) */
    // ---- the page's score ticker: where it may show (MainActivity.tickerCheck)
    private volatile long tickerFlashUntil;
    /** something happened in one of your teams' games: on Live TV the ticker shows for a few seconds */
    void tickerFlash(long ms) { tickerFlashUntil = SystemClock.uptimeMillis() + ms; }
    private boolean watchingSomething() {
        return titleOpenNow() || vodPlayingNow() || addonsOpenNow() || tab.equals("movies") || tab.equals("series");
    }
    boolean tickerWanted() {
        if (!built || watchingSomething()) return false;
        if ((find != null && find.shownNow()) || (sheet != null && sheet.shownNow())) return false;
        if (tab.equals("home")) return !deep;
        if (isLive()) return SystemClock.uptimeMillis() < tickerFlashUntil;
        return false;
    }
    /** for the page's sports alerts: away (TopShelf isn't on screen), home, live, or quiet (Movies, TV Shows, Sports, something playing) */
    String tickerWhere() {
        if (!MainActivity.inFront) return "away";
        if (!built) return "home";
        if (watchingSomething()) return "quiet";
        if (tab.equals("home")) return "home";
        if (isLive()) return "live";
        return "quiet";
    }
    boolean onHomeAtRest() { return built && tab.equals("home") && mode.equals("grid") && !deep && !titleOpenNow() && !vodPlayingNow() && (find == null || !find.shownNow()) && (sheet == null || !sheet.shownNow()); }
    void appPaused() { Diag.ev("app left"); if (live != null) live.appPaused(); }

    /* Settings' and messages' glass shows what's really behind it (the dock, a tab's rows, the guide), not
       just the home photo: a small copy of the screen is taken as it opens, blurred, and used as its glass.
       (Taken by the graphics chip, a quarter size: a few milliseconds.) The pane opens when it's ready. */
    private void snapGlass(final GlassPanel g, final Runnable then) {
        final boolean[] done = { false };
        final Runnable go = new Runnable() { @Override public void run() { if (done[0]) return; done[0] = true; then.run(); } };
        main.postDelayed(go, 160);                  // (opens anyway if the copy is slow)
        if (g == null || android.os.Build.VERSION.SDK_INT < 26) { go.run(); return; }
        try {
            final Bitmap snap = Bitmap.createBitmap(Math.max(8, W / 4), Math.max(8, H / 4), Bitmap.Config.ARGB_8888);
            android.view.PixelCopy.request(act.getWindow(), snap, new android.view.PixelCopy.OnPixelCopyFinishedListener() {
                @Override public void onPixelCopyFinished(int result) {
                    if (result != android.view.PixelCopy.SUCCESS || done[0]) { go.run(); return; }
                    // where video shows, the window is see-through: a frame of the video goes under the copy
                    NativePlayer np = act.nativePlayer();
                    final android.view.SurfaceView plane = np != null ? np.shownPlane() : null;
                    final android.graphics.RectF pr = plane != null ? np.planeRect() : null;
                    if (plane == null || pr == null || pr.width() < 8) { glassFrom(g, snap, null, null); go.run(); return; }
                    try {
                        final Bitmap fr = Bitmap.createBitmap(Math.max(8, Math.round(pr.width() / 4)), Math.max(8, Math.round(pr.height() / 4)), Bitmap.Config.ARGB_8888);
                        android.view.PixelCopy.request(plane, fr, new android.view.PixelCopy.OnPixelCopyFinishedListener() {
                            @Override public void onPixelCopyFinished(int r2) {
                                if (!done[0]) glassFrom(g, snap, r2 == android.view.PixelCopy.SUCCESS ? fr : null, pr);
                                go.run();
                            }
                        }, main);
                    } catch (Throwable t) { glassFrom(g, snap, null, null); go.run(); }
                }
            }, main);
        } catch (Throwable t) { go.run(); }
    }
    /* While the pane is open over a playing picture, the glass keeps up with it: a small frame of the video
       is copied about twenty times a second, put under the (still) copy of the rest of the screen, blurred, and
       shown in the glass. Blurred, that reads as the picture moving behind the glass. */
    private GlassPanel lgPanel; private Bitmap lgSnap; private android.graphics.RectF lgRect; private Bitmap lgFrame;
    private int lgGen;
    private void liveGlass(GlassPanel g, Bitmap snap, android.graphics.RectF rect, Bitmap frame) {
        lgPanel = g; lgSnap = snap; lgRect = rect; lgFrame = frame;
        final int gen = ++lgGen;
        main.postDelayed(new Runnable() { @Override public void run() { liveGlassTick(gen); } }, 100);
    }
    private void liveGlassTick(final int gen) {
        if (gen != lgGen || lgPanel == null) return;
        boolean open = (sheet != null && sheet.shownNow() && sheet.panel == lgPanel) || (dlg != null && dlg.shownNow() && dlg.panel == lgPanel);
        NativePlayer np = act.nativePlayer();
        final android.view.SurfaceView plane = np != null ? np.shownPlane() : null;
        if (!open || plane == null || android.os.Build.VERSION.SDK_INT < 26) { lgPanel = null; lgSnap = null; lgFrame = null; return; }
        final long t0 = SystemClock.uptimeMillis();
        try {
            android.view.PixelCopy.request(plane, lgFrame, new android.view.PixelCopy.OnPixelCopyFinishedListener() {
                @Override public void onPixelCopyFinished(int r) {
                    if (gen != lgGen || lgPanel == null) return;
                    if (r != android.view.PixelCopy.SUCCESS) { main.postDelayed(new Runnable() { @Override public void run() { liveGlassTick(gen); } }, 50); return; }
                    // put together and blurred away from the screen's thread, so moving in Settings stays smooth
                    final GlassPanel g = lgPanel; final Bitmap sn = lgSnap, fr = lgFrame; final android.graphics.RectF rc = lgRect;
                    final float f = sn.getWidth() / (float) Math.max(1, act.getWindow().getDecorView().getWidth());
                    final boolean frosted = !glassMode().equals("liquid") || !g.liquidAllowed;
                    LG_POOL.execute(new Runnable() { @Override public void run() {
                        Bitmap b = null;
                        try { Bitmap solid = compose(sn, fr, rc, f); b = frosted ? Blur.glass(solid, 2, 3, 1.7f, 1f) : Blur.glass(solid, 1, 2, 1.9f, 1.06f); } catch (Throwable ignored) { }
                        final Bitmap out = b;
                        main.post(new Runnable() { @Override public void run() {
                            if (gen != lgGen || lgPanel != g) return;
                            if (out != null) g.layer[0].setImageBitmap(out);
                            long spent = SystemClock.uptimeMillis() - t0;
                            main.postDelayed(new Runnable() { @Override public void run() { liveGlassTick(gen); } }, Math.max(16, 50 - spent));
                        } });
                    } });
                }
            }, main);
        } catch (Throwable t) { lgPanel = null; }
    }
    private static final java.util.concurrent.ExecutorService LG_POOL = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static Bitmap compose(Bitmap snap, Bitmap frame, android.graphics.RectF frameRect, float f) {
        Bitmap solid = Bitmap.createBitmap(snap.getWidth(), snap.getHeight(), Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(solid);
        c.drawColor(0xFF070609);
        if (frame != null && frameRect != null)
            c.drawBitmap(frame, null, new android.graphics.RectF(frameRect.left * f, frameRect.top * f, frameRect.right * f, frameRect.bottom * f), new Paint(Paint.FILTER_BITMAP_FLAG));
        c.drawBitmap(snap, 0, 0, null);
        solid.setHasAlpha(false);
        return solid;
    }

    /** the pane's glass from a copy of the screen (with a frame of the video under its see-through part) */
    private void glassFrom(GlassPanel g, Bitmap snap, Bitmap frame, android.graphics.RectF frameRect) {
        lgGen++; lgPanel = null;
        if (frame != null && frameRect != null) liveGlass(g, snap, frameRect, frame);
        try {
            Bitmap solid = Bitmap.createBitmap(snap.getWidth(), snap.getHeight(), Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(solid);
            c.drawColor(0xFF070609);
            float f = snap.getWidth() / (float) Math.max(1, act.getWindow().getDecorView().getWidth());
            if (frame != null && frameRect != null)
                c.drawBitmap(frame, null, new android.graphics.RectF(frameRect.left * f, frameRect.top * f, frameRect.right * f, frameRect.bottom * f), new Paint(Paint.FILTER_BITMAP_FLAG));
            c.drawBitmap(snap, 0, 0, null);
            solid.setHasAlpha(false);
            boolean frosted = !glassMode().equals("liquid") || !g.liquidAllowed;
            Bitmap b = frosted ? Blur.glass(solid, 2, 3, 1.7f, 1f) : Blur.glass(solid, 1, 2, 1.9f, 1.06f);
            g.setPhotoBox(0, 0, W, H);
            for (int i = 0; i < 2; i++) { g.layer[i].animate().cancel(); setKb(g.layer[i], 1, 0, 0); }
            g.layer[0].setImageBitmap(b); g.layer[0].setAlpha(1f);
            g.layer[1].setImageDrawable(null); g.layer[1].setAlpha(0f);
            ((android.view.ViewGroup) g.layer[0].getParent()).bringChildToFront(g.layer[0]);
            g.refreshTint();
        } catch (Throwable ignored) { }
    }
    /* The TV was switched off while a channel was on full screen: switching it on goes straight back to
       that channel (within 12 hours; Settings › Live TV › Turn on to Live TV). */
    private void wakeToLive(final int tries) {
        String w = prefs.getString("ui.liveWake", null);
        if (w == null || w.isEmpty() || w.equals("null")) return;
        if (!built || live == null) { if (tries < 20) main.postDelayed(new Runnable() { @Override public void run() { wakeToLive(tries + 1); } }, 500); return; }
        prefs.edit().remove("ui.liveWake").apply();
        if (!S.optBoolean("liveWake", true) || !liveNativeOk()) return;
        try {
            JSONObject o = new JSONObject(w);
            if (System.currentTimeMillis() - o.optLong("at") > 12 * 3600000L || o.optString("u").isEmpty()) return;
            Diag.ev("live: back on → " + o.optString("n"));
            livePlayUrl(o.optString("u"));
        } catch (Exception ignored) { }
    }
    void appResumed() { Diag.ev("app back"); if (live != null) live.appResumed(); wakeToLive(0); main.postDelayed(new Runnable() { @Override public void run() { fyNext(); } }, 1500); }
    /** Settings, from the page (shows it, or hands it new rows) */
    void sheetShow(String json) {
        if (sheet == null || !sheet.shownNow()) Diag.ev("settings opened");
        JSONObject o;
        try { o = new JSONObject(json); } catch (Exception e) { return; }
        if (sheet == null) {
            GlassPanel g = glass(3f, 0xB81C1C1F);
            g.copyGlass(menuPanel);
            panels.remove(g);                       // (its glass is a snapshot of what's behind it, not the tab's photo)
            sheet = new NativeSheet(this, g);
            addView(sheet, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        if (!sheet.shownNow()) {
            if (mode.equals("menu")) closeMenu();
            if (mode.equals("top")) exitTopQuiet();
            if (mode.equals("shelf")) exitShelf();
            mode = "sheet";
            sheet.bringToFront();
            if (toastView != null) toastView.bringToFront();
            sheet.setData(o);
            final NativeSheet sh = sheet;
            snapGlass(sheet.panel, new Runnable() { @Override public void run() { if (mode.equals("sheet") || mode.equals("edit")) sh.show(); } });
            drawTop(); applyFocus(true, ' ');
        } else sheet.setData(o);
    }
    /** a message with buttons, from the page */
    void dialogShow(String json) {
        Diag.ev("message shown");
        JSONObject o;
        try { o = new JSONObject(json); } catch (Exception e) { return; }
        if (dlg == null) {
            GlassPanel g = glass(2.6f, 0xE61C1C20);
            g.copyGlass(menuPanel);
            panels.remove(g);
            dlg = new NativeDialog(this, g);
            addView(dlg, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        if (mode.equals("menu")) closeMenu();
        if (mode.equals("top")) exitTopQuiet();
        if (mode.equals("shelf")) exitShelf();
        if (!dlg.shownNow()) dlgFrom = mode.equals("dialog") ? dlgFrom : mode;
        mode = "dialog";
        dlg.bringToFront();
        if (toastView != null) toastView.bringToFront();
        dlg.set(o);
        if (!dlg.shownNow()) { final NativeDialog d = dlg; snapGlass(dlg.panel, new Runnable() { @Override public void run() { if (mode.equals("dialog")) d.show(); } }); }
        drawTop(); applyFocus(true, ' ');
    }
    void dialogClose() {
        if (dlg == null || !dlg.shownNow()) return;
        dlg.hide();
        if (mode.equals("dialog")) {
            mode = dlgFrom.equals("sheet") && sheet != null && sheet.shownNow() ? "sheet" : dlgFrom.equals("find") && find != null && find.shownNow() ? "find" : "grid";
            drawTop(); applyFocus(true, ' ');
        }
    }

    // ================================================================== Search (worked out here: NativeSearch)
    NativeSearch search;
    void searchOpen(String q) {
        if (search == null) search = new NativeSearch(this);
        if (search.active && find != null && find.shownNow()) { if (q != null && !q.isEmpty()) search.query(q, false, false); return; }
        Diag.ev("search shown (the app's own)");
        search.open(q);
    }
    /** the mic: Search opens, and what's said is searched (it arrives through the page's tsVoice) */
    void searchVoice() { searchOpen(""); bridge.voiceInput("Search TopShelf"); }
    void searchQuery(String q, boolean submit) { searchOpen(""); search.query(q, submit, false); }
    void searchShow(JSONObject o, boolean first) { findShowObj(o, first); }
    List<App> appsForSearch() { return new ArrayList<>(items); }
    void launchFromSearch(App a) { if (a.special) { page("openSettings()"); return; } for (App x : items) if (x.id.equals(a.id)) { openApp(x); return; } openApp(a); }
    NativeVod vodForSearch() { return vod != null ? vod : vodM; }
    /** Live TV's channels and guide, for Search (made, but not shown, if Live TV hasn't been opened yet) */
    NativeLive liveData() {
        if (!liveNativeOk()) return null;
        if (live == null) { live = new NativeLive(this); live.setVisibility(INVISIBLE); addView(live, indexOfChild(vod) + 1, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)); }
        return live;
    }
    boolean nativeSearchOn() { return search != null && search.active; }

    /** Search or the sports picker, from the page */
    void findShow(String json) {
        JSONObject o;
        try { o = new JSONObject(json); } catch (Exception e) { return; }
        if (o.optString("kind").equals("search") && nativeSearchOn()) return;     // (the app's own Search is up)
        Diag.ev("search/picker shown");
        findShowObj(o, true);
    }
    private void findShowObj(JSONObject o, boolean first) {
        if (find == null) {
            find = new NativeFind(this);
            addView(find, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        if (!find.shownNow()) {
            if (mode.equals("menu")) closeMenu();
            if (mode.equals("top")) exitTopQuiet();
            if (mode.equals("shelf")) exitShelf();
            findFrom = mode.equals("sheet") ? "sheet" : "grid";
            mode = "find";
            find.bringToFront();
            if (toastView != null) toastView.bringToFront();
            find.setData(o, true);
            find.show();
            // once it covers the screen, nothing under it is drawn at all
            main.removeCallbacks(coverOn);
            main.postDelayed(coverOn, 340);
            if (o.optString("kind").equals("search")) main.postDelayed(new Runnable() { @Override public void run() {
                if (find.shownNow() && mode.equals("find") && find.kindNow().equals("search")) find.startTyping();
            } }, 250);
            drawTop(); applyFocus(true, ' ');
        } else find.setData(o, first);
    }
    /* While Search covers the whole screen, the screens under it are set aside (not drawn), and put
       back as it closes. */
    private final List<View> covered = new ArrayList<>();
    private final Runnable coverOn = new Runnable() { @Override public void run() {
        if (find == null || !find.shownNow() || !covered.isEmpty()) return;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v == find || v == toastView || v == hud || v == menuPanel || v == dlg || v == launchOv || v.getVisibility() != VISIBLE) continue;
            v.setVisibility(INVISIBLE);
            covered.add(v);
        }
    } };
    private void uncover() {
        main.removeCallbacks(coverOn);
        for (View v : covered) v.setVisibility(VISIBLE);
        covered.clear();
    }

    void findClose() {
        if (search != null && search.active && find != null && find.kindNow().equals("search")) search.active = false;
        if (find == null || !find.shownNow()) return;
        uncover();
        find.hide();
        if (mode.equals("find") || mode.equals("menu")) {
            if (mode.equals("menu")) closeMenu();
            mode = findFrom.equals("sheet") && sheet != null && sheet.shownNow() ? "sheet" : "grid";
            drawTop(); applyFocus(true, ' ');
        }
    }
    void sheetClose() {
        if (sheet == null || !sheet.shownNow()) return;
        sheet.hide();
        if (mode.equals("sheet")) { mode = "grid"; drawTop(); applyFocus(true, ' '); }
    }

    /** Sports' games, from the page */
    void setSports(String json) { if (sports != null) sports.setData(json); }
    private SportsData sportsData;
    /** Sports' scores, schedules and channel matching (worked out here, no longer by the page) */
    SportsData sportsData() { if (sportsData == null) sportsData = new SportsData(this); return sportsData; }
    /** the page's team picker changed your sports or teams */
    void sportsPrefsChanged() { if (sportsData != null) sportsData.prefsChanged(); }
    /** a menu the page made (a game's options): its choices run back on the page */
    void pageMenu(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray ls = o.optJSONArray("labels");
            if (ls == null || ls.length() == 0) return;
            List<String> labels = new ArrayList<>(); List<Runnable> acts = new ArrayList<>();
            for (int i = 0; i < ls.length(); i++) {
                labels.add(ls.optString(i));
                final int k = i;
                acts.add(new Runnable() { @Override public void run() { page("window.nhMenuPick && nhMenuPick(" + k + ")"); } });
            }
            if (mode.equals("menu")) closeMenu();
            if (mode.equals("top")) exitTopQuiet();
            if (mode.equals("shelf")) exitShelf();
            showMenu(labels, acts, null, 1f, o.optString("title"));
        } catch (Exception ignored) { }
    }

    /** a menu next to a picture (or in the middle): labels and what each does */
    void showMenu(List<String> labels, List<Runnable> acts, View anchor, float anchorScale, String title) {
        menuHead(title, labels);
        menuActs.clear(); menuActs.addAll(acts);
        menuRows.removeAllViews();
        for (String l : labels) {
            TextView t = text(fMed, 1.35f, Color.WHITE);
            t.setText(l);
            t.setPadding(px(1.3f), px(1f), px(1.3f), px(1f));
            menuRows.addView(t, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
        menuSel = 0;
        mode = "menu";
        drawMenu();
        menuPanel.setVisibility(VISIBLE);
        menuPanel.bringToFront();
        menuPanel.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        int mw = menuPanel.getMeasuredWidth(), mh = menuPanel.getMeasuredHeight();
        float x = W / 2f - mw / 2f, y = H / 2f - mh / 2f;
        if (anchor != null && anchor.getWidth() > 0) {
            int[] p = new int[2], me = new int[2];
            anchor.getLocationInWindow(p); getLocationInWindow(me);
            float extra = anchor.getWidth() * (anchorScale - 1) / 2f;
            float l = p[0] - me[0] - extra, r = p[0] - me[0] + anchor.getWidth() * anchorScale - extra, tp = p[1] - me[1];
            x = r + px(1.6f); y = tp;
            if (x + mw > W - px(1)) x = l - mw - px(1.6f);
            y = Math.max(px(2), Math.min(y, H - mh - px(1)));
        }
        x = Math.max(px(1), Math.min(x, W - mw - px(1)));      // (a narrow screen: kept on it)
        menuPanel.setX(x); menuPanel.setY(y);
        menuPanel.setScaleX(.92f); menuPanel.setScaleY(.92f);
        menuPanel.setPivotX(0); menuPanel.setPivotY(0);
        menuPanel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250).setInterpolator(TileView.SPRING).start();
        act.haptic();
    }

    /** the title's picture, darkened, with what's happening, while another app opens */
    void showLaunch(Bitmap backdrop, String title, String sub) {
        launchOv.removeAllViews();
        if (backdrop != null) {
            ImageView iv = new ImageView(getContext());
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(backdrop);
            launchOv.addView(iv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            View d = new View(getContext()); d.setBackgroundColor(0xB3000000);
            launchOv.addView(d, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView name = text(fSemi, 3f, Color.WHITE); name.setText(title); name.setGravity(Gravity.CENTER);
        box.addView(name, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        TextView st = text(fMed, 1.4f, 0xB3FFFFFF); st.setText(sub);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); sl.topMargin = px(.8f);
        box.addView(st, sl);
        launchOv.addView(box, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        box.setScaleX(.94f); box.setScaleY(.94f); box.setAlpha(0f);
        launchOv.bringToFront();
        launchOv.setVisibility(VISIBLE);
        launchOv.animate().alpha(1f).setDuration(220).start();
        box.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(380).setInterpolator(TileView.SPRING).start();
        main.removeCallbacks(launchHide);
        main.postDelayed(launchHide, 6000);
    }
    private final Runnable launchHide = new Runnable() { @Override public void run() { hideLaunch(); } };
    private void enterTop() {
        mode = "top";
        topSel = Math.max(0, java.util.Arrays.asList(TAB_IDS).indexOf(tab));
        applyFocus(true, ' ');
        drawTop();
        updateBars();
    }
    private void exitTop() { mode = "grid"; drawTop(); applyFocus(true, ' '); updateBars(); }

    private void topActivate() {
        String a = topActs.get(topSel);
        if (a.equals("now:game")) { exitTopQuiet(); if (nowGameEv != null && sportsData != null) sportsData.menuFor(nowGameEv); return; }
        if (a.equals("now:cw")) { exitTopQuiet(); if (nowCwM != null) openTitle(nowCwM); return; }
        if (a.startsWith("app:")) {
            String id = a.substring(4);
            exitTopQuiet();
            for (App x : items) if (x.id.equals(id)) { openApp(x); return; }
            return;
        }
        if (a.equals("tab")) {
            final String id = TAB_IDS[topSel];
            if (id.equals(tab)) { exitTop(); return; }
            if (nativeTab(id)) { mode = "grid"; showTab(id); return; }
            leaveForPage();
            exitTopQuiet();
            page("switchTab('" + id + "', false)");
            return;
        }
        exitTopQuiet();
        if (a.equals("photos")) enterShelf();
        else if (a.equals("search")) searchOpen("");
        else if (a.equals("remote")) page("openLink()");
        else page("openSettings()");
    }
    private void exitTopQuiet() { mode = "grid"; drawTop(); applyFocus(false, ' '); updateBars(); }

    private final Runnable hoverTab = new Runnable() { @Override public void run() {
        if (!mode.equals("top") || topSel >= TAB_IDS.length) return;
        String id = TAB_IDS[topSel];
        if (id.equals(tab)) return;
        if (nativeTab(id)) { showTab(id); drawTop(); return; }
        // a tab that's still the page's (Sports): it opens with its top bar selected, and carries on from there
        leaveForPage();
        page("switchTab('" + id + "', true)");
    } };
    /** the page's top bar rested on one of the tabs drawn here: show it, with the top bar still selected */
    void showTabTop(String id) {
        showTab(id);
        if (!mode.equals("top")) enterTop();
        drawTop();
    }

    // ---- photo mode & screensaver

    private void enterShelf() {
        if (vodPlayingNow()) return;          // (never over a movie or show)
        if (onVod()) showTab("home");
        if (photos.length() == 0 || photo[layer].getDrawable() == null) { toast("No photos loaded yet — see Settings"); return; }
        mode = "shelf";
        applyFocus(true, ' ');
        applyMode(true);
        scheduleSlide();
    }
    private void exitShelf() {
        mode = "grid";
        saver = false;
        if (paused) { paused = false; scheduleSlide(); }
        applyMode(true);
        applyFocus(true, ' ');
    }
    private void startSaver() {
        if (photos.length() == 0 || !owner.equals("native") || !(mode.equals("grid") || mode.equals("top"))) return;
        // never over something you're watching: Live TV full screen, a movie or show, a game page
        if ((isLive() && live.fullNow()) || vodPlayingNow() || MainActivity.videoRecently()) return;
        Diag.ev("screensaver on (" + tab + ")");
        HeapReport.maybe(getContext().getApplicationContext());
        if (mode.equals("top")) exitTopQuiet();
        if (onVod()) showTab("home");
        if (paused) paused = false;
        saver = true;
        mode = "shelf";
        applyFocus(false, ' ');
        applyMode(true);
        scheduleSlide();
        page("document.body.classList.add('saver')");
    }
    private void stopSaver() {
        saver = false;
        page("document.body.classList.remove('saver'); if (window.IDLE) IDLE.last = Date.now()");
        exitShelf();
    }

    // ---- move mode

    private void startMove() {
        if (items.get(focus).special) return;
        mode = "move";
        for (int i = 0; i < items.size(); i++) { TileView t = tiles.get(items.get(i).id); if (t != null) t.setAlpha(i == focus ? 1f : .55f); }
        TileView t = tiles.get(items.get(focus).id);
        if (t != null) t.setMoving(true);
        toast("Use the arrows to move · OK when done");
    }
    private void moveItem(char dir) {
        int last = items.size() - 2, c = focus % COLS, to = focus;
        if (dir == 'l' && c > 0) to = focus - 1;
        if (dir == 'r' && c < COLS - 1 && focus + 1 <= last) to = focus + 1;
        if (dir == 'u' && focus >= COLS) to = focus - COLS;
        if (dir == 'd') to = Math.min(focus + COLS, last);
        if (to == focus) { TileView t = tiles.get(items.get(focus).id); if (t != null) t.bump(dir); return; }
        App it = items.remove(focus);
        items.add(to, it);
        focus = to;
        renderTilesAnimated();
        applyFocus(true, ' ');
    }
    private void renderTilesAnimated() {
        for (int i = 0; i < items.size(); i++) {
            TileView t = tiles.get(items.get(i).id);
            if (t == null) continue;
            FrameLayout home = inDock(i) ? dockCells : drawerGrid;
            t.setRestShadow(home == dockCells);
            t.setLabelOn(home != dockCells);
            float x = tileX(i), y = tileY(i);
            if (t.getParent() != home) {
                ((FrameLayout) t.getParent()).removeView(t);
                home.addView(t, new LayoutParams(px(TW), px(TH + 4)));
                t.setX(px(x)); t.setY(px(y));
            } else t.animate().x(px(x)).y(px(y)).setDuration(380).setInterpolator(TileView.EASE).start();
        }
    }
    private void endMove() {
        TileView t = tiles.get(items.get(focus).id);
        if (t != null) t.setMoving(false);
        for (TileView v : tiles.values()) v.setAlpha(1f);
        mode = "grid";
        saveOrder();
        applyFocus(true, ' ');
    }

    // ---- app menu

    private void openMenu() {
        final App a = items.get(focus);
        if (a.special) return;
        menuActs.clear();
        List<String> labels = new ArrayList<>();
        labels.add("Move"); menuActs.add(new Runnable() { @Override public void run() { startMove(); } });
        if (focus >= COLS) {
            labels.add("Move to front"); menuActs.add(new Runnable() { @Override public void run() {
                App it = items.remove(focus); items.add(0, it); focus = 0; renderTilesAnimated(); saveOrder(); applyFocus(true, ' ');
            } });
        }
        labels.add("Change icon"); menuActs.add(new Runnable() { @Override public void run() {
            page("(function(){const a=items.find(x=>x.id===" + JSONObject.quote(a.id) + ");if(a)icOpen(a);})()");
        } });
        labels.add("App info"); menuActs.add(new Runnable() { @Override public void run() { bridge.appInfo(a.id); } });
        labels.add("Hide from home"); menuActs.add(new Runnable() { @Override public void run() { hideApp(a); } });
        menuHead(a.label, labels);
        menuRows.removeAllViews();
        for (String l : labels) {
            TextView t = text(fMed, 1.35f, Color.WHITE);
            t.setText(l);
            t.setPadding(px(1.3f), px(1f), px(1.3f), px(1f));
            menuRows.addView(t, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
        menuSel = 0;
        mode = "menu";
        drawMenu();
        TileView tv = tiles.get(a.id);
        menuPanel.setVisibility(VISIBLE);
        menuPanel.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        int mw = menuPanel.getMeasuredWidth(), mh = menuPanel.getMeasuredHeight();
        float x = W / 2f - mw / 2f, y = H / 2f - mh / 2f;
        if (tv != null) {
            int[] p = new int[2], me = new int[2];
            tv.card.getLocationInWindow(p); getLocationInWindow(me);
            float l = p[0] - me[0], r = l + tv.card.getWidth() * 1.12f, tp = p[1] - me[1];
            x = r + px(1.6f); y = tp;
            if (x + mw > W - px(1)) x = l - mw - px(1.6f);
            y = Math.max(px(2), Math.min(y, H - mh - px(1)));
        }
        x = Math.max(px(1), Math.min(x, W - mw - px(1)));      // (a narrow screen: kept on it)
        menuPanel.setX(x); menuPanel.setY(y);
        menuPanel.setScaleX(.92f); menuPanel.setScaleY(.92f);
        menuPanel.setPivotX(0); menuPanel.setPivotY(0);
        menuPanel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250).setInterpolator(TileView.SPRING).start();
        act.haptic();
    }
    /* a long menu (subtitles…) shows a window of rows that follows the selection, so it never runs off the screen */
    private static final int MENU_MAX = 9;
    private int menuTop;
    private String menuTitleText = "";
    private void drawMenu() {
        int n = menuRows.getChildCount();
        if (n > MENU_MAX) {
            if (menuSel < menuTop) menuTop = menuSel;
            if (menuSel >= menuTop + MENU_MAX) menuTop = menuSel - MENU_MAX + 1;
            menuTop = Math.max(0, Math.min(menuTop, n - MENU_MAX));
            String t0 = menuTitleText.isEmpty() ? "" : menuTitleText + "   ";
            menuTitle.setText(t0 + (menuSel + 1) + " of " + n + (menuTop > 0 ? "  ▲" : "") + (menuTop + MENU_MAX < n ? "  ▼" : ""));
            menuTitle.setVisibility(VISIBLE);
        } else menuTop = 0;
        for (int i = 0; i < n; i++) {
            TextView t = (TextView) menuRows.getChildAt(i);
            t.setVisibility(n <= MENU_MAX || (i >= menuTop && i < menuTop + MENU_MAX) ? VISIBLE : GONE);
            if (n > MENU_MAX) { t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.END); }
            boolean sel = i == menuSel;
            if (GlassPanel.look.equals("air")) t.setBackground(new GlassPanel.Cut(sel ? GlassPanel.selFill() : Color.TRANSPARENT));
            else { GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(GlassPanel.selRadius(1.4f * R)); bg.setColor(sel ? GlassPanel.selFill() : Color.TRANSPARENT); t.setBackground(bg); }
            t.setTextColor(sel ? GlassPanel.selText() : Color.WHITE);
        }
    }
    private void closeMenu() {
        mode = menuFrom.equals("sheet") && sheet != null && sheet.shownNow() ? "sheet" : menuFrom.equals("find") && find != null && find.shownNow() ? "find" : "grid";
        menuPanel.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() { @Override public void run() { menuPanel.setVisibility(GONE); } }).start();
        applyFocus(true, ' ');
        updateBars();
    }
    private void hideApp(App a) {
        JSONArray h = arr(prefs.getString("ui.hidden", null));
        h.put(a.id);
        prefs.edit().putString("ui.hidden", h.toString()).apply();
        page("window.nhReloaded && nhReloaded('hidden')");
        toast(a.label + " hidden · unhide in Settings");
        loadApps();
        applyFocus(true, ' ');
    }

    // ---- launching an app (its tile, big, on a dark backdrop, while Android opens it)

    private void openApp(final App a) {
        launchOv.removeAllViews();
        TileView t = tiles.get(a.id);
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView big = new ImageView(getContext());
        big.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (t != null && t.art.getDrawable() != null) big.setImageDrawable(t.art.getDrawable().getConstantState() != null ? t.art.getDrawable().getConstantState().newDrawable() : t.art.getDrawable());
        final float rad = 1.6f * R;
        big.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rad); } });
        big.setClipToOutline(true);
        big.setElevation(3 * R);
        box.addView(big, new LinearLayout.LayoutParams(px(31), px(31 * 9 / 16f)));
        TextView name = text(fSemi, 2.4f, Color.WHITE); name.setText(a.label);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); nl.topMargin = px(2.4f);
        box.addView(name, nl);
        TextView sub = text(fMed, 1.3f, 0x9EFFFFFF); sub.setText("Opening…");
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); sl.topMargin = px(.6f);
        box.addView(sub, sl);
        launchOv.addView(box, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        box.setScaleX(.9f); box.setScaleY(.9f); box.setAlpha(0f);
        launchOv.setVisibility(VISIBLE);
        launchOv.animate().alpha(1f).setDuration(220).start();
        box.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(380).setInterpolator(TileView.SPRING).start();
        main.postDelayed(new Runnable() { @Override public void run() {
            boolean ok = false;
            try { ok = bridge.launch(a.id); } catch (Exception ignored) { }
            if (!ok) { hideLaunch(); toast("Couldn't open " + a.label); }
        } }, 140);
        main.postDelayed(new Runnable() { @Override public void run() { hideLaunch(); } }, 6000);
    }
    void hideLaunch() {
        if (launchOv == null || launchOv.getVisibility() != VISIBLE) return;
        launchOv.animate().alpha(0f).setDuration(250).withEndAction(new Runnable() { @Override public void run() { launchOv.setVisibility(GONE); } }).start();
    }

    // ================================================================== Top Shelf row

    /** from the page: {head, items:[{name, img, logo, mine}]} */
    private JSONArray pageShelf = new JSONArray(); private String pageHead = "Popular now";
    private int cwCount;
    void setShelf(String json) {
        try {
            JSONObject o = new JSONObject(json);
            pageHead = o.optString("head", "Popular now");
            pageShelf = o.optJSONArray("items") != null ? o.optJSONArray("items") : new JSONArray();
        } catch (Exception e) { pageShelf = new JSONArray(); }
        mergeShelf();
        if (!built) return;
        buildTshelf();
        updateTshelf(true);
    }

    /* instant play: the first two titles of Continue watching have their streams found ahead (the first one's
       link resolved too), a few seconds after Home has them, so choosing one plays at once */
    private java.util.List<JSONObject> cwAhead;
    private final Runnable cwAheadRun = new Runnable() { @Override public void run() {
        java.util.List<JSONObject> l = cwAhead; if (l == null || vodPlayingNow()) return;
        for (int i = 0; i < l.size() && i < 2; i++) {
            JSONObject m = l.get(i);
            Addons.get(getContext()).prepare(m.optString("type"), m.optString("video", m.optString("id")), m.optJSONObject("cwStream"), i == 0, "Continue watching · " + m.optString("name"));
        }
    } };
    private void prepareCw(java.util.List<JSONObject> cw) {
        cwAhead = cw;
        removeCallbacks(cwAheadRun); postDelayed(cwAheadRun, 5000);
    }
    /** Continue watching (movies and shows) first in the top shelf, then the page's titles */
    private void mergeShelf() {
        JSONArray all = new JSONArray();
        java.util.List<JSONObject> cw = Progress.continueList(getContext(), null, 12);
        cwCount = cw.size();
        prepareCw(cw);
        for (JSONObject m : cw) {
            try {
                JSONObject c = new JSONObject(m.toString());
                c.put("img", m.optString("background").isEmpty() ? m.optString("poster") : m.optString("background"));
                c.put("cw", true);
                all.put(c);
            } catch (Exception ignored) { }
        }
        for (int i = 0; i < pageShelf.length(); i++) all.put(pageShelf.opt(i));
        tsItems = all;
        tsHeadText = cwCount > 0 ? "Continue watching" + (pageShelf.length() > 0 ? "   ·   then " + pageHead : "") : pageHead;
        if (tsSel >= tsItems.length()) tsSel = Math.max(0, tsItems.length() - 1);
    }
    private void cwRefresh() {
        if (!built) return;
        mergeShelf();
        buildTshelf();
        updateTshelf(false);
    }

    private final List<FrameLayout> tsCards = new ArrayList<>();
    private void buildTshelf() {
        tsHead.setText(tsHeadText);
        tsStrip.removeAllViews(); tsCards.clear();
        float x = 3;
        for (int i = 0; i < tsItems.length(); i++) {
            final JSONObject m = tsItems.optJSONObject(i);
            final FrameLayout card = new FrameLayout(getContext());
            final float rad = 1.2f * R;
            card.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rad); } });
            card.setClipToOutline(true);
            card.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { 0xFF34343C, 0xFF1C1C22 }));
            card.setElevation(.8f * R);
            final ImageView iv = new ImageView(getContext());
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(iv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            View shade = new View(getContext());
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] { 0x99000000, 0x00000000, 0x00000000 }));
            card.addView(shade, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            TextView name = text(fSemi, 1.2f, Color.WHITE);
            name.setText(m == null ? "" : m.optString("name"));
            name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setShadowLayer(.5f * R, 0, .1f * R, 0xB3000000);
            FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            nl.leftMargin = px(1); nl.rightMargin = px(1); nl.bottomMargin = px(.8f);
            card.addView(name, nl);
            if (m != null && m.optBoolean("cw")) {
                // how far in, and which episode
                View track = new View(getContext()); track.setBackgroundColor(0x80000000);
                card.addView(track, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, px(.4f), Gravity.BOTTOM));
                View fill = new View(getContext()); fill.setBackgroundColor(0xFFFF453A);
                card.addView(fill, new FrameLayout.LayoutParams(Math.round(px(22) * (float) m.optDouble("cwPct", 0)), px(.4f), Gravity.BOTTOM));
                String lab = m.optString("cwLabel");
                if (!lab.isEmpty()) {
                    TextView ep = text(fSemi, .85f, Color.WHITE); ep.setText(lab); ep.setSingleLine(true); ep.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    GradientDrawable eb = new GradientDrawable(); eb.setCornerRadius(.4f * R); eb.setColor(0xD9000000); ep.setBackground(eb);
                    ep.setPadding(px(.5f), px(.2f), px(.5f), px(.2f));
                    FrameLayout.LayoutParams el = new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT);
                    el.topMargin = px(.7f); el.leftMargin = px(.7f); el.rightMargin = px(.7f);
                    card.addView(ep, el);
                }
            }
            if (m != null && m.optBoolean("mine")) {
                TextView mk = text(fSemi, .9f, 0xFF111111); mk.setText("✓"); mk.setGravity(Gravity.CENTER);
                GradientDrawable mb = new GradientDrawable(); mb.setShape(GradientDrawable.OVAL); mb.setColor(0xE6FFFFFF); mk.setBackground(mb);
                FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(px(1.8f), px(1.8f), Gravity.TOP | Gravity.RIGHT); ml.topMargin = px(.7f); ml.rightMargin = px(.7f);
                card.addView(mk, ml);
            }
            tsStrip.addView(card, at(x, 1f, px(22), px(12.4f)));
            tsCards.add(card);
            x += 22 + 1.4f;
            final String img = m == null ? "" : m.optString("img");
            if (!img.isEmpty()) imgs.load("ts:" + img, new HomeImages.Source() { @Override public byte[] bytes() throws Exception { return HomeImages.http(img, null); } },
                    px(22), px(12.4f), new HomeImages.Done() { @Override public void done(Bitmap b) { if (b != null) iv.setImageBitmap(b); } });
            final String logo = m == null ? "" : m.optString("logo");
            if (!logo.isEmpty()) {
                final TextView nm = name;
                final ImageView lv = new ImageView(getContext());
                lv.setAdjustViewBounds(true);
                lv.setMaxWidth(px(13)); lv.setMaxHeight(px(3.6f));
                FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT);
                ll.leftMargin = px(1); ll.bottomMargin = px(.8f);
                card.addView(lv, ll);
                imgs.load("tsl:" + logo, new HomeImages.Source() { @Override public byte[] bytes() throws Exception { return HomeImages.http(logo, null); } },
                        px(13), px(3.6f), new HomeImages.Done() { @Override public void done(Bitmap b) {
                            if (b != null) { lv.setImageBitmap(b); nm.setVisibility(INVISIBLE); }
                        } });
            }
        }
        // the strip is as wide as all its cards: it slides left inside the row, and a strip only as wide
        // as the screen was cutting off every card past the first screenful
        LayoutParams sl = (LayoutParams) tsStrip.getLayoutParams();
        sl.width = px(x + 6);
        tsStrip.setLayoutParams(sl);
        markTshelf(false);
    }

    private void markTshelf(boolean animate) {
        for (int i = 0; i < tsCards.size(); i++) {
            FrameLayout c = tsCards.get(i);
            boolean f = mode.equals("tshelf") && i == tsSel;
            c.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).translationZ(f ? 1.6f * R : 0).setDuration(animate ? 250 : 0).setInterpolator(TileView.EASE).start();
            c.setForeground(f ? ring() : null);
            c.setZ(f ? 3 : 0);
        }
        if (mode.equals("tshelf") && tsSel < tsCards.size()) {
            float left = 3 + tsSel * (22 + 1.4f) - 3, visible = SW - 6;
            float sx = -tsStrip.getTranslationX() / R;
            if (left < sx) sx = Math.max(0, left);
            else if (left + 22 + 6 > sx + visible) sx = left + 22 + 6 - visible;
            tsStrip.animate().translationX(-px(sx)).setDuration(animate ? 380 : 0).setInterpolator(TileView.EASE).start();
        }
    }
    private android.graphics.drawable.Drawable ring() {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(GlassPanel.look.equals("air") ? 0 : 1.2f * R);
        g.setStroke(Math.max(2, px(GlassPanel.look.equals("air") ? .24f : .16f)), GlassPanel.look.equals("glass") || GlassPanel.look.equals("spot") ? 0xE6FFFFFF : GlassPanel.selFill());
        return g;
    }

    private void updateTshelf(boolean animate) {
        if (tshelf == null) return;
        boolean on = tshelfWanted();
        tshelf.animate().alpha(on ? 1f : 0f).translationY(on ? 0 : px(1.6f)).setDuration(animate ? 450 : 0).setInterpolator(TileView.EASE).start();
        if (on && tsCards.isEmpty() && tsItems.length() > 0) buildTshelf();
        if (!mode.equals("tshelf")) markTshelf(animate);
    }

    // ================================================================== photos

    private Runnable slideTimer;

    private void loadPhotos(boolean force) {
        final String sub = sub(), sort = sort();
        String cached = prefs.getString("photos." + sub.toLowerCase(Locale.US) + "." + sort, null);
        if (cached != null) setPhotos(arr(cached), false);
        bridge.fetchPhotosFor(sub, sort, new Bridge.PhotosCb() { @Override public void done(final String json, final String err) {
            main.post(new Runnable() { @Override public void run() {
                if (!sub.equals(sub()) || !sort.equals(sort())) return;
                if (json != null) { JSONArray a = arr(json); photoStatus = a.length() + " photos" + Bridge.photoNote; setPhotos(a, true); }
                else { photoStatus = "Failed — " + err; if (photos.length() == 0) aurora.setAlpha(1f); }
                page("window.nhPhotoStatus && nhPhotoStatus(" + JSONObject.quote(photoStatus) + ")");
            } });
        } });
    }

    private void setPhotos(JSONArray list, boolean fresh) {
        if (list.length() == 0) return;
        if (list.toString().equals(photosRaw)) return;
        photosRaw = list.toString();
        List<JSONObject> l = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) if (list.optJSONObject(i) != null) l.add(list.optJSONObject(i));
        Collections.shuffle(l, rnd);
        boolean had = photos.length() > 0 && photo[layer].getDrawable() != null;
        photos = new JSONArray(l);
        pIndex = -1;
        if (!had || !fresh) showPhoto(1); else scheduleSlide();
    }
    private String photosRaw = "";

    private void showPhoto(final int step) { showPhoto(step, 0); }
    /** the photo at the size it's drawn (it was up to twice that; a picture much bigger than the
     *  screen is slower to draw every frame, and looks no sharper) */
    private Bitmap fitPhoto(Bitmap b) {
        if (b == null) return null;
        float tw = W * 1.06f * 1.09f, th = H * 1.06f * 1.09f;     // room for the slow zoom
        float sc = Math.max(tw / b.getWidth(), th / b.getHeight());
        if (sc >= .95f) return b;
        Bitmap o = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * sc), Math.round(b.getHeight() * sc), true);
        if (o != b) b.recycle();
        return o;
    }
    private void showPhoto(final int step, final int tries) {
        if (photos.length() == 0 || !built || onVod()) return;
        act.snapHomeSoon();             // (the picture of Home that covers Google's home screen, kept current)
        if (tries >= Math.min(photos.length(), 8)) { photoStatus = photos.length() + " photos found, but the images wouldn't load"; return; }
        pIndex = (pIndex + step + photos.length()) % photos.length();
        final JSONObject p = photos.optJSONObject(pIndex);
        final int token = ++showToken;
        final String glass = glassMode();
        imgs.run(new Runnable() { @Override public void run() {
            Bitmap bmp = null;
            String[] urls = { p.optString("img"), p.optString("full") };
            for (String u : urls) {
                if (u == null || u.isEmpty()) continue;
                try {
                    byte[] d = act.photoCache().get(u, bridge.browserUA);
                    if (d != null) bmp = fitPhoto(HomeImages.decode(d, W, H));
                } catch (Throwable ignored) { }
                if (bmp != null) break;
            }
            if (bmp == null) { main.post(new Runnable() { @Override public void run() { if (token == showToken) showPhoto(step >= 0 ? 1 : -1, tries + 1); } }); return; }
            final Bitmap full = bmp;
            Bitmap light = null, frost = null;
            try {
                if (!glass.equals("solid")) {
                    light = Blur.glass(full, 4, 2, 1.9f, 1.06f);
                    frost = Blur.glass(full, 8, 3, 1.7f, 1f);
                }
            } catch (Throwable ignored) { }
            final Bitmap fl = light, ff = frost;
            final float[] lum = brightness(full);
            final int ink = GlassPanel.look.equals("ink") ? inkOf(full) : 0;
            main.post(new Runnable() { @Override public void run() {
                if (token != showToken) return;
                crossfade(full, fl, ff, p);
                adaptGlass(lum);
                if (ink != 0) { GlassPanel.inkColor = ink; for (GlassPanel g : panels) g.inkChanged(); }
            } });
        } });
    }

    private void crossfade(Bitmap full, Bitmap light, Bitmap frost, JSONObject p) {
        int next = layer ^ 1;
        ImageView nv = photo[next], cv = photo[layer];
        nv.animate().cancel();
        nv.setImageBitmap(full);
        glassBmp[next] = new Bitmap[] { light, frost };
        /* The glass shows the home photo only on Home: on another tab the panes (Settings, menus, the bars)
           keep showing that tab's backdrop, and pick up the new photo when Home comes back. */
        final boolean homeGlass = tab.equals("home");
        if (homeGlass) for (GlassPanel g : panels) {
            boolean frosted = !glassMode().equals("liquid") || !g.liquidAllowed;
            g.layer[next].setImageBitmap(frosted ? frost : light);
        }
        startKenBurns(next, true);
        // while the photos cross over, the glass isn't fully solid: draw the photo behind it again
        setPhotoHole(false);
        main.removeCallbacks(holeLater);
        main.postDelayed(holeLater, 1800);
        /* The new photo fades in over the old one, which stays solid underneath until it's covered
           (and is then hidden). Fading both at once meant two see-through full-screen pictures
           for the graphics chip to blend on every frame of the change; this is one. */
        final int fade = ++fadeToken;
        final int old = layer;
        ((android.view.ViewGroup) nv.getParent()).bringChildToFront(nv);
        cv.animate().cancel();
        if (cv.getDrawable() != null) cv.setAlpha(1f);
        nv.setAlpha(0f);
        nv.animate().alpha(1f).setDuration(1600).setInterpolator(new LinearInterpolator()).withEndAction(new Runnable() { @Override public void run() {
            if (fade != fadeToken) return;
            photo[old].setAlpha(0f);
            if (homeGlass && tab.equals("home")) for (GlassPanel g : panels) { g.layer[old].setAlpha(0f); g.refreshTint(); }
            releaseLayer(old, homeGlass);
        } }).start();
        if (homeGlass) for (GlassPanel g : panels) {
            g.layer[next].animate().cancel(); g.layer[old].animate().cancel();
            ((android.view.ViewGroup) g.layer[next].getParent()).bringChildToFront(g.layer[next]);
            if (g.layer[old].getDrawable() != null) g.layer[old].setAlpha(1f);
            g.layer[next].setAlpha(0f);
            g.layer[next].animate().alpha(1f).setDuration(1600).setInterpolator(new LinearInterpolator()).start();
        }
        layer = next;
        aurora.animate().alpha(0f).setDuration(1200).start();
        setPhotoDim(photoDim);
        setCaption(p);
        scheduleSlide();
    }

    private int fadeToken;
    /* The photo that faded out, and its two glass copies, are given back at once (about 25 MB of pictures each change):
       left to the garbage collector they piled up in graphics memory between collections. */
    private void releaseLayer(int old, boolean homeGlass) {
        final List<Bitmap> gone = new ArrayList<>();
        android.graphics.drawable.Drawable d = photo[old].getDrawable();
        if (d instanceof android.graphics.drawable.BitmapDrawable) gone.add(((android.graphics.drawable.BitmapDrawable) d).getBitmap());
        photo[old].setImageDrawable(null);
        Bitmap[] gb = glassBmp[old];
        glassBmp[old] = null;
        if (gb != null) for (Bitmap b : gb) if (b != null) gone.add(b);
        if (homeGlass) for (GlassPanel g : panels) {
            android.graphics.drawable.Drawable ld = g.layer[old].getDrawable();
            if (ld instanceof android.graphics.drawable.BitmapDrawable && gone.contains(((android.graphics.drawable.BitmapDrawable) ld).getBitmap())) g.layer[old].setImageDrawable(null);
        }
        // (after the screen has surely drawn its last frame with them; a picture still shown anywhere is kept)
        main.postDelayed(new Runnable() { @Override public void run() {
            for (Bitmap b : gone) {
                if (b == null || b.isRecycled() || inUse(b)) continue;
                b.recycle();
            }
        } }, 1500);
    }
    // ================================================================== giving pictures back
    /* Graphics memory only ever went up (330 MB → 460 MB in a few minutes of moving between tabs): each backdrop,
       title picture and episode still was dropped from its cache but left for the garbage collector, and since a
       picture's copy on the graphics chip is freed only when that happens — which, with so little else to collect,
       took minutes — dozens of screen-size pictures piled up there. Now a picture its screen has let go of is
       freed straight away (once nothing on screen shows it), and the collector is nudged after a change of screen. */
    private final java.util.Map<Bitmap, Integer> reclaimQ = new java.util.IdentityHashMap<>();
    /** frees these pictures once nothing on screen shows them any more (call once they're out of their cache) */
    void reclaim(Bitmap... bs) {
        if (bs == null) return;
        for (Bitmap b : bs) if (b != null && !b.isRecycled() && !reclaimQ.containsKey(b)) reclaimQ.put(b, 0);
        main.removeCallbacks(reclaimRun);
        main.postDelayed(reclaimRun, 2000);
    }
    private final Runnable reclaimRun = new Runnable() { @Override public void run() {
        if (reclaimQ.isEmpty()) return;
        java.util.Set<Bitmap> shown = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Bitmap, Boolean>());
        shownBitmaps(NativeHome.this, shown);
        for (Bitmap[] gb : glassBmp) if (gb != null) for (Bitmap x : gb) if (x != null) shown.add(x);
        for (GlassPanel g : panels) shownBitmaps(g, shown);          // (panes that are set aside, not on screen right now)
        int freed = 0; long bytes = 0;
        for (java.util.Iterator<Map.Entry<Bitmap, Integer>> it = reclaimQ.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Bitmap, Integer> e = it.next();
            Bitmap b = e.getKey();
            if (b.isRecycled()) { it.remove(); continue; }
            if (shown.contains(b)) { if (e.getValue() >= 20) it.remove(); else e.setValue(e.getValue() + 1); continue; }   // (still on screen: looked at again later)
            bytes += b.getAllocationByteCount();
            b.recycle(); freed++; it.remove();
        }
        if (freed > 0) Diag.ev("memory: gave back " + freed + " pictures (" + Math.round(bytes / 1048576.0) + " MB)");
        if (!reclaimQ.isEmpty()) main.postDelayed(this, 5000);
    } };
    private static void shownBitmaps(View v, java.util.Set<Bitmap> out) {
        if (v instanceof ImageView) { android.graphics.drawable.Drawable d = ((ImageView) v).getDrawable(); if (d instanceof android.graphics.drawable.BitmapDrawable) out.add(((android.graphics.drawable.BitmapDrawable) d).getBitmap()); }
        android.graphics.drawable.Drawable bgd = v.getBackground();
        if (bgd instanceof android.graphics.drawable.BitmapDrawable) out.add(((android.graphics.drawable.BitmapDrawable) bgd).getBitmap());
        if (v instanceof android.view.ViewGroup) { android.view.ViewGroup g = (android.view.ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) shownBitmaps(g.getChildAt(i), out); }
    }
    /** after a change of screen: the collector gets the pictures nobody holds any more (at most every 15 s, off the main thread) */
    private long gcAt;
    void settleMemory() { main.removeCallbacks(gcNudge); main.postDelayed(gcNudge, 4000); }
    private final Runnable gcNudge = new Runnable() { @Override public void run() {
        if (SystemClock.uptimeMillis() - gcAt < 15000) { main.postDelayed(this, 15000 - (SystemClock.uptimeMillis() - gcAt)); return; }
        gcAt = SystemClock.uptimeMillis();
        new Thread(new Runnable() { @Override public void run() { System.gc(); } }, "settle").start();
    } };

    private boolean inUse(Bitmap b) {
        for (ImageView v : photo) { android.graphics.drawable.Drawable d = v.getDrawable(); if (d instanceof android.graphics.drawable.BitmapDrawable && ((android.graphics.drawable.BitmapDrawable) d).getBitmap() == b) return true; }
        for (GlassPanel g : panels) for (ImageView v : g.layer) { android.graphics.drawable.Drawable d = v.getDrawable(); if (d instanceof android.graphics.drawable.BitmapDrawable && ((android.graphics.drawable.BitmapDrawable) d).getBitmap() == b) return true; }
        for (Bitmap[] gb : glassBmp) if (gb != null) for (Bitmap x : gb) if (x == b) return true;
        return false;
    }

    /* How bright the photo is behind the tab bar, the top-right bar and the dock (0 black … 1 white).
       A bright photo (snow, sky, a white page) made the glass white and its white text unreadable. */
    private float[] brightness(Bitmap b) {
        float[] out = { 0, 0, 0 };
        try {
            Bitmap s = Bitmap.createScaledBitmap(b, 64, 36, true);
            // where each bar sits, as fractions of the screen (the photo is 6% larger, centred)
            float[][] boxes = {
                { .30f, .03f, .70f, .12f },                                  // tabs
                { .62f, .03f, .97f, .12f },                                  // top-right bar
                { .03f, DOCK_PANEL_Y / SH, .97f, (DOCK_PANEL_Y + DOCK_PANEL_H) / SH } };   // dock
            for (int k = 0; k < 3; k++) {
                double sum = 0; int n = 0;
                for (int y = 0; y < 36; y++) for (int x = 0; x < 64; x++) {
                    float fx = ((x + .5f) / 64f * 1.06f) - .03f, fy = ((y + .5f) / 36f * 1.06f) - .03f;
                    if (fx < boxes[k][0] || fx > boxes[k][2] || fy < boxes[k][1] || fy > boxes[k][3]) continue;
                    int c = s.getPixel(x, y);
                    sum += (.2126 * ((c >> 16) & 255) + .7152 * ((c >> 8) & 255) + .0722 * (c & 255)) / 255.0; n++;
                }
                out[k] = n == 0 ? 0 : (float) (sum / n);
            }
            if (s != b) s.recycle();
        } catch (Throwable ignored) { }
        return out;
    }
    /** darker glass over bright photos (only as much as it needs), the usual light glass otherwise */
    private void adaptGlass(float[] lum) {
        GlassPanel[] ps = side ? new GlassPanel[] { null, null, dock } : new GlassPanel[] { tabs, topbar, dock };
        for (int k = 0; k < 3; k++) {
            final GlassPanel g = ps[k];
            if (g == null) continue;
            int base = (Integer) g.tag;
            float l = lum[k];
            int want = base;
            if (l > .48f) {
                float a = Math.min(.62f, .18f + (l - .48f) * 1.5f);
                want = (Math.round(a * 255) << 24) | 0x0A0A0E;
            }
            final int from = g.tintNow(), to = want;
            if (from == to) continue;
            ValueAnimator va = ValueAnimator.ofObject(new android.animation.ArgbEvaluator(), from, to);
            va.setDuration(1600);
            va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { @Override public void onAnimationUpdate(ValueAnimator a) { g.setTintColor((Integer) a.getAnimatedValue()); } });
            va.start();
        }
    }
    private final float[][] kb = new float[2][2];
    private final long[] kbStart = new long[2], kbHeld = new long[2];
    private long kbLastTick;
    /* The slow zoom: the photo grows 9% and drifts a little over the time it's shown, and its glass
       copies follow. It moves a fraction of a pixel per frame, so it's stepped four times a second
       (indistinguishable at this speed) instead of redrawing the whole screen every frame, and it
       holds still while you're pressing buttons so all of the graphics chip goes to your navigation. */
    private void startKenBurns(int which, boolean fresh) {
        if (fresh) {
            kb[which][0] = rnd.nextFloat() * 3 - 1.5f; kb[which][1] = rnd.nextFloat() * 2 - 1f;
            kbStart[which] = SystemClock.uptimeMillis(); kbHeld[which] = 0;
        }
        applyKenBurns(which);
        main.removeCallbacks(kbTicker);
        main.postDelayed(kbTicker, 250);
    }
    private final Runnable kbTicker = new Runnable() { @Override public void run() {
        if (!built || owner.equals("web")) return;
        if (onVod()) { main.postDelayed(this, 1000); return; }
        long now = SystemClock.uptimeMillis();
        long dt = kbLastTick == 0 ? 0 : Math.min(500, now - kbLastTick);
        kbLastTick = now;
        boolean hold = now - lastKey < 1500 || deep || !motion();
        if (hold) for (int i = 0; i < 2; i++) kbHeld[i] += dt;
        else { applyKenBurns(layer); if (photo[layer ^ 1].getAlpha() > 0) applyKenBurns(layer ^ 1); }
        main.postDelayed(this, 250);
    } };
    private void applyKenBurns(int which) {
        float p = 0;
        if (motion()) {
            long dur = (every() + 6) * 1000L;
            p = Math.max(0, Math.min(1, (SystemClock.uptimeMillis() - kbStart[which] - kbHeld[which]) / (float) dur));
        }
        float sc = 1 + .09f * p;
        float tx = kb[which][0] / 100f * W * 1.06f * sc * p, ty = kb[which][1] / 100f * H * 1.06f * sc * p;
        setKb(photo[which], sc, tx, ty);
        if (tab.equals("home")) for (GlassPanel g : panels) setKb(g.layer[which], sc, tx, ty);
    }
    private static void setKb(View v, float sc, float tx, float ty) {
        if (v.getScaleX() == sc && v.getTranslationX() == tx && v.getTranslationY() == ty) return;
        v.setScaleX(sc); v.setScaleY(sc); v.setTranslationX(tx); v.setTranslationY(ty);
    }

    private void restartPhoto() { if (photo[layer].getDrawable() != null) startKenBurns(layer, false); scheduleSlide(); }

    private void scheduleSlide() {
        if (slideTimer != null) main.removeCallbacks(slideTimer);
        if (paused || photos.length() == 0 || owner.equals("web") || onVod()) return;
        // (Featured is Home: the photos only change in photo mode and the screensaver, not hidden behind it)
        if (billboard != null && !mode.equals("shelf") && !saver) return;
        slideTimer = new Runnable() { @Override public void run() { showPhoto(1); } };
        main.postDelayed(slideTimer, every() * 1000L);
    }

    private void setCaption(final JSONObject p) {
        caption.animate().translationY(px(.6f)).alpha(mode.equals("shelf") ? 0f : 0f).setDuration(300).withEndAction(new Runnable() { @Override public void run() {
            boolean other = !p.optString("src").isEmpty();
            capSub.setText(other ? p.optString("src") : "r/" + p.optString("sub", sub()));
            capTitle.setText(cleanTitle(p.optString("title")));
            String by = p.optString("author");
            capBy.setText(by.isEmpty() ? "" : other ? "by " + by : "u/" + by);
            if (gCapT != null) { gCapT.setText(capTitle.getText()); gCapB.setText(by.isEmpty() ? capSub.getText() : capSub.getText() + "  ·  " + capBy.getText()); }
            caption.animate().translationY(0).alpha(mode.equals("shelf") ? 1f : 0f).setDuration(500).setInterpolator(TileView.EASE).start();
        } }).start();
    }
    static String cleanTitle(String t) {
        if (t == null) return "";
        return t.replaceAll("\\[[^\\]]*\\]", "").replaceAll("(?i)\\((?:oc|[^)]*\\d{3,5}\\s*[x×]\\s*\\d{3,5}[^)]*)\\)", "")
                .replaceAll("\\b\\d{3,5}\\s*[x×]\\s*\\d{3,5}\\b", "").replaceAll("\\bOC\\b", "").replaceAll("\\s+([,.!?])", "$1")
                .replaceAll("[\\s,;:|-]+$", "").replaceAll("\\s{2,}", " ").trim();
    }

    private void applyGlassMode() {
        String g = glassMode();
        for (GlassPanel p : panels) {
            boolean solid = g.equals("solid");
            p.back.setVisibility(solid ? GONE : VISIBLE);
            p.tint.setBackgroundColor(solid ? 0xF01E1E22 : (Integer) p.tag);
            p.setLiquid(g.equals("liquid") && p.liquidAllowed);
            Bitmap[] cur = glassBmp[layer];
            if (cur != null) for (int i = 0; i < 2; i++) if (glassBmp[i] != null) p.layer[i].setImageBitmap(g.equals("liquid") && p.liquidAllowed ? glassBmp[i][0] : glassBmp[i][1]);
            p.refreshTint();
            p.applyLook();
        }
        refreshHole();
    }

    // ================================================================== clock

    private final Runnable ticker = new Runnable() { @Override public void run() { tick(); } };
    // ================================================================== Home styles: gallery, billboard

    private void buildGallery(Context c) {
        gallery = new LinearLayout(c);
        gallery.setOrientation(LinearLayout.VERTICAL);
        gTime = text(fReg, 8.2f, Color.WHITE); gTime.setLetterSpacing(-.035f); gTime.setFontFeatureSettings("tnum");
        gDate = text(fMed, 1.9f, 0xF0FFFFFF);
        gNext = text(fSemi, 1.75f, Color.WHITE);
        gNext.setSingleLine(true); gNext.setEllipsize(android.text.TextUtils.TruncateAt.END); gNext.setMaxWidth(px(60));
        for (TextView t : new TextView[] { gTime, gDate, gNext }) t.setShadowLayer(1.2f * R, 0, .12f * R, 0x8C000000);
        gallery.addView(gTime);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); dl.topMargin = px(.2f);
        gallery.addView(gDate, dl);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); nl.topMargin = px(1.1f);
        gallery.addView(gNext, nl);
        gNext.setVisibility(GONE);
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT);
        gl.leftMargin = px(6); gl.bottomMargin = px(5.2f);
        addView(gallery, gl);
        // the photo's own label, quietly in the other corner
        galleryCap = new LinearLayout(c);
        galleryCap.setOrientation(LinearLayout.VERTICAL);
        galleryCap.setGravity(Gravity.RIGHT);
        gCapT = text(fSemi, 1.45f, Color.WHITE); gCapB = text(fReg, 1.25f, 0xE6FFFFFF);
        for (TextView t : new TextView[] { gCapT, gCapB }) { t.setShadowLayer(R, 0, .1f * R, 0x8C000000); t.setGravity(Gravity.RIGHT); t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.END); t.setMaxWidth(px(44)); }
        galleryCap.addView(gCapT);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); bl.topMargin = px(.3f);
        galleryCap.addView(gCapB, bl);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT);
        cl.rightMargin = px(6); cl.bottomMargin = px(5.4f);
        addView(galleryCap, cl);
    }
    /** the gallery's one line: your team's game (live, today, next), else a new episode, else what you're watching; OK acts on it */
    private void galleryNext() {
        String line = null; gAct = null;
        try {
            if (sportsData != null) {
                SportsData.Ev best = null;
                for (SportsData.Ev e : sportsData.featuredGames(8)) if (sportsData.isMine(e)) { best = e; break; }
                if (best != null) {
                    final SportsData.Ev e = best;
                    String[] g = sportsData.nextMine();
                    line = g != null ? (g[0].startsWith("LIVE") ? "● " : "") + g[1] + "  ·  " + g[2] : null;
                    gAct = new Runnable() { @Override public void run() { sportsData.menuFor(e); } };
                }
            }
            if (line == null) {
                List<JSONObject> up = Upcoming.cached();
                long now = System.currentTimeMillis();
                for (final JSONObject m : up) if (m.optLong("calAt") <= now) {
                    line = "New episode: " + m.optString("name") + "  ·  " + m.optString("calLabel");
                    gAct = new Runnable() { @Override public void run() { try { JSONObject t = new JSONObject(m.toString()); t.put("video", m.optString("calVideo")); openTitle(t); } catch (Exception ignored) { } } };
                    break;
                }
            }
            if (line == null) {
                List<JSONObject> cw = Progress.continueList(getContext(), null, 1);
                if (!cw.isEmpty()) {
                    final JSONObject m = cw.get(0);
                    line = "Continue " + m.optString("name") + (m.optString("cwLabel").isEmpty() ? "" : "  ·  " + m.optString("cwLabel"));
                    gAct = new Runnable() { @Override public void run() { openTitle(m); } };
                }
            }
        } catch (Throwable ignored) { }
        nextLine = line;
        if (bcNext != null) { bcNext.setText(line == null ? "" : line); bcNext.setVisibility(line == null ? GONE : VISIBLE); }
        if (gNext == null) return;
        gNext.setText(line == null ? "" : line + "   ›");
        gNext.setVisibility(line == null ? GONE : VISIBLE);
    }
    /** which of Home's own things show: the billboard or the gallery's label (only on Home, not over All apps, photo mode or the screensaver) */
    private void syncHomeStyle(boolean animate) {
        boolean on = built && tab.equals("home") && !deep && !mode.equals("shelf") && !saver;
        if (billboard != null) {
            /* Featured is Home's picture: it stays behind All apps too (dimmed, held still), so the photo
               slideshow never shows through, and it has its own dark ground while its first picture loads */
            boolean vis = built && tab.equals("home") && !mode.equals("shelf") && !saver;
            if (vis) { if (billboard.getVisibility() != VISIBLE) { billboard.setAlpha(0f); billboard.setVisibility(VISIBLE); } billboard.animate().alpha(deep ? .35f : 1f).setDuration(animate ? 400 : 0).start(); }
            else { billboard.animate().cancel(); billboard.setAlpha(0f); billboard.setVisibility(INVISIBLE); }
            billboard.setShown(vis);
            applyCover();
        }
        updateRail();
        if (gallery != null) {
            gallery.animate().alpha(on ? 1f : 0f).setDuration(animate ? 400 : 0).start();
            galleryCap.animate().alpha(on ? 1f : 0f).setDuration(animate ? 400 : 0).start();
            if (on) galleryNext();
        }
    }
    /* While the billboard's picture fills the screen, the photo and its shade under it aren't drawn at all
       (they cost a quarter of each frame for nothing: Home ran at 50 frames a second instead of 60). */
    private boolean bbCover;
    void billboardCover(boolean c) { bbCover = c; applyCover(); }
    private void applyCover() {
        if (!built || !tab.equals("home")) return;
        boolean hide = billboard != null && billboard.getVisibility() == VISIBLE && !mode.equals("shelf") && !saver;
        int v = hide ? INVISIBLE : VISIBLE;
        if (photoBox.getVisibility() != v) photoBox.setVisibility(v);
        if (scrim.getVisibility() != v) scrim.setVisibility(v);
    }
    /** the billboard may move on (nothing covers it, nothing playing, you're on Home's own keys) */
    boolean billboardFree() {
        // (it keeps turning while you're up in the top bar or the side menu: only another tab, or something over it, stops it)
        return MainActivity.inFront && (mode.equals("grid") || mode.equals("top")) && rest && tab.equals("home") && !titleOpenNow() && !vodPlayingNow() && !gameOpenNow() && !saver;
    }

    private int shelfLit;
    private void tick() {
        if (clock == null) return;
        // Backlit Shelf: the light turns warm in the evening and cool in the day
        if (GlassPanel.look.equals("shelf") && shelfLit != GlassPanel.shelfLight()) { shelfLit = GlassPanel.shelfLight(); for (GlassPanel g : panels) g.applyLook(); }
        String mode12 = S.optString("clock", "12");
        Date d = new Date();
        String t = new SimpleDateFormat(mode12.equals("24") ? "HH:mm" : "h:mm", Locale.getDefault()).format(d);
        clock.setText(t);
        clock.setVisibility(mode12.equals("off") ? GONE : VISIBLE);
        if (wTime != null) {
            wTime.setText(t);
            wDate.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(d));
            wClock.setVisibility(mode12.equals("off") ? GONE : VISIBLE);
            refreshNext();
        }
        if (billboard != null) billboard.tick(mode12.equals("off") ? "" : t, wxLine);
        if (gTime != null) {
            gTime.setText(t); gTime.setVisibility(mode12.equals("off") ? GONE : VISIBLE);
            String dd = new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(d);
            gDate.setText(wxLine.isEmpty() ? dd : dd + "  ·  " + wxLine);
            galleryNext();
        }
        if (bcTime != null) {
            bcTime.setText(t);
            String dd = new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(d);
            bcDate.setText(wxLine.isEmpty() ? dd : dd + "  ·  " + wxLine);
            if (saver || gTime == null) galleryNext();           // (the screensaver's line: your game, a new episode…)
        }
        // screensaver
        int sv = S.has("saver") ? S.optInt("saver", 10) : 10;
        if (sv > 0 && !saver && !MainActivity.videoRecently() && SystemClock.uptimeMillis() - lastKey > sv * 60000L) startSaver();
        main.removeCallbacks(ticker);
        main.postDelayed(ticker, 60000 - System.currentTimeMillis() % 60000 + 50);
        if (sv > 0) main.postDelayed(new Runnable() { @Override public void run() { tickSaverOnly(); } }, 10000);
    }
    private void tickSaverOnly() {
        int sv = S.has("saver") ? S.optInt("saver", 10) : 10;
        if (sv > 0 && !saver && !MainActivity.videoRecently() && SystemClock.uptimeMillis() - lastKey > sv * 60000L) startSaver();
    }

    // ================================================================== Home's widgets

    private LinearLayout wClock, wWx, wNext, wHours;
    private TextView wTime, wDate, wTemp, wCond, wHiLo, wNextA, wNextA2, wNextB, wNextB2, wLabA, wLabB;
    private LinearLayout card(float wRem) {
        LinearLayout k = new LinearLayout(getContext());
        k.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(2f * R); g.setColor(0x8C14161E); g.setStroke(Math.max(1, px(.07f)), 0x1FFFFFFF);
        k.setBackground(g);
        k.setPadding(px(2f), px(1.6f), px(2f), px(1.7f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(wRem > 0 ? px(wRem) : LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
        if (widgets.getChildCount() > 0) { if (portrait) lp.topMargin = px(1.2f); else lp.leftMargin = px(1.4f); }
        if (portrait) lp.width = px(SW - 6);
        widgets.addView(k, lp);
        return k;
    }
    private TextView wText(LinearLayout into, Typeface tf, float size, int color, float topRem) {
        TextView t = text(tf, size, color);
        t.setShadowLayer(.5f * R, 0, .08f * R, 0x66000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); lp.topMargin = px(topRem);
        into.addView(t, lp);
        return t;
    }
    private void buildWidgets() {
        widgets.removeAllViews();
        // the time
        wClock = card(0);
        wClock.setMinimumWidth(px(20));
        wTime = wText(wClock, fSemi, 5.6f, Color.WHITE, 0); wTime.setLetterSpacing(-.03f); wTime.setFontFeatureSettings("tnum");
        wDate = wText(wClock, fMed, 1.45f, 0xE6FFFFFF, .5f);
        // the weather: now, today's high and low, and the next five hours
        wWx = card(27);
        LinearLayout top = new LinearLayout(getContext()); top.setGravity(Gravity.CENTER_VERTICAL);
        wWx.addView(top, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        wTemp = text(fSemi, 3.8f, Color.WHITE); top.addView(wTemp);
        View gap = new View(getContext()); top.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
        LinearLayout rc = new LinearLayout(getContext()); rc.setOrientation(LinearLayout.VERTICAL); rc.setGravity(Gravity.RIGHT);
        wCond = text(fMed, 1.3f, 0xF2FFFFFF); wCond.setGravity(Gravity.RIGHT); rc.addView(wCond);
        wHiLo = text(fMed, 1.2f, 0xD9FFFFFF); wHiLo.setGravity(Gravity.RIGHT); rc.addView(wHiLo);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); rl.leftMargin = px(1.6f);
        top.addView(rc, rl);
        wHours = new LinearLayout(getContext());
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); hl.topMargin = px(1.1f);
        wWx.addView(wHours, hl);
        wWx.setVisibility(GONE);
        // what's on next: your team's game, a new episode of a show you watch
        wNext = card(27);
        wLabA = wText(wNext, fSemi, 1f, 0xFFFFC23D, 0); wLabA.setLetterSpacing(.1f);
        wNextA = wText(wNext, fSemi, 1.6f, Color.WHITE, .5f);
        wNextA2 = wText(wNext, fMed, 1.2f, 0xE0FFFFFF, .3f);
        wLabB = wText(wNext, fSemi, 1f, 0xFF9FD3FF, 1.2f); wLabB.setLetterSpacing(.1f);
        wNextB = wText(wNext, fSemi, 1.45f, Color.WHITE, .5f);
        wNextB2 = wText(wNext, fMed, 1.2f, 0xE0FFFFFF, .3f);
        for (TextView t : new TextView[] { wNextA, wNextA2, wNextB, wNextB2 }) { t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.END); t.setMaxWidth(px(portrait ? SW - 10 : 23)); }
        wNext.setVisibility(GONE);
        main.removeCallbacks(wxLoad); main.postDelayed(wxLoad, 1500);
    }
    /** your team's next (or live) game, and the next new episode */
    /** Settings › Home widgets: all of them, just the time, or none */
    private void applyWidgetPref() {
        if (widgets == null) return;
        if (!homeStyle.equals("classic")) { widgets.setVisibility(GONE); return; }
        String w = S.optString("widgets", "all");
        widgets.setVisibility(w.equals("off") ? GONE : VISIBLE);
        boolean all = w.equals("all");
        if (!all) { wWx.setVisibility(GONE); wNext.setVisibility(GONE); }
        else if (wTemp.getText().length() > 0 && !Double.isNaN(S.optDouble("wxLat", Double.NaN))) wWx.setVisibility(VISIBLE);
    }
    private void refreshNext() {
        if (wNext == null) return;
        if (!"all".equals(S.optString("widgets", "all"))) { applyWidgetPref(); return; }
        String[] g = null;
        try { if (sportsData != null) g = sportsData.nextMine(); } catch (Throwable ignored) { }
        JSONObject ep = null;
        try { List<JSONObject> up = Upcoming.cached(); if (!up.isEmpty()) ep = up.get(0); } catch (Throwable ignored) { }
        boolean a = g != null, b = ep != null;
        wLabA.setVisibility(a ? VISIBLE : GONE); wNextA.setVisibility(a ? VISIBLE : GONE); wNextA2.setVisibility(a ? VISIBLE : GONE);
        if (a) { wLabA.setText(g[0]); wLabA.setTextColor(g[0].startsWith("LIVE") ? 0xFFFF7A59 : 0xFFFFC23D); wNextA.setText(g[1]); wNextA2.setText(g[2]); }
        wLabB.setVisibility(b ? VISIBLE : GONE); wNextB.setVisibility(b ? VISIBLE : GONE); wNextB2.setVisibility(b ? VISIBLE : GONE);
        LinearLayout.LayoutParams lb = (LinearLayout.LayoutParams) wLabB.getLayoutParams(); lb.topMargin = a ? px(1.2f) : 0; wLabB.setLayoutParams(lb);
        if (b) {
            String lab = ep.optString("calLabel");
            wLabB.setText(lab.startsWith("New") || lab.startsWith("Today") ? "NEW EPISODE" : "COMING UP");
            wNextB.setText(ep.optString("name")); wNextB2.setText(lab);
        }
        wNext.setVisibility(a || b ? VISIBLE : GONE);
    }
    /* the weather (open-meteo.com, as the page's was): where Settings › Weather says, every 20 minutes */
    private final Runnable wxLoad = new Runnable() { @Override public void run() {
        main.removeCallbacks(this); main.postDelayed(this, 20 * 60000);
        final double lat = S.optDouble("wxLat", Double.NaN), lon = S.optDouble("wxLon", Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lon)) { if (wWx != null) wWx.setVisibility(GONE); return; }
        final boolean c = "c".equals(S.optString("wxUnit"));
        new Thread(new Runnable() { @Override public void run() {
            try {
                byte[] d = HomeImages.http("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,weather_code,is_day&hourly=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min"
                        + "&temperature_unit=" + (c ? "celsius" : "fahrenheit") + "&timezone=auto&forecast_days=2", null);
                if (d == null) return;
                final JSONObject j = new JSONObject(new String(d, "UTF-8"));
                main.post(new Runnable() { @Override public void run() { showWeather(j); } });
            } catch (Throwable ignored) { }
        } }, "weather").start();
    } };
    private static String wxLabel(int code, boolean day) {
        if (code == 0) return day ? "Clear" : "Clear night";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "Rain";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "Snow";
        if (code >= 95) return "Thunderstorms";
        return "Cloudy";
    }
    private void showWeather(JSONObject j) {
        if (wWx == null) return;
        JSONObject cur = j.optJSONObject("current"), daily = j.optJSONObject("daily"), hourly = j.optJSONObject("hourly");
        if (cur == null) return;
        wTemp.setText(Math.round(cur.optDouble("temperature_2m")) + "°");
        wxLine = Math.round(cur.optDouble("temperature_2m")) + "°  " + wxLabel(cur.optInt("weather_code"), cur.optInt("is_day", 1) == 1).toLowerCase(Locale.US);
        tick();
        wCond.setText(wxLabel(cur.optInt("weather_code"), cur.optInt("is_day", 1) == 1));
        JSONArray hi = daily == null ? null : daily.optJSONArray("temperature_2m_max"), lo = daily == null ? null : daily.optJSONArray("temperature_2m_min");
        wHiLo.setText(hi != null && lo != null ? "H " + Math.round(hi.optDouble(0)) + "°  ·  L " + Math.round(lo.optDouble(0)) + "°" : "");
        wHours.removeAllViews();
        JSONArray ht = hourly == null ? null : hourly.optJSONArray("time"), hv = hourly == null ? null : hourly.optJSONArray("temperature_2m");
        String now = cur.optString("time");          // (local time, "2026-10-30T21:15")
        if (ht != null && hv != null) {
            int start = 0;
            for (int i = 0; i < ht.length(); i++) if (ht.optString(i).compareTo(now) > 0) { start = i; break; }
            for (int i = start; i < Math.min(ht.length(), start + 5); i++) {
                String hh = ht.optString(i); int h24 = 0;
                try { h24 = Integer.parseInt(hh.substring(11, 13)); } catch (Exception ignored) { }
                String lab = "24".equals(S.optString("clock")) ? String.format(Locale.US, "%02d:00", h24) : (h24 % 12 == 0 ? 12 : h24 % 12) + (h24 < 12 ? " AM" : " PM");
                LinearLayout col = new LinearLayout(getContext()); col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER_HORIZONTAL);
                TextView a = text(fMed, 1.05f, 0xD9FFFFFF); a.setText(lab); col.addView(a);
                TextView b = text(fSemi, 1.35f, Color.WHITE); b.setText(Math.round(hv.optDouble(i)) + "°");
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); bl.topMargin = px(.35f);
                col.addView(b, bl);
                wHours.addView(col, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            }
        }
        wWx.setVisibility(VISIBLE);
        applyWidgetPref();
    }

    void setWeather(String s) {
        if (wx == null) return;
        wx.setText(s == null ? "" : s);
        wx.setVisibility(s == null || s.isEmpty() ? GONE : VISIBLE);
    }

    void setLink(boolean on) {
        if (on == linkOn || !built) { linkOn = on; return; }
        linkOn = on;
        buildTopRow();
        drawTop();
    }

    // ================================================================== from outside

    /** native: Home on screen and in charge; overlay: the page shows a panel over Home; web: another tab */
    void setOwner(String o) {
        String was = owner;
        owner = o;
        if (!built) { setVisibility(o.equals("web") ? INVISIBLE : VISIBLE); return; }
        if (!o.equals("native")) { if (vodM != null) vodM.pause(); if (vodS != null) vodS.pause(); }
        if (live != null) live.setCovered(!o.equals("native"));
        if (o.equals("web")) {
            if (slideTimer != null) main.removeCallbacks(slideTimer);
            setVisibility(INVISIBLE);
        } else {
            setVisibility(VISIBLE);
            if (was.equals("web")) { if (!onVod()) restartPhoto(); if (mode.equals("top")) exitTopQuiet(); }
            if (o.equals("native")) { requestFocus(); lastKey = SystemClock.uptimeMillis(); }
        }
    }
    /** for the report: what's on screen */
    JSONObject screenState() {
        JSONObject o = new JSONObject();
        try {
            o.put("tab", tab).put("mode", mode).put("owner", owner).put("visible", getVisibility() == VISIBLE).put("alpha", getAlpha())
             .put("title", titleOpenNow()).put("vodPlaying", vodPlayingNow());
            if (vodPlayer != null) o.put("player", vodPlayer.state());
            JSONArray shown = new JSONArray();
            for (int i = 0; i < getChildCount(); i++) { View v = getChildAt(i); if (v.getVisibility() == VISIBLE && v.getAlpha() > 0.01f) shown.put(v.getClass().getSimpleName() + (v.getWidth() < getWidth() / 2 ? "(small)" : "")); }
            o.put("shownViews", shown);
        } catch (Exception ignored) { }
        return o;
    }
    boolean ownsKeys() { return owner.equals("native") && getVisibility() == VISIBLE; }

    static boolean systemKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE: case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_POWER: case KeyEvent.KEYCODE_SLEEP: case KeyEvent.KEYCODE_WAKEUP:
            case KeyEvent.KEYCODE_TV_POWER: case KeyEvent.KEYCODE_TV_INPUT: case KeyEvent.KEYCODE_ASSIST: case KeyEvent.KEYCODE_VOICE_ASSIST:
            case KeyEvent.KEYCODE_ALL_APPS: case KeyEvent.KEYCODE_APP_SWITCH: case KeyEvent.KEYCODE_BRIGHTNESS_UP: case KeyEvent.KEYCODE_BRIGHTNESS_DOWN:
                return true;
            default: return false;
        }
    }
    void goHome() {
        if (!built) return;
        act.snapHomeSoon();
        hideLaunch();
        // Home on the remote leaves a movie, a title's page and Addons too (where you got to is saved)
        if (vodPlayingNow()) try { vodPlayer.close(); } catch (Throwable ignored) { }
        if (titleOpenNow()) try { titleView.close(); } catch (Throwable ignored) { }
        if (addonsOpenNow()) try { addonsView.close(); } catch (Throwable ignored) { }
        if (dlg != null && dlg.shownNow()) { dlg.hide(); page("window.nhDlgPick && nhDlgPick(-1)"); }
        if (find != null && find.shownNow()) { uncover(); find.hide(); if (nativeSearchOn()) search.active = false; else page("window.nhFindClose && nhFindClose()"); }
        if (sheet != null && sheet.shownNow()) { sheet.hide(); page("closeSettings()"); }
        if (mode.equals("menu")) closeMenu();
        if (mode.equals("move")) endMove();
        if (mode.equals("shelf")) exitShelf();
        mode = "grid";
        rest = true;
        showTab("home");
        focus = 0;
        drawTop();
        applyFocus(true, ' ');
    }

    void reload(String what) {
        if (!built) return;
        String oldSub = sub(), oldSort = sort(), oldGlass = glassMode();
        boolean oldNoDock = noDock();
        boolean oldMotion = motion();
        String oldTmdb = S.optString("tmdbKey", ""), oldLook = S.optString("vodLook", "shelves");
        String oldWx = S.optString("wxLat") + S.optString("wxLon") + S.optString("wxUnit");
        readSettings();
        if (!oldWx.equals(S.optString("wxLat") + S.optString("wxLon") + S.optString("wxUnit"))) main.post(wxLoad);
        applyWidgetPref(); refreshNext();
        // Settings › Home › Menu changed: Home is made again in the other style
        if (side != !"top".equals(S.optString("nav", "side")) || (!side && !portrait && !barStyle.equals(barStylePref(S))) || !homeStyle.equals(homeStylePref(S)) || (side && !menuStyle.equals(menuStylePref(S))) || !GlassPanel.look.equals(lookPref(S))) { main.postDelayed(new Runnable() { @Override public void run() { act.recreate(); } }, 700); return; }
        if (!oldLook.equals(S.optString("vodLook", "shelves"))) { if (vodM != null) vodM.lookChanged(); if (vodS != null) vodS.lookChanged(); }
        // a TMDB key added or removed: Movies & TV Shows make their rows again (TMDB's rows come or go)
        if (!oldTmdb.equals(S.optString("tmdbKey", ""))) { if (vodM != null) vodM.addonsChanged(); if (vodS != null) vodS.addonsChanged(); }
        if (what.equals("apps") || what.equals("all")) { loadApps(); applyFocus(false, ' '); }
        else if (what.equals("icons")) renderTiles();
        if (!oldGlass.equals(glassMode())) applyGlassMode();
        if (oldNoDock != noDock()) { rest = true; focus = 0; renderTiles(); deep = false; applyFocus(false, ' '); applyMode(true); }
        if (!oldSub.equals(sub()) || !oldSort.equals(sort()) || what.equals("photos")) { photosRaw = ""; photos = new JSONArray(); loadPhotos(true); }
        if (oldMotion != motion()) startKenBurns(layer, false);
        tick();
        scheduleSlide();
        updateTshelf(true);
    }

    String photoStatus() { return photoStatus; }

    // ---- toasts while the page is hidden
    private TextView toastView;
    private final Runnable toastHide = new Runnable() { @Override public void run() { if (toastView != null) toastView.animate().alpha(0f).translationY(px(1)).setDuration(300).start(); } };
    /** what the TV test is doing, top left (the page's own label isn't drawn while Home is native) */
    private TextView hud;
    void setHud(String t) {
        if (!built) return;
        if (t == null || t.isEmpty()) { if (hud != null) hud.setVisibility(GONE); return; }
        if (hud == null) {
            hud = text(fMed, 1.05f, Color.WHITE);
            hud.setPadding(px(1), px(.5f), px(1), px(.5f));
            GradientDrawable b = new GradientDrawable(); b.setCornerRadius(R); b.setColor(0xB8000000); hud.setBackground(b);
            addView(hud, at(1.2f, 1.2f, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }
        hud.setText(t);
        hud.setVisibility(VISIBLE);
        hud.bringToFront();
    }

    /** Clear Continue watching (asks first: it's all of it) */
    void clearContinue() {
        final int n = Progress.continueCount(getContext());
        if (n == 0) { toast("Continue watching is already empty"); return; }
        final List<String> l = new ArrayList<>(); final List<Runnable> a = new ArrayList<>();
        l.add("Clear all " + n + (n == 1 ? " title" : " titles")); a.add(new Runnable() { @Override public void run() {
            Progress.hideAll(getContext());
            toast("Continue watching cleared · watching something brings it back");
        } });
        l.add("Keep them"); a.add(new Runnable() { @Override public void run() { } });
        main.postDelayed(new Runnable() { @Override public void run() { showMenu(l, a, null, 1f, "Clear Continue watching?"); } }, 60);
    }
    void toast(String msg) {
        if (!built) return;
        if (toastView == null) {
            toastView = text(fMed, 1.25f, Color.WHITE);
            toastView.setPadding(px(1.8f), px(1f), px(1.8f), px(1f));
            GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(3 * R); bg.setColor(0xE6202024);
            toastView.setBackground(bg);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = px(4);
            addView(toastView, lp);
            toastView.setAlpha(0f);
        }
        toastView.bringToFront();
        toastView.setText(msg);
        toastView.setTranslationY(px(1));
        toastView.animate().alpha(1f).translationY(0).setDuration(300).setInterpolator(TileView.EASE).start();
        main.removeCallbacks(toastHide);
        main.postDelayed(toastHide, 3000);
    }

    /** runs something in the page and lets it take over the screen right away if it opens a panel */
    void page(String code) { act.js(code + ";window.nhSync&&nhSync()"); }

    // ================================================================== frame meter (diagnostic)

    private long[] frames = new long[0];
    private int nFrames;
    private boolean measuring;
    private final Choreographer.FrameCallback fcb = new Choreographer.FrameCallback() {
        @Override public void doFrame(long t) {
            if (!measuring) return;
            if (nFrames < frames.length) frames[nFrames++] = t;
            Choreographer.getInstance().postFrameCallback(this);
        }
    };
    /* Android's own timing of each frame it drew: how long it took in all, and how long the graphics
       chip spent on it (Android 12+). That says whether a slow screen is too much drawing or too much work. */
    private final double[] fmTotal = new double[2000], fmGpu = new double[2000], fmUi = new double[2000], fmSync = new double[2000], fmIssue = new double[2000], fmSwap = new double[2000], fmDelay = new double[2000];
    private int fmN;
    private Object fmListener;
    private Diag.Snap measureSnap;
    void measureStart() {
        frames = new long[2000]; nFrames = 0; measuring = true; fmN = 0;
        measureSnap = Diag.snap();
        /* (one chain of frame callbacks only: a test starting again within a frame of the last one ending left
           the old chain running too, so every frame was counted twice, three times… — the reports' 120, 180,
           240 "frames a second") */
        Choreographer.getInstance().removeFrameCallback(fcb);
        Choreographer.getInstance().postFrameCallback(fcb);
        try {
            android.view.Window.OnFrameMetricsAvailableListener l = new android.view.Window.OnFrameMetricsAvailableListener() {
                @Override public void onFrameMetricsAvailable(android.view.Window w, android.view.FrameMetrics m, int dropped) {
                    if (fmN >= fmTotal.length) return;
                    fmTotal[fmN] = m.getMetric(android.view.FrameMetrics.TOTAL_DURATION) / 1e6;
                    fmGpu[fmN] = android.os.Build.VERSION.SDK_INT >= 31 ? m.getMetric(android.view.FrameMetrics.GPU_DURATION) / 1e6 : -1;
                    // where a frame's time goes: the app's own work, handing it over, and waiting for the screen
                    fmUi[fmN] = (m.getMetric(android.view.FrameMetrics.INPUT_HANDLING_DURATION) + m.getMetric(android.view.FrameMetrics.ANIMATION_DURATION)
                            + m.getMetric(android.view.FrameMetrics.LAYOUT_MEASURE_DURATION) + m.getMetric(android.view.FrameMetrics.DRAW_DURATION)) / 1e6;
                    fmSync[fmN] = m.getMetric(android.view.FrameMetrics.SYNC_DURATION) / 1e6;
                    fmIssue[fmN] = m.getMetric(android.view.FrameMetrics.COMMAND_ISSUE_DURATION) / 1e6;
                    fmSwap[fmN] = m.getMetric(android.view.FrameMetrics.SWAP_BUFFERS_DURATION) / 1e6;
                    fmDelay[fmN] = m.getMetric(android.view.FrameMetrics.UNKNOWN_DELAY_DURATION) / 1e6;
                    fmN++;
                }
            };
            act.getWindow().addOnFrameMetricsAvailableListener(l, main);
            fmListener = l;
        } catch (Throwable ignored) { fmListener = null; }
    }
    private static double r1(double v) { return Math.round(v * 10) / 10.0; }
    private static double pct(double[] a, int n, double q) {
        if (n == 0) return -1;
        double[] c = java.util.Arrays.copyOf(a, n); java.util.Arrays.sort(c);
        return c[Math.min(n - 1, (int) (n * q))];
    }
    String measureStop() {
        measuring = false;
        Choreographer.getInstance().removeFrameCallback(fcb);
        JSONObject o = new JSONObject();
        try {
            if (nFrames < 3) return "{}";
            double[] d = new double[nFrames - 1];
            for (int i = 1; i < nFrames; i++) d[i - 1] = (frames[i] - frames[i - 1]) / 1e6;
            double span = (frames[nFrames - 1] - frames[0]) / 1e6;
            java.util.Arrays.sort(d);
            int over50 = 0; for (double x : d) if (x > 50) over50++;
            o.put("fps", Math.round(d.length * 1000 / span));
            o.put("p50", Math.round(d[d.length / 2]));
            o.put("p95", Math.round(d[(int) (d.length * .95)]));
            o.put("worst", Math.round(d[d.length - 1]));
            o.put("over50", over50);
        } catch (Exception ignored) { }
        try {
            if (fmListener != null) act.getWindow().removeOnFrameMetricsAvailableListener((android.view.Window.OnFrameMetricsAvailableListener) fmListener);
            fmListener = null;
            int n = fmN;
            o.put("drawn", n);
            try { o.put("hz", Math.round(act.getWindowManager().getDefaultDisplay().getRefreshRate() * 10) / 10.0); } catch (Throwable ignored) { }
            o.put("cpu50", Math.round(pct(fmTotal, n, .5) * 10) / 10.0);
            o.put("cpu95", Math.round(pct(fmTotal, n, .95) * 10) / 10.0);
            if (n > 0) {
                o.put("ui50", Math.round(pct(fmUi, n, .5) * 10) / 10.0); o.put("sync50", Math.round(pct(fmSync, n, .5) * 10) / 10.0);
                o.put("issue50", Math.round(pct(fmIssue, n, .5) * 10) / 10.0); o.put("swap50", Math.round(pct(fmSwap, n, .5) * 10) / 10.0);
                o.put("delay50", Math.round(pct(fmDelay, n, .5) * 10) / 10.0);
                o.put("videoPlane", act.nativePlayerPlane());
            }
            if (n > 0 && fmGpu[0] >= 0) { o.put("gpu50", Math.round(pct(fmGpu, n, .5) * 10) / 10.0); o.put("gpu95", Math.round(pct(fmGpu, n, .95) * 10) / 10.0); }
            if (n > 0) {
                // the slow ends of each part, and the worst few frames taken apart
                o.put("ui95", r1(pct(fmUi, n, .95))); o.put("issue95", r1(pct(fmIssue, n, .95)));
                o.put("swap95", r1(pct(fmSwap, n, .95))); o.put("delay95", r1(pct(fmDelay, n, .95)));
                /* a frame's own work, without the wait before it started: on a screen that sits still,
                   the first frame after a pause "waits" for hundreds of ms, which made cpu95 look slow
                   when nothing was (Sports and Settings read ~200 ms with 2–14 frames drawn) */
                double[] work = new double[n]; int waited = 0;
                for (int i = 0; i < n; i++) { work[i] = Math.max(0, fmTotal[i] - fmDelay[i]); if (fmDelay[i] > 100) waited++; }
                o.put("work50", r1(pct(work, n, .5))); o.put("work95", r1(pct(work, n, .95)));
                o.put("framesAfterPause", waited);
                int over33 = 0; for (int i = 0; i < n; i++) if (fmTotal[i] > 33.4) over33++;
                o.put("framesOver33ms", over33);
                Integer[] idx = new Integer[n]; for (int i = 0; i < n; i++) idx[i] = i;
                java.util.Arrays.sort(idx, new java.util.Comparator<Integer>() { @Override public int compare(Integer a, Integer b) { return Double.compare(fmTotal[b], fmTotal[a]); } });
                JSONArray worst = new JSONArray();
                for (int k = 0; k < Math.min(3, n); k++) {
                    int i = idx[k];
                    worst.put(new JSONObject().put("frame", i).put("total", r1(fmTotal[i])).put("ui", r1(fmUi[i])).put("sync", r1(fmSync[i]))
                            .put("issue", r1(fmIssue[i])).put("swap", r1(fmSwap[i])).put("gpu", r1(fmGpu[i])).put("delay", r1(fmDelay[i])));
                }
                o.put("worstFrames", worst);
            }
            if (measureSnap != null) Diag.delta(measureSnap, Diag.snap(), o);
            JSONObject sys = Diag.system(act);
            o.put("graphicsMB", sys.opt("graphicsMB")); o.put("appMB", sys.opt("appMB")); o.put("tvFreeMB", sys.opt("tvFreeMB")); o.put("heat", sys.opt("heat"));
            o.put("memoryMB", sys.opt("memoryMB")); o.put("pictureCache", imgs.cacheInfo());
            o.put("test", testFlags);
        } catch (Throwable ignored) { }
        return o.toString();
    }

    /* The diagnostic's Home test, run here step by step, because the page is asleep while Home shows:
       k<code> press a key · w<ms> wait · m+ start measuring · m-<label> stop and keep the result ·
       t<flags> comparison switches · s<name> screenshot for the report. The results go back to the page. */
    void runScript(final String script, final String id) {
        final String[] st = script.split(";");
        final JSONArray results = new JSONArray();
        final int[] i = { 0 };
        main.post(new Runnable() { @Override public void run() {
            final Runnable self = this;
            long wait = 0;
            try {
                while (i[0] < st.length && wait == 0) {
                    String x = st[i[0]++].trim();
                    if (x.isEmpty()) continue;
                    char c = x.charAt(0);
                    String arg = x.substring(1);
                    if (c == 'k') injectKey(Integer.parseInt(arg));
                    else if (c == 'w') wait = Long.parseLong(arg);
                    else if (c == 't') setTest(arg);
                    else if (c == 'v') showTab(arg);
                    else if (c == 'j') act.js(arg + ";window.nhSync&&nhSync()");
                    else if (c == 'h') setHud("Diagnostic · " + arg);
                    else if (c == 'p') results.put(new JSONObject().put("label", "Live TV player (native)").put("info", live == null ? "not opened" : live.playerDiag()));
                    else if (c == 's') bridge.nhShot(arg);
                    else if (c == 'm' && arg.startsWith("+")) measureStart();
                    else if (c == 'm' && arg.startsWith("-")) { JSONObject r = new JSONObject(measureStop()); r.put("label", arg.substring(1) + " (native)"); results.put(r); }
                }
            } catch (Throwable t) {
                try { results.put(new JSONObject().put("label", "Home script error").put("error", String.valueOf(t))); } catch (Exception ignored) { }
                i[0] = st.length;
            }
            if (i[0] < st.length || wait > 0) { main.postDelayed(self, Math.max(1, wait)); return; }
            setTest("");
            act.js("window.nhScriptDone && nhScriptDone(" + JSONObject.quote(id) + "," + results.toString() + ")");
        } });
    }

    /** keys from the diagnostic, timed here rather than by the page (the page is idle while Home shows) */
    void injectKeys(String codes, int gap) {
        String[] k = codes.split(",");
        for (int i = 0; i < k.length; i++) {
            final int code;
            try { code = Integer.parseInt(k[i].trim()); } catch (Exception e) { continue; }
            main.postDelayed(new Runnable() { @Override public void run() { if (built) injectKey(code); } }, (long) i * gap);
        }
    }

    /* switches for the diagnostic's comparisons: what does each piece of the look cost?
       noshadow — tiles without their shadows; notilt — no 3-D tilt; noglass — the drawer without the photo behind it */
    private String testFlags = "";
    void setTest(String flags) {
        testFlags = flags == null ? "" : flags;
        boolean noShadow = testFlags.contains("noshadow");
        TileView.tiltOn = !testFlags.contains("notilt");
        TileView.shadowsOn = !noShadow;
        for (TileView t : tiles.values()) t.applyShadow();
        // put everything back first, then switch off what this comparison asks for
        applyGlassMode();
        boolean f;
        photoBox.setVisibility(testFlags.contains("nophoto") ? INVISIBLE : VISIBLE);
        scrim.setVisibility(testFlags.contains("noscrim") ? INVISIBLE : VISIBLE);
        if (!testFlags.contains("nophoto") && !testFlags.contains("noscrim")) applyCover();
        content.setVisibility(testFlags.contains("notiles") ? INVISIBLE : VISIBLE);
        f = testFlags.contains("nobars");
        tabs.setVisibility(f ? INVISIBLE : VISIBLE); if (side || barStyle.equals("classic") || barStyle.equals("float")) topbar.setVisibility(f ? INVISIBLE : VISIBLE);
        for (View x : barExtras) x.setVisibility(f ? INVISIBLE : VISIBLE);
        for (GlassPanel g : panels) {
            if (testFlags.contains("nolens")) g.setLiquid(false);
            if (testFlags.contains("noglassall") || (g == drawer && testFlags.contains("noglass"))) g.back.setVisibility(GONE);
            g.refreshTint();
        }
        refreshHole();
        boolean redraw = testFlags.contains("redraw");
        if (redraw && !redrawing) { redrawing = true; Choreographer.getInstance().postFrameCallback(redrawCb); }
        redrawing = redraw;
    }
    /* for the comparisons: redraw the whole screen every frame, as when the drawer opens or a photo
       changes, so each piece's cost shows up in full */
    private boolean redrawing;
    private final Choreographer.FrameCallback redrawCb = new Choreographer.FrameCallback() {
        @Override public void doFrame(long t) {
            if (!redrawing || !built) { redrawing = false; return; }
            photoBox.invalidate(); scrim.invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    /** a key from the diagnostic (as if pressed on the remote) */
    void injectKey(int code) {
        key(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        key(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    // ================================================================== small drawables

    /** colourful fallback background while there's no photo */
    static final class Aurora extends android.graphics.drawable.Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            c.drawColor(0xFF0B0D18);
            blob(c, b, .18f, .20f, .60f, .70f, 0xFF2B4B9B);
            blob(c, b, .85f, .15f, .55f, .60f, 0xFF6A2F8A);
            blob(c, b, .60f, .85f, .70f, .70f, 0xFF0F5C6E);
        }
        private void blob(Canvas c, android.graphics.Rect b, float x, float y, float rw, float rh, int col) {
            c.save();
            c.translate(b.left + b.width() * x, b.top + b.height() * y);
            c.scale(b.width() * rw, b.height() * rh);
            p.setShader(new RadialGradient(0, 0, 1, new int[] { col, col & 0x00FFFFFF }, new float[] { 0, .6f }, Shader.TileMode.CLAMP));
            c.drawCircle(0, 0, 1, p);
            c.restore();
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }

    /** the soft light inside the dock that follows the focused app */
    static final class Glow extends android.graphics.drawable.Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            c.save();
            c.translate(b.exactCenterX(), b.exactCenterY());
            c.scale(b.width() / 2f, b.height() / 2f);
            // (the same unit-circle light every time: made once, not on every frame the dock moves)
            if (p.getShader() == null) p.setShader(new RadialGradient(0, 0, 1, new int[] { 0x3DFFFFFF, 0x00FFFFFF }, new float[] { 0, .7f }, Shader.TileMode.CLAMP));
            c.drawCircle(0, 0, 1, p);
            c.restore();
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
