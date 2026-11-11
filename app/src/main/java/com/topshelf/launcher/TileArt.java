package com.topshelf.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.Base64;

import org.json.JSONObject;

/** Draws a tile's artwork once into a picture: the app's TV banner, or its icon on a colour, or the look
 *  chosen with Change icon (your own picture, an icon pack, a colour, frosted, or the app's name). */
final class TileArt {
    private TileArt() {}

    static Bitmap render(Bridge bridge, NativeHome.App a, JSONObject look, int w, int h, float rem, Typeface bold) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        if (a.special) {
            gradient(c, w, h, 0xFF3A3A40, 0xFF1D1D21);
            HomeIcons g = new HomeIcons(HomeIcons.GEAR_BIG, 1.6f);
            g.setColor(Ui.WHITE_95);
            int s = Math.round(4.4f * rem);
            g.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
            g.draw(c);
            return out;
        }
        int tint = parse(a.tint, 0xFF2A2A2E);
        String k = look == null ? "" : look.optString("k");
        if (k.equals("image")) {
            String f = look.optString("f");
            byte[] d = null;
            try {
                if (f.startsWith("data:")) d = Base64.decode(f.substring(f.indexOf(',') + 1), Base64.DEFAULT);
                else d = bridge.packs().readCustom(f);
            } catch (Exception ignored) { }
            Bitmap img = d == null ? null : BitmapFactory.decodeByteArray(d, 0, d.length);
            if (img != null) { cover(c, img, w, h); return out; }
            k = "";
        }
        if (k.equals("color") || k.equals("pack")) {
            int col = look.has("c") ? parse(look.optString("c"), tint) : k.equals("pack") ? (a.banner ? 0xFF2A2A30 : tint) : tint;
            gradient(c, w, h, shade(col, .18f), shade(col, -.25f));
            Bitmap ic = null;
            if (k.equals("pack")) { byte[] d = bridge.packs().packIcon(look.optString("p"), a.id); if (d != null) ic = BitmapFactory.decodeByteArray(d, 0, d.length); }
            if (ic == null) ic = icon(bridge, a, false);
            if (ic != null) centered(c, ic, w, h, .56f, true); else glyph(c, a, w, h, rem, bold);
            return out;
        }
        if (k.equals("frost")) {
            gradient(c, w, h, shade(tint, .18f), shade(tint, -.25f));
            Bitmap ic = icon(bridge, a, false);
            if (ic != null) {
                Bitmap small = Bitmap.createScaledBitmap(ic, 24, 24, true);
                Blur.stack(small, 6);
                Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
                android.graphics.ColorMatrix m = new android.graphics.ColorMatrix(); m.setSaturation(1.8f);
                android.graphics.ColorMatrix br = new android.graphics.ColorMatrix(new float[] { .85f, 0, 0, 0, 0, 0, .85f, 0, 0, 0, 0, 0, .85f, 0, 0, 0, 0, 0, .95f, 0 });
                m.postConcat(br);
                p.setColorFilter(new android.graphics.ColorMatrixColorFilter(m));
                c.drawBitmap(small, null, new RectF(-w * .25f, -h * .25f, w * 1.25f, h * 1.25f), p);
                Paint top = new Paint();
                top.setShader(new LinearGradient(0, 0, 0, h * .55f, 0x29FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, top);
                centered(c, ic, w, h, .5f, true);
            } else glyph(c, a, w, h, rem, bold);
            return out;
        }
        if (k.equals("name")) {
            gradient(c, w, h, shade(tint, .18f), shade(tint, -.25f));
            Bitmap ic = icon(bridge, a, false);
            if (ic != null) {
                float ih = h * .26f, iw = ih * ic.getWidth() / ic.getHeight();
                c.drawBitmap(ic, null, new RectF(w * .07f, h * .11f, w * .07f + iw, h * .11f + ih), new Paint(Paint.FILTER_BITMAP_FLAG));
            }
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setTypeface(bold); t.setColor(Color.WHITE);
            t.setTextSize(w * .1f);
            t.setLetterSpacing(-.03f);
            String s = a.label;
            float maxW = w * .86f;
            while (t.measureText(s) > maxW && s.length() > 2) s = s.substring(0, s.length() - 2) + "…";
            c.drawText(s, w * .07f, h * .89f, t);
            return out;
        }
        // the app's own artwork
        if (a.banner) {
            Bitmap b = icon(bridge, a, true);
            if (b != null) { cover(c, b, w, h); return out; }
        }
        gradient(c, w, h, shade(tint, .18f), shade(tint, -.25f));
        Bitmap ic = icon(bridge, a, false);
        if (ic != null) centered(c, ic, w, h, .56f, true); else glyph(c, a, w, h, rem, bold);
        return out;
    }

