package com.topshelf.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Search and the sports & teams picker, drawn by Android: a big glass pane with a text box at the top
 * and rows of results underneath (apps, posters, channels, teams…), each row sliding sideways. What's
 * found and what OK does stay with the page (nhFind hands the rows over); moving happens here.
 */
final class NativeFind extends FrameLayout {
    private static final float INSET = 0, HEAD_Y = 3.2f, HEAD_H = 5, BODY_Y = 9.6f, PADX = 4.6f, GAP = 1.3f;

    private final NativeHome h;
    private final float R;
    final FrameLayout panel;
    private final FrameLayout head;
    private final GradientDrawable headBg;
    private final EditText input;
    private final TextView btn;            // Done (picker)
    private final ImageView mic;           // voice (search)
    private final FrameLayout body;
    private final LinearLayout list;
    private final TextView big;
    private final int pw, ph;

    private String kind = "";
    private boolean noBox;
    private boolean shown, typing, quiet;
    private int r = -1;                    // -2 the button at the right of the box, -1 the box, 0… a row
    private final Map<Integer, Integer> cols = new HashMap<>();
    private final List<Sec> secs = new ArrayList<>();
    private final List<Sec> rows = new ArrayList<>();     // sections that have items (the remote's rows)
    private View focusedItem;

    private static final class Sec {
        int ri; String kind; JSONArray items; FrameLayout strip; LinearLayout in; View view; List<View> cards = new ArrayList<>(); float scroll;
    }

