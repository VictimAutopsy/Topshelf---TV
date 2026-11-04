package com.topshelf.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Random;

/**
 * A game's own poster for Featured Home, when there's no photo of the game itself: drawn by TopShelf,
 * like a broadcast's matchup graphic. Two tall slanted panels on the right, one per team, each holding that
 * team's fan art graded into its colours (the team colour rising from the bottom, a lit edge in its second
 * colour, pinstripes), the team's logo at the foot with a shadow, a VS badge where they meet, light streaks
 * across both, the away team's logo huge and faint in the dark field on the left (where the title sits),
 * the two teams' colours glowing in the corners, fine grain and a vignette. Made once per game and kept as a
 * picture in the app's cache (a day), so it's as sharp as any photo.
 *
 * Away team on the left, home on the right, as a TV listing puts them.
 */
final class Matchup {
    private Matchup() { }

    static final int W = 1920, H = 1080;

    /** the picture's address ("file://…"), or "" (no teams to draw) — made on a worker thread */
    /** what the poster shows of the score: "" before the game, else "21-17|Q3" (a new picture when it changes) */
    static String scoreSig(SportsData.Ev e) {
        if (e == null || e.teams.size() != 2 || !(e.state.equals("in") || e.state.equals("post"))) return "";
        String per = e.state.equals("post") ? "FINAL" : (e.per.isEmpty() ? "LIVE" : e.per);
        return e.teams.get(0).score + "-" + e.teams.get(1).score + "|" + per;
    }
    static String make(SportsData.Ev e, String fanA, String fanB, File cacheDir) {
        if (e == null || e.teams.size() != 2) return "";
        SportsData.Team a = e.teams.get(0), b = e.teams.get(1);
        File dir = new File(cacheDir, "matchup");
        dir.mkdirs();
        String key = (e.lg + "-" + e.id + "-" + Integer.toHexString((fanA + "|" + fanB + "|" + a.logo + b.logo + "|" + scoreSig(e)).hashCode())).replaceAll("[^A-Za-z0-9_-]", "_");
        File out = new File(dir, key + ".jpg");
        if (out.exists() && System.currentTimeMillis() - out.lastModified() < 86400000L && out.length() > 20000) return "file://" + out.getAbsolutePath();
        tidy(dir);
        Bitmap artA = load(fanA, 1280), artB = load(fanB, 1280), logoA = load(a.logo, 520), logoB = load(b.logo, 520);
        Bitmap bm = null;
        try {
            bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            draw(new Canvas(bm), a, b, artA, artB, logoA, logoB, e.id.hashCode(), scoreSig(e));
            FileOutputStream fo = new FileOutputStream(out);
            try { bm.compress(Bitmap.CompressFormat.JPEG, 92, fo); } finally { fo.close(); }
            return "file://" + out.getAbsolutePath();
        } catch (Throwable t) {
            Diag.ev("matchup art: " + t);
            return "";
        } finally {
            for (Bitmap x : new Bitmap[] { bm, artA, artB, logoA, logoB }) if (x != null) x.recycle();
        }
    }

    private static void tidy(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        long old = System.currentTimeMillis() - 2 * 86400000L;
        for (File f : fs) if (f.lastModified() < old) f.delete();
    }
    private static Bitmap load(String u, int max) {
        if (u == null || !u.startsWith("http")) return null;
        try { byte[] d = HomeImages.http(u, null); return d == null ? null : HomeImages.decode(d, max, max); } catch (Throwable t) { return null; }
    }
    private static int col(String hex, int fallback) {
        if (hex == null || hex.isEmpty()) return fallback;
        try { return Color.parseColor(hex.startsWith("#") ? hex : "#" + hex) | 0xFF000000; } catch (Exception x) { return fallback; }
    }
    private static int alpha(int c, float a) { return (c & 0x00FFFFFF) | (Math.round(Math.max(0, Math.min(1, a)) * 255) << 24); }
    /** a team colour that shows on black: very dark ones (navy, black) are lifted, keeping their hue */
    private static int lift(int c) {
        float[] hsv = new float[3]; Color.colorToHSV(c, hsv);
        if (hsv[2] < .42f) hsv[2] = .42f + hsv[2] * .3f;
        if (hsv[1] < .08f) { hsv[2] = Math.max(hsv[2], .55f); }
        return Color.HSVToColor(hsv);
    }

