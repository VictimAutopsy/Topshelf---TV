package com.topshelf.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
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
 * Settings › Addons: a full-screen window listing the installed addons (what each offers, on or off), with a
 * QR code to add more from your phone (paste a link, or copy your Stremio account's addons). OK on an addon:
 * turn it on/off, show its rows in Movies & TV or not, move it up/down (streams are listed in this order),
 * remove it.
 */
final class AddonsView extends FrameLayout {
    private final NativeHome h;
    private final float R;
    private final ListView listV;
    private final ImageView qr;
    private final TextView qrUrl, count;
    private int sel;
    private JSONArray list = new JSONArray();
    private final Map<String, Bitmap> logos = new HashMap<>();

    AddonsView(NativeHome host) {
        super(host.getContext());
        h = host; R = host.R;
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { 0xFF15122A, 0xFF0B0C14, 0xFF07080D }));
        setClickable(true);
        TextView t = h.text(h.fSemi, 3f, Color.WHITE); t.setText("Addons");
        addView(t, h.at(5, 4, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        TextView sub = h.text(h.fReg, Ui.TEXT_M, Ui.WHITE_75);
        sub.setText("The same addons Nuvio and Stremio use. Choose a movie or show in Movies & TV Shows to see the streams your addons find for it — like your debrid service's — and play them in TopShelf's player.");
        sub.setLineSpacing(0, 1.25f);
        addView(sub, h.at(5, 8.4f, h.px(Math.min(60, h.SW - 10)), LayoutParams.WRAP_CONTENT));
        count = h.text(h.fSemi, Ui.TEXT_S, Ui.WHITE_60); count.setLetterSpacing(.08f);
        addView(count, h.at(5, 13.6f + (h.portrait ? 4 : 0), LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        listV = new ListView(getContext());
        addView(listV, h.at(4, 15.6f + (h.portrait ? 4 : 0), h.px(Math.min(62, h.SW - 8)), h.px(h.SH - 15.6f - 3 - (h.portrait ? 4 : 0))));

        // the phone panel
        LinearLayout side = new LinearLayout(getContext()); side.setOrientation(LinearLayout.VERTICAL); side.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable sb = new GradientDrawable(); sb.setCornerRadius(2 * R); sb.setColor(0xFF17181F); sb.setStroke(Math.max(1, h.px(.1f)), Ui.WHITE_12);
        side.setBackground(sb); side.setPadding(h.px(2.4f), h.px(2.4f), h.px(2.4f), h.px(2.4f));
        TextView st = h.text(h.fSemi, 1.6f, Color.WHITE); st.setText("Add from your phone"); side.addView(st);
        TextView ss = h.text(h.fReg, Ui.TEXT_S, Ui.WHITE_75); ss.setGravity(Gravity.CENTER); ss.setLineSpacing(0, 1.25f);
        ss.setText("Scan to paste an addon's link, or copy all your addons from your Stremio account.");
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); sl.topMargin = h.px(.8f);
        side.addView(ss, sl);
        FrameLayout qb = new FrameLayout(getContext());
        GradientDrawable wb = new GradientDrawable(); wb.setCornerRadius(1.2f * R); wb.setColor(Color.WHITE); qb.setBackground(wb);
        qb.setPadding(h.px(1), h.px(1), h.px(1), h.px(1));
        qr = new ImageView(getContext()); qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qb.addView(qr, new LayoutParams(h.px(17), h.px(17)));
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); ql.topMargin = h.px(1.6f);
        side.addView(qb, ql);
        qrUrl = h.text(h.fReg, Ui.TEXT_XS, Ui.WHITE_60); qrUrl.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); ul.topMargin = h.px(1.2f);
        side.addView(qrUrl, ul);
        TextView back = h.text(h.fReg, Ui.TEXT_XS, Ui.WHITE_50); back.setText("OK on an addon · options      Back · close");
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); bl.topMargin = h.px(1.6f);
        side.addView(back, bl);
        addView(side, h.at(h.SW - 5 - 30, 15.6f, h.px(30), LayoutParams.WRAP_CONTENT));
        if (!h.act.isTv()) side.setVisibility(GONE);        // (on the phone itself, a code to scan from a phone is no use)
    }

    void open() {
        refresh();
        String url = phoneUrl();
        if (!url.isEmpty()) { qr.setImageBitmap(NativeHome.qrBitmapOf(url)); qrUrl.setText(url); }
        else { qr.setImageDrawable(null); qrUrl.setText("The TV isn't on your home network right now"); }
        setVisibility(VISIBLE); bringToFront();
        setAlpha(0f); animate().alpha(1f).setDuration(Ui.FAST).start();
    }
    void close() { animate().alpha(0f).setDuration(Ui.FAST).withEndAction(new Runnable() { @Override public void run() { setVisibility(GONE); } }).start(); }
    boolean shownNow() { return getVisibility() == VISIBLE; }

    private String phoneUrl() {
        MainActivity a = h.act;
        if (a.link == null || a.link.port() == 0 || LinkServer.localIp().isEmpty()) return "";
        return "http://" + LinkServer.localIp() + ":" + a.link.port() + "/addons?k=" + Addons.get(getContext()).newSetupKey();
    }

    void refresh() {
        list = Addons.get(getContext()).list();
        if (sel >= list.length()) sel = Math.max(0, list.length() - 1);
        count.setText(list.length() == 0 ? "NO ADDONS YET" : list.length() + (list.length() == 1 ? " ADDON" : " ADDONS") + " · STREAMS ARE LISTED IN THIS ORDER");
        listV.invalidate();
    }

    boolean key(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int c = e.getKeyCode();
        if (c == KeyEvent.KEYCODE_BACK || c == KeyEvent.KEYCODE_ESCAPE) { close(); return true; }
        if (c == KeyEvent.KEYCODE_DPAD_UP) { if (sel > 0) sel--; listV.reveal(); listV.invalidate(); }
        else if (c == KeyEvent.KEYCODE_DPAD_DOWN) { if (sel < list.length() - 1) sel++; listV.reveal(); listV.invalidate(); }
        else if (c == KeyEvent.KEYCODE_DPAD_CENTER || c == KeyEvent.KEYCODE_ENTER || c == KeyEvent.KEYCODE_NUMPAD_ENTER || c == KeyEvent.KEYCODE_BUTTON_A) menu();
        return true;
    }

    private void menu() {
        final JSONObject a = list.optJSONObject(sel);
        if (a == null) return;
        final Addons ad = Addons.get(getContext());
        final String url = a.optString("url");
        JSONObject m = a.optJSONObject("manifest");
        List<String> l = new ArrayList<>(); List<Runnable> r = new ArrayList<>();
        final boolean on = a.optBoolean("on", true);
        l.add(on ? "Turn off" : "Turn on"); r.add(new Runnable() { @Override public void run() { ad.set(url, "on", !on); refresh(); } });
        if (sel > 0) { l.add("Move up"); r.add(new Runnable() { @Override public void run() { ad.move(url, -1); sel--; refresh(); } }); }
        if (sel < list.length() - 1) { l.add("Move down"); r.add(new Runnable() { @Override public void run() { ad.move(url, 1); sel++; refresh(); } }); }
        l.add("Check for an update"); r.add(new Runnable() { @Override public void run() {
            new Thread(new Runnable() { @Override public void run() {
                final String err = ad.install(url);
                post(new Runnable() { @Override public void run() { h.toast(err.isEmpty() ? "Up to date" : err); refresh(); } });
            } }).start();
        } });
        l.add("Remove"); r.add(new Runnable() { @Override public void run() { ad.remove(url); h.toast("Removed"); refresh(); } });
        h.showMenu(l, r, null, 1f, m == null ? "Addon" : m.optString("name"));
    }
    private static boolean hasRows(JSONObject m) {
        JSONArray cs = m == null ? null : m.optJSONArray("catalogs");
        if (cs != null) for (int i = 0; i < cs.length(); i++) { String t = cs.optJSONObject(i).optString("type"); if (t.equals("movie") || t.equals("series")) return true; }
        return false;
    }
    static String what(JSONObject m) {
        if (m == null) return "";
        List<String> out = new ArrayList<>();
        if (Addons.offers(m, "stream", null, null)) out.add("streams");
        if (Addons.offers(m, "subtitles", null, null)) out.add("subtitles");
        if (Addons.offers(m, "meta", null, null)) out.add("details");
        return out.isEmpty() ? "catalogs only (not used here)" : TextUtils.join(" · ", out);
    }

    /** the addons, drawn as one view (rows like the guide's) */
    private final class ListView extends View {
        private float scroll;
        private final float rowH = 5.4f * R;
        private final TextPaint tn = new TextPaint(Paint.ANTI_ALIAS_FLAG), ts = new TextPaint(Paint.ANTI_ALIAS_FLAG), tc = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF rf = new RectF();
        ListView(android.content.Context c) {
            super(c);
            tn.setTypeface(h.fSemi); tn.setTextSize(1.35f * R);
            ts.setTypeface(h.fReg); ts.setTextSize(1f * R);
            tc.setTypeface(h.fSemi); tc.setTextSize(.85f * R); tc.setTextAlign(Paint.Align.CENTER);
        }
        void reveal() {
            float y = sel * rowH, vh = getHeight();
            if (y < scroll) scroll = y; else if (y + rowH > scroll + vh) scroll = y + rowH - vh;
        }
        @Override protected void onDraw(Canvas c) {
            int w = getWidth();
            if (list.length() == 0) {
                ts.setColor(Ui.WHITE_60);
                c.drawText("Scan the code on the right with your phone to add your first addon.", R, 2 * R, ts);
                return;
            }
            for (int i = 0; i < list.length(); i++) {
                float y = i * rowH - scroll;
                if (y + rowH < 0 || y > getHeight()) continue;
                JSONObject a = list.optJSONObject(i), m = a.optJSONObject("manifest");
                boolean f = i == sel, on = a.optBoolean("on", true);
                rf.set(R, y + .3f * R, w - R, y + rowH - .3f * R);
                p.setColor(f ? 0xFF34333D : Ui.WHITE_7); c.drawRoundRect(rf, R, R, p);
                if (f) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2, .14f * R)); p.setColor(Ui.WHITE_85); c.drawRoundRect(rf, R, R, p); p.setStyle(Paint.Style.FILL); }
                // logo
                Bitmap lg = logo(m == null ? "" : m.optString("logo"));
                RectF lr = new RectF(rf.left + 1.1f * R, rf.centerY() - 1.6f * R, rf.left + 4.3f * R, rf.centerY() + 1.6f * R);
                p.setColor(Ui.WHITE_12); c.drawRoundRect(lr, .7f * R, .7f * R, p);
                if (lg != null) { float s = Math.min(lr.width() / lg.getWidth(), lr.height() / lg.getHeight()); float dw = lg.getWidth() * s, dh = lg.getHeight() * s;
                    p.setAlpha(on ? 255 : 110); c.drawBitmap(lg, null, new RectF(lr.centerX() - dw / 2, lr.centerY() - dh / 2, lr.centerX() + dw / 2, lr.centerY() + dh / 2), p); p.setAlpha(255); }
                float tx = lr.right + 1.2f * R;
                String chip = on ? "ON" : "OFF";
                float cw = tc.measureText(chip) + 1.4f * R;
                RectF cr = new RectF(rf.right - 1.2f * R - cw, rf.centerY() - .95f * R, rf.right - 1.2f * R, rf.centerY() + .95f * R);
                p.setColor(on ? 0x3330D158 : Ui.WHITE_12); c.drawRoundRect(cr, .95f * R, .95f * R, p);
                tc.setColor(on ? 0xFF30D158 : Ui.WHITE_60); c.drawText(chip, cr.centerX(), cr.centerY() + tc.getTextSize() * .36f, tc);
                float avail = cr.left - tx - R;
                tn.setColor(on ? Color.WHITE : Ui.WHITE_60);
                c.drawText(TextUtils.ellipsize((m == null ? a.optString("url") : m.optString("name")) + (m != null && !m.optString("version").isEmpty() ? "  v" + m.optString("version") : ""), tn, Math.max(1, avail), TextUtils.TruncateAt.END).toString(), tx, rf.centerY() - .15f * R, tn);
                ts.setColor(f ? Ui.WHITE_85 : Ui.WHITE_60);
                c.drawText(TextUtils.ellipsize(what(m) + (m != null && !m.optString("description").isEmpty() ? "  ·  " + m.optString("description") : ""), ts, Math.max(1, avail), TextUtils.TruncateAt.END).toString(), tx, rf.centerY() + 1.45f * R, ts);
            }
        }
    }
    private Bitmap logo(final String url) {
        if (url == null || url.isEmpty()) return null;
        if (logos.containsKey(url)) return logos.get(url);
        logos.put(url, null);
        h.imgs.run(new Runnable() { @Override public void run() {
            Bitmap b = null;
            try { byte[] d = HomeImages.http(url, null); if (d != null) b = HomeImages.decode(d, h.px(4), h.px(4)); } catch (Throwable ignored) { }
            final Bitmap fb = b;
            post(new Runnable() { @Override public void run() { logos.put(url, fb); listV.invalidate(); } });
        } });
        return null;
    }
}