    static Bitmap icon(Bridge bridge, NativeHome.App a, boolean banner) {
        byte[] d = bridge.iconPng(a.id, banner);
        return d == null ? null : BitmapFactory.decodeByteArray(d, 0, d.length);
    }

    private static void cover(Canvas c, Bitmap b, int w, int h) {
        float s = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
        float bw = b.getWidth() * s, bh = b.getHeight() * s;
        c.drawBitmap(b, null, new RectF((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2), new Paint(Paint.FILTER_BITMAP_FLAG));
    }

    private static void centered(Canvas c, Bitmap ic, int w, int h, float frac, boolean shadow) {
        float ih = h * frac, iw = ih * ic.getWidth() / ic.getHeight();
        RectF r = new RectF((w - iw) / 2, (h - ih) / 2, (w + iw) / 2, (h + ih) / 2);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        if (shadow) {
            Bitmap sh = ic.extractAlpha();
            Paint sp = new Paint(Paint.FILTER_BITMAP_FLAG);
            sp.setColor(0x59000000);
            sp.setMaskFilter(new android.graphics.BlurMaskFilter(h * .07f, android.graphics.BlurMaskFilter.Blur.NORMAL));
            RectF sr = new RectF(r); sr.offset(0, h * .03f);
            c.drawBitmap(sh, null, sr, sp);
        }
        c.drawBitmap(ic, null, r, p);
    }

    private static void glyph(Canvas c, NativeHome.App a, int w, int h, float rem, Typeface bold) {
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTypeface(bold); t.setColor(0xEBFFFFFF); t.setTextSize(4.2f * rem * 1.12f); t.setTextAlign(Paint.Align.CENTER);
        String s = a.label.isEmpty() ? "?" : a.label.substring(0, 1);
        Rect b = new Rect(); t.getTextBounds(s, 0, 1, b);
        c.drawText(s, w / 2f, h / 2f + b.height() / 2f, t);
    }

    private static void gradient(Canvas c, int w, int h, int from, int to) {
        // 160deg, like the page's tiles
        double a = Math.toRadians(160);
        float dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
        float len = Math.abs(w * dx) + Math.abs(h * dy);
        Paint p = new Paint();
        p.setShader(new LinearGradient(w / 2f - dx * len / 2, h / 2f - dy * len / 2, w / 2f + dx * len / 2, h / 2f + dy * len / 2, from, to, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
    }

    static int parse(String hex, int def) {
        try { return 0xFF000000 | Integer.parseInt(hex.replace("#", "").substring(0, 6), 16); } catch (Exception e) { return def; }
    }
    static int shade(int col, float amt) {
        int[] ch = { (col >> 16) & 255, (col >> 8) & 255, col & 255 };
        for (int i = 0; i < 3; i++) {
            float v = ch[i];
            v = amt > 0 ? v + (255 - v) * amt : v + v * amt;
            ch[i] = Math.max(0, Math.min(255, Math.round(v)));
        }
        return 0xFF000000 | (ch[0] << 16) | (ch[1] << 8) | ch[2];
    }
}
