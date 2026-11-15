package com.topshelf.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Game alerts as one small bar in the look that's chosen (Chad picked it from mock-ups, 1.0.442; it was a wide card
 * with the teams' colours at its ends, dropping in from the top middle): a tag naming what happened, the two teams'
 * logos around the matchup or the score, a short detail, and "OK · Watch on …" when there's a channel showing it.
 * On Air: a square black bar whose tag is the red bar with the slanted end; the other looks: their own highlight
 * and corners (GlassPanel.onButton / corner). Where it shows is a setting (Settings › Sports › Alert position):
 * the top right corner, or the bottom middle where messages appear.
 *
 * Used for: a game of yours starting soon, one of your teams scoring, the red zone, and Yahoo Fantasy plays.
 */
final class SportsAlert extends FrameLayout {
    private final NativeHome h;
    private final LinearLayout card;
    private final ImageView logoA, logoB;
    private final TextView tag, headline, line, hint;
    private final View rule;
    private boolean up, bottom;

    SportsAlert(NativeHome home) {
        super(home.getContext());
        h = home;
        setClipChildren(false);
        card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setClipToOutline(true);
        card.setElevation(h.px(1.2f));
        tag = h.text(h.fSemi, Ui.TEXT_S, Color.WHITE); tag.setGravity(Gravity.CENTER); tag.setSingleLine(true);
        card.addView(tag, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        logoA = new ImageView(getContext()); logoB = new ImageView(getContext());
        logoA.setScaleType(ImageView.ScaleType.FIT_CENTER); logoB.setScaleType(ImageView.ScaleType.FIT_CENTER);
        boolean narrow = h.SW < 60;                 // (a phone held upright)
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(h.px(2.2f), h.px(2.2f)); la.leftMargin = h.px(.9f);
        card.addView(logoA, la);
        headline = h.text(h.fSemi, Ui.TEXT_M, Color.WHITE); headline.setSingleLine(true); headline.setEllipsize(android.text.TextUtils.TruncateAt.END);
        headline.setFontFeatureSettings("tnum"); headline.setMaxWidth(h.px(narrow ? 20 : 34));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); hl.leftMargin = h.px(.8f);
        card.addView(headline, hl);
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(h.px(2.2f), h.px(2.2f)); lb.leftMargin = h.px(.8f);
        card.addView(logoB, lb);
        line = h.text(h.fMed, Ui.TEXT_S, Ui.WHITE_75); line.setSingleLine(true); line.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.setMaxWidth(h.px(narrow ? 12 : 26));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); ll.leftMargin = h.px(1f);
        card.addView(line, ll);
        rule = new View(getContext()); rule.setBackgroundColor(Ui.WHITE_25);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(Math.max(1, h.px(.1f)), h.px(1.9f)); rl.leftMargin = h.px(1.2f);
        card.addView(rule, rl);
        hint = h.text(h.fSemi, Ui.TEXT_S, Color.WHITE); hint.setSingleLine(true);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); il.leftMargin = h.px(1.1f);
        card.addView(hint, il);
        addView(card, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, h.px(3.9f)));
        setVisibility(GONE);
    }

    /** a team end of the notification: {abbreviation, colour (#rrggbb), logo url} */
    static final class End { String abbr = "", color = "", logo = ""; }

    private Runnable onOk;

    /** Settings › Sports › Alert position: true for the bottom middle (the top right corner otherwise) */
    private boolean wantsBottom() {
        try { String st = h.prefs.getString("ui.settings", null); return st != null && "bottom".equals(new org.json.JSONObject(st).optString("alertPlace")); } catch (Exception e) { return false; }
    }
    /** how far off its place it starts and ends: in from the right edge, or up from the bottom */
    private void away() { card.setTranslationX(bottom ? 0 : h.px(5)); card.setTranslationY(bottom ? h.px(3) : 0); }

    /**
     * kicker: "NFL · STARTING SOON" (its last part is the tag); headline: "ARI 7 – 14 DAL" or the matchup; detail: a
     * short line after it; a / b: the two teams, for their logos (or null); hint: "OK · Watch on FOX" or "".
     * (kickerColor, the badge words and accent belonged to the old card and aren't drawn.)
     */
    void show(String kickerText, int kickerColor, String headlineText, String detail, End a, End b, String wordA, String wordB, int accent, String hintText, Runnable ok, long ms) {
        boolean air = GlassPanel.look.equals("air");
        // the bar and its tag follow the look (set each time: the look can change in Settings)
        final float rad = GlassPanel.corner(1.95f * h.R);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(air ? 0xFF0B0B0D : 0xFA0B0D12); bg.setCornerRadius(rad);
        card.setBackground(bg);
        card.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rad); }
        });
        String k = kickerText == null ? "" : kickerText.trim();
        int dot = k.lastIndexOf('\u00B7');
        String what = dot >= 0 ? k.substring(dot + 1).trim() : k;
        if (k.startsWith("FANTASY") && dot >= 0) what = "FANTASY " + what;
        tag.setText(what);
        float in = air ? 0 : .55f * h.R;
        GlassPanel.onButton(tag, Math.max(0, rad - in), h.px(1.3f), 0);
        tag.setAllCaps(true); tag.setLetterSpacing(.06f);
        LinearLayout.LayoutParams tl = (LinearLayout.LayoutParams) tag.getLayoutParams();
        tl.setMargins(Math.round(in), Math.round(in), 0, Math.round(in)); tag.setLayoutParams(tl);
        headline.setText(headlineText == null ? "" : headlineText.replaceAll("\\s{2,}", " "));
        String d = detail == null ? "" : detail.replaceAll("\\s{2,}", " ").trim();
        line.setText(d); line.setVisibility(d.isEmpty() ? GONE : VISIBLE);
        setLogo(logoA, a); setLogo(logoB, b);
        setHint(hintText, ok);
        // its place
        boolean was = bottom;
        bottom = wantsBottom();
        FrameLayout.LayoutParams cl = (FrameLayout.LayoutParams) card.getLayoutParams();
        cl.gravity = bottom ? Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL : Gravity.TOP | Gravity.RIGHT;
        float side = h.SW < 60 ? 3 : 5;
        cl.setMargins(0, bottom ? 0 : h.px(2.5f + h.TOP), bottom ? 0 : h.px(side), bottom ? h.px(4 + h.BOT) : 0);
        card.setLayoutParams(cl);
        if (getVisibility() != VISIBLE || was != bottom) { setVisibility(VISIBLE); away(); card.setAlpha(0f); }
        bringToFront();
        up = true;
        card.animate().cancel();
        card.animate().translationX(0).translationY(0).alpha(1f).setDuration(Ui.SLIDE).setInterpolator(TileView.EASE).start();
        removeCallbacks(autoHide);
        postDelayed(autoHide, ms);
        h.act.haptic();
    }
    private void setLogo(final ImageView logo, End t) {
        logo.setImageDrawable(null);
        if (t == null || t.logo.isEmpty()) { logo.setVisibility(GONE); return; }
        logo.setVisibility(VISIBLE);
        final String u = t.logo;
        h.imgs.load("sa:" + u, new HomeImages.Source() { @Override public byte[] bytes() throws Exception { return HomeImages.http(u, null); } }, h.px(5.6f), h.px(5.6f),
                new HomeImages.Done() { @Override public void done(Bitmap bm) { if (bm != null) logo.setImageBitmap(bm); } });
    }
    /** a finger on the bar (phones) */
    boolean hitCard(float x, float y) { return h.hit(card, x, y, .6f); }
    /** what OK does while it's up (the channel was found a moment later, say) */
    void setHint(String text, Runnable ok) {
        onOk = ok;
        boolean none = text == null || text.isEmpty();
        hint.setText(none ? "" : text.replaceAll("\\s{2,}", " "));
        hint.setVisibility(none ? GONE : VISIBLE); rule.setVisibility(none ? GONE : VISIBLE);
        // (room at the bar's end after whatever is last on it)
        card.setPadding(0, 0, h.px(none ? 1.5f : 1.6f), 0);
    }
    boolean shown() { return up && getVisibility() == VISIBLE; }
    boolean takesOk() { return shown() && onOk != null && card.getAlpha() > .5f; }
    void ok() { Runnable r = onOk; hide(); if (r != null) r.run(); }

    private Runnable after;
    /** away again (after: what to do once it's gone, e.g. the next alert) */
    void hide() {
        if (!up) return;
        up = false; onOk = null;
        removeCallbacks(autoHide);
        card.animate().cancel();
        card.animate().translationX(bottom ? 0 : h.px(5)).translationY(bottom ? h.px(3) : 0).alpha(0f).setDuration(Ui.BASE).setInterpolator(TileView.EASE).withEndAction(new Runnable() { @Override public void run() {
            if (up) return;
            setVisibility(GONE);
            Runnable a = after; after = null;
            if (a != null) a.run();
        } }).start();
    }
    void onHidden(Runnable a) { after = a; }
    private final Runnable autoHide = new Runnable() { @Override public void run() { hide(); } };
}
