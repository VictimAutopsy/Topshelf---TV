package com.topshelf.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings, drawn by Android. On a TV it's a full screen: the sections listed down the left (moving through
 * them shows each one's settings on the right; ▶ or OK goes into them, ◀ or Back comes out). On a narrow
 * screen it's a glass sheet on the right.
 *
 * Earlier: a glass sheet on the right with a list of rows (a setting and its value,
 * ‹ › to change it, or a section to open). What the rows are and what each one does stays with the
 * page (nhSheet hands the rows over); moving up and down happens here, and changing a value, opening a
 * section or going back asks the page, which hands back the new rows.
 */
final class NativeSheet extends FrameLayout {
    private static final float SHEET_W = 42, RAD = 3, ROWS_TOP = 9.4f, FOOT_H = 4.6f, LEFT_W = 31;

    /* the full screen's left column: the sections */
    private final boolean pane;
    private LinearLayout left;
    private final List<LinearLayout> secViews = new ArrayList<>();
    private int leftSel, curSec = -1;
    private boolean focusLeft = true;
    private String curSub = "";
    private long askedAt;

    private final NativeHome h;
    private final float R;
    final GlassPanel panel;
    private final TextView back, title, foot;
    private final FrameLayout clip;
    private final LinearLayout rows;
    private final List<Row> rowViews = new ArrayList<>();
    private JSONArray data = new JSONArray();
    private String sig = "";
    private int sel;
    private boolean shown;
    private EditText input;
    private int editing = -1;

    private final class Row {
        final LinearLayout v;
        final TextView label, value, chev;
        JSONObject d;
        Row(LinearLayout v, TextView label, TextView value, TextView chev) { this.v = v; this.label = label; this.value = value; this.chev = chev; }
    }

