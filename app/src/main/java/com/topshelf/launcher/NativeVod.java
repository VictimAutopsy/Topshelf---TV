package com.topshelf.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Movies & TV Shows, drawn by Android (it was a web page): rows of posters from Cinemeta (Popular,
 * New, Top rated, genres, and My List), the focused title's backdrop and details, the trailer
 * preview when you rest on a title, OK to open it in Nuvio and hold OK for more.
 *
 * Drawn the way this TV's graphics chip likes: the backdrop, its shading, its colour glow and the
 * film grain are painted together into one solid picture per title (off the main thread), posters
 * are solid pictures with only their rounded edges clipped, and only the focused poster has a
 * shadow and glow.
 */
final class NativeVod extends FrameLayout {
    /* Fading in, this pane is drawn piece by piece at the fading strength rather than into one picture
       first: its rows and pictures are still arriving as it fades, and each one spoilt that picture,
       so the whole screen was drawn twice a frame (switching tabs ran at about 20 frames a second). */
    @Override public boolean hasOverlappingRendering() { return false; }
    static final String CINEMETA = "https://v3-cinemeta.strem.io";
    private static final String[] G_MOVIE = { "Action", "Comedy", "Drama", "Sci-Fi", "Thriller", "Horror", "Animation", "Romance", "Crime", "Family", "Documentary" };
    private static final String[] G_SERIES = { "Drama", "Comedy", "Crime", "Sci-Fi", "Animation", "Reality-TV", "Documentary", "Mystery", "Action", "Family" };

    // poster geometry (rem)
    private static float PAD = 5;           // (3 on a phone held upright)
    private static final float PW = 13, PH = 19.5f, GAP = 1.6f, ROW_H = 26.6f, ROWS_Y = 34f, XW = 34.7f;
    /* Mixed shelves (Settings › Movies & TV look, after Apple TV's app): rows of different shapes — Up Next and
       Coming up as wide pictures with their names underneath, a numbered Top 10, Browse tiles that jump to a
       genre or service row, and posters for the rest. "classic": every row of posters, as before. */
    private boolean shelves() { return !"classic".equals(h.S.optString("vodLook", "shelves")); }
    private static final float WW = 24f, WH = 13.5f, TW = 19f, TH = 10.4f, NUM = 7.6f;
    private static float cardW(Row x) { return x.kind.equals("wide") ? WW : x.kind.equals("tile") ? TW : PW; }
    private static float cardH(Row x) { return x.kind.equals("wide") ? WH : x.kind.equals("tile") ? TH : PH; }
    private static float lead(Row x) { return x.kind.equals("top") ? NUM : 0; }
    private static float pitch(Row x) { return lead(x) + cardW(x) + (x.kind.equals("wide") ? 2f : GAP); }
    private static float rowH(Row x) { return x.kind.equals("wide") ? 24.2f : x.kind.equals("tile") ? 18.6f : ROW_H; }
    /** where a card starts in its row (rem): Top 10's tenth card leaves room for a two-digit number as big as the rest */
    private static final float TEN_EXTRA = 4.2f;
    private static float cellX(Row x, int c) { return PAD + lead(x) + c * pitch(x) + (x.kind.equals("top") && c >= 9 ? TEN_EXTRA : 0); }
    private float rowY(int i) { float y = 0; for (int k = 0; k < i && k < rows.size(); k++) y += rowH(rows.get(k)); return y; }

    private final NativeHome h;
    private final float R;
    private final int W, H;
    private String type = "movie";
    private boolean shown;

    // theme
    private int ring, glowCol, metaCol, hintCol, chipBg, chipFg;
    private float rad;

    // views
    private final ImageView[] bg = new ImageView[2];
    private int bgTop = -1;
    private final FrameLayout trBg;          // full-screen trailer (Settings → Trailer previews: behind the title)
    private final View trBgShade, trFade;
    private final TextView label;
    private final LinearLayout hero;
    private final ImageView heroLogo;
    private final TextView heroTitle, heroChip, heroMeta, heroDesc, heroHint;
    private final FrameLayout rowsBox, rowsIn;
    private final View glowView;
    private Bitmap glowBmp, glowWideBmp;

    // rows
    private final class Row {
        String id, title, url; boolean mine, cw, cal, loading; String err = "";
        String kind = "poster";        // poster | wide (landscape, title under) | top (numbered) | tile (Browse)
        List<JSONObject> items;
        int col; float scroll;
        FrameLayout view, strip; TextView titleV;
        final Map<Integer, PosterView> posters = new HashMap<>();
    }
    private final List<Row> rows = new ArrayList<>();
    private int r = 0;
    /** a title from the first ordinary row (not Continue watching): for the self-check to open */
    JSONObject firstTitle() {
        for (Row w : rows) if (!w.cw && !w.cal && w.items != null) for (JSONObject m : w.items) if (m != null && m.optString("id").startsWith("tt")) return m;
        return null;
    }
    private final Map<String, Object[]> cat = new HashMap<>();   // url → {time, List<JSONObject>}

    // trailer preview
    private TrailerPlayer trailer;
    private FrameLayout wide;                 // the widened card
    private FrameLayout wideTr;               // where its trailer plays
    private String wideKey = "";
    private final Runnable expandLater = new Runnable() { @Override public void run() { expand(); } };

    // backdrops
    /** Continue watching first (what you're part-way through, and shows' next episodes) */
    private void addCw() {
        if (!Progress.continueList(getContext(), type, 1).isEmpty()) { Row cw = new Row(); cw.id = "cw"; cw.title = "Continue watching"; cw.cw = true; rows.add(cw); }
        // TV Shows: the next new episode of each show you watch (Upcoming)
        if (type.equals("series") && !Upcoming.cached().isEmpty()) { Row c = new Row(); c.id = "cal"; c.title = "Coming up"; c.cal = true; rows.add(c); }
    }
    /* Coming up is worked out again when TV Shows is shown (at most every 20 minutes); when what it holds
       changes, the rows are made again where you are. */
    private long calAt;
    private void refreshUpcoming(boolean force) {
        if (!type.equals("series")) return;
        long now = System.currentTimeMillis();
        if (!force && now - calAt < 20 * 60000L) return;
        calAt = now;
        final JSONArray ml = myList();
        final boolean h24 = "24".equals(h.S.optString("clock", "12"));
        final String before = calKeys(Upcoming.cached());
        h.imgs.run(new Runnable() { @Override public void run() {
            final List<JSONObject> l;
            try { l = Upcoming.compute(getContext(), ml, h24); } catch (Throwable e) { Diag.ev("coming up: " + e); return; }
            main().post(new Runnable() { @Override public void run() {
                if (calKeys(l).equals(before) || !type.equals("series") || rows.isEmpty()) return;
                cwChanged();
            } });
        } });
    }
    private static String calKeys(List<JSONObject> l) { StringBuilder b = new StringBuilder(); for (JSONObject m : l) b.append(m.optString("id")).append(m.optString("calLabel")).append('|'); return b.toString(); }
    /** what you've watched changed: Continue watching again (keeping your place) */
    /** the addons changed: the rows are made again (now if on screen, else when next shown) */
    /** Settings › Movies & TV look changed: the new look, and the rows made again */
    void lookChanged() { glows.clear(); theme(); shadeLabel(); addonsChanged(); }
    void addonsChanged() {
        if (rows.isEmpty()) return;
        if (shown) { String curId = r < rows.size() ? rows.get(r).id : ""; buildRows(); for (int k = 0; k < rows.size(); k++) if (rows.get(k).id.equals(curId)) r = k; if (r >= rows.size()) r = 0; syncRows(); focus(false); }
        else rows.clear();
    }
    void cwChanged() {
        if (rows.isEmpty()) return;
        String curId = r < rows.size() ? rows.get(r).id : "";
        buildRowsKeep();
        int i = 0; for (int k = 0; k < rows.size(); k++) if (rows.get(k).id.equals(curId)) i = k;
        r = Math.min(i, rows.size() - 1);
        if (shown) { syncRows(); focus(false); }
    }
    private String bgWant = "";
    private long lastMove;
    private final LruCache<String, Bitmap[]> baked = new LruCache<String, Bitmap[]>(3) {   // url → {composite, glass light, glass frost} (full-screen pictures: a few)
        /* a backdrop dropped from here is given back as soon as nothing shows it (it used to wait for the garbage
           collector, and browsing piled up dozens of screen-size pictures on the graphics chip) */
        @Override protected void entryRemoved(boolean evicted, String k, Bitmap[] old, Bitmap[] nu) {
            if (old == null || old == nu) return;
            for (Bitmap[] v : snapshot().values()) if (v == old) return;     // (the same pictures are kept under another name: "last:")
            h.reclaim(old);
        }
    };
    private final Runnable trimmer = new Runnable() { @Override public void run() { baked.evictAll(); } };
    { Diag.TRIMMERS.add(trimmer); }
    void dispose() { Diag.TRIMMERS.remove(trimmer); baked.evictAll(); }
    private final Runnable bgLater = new Runnable() { @Override public void run() { backdropNow(); } };
    private final Runnable heroLater = new Runnable() { @Override public void run() { drawHero(); } };
    private int heroTok;
    private static Bitmap grain;