    private static void draw(Canvas c, SportsData.Team a, SportsData.Team b, Bitmap artA, Bitmap artB, Bitmap logoA, Bitmap logoB, int seed, String sig) {
        int ca = lift(col(a.color, 0xFF3A4A66)), cb = lift(col(b.color, 0xFF66403A));
        int ea = col(a.alt, 0xFFFFFFFF), eb = col(b.alt, 0xFFFFFFFF);
        if (Math.abs(lum(ea) - lum(ca)) < .25f) ea = 0xFFFFFFFF;
        if (Math.abs(lum(eb) - lum(cb)) < .25f) eb = 0xFFFFFFFF;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        // ---- the dark field, the two colours glowing in it, pinstripes
        c.drawColor(0xFF07080B);
        p.setShader(new RadialGradient(W * .18f, H * .28f, W * .62f, new int[] { alpha(ca, .42f), alpha(ca, .12f), 0 }, new float[] { 0, .5f, 1 }, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(new RadialGradient(W * 1.02f, H * .95f, W * .55f, new int[] { alpha(cb, .38f), alpha(cb, .1f), 0 }, new float[] { 0, .5f, 1 }, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);
        stripes(c, new RectF(0, 0, W, H), 0x0DFFFFFF, 26);
        // the away team's logo, huge and faint, tipped, half off the left edge
        if (logoA != null) {
            c.save(); c.rotate(-9, W * .12f, H * .55f);
            Paint q = new Paint(Paint.FILTER_BITMAP_FLAG); q.setAlpha(18);
            float s = H * 1.05f, ww = s * logoA.getWidth() / Math.max(1, logoA.getHeight());
            c.drawBitmap(logoA, null, new RectF(-W * .16f, H * .02f, -W * .16f + ww, H * .02f + s), q);
            c.restore();
        }

        // ---- the two panels: tall, slanted, meeting at a gap where the VS sits
        float slant = H * .2f, gap = W * .014f;
        float aL = W * .40f, aR = W * .685f, bL = aR + gap, bR = W + slant + 40;
        Path pa = slantRect(aL, aR, slant), pb = slantRect(bL, bR, slant);
        panel(c, pa, new RectF(aL, 0, aR + slant, H), artA, ca, ea, logoA, seed, a.nick.isEmpty() ? a.name : a.nick);
        panel(c, pb, new RectF(bL, 0, Math.min(bR, W + slant), H), artB, cb, eb, logoB, seed * 31 + 7, b.nick.isEmpty() ? b.name : b.nick);

        // light streaks running along the slant, across both panels
        Paint ls = new Paint(Paint.ANTI_ALIAS_FLAG);
        ls.setStrokeCap(Paint.Cap.ROUND);
        Random r = new Random(seed);
        for (int i = 0; i < 4; i++) {
            float x = W * (.44f + r.nextFloat() * .5f), len = H * (.3f + r.nextFloat() * .35f), y = H * (.08f + r.nextFloat() * .55f);
            float dx = slant / H * len;
            ls.setStrokeWidth(2 + r.nextFloat() * 3);
            ls.setShader(new LinearGradient(x + dx, y, x, y + len, new int[] { 0x00FFFFFF, 0x47FFFFFF, 0x00FFFFFF }, null, Shader.TileMode.CLAMP));
            c.drawLine(x + dx, y, x, y + len, ls);
        }

        // ---- VS (or, once it's begun, the score), on a slanted black plate where the panels meet, with a white glow
        float vx = aR + gap / 2 + slant * .5f, vy = H * .5f;
        Typeface ti = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC);
        Paint vp = new Paint(Paint.ANTI_ALIAS_FLAG); vp.setTypeface(ti); vp.setTextSize(H * .085f); vp.setTextAlign(Paint.Align.CENTER);
        String mid = "VS", per = "";
        if (!sig.isEmpty()) {
            int bar = sig.indexOf('|'); String sc = sig.substring(0, bar); per = sig.substring(bar + 1);
            int dash = sc.indexOf('-');
            mid = sc.substring(0, dash) + "  –  " + sc.substring(dash + 1);
            vp.setFontFeatureSettings("tnum");
        }
        float tw = vp.measureText(mid);
        Path plate = new Path();
        float pw = tw * .8f + H * .05f, ph = H * .075f, sk = ph * .45f;
        plate.moveTo(vx - pw + sk, vy - ph); plate.lineTo(vx + pw + sk, vy - ph); plate.lineTo(vx + pw - sk, vy + ph); plate.lineTo(vx - pw - sk, vy + ph); plate.close();
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG); glow.setColor(0x66FFFFFF); glow.setMaskFilter(new BlurMaskFilter(H * .03f, BlurMaskFilter.Blur.NORMAL));
        c.drawPath(plate, glow);
        Paint pl = new Paint(Paint.ANTI_ALIAS_FLAG); pl.setColor(0xFF07080B); c.drawPath(plate, pl);
        pl.setStyle(Paint.Style.STROKE); pl.setStrokeWidth(3); pl.setColor(0xE6FFFFFF); c.drawPath(plate, pl);
        vp.setColor(0xFFFFFFFF); vp.setShadowLayer(H * .012f, 0, 0, 0xB3FFFFFF);
        c.drawText(mid, vx, vy + vp.getTextSize() * .36f, vp);
        if (!per.isEmpty()) {
            // the period (or FINAL) on a small tab above the plate: red while it's on
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG); tp.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD)); tp.setTextSize(H * .034f); tp.setLetterSpacing(.1f); tp.setTextAlign(Paint.Align.CENTER);
            String pt = per.toUpperCase(java.util.Locale.US);
            float pw2 = tp.measureText(pt) / 2 + H * .022f, py = vy - ph - H * .012f, th2 = H * .05f, sk2 = th2 * .45f, px = vx + sk + sk2 * .5f;
            Path tab = new Path();
            tab.moveTo(px - pw2 + sk2, py - th2); tab.lineTo(px + pw2 + sk2, py - th2); tab.lineTo(px + pw2, py); tab.lineTo(px - pw2, py); tab.close();
            Paint tf = new Paint(Paint.ANTI_ALIAS_FLAG); tf.setColor(pt.equals("FINAL") ? 0xFF2A2D33 : 0xFFE8412F);
            c.drawPath(tab, tf);
            tp.setColor(0xFFFFFFFF);
            c.drawText(pt, px + sk2 * .5f, py - th2 / 2 + tp.getTextSize() * .36f, tp);
        }

        // ---- grain and the vignette
        Paint gp = new Paint();
        Random g = new Random(seed ^ 0x5bd1e995);
        for (int i = 0; i < 70000; i++) {
            gp.setColor(g.nextBoolean() ? 0x0FFFFFFF : 0x14000000);
            float x = g.nextFloat() * W, y = g.nextFloat() * H;
            c.drawRect(x, y, x + 2, y + 2, gp);
        }
        Paint vg = new Paint();
        vg.setShader(new LinearGradient(0, H * .62f, 0, H, 0x00000000, 0x9E000000, Shader.TileMode.CLAMP)); c.drawRect(0, H * .62f, W, H, vg);
        vg.setShader(new LinearGradient(0, 0, 0, H * .2f, 0x73000000, 0x00000000, Shader.TileMode.CLAMP)); c.drawRect(0, 0, W, H * .2f, vg);
    }

    private static float lum(int c) { return (.2126f * ((c >> 16) & 255) + .7152f * ((c >> 8) & 255) + .0722f * (c & 255)) / 255f; }

    /** a panel from x0 to x1 at the bottom, leaning right by `slant` at the top */
    private static Path slantRect(float x0, float x1, float slant) {
        Path p = new Path();
        p.moveTo(x0 + slant, 0); p.lineTo(x1 + slant, 0); p.lineTo(x1, H); p.lineTo(x0, H); p.close();
        return p;
    }

    private static void stripes(Canvas c, RectF r, int color, float every) {
        // fine lines leaning the same way as the panels
        Paint s = new Paint(Paint.ANTI_ALIAS_FLAG); s.setColor(color); s.setStrokeWidth(1.6f);
        float lean = r.height() * .2f;
        for (float x = r.left - lean; x < r.right; x += every) c.drawLine(x, r.bottom, x + lean, r.top, s);
    }

    private static void panel(Canvas c, Path shape, RectF box, Bitmap art, int team, int edge, Bitmap logo, int seed, String name) {
        c.save();
        c.clipPath(shape);
        if (art != null) {
            // the fan art, covering the panel, pulled toward the team's colours
            float s = Math.max(box.width() / art.getWidth(), box.height() / art.getHeight());
            float dw = art.getWidth() * s, dh = art.getHeight() * s;
            RectF dst = new RectF(box.centerX() - dw / 2, box.centerY() - dh / 2, box.centerX() + dw / 2, box.centerY() + dh / 2);
            Paint ap = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            ColorMatrix m = new ColorMatrix(); m.setSaturation(.55f);
            ColorMatrix contrast = new ColorMatrix(new float[] { 1.18f, 0, 0, 0, -18, 0, 1.18f, 0, 0, -18, 0, 0, 1.18f, 0, -18, 0, 0, 0, 1, 0 });
            m.postConcat(contrast);
            ap.setColorFilter(new ColorMatrixColorFilter(m));
            c.drawBitmap(art, null, dst, ap);
            Paint tint = new Paint(); tint.setColor(alpha(team, .22f)); c.drawRect(box, tint);
        } else {
            // no fan art: the team's colour, deep, with its logo huge and cropped
            Paint f = new Paint();
            f.setShader(new LinearGradient(0, 0, 0, H, darker(team, .55f), darker(team, .25f), Shader.TileMode.CLAMP));
            c.drawRect(box, f);
            if (logo != null) {
                Paint q = new Paint(Paint.FILTER_BITMAP_FLAG); q.setAlpha(46);
                float sz = H * 1.1f, ww = sz * logo.getWidth() / Math.max(1, logo.getHeight());
                c.drawBitmap(logo, null, new RectF(box.centerX() - ww / 2 + H * .1f, -H * .12f, box.centerX() + ww / 2 + H * .1f, -H * .12f + sz), q);
            }
        }
        // the team colour rising from the bottom, a shade from the top, pinstripes
        Paint up = new Paint();
        up.setShader(new LinearGradient(0, H, 0, H * .3f, new int[] { alpha(team, .9f), alpha(team, .38f), alpha(team, 0) }, new float[] { 0, .45f, 1 }, Shader.TileMode.CLAMP));
        c.drawRect(box, up);
        Paint top = new Paint(); top.setShader(new LinearGradient(0, 0, 0, H * .35f, 0x8C000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(box, top);
        stripes(c, box, 0x0BFFFFFF, 14);
        c.restore();

        // the lit edge, in the team's second colour, with its glow
        Paint gl = new Paint(Paint.ANTI_ALIAS_FLAG); gl.setStyle(Paint.Style.STROKE); gl.setStrokeWidth(14); gl.setColor(alpha(edge, .45f));
        gl.setMaskFilter(new BlurMaskFilter(16, BlurMaskFilter.Blur.NORMAL));
        c.drawPath(shape, gl);
        Paint ed = new Paint(Paint.ANTI_ALIAS_FLAG); ed.setStyle(Paint.Style.STROKE); ed.setStrokeWidth(5); ed.setColor(edge);
        c.drawPath(shape, ed);

        // the logo at the panel's foot, with a soft shadow
        if (logo != null) {
            float sz = H * .24f, ww = sz * logo.getWidth() / Math.max(1, logo.getHeight());
            if (ww > box.width() * .55f) { sz *= box.width() * .55f / ww; ww = box.width() * .55f; }
            float cy = H * .73f, right = Math.min(box.right - H * .2f, W);
            float cx = (box.left + right) / 2f + H * .2f * (1 - cy / H);      // (the middle of the panel at that height)
            RectF d = new RectF(cx - ww / 2, cy - sz / 2, cx + ww / 2, cy + sz / 2);
            // its shadow: the logo's shape, blurred, in black, a little lower
            Paint bp = new Paint(); bp.setMaskFilter(new BlurMaskFilter(Math.max(4f, logo.getWidth() * .05f), BlurMaskFilter.Blur.NORMAL));
            int[] off = new int[2];
            Bitmap sh = logo.extractAlpha(bp, off);
            float k = d.width() / logo.getWidth();
            RectF ds = new RectF(d.left + off[0] * k, d.top + off[1] * k + H * .014f, d.left + (off[0] + sh.getWidth()) * k, d.top + (off[1] + sh.getHeight()) * k + H * .014f);
            Paint shp = new Paint(Paint.FILTER_BITMAP_FLAG); shp.setColor(0xCC000000);
            c.drawBitmap(sh, null, ds, shp);
            sh.recycle();
            c.drawBitmap(logo, null, d, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        }
        // the team's name under it, in broadcast capitals
        if (name != null && !name.isEmpty()) {
            float ny = H * .925f, right = Math.min(box.right - H * .2f, W);
            float nx = (box.left + right) / 2f + H * .2f * (1 - ny / H);
            Paint np = new Paint(Paint.ANTI_ALIAS_FLAG);
            np.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
            np.setTextSize(H * .056f); np.setLetterSpacing(.06f); np.setTextAlign(Paint.Align.CENTER);
            String t = name.toUpperCase(java.util.Locale.US);
            float max = (right - box.left) * .8f;
            while (np.measureText(t) > max && np.getTextSize() > H * .03f) np.setTextSize(np.getTextSize() * .92f);
            np.setColor(0xF2FFFFFF); np.setShadowLayer(H * .012f, 0, H * .004f, 0xB3000000);
            c.drawText(t, nx, ny, np);
        }
    }
    private static int darker(int c, float k) {
        float[] hsv = new float[3]; Color.colorToHSV(c, hsv); hsv[2] *= k; return Color.HSVToColor(hsv);
    }
}
