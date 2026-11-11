package com.topshelf.launcher;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** An app tile: artwork on a rounded card with a shadow, gloss, a shine that sweeps across when it's
 *  focused, a little tilt in the direction you moved, and its name underneath. All animation is on
 *  view properties, which Android animates on its render thread. */
final class TileView extends FrameLayout {
    static final Interpolator EASE = new PathInterpolator(.2f, .8f, .2f, 1f);
    static final Interpolator SPRING = new PathInterpolator(.34f, 1.4f, .5f, 1f);
    static boolean tiltOn = true, shadowsOn = true;   // the diagnostic's comparisons switch these
    boolean restShadow = true;                        // tiles in All apps rest flat on the glass (only the focused one lifts)

    final FrameLayout card;
    final ImageView art;
    final View gloss, shine;
    final TextView label;
    final float R, tw, th;
    boolean focused;
    String artKey = "";
    private ObjectAnimator wiggle;

    TileView(Context c, float rem, Typeface medium) {
        super(c);
        R = rem; tw = 19.4f * rem; th = tw * 9f / 16f;
        setClipChildren(false);
        setClipToPadding(false);
        final float rad = .85f * rem;
        /* The card's middle is drawn as a plain solid picture; only thin strips along the rounded
           top and bottom go through the rounded clip (soft clips are slow on this TV's chip). */
        card = new FrameLayout(c) {
            private final android.graphics.Path p = new android.graphics.Path();
            private int pw, ph;
            @Override protected void dispatchDraw(Canvas cv) {
                int w = getWidth(), h = getHeight();
                if (w == 0 || h <= 2 * rad) { super.dispatchDraw(cv); return; }
                if (w != pw || h != ph) { p.reset(); p.addRoundRect(new RectF(0, 0, w, h), rad, rad, android.graphics.Path.Direction.CW); pw = w; ph = h; }
                cv.save(); cv.clipRect(0, rad, w, h - rad); super.dispatchDraw(cv); cv.restore();
                cv.save(); cv.clipRect(0, 0, w, (float) Math.ceil(rad)); cv.clipPath(p); super.dispatchDraw(cv); cv.restore();
                cv.save(); cv.clipRect(0, (float) Math.floor(h - rad), w, h); cv.clipPath(p); super.dispatchDraw(cv); cv.restore();
            }
        };
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rad); }
        });
        card.setClipToOutline(false);              // the outline still casts the shadow
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(rad); bg.setColor(0xFF232326);
        card.setBackground(bg);
        card.setElevation(shadowsOn && restShadow ? .5f * rem : 0);
        if (android.os.Build.VERSION.SDK_INT >= 28) { card.setOutlineSpotShadowColor(0xFF000000); card.setOutlineAmbientShadowColor(0xFF000000); }
        card.setCameraDistance(80 * rem * 4);
        addView(card, new LayoutParams(Math.round(tw), Math.round(th)));
        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(art, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        gloss = new View(c);
        gloss.setBackground(new Gloss());
        gloss.setAlpha(0f);
        card.addView(gloss, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        shine = new View(c);
        shine.setBackground(new Shine());
        LayoutParams sl = new LayoutParams(Math.round(tw * 1.2f), Math.round(th * 1.2f));
        sl.leftMargin = Math.round(-tw * .1f); sl.topMargin = Math.round(-th * .1f);
        shine.setTranslationX(-tw * 1.2f * 1.3f);
        shine.setVisibility(INVISIBLE);           // only drawn while it sweeps across
        card.addView(shine, sl);
        // the glass edge is painted into the artwork itself (see bake), so each tile is one picture to draw
        label = new TextView(c);
        label.setTypeface(medium);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, 1.3f * rem);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setShadowLayer(.6f * rem, 0, .1f * rem, Ui.BLACK_55);
        label.setAlpha(0f);
        label.setTranslationY(-.5f * rem);
        LayoutParams ll = new LayoutParams(Math.round(tw * 1.5f), LayoutParams.WRAP_CONTENT);
        ll.leftMargin = Math.round(-tw * .25f);
        ll.topMargin = Math.round(th * 1.06f + .9f * rem);
        addView(label, ll);
    }

    void setLabel(String s) { label.setText(s); }
    /** the name under the tile (shown in the drawer; the dock shows just the icons) */
    private boolean labelOn = true;
    void setLabelOn(boolean on) { if (labelOn == on) return; labelOn = on; label.animate().cancel(); label.setAlpha(on && focused ? 1f : 0f); }
    void setArt(Bitmap b, String key) {
        artKey = key; art.setImageBitmap(b);
        card.setBackground(null);               // the artwork covers it (its backdrop is baked in)
    }

    /* This TV's graphics chip has little to spare for covering the same pixels twice, and a drawer
       of twenty tiles drew each one three times over (backdrop, artwork, glass edge). The backdrop
       and the edge are painted into the artwork once, when it's made. */
    static Bitmap bake(Bitmap art, float rem) {
        if (art == null) return null;
        int w = art.getWidth(), h = art.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(0xFF232326);
        c.drawBitmap(art, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
        float tw = 19.4f * rem, th = tw * 9f / 16f;
        float sx = w / tw, sy = h / th;
        c.scale(sx, sy);
        new TileRim(rem, .85f * rem).paint(c, new RectF(0, 0, tw, th));
        art.recycle();
        out.setHasAlpha(false);                  // fully solid: lets the chip skip what's hidden behind it
        return out;
    }

    void setFocused(boolean f, boolean animate, boolean reverse) {
        if (f == focused && animate) return;
        focused = f;
        if (!animate) {
            // straight to the end state, with nothing left running (no animation frame to come)
            card.animate().cancel(); gloss.animate().cancel(); label.animate().cancel(); shine.animate().cancel();
            card.setScaleX(f ? 1.12f : 1f); card.setScaleY(f ? 1.12f : 1f); card.setTranslationZ(f && shadowsOn ? 2.2f * R : 0f);
            card.setRotationX(0); card.setRotationY(0); card.setTranslationX(0); card.setTranslationY(0);     // (a tilt or nudge cut short)
            gloss.setAlpha(f ? 1f : 0f);
            label.setAlpha(f && labelOn ? 1f : 0f); label.setTranslationY(f ? 0f : -.5f * R);
            shine.setVisibility(INVISIBLE);
            setZ(f ? 10 : 0);
            return;
        }
        long d = 260;
        card.animate().scaleX(f ? 1.12f : 1f).scaleY(f ? 1.12f : 1f).translationZ(f && shadowsOn ? 2.2f * R : 0f).setDuration(d).setInterpolator(EASE).start();
        gloss.animate().alpha(f ? 1f : 0f).setDuration(animate ? Ui.MOVE : 0).start();
        label.animate().alpha(f && labelOn ? 1f : 0f).translationY(f ? 0f : -.5f * R).setDuration(animate ? Ui.FAST : 0).setInterpolator(EASE).start();
        setZ(f ? 10 : 0);
        if (f && animate) {
            float span = tw * 1.2f * 1.3f;
            shine.animate().cancel();
            shine.setTranslationX(reverse ? span : -span);
            shine.setVisibility(VISIBLE);
            shine.animate().translationX(reverse ? -span : span).setStartDelay(120).setDuration(1000).setInterpolator(EASE)
                    .withEndAction(new Runnable() { @Override public void run() { shine.setVisibility(INVISIBLE); } }).start();
        }
    }

    void applyShadow() { card.setElevation(shadowsOn && restShadow ? .5f * R : 0); card.setTranslationZ(focused && shadowsOn ? 2.2f * R : 0); }
    void setRestShadow(boolean on) { if (restShadow != on) { restShadow = on; applyShadow(); } }

    void tilt(char dir) {
        if (!tiltOn) return;
        float rx = 0, ry = 0;
        if (dir == 'l') ry = -5; else if (dir == 'r') ry = 5; else if (dir == 'u') rx = 5; else if (dir == 'd') rx = -5;
        card.animate().rotationX(rx).rotationY(ry).setDuration(140).setInterpolator(EASE).withEndAction(new Runnable() {
            @Override public void run() { card.animate().rotationX(0).rotationY(0).setDuration(Ui.SLOW).setInterpolator(SPRING).start(); }
        }).start();
    }

    void bump(char dir) {
        float dx = dir == 'l' ? -.7f * R : dir == 'r' ? .7f * R : 0, dy = dir == 'u' ? -.6f * R : dir == 'd' ? .6f * R : 0;
        card.animate().translationX(dx).translationY(dy).setDuration(128).setInterpolator(EASE).withEndAction(new Runnable() {
            @Override public void run() { card.animate().translationX(0).translationY(0).setDuration(Ui.FAST).setInterpolator(EASE).start(); }
        }).start();
    }

    void press() {
        card.animate().scaleX(1.04f).scaleY(1.04f).setDuration(100).withEndAction(new Runnable() {
            @Override public void run() { card.animate().scaleX(focused ? 1.12f : 1f).scaleY(focused ? 1.12f : 1f).setDuration(Ui.FAST).setInterpolator(EASE).start(); }
        }).start();
    }

    void setMoving(boolean on) {
        if (wiggle != null) { wiggle.cancel(); wiggle = null; card.setRotation(0); }
        if (on) {
            card.animate().scaleX(1.16f).scaleY(1.16f).setDuration(Ui.FAST).start();
            wiggle = ObjectAnimator.ofFloat(card, "rotation", -1.3f, 1.3f);
            wiggle.setDuration(Ui.BASE);
            wiggle.setRepeatCount(ValueAnimator.INFINITE);
            wiggle.setRepeatMode(ValueAnimator.REVERSE);
            wiggle.setInterpolator(new LinearInterpolator());
            wiggle.start();
            label.animate().alpha(labelOn ? 1f : 0f).translationY(0).setDuration(Ui.FAST).start();
        } else {
            card.animate().scaleX(focused ? 1.12f : 1f).scaleY(focused ? 1.12f : 1f).setDuration(Ui.FAST).start();
        }
    }

    /** soft light from the top of a focused tile */
    static final class Gloss extends Drawable {
        private final Paint p = new Paint();
        @Override protected void onBoundsChange(android.graphics.Rect b) {
            p.setShader(new RadialGradient(0, 0, 1f, new int[] { Ui.WHITE_25, 0x00FFFFFF }, new float[] { 0f, .6f }, Shader.TileMode.CLAMP));
        }
        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            c.save();
            c.translate(b.exactCenterX(), b.top - b.height() * .15f);
            c.scale(b.width() * 1.3f, b.height() * .85f);
            c.drawCircle(0, 0, 1.2f, p);
            c.restore();
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** the diagonal band of light that sweeps over a tile as it gets focus */
    static final class Shine extends Drawable {
        private final Paint p = new Paint();
        @Override protected void onBoundsChange(android.graphics.Rect b) {
            double a = Math.toRadians(115);
            float dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
            float len = Math.abs(b.width() * dx) + Math.abs(b.height() * dy);
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            p.setShader(new LinearGradient(cx - dx * len / 2, cy - dy * len / 2, cx + dx * len / 2, cy + dy * len / 2,
                    new int[] { 0, 0, Ui.WHITE_25, 0, 0 }, new float[] { 0f, .38f, .5f, .62f, 1f }, Shader.TileMode.CLAMP));
        }
        @Override public void draw(Canvas c) { c.drawRect(getBounds(), p); }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** the glass edge: a light line along the top, a faint border, a dark line along the bottom */
    static final class TileRim extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float R, rad;
        TileRim(float rem, float radius) { R = rem; rad = radius; }
        private final RectF box = new RectF();
        @Override public void draw(Canvas c) { box.set(getBounds()); paint(c, box); }
        void paint(Canvas c, RectF r) {
            p.setStyle(Paint.Style.STROKE);
            float sw = .07f * R;
            p.setStrokeWidth(sw);
            p.setColor(Ui.WHITE_12);
            c.drawRoundRect(new RectF(r.left + sw / 2, r.top + sw / 2, r.right - sw / 2, r.bottom - sw / 2), rad, rad, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.WHITE_40);
            c.save(); c.clipRect(r.left, r.top, r.right, r.top + .1f * R);
            android.graphics.Path path = new android.graphics.Path(); path.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
            c.clipPath(path); c.drawRect(r, p); c.restore();
            p.setColor(Ui.BLACK_25);
            c.save(); c.clipRect(r.left, r.bottom - .1f * R, r.right, r.bottom); c.clipPath(path); c.drawRect(r, p); c.restore();
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
