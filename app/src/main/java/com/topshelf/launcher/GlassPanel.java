package com.topshelf.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * A pane of glass over the photo. What's behind it is a ready-made blurred copy of the photo,
 * placed and animated exactly like the photo itself (so it lines up), clipped to the pane's
 * rounded shape, with a tint and a rim of light on top. "Liquid" glass also bends the picture at
 * the edges with a small shader on the graphics chip (Android 13+). Nothing is re-blurred per frame.
 */
final class GlassPanel extends FrameLayout {
    final FrameLayout back;                       // holds the two blurred photo layers (crossfade)
    /* holds `back`. With liquid glass it keeps the bent picture as a ready-made layer: the lens shader then
       only runs again when the picture behind changes (the photo's slow drift, a new photo), not on every
       frame something on the pane moves (a tile growing as you move along the dock). */
    private final FrameLayout host;
    final ImageView[] layer = new ImageView[2];
    final View tint;
    final Rim rim;
    float radius;
    private int photoX, photoY, photoW, photoH;   // the photo layer's box on screen
    private boolean liquid;
    private Object shader;                         // RuntimeShader (API 33+)
    boolean liquidAllowed = true;                  // panels the page drew without the lens (the drawer, menus)
    Object tag;                                    // the tint colour (Integer)
    private float dim;                             // darkens the photo behind (All apps)
    private int tintColor;
    private android.animation.ValueAnimator dimAnim;

