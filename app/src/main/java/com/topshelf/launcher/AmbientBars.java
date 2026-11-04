package com.topshelf.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.TextureView;
import android.view.View;

/**
 * Ambient light over a trailer's black bars (Featured), the way a Philips Hue or Govee TV light strip works.
 * Many trailers are widescreen films inside a 16:9 video, so black bands sit above and below the picture.
 * About 12 times a second a tiny copy of the frame (40×270) is read; rows that stay black across a couple of
 * seconds are the bars (a dark scene isn't mistaken for them: the bars must be in every frame, top and bottom
 * alike). Along each edge of the picture sit ten "lights", one per zone: each takes the colour of the picture's
 * outermost rows in its zone (a little richer, as the light strips make it) and glows out into the bar as a soft
 * pool of light, fading towards the screen's edge and blending into its neighbours. They follow the film closely:
 * a quick ease from frame to frame, an instant change at a cut.
 */
final class AmbientBars extends View {
    interface Source { TextureView texture(); }
    private static final int SW = 40, SH = 270, ZONES = 10;     // (a sample row is ~4 px of a 1080p picture)
    private Source src;
    private final Bitmap sample = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888);
    private final int[] px = new int[SW * SH];
    private final int[] hist = new int[48]; private int histN, histI;       // recent {top, bottom} bar rows, in pairs (~2 s)
    private float top, bot, topT, botT;                                       // bars as fractions of the height (shown, wanted)
    private final float[] cTop = new float[ZONES * 3], cBot = new float[ZONES * 3], cTopT = new float[ZONES * 3], cBotT = new float[ZONES * 3];
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG), mask = new Paint(), black = new Paint();
    private final Matrix m = new Matrix();
    private final float[] hsv = new float[3];
    private boolean on, logged;

    AmbientBars(Context c) {
        super(c); setWillNotDraw(false);
        glow.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));      // (overlapping lights mix as light does)
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        black.setColor(Color.BLACK);
    }

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
        postDelayed(this, 80);          // (about 12 a second: the light keeps up with the film)
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
        // the bars are what every recent frame had (at least ~3% each, about the same top and bottom)
        int mt = SH, mb = SH;
        for (int i = 0; i < histN; i++) { mt = Math.min(mt, hist[i * 2]); mb = Math.min(mb, hist[i * 2 + 1]); }
        boolean bars = histN >= hist.length / 2 && mt >= 8 && mb >= 8 && Math.abs(mt - mb) <= 8;
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
        // each zone's colour: the picture's outermost rows there (past the soft line where it meets the bar)
        edge(mt + 2, mt + 6, cTopT);
        edge(SH - mb - 6, SH - mb - 2, cBotT);
        // a cut to a new scene: the light changes with it at once (no easing over from the last one)
        if (diff(cTop, cTopT) + diff(cBot, cBotT) > 70) { System.arraycopy(cTopT, 0, cTop, 0, cTop.length); System.arraycopy(cBotT, 0, cBot, 0, cBot.length); }
        invalidate();
    }
    private static float diff(float[] a, float[] b) { float d = 0; for (int i = 0; i < a.length; i++) d += Math.abs(a[i] - b[i]); return d / a.length; }
    private int rowMax(int r) {
        int mx = 0;
        for (int x = 0; x < SW; x++) { int c = px[r * SW + x]; int l = (((c >> 16) & 255) * 3 + ((c >> 8) & 255) * 6 + (c & 255)) / 10; if (l > mx) mx = l; }
        return mx;
    }
    private void edge(int r0, int r1, float[] out) {
        r0 = Math.max(0, r0); r1 = Math.min(SH, Math.max(r0 + 1, r1));
        for (int k = 0; k < ZONES; k++) {
            int x0 = k * SW / ZONES, x1 = (k + 1) * SW / ZONES; float r = 0, g = 0, b = 0; int n = 0;
            for (int y = r0; y < r1; y++) for (int x = x0; x < x1; x++) { int c = px[y * SW + x]; r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; n++; }
            n = Math.max(1, n);
            out[k * 3] = r / n; out[k * 3 + 1] = g / n; out[k * 3 + 2] = b / n;
        }
    }

    @Override protected void onDraw(Canvas c) {
        boolean moving = false;
        if (Math.abs(top - topT) > .001f) { top += (topT - top) * .12f; moving = true; } else top = topT;
        if (Math.abs(bot - botT) > .001f) { bot += (botT - bot) * .12f; moving = true; } else bot = botT;
        for (int i = 0; i < ZONES * 3; i++) {
            if (Math.abs(cTop[i] - cTopT[i]) > .5f) { cTop[i] += (cTopT[i] - cTop[i]) * .4f; moving = true; } else cTop[i] = cTopT[i];
            if (Math.abs(cBot[i] - cBotT[i]) > .5f) { cBot[i] += (cBotT[i] - cBot[i]) * .4f; moving = true; } else cBot[i] = cBotT[i];
        }
        int w = getWidth(), h = getHeight();
        float th = top * h, bh = bot * h, over = h * .012f;      // (over: ~13 px into the picture, so no line where they meet)
        if (th > 1) band(c, w, 0, th, cTop, true, over);
        if (bh > 1) band(c, w, h - bh, h, cBot, false, over);
        if (moving) postInvalidateOnAnimation();
    }

    /** one bar: a light per zone on the picture's edge, glowing out into the bar */
    private void band(Canvas c, int w, float y0, float y1, float[] col, boolean above, float over) {
        float edgeY = above ? y1 : y0, depth = y1 - y0;
        float l0 = above ? y0 : y0 - over, l1 = above ? y1 + over : y1;
        int sc = c.saveLayer(0, l0, w, l1, null);
        c.drawRect(0, l0, w, l1, black);
        float zw = w / (float) ZONES, rx = zw * 1.05f, ry = depth * 1.35f;
        for (int k = 0; k < ZONES; k++) {
            int lc = light(col[k * 3], col[k * 3 + 1], col[k * 3 + 2]);
            float cx = (k + .5f) * zw;
            glow.setShader(new RadialGradient(cx, edgeY, rx, new int[] { lc, (lc & 0x00FFFFFF) | 0xA6000000, (lc & 0x00FFFFFF) | 0x38000000, lc & 0x00FFFFFF },
                    new float[] { 0f, .35f, .7f, 1f }, Shader.TileMode.CLAMP));
            c.save();
            c.scale(1f, ry / rx, cx, edgeY);                      // (a wide, shallow pool of light)
            c.drawCircle(cx, edgeY, rx, glow);
            c.restore();
        }
        glow.setShader(null);
        // the light is kept to the bar, and fades out over the first few pixels of the picture
        float a = above ? l0 : l1, b = above ? l1 : l0, cut = depth / (depth + over);
        mask.setShader(new LinearGradient(0, a, 0, b, new int[] { 0xFF000000, 0xFF000000, 0xE6000000, 0x00000000 }, new float[] { 0f, cut, cut, 1f }, Shader.TileMode.CLAMP));
        c.drawRect(0, l0, w, l1, mask);
        mask.setShader(null);
        c.restoreToCount(sc);
    }
    /** a zone's light: its colour made a little richer (as light strips do), a dark zone stays dark */
    private int light(float r, float g, float b) {
        Color.RGBToHSV(clamp(r), clamp(g), clamp(b), hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.25f);
        hsv[2] = Math.min(1f, hsv[2] * 1.1f);
        return Color.HSVToColor(255, hsv);
    }
    private static int clamp(float v) { return Math.max(0, Math.min(255, Math.round(v))); }
}
