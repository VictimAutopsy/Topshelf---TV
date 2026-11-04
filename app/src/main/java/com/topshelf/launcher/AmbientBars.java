package com.topshelf.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.TextureView;
import android.view.View;

/**
 * Ambient light over a trailer's black bars (Featured). Many trailers are widescreen films inside a 16:9 video,
 * so black bands sit above and below the picture. Five times a second a tiny copy of the frame (32×72) is read;
 * rows that stay black across a couple of seconds are the bars (a dark scene isn't mistaken for them: the bars
 * must be there in every frame, top and bottom alike). Over each bar, the colours of the picture's nearest edge
 * spill outwards, fading to dark towards the screen's edge — like light from the picture — and follow the
 * film as it changes, easing from one to the next.
 */
final class AmbientBars extends View {
    interface Source { TextureView texture(); }
    private static final int SW = 32, SH = 270, COLS = 16;      // (a sample row is ~4 px of a 1080p picture)
    private Source src;
    private final Bitmap sample = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888);
    private final int[] px = new int[SW * SH];
    private final int[] hist = new int[20]; private int histN, histI;       // recent {top, bottom} bar rows, in pairs
    private float top, bot, topT, botT;                                       // bars as fractions of the height (shown, wanted)
    private final float[] cTop = new float[COLS * 3], cBot = new float[COLS * 3], cTopT = new float[COLS * 3], cBotT = new float[COLS * 3];
    private final int[] gTop = new int[COLS], gBot = new int[COLS];
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), shade = new Paint();
    private final Matrix m = new Matrix();
    private final Paint fade = new Paint();
    { fade.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)); }
    private boolean on, logged;

    AmbientBars(Context c) { super(c); setWillNotDraw(false); }

    void start(Source s) {
        src = s; on = true; histN = 0; histI = 0; topT = botT = 0; logged = false;
        removeCallbacks(tick); post(tick);
    }
    void stop() {
        on = false; src = null; removeCallbacks(tick);
        topT = botT = 0; top = bot = 0; invalidate();
    }

    private final Runnable tick = new Runnable() { @Override public void run() {
        if (!on) return;
        read();
        postDelayed(this, 200);
    } };

    private void read() {
        TextureView tv = src == null ? null : src.texture();
        if (tv == null || !tv.isAvailable()) return;
        try { tv.getBitmap(sample); } catch (Throwable t) { return; }
        sample.getPixels(px, 0, SW, 0, 0, SW, SH);
        // the black rows at the top and the bottom (a row is black when even its brightest point is dark)
        int t = 0, b = 0;
        while (t < SH && rowMax(t) < 26) t++;
        if (t >= SH - 8) return;                                   // (a black frame: says nothing)
        while (b < SH && rowMax(SH - 1 - b) < 26) b++;
        hist[histI * 2] = t; hist[histI * 2 + 1] = b; histI = (histI + 1) % (hist.length / 2); histN = Math.min(histN + 1, hist.length / 2);
        // the bars are what every recent frame had (at least ~4% each, about the same top and bottom)
        int mt = SH, mb = SH;
        for (int i = 0; i < histN; i++) { mt = Math.min(mt, hist[i * 2]); mb = Math.min(mb, hist[i * 2 + 1]); }
        boolean bars = histN >= 10 && mt >= 8 && mb >= 8 && Math.abs(mt - mb) <= 8;
        float nt = bars ? mt / (float) SH : 0, nb = bars ? mb / (float) SH : 0;
        // into the screen's terms (the picture may be scaled to fill: the same scaling as the video)
        if (bars) {
            tv.getTransform(m);
            float[] pts = { 0, nt * tv.getHeight(), 0, (1 - nb) * tv.getHeight() };
            m.mapPoints(pts);
            nt = Math.max(0, pts[1] / getHeight()); nb = Math.max(0, 1 - pts[3] / getHeight());
            if (!logged) { logged = true; Diag.ev("featured: trailer has black bars (" + Math.round(nt * 100) + "% top, " + Math.round(nb * 100) + "% bottom) · ambient light on"); }
        }
        topT = nt; botT = nb;
        // the colours along the picture's top and bottom edges (the six rows inside each edge, averaged)
        edge(mt + 3, mt + 22, cTopT);
        edge(SH - mb - 22, SH - mb - 3, cBotT);
        invalidate();
    }
    private int rowMax(int r) {
        int mx = 0;
        for (int x = 0; x < SW; x++) { int c = px[r * SW + x]; int l = (((c >> 16) & 255) * 3 + ((c >> 8) & 255) * 6 + (c & 255)) / 10; if (l > mx) mx = l; }
        return mx;
    }
    private void edge(int r0, int r1, float[] out) {
        r0 = Math.max(0, r0); r1 = Math.min(SH, Math.max(r0 + 1, r1));
        for (int k = 0; k < COLS; k++) {
            int x0 = k * SW / COLS, x1 = (k + 1) * SW / COLS; float r = 0, g = 0, b = 0; int n = 0;
            for (int y = r0; y < r1; y++) for (int x = x0; x < x1; x++) { int c = px[y * SW + x]; r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; n++; }
            n = Math.max(1, n);
            out[k * 3] = r / n; out[k * 3 + 1] = g / n; out[k * 3 + 2] = b / n;
        }
    }

    @Override protected void onDraw(Canvas c) {
        boolean moving = false;
        float k = .12f;
        if (Math.abs(top - topT) > .001f) { top += (topT - top) * k; moving = true; } else top = topT;
        if (Math.abs(bot - botT) > .001f) { bot += (botT - bot) * k; moving = true; } else bot = botT;
        for (int i = 0; i < COLS * 3; i++) {
            if (Math.abs(cTop[i] - cTopT[i]) > .5f) { cTop[i] += (cTopT[i] - cTop[i]) * .1f; moving = true; } else cTop[i] = cTopT[i];
            if (Math.abs(cBot[i] - cBotT[i]) > .5f) { cBot[i] += (cBotT[i] - cBot[i]) * .1f; moving = true; } else cBot[i] = cBotT[i];
        }
        int w = getWidth(), h = getHeight();
        float th = top * h, bh = bot * h;
        float over = h * .012f;          // (~13 px at 1080p: the bar's edge, blended into the picture)
        if (th > 1) band(c, w, 0, th, cTop, gTop, true, over);
        if (bh > 1) band(c, w, h - bh, h, cBot, gBot, false, over);
        if (moving) postInvalidateOnAnimation();
    }
    /** one bar: the edge's colours across it, fading to dark away from the picture */
    private void band(Canvas c, int w, float y0, float y1, float[] col, int[] g, boolean above, float over) {
        for (int i = 0; i < COLS; i++) {
            // (a little lifted and softened: light, not a copy of the picture)
            float r = col[i * 3], gg = col[i * 3 + 1], b = col[i * 3 + 2];
            g[i] = 0xFF000000 | (clamp(r * .85f + 8) << 16) | (clamp(gg * .85f + 8) << 8) | clamp(b * .85f + 8);
        }
        p.setShader(new LinearGradient(0, 0, w, 0, g, null, Shader.TileMode.CLAMP));
        c.drawRect(0, y0, w, y1, p);
        int near = 0x14000000, far = 0xF2000000;
        shade.setShader(new LinearGradient(0, above ? y1 : y0, 0, above ? y0 : y1, near, far, Shader.TileMode.CLAMP));
        c.drawRect(0, y0, w, y1, shade);
        shade.setShader(null);
        // past the bar: the same light, fading out over the edge of the picture (no hard line where they meet)
        float e0 = above ? y1 : y0 - over, e1 = above ? y1 + over : y0;
        c.saveLayerAlpha(0, e0, w, e1, 255);
        c.drawRect(0, e0, w, e1, p);
        fade.setShader(new LinearGradient(0, above ? y1 : y0, 0, above ? y1 + over : y0 - over, 0xEB000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, e0, w, e1, fade);
        c.restore();
        p.setShader(null);
    }
    private static int clamp(float v) { return Math.max(0, Math.min(255, Math.round(v))); }
}
