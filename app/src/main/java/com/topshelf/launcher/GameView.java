package com.topshelf.launcher;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A game's own page (OK on Sports' scoreboard, which it grows out of; or from a game's menu): what the game's menu
 * offered as buttons under the scoreboard (watch on your channel, remind me, update: ◀ ▶ and OK), the scoreboard
 * on the teams' colours, the score by quarter or period, the chance each side had of winning as the game went, each team's leaders, the scoring plays and the
 * team stats side by side — all from ESPN's game summary, fetched again every 30 s while the game is on.
 * ▲ ▼ move down the page, Back closes it (back into the scoreboard, when it came from there).
 */
final class GameView extends FrameLayout {
    private static final int GROUND = SportsLook.GROUND, CARD = SportsLook.CARD, EDGE = SportsLook.EDGE, GOLD = SportsLook.GOLD, LIVE_C = SportsLook.LIVE, DIM = SportsLook.TER;
    private final NativeHome h;
    private final float R;
    private final Typeface cond, heavy;
    private final Page page;
    private String sport = "", league = "", id = "";
    private JSONObject sum;                       // ESPN's summary
    private String err = "";
    private final List<JSONObject> teams = new ArrayList<>();      // away, home: {name, abbr, color, logo, score, rec, lines[]}
    private float scroll, scrollT, maxScroll;
    private ValueAnimator anim;
    private int gen;
    // the game's menu, as buttons on the page: watch on a channel (the first `watch` of them), remind me; then Update
    private SportsData.Ev ev;
    private final List<String> actL = new ArrayList<>();
    private final List<Runnable> actR = new ArrayList<>();
    private int actWatch, act;
    private String actNote = "";
    // what the page grew out of (the scoreboard), and how far it has grown
    private android.graphics.Rect from;
    private ValueAnimator grow;

