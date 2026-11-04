package com.topshelf.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.view.TextureView;
import android.view.View;

/**
 * Ambient light over a trailer's black bars (Featured). Many trailers are widescreen films inside a 16:9 video,
 * so black bands sit above and below the picture. Up to 30 times a second (with the screen's refresh) a tiny copy
 * of the frame (40×270) is read; rows that stay black across a couple of seconds are the bars (a dark scene isn't
 * mistaken for them: the bars must be in every frame, top and bottom alike). Like a Hue or Govee TV kit, only the
 * picture's edges are read: the few rows touching each bar, in 20 zones across. Their colours spill out over the bar,
 * fading to dark towards the screen's edge, and follow the film closely: a very short ease from frame to frame, an
 * instant change at any clear change of scene.
 * Two styles (Settings › Ambient light on trailers): Glow, the edge's colours as a smooth spill; or Reflection, a
 * heavily blurred mirror image of the picture's edge (a sunset or a skyline's lights carry on into the bar).
 * Either way the light follows the scene's brightness: a dark scene lets it sink almost to black (it's never
 * brighter than the film), and a sudden flash — an explosion, lightning — flares out into the bars and fades.
 */
final class AmbientBars extends View {
    interface Source { TextureView texture(); }
    private static final int SW = 40, SH = 270, ZONES = 20;     // (a sample row is ~4 px of a 1080p picture)
    private Source src;
    private final Bitmap sample = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888);
    private final int[] px = new int[SW * SH];
    private final int[] hist = new int[48]; private int histN, histI;       // recent {top, bottom} bar rows, in pairs (~2 s)
    private float top, bot, topT, botT;                                       // bars as fractions of the height (shown, wanted)
    private final float[] cTop = new float[ZONES * 3], cBot = new float[ZONES * 3], cTopT = new float[ZONES * 3], cBotT = new float[ZONES * 3];
    private final int[] gTop = new int[ZONES], gBot = new int[ZONES];
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), shade = new Paint(), fade = new Paint();
    private final Matrix m = new Matrix();
    private boolean on, logged;
    private boolean reflect;                       // the style: a blurred mirror of the edge, or (false) the glow
    private float lum, lumEma = -1, lumShown = -1, flash;   // the scene's brightness (0–255): now, its recent level, as shown; a flash's strength
    private final Bitmap rTop = Bitmap.createBitmap(SW, SH / 2, Bitmap.Config.ARGB_8888), rBot = Bitmap.createBitmap(SW, SH / 2, Bitmap.Config.ARGB_8888);
    private final int[] rBuf = new int[SW * (SH / 2)], rTmp = new int[SW * (SH / 2)];
    private int rTopN, rBotN;
    private final Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG), addB = new Paint(Paint.FILTER_BITMAP_FLAG), addG = new Paint(Paint.ANTI_ALIAS_FLAG), dim = new Paint();
    private final android.graphics.Rect rs = new android.graphics.Rect();
    private final android.graphics.RectF rd = new android.graphics.RectF();
    void style(String st) { reflect = "reflection".equals(st); }

    AmbientBars(Context c) {
        super(c); setWillNotDraw(false);
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        addB.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        addG.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
    }

    void start(Source s) {
        src = s; on = true; histN = 0; histI = 0; topT = botT = 0; logged = false; lumEma = lumShown = -1; flash = 0; rTopN = rBotN = 0;
        frames = 0; reads = 0; readNs = 0; statAt = System.nanoTime(); every = 2;
        android.view.Choreographer.getInstance().removeFrameCallback(frame);
        android.view.Choreographer.getInstance().postFrameCallback(frame);
    }
    void stop() {
        on = false; src = null;
        android.view.Choreographer.getInstance().removeFrameCallback(frame);
        topT = botT = 0; top = bot = 0; invalidate();
    }

    /* read with the screen's own rhythm: every second refresh (30 a second), or every fourth if reading
       turns out slow on this TV (so the rest of Home stays smooth) */
    private int frames, reads, every = 2; private long readNs, statAt; private boolean statLogged;
    private final android.view.Choreographer.FrameCallback frame = new android.view.Choreographer.FrameCallback() {
        @Override public void doFrame(long t) {
            if (!on) return;
            if (++frames % every == 0) {
                long r0 = System.nanoTime();
                read();
                readNs += System.nanoTime() - r0; reads++;
                if (reads == 30) every = readNs / reads > 6_000_000L ? 4 : 2;
                if (!statLogged && System.nanoTime() - statAt > 10_000_000_000L && reads > 0) {
                    statLogged = true;
                    Diag.ev("featured: ambient light reads " + Math.round(reads / ((System.nanoTime() - statAt) / 1e9)) + " a second, " + String.format(java.util.Locale.US, "%.1f", readNs / reads / 1e6) + " ms each");
                }
            }
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    };

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
        if (diff(cTop, cTopT) + diff(cBot, cBotT) > 36) { System.arraycopy(cTopT, 0, cTop, 0, cTop.length); System.arraycopy(cBotT, 0, cBot, 0, cBot.length); }
        // the scene's brightness (the picture between the bars), and a flash: suddenly much brighter than just now
        long sum = 0; int n = 0;
        for (int y = mt; y < SH - mb; y += 3) for (int x = 0; x < SW; x++) { int c = px[y * SW + x]; sum += (((c >> 16) & 255) * 3 + ((c >> 8) & 255) * 6 + (c & 255)) / 10; n++; }
        lum = n == 0 ? 0 : sum / (float) n;
        if (lumEma < 0) { lumEma = lum; lumShown = lum; }
        if (lum - lumEma > 26 && lum > 55) flash = Math.max(flash, Math.min(1f, (lum - lumEma) / 55f));
        lumEma += (lum - lumEma) * .06f;
        // the reflection: the picture's rows nearest each bar (as many as the bar is deep), blurred
        if (reflect && bars) {
            rTopN = strip(mt, 1, Math.min(mt, (SH - mt - mb) / 2), rTop);
            rBotN = strip(SH - mb - 1, -1, Math.min(mb, (SH - mt - mb) / 2), rBot);
        }
        invalidate();
    }
    /** n rows of the picture from row `from` (going `dir`: away from the bar), blurred, into `out` (row 0 nearest the bar) */
    private int strip(int from, int dir, int n, Bitmap out) {
        n = Math.max(1, Math.min(n, SH / 2));
        for (int y = 0; y < n; y++) { int sy = Math.max(0, Math.min(SH - 1, from + dir * y)); System.arraycopy(px, sy * SW, rBuf, y * SW, SW); }
        for (int pass = 0; pass < 2; pass++) { blur(rBuf, rTmp, SW, n, 2, true); blur(rTmp, rBuf, SW, n, 3, false); }
        out.setPixels(rBuf, 0, SW, 0, 0, SW, n);
        return n;
    }
    /** a box blur along rows (or columns) */
    private static void blur(int[] in, int[] out, int w, int h, int r, boolean rows) {
        int len = rows ? w : h, lines = rows ? h : w;
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) {
                int ra = 0, ga = 0, ba = 0, cnt = 0;
                for (int k = -r; k <= r; k++) {
                    int j = Math.max(0, Math.min(len - 1, i + k));
                    int c = rows ? in[l * w + j] : in[j * w + l];
                    ra += (c >> 16) & 255; ga += (c >> 8) & 255; ba += c & 255; cnt++;
                }
                int v = 0xFF000000 | ((ra / cnt) << 16) | ((ga / cnt) << 8) | (ba / cnt);
                if (rows) out[l * w + i] = v; else out[i * w + l] = v;
            }
        }
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
            if (Math.abs(cTop[i] - cTopT[i]) > .5f) { cTop[i] += (cTopT[i] - cTop[i]) * .65f; moving = true; } else cTop[i] = cTopT[i];
            if (Math.abs(cBot[i] - cBotT[i]) > .5f) { cBot[i] += (cBotT[i] - cBot[i]) * .65f; moving = true; } else cBot[i] = cBotT[i];
        }
        // brightness: the light sinks with a dark scene and flares with a flash (the flash fades over ~half a second)
        if (lumShown >= 0) { if (Math.abs(lumShown - lum) > .5f) { lumShown += (lum - lumShown) * .3f; moving = true; } else lumShown = lum; }
        if (flash > .02f) { flash *= .86f; moving = true; } else flash = 0;
        level = lumShown < 0 ? 1f : Math.max(.18f, Math.min(1f, .18f + lumShown / 115f));
        int w = getWidth(), h = getHeight();
        float th = top * h, bh = bot * h, over = h * .012f;      // (over: ~13 px into the picture, so no line where they meet)
        if (th > 1) band(c, w, 0, th, cTop, gTop, true, over);
        if (bh > 1) band(c, w, h - bh, h, cBot, gBot, false, over);
        if (moving) postInvalidateOnAnimation();
    }
    private float level = 1f;
    /** one bar: the edge's colours across it (or the edge's blurred reflection), fading to dark away from the picture */
    private void band(Canvas c, int w, float y0, float y1, float[] col, int[] g, boolean above, float over) {
        for (int i = 0; i < ZONES; i++) {
            // (a little lifted and softened: light, not a copy of the picture)
            g[i] = 0xFF000000 | (clamp(col[i * 3] * .85f + 8) << 16) | (clamp(col[i * 3 + 1] * .85f + 8) << 8) | clamp(col[i * 3 + 2] * .85f + 8);
        }
        LinearGradient lg = new LinearGradient(0, 0, w, 0, g, null, Shader.TileMode.CLAMP);
        p.setShader(lg);
        int rn = above ? rTopN : rBotN;
        boolean mirror = reflect && rn > 0;
        base(c, w, y0, y1, above, mirror, rn, bp, p);
        // brightness: a dark scene dims the light (never brighter than the film); a flash adds to it
        if (level < .99f) { dim.setColor(Math.round((1 - level) * 235) << 24); c.drawRect(0, y0, w, y1, dim); }
        if (flash > .02f) { int a = Math.round(flash * 120); addB.setAlpha(a); addG.setShader(lg); addG.setAlpha(a); base(c, w, y0, y1, above, mirror, rn, addB, addG); addG.setShader(null); }
        int nearA = Math.round(0x14 * (1 - flash)), farA = Math.round(0xF2 * (1 - .45f * flash));
        shade.setShader(new LinearGradient(0, above ? y1 : y0, 0, above ? y0 : y1, nearA << 24, farA << 24, Shader.TileMode.CLAMP));
        c.drawRect(0, y0, w, y1, shade);
        shade.setShader(null);
        // past the bar: the same light, fading out over the edge of the picture (no hard line where they meet)
        float e0 = above ? y1 : y0 - over, e1 = above ? y1 + over : y0;
        int sc = c.saveLayer(0, e0, w, e1, null);
        c.drawRect(0, e0, w, e1, p);
        fade.setShader(new LinearGradient(0, above ? y1 : y0, 0, above ? y1 + over : y0 - over, 0xEB000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, e0, w, e1, fade);
        c.restoreToCount(sc);
        p.setShader(null);
    }
    /** the bar's light itself: the blurred reflection (row 0 at the picture's edge, deeper rows towards the screen's edge) or the glow */
    private void base(Canvas c, int w, float y0, float y1, boolean above, boolean mirror, int rn, Paint bmp, Paint grad) {
        if (!mirror) { c.drawRect(0, y0, w, y1, grad); return; }
        rs.set(0, 0, SW, rn); rd.set(0, y0, w, y1);
        if (above) { c.save(); c.scale(1f, -1f, 0, (y0 + y1) / 2f); c.drawBitmap(rTop, rs, rd, bmp); c.restore(); }
        else c.drawBitmap(rBot, rs, rd, bmp);
    }
    private static int clamp(float v) { return Math.max(0, Math.min(255, Math.round(v))); }
}
