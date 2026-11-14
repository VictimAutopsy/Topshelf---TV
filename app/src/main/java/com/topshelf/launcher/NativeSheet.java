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
 * Settings, drawn by Android. On a TV it's a full screen (Chad's design, 1.0.394, the panel as a menu 1.0.410):
 *   - the sections down the left with their icons, names and a line about each, the chosen one's settings beside
 *     them; ▶ or OK goes into the settings, ◀ or Back comes out;
 *   - OK on a setting with several choices: the sections shrink to a strip of icons, the settings move over and a
 *     panel pops out on the right with what the setting does and its choices (▲▼, OK chooses, Back closes it).
 *     Nothing is changed with ◀ ▶ or in place (he didn't want to step through choices): two choices get the
 *     panel too.
 * On Air it's edge to edge and solid; the other looks keep their own panel around the same layout.
 * On a narrow screen it's a glass sheet on the right.
 *
 * Earlier: a glass sheet on the right with a list of rows (a setting and its value,
 * ‹ › to change it, or a section to open). What the rows are and what each one does stays with the
 * page (nhSheet hands the rows over); moving up and down happens here, and changing a value, opening a
 * section or going back asks the page, which hands back the new rows.
 */
final class NativeSheet extends FrameLayout {
    private static final float SHEET_W = 42, RAD = 3, ROWS_TOP = 9.4f, PANE_TOP = 11.4f, FOOT_H = 4.6f, LEFT_W = 32.75f, RAIL_W = 8.25f;

    /* the full screen's left column: the sections */
    private final boolean pane;
    private LinearLayout left;
    private final List<LinearLayout> secViews = new ArrayList<>();
    private int leftSel, curSec = -1;
    private boolean focusLeft = true;
    private String curSub = "";
    private long askedAt;
    /* the two steps' measures, and the detail panel */
    private final boolean air;
    private int pw, ph, lwFull, lwRail, midW, railNow = -1;
    private View leftBg, div, dDiv;
    private TextView setTitle, about, dTitle, dAbout, dNote;
    private LinearLayout head, detail, dOpts;
    private FrameLayout dPreview;
    private String detailSig = "";
    /* the panel of choices: open for row chRow, on its choice chSel */
    private boolean choosing;
    private int chRow = -1, chSel, chCur = -1;
    private String chLabel = "", chAbout = "", chRowLabel = "";
    private JSONArray chOpts = new JSONArray();

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
        air = GlassPanel.look.equals("air");
        final boolean full = pane && air;                      // On Air: edge to edge, and solid (the screen behind showed through)
        int inset = full ? 0 : h.px(2);
        pw = pane ? h.W - 2 * inset : h.px(Math.min(SHEET_W, h.SW - 4)); ph = h.H - 2 * inset;
        lwFull = pane ? h.px(LEFT_W) : 0; lwRail = pane ? h.px(RAIL_W) : 0;
        midW = (pw - lwRail) / 2;
        int lw = lwFull, top = h.px(pane ? PANE_TOP : ROWS_TOP);
        LayoutParams pl = new LayoutParams(pw, ph);
        pl.leftMargin = h.W - pw - inset; pl.topMargin = inset;
        addView(panel, pl);
        if (full) { View solid = new View(c); solid.setBackgroundColor(0xFF0B0B0D); panel.addView(solid, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)); }
        if (pane) {
            leftBg = new View(c); if (air) leftBg.setBackgroundColor(0xFF101013);
            panel.addView(leftBg, new LayoutParams(lw, ph));
            div = new View(c); div.setBackgroundColor(air ? 0xFF1F1F25 : Ui.WHITE_12);
            LayoutParams dl = new LayoutParams(Math.max(1, h.px(.1f)), air ? ph : ph - h.px(6)); dl.leftMargin = lw; dl.topMargin = air ? 0 : h.px(3);
            panel.addView(div, dl);
            setTitle = h.text(air ? SportsLook.heavy() : h.fSemi, air ? 3.2f : 2.6f, Color.WHITE); setTitle.setText("Settings");
            if (air) { setTitle.setAllCaps(true); setTitle.setLetterSpacing(.04f); } else setTitle.setLetterSpacing(-.02f);
            LayoutParams stl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); stl.leftMargin = h.px(3.75f); stl.topMargin = h.px(4f);
            panel.addView(setTitle, stl);
            left = new LinearLayout(c); left.setOrientation(LinearLayout.VERTICAL);
            LayoutParams ll = new LayoutParams(lw, LayoutParams.WRAP_CONTENT); ll.topMargin = h.px(10.4f);
            panel.addView(left, ll);
        }

        head = new LinearLayout(c);
        head.setOrientation(LinearLayout.VERTICAL);
        back = h.text(h.fMed, Ui.TEXT_M, Ui.WHITE_60);
        head.addView(back);
        title = h.text(pane && air ? SportsLook.heavy() : h.fSemi, pane && air ? 3.6f : 2.6f, Color.WHITE);
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        if (pane && air) { title.setAllCaps(true); title.setLetterSpacing(.03f); } else title.setLetterSpacing(-.02f);       // (after "one line", which undoes capitals)
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); tl.topMargin = h.px(.4f);
        head.addView(title, tl);
        about = h.text(h.fReg, Ui.TEXT_XL, Ui.WHITE_60); about.setSingleLine(true); about.setEllipsize(TextUtils.TruncateAt.END);
        about.setVisibility(GONE);
        head.addView(about);
        LayoutParams hl = new LayoutParams(pw - lw - h.px(5.2f), LayoutParams.WRAP_CONTENT);
        hl.leftMargin = lw + h.px(4); hl.topMargin = h.px(pane ? 3.4f : 3.2f);
        panel.addView(head, hl);

        clip = new FrameLayout(c);
        clip.setClipChildren(true);
        rows = new LinearLayout(c);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(h.px(1), h.px(.4f), h.px(1), h.px(.4f));
        rows.setClipToPadding(false);
        clip.addView(rows, new LayoutParams(LayoutParams.MATCH_PARENT, h.px(400)));
        LayoutParams cl = new LayoutParams(pw - lw - h.px(3.2f), ph - top - h.px(FOOT_H));
        cl.leftMargin = lw + h.px(1.6f); cl.topMargin = top;
        panel.addView(clip, cl);

        foot = h.text(h.fReg, Ui.TEXT_S, Ui.WHITE_60);
        foot.setLineSpacing(0, 1.3f); foot.setMaxLines(2); foot.setEllipsize(TextUtils.TruncateAt.END);
        LayoutParams fl = new LayoutParams(pw - lw - h.px(7), LayoutParams.WRAP_CONTENT);
        fl.leftMargin = lw + h.px(4); fl.topMargin = ph - h.px(FOOT_H) + h.px(1.2f);
        panel.addView(foot, fl);

        if (pane) {
            // the detail panel (step 2): what the setting you're on does, and its choices
            int dx = lwRail + midW;
            detail = new LinearLayout(c); detail.setOrientation(LinearLayout.VERTICAL);
            detail.setPadding(h.px(5f), h.px(4.4f), h.px(5.5f), h.px(2f));
            if (air) detail.setBackgroundColor(0xFF101013);        // (the other looks' panels have round corners: no plate to poke out of them)
            LayoutParams dtl = new LayoutParams(pw - dx, ph); dtl.leftMargin = dx;
            panel.addView(detail, dtl);
            dDiv = new View(c); dDiv.setBackgroundColor(air ? 0xFF1F1F25 : Ui.WHITE_12);
            LayoutParams ddl = new LayoutParams(Math.max(1, h.px(.1f)), ph); ddl.leftMargin = dx;
            panel.addView(dDiv, ddl);
            dTitle = h.text(air ? SportsLook.heavy() : h.fSemi, air ? 2.75f : 2.2f, Color.WHITE);
            if (air) { dTitle.setAllCaps(true); dTitle.setLetterSpacing(.03f); }
            dTitle.setMaxLines(2);
            detail.addView(dTitle);
            dAbout = h.text(h.fReg, Ui.TEXT_XL, Ui.WHITE_75); dAbout.setLineSpacing(0, 1.4f);
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); al.topMargin = h.px(.9f);
            detail.addView(dAbout, al);
            dPreview = new FrameLayout(c); dPreview.setVisibility(GONE);
            LinearLayout.LayoutParams pvl = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, h.px(13f)); pvl.topMargin = h.px(2.2f);
            detail.addView(dPreview, pvl);
            dOpts = new LinearLayout(c); dOpts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); ol.topMargin = h.px(2.2f);
            detail.addView(dOpts, ol);
            dNote = h.text(h.fReg, Ui.TEXT_L, Ui.WHITE_50); dNote.setLineSpacing(0, 1.4f);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); nl.topMargin = h.px(1.8f);
            detail.addView(dNote, nl);
            applyPane(false);
        }

        setClipChildren(false);
        if (pane) { panel.setAlpha(0f); panel.setTranslationX(h.px(3)); }
        else panel.setTranslationX(pw + h.px(4));
        setVisibility(GONE);
    }

    // ================================================================== the sections (full screen)

    /** a row's place in its card: plain fill, and a hairline above it unless it's the card's first */
    private static final class RowBg extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint p = new android.graphics.Paint();
        private final int fill, line; private final float lineH;
        RowBg(int fill, int line, float lineH) { this.fill = fill; this.line = line; this.lineH = lineH; }
        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            if (fill != 0) { p.setColor(fill); c.drawRect(b, p); }
            if (line != 0) { p.setColor(line); c.drawRect(b.left, b.top, b.right, b.top + lineH, p); }
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
    private boolean firstInCard(int i) {
        if (i <= 0) return true;
        JSONObject d = data.optJSONObject(i), prev = data.optJSONObject(i - 1);
        return prev == null || prev.optBoolean("head") || (d != null && d.optBoolean("gap"));
    }

    /** which of the two steps is showing: the sections in full (you're choosing one), or the strip of icons with
        the detail panel (you're inside one). Sets every part's place for that step. */
    private void applyPane(boolean animate) {
        if (!pane) return;
        final boolean rail = choosing;
        if (railNow == (rail ? 1 : 0)) { styleLeft(); drawDetail(); return; }
        boolean first = railNow < 0;
        railNow = rail ? 1 : 0;
        int lw = rail ? lwRail : lwFull;
        int padL = h.px(rail ? 4.4f : 6f), padR = h.px(rail ? 3.4f : 7f), areaW = rail ? midW : pw - lw;
        LayoutParams cl = (LayoutParams) clip.getLayoutParams();
        float from = cl.leftMargin;
        cl.leftMargin = lw + padL - h.px(1); cl.width = areaW - padL - padR + h.px(2);
        clip.setLayoutParams(cl);
        LayoutParams hl = (LayoutParams) head.getLayoutParams(); hl.leftMargin = lw + padL; hl.width = areaW - padL - padR; head.setLayoutParams(hl);
        LayoutParams fl = (LayoutParams) foot.getLayoutParams(); fl.leftMargin = lw + padL; fl.width = areaW - padL - padR; foot.setLayoutParams(fl);
        LayoutParams bl = (LayoutParams) leftBg.getLayoutParams(); bl.width = lw; leftBg.setLayoutParams(bl);
        LayoutParams dl = (LayoutParams) div.getLayoutParams(); dl.leftMargin = lw; div.setLayoutParams(dl);
        LayoutParams ll = (LayoutParams) left.getLayoutParams(); ll.width = lw; left.setLayoutParams(ll);
        setTitle.setVisibility(rail ? INVISIBLE : VISIBLE);
        for (int i = 0; i < secViews.size(); i++) shapeSection(secViews.get(i), rail);
        // (the rows are as wide as their column: made again so a value's room is right for it)
        if (data.length() > 0) { rebuild(); for (int i = 0; i < rowViews.size(); i++) fill(i); fitHeight(); style(false); }
        styleLeft();
        detailSig = ""; drawDetail();
        View[] slide = { clip, head, foot };
        for (View v : slide) { v.animate().cancel(); v.setTranslationX(0); }
        detail.animate().cancel(); dDiv.animate().cancel();
        if (animate && !first && shown) {
            float dx = from - cl.leftMargin;
            for (View v : slide) { v.setTranslationX(dx); v.animate().translationX(0).setDuration(Ui.MOVE).setInterpolator(TileView.EASE).start(); }
            if (rail) {
                detail.setVisibility(VISIBLE); dDiv.setVisibility(VISIBLE);
                detail.setAlpha(0f); detail.setTranslationX(h.px(4));
                detail.animate().alpha(1f).translationX(0).setDuration(Ui.MOVE).setInterpolator(TileView.EASE).start();
            } else { detail.setVisibility(GONE); dDiv.setVisibility(GONE); }
        } else {
            detail.setAlpha(1f); detail.setTranslationX(0);
            detail.setVisibility(rail ? VISIBLE : GONE); dDiv.setVisibility(rail ? VISIBLE : GONE);
        }
    }

    /** the page hands over a setting's choices (OK was pressed on it): the panel pops out with them */
    void choices(JSONObject o) {
        if (!pane || o == null) return;
        JSONArray opts = o.optJSONArray("opts");
        if (opts == null || opts.length() == 0) return;
        // (a setting that's only typed into has the one "choice": straight to typing)
        if (opts.length() == 1 && opts.optJSONObject(0).optInt("steps") == -2) { int row = o.optInt("i", sel); if (row < data.length() && data.optJSONObject(row).optJSONObject("edit") != null) { sel = row; startEdit(row); } return; }
        chRow = o.optInt("i", sel); chLabel = o.optString("label"); chAbout = o.optString("about");
        chRowLabel = chRow < data.length() ? data.optJSONObject(chRow).optString("label") : "";
        chOpts = opts; chCur = o.optInt("cur", -1); chSel = Math.max(0, chCur);
        choosing = true; focusLeft = false;
        applyPane(true);
    }
    private void closeChooser(boolean animate) {
        if (!choosing) return;
        choosing = false;
        applyPane(animate);
    }
    /** the panel: what the setting does, then its choices; the one that's set has its mark filled */
    private void drawDetail() {
        if (!pane || detail == null || !choosing) return;
        String sg = chLabel + '|' + chAbout + '|' + chOpts.toString() + '|' + chCur + '|' + chSel;
        if (sg.equals(detailSig)) return;
        detailSig = sg;
        dTitle.setText(chLabel);
        dAbout.setText(chAbout); dAbout.setVisibility(chAbout.isEmpty() ? GONE : VISIBLE);
        dNote.setText("OK to choose  ·  Back to close");
        dPreview.setVisibility(GONE);
        dOpts.removeAllViews();
        int n = chOpts.length();
        // (a long list shows the part around the one you're on)
        int max = 9, from = n <= max ? 0 : Math.max(0, Math.min(chSel - max / 2, n - max)), to = Math.min(n, from + max);
        int line = air ? 0xFF1F1F25 : Ui.WHITE_12, st = GlassPanel.selText();
        if (from > 0) dOpts.addView(moreLine("▲  " + from + " more"));
        for (int i = from; i < to; i++) {
            JSONObject o = chOpts.optJSONObject(i); if (o == null) continue;
            boolean on = i == chSel, set = i == chCur;
            LinearLayout row = new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(h.px(4.5f));
            row.setPadding(h.px(1.4f), 0, h.px(air ? 2.2f : 1.4f), 0);
            if (!on) row.setBackground(new RowBg(0, line, Math.max(1, h.px(.1f))));
            else if (air) row.setBackground(new GlassPanel.Cut(GlassPanel.selFill()));
            else { GradientDrawable g = new GradientDrawable(); g.setCornerRadius(GlassPanel.selRadius(1.5f * R)); g.setColor(GlassPanel.selFill()); row.setBackground(g); }
            View mark = new View(getContext());
            GradientDrawable mg = new GradientDrawable(); mg.setCornerRadius(GlassPanel.corner(.7f * R));
            if (set) mg.setColor(on ? st : GlassPanel.selFill()); else mg.setStroke(Math.max(1, h.px(.16f)), on ? st : Ui.WHITE_50);
            mark.setBackground(mg);
            row.addView(mark, new LinearLayout.LayoutParams(h.px(1.4f), h.px(1.4f)));
            TextView nm = h.text(on ? h.fSemi : h.fMed, Ui.TEXT_XL, on ? st : set ? Color.WHITE : Ui.WHITE_85); nm.setSingleLine(true); nm.setEllipsize(TextUtils.TruncateAt.END);
            nm.setText(o.optString("l"));
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1); nl.leftMargin = h.px(1.4f);
            row.addView(nm, nl);
            String note = o.optString("n");
            if (!note.isEmpty()) {
                TextView nt = h.text(h.fReg, Ui.TEXT_M, on ? (st & 0x00FFFFFF) | 0xD9000000 : Ui.WHITE_50); nt.setSingleLine(true); nt.setText(note);
                LinearLayout.LayoutParams ntl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); ntl.leftMargin = h.px(1.2f);
                row.addView(nt, ntl);
            }
            dOpts.addView(row, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
        if (to < n) dOpts.addView(moreLine("▼  " + (n - to) + " more"));
    }
    private TextView moreLine(String t) {
        TextView more = h.text(h.fReg, Ui.TEXT_M, Ui.WHITE_50); more.setText(t);
        more.setPadding(h.px(1.4f), h.px(.7f), 0, h.px(.7f));
        return more;
    }

    private static int iconFor(String name) {
        String n = name.toLowerCase(java.util.Locale.US);
        return n.startsWith("home") ? HomeIcons.HOME : n.startsWith("movies") ? HomeIcons.FILM : n.startsWith("live") ? HomeIcons.TV
                : n.startsWith("sports") ? HomeIcons.TROPHY : n.startsWith("screen") ? HomeIcons.MOON : n.startsWith("remote") ? HomeIcons.REMOTE : HomeIcons.SETTINGS;
    }
    /** a section's line: its icon, name and what's in it; just its icon in the strip */
    private void shapeSection(LinearLayout v, boolean rail) {
        v.getChildAt(1).setVisibility(rail ? GONE : VISIBLE);
        v.setGravity(rail ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
        v.setPadding(rail ? 0 : h.px(3.75f), 0, rail ? 0 : h.px(2.6f), 0);
    }
    private void buildLeft(JSONArray secs) {
        if (left == null || secs == null) return;
        if (secViews.size() != secs.length()) {
            left.removeAllViews(); secViews.clear();
            for (int i = 0; i < secs.length(); i++) {
                JSONObject x = secs.optJSONObject(i);
                LinearLayout v = new LinearLayout(getContext());
                v.setMinimumHeight(h.px(6.5f));
                View ic = new View(getContext()); ic.setBackground(new HomeIcons(iconFor(x == null ? "" : x.optString("name")), 1.8f));
                v.addView(ic, new LinearLayout.LayoutParams(h.px(2.5f), h.px(2.5f)));
                LinearLayout tx = new LinearLayout(getContext()); tx.setOrientation(LinearLayout.VERTICAL);
                TextView n = h.text(air ? SportsLook.cond() : h.fSemi, air ? 1.9f : 1.5f, Color.WHITE); n.setSingleLine(true); n.setEllipsize(TextUtils.TruncateAt.END);
                if (air) { n.setAllCaps(true); n.setLetterSpacing(.05f); }
                TextView a = h.text(h.fReg, Ui.TEXT_M, Ui.WHITE_50); a.setSingleLine(true); a.setEllipsize(TextUtils.TruncateAt.END);
                tx.addView(n); LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); al.topMargin = h.px(.15f); tx.addView(a, al);
                LinearLayout.LayoutParams txl = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1); txl.leftMargin = h.px(1.6f);
                v.addView(tx, txl);
                shapeSection(v, !focusLeft);
                LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                if (!air) { vl.leftMargin = vl.rightMargin = h.px(1.1f); vl.bottomMargin = h.px(.3f); }       // (a rounded highlight keeps off the panel's edge)
                left.addView(v, vl);
                secViews.add(v);
            }
        }
        for (int i = 0; i < secs.length(); i++) {
            JSONObject x = secs.optJSONObject(i); if (x == null) continue;
            LinearLayout tx = (LinearLayout) secViews.get(i).getChildAt(1);
            ((TextView) tx.getChildAt(0)).setText(x.optString("name"));
            ((TextView) tx.getChildAt(1)).setText(x.optString("about"));
        }
        styleLeft();
    }
    private void styleLeft() {
        for (int i = 0; i < secViews.size(); i++) {
            LinearLayout v = secViews.get(i);
            boolean on = i == leftSel;
            int st = GlassPanel.selText();
            LinearLayout tx = (LinearLayout) v.getChildAt(1);
            HomeIcons ic = (HomeIcons) v.getChildAt(0).getBackground();
            if (!on) v.setBackground(null);
            else if (focusLeft) {
                // choosing a section: the look's highlight
                if (air) v.setBackground(new GlassPanel.Cut(GlassPanel.selFill()));
                else { GradientDrawable g = new GradientDrawable(); g.setCornerRadius(GlassPanel.selRadius(1.5f * R)); g.setColor(GlassPanel.selFill()); v.setBackground(g); }
            } else {
                // inside it: a quiet plate with the highlight's colour down its edge
                GradientDrawable plate = new GradientDrawable(); plate.setColor(air ? 0xFF17171B : Ui.WHITE_12);
                GradientDrawable edge = new GradientDrawable(); edge.setColor(GlassPanel.selFill());
                android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] { plate, edge });
                ld.setLayerWidth(1, h.px(.4f)); ld.setLayerGravity(1, Gravity.LEFT | Gravity.FILL_VERTICAL);
                v.setBackground(ld);
            }
            boolean hot = on && focusLeft;
            ic.setColor(hot ? st : on ? Color.WHITE : focusLeft ? Ui.WHITE_75 : Ui.WHITE_60);
            ((TextView) tx.getChildAt(0)).setTextColor(hot ? st : Color.WHITE);
            ((TextView) tx.getChildAt(1)).setTextColor(hot ? (st & 0x00FFFFFF) | 0xD9000000 : Ui.WHITE_50);
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
        applyPane(true);
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
            focusLeft = true; curSec = -1; curSub = ""; choosing = false;         // (opening again starts on the sections)
            applyPane(false);
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
            String ab = sub.isEmpty() ? o.optString("about") : "";
            about.setText(ab); about.setVisibility(ab.isEmpty() ? GONE : VISIBLE);
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
        // (the rows changed under an open panel of choices: it's closed)
        if (choosing && (chRow >= data.length() || !chRowLabel.equals(data.optJSONObject(chRow).optString("label")))) choosing = false;
        applyPane(shown);
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
                v.setPadding(h.px(pane ? .1f : 1.4f), h.px(i == 0 ? .3f : pane ? 2.2f : 1.9f), h.px(1.4f), h.px(pane ? .8f : .5f));
                v.setGravity(Gravity.CENTER_VERTICAL);
                if (pane) {
                    // (the same tick the rows of posters have beside their names)
                    View tick = new View(getContext()); tick.setBackgroundColor(GlassPanel.selFill());
                    LinearLayout.LayoutParams tkl = new LinearLayout.LayoutParams(h.px(.4f), h.px(1.3f)); tkl.rightMargin = h.px(.9f);
                    v.addView(tick, tkl);
                }
                TextView l = h.text(pane && air ? SportsLook.cond() : h.fSemi, pane ? Ui.TEXT_M : Ui.TEXT_S, pane ? Ui.WHITE_75 : Ui.WHITE_60);
                l.setLetterSpacing(pane ? .14f : .1f); l.setSingleLine(true); l.setAllCaps(true);          // (in this order: one line first, or it undoes the capitals)
                v.addView(l, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
                TextView val = h.text(h.fMed, Ui.TEXT_S, 0), ch = h.text(h.fMed, Ui.TEXT_S, 0);
                val.setVisibility(GONE); ch.setVisibility(GONE);
                rows.addView(v, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
                rowViews.add(new Row(v, l, val, ch));
                continue;
            }
            boolean sec = d.optBoolean("chev") && !pane;          // (on a TV a setting that opens a list looks like the others in its card)
            LinearLayout v = new LinearLayout(getContext());
            v.setGravity(Gravity.CENTER_VERTICAL);
            v.setPadding(h.px(pane ? 1.75f : 1.4f), h.px(sec ? 1.35f : 1f), h.px(pane ? 1.75f : 1.4f), h.px(sec ? 1.35f : 1f));
            v.setMinimumHeight(h.px(pane ? 3.9f : 3.6f));
            TextView l = h.text(sec ? h.fSemi : h.fMed, sec || pane ? 1.5f : 1.35f, Color.WHITE);
            l.setSingleLine(true); l.setEllipsize(TextUtils.TruncateAt.END);
            v.addView(l, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            TextView val = h.text(h.fMed, sec ? 1.05f : pane ? 1.5f : 1.35f, Ui.WHITE_60);
            val.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            /* a value may take up to 62% of its row (it was held to a narrow column made for the small sheet: on the
               full-screen Settings long values were cut off with half the row empty beside them) */
            int rowW = clip.getLayoutParams().width > 0 ? clip.getLayoutParams().width - h.px(2) : h.px(34);
            int valMax = Math.max(h.px(20), Math.round(rowW * .62f));
            if (sec) { val.setMaxLines(2); val.setMaxWidth(valMax); val.setLineSpacing(0, 1.15f); }
            else { val.setSingleLine(true); val.setEllipsize(TextUtils.TruncateAt.END); val.setMaxWidth(valMax); }
            LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); vl.leftMargin = h.px(1.5f);
            v.addView(val, vl);
            TextView ch = h.text(h.fSemi, 1.6f, Ui.WHITE_60);
            ch.setText("›");
            ch.setVisibility(d.optBoolean("chev") ? VISIBLE : GONE);
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
        if (!pane && r.d.optBoolean("cycle") && !v.isEmpty() && cur) v = "‹  " + v + "  ›";       // (on a TV, OK opens its choices)
        if (!v.contentEquals(r.value.getText())) r.value.setText(v);
    }

    private int styled = -1;
    private void style(boolean animate) {
        for (int i = 0; i < rowViews.size(); i++) styleRow(i, animate);
        styled = sel;
        reveal(animate);
        drawDetail();
    }
    /** moving: only the row you left and the one you're on change */
    private void restyle() {
        if (styled >= 0 && styled < rowViews.size()) styleRow(styled, true);
        styleRow(sel, true);
        styled = sel;
        reveal(true);
        drawDetail();
    }
    private void styleRow(int i, boolean animate) {
        {
            Row r = rowViews.get(i);
            if (data.optJSONObject(i) != null && data.optJSONObject(i).optBoolean("head")) { fill(i); return; }
            boolean on = i == sel && !(pane && focusLeft);
            int st = GlassPanel.selText(), dim = (st & 0x00FFFFFF) | Ui.BLACK_60;
            if (on && GlassPanel.look.equals("air")) r.v.setBackground(new GlassPanel.Cut(GlassPanel.selFill()));
            else if (on) { GradientDrawable g = new GradientDrawable(); g.setCornerRadius(GlassPanel.selRadius(1.5f * R)); g.setColor(GlassPanel.selFill()); r.v.setBackground(g); }
            else if (pane) r.v.setBackground(new RowBg(air ? 0xFF141418 : Ui.WHITE_7, firstInCard(i) ? 0 : air ? 0xFF1F1F25 : Ui.WHITE_7, Math.max(1, h.px(.1f))));
            else r.v.setBackground(null);
            r.label.setTextColor(on ? st : Color.WHITE);
            r.value.setTextColor(on ? dim : Ui.WHITE_60);
            r.chev.setTextColor(on ? dim : Ui.WHITE_60);
            float s = on && !pane ? 1.02f : 1f;          // (in its card the chosen row keeps its size)
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
        if (choosing) {
            // in the panel of choices: ▲▼ move, ◀ closes it
            int n = chOpts.length();
            if (k == 'u' && chSel > 0) { chSel--; drawDetail(); }
            else if (k == 'd' && chSel < n - 1) { chSel++; drawDetail(); }
            else if (k == 'l') closeChooser(true);
            return true;
        }
        if (pane && k == 'l') { if (curSub.isEmpty()) setFocusLeft(true); return true; }
        if (pane && k == 'r') return true;
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
            leftSel = i; focusLeft = true; choosing = false; applyPane(true); style(true); askSoon(0); return 0;
        }
        if (pane && focusLeft) { focusLeft = false; applyPane(true); }
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
        if (choosing) {
            // the choice you're on: the page sets it (-2 is typing, which is this screen's)
            JSONObject o = chOpts.optJSONObject(chSel);
            int row = chRow, steps = o == null ? 0 : o.optInt("steps", 0);
            closeChooser(true);
            if (steps == -2) { if (row < data.length() && data.optJSONObject(row).optJSONObject("edit") != null) { sel = row; startEdit(row); } }
            else h.page("window.nhSheetPick && nhSheetPick(" + row + "," + chSel + ")");
            return;
        }
        if (pane && r.optBoolean("cycle")) { h.page("window.nhSheetChoices && nhSheetChoices(" + sel + ")"); return; }
        if (r.optJSONObject("edit") != null) { startEdit(sel); return; }
        View v = rowViews.get(sel).v;
        v.animate().scaleX(.99f).scaleY(.99f).setDuration(80).withEndAction(new Runnable() { @Override public void run() {
            float up = pane ? 1f : 1.02f;
            rowViews.get(Math.min(sel, rowViews.size() - 1)).v.animate().scaleX(up).scaleY(up).setDuration(Ui.QUICK).start();
        } }).start();
        h.page("window.nhSheetKey && nhSheetKey(" + sel + ",'ok')");
    }
    void back() {
        if (editing >= 0) { endEdit(false); return; }
        if (pane) {
            if (choosing) closeChooser(true);
            else if (focusLeft) h.page("closeSettings()");
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
