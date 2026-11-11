package com.topshelf.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Html;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A message in the middle of the screen with a few buttons (an update is ready, speed test results,
 * setting up the Home button…), drawn by Android. The page says what it says and what each button does.
 */
final class NativeDialog extends FrameLayout {
    private static final float W_REM = 52;
    private float wRem() { return Math.min(W_REM, h.SW - 4); }

    private final NativeHome h;
    private final float R;
    final GlassPanel panel;
    private final TextView title, body;
    private final LinearLayout bodyCol, table;
    private final FrameLayout bodyClip;
    private final LinearLayout btns;
    private final List<TextView> buttons = new ArrayList<>();
    private final View scrim;
    private int sel;
    private boolean shown;

    NativeDialog(NativeHome host, GlassPanel glass) {
        super(host.getContext());
        h = host; R = host.R;
        Context c = getContext();
        scrim = new View(c);
        scrim.setBackgroundColor(Ui.BLACK_55);
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        panel = glass;
        panel.setLiquidAllowed(false);
        panel.setElevation(2.4f * R);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(h.px(3), h.px(2.8f), h.px(3), h.px(2.6f));
        title = h.text(h.fSemi, 2f, Color.WHITE);
        title.setLetterSpacing(-.01f);
        col.addView(title);
        bodyClip = new FrameLayout(c);
        bodyClip.setClipChildren(true);
        bodyCol = new LinearLayout(c);
        bodyCol.setOrientation(LinearLayout.VERTICAL);
        body = h.text(h.fReg, Ui.TEXT_M, Ui.WHITE_85);
        body.setLineSpacing(0, 1.35f);
        bodyCol.addView(body, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        table = new LinearLayout(c);
        table.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tbl = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); tbl.topMargin = h.px(1);
        bodyCol.addView(table, tbl);
        bodyClip.addView(bodyCol, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); bl.topMargin = h.px(1.2f);
        col.addView(bodyClip, bl);
        btns = new LinearLayout(c);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); nl.topMargin = h.px(2.2f);
        col.addView(btns, nl);
        panel.addView(col, new LayoutParams(h.px(wRem()), LayoutParams.WRAP_CONTENT));
        addView(panel, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setVisibility(GONE);
    }

    boolean shownNow() { return shown; }

    @SuppressWarnings("deprecation")
    void set(JSONObject o) {
        title.setText(o.optString("title"));
        String html = o.optString("html").replaceAll("(?i)</p>\\s*<p[^>]*>", "<br><br>").replaceAll("(?i)</?p[^>]*>", "");
        body.setText(Html.fromHtml(html).toString().trim().isEmpty() ? "" : Html.fromHtml(html));
        body.setVisibility(body.getText().length() == 0 ? GONE : VISIBLE);
        fillTable(o.optJSONObject("table"));
        bodyCol.setTranslationY(0);
        // a long message scrolls (Up / Down) inside a box that fits the screen
        LinearLayout.LayoutParams bl = (LinearLayout.LayoutParams) bodyClip.getLayoutParams();
        bodyCol.measure(MeasureSpec.makeMeasureSpec(h.px(wRem() - 6), MeasureSpec.EXACTLY), MeasureSpec.UNSPECIFIED);
        int max = h.H - h.px(20);
        bl.height = Math.min(bodyCol.getMeasuredHeight(), max);
        bodyClip.setLayoutParams(bl);
        FrameLayout.LayoutParams cl = (FrameLayout.LayoutParams) bodyCol.getLayoutParams();
        cl.height = bodyCol.getMeasuredHeight(); bodyCol.setLayoutParams(cl);
        btns.removeAllViews(); buttons.clear();
        JSONArray bs = o.optJSONArray("buttons");
        if (bs != null) for (int i = 0; i < bs.length(); i++) {
            TextView b = h.text(h.fSemi, Ui.TEXT_M, Color.WHITE);
            b.setText(bs.optString(i));
            b.setPadding(h.px(1.8f), h.px(.85f), h.px(1.8f), h.px(.85f));
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            if (i > 0) l.leftMargin = h.px(1);
            btns.addView(b, l);
            buttons.add(b);
        }
        sel = 0;
        mark();
    }