    NativeFind(NativeHome host) {
        super(host.getContext());
        h = host; R = host.R;
        Context c = getContext();
        /* A whole screen of its own, painted opaque: nothing behind it has to be blended through it,
           so the graphics chip only draws what's on it (a see-through pane this big was slow). */
        panel = new FrameLayout(c);
        ImageView back = new ImageView(c);
        back.setScaleType(ImageView.ScaleType.FIT_XY);
        back.setImageBitmap(backdrop(h.W, h.H));
        panel.addView(back, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        pw = h.W - h.px(2 * INSET); ph = h.H - h.px(2 * INSET);
        LayoutParams pl = new LayoutParams(pw, ph); pl.leftMargin = h.px(INSET); pl.topMargin = h.px(INSET);
        addView(panel, pl);

        // the box
        head = new FrameLayout(c);
        headBg = new GradientDrawable(); headBg.setCornerRadius(2.5f * R); headBg.setColor(Ui.WHITE_12);
        head.setBackground(headBg);
        ImageView mag = new ImageView(c);
        HomeIcons mi = new HomeIcons(HomeIcons.SEARCH, 2f); mi.setColor(Ui.WHITE_60);
        mag.setImageDrawable(mi);
        LayoutParams ml = new LayoutParams(h.px(2), h.px(2), Gravity.CENTER_VERTICAL); ml.leftMargin = h.px(1.6f);
        head.addView(mag, ml);
        input = new EditText(c);
        input.setBackground(null);
        input.setTypeface(h.fMed);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 1.6f * R);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0x73FFFFFF);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        input.setPadding(0, 0, 0, 0);
        input.setFocusable(false);
        LayoutParams il = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        il.leftMargin = h.px(4.6f); il.rightMargin = h.px(10);
        head.addView(input, il);
        mic = new ImageView(c);
        HomeIcons mc = new HomeIcons(HomeIcons.MIC, 2f); mc.setColor(Ui.WHITE_85);
        mic.setImageDrawable(mc);
        mic.setPadding(h.px(.85f), h.px(.85f), h.px(.85f), h.px(.85f));
        LayoutParams mcl = new LayoutParams(h.px(3.6f), h.px(3.6f), Gravity.CENTER_VERTICAL | Gravity.END); mcl.rightMargin = h.px(.7f);
        head.addView(mic, mcl);
        btn = h.text(h.fSemi, Ui.TEXT_M, 0xFF111111);
        btn.setText("Done");
        btn.setPadding(h.px(1.8f), h.px(.8f), h.px(1.8f), h.px(.8f));
        LayoutParams bl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.END); bl.rightMargin = h.px(.7f);
        head.addView(btn, bl);
        LayoutParams hl = new LayoutParams(pw - h.px(4.8f), h.px(HEAD_H)); hl.leftMargin = h.px(2.4f); hl.topMargin = h.px(HEAD_Y);
        panel.addView(head, hl);

        // the rows
        body = new FrameLayout(c);
        body.setClipChildren(true);
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        body.addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, h.px(400)));
        big = h.text(h.fMed, Ui.TEXT_L, Ui.WHITE_60);
        big.setGravity(Gravity.CENTER);
        LayoutParams gl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); gl.topMargin = h.px(4);
        body.addView(big, gl);
        LayoutParams yl = new LayoutParams(pw, ph - h.px(BODY_Y)); yl.topMargin = h.px(BODY_Y);
        panel.addView(body, yl);

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable s) {
                if (quiet) return;
                h.main.removeCallbacks(sendQ);
                h.main.postDelayed(sendQ, 300);
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int action, KeyEvent ev) {
                if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_ACTION_GO
                        || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_UP)) { submit(); return true; }
                return false;
            }
        });

        panel.setAlpha(0f);
        panel.setTranslationY(0);
        setVisibility(GONE);
    }

    /** Search is the app's own (NativeSearch); the sports picker and the icon picker are still the page's */
    private boolean nat() { return kind.equals("search") && h.nativeSearchOn(); }
    private final Runnable sendQ = new Runnable() { @Override public void run() {
        if (nat()) h.search.query(input.getText().toString(), false, false);
        else h.page("window.nhFindQ && nhFindQ(" + JSONObject.quote(input.getText().toString()) + ",false)");
    } };

    boolean shownNow() { return shown; }
    boolean typingNow() { return typing; }
    String kindNow() { return kind; }

    void show() {
        if (shown) return;
        shown = true;
        setVisibility(VISIBLE);
        panel.animate().cancel();
        panel.animate().alpha(1f).translationY(0).setDuration(Ui.MOVE).setInterpolator(TileView.EASE).start();
        h.updateBars();
    }
    void hide() {
        if (!shown) return;
        shown = false;
        stopTyping(false);
        h.updateBars();
        panel.animate().cancel();
        panel.animate().alpha(0f).setDuration(Ui.FAST).setInterpolator(TileView.EASE)
                .withEndAction(new Runnable() { @Override public void run() { if (!shown) setVisibility(GONE); } }).start();
    }

    // ================================================================== data from the page

    void setData(JSONObject o, boolean first) {
        String k = o.optString("kind");
        boolean newKind = !k.equals(kind);
        kind = k;
        boolean search = kind.equals("search");
        boolean box = !o.optBoolean("nobox");
        head.setVisibility(box ? VISIBLE : GONE);
        ((LayoutParams) body.getLayoutParams()).topMargin = h.px(box ? BODY_Y : 2.4f);
        body.requestLayout();
        noBox = !box;
        mic.setVisibility(search ? VISIBLE : GONE);
        btn.setVisibility(search ? GONE : VISIBLE);
        input.setHint(o.optString("ph"));
        String q = o.optString("q");
        if (!typing && !q.equals(input.getText().toString())) { quiet = true; input.setText(q); input.setSelection(input.length()); quiet = false; }
        big.setText(o.optString("big"));
        big.setVisibility(o.optString("big").isEmpty() ? GONE : VISIBLE);

        // keep your place: the same kind of row, the same column
        String curKind = r >= 0 && r < rows.size() ? rows.get(r).kind : null;
        Map<String, Float> oldScroll = new HashMap<>();
        Map<String, Integer> oldCol = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) { oldScroll.put(rows.get(i).kind, rows.get(i).scroll); oldCol.put(rows.get(i).kind, col(i)); }
        build(o.optJSONArray("sections"));
        cols.clear();
        for (int i = 0; i < rows.size(); i++) {
            Sec s = rows.get(i);
            if (oldCol.containsKey(s.kind) && !newKind) { cols.put(i, Math.min(oldCol.get(s.kind), s.items.length() - 1)); s.scroll = oldScroll.get(s.kind); s.in.setTranslationX(-s.scroll); }
        }
        if (first || newKind) r = o.optInt("start", -1);
        if (o.has("col") && !rows.isEmpty()) { cols.put(0, Math.min(o.optInt("col"), rows.get(0).items.length() - 1)); if (first || newKind) r = 0; }
        else if (r >= 0) {
            int ni = -1;
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).kind.equals(curKind)) ni = i;
            r = ni >= 0 ? ni : Math.min(r, rows.size() - 1);
        }
        if (r >= rows.size()) r = rows.size() - 1;
        mark(false);
    }

    private void build(JSONArray ss) {
        list.removeAllViews();
        secs.clear(); rows.clear(); ringOf.clear(); ringRad.clear();
        focusedItem = null;
        if (ss == null) return;
        Context c = getContext();
        for (int i = 0; i < ss.length(); i++) {
            JSONObject so = ss.optJSONObject(i);
            Sec s = new Sec();
            s.ri = so.optInt("ri", -1); s.kind = so.optString("kind"); s.items = so.optJSONArray("items") == null ? new JSONArray() : so.optJSONArray("items");
            LinearLayout v = new LinearLayout(c);
            v.setOrientation(LinearLayout.VERTICAL);
            v.setClipChildren(false); v.setClipToPadding(false);
            TextView t = h.text(h.fSemi, Ui.TEXT_L, Ui.WHITE_95);
            t.setText(so.optString("title"));
            t.setPadding(h.px(PADX), 0, 0, 0);
            v.addView(t);
            if (s.items.length() == 0) {
                TextView n = h.text(h.fReg, Ui.TEXT_M, Ui.WHITE_60);
                n.setText(so.optString("note"));
                n.setPadding(h.px(PADX), h.px(.8f), h.px(PADX), 0);
                if (!so.optString("note").isEmpty()) v.addView(n);
            } else {
                s.strip = new FrameLayout(c);
                s.strip.setClipChildren(false);
                s.in = new LinearLayout(c);
                s.in.setClipChildren(false);
                s.in.setClipToPadding(false);      // (the item you're on grows past the row's padding)
                s.in.setPadding(h.px(PADX), h.px(1), h.px(PADX), h.px(1.4f));
                for (int j = 0; j < s.items.length(); j++) {
                    View card = item(s.items.optJSONObject(j));
                    LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                    if (j > 0) cl.leftMargin = h.px(GAP);
                    s.in.addView(card, cl);
                    s.cards.add(card);
                }
                s.strip.addView(s.in, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
                v.addView(s.strip, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
                rows.add(s);
            }
            LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); vl.topMargin = h.px(1.2f);
            list.addView(v, vl);
            s.view = v;
            secs.add(s);
        }
        list.measure(MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY), MeasureSpec.UNSPECIFIED);
        LayoutParams lp = (LayoutParams) list.getLayoutParams();
        lp.height = Math.max(list.getMeasuredHeight() + h.px(3), h.px(10));
        list.setLayoutParams(lp);
    }

    private int col(int row) { Integer c = cols.get(row); return c == null ? 0 : c; }

    // ================================================================== one result

    /** the part of an item that gets the focus ring */
    private final Map<View, View> ringOf = new HashMap<>();
    private final Map<View, Float> ringRad = new HashMap<>();

    private View item(JSONObject it) {
        String t = it.optString("t");
        Context c = getContext();
        boolean on = it.optBoolean("on");
        if (t.equals("chip")) {
            TextView v = h.text(h.fSemi, Ui.TEXT_M, on ? GlassPanel.selText() : Color.WHITE);
            v.setText(it.optString("label") + (on ? "  ✓" : ""));
            v.setSingleLine(true);
            v.setPadding(h.px(1.6f), h.px(.9f), h.px(1.6f), h.px(.9f));
            GradientDrawable b = new GradientDrawable(); b.setCornerRadius(GlassPanel.corner(2 * R)); b.setColor(on ? GlassPanel.selFill() : Ui.WHITE_12);
            v.setBackground(b);
            ringOf.put(v, v); ringRad.put(v, 2 * R);
            return v;
        }
        if (t.equals("app")) {
            LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL);
            FrameLayout card = new FrameLayout(c);
            int tint = 0xFF2A2A2E; try { tint = Color.parseColor(it.optString("tint")); } catch (Exception ignored) { }
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { shade(tint, .18f), shade(tint, -.25f) });
            g.setCornerRadius(R);
            card.setBackground(g);
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(iv, new LayoutParams(h.px(4.3f), h.px(4.3f), Gravity.CENTER));
            appIcon(iv, it.optString("id"));
            v.addView(card, new LinearLayout.LayoutParams(h.px(13), h.px(7.3f)));
            TextView l = h.text(h.fMed, Ui.TEXT_S, Color.WHITE);
            l.setText(it.optString("label")); l.setSingleLine(true); l.setEllipsize(TextUtils.TruncateAt.END); l.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(h.px(13), LayoutParams.WRAP_CONTENT); ll.topMargin = h.px(.6f);
            v.addView(l, ll);
            ringOf.put(v, card); ringRad.put(v, R);
            return v;
        }
        if (t.equals("poster")) {
            LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL);
            NativeVod.RoundFrame card = new NativeVod.RoundFrame(c, R);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { 0xFF34343C, 0xFF1C1C22 }); g.setCornerRadius(R);
            card.setBackground(g);
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(iv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            image(iv, it.optString("img"), it.optString("img2"), h.px(10), h.px(15));
            v.addView(card, new LinearLayout.LayoutParams(h.px(10), h.px(15)));
            TextView l = h.text(h.fSemi, Ui.TEXT_S, Color.WHITE);
            l.setText(it.optString("label")); l.setMaxLines(2); l.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(h.px(10), LayoutParams.WRAP_CONTENT); ll.topMargin = h.px(.6f);
            v.addView(l, ll);
            if (!it.optString("sub").isEmpty()) {
                TextView y = h.text(h.fReg, Ui.TEXT_XS, Ui.WHITE_60); y.setText(it.optString("sub"));
                v.addView(y);
            }
            ringOf.put(v, card); ringRad.put(v, R);
            return v;
        }
        if (t.equals("look")) return look(it);
        if (t.equals("team")) {
            FrameLayout v = new FrameLayout(c);
            GradientDrawable g = new GradientDrawable(); g.setCornerRadius(1.4f * R);
            g.setColor(on ? 0x3830D158 : Ui.WHITE_7);
            if (on) g.setStroke(Math.max(2, h.px(.14f)), 0xFF30D158);
            v.setBackground(g);
            LinearLayout col = new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setPadding(h.px(.6f), h.px(1), h.px(.6f), h.px(.9f));
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            col.addView(iv, new LinearLayout.LayoutParams(h.px(4.4f), h.px(4.4f)));
            image(iv, it.optString("img"), "", h.px(4.4f), h.px(4.4f));
            TextView l = h.text(h.fSemi, Ui.TEXT_S, Color.WHITE);
            l.setText(it.optString("label")); l.setMaxLines(2); l.setEllipsize(TextUtils.TruncateAt.END); l.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); ll.topMargin = h.px(.5f);
            col.addView(l, ll);
            v.addView(col, new LayoutParams(h.px(11), h.px(8.6f)));
            if (on) {
                TextView ck = h.text(h.fSemi, Ui.TEXT_XS, Color.WHITE); ck.setText("✓"); ck.setGravity(Gravity.CENTER);
                GradientDrawable cb = new GradientDrawable(); cb.setShape(GradientDrawable.OVAL); cb.setColor(0xFF30D158); ck.setBackground(cb);
                LayoutParams kl = new LayoutParams(h.px(1.6f), h.px(1.6f), Gravity.END | Gravity.TOP); kl.topMargin = h.px(.5f); kl.rightMargin = h.px(.5f);
                v.addView(ck, kl);
            }
            ringOf.put(v, v); ringRad.put(v, 1.4f * R);
            return v;
        }
        // a channel or a show on a channel
        LinearLayout v = new LinearLayout(c);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(h.px(1.1f), 0, h.px(1.1f), 0);
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(1.3f * R); g.setColor(0x17FFFFFF);
        v.setBackground(g);
        FrameLayout lg = new FrameLayout(c);
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        lg.addView(iv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        if (it.optString("img").isEmpty()) {
            GradientDrawable lb = new GradientDrawable(); lb.setCornerRadius(.7f * R); lb.setColor(Ui.WHITE_12); lg.setBackground(lb);
            TextView ini = h.text(h.fSemi, Ui.TEXT_L, Color.WHITE); ini.setText(it.optString("label").isEmpty() ? "" : it.optString("label").substring(0, 1)); ini.setGravity(Gravity.CENTER);
            lg.addView(ini, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        } else image(iv, it.optString("img"), "", h.px(4.6f), h.px(3.2f));
        v.addView(lg, new LinearLayout.LayoutParams(h.px(4.6f), h.px(3.2f)));
        LinearLayout tx = new LinearLayout(c); tx.setOrientation(LinearLayout.VERTICAL);
        TextView b = h.text(h.fSemi, Ui.TEXT_M, Color.WHITE);
        b.setText((it.optBoolean("bell") ? "🔔 " : "") + it.optString("label")); b.setSingleLine(true); b.setEllipsize(TextUtils.TruncateAt.END);
        tx.addView(b);
        TextView sb = h.text(h.fReg, Ui.TEXT_XS, it.optBoolean("live") ? 0xFFFF6B60 : Ui.WHITE_60);
        sb.setText(it.optString("sub")); sb.setSingleLine(true); sb.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); sl.topMargin = h.px(.2f);
        tx.addView(sb, sl);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1); tl.leftMargin = h.px(1);
        v.addView(tx, tl);
        v.setLayoutParams(new LinearLayout.LayoutParams(h.px(25), h.px(6.2f)));
        FrameLayout wrap = new FrameLayout(c);
        wrap.addView(v, new LayoutParams(h.px(25), h.px(6.2f)));
        ringOf.put(wrap, v); ringRad.put(wrap, 1.3f * R);
        return wrap;
    }

    /** how an app's tile would look (the icon picker): painted the way Home paints it */
    private View look(final JSONObject it) {
        Context c = getContext();
        LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); v.setGravity(Gravity.CENTER_HORIZONTAL);
        NativeVod.RoundFrame card = new NativeVod.RoundFrame(c, R);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { 0xFF3A3A42, 0xFF202026 }); g.setCornerRadius(R);
        card.setBackground(g);
        final int w = h.px(16), hh = Math.round(16 * 9 / 16f * R);
        final ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        card.addView(iv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        if (it.optBoolean("photo")) {
            ImageView ph = new ImageView(c);
            HomeIcons pi = new HomeIcons(HomeIcons.PHOTOS, 1.6f); pi.setColor(Ui.WHITE_85);
            ph.setImageDrawable(pi);
            card.addView(ph, new LayoutParams(h.px(3.6f), h.px(3.6f), Gravity.CENTER));
        } else {
            final JSONObject app = it.optJSONObject("app");
            final JSONObject lk = it.optJSONObject("look");
            final String key = "look:" + (app == null ? "" : app.optString("id")) + ":" + (lk == null ? "" : lk.toString().hashCode());
            iv.setTag(key);
            Bitmap b = pics.get(key);
            if (b != null) iv.setImageBitmap(b);
            else if (app != null) loader.execute(new Runnable() { @Override public void run() {
                Bitmap out = null;
                try {
                    NativeHome.App a = new NativeHome.App();
                    a.id = app.optString("id"); a.label = app.optString("label"); a.tint = app.optString("tint", "#2a2a2e"); a.banner = app.optBoolean("banner");
                    out = TileArt.render(h.bridge, a, lk, w, hh, R * .82f, h.fSemi);
                    if (out != null) pics.put(key, out);
                } catch (Throwable ignored) { }
                final Bitmap fo = out;
                h.main.post(new Runnable() { @Override public void run() { if (fo != null && key.equals(iv.getTag())) iv.setImageBitmap(fo); } });
            } });
        }
        v.addView(card, new LinearLayout.LayoutParams(w, hh));
        TextView l = h.text(h.fMed, Ui.TEXT_S, Color.WHITE);
        l.setText(it.optString("label")); l.setSingleLine(true); l.setEllipsize(TextUtils.TruncateAt.END); l.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(w, LayoutParams.WRAP_CONTENT); ll.topMargin = h.px(.7f);
        v.addView(l, ll);
        ringOf.put(v, card); ringRad.put(v, R);
        return v;
    }

    /** dark, with a soft light from the top: painted once */
    private static Bitmap backdropCache;
    private static Bitmap backdrop(int w, int hh) {
        if (backdropCache != null && backdropCache.getWidth() == w) return backdropCache;
        Bitmap b = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, hh, 0xFF17181D, 0xFF0B0B0E, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, hh, p);
        p.setShader(new android.graphics.RadialGradient(w * .5f, -hh * .25f, w * .6f, 0x1F8FA8FF, 0x008FA8FF, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, hh, p);
        b.setHasAlpha(false);
        backdropCache = b;
        return b;
    }

    private static int shade(int c, float amt) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        if (amt > 0) { r += (255 - r) * amt; g += (255 - g) * amt; b += (255 - b) * amt; } else { r += r * amt; g += g * amt; b += b * amt; }
        return 0xFF000000 | (Math.max(0, Math.min(255, r)) << 16) | (Math.max(0, Math.min(255, g)) << 8) | Math.max(0, Math.min(255, b));
    }

    // ================================================================== pictures

    static { Diag.TRIMMERS.add(new Runnable() { @Override public void run() { pics.evictAll(); } }); }
    private static final android.util.LruCache<String, Bitmap> pics = new android.util.LruCache<String, Bitmap>(20 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final java.util.concurrent.ExecutorService loader = java.util.concurrent.Executors.newFixedThreadPool(3);

    /* pictures go through Home's picture loader: one download per picture however many times the rows
       are rebuilt while results arrive, kept for the next time */
    private void image(final ImageView iv, final String url, final String fallback, final int w, final int hh) {
        if (url == null || url.isEmpty()) return;
        final String key = "fd:" + url + ":" + w;
        iv.setTag(key);
        Bitmap b = h.imgs.cached(key);
        if (b != null) { iv.setImageBitmap(b); return; }
        h.imgs.make(key, new HomeImages.Maker() { @Override public Bitmap make() {
            Bitmap got = fetch(url, w, hh);
            if (got == null && fallback != null && !fallback.isEmpty() && !fallback.equals(url)) got = fetch(fallback, w, hh);
            return got;
        } }, new HomeImages.Done() { @Override public void done(Bitmap out) {
            if (out == null || !key.equals(iv.getTag())) return;
            iv.setAlpha(0f); iv.setImageBitmap(out); iv.animate().alpha(1f).setDuration(Ui.FAST).start();
        } });
    }
    private static Bitmap fetch(String url, int w, int hh) {
        try { byte[] d = HomeImages.http(url, null); return d == null ? null : HomeImages.decode(d, w, hh); } catch (Throwable t) { return null; }
    }
    private void appIcon(final ImageView iv, final String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        final String key = "app:" + pkg;
        iv.setTag(key);
        Bitmap b = pics.get(key);
        if (b != null) { iv.setImageBitmap(b); return; }
        loader.execute(new Runnable() { @Override public void run() {
            Bitmap out = null;
            try {
                Drawable d = getContext().getPackageManager().getApplicationIcon(pkg);
                int s = h.px(4.3f) * 2;
                out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(out);
                d.setBounds(0, 0, s, s); d.draw(cv);
                pics.put(key, out);
            } catch (Throwable ignored) { }
            final Bitmap fo = out;
            h.main.post(new Runnable() { @Override public void run() { if (fo != null && key.equals(iv.getTag())) iv.setImageBitmap(fo); } });
        } });
    }

    // ================================================================== focus

    private void mark(boolean animate) {
        // the box and the button
        headBg.setColor(r == -1 ? 0x29FFFFFF : Ui.WHITE_12);
        headBg.setStroke(r == -1 ? Math.max(2, h.px(.18f)) : 0, Ui.WHITE_95);
        GradientDrawable bb = new GradientDrawable(); bb.setCornerRadius(2 * R);
        if (kind.equals("search")) {
            bb.setShape(GradientDrawable.OVAL); bb.setColor(r == -2 ? Color.WHITE : Color.TRANSPARENT);
            mic.setBackground(bb);
            ((HomeIcons) mic.getDrawable()).setColor(r == -2 ? 0xFF111111 : Ui.WHITE_85);
        } else {
            bb.setColor(Color.WHITE);
            if (r == -2) bb.setStroke(Math.max(2, h.px(.18f)), 0xFF0A84FF);
            btn.setBackground(bb);
            btn.setScaleX(r == -2 ? 1.06f : 1f); btn.setScaleY(r == -2 ? 1.06f : 1f);
        }
        // the item
        View next = null;
        if (r >= 0 && r < rows.size()) {
            Sec s = rows.get(r);
            int c = Math.min(col(r), s.cards.size() - 1);
            cols.put(r, c);
            next = s.cards.get(c);
        }
        if (focusedItem != next) {
            if (focusedItem != null) ring(focusedItem, false, animate);
            focusedItem = next;
            if (next != null) ring(next, true, animate);
        }
        if (r >= 0) reveal(animate);
        else if (animate) list.animate().translationY(0).setDuration(Ui.MOVE).setInterpolator(TileView.EASE).start(); else list.setTranslationY(0);
    }

    private void ring(View v, boolean on, boolean animate) {
        View card = ringOf.get(v);
        if (card == null) card = v;
        if (on) {
            GradientDrawable rg = new GradientDrawable();
            Float rad = ringRad.get(v);
            boolean plain = GlassPanel.look.equals("glass") || GlassPanel.look.equals("spot");
            rg.setCornerRadius(GlassPanel.corner(rad == null ? R : rad)); rg.setStroke(Math.max(2, h.px(GlassPanel.look.equals("air") ? .24f : .18f)), plain ? Color.WHITE : GlassPanel.selFill());
            card.setForeground(rg);
        } else card.setForeground(null);
        float s = on ? 1.07f : 1f;
        v.setPivotX(v.getWidth() / 2f); v.setPivotY(card.getHeight() / 2f);
        if (animate) v.animate().scaleX(s).scaleY(s).setDuration(Ui.FAST).setInterpolator(TileView.EASE).start(); else { v.setScaleX(s); v.setScaleY(s); }
    }

    private void reveal(boolean animate) {
        Sec s = rows.get(r);
        View card = focusedItem;
        // sideways: keep the item inside the pane
        if (card != null && card.getWidth() > 0) {
            float left = card.getLeft(), right = card.getRight(), to = s.scroll, pad = h.px(PADX);
            if (left < s.scroll + pad) to = left - pad;
            else if (right > s.scroll + pw - pad) to = right - pw + pad;
            to = Math.max(0, Math.min(to, Math.max(0, s.in.getWidth() - pw)));
            if (to != s.scroll) {
                s.scroll = to;
                if (animate) s.in.animate().translationX(-to).setDuration(Ui.BASE).setInterpolator(TileView.EASE).start(); else s.in.setTranslationX(-to);
            }
        } else if (card != null) {
            card.post(new Runnable() { @Override public void run() { if (focusedItem != null && focusedItem.getWidth() > 0) reveal(false); } });
        }
        // up and down: the row's section in view
        View sv = s.view;
        if (sv.getHeight() == 0) return;
        float viewH = body.getHeight() > 0 ? body.getHeight() : ph - h.px(BODY_Y);
        float cur = -list.getTranslationY(), top = sv.getTop() - h.px(.6f), bot = sv.getBottom() + h.px(.6f), to = cur;
        if (top < cur) to = Math.max(0, top);
        else if (bot > cur + viewH) to = bot - viewH;
        if (to == cur) return;
        if (animate) list.animate().translationY(-to).setDuration(Ui.MOVE).setInterpolator(TileView.EASE).start(); else list.setTranslationY(-to);
    }

    // ================================================================== keys

    void key(char d) {
        if (r == -2) {
            if (d == 'l' || d == 'd') { r = -1; mark(true); }
            return;
        }
        if (noBox && r < 0) { if (!rows.isEmpty()) { r = 0; mark(true); } return; }
        if (r == -1) {
            if (d == 'd' && !rows.isEmpty()) { r = 0; mark(true); }
            else if (d == 'r' || d == 'u') { r = -2; mark(true); }
            return;
        }
        Sec s = rows.get(r);
        int c = col(r);
        if (d == 'u') { if (r == 0 && noBox) return; r = r == 0 ? -1 : r - 1; }
        else if (d == 'd') { if (r < rows.size() - 1) r++; else return; }
        else if (d == 'l') { if (c > 0) cols.put(r, c - 1); else return; }
        else if (d == 'r') { if (c < s.cards.size() - 1) cols.put(r, c + 1); else return; }
        mark(true);
    }
    /* a finger (phones): "ok" when something's chosen (the box, the button beside it, a result), "back" outside
       the pane, null for nothing */
    String focusAt(float x, float y) {
        if (!h.hit(panel, x, y, 0)) return "back";
        if (h.hit(btn, x, y, .4f) || h.hit(mic, x, y, .4f)) { r = -2; mark(true); return "ok"; }
        if (!noBox && h.hit(head, x, y, 0)) { r = -1; mark(true); return "ok"; }
        for (int ri = 0; ri < rows.size(); ri++) {
            Sec s = rows.get(ri);
            for (int c = 0; c < s.cards.size(); c++) if (h.hit(s.cards.get(c), x, y, 0)) { r = ri; cols.put(ri, c); mark(true); return "ok"; }
        }
        return null;
    }
    boolean overBox(float x, float y) { return h.hit(input, x, y, .6f); }
    void ok() {
        if (r == -2) { if (nat()) h.bridge.voiceInput("Search TopShelf"); else h.page(kind.equals("search") ? "window.nhFindVoice && nhFindVoice()" : "window.nhFindClose && nhFindClose()"); return; }
        if (r == -1) { startTyping(); return; }
        Sec s = rows.get(r);
        if (focusedItem != null) {
            final View v = focusedItem;
            v.animate().scaleX(1.02f).scaleY(1.02f).setDuration(90).withEndAction(new Runnable() { @Override public void run() {
                if (v == focusedItem) v.animate().scaleX(1.07f).scaleY(1.07f).setDuration(Ui.QUICK).start();
            } }).start();
        }
        if (nat()) h.search.ok(s.ri, col(r));
        else h.page("window.nhFindOk && nhFindOk(" + s.ri + "," + col(r) + ")");
    }
    void hold() {
        if (r < 0) return;
        if (nat()) h.search.hold(rows.get(r).ri, col(r));
        else h.page("window.nhFindHold && nhFindHold(" + rows.get(r).ri + "," + col(r) + ")");
    }
    void back() {
        if (typing) { stopTyping(false); return; }
        if (nat()) h.search.close();
        else h.page("window.nhFindClose && nhFindClose()");
    }

    // ================================================================== typing

    void startTyping() {
        typing = true;
        r = -1; mark(true);
        input.setFocusable(true);
        input.setFocusableInTouchMode(true);
        input.requestFocus();
        input.setSelection(input.length());
        input.postDelayed(new Runnable() { @Override public void run() {
            if (!typing) return;
            InputMethodManager im = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        } }, 120);
    }
    /** Enter on the keyboard: search now and go down to the results */
    void submit() {
        h.main.removeCallbacks(sendQ);
        if (nat()) h.search.query(input.getText().toString(), true, false);
        else h.page("window.nhFindQ && nhFindQ(" + JSONObject.quote(input.getText().toString()) + ",true)");
        stopTyping(true);
    }
    void stopTyping(boolean toResults) {
        if (!typing) return;
        typing = false;
        InputMethodManager im = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
        input.setFocusable(false);
        h.act.focusWeb();
        if (toResults && !rows.isEmpty()) { r = 0; }
        mark(true);
    }
    /** keys while typing: Down and Back leave the box, the rest go to the keyboard/box */
    boolean typingKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BACK) { if (down) stopTyping(false); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) { if (down) { h.main.removeCallbacks(sendQ); sendQ.run(); stopTyping(true); } return true; }
        if (code == KeyEvent.KEYCODE_DPAD_UP) { if (down) { stopTyping(false); r = -2; mark(true); } return true; }
        return false;
    }
}
