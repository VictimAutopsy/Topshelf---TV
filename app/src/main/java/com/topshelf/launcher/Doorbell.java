package com.topshelf.launcher;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The doorbell on screen (Ring, see Ring.java): a card in the top right with the doorbell's name, the time, and the
 * picture its camera took when the bell was pressed (it arrives a few seconds after the card). OK shows the picture
 * full screen, Back goes back to the card and then closes it; it goes by itself after a minute. What was playing is
 * paused while it's up and carries on after (see NativeHome.doorbell).
 */
final class Doorbell extends FrameLayout {
    private final NativeHome h;
    private final LinearLayout card;
    private final TextView kicker, when, headline, wait, hint;
    private final ImageView pic, big;
    private final FrameLayout picBox, full;
    private final TextView fullLabel;
    private boolean up, isFull;
    private Runnable onClose;

    Doorbell(NativeHome home) {
        super(home.getContext());
        h = home;
        boolean air = GlassPanel.look.equals("air");
        setClickable(false);
        // ---- the card
        card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(h.px(air ? 2.2f : 1.8f), h.px(1.5f), h.px(1.8f), h.px(1.6f));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xF50B0C10); bg.setCornerRadius(air ? 0 : 1.6f * h.R);
        bg.setStroke(Math.max(1, h.px(.1f)), 0x33FFFFFF);
        card.setBackground(air ? new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] { bg, new GlassPanel.Tab(GlassPanel.airColor) }) : bg);
        card.setElevation(h.px(2));
        LinearLayout top = new LinearLayout(getContext()); top.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(getContext());
        GradientDrawable dd = new GradientDrawable(); dd.setShape(GradientDrawable.OVAL); dd.setColor(0xFFFF453A); dot.setBackground(dd);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(h.px(.9f), h.px(.9f)); dl.rightMargin = h.px(.8f);
        top.addView(dot, dl);
        kicker = h.text(h.fSemi, 1.1f, 0xFFFF8A7A); kicker.setLetterSpacing(.12f);
        top.addView(kicker, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        when = h.text(h.fMed, 1.1f, 0xB3FFFFFF);
        top.addView(when);
        card.addView(top);
        headline = h.text(h.fSemi, 2.1f, Color.WHITE);
        headline.setText(air ? "SOMEONE'S AT THE DOOR" : "Someone's at the door");
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); hl.topMargin = h.px(.5f);
        card.addView(headline, hl);
        picBox = new FrameLayout(getContext());
        GradientDrawable pb = new GradientDrawable(); pb.setColor(0xFF15171D); pb.setCornerRadius(air ? 0 : .9f * h.R); picBox.setBackground(pb);
        picBox.setClipToOutline(true);
        pic = new ImageView(getContext()); pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picBox.addView(pic, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        wait = h.text(h.fMed, 1.2f, 0x99FFFFFF); wait.setText("Getting the picture…");
        picBox.addView(wait, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(h.px(36), h.px(20.25f)); pl.topMargin = h.px(1.1f);
        card.addView(picBox, pl);
        hint = h.text(h.fMed, 1.1f, 0xB3FFFFFF); hint.setText("OK · Full screen    ·    Back · Close");
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); il.topMargin = h.px(1f);
        card.addView(hint, il);
        LayoutParams cl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT);
        cl.topMargin = h.px(3); cl.rightMargin = h.px(3);
        addView(card, cl);
        // ---- full screen
        full = new FrameLayout(getContext());
        full.setBackgroundColor(0xFF000000);
        big = new ImageView(getContext()); big.setScaleType(ImageView.ScaleType.FIT_CENTER);
        full.addView(big, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        fullLabel = h.text(h.fSemi, 1.5f, Color.WHITE);
        fullLabel.setShadowLayer(.8f * h.R, 0, .1f * h.R, 0xCC000000);
        LayoutParams fl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT); fl.leftMargin = h.px(4); fl.topMargin = h.px(3);
        full.addView(fullLabel, fl);
        TextView fh = h.text(h.fMed, 1.15f, 0xCCFFFFFF); fh.setText("Back · Close"); fh.setShadowLayer(.8f * h.R, 0, .1f * h.R, 0xCC000000);
        LayoutParams fhl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT); fhl.rightMargin = h.px(4); fhl.bottomMargin = h.px(3);
        full.addView(fh, fhl);
        full.setVisibility(GONE);
        addView(full, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setVisibility(GONE);
    }

    boolean shownNow() { return up; }

    void show(String device, Runnable closed) {
        onClose = closed;
        String name = device == null || device.isEmpty() ? "Doorbell" : device;
        kicker.setText(name.toUpperCase(java.util.Locale.US));
        when.setText(Reminders.time(h, System.currentTimeMillis()));
        fullLabel.setText(name + "  ·  " + Reminders.time(h, System.currentTimeMillis()));
        pic.setImageDrawable(null); big.setImageDrawable(null);
        wait.setText("Getting the picture…"); wait.setVisibility(VISIBLE);
        isFull = false; full.setVisibility(GONE); card.setVisibility(VISIBLE);
        if (!up) { setVisibility(VISIBLE); card.setTranslationX(h.px(46)); card.setAlpha(0f); }
        up = true;
        bringToFront();
        card.animate().cancel();
        card.animate().translationX(0).alpha(1f).setDuration(420).setInterpolator(TileView.SPRING).start();
        removeCallbacks(autoHide); postDelayed(autoHide, 60000);
        h.act.haptic();
    }
    void picture(Bitmap b) {
        if (!up) return;
        if (b == null) { wait.setText("No picture from the doorbell"); return; }
        wait.setVisibility(GONE);
        pic.setImageBitmap(b); big.setImageBitmap(b);
        pic.setAlpha(0f); pic.animate().alpha(1f).setDuration(400).start();
    }
    void ok() {
        if (!up) return;
        isFull = !isFull;
        full.setVisibility(isFull ? VISIBLE : GONE);
        card.setVisibility(isFull ? GONE : VISIBLE);
        removeCallbacks(autoHide); postDelayed(autoHide, 60000);
    }
    void back() { if (isFull) ok(); else hide(); }
    void hide() {
        if (!up) return;
        up = false; isFull = false;
        removeCallbacks(autoHide);
        full.setVisibility(GONE); card.setVisibility(VISIBLE);
        card.animate().cancel();
        card.animate().translationX(h.px(46)).alpha(0f).setDuration(280).setInterpolator(TileView.EASE).withEndAction(new Runnable() { @Override public void run() {
            if (!up) setVisibility(GONE);
        } }).start();
        Runnable r = onClose; onClose = null;
        if (r != null) r.run();
    }
    private final Runnable autoHide = new Runnable() { @Override public void run() { hide(); } };
}
