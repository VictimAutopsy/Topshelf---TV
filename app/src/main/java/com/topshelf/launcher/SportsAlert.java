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
 * Game alerts as a sports notification, dropping in from the top middle of the screen (they were a menu,
 * a toast at the foot, or a card in the corner): the two teams' colours at either end with their logos, the
 * league and what happened, the matchup or the score in big condensed figures, and "OK · Watch on …" when
 * there's a channel showing it.
 *
 * Used for: a game of yours starting soon, one of your teams scoring, and Yahoo Fantasy plays.
 */
final class SportsAlert extends FrameLayout {
    private final NativeHome h;
    private final LinearLayout card;
    private final FrameLayout endA, endB;
    private final ImageView logoA, logoB;
    private final TextView badgeA, badgeB, kicker, headline, line, hint;
    private final Typeface cond;
    private boolean up;

    SportsAlert(NativeHome home) {
        super(home.getContext());
        h = home;
        cond = SportsLook.cond();
        setClipChildren(false);
        card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xFA0B0D12); bg.setCornerRadius(1.1f * h.R);
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), GlassPanel.corner(1.1f * h.R)); }
        });
        card.setElevation(h.px(1.4f));
        // the two ends: each team's colour, its logo (or a short word when it's not a game: TD, FINAL…)
        endA = new FrameLayout(getContext()); endB = new FrameLayout(getContext());
        logoA = new ImageView(getContext()); logoB = new ImageView(getContext());
        badgeA = h.text(cond, 2f, Color.WHITE); badgeB = h.text(cond, 2f, Color.WHITE);
        for (Object[] e : new Object[][] { { endA, logoA, badgeA }, { endB, logoB, badgeB } }) {
            FrameLayout f = (FrameLayout) e[0]; ImageView iv = (ImageView) e[1]; TextView b = (TextView) e[2];
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            f.addView(iv, new FrameLayout.LayoutParams(h.px(5.6f), h.px(5.6f), Gravity.CENTER));
            b.setGravity(Gravity.CENTER); b.setLetterSpacing(.04f);
            f.addView(b, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        }
        card.addView(endA, new LinearLayout.LayoutParams(h.px(9), LayoutParams.MATCH_PARENT));
        LinearLayout mid = new LinearLayout(getContext());
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER_HORIZONTAL);
        mid.setPadding(h.px(2), h.px(1.2f), h.px(2), h.px(1.3f));
        kicker = h.text(h.fSemi, Ui.TEXT_S, SportsLook.GOLD); kicker.setLetterSpacing(.14f); kicker.setGravity(Gravity.CENTER);
        headline = h.text(cond, 2.6f, Color.WHITE); headline.setGravity(Gravity.CENTER); headline.setSingleLine(true); headline.setEllipsize(android.text.TextUtils.TruncateAt.END);
        headline.setFontFeatureSettings("tnum");
        line = h.text(h.fMed, Ui.TEXT_L, Ui.WHITE_95); line.setGravity(Gravity.CENTER); line.setMaxLines(2); line.setEllipsize(android.text.TextUtils.TruncateAt.END);
        hint = h.text(h.fSemi, Ui.TEXT_M, 0xFF111418); hint.setGravity(Gravity.CENTER);
        GradientDrawable hb = new GradientDrawable(); hb.setColor(Color.WHITE); hb.setCornerRadius(1.2f * h.R);
        hint.setBackground(hb); hint.setPadding(h.px(1.4f), h.px(.35f), h.px(1.4f), h.px(.35f));
        mid.addView(kicker, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); hl.topMargin = h.px(.2f);
        mid.addView(headline, hl);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); ll.topMargin = h.px(.15f);
        mid.addView(line, ll);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); il.topMargin = h.px(.8f);
        mid.addView(hint, il);
        card.addView(mid, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        card.addView(endB, new LinearLayout.LayoutParams(h.px(9), LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(h.px(Math.min(66f, h.SW - 4)), LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        cl.topMargin = h.px(2f);
        addView(card, cl);
        setVisibility(GONE);
    }

    /** a team end of the notification: {abbreviation, colour (#rrggbb), logo url} */
    static final class End { String abbr = "", color = "", logo = ""; }

    private Runnable onOk;

    /**
     * kicker: "NFL · STARTING SOON"; headline: the big line ("Cowboys score!", "ARI 7 – 14 DAL"); detail: a smaller
     * line; a / b: the two teams (or null: then badge words A / B on the accent colour); hint: "OK · Watch on FOX" or "".
     */
    void show(String kickerText, int kickerColor, String headlineText, String detail, End a, End b, String wordA, String wordB, int accent, String hintText, Runnable ok, long ms) {
        // the card's corners and its OK button follow the look
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xFA0B0D12); bg.setCornerRadius(GlassPanel.corner(1.1f * h.R));
        card.setBackground(bg); card.invalidateOutline();
        GlassPanel.onButton(hint, 1.2f * h.R, h.px(1.4f), h.px(.35f));
        kicker.setText(kickerText); kicker.setTextColor(kickerColor);
        headline.setText(headlineText);
        line.setText(detail == null ? "" : detail); line.setVisibility(detail == null || detail.isEmpty() ? GONE : VISIBLE);
        setEnd(endA, logoA, badgeA, a, wordA, accent);
        setEnd(endB, logoB, badgeB, b, wordB, accent);
        setHint(hintText, ok);
        if (getVisibility() != VISIBLE) { setVisibility(VISIBLE); card.setTranslationY(-h.px(14)); card.setAlpha(0f); }
        bringToFront();
        up = true;
        card.animate().cancel();
        card.animate().translationY(0).alpha(1f).setDuration(Ui.SLIDE).setInterpolator(TileView.SPRING).start();
        removeCallbacks(autoHide);
        postDelayed(autoHide, ms);
        h.act.haptic();
    }
    private void setEnd(FrameLayout end, final ImageView logo, TextView badge, End t, String word, int accent) {
        logo.setImageDrawable(null);
        if (t == null) {
            end.setBackgroundColor(accent);
            badge.setText(word == null ? "" : word); badge.setVisibility(VISIBLE);
            return;
        }
        int c;
        try { c = t.color.isEmpty() ? 0xFF2A3340 : Color.parseColor(t.color.startsWith("#") ? t.color : "#" + t.color); } catch (Exception e) { c = 0xFF2A3340; }
        end.setBackgroundColor(c | 0xFF000000);
        badge.setText(t.abbr); badge.setVisibility(t.logo.isEmpty() ? VISIBLE : GONE);
        if (!t.logo.isEmpty()) {
            final String u = t.logo;
            h.imgs.load("sa:" + u, new HomeImages.Source() { @Override public byte[] bytes() throws Exception { return HomeImages.http(u, null); } }, h.px(5.6f), h.px(5.6f),
                    new HomeImages.Done() { @Override public void done(Bitmap bm) { if (bm != null) logo.setImageBitmap(bm); } });
        }
    }
    /** what OK does while it's up (the channel was found a moment later, say) */
    void setHint(String text, Runnable ok) {
        onOk = ok;
        hint.setText(text == null ? "" : text);
        hint.setVisibility(text == null || text.isEmpty() ? GONE : VISIBLE);
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
        card.animate().translationY(-h.px(14)).alpha(0f).setDuration(Ui.BASE).setInterpolator(TileView.EASE).withEndAction(new Runnable() { @Override public void run() {
            if (up) return;
            setVisibility(GONE);
            Runnable a = after; after = null;
            if (a != null) a.run();
        } }).start();
    }
    void onHidden(Runnable a) { after = a; }
    private final Runnable autoHide = new Runnable() { @Override public void run() { hide(); } };
}
