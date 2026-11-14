package com.topshelf.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * Placeholders: where something is on its way, grey shapes the size of what's coming, breathing slowly (as the
 * standings tables always did), instead of a line saying "Loading…". For a screen that draws itself: block()
 * with tone() as the colour, and ask for the next frame while it loads. For a row of views: a Skel of n cards.
 */
final class Skel extends View {
    /** the placeholder's colour just now: a faint white whose strength rises and falls every couple of seconds */
    static int tone() {
        float k = (float) (.5 + .5 * Math.sin(SystemClock.uptimeMillis() / 380.0));
        return ((int) (16 + 16 * k) << 24) | 0xFFFFFF;
    }
    /** one placeholder shape (its corners the look's) */
    static void block(Canvas c, Paint p, RectF r, float x, float y, float w, float hh, float rad) {
        r.set(x, y, x + w, y + hh);
        float q = GlassPanel.corner(rad);
        c.drawRoundRect(r, q, q, p);
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final int n; private final float cw, ch, gap, rad, R; private final boolean lines;
    /** n cards cw × ch (px) in a row; with `lines`, two short bars under each (a name and a detail) */
    Skel(Context c, int n, float cw, float ch, float gap, float rad, float R, boolean lines) {
        super(c); this.n = n; this.cw = cw; this.ch = ch; this.gap = gap; this.rad = rad; this.R = R; this.lines = lines;
    }
    int wide() { return Math.round(n * (cw + gap)); }
    int tall() { return Math.round(ch + (lines ? 3.6f * R : 0) + 2); }
    @Override protected void onDraw(Canvas c) {
        p.setColor(tone());
        for (int i = 0; i < n; i++) {
            float x = i * (cw + gap);
            block(c, p, r, x, 0, cw, ch, rad);
            if (lines) { block(c, p, r, x, ch + 1.1f * R, cw * .7f, 1.1f * R, .4f * R); block(c, p, r, x, ch + 2.7f * R, cw * .45f, .9f * R, .4f * R); }
        }
        if (isShown()) postInvalidateOnAnimation();
    }
}