    NativeSheet(NativeHome host, GlassPanel glass) {
        super(host.getContext());
        h = host; R = host.R;
        Context c = getContext();
        panel = glass;
        panel.setLiquidAllowed(false);
        panel.setElevation(2.4f * R);
        pane = h.SW >= 100;
        int pw = pane ? h.W - h.px(4) : h.px(Math.min(SHEET_W, h.SW - 4)), ph = h.H - h.px(4);
        int lw = pane ? h.px(LEFT_W) : 0;
        LayoutParams pl = new LayoutParams(pw, ph);
        pl.leftMargin = h.W - pw - h.px(2); pl.topMargin = h.px(2);
        addView(panel, pl);
        if (pane) {
            TextView st = h.text(h.fSemi, 2.6f, Color.WHITE); st.setLetterSpacing(-.02f); st.setText("Settings");
            LayoutParams stl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); stl.leftMargin = h.px(3f); stl.topMargin = h.px(3.6f);
            panel.addView(st, stl);
            left = new LinearLayout(c); left.setOrientation(LinearLayout.VERTICAL);
            LayoutParams ll = new LayoutParams(lw - h.px(3.2f), ph - h.px(ROWS_TOP) - h.px(FOOT_H)); ll.leftMargin = h.px(1.6f); ll.topMargin = h.px(ROWS_TOP);
            panel.addView(left, ll);
            View div = new View(c); div.setBackgroundColor(Ui.WHITE_12);
            LayoutParams dl = new LayoutParams(Math.max(1, h.px(.1f)), ph - h.px(6)); dl.leftMargin = lw; dl.topMargin = h.px(3);
            panel.addView(div, dl);
        }

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.VERTICAL);
        back = h.text(h.fMed, Ui.TEXT_M, Ui.WHITE_60);
        head.addView(back);
        title = h.text(h.fSemi, 2.6f, Color.WHITE);
        title.setLetterSpacing(-.02f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); tl.topMargin = h.px(.4f);
        head.addView(title, tl);
        LayoutParams hl = new LayoutParams(pw - lw - h.px(5.2f), LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        hl.leftMargin = lw + h.px(4); hl.bottomMargin = ph - h.px(ROWS_TOP - .2f);
        hl.gravity = Gravity.TOP; hl.topMargin = h.px(3.2f);
        panel.addView(head, hl);

        clip = new FrameLayout(c);
        clip.setClipChildren(true);
        rows = new LinearLayout(c);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(h.px(1), h.px(.4f), h.px(1), h.px(.4f));
        rows.setClipToPadding(false);
        clip.addView(rows, new LayoutParams(LayoutParams.MATCH_PARENT, h.px(400)));
        LayoutParams cl = new LayoutParams(pw - lw - h.px(3.2f), ph - h.px(ROWS_TOP) - h.px(FOOT_H));
        cl.leftMargin = lw + h.px(1.6f); cl.topMargin = h.px(ROWS_TOP);
        panel.addView(clip, cl);

        foot = h.text(h.fReg, Ui.TEXT_S, Ui.WHITE_60);
        foot.setLineSpacing(0, 1.3f); foot.setMaxLines(2); foot.setEllipsize(TextUtils.TruncateAt.END);
        LayoutParams fl = new LayoutParams(pw - lw - h.px(7), LayoutParams.WRAP_CONTENT);
        fl.leftMargin = lw + h.px(4); fl.topMargin = ph - h.px(FOOT_H) + h.px(1.2f);
        panel.addView(foot, fl);

        setClipChildren(false);
        if (pane) { panel.setAlpha(0f); panel.setTranslationX(h.px(3)); }
        else panel.setTranslationX(pw + h.px(4));
        setVisibility(GONE);
    }

    // ================================================================== the sections (full screen)

    private void buildLeft(JSONArray secs) {
        if (left == null || secs == null) return;
        if (secViews.size() != secs.length()) {
            left.removeAllViews(); secViews.clear();
            for (int i = 0; i < secs.length(); i++) {
                LinearLayout v = new LinearLayout(getContext()); v.setOrientation(LinearLayout.VERTICAL);
                v.setPadding(h.px(1.4f), h.px(.95f), h.px(1.4f), h.px(.95f));
                TextView n = h.text(h.fSemi, Ui.TEXT_XL, Color.WHITE); n.setSingleLine(true); n.setEllipsize(TextUtils.TruncateAt.END);
                TextView a = h.text(h.fReg, Ui.TEXT_S, Ui.WHITE_60); a.setSingleLine(true); a.setEllipsize(TextUtils.TruncateAt.END);
                v.addView(n); LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); al.topMargin = h.px(.15f); v.addView(a, al);
                LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); vl.bottomMargin = h.px(.35f);
                left.addView(v, vl);
                secViews.add(v);
            }
        }
        for (int i = 0; i < secs.length(); i++) {
            JSONObject x = secs.optJSONObject(i); if (x == null) continue;
            LinearLayout v = secViews.get(i);
            ((TextView) v.getChildAt(0)).setText(x.optString("name"));
            ((TextView) v.getChildAt(1)).setText(x.optString("about"));
        }
        styleLeft();
    }
    private void styleLeft() {
        for (int i = 0; i < secViews.size(); i++) {
            LinearLayout v = secViews.get(i);
            boolean on = i == leftSel;
            int bg = on && focusLeft ? GlassPanel.selFill() : on ? Ui.WHITE_12 : 0, st = GlassPanel.selText();
            if (bg == 0) v.setBackground(null);
            else if (GlassPanel.look.equals("air")) v.setBackground(new GlassPanel.Cut(bg));
            else { GradientDrawable g = new GradientDrawable(); g.setCornerRadius(GlassPanel.selRadius(1.5f * R)); g.setColor(bg); v.setBackground(g); }
            ((TextView) v.getChildAt(0)).setTextColor(on && focusLeft ? st : Color.WHITE);
            ((TextView) v.getChildAt(1)).setTextColor(on && focusLeft ? (st & 0x00FFFFFF) | Ui.BLACK_60 : Ui.WHITE_60);
        }
    }
    /** show that section's settings on the right (asked of the page a moment after the remote settles) */
    private boolean askPending;
    private final Runnable askSection = new Runnable() { @Override public void run() {
        askPending = false;
        askedAt = android.os.SystemClock.uptimeMillis();
        h.page("window.nhSheetSection && nhSheetSection(" + leftSel + ")");
    } };
    private void askSoon(long ms) { removeCallbacks(askSection); if (ms <= 0) askSection.run(); else { askPending = true; postDelayed(askSection, ms); } }
    private void setFocusLeft(boolean on) {
        if (focusLeft == on) return;
        focusLeft = on;
        styleLeft();
        style(true);
    }

    boolean shownNow() { return shown; }
    boolean editingNow() { return editing >= 0; }

    void show() {
        if (shown) return;
        shown = true;
        setVisibility(VISIBLE);
        panel.animate().cancel();
        if (pane) panel.animate().translationX(0).alpha(1f).setDuration(Ui.MOVE).setInterpolator(TileView.EASE).start();
        else panel.animate().translationX(0).setDuration(Ui.SLIDE).setInterpolator(TileView.EASE).start();
        panel.refreshTint();
    }
    void hide() {
        if (!shown) return;
        shown = false;
        endEdit(false);
        removeCallbacks(askSection); askPending = false;
        panel.animate().cancel();
        Runnable gone = new Runnable() { @Override public void run() {
            if (shown) return;
            setVisibility(GONE);
            focusLeft = true; curSec = -1; curSub = "";          // (opening again starts on the sections)
        } };
        if (pane) panel.animate().translationX(h.px(3)).alpha(0f).setDuration(Ui.BASE).setInterpolator(TileView.EASE).withEndAction(gone).start();
        else panel.animate().translationX(panel.getWidth() + h.px(4)).setDuration(Ui.MOVE).setInterpolator(TileView.EASE).withEndAction(gone).start();
    }

    /** the rows from the page */
    void setData(JSONObject o) {
        String b = o.optString("back");
        if (pane) {
            int sec = o.optInt("sec", -1);
            String sub = o.optString("sub");
            buildLeft(o.optJSONArray("sections"));
            if (sec < 0) {
                // (the page's list of sections: here they're on the left already, so straight to the chosen one)
                curSec = -1; title.setText(""); back.setVisibility(GONE);
                askSoon(0);
                return;
            }
            boolean asked = android.os.SystemClock.uptimeMillis() - askedAt < 1500;
            // a jump straight to a setting (e.g. "set up your playlist"), or a page inside a section: on the right
            if ((!asked && sec != curSec) || (!sub.isEmpty() && !sub.equals(curSub))) { focusLeft = false; leftSel = sec; }
            else if (!focusLeft) leftSel = sec;
            curSec = sec; curSub = sub;
            styleLeft();
            b = sub.isEmpty() ? "" : b;
        }
        back.setText(b.isEmpty() ? "" : "‹ " + b);
        back.setVisibility(b.isEmpty() ? GONE : VISIBLE);
        title.setText(o.optString("title"));
        foot.setText(o.optString("foot"));
        data = o.optJSONArray("rows") == null ? new JSONArray() : o.optJSONArray("rows");
        StringBuilder s = new StringBuilder(o.optString("title")).append('|');
        for (int i = 0; i < data.length(); i++) { JSONObject r = data.optJSONObject(i); s.append(r.optString("label")).append(r.optBoolean("chev")).append(r.optBoolean("gap")).append('|'); }
        boolean rebuilt = false;
        if (!s.toString().equals(sig)) { sig = s.toString(); rebuild(); rebuilt = true; }
        for (int i = 0; i < rowViews.size(); i++) fill(i);
        sel = Math.max(0, Math.min(o.optInt("sel", sel), data.length() - 1));
        if (editing >= 0 && editing < rowViews.size()) fill(editing);
        fitHeight();
        style(!rebuilt);
    }

    private void rebuild() {
        endEdit(false);
        rows.removeAllViews();
        rowViews.clear();
        for (int i = 0; i < data.length(); i++) {
            JSONObject d = data.optJSONObject(i);
            if (d.optBoolean("head")) {
                // a group's heading: small, quiet capitals; never chosen
                LinearLayout v = new LinearLayout(getContext());
                v.setPadding(h.px(1.4f), h.px(i == 0 ? .3f : 1.9f), h.px(1.4f), h.px(.5f));
                TextView l = h.text(h.fSemi, Ui.TEXT_S, Ui.WHITE_60);
                l.setLetterSpacing(.1f); l.setAllCaps(true); l.setSingleLine(true);
                v.addView(l, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
                TextView val = h.text(h.fMed, Ui.TEXT_S, 0), ch = h.text(h.fMed, Ui.TEXT_S, 0);
                val.setVisibility(GONE); ch.setVisibility(GONE);
                rows.addView(v, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
                rowViews.add(new Row(v, l, val, ch));
                continue;
            }
            boolean sec = d.optBoolean("chev");
            LinearLayout v = new LinearLayout(getContext());
            v.setGravity(Gravity.CENTER_VERTICAL);
            v.setPadding(h.px(1.4f), h.px(sec ? 1.35f : 1f), h.px(1.4f), h.px(sec ? 1.35f : 1f));
            v.setMinimumHeight(h.px(3.6f));
            TextView l = h.text(sec ? h.fSemi : h.fMed, sec ? 1.5f : 1.35f, Color.WHITE);
            l.setSingleLine(true); l.setEllipsize(TextUtils.TruncateAt.END);
            v.addView(l, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            TextView val = h.text(h.fMed, sec ? 1.05f : 1.35f, Ui.WHITE_60);
            val.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            /* a value may take up to 62% of its row (it was held to a narrow column made for the small sheet: on the
               full-screen Settings long values were cut off with half the row empty beside them) */
            int rowW = rows.getWidth() > 0 ? rows.getWidth() : clip.getWidth() > 0 ? clip.getWidth() : h.px(34);
            int valMax = Math.max(h.px(20), Math.round(rowW * .62f));
            if (sec) { val.setMaxLines(2); val.setMaxWidth(valMax); val.setLineSpacing(0, 1.15f); }
            else { val.setSingleLine(true); val.setEllipsize(TextUtils.TruncateAt.END); val.setMaxWidth(valMax); }
            LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); vl.leftMargin = h.px(1.5f);
            v.addView(val, vl);
            TextView ch = h.text(h.fSemi, 1.6f, Ui.WHITE_60);
            ch.setText("›");
            ch.setVisibility(sec ? VISIBLE : GONE);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); cl.leftMargin = h.px(.6f);
            v.addView(ch, cl);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            if (d.optBoolean("gap") && i > 0) rl.topMargin = h.px(1.4f);
            rows.addView(v, rl);
            rowViews.add(new Row(v, l, val, ch));
        }
        rows.setTranslationY(0);
    }

    /** the list is as tall as its rows with their text in (measured after the text is set) */
    private void fitHeight() {
        LayoutParams lp = (LayoutParams) rows.getLayoutParams();
        rows.measure(MeasureSpec.makeMeasureSpec(clip.getLayoutParams().width, MeasureSpec.EXACTLY), MeasureSpec.UNSPECIFIED);
        int hh = Math.max(rows.getMeasuredHeight() + h.px(1), h.px(10));
        if (lp.height != hh) { lp.height = hh; rows.setLayoutParams(lp); }
    }

    private void fill(int i) {
        Row r = rowViews.get(i);
        r.d = data.optJSONObject(i);
        String l = r.d.optString("label");
        if (!l.contentEquals(r.label.getText())) r.label.setText(l);
        String v = r.d.optString("value");
        boolean cur = i == sel && editing < 0 && !(pane && focusLeft);
        if (r.d.optBoolean("cycle") && !v.isEmpty() && cur) v = "‹  " + v + "  ›";
        if (!v.contentEquals(r.value.getText())) r.value.setText(v);
    }

    private int styled = -1;
    private void style(boolean animate) {
        for (int i = 0; i < rowViews.size(); i++) styleRow(i, animate);
        styled = sel;
        reveal(animate);
    }
    /** moving: only the row you left and the one you're on change */
    private void restyle() {
        if (styled >= 0 && styled < rowViews.size()) styleRow(styled, true);
        styleRow(sel, true);
        styled = sel;
        reveal(true);
    }
    private void styleRow(int i, boolean animate) {
        {
            Row r = rowViews.get(i);
            if (data.optJSONObject(i) != null && data.optJSONObject(i).optBoolean("head")) { fill(i); return; }
            boolean on = i == sel && !(pane && focusLeft);
            int st = GlassPanel.selText(), dim = (st & 0x00FFFFFF) | Ui.BLACK_60;
            if (on && GlassPanel.look.equals("air")) r.v.setBackground(new GlassPanel.Cut(GlassPanel.selFill()));
            else if (on) { GradientDrawable g = new GradientDrawable(); g.setCornerRadius(GlassPanel.selRadius(1.5f * R)); g.setColor(GlassPanel.selFill()); r.v.setBackground(g); }
            else r.v.setBackground(null);
            r.label.setTextColor(on ? st : Color.WHITE);
            r.value.setTextColor(on ? dim : Ui.WHITE_60);
            r.chev.setTextColor(on ? dim : Ui.WHITE_60);
            float s = on ? 1.02f : 1f;
            r.v.setPivotX(r.v.getWidth() / 2f);
            if (animate && r.v.getScaleX() != s) r.v.animate().scaleX(s).scaleY(s).setDuration(Ui.FAST).setInterpolator(TileView.EASE).start();
            else { r.v.setScaleX(s); r.v.setScaleY(s); }
            fill(i);
        }
    }

    private void reveal(final boolean animate) {
        if (sel >= rowViews.size()) return;
        // (a group's heading comes into view with its first setting)
        final View v = sel > 0 && data.optJSONObject(sel - 1) != null && data.optJSONObject(sel - 1).optBoolean("head") ? rowViews.get(sel - 1).v : rowViews.get(sel).v;
        if (v.getHeight() == 0) { v.post(new Runnable() { @Override public void run() { if (v.getHeight() > 0) reveal(false); } }); return; }
        float viewH = clip.getHeight() > 0 ? clip.getHeight() : clip.getLayoutParams().height;
        float cur = -rows.getTranslationY(), top = v.getTop(), bot = v.getBottom(), pad = h.px(2.5f);
        float to = cur;
        if (top - pad < cur) to = Math.max(0, top - pad);
        else if (bot + pad > cur + viewH) to = bot + pad - viewH;
        to = Math.max(0, Math.min(to, Math.max(0, rows.getHeight() - viewH)));
        if (to == cur) return;
        if (animate) rows.animate().translationY(-to).setDuration(Ui.FAST).setInterpolator(TileView.EASE).start();
        else rows.setTranslationY(-to);
    }

    // ================================================================== keys

    boolean key(char k) {
        if (pane && focusLeft) {
            if (k == 'u' && leftSel > 0) { leftSel--; styleLeft(); askSoon(160); }
            else if (k == 'd' && leftSel < secViews.size() - 1) { leftSel++; styleLeft(); askSoon(160); }
            else if (k == 'r') enterRows();
            return true;
        }
        if (data.length() == 0) return true;
        if (pane && k == 'l' && curSub.isEmpty() && !(sel < data.length() && data.optJSONObject(sel).optBoolean("cycle"))) { setFocusLeft(true); return true; }
        if (k == 'u' || k == 'd') {
            // (stepping over the group headings)
            for (int n = 0; n < data.length(); n++) { sel = (sel + (k == 'd' ? 1 : -1) + data.length()) % data.length(); if (!data.optJSONObject(sel).optBoolean("head")) break; }
            restyle();
            return true;
        }
        JSONObject r = data.optJSONObject(sel);
        if ((k == 'l' || k == 'r') && r.optBoolean("cycle")) { h.page("window.nhSheetKey && nhSheetKey(" + sel + ",'" + k + "')"); return true; }
        return true;
    }
    /* a finger (phones): 1 a setting was tapped (it's chosen: OK acts on it), -1 the way back (the "‹" line, or
       outside the panel), 0 nothing */
    int focusAt(float x, float y) {
        if (!h.hit(panel, x, y, 0) || (back.getVisibility() == VISIBLE && h.hit(back, x, y, .6f))) return -1;
        if (pane) for (int i = 0; i < secViews.size(); i++) if (h.hit(secViews.get(i), x, y, 0)) {
            leftSel = i; focusLeft = true; styleLeft(); style(true); askSoon(0); return 0;
        }
        if (pane && focusLeft) { focusLeft = false; styleLeft(); }
        for (int i = 0; i < rowViews.size(); i++) {
            JSONObject d = data.optJSONObject(i);
            if (d == null || d.optBoolean("head")) continue;
            if (h.hit(rowViews.get(i).v, x, y, 0)) { sel = i; restyle(); return 1; }
        }
        return 0;
    }
    boolean overInput(float x, float y) { return input != null && h.hit(input, x, y, .6f); }
    /** from the sections into the chosen one's settings */
    private void enterRows() {
        if (askPending) askSoon(0);
        if (data.length() == 0) return;
        setFocusLeft(false);
    }
    void ok() {
        if (pane && focusLeft) { enterRows(); return; }
        if (data.length() == 0) return;
        JSONObject r = data.optJSONObject(sel);
        if (r.optBoolean("head")) return;
        if (r.optJSONObject("edit") != null) { startEdit(sel); return; }
        View v = rowViews.get(sel).v;
        v.animate().scaleX(.99f).scaleY(.99f).setDuration(80).withEndAction(new Runnable() { @Override public void run() {
            rowViews.get(Math.min(sel, rowViews.size() - 1)).v.animate().scaleX(1.02f).scaleY(1.02f).setDuration(Ui.QUICK).start();
        } }).start();
        h.page("window.nhSheetKey && nhSheetKey(" + sel + ",'ok')");
    }
    void back() {
        if (editing >= 0) { endEdit(false); return; }
        if (pane) {
            if (focusLeft) h.page("closeSettings()");
            else if (!curSub.isEmpty()) h.page("settingsBack()");
            else setFocusLeft(true);
            return;
        }
        h.page("settingsBack()");
    }

    // ================================================================== typing (a link, a subreddit…)

    private void startEdit(int i) {
        endEdit(false);
        final JSONObject e = data.optJSONObject(i).optJSONObject("edit");
        Row r = rowViews.get(i);
        editing = i;
        input = new EditText(getContext());
        input.setTypeface(h.fMed);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 1.3f * R);
        input.setTextColor(0xFF111111);
        input.setHintTextColor(0x80111111);
        input.setHint(e.optString("ph"));
        input.setSingleLine(true);
        input.setText(e.optString("value"));
        input.setSelectAllOnFocus(e.optString("value").startsWith("http") && e.optString("value").length() > 8);
        int type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        if (e.optBoolean("wide") || "url".equals(e.optString("type"))) type |= InputType.TYPE_TEXT_VARIATION_URI;
        input.setInputType(type);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        input.setPadding(h.px(.8f), h.px(.4f), h.px(.8f), h.px(.4f));
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(.6f * R); bg.setColor(Color.WHITE); bg.setStroke(Math.max(2, h.px(.13f)), 0xFF0A84FF);
        input.setBackground(bg);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int action, KeyEvent ev) {
                if (action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_ACTION_GO || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_UP)) { endEdit(true); return true; }
                return false;
            }
        });
        r.value.setVisibility(GONE);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(h.px(e.optBoolean("wide") ? 24 : 15), LayoutParams.WRAP_CONTENT);
        il.leftMargin = h.px(1.2f);
        r.v.addView(input, 1, il);
        input.setFocusableInTouchMode(true);
        input.requestFocus();
        if (!input.getText().toString().startsWith("http") || input.length() <= 8) input.setSelection(input.length());
        input.postDelayed(new Runnable() { @Override public void run() {
            if (input == null) return;
            InputMethodManager im = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        } }, 120);
    }

    void endEdit(boolean commit) {
        if (input == null) { editing = -1; return; }
        EditText in = input;
        int i = editing;
        String v = in.getText().toString();
        input = null; editing = -1;
        InputMethodManager im = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(in.getWindowToken(), 0);
        in.clearFocus();
        if (in.getParent() != null) ((LinearLayout) in.getParent()).removeView(in);
        if (i >= 0 && i < rowViews.size()) { rowViews.get(i).value.setVisibility(VISIBLE); fill(i); }
        h.act.focusWeb();
        if (commit && i >= 0) h.page("window.nhSheetEdit && nhSheetEdit(" + i + "," + JSONObject.quote(v) + ")");
    }
}