    GlassPanel(Context c, float radius, int tintColor, float rimWidth) {
        super(c);
        this.radius = radius;
        /* Not clipped as a whole to its rounded shape: on this TV's graphics chip anything drawn
           through a soft rounded clip is blended pixel by pixel, which is slow over a big pane. The
           middle of the glass is drawn as a plain solid rectangle and only the strips along the
           rounded top and bottom edges go through the rounded clip (see drawChild). */
        setClipToOutline(false);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Math.min(GlassPanel.this.radius, Math.min(v.getWidth(), v.getHeight()) / 2f)); }
        });
        back = new FrameLayout(c);
        back.setClipChildren(true);
        host = new FrameLayout(c);
        host.addView(back, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(host, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        for (int i = 0; i < 2; i++) {
            layer[i] = new ImageView(c);
            layer[i].setScaleType(ImageView.ScaleType.FIT_XY);
            layer[i].setAlpha(0f);
            back.addView(layer[i], new LayoutParams(1, 1));
        }
        this.tintColor = tintColor;
        applyFilter();
        tint = new View(c);
        tint.setBackgroundColor(tintColor);
        addView(tint, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        rim = new Rim(rimWidth);
        setForeground(rim);
    }

    /** Sized by its content (tab names, buttons…) or by its layout params; the photo layers and the
     *  tint then fill exactly that (the photo layers are bigger than the pane and must not size it). */
    @Override protected void onMeasure(int ws, int hs) {
        int w = 0, h = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch == host || ch == tint || ch.getVisibility() == GONE) continue;
            measureChildWithMargins(ch, ws, 0, hs, 0);
            LayoutParams lp = (LayoutParams) ch.getLayoutParams();
            w = Math.max(w, ch.getMeasuredWidth() + lp.leftMargin + lp.rightMargin);
            h = Math.max(h, ch.getMeasuredHeight() + lp.topMargin + lp.bottomMargin);
        }
        int mw = resolveSize(w, ws), mh = resolveSize(h, hs);
        setMeasuredDimension(mw, mh);
        int ew = MeasureSpec.makeMeasureSpec(mw, MeasureSpec.EXACTLY), eh = MeasureSpec.makeMeasureSpec(mh, MeasureSpec.EXACTLY);
        host.measure(ew, eh);
        tint.measure(ew, eh);
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch == host || ch == tint || ch.getVisibility() == GONE) continue;
            LayoutParams lp = (LayoutParams) ch.getLayoutParams();
            if (lp.width == LayoutParams.MATCH_PARENT || lp.height == LayoutParams.MATCH_PARENT)
                ch.measure(lp.width == LayoutParams.MATCH_PARENT ? ew : MeasureSpec.makeMeasureSpec(ch.getMeasuredWidth(), MeasureSpec.EXACTLY),
                           lp.height == LayoutParams.MATCH_PARENT ? eh : MeasureSpec.makeMeasureSpec(ch.getMeasuredHeight(), MeasureSpec.EXACTLY));
        }
    }

    private final android.graphics.Path rr = new android.graphics.Path();
    private int rrW, rrH;
    private View rectClipChild;
    /** a child that's only clipped to the pane's box, not its rounded corners (the app grid,
     *  whose tiles sit inside the corners anyway, and are then drawn as solid pictures) */
    void setRectClipChild(View v) { rectClipChild = v; }
    private float effRadius() { return Math.min(radius, Math.min(getWidth(), getHeight()) / 2f); }
    private android.graphics.Path rrPath() {
        int w = getWidth(), h = getHeight();
        if (w != rrW || h != rrH) { rr.reset(); float r = effRadius(); rr.addRoundRect(new RectF(0, 0, w, h), r, r, android.graphics.Path.Direction.CW); rrW = w; rrH = h; }
        return rr;
    }
    @Override protected boolean drawChild(Canvas c, View ch, long t) {
        int w = getWidth(), h = getHeight();
        float r = effRadius();
        boolean more;
        if (ch == rectClipChild) {
            c.save(); c.clipRect(0, 0, w, h); more = super.drawChild(c, ch, t); c.restore();
            return more;
        }
        if (ch == host && h > 2 * r + 4) {
            c.save(); c.clipRect(0, r, w, h - r); super.drawChild(c, ch, t); c.restore();
            c.save(); c.clipRect(0, 0, w, (float) Math.ceil(r)); c.clipPath(rrPath()); super.drawChild(c, ch, t); c.restore();
            c.save(); c.clipRect(0, (float) Math.floor(h - r), w, h); c.clipPath(rrPath()); more = super.drawChild(c, ch, t); c.restore();
            return more;
        }
        c.save(); c.clipPath(rrPath()); more = super.drawChild(c, ch, t); c.restore();
        return more;
    }

    /** where the photo layer is on screen (the blurred copies are placed the same way) */
    /** a pane made later starts showing exactly what another pane shows (same picture, same place) */
    void copyGlass(GlassPanel o) {
        setPhotoBox(o.photoX, o.photoY, o.photoW, o.photoH);
        for (int i = 0; i < 2; i++) {
            layer[i].setImageDrawable(o.layer[i].getDrawable());
            layer[i].setAlpha(o.layer[i].getAlpha());
            layer[i].setScaleX(o.layer[i].getScaleX()); layer[i].setScaleY(o.layer[i].getScaleY());
            layer[i].setTranslationX(o.layer[i].getTranslationX()); layer[i].setTranslationY(o.layer[i].getTranslationY());
        }
        refreshTint();
    }

    void setPhotoBox(int x, int y, int w, int h) {
        photoX = x; photoY = y; photoW = w; photoH = h;
        syncBack();
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        syncBack();
        if (changed) { invalidateOutline(); updateShader(); }
    }

    private void syncBack() {
        if (photoW == 0) return;
        int ox = 0, oy = 0;
        View v = this;
        while (v != null && !(v instanceof NativeHome)) { ox += v.getLeft(); oy += v.getTop(); v = (View) v.getParent(); }
        for (ImageView iv : layer) {
            LayoutParams lp = (LayoutParams) iv.getLayoutParams();
            int nx = photoX - ox, ny = photoY - oy;
            if (lp.leftMargin != nx || lp.topMargin != ny || lp.width != photoW || lp.height != photoH) {
                lp.leftMargin = nx; lp.topMargin = ny; lp.width = photoW; lp.height = photoH;
                iv.setLayoutParams(lp);
            }
        }
    }

    void setLiquidAllowed(boolean on) { liquidAllowed = on; }
    /* The tint and the darkening are applied to the blurred photo as it's drawn (one colour formula
       on the graphics chip) instead of as two more see-through layers over the whole pane — the same
       look with a third of the drawing. The tint layer itself is only used when there's no photo. */
    void dimTo(float a, long ms) {
        if (dimAnim != null) dimAnim.cancel();
        if (ms <= 0 || a == dim) { dim = a; applyFilter(); return; }
        dimAnim = android.animation.ValueAnimator.ofFloat(dim, a);
        dimAnim.setDuration(ms);
        dimAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator va) { dim = (Float) va.getAnimatedValue(); applyFilter(); }
        });
        dimAnim.start();
    }

    void setTintColor(int c) { tintColor = c; if (!clear && !material()) tint.setBackgroundColor(c); applyFilter(); }

    // ================================================================== TopShelf's own looks (Settings › Home › Look)

    /* glass — the blurred photo (as before); shelf — "Backlit Shelf": solid dark with a strip of light along
       the top edge falling down the surface (warm in the evening, cool in the day); ink — "Photo Ink": a flat
       solid colour taken from the photo on screen; air — "On Air": square-cut black bars with a colour tab,
       like a broadcast's on-screen graphics; spot — "Spotlight": no pane at all, just a soft pool of shade. */
    static String look = "glass";
    static float rem = 10;
    static int inkColor = 0xFF1F2E45, airColor = 0xFFE8412F;
    static int shelfLight() { int hr = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY); return hr >= 7 && hr < 17 ? 0xFFD3E4FF : 0xFFFFC983; }
    private float baseRadius = -1;
    /* the highlight on what you're on, in each look: white (glass, Spotlight), the shelf's light (Backlit
       Shelf), the photo's pale paper colour with ink words (Photo Ink), the red bar with white words (On Air) */
    static int selFill() {
        if (look.equals("shelf")) return shelfLight();
        if (look.equals("ink")) return inkPaper();
        if (look.equals("air")) return airColor;
        return 0xFFFFFFFF;
    }
    static int selText() { return look.equals("air") ? 0xFFFFFFFF : look.equals("ink") ? (inkColor | 0xFF000000) : 0xFF111111; }
    static float selRadius(float r) { return look.equals("air") ? 0 : look.equals("ink") ? Math.min(r, .5f * rem) : r; }
    /* a button in each look (Featured's Watch now / More details, messages' buttons): glass and Spotlight keep
       the white pill; On Air is a square bar with a cut end — red with white words when you're on it, black
       otherwise; Backlit Shelf is a dark plate with its lit top edge, glowing in the shelf's light when you're on
       it; Photo Ink is printed in the photo's colour with a hard shadow, pale paper when you're on it */
    static Drawable buttonBg(boolean on, float radius, int restGlass) {
        if (look.equals("air")) return new Cut(on ? airColor : 0xEB0B0B0D);
        if (look.equals("shelf")) {
            if (on) { android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(); g.setColor(shelfLight()); g.setCornerRadius(Math.min(radius, .45f * rem)); return g; }
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(); g.setColor(0xF2151217); g.setCornerRadius(Math.min(radius, .45f * rem));
            return new android.graphics.drawable.LayerDrawable(new Drawable[] { g, new Edge(shelfLight(), Math.min(radius, .45f * rem)) });
        }
        if (look.equals("ink")) return new Print(Math.min(radius, .5f * rem), on);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(); g.setCornerRadius(radius); g.setColor(on ? 0xFFFFFFFF : restGlass);
        return g;
    }
    /* the guide's raised cell (the show / category you're on): its fill, outline and corners in each look —
       On Air's is red and square, the shelf's warm dark with an outline of its light, Photo Ink's the photo's
       colour with a pale outline, glass and Spotlight as before */
    static int raiseFill() { return look.equals("air") ? airColor : look.equals("shelf") ? 0xFF241D20 : look.equals("ink") ? (inkColor | 0xFF000000) : 0xFF34333D; }
    static int raiseRing() { return look.equals("air") ? Ui.WHITE_95 : look.equals("shelf") ? shelfLight() : look.equals("ink") ? inkPaper() : Ui.WHITE_85; }
    static float corner(float r) { return look.equals("air") ? 0 : look.equals("shelf") || look.equals("ink") ? Math.min(r, .5f * rem) : r; }
    static int buttonText(boolean on) {
        if (look.equals("air")) return 0xFFFFFFFF;
        if (look.equals("shelf")) return on ? 0xFF1A1410 : 0xFFFFFFFF;
        if (look.equals("ink")) return on ? (inkColor | 0xFF000000) : 0xFFFFFFFF;
        return on ? 0xFF111111 : 0xFFFFFFFF;
    }
    /** On Air: a bar with its right end cut on a slant */
    static final class Cut extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        Cut(int color) { p.setColor(color); }
        @Override protected void onBoundsChange(android.graphics.Rect b) {
            float k = Math.min(b.height() * .4f, 1.1f * rem);
            path.reset(); path.moveTo(b.left, b.top); path.lineTo(b.right, b.top); path.lineTo(b.right - k, b.bottom); path.lineTo(b.left, b.bottom); path.close();
        }
        @Override public void draw(Canvas c) { c.drawPath(path, p); }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
    static int inkPaper() { float[] h = new float[3]; android.graphics.Color.colorToHSV(inkColor, h); return android.graphics.Color.HSVToColor(new float[] { h[0], .14f, .97f }); }
    static int inkShadow() { float[] h = new float[3]; android.graphics.Color.colorToHSV(inkColor, h); return android.graphics.Color.HSVToColor(new float[] { h[0], Math.min(1f, h[1] + .15f), .11f }); }
    boolean material() { return !bare && !look.equals("glass"); }
    /** puts this pane in the current look (and back to glass) */
    void applyLook() {
        if (bare) return;
        if (baseRadius < 0) baseRadius = radius;
        boolean m = material();
        float r = !m ? baseRadius : look.equals("air") ? 0 : look.equals("shelf") ? Math.min(baseRadius, .55f * rem) : look.equals("ink") ? Math.min(baseRadius, .9f * rem) : baseRadius;
        if (r != radius) { radius = r; rrW = -1; invalidateOutline(); rim.setRadius(r); }
        if (!m) {
            setBackground(null);
            host.setVisibility(VISIBLE);
            tint.setBackgroundColor(tintColor);
            if (getForeground() == null && baseRadius > 0) setForeground(rim);
            refreshTint();
            return;
        }
        /* the look is the pane's own background (drawn by Android with its rounded corners, which is quick),
           not a layer clipped to the rounded shape */
        host.setVisibility(GONE);
        if (!clear) { setBackground(lookBg()); tint.setVisibility(GONE); }
        setForeground(look.equals("shelf") ? new Edge(shelfLight(), radius) : look.equals("air") ? new Tab(airColor) : look.equals("spot") ? new Beam() : null);
    }
    private Drawable lookBg() {
        if (look.equals("spot")) return new Pool();
        if (look.equals("ink")) return new Print(radius);
        int c = look.equals("shelf") ? 0xF7151217 : look.equals("air") ? 0xF00B0B0D : (inkColor & 0x00FFFFFF) | 0xF5000000;
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(c); g.setCornerRadius(radius);
        return g;
    }
    /** a new photo's ink (Photo Ink): every pane takes it on */
    void inkChanged() { if (material() && look.equals("ink") && !clear) setBackground(lookBg()); }

    /** Backlit Shelf: the strip of light along the top edge, and its glow falling down the pane */
    static final class Edge extends Drawable {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(), ledge = new Paint();
        private final int color; private final float r;
        private final android.graphics.Path clip = new android.graphics.Path();
        Edge(int color, float radius) { this.color = color; r = radius; line.setColor(color); }
        @Override protected void onBoundsChange(android.graphics.Rect b) {
            float gh = Math.min(b.height() * .6f, 3.2f * rem);
            glow.setShader(new LinearGradient(0, b.top, 0, b.top + gh, new int[] { (color & 0x00FFFFFF) | Ui.BLACK_55, (color & 0x00FFFFFF) | 0x29000000, color & 0x00FFFFFF }, new float[] { 0, .35f, 1 }, Shader.TileMode.CLAMP));
            ledge.setShader(new LinearGradient(b.left, 0, b.right, 0, new int[] { color & 0x00FFFFFF, (color & 0x00FFFFFF) | Ui.BLACK_55, color & 0x00FFFFFF }, null, Shader.TileMode.CLAMP));
            clip.reset(); clip.addRoundRect(new RectF(b), r, r, android.graphics.Path.Direction.CW);
        }
        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float gh = Math.min(b.height() * .7f, 4.6f * rem), lw = Math.max(3f, .24f * rem);
            c.save();
            if (r > 0) c.clipPath(clip);
            c.drawRect(b.left, b.top, b.right, b.top + gh, glow);
            c.drawRect(b.left, b.top, b.right, b.top + lw, line);
            // the ledge: light catching the bottom edge
            c.drawRect(b.left, b.bottom - Math.max(2f, .12f * rem), b.right, b.bottom, ledge);
            c.restore();
        }
        @Override public void setAlpha(int a) { line.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
    /** Photo Ink: the pane printed in the photo's colour, with a hard offset shadow in its darkest shade
        (like a screen-printed poster); the face sits up and to the left inside the pane's box */
    static final class Print extends Drawable {
        private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG), shadow = new Paint(Paint.ANTI_ALIAS_FLAG), key = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float r; private final RectF a = new RectF();
        Print(float radius) { this(radius, false); }
        Print(float radius, boolean paper) {
            r = radius;
            face.setColor(paper ? inkPaper() : inkColor | 0xFF000000); shadow.setColor(inkShadow());
            key.setStyle(Paint.Style.STROKE); key.setStrokeWidth(Math.max(2f, .12f * rem)); key.setColor((inkPaper() & 0x00FFFFFF) | 0x59000000);
        }
        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float o = Math.min(.5f * rem, Math.min(b.width(), b.height()) * .08f);
            a.set(b.left + o, b.top + o, b.right, b.bottom); c.drawRoundRect(a, r, r, shadow);
            a.set(b.left, b.top, b.right - o, b.bottom - o); c.drawRoundRect(a, r, r, face);
            float k = key.getStrokeWidth() / 2f; a.inset(k, k); c.drawRoundRect(a, Math.max(0, r - k), Math.max(0, r - k), key);
        }
        @Override public void setAlpha(int al) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
    /** Spotlight: light falling on the pane from above — a bright soft patch at its top middle */
    static final class Beam extends Drawable {
        private final Paint p = new Paint();
        @Override protected void onBoundsChange(android.graphics.Rect b) {
            if (b.width() <= 0 || b.height() <= 0) return;
            android.graphics.RadialGradient g = new android.graphics.RadialGradient(0, 0, 1f, new int[] { 0x3DFFF6E8, 0x14FFF6E8, 0x00FFF6E8 }, new float[] { 0, .5f, 1 }, Shader.TileMode.CLAMP);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(Math.min(b.width() * .5f, 30 * rem), Math.min(b.height() * .9f, 16 * rem)); m.postTranslate(b.exactCenterX(), b.top);
            g.setLocalMatrix(m); p.setShader(g);
        }
        @Override public void draw(Canvas c) { c.drawRect(getBounds(), p); }
        @Override public void setAlpha(int al) { p.setAlpha(al); }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
    /** On Air: the colour tab down the left edge */
    static final class Tab extends Drawable {
        private final Paint p = new Paint();
        Tab(int color) { p.setColor(color); }
        @Override public void draw(Canvas c) { android.graphics.Rect b = getBounds(); c.drawRect(b.left, b.top, b.left + Math.max(3f, .42f * rem), b.bottom, p); }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
    /** Spotlight: a soft pool of shade under what's on the pane, fading out toward its corners
        (a pane that covers most of the screen, like Settings, is evenly shaded so its edges stay readable) */
    static final class Pool extends Drawable {
        private final Paint p = new Paint();
        @Override protected void onBoundsChange(android.graphics.Rect b) {
            if (b.width() <= 0 || b.height() <= 0) return;
            if (b.width() > 70 * rem && b.height() > 40 * rem) { p.setShader(null); p.setColor(0xC8060810); return; }
            android.graphics.RadialGradient g = new android.graphics.RadialGradient(0, 0, 1f, new int[] { 0xD6060810, 0xA8060810, 0x33060810 }, new float[] { 0, .55f, 1 }, Shader.TileMode.CLAMP);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(b.width() * .72f, b.height() * .72f); m.postTranslate(b.exactCenterX(), b.exactCenterY());
            g.setLocalMatrix(m);
            p.setShader(g);
        }
        @Override public void draw(Canvas c) { c.drawRect(getBounds(), p); }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /* See-through: over a moving picture (a trailer playing behind the title) the blurred copy of the
       still photo would cover it like a solid board, so the pane is then just a light dark tint
       that lets the video show through, and goes back to glass after. */
    private boolean clear;
    static final int CLEAR_TINT = 0x52000000;
    void setClear(final boolean on, long ms) {
        if (bare || on == clear) return;
        clear = on;
        host.animate().cancel(); tint.animate().cancel();
        if (on) {
            if (material()) setBackground(null);
            tint.setBackgroundColor(CLEAR_TINT);
            if (tint.getVisibility() != VISIBLE) { tint.setAlpha(0f); tint.setVisibility(VISIBLE); }
            tint.animate().alpha(1f).setDuration(ms).start();
            host.animate().alpha(0f).setDuration(ms).start();
        } else {
            host.animate().alpha(1f).setDuration(ms).start();
            if (material()) { tint.animate().cancel(); tint.setVisibility(GONE); tint.setAlpha(1f); setBackground(lookBg()); return; }
            tint.animate().alpha(0f).setDuration(ms).withEndAction(new Runnable() { @Override public void run() {
                if (clear) return;
                tint.setBackgroundColor(tintColor); tint.setAlpha(1f); refreshTint();
            } }).start();
        }
    }
    boolean isClear() { return clear; }
    /* No glass at all: the pane keeps its place and its children (a top bar drawn as plain words on the
       photo) but draws no blurred photo, tint or rim. */
    boolean bare;
    void setBare() { bare = true; host.setVisibility(GONE); tint.setVisibility(GONE); setForeground(null); setBackground(null); }
    int tintNow() { return tintColor; }

    /** the tint as its own layer only while no blurred photo is showing (or in solid mode) */
    void refreshTint() {
        if (clear || bare) return;
        if (material()) { tint.setVisibility(GONE); return; }
        boolean photo = back.getVisibility() == VISIBLE && (layer[0].getDrawable() != null || layer[1].getDrawable() != null);
        tint.setVisibility(photo ? GONE : VISIBLE);
    }

    /** nothing behind shows through: the blurred photo is up and fully drawn */
    boolean isOpaqueGlass() {
        return !clear && !bare && !material() && getVisibility() == VISIBLE && getAlpha() >= 1f && back.getVisibility() == VISIBLE && tint.getVisibility() == GONE
                && (layer[0].getDrawable() != null && layer[0].getAlpha() >= 1f || layer[1].getDrawable() != null && layer[1].getAlpha() >= 1f);
    }

    private void applyFilter() {
        float a = ((tintColor >>> 24) & 255) / 255f;
        float s = (1 - dim) * (1 - a);
        float r = ((tintColor >> 16) & 255) * a, g = ((tintColor >> 8) & 255) * a, b = (tintColor & 255) * a;
        android.graphics.ColorMatrixColorFilter f = new android.graphics.ColorMatrixColorFilter(new float[] {
            s, 0, 0, 0, r,
            0, s, 0, 0, g,
            0, 0, s, 0, b,
            0, 0, 0, 1, 0 });
        for (ImageView iv : layer) iv.setColorFilter(f);
    }

    void setLiquid(boolean on) {
        liquid = on && Build.VERSION.SDK_INT >= 33;
        updateShader();
    }

    private static final String LENS =
        "uniform shader content; uniform float2 size; uniform float radius; uniform float bezel; uniform float strength;\n" +
        "float sdf(float2 p) { float2 c = size * 0.5; float2 q = abs(p - c) - (c - float2(radius)); return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius; }\n" +
        "half4 main(float2 p) {\n" +
        "  float d = -sdf(p); float t = clamp(1.0 - d / bezel, 0.0, 1.0);\n" +
        "  float2 n = float2(sdf(p + float2(1.0, 0.0)) - sdf(p - float2(1.0, 0.0)), sdf(p + float2(0.0, 1.0)) - sdf(p - float2(0.0, 1.0)));\n" +
        "  float l = length(n); n = l > 0.0001 ? n / l : float2(0.0);\n" +
        "  return content.eval(p - n * strength * t * t);\n" +
        "}\n";

    private void updateShader() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (!liquid || getWidth() == 0) { back.setRenderEffect(null); host.setLayerType(LAYER_TYPE_NONE, null); return; }
        try {
            android.graphics.RuntimeShader s = shader instanceof android.graphics.RuntimeShader ? (android.graphics.RuntimeShader) shader : new android.graphics.RuntimeShader(LENS);
            shader = s;
            float w = getWidth(), h = getHeight();
            s.setFloatUniform("size", w, h);
            s.setFloatUniform("radius", Math.min(radius, Math.min(w, h) / 2f));
            s.setFloatUniform("bezel", Math.min(radius * 1.2f, Math.min(h * 0.45f, radius * 3f)));
            s.setFloatUniform("strength", radius * 0.5f);
            back.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(s, "content"));
            if (host.getLayerType() != LAYER_TYPE_HARDWARE) host.setLayerType(LAYER_TYPE_HARDWARE, null);
        } catch (Throwable t) { liquid = false; back.setRenderEffect(null); host.setLayerType(LAYER_TYPE_NONE, null); }
    }

    /** The bright rim of light around the edge (white at the top-left, fading, catching again at the bottom-right). */
    static final class Rim extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float w;
        private float angle = 120f, radius;
        Rim(float width) { w = width; p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(width); }
        void setAngle(float a) { angle = a; shaderFor(getBounds()); invalidateSelf(); }
        void setRadius(float r) { radius = r; invalidateSelf(); }
        @Override protected void onBoundsChange(android.graphics.Rect b) { shaderFor(b); }
        private void shaderFor(android.graphics.Rect b) {
            if (b.width() == 0) return;
            double a = Math.toRadians(angle);
            float dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
            float len = Math.abs(b.width() * dx) + Math.abs(b.height() * dy);
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            p.setShader(new LinearGradient(cx - dx * len / 2, cy - dy * len / 2, cx + dx * len / 2, cy + dy * len / 2,
                    new int[] { Ui.WHITE_85, 0x29FFFFFF, 0x00FFFFFF, Ui.WHITE_7, Ui.WHITE_50 },
                    new float[] { 0f, 0.2f, 0.42f, 0.68f, 1f }, Shader.TileMode.CLAMP));
        }
        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float h = w / 2f;
            float r = radius > 0 ? Math.min(radius, Math.min(b.width(), b.height()) / 2f) : Math.min(b.width(), b.height()) / 2f;
            box.set(b.left + h, b.top + h, b.right - h, b.bottom - h);
            c.drawRoundRect(box, r - h, r - h, p);
        }
        private final RectF box = new RectF();       // (one, reused: every glass pane draws its rim each frame)
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    void setBitmaps(Bitmap a, int which) {
        layer[which].setImageBitmap(a);
    }
}