    NativeVod(NativeHome host) {
        super(host.getContext());
        h = host; R = host.R; W = host.W; H = host.H;
        PAD = h.portrait ? 3 : 5;
        setClipChildren(true);
        for (int i = 0; i < 2; i++) {
            bg[i] = new ImageView(getContext());
            bg[i].setScaleType(ImageView.ScaleType.FIT_XY);
            bg[i].setAlpha(0f);
            addView(bg[i], new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        trBg = new FrameLayout(getContext());
        trBg.setAlpha(0f);
        addView(trBg, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        // (Settings → Header trailer: "fit" — the soft edges of the trailer's box, over it)
        trFade = new View(getContext());
        trFade.setVisibility(GONE);
        addView(trFade, new LayoutParams(1, 1));
        trBgShade = new View(getContext());
        trBgShade.setVisibility(GONE);
        addView(trBgShade, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        label = new TextView(getContext()) {
            @Override protected void onLayout(boolean c, int l, int t, int rr, int b) { super.onLayout(c, l, t, rr, b); shadeLabel(); }
        };
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 3.2f * R);
        label.setIncludeFontPadding(false);
        label.setLetterSpacing(-.02f);
        label.setShadowLayer(.8f * R, 0, .1f * R, 0x73000000);
        addView(label, h.at(PAD, 2.5f, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        hero = new LinearLayout(getContext());
        hero.setOrientation(LinearLayout.VERTICAL);
        heroLogo = new ImageView(getContext());
        heroLogo.setAdjustViewBounds(true);
        heroLogo.setMaxWidth(h.px(Math.min(42, h.SW - 2 * PAD))); heroLogo.setMaxHeight(h.px(10.5f));
        heroLogo.setScaleType(ImageView.ScaleType.FIT_START);
        hero.addView(heroLogo, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        heroTitle = h.text(h.fSemi, 4.6f, Color.WHITE);
        heroTitle.setLetterSpacing(-.02f);
        heroTitle.setMaxLines(2); heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroTitle.setShadowLayer(1.2f * R, 0, .2f * R, 0x80000000);
        hero.addView(heroTitle);
        LinearLayout metaRow = new LinearLayout(getContext());
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        heroChip = h.text(h.fSemi, 1.25f, Color.WHITE);
        heroChip.setText("✓ My List");
        heroChip.setPadding(h.px(.8f), h.px(.25f), h.px(.8f), h.px(.25f));
        LinearLayout.LayoutParams chl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); chl.rightMargin = h.px(1);
        metaRow.addView(heroChip, chl);
        heroMeta = h.text(h.fMed, 1.65f, 0xD1FFFFFF);
        heroMeta.setSingleLine(true); heroMeta.setEllipsize(TextUtils.TruncateAt.END);
        metaRow.addView(heroMeta);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); ml.topMargin = h.px(1.1f);
        hero.addView(metaRow, ml);
        heroDesc = h.text(h.fReg, 1.6f, 0xC7FFFFFF);
        heroDesc.setMaxLines(3); heroDesc.setEllipsize(TextUtils.TruncateAt.END);
        heroDesc.setLineSpacing(0, 1.22f);
        heroDesc.setShadowLayer(.6f * R, 0, .1f * R, 0x80000000);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); dl.topMargin = h.px(.8f);
        hero.addView(heroDesc, dl);
        heroHint = h.text(h.fMed, 1.3f, 0x8CFFFFFF);
        heroHint.setLetterSpacing(.02f);
        heroHint.setText("OK · Open in Nuvio  ·  hold OK · More");
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); hl.topMargin = h.px(1);
        hero.addView(heroHint, hl);
        addView(hero, h.at(PAD, 8.6f, h.px(Math.min(72, h.SW - 2 * PAD)), LayoutParams.WRAP_CONTENT));

        rowsBox = new FrameLayout(getContext());
        rowsBox.setClipChildren(true);
        addView(rowsBox, h.at(0, ROWS_Y, LayoutParams.MATCH_PARENT, H - h.px(ROWS_Y)));
        rowsIn = new FrameLayout(getContext());
        rowsIn.setClipChildren(false);
        rowsBox.addView(rowsIn, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        glowView = new View(getContext());
        glowView.setAlpha(0f);
    }

    // ================================================================== showing

    boolean isShown2() { return shown; }
    String type() { return type; }

    void show(String t) {
        String nt = t.equals("series") ? "series" : "movie";
        boolean changed = !nt.equals(type) || rows.isEmpty();
        type = nt;
        theme();
        shown = true;
        setVisibility(VISIBLE);
        // something to stand on straight away: the last backdrop seen here (kept on the TV), or the theme
        if (changed || bgTop < 0) instantBackdrop();
        if (changed) { r = 0; buildRows(); }
        else cwChanged();
        refreshUpcoming(false);
        setAlpha(0f);
        animate().alpha(1f).setDuration(350).start();     // (no ready-made picture: see hasOverlappingRendering)
    }

    /* Backdrops have a worker of their own (they waited behind dozens of posters before). */
    private final java.util.concurrent.ExecutorService bgWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private File lastBgFile(String t) { return new File(h.act.getCacheDir(), "vodbg-" + t + (h.portrait ? "-p" : "") + ".jpg"); }

    private void instantBackdrop() {
        final String t = type;
        bgWant = "";
        bgShownReal = false;
        Bitmap[] cached = baked.get("last:" + t);
        if (cached != null) { showBackdropFast(cached); return; }
        // until then the screen is the theme's dark colour (never Home's photo showing through)
        bgWorker.execute(new Runnable() { @Override public void run() {
            final File f = lastBgFile(t);
            Bitmap b = f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
            if (b == null) {
                final Bitmap[] theme = bake(null, t);
                main().post(new Runnable() { @Override public void run() { baked.put("theme:" + t, theme); if (shown && t.equals(type) && !bgShownReal) showBackdropFast(theme); } });
                return;
            }
            if (b.getWidth() != W || b.getHeight() != H) { Bitmap s2 = Bitmap.createScaledBitmap(b, W, H, true); if (s2 != b) b.recycle(); b = s2; }
            b.setHasAlpha(false);
            final Bitmap[] out = new Bitmap[] { b, Blur.glass(b, 4, 2, 1.9f, 1.06f), Blur.glass(b, 8, 3, 1.7f, 1f) };
            main().post(new Runnable() { @Override public void run() {
                baked.put("last:" + t, out);
                if (shown && t.equals(type) && !bgShownReal) showBackdropFast(out);
            } });
        } });
    }
    private boolean bgShownReal;
    private void showBackdropFast(Bitmap[] b) {
        showBackdrop(b);
        bg[bgTop].animate().cancel();
        bg[bgTop].setAlpha(1f);
        for (ImageView o : bg) if (o != bg[bgTop]) o.setAlpha(0f);
    }


    void hide() {
        shown = false;
        collapse(false);
        // let go of the trailer player entirely: a paused web video keeps the TV's video decoder, and
        // Live TV then has to fall back to Android's slow software decoder
        if (trailer != null) { trailer.release(); trailer = null; }
        main().removeCallbacks(bgLater); main().removeCallbacks(heroLater);
        animate().alpha(0f).setDuration(250).withLayer().withEndAction(new Runnable() { @Override public void run() { if (!shown) { setVisibility(INVISIBLE); release(); } } }).start();
    }
    /* Off screen, Movies & TV let go of their posters and backdrops (each tab visited kept ~20 MB of pictures in graphics
       memory, the whole session); the rows' lists stay, so coming back rebuilds the pictures near you in a moment. */
    /** (not on screen) the pictures go now, rows kept */
    void releaseHidden() { if (!shown) release(); }
    private void release() {
        for (Row y : rows) if (y.view != null) { rowsIn.removeView(y.view); y.view = null; y.strip = null; y.posters.clear(); }
        focused = null;
        for (ImageView b : bg) { b.animate().cancel(); b.setImageDrawable(null); }
        bgTop = -1; bgWant = "";
        baked.evictAll();
    }

    /** a title page or the movie player opened: the trailer stops and lets go of the TV's video decoder
     *  (a web video keeps it even when stopped, and the movie then can't get it: sound, black picture) */
    void stopTrailer() {
        collapse(false);
        if (trailer != null) { trailer.release(); trailer = null; }
    }
    /** a panel of the page (Settings…) opened over this, or an app opened: stop the trailer */
    void pause() { collapse(true); }

    private android.os.Handler main() { return h.main; }

    private void theme() {
        boolean mv = type.equals("movie");
        setBackgroundColor(mv ? 0xFF070403 : 0xFF050811);
        ring = mv ? 0xFFFFD08A : 0xFF9FE4FF;
        glowCol = mv ? 0x73FFAA3C : 0x6B64D2FF;
        metaCol = mv ? 0xDBFFE8C8 : 0xDBD6ECFF;
        hintCol = mv ? 0x99FFCF8A : 0x998CD7FF;
        chipBg = mv ? 0x40FFB340 : 0x3864D2FF;
        chipFg = mv ? 0xFFFFD99C : 0xFFC2EFFF;
        rad = (mv ? .7f : 1.4f) * R;
        if (shelves()) { rad = 1.1f * R; ring = 0xE6FFFFFF; glowCol = 0x40FFFFFF; }
        // the card you're on, in each look: On Air's square cards with the red outline, the shelf's light,
        // Photo Ink's pale paper, Spotlight's bright white glow
        String lk = GlassPanel.look;
        if (lk.equals("air")) { rad = 0; ring = GlassPanel.airColor; glowCol = 0x59E8412F; }
        else if (lk.equals("shelf")) { rad = Math.min(rad, .5f * R); ring = GlassPanel.shelfLight(); glowCol = (GlassPanel.shelfLight() & 0x00FFFFFF) | 0x73000000; }
        else if (lk.equals("ink")) { rad = Math.min(rad, .6f * R); ring = GlassPanel.inkPaper(); glowCol = (GlassPanel.inkColor & 0x00FFFFFF) | 0x8C000000; }
        else if (lk.equals("spot")) { ring = 0xFFFFFFFF; glowCol = 0x73FFFFFF; }
        label.setText(mv ? "Movies" : "TV Shows");
        if (lk.equals("air")) { label.setAllCaps(true); label.setLetterSpacing(.04f); }
        label.setTypeface(lk.equals("air") ? h.fSemi : mv && !shelves() ? Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC) : h.fSemi);
        shadeLabel();
        heroTitle.setTypeface(mv ? Typeface.create(Typeface.SERIF, Typeface.BOLD) : h.fSemi);
        heroMeta.setTextColor(metaCol);
        heroHint.setTextColor(hintCol);
        GradientDrawable cb = new GradientDrawable(); cb.setCornerRadius(R); cb.setColor(chipBg);
        heroChip.setBackground(cb); heroChip.setTextColor(chipFg);
        trBgShade.setBackground(new ShadeDrawable());
        glowBmp = glowBitmap(PW, PH);
        glowWideBmp = glowBitmap(XW, PH);
    }

    private void shadeLabel() {
        int w = label.getWidth();
        if (w == 0) return;
        if (shelves()) { label.getPaint().setShader(null); label.setTextColor(0xF2FFFFFF); label.invalidate(); return; }
        boolean mv = type.equals("movie");
        int[] c = mv ? new int[] { 0xFFFFE0A8, 0xFFFFB340, 0xFFFF7A3D } : new int[] { 0xFFA6ECFF, 0xFF64D2FF, 0xFF8E8CFF };
        label.getPaint().setShader(new LinearGradient(0, 0, w, 0, c, new float[] { 0, .55f, 1 }, Shader.TileMode.CLAMP));
        label.invalidate();
    }

    // ================================================================== rows

    private void buildRows() {
        collapse(false);
        rowsIn.removeAllViews();
        rows.clear();
        JSONArray mine = myList();
        boolean any = false;
        for (int i = 0; i < mine.length(); i++) if (type.equals(mine.optJSONObject(i).optString("type"))) any = true;
        addCw();
        if (any) { Row m = new Row(); m.id = "mine"; m.title = "My List"; m.mine = true; rows.add(m); }
        int y = Calendar.getInstance().get(Calendar.YEAR);
        /* Rows that stay current (Feeds): Trakt's (with its app ID) and TMDB's (with a key), around Cinemeta's */
        boolean tk = Feeds.traktOn(h), tm = Feeds.tmdbOn(h), mv = type.equals("movie");
        String tt = mv ? "movies" : "shows";
        if (tk) addRow("tk-trend", "Trending now", "trakt:/" + tt + "/trending?limit=40");
        addRow("pop", "Popular", CINEMETA + "/catalog/" + type + "/top.json");
        if (mv) {
            if (tm) addRow("tm-now", "In theaters", "tmdb:/movie/now_playing?region=US");
            if (tk) addRow("tk-box", "Box office this weekend", "trakt:/movies/boxoffice");
        } else if (tm) {
            addRow("tm-today", "Airing today", "tmdb:/tv/airing_today");
            addRow("tm-week", "On this week", "tmdb:/tv/on_the_air");
        }
        addRow("new", mv ? "New releases" : "New this year", CINEMETA + "/catalog/" + type + "/year/genre=" + y + ".json");
        addRow("top", "Top rated", CINEMETA + "/catalog/" + type + "/imdbRating.json");
        if (tk) addRow("tk-week", "Most watched this week", "trakt:/" + tt + "/watched/weekly?limit=40");
        if (tk) addRow("tk-ant", "Most anticipated", "trakt:/" + tt + "/anticipated?limit=40");
        if (mv && tm) addRow("tm-soon", "Coming soon", "tmdb:/movie/upcoming?region=US");
        if (tm) for (int si = 0; si < Feeds.SERVICES.length; si++)
            addRow("tm-sv" + si, "On " + Feeds.SERVICES[si][0], "tmdb:/discover/" + (mv ? "movie" : "tv") + "?with_watch_providers={svc" + si + "}&watch_region=US&with_watch_monetization_types=flatrate&sort_by=popularity.desc");
        // your addons' own rows (Settings › Addons › show rows), in the addons' order, before the genres
        try { for (JSONObject c : Addons.get(getContext()).catalogs(type)) addRow("a-" + c.optString("id"), c.optString("title"), c.optString("url")); } catch (Throwable ignored) { }
        for (String g : type.equals("movie") ? G_MOVIE : G_SERIES)
            addRow("g-" + g, g, CINEMETA + "/catalog/" + type + "/top/genre=" + android.net.Uri.encode(g) + ".json");
        applyKinds();
        rowsIn.setTranslationY(0);
        sizeRowsIn();
        if (r >= rows.size()) r = 0;
        focus(false);
    }
    /** each row's shape, the Top 10 and the Browse tiles (mixed shelves); every row a poster row when classic */
    private void applyKinds() {
        for (int i = rows.size() - 1; i >= 0; i--) if (rows.get(i).id.equals("browse") || rows.get(i).id.equals("genres")) rows.remove(i);
        boolean sh = shelves(), mv = type.equals("movie");
        int topAt = -1;
        for (int i = 0; i < rows.size(); i++) {
            Row x = rows.get(i);
            x.kind = "poster";
            if (x.cw) x.title = sh ? "Up Next" : "Continue watching";
            if (!sh) { if (x.id.equals("tk-trend")) x.title = "Trending now"; if (x.id.equals("pop")) x.title = "Popular"; continue; }
            if (x.cw || x.cal || x.id.equals("new") || x.id.equals("tm-today")) x.kind = "wide";
            if (x.id.equals("tk-trend")) { x.kind = "top"; x.title = "Top 10 right now"; topAt = i; }
        }
        if (sh && topAt < 0) for (int i = 0; i < rows.size(); i++) if (rows.get(i).id.equals("pop")) { rows.get(i).kind = "top"; rows.get(i).title = "Top 10"; topAt = i; }
        if (!sh) return;
        /* Two rows of tiles that jump to rows further down (OK): Browse — the streaming services (their logos)
           and Trakt's lists — and Genres. */
        Row b = new Row(); b.id = "browse"; b.title = "Browse"; b.kind = "tile"; b.items = new ArrayList<>();
        Row gr = new Row(); gr.id = "genres"; gr.title = "Genres"; gr.kind = "tile"; gr.items = new ArrayList<>();
        int[][] tint = { { 0xFF1D2A5E, 0xFF5A3FA0 }, { 0xFF3A1414, 0xFF8A2F2F }, { 0xFF5E4A12, 0xFFC99A2E }, { 0xFF12384A, 0xFF2F7F9A }, { 0xFF14402A, 0xFF2F8A5A }, { 0xFF3A1A3A, 0xFF8A3F8A } };
        int k = 0;
        for (Row x : rows) {
            boolean svc = x.id.startsWith("tm-sv"), gen = x.id.startsWith("g-"), more = x.id.equals("tk-ant") || x.id.equals("tk-week") || x.id.equals("tk-box") || x.id.equals("tm-soon");
            if (!svc && !gen && !more) continue;
            try {
                int[] c = tint[k++ % tint.length];
                (gen ? gr : b).items.add(new JSONObject().put("tile", true).put("name", x.title.replaceFirst("^On ", "")).put("target", x.id).put("url", x.url)
                        .put("svc", svc ? x.id.substring(5) : "")
                        .put("sub", svc ? "Streaming now" : gen ? (mv ? "Movies" : "Shows") : "From Trakt").put("c1", svc ? 0xFF22252E : c[0]).put("c2", svc ? 0xFF3A3F4C : c[1]));
            } catch (Exception ignored) { }
        }
        int at = topAt >= 0 ? topAt + 1 : Math.min(rows.size(), 2);
        if (b.items.size() >= 2) rows.add(at++, b);
        // Genres a little further down: after New releases when it's there
        int ga = -1; for (int i = 0; i < rows.size(); i++) if (rows.get(i).id.equals("new")) ga = i + 1;
        if (gr.items.size() >= 2) rows.add(ga > 0 ? ga : at, gr);
    }
    private void addRow(String id, String title, String url) { Row x = new Row(); x.id = id; x.title = title; x.url = url; rows.add(x); }

    /** rows near the focus have views (and their pictures); rows further away are let go */
    private void syncRows() {
        for (int i = 0; i < rows.size(); i++) {
            Row x = rows.get(i);
            boolean near = i >= r - 1 && i <= r + 2;
            if (near && x.items == null) load(i);
            if (near && x.view == null) makeRowView(i);
            if (!near && x.view != null && Math.abs(i - r) > 3) { rowsIn.removeView(x.view); x.view = null; x.strip = null; x.posters.clear(); }
            if (x.view != null) syncPosters(x);
        }
    }

    private void makeRowView(int i) {
        final Row x = rows.get(i);
        x.view = new FrameLayout(getContext());
        x.view.setClipChildren(false);
        rowsIn.addView(x.view, h.at(0, rowY(i), LayoutParams.MATCH_PARENT, h.px(rowH(x))));
        boolean mv = type.equals("movie") && !shelves();
        x.titleV = h.text(h.fSemi, mv ? 1.4f : 1.8f, mv ? 0xFFFFCF8A : 0xEBFFFFFF);
        x.titleV.setText(mv ? x.title.toUpperCase(Locale.US) : x.title);
        if (mv) x.titleV.setLetterSpacing(.16f);
        x.titleV.setShadowLayer(.6f * R, 0, .1f * R, 0x99000000);
        if (!mv) {
            GradientDrawable bar = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { 0xFF64D2FF, 0xFF8E8CFF });
            bar.setCornerRadius(.3f * R); bar.setBounds(0, 0, h.px(.45f), h.px(1.65f));
            x.titleV.setCompoundDrawables(bar, null, null, null);
            x.titleV.setCompoundDrawablePadding(h.px(.7f));
            x.titleV.setGravity(Gravity.CENTER_VERTICAL);
        }
        if (shelves()) {
            // (Apple TV's way: plain white titles, no bar)
            x.titleV.setCompoundDrawables(null, null, null, null);
            x.titleV.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 1.7f * R);
            x.titleV.setTypeface(h.fSemi);
            x.titleV.setTextColor(0xF2FFFFFF);
        }
        if (GlassPanel.look.equals("air")) {
            // On Air: the row's name as a lower-third label: capitals beside a red tab
            android.graphics.drawable.ColorDrawable tabD = new android.graphics.drawable.ColorDrawable(GlassPanel.airColor);
            tabD.setBounds(0, 0, h.px(.42f), h.px(1.7f));
            x.titleV.setCompoundDrawables(tabD, null, null, null);
            x.titleV.setCompoundDrawablePadding(h.px(.7f));
            x.titleV.setGravity(Gravity.CENTER_VERTICAL);
            x.titleV.setText(x.title.toUpperCase(Locale.US)); x.titleV.setLetterSpacing(.06f);
            x.titleV.setTypeface(h.fSemi);
        }
        x.view.addView(x.titleV, h.at(PAD, .6f, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        if (GlassPanel.look.equals("shelf") && !x.kind.equals("tile")) {
            // Backlit Shelf: the row stands on a lit ledge
            View ledge = new View(getContext());
            GradientDrawable plate = new GradientDrawable(); plate.setColor(0xE6151217);
            ledge.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] { plate, new GlassPanel.Edge(GlassPanel.shelfLight(), 0) }));
            x.view.addView(ledge, h.at(0, x.kind.equals("wide") ? rowH(x) - 1.8f : 3.2f + cardH(x) + .9f, LayoutParams.MATCH_PARENT, h.px(.7f)));
        }
        x.strip = new FrameLayout(getContext());
        x.strip.setClipChildren(false);
        x.view.addView(x.strip, h.at(0, 3.2f, h.px(Math.max(h.SW, PAD * 2 + 200 * pitch(x))), h.px(rowH(x) - 3.2f)));
        x.strip.setTranslationX(-h.px(x.scroll));
        if (x.items != null && x.items.isEmpty()) {
            TextView e = h.text(h.fMed, 1.4f, 0x8CFFFFFF);
            e.setText(x.err.isEmpty() ? "Nothing here yet" : "Couldn't load this row (" + x.err + ")");
            x.strip.addView(e, h.at(PAD + .2f, 8, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }
    }

    /** posters exist for what's on screen in a row (and a little either side) */
    private void syncPosters(Row x) {
        int n = x.items == null ? 9 : x.items.size();     // placeholders while loading
        if (x.kind.equals("top")) n = Math.min(n, 10);
        float pt = pitch(x);
        int first = (int) Math.floor(x.scroll / pt);
        int lo = Math.max(0, first - 2), hi = Math.min(n - 1, first + (int) Math.ceil(h.SW / pt) + 2);
        for (Integer k : new ArrayList<>(x.posters.keySet())) {
            if (k < lo || k > hi) { PosterView p = x.posters.remove(k); x.strip.removeView(p); if (p.extra != null) x.strip.removeView(p.extra); }
        }
        for (int c = lo; c <= hi; c++) {
            if (x.posters.containsKey(c)) continue;
            PosterView p = new PosterView(c, x);
            x.posters.put(c, p);
            float px0 = cellX(x, c);
            x.strip.addView(p, h.at(px0, 1.55f, h.px(cardW(x)), h.px(cardH(x))));
            if (x.kind.equals("top")) {
                // the number, big and quiet, to the poster's left
                // (the box is taller than the digits and runs a little behind the poster: nothing is cut off, "10" included)
                boolean two = c + 1 >= 10;
                TextView num = h.text(h.fSemi, 11f, 0x40FFFFFF);
                num.setText(String.valueOf(c + 1)); num.setGravity(Gravity.RIGHT | Gravity.BOTTOM); num.setIncludeFontPadding(false);
                num.setLetterSpacing(two ? -.07f : -.04f); num.setSingleLine(true);
                // (it ends just at the poster's edge, a sliver under it: the whole number shows)
                float extraW = two ? TEN_EXTRA : 0;
                x.strip.addView(num, 0, h.at(px0 - NUM - 2.6f - extraW, 1.55f + PH - 13.5f, h.px(NUM + 3.0f + extraW), h.px(13.5f)));
                p.extra = num;
            } else if (x.kind.equals("wide")) {
                // its name (and where you are / when it's on) underneath
                LinearLayout cap = new LinearLayout(getContext()); cap.setOrientation(LinearLayout.VERTICAL);
                TextView t1 = h.text(h.fSemi, 1.35f, 0xF2FFFFFF), t2 = h.text(h.fMed, 1.1f, 0x99FFFFFF);
                t1.setSingleLine(true); t1.setEllipsize(TextUtils.TruncateAt.END); t2.setSingleLine(true); t2.setEllipsize(TextUtils.TruncateAt.END);
                cap.addView(t1); cap.addView(t2);
                x.strip.addView(cap, h.at(px0, 1.55f + WH + 1.1f, h.px(WW), LayoutParams.WRAP_CONTENT));
                p.extra = cap; p.capTitle = t1; p.capSub = t2;
            }
            if (x.items != null && c < x.items.size()) p.set(x.items.get(c));
            if (wide != null && x == rows.get(r) && c > x.col && !x.kind.equals("wide")) { p.setTranslationX(h.px(XW - PW)); if (p.extra != null) p.extra.setTranslationX(h.px(XW - PW)); }
        }
    }

    private void load(final int i) {
        final Row x = rows.get(i);
        if (x.items != null || x.loading) return;
        if (x.cw) { x.items = Progress.continueList(getContext(), type, 20); return; }
        if (x.cal) { x.items = new ArrayList<>(Upcoming.cached()); return; }
        if (x.mine) {
            x.items = new ArrayList<>();
            JSONArray a = myList();
            for (int k = 0; k < a.length(); k++) { JSONObject o = a.optJSONObject(k); if (o != null && type.equals(o.optString("type"))) x.items.add(o); }
            return;
        }
        Object[] c = cat.get(x.url);
        if (c != null && System.currentTimeMillis() - (Long) c[0] < Feeds.ttl(x.url)) { x.items = asList(c[1]); return; }
        x.loading = true;
        final String url = x.url, t = type;
        h.imgs.run(new Runnable() { @Override public void run() {
            List<JSONObject> out = null; String err = "";
            String body = readCache(url);
            boolean fresh = false;
            // a copy from the last few hours is used as is (rows appear at once); older ones are refreshed
            boolean recent = body != null && System.currentTimeMillis() - cacheFile(url).lastModified() < Feeds.ttl(url);
            if (!recent) {
                try {
                    String d = Feeds.fetch(h, url, t);       // (Cinemeta, an addon, Trakt or TMDB)
                    if (d != null) { body = d; fresh = true; }
                } catch (Throwable e) { err = e.getMessage() == null ? "offline" : e.getMessage(); }
            }
            try { if (body != null) out = parse(body, t); } catch (Throwable e) { err = "bad data"; }
            if (fresh && out != null && !out.isEmpty()) writeCache(url, body);
            final List<JSONObject> res = out; final String er = err;
            main().post(new Runnable() { @Override public void run() {
                x.loading = false;
                x.items = res == null ? new ArrayList<JSONObject>() : res;
                if (res == null) x.err = er.isEmpty() ? "offline" : er;
                else cat.put(url, new Object[] { System.currentTimeMillis(), res });
                if (!t.equals(type) || !rows.contains(x)) return;
                int idx = rows.indexOf(x);
                if (x.view != null) { rowsIn.removeView(x.view); x.view = null; x.strip = null; x.posters.clear(); }
                syncRows();
                if (idx == r) focus(false);
            } });
        } });
    }
    @SuppressWarnings("unchecked") private static List<JSONObject> asList(Object o) { return (List<JSONObject>) o; }

    static List<JSONObject> parse(String body, String type) throws Exception {
        JSONArray metas = new JSONObject(body).optJSONArray("metas");
        List<JSONObject> out = new ArrayList<>();
        if (metas == null) return out;
        for (int i = 0; i < metas.length(); i++) {
            JSONObject m = metas.optJSONObject(i);
            if (m == null || m.optString("poster").isEmpty()) continue;
            out.add(slim(m, type));
        }
        return out;
    }
    /** the same fields the page keeps (My List is shared with it) */
    static JSONObject slim(JSONObject m, String type) throws Exception {
        String yt = "";
        JSONArray ts = m.optJSONArray("trailerStreams"), tr = m.optJSONArray("trailers");
        if (ts != null && ts.length() > 0) yt = ts.optJSONObject(0).optString("ytId");
        if (yt.isEmpty() && tr != null && tr.length() > 0) yt = tr.optJSONObject(0).optString("source");
        JSONObject o = new JSONObject();
        o.put("id", m.optString("id", m.optString("imdb_id")));
        o.put("tmdb", m.optString("moviedb_id", ""));
        o.put("type", m.optString("type", type).isEmpty() ? type : m.optString("type", type));
        o.put("name", m.optString("name"));
        o.put("poster", m.optString("poster")); o.put("background", m.optString("background")); o.put("logo", m.optString("logo"));
        o.put("year", m.optString("releaseInfo", m.optString("year")));
        o.put("rating", m.optString("imdbRating")); o.put("runtime", m.optString("runtime"));
        JSONArray g = m.optJSONArray("genres"); if (g == null) g = m.optJSONArray("genre");
        JSONArray g3 = new JSONArray(); if (g != null) for (int i = 0; i < Math.min(3, g.length()); i++) g3.put(g.optString(i));
        o.put("genres", g3);
        String d = m.optString("description"); o.put("desc", d.length() > 420 ? d.substring(0, 420) : d);
        o.put("yt", yt);
        return o;
    }

    private File cacheFile(String url) { return new File(h.act.getCacheDir(), "cm-" + Integer.toHexString(url.hashCode()) + ".json"); }
    private String readCache(String url) {
        try { File f = cacheFile(url); if (!f.exists()) return null; byte[] b = new byte[(int) f.length()]; FileInputStream in = new FileInputStream(f); int n = 0; while (n < b.length) { int k = in.read(b, n, b.length - n); if (k < 0) break; n += k; } in.close(); return new String(b, 0, n, StandardCharsets.UTF_8); }
        catch (Exception e) { return null; }
    }
    private void writeCache(String url, String body) {
        try { FileOutputStream o = new FileOutputStream(cacheFile(url)); o.write(body.getBytes(StandardCharsets.UTF_8)); o.close(); } catch (Exception ignored) { }
    }

    // ================================================================== focus

    private JSONObject cur() {
        if (r >= rows.size()) return null;
        Row x = rows.get(r);
        return x.items != null && x.col < x.items.size() ? x.items.get(x.col) : null;
    }

    private void focus(boolean smooth) {
        if (rows.isEmpty()) return;
        Row x = rows.get(r);
        syncRows();
        // the focused row sits at the top of the rows
        float ty = -h.px(rowY(r) - .4f);
        if (smooth) rowsIn.animate().translationY(ty).setDuration(380).setInterpolator(TileView.EASE).start();
        else { rowsIn.animate().cancel(); rowsIn.setTranslationY(ty); }
        // and the focused poster within its row
        float left = cellX(x, x.col);
        if (left - lead(x) < x.scroll + PAD || left + cardW(x) > x.scroll + h.SW - PAD) setScroll(x, Math.max(0, left - lead(x) - PAD), smooth);
        mark(smooth);
        main().removeCallbacks(heroLater);
        main().postDelayed(heroLater, smooth ? 160 : 0);
        scheduleBackdrop();
        main().removeCallbacks(expandLater);
        main().removeCallbacks(prefetchLater);
        // the trailer waits until you've had time to read about the title (Settings → Trailer starts after)
        if (!trMode().equals("off")) { main().postDelayed(prefetchLater, 2500); main().postDelayed(expandLater, Math.max(1300, h.S.optInt("trailerDelay", 5) * 1000L)); }
    }
    /* resting on a title: its trailer's video is looked up while the preview waits to start, so it
       plays the moment it's due (passing titles by isn't enough to ask) */
    private final Runnable prefetchLater = new Runnable() { @Override public void run() {
        JSONObject m = cur();
        if (m == null || !shown || !h.vodHasKeys() || m.optString("yt").isEmpty()) return;
        player().prefetch(m.optString("yt"), trMax(), !h.S.optBoolean("trailerSound", false));
    } };
    /** the biggest picture worth fetching: the whole screen behind the title, or the card */
    private int trMax() { return trMode().equals("bg") ? 1080 : 720; }

    /* While a trailer plays behind the title, the title and its details step aside and the bars at
       the top turn see-through (their glass showed the still picture, which covered the trailer). */
    private boolean trBgOn;
    private void trBgShowing(boolean on) {
        if (on == trBgOn) return;
        trBgOn = on;
        h.vodGlassClear(on);
        /* Settings → Header trailer "In place of the picture" (as Nuvio does it): the trailer simply takes the
           backdrop's place, the whole screen under the same shading; the title, its details and the rows stay
           where they are. (It was a 16:9 box above the rows, hard-edged over the picture.) */
        if (trFit()) { if (!on) untuck(); return; }
        hero.animate().cancel();
        hero.animate().alpha(on ? 0f : 1f).translationY(0).setDuration(on ? 600 : 300).setInterpolator(TileView.EASE).start();
        // Settings → Header trailer: "slide" — the rows slide right off the bottom and the tab's name fades, so
        // only the trailer is on screen (any key brings them back; a slim strip of rows used to stay showing)
        if (on && !trFit()) {
            tucked = true;
            rowsBox.animate().cancel(); rowsBox.animate().translationY(H - h.px(ROWS_Y) + h.px(2)).setDuration(800).setInterpolator(TileView.EASE).start();
            label.animate().cancel(); label.animate().alpha(0f).setDuration(500).start();
            trBgShade.animate().cancel(); trBgShade.animate().alpha(.3f).setDuration(800).start();     // (the shading for the title's text isn't needed then)
        }
        else if (!on) untuck();
    }
    private boolean tucked;
    /** the rows come back up (the first press after they tucked away does only this) */
    private boolean untuck() {
        if (!tucked && rowsBox.getTranslationY() == 0) return false;
        boolean was = tucked;
        tucked = false;
        trBgShade.animate().cancel(); trBgShade.animate().alpha(1f).setDuration(350).start();
        label.animate().cancel(); label.animate().alpha(1f).setDuration(300).start();
        rowsBox.animate().cancel();
        rowsBox.animate().translationY(0).setDuration(350).setInterpolator(TileView.EASE).start();
        return was;
    }
    private boolean trFit() { return h.S.optString("trailerFit", "slide").equals("fit"); }
    /** where the trailer behind the title plays: the whole screen, in the backdrop's place */
    private void placeTrBg() {
        LayoutParams lp = (LayoutParams) trBg.getLayoutParams();
        if (lp.width != LayoutParams.MATCH_PARENT || lp.leftMargin != 0) { lp.width = LayoutParams.MATCH_PARENT; lp.height = LayoutParams.MATCH_PARENT; lp.leftMargin = 0; lp.topMargin = 0; trBg.setLayoutParams(lp); }
        trFade.setVisibility(GONE);
    }

    private void setScroll(Row x, float s, boolean smooth) {
        x.scroll = s;
        syncPosters(x);
        if (x.strip == null) return;
        if (smooth) x.strip.animate().translationX(-h.px(s)).setDuration(380).setInterpolator(TileView.EASE).start();
        else x.strip.setTranslationX(-h.px(s));
    }

    private PosterView focused;
    private void mark(boolean smooth) {
        Row x = rows.get(r);
        PosterView p = x.posters.get(x.col);
        if (p == focused) { if (p != null) p.setFocus(true, smooth); placeGlow(); return; }
        if (focused != null) focused.setFocus(false, smooth);
        focused = p;
        if (p != null) p.setFocus(true, smooth);
        placeGlow();
    }

    /** the soft glow and shadow sit behind the focused poster (one picture, made once per look) */
    private void placeGlow() {
        if (glowView.getParent() != null) ((FrameLayout) glowView.getParent()).removeView(glowView);
        if (focused == null || focused.getParent() == null) { glowView.setAlpha(0f); return; }
        FrameLayout strip = (FrameLayout) focused.getParent();
        boolean w = wide != null;
        Row fr = rows.get(r);
        boolean tw = w && !fr.kind.equals("wide");          // (a poster opened out; a wide card stays its size)
        Bitmap b = tw ? glowWideBmp : fr.kind.equals("poster") || fr.kind.equals("top") ? glowBmp : glowFor(cardW(fr), cardH(fr));
        glowView.setBackground(new android.graphics.drawable.BitmapDrawable(getResources(), b));
        LayoutParams pl = (LayoutParams) focused.getLayoutParams();
        int m = h.px(4.5f);
        LayoutParams gl = new LayoutParams(b.getWidth(), b.getHeight());
        gl.leftMargin = pl.leftMargin - m; gl.topMargin = pl.topMargin - m;
        strip.addView(glowView, strip.indexOfChild(focused), gl);
        glowView.setPivotX(m + h.px(cardW(fr)) / 2f); glowView.setPivotY(m + h.px(cardH(fr)) / 2f);
        float gs = focusScale(fr);
        glowView.setScaleX(gs); glowView.setScaleY(gs);
        glowView.setAlpha(0f);
        glowView.animate().alpha(1f).setDuration(250).start();
    }

    private final Map<String, Bitmap> glows = new HashMap<>();
    private Bitmap glowFor(float w, float hh) { String k = w + "x" + hh + ":" + glowCol; Bitmap b = glows.get(k); if (b == null) { b = glowBitmap(w, hh); glows.put(k, b); } return b; }
    /** how much the focused card grows: less for the big landscape ones */
    private static float focusScale(Row x) { return x.kind.equals("wide") ? 1.08f : x.kind.equals("tile") ? 1.1f : 1.12f; }
    private Bitmap glowBitmap(float wRem, float hRem) {
        int m = h.px(4.5f), w = h.px(wRem), hh = h.px(hRem);
        Bitmap b = Bitmap.createBitmap(w + 2 * m, hh + 2 * m, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setMaskFilter(new BlurMaskFilter(1.3f * R, BlurMaskFilter.Blur.NORMAL));
        p.setColor(0xA6000000);
        c.drawRoundRect(new RectF(m, m + 1.6f * R * .6f, m + w, m + hh + 1.6f * R * .6f), rad, rad, p);
        p.setMaskFilter(new BlurMaskFilter(1.3f * R, BlurMaskFilter.Blur.OUTER));
        p.setColor(glowCol);
        c.drawRoundRect(new RectF(m, m, m + w, m + hh), rad, rad, p);
        return b;
    }

    // ================================================================== keys (from NativeHome)

    boolean move(char d) {
        if (untuck()) return true;
        if (rows.isEmpty()) return true;
        Row x = rows.get(r);
        int n = x.items == null ? 0 : x.items.size();
        if (x.kind.equals("top")) n = Math.min(n, 10);
        lastMove = SystemClock.uptimeMillis();
        if (d == 'u') { if (r == 0) { collapse(true); h.enterTopFromVod(); return true; } r--; }
        else if (d == 'd') { if (r >= rows.size() - 1) return true; r++; }
        else if (d == 'l') { if (x.col <= 0) { collapse(true); h.leftEdge(); return true; } x.col--; }      // (off the left edge: the menu)
        else if (d == 'r') { if (x.col >= n - 1) return true; x.col++; }
        collapse(true);
        focus(true);
        return true;
    }

    /** a finger (phones): the poster under it becomes the chosen one (true: OK then opens it); the widened card or the details up top are the chosen title */
    boolean focusAt(float x, float y) {
        if (wide != null && h.hit(wide, x, y, 0)) return true;
        for (int i = 0; i < rows.size(); i++) {
            Row x0 = rows.get(i);
            for (Map.Entry<Integer, PosterView> en : x0.posters.entrySet()) {
                if (!h.hit(en.getValue(), x, y, 0)) continue;
                int c = en.getKey();
                if (x0.items == null || c >= x0.items.size()) return false;
                if (i == r && c == x0.col) return true;
                collapse(true);
                r = i; x0.col = c;
                lastMove = SystemClock.uptimeMillis();
                focus(true);
                return true;
            }
        }
        return h.hit(hero, x, y, 0) && cur() != null;
    }
    void ok() {
        if (untuck()) return;
        final JSONObject m = cur();
        if (m == null) return;
        if (m.optBoolean("tile")) {
            // Browse: to that row
            for (int k = 0; k < rows.size(); k++) if (rows.get(k).id.equals(m.optString("target"))) { collapse(true); r = k; rows.get(k).col = 0; focus(true); return; }
            return;
        }
        if (focused != null) focused.animate().scaleX(1.06f).scaleY(1.06f).setDuration(100).withEndAction(new Runnable() { @Override public void run() {
            if (focused != null) { float s = focusScale(focused.row); focused.animate().scaleX(s).scaleY(s).setDuration(200).setInterpolator(TileView.EASE).start(); }
        } }).start();
        open(m);
    }

    void hold() {
        if (untuck()) return;
        final JSONObject m = cur();
        if (m == null || m.optBoolean("tile")) return;
        holdFor(m, focused, 1.12f);
    }
    /** a title found in Search: its page (or Nuvio) */
    void openFromSearch(JSONObject m) { open(m); }
    /** a title's menu (from its poster here, or from Search) */
    void holdFor(final JSONObject m, View anchor, float scale) {
        collapse(true);
        List<String> labels = new ArrayList<>(); List<Runnable> acts = new ArrayList<>();
        if (Addons.get(getContext()).anyStreams()) { labels.add("Choose a stream"); acts.add(new Runnable() { @Override public void run() { h.openTitle(m); } }); }
        if (m.has("video") && m.has("cwPct")) {
            labels.add("Remove from Continue watching"); acts.add(new Runnable() { @Override public void run() { Progress.hide(getContext(), m.optString("video")); } });
            labels.add("Clear all of Continue watching…"); acts.add(new Runnable() { @Override public void run() { h.clearContinue(); } });
        }
        labels.add("Open in Nuvio"); acts.add(new Runnable() { @Override public void run() { openNuvio(m); } });
        if (h.linkOn) { labels.add("Open on your other TV"); acts.add(new Runnable() { @Override public void run() {
            h.page("lkSend({type:'open',what:'title',m:" + m.toString() + "}).then(r=>{if(r)toast('Sent to '+lkTvName())})");
        } }); }
        if (!m.optString("yt").isEmpty()) { labels.add("Watch trailer"); acts.add(new Runnable() { @Override public void run() {
            h.showLaunch(currentBackdrop(), m.optString("name"), "Opening the trailer in YouTube…");
            h.bridge.openTrailer(m.optString("yt"));
        } }); }
        final boolean in = inList(m);
        labels.add(in ? "Remove from My List" : "Add to My List"); acts.add(new Runnable() { @Override public void run() { toggleList(m); } });
        // a movie: watched / not watched straight from its poster (a show: on its page, by episode, season or all of it)
        if ("movie".equals(m.optString("type", type)) && m.optString("id").startsWith("tt")) {
            final String id = m.optString("id");
            JSONObject pr = Progress.get(getContext(), id);
            final boolean was = (pr != null && Progress.done(pr)) || (pr == null && Progress.watched(getContext(), id));
            labels.add(was ? "Mark as not watched" : "Mark as watched"); acts.add(new Runnable() { @Override public void run() {
                if (was) { Progress.forget(getContext(), id); Progress.unmarkWatched(getContext(), id); }
                else try { Progress.put(getContext(), id, 1, 1, "", 0, 0, new JSONObject().put("title", m)); } catch (Exception ignored) { }
                if (h.act.trakt != null) h.act.trakt.history(!was, id);
                h.toast(was ? "Marked “" + m.optString("name") + "” as not watched" : "Marked “" + m.optString("name") + "” as watched");
            } });
        }
        h.showMenu(labels, acts, anchor, scale, m.optString("name"));
    }

    boolean back() {
        if (untuck()) return true;
        if (r > 0) { collapse(true); r = 0; focus(true); return true; }
        return false;     // NativeHome goes back to Home
    }

    private void open(JSONObject m) {
        collapse(true);
        // with stream addons installed: the title's own page here (details, episodes, the streams to choose from)
        if (Addons.get(getContext()).anyStreams()) { h.openTitle(m); return; }
        openNuvio(m);
    }
    private void openNuvio(JSONObject m) {
        String via = h.bridge.openInStremioApp(m.optString("type"), m.optString("id"), "");
        if (via == null || via.isEmpty()) { h.toast("Nuvio or Stremio isn't installed"); return; }
        String app = via.replaceAll("\\s+v?\\d[\\w.\\-]*(\\s*\\(.*\\))?$", "").replaceAll("\\s*\\(no title links\\)$", "");
        h.showLaunch(currentBackdrop(), m.optString("name"), "Opening in " + app + "…");
    }

    private Bitmap currentBackdrop() {
        if (bgTop < 0) return null;
        android.graphics.drawable.Drawable d = bg[bgTop].getDrawable();
        return d instanceof android.graphics.drawable.BitmapDrawable ? ((android.graphics.drawable.BitmapDrawable) d).getBitmap() : null;
    }

    // ================================================================== My List (shared with the page)

    private JSONArray myList() {
        try { String s = h.prefs.getString("ui.mylist", null); return s == null ? new JSONArray() : new JSONArray(s); } catch (Exception e) { return new JSONArray(); }
    }
    private boolean inList(JSONObject m) {
        JSONArray a = myList();
        for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null && o.optString("id").equals(m.optString("id")) && o.optString("type").equals(m.optString("type"))) return true; }
        return false;
    }
    void toggleList(JSONObject m) {
        JSONArray a = myList(), out = new JSONArray();
        boolean had = inList(m);
        if (!had) out.put(m);
        for (int i = 0; i < a.length() && out.length() < 200; i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            if (had && o.optString("id").equals(m.optString("id")) && o.optString("type").equals(m.optString("type"))) continue;
            out.put(o);
        }
        h.prefs.edit().putString("ui.mylist", out.toString()).apply();
        h.toast(had ? "Removed “" + m.optString("name") + "” from My List" : "Added “" + m.optString("name") + "” to My List");
        h.page("V.mine=readJSON('mylist',[]);window.tshelfRebuild&&tshelfRebuild();typeof searchRefresh==='function'&&searchRefresh()");
        // rebuild the rows, keeping your place
        if (rows.isEmpty() || r >= rows.size()) return;
        String curId = rows.get(r).id;
        Map<String, Integer> cols = new HashMap<>(); Map<String, Float> scr = new HashMap<>();
        for (Row x : rows) { cols.put(x.id, x.col); scr.put(x.id, x.scroll); }
        int keepR = r;
        buildRowsKeep();
        for (Row x : rows) { Integer c = cols.get(x.id); if (c != null) { x.col = c; x.scroll = scr.get(x.id); } }
        int i = -1; for (int k = 0; k < rows.size(); k++) if (rows.get(k).id.equals(curId)) i = k;
        r = i >= 0 ? i : Math.max(0, Math.min(keepR, rows.size() - 1));
        Row x = rows.get(r);
        if (x.mine) { load(r); if (x.items != null) x.col = Math.min(x.col, Math.max(0, x.items.size() - 1)); }
        for (Row y : rows) if (y.view != null) { rowsIn.removeView(y.view); y.view = null; y.strip = null; y.posters.clear(); }
        focused = null;
        focus(false);
        drawHero();
    }
    private void buildRowsKeep() {
        List<Row> old = new ArrayList<>(rows);
        collapse(false);
        rowsIn.removeAllViews();
        rows.clear();
        boolean any = false;
        JSONArray mine = myList();
        for (int i = 0; i < mine.length(); i++) if (type.equals(mine.optJSONObject(i).optString("type"))) any = true;
        addCw();
        if (any) { Row m = new Row(); m.id = "mine"; m.title = "My List"; m.mine = true; rows.add(m); }
        for (Row o : old) if (!o.mine && !o.cw && !o.cal && !o.id.equals("browse") && !o.id.equals("genres")) { o.view = null; o.strip = null; o.posters.clear(); rows.add(o); }
        applyKinds();
        sizeRowsIn();
    }
    /** the rows' frame is as tall as all of them: it slides up inside the clipped area, and a frame
     *  only as tall as the screen was cutting off the rows further down */
    private void sizeRowsIn() {
        LayoutParams lp = (LayoutParams) rowsIn.getLayoutParams();
        lp.height = h.px(rowY(rows.size()) + 30);
        rowsIn.setLayoutParams(lp);
    }

