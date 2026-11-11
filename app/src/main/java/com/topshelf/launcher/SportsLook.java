package com.topshelf.launcher;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.view.View;

/**
 * What every sports screen shares, so they read as one thing: the Sports tab, a game's page, Standings, the
 * stats panel over a game and the game alerts.
 *
 *  - the colours (one ground, one card, one edge; five strengths of text; gold for "yours / still to come",
 *    orange for live, green and red for good and bad);
 *  - the two condensed typefaces and the sizes the small labels and the controls are set in;
 *  - one control (a tab, a league, a view, a button on a game's page): the same shape, the same "chosen"
 *    fill (the look's own highlight, as on the rest of TopShelf) and the same edge when it isn't;
 *  - one way of showing where the remote is: a white ring with a soft glow round it, in the team's colour
 *    on a game or a team and in gold on a control, which glides from one thing to the next ({@link Focus}).
 */
final class SportsLook {
    private SportsLook() { }

    // ------------------------------------------------------------------ colours
    static final int GROUND = 0xFF0A0E13, CARD = 0xFF131A23, EDGE = 0xFF222C38, TRACK = 0xFF161E28, CHIP_EDGE = 0xFF2A3442;
    /** text, strongest to faintest */
    static final int INK = 0xFFF2F5F8, SOFT = 0xFFDCE3EA, SUB = 0xFFAEB8C4, TER = 0xFF8E99A8, FAINT = 0xFF6B7787;
    static final int GOLD = 0xFFFFC23D, LIVE = 0xFFFF7A59, ON_LIVE = 0xFF160400, GREEN = 0xFF3DDC84, RED = 0xFFFF6B6B;
    /** the ring round what the remote is on */
    static final int RING = Ui.WHITE_95;
    static final float RING_W = .16f;

    // ------------------------------------------------------------------ type
    /** small capital labels, a line of detail, a control's words (rem) */
    static final float T_LABEL = 1.15f, T_BODY = 1.3f, T_CTRL = 1.3f;
    private static Typeface cond, heavy;
    static Typeface cond() { if (cond == null) cond = Typeface.create("sans-serif-condensed", Typeface.BOLD); return cond; }
    static Typeface heavy() {
        if (heavy == null) heavy = Build.VERSION.SDK_INT >= 28 ? Typeface.create(Typeface.create("sans-serif-condensed", Typeface.NORMAL), 800, false) : cond();
        return heavy;
    }