    GameView(NativeHome host) {
        super(host.getContext());
        h = host; R = host.R;
        setBackgroundColor(GROUND);
        setClickable(true);
        cond = SportsLook.cond(); heavy = SportsLook.heavy();
        page = new Page(getContext());
        addView(page, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setVisibility(GONE);
    }

    boolean shownNow() { return getVisibility() == VISIBLE; }

    /** opens on a game: its league (as ESPN's path has it) and ESPN's id; the scoreboard's teams show at once */
    void open(String sport, String league, String id, JSONArray scoreboardTeams) { open(sport, league, id, scoreboardTeams, null, null); }
    /** …with the game itself (for its buttons) and where on the screen it grows out of (either may be null) */
    void open(String sport, String league, String id, JSONArray scoreboardTeams, SportsData.Ev game, android.graphics.RectF fromRect) {
        this.sport = sport; this.league = league; this.id = id;
        sum = null; err = ""; scroll = scrollT = 0; gen++;
        teams.clear();
        if (scoreboardTeams != null) for (int i = 0; i < scoreboardTeams.length() && i < 2; i++) teams.add(scoreboardTeams.optJSONObject(i));
        ev = game; actL.clear(); actR.clear(); actWatch = 0; act = 0; actNote = "";
        animate().cancel(); animate().setStartDelay(0);
        if (grow != null) grow.cancel();
        from = null;
        if (fromRect != null && fromRect.width() > 1 && fromRect.height() > 1 && h.getWidth() > 0) {
            from = new android.graphics.Rect(Math.round(fromRect.left), Math.round(fromRect.top), Math.round(fromRect.right), Math.round(fromRect.bottom));
        }
        setVisibility(VISIBLE); bringToFront();
        if (from != null) { setAlpha(1f); grown(0f); growTo(0f, 1f, 340, null); }
        else { setClipBounds(null); page.setTranslationY(0); page.setAlpha(1f); setAlpha(0f); animate().alpha(1f).setDuration(Ui.FAST).start(); }
        load();
        loadActs();
    }
    /* Growing out of the scoreboard: the page is shown through a window that opens from the scoreboard's place to
       the whole screen, its own scoreboard riding up from there to the top (nothing is redrawn as it goes). */
    private void grown(float f) {
        if (from == null) return;
        int w = h.getWidth(), hh = h.getHeight();
        setClipBounds(new android.graphics.Rect(Math.round(from.left * (1 - f)), Math.round(from.top * (1 - f)), Math.round(from.right + (w - from.right) * f), Math.round(from.bottom + (hh - from.bottom) * f)));
        page.setTranslationY(from.top * (1 - f));
        page.setAlpha(Math.min(1f, .25f + f * 1.5f));
    }
    private void growTo(float a, float b, int ms, final Runnable then) {
        if (grow != null) grow.cancel();
        grow = ValueAnimator.ofFloat(a, b);
        grow.setDuration(ms); grow.setInterpolator(TileView.EASE);
        grow.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { @Override public void onAnimationUpdate(ValueAnimator v) { grown((Float) v.getAnimatedValue()); } });
        grow.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean off;
            @Override public void onAnimationCancel(android.animation.Animator x) { off = true; }
            @Override public void onAnimationEnd(android.animation.Animator x) { if (!off && then != null) then.run(); }
        });
        grow.start();
    }
    void close() {
        gen++;
        removeCallbacks(again);
        final Runnable gone = new Runnable() { @Override public void run() { setVisibility(GONE); setClipBounds(null); page.setTranslationY(0); page.setAlpha(1f); logos.clear(); } };
        if (from != null && scroll < R) { growTo(1f, 0f, 240, gone); animate().alpha(0f).setStartDelay(120).setDuration(120).withEndAction(new Runnable() { @Override public void run() { animate().setStartDelay(0); } }).start(); }
        else animate().alpha(0f).setDuration(Ui.QUICK).withEndAction(gone).start();
    }
    /** gone at once (something else takes the screen: a channel starts playing) */
    private void closeNow() {
        gen++;
        removeCallbacks(again);
        animate().cancel(); if (grow != null) grow.cancel();
        setVisibility(GONE); setClipBounds(null); page.setTranslationY(0); page.setAlpha(1f); logos.clear();
    }
    /** the game's buttons (asked for again after one is used: "Remind me" becomes "Cancel reminder") */
    private void loadActs() {
        if (ev == null || h.sportsData() == null) return;
        final int g = gen;
        h.sportsData().actions(ev, new SportsData.Actions() { @Override public void got(List<String> labels, List<Runnable> runs, int watch, String note) {
            if (g != gen) return;
            actL.clear(); actL.addAll(labels); actR.clear(); actR.addAll(runs); actWatch = watch; actNote = note;
            if (act > actL.size()) act = actL.size();
            page.invalidate();
        } });
    }
    /** OK: the button the remote is on (the last one is always Update) */
    private void actOk() {
        if (scrollT > R) { scrollTo(0); return; }             // (the buttons are at the top: back up to them first)
        if (act < actR.size()) {
            Runnable r = actR.get(act);
            if (act < actWatch) { closeNow(); r.run(); return; }
            r.run();
            loadActs();
            return;
        }
        load(); loadActs(); h.toast("Updating…");
    }
    private final Runnable again = new Runnable() { @Override public void run() { if (shownNow()) load(); } };
    private void load() {
        final int g = gen;
        final String url = SportsData.ESPN + "/" + sport + "/" + league + "/summary?event=" + id;
        new Thread(new Runnable() { @Override public void run() {
            JSONObject o = null; String e = "";
            try { o = SportsData.get(url); } catch (Throwable t) { e = String.valueOf(t.getMessage()); }
            final JSONObject fo = o; final String fe = e;
            post(new Runnable() { @Override public void run() {
                if (g != gen) return;
                if (fo != null) { sum = fo; readHeader(); } else if (sum == null) err = fe;
                page.invalidate();
                removeCallbacks(again);
                if ("in".equals(state())) postDelayed(again, 30000);
            } });
        } }, "game").start();
    }

    // ------------------------------------------------------------------ reading the summary

    private JSONObject comp() {
        JSONObject hd = sum == null ? null : sum.optJSONObject("header");
        JSONArray cs = hd == null ? null : hd.optJSONArray("competitions");
        return cs == null ? null : cs.optJSONObject(0);
    }
    private String state() {
        JSONObject c = comp(), st = c == null ? null : c.optJSONObject("status");
        JSONObject ty = st == null ? null : st.optJSONObject("type");
        return ty == null ? "" : ty.optString("state");
    }
    private void readHeader() {
        JSONObject c = comp();
        JSONArray cs = c == null ? null : c.optJSONArray("competitors");
        if (cs == null || cs.length() < 2) return;
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < cs.length(); i++) {
            JSONObject o = cs.optJSONObject(i), t = o == null ? null : o.optJSONObject("team");
            if (t == null) continue;
            try {
                JSONObject x = new JSONObject();
                x.put("home", "home".equals(o.optString("homeAway")));
                x.put("name", t.optString("shortDisplayName", t.optString("name", t.optString("displayName"))));
                x.put("abbr", t.optString("abbreviation"));
                x.put("id", t.optString("id"));
                x.put("color", t.optString("color").isEmpty() ? "" : "#" + t.optString("color"));
                x.put("alt", t.optString("alternateColor").isEmpty() ? "" : "#" + t.optString("alternateColor"));
                JSONArray lg = t.optJSONArray("logos");
                x.put("logo", lg != null && lg.optJSONObject(0) != null ? lg.optJSONObject(0).optString("href") : t.optString("logo"));
                x.put("score", o.optString("score"));
                x.put("winner", o.optBoolean("winner"));
                JSONArray rec = o.optJSONArray("record");
                x.put("rec", rec != null && rec.optJSONObject(0) != null ? rec.optJSONObject(0).optString("summary", rec.optJSONObject(0).optString("displayValue")) : "");
                JSONArray ls = o.optJSONArray("linescores"), lines = new JSONArray();
                if (ls != null) for (int k = 0; k < ls.length(); k++) { JSONObject q = ls.optJSONObject(k); lines.put(q == null ? "" : q.optString("displayValue", q.optString("value"))); }
                x.put("lines", lines);
                out.add(x);
            } catch (Exception ignored) { }
        }
        if (out.size() < 2) return;
        if (out.get(0).optBoolean("home")) { JSONObject t = out.get(0); out.set(0, out.get(1)); out.set(1, t); }
        teams.clear(); teams.addAll(out);
    }

    // ------------------------------------------------------------------ keys

    boolean key(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int c = e.getKeyCode();
        if (c == KeyEvent.KEYCODE_BACK || c == KeyEvent.KEYCODE_ESCAPE) { close(); return true; }
        if (c == KeyEvent.KEYCODE_DPAD_DOWN) scrollTo(scrollT + 9 * R);
        else if (c == KeyEvent.KEYCODE_DPAD_UP) scrollTo(scrollT - 9 * R);
        else if (c == KeyEvent.KEYCODE_DPAD_LEFT) { if (scrollT > R) scrollTo(0); else if (act > 0) { act--; page.invalidate(); } }
        else if (c == KeyEvent.KEYCODE_DPAD_RIGHT) { if (scrollT > R) scrollTo(0); else if (act < actL.size()) { act++; page.invalidate(); } }
        else if (c == KeyEvent.KEYCODE_DPAD_CENTER || c == KeyEvent.KEYCODE_ENTER || c == KeyEvent.KEYCODE_NUMPAD_ENTER || c == KeyEvent.KEYCODE_BUTTON_A) actOk();
        return true;
    }
    private void scrollTo(float to) {
        to = Math.max(0, Math.min(to, maxScroll));
        if (to == scrollT) return;
        scrollT = to;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(scroll, to);
        anim.setDuration(Ui.MOVE); anim.setInterpolator(TileView.EASE);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { @Override public void onAnimationUpdate(ValueAnimator a) { scroll = (Float) a.getAnimatedValue(); page.invalidate(); } });
        anim.start();
    }

    // ------------------------------------------------------------------ logos

    private final Map<String, Bitmap> logos = new HashMap<>();
    private Bitmap logo(final String url) {
        if (url == null || url.isEmpty()) return null;
        if (logos.containsKey(url)) return logos.get(url);
        logos.put(url, null);
        new Thread(new Runnable() { @Override public void run() {
            Bitmap b = null;
            try { byte[] d = HomeImages.http(url, null); if (d != null) b = HomeImages.decode(d, 260, 260); } catch (Throwable ignored) { }
            final Bitmap fb = b;
            post(new Runnable() { @Override public void run() { logos.put(url, fb); page.invalidate(); } });
        } }, "logo").start();
        return null;
    }
    private static int col(String hex, int def) { try { return Color.parseColor(hex); } catch (Exception e) { return def; } }
    private static float lum(int c) { return (.2126f * Color.red(c) + .7152f * Color.green(c) + .0722f * Color.blue(c)) / 255f; }
    private int teamCol(int i) {
        if (i >= teams.size()) return 0xFF3A4A66;
        JSONObject t = teams.get(i);
        int c = col(t.optString("color"), 0xFF3A4A66);
        if (lum(c) > .5f) { int a = col(t.optString("alt"), 0); c = a != 0 && lum(a) < .5f ? a : Color.rgb(Color.red(c) * 45 / 100, Color.green(c) * 45 / 100, Color.blue(c) * 45 / 100); }
        return c;
    }

    // ------------------------------------------------------------------ the page

    private final class Page extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        Page(android.content.Context c) { super(c); }

        private void text(Canvas c, String s, Typeface tf, float size, int color, float x, float y, Paint.Align a, float sp) {
            tp.setTypeface(tf); tp.setTextSize(size * R); tp.setColor(color); tp.setTextAlign(a); tp.setLetterSpacing(sp);
            c.drawText(s, x, y, tp); tp.setLetterSpacing(0);
        }
        private float measure(String s, Typeface tf, float size) { tp.setTypeface(tf); tp.setTextSize(size * R); return tp.measureText(s); }
        private String ellip(String s, Typeface tf, float size, float max) { tp.setTypeface(tf); tp.setTextSize(size * R); return TextUtils.ellipsize(s, tp, Math.max(1, max), TextUtils.TruncateAt.END).toString(); }
        private void head(Canvas c, String s, float x, float y) { text(c, s, heavy, 1.6f, Color.WHITE, x, y, Paint.Align.LEFT, .06f); }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), hh = getHeight();
            float px = 4 * R, cw = w - 2 * px;
            c.save(); c.translate(0, -scroll);
            float y = header(c, w);
            y = buttons(c, px, y, cw);
            if (sum == null) {
                text(c, err.isEmpty() ? "Loading the game…" : "Couldn't load the game (" + err + ")", h.fMed, 1.5f, DIM, px, y + 4 * R, Paint.Align.LEFT, 0);
                c.restore(); maxScroll = 0; return;
            }
            y += 2.4f * R;
            y = lineScore(c, px, y, cw) + 2.4f * R;
            y = winChance(c, px, y, cw);
            y = leaders(c, px, y, cw);
            y = scoring(c, px, y, cw);
            y = stats(c, px, y, cw);
            c.restore();
            maxScroll = Math.max(0, y + 3 * R - hh);
            // the foot
            text(c, "◀ ▶ choose   ·   OK select   ·   ▲ ▼ move   ·   Back close", h.fMed, 1.1f, Ui.WHITE_60, px, hh - 1.6f * R, Paint.Align.LEFT, 0);
        }

        /** the game's buttons, under the scoreboard: Sports' own controls, the one the remote is on lit and ringed */
        private float buttons(Canvas c, float x, float y, float cw) {
            boolean air = GlassPanel.look.equals("air");
            float ch = SportsLook.CTRL_H * R, top = y + 1.5f * R, pad = SportsLook.CTRL_PAD * R, bx = x;
            RectF on = null;
            for (int i = 0; i <= actL.size(); i++) {
                String lab = i < actL.size() ? actL.get(i) : "Update";
                if (air) lab = lab.toUpperCase(Locale.US);
                float sp = air ? .05f : 0;
                tp.setTypeface(h.fSemi); tp.setTextSize(SportsLook.T_CTRL * R); tp.setLetterSpacing(sp);
                float bw = tp.measureText(lab) + 2 * pad; tp.setLetterSpacing(0);
                if (bx + bw > x + cw) break;
                RectF r = new RectF(bx, top, bx + bw, top + ch);
                boolean sel = i == act;
                SportsLook.ctrl(c, p, r, R, sel);
                text(c, lab, h.fSemi, SportsLook.T_CTRL, SportsLook.ctrlText(sel), r.centerX(), r.centerY() + .46f * R, Paint.Align.CENTER, sp);
                if (sel) on = r;
                bx += bw + .8f * R;
            }
            if (!actNote.isEmpty() && bx < x + cw - 10 * R) text(c, ellip(actNote, h.fMed, SportsLook.T_LABEL, x + cw - bx - R), h.fMed, SportsLook.T_LABEL, DIM, bx + .6f * R, top + ch / 2 + .4f * R, Paint.Align.LEFT, 0);
            if (on != null) { float rad = SportsLook.ctrlRadius(ch); SportsLook.glow(c, p, on, rad, R, GOLD, .8f, SportsLook.GLOW_CTRL); SportsLook.ring(c, p, on, rad, R, 255); }
            return top + ch;
        }

        /** the scoreboard across the top, split in the teams' colours; returns where the page goes on */
        private float header(Canvas c, int w) {
            float hh = 17 * R;
            if (teams.size() < 2) { text(c, "Loading…", h.fMed, 1.5f, DIM, 4 * R, 8 * R, Paint.Align.LEFT, 0); return hh; }
            int c0 = teamCol(0), c1 = teamCol(1);
            p.setColor(c0); c.drawRect(0, 0, w, hh, p);
            Path sp = new Path(); sp.moveTo(w * .54f, 0); sp.lineTo(w, 0); sp.lineTo(w, hh); sp.lineTo(w * .46f, hh); sp.close();
            p.setColor(c1); c.drawPath(sp, p);
            p.setColor(0x6B04070A); c.drawRect(0, 0, w, hh, p);
            float cx = w / 2f, ly = 3.4f * R, d = 9 * R;
            String st = state();
            JSONObject cm = comp(), sto = cm == null ? null : cm.optJSONObject("status"), ty = sto == null ? null : sto.optJSONObject("type");
            String detail = ty == null ? "" : ty.optString("shortDetail");
            boolean live = "in".equals(st), post = "post".equals(st);
            for (int i = 0; i < 2; i++) {
                JSONObject t = teams.get(i);
                boolean left = i == 0;
                float lx = left ? 4 * R : w - 4 * R - d;
                p.setColor(Color.WHITE); c.drawCircle(lx + d / 2, ly + d / 2, d / 2, p);
                Bitmap b = logo(t.optString("logo"));
                if (b != null) { float s = Math.min(d * .7f / b.getWidth(), d * .7f / b.getHeight()); float bw = b.getWidth() * s, bh = b.getHeight() * s; c.drawBitmap(b, null, new RectF(lx + (d - bw) / 2, ly + (d - bh) / 2, lx + (d + bw) / 2, ly + (d + bh) / 2), p); }
                float nx = left ? lx + d + 2 * R : lx - 2 * R;
                Paint.Align al = left ? Paint.Align.LEFT : Paint.Align.RIGHT;
                text(c, ellip(t.optString("name").toUpperCase(Locale.US), heavy, 3.2f, cx - 14 * R - (d + 6 * R)), heavy, 3.2f, Color.WHITE, nx, ly + 4.6f * R, al, .01f);
                if (!t.optString("rec").isEmpty()) text(c, t.optString("rec"), h.fMed, 1.4f, SportsLook.SOFT, nx, ly + 7f * R, al, 0);
                String sc = t.optString("score");
                if ((live || post) && !sc.isEmpty()) {
                    boolean dim = post && !t.optBoolean("winner");
                    text(c, sc, heavy, 8f, dim ? SportsLook.SUB : Color.WHITE, left ? cx - 7 * R : cx + 7 * R, ly + 7.6f * R, left ? Paint.Align.RIGHT : Paint.Align.LEFT, -.02f);
                }
            }
            text(c, detail.toUpperCase(Locale.US), heavy, 1.8f, live ? GOLD : Color.WHITE, cx, ly + 4.4f * R, Paint.Align.CENTER, .04f);
            if (live) { p.setColor(LIVE_C); c.drawRoundRect(new RectF(cx - 2.6f * R, 1.2f * R, cx + 2.6f * R, 3.1f * R), .4f * R, .4f * R, p); text(c, "LIVE", heavy, 1.1f, SportsLook.ON_LIVE, cx, 2.55f * R, Paint.Align.CENTER, .1f); }
            JSONObject gi = sum == null ? null : sum.optJSONObject("gameInfo"), v = gi == null ? null : gi.optJSONObject("venue");
            if (v != null) text(c, v.optString("fullName").toUpperCase(Locale.US), cond, 1.1f, Ui.WHITE_85, cx, hh - 1.4f * R, Paint.Align.CENTER, .1f);
            return hh;
        }

        /** the score by quarter / period / inning */
        private float lineScore(Canvas c, float x, float y, float cw) {
            if (teams.size() < 2) return y;
            JSONArray a = teams.get(0).optJSONArray("lines"), b = teams.get(1).optJSONArray("lines");
            int n = Math.max(a == null ? 0 : a.length(), b == null ? 0 : b.length());
            if (n == 0) return y - 2.4f * R;
            boolean base = sport.equals("baseball");
            head(c, base ? "BY INNING" : sport.equals("hockey") ? "BY PERIOD" : sport.equals("soccer") ? "BY HALF" : "BY QUARTER", x, y + 1.4f * R);
            float top = y + 2.8f * R, rowH = 3.4f * R, nameW = 14 * R, colW = Math.min(5.2f * R, (cw - nameW - 6 * R) / (n + 1));
            RectF box = new RectF(x, top, x + nameW + colW * (n + 1) + 3 * R, top + rowH * 3 + R);
            p.setColor(CARD); c.drawRoundRect(box, SportsLook.card(1.2f * R), SportsLook.card(1.2f * R), p);
            for (int k = 0; k < n; k++) {
                String lab = base ? String.valueOf(k + 1) : sport.equals("football") || sport.equals("basketball") ? (k < 4 ? String.valueOf(k + 1) : "OT" + (k > 4 ? (k - 3) : "")) : String.valueOf(k + 1);
                text(c, lab, cond, 1.2f, DIM, x + nameW + colW * k + colW / 2, top + 2.3f * R, Paint.Align.CENTER, 0);
            }
            text(c, base ? "R" : "T", cond, 1.2f, DIM, x + nameW + colW * n + colW / 2, top + 2.3f * R, Paint.Align.CENTER, 0);
            for (int i = 0; i < 2; i++) {
                JSONObject t = teams.get(i); JSONArray l = t.optJSONArray("lines");
                float ry = top + rowH * (i + 1) + 2.3f * R;
                p.setColor(teamCol(i)); c.drawRect(x + 1.2f * R, ry - 1.5f * R, x + 1.6f * R, ry + .3f * R, p);
                text(c, t.optString("abbr").isEmpty() ? t.optString("name") : t.optString("abbr"), heavy, 1.6f, Color.WHITE, x + 2.4f * R, ry, Paint.Align.LEFT, 0);
                for (int k = 0; k < n; k++) text(c, l != null && k < l.length() ? l.optString(k) : "", cond, 1.6f, SportsLook.SOFT, x + nameW + colW * k + colW / 2, ry, Paint.Align.CENTER, 0);
                text(c, t.optString("score"), heavy, 1.8f, Color.WHITE, x + nameW + colW * n + colW / 2, ry, Paint.Align.CENTER, 0);
            }
            return box.bottom;
        }

        /** the chance each side had of winning, play by play */
        private float winChance(Canvas c, float x, float y, float cw) {
            JSONArray wp = sum.optJSONArray("winprobability");
            if (wp == null || wp.length() < 4 || teams.size() < 2) return y;
            head(c, "WIN CHANCE", x, y + 1.4f * R);
            float top = y + 2.8f * R, gh = 13 * R;
            RectF box = new RectF(x, top, x + cw, top + gh + 2 * R);
            p.setColor(CARD); c.drawRoundRect(box, SportsLook.card(1.2f * R), SportsLook.card(1.2f * R), p);
            float gx0 = x + 9 * R, gx1 = x + cw - 2 * R, gy0 = top + R, gy1 = top + gh + R, mid = (gy0 + gy1) / 2;
            int n = wp.length();
            Path line = new Path(), upper = new Path(), lower = new Path();
            for (int i = 0; i < n; i++) {
                double hw = wp.optJSONObject(i) == null ? .5 : wp.optJSONObject(i).optDouble("homeWinPercentage", .5);
                float px0 = gx0 + (gx1 - gx0) * i / (n - 1f), py = (float) (gy1 - (gy1 - gy0) * hw);
                if (i == 0) { line.moveTo(px0, py); upper.moveTo(px0, mid); lower.moveTo(px0, mid); }
                line.lineTo(px0, py); upper.lineTo(px0, Math.min(py, mid)); lower.lineTo(px0, Math.max(py, mid));
            }
            upper.lineTo(gx1, mid); upper.close(); lower.lineTo(gx1, mid); lower.close();
            p.setColor((teamCol(1) & 0xFFFFFF) | Ui.BLACK_55); c.drawPath(upper, p);        // (home winning: above the middle)
            p.setColor((teamCol(0) & 0xFFFFFF) | Ui.BLACK_55); c.drawPath(lower, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1, .08f * R)); p.setColor(Ui.WHITE_25);
            p.setPathEffect(new DashPathEffect(new float[] { .5f * R, .4f * R }, 0)); c.drawLine(gx0, mid, gx1, mid, p); p.setPathEffect(null);
            p.setStrokeWidth(Math.max(2, .16f * R)); p.setColor(Color.WHITE); c.drawPath(line, p);
            p.setStyle(Paint.Style.FILL);
            double last = wp.optJSONObject(n - 1) == null ? .5 : wp.optJSONObject(n - 1).optDouble("homeWinPercentage", .5);
            String a0 = teams.get(0).optString("abbr"), a1 = teams.get(1).optString("abbr");
            text(c, a1 + " " + Math.round(last * 100) + "%", heavy, 1.6f, Color.WHITE, x + 1.6f * R, gy0 + 1.6f * R, Paint.Align.LEFT, 0);
            text(c, a0 + " " + Math.round((1 - last) * 100) + "%", heavy, 1.6f, Color.WHITE, x + 1.6f * R, gy1 - .2f * R, Paint.Align.LEFT, 0);
            return box.bottom + 2.4f * R;
        }

        /** each team's leaders (passing, rushing, receiving; points, rebounds, assists…) */
        private float leaders(Canvas c, float x, float y, float cw) {
            JSONArray ld = sum.optJSONArray("leaders");
            if (ld == null || ld.length() == 0) return y;
            head(c, "LEADERS", x, y + 1.4f * R);
            float top = y + 2.8f * R, colW = (cw - 1.6f * R) / 2, rowH = 4.6f * R;
            float bottom = top;
            for (int ti = 0; ti < Math.min(2, ld.length()); ti++) {
                JSONObject tl = ld.optJSONObject(ti);
                if (tl == null) continue;
                JSONObject tm = tl.optJSONObject("team");
                int side = 0;
                if (tm != null && teams.size() == 2 && tm.optString("id").equals(teams.get(1).optString("id"))) side = 1;
                float cx0 = x + side * (colW + 1.6f * R);
                JSONArray cats = tl.optJSONArray("leaders");
                int rows = cats == null ? 0 : Math.min(4, cats.length());
                RectF box = new RectF(cx0, top, cx0 + colW, top + rows * rowH + 3.4f * R);
                p.setColor(CARD); c.drawRoundRect(box, SportsLook.card(1.2f * R), SportsLook.card(1.2f * R), p);
                p.setColor(teamCol(side)); c.drawRect(cx0, top + 1.2f * R, cx0 + .4f * R, top + 2.6f * R, p);
                text(c, tm == null ? "" : tm.optString("displayName", tm.optString("abbreviation")), heavy, 1.5f, Color.WHITE, cx0 + 1.4f * R, top + 2.4f * R, Paint.Align.LEFT, 0);
                for (int k = 0; k < rows; k++) {
                    JSONObject cat = cats.optJSONObject(k);
                    JSONArray who = cat == null ? null : cat.optJSONArray("leaders");
                    JSONObject one = who == null ? null : who.optJSONObject(0);
                    if (one == null) continue;
                    JSONObject ath = one.optJSONObject("athlete");
                    float ry = top + 3.4f * R + k * rowH;
                    text(c, cat.optString("displayName").toUpperCase(Locale.US), cond, 1f, DIM, cx0 + 1.4f * R, ry + 1.6f * R, Paint.Align.LEFT, .08f);
                    text(c, ellip(ath == null ? "" : ath.optString("displayName"), h.fSemi, 1.4f, colW * .45f), h.fSemi, 1.4f, Color.WHITE, cx0 + 1.4f * R, ry + 3.6f * R, Paint.Align.LEFT, 0);
                    text(c, ellip(one.optString("displayValue"), h.fMed, 1.3f, colW * .5f), h.fMed, 1.3f, SportsLook.SOFT, cx0 + colW - 1.4f * R, ry + 3.6f * R, Paint.Align.RIGHT, 0);
                }
                bottom = Math.max(bottom, box.bottom);
            }
            return bottom + 2.4f * R;
        }

        /** the scoring plays (the latest at the top) */
        private float scoring(Canvas c, float x, float y, float cw) {
            JSONArray sp = sum.optJSONArray("scoringPlays");
            if (sp == null || sp.length() == 0) return y;
            head(c, "SCORING", x, y + 1.4f * R);
            float top = y + 2.8f * R, rowH = 3.6f * R;
            int n = Math.min(12, sp.length());
            RectF box = new RectF(x, top, x + cw, top + n * rowH + R);
            p.setColor(CARD); c.drawRoundRect(box, SportsLook.card(1.2f * R), SportsLook.card(1.2f * R), p);
            for (int k = 0; k < n; k++) {
                JSONObject pl = sp.optJSONObject(sp.length() - 1 - k);
                if (pl == null) continue;
                float ry = top + R + k * rowH + 2.2f * R;
                JSONObject tm = pl.optJSONObject("team"), per = pl.optJSONObject("period"), clk = pl.optJSONObject("clock");
                int side = tm != null && teams.size() == 2 && tm.optString("id").equals(teams.get(1).optString("id")) ? 1 : 0;
                p.setColor(teamCol(side)); c.drawCircle(x + 1.8f * R, ry - .5f * R, .45f * R, p);
                String when = (per == null ? "" : "Q" + per.optInt("number")) + (clk == null ? "" : " " + clk.optString("displayValue"));
                if (!sport.equals("football") && !sport.equals("basketball")) when = per == null ? "" : String.valueOf(per.optInt("number"));
                text(c, when, cond, 1.2f, DIM, x + 3.2f * R, ry, Paint.Align.LEFT, 0);
                String score = pl.optString("awayScore") + "–" + pl.optString("homeScore");
                float sw = measure(score, heavy, 1.4f);
                text(c, score, heavy, 1.4f, Color.WHITE, x + cw - 1.6f * R, ry, Paint.Align.RIGHT, 0);
                text(c, ellip(pl.optString("text"), h.fMed, 1.25f, cw - 12 * R - sw), h.fMed, 1.25f, SportsLook.SOFT, x + 9 * R, ry, Paint.Align.LEFT, 0);
            }
            return box.bottom + 2.4f * R;
        }

        /** the team stats, side by side with a bar for who's ahead */
        private float stats(Canvas c, float x, float y, float cw) {
            JSONObject bx = sum.optJSONObject("boxscore");
            JSONArray ts = bx == null ? null : bx.optJSONArray("teams");
            if (ts == null || ts.length() < 2) return y;
            JSONObject t0 = ts.optJSONObject(0), t1 = ts.optJSONObject(1);
            // (ESPN lists the away team first here too, but check)
            if (t0 != null && teams.size() == 2 && t0.optJSONObject("team") != null && t0.optJSONObject("team").optString("id").equals(teams.get(1).optString("id"))) { JSONObject t = t0; t0 = t1; t1 = t; }
            JSONArray s0 = t0 == null ? null : t0.optJSONArray("statistics"), s1 = t1 == null ? null : t1.optJSONArray("statistics");
            if (s0 == null || s1 == null) return y;
            head(c, "TEAM STATS", x, y + 1.4f * R);
            float top = y + 2.8f * R, rowH = 3.4f * R;
            int n = Math.min(14, Math.min(s0.length(), s1.length()));
            RectF box = new RectF(x, top, x + cw, top + n * rowH + R);
            p.setColor(CARD); c.drawRoundRect(box, SportsLook.card(1.2f * R), SportsLook.card(1.2f * R), p);
            float mid = x + cw / 2;
            for (int k = 0; k < n; k++) {
                JSONObject a = s0.optJSONObject(k), b = s1.optJSONObject(k);
                if (a == null || b == null) continue;
                float ry = top + R + k * rowH + 2.1f * R;
                String va = a.optString("displayValue"), vb = b.optString("displayValue");
                text(c, a.optString("label", a.optString("name")), h.fMed, 1.2f, DIM, mid, ry, Paint.Align.CENTER, 0);
                double na = num(va), nb = num(vb);
                boolean aw = na > nb, bw = nb > na;
                text(c, va, heavy, 1.5f, aw ? Color.WHITE : SportsLook.SUB, x + 2 * R, ry, Paint.Align.LEFT, 0);
                text(c, vb, heavy, 1.5f, bw ? Color.WHITE : SportsLook.SUB, x + cw - 2 * R, ry, Paint.Align.RIGHT, 0);
                if (na >= 0 && nb >= 0 && na + nb > 0) {
                    float bwid = cw * .16f, by = ry + .6f * R, fa = (float) (na / (na + nb));
                    p.setColor(Ui.WHITE_12); c.drawRect(mid - bwid, by, mid + bwid, by + .3f * R, p);
                    float split = mid - bwid + 2 * bwid * fa;
                    p.setColor(teamCol(0)); c.drawRect(mid - bwid, by, split, by + .3f * R, p);
                    p.setColor(teamCol(1)); c.drawRect(split, by, mid + bwid, by + .3f * R, p);
                }
            }
            return box.bottom + 2.4f * R;
        }
    }
    private static double num(String s) {
        try { String t = s.trim(); if (t.contains("-") && !t.startsWith("-")) t = t.substring(0, t.indexOf('-')); if (t.contains("/")) t = t.substring(0, t.indexOf('/')); if (t.contains(":")) { String[] p = t.split(":"); return Double.parseDouble(p[0]) * 60 + Double.parseDouble(p[1]); } return Double.parseDouble(t.replace("%", "")); }
        catch (Exception e) { return -1; }
    }
}