    // ================================================================== title details

    private void drawHero() {
        final JSONObject m = cur();
        if (m == null) { hero.setAlpha(0f); return; }
        final int tok = ++heroTok;
        final String logo = m.optString("logo");
        heroLogo.setImageDrawable(null);
        heroLogo.setVisibility(GONE);
        heroTitle.setVisibility(VISIBLE);
        heroTitle.setText(m.optString("name"));
        if (!logo.isEmpty()) {
            Bitmap b = h.imgs.cached("vl:" + logo);
            if (b != null) { heroLogo.setImageBitmap(b); heroLogo.setVisibility(VISIBLE); heroTitle.setVisibility(GONE); }
            else {
                heroTitle.setVisibility(INVISIBLE);       // the logo usually arrives in a moment
                h.imgs.load("vl:" + logo, new HomeImages.Source() { @Override public byte[] bytes() throws Exception { return HomeImages.http(logo, null); } },
                        h.px(34), h.px(9.5f), new HomeImages.Done() { @Override public void done(Bitmap bm) {
                            if (tok != heroTok) return;
                            if (bm != null) { heroLogo.setImageBitmap(bm); heroLogo.setVisibility(VISIBLE); heroTitle.setVisibility(GONE); }
                            else heroTitle.setVisibility(VISIBLE);
                        } });
                main().postDelayed(new Runnable() { @Override public void run() { if (tok == heroTok && heroLogo.getVisibility() != VISIBLE) heroTitle.setVisibility(VISIBLE); } }, 450);
            }
        }
        List<String> parts = new ArrayList<>();
        String y = m.optString("year"); if (!y.isEmpty()) parts.add(y.length() > 9 ? y.substring(0, 9) : y);
        if (!m.optString("rating").isEmpty()) parts.add("★ " + m.optString("rating"));
        if (!m.optString("runtime").isEmpty()) parts.add(m.optString("runtime"));
        JSONArray g = m.optJSONArray("genres");
        if (g != null && g.length() > 0) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < g.length(); i++) { if (i > 0) sb.append(", "); sb.append(g.optString(i)); } parts.add(sb.toString()); }
        heroMeta.setText(TextUtils.join("  ·  ", parts));
        heroChip.setVisibility(inList(m) ? VISIBLE : GONE);
        heroDesc.setText(m.optString("desc"));
        hero.animate().cancel();
        hero.setAlpha(.7f); hero.setTranslationY(.35f * R);
        hero.animate().alpha(1f).translationY(0).setDuration(320).setInterpolator(TileView.EASE).start();
    }

    // ================================================================== backdrop

    /** while you're moving quickly it waits for focus to rest before changing the picture */
    private void scheduleBackdrop() {
        main().removeCallbacks(bgLater);
        boolean quick = SystemClock.uptimeMillis() - lastMove < 450 && bgTop >= 0;
        main().postDelayed(bgLater, quick ? 380 : 0);
    }

    private void backdropNow() {
        JSONObject m = cur();
        if (m == null || !shown) return;
        String u = m.optString("background");
        if (u.isEmpty()) u = m.optString("poster");
        final String url = u, t = type;
        if (url.equals(bgWant)) return;
        bgWant = url;
        Bitmap[] have = baked.get(t + url);
        if (have != null) { bgShownReal = true; showBackdrop(have); return; }
        bgWorker.execute(new Runnable() { @Override public void run() {
            if (!url.equals(bgWant)) return;           // focus moved on before its turn
            Bitmap src = null;
            try { byte[] d = url.isEmpty() ? null : HomeImages.http(url, null); if (d != null) src = HomeImages.decode(d, W, H); } catch (Throwable ignored) { }
            final Bitmap[] out;
            try { out = bake(src, t); } catch (Throwable e) { return; }
            if (src != null) src.recycle();
            // kept on the TV: next time this screen opens on it at once
            try { java.io.FileOutputStream fo = new java.io.FileOutputStream(lastBgFile(t)); out[0].compress(Bitmap.CompressFormat.JPEG, 86, fo); fo.close(); } catch (Throwable ignored) { }
            main().post(new Runnable() { @Override public void run() {
                baked.put(t + url, out);
                baked.put("last:" + t, out);
                if (url.equals(bgWant) && t.equals(type) && shown) { bgShownReal = true; showBackdrop(out); }
            } });
        } });
    }

    /** new picture fades in over the old one (which stays solid underneath until covered) */
    private void showBackdrop(final Bitmap[] b) {
        final int next = bgTop == 0 ? 1 : 0;
        final ImageView nv = bg[next];
        nv.animate().cancel();
        nv.setImageBitmap(b[0]);
        bringChildToFront2(nv);
        nv.setAlpha(0f);
        final int under = bgTop;
        bgTop = next;
        nv.animate().alpha(1f).setDuration(900).setInterpolator(new LinearInterpolator()).withEndAction(new Runnable() { @Override public void run() {
            if (bgTop == next && under >= 0) bg[under].setAlpha(0f);
        } }).start();
        h.setVodGlass(b[1], b[2]);
    }
    private void bringChildToFront2(View v) {
        // keep the two pictures at the bottom: just swap their order
        int i = indexOfChild(v);
        if (i == 1) return;
        removeView(v);
        addView(v, 1, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    /** backdrop + shading + colour glow + grain, painted once into one solid screen-size picture */
    private Bitmap[] bake(Bitmap src, String t) {
        boolean mv = t.equals("movie");
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        // the theatre (shows where there's no picture)
        c.drawColor(mv ? 0xFF070403 : 0xFF050811);
        int amb;
        if (src != null) {
            // cover, centred, a fifth of the way down
            // (a phone held upright: the picture across the top, fading into the theatre below)
            boolean tall = H > W;
            float s = tall ? Math.max(W / (float) src.getWidth(), H * .42f / src.getHeight()) : Math.max(W / (float) src.getWidth(), H / (float) src.getHeight());
            float dw = src.getWidth() * s, dh = src.getHeight() * s;
            Matrix mx = new Matrix(); mx.setScale(s, s); mx.postTranslate((W - dw) / 2f, tall ? 0 : (H - dh) * .2f);
            c.drawBitmap(src, mx, p);
            amb = ambient(src);
        } else amb = mv ? 0xFFFFAA50 : 0xFF64C8FF;
        int base = mv ? 0x0A0502 : 0x040814, end = mv ? 0x080402 : 0x03060E;
        Paint g = new Paint();
        if (H > W) g.setShader(new LinearGradient(0, 0, 0, H, new int[] { a(base, .3f), a(base, .5f), a(base, .93f), a(base, 1f) }, new float[] { 0, .16f, .36f, .44f }, Shader.TileMode.CLAMP));
        else g.setShader(new LinearGradient(0, 0, W, 0, new int[] { a(base, mv ? .88f : .9f), a(base, .55f), a(base, .05f), a(base, .05f) }, new float[] { 0, .38f, .7f, 1 }, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, g);
        g.setShader(new LinearGradient(0, 0, 0, H, new int[] { a(base, .4f), a(base, 0), a(base, .3f), a(end, .94f), a(end, .94f) }, new float[] { 0, .2f, .46f, .72f, 1 }, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, g);
        // the title's colour glowing softly behind the rows, like a bias light
        glow(c, W * .5f, H * 1.08f, W * .75f, H * .55f, a(amb & 0xFFFFFF, .34f), .72f);
        glow(c, W * .88f, H * .22f, W * .35f, H * .45f, a(amb & 0xFFFFFF, .14f), .7f);
        // film grain (still: moving grain would redraw the whole screen all the time)
        Paint gp = new Paint();
        gp.setShader(new android.graphics.BitmapShader(grain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        gp.setAlpha(Math.round(255 * (mv ? .055f : .025f)));
        c.drawRect(0, 0, W, H, gp);
        out.setHasAlpha(false);
        Bitmap light = Blur.glass(out, 4, 2, 1.9f, 1.06f), frost = Blur.glass(out, 8, 3, 1.7f, 1f);
        return new Bitmap[] { out, light, frost };
    }
    private static int a(int rgb, float alpha) { return (Math.round(alpha * 255) << 24) | (rgb & 0xFFFFFF); }
    private static void glow(Canvas c, float cx, float cy, float rx, float ry, int col, float stop) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(0, 0, 1, new int[] { col, col & 0xFFFFFF }, new float[] { 0, stop }, Shader.TileMode.CLAMP));
        c.save(); c.translate(cx, cy); c.scale(rx, ry); c.drawCircle(0, 0, 1, p); c.restore();
    }
    /** the picture's main colour, lifted to a glowing tone (bright, colourful pixels count most) */
    private static int ambient(Bitmap src) {
        Bitmap s = Bitmap.createScaledBitmap(src, 24, 14, true);
        double r = 0, g = 0, b = 0, w = 0;
        for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
            int px = s.getPixel(x, y);
            int R = (px >> 16) & 255, G = (px >> 8) & 255, B = px & 255;
            int mx = Math.max(R, Math.max(G, B)), mn = Math.min(R, Math.min(G, B));
            double wt = .15 + (mx - mn) / 255.0 * (mx / 255.0) * 3;
            r += R * wt; g += G * wt; b += B * wt; w += wt;
        }
        if (s != src) s.recycle();
        r /= w; g /= w; b /= w;
        double mx = Math.max(Math.max(r, g), Math.max(b, 1)), k = Math.min(2.2, 210 / mx);
        return 0xFF000000 | (clamp(r * k) << 16) | (clamp(g * k) << 8) | clamp(b * k);
    }
    private static int clamp(double v) { return (int) Math.max(0, Math.min(255, Math.round(v))); }
    private static synchronized Bitmap grain() {
        if (grain != null) return grain;
        int n = 180;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        java.util.Random rnd = new java.util.Random(7);
        int[] px = new int[n * n];
        for (int i = 0; i < px.length; i++) { int v = rnd.nextInt(256); px[i] = 0xFF000000 | (v << 16) | (v << 8) | v; }
        b.setPixels(px, 0, n, 0, 0, n, n);
        grain = b;
        return b;
    }

    /** the shading drawn over a trailer playing behind the title (only while it plays) */
    private final class ShadeDrawable extends android.graphics.drawable.Drawable {
        // (both shades made once per kind, not on every frame of the trailer)
        private final Paint g1 = new Paint(), g2 = new Paint();
        private String madeFor;
        @Override public void draw(Canvas c) {
            boolean mv = type.equals("movie");
            if (!type.equals(madeFor)) {
                madeFor = type;
                int base = mv ? 0x0A0502 : 0x040814, end = mv ? 0x080402 : 0x03060E;
                g1.setShader(new LinearGradient(0, 0, W, 0, new int[] { a(base, .88f), a(base, .55f), a(base, .05f), a(base, .05f) }, new float[] { 0, .38f, .7f, 1 }, Shader.TileMode.CLAMP));
                g2.setShader(new LinearGradient(0, 0, 0, H, new int[] { a(base, .4f), a(base, 0), a(base, .3f), a(end, .94f), a(end, .94f) }, new float[] { 0, .2f, .46f, .72f, 1 }, Shader.TileMode.CLAMP));
            }
            c.drawRect(0, 0, W, H, g1);
            c.drawRect(0, 0, W, H, g2);
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    // ================================================================== trailer preview

    private String trMode() { return h.S.optString("trailers", "card"); }

    private final Map<String, String> ytAsked = new HashMap<>();       // title id → its trailer's YouTube id ("" none)
    private void askTrailer(final JSONObject m) {
        final String id = m.optString("id").split(":")[0], t = m.optString("type", type).equals("series") ? "series" : "movie";
        ytAsked.put(m.optString("id"), "");
        h.imgs.run(new Runnable() { @Override public void run() {
            String yt = "";
            try {
                byte[] d = HomeImages.http(CINEMETA + "/meta/" + t + "/" + id + ".json", null);
                JSONObject meta = d == null ? null : new JSONObject(new String(d, StandardCharsets.UTF_8)).optJSONObject("meta");
                if (meta != null) yt = slim(meta, t).optString("yt");
            } catch (Throwable ignored) { }
            final String y = yt;
            main().post(new Runnable() { @Override public void run() {
                ytAsked.put(m.optString("id"), y);
                if (!y.isEmpty()) try { m.put("yt", y); } catch (Exception ignored) { }
                if (cur() == m && shown) expand();          // (still resting on it)
            } });
        } });
    }
    /** resting on a title: its poster widens into a backdrop card that plays the trailer */
    private void expand() {
        final JSONObject m = cur();
        if (m == null || !shown || focused == null || !h.vodHasKeys()) return;
        String how = trMode();
        if (how.equals("off") || m.optBoolean("tile")) return;
        /* no trailer known for this one (Up Next, Coming up, My List and Trakt's lists often come without):
           Cinemeta is asked for it once, and the card opens when it answers */
        if (m.optString("yt").isEmpty() && m.optString("id").startsWith("tt") && !ytAsked.containsKey(m.optString("id"))) { askTrailer(m); return; }
        if (m.optString("yt").isEmpty() && ytAsked.containsKey(m.optString("id")) && !ytAsked.get(m.optString("id")).isEmpty())
            try { m.put("yt", ytAsked.get(m.optString("id"))); } catch (Exception ignored) { }
        final String key = type + ":" + r + ":" + rows.get(r).col + ":" + m.optString("id");
        if (key.equals(wideKey)) return;
        collapse(true);
        wideKey = key;
        final boolean muted = !h.S.optBoolean("trailerSound", false);
        if (!m.optString("yt").isEmpty()) player().prefetch(m.optString("yt"), trMax(), muted);
        if (how.equals("bg")) {
            if (m.optString("yt").isEmpty()) return;
            trBgShade.setVisibility(VISIBLE); trBgShade.setAlpha(1f);
            placeTrBg();
            player().play(trBg, m.optString("yt"), muted, trMax(), new TrailerPlayer.Listener() {
                @Override public void playing() { if (key.equals(wideKey)) { trBg.animate().alpha(1f).setDuration(400).start(); trFade.animate().alpha(1f).setDuration(400).start(); trBgShowing(true); } }
                @Override public void ended() { trBg.animate().alpha(0f).setDuration(500).start(); trFade.animate().alpha(0f).setDuration(500).start(); trBgShowing(false); }
            });
            return;
        }
        final String back = m.optString("background");
        if (back.isEmpty()) return;
        h.imgs.load("vx:" + back, new HomeImages.Source() { @Override public byte[] bytes() throws Exception { return HomeImages.http(back, null); } },
                h.px(trW()), h.px(trH()), new HomeImages.Done() { @Override public void done(Bitmap b) {
                    if (!key.equals(wideKey) || focused == null) return;
                    grow(m, b, key, muted);
                } });
    }

    private void grow(final JSONObject m, Bitmap back, final String key, final boolean muted) {
        Row x = rows.get(r);
        FrameLayout strip = (FrameLayout) focused.getParent();
        if (strip == null) return;
        // (a plain rounded clip here: the trailer's web view mustn't be drawn in pieces)
        wide = new FrameLayout(getContext());
        wide.setBackgroundColor(0xFF111111);
        final float wr = rad;
        wide.setOutlineProvider(new android.view.ViewOutlineProvider() { @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), wr); } });
        wide.setClipToOutline(true);
        ImageView iv = new ImageView(getContext());
        iv.setScaleType(ImageView.ScaleType.MATRIX);
        if (back != null) {
            iv.setImageBitmap(back);
            float s = Math.max(h.px(trW()) / (float) back.getWidth(), h.px(trH()) / (float) back.getHeight());
            Matrix mx = new Matrix(); mx.setScale(s, s); mx.postTranslate((h.px(trW()) - back.getWidth() * s) / 2f, (h.px(trH()) - back.getHeight() * s) * .2f);
            iv.setImageMatrix(mx);
        }
        wide.addView(iv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        wideTr = new FrameLayout(getContext());
        wideTr.setBackgroundColor(Color.BLACK);
        wideTr.setAlpha(0f);
        wide.addView(wideTr, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        View shade = new View(getContext());
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] { 0xB8000000, 0x00000000, 0x00000000 }));
        wide.addView(shade, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        final TextView name = h.text(h.fSemi, 1.9f, Color.WHITE);
        name.setText(m.optString("name"));
        name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
        name.setShadowLayer(.6f * R, 0, .1f * R, 0x99000000);
        LayoutParams nl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        nl.leftMargin = h.px(1.1f); nl.rightMargin = h.px(1.1f); nl.bottomMargin = h.px(1);
        wide.addView(name, nl);
        final String logo = m.optString("logo");
        if (!logo.isEmpty()) {
            Bitmap lb = h.imgs.cached("vl:" + logo);
            if (lb != null) {
                ImageView lv = new ImageView(getContext());
                lv.setAdjustViewBounds(true); lv.setMaxWidth(h.px(13)); lv.setMaxHeight(h.px(4.2f));
                lv.setImageBitmap(lb);
                LayoutParams ll = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT);
                ll.leftMargin = h.px(1.1f); ll.bottomMargin = h.px(1);
                wide.addView(lv, ll);
                name.setVisibility(INVISIBLE);
            }
        }
        GradientDrawable ringD = new GradientDrawable(); ringD.setCornerRadius(rad); ringD.setStroke(Math.max(2, h.px(.16f)), ring);
        wide.setForeground(ringD);
        LayoutParams fl = (LayoutParams) focused.getLayoutParams();
        LayoutParams wl = new LayoutParams(h.px(trW()), h.px(trH())); wl.leftMargin = fl.leftMargin; wl.topMargin = fl.topMargin;
        strip.addView(wide, wl);
        wide.setZ(4);                                  // above the focused poster (which is raised itself)
        final PosterView under = focused;
        main().postDelayed(new Runnable() { @Override public void run() { if (wide != null && focused == under) under.setVisibility(INVISIBLE); } }, 460);
        wide.setPivotX(h.px(focused.cw) / 2f); wide.setPivotY(h.px(focused.ch) / 2f);
        float fsc = focusScale(x);
        wide.setScaleX(fsc); wide.setScaleY(fsc);
        wide.setAlpha(0f);
        wide.animate().alpha(1f).setDuration(450).start();
        // the posters after it move along to make room
        for (Map.Entry<Integer, PosterView> e : x.posters.entrySet())
            if (e.getKey() > x.col && trShift() > 0) {
                e.getValue().animate().translationX(h.px(trShift())).setDuration(400).setInterpolator(TileView.EASE).start();
                if (e.getValue().extra != null) e.getValue().extra.animate().translationX(h.px(trShift())).setDuration(400).setInterpolator(TileView.EASE).start();     // (Top 10's numbers too)
            }
        // keep the whole card on screen
        float left = cellX(x, x.col);
        if (left + trW() > x.scroll + h.SW - 2.5f) setScroll(x, left + trW() - h.SW + 2.5f, true);
        placeGlow();
        glowView.setPivotX(h.px(4.5f) + h.px(focused.cw) / 2f);
        final String yt = m.optString("yt");
        if (!yt.isEmpty()) main().postDelayed(new Runnable() { @Override public void run() {
            if (!key.equals(wideKey) || wideTr == null) return;
            final FrameLayout host = wideTr;
            player().play(host, yt, muted, trMax(), new TrailerPlayer.Listener() {
                @Override public void playing() { if (key.equals(wideKey)) host.animate().alpha(1f).setDuration(400).start(); }
                @Override public void ended() { host.animate().alpha(0f).setDuration(500).start(); }
            });
        } }, 600);
    }

    /** the trailer card's size (rem): a poster opens out to XW wide; a wide card plays in its own frame */
    private float trW() { return rows.get(r).kind.equals("wide") ? WW : XW; }
    private float trH() { return rows.get(r).kind.equals("wide") ? WH : PH; }
    private float trShift() { Row x = rows.get(r); return x.kind.equals("wide") ? 0 : XW - PW; }
    private TrailerPlayer player() { if (trailer == null) trailer = new TrailerPlayer(h.act); return trailer.tail(h.S.optInt("trailerTail", 10)); }

    /** back to plain posters (and no trailer) */
    private void collapse(boolean animate) {
        main().removeCallbacks(expandLater);
        wideKey = "";
        if (trailer != null) trailer.stop();
        trBg.animate().cancel(); trBg.setAlpha(0f); trBgShade.setVisibility(GONE);
        trFade.animate().cancel(); trFade.setAlpha(0f); trFade.setVisibility(GONE);
        trBgShowing(false);
        if (wide == null) return;
        if (focused != null) focused.setVisibility(VISIBLE);
        for (Row x : rows) for (PosterView p : x.posters.values()) p.setVisibility(VISIBLE);
        final FrameLayout w = wide;
        wide = null; wideTr = null;
        if (animate) w.animate().alpha(0f).setDuration(250).withEndAction(new Runnable() { @Override public void run() { if (w.getParent() != null) ((FrameLayout) w.getParent()).removeView(w); } }).start();
        else if (w.getParent() != null) ((FrameLayout) w.getParent()).removeView(w);
        for (Row x : rows) for (PosterView p : x.posters.values()) {
            if (p.extra != null && p.extra.getTranslationX() != 0) { if (animate) p.extra.animate().translationX(0).setDuration(400).setInterpolator(TileView.EASE).start(); else p.extra.setTranslationX(0); }
            if (p.getTranslationX() == 0) continue;
            if (animate) p.animate().translationX(0).setDuration(400).setInterpolator(TileView.EASE).start(); else p.setTranslationX(0);
        }
        if (animate) placeGlow();
    }

    // ================================================================== a poster

    /** Rounded frame whose middle is drawn as a plain solid picture; only the strips along the rounded
     *  top and bottom go through the rounded clip (soft clips are slow on this TV's graphics chip). */
    static class RoundFrame extends FrameLayout {
        private final float rr;
        private final Path p = new Path();
        private int pw, ph;
        RoundFrame(android.content.Context c, float radius) { super(c); rr = radius; }
        @Override protected void dispatchDraw(Canvas cv) {
            int w = getWidth(), hh = getHeight();
            if (w == 0 || hh <= 2 * rr) { super.dispatchDraw(cv); return; }
            if (w != pw || hh != ph) { p.reset(); p.addRoundRect(new RectF(0, 0, w, hh), rr, rr, Path.Direction.CW); pw = w; ph = hh; }
            cv.save(); cv.clipRect(0, rr, w, hh - rr); super.dispatchDraw(cv); cv.restore();
            cv.save(); cv.clipRect(0, 0, w, (float) Math.ceil(rr)); cv.clipPath(p); super.dispatchDraw(cv); cv.restore();
            cv.save(); cv.clipRect(0, (float) Math.floor(hh - rr), w, hh); cv.clipPath(p); super.dispatchDraw(cv); cv.restore();
        }
    }

    private GradientDrawable dimmed() { GradientDrawable d = new GradientDrawable(); d.setCornerRadius(rad); d.setColor(0x75000000); return d; }
    private final class PosterView extends RoundFrame {
        final ImageView img;
        final TextView name;
        String key = "";
        boolean focusedNow;
        final Row row;
        final float cw, ch;              // its size (rem)
        View extra;                      // beside it in the row: the Top 10 number, or the name underneath
        TextView capTitle, capSub;

        PosterView(int c, Row x) {
            super(NativeVod.this.getContext(), rad);
            row = x; cw = cardW(x); ch = cardH(x);
            if (GlassPanel.look.equals("spot")) setForeground(dimmed());
            GradientDrawable b = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { 0xFF34343C, 0xFF1C1C22 });
            b.setCornerRadius(rad);
            setBackground(b);
            name = h.text(h.fSemi, 1.25f, Color.WHITE);
            name.setMaxLines(3); name.setEllipsize(TextUtils.TruncateAt.END);
            LayoutParams nl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            nl.leftMargin = h.px(.7f); nl.rightMargin = h.px(.7f); nl.bottomMargin = h.px(.7f);
            addView(name, nl);
            img = new ImageView(getContext());
            img.setScaleType(ImageView.ScaleType.FIT_XY);
            addView(img, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            setPivotX(h.px(cw) / 2f); setPivotY(h.px(ch) / 2f);
        }

        void set(final JSONObject m) {
            name.setText(m.optString("name"));
            if (m.optBoolean("tile")) { tile(m); return; }
            cwMark(m);
            if (capTitle != null) {
                capTitle.setText(m.optString("name"));
                String sub = m.has("cwLabel") ? m.optString("cwLabel") : m.has("calLabel") ? (m.optString("calEp").isEmpty() ? m.optString("calLabel") : m.optString("calEp")) : "";
                if (sub.isEmpty()) { JSONArray g = m.optJSONArray("genres"); sub = (g != null && g.length() > 0 ? g.optString(0) + "  ·  " : "") + m.optString("year", m.optString("releaseInfo")); }
                capSub.setText(sub);
            }
            if (row.kind.equals("wide")) { wideArt(m); return; }
            final String main = posterUrl(m), fb = m.optString("poster");
            final String k = "vp:" + main;
            if (k.equals(key)) return;
            key = k;
            final int w = h.px(PW), hh = h.px(PH);
            Bitmap b = h.imgs.cached(k);
            if (b != null) { img.setImageBitmap(b); setBackground(null); return; }
            img.setImageDrawable(null);
            h.imgs.make(k, new HomeImages.Maker() { @Override public Bitmap make() throws Exception {
                byte[] d = null;
                try { d = HomeImages.http(main, null); } catch (Exception ignored) { }
                if ((d == null || d.length == 0) && !fb.equals(main)) d = HomeImages.http(fb, null);
                if (d == null) return null;
                Bitmap src = HomeImages.decode(d, w, hh);
                return src == null ? null : posterBitmap(src, w, hh);
            } }, new HomeImages.Done() { @Override public void done(Bitmap bm) {
                if (!k.equals(key) || bm == null) return;
                img.setImageBitmap(bm);
                setBackground(null);
            } });
        }

        /** Browse: the name on colour, what's there underneath */
        private void tile(JSONObject m) {
            key = "tile:" + m.optString("target");
            name.setVisibility(VISIBLE); if (capSub != null) capSub.setVisibility(VISIBLE);
            img.setImageDrawable(null);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { m.optInt("c1"), m.optInt("c2") });
            g.setCornerRadius(rad); setBackground(g);
            name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 1.9f * R); name.setMaxLines(2);
            LayoutParams nl = (LayoutParams) name.getLayoutParams(); nl.gravity = Gravity.TOP | Gravity.LEFT; nl.leftMargin = h.px(1.3f); nl.topMargin = h.px(1.1f); name.setLayoutParams(nl);
            if (capSub == null) {
                capSub = h.text(h.fMed, 1.1f, 0xBFFFFFFF);
                LayoutParams sl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT);
                sl.leftMargin = h.px(1.3f); sl.bottomMargin = h.px(1f);
                addView(capSub, sl);
            }
            capSub.setText(m.optString("sub"));
            // the real picture (the service's logo, or the genre's top title) replaces the plain colour when it's made
            final String k = "vt:" + m.optString("target") + ":" + type;
            key = k;
            Bitmap have = h.imgs.cached(k);
            @SuppressWarnings("unchecked")
            Object[] info = tileInfo.get(m.optString("target") + ":" + type);
            if (have != null && info != null) { showTileArt(have); tileHero(m, (String) info[0], (List<String>) info[1]); return; }
            final int w = h.px(TW), hh = h.px(TH);
            h.imgs.make(k, new HomeImages.Maker() { @Override public Bitmap make() throws Exception { return tileArt(m, w, hh); } },
                new HomeImages.Done() { @Override public void done(Bitmap bm) { if (k.equals(key) && bm != null) showTileArt(bm); } });
        }
        private void showTileArt(Bitmap bm) {
            img.setImageBitmap(bm); setBackground(null);
            name.setVisibility(INVISIBLE); if (capSub != null) capSub.setVisibility(INVISIBLE);     // (the picture carries its own words)
        }
        /** a wide card: the title's backdrop with its logo on it (one picture) */
        private void wideArt(final JSONObject m) {
            final String back = m.optString("background").isEmpty() ? m.optString("poster") : m.optString("background"), logo = m.optString("logo");
            final String k = "vw:" + back + "|" + logo;
            if (k.equals(key)) return;
            key = k;
            final int w = h.px(WW), hh = h.px(WH);
            Bitmap b = h.imgs.cached(k);
            if (b != null) { img.setImageBitmap(b); setBackground(null); return; }
            img.setImageDrawable(null);
            h.imgs.make(k, new HomeImages.Maker() { @Override public Bitmap make() throws Exception {
                byte[] d = back.isEmpty() ? null : HomeImages.http(back, null);
                if (d == null) return null;
                Bitmap src = HomeImages.decode(d, w, hh);
                if (src == null) return null;
                Bitmap out = posterBitmap(src, w, hh);
                Bitmap lg = null;
                try { byte[] ld = logo.isEmpty() ? null : HomeImages.http(logo, null); if (ld != null) lg = HomeImages.decode(ld, Math.round(w * .5f), Math.round(hh * .32f)); } catch (Throwable ignored) { }
                Canvas c = new Canvas(out);
                Paint sh = new Paint(); sh.setShader(new LinearGradient(0, hh * .45f, 0, hh, 0x00000000, 0xA6000000, Shader.TileMode.CLAMP));
                c.drawRect(0, hh * .45f, w, hh, sh);
                if (lg != null) {
                    float s = Math.min(w * .48f / lg.getWidth(), hh * .3f / lg.getHeight());
                    float lw = lg.getWidth() * s, lh = lg.getHeight() * s, lx = w * .06f, ly = hh - lh - hh * .09f;
                    c.drawBitmap(lg, null, new RectF(lx, ly, lx + lw, ly + lh), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
                    lg.recycle();
                }
                return out;
            } }, new HomeImages.Done() { @Override public void done(Bitmap bm) {
                if (!k.equals(key) || bm == null) return;
                img.setImageBitmap(bm);
                setBackground(null);
            } });
        }

        private View cwBar, cwFill; private TextView cwLab;
        void cwMark(JSONObject m) {
            boolean cw = m.has("cwPct"), cal = m.has("calLabel");
            if (!cw && !cal) { if (cwBar != null) { cwBar.setVisibility(GONE); cwFill.setVisibility(GONE); cwLab.setVisibility(GONE); } return; }
            if (cwBar == null) {
                cwBar = new View(getContext()); cwBar.setBackgroundColor(0x66000000);
                addView(cwBar, new LayoutParams(LayoutParams.MATCH_PARENT, h.px(.45f), Gravity.BOTTOM));
                cwFill = new View(getContext()); cwFill.setBackgroundColor(0xFFFF453A);
                addView(cwFill, new LayoutParams(0, h.px(.45f), Gravity.BOTTOM));
                cwLab = h.text(h.fSemi, 1.0f, Color.WHITE);
                GradientDrawable lb = new GradientDrawable(); lb.setCornerRadius(.4f * R); lb.setColor(0xD9000000); cwLab.setBackground(lb);
                cwLab.setPadding(h.px(.5f), h.px(.2f), h.px(.5f), h.px(.2f)); cwLab.setSingleLine(true); cwLab.setEllipsize(TextUtils.TruncateAt.END);
                LayoutParams ll = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT);
                ll.leftMargin = h.px(.5f); ll.rightMargin = h.px(.5f); ll.bottomMargin = h.px(1f);
                addView(cwLab, ll);
            }
            if (cal) {
                // Coming up: when, and which episode (no progress bar); the new ones out now stand out
                cwBar.setVisibility(GONE); cwFill.setVisibility(GONE);
                String lab = m.optString("calLabel");
                cwLab.setText(lab);
                GradientDrawable lb = new GradientDrawable(); lb.setCornerRadius(.4f * R); lb.setColor(lab.startsWith("New") || lab.startsWith("Today") ? 0xE6FF453A : 0xD9000000); cwLab.setBackground(lb);
                // (a wide card has the show's logo along its bottom: the label goes at the top)
                LayoutParams ll = (LayoutParams) cwLab.getLayoutParams();
                ll.gravity = (capTitle != null ? Gravity.TOP : Gravity.BOTTOM) | Gravity.LEFT; ll.topMargin = capTitle != null ? h.px(.8f) : 0; ll.leftMargin = h.px(capTitle != null ? .8f : .5f);
                cwLab.setLayoutParams(ll);
                cwLab.setVisibility(VISIBLE);
                cwLab.bringToFront();
                return;
            }
            GradientDrawable lb0 = new GradientDrawable(); lb0.setCornerRadius(.4f * R); lb0.setColor(0xD9000000); cwLab.setBackground(lb0);
            cwBar.setVisibility(VISIBLE); cwFill.setVisibility(VISIBLE);
            LayoutParams fl = (LayoutParams) cwFill.getLayoutParams(); fl.width = Math.round(h.px(PosterView.this.cw) * (float) m.optDouble("cwPct", 0)); cwFill.setLayoutParams(fl);
            String lab = m.optString("cwLabel");
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("^(Next · )?(S\\d+ · E\\d+)").matcher(lab);
            cwLab.setText(mm.find() ? (mm.group(1) == null ? "" : "Next · ") + mm.group(2) : m.optDouble("cwPct", 0) > 0 ? Math.round(m.optDouble("cwPct") * 100) + "%" : "");
            cwLab.setVisibility(cwLab.getText().length() > 0 && capTitle == null ? VISIBLE : GONE);     // (a wide card says it underneath)
            cwBar.bringToFront(); cwFill.bringToFront(); cwLab.bringToFront();
        }
        void setFocus(boolean f, boolean animate) {
            focusedNow = f;
            float s = f ? focusScale(row) : 1f;
            if (animate) animate().scaleX(s).scaleY(s).setDuration(250).setInterpolator(TileView.EASE).start();
            else { animate().cancel(); setScaleX(s); setScaleY(s); }
            // the name underneath steps down as the card lifts (and brightens)
            if (capTitle != null) {
                float dy = f ? h.px(ch) * (s - 1) / 2f : 0;
                if (animate) extra.animate().translationY(dy).setDuration(250).setInterpolator(TileView.EASE).start(); else extra.setTranslationY(dy);
                capTitle.setTextColor(f ? Color.WHITE : 0xD9FFFFFF);
            }
            if (row.kind.equals("top") && extra != null) ((TextView) extra).setTextColor(f ? 0x80FFFFFF : 0x40FFFFFF);
            if (f) { GradientDrawable rg = new GradientDrawable(); rg.setCornerRadius(rad); rg.setStroke(Math.max(2, h.px(.16f)), ring); setForeground(rg); }
            else setForeground(GlassPanel.look.equals("spot") ? dimmed() : null);     // (Spotlight: the cards you're not on sit back in the dark)
            setZ(f ? 2 : 0);
        }
    }

    /* A Browse tile's picture (worker thread). A streaming service: its own logo (from TMDB's list of
       services) on a wash of its colour, with its name. A genre or list: the backdrop of what's at the
       top of that row, darkened towards the left, with the row's name over it. */
    private Bitmap tileArt(final JSONObject m, int w, int hh) throws Exception {
        // what's in that row: its top titles (for the header above, like a poster's) and the first one's backdrop
        String url0 = m.optString("url"), body0 = readCache(url0);
        if (body0 == null) try { body0 = Feeds.fetch(h, url0, type); } catch (Throwable ignored) { }
        String bk = ""; final List<String> names = new ArrayList<>();
        if (body0 != null) try { for (JSONObject x : parse(body0, type)) { if (bk.isEmpty() && !x.optString("background").isEmpty()) bk = x.optString("background"); if (names.size() < 4) names.add(x.optString("name")); } } catch (Throwable ignored) { }
        final String heroBack = bk;
        main().post(new Runnable() { @Override public void run() { tileHero(m, heroBack, names); } });
        Bitmap out = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        RectF all = new RectF(0, 0, w, hh);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        android.text.TextPaint tp = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        String svc = m.optString("svc");
        if (!svc.isEmpty()) {
            String lu = Feeds.providerLogo(h, svc);
            byte[] d = lu == null ? null : HomeImages.http(lu, null);
            Bitmap lg = d == null ? null : HomeImages.decode(d, hh, hh);
            if (lg == null) return null;
            /* The whole tile is the service's logo: its square picture as tall as the tile, and the rest of
               the tile in that picture's own background colour, so it reads as one big logo. */
            int edge = edgeColour(lg);
            c.drawColor(edge);
            float s = hh, x0 = (w - s) / 2f;
            c.drawBitmap(lg, null, new RectF(x0, 0, x0 + s, s), p);
            // (softened seams where the picture meets the colour, in case its edge isn't one flat colour)
            Paint sf = new Paint();
            sf.setShader(new LinearGradient(x0, 0, x0 + s * .12f, 0, edge, edge & 0x00FFFFFF, Shader.TileMode.CLAMP)); c.drawRect(x0, 0, x0 + s * .12f, hh, sf);
            sf.setShader(new LinearGradient(x0 + s, 0, x0 + s * .88f, 0, edge, edge & 0x00FFFFFF, Shader.TileMode.CLAMP)); c.drawRect(x0 + s * .88f, 0, x0 + s, hh, sf);
        } else {
            // the top title of that row (found above)
            String back = heroBack, top = "";
            if (body0 != null) for (JSONObject x : parse(body0, type)) { if (!x.optString("background").isEmpty()) { top = x.optString("name"); break; } }
            byte[] d = back.isEmpty() ? null : HomeImages.http(back, null);
            Bitmap src = d == null ? null : HomeImages.decode(d, w, hh);
            if (src == null) return null;
            float s = Math.max(w / (float) src.getWidth(), hh / (float) src.getHeight());
            Matrix mx = new Matrix(); mx.setScale(s, s); mx.postTranslate((w - src.getWidth() * s) / 2f, (hh - src.getHeight() * s) * .35f);
            c.drawBitmap(src, mx, p);
            src.recycle();
            p.setShader(new LinearGradient(0, 0, w, 0, new int[] { 0xE6000000, 0x99000000, 0x14000000 }, new float[] { 0, .45f, 1 }, Shader.TileMode.CLAMP)); c.drawRect(all, p);
            p.setShader(new LinearGradient(0, hh * .4f, 0, hh, 0x00000000, 0x80000000, Shader.TileMode.CLAMP)); c.drawRect(all, p); p.setShader(null);
            // the name: as big as fits, over two lines if it must (never cut off)
            tp.setTypeface(h.fSemi); tp.setColor(Color.WHITE);
            tp.setShadowLayer(.5f * R, 0, .1f * R, 0x99000000);
            android.text.StaticLayout nl = fitText(m.optString("name"), tp, w - 2.6f * R, 2.1f * R, 1.3f * R, 2);
            float bottom = top.isEmpty() ? hh - 1.1f * R : hh - 2.6f * R;
            c.save(); c.translate(1.3f * R, bottom - nl.getHeight()); nl.draw(c); c.restore();
            tp.setTypeface(h.fMed); tp.setTextSize(1.1f * R); tp.setColor(0xCCFFFFFF);
            if (!top.isEmpty()) c.drawText(TextUtils.ellipsize("Top: " + top, tp, w - 2.6f * R, TextUtils.TruncateAt.END).toString(), 1.3f * R, hh - 1.05f * R, tp);
        }
        // the glass edge, as on the posters
        Paint e = new Paint(Paint.ANTI_ALIAS_FLAG); e.setStyle(Paint.Style.STROKE); e.setStrokeWidth(.14f * R); e.setColor(0x2EFFFFFF);
        c.drawRoundRect(all, rad, rad, e);
        // rounded corners (the card's own clip only rounds its top and bottom strips)
        Bitmap round = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
        Canvas rc = new Canvas(round);
        Paint rp = new Paint(Paint.ANTI_ALIAS_FLAG); rp.setShader(new android.graphics.BitmapShader(out, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        rc.drawRoundRect(all, rad, rad, rp);
        out.recycle();
        return round;
    }
    /** text laid out within a width: the biggest size from big down to small that fits in maxLines, whole words */
    private static android.text.StaticLayout fitText(String s, android.text.TextPaint tp, float width, float big, float small, int maxLines) {
        android.text.StaticLayout l = null;
        for (float sz = big; sz >= small - .01f; sz -= (big - small) / 6f) {
            tp.setTextSize(sz);
            l = android.text.StaticLayout.Builder.obtain(s, 0, s.length(), tp, Math.max(1, Math.round(width))).setLineSpacing(0, .95f).setIncludePad(false).build();
            boolean wordsFit = true;
            for (String wd : s.split(" ")) if (tp.measureText(wd) > width) wordsFit = false;
            if (l.getLineCount() <= maxLines && wordsFit) return l;
        }
        tp.setTextSize(small);
        return android.text.StaticLayout.Builder.obtain(s, 0, s.length(), tp, Math.max(1, Math.round(width))).setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build();
    }
    /** a Browse tile in the header: its name, what it is, what's top in it now, and that title's backdrop */
    private final Map<String, Object[]> tileInfo = new HashMap<>();      // tile target → {backdrop, top names}
    private void tileHero(JSONObject m, String back, List<String> names) {
        tileInfo.put(m.optString("target") + ":" + type, new Object[] { back, names });
        try {
            if (!back.isEmpty()) m.put("background", back);
            if (!names.isEmpty()) m.put("desc", "Top right now: " + TextUtils.join("  ·  ", names));
            m.put("year", m.optString("sub"));
        } catch (Exception ignored) { }
        if (cur() == m && shown) { drawHero(); scheduleBackdrop(); }
    }
    /** the colour round a logo's edge (its corners and sides), for the rest of its tile */
    private static int edgeColour(Bitmap b) {
        int w = b.getWidth(), hh = b.getHeight(); long r = 0, g = 0, bl = 0; int n = 0;
        int[][] pts = { { 1, 1 }, { w - 2, 1 }, { 1, hh - 2 }, { w - 2, hh - 2 }, { w / 2, 1 }, { w / 2, hh - 2 }, { 1, hh / 2 }, { w - 2, hh / 2 } };
        for (int[] q : pts) { int c = b.getPixel(Math.max(0, Math.min(w - 1, q[0])), Math.max(0, Math.min(hh - 1, q[1]))); if (Color.alpha(c) < 128) continue; r += Color.red(c); g += Color.green(c); bl += Color.blue(c); n++; }
        return n == 0 ? 0xFF15161C : Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
    }
    private static int blend(int a, int b, float t) {
        int r = Math.round(Color.red(a) * (1 - t) + Color.red(b) * t), g = Math.round(Color.green(a) * (1 - t) + Color.green(b) * t), bl = Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t);
        return Color.rgb(r, g, bl);
    }

    /** the poster at its exact size, with its glass edge painted in, as one solid picture */
    private Bitmap posterBitmap(Bitmap src, int w, int hh) {
        Bitmap out = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(0xFF1C1C22);
        float s = Math.max(w / (float) src.getWidth(), hh / (float) src.getHeight());
        Matrix mx = new Matrix(); mx.setScale(s, s); mx.postTranslate((w - src.getWidth() * s) / 2f, (hh - src.getHeight() * s) / 2f);
        c.drawBitmap(src, mx, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        src.recycle();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF r0 = new RectF(0, 0, w, hh);
        Path clip = new Path(); clip.addRoundRect(r0, rad, rad, Path.Direction.CW);
        c.save(); c.clipPath(clip);
        p.setColor(0x59FFFFFF); c.drawRect(0, 0, w, .1f * R, p);                    // light along the top
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(.07f * R * 2); p.setColor(0x24FFFFFF);
        c.drawRoundRect(r0, rad, rad, p);                                         // faint edge
        c.restore();
        out.setHasAlpha(false);
        return out;
    }

    String posterUrl(JSONObject m) {
        String p = h.S.optString("posterUrl", "").trim();
        String id = m.optString("id"), tmdb = m.optString("tmdb");
        if (p.isEmpty() || id.isEmpty()) return m.optString("poster");
        if (p.matches(".*\\{tmdb(_id|Id)?\\}.*") && tmdb.isEmpty()) return m.optString("poster");
        return p.replaceAll("\\{(imdb_id|imdbId|imdb|id)\\}", android.net.Uri.encode(id))
                .replaceAll("\\{(tmdb_id|tmdbId|tmdb)\\}", android.net.Uri.encode(tmdb))
                .replaceAll("\\{type\\}", m.optString("type").equals("series") ? "series" : "movie");
    }

    // ================================================================== diagnostic

    /** for the report: how many rows have loaded */
    String status() {
        int n = 0; for (Row x : rows) if (x.items != null && !x.items.isEmpty()) n++;
        return type + " " + n + "/" + rows.size() + " rows";
    }
}
