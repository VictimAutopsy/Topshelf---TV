package com.topshelf.launcher;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Playing a movie or an episode from an addon's stream, full screen, with a plain media-player bar (the same
 * look as Live TV's): the title, a seek bar (▲ to choose it, ◀ ▶ to move along it), back 10 s / play-pause /
 * forward 30 s, and More (subtitles — the file's own and the addons' — audio, from the start, other streams,
 * next episode). Carries on where you left off; remembers where you got to.
 */
final class VodPlayer extends FrameLayout {
    private static final int ID0 = 2000000;
    private final NativeHome h;
    private final float R;
    private final FrameLayout bar;
    private final TextView tTitle, tSub, loading, cues;
    private final SubPics pics;
    private final Bar ctl;
    private FrameLayout capsule;
    private TitleView from;
    private JSONObject item, stream, next;
    private String videoId = "", label = "";
    private int pid = ID0, row = 1, sel = 1;
    private boolean ready, paused, scrubbing;
    private long scrubTo, lastScrubAt; private int scrubRun;
    private final Map<View, Integer> hidden = new HashMap<>();
    private String subLang = "", playUrl = "";
    private java.util.Map<String, String> headers = new HashMap<>();
    private final List<JSONObject> addonSubs = new ArrayList<>();
    private volatile boolean subsAsked;
    private String[] sideSub;                 // the addon subtitle added to the player (null: none)
    private double startPct;                  // (carrying on from another device: only a percentage is known)
    private boolean scrobbling;

