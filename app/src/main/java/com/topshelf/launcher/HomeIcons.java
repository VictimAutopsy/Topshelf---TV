package com.topshelf.launcher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** The line icons of the top bar, the settings tile and the "All apps" hint (drawn on a 24×24 grid, like the page's SVGs). */
final class HomeIcons extends Drawable {
    static final int SEARCH = 0, PHOTOS = 1, SETTINGS = 2, REMOTE = 3, CHEVRON = 4, GEAR_BIG = 5, MIC = 6,
            HOME = 7, FILM = 8, TV = 9, LIVE = 10, TROPHY = 11;      // (the side menu's tabs)
    private final int kind;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int color = 0xFFFFFFFF;
    private final float stroke;

    HomeIcons(int kind, float stroke) {
        this.kind = kind; this.stroke = stroke;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }
    void setColor(int c) { if (c != color) { color = c; invalidateSelf(); } }

    @Override public void draw(Canvas c) {
        android.graphics.Rect b = getBounds();
        float s = Math.min(b.width(), b.height()) / 24f;
        c.save();
        c.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f);
        c.scale(s, s);
        p.setColor(color);
        p.setStrokeWidth(stroke);
        switch (kind) {
            case SEARCH:
                c.drawCircle(11, 11, 7, p);
                c.drawLine(20.5f, 20.5f, 16, 16, p);
                break;
            case PHOTOS: {
                c.drawRoundRect(PHOTO_BOX, 3, 3, p);
                c.drawCircle(9, 10, 1.8f, p);
                c.drawPath(PHOTO_HILL, p);
                break;
            }
            case SETTINGS: case GEAR_BIG:
                c.drawPath(gear(), p);
                c.drawCircle(12, 12, 3.2f, p);
                break;
            case REMOTE:
                c.drawRoundRect(REMOTE_BOX, 5, 5, p);
                c.drawCircle(12, 8, 2, p);
                c.drawPoint(12, 13.5f, p); c.drawPoint(12, 17, p);
                break;
            case MIC: {
                c.drawRoundRect(MIC_BOX, 3, 3, p);
                c.drawPath(MIC_CUP, p);
                c.drawLine(12, 18, 12, 21.5f, p);
                break;
            }
            case CHEVRON: {
                c.drawPath(CHEV, p);
                break;
            }
            case HOME: c.drawPath(HOUSE, p); break;
            case FILM: c.drawRect(4, 4, 20, 20, p); c.drawPath(FILM_LINES, p); break;
            case TV: c.drawRoundRect(TV_BOX, 2, 2, p); c.drawLine(8, 21, 16, 21, p); break;
            case LIVE: c.drawCircle(12, 12, 2, p); c.drawPath(WAVES, p); break;
            case TROPHY: c.drawPath(CUP, p); break;
        }
        c.restore();
    }

    // the shapes, made once (they were made each time an icon was drawn)
    private static final RectF PHOTO_BOX = new RectF(3, 4, 21, 20), REMOTE_BOX = new RectF(7, 2.5f, 17, 21.5f), MIC_BOX = new RectF(9, 2.5f, 15, 14.5f);
    private static final Path PHOTO_HILL = new Path(), MIC_CUP = new Path(), CHEV = new Path(),
            HOUSE = new Path(), FILM_LINES = new Path(), WAVES = new Path(), CUP = new Path();
    private static final RectF TV_BOX = new RectF(3, 6, 21, 18);
    static {
        HOUSE.moveTo(3, 11); HOUSE.lineTo(12, 4); HOUSE.lineTo(21, 11); HOUSE.lineTo(21, 20); HOUSE.quadTo(21, 21, 20, 21); HOUSE.lineTo(15, 21);
        HOUSE.lineTo(15, 15); HOUSE.lineTo(9, 15); HOUSE.lineTo(9, 21); HOUSE.lineTo(4, 21); HOUSE.quadTo(3, 21, 3, 20); HOUSE.close();
        FILM_LINES.moveTo(8, 4); FILM_LINES.lineTo(8, 20); FILM_LINES.moveTo(16, 4); FILM_LINES.lineTo(16, 20);
        FILM_LINES.moveTo(4, 9); FILM_LINES.lineTo(8, 9); FILM_LINES.moveTo(4, 15); FILM_LINES.lineTo(8, 15);
        FILM_LINES.moveTo(16, 9); FILM_LINES.lineTo(20, 9); FILM_LINES.moveTo(16, 15); FILM_LINES.lineTo(20, 15);
        RectF in = new RectF(6.3f, 6.3f, 17.7f, 17.7f), out = new RectF(2.1f, 2.1f, 21.9f, 21.9f);
        WAVES.arcTo(in, 225, -90, true); WAVES.arcTo(in, -45, 90, true); WAVES.arcTo(out, 225, -90, true); WAVES.arcTo(out, -45, 90, true);
        CUP.moveTo(8, 4); CUP.lineTo(16, 4); CUP.lineTo(16, 9); CUP.arcTo(new RectF(8, 5, 16, 13), 0, 180); CUP.close();
        CUP.moveTo(8, 6); CUP.lineTo(5, 6); CUP.quadTo(5, 10, 8, 10.5f); CUP.moveTo(16, 6); CUP.lineTo(19, 6); CUP.quadTo(19, 10, 16, 10.5f);
        CUP.moveTo(12, 13); CUP.lineTo(12, 17); CUP.moveTo(8, 20); CUP.lineTo(16, 20);
        PHOTO_HILL.moveTo(21, 16); PHOTO_HILL.lineTo(16, 11); PHOTO_HILL.lineTo(7, 20);
        MIC_CUP.moveTo(5, 11); MIC_CUP.cubicTo(5, 15, 8, 18, 12, 18); MIC_CUP.cubicTo(16, 18, 19, 15, 19, 11);
        CHEV.moveTo(6, 9); CHEV.lineTo(12, 15); CHEV.lineTo(18, 9);
    }
    private static Path gearPath;
    /** an eight-tooth cog like the settings icon */
    private static Path gear() {
        if (gearPath != null) return gearPath;
        Path g = new Path();
        int teeth = 8;
        float ro = 10.2f, ri = 7.6f;
        for (int i = 0; i < teeth * 4; i++) {
            double a = (i / (double) (teeth * 4)) * Math.PI * 2 - Math.PI / 2;
            int ph = i % 4;
            float r = (ph == 0 || ph == 1) ? ro : ri;
            float x = 12 + (float) (Math.cos(a) * r), y = 12 + (float) (Math.sin(a) * r);
            if (i == 0) g.moveTo(x, y); else g.lineTo(x, y);
        }
        g.close();
        gearPath = g;
        return g;
    }

    @Override public void setAlpha(int a) { p.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