    /** a table in the message (the test's results): a header row and rows coloured by how they did */
    private void fillTable(JSONObject t) {
        table.removeAllViews();
        if (t == null) { table.setVisibility(GONE); return; }
        table.setVisibility(VISIBLE);
        JSONArray head = t.optJSONArray("head"), rows = t.optJSONArray("rows");
        if (head != null) table.addView(row(head, null, true));
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            table.addView(row(r.optJSONArray("cells"), r.optString("tone"), false));
        }
    }
    private View row(JSONArray cells, String tone, boolean head) {
        LinearLayout r = new LinearLayout(getContext());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, h.px(head ? .2f : .45f), 0, h.px(head ? .5f : .45f));
        int toneCol = "good".equals(tone) ? 0xFF30D158 : "ok".equals(tone) ? 0xFFFF9F0A : "slow".equals(tone) ? 0xFFFF453A : 0;
        if (!head) {
            View dot = new View(getContext());
            GradientDrawable d = new GradientDrawable(); d.setShape(GradientDrawable.OVAL); d.setColor(toneCol == 0 ? Ui.WHITE_20 : toneCol); dot.setBackground(d);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(h.px(.6f), h.px(.6f)); dl.rightMargin = h.px(.7f);
            r.addView(dot, dl);
        } else {
            View sp = new View(getContext());
            r.addView(sp, new LinearLayout.LayoutParams(h.px(1.3f), 1));
        }
        for (int i = 0; cells != null && i < cells.length(); i++) {
            TextView c = h.text(head ? h.fSemi : (i == 0 ? h.fMed : h.fReg), head ? .9f : 1f, head ? Ui.WHITE_60 : i == 0 ? Ui.WHITE_95 : Ui.WHITE_85);
            c.setText(cells.optString(i));
            c.setSingleLine(true); c.setEllipsize(android.text.TextUtils.TruncateAt.END);
            c.setGravity(i == 0 ? Gravity.START : Gravity.END);
            if (!head && i == 1 && toneCol != 0) c.setTextColor(toneCol);
            r.addView(c, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, i == 0 ? 3.2f : 1f));
        }
        if (!head) {
            GradientDrawable line = new GradientDrawable(); line.setColor(0x0FFFFFFF);
            r.setBackground(null);
        }
        return r;
    }

    private void mark() {
        for (int i = 0; i < buttons.size(); i++) {
            TextView b = buttons.get(i);
            boolean on = i == sel;
            b.setBackground(GlassPanel.buttonBg(on, 2 * R, Ui.WHITE_12));
            b.setTextColor(GlassPanel.buttonText(on));
            float s = on && !GlassPanel.look.equals("air") ? 1.05f : 1f;
            b.animate().scaleX(s).scaleY(s).setDuration(Ui.FAST).setInterpolator(TileView.EASE).start();
        }
    }

    void show() {
        shown = true;
        setVisibility(VISIBLE);
        scrim.setAlpha(0f); scrim.animate().alpha(1f).setDuration(Ui.BASE).start();
        panel.setAlpha(0f); panel.setScaleX(.94f); panel.setScaleY(.94f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Ui.MOVE).setInterpolator(TileView.SPRING).start();
        panel.refreshTint();
    }
    void hide() {
        if (!shown) return;
        shown = false;
        scrim.animate().alpha(0f).setDuration(Ui.FAST).start();
        panel.animate().alpha(0f).scaleX(.96f).scaleY(.96f).setDuration(Ui.FAST)
                .withEndAction(new Runnable() { @Override public void run() { if (!shown) setVisibility(GONE); } }).start();
    }

    void key(char d) {
        if (d == 'l' && sel > 0) { sel--; mark(); }
        else if (d == 'r' && sel < buttons.size() - 1) { sel++; mark(); }
        else if (d == 'u' || d == 'd') {
            float max = Math.max(0, bodyCol.getHeight() - bodyClip.getHeight());
            float to = Math.max(-max, Math.min(0, bodyCol.getTranslationY() + (d == 'u' ? 1 : -1) * 6 * R));
            bodyCol.animate().translationY(to).setDuration(Ui.FAST).setInterpolator(TileView.EASE).start();
        }
    }
    /** a finger (phones): a button tapped is chosen (OK then presses it) */
    boolean focusAt(float x, float y) {
        for (int i = 0; i < buttons.size(); i++) if (h.hit(buttons.get(i), x, y, .4f)) { sel = i; mark(); return true; }
        return false;
    }
    void ok() { h.page("window.nhDlgPick && nhDlgPick(" + sel + ")"); }
    void back() { h.page("window.nhDlgPick && nhDlgPick(-1)"); }
}