    VodPlayer(NativeHome host) {
        super(host.getContext());
        h = host; R = host.R;
        setClickable(true);
        loading = h.text(h.fSemi, 1.6f, Color.WHITE); loading.setGravity(Gravity.CENTER);
        loading.setShadowLayer(R, 0, 0, 0xCC000000);
        addView(loading, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        cues = h.text(h.fMed, 2.2f, Color.WHITE); cues.setGravity(Gravity.CENTER); cues.setVisibility(GONE);
        LayoutParams cl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cl.bottomMargin = h.px(4.5f);
        addView(cues, cl);
        pics = new SubPics(getContext());
        addView(pics, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        /* The bar: what's playing up in the top left (over a soft shade), and the controls across the whole foot of
           the screen over a deeper shade — the timeline from edge to edge (lined up with the title), play controls in
           the middle, From the start and Episodes on the left, Subtitles, Audio, Next episode and More on the right. */
        bar = new FrameLayout(getContext());
        bar.setClipChildren(false);
        View topShade = new View(getContext());
        topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { 0xC0000000, 0x66000000, 0x00000000 }));
        bar.addView(topShade, new LayoutParams(LayoutParams.MATCH_PARENT, h.px(15)));
        View footShade = new View(getContext());
        footShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] { 0xD9000000, 0xA6000000, 0x4D000000, 0x00000000 }));
        bar.addView(footShade, new LayoutParams(LayoutParams.MATCH_PARENT, h.px(26), Gravity.BOTTOM));
        LinearLayout col = new LinearLayout(getContext()); col.setOrientation(LinearLayout.VERTICAL);
        tSub = h.text(h.fMed, 1.1f, 0xC7FFFFFF); tSub.setSingleLine(true); tSub.setEllipsize(TextUtils.TruncateAt.END);
        tTitle = h.text(h.fSemi, 2.3f, Color.WHITE); tTitle.setSingleLine(true); tTitle.setEllipsize(TextUtils.TruncateAt.END);
        tTitle.setShadowLayer(.8f * R, 0, .1f * R, 0x99000000); tSub.setShadowLayer(.6f * R, 0, .1f * R, 0x99000000);
        col.addView(tSub);
        LinearLayout.LayoutParams ttl = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); ttl.topMargin = h.px(.3f);
        col.addView(tTitle, ttl);
        LayoutParams cll = new LayoutParams(h.px(Math.min(80, h.SW - 10)), LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT);
        cll.leftMargin = h.px(5); cll.topMargin = h.px(3.6f);
        bar.addView(col, cll);
        capsule = new FrameLayout(getContext());
        capsule.setClipChildren(false);
        ctl = new Bar(getContext());
        capsule.addView(ctl, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        LayoutParams capl = new LayoutParams(LayoutParams.MATCH_PARENT, h.px(13.2f), Gravity.BOTTOM);
        capl.bottomMargin = h.px(2.4f);
        bar.addView(capsule, capl);
        addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        buildPauseInfo();
        buildOpening();
        skipBtn = h.text(h.fSemi, 1.5f, 0xFF111111);
        GradientDrawable sb = new GradientDrawable(); sb.setCornerRadius(h.px(2.4f)); sb.setColor(0xF2FFFFFF); skipBtn.setBackground(sb);
        skipBtn.setPadding(h.px(2.2f), h.px(1.1f), h.px(2.2f), h.px(1.1f));
        skipBtn.setElevation(h.px(.6f));
        LayoutParams kl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT);
        kl.rightMargin = h.px(5); kl.bottomMargin = h.px(19);
        addView(skipBtn, kl);
        skipBtn.setAlpha(0f); skipBtn.setVisibility(GONE);
        // Up next: the next episode, counting down to it
        upCard = new LinearLayout(getContext()); upCard.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable ub = new GradientDrawable(); ub.setCornerRadius(h.px(1.8f)); ub.setColor(0xE6141820); ub.setStroke(Math.max(1, h.px(.08f)), 0x33FFFFFF);
        upCard.setBackground(ub);
        upCard.setPadding(h.px(2f), h.px(1.6f), h.px(2f), h.px(1.6f));
        upCard.setElevation(h.px(.8f));
        upLab = h.text(h.fSemi, 1f, 0xFF9FD3FF); upLab.setLetterSpacing(.1f); upCard.addView(upLab);
        upName = h.text(h.fSemi, 1.7f, Color.WHITE); upName.setSingleLine(true); upName.setEllipsize(TextUtils.TruncateAt.END); upName.setMaxWidth(h.px(36));
        LinearLayout.LayoutParams unl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); unl.topMargin = h.px(.5f);
        upCard.addView(upName, unl);
        LinearLayout ubtns = new LinearLayout(getContext()); ubtns.setGravity(Gravity.CENTER_VERTICAL);
        upPlay = h.text(h.fSemi, 1.3f, 0xFF111111);
        GradientDrawable pb = new GradientDrawable(); pb.setCornerRadius(h.px(1.6f)); pb.setColor(Color.WHITE); upPlay.setBackground(pb);
        upPlay.setPadding(h.px(1.6f), h.px(.7f), h.px(1.6f), h.px(.7f));
        ubtns.addView(upPlay);
        upCredits = h.text(h.fMed, 1.2f, 0xD9FFFFFF); upCredits.setText("Back · watch the credits");
        LinearLayout.LayoutParams ucl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); ucl.leftMargin = h.px(1.4f);
        ubtns.addView(upCredits, ucl);
        LinearLayout.LayoutParams ubl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); ubl.topMargin = h.px(1.1f);
        upCard.addView(ubtns, ubl);
        LayoutParams upl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT);
        upl.rightMargin = h.px(5); upl.bottomMargin = h.px(19);
        addView(upCard, upl);
        upCard.setAlpha(0f); upCard.setVisibility(GONE);
        setVisibility(GONE);
    }

    /* ------------------------------------------------------------------ paused (as Nuvio does it): after a few seconds
       with nothing pressed, the controls go and the picture dims under what you're watching — the title's logo (or its
       name), the year, length, rating and genres, the episode, and the description. OK plays on. */
    private FrameLayout pauseInfo;
    private android.widget.ImageView piLogo;
    private TextView piKicker, piName, piMeta, piEp, piDesc, piHint;
    private boolean pauseInfoUp;
    /* ---- the opening screen (like Nuvio's): while the stream opens, the title's backdrop fills the screen, dimmed,
       with its logo in the middle gently breathing, the episode under it, and a thin line sweeping beneath. It fades
       away the moment the picture starts. Only Back works meanwhile (it stops, as always). */
    private FrameLayout opening;
    private android.widget.ImageView opBg, opLogo;
    private TextView opName, opEp, opNote;
    private Sweep opLine;
    private boolean openingUp;
    private String carryOnNote;               // (shown once the picture's up)
    private void buildOpening() {
        opening = new FrameLayout(getContext());
        opening.setBackgroundColor(Color.BLACK);
        opBg = new android.widget.ImageView(getContext()); opBg.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        opening.addView(opBg, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        View dim = new View(getContext()); dim.setBackgroundColor(0x99000000);
        opening.addView(dim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        View vig = new View(getContext());
        GradientDrawable vg = new GradientDrawable(); vg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        vg.setColors(new int[] { 0x00000000, 0x8C000000 }); vg.setGradientRadius(h.px(78)); vig.setBackground(vg);
        opening.addView(vig, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        LinearLayout col = new LinearLayout(getContext()); col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER_HORIZONTAL);
        opLogo = new android.widget.ImageView(getContext()); opLogo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER); opLogo.setAdjustViewBounds(true);
        opLogo.setMaxWidth(h.px(40)); opLogo.setMaxHeight(h.px(14));
        col.addView(opLogo, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        opName = h.text(h.fSemi, 4.2f, Color.WHITE); opName.setGravity(Gravity.CENTER); opName.setMaxLines(2); opName.setEllipsize(TextUtils.TruncateAt.END);
        opName.setLetterSpacing(-.02f); opName.setShadowLayer(R, 0, .1f * R, 0x99000000); opName.setMaxWidth(h.px(Math.min(70, h.SW - 10)));
        col.addView(opName, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        opEp = h.text(h.fSemi, 1.5f, 0xE6FFFFFF); opEp.setGravity(Gravity.CENTER); opEp.setMaxLines(1); opEp.setEllipsize(TextUtils.TruncateAt.END);
        opEp.setShadowLayer(.6f * R, 0, .1f * R, 0x99000000); opEp.setMaxWidth(h.px(Math.min(70, h.SW - 10)));
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); el.topMargin = h.px(1.6f);
        col.addView(opEp, el);
        opLine = new Sweep(getContext());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(h.px(16), h.px(.5f)); sl.topMargin = h.px(3f);
        col.addView(opLine, sl);
        opNote = h.text(h.fMed, 1.1f, 0x99FFFFFF); opNote.setGravity(Gravity.CENTER); opNote.setMaxLines(1); opNote.setEllipsize(TextUtils.TruncateAt.END);
        opNote.setMaxWidth(h.px(Math.min(70, h.SW - 10)));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); nl.topMargin = h.px(1.4f);
        col.addView(opNote, nl);
        opening.addView(col, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        opening.setVisibility(GONE);
        addView(opening, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }
    private void showOpening(long carryOnAt) {
        openingUp = true;
        opening.animate().cancel(); opening.setAlpha(1f); opening.setVisibility(VISIBLE); opening.bringToFront();
        String name = item.optString("name");
        opName.setText(name); opName.setVisibility(VISIBLE);
        opLogo.setImageDrawable(null); opLogo.setVisibility(GONE);
        opLogo.animate().cancel(); opLogo.setAlpha(1f); opName.setAlpha(1f);
        boolean series = "series".equals(item.optString("type"));
        opEp.setText(series ? label : ""); opEp.setVisibility(series && !label.isEmpty() ? VISIBLE : GONE);
        opNote.setText(carryOnAt > 0 ? "Carrying on at " + TitleView.clock(carryOnAt) : stream.optString("_addon"));
        opNote.setVisibility(opNote.getText().length() == 0 ? GONE : VISIBLE);
        // the backdrop (slowly drifting in), and the logo in place of the name once it's here
        opBg.setImageDrawable(null); opBg.animate().cancel(); opBg.setScaleX(1.04f); opBg.setScaleY(1.04f); opBg.setAlpha(0f);
        final String bg = item.optString("background"), logo = item.optString("logo");
        final int my = pid;
        if (bg.startsWith("http")) h.imgs.load("op:" + bg, new HomeImages.Source() { @Override public byte[] bytes() throws Exception { return HomeImages.http(bg, null); } }, h.W, h.H,
                new HomeImages.Done() { @Override public void done(android.graphics.Bitmap b) {
                    if (b == null || !openingUp || my != pid) return;
                    opBg.setImageBitmap(b);
                    opBg.animate().alpha(1f).setDuration(500).start();
                    opBg.animate().scaleX(1f).scaleY(1f).setDuration(12000).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                } });
        if (logo.startsWith("http")) h.imgs.load("op:" + logo, new HomeImages.Source() { @Override public byte[] bytes() throws Exception { return HomeImages.http(logo, null); } }, h.px(40), h.px(14),
                new HomeImages.Done() { @Override public void done(android.graphics.Bitmap b) {
                    if (b == null || !openingUp || my != pid) return;
                    opLogo.setImageBitmap(b); opLogo.setVisibility(VISIBLE); opName.setVisibility(GONE);
                    breathe();
                } });
        opLine.start();
    }
    private void breathe() {
        if (!openingUp) return;
        final View v = opLogo.getVisibility() == VISIBLE ? opLogo : opName;
        v.animate().alpha(v.getAlpha() > .9f ? .7f : 1f).setDuration(1400).setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction(new Runnable() { @Override public void run() { breathe(); } }).start();
    }
    /** the picture's here: the opening screen fades away (at once when the player closes) */
    private void hideOpening(boolean fade) {
        if (!openingUp) return;
        openingUp = false;
        opLine.stop();
        opLogo.animate().cancel(); opName.animate().cancel(); opBg.animate().cancel();
        if (fade) opening.animate().alpha(0f).setDuration(450).withEndAction(new Runnable() { @Override public void run() { if (!openingUp) { opening.setVisibility(GONE); opBg.setImageDrawable(null); opLogo.setImageDrawable(null); } } }).start();
        else { opening.animate().cancel(); opening.setVisibility(GONE); opBg.setImageDrawable(null); opLogo.setImageDrawable(null); }
    }
    /** a thin line with a bright stretch sweeping across it, over and over */
    private final class Sweep extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private long t0; private boolean on;
        Sweep(android.content.Context c) { super(c); }
        void start() { on = true; t0 = SystemClock.uptimeMillis(); invalidate(); }
        void stop() { on = false; }
        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), hh = getHeight();
            float rad = hh / 2f;
            p.setColor(0x33FFFFFF); r.set(0, 0, w, hh); c.drawRoundRect(r, rad, rad, p);
            if (!on) return;
            float f = ((SystemClock.uptimeMillis() - t0) % 1500) / 1500f;
            float e = f < .5f ? 2 * f * f : 1 - (float) Math.pow(-2 * f + 2, 2) / 2;     // (eases in and out)
            float seg = w * .35f, x = -seg + (w + seg) * e;
            p.setColor(Color.WHITE); r.set(Math.max(0, x), 0, Math.min(w, x + seg), hh);
            if (r.width() > 0) c.drawRoundRect(r, rad, rad, p);
            postInvalidateOnAnimation();
        }
    }

    private void buildPauseInfo() {
        pauseInfo = new FrameLayout(getContext());
        View dim = new View(getContext());
        dim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] { 0xF0000000, 0xB3000000, 0x59000000 }));
        pauseInfo.addView(dim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        LinearLayout col = new LinearLayout(getContext()); col.setOrientation(LinearLayout.VERTICAL);
        piKicker = h.text(h.fSemi, 1.1f, 0xB3FFFFFF); piKicker.setLetterSpacing(.16f); piKicker.setText("PAUSED");
        col.addView(piKicker);
        piLogo = new android.widget.ImageView(getContext()); piLogo.setScaleType(android.widget.ImageView.ScaleType.FIT_START); piLogo.setAdjustViewBounds(true);
        piLogo.setMaxWidth(h.px(34)); piLogo.setMaxHeight(h.px(11));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); ll.topMargin = h.px(1.4f);
        col.addView(piLogo, ll);
        piName = h.text(h.fSemi, 4f, Color.WHITE); piName.setLetterSpacing(-.02f); piName.setMaxLines(2); piName.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); nl.topMargin = h.px(1f);
        col.addView(piName, nl);
        piMeta = h.text(h.fMed, 1.35f, 0xD9FFFFFF);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); ml.topMargin = h.px(1.2f);
        col.addView(piMeta, ml);
        piEp = h.text(h.fSemi, 1.6f, Color.WHITE); piEp.setMaxLines(1); piEp.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); el.topMargin = h.px(1.8f);
        col.addView(piEp, el);
        piDesc = h.text(h.fReg, 1.4f, 0xCCFFFFFF); piDesc.setMaxLines(5); piDesc.setEllipsize(TextUtils.TruncateAt.END); piDesc.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(h.px(54), LayoutParams.WRAP_CONTENT); dl.topMargin = h.px(1.2f);
        col.addView(piDesc, dl);
        piHint = h.text(h.fMed, 1.15f, 0x99FFFFFF); piHint.setText("OK · Play   ·   Back · Stop");
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); hl.topMargin = h.px(2.6f);
        col.addView(piHint, hl);
        LayoutParams cl = new LayoutParams(h.px(Math.min(62, h.SW - 10)), LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.LEFT);
        cl.leftMargin = h.px(6);
        pauseInfo.addView(col, cl);
        pauseInfo.setAlpha(0f); pauseInfo.setVisibility(GONE);
        addView(pauseInfo, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }
    private final Runnable pauseInfoLater = new Runnable() { @Override public void run() {
        if (!paused || !shownNow() || h.menuOpenNow() || item == null) return;
        showPauseInfo();
    } };
    private void showPauseInfo() {
        String name = item.optString("name");
        piName.setText(name);
        List<String> m = new ArrayList<>();
        if (!item.optString("year").isEmpty()) m.add(item.optString("year").replaceAll("[–-]$", ""));
        if (!item.optString("runtime").isEmpty()) m.add(item.optString("runtime"));
        if (!item.optString("rating").isEmpty() && !item.optString("rating").equals("0")) m.add("★ " + item.optString("rating"));
        JSONArray g = item.optJSONArray("genres");
        if (g != null) { List<String> gs = new ArrayList<>(); for (int i = 0; i < g.length() && i < 3; i++) gs.add(g.optString(i)); if (!gs.isEmpty()) m.add(TextUtils.join(", ", gs)); }
        piMeta.setText(TextUtils.join("   ·   ", m)); piMeta.setVisibility(m.isEmpty() ? GONE : VISIBLE);
        boolean series = "series".equals(item.optString("type"));
        piEp.setText(series ? label : ""); piEp.setVisibility(series && !label.isEmpty() ? VISIBLE : GONE);
        String d = item.optString("desc", item.optString("description"));
        piDesc.setText(d); piDesc.setVisibility(d.trim().isEmpty() ? GONE : VISIBLE);
        // the title's logo when it has one (the name until it comes, and if it doesn't)
        piLogo.setImageDrawable(null); piLogo.setVisibility(GONE); piName.setVisibility(VISIBLE);
        final String logo = item.optString("logo");
        if (logo.startsWith("http")) h.imgs.load("pi:" + logo, new HomeImages.Source() { @Override public byte[] bytes() throws Exception { return HomeImages.http(logo, null); } }, h.px(34), h.px(11),
                new HomeImages.Done() { @Override public void done(android.graphics.Bitmap b) { if (b != null && pauseInfoUp) { piLogo.setImageBitmap(b); piLogo.setVisibility(VISIBLE); piName.setVisibility(GONE); } } });
        pauseInfoUp = true;
        removeCallbacks(barHide);
        bar.animate().cancel(); bar.animate().alpha(0f).setDuration(300).start();
        pauseInfo.setVisibility(VISIBLE); pauseInfo.bringToFront();
        pauseInfo.animate().cancel(); pauseInfo.setTranslationX(-h.px(2));
        pauseInfo.animate().alpha(1f).translationX(0).setDuration(450).setInterpolator(TileView.EASE).start();
    }
    private void hidePauseInfo(boolean animate) {
        if (pauseInfo == null) return;
        pauseInfoUp = false;
        pauseInfo.animate().cancel();
        if (!animate) { pauseInfo.setAlpha(0f); pauseInfo.setVisibility(GONE); return; }
        pauseInfo.animate().alpha(0f).setDuration(250).withEndAction(new Runnable() { @Override public void run() { if (!pauseInfoUp) pauseInfo.setVisibility(GONE); } }).start();
    }

    /* ------------------------------------------------------------------ skipping intros, recaps and credits
       (as ARVIO does, Apache 2.0): the parts of an episode come from IntroDB (shows, crowd-sourced), or for
       anime from AniSkip (the show's MyAnimeList number found through ARM). While one is playing a button
       shows at the bottom right; OK (with the bar away) jumps past it — at the credits, to the next episode. */
    private final TextView skipBtn;
    /* ------------------------------------------------------------------ up next: as the credits start (or 30 s
       before the end when nobody's marked them), a card for the next episode counts down 10 s and plays it from the
       same source; OK plays it now, Back stays for the credits */
    private final LinearLayout upCard;
    private final TextView upLab, upName, upPlay, upCredits;
    private boolean upShown, upDismissed;
    private int upLeft;
    private final Runnable upTick = new Runnable() { @Override public void run() {
        if (!upShown) return;
        if (paused) { postDelayed(this, 1000); return; }
        upLeft--;
        if (upLeft <= 0) { goNext(); return; }
        upPlay.setText("▶  Play now · " + upLeft);
        postDelayed(this, 1000);
    } };
    private long upAt() {
        NativePlayer np = h.act.nativePlayer();
        long d = dur(np);
        for (long[] g : segs) if (g[2] == 2) return g[0];
        return d > 10 * 60000 ? d - 30000 : -1;
    }
    private void checkUpNext() {
        if (next == null || from == null || !ready || !h.S.optBoolean("autoNext", true)) return;
        NativePlayer np = h.act.nativePlayer();
        if (np == null) return;
        long at = upAt(), pos = np.positionMs();
        if (at < 0) return;
        if (upShown && pos < at - 5000) { hideUpNext(); upDismissed = false; return; }       // (went back before the credits)
        if (upShown || upDismissed || pos < at) return;
        upShown = true;
        upLeft = 10;
        upLab.setText("UP NEXT · S" + next.optInt("season") + " · E" + next.optInt("episode", next.optInt("number")));
        upName.setText(next.optString("name", next.optString("title", "Next episode")));
        upPlay.setText("▶  Play now · " + upLeft);
        hideSkip();
        upCard.setVisibility(VISIBLE); upCard.bringToFront();
        upCard.setTranslationX(h.px(3)); upCard.animate().alpha(1f).translationX(0).setDuration(300).start();
        removeCallbacks(upTick); postDelayed(upTick, 1000);
    }
    private void hideUpNext() {
        upShown = false; removeCallbacks(upTick);
        upCard.animate().cancel();
        upCard.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() { @Override public void run() { if (!upShown) upCard.setVisibility(GONE); } }).start();
    }
    /** the next episode now (this one counts as watched) */
    private void goNext() {
        if (next == null) return;
        int[] se = seasonEpisode();
        Progress.put(getContext(), videoId, 1, 1, "series".equals(item.optString("type")) ? item.optString("id") : "", se[0], se[1], null);
        final JSONObject nx = next, was = stream; final TitleView tv = from;
        upShown = false;
        close();
        if (tv != null) tv.afterEpisode(nx, was);
    }
    private final List<long[]> segs = new ArrayList<>();           // {start, end, kind: 0 intro, 1 recap, 2 credits}
    private long[] segNow;
    private void askSegments(final String vid) {
        segs.clear(); segNow = null; hideSkip();
        String[] p = vid.split(":");
        if (p.length < 3 || !p[0].startsWith("tt")) return;
        final String imdb = p[0]; final int s, e;
        try { s = Integer.parseInt(p[1]); e = Integer.parseInt(p[2]); } catch (Exception x) { return; }
        new Thread(new Runnable() { @Override public void run() {
            final List<long[]> out = new ArrayList<>();
            String src = "";
            try {
                JSONObject o = getJson("https://api.introdb.app/segments?imdb_id=" + imdb + "&season=" + s + "&episode=" + e);
                if (o != null) {
                    addSeg(out, o.optJSONObject("recap"), 1); addSeg(out, o.optJSONObject("intro"), 0); addSeg(out, o.optJSONObject("outro"), 2);
                    if (!out.isEmpty()) src = "IntroDB";
                }
            } catch (Throwable ignored) { }
            if (out.isEmpty()) try {
                byte[] ad = HomeImages.http("https://arm.haglund.dev/api/v2/imdb?id=" + imdb, null);
                String arm = ad == null ? null : new String(ad, "UTF-8");
                org.json.JSONArray a = arm == null ? null : new org.json.JSONArray(arm);
                int mal = a == null || a.length() == 0 ? 0 : a.optJSONObject(0).optInt("myanimelist");
                if (mal > 0) {
                    JSONObject o = getJson("https://api.aniskip.com/v2/skip-times/" + mal + "/" + e + "?types=op&types=ed&types=recap&types=mixed-op&types=mixed-ed&episodeLength=0");
                    org.json.JSONArray rs = o == null || !o.optBoolean("found") ? null : o.optJSONArray("results");
                    if (rs != null) for (int i = 0; i < rs.length(); i++) {
                        JSONObject r = rs.optJSONObject(i), iv = r == null ? null : r.optJSONObject("interval");
                        if (iv == null) continue;
                        String t = r.optString("skipType");
                        long a0 = Math.round(iv.optDouble("startTime") * 1000), b0 = Math.round(iv.optDouble("endTime") * 1000);
                        if (b0 > a0) out.add(new long[] { a0, b0, t.endsWith("ed") ? 2 : t.equals("recap") ? 1 : 0 });
                    }
                    if (!out.isEmpty()) src = "AniSkip";
                }
            } catch (Throwable ignored) { }
            final String from0 = src;
            post(new Runnable() { @Override public void run() {
                if (!vid.equals(videoId)) return;
                segs.clear(); segs.addAll(out);
                if (!out.isEmpty()) Diag.ev("player: " + out.size() + " skippable part(s) from " + from0);
            } });
        } }, "segments").start();
    }
    private static JSONObject getJson(String url) throws Exception {
        byte[] d = HomeImages.http(url, null);
        return d == null ? null : new JSONObject(new String(d, "UTF-8"));
    }
    private static void addSeg(List<long[]> out, JSONObject o, int kind) {
        if (o == null) return;
        long a = o.optLong("start_ms"), b = o.optLong("end_ms");
        if (a <= 0 && o.has("start_sec")) a = Math.round(o.optDouble("start_sec", 0) * 1000);
        if (b <= 0 && o.has("end_sec")) b = Math.round(o.optDouble("end_sec", 0) * 1000);
        if (b > a && a >= 0) out.add(new long[] { a, b, kind });
    }
    /** (twice a second) is a skippable part playing? */
    private void checkSkip() {
        if (segs.isEmpty() || !ready) { if (segNow != null) hideSkip(); return; }
        NativePlayer np = h.act.nativePlayer();
        if (np == null) return;
        long pos = np.positionMs();
        long[] in = null;
        for (long[] g : segs) if (pos >= g[0] && pos < g[1] - 1500) { in = g; break; }
        if (in == segNow) return;
        segNow = in;
        if (in == null) { hideSkip(); return; }
        if (in[2] == 2 && next != null && from != null && h.S.optBoolean("autoNext", true)) { segNow = null; return; }   // (the Up next card has the credits)
        skipBtn.setText(in[2] == 2 ? (next != null ? "Next episode  ›" : "Skip credits  ›") : in[2] == 1 ? "Skip recap  ›" : "Skip intro  ›");
        skipBtn.setVisibility(VISIBLE); skipBtn.bringToFront();
        skipBtn.animate().alpha(1f).setDuration(250).start();
    }
    private void hideSkip() { segNow = null; skipBtn.animate().cancel(); skipBtn.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() { @Override public void run() { if (segNow == null) skipBtn.setVisibility(GONE); } }).start(); }
    private boolean skipShown() { return segNow != null && skipBtn.getVisibility() == VISIBLE; }
    private void doSkip() {
        long[] g = segNow;
        if (g == null) return;
        NativePlayer np = h.act.nativePlayer();
        if (g[2] == 2 && next != null) { final JSONObject nx = next, was = stream; final TitleView tv = from; close(); if (tv != null) tv.afterEpisode(nx, was); return; }
        if (np != null) np.seekMs(g[1]);
        hideSkip();
    }

    boolean shownNow() { return getVisibility() == VISIBLE; }
    /** for the report */
    org.json.JSONObject state() {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("shown", shownNow()).put("ready", ready).put("pictured", pictured).put("paused", paused).put("alpha", getAlpha())
             .put("bar", bar.getAlpha()).put("loading", loading.getVisibility() == VISIBLE ? loading.getText().toString() : "")
             .put("w", getWidth()).put("h", getHeight()).put("index", h.indexOfChild(this)).put("children", h.getChildCount());
            android.graphics.drawable.Drawable bg = getBackground();
            if (bg instanceof android.graphics.drawable.ColorDrawable) o.put("bg", Integer.toHexString(((android.graphics.drawable.ColorDrawable) bg).getColor()));
        } catch (Exception ignored) { }
        return o;
    }

    /** something else playing here (catch-up from Live TV): told when it fails, so it can try another address */
    interface Fallback { void failed(String why); }
    private Fallback fallback;
    private long hintDur;                     // its length when the stream doesn't say (a catch-up show's)
    void play(TitleView tv, JSONObject item, String videoId, String label, JSONObject stream, JSONObject next) {
        fallback = null; hintDur = 0;
        start(tv, item, videoId, label, stream, next);
    }
    /** a show from Live TV's catch-up: no title page, no progress kept */
    void playOther(JSONObject item, String label, JSONObject stream, long lengthMs, Fallback fb) {
        fallback = fb; hintDur = Math.max(0, lengthMs);
        start(null, item, "", label, stream, null);
    }
    private long dur(NativePlayer np) { long d = np == null ? 0 : np.durationMs(); return d > 0 ? d : hintDur; }
    private void start(TitleView tv, JSONObject item, String videoId, String label, JSONObject stream, JSONObject next) {
        this.from = tv; this.item = item; this.videoId = videoId; this.label = label; this.stream = stream; this.next = next;
        ready = false; pictured = false; paused = false; scrubbing = false; row = 1; sel = 2;
        hidePauseInfo(false);
        upShown = false; upDismissed = false; removeCallbacks(upTick); upCard.setVisibility(GONE); upCard.setAlpha(0f);
        removeCallbacks(noPicture); removeCallbacks(waitLog); waits = 0; postDelayed(waitLog, 5000);
        postDelayed(noPicture, 60000);          // (still no picture after a minute, playing or not: a failed stream)
        tTitle.setText(item.optString("name"));
        String bd = TextUtils.join(" ", TitleView.badges((stream.optString("name") + " " + stream.optString("title")).toUpperCase(Locale.US)));
        tSub.setText((label.isEmpty() ? "" : label + "   ·   ") + stream.optString("_addon") + (bd.isEmpty() ? "" : "   ·   " + bd));
        loading.setVisibility(GONE);
        cues.setVisibility(GONE);
        // only the picture and this bar: everything else of the app steps aside (it would cover the video)
        hidden.clear();
        for (int i = 0; i < h.getChildCount(); i++) { View v = h.getChildAt(i); if (v != this && !h.overlay(v) && v.getVisibility() == VISIBLE) { hidden.put(v, VISIBLE); v.setVisibility(INVISIBLE); } }
        setVisibility(VISIBLE); bringToFront();
        removeCallbacks(barHide); bar.animate().cancel(); bar.setAlpha(0f);      // (the controls come with the picture)
        h.stopTrailers();
        final NativePlayer np = h.act.nativePlayer();
        if (np == null) { h.toast("The TV's video player isn't available"); close(); return; }
        long st0 = Progress.startAt(getContext(), videoId);
        Flight.vodNext(Flight.vodKind(st0 > 30000));
        startPct = st0 < 0 ? Progress.pct(getContext(), videoId) : 0;
        final long start = Math.max(0, st0);
        String url0 = stream.optString("url");
        // (resolved ahead by instant play: straight to the file, no waiting on the addon's redirect)
        String rd = Addons.get(getContext()).readyLink(url0);
        if (rd != null) Diag.ev("instant play: playing the link resolved ahead");
        final String url = rd != null ? rd : url0;
        playUrl = url;
        preparedNext = false;
        headers = streamHeaders(stream);
        addonSubs.clear(); subsAsked = false;
        // (black until the picture comes: nothing of the app shows through meanwhile)
        setBackgroundColor(Color.BLACK);
        final int myPid = ++pid;
        showOpening(start);
        np.playVod(myPid, url, start, null, headers);
        np.layout("fs", 0, 0, 0, 0, 0, 0);
        if (start > 0) carryOnNote = "Carrying on at " + TitleView.clock(start) + " · More › From the start";
        if (fallback == null) askSubs(); else subsAsked = true;
        askSegments(videoId);
        removeCallbacks(saveTick); postDelayed(saveTick, 10000);
        removeCallbacks(tick); postDelayed(tick, 500);
    }

    /* While a movie or episode plays, nothing of Home may show: the picture is on its own plane *under* the app's
       window, so any of Home's views that comes back (the screensaver's photos once came up over a playing show,
       with only its subtitles still on top) hides the video completely. Twice a second, anything of Home that has
       come back is put away again (and back as it was when the player closes), and the report says what it was. */
    private int strayLogs;
    private void keepHomeAway() {
        if (!shownNow()) return;
        for (int i = 0; i < h.getChildCount(); i++) {
            View v = h.getChildAt(i);
            if (v == this || h.overlay(v) || v.getVisibility() != VISIBLE) continue;
            if (!hidden.containsKey(v)) hidden.put(v, VISIBLE);
            v.setVisibility(INVISIBLE);
            if (strayLogs++ < 6) Diag.ev("vod: put away " + v.getClass().getSimpleName() + " that came up over the video (" + h.screenWhy() + ")");
        }
    }

    /** some addons ask for particular request headers for their links */
    private static java.util.Map<String, String> streamHeaders(JSONObject s) {
        java.util.Map<String, String> m = new HashMap<>();
        JSONObject bh = s.optJSONObject("behaviorHints");
        JSONObject ph = bh == null ? null : bh.optJSONObject("proxyHeaders");
        JSONObject rq = ph == null ? null : ph.optJSONObject("request");
        if (rq != null) for (java.util.Iterator<String> it = rq.keys(); it.hasNext(); ) { String k = it.next(); m.put(k, rq.optString(k)); }
        return m;
    }
    /** the addons' subtitles for this title, fetched alongside (only added to the player when you choose one) */
    private void askSubs() {
        final String vid = videoId, type = item.optString("type");
        new Thread(new Runnable() { @Override public void run() {
            final List<JSONObject> s = Addons.get(getContext()).subtitles(type, vid);
            post(new Runnable() { @Override public void run() { if (vid.equals(videoId)) { addonSubs.clear(); addonSubs.addAll(s); subsAsked = true; } } });
        } }, "subs").start();
    }
    /** an addon's subtitle: the player starts again at the same moment with it added and on */
    private void useAddonSub(JSONObject x) {
        NativePlayer np = h.act.nativePlayer();
        if (np == null) return;
        String lang = x.optString("lang");
        sideSub = new String[] { x.optString("url"), lang, NativeLive.langName(lang.length() > 3 ? lang.substring(0, 3) : lang, lang) + " · " + x.optString("_addon") };
        List<String[]> l = new ArrayList<>(); l.add(sideSub);
        long at = np.positionMs();
        ready = false; loading.setText("Adding subtitles…"); loading.setVisibility(VISIBLE);
        np.playVod(++pid, playUrl, at, l, headers);
        np.setPaused(paused);
        if (!lang.isEmpty()) h.prefs.edit().putString("vodSubLang", lang.length() > 2 ? lang.substring(0, 2) : lang).apply();
    }

    void close() {
        removeCallbacks(scrubLive); if (scrubbing) { scrubbing = false; NativePlayer sp = h.act.nativePlayer(); if (sp != null) sp.scrubDone(); }
        hidePauseInfo(false); removeCallbacks(pauseInfoLater);
        hideOpening(false); carryOnNote = null;
        save();
        scrobble("stop");
        removeCallbacks(saveTick); removeCallbacks(tick); removeCallbacks(barHide); removeCallbacks(noPicture); removeCallbacks(waitLog); removeCallbacks(upTick);
        upShown = false; upCard.setVisibility(GONE);
        segs.clear(); segNow = null; skipBtn.setVisibility(GONE); skipBtn.setAlpha(0f);
        pid++;
        NativePlayer np = h.act.nativePlayer();
        if (np != null) try { np.stop(); } catch (Throwable ignored) { }
        pics.invalidate(); cues.setVisibility(GONE);
        for (Map.Entry<View, Integer> e : hidden.entrySet()) e.getKey().setVisibility(e.getValue());
        hidden.clear();
        setVisibility(GONE);
        if (from != null) from.refreshProgress();
        h.settleMemory();                 // (the video's buffers and pictures are let go)
    }

    private void save() {
        NativePlayer np = h.act.nativePlayer();
        if (np == null || !ready || item == null) return;
        long pos = np.positionMs(), dur = np.durationMs();
        if (dur <= 0 || pos <= 0) return;
        int[] se = seasonEpisode();
        Progress.put(getContext(), videoId, pos, dur, "series".equals(item.optString("type")) ? item.optString("id") : "", se[0], se[1], info());
    }
    private int[] seasonEpisode() {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(":(\\d+):(\\d+)$").matcher(videoId);
        return m.find() ? new int[] { Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) } : new int[] { 0, 0 };
    }
    /** what Continue watching needs to show it and carry on: the title, the episode, the stream, the next episode */
    private JSONObject info() {
        JSONObject o = new JSONObject();
        try {
            JSONObject t = new JSONObject();
            for (String k : new String[] { "id", "type", "name", "poster", "background", "logo", "year", "rating", "runtime", "desc" }) t.put(k, item.optString(k));
            if (item.optJSONArray("genres") != null) t.put("genres", item.optJSONArray("genres"));
            o.put("title", t).put("label", label);
            if (stream != null) o.put("stream", new JSONObject().put("addon", stream.optString("_addon")).put("name", stream.optString("name")).put("title", stream.optString("title")));
            if (next != null) {
                String nl = "S" + next.optInt("season") + " · E" + next.optInt("episode", next.optInt("number")) + "  " + next.optString("name", next.optString("title"));
                o.put("next", new JSONObject().put("id", next.optString("id")).put("label", nl));
            }
        } catch (Exception ignored) { }
        return o;
    }
    /** Trakt: what you're watching (start / pause / stop, with how far in) */
    private void scrobble(String what) {
        if (item == null) return;
        NativePlayer np = h.act.nativePlayer();
        double pct = np == null || np.durationMs() <= 0 ? -1 : np.positionMs() * 100.0 / np.durationMs();
        if (what.equals("stop") && !scrobbling) return;
        if (pct < 0 && !what.equals("stop")) return;
        scrobbling = !what.equals("stop");
        Trakt t = h.act.trakt;
        if (t != null) t.scrobble(what, item.optString("type"), item.optString("id"), videoId, Math.max(0, pct));
    }
    private final Runnable saveTick = new Runnable() { @Override public void run() { save(); postDelayed(this, 10000); } };
    /** instant play: the next episode's streams found (and its link resolved) during this one's last minutes */
    private boolean preparedNext;
    private void prepareNext() {
        if (preparedNext || next == null) return;
        NativePlayer np = h.act.nativePlayer(); long d = dur(np);
        if (np == null || d <= 0) return;
        long pos = np.positionMs();
        if (d - pos > 8 * 60000L && pos < d * .85) return;
        preparedNext = true;
        Addons.get(getContext()).prepare(item.optString("type", "series"), next.optString("id"), stream, true, "the next episode");
    }
    private final Runnable tick = new Runnable() { @Override public void run() {
        keepHomeAway();
        if (bar.getAlpha() > .05f) ctl.invalidate();
        checkSkip();
        checkUpNext();
        prepareNext();
        /* the bar (all of it) goes once nothing's happened for 7 s while playing: it used to stay up after a menu
           (More, Subtitles) had closed, since nothing set it going again */
        if (bar.getAlpha() > .5f && !paused && !scrubbing && !h.menuOpenNow() && SystemClock.uptimeMillis() - barAt > 7000) {
            bar.animate().cancel(); bar.animate().alpha(0f).setDuration(300).start();
        }
        postDelayed(this, 500);
    } };

    /** playing (sound) but no picture yet: after a while that's a failed stream, not a wait */
    private boolean pictured;
    /** while waiting for the picture, what the player is doing (every 5 s, into the report's timeline) */
    private int waits;
    private final Runnable waitLog = new Runnable() { @Override public void run() {
        if (pictured || !shownNow() || waits++ > 12) return;
        NativePlayer np = h.act.nativePlayer();
        Diag.ev("vod: waiting " + (waits * 5) + " s · " + (np == null ? "no player" : np.state().toString()));
        postDelayed(this, 5000);
    } };
    private final Runnable noPicture = new Runnable() { @Override public void run() {
        if (pictured || !shownNow()) return;
        Diag.ev("vod: playing but no picture after 20 s");
        final JSONObject failed = stream; final TitleView tv = from; final Fallback fb = fallback;
        close();
        if (tv != null) tv.streamFailed(failed, "the picture never started", ready);
        else if (fb != null) fb.failed("the picture never started");
    } };
    void event(int id, String ev, String data) {
        if (id != pid) return;
        if (!ev.equals("cues") && !ev.equals("cuesimg")) Diag.ev("vod: " + ev + (data == null || data.isEmpty() || ev.equals("tracks") ? "" : " " + (data.length() > 100 ? data.substring(0, 100) : data)));
        if (ev.equals("ready")) { pictured = true; removeCallbacks(noPicture); setBackgroundColor(Color.TRANSPARENT); }
        if (ev.equals("ready") || ev.equals("playing")) {
            ready = true;
            if (pictured) {
                loading.setVisibility(GONE);
                if (openingUp) { hideOpening(true); showBar(); if (carryOnNote != null) h.toast(carryOnNote); carryOnNote = null; }
            }
            else { removeCallbacks(noPicture); postDelayed(noPicture, 20000); }
            NativePlayer np = h.act.nativePlayer();
            if (startPct > 0 && np != null && np.durationMs() > 0) {
                long at = (long) (np.durationMs() * startPct / 100.0) - 3000;
                startPct = 0;
                if (at > 60000) { np.seekMs(at); h.toast("Carrying on at " + TitleView.clock(at) + " (from Trakt) · More › From the start"); }
            }
            if (!scrobbling && !paused) scrobble("start");
        }
        else if (ev.equals("buffering")) { if (ready) { loading.setText("Buffering…"); loading.setVisibility(VISIBLE); } }
        else if (ev.equals("cues")) showCues(data);
        else if (ev.equals("cuesimg")) pics.invalidate();
        else if (ev.equals("ended")) {
            save();
            int[] se = seasonEpisode();
            Progress.put(getContext(), videoId, 1, 1, "series".equals(item.optString("type")) ? item.optString("id") : "", se[0], se[1], null);
            final JSONObject nx = next, was = stream; final TitleView tv = from;
            close();
            if (nx != null && tv != null) { tv.afterEpisode(nx, was); h.toast("Up next: " + nx.optString("name", nx.optString("title")) + "   ·   Back to stop"); }
        } else if (ev.equals("error")) {
            // (the Live TV watchdog's own verdicts: a big file still loading isn't a broken stream)
            if (data.startsWith("playback stood still") || data.startsWith("picture stopped")) { Diag.ev("vod: slow (" + data + ")"); loading.setText("Buffering…"); loading.setVisibility(VISIBLE); return; }
            Diag.ev("vod: couldn't play (" + data + ")");
            // an addon subtitle that won't load mustn't take the movie with it: carry on without it
            if (sideSub != null) { sideSub = null; NativePlayer np = h.act.nativePlayer(); h.toast("That subtitle wouldn't load"); if (np != null) np.playVod(++pid, playUrl, np.positionMs(), null, headers); return; }
            final JSONObject failed = stream; final TitleView tv = from; final String why = friendly(data); final Fallback fb = fallback;
            close();
            final boolean dec = data != null && (data.contains("DECODER") || data.contains("DECODING") || data.contains("Decoder") || data.contains("MediaCodec"));
            if (tv != null) tv.streamFailed(failed, why, dec);
            else if (fb != null) fb.failed(why);
            else h.toast("That didn't play: " + why);
        }
    }
    /** what went wrong, in plain words */
    static String friendly(String d) {
        String x = d == null ? "" : d;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Response code: (\\d+)").matcher(x);
        if (m.find()) {
            int c = Integer.parseInt(m.group(1));
            return c == 403 || c == 401 ? "the link was refused (" + c + ") — it may have expired, or need your debrid key" : c == 404 || c == 410 ? "the file isn't there any more (" + c + ")" : c == 429 ? "too many requests (429) — wait a moment" : c >= 500 ? "the addon's server failed (" + c + ")" : "the link answered " + c;
        }
        if (x.contains("UnrecognizedInputFormat")) return "TopShelf can't open this file's format";
        if (x.contains("DECODER") || x.contains("Decoder") || x.contains("MediaCodec")) return "the TV can't decode this video (try a 1080p or non-Dolby-Vision stream)";
        if (x.contains("TIMEOUT") || x.contains("Timeout")) return "the link took too long to answer";
        if (x.contains("NETWORK") || x.contains("UnknownHost")) return "couldn't reach the link";
        if (x.contains("AUDIO") || x.contains("AudioSink")) return "the TV can't play this file's sound";
        return x.length() > 120 ? x.substring(0, 120) : x;
    }
    private void showCues(String s) {
        if (s == null || s.isEmpty()) { cues.setVisibility(GONE); return; }
        // Settings › Movies & TV › Subtitle size and background
        String sz = h.S.optString("subSize", "m");
        float k = sz.equals("s") ? .8f : sz.equals("l") ? 1.3f : sz.equals("xl") ? 1.6f : 1f;
        cues.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 2.2f * k * R);
        boolean box = !"shadow".equals(h.S.optString("subBg"));
        cues.setShadowLayer(box ? 0 : .5f * R, 0, box ? 0 : .1f * R, 0xFF000000);
        android.text.SpannableString sp = new android.text.SpannableString(s);
        if (box) sp.setSpan(new android.text.style.BackgroundColorSpan(0xB8000000), 0, s.length(), 0);
        cues.setText(sp); cues.setVisibility(VISIBLE);
    }

    // ================================================================== the bar

    private final Runnable barHide = new Runnable() { @Override public void run() {
        if (paused) return;
        if (scrubbing) commitScrub();
        bar.animate().alpha(0f).setDuration(300).start();
    } };
    private boolean barShown() { return bar.getAlpha() > .5f; }
    private long barAt;
    private void showBar() {
        barAt = SystemClock.uptimeMillis();
        bar.animate().alpha(1f).setDuration(250).start();
        ctl.invalidate();
        removeCallbacks(barHide); postDelayed(barHide, 6000);
    }
    /** left to right as drawn: From the start, Episodes (a show) · back 10, play, ahead 30 · Subtitles, Audio (more
        than one), Next episode (when there is one), More */
    private List<String> buttons() {
        List<String> b = new ArrayList<>();
        b.add("start");
        if (from != null && item != null && "series".equals(item.optString("type"))) b.add("episodes");
        b.add("back"); b.add("play"); b.add("fwd");
        b.add("subs");
        JSONArray au = tracks().optJSONArray("audio");
        if (au != null && au.length() > 1) b.add("audio");
        if (next != null && from != null) b.add("next");
        b.add("more");
        return b;
    }
    private int playIndex() { return buttons().indexOf("play"); }

    boolean key(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int c = e.getKeyCode();
        if (openingUp) { if (c == KeyEvent.KEYCODE_BACK || c == KeyEvent.KEYCODE_ESCAPE) close(); return true; }
        // the pause screen: OK plays on, Back closes the player as usual, anything else brings the controls back
        if (pauseInfoUp) {
            boolean okK = c == KeyEvent.KEYCODE_DPAD_CENTER || c == KeyEvent.KEYCODE_ENTER || c == KeyEvent.KEYCODE_NUMPAD_ENTER || c == KeyEvent.KEYCODE_BUTTON_A
                    || c == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || c == KeyEvent.KEYCODE_MEDIA_PLAY;
            hidePauseInfo(true);
            if (okK) { togglePause(); showBar(); return true; }
            if (c != KeyEvent.KEYCODE_BACK && c != KeyEvent.KEYCODE_ESCAPE) { row = 1; sel = playIndex(); showBar(); return true; }
        }
        if (paused) { removeCallbacks(pauseInfoLater); postDelayed(pauseInfoLater, 3500); }
        NativePlayer np = h.act.nativePlayer();
        boolean ok = c == KeyEvent.KEYCODE_DPAD_CENTER || c == KeyEvent.KEYCODE_ENTER || c == KeyEvent.KEYCODE_NUMPAD_ENTER || c == KeyEvent.KEYCODE_BUTTON_A;
        if (c == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || c == KeyEvent.KEYCODE_MEDIA_PLAY || c == KeyEvent.KEYCODE_MEDIA_PAUSE) { togglePause(); showBar(); return true; }
        if (c == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) { skip(30000); showBar(); return true; }
        if (c == KeyEvent.KEYCODE_MEDIA_REWIND) { skip(-10000); showBar(); return true; }
        // the Up next card: OK plays it now, Back stays for the credits
        if (upShown && !barShown()) {
            if (ok) { goNext(); return true; }
            if (c == KeyEvent.KEYCODE_BACK || c == KeyEvent.KEYCODE_ESCAPE) { upDismissed = true; hideUpNext(); return true; }
        }
        if (c == KeyEvent.KEYCODE_BACK || c == KeyEvent.KEYCODE_ESCAPE) {
            if (scrubbing) { removeCallbacks(scrubCommit); scrubbing = false; scrubEnd(false); ctl.invalidate(); return true; }
            if (barShown()) { removeCallbacks(barHide); bar.animate().alpha(0f).setDuration(200).start(); return true; }
            close(); return true;
        }
        // a "Skip intro" button showing (and the bar away): OK takes it
        if (ok && !barShown() && skipShown()) { doSkip(); return true; }
        if (!barShown()) {
            // the bar's away: ◀ ▶ skip straight away, anything else brings it up
            if (c == KeyEvent.KEYCODE_DPAD_LEFT) skip(-10000);
            else if (c == KeyEvent.KEYCODE_DPAD_RIGHT) skip(30000);
            row = 1; sel = playIndex();
            showBar();
            return true;
        }
        if (c == KeyEvent.KEYCODE_DPAD_UP) { if (row == 1) { row = 0; scrubbing = false; } }
        else if (c == KeyEvent.KEYCODE_DPAD_DOWN) { if (row == 0) { commitScrub(); row = 1; } }
        else if (c == KeyEvent.KEYCODE_DPAD_LEFT || c == KeyEvent.KEYCODE_DPAD_RIGHT) {
            boolean right = c == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (row == 1) sel = Math.max(0, Math.min(buttons().size() - 1, sel + (right ? 1 : -1)));
            else if (np != null && dur(np) > 0) {
                long now = SystemClock.uptimeMillis();
                scrubRun = now - lastScrubAt < 450 ? scrubRun + 1 : 0; lastScrubAt = now;
                long step = scrubRun < 4 ? 10000 : scrubRun < 12 ? 30000 : 60000;
                if (!scrubbing) { scrubbing = true; scrubBegin(np); }
                scrubTo = Math.max(0, Math.min(dur(np) - 2000, scrubTo + (right ? step : -step)));
                removeCallbacks(scrubCommit); postDelayed(scrubCommit, 1300);    // (a moment to look at where you are)
            }
        } else if (ok) {
            if (row == 0) { if (scrubbing) commitScrub(); else togglePause(); }
            else press(buttons().get(sel));
        }
        showBar();
        return true;
    }
    /* a finger (phones): with the bar up, a tap on a button presses it, a tap on the line jumps there, a tap
       anywhere else puts the bar away; "done" then, or null to let OK act (bring the bar up, take a "Skip intro") */
    String focusAt(float x, float y) {
        if (upShown && h.hit(upCard, x, y, .4f)) { goNext(); return "done"; }
        if (!barShown()) return null;
        NativePlayer np = h.act.nativePlayer();
        float[] l = h.local(ctl, x, y);
        if (l[0] >= ctl.tx0 - R && l[0] <= ctl.tx1 + R && Math.abs(l[1] - ctl.ty) < 1.8f * R) {
            long d = dur(np);
            if (np != null && d > 0) { if (scrubbing) { scrubbing = false; removeCallbacks(scrubCommit); removeCallbacks(scrubLive); np.scrubDone(); if (scrubWasPlaying && !paused) np.setPaused(false); } np.seekMs((long) (Math.max(0, Math.min(1, (l[0] - ctl.tx0) / Math.max(1, ctl.tx1 - ctl.tx0))) * Math.max(0, d - 2000))); }
            row = 0; showBar(); return "done";
        }
        List<String> bs = buttons();
        for (int i = 0; i < bs.size() && i < ctl.bx.length; i++) {
            if (Math.hypot(l[0] - ctl.bx[i], l[1] - ctl.by) < 2.9f * R) { row = 1; sel = i; press(bs.get(i)); showBar(); return "done"; }
        }
        removeCallbacks(barHide); bar.animate().alpha(0f).setDuration(200).start();
        return "done";
    }
    private final Runnable scrubCommit = new Runnable() { @Override public void run() { commitScrub(); } };
    private void commitScrub() {
        removeCallbacks(scrubCommit);
        if (!scrubbing) return;
        scrubbing = false;
        scrubEnd(true);
        ctl.invalidate();
    }
    /* ------------------------------------------------------------------ live scrubbing (as Apple TV and YouTube do):
       while you scrub, the film itself jumps to each spot, paused and silent, using the TV's own decoder and the
       stream already open (the nearest key frame: quick even over the internet); a new spot is asked for at most
       every ~quarter second, the newest one winning. Letting go plays on from the spot; Back goes back to where you
       were. (It replaced pictures fetched separately, which were slow with big files and came out black in HDR.) */
    private boolean scrubWasPlaying; private long scrubFrom, liveAsked = -1, liveAt;
    private void scrubBegin(NativePlayer np) {
        scrubFrom = np.positionMs(); scrubTo = scrubFrom;
        scrubWasPlaying = !paused;
        if (scrubWasPlaying) np.setPaused(true);
        liveAsked = -1;
        removeCallbacks(scrubLive); postDelayed(scrubLive, 60);
    }
    private final Runnable scrubLive = new Runnable() { @Override public void run() {
        if (!scrubbing) return;
        NativePlayer np = h.act.nativePlayer();
        long now = SystemClock.uptimeMillis();
        if (np != null && scrubTo != liveAsked && now - liveAt >= 260) { np.scrubSeek(scrubTo); liveAsked = scrubTo; liveAt = now; }
        postDelayed(this, 80);
    } };
    /** scrubbing ends: at the chosen spot (exactly), or back where it started; playing again if it was */
    private void scrubEnd(boolean keep) {
        removeCallbacks(scrubLive);
        NativePlayer np = h.act.nativePlayer();
        if (np == null) return;
        np.scrubDone();
        np.seekMs(keep ? scrubTo : scrubFrom);
        if (scrubWasPlaying && !paused) np.setPaused(false);
    }

    private void skip(long ms) { NativePlayer np = h.act.nativePlayer(); long d = dur(np); if (np != null && d > 0) np.seekMs(Math.max(0, Math.min(d - 2000, np.positionMs() + ms))); }
    private void togglePause() {
        NativePlayer np = h.act.nativePlayer(); if (np == null) return;
        paused = !paused; np.setPaused(paused);
        removeCallbacks(pauseInfoLater);
        if (paused) postDelayed(pauseInfoLater, 3500); else hidePauseInfo(true);
        if (paused) save();
        scrobble(paused ? "pause" : "start");
        ctl.invalidate();
    }
    private void press(String id) {
        final NativePlayer np = h.act.nativePlayer();
        if (id.equals("back")) skip(-10000);
        else if (id.equals("fwd")) skip(30000);
        else if (id.equals("play")) togglePause();
        else if (id.equals("more")) more();
        else if (id.equals("start")) { if (np != null) { np.seekMs(0); h.toast("From the start"); } }
        else if (id.equals("subs")) { removeCallbacks(barHide); subMenu(); }
        else if (id.equals("audio")) { JSONArray au = tracks().optJSONArray("audio"); if (au != null) { removeCallbacks(barHide); audioMenu(au); } }
        else if (id.equals("next")) { if (next != null) { final JSONObject nx = next, was = stream; final TitleView tv = from; close(); if (tv != null) tv.afterEpisode(nx, was); } }
        else if (id.equals("episodes")) { close(); }        // (back to the show's page, on its episodes)
    }

    private void more() {
        removeCallbacks(barHide);
        final NativePlayer np = h.act.nativePlayer();
        List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
        l.add("Subtitles"); a.add(new Runnable() { @Override public void run() { later(new Runnable() { @Override public void run() { subMenu(); } }); } });
        JSONObject tr = tracks();
        final JSONArray au = tr.optJSONArray("audio");
        if (au != null && au.length() > 1) { l.add("Audio"); a.add(new Runnable() { @Override public void run() { later(new Runnable() { @Override public void run() { audioMenu(au); } }); } }); }
        l.add("From the start"); a.add(new Runnable() { @Override public void run() { if (np != null) np.seekMs(0); } });
        if (next != null) { final JSONObject nx = next; final TitleView tv = from;
            l.add("Next episode · " + nx.optString("name", nx.optString("title"))); final JSONObject was = stream; a.add(new Runnable() { @Override public void run() { close(); if (tv != null) tv.afterEpisode(nx, was); } }); }
        l.add("Choose another stream"); a.add(new Runnable() { @Override public void run() { close(); } });
        h.showMenu(l, a, null, 1f, item.optString("name"));
    }
    private void later(Runnable r) { postDelayed(r, 60); }
    private JSONObject tracks() { NativePlayer np = h.act.nativePlayer(); try { return new JSONObject(np == null ? "{}" : np.tracks()); } catch (Exception e) { return new JSONObject(); } }
    private static String trackName(JSONObject t, int i, String kind) {
        String n = t.optString("name");
        if (!n.isEmpty() && !n.matches("(?i)^(audio|stereo|default|subtitles|captions|\\d+)$")) return n;
        return NativeLive.langName(t.optString("lang"), kind.equals("audio") ? "Track " + (i + 1) : "Subtitles " + (i + 1)) + (kind.equals("audio") && t.optInt("ch") > 2 ? " · surround" : "");
    }
    private void subMenu() {
        final JSONObject tr = tracks();
        final JSONArray tx = tr.optJSONArray("text");
        final NativePlayer np = h.act.nativePlayer();
        List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
        boolean anyOn = false; if (tx != null) for (int i = 0; i < tx.length(); i++) if (tr.optBoolean("textOn") && tx.optJSONObject(i).optBoolean("on")) anyOn = true;
        l.add((anyOn ? "" : "✓ ") + "Off"); a.add(new Runnable() { @Override public void run() { if (np != null) np.select("text", -1, -1); showCues(""); h.prefs.edit().putString("vodSubLang", "").apply(); } });
        if (tx != null) for (int i = 0; i < tx.length(); i++) {
            final JSONObject t = tx.optJSONObject(i); final String name = trackName(t, i, "text");
            l.add((tr.optBoolean("textOn") && t.optBoolean("on") ? "✓ " : "") + name);
            a.add(new Runnable() { @Override public void run() {
                if (np != null) np.select("text", t.optInt("g"), t.optInt("t"));
                String lang = t.optString("lang"); if (!lang.isEmpty()) h.prefs.edit().putString("vodSubLang", lang.length() > 2 ? lang.substring(0, 2) : lang).apply();
                h.toast("Subtitles: " + name);
            } });
        }
        // from the subtitle addons (your language first)
        String sys = Locale.getDefault().getLanguage(), want = h.prefs.getString("vodSubLang", "");
        List<JSONObject> mine = new ArrayList<>(), rest = new ArrayList<>();
        for (JSONObject x : addonSubs) { String lg = x.optString("lang").toLowerCase(Locale.US); (lg.startsWith(sys) || (!want.isEmpty() && lg.startsWith(want)) || (lg.equals("eng") && sys.equals("en")) ? mine : rest).add(x); }
        mine.addAll(rest);
        int n = 0;
        for (final JSONObject x : mine) {
            if (n++ >= 15) break;
            String lang = x.optString("lang");
            l.add(NativeLive.langName(lang.length() > 3 ? lang.substring(0, 3) : lang, lang) + "  ·  from " + x.optString("_addon"));
            a.add(new Runnable() { @Override public void run() { useAddonSub(x); } });
        }
        if (l.size() == 1) { l.add(subsAsked ? "(none in this file, and no subtitle addon found any)" : "(still asking your subtitle addons…)"); a.add(new Runnable() { @Override public void run() { } }); }
        h.showMenu(l, a, null, 1f, "Subtitles");
    }
    private void audioMenu(JSONArray au) {
        final NativePlayer np = h.act.nativePlayer();
        List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
        for (int i = 0; i < au.length(); i++) {
            final JSONObject t = au.optJSONObject(i); final String name = trackName(t, i, "audio");
            l.add((t.optBoolean("on") ? "✓ " : "") + name);
            a.add(new Runnable() { @Override public void run() { if (np != null) np.select("audio", t.optInt("g"), t.optInt("t")); h.toast("Audio: " + name); } });
        }
        h.showMenu(l, a, null, 1f, "Audio");
    }

    /** across the foot: the timeline (times at either end), then the round buttons; edges in line with the title */
    private final class Bar extends View {
        private final TextPaint tTime = new TextPaint(Paint.ANTI_ALIAS_FLAG), tNum = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), st = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path path = new Path();
        float[] bx = new float[0]; float by, ty, tx0, tx1;        // (where things are: for a finger)
        Bar(android.content.Context c) {
            super(c);
            tTime.setTypeface(h.fMed); tTime.setTextSize(1.1f * R); tTime.setColor(0xE0FFFFFF); tTime.setFontFeatureSettings("tnum");
            tNum.setTypeface(h.fSemi); tNum.setTextSize(.74f * R); tNum.setTextAlign(Paint.Align.CENTER);
            st.setStyle(Paint.Style.STROKE); st.setStrokeCap(Paint.Cap.ROUND); st.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override protected void onDraw(Canvas c) {
            NativePlayer np = h.act.nativePlayer();
            int w = getWidth();
            float pad = 5f * R;
            long pos = np == null ? 0 : np.positionMs(), dur = dur(np), buf = np == null ? 0 : np.bufferedMs();
            float span = Math.max(1, dur);
            boolean seekRow = row == 0;
            long tw = scrubbing ? scrubTo : pos;
            // the timeline: elapsed on the left, what's left on the right
            String left = TitleView.clock(tw), right = dur > 0 ? "\u2212" + TitleView.clock(Math.max(0, dur - tw)) : "";
            ty = 3.6f * R;
            float tl = tTime.measureText("0:00:00") + 1.4f * R;
            tx0 = pad + tl; tx1 = w - pad - tl;
            float base = ty + tTime.getTextSize() * .36f;
            tTime.setColor(seekRow ? Color.WHITE : 0xE0FFFFFF);
            tTime.setTextAlign(Paint.Align.LEFT); c.drawText(left, pad, base, tTime);
            tTime.setTextAlign(Paint.Align.RIGHT); c.drawText(right, w - pad, base, tTime);
            float th = (seekRow ? .62f : .44f) * R, len = tx1 - tx0;
            p.setColor(0x2EFFFFFF); r.set(tx0, ty - th / 2, tx1, ty + th / 2); c.drawRoundRect(r, th, th, p);
            p.setColor(0x52FFFFFF); r.set(tx0, ty - th / 2, tx0 + len * Math.min(1, buf / span), ty + th / 2); c.drawRoundRect(r, th, th, p);
            float posX = tx0 + len * Math.min(1, pos / span), kx = tx0 + len * Math.min(1, tw / span);
            p.setColor(Color.WHITE); r.set(tx0, ty - th / 2, posX, ty + th / 2); c.drawRoundRect(r, th, th, p);
            if (seekRow) {
                p.setColor(0x40FFFFFF); c.drawCircle(kx, ty, 1.25f * R, p);
                p.setColor(Color.WHITE); c.drawCircle(kx, ty, .8f * R, p);
            } else c.drawCircle(posX, ty, .62f * R, p);
            // the buttons
            List<String> bs = buttons();
            if (bx.length != bs.size()) bx = new float[bs.size()];
            by = 9.1f * R;
            float mid = w / 2f, small = 2.25f * R, gap = 1.1f * R;
            int pi = bs.indexOf("play");
            // the middle three, then the rest packed to either side
            float xl = pad + small, xr = w - pad - small;
            for (int i = 0; i < bs.size(); i++) {
                String id = bs.get(i);
                if (id.equals("back")) bx[i] = mid - 6.4f * R;
                else if (id.equals("play")) bx[i] = mid;
                else if (id.equals("fwd")) bx[i] = mid + 6.4f * R;
                else if (i < pi) { bx[i] = xl; xl += 2 * small + gap; }
            }
            for (int i = bs.size() - 1; i > pi; i--) {
                String id = bs.get(i);
                if (id.equals("fwd")) continue;
                bx[i] = xr; xr -= 2 * small + gap;
            }
            for (int i = 0; i < bs.size(); i++) {
                String id = bs.get(i);
                boolean f = row == 1 && i == sel;
                float x = bx[i], rad = id.equals("play") ? 3f * R : id.equals("back") || id.equals("fwd") ? 2.5f * R : small;
                p.setColor(f ? Color.WHITE : 0x17FFFFFF); c.drawCircle(x, by, rad, p);
                icon(c, id, x, by, f ? 0xFF111418 : Color.WHITE);
            }
        }
        /** each button's picture, drawn in lines */
        private void icon(Canvas c, String id, float x, float y, int col) {
            p.setColor(col); st.setColor(col); st.setStrokeWidth(.17f * R);
            float u = R * .085f;      // (a 24-unit icon box, centred)
            if (id.equals("play")) {
                if (paused) { path.reset(); float k = 1.05f * R; path.moveTo(x - k * .65f, y - k); path.lineTo(x + k, y); path.lineTo(x - k * .65f, y + k); path.close(); c.drawPath(path, p); }
                else { float bw = .42f * R, bh = 2f * R; r.set(x - .8f * R, y - bh / 2, x - .8f * R + bw, y + bh / 2); c.drawRoundRect(r, .12f * R, .12f * R, p); r.offset(1.6f * R - bw, 0); c.drawRoundRect(r, .12f * R, .12f * R, p); }
            } else if (id.equals("back") || id.equals("fwd")) {
                boolean cw = id.equals("fwd");
                float ar = 1.1f * R;
                r.set(x - ar, y - ar, x + ar, y + ar);
                c.drawArc(r, cw ? -60 : -120, cw ? -290 : 290, false, st);
                double ang = Math.toRadians(cw ? -60 : -120);
                float ax = x + (float) (ar * Math.cos(ang)), ay = y + (float) (ar * Math.sin(ang));
                path.reset(); float d = .45f * R, dir = cw ? 1 : -1;
                path.moveTo(ax + dir * d * .2f, ay - d); path.lineTo(ax + dir * d * .9f, ay + d * .15f); path.lineTo(ax - dir * d * .5f, ay + d * .35f); path.close();
                c.drawPath(path, p);
                tNum.setColor(col); c.drawText(cw ? "30" : "10", x, y + tNum.getTextSize() * .36f, tNum);
            } else if (id.equals("more")) { for (int k = -1; k <= 1; k++) c.drawCircle(x + k * .6f * R, y, .2f * R, p); }
            else if (id.equals("start")) {
                // a bar and a back-pointing triangle: to the beginning
                c.drawLine(x - 7 * u, y - 7 * u, x - 7 * u, y + 7 * u, st);
                path.reset(); path.moveTo(x + 7 * u, y - 7 * u); path.lineTo(x - 4 * u, y); path.lineTo(x + 7 * u, y + 7 * u); path.close(); c.drawPath(path, p);
            } else if (id.equals("next")) {
                path.reset(); path.moveTo(x - 7 * u, y - 7 * u); path.lineTo(x + 4 * u, y); path.lineTo(x - 7 * u, y + 7 * u); path.close(); c.drawPath(path, p);
                c.drawLine(x + 7 * u, y - 7 * u, x + 7 * u, y + 7 * u, st);
            } else if (id.equals("episodes")) {
                c.drawLine(x - 8 * u, y - 6 * u, x + 8 * u, y - 6 * u, st); c.drawLine(x - 8 * u, y, x + 8 * u, y, st); c.drawLine(x - 8 * u, y + 6 * u, x + 3 * u, y + 6 * u, st);
            } else if (id.equals("subs")) {
                r.set(x - 9 * u, y - 6.5f * u, x + 9 * u, y + 6.5f * u); c.drawRoundRect(r, 2 * u, 2 * u, st);
                c.drawLine(x - 5 * u, y + 1.5f * u, x - 1 * u, y + 1.5f * u, st); c.drawLine(x + 1.5f * u, y + 1.5f * u, x + 5 * u, y + 1.5f * u, st);
                c.drawLine(x - 5 * u, y - 2.5f * u, x + 5 * u, y - 2.5f * u, st);
            } else if (id.equals("audio")) {
                path.reset(); path.moveTo(x - 8 * u, y - 3 * u); path.lineTo(x - 4 * u, y - 3 * u); path.lineTo(x + 1 * u, y - 7.5f * u); path.lineTo(x + 1 * u, y + 7.5f * u); path.lineTo(x - 4 * u, y + 3 * u); path.lineTo(x - 8 * u, y + 3 * u); path.close();
                c.drawPath(path, p);
                r.set(x - 1 * u, y - 5 * u, x + 7 * u, y + 5 * u); c.drawArc(r, -55, 110, false, st);
            }
        }
    }

    /** picture subtitles (Blu-ray PGS, DVD) drawn where the film puts them */
    private final class SubPics extends View {
        private final Paint pp = new Paint(Paint.FILTER_BITMAP_FLAG);
        SubPics(android.content.Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            NativePlayer np = h.act.nativePlayer();
            if (np == null) return;
            int w = getWidth(), hh = getHeight();
            // (the picture fills the width at 16:9, centred: the cues' places are fractions of it)
            float vh = Math.min(hh, w * 9 / 16f), vw = vh * 16 / 9f, ox = (w - vw) / 2f, oy = (hh - vh) / 2f;
            for (androidx.media3.common.text.Cue q : np.pictureCues()) {
                if (q.bitmap == null) continue;
                float cw = q.size != androidx.media3.common.text.Cue.DIMEN_UNSET ? q.size * vw : q.bitmap.getWidth() * vw / 1920f;
                float ch = q.bitmapHeight != androidx.media3.common.text.Cue.DIMEN_UNSET ? q.bitmapHeight * vh : cw * q.bitmap.getHeight() / Math.max(1f, q.bitmap.getWidth());
                float x = q.position != androidx.media3.common.text.Cue.DIMEN_UNSET ? ox + q.position * vw : ox + (vw - cw) / 2f;
                if (q.positionAnchor == androidx.media3.common.text.Cue.ANCHOR_TYPE_MIDDLE) x -= cw / 2f; else if (q.positionAnchor == androidx.media3.common.text.Cue.ANCHOR_TYPE_END) x -= cw;
                float y = q.line != androidx.media3.common.text.Cue.DIMEN_UNSET && q.lineType == androidx.media3.common.text.Cue.LINE_TYPE_FRACTION ? oy + q.line * vh : oy + vh * .9f - ch;
                if (q.lineAnchor == androidx.media3.common.text.Cue.ANCHOR_TYPE_MIDDLE) y -= ch / 2f; else if (q.lineAnchor == androidx.media3.common.text.Cue.ANCHOR_TYPE_END) y -= ch;
                c.drawBitmap(q.bitmap, null, new RectF(x, y, x + cw, y + ch), pp);
            }
        }
    }
}