    // ------------------------------------------------------------------ a control
    /** a control's height (rem) and the room either side of its words */
    static final float CTRL_H = 2.9f, CTRL_PAD = 1.4f;
    static float ctrlRadius(float hPx) { return GlassPanel.corner(hPx / 2); }
    /** the colour of a control's words */
    static int ctrlText(boolean chosen) {
        if (!chosen) return SOFT;
        return GlassPanel.look.equals("glass") || GlassPanel.look.equals("spot") ? GROUND : GlassPanel.selText();
    }
    /** a control's fill and edge (its words and the focus ring are the caller's) */
    static void ctrl(Canvas c, Paint p, RectF r, float R, boolean chosen) {
        float rad = ctrlRadius(r.height());
        p.setStyle(Paint.Style.FILL);
        p.setColor(chosen ? GlassPanel.selFill() : Ui.WHITE_7);
        c.drawRoundRect(r, rad, rad, p);
        if (!chosen) {
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1, .08f * R)); p.setColor(CHIP_EDGE);
            c.drawRoundRect(r, rad, rad, p);
            p.setStyle(Paint.Style.FILL);
        }
    }

    // ------------------------------------------------------------------ the glow
    /* One small soft-edged picture per corner size, stretched to whatever it goes round (its corners kept
       as they are, its sides stretched): a glow of any size costs nothing to make or to move. */
    private static final java.util.Map<Integer, Bitmap> glows = new java.util.HashMap<>();
    private static final Rect src = new Rect();
    private static final RectF dst = new RectF();
    /** how far the glow reaches (rem): round a game or a team, and round a control */
    static final float GLOW_BIG = 1.5f, GLOW_CTRL = .8f;
    /** a soft glow round r in a colour; strength 0–1 */
    static void glow(Canvas c, Paint p, RectF r, float rad, float R, int col, float strength, float reach) {
        int blur = Math.max(2, Math.round(reach * R)), m = blur * 2, k = Math.max(1, Math.round(rad));
        k = Math.min(k, (int) Math.floor(Math.min(r.width(), r.height()) / 2f));
        if (k < 1) return;
        int key = k * 4096 + blur;
        Bitmap b = glows.get(key);
        if (b == null) {
            int size = 2 * (m + k) + 2;
            b = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8);
            Canvas bc = new Canvas(b);
            Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
            bp.setColor(Color.WHITE);
            bp.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.OUTER));
            bc.drawRoundRect(new RectF(m, m, size - m, size - m), k, k, bp);
            if (glows.size() > 12) glows.clear();
            glows.put(key, b);
        }
        int e = m + k, n = b.getWidth();
        int old = p.getColor(); Paint.Style os = p.getStyle();
        p.setStyle(Paint.Style.FILL);
        p.setColor((col & 0x00FFFFFF) | (Math.round(255 * Math.max(0, Math.min(1, strength))) << 24));
        float l = r.left - m, t = r.top - m, rt = r.right + m, bt = r.bottom + m;
        float[] xs = { l, l + e, rt - e, rt }, ys = { t, t + e, bt - e, bt };
        int[] sx = { 0, e, n - e, n };
        for (int iy = 0; iy < 3; iy++) for (int ix = 0; ix < 3; ix++) {
            if (ix == 1 && iy == 1) continue;              // (the middle is empty: the glow is only outside)
            if (xs[ix + 1] <= xs[ix] || ys[iy + 1] <= ys[iy]) continue;
            src.set(sx[ix], sx[iy], sx[ix + 1], sx[iy + 1]);
            dst.set(xs[ix], ys[iy], xs[ix + 1], ys[iy + 1]);
            c.drawBitmap(b, src, dst, p);
        }
        p.setColor(old); p.setStyle(os);
    }
    /** the ring */
    static void ring(Canvas c, Paint p, RectF r, float rad, float R, int alpha) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2, RING_W * R));
        p.setColor((RING & 0x00FFFFFF) | ((Color.alpha(RING) * Math.max(0, Math.min(255, alpha)) / 255) << 24));
        c.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.FILL);
    }
    /** a team colour made bright enough to glow on the dark ground */
    static int glowColor(int col) {
        float[] hsv = new float[3];
        Color.colorToHSV(col | 0xFF000000, hsv);
        if (hsv[1] < .12f) return 0xFFE9EEF3;              // (black, white and grey teams: a white glow)
        hsv[2] = Math.max(hsv[2], .85f); hsv[1] = Math.min(hsv[1], .85f);
        return Color.HSVToColor(hsv);
    }

    /**
     * Where the remote is, on a screen made of views: laid over them, it draws the ring and the glow and
     * glides from one thing to the next (touches nothing, takes no keys).
     */
    static final class Focus extends View {
        private final float R;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF cur = new RectF(), from = new RectF(), to = new RectF();
        private float rad, radFrom, radTo, show, showTo, reach = GLOW_BIG;
        private int col = GOLD, colFrom = GOLD, colTo = GOLD;
        private ValueAnimator an;
        Focus(android.content.Context c, float rem) { super(c); R = rem; setWillNotDraw(false); }

        /** goes to r (this view's own coordinates), with that corner and colour */
        void to(RectF r, float radius, int colour, boolean control, boolean smooth) {
            reach = control ? GLOW_CTRL : GLOW_BIG;
            boolean was = showTo > 0 && show > .05f;
            if (an != null) an.cancel();
            to.set(r); radTo = radius; colTo = colour; showTo = 1;
            if (!was || !smooth) { cur.set(r); rad = radius; col = colour; }
            from.set(cur); radFrom = rad; colFrom = col;
            final float s0 = smooth ? show : 1;
            if (!smooth) { show = 1; invalidate(); return; }
            run(s0, 1, 260);
        }
        /** nothing here has the remote */
        void off(boolean smooth) {
            if (showTo == 0 && show == 0) return;
            if (an != null) an.cancel();
            showTo = 0;
            from.set(cur); to.set(cur); radFrom = radTo = rad; colFrom = colTo = col;
            if (!smooth) { show = 0; invalidate(); return; }
            run(show, 0, 160);
        }
        private void run(final float s0, final float s1, int ms) {
            an = ValueAnimator.ofFloat(0, 1);
            an.setDuration(ms); an.setInterpolator(TileView.EASE);
            an.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { @Override public void onAnimationUpdate(ValueAnimator a) {
                float f = (Float) a.getAnimatedValue();
                cur.set(from.left + (to.left - from.left) * f, from.top + (to.top - from.top) * f, from.right + (to.right - from.right) * f, from.bottom + (to.bottom - from.bottom) * f);
                rad = radFrom + (radTo - radFrom) * f;
                col = mix(colFrom, colTo, f);
                show = s0 + (s1 - s0) * f;
                invalidate();
            } });
            an.start();
        }
        private static int mix(int a, int b, float f) {
            return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * f), Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * f),
                    Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f));
        }
        @Override protected void onDraw(Canvas c) {
            if (show <= .01f || cur.isEmpty()) return;
            glow(c, p, cur, rad, R, col, .8f * show, reach);
            ring(c, p, cur, rad, R, Math.round(255 * show));
        }
        @Override public boolean hasOverlappingRendering() { return false; }
    }
}
