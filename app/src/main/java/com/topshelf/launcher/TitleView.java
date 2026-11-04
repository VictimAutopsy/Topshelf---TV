package com.topshelf.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * A movie's or show's page (from Movies & TV Shows, with stream addons installed): its backdrop and details;
 * for a show its seasons and episodes; and the streams every installed addon finds for it, grouped by addon in
 * the order the addons are listed — OK on one plays it in TopShelf's player (carrying on where you left off).
 */
final class TitleView extends FrameLayout {
    private final NativeHome h;
    private final float R;
    private final ImageView bg, logo;
    private final TextView title, meta, desc, hint;
    private final Panel panel;

    private JSONObject item;               // the title as Movies & TV listed it
    private JSONObject full;               // its details from the addons/Cinemeta
    private boolean series;
    private final List<Integer> seasons = new ArrayList<>();
    private final List<JSONObject> eps = new ArrayList<>();     // the chosen season's episodes
    private int season, epSel;
    private final List<JSONObject> streams = new ArrayList<>();
    private final Map<String, String> asked = new LinkedHashMap<>();    // addon → "…" / count / error
    private String videoId = "", streamsFor = "";
    private int strSel; private int gen;
    private String zone = "buttons";       // "buttons" | "seasons" | "episodes" | "cast" | "streams" / "chips" (the streams screen, over the rest)
    private int btnSel;                    // the buttons under the details (see buttons())
    private int castSel;
    private int reviewOpen = -1;           // a review read in full (index), or -1
    private float reviewScroll;
    private final List<JSONObject> allEps = new ArrayList<>();     // every episode, in order (specials last)
    // more about the title: Trakt's rating, related titles and reviews (or the same genre from Cinemeta)
    private int traktPct, traktVotes;
    /** each episode's Trakt rating ("season:episode" → 0–10), for the cards and the ratings strip */
    private final Map<String, Double> epRating = new java.util.HashMap<>();
    private final List<JSONObject> similar = new ArrayList<>(), reviews = new ArrayList<>(), traktCast = new ArrayList<>();
    /** each row's place: videos, similar, reviews */
    private static final class RowState { int sel; float scroll, scrollT; }
    private final Map<String, RowState> rowState = new java.util.HashMap<>();
    private RowState rs(String z) { RowState r = rowState.get(z); if (r == null) { r = new RowState(); rowState.put(z, r); } return r; }
    /** titles opened from "More like this" (Back returns to the one before) */
    private final List<JSONObject> history = new ArrayList<>();
    private boolean pendingBest;           // Play pressed while the addons are still answering
    private String filter;                 // the streams screen: only this addon's (null: all)
    private int chipSel;                   // the addon chips above the list (0: All)
    private boolean streamsScreen() { return zone.equals("streams") || zone.equals("chips"); }
    /** the streams the list shows (all, or one addon's) */
    private List<JSONObject> shown() {
        if (filter == null) return streams;
        List<JSONObject> l = new ArrayList<>();
        for (JSONObject x : streams) if (filter.equals(x.optString("_addon"))) l.add(x);
        return l;
    }
    private List<String> chipNames() {
        List<String> l = new ArrayList<>(); l.add("All");
        for (Map.Entry<String, String> e : asked.entrySet()) if (e.getValue().matches("[1-9]\\d*")) l.add(e.getKey());
        return l;
    }
    private String wantVideo = "";         // from Continue watching: this episode
    private JSONObject lastStream;         // and the stream you used last time (chosen first)

    // ================================================================== the trailer behind the details
    private final android.widget.FrameLayout trHost;
    private final View shade, shade2;
    private TrailerPlayer tp;
    private int trTries;
    private final Runnable trLater = new Runnable() { @Override public void run() { startTrailer(); } };
    /* The trailer waits until you've sat still on the page for a while (the "Trailer starts after" setting,
       plus five seconds); then the details step aside and it plays full screen. Any button brings the page
       back (that press does only that). When it ends by itself, the page comes back and it isn't started
       again; stopped by a press, it can start again after another still spell. */
    private boolean trFull, trEndedOnce;
    private long idleMs() { return Math.max(8000, h.S.optInt("trailerDelay", 5) * 1000L + 5000); }
    private void scheduleTrailer() {
        removeCallbacks(trLater);
        trTries = 0;
        if (h.S.optString("trailers", "card").equals("off") || trEndedOnce) return;
        postDelayed(trLater, idleMs());
    }
    private void startTrailer() {
        if (!shownNow() || item == null || h.vodPlayingNow()) return;
        if (streamsScreen() || reviewOpen >= 0 || person != null) { postDelayed(trLater, idleMs()); return; }       // (choosing a stream, reading)
        String yt = trailerId();
        if (yt.isEmpty()) { if (full == null && trTries++ < 6) postDelayed(trLater, 1500); return; }    // (the details may name it)
        if (tp == null) tp = new TrailerPlayer(h.act);
        final int g = gen;
        tp.play(trHost, yt, !h.S.optBoolean("trailerSound", false), 1080, new TrailerPlayer.Listener() {
            @Override public void playing() { if (g == gen) trailerEnter(); }
            @Override public void ended() { if (g == gen) { trEndedOnce = true; trailerExit(); } }
        });
    }
    private void trailerEnter() {
        trFull = true;
        trHost.animate().cancel(); trHost.animate().alpha(1f).setDuration(700).start();
        for (View v : new View[] { panel, shade, shade2, hint, epBg }) { v.animate().cancel(); v.animate().alpha(0f).setDuration(700).start(); }
    }
    private void trailerExit() {
        trFull = false;
        if (tp != null) tp.stop();
        trHost.animate().cancel(); trHost.animate().alpha(0f).setDuration(350).start();
        for (View v : new View[] { panel, shade, shade2, hint }) { v.animate().cancel(); v.animate().alpha(1f).setDuration(350).start(); }
        epBg.animate().cancel(); epBg.animate().alpha(epBgFor.isEmpty() ? 0f : 1f).setDuration(350).start();
        scheduleTrailer();
    }
    /** a video starts, or the page closes: the trailer stops and lets go of the TV's video decoder */
    void stopTrailer() {
        removeCallbacks(trLater);
        trHost.animate().cancel(); trHost.setAlpha(0f);
        if (trFull) { trFull = false; for (View v : new View[] { panel, shade, shade2, hint }) { v.animate().cancel(); v.setAlpha(1f); } }
        if (tp != null) { tp.release(); tp = null; }
    }

    /* Browsing a show's episodes, the backdrop is the episode's own picture — blurred into a soft wash of
       its colours if you haven't seen it (no spoilers), sharp if you have. */
    private final ImageView epBg;
    private String epBgFor = "";
    private final Runnable epBgLater = new Runnable() { @Override public void run() { loadEpBg(); } };
    private void updateEpBg() {
        String want = zone.equals("episodes") && epSel < eps.size() ? eps.get(epSel).optString("id") : "";
        if (want.equals(epBgFor)) return;
        epBgFor = want;
        removeCallbacks(epBgLater);
        if (want.isEmpty()) { epBg.animate().cancel(); epBg.animate().alpha(0f).setDuration(450).start(); return; }
        postDelayed(epBgLater, 220);          // (passing episodes by quickly doesn't fetch each one)
    }
    private void loadEpBg() {
        if (epSel >= eps.size()) return;
        final JSONObject e = eps.get(epSel);
        final String id = e.optString("id");
        if (!id.equals(epBgFor)) return;
        String th0 = e.optString("thumbnail"), th1 = epStill(e);
        final String url = !th0.isEmpty() ? th0 : th1;
        if (url == null || url.isEmpty()) return;
        JSONObject pr = Progress.get(getContext(), id);
        final boolean seen = (pr != null && Progress.done(pr)) || Progress.watched(getContext(), id);
        final int g = gen;
        h.imgs.run(new Runnable() { @Override public void run() {
            Bitmap b = null;
            try {
                byte[] d = HomeImages.http(url, null);
                if (d != null) b = HomeImages.decode(d, h.W / 2, h.H / 2);
                if (b != null && !seen) { Bitmap s = Blur.glass(b, 6, 4, 1.8f, .9f); b.recycle(); b = s; }      // (the ambient wash)
            } catch (Throwable ignored) { }
            final Bitmap fb = b;
            post(new Runnable() { @Override public void run() {
                if (g != gen || fb == null || !id.equals(epBgFor)) { h.reclaim(fb); return; }
                Bitmap old = bitmapOf(epBg);
                epBg.setImageBitmap(fb);
                epBg.animate().cancel(); epBg.setAlpha(Math.min(epBg.getAlpha(), .35f));
                if (!trFull) epBg.animate().alpha(1f).setDuration(450).start();
                if (old != null && old != fb) h.reclaim(old);
            } });
        } });
    }

    TitleView(NativeHome host) {
        super(host.getContext());
        h = host; R = host.R;
        setBackgroundColor(0xFF07080D);
        setClickable(true);
        bg = new ImageView(getContext()); bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(bg, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        epBg = new ImageView(getContext()); epBg.setScaleType(ImageView.ScaleType.CENTER_CROP); epBg.setAlpha(0f);
        addView(epBg, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        // the title's trailer, behind the details (as Nuvio's title page does): invisible until its picture moves
        trHost = new android.widget.FrameLayout(getContext());
        trHost.setAlpha(0f);
        addView(trHost, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        shade = new View(getContext());
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] { 0xF207080D, 0xD907080D, 0x6607080D, 0x3307080D }));
        addView(shade, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        shade2 = new View(getContext());
        shade2.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { 0x0007080D, 0x8007080D, 0xF207080D }));
        addView(shade2, h.at(0, 20, LayoutParams.MATCH_PARENT, h.px(h.SH - 20)));
        logo = new ImageView(getContext()); logo.setScaleType(ImageView.ScaleType.FIT_START);
        addView(logo, h.at(5, 4, h.px(42), h.px(10)));
        title = h.text(h.fSemi, 4.2f, Color.WHITE); title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        title.setShadowLayer(R, 0, .2f * R, 0x99000000);
        addView(title, h.at(5, 5.4f, h.px(70), LayoutParams.WRAP_CONTENT));
        meta = h.text(h.fMed, 1.45f, 0xD9FFFFFF);
        addView(meta, h.at(5, 15.2f, h.px(70), LayoutParams.WRAP_CONTENT));
        desc = h.text(h.fReg, 1.35f, 0xBFFFFFFF); desc.setMaxLines(3); desc.setEllipsize(TextUtils.TruncateAt.END); desc.setLineSpacing(0, 1.22f);
        addView(desc, h.at(5, 18f, h.px(64), LayoutParams.WRAP_CONTENT));
        panel = new Panel(getContext());
        addView(panel, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        title.setVisibility(GONE); meta.setVisibility(GONE); desc.setVisibility(GONE); logo.setVisibility(GONE);
        hint = h.text(h.fReg, 1.35f, 0x8CFFFFFF);
        addView(hint, h.at(5, h.SH - 2.9f, h.px(Math.min(100, h.SW - 10)), LayoutParams.WRAP_CONTENT));
        if (h.portrait) {
            // a phone held upright: the picture across the top (whole, not cropped), fading into the page below it
            int ih = Math.round(h.W * 9 / 16f);
            for (View v : new View[] { bg, epBg, trHost }) { LayoutParams lp = (LayoutParams) v.getLayoutParams(); lp.height = ih; v.setLayoutParams(lp); }
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { 0x3307080D, 0x1A07080D, 0x8C07080D, 0xFF07080D }));
            LayoutParams sl = (LayoutParams) shade.getLayoutParams(); sl.height = ih + 2; shade.setLayoutParams(sl);
            shade2.setVisibility(GONE);
            hint.setVisibility(GONE);
        }
        setVisibility(GONE);
    }

    boolean shownNow() { return getVisibility() == VISIBLE; }

    void open(JSONObject m) { removeCallbacks(playWhenReady); history.clear(); backTo.clear(); person = null; openInner(m); }
    /** Home's billboard "Watch now": the title opens and plays the best stream as soon as one is in */
    void openPlay(JSONObject m) { open(m); playTries = 0; postDelayed(playWhenReady, 500); }
    private int playTries;
    private final Runnable playWhenReady = new Runnable() { @Override public void run() {
        if (!shownNow()) return;
        if (target().isEmpty()) { if (++playTries < 24) postDelayed(this, 500); return; }
        playBest();
    } };
    private void openInner(JSONObject m) {
        item = m; full = null; gen++; person = null;
        epRating.clear();
        trEndedOnce = false; trFull = false; epBgFor = ""; removeCallbacks(epBgLater);
        epBg.animate().cancel(); epBg.setAlpha(0f); drop(epBg);
        for (View v : new View[] { panel, shade, shade2, hint }) { v.animate().cancel(); v.setAlpha(1f); }
        traktPct = 0; traktVotes = 0; similar.clear(); reviews.clear(); traktCast.clear(); reviewOpen = -1; castFor = null; castCache = null; rowState.clear();
        series = "series".equals(m.optString("type"));
        seasons.clear(); eps.clear(); streams.clear(); asked.clear(); videoId = ""; streamsFor = ""; strSel = 0; epSel = 0;
        thumbs.evictAll(); thumbAsked.clear(); thumbFailed.clear(); blurAsked.clear(); panel.resetScroll();
        zone = "buttons"; btnSel = 0; castSel = 0; pendingBest = false; allEps.clear(); binge = null; removeCallbacks(bestWait);
        title.setText(m.optString("name"));
        drop(logo); drop(bg);
        meta.setText(metaLine(m));
        desc.setText(m.optString("desc"));
        loadPic(m.optString("background").isEmpty() ? m.optString("poster") : m.optString("background"), bg, h.W, h.H);
        if (!m.optString("logo").isEmpty()) loadLogo(m.optString("logo"));
        setVisibility(VISIBLE); bringToFront();
        setAlpha(0f); animate().alpha(1f).setDuration(200).start();
        if (tp != null) tp.stop();
        trHost.animate().cancel(); trHost.setAlpha(0f);
        scheduleTrailer();
        drawHint();
        panel.invalidate();
        final int g = gen;
        final String type = m.optString("type"), id = m.optString("id");
        h.imgs.run(new Runnable() { @Override public void run() {
            final JSONObject f = Addons.get(getContext()).meta(type, id);
            post(new Runnable() { @Override public void run() { if (g == gen) gotMeta(f); } });
        } });
        wantVideo = m.optString("video");
        lastStream = m.optJSONObject("cwStream");
        if (!series) askStreams(id);
        else if (!wantVideo.isEmpty()) { askStreams(wantVideo); zone = "streams"; drawHint(); }
    }
    void close() {
        gen++; person = null; pGen++; backTo.clear();
        stopTrailer();
        if (curAsk != null) { curAsk.cancel(); curAsk = null; }
        binge = null;
        animate().alpha(0f).setDuration(160).withEndAction(new Runnable() { @Override public void run() {
            setVisibility(GONE); drop(bg); drop(logo); drop(epBg); epBgFor = "";
            // (its pictures go too: up to 32 MB of stills, photos and posters)
            thumbs.evictAll(); thumbFailed.clear(); similar.clear(); reviews.clear(); traktCast.clear(); castFor = null; castCache = null;
            h.settleMemory();
        } }).start();
    }

    private static String metaLine(JSONObject m) {
        List<String> p = new ArrayList<>();
        if (!m.optString("year").isEmpty()) p.add(m.optString("year"));
        if (!m.optString("runtime").isEmpty()) p.add(m.optString("runtime"));
        if (!m.optString("rating").isEmpty()) p.add("★ " + m.optString("rating"));
        JSONArray g = m.optJSONArray("genres");
        if (g != null) for (int i = 0; i < g.length(); i++) p.add(g.optString(i));
        return TextUtils.join("   ·   ", p);
    }

    private void gotMeta(JSONObject f) {
        full = f;
        panel.invalidate(); drawHint();
        loadExtras();
        if (f == null) { if (series) { asked.put("Details", "couldn't load this show's episodes"); panel.invalidate(); } return; }
        if (desc.getText().length() == 0) desc.setText(f.optString("description"));
        if (item.optString("logo").isEmpty() && !f.optString("logo").isEmpty()) loadLogo(f.optString("logo"));
        if (!series) return;
        JSONArray vs = f.optJSONArray("videos");
        TreeSet<Integer> ss = new TreeSet<>();
        if (vs != null) for (int i = 0; i < vs.length(); i++) { JSONObject v = vs.optJSONObject(i); if (v != null) ss.add(v.optInt("season", 1)); }
        seasons.clear(); seasons.addAll(ss);
        if (seasons.size() > 1 && seasons.get(0) == 0) { seasons.remove(0); seasons.add(0); }    // specials last
        allEps.clear();
        if (vs != null) for (int i = 0; i < vs.length(); i++) { JSONObject v = vs.optJSONObject(i); if (v != null) allEps.add(v); }
        Collections.sort(allEps, new Comparator<JSONObject>() { @Override public int compare(JSONObject a, JSONObject b) {
            int sa = a.optInt("season", 1), sb = b.optInt("season", 1);
            if (sa != sb) return (sa == 0 ? 9999 : sa) - (sb == 0 ? 9999 : sb);
            return a.optInt("episode", a.optInt("number")) - b.optInt("episode", b.optInt("number"));
        } });
        // start at the episode from Continue watching, else after the last one watched (or the first)
        JSONObject last = Progress.lastEpisode(getContext(), item.optString("id"));
        int s0 = seasons.isEmpty() ? 1 : seasons.get(0);
        java.util.regex.Matcher wm = java.util.regex.Pattern.compile(":(\\d+):(\\d+)$").matcher(wantVideo);
        if (wm.find() && seasons.contains(Integer.parseInt(wm.group(1)))) s0 = Integer.parseInt(wm.group(1));
        else if (last != null && seasons.contains(last.optInt("season"))) s0 = last.optInt("season");
        pickSeason(s0);
        if (!wantVideo.isEmpty()) { for (int i = 0; i < eps.size(); i++) if (eps.get(i).optString("id").equals(wantVideo)) epSel = i; }
        else if (last != null) for (int i = 0; i < eps.size(); i++) if (eps.get(i).optString("id").equals(last.optString("id"))) epSel = Progress.done(last) && i + 1 < eps.size() ? i + 1 : i;
        panel.revealEp();
        panel.invalidate();
    }
    private void pickSeason(int s) {
        season = s; eps.clear(); epSel = 0;
        JSONArray vs = full == null ? null : full.optJSONArray("videos");
        if (vs != null) for (int i = 0; i < vs.length(); i++) { JSONObject v = vs.optJSONObject(i); if (v != null && v.optInt("season", 1) == s) eps.add(v); }
        Collections.sort(eps, new Comparator<JSONObject>() { @Override public int compare(JSONObject a, JSONObject b) { return a.optInt("episode", a.optInt("number")) - b.optInt("episode", b.optInt("number")); } });
    }

    /** asks every stream addon for this movie / episode; results appear as they come */
    private Addons.Ask curAsk;
    private void askStreams(final String vid) {
        if (curAsk != null) curAsk.cancel();          // (the addons still answering for the last one stop being waited for)
        final Addons.Ask ask = curAsk = new Addons.Ask();
        videoId = vid; streamsFor = vid; streams.clear(); asked.clear(); strSel = 0; panel.revealStr();
        for (JSONObject o : Addons.get(getContext()).enabled())
            if (Addons.offers(o.optJSONObject("manifest"), "stream", item.optString("type"), vid)) asked.put(o.optJSONObject("manifest").optString("name"), "…");
        if (asked.isEmpty()) asked.put("Addons", "none of your addons offer streams for this");
        panel.invalidate(); drawHint();
        final int g = gen; final String type = item.optString("type");
        new Thread(new Runnable() { @Override public void run() {
            Addons.get(getContext()).streams(type, vid, new Addons.StreamsListener() {
                @Override public void addon(final String name, final List<JSONObject> got, final String err) {
                    post(new Runnable() { @Override public void run() {
                        if (g != gen || !vid.equals(streamsFor)) return;
                        asked.put(name, !err.isEmpty() && got.isEmpty() ? err : String.valueOf(got.size()));
                        addStreams(got);
                        panel.invalidate(); drawHint();
                        bestCheck();
                    } });
                }
            }, ask);
        } }, "streams").start();
    }
    /** keeps the addons' order (their order in Settings › Addons), each addon's own order inside it */
    private void addStreams(List<JSONObject> got) {
        List<String> order = new ArrayList<>(asked.keySet());
        JSONObject keep = strSel < streams.size() ? streams.get(strSel) : null;
        // the same link from two addons is listed once
        java.util.Set<String> have = new java.util.HashSet<>();
        for (JSONObject x : streams) have.add(x.optString("url"));
        for (JSONObject x : got) if (have.add(x.optString("url"))) streams.add(x);
        final List<String> ord = order;
        List<JSONObject> sorted = new ArrayList<>(streams);
        // best picture this TV will actually play first (ties keep the addons' order)
        final java.util.Map<JSONObject, Integer> sc = new java.util.HashMap<>();
        for (JSONObject x : sorted) sc.put(x, score(x));
        Collections.sort(sorted, new Comparator<JSONObject>() { @Override public int compare(JSONObject a, JSONObject b) {
            int d = sc.get(b) - sc.get(a);
            return d != 0 ? d : ord.indexOf(a.optString("_addon")) - ord.indexOf(b.optString("_addon"));
        } });
        streams.clear(); streams.addAll(sorted);
        if (lastStream != null) for (int i = 0; i < streams.size(); i++) {
            JSONObject x = streams.get(i);
            if (x.optString("_addon").equals(lastStream.optString("addon")) && x.optString("name").equals(lastStream.optString("name")) && x.optString("title").equals(lastStream.optString("title"))) {
                streams.remove(i); try { x.put("_last", true); } catch (Exception ignored) { } streams.add(0, x); break;
            }
        }
        if (keep != null) strSel = Math.max(0, streams.indexOf(keep));
        panel.revealStr();
    }

    // ================================================================== someone in the cast: their page
    /* OK on someone in the cast opens their page: their photo, what they do, born (and died), where, how many
       films and shows; their biography (OK reads all of it); then rows of what they're known for, their movies
       (newest first, what's coming first of all) and their TV shows. OK on one opens it, and Back from there comes
       back here. From Trakt (as the cast is); with a TMDB key, TMDB fills in what Trakt hasn't (or does it all
       without Trakt); and Wikipedia's summary when neither has a biography. */
    private final class Person {
        final JSONObject who;
        String name, photo, bio = "", born = "", died = "", place = "", dept = "";
        final List<JSONObject> known = new ArrayList<>(), movies = new ArrayList<>(), shows = new ArrayList<>();
        boolean loading = true, bioOpen, bioLong; String note = "", counts = "";
        String zone = "bio";
        float pageY, pageYT, bioScroll;
        final Map<String, RowState> rows = new java.util.HashMap<>();
        RowState rs(String z) { RowState r = rows.get(z); if (r == null) { r = new RowState(); rows.put(z, r); } return r; }
        List<JSONObject> list(String z) { return z.equals("known") ? known : z.equals("movies") ? movies : z.equals("shows") ? shows : new ArrayList<JSONObject>(); }
        List<String> zones() { List<String> l = new ArrayList<>(); l.add("bio"); for (String z : new String[] { "known", "movies", "shows" }) if (!list(z).isEmpty()) l.add(z); return l; }
        Person(JSONObject w) { who = w; name = w.optString("name"); photo = w.optString("photo"); }
    }
    private Person person;
    private int pGen;
    /** history entries that go back to someone's page (the title it was opened from rides along as "_title") */
    private final java.util.IdentityHashMap<JSONObject, Person> backTo = new java.util.IdentityHashMap<>();
    private void backInHistory() {
        JSONObject x = history.remove(history.size() - 1);
        Person p = backTo.remove(x);
        if (p == null) { openInner(x); return; }
        openInner(x.optJSONObject("_title"));
        zone = "cast";
        person = p;
        panel.invalidate(); drawHint();
    }
    private void openPerson(JSONObject who) {
        final Person P = new Person(who);
        person = P;
        stopTrailerKeepTimer();
        final int g = ++pGen;
        panel.invalidate(); drawHint();
        Diag.ev("title: person page · " + P.name);
        new Thread(new Runnable() { @Override public void run() {
            try { loadPerson(P); } catch (Throwable t) { Diag.ev("title: person page didn't load (" + t.getMessage() + ")"); }
            post(new Runnable() { @Override public void run() {
                if (g != pGen) return;
                P.loading = false;
                if (P.known.isEmpty() && P.movies.isEmpty() && P.shows.isEmpty() && P.note.isEmpty())
                    P.note = h.act.trakt == null && !Feeds.tmdbOn(h) ? "Sign in to Trakt (or add a TMDB key) in Settings to see their movies and shows." : "Couldn't find their movies and shows.";
                Diag.ev("title: person " + P.name + " · " + P.movies.size() + " movies, " + P.shows.size() + " shows, bio " + (P.bio.isEmpty() ? "none" : "yes"));
                panel.invalidate(); drawHint();
            } });
        } }, "title-person").start();
    }
    private void stopTrailerKeepTimer() { if (trFull) trailerExit(); else if (tp != null) tp.stop(); }
    private void closePerson() { person = null; pGen++; panel.invalidate(); drawHint(); scheduleTrailer(); }
    private void personKey(int c, boolean ok) {
        Person P = person;
        if (P.bioOpen) {
            if (c == KeyEvent.KEYCODE_BACK || c == KeyEvent.KEYCODE_ESCAPE || ok) P.bioOpen = false;
            else if (c == KeyEvent.KEYCODE_DPAD_DOWN) P.bioScroll += 6 * R;
            else if (c == KeyEvent.KEYCODE_DPAD_UP) P.bioScroll = Math.max(0, P.bioScroll - 6 * R);
            return;
        }
        if (c == KeyEvent.KEYCODE_BACK || c == KeyEvent.KEYCODE_ESCAPE) { closePerson(); return; }
        List<String> zs = P.zones();
        int zi = Math.max(0, zs.indexOf(P.zone));
        if (c == KeyEvent.KEYCODE_DPAD_DOWN && zi < zs.size() - 1) P.zone = zs.get(zi + 1);
        else if (c == KeyEvent.KEYCODE_DPAD_UP && zi > 0) P.zone = zs.get(zi - 1);
        else if (P.zone.equals("bio")) { if (ok && P.bioLong) { P.bioOpen = true; P.bioScroll = 0; } }
        else {
            RowState r = P.rs(P.zone); List<JSONObject> l = P.list(P.zone);
            if (c == KeyEvent.KEYCODE_DPAD_LEFT) r.sel = Math.max(0, r.sel - 1);
            else if (c == KeyEvent.KEYCODE_DPAD_RIGHT) r.sel = Math.min(l.size() - 1, r.sel + 1);
            else if (ok && r.sel < l.size()) openFromPerson(l.get(r.sel));
        }
    }
    private void openFromPerson(final JSONObject m) {
        final Person P = person;
        String id = m.optString("id");
        if (id.startsWith("tt")) {
            try {
                JSONObject mark = new JSONObject().put("_title", item);
                backTo.put(mark, P); history.add(mark);
            } catch (Exception ignored) { }
            openInner(m);
            return;
        }
        // (from TMDB without an IMDb id yet: one question first)
        if (!id.startsWith("tmdb:")) return;
        h.toast("Opening " + m.optString("name") + "…");
        final String[] parts = id.split(":");
        final int g = pGen;
        new Thread(new Runnable() { @Override public void run() {
            String im = "";
            try { String r = Feeds.tmdbRaw(h, "/" + parts[1] + "/" + parts[2] + "/external_ids"); if (r != null) im = new JSONObject(r).optString("imdb_id"); } catch (Exception ignored) { }
            final String fim = im;
            post(new Runnable() { @Override public void run() {
                if (g != pGen || person != P) return;
                if (!fim.startsWith("tt")) { h.toast("Couldn't open that one"); return; }
                try {
                    m.put("id", fim);
                    m.put("logo", "https://images.metahub.space/logo/medium/" + fim + "/img");
                } catch (Exception ignored) { }
                openFromPerson(m);
            } });
        } }, "title-person-open").start();
    }
    private static final java.util.regex.Pattern SELF = java.util.regex.Pattern.compile("(?i)^(self|himself|herself|themselves|host|guest|narrator|various|\\s*)(\\s*\\(.*\\))?$");
    private static boolean talky(String genres) { String g = genres.toLowerCase(Locale.US); return g.contains("talk") || g.contains("news") || g.contains("award") || g.contains("game-show") || g.contains("game show"); }
    /** a film or show from Trakt, as the pages want it (null without an IMDb id: nothing could open it) */
    private static JSONObject traktTitle(JSONObject x, String type, String character, int eps) throws Exception {
        String im = x.optJSONObject("ids") == null ? "" : x.optJSONObject("ids").optString("imdb");
        if (!im.startsWith("tt")) return null;
        JSONObject m = new JSONObject().put("id", im).put("type", type).put("name", x.optString("title"))
                .put("year", x.optInt("year") > 0 ? String.valueOf(x.optInt("year")) : "")
                .put("poster", "https://images.metahub.space/poster/medium/" + im + "/img")
                .put("background", "https://images.metahub.space/background/medium/" + im + "/img")
                .put("logo", "https://images.metahub.space/logo/medium/" + im + "/img")
                .put("desc", x.optString("overview")).put("character", character).put("_eps", eps)
                .put("_votes", x.optInt("votes")).put("_date", x.optString("released", x.optString("first_aired")));
        if (x.optDouble("rating", 0) > 0) m.put("rating", String.format(Locale.US, "%.1f", x.optDouble("rating")));
        JSONArray gs = x.optJSONArray("genres");
        if (gs != null) { JSONArray cap = new JSONArray(); for (int k = 0; k < gs.length(); k++) { String s0 = gs.optString(k); cap.put(s0.isEmpty() ? s0 : Character.toUpperCase(s0.charAt(0)) + s0.substring(1)); } m.put("genres", cap); m.put("_g", gs.join(",")); }
        return m;
    }
    private static String firstOf(JSONObject x) {
        String ch = x.optString("character");
        JSONArray chs = x.optJSONArray("characters");
        if (ch.isEmpty() && chs != null && chs.length() > 0) ch = chs.optString(0);
        return ch;
    }
    /** on the person's thread: everything their page shows */
    private void loadPerson(Person P) throws Exception {
        Trakt t = h.act.trakt;
        String id = P.who.optString("pid"), tmdbId = P.who.optString("tmdbPid");
        List<JSONObject> mv = new ArrayList<>(), sh = new ArrayList<>();
        if (t != null) {
            if (id.isEmpty()) {         // (a cast from the details: find them by name)
                String s0 = t.pub("/search/person?query=" + android.net.Uri.encode(P.name) + "&limit=1");
                JSONArray a = s0 == null ? null : new JSONArray(s0);
                JSONObject pe = a != null && a.length() > 0 && a.optJSONObject(0) != null ? a.optJSONObject(0).optJSONObject("person") : null;
                if (pe != null && pe.optString("name").equalsIgnoreCase(P.name) && pe.optJSONObject("ids") != null) {
                    id = pe.optJSONObject("ids").optString("slug", pe.optJSONObject("ids").optString("trakt"));
                    if (tmdbId.isEmpty()) tmdbId = pe.optJSONObject("ids").optString("tmdb");
                }
            }
            if (!id.isEmpty()) {
                String ps = t.pub("/people/" + id + "?extended=full");
                if (ps != null) {
                    JSONObject o = new JSONObject(ps);
                    P.bio = o.optString("biography").trim(); P.born = o.optString("birthday"); P.died = o.optString("death");
                    P.place = o.optString("birthplace"); P.dept = o.optString("known_for_department");
                    if (tmdbId.isEmpty() && o.optJSONObject("ids") != null) tmdbId = o.optJSONObject("ids").optString("tmdb");
                }
                String ms = t.pub("/people/" + id + "/movies?extended=full");
                if (ms != null) {
                    JSONObject o = new JSONObject(ms);
                    JSONArray a = o.optJSONArray("cast");
                    if (a != null) for (int i = 0; i < a.length(); i++) { JSONObject x = a.optJSONObject(i); JSONObject f = x == null || x.optJSONObject("movie") == null ? null : traktTitle(x.optJSONObject("movie"), "movie", firstOf(x), 0); if (f != null) mv.add(f); }
                    addCrew(o.optJSONObject("crew"), "movie", mv);
                }
                String ss = t.pub("/people/" + id + "/shows?extended=full");
                if (ss != null) {
                    JSONObject o = new JSONObject(ss);
                    JSONArray a = o.optJSONArray("cast");
                    if (a != null) for (int i = 0; i < a.length(); i++) { JSONObject x = a.optJSONObject(i); JSONObject f = x == null || x.optJSONObject("show") == null ? null : traktTitle(x.optJSONObject("show"), "series", firstOf(x), x.optInt("episode_count")); if (f != null) sh.add(f); }
                    addCrew(o.optJSONObject("crew"), "series", sh);
                }
            }
        }
        // TMDB: a biography Trakt didn't have, or everything when Trakt had nothing
        if (Feeds.tmdbOn(h) && (P.bio.isEmpty() || mv.isEmpty() && sh.isEmpty())) {
            if (tmdbId.isEmpty()) {
                String sr = Feeds.tmdbRaw(h, "/search/person?query=" + android.net.Uri.encode(P.name));
                JSONArray a = sr == null ? null : new JSONObject(sr).optJSONArray("results");
                if (a != null && a.length() > 0 && a.optJSONObject(0).optString("name").equalsIgnoreCase(P.name)) tmdbId = String.valueOf(a.optJSONObject(0).optInt("id"));
            }
            String pr = tmdbId.isEmpty() ? null : Feeds.tmdbRaw(h, "/person/" + tmdbId + "?append_to_response=combined_credits");
            if (pr != null) {
                JSONObject o = new JSONObject(pr);
                if (P.bio.isEmpty()) P.bio = o.optString("biography").trim();
                if (P.born.isEmpty()) P.born = o.optString("birthday").replace("null", "");
                if (P.died.isEmpty()) P.died = o.optString("deathday").replace("null", "");
                if (P.place.isEmpty()) P.place = o.optString("place_of_birth").replace("null", "");
                if (P.dept.isEmpty()) P.dept = o.optString("known_for_department");
                if (P.photo.isEmpty() && !o.optString("profile_path").isEmpty() && !o.optString("profile_path").equals("null")) P.photo = "https://image.tmdb.org/t/p/h632" + o.optString("profile_path");
                JSONObject cc = o.optJSONObject("combined_credits");
                JSONArray a = cc == null ? null : cc.optJSONArray("cast");
                if (mv.isEmpty() && sh.isEmpty() && a != null) for (int i = 0; i < a.length(); i++) {
                    JSONObject x = a.optJSONObject(i); if (x == null || x.optString("poster_path").isEmpty() || x.optString("poster_path").equals("null")) continue;
                    boolean tv = "tv".equals(x.optString("media_type"));
                    String date = tv ? x.optString("first_air_date") : x.optString("release_date");
                    JSONObject m = new JSONObject().put("id", "tmdb:" + (tv ? "tv" : "movie") + ":" + x.optInt("id")).put("type", tv ? "series" : "movie")
                            .put("name", tv ? x.optString("name") : x.optString("title")).put("year", date.length() >= 4 ? date.substring(0, 4) : "")
                            .put("poster", "https://image.tmdb.org/t/p/w342" + x.optString("poster_path"))
                            .put("background", x.optString("backdrop_path").isEmpty() || x.optString("backdrop_path").equals("null") ? "" : "https://image.tmdb.org/t/p/w1280" + x.optString("backdrop_path"))
                            .put("desc", x.optString("overview")).put("character", x.optString("character")).put("_eps", x.optInt("episode_count"))
                            .put("_votes", x.optInt("vote_count")).put("_date", date);
                    if (x.optDouble("vote_average", 0) > 0) m.put("rating", String.format(Locale.US, "%.1f", x.optDouble("vote_average")));
                    JSONArray gi = x.optJSONArray("genre_ids"); String gg = "";
                    if (gi != null) for (int k = 0; k < gi.length(); k++) { int gid = gi.optInt(k); if (gid == 10767) gg += "talk,"; if (gid == 10763) gg += "news,"; }
                    m.put("_g", gg);
                    (tv ? sh : mv).add(m);
                }
            }
        }
        // Wikipedia's summary, when no one else has a biography
        if (P.bio.isEmpty()) try {
            byte[] d = HomeImages.http("https://en.wikipedia.org/api/rest_v1/page/summary/" + android.net.Uri.encode(P.name.replace(' ', '_')), null);
            if (d != null) {
                JSONObject o = new JSONObject(new String(d, "UTF-8"));
                String what = o.optString("description").toLowerCase(Locale.US);
                if (!"disambiguation".equals(o.optString("type")) && (what.contains("act") || what.contains("direct") || what.contains("comedian") || what.contains("film") || what.contains("writer") || what.contains("presenter") || what.contains("singer") || what.contains("musician")))
                    P.bio = o.optString("extract").trim();
            }
        } catch (Exception ignored) { }
        // one of each (someone with two parts in one film), the parts joined
        mv = dedupe(mv); sh = dedupe(sh);
        Comparator<JSONObject> newest = new Comparator<JSONObject>() { @Override public int compare(JSONObject a, JSONObject b) { return dateKey(b).compareTo(dateKey(a)); } };
        Collections.sort(mv, newest); Collections.sort(sh, newest);
        // what they're known for: the most watched, leaving out talk shows, appearances as themselves and one-off episodes
        List<JSONObject> k = new ArrayList<>();
        for (JSONObject m : mv) if (!SELF.matcher(m.optString("character")).matches()) k.add(m);
        for (JSONObject m : sh) if (!SELF.matcher(m.optString("character")).matches() && !talky(m.optString("_g")) && (m.optInt("_eps") == 0 || m.optInt("_eps") >= 3)) k.add(m);
        Collections.sort(k, new Comparator<JSONObject>() { @Override public int compare(JSONObject a, JSONObject b) { return b.optInt("_votes") - a.optInt("_votes"); } });
        List<JSONObject> shows = new ArrayList<>();
        for (JSONObject m : sh) if (!talky(m.optString("_g"))) shows.add(m);
        final List<JSONObject> fk = new ArrayList<>(k.subList(0, Math.min(12, k.size()))), fm = new ArrayList<>(mv.subList(0, Math.min(60, mv.size()))), fs = new ArrayList<>(shows.subList(0, Math.min(40, shows.size())));
        P.known.addAll(fk); P.movies.addAll(fm); P.shows.addAll(fs);
        P.counts = mv.size() + (mv.size() == 1 ? " movie" : " movies") + (shows.isEmpty() ? "" : "   ·   " + shows.size() + (shows.size() == 1 ? " show" : " shows"));
    }
    private static void addCrew(JSONObject crew, String type, List<JSONObject> into) throws Exception {
        if (crew == null) return;
        String[] depts = { "directing", "writing", "production", "creator" };
        for (String d : depts) {
            JSONArray a = crew.optJSONArray(d); if (a == null) continue;
            for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.optJSONObject(i); if (x == null) continue;
                JSONObject t = x.optJSONObject(type.equals("movie") ? "movie" : "show"); if (t == null) continue;
                String job = x.optString("job"); JSONArray jobs = x.optJSONArray("jobs"); if (job.isEmpty() && jobs != null && jobs.length() > 0) job = jobs.optString(0);
                if (!d.equals("directing") && !job.equals("Creator") && !job.equals("Screenplay") && !job.equals("Writer")) continue;
                JSONObject f = traktTitle(t, type, job, 0); if (f != null) { f.put("_crew", true); into.add(f); }
            }
        }
    }
    private static List<JSONObject> dedupe(List<JSONObject> l) {
        LinkedHashMap<String, JSONObject> m = new LinkedHashMap<>();
        for (JSONObject x : l) {
            JSONObject o = m.get(x.optString("id"));
            if (o == null) { m.put(x.optString("id"), x); continue; }
            String a = o.optString("character"), b = x.optString("character");
            try { if (!b.isEmpty() && !a.contains(b)) o.put("character", a.isEmpty() ? b : a + " / " + b); } catch (Exception ignored) { }
        }
        return new ArrayList<>(m.values());
    }
    private static String dateKey(JSONObject m) {
        String d = m.optString("_date");
        if (d.length() >= 10) return d.substring(0, 10);
        String y = m.optString("year");
        return y.length() >= 4 ? y.substring(0, 4) + "-12-31" : "0000";
    }
    private static String today() { return new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date()); }
    /** "Born April 3, 1969 (57)" · "1931 – 2016 (aged 84)" */
    private static String lifeLine(Person P) {
        try {
            java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US), out = new java.text.SimpleDateFormat("MMMM d, yyyy", Locale.US);
            if (P.born.length() < 10) return "";
            java.util.Date b = in.parse(P.born.substring(0, 10));
            java.util.Date e = P.died.length() >= 10 ? in.parse(P.died.substring(0, 10)) : new java.util.Date();
            java.util.Calendar cb = java.util.Calendar.getInstance(), ce = java.util.Calendar.getInstance(); cb.setTime(b); ce.setTime(e);
            int age = ce.get(java.util.Calendar.YEAR) - cb.get(java.util.Calendar.YEAR);
            if (ce.get(java.util.Calendar.DAY_OF_YEAR) < cb.get(java.util.Calendar.DAY_OF_YEAR)) age--;
            if (P.died.length() >= 10) return "Born " + out.format(b) + "   ·   Died " + out.format(e) + " (aged " + age + ")";
            return "Born " + out.format(b) + " (" + age + ")";
        } catch (Exception ex) { return ""; }
    }

    // ================================================================== keys

    private boolean okHeld, okHeldFired;
    boolean key(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (trFull) { trailerExit(); return true; }
            scheduleTrailer();
        }
        int kc = e.getKeyCode();
        boolean okKey = kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER || kc == KeyEvent.KEYCODE_BUTTON_A;
        boolean epZone = series && (zone.equals("episodes") || zone.equals("seasons")) && reviewOpen < 0;
        if (kc == KeyEvent.KEYCODE_MENU && epZone) { if (e.getAction() == KeyEvent.ACTION_DOWN) watchMenu(); return true; }
        /* On an episode or a season, OK acts when it's let go, so that holding it can open the watched menu */
        if (okKey && epZone) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) { okHeld = true; okHeldFired = false; return true; }
                if (okHeld && !okHeldFired && e.getRepeatCount() >= 2) { okHeldFired = true; watchMenu(); }
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_UP) {
                boolean fire = okHeld && !okHeldFired;
                okHeld = false;
                if (!fire) return true;
                KeyEvent down = new KeyEvent(e.getDownTime(), e.getEventTime(), KeyEvent.ACTION_DOWN, kc, 0);
                boolean r = key0(down);
                updateEpBg();
                return r;
            }
        }
        boolean r = key0(e);
        updateEpBg();
        return r;
    }
    private boolean key0(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int c = e.getKeyCode();
        boolean ok = c == KeyEvent.KEYCODE_DPAD_CENTER || c == KeyEvent.KEYCODE_ENTER || c == KeyEvent.KEYCODE_NUMPAD_ENTER || c == KeyEvent.KEYCODE_BUTTON_A;
        if (person != null) { personKey(c, ok); panel.invalidate(); drawHint(); return true; }
        // a review open in full: ▲ ▼ read on, ◀ ▶ the next one, Back or OK closes it
        if (reviewOpen >= 0) {
            if (c == KeyEvent.KEYCODE_BACK || c == KeyEvent.KEYCODE_ESCAPE || ok) reviewOpen = -1;
            else if (c == KeyEvent.KEYCODE_DPAD_DOWN) reviewScroll += 6 * R;
            else if (c == KeyEvent.KEYCODE_DPAD_UP) reviewScroll = Math.max(0, reviewScroll - 6 * R);
            else if (c == KeyEvent.KEYCODE_DPAD_LEFT && reviewOpen > 0) { reviewOpen--; reviewScroll = 0; rs("reviews").sel = reviewOpen; }
            else if (c == KeyEvent.KEYCODE_DPAD_RIGHT && reviewOpen < reviews.size() - 1) { reviewOpen++; reviewScroll = 0; rs("reviews").sel = reviewOpen; }
            panel.invalidate(); drawHint();
            return true;
        }
        if (c == KeyEvent.KEYCODE_BACK || c == KeyEvent.KEYCODE_ESCAPE) {
            if (zone.equals("chips")) zone = "streams";
            else if (zone.equals("streams")) { zone = series ? "episodes" : "buttons"; pendingBest = false; binge = null; removeCallbacks(bestWait); }
            else if (!history.isEmpty()) { backInHistory(); return true; }
            else { close(); return true; }
        } else if (zone.equals("chips")) {
            List<String> ch = chipNames();
            if (c == KeyEvent.KEYCODE_DPAD_LEFT) chipSel = Math.max(0, chipSel - 1);
            else if (c == KeyEvent.KEYCODE_DPAD_RIGHT) chipSel = Math.min(ch.size() - 1, chipSel + 1);
            else if (c == KeyEvent.KEYCODE_DPAD_DOWN) zone = "streams";
            else if (ok) { filter = chipSel <= 0 || chipSel >= ch.size() ? null : ch.get(chipSel); strSel = 0; panel.resetStr(); zone = "streams"; }
        } else if (zone.equals("streams")) {
            List<JSONObject> l = shown();
            if (c == KeyEvent.KEYCODE_DPAD_UP) { if (strSel > 0) strSel--; else if (chipNames().size() > 2) zone = "chips"; }
            else if (c == KeyEvent.KEYCODE_DPAD_DOWN) { if (strSel < l.size() - 1) strSel++; }
            else if (ok && strSel < l.size()) { autoTries = 0; JSONObject x = l.get(strSel); filter = null; strSel = streams.indexOf(x); play(x); }
            panel.revealStr();
        } else if (c == KeyEvent.KEYCODE_DPAD_DOWN && !zone.equals("seasons")) { moveZone(1); }
        else if (c == KeyEvent.KEYCODE_DPAD_UP && !zone.equals("episodes")) { moveZone(-1); }
        else if (zone.equals("buttons")) {
            List<String> bs = buttons();
            if (c == KeyEvent.KEYCODE_DPAD_LEFT) btnSel = Math.max(0, btnSel - 1);
            else if (c == KeyEvent.KEYCODE_DPAD_RIGHT) btnSel = Math.min(bs.size() - 1, btnSel + 1);
            else if (ok && btnSel < bs.size()) {
                String b = bs.get(btnSel);
                if (b.equals("play")) playBest();
                else if (b.equals("streams")) { String t = target(); if (t.isEmpty()) h.toast("Still loading the episodes…"); else { if (!t.equals(streamsFor)) askStreams(t); openStreams(); } }
                else if (b.equals("trailer")) { stopTrailer(); h.toast("Opening the trailer…"); h.bridge.openTrailer(trailerId()); }
                else if (b.equals("list")) toggleList();
                else if (b.equals("watched")) { if (series) watchMenu(); else toggleWatched(); }
            }
        } else if (zone.equals("seasons")) {
            int i = seasons.indexOf(season);
            if (c == KeyEvent.KEYCODE_DPAD_LEFT && i > 0) { pickSeason(seasons.get(i - 1)); panel.revealEp(); }
            else if (c == KeyEvent.KEYCODE_DPAD_RIGHT && i < seasons.size() - 1) { pickSeason(seasons.get(i + 1)); panel.revealEp(); }
            else if (c == KeyEvent.KEYCODE_DPAD_DOWN || ok) { if (!eps.isEmpty()) zone = "episodes"; }
        } else if (zone.equals("episodes")) {      // a row of big pictures
            if (c == KeyEvent.KEYCODE_DPAD_LEFT) { if (epSel > 0) epSel--; }
            else if (c == KeyEvent.KEYCODE_DPAD_RIGHT) { if (epSel < eps.size() - 1) epSel++; }
            else if (c == KeyEvent.KEYCODE_DPAD_UP) zone = seasons.isEmpty() ? "buttons" : "seasons";
            else if (ok && epSel < eps.size()) {
                String id = eps.get(epSel).optString("id");
                if (!id.equals(streamsFor) || streams.isEmpty() && !asked.containsValue("…")) askStreams(id);
                openStreams();
            }
            panel.revealEp();
        } else if (zone.equals("cast")) {
            if (c == KeyEvent.KEYCODE_DPAD_LEFT) castSel = Math.max(0, castSel - 1);
            else if (c == KeyEvent.KEYCODE_DPAD_RIGHT) castSel = Math.min(cast().size() - 1, castSel + 1);
            else if (ok && castSel < cast().size()) { openPerson(cast().get(castSel)); return true; }
            panel.revealCast();
        } else if (zone.equals("videos") || zone.equals("similar") || zone.equals("reviews")) {
            RowState r = rs(zone);
            int n = zone.equals("videos") ? videos().size() : zone.equals("similar") ? similar.size() : reviews.size();
            if (c == KeyEvent.KEYCODE_DPAD_LEFT) r.sel = Math.max(0, r.sel - 1);
            else if (c == KeyEvent.KEYCODE_DPAD_RIGHT) r.sel = Math.min(n - 1, r.sel + 1);
            else if (ok && r.sel < n) {
                if (zone.equals("videos")) { h.toast("Opening on YouTube…"); h.bridge.openTrailer(videos().get(r.sel).optString("yt")); }
                else if (zone.equals("similar")) { history.add(item); openInner(similar.get(r.sel)); return true; }
                else if (zone.equals("reviews")) { reviewOpen = r.sel; reviewScroll = 0; }
            }
        }
        panel.invalidate(); drawHint();
        return true;
    }
    /* a finger (phones): what's under it gets chosen, the way the remote would choose it. Returns "ok" when
       OK should then act on it, "done" when choosing it was all (a season, a cast member), null for nothing there. */
    String focusAt(float x, float y) {
        if (trFull) { trailerExit(); return "done"; }
        scheduleTrailer();
        if (reviewOpen >= 0) { reviewOpen = -1; panel.invalidate(); drawHint(); return "done"; }
        int[] a = new int[2], b = new int[2];
        panel.getLocationInWindow(a); h.getLocationInWindow(b);
        float lx = x - (a[0] - b[0]), ly = y - (a[1] - b[1]);
        boolean st = streamsScreen();
        for (int k = panel.hitR.size() - 1; k >= 0; k--) {
            float[] r = panel.hitR.get(k);
            String z = panel.hitZ.get(k);
            boolean sz = z.equals("streams") || z.equals("chips");
            if (sz != st) continue;
            float sl = .4f * R;
            if (lx < r[0] - sl || lx > r[2] + sl || ly < r[1] - sl || ly > r[3] + sl) continue;
            int i = panel.hitI.get(k);
            if (person != null) {
                if (!z.startsWith("p:")) continue;
                person.bioOpen = false;
                person.zone = z.substring(2); if (!person.zone.equals("bio")) person.rs(person.zone).sel = i;
                panel.invalidate(); drawHint();
                return "ok";
            }
            switch (z) {
                case "buttons": zone = z; btnSel = i; break;
                case "season": zone = "seasons"; if (i != season) { pickSeason(i); panel.revealEp(); } panel.invalidate(); drawHint(); updateEpBg(); return "done";
                case "episodes": zone = z; epSel = i; panel.revealEp(); break;
                case "cast": zone = z; castSel = i; panel.revealCast(); break;
                case "chips": zone = z; chipSel = i; break;
                case "streams": zone = z; strSel = i; break;
                default: zone = z; rs(z).sel = i; break;
            }
            panel.invalidate(); drawHint(); updateEpBg();
            return "ok";
        }
        return null;
    }
    /** the streams screen, over the title */
    private void openStreams() {
        if (!series && !item.optString("id").equals(streamsFor)) askStreams(item.optString("id"));
        zone = "streams"; filter = null; chipSel = 0; strSel = 0; panel.resetStr();
        panel.revealStr(); panel.invalidate(); drawHint();
    }
    private int bestIndex() {
        // the next episode, carrying on: the same source as the last one (its "binge group", or the same addon and quality)
        if (binge != null) {
            String bg = bingeGroup(binge);
            if (!bg.isEmpty()) for (int i = 0; i < streams.size(); i++) if (!streams.get(i).optBoolean("_failed") && bg.equals(bingeGroup(streams.get(i)))) return i;
            List<String> q = badges(text(binge));
            for (int i = 0; i < streams.size(); i++) { JSONObject x = streams.get(i); if (!x.optBoolean("_failed") && x.optString("_addon").equals(binge.optString("_addon")) && badges(text(x)).equals(q)) return i; }
        }
        for (int i = 0; i < streams.size(); i++) if (!streams.get(i).optBoolean("_failed")) return i;
        return -1;
    }
    private static String bingeGroup(JSONObject s) { JSONObject bh = s.optJSONObject("behaviorHints"); return bh == null ? "" : bh.optString("bingeGroup"); }
    /** the stream the last episode played from (the next one picks the same source), or null */
    private JSONObject binge;
    /** Play: the best stream, straight away (waiting a few seconds for the addons if they're still answering) */
    /** what Play plays: the movie, or the show's next episode ("" while the episodes load) */
    private String target() { if (!series) return item.optString("id"); JSONObject u = upNext(); return u == null ? "" : u.optString("id"); }
    /** a show's next episode: the one from Continue watching, the one you're partway through, the one after the last you finished, or the first */
    private JSONObject upNext() {
        if (!series || allEps.isEmpty()) return null;
        if (!wantVideo.isEmpty()) for (JSONObject e : allEps) if (e.optString("id").equals(wantVideo)) return e;
        JSONObject last = Progress.lastEpisode(getContext(), item.optString("id"));
        if (last != null) for (int i = 0; i < allEps.size(); i++) if (allEps.get(i).optString("id").equals(last.optString("id"))) {
            if (!Progress.done(last)) return allEps.get(i);
            for (int j = i + 1; j < allEps.size(); j++) if (allEps.get(j).optInt("season", 1) != 0) return allEps.get(j);
            return allEps.get(i);
        }
        for (JSONObject e : allEps) if (e.optInt("season", 1) != 0 && !Progress.watched(getContext(), e.optString("id"))) return e;
        return allEps.get(0);
    }
    /** shows the up-next episode in the row (so the player knows its name and what follows) */
    private void selectEpisode(JSONObject e) {
        int sn = e.optInt("season", 1);
        if (sn != season) pickSeason(sn);
        for (int i = 0; i < eps.size(); i++) if (eps.get(i).optString("id").equals(e.optString("id"))) epSel = i;
        panel.revealEp();
    }
    private List<String> buttons() {
        List<String> b = new ArrayList<>();
        b.add("play"); b.add("streams");
        if (!trailerId().isEmpty()) b.add("trailer");
        b.add("list");
        b.add("watched");                 // (a show: a menu — this season, the whole show…)
        return b;
    }
    /** the sections, top to bottom, that the remote moves between */
    private List<String> zones() {
        List<String> z = new ArrayList<>();
        z.add("buttons");
        if (series && !seasons.isEmpty()) { z.add("seasons"); z.add("episodes"); }
        if (!cast().isEmpty()) z.add("cast");
        if (!videos().isEmpty()) z.add("videos");
        if (!similar.isEmpty()) z.add("similar");
        if (!reviews.isEmpty()) z.add("reviews");
        if (full != null && !about().isEmpty()) z.add("about");
        return z;
    }
    private void moveZone(int d) {
        List<String> z = zones();
        int i = z.indexOf(zone);
        if (i < 0) return;
        if (d > 0 && zone.equals("seasons")) { zone = "episodes"; return; }
        int j = i + d;
        if (d < 0 && j >= 0 && z.get(j).equals("episodes") && i > j) { zone = "episodes"; return; }
        if (j >= 0 && j < z.size()) zone = z.get(j);
    }
    // My List (shared with Movies & TV and the page)
    private boolean inList() {
        try {
            JSONArray a = new JSONArray(h.prefs.getString("ui.mylist", "[]"));
            for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null && o.optString("id").equals(item.optString("id")) && o.optString("type").equals(item.optString("type"))) return true; }
        } catch (Exception ignored) { }
        return false;
    }
    private void toggleList() { h.toggleMyList(item); panel.invalidate(); }
    private boolean watchedMovie() { JSONObject p = Progress.get(getContext(), item.optString("id")); return (p != null && Progress.done(p)) || (p == null && Progress.watched(getContext(), item.optString("id"))); }
    private void toggleWatched() {
        String id = item.optString("id");
        boolean was = watchedMovie();
        if (was) { Progress.forget(getContext(), id); Progress.unmarkWatched(getContext(), id); }
        else try { Progress.put(getContext(), id, 1, 1, "", 0, 0, new JSONObject().put("title", item)); } catch (Exception ignored) { }
        if (h.act.trakt != null) h.act.trakt.history(!was, id);
        h.toast(was ? "Marked as not watched" : "Marked as watched");
        panel.invalidate();
    }
    // ---- a show: marking episodes, a season or the whole show watched (hold OK on an episode or season)
    private boolean epSeen(JSONObject e) { JSONObject p = Progress.get(getContext(), e.optString("id")); return (p != null && Progress.done(p)) || Progress.watched(getContext(), e.optString("id")); }
    private List<JSONObject> seasonEps(int s) { List<JSONObject> l = new ArrayList<>(); for (JSONObject e : allEps) if (e.optInt("season", -1) == s) l.add(e); return l; }
    private List<JSONObject> showEps() { List<JSONObject> l = new ArrayList<>(); for (JSONObject e : allEps) if (e.optInt("season") > 0 && aired(e)) l.add(e); return l; }
    private static boolean aired(JSONObject e) {
        String r = e.optString("released", e.optString("firstAired"));
        Long at = r.isEmpty() ? null : SportsData.parseDate(r);
        return at == null || at <= System.currentTimeMillis();
    }
    private boolean allSeen(List<JSONObject> l) { if (l.isEmpty()) return false; for (JSONObject e : l) if (aired(e) && !epSeen(e)) return false; return true; }
    private boolean showSeen() { return allSeen(showEps()); }
    private void markEps(List<JSONObject> l, boolean on, String what, boolean wholeShow) {
        List<String> ids = new ArrayList<>(); List<int[]> se = new ArrayList<>();
        for (JSONObject e : l) { if (on && !aired(e)) continue; ids.add(e.optString("id")); se.add(new int[] { e.optInt("season"), e.optInt("episode", e.optInt("number")) }); }
        if (ids.isEmpty()) return;
        Progress.setWatched(getContext(), ids, on);
        if (h.act.trakt != null) h.act.trakt.historyShow(on, item.optString("id").split(":")[0], wholeShow ? null : se);
        h.toast((on ? "Marked " : "Marked not watched: ") + what + (on ? " as watched" : ""));
        epBgFor = ""; updateEpBg();            // (its picture: sharp now, or blurred again)
        panel.invalidate(); drawHint();
    }
    private void watchMenu() {
        List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
        final JSONObject e = zone.equals("episodes") && epSel < eps.size() ? eps.get(epSel) : null;
        if (e != null) {
            final boolean w = epSeen(e);
            final int en = e.optInt("episode", e.optInt("number"));
            l.add(w ? "Mark this episode as not watched" : "Mark this episode as watched");
            a.add(new Runnable() { @Override public void run() { markEps(java.util.Collections.singletonList(e), !w, "S" + e.optInt("season") + " · E" + en, false); } });
            if (!w) {
                // everything up to here (a show you're catching up on)
                final List<JSONObject> upTo = new ArrayList<>();
                for (JSONObject x : allEps) { if (x.optInt("season") <= 0) continue; upTo.add(x); if (x.optString("id").equals(e.optString("id"))) break; }
                if (upTo.size() > 1) { l.add("Mark this and every episode before it as watched"); a.add(new Runnable() { @Override public void run() { markEps(upTo, true, "everything up to S" + e.optInt("season") + " · E" + en, false); } }); }
            }
        }
        final int s = season;
        final List<JSONObject> se = seasonEps(s);
        if (!se.isEmpty()) {
            final boolean sw = allSeen(se);
            final String sn = s == 0 ? "the specials" : "Season " + s;
            l.add(sw ? "Mark " + sn + " as not watched" : "Mark " + sn + " as watched");
            a.add(new Runnable() { @Override public void run() { markEps(se, !sw, sn, false); } });
        }
        final List<JSONObject> all = showEps();
        if (!all.isEmpty()) {
            final boolean aw = allSeen(all);
            l.add(aw ? "Mark the whole show as not watched" : "Mark the whole show as watched");
            a.add(new Runnable() { @Override public void run() { markEps(all, !aw, "the whole show", true); } });
        }
        if (l.isEmpty()) return;
        h.showMenu(l, a, null, 1f, item.optString("name"));
    }
    /** trailers and clips (YouTube) */
    private List<JSONObject> videoCache; private JSONObject videoFor;
    private List<JSONObject> videos() {
        if (videoFor == full && videoCache != null) return videoCache;
        List<JSONObject> l = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        try {
            JSONArray ts = full == null ? null : full.optJSONArray("trailerStreams");
            if (ts != null) for (int i = 0; i < ts.length(); i++) { JSONObject x = ts.optJSONObject(i); if (x != null && !x.optString("ytId").isEmpty() && seen.add(x.optString("ytId"))) l.add(new JSONObject().put("yt", x.optString("ytId")).put("title", x.optString("title", "Trailer"))); }
            JSONArray t = full == null ? null : full.optJSONArray("trailers");
            if (t != null) for (int i = 0; i < t.length(); i++) { JSONObject x = t.optJSONObject(i); if (x != null && !x.optString("source").isEmpty() && seen.add(x.optString("source"))) l.add(new JSONObject().put("yt", x.optString("source")).put("title", x.optString("type", "Trailer"))); }
            if (l.isEmpty() && !item.optString("yt").isEmpty()) l.add(new JSONObject().put("yt", item.optString("yt")).put("title", "Trailer"));
        } catch (Exception ignored) { }
        videoFor = full; videoCache = l;
        return l;
    }
    private static int photos(List<JSONObject> l) { int n = 0; for (JSONObject x : l) if (!x.optString("photo").isEmpty()) n++; return n; }
    /** "Released · June 3, 2022" and the like */
    private List<String[]> about() {
        List<String[]> l = new ArrayList<>();
        JSONObject f = full == null ? item : full;
        String rel = f.optString("released");
        if (rel.length() >= 10) try { l.add(new String[] { series ? "First aired" : "Released", new java.text.SimpleDateFormat("MMMM d, yyyy", Locale.US).format(new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(rel.substring(0, 10))) }); } catch (Exception ignored) { }
        if (!f.optString("runtime").isEmpty()) l.add(new String[] { series ? "Episode length" : "Runtime", f.optString("runtime") });
        if (series && !allEps.isEmpty()) { int n = 0; for (JSONObject e : allEps) if (e.optInt("season", 1) != 0) n++; int sc = 0; for (int s0 : seasons) if (s0 != 0) sc++; l.add(new String[] { "Seasons", sc + " season" + (sc == 1 ? "" : "s") + "  ·  " + n + " episodes" }); }
        JSONArray g = f.optJSONArray("genres");
        if (g != null && g.length() > 0) { List<String> x = new ArrayList<>(); for (int i = 0; i < g.length(); i++) x.add(g.optString(i)); l.add(new String[] { "Genres", TextUtils.join(", ", x) }); }
        String d = panel.namesAll(f.opt("director")), w = panel.namesAll(f.opt("writer"));
        if (!d.isEmpty()) l.add(new String[] { "Director", d });
        if (!w.isEmpty()) l.add(new String[] { "Writers", w });
        if (!f.optString("country").isEmpty()) l.add(new String[] { "Country", f.optString("country") });
        if (!f.optString("language").isEmpty()) l.add(new String[] { "Language", f.optString("language") });
        if (!f.optString("status").isEmpty()) l.add(new String[] { "Status", f.optString("status") });
        if (!f.optString("imdbRating").isEmpty()) l.add(new String[] { "IMDb", f.optString("imdbRating") + " / 10" });
        if (traktPct > 0) l.add(new String[] { "Trakt", traktPct + "%  ·  " + String.format(Locale.US, "%,d", traktVotes) + " votes" });
        if (!f.optString("awards").isEmpty()) l.add(new String[] { "Awards", f.optString("awards") });
        return l;
    }
    /** Trakt's rating, related titles and reviews; without Trakt, more of the same genre from Cinemeta */
    private void loadExtras() {
        final int g = gen;
        final String id = item.optString("id"), type = item.optString("type");
        final String kind = series ? "shows" : "movies";
        if (!id.startsWith("tt")) return;
        new Thread(new Runnable() { @Override public void run() {
            int pct = 0, votes = 0;
            final List<JSONObject> sim = new ArrayList<>(), rev = new ArrayList<>(), tcast = new ArrayList<>();
            final Map<String, Double> rates = new java.util.HashMap<>();
            Trakt t = h.act.trakt;
            // a show's episodes' ratings (an idea from ARVIO's episode heatmap): one question for every season
            if (series) try {
                String ss = t == null ? null : t.pub("/shows/" + id + "/seasons?extended=full,episodes");
                if (ss != null) {
                    JSONArray a = new JSONArray(ss);
                    for (int i = 0; i < a.length(); i++) {
                        JSONArray es = a.optJSONObject(i) == null ? null : a.optJSONObject(i).optJSONArray("episodes");
                        if (es != null) for (int k = 0; k < es.length(); k++) {
                            JSONObject e = es.optJSONObject(k);
                            if (e != null && e.optInt("votes") >= 3 && e.optDouble("rating", 0) > 0) rates.put(e.optInt("season") + ":" + e.optInt("number"), e.optDouble("rating"));
                        }
                    }
                }
            } catch (Exception ignored) { }
            try {
                String r = t == null ? null : t.pub("/" + kind + "/" + id + "/ratings");
                if (r != null) { JSONObject o = new JSONObject(r); pct = (int) Math.round(o.optDouble("rating") * 10); votes = o.optInt("votes"); }
                String rl = t == null ? null : t.pub("/" + kind + "/" + id + "/related?limit=16&extended=full");
                if (rl != null) {
                    JSONArray a = new JSONArray(rl);
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject x = a.optJSONObject(i); if (x == null) continue;
                        String im = x.optJSONObject("ids") == null ? "" : x.optJSONObject("ids").optString("imdb");
                        if (!im.startsWith("tt")) continue;
                        JSONObject m = new JSONObject().put("id", im).put("type", type).put("name", x.optString("title"))
                                .put("year", x.optInt("year") > 0 ? String.valueOf(x.optInt("year")) : "")
                                .put("poster", "https://images.metahub.space/poster/medium/" + im + "/img")
                                .put("background", "https://images.metahub.space/background/medium/" + im + "/img")
                                .put("logo", "https://images.metahub.space/logo/medium/" + im + "/img")
                                .put("desc", x.optString("overview"));
                        if (x.optDouble("rating", 0) > 0) m.put("rating", String.format(Locale.US, "%.1f", x.optDouble("rating")));
                        JSONArray gs = x.optJSONArray("genres");
                        if (gs != null) { JSONArray cap = new JSONArray(); for (int k = 0; k < gs.length(); k++) { String s0 = gs.optString(k); cap.put(s0.isEmpty() ? s0 : Character.toUpperCase(s0.charAt(0)) + s0.substring(1)); } m.put("genres", cap); }
                        sim.add(m);
                    }
                }
                String pp = t == null ? null : t.pub("/" + kind + "/" + id + "/people?extended=images");
                if (pp != null) {
                    JSONArray a = new JSONObject(pp).optJSONArray("cast");
                    if (a != null) for (int i = 0; i < a.length() && tcast.size() < 24; i++) {
                        JSONObject x = a.optJSONObject(i); JSONObject pe = x == null ? null : x.optJSONObject("person");
                        if (pe == null || pe.optString("name").isEmpty()) continue;
                        String ch = x.optString("character");
                        JSONArray chs = x.optJSONArray("characters");
                        if (ch.isEmpty() && chs != null && chs.length() > 0) ch = chs.optString(0);
                        String photo = "";
                        JSONObject im = pe.optJSONObject("images");
                        JSONArray hs = im == null ? null : im.optJSONArray("headshot");
                        if (hs != null && hs.length() > 0 && !hs.optString(0).isEmpty()) { photo = hs.optString(0); if (!photo.startsWith("http")) photo = "https://" + photo; }
                        JSONObject pids = pe.optJSONObject("ids");
                        tcast.add(new JSONObject().put("name", pe.optString("name")).put("character", ch).put("photo", photo)
                                .put("pid", pids == null ? "" : pids.optString("slug", pids.optString("trakt"))).put("tmdbPid", pids == null ? "" : pids.optString("tmdb")));
                    }
                }
                String cm = t == null ? null : t.pub("/" + kind + "/" + id + "/comments/likes?limit=20");
                if (cm != null) {
                    JSONArray a = new JSONArray(cm);
                    for (int i = 0; i < a.length() && rev.size() < 10; i++) {
                        JSONObject x = a.optJSONObject(i);
                        if (x == null || x.optBoolean("spoiler") || x.optString("comment").length() < 40) continue;
                        rev.add(x);
                    }
                }
            } catch (Exception ignored) { }
            // no Trakt (or nothing related): popular titles in the same genre
            if (sim.isEmpty()) try {
                JSONObject f = full;
                JSONArray gs = f == null ? null : f.optJSONArray("genres");
                String gn = gs != null && gs.length() > 0 ? gs.optString(0) : "";
                byte[] d = HomeImages.http("https://v3-cinemeta.strem.io/catalog/" + type + "/top" + (gn.isEmpty() ? "" : "/genre=" + android.net.Uri.encode(gn)) + ".json", null);
                JSONArray metas = d == null ? null : new JSONObject(new String(d, "UTF-8")).optJSONArray("metas");
                if (metas != null) for (int i = 0; i < metas.length() && sim.size() < 16; i++) {
                    JSONObject x = metas.optJSONObject(i);
                    if (x == null || x.optString("id").equals(id)) continue;
                    JSONObject m = new JSONObject().put("id", x.optString("id")).put("type", type).put("name", x.optString("name"))
                            .put("poster", x.optString("poster")).put("background", x.optString("background")).put("logo", x.optString("logo"))
                            .put("year", x.optString("releaseInfo")).put("rating", x.optString("imdbRating")).put("desc", x.optString("description"));
                    if (x.optJSONArray("genres") != null) m.put("genres", x.optJSONArray("genres"));
                    sim.add(m);
                }
            } catch (Exception ignored) { }
            final int fp = pct, fv = votes;
            post(new Runnable() { @Override public void run() {
                if (g != gen) return;
                traktPct = fp; traktVotes = fv;
                epRating.clear(); epRating.putAll(rates);
                similar.clear(); similar.addAll(sim);
                reviews.clear(); reviews.addAll(rev);
                // Trakt's cast: use it when it has pictures, or when the details had no cast; photos it lacks come from the details
                List<JSONObject> own = new ArrayList<>(); castFor = null; castCache = null;
                if (!tcast.isEmpty()) {
                    own.addAll(cast());
                    boolean pics = false; for (JSONObject x : tcast) if (!x.optString("photo").isEmpty()) pics = true;
                    if (pics || own.isEmpty()) {
                        for (JSONObject x : tcast) if (x.optString("photo").isEmpty()) for (JSONObject o : own) if (o.optString("name").equalsIgnoreCase(x.optString("name")) && !o.optString("photo").isEmpty()) try { x.put("photo", o.optString("photo")); } catch (Exception ignored) { }
                        traktCast.clear(); traktCast.addAll(tcast);
                    }
                    castFor = null; castCache = null;
                }
                Diag.ev("title: cast " + cast().size() + " (" + (traktCast.isEmpty() ? "details" : "Trakt") + ", " + photos(cast()) + " with photos)");
                panel.invalidate();
            } });
        } }, "title-extras").start();
    }
    private String trailerId() {
        if (!item.optString("yt").isEmpty()) return item.optString("yt");
        JSONArray t = full == null ? null : full.optJSONArray("trailers");
        if (t != null) for (int i = 0; i < t.length(); i++) { JSONObject x = t.optJSONObject(i); if (x != null && !x.optString("source").isEmpty()) return x.optString("source"); }
        JSONArray ts = full == null ? null : full.optJSONArray("trailerStreams");
        if (ts != null) for (int i = 0; i < ts.length(); i++) { JSONObject x = ts.optJSONObject(i); if (x != null && !x.optString("ytId").isEmpty()) return x.optString("ytId"); }
        return "";
    }
    /** the cast: [{name, character, photo}] (photos when the details have them) */
    private List<JSONObject> castCache; private JSONObject castFor;
    private List<JSONObject> cast() {
        if (castFor == full && castCache != null) return castCache;
        List<JSONObject> l = new ArrayList<>();
        // Trakt's cast (with headshots) first; the details' own cast fills in
        if (!traktCast.isEmpty()) { l.addAll(traktCast); castFor = full; castCache = l; return l; }
        JSONObject ex = full == null ? null : full.optJSONObject("app_extras");
        JSONArray a = ex == null ? null : ex.optJSONArray("cast");
        if (a != null) for (int i = 0; i < a.length() && l.size() < 20; i++) { JSONObject x = a.optJSONObject(i); if (x != null && !x.optString("name").isEmpty()) l.add(x); }
        if (l.isEmpty() && full != null) {
            JSONArray n = full.optJSONArray("cast");
            if (n != null) for (int i = 0; i < n.length() && l.size() < 20; i++) try { l.add(new JSONObject().put("name", n.optString(i))); } catch (Exception ignored) { }
        }
        castFor = full; castCache = l;
        return l;
    }
    private void playBest() {
        String tgt = binge != null && !streamsFor.isEmpty() ? streamsFor : target();
        if (tgt.isEmpty()) { h.toast("Still loading the episodes…"); return; }
        if (series && binge == null) { JSONObject u = upNext(); if (u != null) selectEpisode(u); }
        if (!tgt.equals(streamsFor)) askStreams(tgt);
        boolean waiting = asked.containsValue("…");
        int b = bestIndex();
        if (b >= 0 && (!waiting || pendingBestWaited)) { pendingBest = false; pendingBestWaited = false; autoTries = 0; strSel = b; play(streams.get(b)); return; }
        if (b < 0 && !waiting) { pendingBest = false; binge = null; h.toast("No playable streams found for this"); return; }
        pendingBest = true; pendingBestWaited = false;
        removeCallbacks(bestWait); postDelayed(bestWait, 6000);
        panel.invalidate();
    }
    private boolean pendingBestWaited;
    private final Runnable bestWait = new Runnable() { @Override public void run() { if (pendingBest && shownNow()) { pendingBestWaited = true; playBest(); } } };
    /** (called as addons answer) */
    private void bestCheck() { if (pendingBest && !asked.containsValue("…")) { removeCallbacks(bestWait); playBest(); } }
    private void drawHint() {
        String resume = "";
        JSONObject p = Progress.get(getContext(), series ? (streamsScreen() ? videoId : epSel < eps.size() ? eps.get(epSel).optString("id") : "") : item.optString("id"));
        if (p != null && !Progress.done(p) && Progress.fraction(p) > 0) resume = "   ·   picks up at " + (p.optLong("dur") > 0 ? clock(p.optLong("pos")) : Math.round(p.optDouble("pct")) + "%");
        if (person != null) {
            String back = "Back · " + TextUtils.ellipsize(item.optString("name"), hint.getPaint(), 30 * R, TextUtils.TruncateAt.END);
            hint.setText(person.bioOpen ? "▲ ▼ read on   ·   OK or Back · close"
                    : person.zone.equals("bio") ? (person.bioLong ? "OK · the whole biography   ·   " : "") + "▼ their films and shows   ·   " + back
                    : "◀ ▶ choose   ·   OK · open   ·   ▲ ▼ more   ·   " + back);
            return;
        }
        if (reviewOpen >= 0) { hint.setText("▲ ▼ read on   ·   ◀ ▶ other reviews   ·   OK or Back · close"); return; }
        hint.setText(zone.equals("chips") ? "◀ ▶ addon   ·   OK · show only its streams   ·   ▼ the list"
                : zone.equals("streams") ? "▲ ▼ choose   ·   OK · play" + resume + (chipNames().size() > 2 ? "   ·   ▲ at the top · filter by addon" : "") + "   ·   Back · " + (series ? "episodes" : "back")
                : zone.equals("buttons") ? "◀ ▶ choose   ·   OK   ·   ▼ more   ·   Back · " + (history.isEmpty() ? "close" : "back")
                : zone.equals("cast") ? "◀ ▶ cast   ·   OK · their page   ·   ▲ ▼ more   ·   Back · close"
                : zone.equals("videos") ? "◀ ▶ videos   ·   OK · watch on YouTube   ·   ▲ ▼ more   ·   Back · close"
                : zone.equals("similar") ? "◀ ▶ titles   ·   OK · open   ·   ▲ ▼ more   ·   Back · " + (history.isEmpty() ? "close" : "back")
                : zone.equals("reviews") ? "◀ ▶ reviews   ·   OK · read it all   ·   ▲ ▼ more   ·   Back · close"
                : zone.equals("about") ? "▲ back up   ·   Back · close"
                : zone.equals("episodes") ? "◀ ▶ episodes   ·   OK · streams for this episode   ·   hold OK · watched / not watched   ·   Back · close" : "◀ ▶ season   ·   ▼ episodes   ·   hold OK · mark the season watched   ·   Back · close");
    }

    // ================================================================== playing

    private void playNow(JSONObject s) { play(s); }
    private void play(JSONObject s) {
        JSONObject ep = null;
        if (series) for (JSONObject e : allEps) if (e.optString("id").equals(videoId)) ep = e;
        String label = series && ep != null ? "S" + ep.optInt("season") + " · E" + ep.optInt("episode", ep.optInt("number")) + "  " + ep.optString("name", ep.optString("title")) : "";
        binge = null;
        // what comes after: the next episode, into the next season too (specials aren't "next")
        JSONObject next = null;
        if (series && ep != null) {
            int i = allEps.indexOf(ep);
            for (int j = i + 1; i >= 0 && j < allEps.size(); j++) {
                JSONObject c = allEps.get(j);
                if (c.optInt("season", 1) == 0 && ep.optInt("season", 1) != 0) continue;
                if (!released(c)) break;
                next = c; break;
            }
        }
        Diag.ev("vod: playing " + item.optString("name") + (label.isEmpty() ? "" : " " + label) + " from " + s.optString("_addon"));
        h.vodPlayer().play(this, item, videoId, label, s, next);
    }
    /** the player finished an episode and there's a next one: show it, asking for its streams */
    void afterEpisode(JSONObject next) { afterEpisode(next, null); }
    /** …and when the last one played to the end, the next one starts by itself from the same source (Back stops it) */
    void afterEpisode(JSONObject next, JSONObject from) {
        if (next == null) return;
        selectEpisode(next);
        askStreams(next.optString("id"));
        zone = "streams"; filter = null; chipSel = 0;
        if (from != null && h.S.optBoolean("autoNext", true)) {
            binge = from; autoTries = 0;
            pendingBest = true; pendingBestWaited = false;
            removeCallbacks(bestWait); postDelayed(bestWait, 8000);
        }
        panel.revealEp(); panel.revealStr(); panel.invalidate(); drawHint();
    }
    /** out already (no air date, or one that's passed) */
    private static boolean released(JSONObject e) {
        String r = e.optString("released", e.optString("firstAired"));
        if (r.length() < 10) return true;
        try { return new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(r.substring(0, 10)).getTime() <= System.currentTimeMillis(); } catch (Exception x) { return true; }
    }
    void refreshProgress() { panel.invalidate(); drawHint(); }
    /** a stream that wouldn't play: marked, and the next one picked out */
    void streamFailed(JSONObject s, String why, boolean decoder) {
        int i = streams.indexOf(s);
        if (i >= 0) try { s.put("_failed", true); } catch (Exception ignored) { }
        // the TV couldn't decode it: that kind of file goes lower next time
        String k = kind(s);
        if (decoder && !k.isEmpty()) h.prefs.edit().putInt("vodBad." + k, h.prefs.getInt("vodBad." + k, 0) + 1).apply();
        // the next best one that hasn't failed, played straight away (three goes, then you choose)
        JSONObject next = null;
        for (int j = Math.max(0, i + 1); j < streams.size(); j++) if (!streams.get(j).optBoolean("_failed")) { next = streams.get(j); strSel = j; break; }
        panel.invalidate(); drawHint();
        if (next != null && autoTries < 3) {
            autoTries++;
            List<String> b = badges(text(next));
            h.toast("That stream didn't play (" + why + ") — trying the next best" + (b.isEmpty() ? "" : ": " + TextUtils.join(" ", b)));
            playNow(next);
        } else { zone = "streams"; filter = null; panel.revealStr(); panel.invalidate(); drawHint(); h.toast("That stream didn't play: " + why + (next != null ? " — the next one is selected" : "")); }
    }
    private int autoTries;

    static String clock(long ms) { long s = ms / 1000; return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) : String.format(Locale.US, "%d:%02d", s / 60, s % 60); }

    // ================================================================== the lower half: seasons, episodes, streams

    // episode stills (Cinemeta's thumbnails), loaded as their rows come into view
    /* pictures on the page (episode stills, cast, posters, videos). A picture pushed out of the cache is fetched again when it's
       back on screen (it wasn't, which left blank episodes after scrolling); one that failed is tried again a little later. */
    private final android.util.LruCache<String, Bitmap> thumbs = new android.util.LruCache<String, Bitmap>((int) Math.min(32L << 20, Runtime.getRuntime().maxMemory() / 8)) {
        @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
        @Override protected void entryRemoved(boolean evicted, String k, Bitmap old, Bitmap nu) { thumbAsked.remove(k); if (old != nu) h.reclaim(old); }
    };
    private final java.util.Set<String> thumbAsked = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private final Map<String, long[]> thumbFailed = new java.util.HashMap<>();      // url → {tries, when}
    private static final java.util.Set<String> badHosts = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static final java.util.concurrent.ExecutorService PICS = java.util.concurrent.Executors.newFixedThreadPool(4);
    private Bitmap thumb(final String url, final int w, final int hh) { return thumb(url, null, w, hh); }
    /** a picture, with a second address to try if the first doesn't load */
    private Bitmap thumb(final String url, final String alt, final int w, final int hh) {
        if (url == null || url.isEmpty()) return alt == null ? null : thumb(alt, null, w, hh);
        Bitmap b = thumbs.get(url);
        if (b != null) return b;
        long[] f = thumbFailed.get(url);
        if (f != null) {
            if (alt != null && !alt.isEmpty() && f[0] >= 1) { Bitmap a = thumb(alt, null, w, hh); if (a != null) return a; }
            if (f[0] >= 3 || System.currentTimeMillis() - f[1] < 8000) return null;
        }
        if (!thumbAsked.add(url)) return null;
        final int g = gen;
        PICS.execute(new Runnable() { @Override public void run() {
            Bitmap x = null;
            String err = "";
            try { int hs = url.indexOf('#'); byte[] d = HomeImages.http(hs > 0 ? url.substring(0, hs) : url, null); if (d != null) x = HomeImages.decode(d, w, hh); else err = "no answer"; } catch (Throwable t) { err = String.valueOf(t.getMessage()); }
            if (x == null) { String host = android.net.Uri.parse(url).getHost(); if (badHosts.add(host)) Diag.ev("title: pictures from " + host + " didn't load (" + err + ")"); }
            final Bitmap fx = x;
            post(new Runnable() { @Override public void run() {
                thumbAsked.remove(url);
                if (fx != null) { thumbFailed.remove(url); if (g == gen) thumbs.put(url, fx); else h.reclaim(fx); }
                else { long[] o = thumbFailed.get(url); thumbFailed.put(url, new long[] { (o == null ? 0 : o[0]) + 1, System.currentTimeMillis() }); if (g == gen) postDelayed(new Runnable() { @Override public void run() { panel.invalidate(); } }, 8500); }
                if (g == gen) panel.invalidate();
            } });
        } });
        return null;
    }
    /** a picture blurred into a soft wash (made once, on the picture worker; null until it's ready) */
    private final java.util.Set<String> blurAsked = new java.util.HashSet<>();
    private Bitmap blurred(final String key, final Bitmap src) {
        final String k = "blur:" + key;
        Bitmap b = thumbs.get(k);
        if (b != null) return b;
        if (!blurAsked.add(k)) return null;
        final int g = gen;
        PICS.execute(new Runnable() { @Override public void run() {
            Bitmap x = null;
            try { x = Blur.glass(src, 3, 5, 1.6f, .92f); } catch (Throwable ignored) { }
            final Bitmap fx = x;
            post(new Runnable() { @Override public void run() {
                blurAsked.remove(k);
                if (fx != null && g == gen) { thumbs.put(k, fx); panel.invalidate(); } else h.reclaim(fx);
            } });
        } });
        return null;
    }
    /** an episode's still: the details' own, or Metahub's (which has most episodes) */
    private static String epStill(JSONObject e) {
        String id = e.optString("id");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(tt\\d+):(\\d+):(\\d+)$").matcher(id);
        return m.find() ? "https://episodes.metahub.space/" + m.group(1) + "/" + m.group(2) + "/" + m.group(3) + "/w780.jpg" : null;
    }

    /** what the file is, in plain words: "Remux · HEVC · Atmos 7.1" */
    static String summary(String u) {
        List<String> p = new ArrayList<>();
        p.add(u.contains("REMUX") ? "Remux" : u.matches("(?s).*\\b(BLURAY|BLU-RAY|BDRIP|BRRIP)\\b.*") ? "Blu-ray" : u.matches("(?s).*\\bWEB-?DL\\b.*") ? "WEB-DL" : u.matches("(?s).*\\bWEB-?RIP\\b.*") ? "WEBRip" : u.contains("WEB") ? "WEB" : u.contains("HDTV") ? "HDTV" : u.matches("(?s).*\\b(CAM|HDCAM|TS|TELESYNC|HDTS)\\b.*") ? "Camera recording" : "");
        p.add(u.matches("(?s).*\\b(HEVC|H\\.?265|X265)\\b.*") ? "HEVC" : u.matches("(?s).*\\bAV1\\b.*") ? "AV1" : u.matches("(?s).*\\b(H\\.?264|X264|AVC)\\b.*") ? "H.264" : "");
        String au = u.contains("ATMOS") ? "Atmos" : u.contains("TRUEHD") ? "TrueHD" : u.matches("(?s).*DTS-?HD.*") ? "DTS-HD" : u.matches("(?s).*\\bDTS(:?X)?\\b.*") ? "DTS" : u.matches("(?s).*\\b(DDP|DD\\+|EAC3|E-AC-3)(\\d|\\b).*") ? "Dolby Digital+" : u.matches("(?s).*\\b(DD|AC3)(\\d|\\b).*") ? "Dolby Digital" : u.contains("AAC") ? "AAC" : "";
        java.util.regex.Matcher ch = java.util.regex.Pattern.compile("(?<![0-9])(?<![0-9]\\.)([257]\\.[01])(?![0-9])").matcher(u);
        if (ch.find()) au = (au.isEmpty() ? "" : au + " ") + ch.group(1);
        p.add(au);
        List<String> out = new ArrayList<>(); for (String x : p) if (!x.isEmpty()) out.add(x);
        return TextUtils.join("  ·  ", out);
    }
    /** the file's name (or the addon's longest line about it) */
    static String fileLine(JSONObject s) {
        String all = (s.optString("title").isEmpty() ? s.optString("description") : s.optString("title"));
        String best = "";
        for (String l : all.split("\n")) {
            String t = l.trim();
            if (t.matches("(?i).*\\.(mkv|mp4|avi|m2ts|ts|mov)$") || t.matches(".*[A-Za-z0-9]+\\.[A-Za-z0-9]+\\.[A-Za-z0-9.]+.*") && t.length() > best.length()) best = t;
        }
        if (best.isEmpty()) best = all.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return best;
    }

    private final class Panel extends View {
        private float seasonOff;            // how far the season names have scrolled (px)
        private float epScroll, strScroll, epScrollT, strScrollT, dim, pageY, pageYT, castScroll, castScrollT;
        private final float cardW = 30 * R, cardH = 30 * R * 9 / 16f, cardGap = 1.8f * R, strH = 6.6f * R;
        private final TextPaint t1 = new TextPaint(Paint.ANTI_ALIAS_FLAG), t2 = new TextPaint(Paint.ANTI_ALIAS_FLAG), tc = new TextPaint(Paint.ANTI_ALIAS_FLAG),
                th = new TextPaint(Paint.ANTI_ALIAS_FLAG), tq = new TextPaint(Paint.ANTI_ALIAS_FLAG), ts = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), bp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF rf = new RectF(), rq = new RectF();
        private final android.graphics.Path clip = new android.graphics.Path();
        Panel(android.content.Context c) {
            super(c);
            t1.setTypeface(h.fSemi); t1.setTextSize(1.15f * R);
            t2.setTypeface(h.fReg); t2.setTextSize(.88f * R);
            tc.setTypeface(h.fSemi); tc.setTextSize(.72f * R); tc.setTextAlign(Paint.Align.CENTER); tc.setLetterSpacing(.04f);
            th.setTypeface(h.fSemi); th.setTextSize(1.3f * R);
            tq.setTypeface(h.fSemi); tq.setTextAlign(Paint.Align.CENTER);
            ts.setTypeface(h.fSemi); ts.setTextSize(.78f * R); ts.setLetterSpacing(.1f);
        }
        void resetScroll() { seasonOff = 0; epScroll = epScrollT = strScroll = strScrollT = 0; dim = 0; pageY = pageYT = 0; castScroll = castScrollT = 0; }
        private final float castD = 9.5f * R, castGap = 3.2f * R;
        void revealCast() {
            float vw = getWidth() - 10 * R, pitch = castD + castGap; if (vw <= 0) return;
            float x = castSel * pitch;
            if (x < castScrollT) castScrollT = x; else if (x + castD > castScrollT + vw) castScrollT = x + castD - vw;
            invalidate();
        }
        void resetStr() { strScroll = strScrollT = 0; }
        // where things sit (screen units)
        private float seasonsY() { return 26.4f * R; }
        private float rowY() { return 31.2f * R; }
        private float listTop() { return 11.5f * R; }
        private float listX0() { return 17 * R; }
        private float listX1() { return getWidth() - 17 * R; }
        void revealEp() {
            float vw = getWidth() - 10 * R; if (vw <= 0) return;
            float x = epSel * (cardW + cardGap);
            if (x < epScrollT + cardW * .2f) epScrollT = Math.max(0, x - cardW * .2f);
            else if (x + cardW > epScrollT + vw - cardW * .2f) epScrollT = x + cardW - vw + cardW * .2f;
            epScrollT = Math.max(0, Math.min(epScrollT, Math.max(0, eps.size() * (cardW + cardGap) - cardGap - vw)));
            invalidate();
        }
        void revealStr() { invalidate(); }     // (the streams screen keeps the chosen card in view as it draws)
        /* what was drawn where (for a finger): {left, top, right, bottom} on screen, its section and which one */
        final List<float[]> hitR = new ArrayList<>(); final List<String> hitZ = new ArrayList<>(); final List<Integer> hitI = new ArrayList<>();
        float hitOy;           // (the details are drawn lower on a phone held upright)
        float btnExtra;        // the buttons' extra lines on a narrow screen (px)
        /** how far down the details start: on a phone held upright, below the picture across the top */
        float oy() { return h.portrait ? (h.SW * 9 / 16f - 9f) * R : 0; }
        /** a width (rem) that fits the screen */
        float cw(float rem) { return Math.min(rem * R, getWidth() - 10 * R); }
        void hit(float l, float t, float r, float b, String z, int i, boolean page) {
            float dy = (page ? -pageY : 0) + hitOy;
            hitR.add(new float[] { l, t + dy, r, b + dy }); hitZ.add(z); hitI.add(i);
        }
        @Override protected void onDraw(Canvas c) {
            hitR.clear(); hitZ.clear(); hitI.clear();
            int w = getWidth(), hh = getHeight();
            boolean moving = false;
            if (Math.abs(epScroll - epScrollT) > .5f) { epScroll += (epScrollT - epScroll) * .25f; moving = true; } else epScroll = epScrollT;
            if (Math.abs(strScroll - strScrollT) > .5f) { strScroll += (strScrollT - strScroll) * .25f; moving = true; } else strScroll = strScrollT;
            float dimT = streamsScreen() ? 1f : 0f;
            if (Math.abs(dim - dimT) > .02f) { dim += (dimT - dim) * .3f; moving = true; } else dim = dimT;
            if (Math.abs(castScroll - castScrollT) > .5f) { castScroll += (castScrollT - castScroll) * .25f; moving = true; } else castScroll = castScrollT;
            for (RowState r : rowState.values()) if (Math.abs(r.scroll - r.scrollT) > .5f) { r.scroll += (r.scrollT - r.scroll) * .25f; moving = true; } else r.scroll = r.scrollT;
            if (person != null) { if (drawPerson(c, w, hh)) moving = true; if (moving) postInvalidateOnAnimation(); return; }
            if (dim < .99f) { if (drawPage(c, w, hh)) moving = true; }
            if (dim > .01f) drawStreamsScreen(c, w, hh);
            if (reviewOpen >= 0 && reviewOpen < reviews.size()) drawReviewOpen(c, w, hh);
            if (moving) postInvalidateOnAnimation();
        }

        // ------------------------------------------------------------ the details page (like Nuvio's): logo, facts, buttons, then episodes and cast below
        private final TextPaint big = new TextPaint(Paint.ANTI_ALIAS_FLAG), body = new TextPaint(Paint.ANTI_ALIAS_FLAG), small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        { big.setTypeface(h.fSemi); body.setTypeface(h.fReg); small.setTypeface(h.fMed); }
        private android.text.StaticLayout descL; private String descFor = ""; private float descW;
        private static final float HERO_BOTTOM = 52f;            // (screen units) where a show's seasons start
        /** returns true while still moving */
        /** where each section starts (page pixels) */
        private Map<String, Float> tops() {
            Map<String, Float> t = new LinkedHashMap<>();
            float y = (series ? HERO_BOTTOM * R : 55f * R) + oy() + btnExtra;
            if (series) { t.put("seasons", y); y += 40f * R; }
            if (!cast().isEmpty()) { t.put("cast", y); y += 21f * R; }
            if (!videos().isEmpty()) { t.put("videos", y); y += 25f * R; }
            if (!similar.isEmpty()) { t.put("similar", y); y += 30f * R; }
            if (!reviews.isEmpty()) { t.put("reviews", y); y += 22f * R; }
            if (full != null) { t.put("about", y); y += (4.5f + (about().size() + 1) / 2 * 4.6f) * R; }
            t.put("end", y);
            return t;
        }
        private boolean drawPage(Canvas c, int w, int hh) {
            Map<String, Float> tp = tops();
            float sTop = tp.containsKey("seasons") ? tp.get("seasons") : HERO_BOTTOM * R + oy() + btnExtra;
            float want = 0;
            String zk = zone.equals("episodes") ? "seasons" : zone;
            if (!zone.equals("buttons") && tp.containsKey(zk)) {
                want = tp.get(zk) - 6.5f * R;
                if (series && (zk.equals("cast"))) want = sTop - 6.5f * R + Math.max(0, tp.get("cast") + 21f * R - (sTop - 6.5f * R) - (hh - 5f * R));
                if (zk.equals("about")) want = Math.min(want, Math.max(0, tp.get("end") - (hh - 6f * R)));
                if (zk.equals("cast") && !series) want = Math.max(0, tp.get("cast") + 21f * R - (hh - 5f * R));
            }
            pageYT = Math.max(0, want);
            boolean moving = false;
            if (Math.abs(pageY - pageYT) > .5f) { pageY += (pageYT - pageY) * .22f; moving = true; } else pageY = pageYT;
            // the backdrop darkens as the page moves up
            float dk = Math.min(.85f, pageY / (30 * R));
            if (dk > 0) { p.setColor(Color.argb(Math.round(dk * 255), 7, 8, 13)); c.drawRect(0, 0, w, hh, p); }
            c.save(); c.translate(0, -pageY);
            float vis0 = pageY, vis1 = pageY + hh;
            if (vis0 < 60 * R + oy() + btnExtra) { c.save(); c.translate(0, oy()); hitOy = oy(); drawHero(c, w); hitOy = 0; c.restore(); }
            if (series && sTop < vis1 && sTop + 40 * R > vis0) drawSeries(c, w, sTop);
            if (tp.containsKey("cast")) drawCast(c, w, tp.get("cast"));
            if (tp.containsKey("videos") && tp.get("videos") < vis1) drawVideos(c, w, tp.get("videos"));
            if (tp.containsKey("similar") && tp.get("similar") < vis1) drawSimilar(c, w, tp.get("similar"));
            if (tp.containsKey("reviews") && tp.get("reviews") < vis1) drawReviews(c, w, tp.get("reviews"));
            if (tp.containsKey("about") && tp.get("about") < vis1) drawAbout(c, w, tp.get("about"));
            c.restore();
            // the foot fades out under the hint
            p.setShader(new LinearGradient(0, hh - 6 * R, 0, hh, 0x0007080D, 0xF207080D, Shader.TileMode.CLAMP)); c.drawRect(0, hh - 6 * R, w, hh, p); p.setShader(null);
            return moving;
        }
        private void drawHero(Canvas c, int w) {
            float x = 5 * R;
            JSONObject f = full != null ? full : item;
            // logo (or the name)
            Bitmap lb = logo.getDrawable() instanceof android.graphics.drawable.BitmapDrawable ? ((android.graphics.drawable.BitmapDrawable) logo.getDrawable()).getBitmap() : null;
            if (lb != null) {
                float sc = Math.min(cw(48) / lb.getWidth(), 12.5f * R / lb.getHeight());
                c.drawBitmap(lb, null, new RectF(x, 17.5f * R - lb.getHeight() * sc, x + lb.getWidth() * sc, 17.5f * R), bp);
            } else {
                big.setTextSize(5.2f * R); big.setColor(Color.WHITE); big.setShadowLayer(R, 0, .2f * R, 0x99000000);
                String t = item.optString("name");
                android.text.StaticLayout sl = android.text.StaticLayout.Builder.obtain(t, 0, t.length(), big, Math.round(cw(72))).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, .95f).build();
                c.save(); c.translate(x, 17.5f * R - sl.getHeight()); sl.draw(c); c.restore();
                big.clearShadowLayer();
            }
            // genres • year • IMDb
            float y = 21.4f * R;
            small.setTextSize(1.75f * R); small.setColor(0xD9FFFFFF);
            List<String> g = new ArrayList<>();
            JSONArray ga = f.optJSONArray("genres");
            if (ga != null) for (int i = 0; i < ga.length() && g.size() < 3; i++) g.add(ga.optString(i));
            String yr = f.optString("releaseInfo", item.optString("year"));
            if (yr.isEmpty()) yr = item.optString("year");
            List<String> r1 = new ArrayList<>();
            if (!g.isEmpty()) r1.add(TextUtils.join(", ", g));
            if (!yr.isEmpty()) r1.add(yr.replace("-", "–"));
            String line = TextUtils.join("   •   ", r1);
            c.drawText(line, x, y, small);
            float lx = x + small.measureText(line);
            String rating = f.optString("imdbRating", item.optString("rating"));
            if (!rating.isEmpty()) {
                lx += (r1.isEmpty() ? 0 : 1.6f * R);
                tc.setTextSize(1.15f * R);
                float iw = tc.measureText("IMDb") + 1.2f * R;
                rq.set(lx, y - 1.45f * R, lx + iw, y + .35f * R);
                p.setColor(0xFFF5C518); c.drawRoundRect(rq, .35f * R, .35f * R, p);
                tc.setColor(0xFF000000); c.drawText("IMDb", rq.centerX(), y - .17f * R, tc);
                c.drawText(rating, lx + iw + .7f * R, y, small);
                lx += iw + .7f * R + small.measureText(rating);
            }
            if (traktPct > 0) {
                lx += 1.6f * R;
                tc.setTextSize(1.15f * R);
                float iw = tc.measureText("TRAKT") + 1.2f * R;
                rq.set(lx, y - 1.45f * R, lx + iw, y + .35f * R);
                p.setColor(0xFFED1C24); c.drawRoundRect(rq, .35f * R, .35f * R, p);
                tc.setColor(Color.WHITE); c.drawText("TRAKT", rq.centerX(), y - .17f * R, tc);
                c.drawText(traktPct + "%", lx + iw + .7f * R, y, small);
            }
            // badges: rating certificate and status, then runtime • country • language
            y = 25f * R;
            float bx = x;
            JSONObject ex = f.optJSONObject("app_extras");
            String cert = ex == null ? "" : ex.optString("certification", ex.optString("certificationLocal"));
            if (!cert.isEmpty()) bx = outline(c, cert, bx, y);
            String st = f.optString("status");
            if (!st.isEmpty()) bx = outline(c, st.toUpperCase(Locale.US), bx, y);
            String nx = nextAiring();
            if (!nx.isEmpty()) {
                small.setTextSize(1.3f * R);
                float nw = small.measureText(nx) + 1.6f * R;
                rq.set(bx, y - 1.05f * R, bx + nw, y + 1.05f * R);
                p.setColor(0xFF30D158); c.drawRoundRect(rq, .35f * R, .35f * R, p);
                small.setColor(0xFF04220E); c.drawText(nx, bx + .8f * R, y + .47f * R, small);
                bx = rq.right + .8f * R;
            }
            List<String> r2 = new ArrayList<>();
            String rt = f.optString("runtime", item.optString("runtime"));
            if (!rt.isEmpty()) r2.add(rt);
            if (!f.optString("country").isEmpty()) r2.add(f.optString("country"));
            if (!f.optString("language").isEmpty()) r2.add(f.optString("language"));
            small.setTextSize(1.6f * R); small.setColor(0xB3FFFFFF);
            if (!r2.isEmpty()) c.drawText(TextUtils.ellipsize(TextUtils.join("   •   ", r2), small, cw(70) - (bx - x), TextUtils.TruncateAt.END).toString(), bx + (bx > x ? .4f * R : 0), y + .55f * R, small);
            // credits
            y = 28.6f * R;
            List<String> cr = new ArrayList<>();
            String dir = names(f.opt("director")), wri = names(f.opt("writer"));
            if (!dir.isEmpty()) cr.add("Director  " + dir);
            else if (!wri.isEmpty()) cr.add("Writers  " + wri);
            List<JSONObject> cs = cast();
            if (!cs.isEmpty()) { List<String> nm = new ArrayList<>(); for (int i = 0; i < Math.min(3, cs.size()); i++) nm.add(cs.get(i).optString("name")); cr.add("Starring  " + TextUtils.join(", ", nm)); }
            if (!cr.isEmpty()) { small.setTextSize(1.55f * R); small.setColor(0x99FFFFFF); c.drawText(TextUtils.ellipsize(TextUtils.join("     ", cr), small, cw(78), TextUtils.TruncateAt.END).toString(), x, y, small); }
            // the story
            String d = item.optString("desc"); if (d.isEmpty() && full != null) d = full.optString("description");
            float dw = cw(68);
            if (descL == null || !d.equals(descFor) || dw != descW) {
                body.setTextSize(1.85f * R); body.setColor(0xE6FFFFFF);
                descL = d.isEmpty() ? null : android.text.StaticLayout.Builder.obtain(d, 0, d.length(), body, Math.round(dw)).setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.22f).build();
                descFor = d; descW = dw;
            }
            if (descL != null) { c.save(); c.translate(x, 30.2f * R); descL.draw(c); c.restore(); }
            // awards
            String aw = f.optString("awards");
            if (!aw.isEmpty()) { small.setTextSize(1.45f * R); small.setColor(0xFFF6C344); c.drawText(TextUtils.ellipsize("🏆  " + aw, small, cw(68), TextUtils.TruncateAt.END).toString(), x, 40.3f * R, small); }
            // buttons
            float by = 42.3f * R, extra = 0;
            List<String> bs = buttons();
            boolean on = zone.equals("buttons");
            float cx = x;
            JSONObject up = series ? upNext() : null;
            String tgt = target();
            JSONObject pr = tgt.isEmpty() ? null : Progress.get(getContext(), tgt);
            boolean resume = pr != null && !Progress.done(pr) && Progress.fraction(pr) > 0;
            for (int i = 0; i < bs.size(); i++) {
                String b = bs.get(i), label;
                if (b.equals("play")) {
                    label = pendingBest ? "Finding the best stream…" : (resume ? "▶  Resume" : "▶  Play");
                    if (!pendingBest && up != null) label += "  S" + up.optInt("season", 1) + " · E" + up.optInt("episode", up.optInt("number"));
                } else if (b.equals("streams")) label = "Choose a stream";
                else if (b.equals("list")) label = inList() ? "✓  My List" : "＋  My List";
                else if (b.equals("watched")) label = series ? (showSeen() ? "✓  Watched" : "Watched…") : watchedMovie() ? "✓  Watched" : "Mark watched";
                else label = "▶  Trailer";
                th.setTextSize(1.8f * R);
                if (cx > x && cx + th.measureText(label) + 5f * R > w - 5 * R) { cx = x; by += 6.4f * R; extra += 6.4f * R; }      // (a narrow screen: the next line)
                float bx0 = cx;
                cx = button(c, label, cx, by, on && btnSel == i, b.equals("play")) + 1.2f * R;
                hit(bx0, by, cx - 1.2f * R, by + 5.2f * R, "buttons", i, true);
            }
            btnExtra = extra;
            // under the buttons: how far you got, and what Play will pick
            float ly = by + 7.6f * R;
            if (resume) {
                float pw = 18 * R;
                p.setColor(0x40FFFFFF); c.drawRoundRect(new RectF(x, ly - .9f * R, x + pw, ly - .4f * R), .25f * R, .25f * R, p);
                p.setColor(Color.WHITE); c.drawRoundRect(new RectF(x, ly - .9f * R, x + pw * (float) Progress.fraction(pr), ly - .4f * R), .25f * R, .25f * R, p);
                small.setTextSize(1.5f * R); small.setColor(0xB3FFFFFF);
                c.drawText(pr.optLong("dur") > 0 ? Math.max(1, (pr.optLong("dur") - pr.optLong("pos")) / 60000) + " min left" : Math.round(pr.optDouble("pct")) + "% watched", x + pw + 1.2f * R, ly - .1f * R, small);
                ly += 2.8f * R;
            }
            if (!series) {
                int bi = bestIndex();
                small.setTextSize(1.5f * R);
                if (bi >= 0) {
                    String all = text(streams.get(bi));
                    List<String> q = badges(all), parts = new ArrayList<>();
                    for (String r : new String[] { "4K", "1080p", "720p" }) if (q.contains(r)) { parts.add(r); break; }
                    parts.add(q.contains("DV") && !q.contains("Remux") ? "Dolby Vision" : q.contains("HDR10+") ? "HDR10+" : q.contains("DV") || q.contains("HDR") ? "HDR10" : "SDR");
                    String sum = summary(all); if (!sum.isEmpty()) parts.add(sum.replace("  ·  ", " · "));
                    String sz = size(all); if (!sz.isEmpty()) parts.add(sz);
                    small.setColor(0x80FFFFFF); c.drawText("Play picks", x, ly, small);
                    float px = x + small.measureText("Play picks") + R;
                    small.setColor(0xD9FFFFFF); c.drawText(TextUtils.ellipsize(TextUtils.join("  ·  ", parts), small, 70 * R, TextUtils.TruncateAt.END).toString(), px, ly, small);
                } else { small.setColor(0x80FFFFFF); c.drawText(asked.containsValue("…") ? "Asking your addons for streams…" : streams.isEmpty() && !asked.isEmpty() ? "No playable streams found" : "", x, ly, small); }
            }
        }
        String namesAll(Object o) {
            if (o instanceof JSONArray) { JSONArray a = (JSONArray) o; List<String> l = new ArrayList<>(); for (int i = 0; i < a.length() && l.size() < 4; i++) l.add(a.optString(i)); return TextUtils.join(", ", l); }
            return o == null ? "" : String.valueOf(o).trim();
        }
        /** a show's next new episode, e.g. "NEW EPISODE FRI, OCT 31" */
        private String nextAiring() {
            if (!series) return "";
            long now = System.currentTimeMillis();
            JSONObject best = null; long bt = Long.MAX_VALUE;
            java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
            for (JSONObject e : allEps) {
                String r = e.optString("released", e.optString("firstAired"));
                if (r.length() < 10) continue;
                try { long t = in.parse(r.substring(0, 10)).getTime(); if (t > now - 86400000L && t < bt) { bt = t; best = e; } } catch (Exception ignored) { }
            }
            if (best == null) return "";
            boolean today = bt <= now;
            return (today ? "NEW EPISODE TODAY" : "NEW EPISODE " + new java.text.SimpleDateFormat("EEE, MMM d", Locale.US).format(new java.util.Date(bt)).toUpperCase(Locale.US));
        }
        private void header(Canvas c, String t, float y) {
            big.setTextSize(2.1f * R); big.setColor(Color.WHITE);
            c.drawText(t, 5 * R, y + 2f * R, big);
        }
        /** keeps a row's chosen item in view */
        private void reveal(RowState r, float pitch, float itemW, int w) {
            float vw = w - 10 * R, x = r.sel * pitch;
            if (x < r.scrollT) r.scrollT = x; else if (x + itemW > r.scrollT + vw) r.scrollT = x + itemW - vw;
        }
        private void drawImage(Canvas c, Bitmap b, RectF r, float rad) {
            c.save(); clip.reset(); clip.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW); c.clipPath(clip);
            if (b != null) {
                float sc = Math.max(r.width() / b.getWidth(), r.height() / b.getHeight());
                float dw = b.getWidth() * sc, dh = b.getHeight() * sc;
                c.drawBitmap(b, null, new RectF(r.centerX() - dw / 2, r.centerY() - dh / 2, r.centerX() + dw / 2, r.centerY() + dh / 2), bp);
            } else { p.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, 0xFF2C2B38, 0xFF15141C, Shader.TileMode.CLAMP)); c.drawRect(r, p); p.setShader(null); }
            c.restore();
        }
        private void ring(Canvas c, RectF r, float rad) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(3, .22f * R)); p.setColor(Color.WHITE); c.drawRoundRect(r, rad, rad, p); p.setStyle(Paint.Style.FILL); }
        // trailers and clips
        private void drawVideos(Canvas c, int w, float top) {
            List<JSONObject> v = videos();
            header(c, "Trailers & videos", top);
            RowState r = rs("videos"); boolean on = zone.equals("videos");
            float iw = 26 * R, ih = iw * 9 / 16f, pitch = iw + 1.8f * R, y = top + 4f * R;
            reveal(r, pitch, iw, w);
            for (int i = 0; i < v.size(); i++) {
                float x = 5 * R + i * pitch - r.scroll;
                if (x + iw < 0 || x > w) continue;
                boolean f = on && i == r.sel;
                c.save(); if (f) c.scale(1.05f, 1.05f, x + iw / 2, y + ih / 2);
                rq.set(x, y, x + iw, y + ih); hit(x, y, x + iw, y + ih, "videos", i, true);
                drawImage(c, thumb("https://img.youtube.com/vi/" + v.get(i).optString("yt") + "/mqdefault.jpg", Math.round(iw), Math.round(ih)), new RectF(rq), R);
                if (!f) { p.setColor(0x40000000); c.drawRoundRect(rq, R, R, p); }
                p.setColor(0xCC000000); c.drawCircle(rq.centerX(), rq.centerY(), 2.2f * R, p);
                tq.setTextSize(2f * R); tq.setColor(Color.WHITE); c.drawText("▶", rq.centerX() + .2f * R, rq.centerY() + .7f * R, tq);
                if (f) ring(c, rq, R);
                c.restore();
                big.setTextSize(1.55f * R); big.setColor(f ? Color.WHITE : 0xB3FFFFFF);
                c.drawText(TextUtils.ellipsize(v.get(i).optString("title"), big, iw, TextUtils.TruncateAt.END).toString(), x, y + ih + (f ? 2.7f : 2.2f) * R, big);
            }
        }
        // more like this: posters
        private void drawSimilar(Canvas c, int w, float top) {
            header(c, "More like this", top);
            RowState r = rs("similar"); boolean on = zone.equals("similar");
            float iw = 13.5f * R, ih = iw * 1.5f, pitch = iw + 1.6f * R, y = top + 4f * R;
            reveal(r, pitch, iw, w);
            for (int i = 0; i < similar.size(); i++) {
                float x = 5 * R + i * pitch - r.scroll;
                if (x + iw < 0 || x > w) continue;
                JSONObject m = similar.get(i);
                boolean f = on && i == r.sel;
                c.save(); if (f) c.scale(1.06f, 1.06f, x + iw / 2, y + ih / 2);
                rq.set(x, y, x + iw, y + ih); hit(x, y, x + iw, y + ih, "similar", i, true);
                Bitmap b = thumb(m.optString("poster"), Math.round(iw), Math.round(ih));
                drawImage(c, b, new RectF(rq), .8f * R);
                if (b == null) { tq.setTextSize(1.4f * R); tq.setColor(0x99FFFFFF);
                    android.text.StaticLayout sl = android.text.StaticLayout.Builder.obtain(m.optString("name"), 0, m.optString("name").length(), tq, Math.round(iw - 2 * R)).setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL).setMaxLines(4).build();
                    tq.setTextAlign(Paint.Align.LEFT); c.save(); c.translate(x + R, y + ih / 2 - sl.getHeight() / 2f); sl.draw(c); c.restore(); tq.setTextAlign(Paint.Align.CENTER); }
                if (f) ring(c, rq, .8f * R);
                c.restore();
                small.setTextSize(1.35f * R); small.setColor(f ? Color.WHITE : 0x99FFFFFF);
                c.drawText(TextUtils.ellipsize(m.optString("name"), small, iw, TextUtils.TruncateAt.END).toString(), x, y + ih + (f ? 2.6f : 2.1f) * R, small);
                String sub = m.optString("year") + (m.optString("rating").isEmpty() ? "" : "   ★ " + m.optString("rating"));
                small.setTextSize(1.15f * R); small.setColor(0x80FFFFFF);
                c.drawText(sub.trim(), x, y + ih + (f ? 4.3f : 3.8f) * R, small);
            }
        }
        // reviews (Trakt comments, no spoilers)
        private void drawReviews(Canvas c, int w, float top) {
            header(c, "Reviews", top);
            RowState r = rs("reviews"); boolean on = zone.equals("reviews");
            float iw = 40 * R, ih = 15 * R, pitch = iw + 1.6f * R, y = top + 4f * R;
            reveal(r, pitch, iw, w);
            for (int i = 0; i < reviews.size(); i++) {
                float x = 5 * R + i * pitch - r.scroll;
                if (x + iw < 0 || x > w) continue;
                JSONObject m = reviews.get(i);
                boolean f = on && i == r.sel;
                rq.set(x, y, x + iw, y + ih); hit(x, y, x + iw, y + ih, "reviews", i, true);
                p.setColor(f ? 0xFF24262C : 0xFF17191D); c.drawRoundRect(rq, R, R, p);
                if (f) ring(c, rq, R);
                JSONObject u = m.optJSONObject("user");
                String who = u == null ? "" : u.optString("username");
                big.setTextSize(1.45f * R); big.setColor(Color.WHITE);
                c.drawText(TextUtils.ellipsize(who, big, iw * .55f, TextUtils.TruncateAt.END).toString(), x + 1.6f * R, y + 2.6f * R, big);
                JSONObject us = m.optJSONObject("user_stats");
                int rt = us == null ? 0 : us.optInt("rating");
                String meta2 = (rt > 0 ? "★ " + rt + "/10    " : "") + "♥ " + m.optInt("likes");
                small.setTextSize(1.25f * R); small.setColor(0x99FFFFFF); small.setTextAlign(Paint.Align.RIGHT);
                c.drawText(meta2, x + iw - 1.6f * R, y + 2.6f * R, small); small.setTextAlign(Paint.Align.LEFT);
                String tx = m.optString("comment").replaceAll("\\s+", " ").trim();
                body.setTextSize(1.45f * R); body.setColor(f ? 0xE6FFFFFF : 0xB3FFFFFF);
                // as many lines as fit in the card (with room at the foot), the rest cut with "…"
                Paint.FontMetrics fm = body.getFontMetrics();
                float lineH = (fm.descent - fm.ascent) * 1.18f;
                int lines = Math.max(1, (int) ((ih - 3.8f * R - 1.3f * R) / lineH));
                android.text.StaticLayout sl = android.text.StaticLayout.Builder.obtain(tx, 0, tx.length(), body, Math.round(iw - 3.2f * R)).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.18f).build();
                c.save(); c.clipRect(rq.left, rq.top, rq.right, rq.bottom - .9f * R); c.translate(x + 1.6f * R, y + 3.8f * R); sl.draw(c); c.restore();
            }
        }
        // a review in full, over the page
        private void drawReviewOpen(Canvas c, int w, int hh) {
            JSONObject m = reviews.get(reviewOpen);
            p.setColor(0xE607080D); c.drawRect(0, 0, w, hh, p);
            float cw = Math.min(w - 16 * R, 84 * R), x0 = (w - cw) / 2f, y0 = 5 * R, y1 = hh - 5.5f * R;
            rf.set(x0, y0, x0 + cw, y1);
            p.setColor(0xFF17191D); c.drawRoundRect(rf, 1.4f * R, 1.4f * R, p);
            JSONObject u = m.optJSONObject("user");
            String who = u == null ? "" : u.optString("username");
            big.setTextSize(2.2f * R); big.setColor(Color.WHITE);
            c.drawText(TextUtils.ellipsize(who, big, cw * .5f, TextUtils.TruncateAt.END).toString(), x0 + 2.4f * R, y0 + 3.8f * R, big);
            JSONObject us = m.optJSONObject("user_stats");
            int rt = us == null ? 0 : us.optInt("rating");
            String when = m.optString("created_at");
            if (when.length() >= 10) try { when = new java.text.SimpleDateFormat("MMM d, yyyy", Locale.US).format(new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(when.substring(0, 10))); } catch (Exception ignored) { }
            String meta2 = (rt > 0 ? "★ " + rt + "/10      " : "") + "♥ " + m.optInt("likes") + (when.isEmpty() ? "" : "      " + when) + "      " + (reviewOpen + 1) + " of " + reviews.size();
            small.setTextSize(1.45f * R); small.setColor(0x99FFFFFF); small.setTextAlign(Paint.Align.RIGHT);
            c.drawText(meta2, x0 + cw - 2.4f * R, y0 + 3.6f * R, small); small.setTextAlign(Paint.Align.LEFT);
            p.setColor(0x1FFFFFFF); c.drawRect(x0 + 2.4f * R, y0 + 5.4f * R, x0 + cw - 2.4f * R, y0 + 5.4f * R + Math.max(1, .08f * R), p);
            String tx = m.optString("comment").trim();
            body.setTextSize(1.8f * R); body.setColor(0xF0FFFFFF);
            android.text.StaticLayout sl = android.text.StaticLayout.Builder.obtain(tx, 0, tx.length(), body, Math.round(cw - 4.8f * R)).setLineSpacing(0, 1.3f).build();
            float ty0 = y0 + 6.8f * R, vh = y1 - 2 * R - ty0;
            reviewScroll = Math.max(0, Math.min(reviewScroll, Math.max(0, sl.getHeight() - vh)));
            c.save(); c.clipRect(x0, ty0, x0 + cw, ty0 + vh); c.translate(x0 + 2.4f * R, ty0 - reviewScroll); sl.draw(c); c.restore();
            small.setTextSize(1.3f * R); small.setColor(0x99FFFFFF);
            if (sl.getHeight() - reviewScroll > vh + 2) c.drawText("▼ more", x0 + cw - 8 * R, y1 - .8f * R, small);
            if (reviewScroll > 0) c.drawText("▲", x0 + cw - 8 * R, y0 + 6.4f * R, small);
        }
        // about: the facts, in two columns
        private void drawAbout(Canvas c, int w, float top) {
            List<String[]> a = about();
            if (a.isEmpty()) return;
            header(c, "About", top);
            boolean on = zone.equals("about");
            int half = (a.size() + 1) / 2;
            float colW = 42 * R;
            for (int i = 0; i < a.size(); i++) {
                float x = 5 * R + (i < half ? 0 : colW + 4 * R), y = top + 5.6f * R + (i < half ? i : i - half) * 4.6f * R;
                small.setTextSize(1.25f * R); small.setColor(0x80FFFFFF);
                c.drawText(a.get(i)[0].toUpperCase(Locale.US), x, y, small);
                body.setTextSize(1.6f * R); body.setColor(on ? Color.WHITE : 0xE6FFFFFF);
                c.drawText(TextUtils.ellipsize(a.get(i)[1], body, colW, TextUtils.TruncateAt.END).toString(), x, y + 2.1f * R, body);
            }
        }
        private String names(Object o) {
            if (o instanceof JSONArray) { JSONArray a = (JSONArray) o; List<String> l = new ArrayList<>(); for (int i = 0; i < a.length() && l.size() < 2; i++) l.add(a.optString(i)); return TextUtils.join(", ", l); }
            return o == null ? "" : String.valueOf(o).trim();
        }
        private float outline(Canvas c, String s, float x, float y) {
            small.setTextSize(1.3f * R);
            float bw = small.measureText(s) + 1.4f * R;
            rq.set(x, y - 1.05f * R, x + bw, y + 1.05f * R);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2, .1f * R)); p.setColor(0x99FFFFFF); c.drawRoundRect(rq, .35f * R, .35f * R, p); p.setStyle(Paint.Style.FILL);
            small.setColor(0xE6FFFFFF); c.drawText(s, x + .7f * R, y + .47f * R, small);
            return rq.right + .8f * R;
        }
        private float button(Canvas c, String label, float x, float y, boolean focused, boolean primary) {
            th.setTextSize(1.8f * R);
            float bw = th.measureText(label) + 5f * R, bh = 5.2f * R;
            boolean air = GlassPanel.look.equals("air");
            if (air) { label = label.toUpperCase(Locale.US); bw = th.measureText(label) + 6.2f * R; }
            rf.set(x, y, x + bw, y + bh);
            if (GlassPanel.look.equals("glass") || GlassPanel.look.equals("spot")) {
                if (focused) { p.setShadowLayer(1.6f * R, 0, .4f * R, 0x80000000); p.setColor(Color.WHITE); c.drawRoundRect(rf, bh / 2, bh / 2, p); p.clearShadowLayer(); }
                else { p.setColor(primary ? 0x4DFFFFFF : 0x2EFFFFFF); c.drawRoundRect(rf, bh / 2, bh / 2, p); }
            } else {
                // the look's own buttons (On Air's cut bars, the shelf's lit plates, Photo Ink's prints)
                android.graphics.drawable.Drawable d = GlassPanel.buttonBg(focused, bh / 2, primary ? 0x4DFFFFFF : 0x2EFFFFFF);
                d.setBounds(Math.round(rf.left), Math.round(rf.top), Math.round(rf.right), Math.round(rf.bottom));
                d.draw(c);
            }
            th.setColor(GlassPanel.buttonText(focused));
            c.drawText(label, x + 2.5f * R, y + bh / 2 + th.getTextSize() * .36f, th);
            th.setTextSize(1.3f * R);
            return rf.right;
        }

        // ------------------------------------------------------------ a show: seasons, then big episode pictures
        private void drawSeries(Canvas c, int w, float top) {
            float x = 5 * R, sy = top;
            boolean fz = zone.equals("seasons");
            small.setTextSize(2f * R); small.setColor(Color.WHITE);
            if (seasons.isEmpty()) { small.setTextSize(1.6f * R); small.setColor(0x99FFFFFF); c.drawText(full == null ? "Loading episodes…" : "No episodes listed", x, sy + 2.2f * R, small); }
            th.setTextSize(1.6f * R);
            /* The season names scroll sideways when there are more than fit (the chosen one always in view),
               beside the ratings strip when there is one. */
            boolean rated = false;
            for (JSONObject e : eps) if (epRating.containsKey(e.optInt("season", season) + ":" + e.optInt("episode", e.optInt("number")))) { rated = true; break; }
            float heatRoom = rated ? Math.min(w * .36f, eps.size() * 3.35f * R) : 0;
            float x0 = 5 * R, x1 = w - 5 * R - (heatRoom > 0 ? heatRoom + 2 * R : 0);
            float total = 0, selL = 0, selR = 0;
            for (int s : seasons) {
                float cw = th.measureText(s == 0 ? "Specials" : "Season " + s) + 3.2f * R;
                if (s == season) { selL = total; selR = total + cw; }
                total += cw + .6f * R;
            }
            float room = x1 - x0;
            if (total - .6f * R <= room) seasonOff = 0;
            else {
                if (selR > seasonOff + room) seasonOff = selR - room + (seasons.indexOf(season) < seasons.size() - 1 ? 2.5f * R : 0);
                if (selL < seasonOff) seasonOff = Math.max(0, selL - (seasons.indexOf(season) > 0 ? 2.5f * R : 0));
                seasonOff = Math.max(0, Math.min(seasonOff, total - .6f * R - room));
            }
            c.save();
            c.clipRect(x0 - .4f * R, sy - .4f * R, x1 + .4f * R, sy + 4f * R);
            x = x0 - seasonOff;
            for (int s : seasons) {
                String lab = s == 0 ? "Specials" : "Season " + s;
                float cw = th.measureText(lab) + 3.2f * R;
                if (x + cw > x0 - R && x < x1 + R) {
                    boolean cur = s == season;
                    rf.set(x, sy, x + cw, sy + 3.6f * R);
                    hit(Math.max(x, x0), sy, Math.min(x + cw, x1), sy + 3.6f * R, "season", s, true);
                    if (cur && fz) { p.setColor(Color.WHITE); c.drawRoundRect(rf, 1.8f * R, 1.8f * R, p); }
                    else if (cur) { p.setColor(0x38FFFFFF); c.drawRoundRect(rf, 1.8f * R, 1.8f * R, p); }
                    th.setColor(cur && fz ? 0xFF111111 : cur ? Color.WHITE : 0x8CFFFFFF);
                    c.drawText(lab, x + 1.6f * R, rf.centerY() + th.getTextSize() * .35f, th);
                }
                x += cw + .6f * R;
            }
            c.restore();
            // more seasons off either side: a small arrow says so
            th.setColor(0x99FFFFFF);
            if (seasonOff > 1) c.drawText("‹", x0 - 2.2f * R, sy + 2.45f * R, th);
            if (total - .6f * R - seasonOff > room + 1) c.drawText("›", x1 + .9f * R, sy + 2.45f * R, th);
            th.setTextSize(1.3f * R);
            boolean fe = zone.equals("episodes");
            if (heatRoom > 0) drawHeat(c, w - 5 * R - heatRoom, w - 5 * R, sy, fe);
            float y = top + 5.6f * R;
            for (int i = 0; i < eps.size(); i++) {
                float cx = 5 * R + i * (cardW + cardGap) - epScroll;
                if (cx + cardW < -cardW || cx > w + cardW) continue;
                JSONObject e = eps.get(i);
                boolean f = i == epSel && fe;
                c.save();
                if (f) c.scale(1.05f, 1.05f, cx + cardW / 2, y + cardH / 2);
                rq.set(cx, y, cx + cardW, y + cardH);
                hit(cx, y, cx + cardW, y + cardH, "episodes", i, true);
                if (f) { p.setShadowLayer(2f * R, 0, .6f * R, 0xB3000000); p.setColor(0xFF000000); c.drawRoundRect(rq, R, R, p); p.clearShadowLayer(); }
                String th0 = e.optString("thumbnail"), th1 = epStill(e);
                Bitmap b = th0.isEmpty() ? thumb(th1, null, Math.round(cardW), Math.round(cardH)) : thumb(th0, th1 != null && th1.equals(th0) ? null : th1, Math.round(cardW), Math.round(cardH));
                // not seen yet: its picture blurred (no spoilers); seen: the picture itself
                JSONObject pr0 = Progress.get(getContext(), e.optString("id"));
                boolean seen0 = (pr0 != null && Progress.done(pr0)) || Progress.watched(getContext(), e.optString("id"));
                if (b != null && !seen0) b = blurred(e.optString("id"), b);
                c.save(); clip.reset(); clip.addRoundRect(rq, R, R, android.graphics.Path.Direction.CW); c.clipPath(clip);
                if (b != null) {
                    float sc = Math.max(rq.width() / b.getWidth(), rq.height() / b.getHeight());
                    float dw = b.getWidth() * sc, dh = b.getHeight() * sc;
                    c.drawBitmap(b, null, new RectF(rq.centerX() - dw / 2, rq.centerY() - dh / 2, rq.centerX() + dw / 2, rq.centerY() + dh / 2), bp);
                } else {
                    p.setShader(new LinearGradient(rq.left, rq.top, rq.right, rq.bottom, 0xFF2C2B38, 0xFF15141C, Shader.TileMode.CLAMP)); c.drawRect(rq, p); p.setShader(null);
                    tq.setTextSize(4.5f * R); tq.setColor(0x33FFFFFF);
                    c.drawText(String.valueOf(e.optInt("episode", e.optInt("number"))), rq.centerX(), rq.centerY() + 1.6f * R, tq);
                }
                if (!f) { p.setColor(fe ? 0x59000000 : 0x33000000); c.drawRect(rq, p); }
                int sn = e.optInt("season", season), en = e.optInt("episode", e.optInt("number"));
                chip(c, rq.left + .9f * R, rq.top + .9f * R, "S" + sn + " · E" + (en < 10 ? "0" : "") + en, 0xB3000000, Color.WHITE, false);
                Double rt = epRating.get(sn + ":" + en);
                if (rt != null) chip(c, rq.right - .9f * R, rq.top + .9f * R, String.format(Locale.US, "★ %.1f", rt), 0xB3000000, heat(rt), true);
                JSONObject pr = Progress.get(getContext(), e.optString("id"));
                boolean seen = (pr != null && Progress.done(pr)) || Progress.watched(getContext(), e.optString("id"));
                if (pr != null && !seen && Progress.fraction(pr) > 0) {
                    p.setColor(0x99000000); c.drawRect(rq.left, rq.bottom - .55f * R, rq.right, rq.bottom, p);
                    p.setColor(0xFFFF5A5F); c.drawRect(rq.left, rq.bottom - .55f * R, rq.left + rq.width() * (float) Progress.fraction(pr), rq.bottom, p);
                }
                c.restore();
                if (f) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(3, .22f * R)); p.setColor(Color.WHITE); c.drawRoundRect(rq, R, R, p); p.setStyle(Paint.Style.FILL); }
                if (seen) {
                    float bx = rq.right - 1.6f * R, by = rq.bottom - 1.6f * R;
                    p.setColor(0xE630D158); c.drawCircle(bx, by, 1f * R, p);
                    tq.setTextSize(1.25f * R); tq.setColor(Color.WHITE); c.drawText("✓", bx, by + .45f * R, tq);
                }
                c.restore();
                int n = e.optInt("episode", e.optInt("number"));
                String name = e.optString("name", e.optString("title"));
                float ty = y + cardH + (f ? 3.2f : 2.6f) * R;
                big.setTextSize(1.8f * R); big.setColor(f ? Color.WHITE : 0xB3FFFFFF);
                c.drawText(TextUtils.ellipsize(n + ".  " + (name.isEmpty() ? "Episode " + n : name), big, cardW, TextUtils.TruncateAt.END).toString(), cx, ty, big);
                String when = pr != null && !seen && Progress.fraction(pr) > 0 ? (pr.optLong("dur") > 0 ? Math.max(1, (pr.optLong("dur") - pr.optLong("pos")) / 60000) + " min left" : Math.round(pr.optDouble("pct")) + "% watched") : airDate(e);
                small.setTextSize(1.4f * R); small.setColor(f ? 0x99FFFFFF : 0x66FFFFFF);
                c.drawText(when, cx, ty + 2.1f * R, small);
            }
            // the chosen episode's story
            if (epSel < eps.size()) {
                JSONObject e = eps.get(epSel);
                String ov = e.optString("overview", e.optString("description")).replace('\n', ' ').trim();
                if (!ov.isEmpty()) {
                    body.setTextSize(1.7f * R); body.setColor(fe ? 0xD9FFFFFF : 0x99FFFFFF);
                    android.text.StaticLayout sl = android.text.StaticLayout.Builder.obtain(ov, 0, ov.length(), body, Math.round(84 * R))
                            .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.22f).build();
                    c.save(); c.translate(5 * R, y + cardH + 6.8f * R); sl.draw(c); c.restore();
                }
            }
        }

        /** a small label on a picture: dark pill, text in colour; right: x is its right edge */
        private void chip(Canvas c, float x, float y, String s, int bg, int fg, boolean right) {
            tq.setTextSize(1.15f * R);
            float tw = tq.measureText(s), cw = tw + 1.4f * R, chh = 2.1f * R;
            float l = right ? x - cw : x;
            rf.set(l, y, l + cw, y + chh);
            p.setColor(bg); c.drawRoundRect(rf, .55f * R, .55f * R, p);
            tq.setColor(fg); c.drawText(s, rf.centerX(), rf.centerY() + .42f * R, tq);
        }
        /** an episode rating's colour: deep green (great) → red (poor) */
        private int heat(double r) {
            return r >= 9 ? 0xFF30D158 : r >= 8.3 ? 0xFF7ED957 : r >= 7.6 ? 0xFFC9DC4A : r >= 7 ? 0xFFFFD23F : r >= 6 ? 0xFFFF9F0A : 0xFFFF453A;
        }
        /* The season's episodes as a strip of rated squares (ARVIO's heatmap): beside the season names, the
           chosen episode ringed; only when Trakt has ratings for this season. */
        private void drawHeat(Canvas c, float x0, float x1, float y, boolean on) {
            if (eps.isEmpty() || epRating.isEmpty()) return;
            int n = eps.size(), rated = 0;
            for (JSONObject e : eps) if (epRating.containsKey(e.optInt("season", season) + ":" + e.optInt("episode", e.optInt("number")))) rated++;
            if (rated == 0) return;
            float room = x1 - x0, sq = Math.min(3.0f * R, (room - (n - 1) * .35f * R) / n), gap = .35f * R;
            if (sq < 1.1f * R) return;                                   // (too many to fit beside the seasons)
            boolean nums = sq >= 2.5f * R;
            float total = n * sq + (n - 1) * gap, x = x1 - total, top = y + (3.6f * R - sq) / 2;
            tq.setTextSize(Math.min(1.05f * R, sq * .4f));
            for (int i = 0; i < n; i++) {
                JSONObject e = eps.get(i);
                Double rt = epRating.get(e.optInt("season", season) + ":" + e.optInt("episode", e.optInt("number")));
                rf.set(x, top, x + sq, top + sq);
                p.setColor(rt == null ? 0x26FFFFFF : (heat(rt) & 0x00FFFFFF) | (on && i == epSel ? 0xFF000000 : 0xCC000000));
                c.drawRoundRect(rf, .4f * R, .4f * R, p);
                if (nums && rt != null) { tq.setColor(0xFF111111); c.drawText(String.format(Locale.US, "%.1f", rt), rf.centerX(), rf.centerY() + tq.getTextSize() * .36f, tq); }
                if (i == epSel) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2, .16f * R)); p.setColor(Color.WHITE); c.drawRoundRect(rf, .4f * R, .4f * R, p); p.setStyle(Paint.Style.FILL); }
                x += sq + gap;
            }
        }

        // ------------------------------------------------------------ someone's page
        private android.text.StaticLayout pBioL, pBioFull; private String pBioFor = ""; private float pBioW;
        private final float pRowH = 31f * R;
        private float pTop(Person P, String z) {
            float y = (h.portrait ? 30f : 37f) * R + oy();
            for (String k : new String[] { "known", "movies", "shows" }) { if (P.list(k).isEmpty()) continue; if (k.equals(z)) return y; y += pRowH; }
            return y;
        }
        /** returns true while still moving */
        private boolean drawPerson(Canvas c, int w, int hh) {
            Person P = person;
            boolean moving = false;
            p.setColor(0xFF07080D); c.drawRect(0, 0, w, hh, p);
            // their best-known title's picture, faint, behind the top right
            String bd = P.known.isEmpty() ? "" : P.known.get(0).optString("background");
            Bitmap bb = bd.isEmpty() ? null : thumb(bd, w / 2, hh / 2);
            if (bb != null && !h.portrait) {
                float x0 = w * .38f, y1 = hh * .78f;
                bp.setAlpha(150); drawImage(c, bb, new RectF(x0, 0, w, y1), 0); bp.setAlpha(255);
                p.setShader(new LinearGradient(x0, 0, w, 0, new int[] { 0xFF07080D, 0x9907080D, 0x3307080D }, new float[] { 0f, .45f, 1f }, Shader.TileMode.CLAMP)); c.drawRect(x0 - 1, 0, w, y1, p);
                p.setShader(new LinearGradient(0, y1 * .45f, 0, y1, 0x0007080D, 0xFF07080D, Shader.TileMode.CLAMP)); c.drawRect(x0 - 1, y1 * .45f - 1, w, y1 + 1, p); p.setShader(null);
            }
            // the page moves to the row you're on
            float want = 0;
            if (!P.zone.equals("bio")) want = pTop(P, P.zone) - 6.5f * R;
            float end = pTop(P, "end") + 2 * R;
            P.pageYT = Math.max(0, Math.min(want, Math.max(0, end - hh + 5 * R)));
            if (Math.abs(P.pageY - P.pageYT) > .5f) { P.pageY += (P.pageYT - P.pageY) * .22f; moving = true; } else P.pageY = P.pageYT;
            for (RowState r : P.rows.values()) if (Math.abs(r.scroll - r.scrollT) > .5f) { r.scroll += (r.scrollT - r.scroll) * .25f; moving = true; } else r.scroll = r.scrollT;
            c.save(); c.translate(0, -P.pageY);
            // the photo
            float pw = (h.portrait ? 12 : 17) * R, ph = pw * 1.5f, px = 5 * R, py = 5 * R + oy();
            RectF pr = new RectF(px, py, px + pw, py + ph);
            Bitmap pb = P.photo.isEmpty() ? null : thumb(P.photo + "#big", Math.round(pw), Math.round(ph));
            drawImage(c, pb, pr, 1.2f * R);
            if (pb == null) {
                String ini = ""; for (String part : P.name.split(" ")) if (!part.isEmpty() && ini.length() < 2) ini += part.charAt(0);
                tq.setTextSize(5f * R); tq.setColor(0x66FFFFFF); c.drawText(ini.toUpperCase(Locale.US), pr.centerX(), pr.centerY() + 1.8f * R, tq);
            }
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1, .08f * R)); p.setColor(0x26FFFFFF); c.drawRoundRect(pr, 1.2f * R, 1.2f * R, p); p.setStyle(Paint.Style.FILL);
            // who they are
            float tx = px + pw + 3.5f * R, tw = Math.max(20 * R, Math.min(72 * R, w - tx - 5 * R));
            float y = py + 1.6f * R;
            String kick = (P.dept.isEmpty() ? "" : (P.dept.equals("Acting") ? "ACTOR" : P.dept.toUpperCase(Locale.US)) + (P.place.isEmpty() ? "" : "   ·   ")) + P.place.toUpperCase(Locale.US);
            if (!kick.isEmpty()) { ts.setTextSize(1.05f * R); ts.setColor(0x99FFFFFF); c.drawText(TextUtils.ellipsize(kick, ts, tw, TextUtils.TruncateAt.END).toString(), tx, y, ts); }
            y += 4.6f * R;
            big.setTextSize((h.portrait ? 3f : 4.4f) * R); big.setColor(Color.WHITE);
            c.drawText(TextUtils.ellipsize(P.name, big, tw, TextUtils.TruncateAt.END).toString(), tx, y, big);
            y += 2.8f * R;
            String life = lifeLine(P);
            small.setTextSize(1.45f * R); small.setColor(0xD9FFFFFF);
            if (!life.isEmpty()) { c.drawText(TextUtils.ellipsize(life, small, tw, TextUtils.TruncateAt.END).toString(), tx, y, small); y += 2.3f * R; }
            if (!P.counts.isEmpty() && !P.loading) { small.setColor(0x99FFFFFF); small.setTextSize(1.3f * R); c.drawText(P.counts, tx, y, small); y += 2.3f * R; }
            y += .8f * R;
            // the biography (OK reads it all)
            String bio = P.loading ? "Loading…" : P.bio.isEmpty() ? (P.note.isEmpty() ? "No biography yet." : P.note) : P.bio;
            if (!bio.equals(pBioFor) || pBioW != tw) {
                pBioFor = bio; pBioW = tw;
                body.setTextSize(1.45f * R);
                pBioL = android.text.StaticLayout.Builder.obtain(bio, 0, bio.length(), body, Math.round(tw)).setLineSpacing(0, 1.3f).setMaxLines(h.portrait ? 5 : 6).setEllipsize(TextUtils.TruncateAt.END).build();
                pBioFull = null;
                P.bioLong = !P.bio.isEmpty() && pBioL.getLineCount() > 0 && pBioL.getEllipsisCount(pBioL.getLineCount() - 1) > 0;
            }
            boolean bf = P.zone.equals("bio");
            RectF br = new RectF(tx - 1.4f * R, y - 1f * R, tx + tw + 1.4f * R, y + pBioL.getHeight() + (P.bioLong ? 3.2f : 1f) * R);
            if (bf && P.bioLong) { p.setColor(0x14FFFFFF); c.drawRoundRect(br, R, R, p); ring(c, br, R); }
            hit(br.left, br.top - P.pageY, br.right, br.bottom - P.pageY, "p:bio", 0, false);
            body.setColor(bf ? 0xE6FFFFFF : 0xB3FFFFFF);
            c.save(); c.translate(tx, y); pBioL.draw(c); c.restore();
            if (P.bioLong) { small.setTextSize(1.2f * R); small.setColor(bf ? Color.WHITE : 0x80FFFFFF); c.drawText("Read more", tx, y + pBioL.getHeight() + 2f * R, small); }
            // their films and shows
            for (String z : new String[] { "known", "movies", "shows" }) {
                List<JSONObject> l = P.list(z); if (l.isEmpty()) continue;
                float top = pTop(P, z);
                if (top - P.pageY > hh || top + pRowH - P.pageY < 0) continue;
                drawPRow(c, w, top, z.equals("known") ? "Known for" : z.equals("movies") ? "Movies" : "TV shows", l, z, P);
            }
            c.restore();
            p.setShader(new LinearGradient(0, hh - 6 * R, 0, hh, 0x0007080D, 0xF207080D, Shader.TileMode.CLAMP)); c.drawRect(0, hh - 6 * R, w, hh, p); p.setShader(null);
            if (P.bioOpen) drawBioOpen(c, w, hh, P);
            return moving;
        }
        private void drawPRow(Canvas c, int w, float top, String title, List<JSONObject> l, String z, Person P) {
            header(c, title, top);
            RowState r = P.rs(z); boolean on = P.zone.equals(z);
            float iw = 13.5f * R, ih = iw * 1.5f, pitch = iw + 1.6f * R, y = top + 4f * R;
            reveal(r, pitch, iw, w);
            String now = today();
            for (int i = 0; i < l.size(); i++) {
                float x = 5 * R + i * pitch - r.scroll;
                if (x + iw < 0 || x > w) continue;
                JSONObject m = l.get(i);
                boolean f = on && i == r.sel;
                c.save(); if (f) c.scale(1.06f, 1.06f, x + iw / 2, y + ih / 2);
                rq.set(x, y, x + iw, y + ih); hit(x, y - P.pageY, x + iw, y + ih - P.pageY, "p:" + z, i, false);
                Bitmap b = thumb(m.optString("poster"), Math.round(iw), Math.round(ih));
                drawImage(c, b, new RectF(rq), .8f * R);
                if (b == null) { tq.setTextSize(1.4f * R); tq.setColor(0x99FFFFFF);
                    android.text.StaticLayout sl = android.text.StaticLayout.Builder.obtain(m.optString("name"), 0, m.optString("name").length(), tq, Math.round(iw - 2 * R)).setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL).setMaxLines(4).build();
                    tq.setTextAlign(Paint.Align.LEFT); c.save(); c.translate(x + R, y + ih / 2 - sl.getHeight() / 2f); sl.draw(c); c.restore(); tq.setTextAlign(Paint.Align.CENTER); }
                // (still to come: a tag on the poster)
                if (dateKey(m).compareTo(now) > 0 && !dateKey(m).equals("0000")) {
                    ts.setTextSize(.78f * R); float lw = ts.measureText("COMING") + 1.4f * R;
                    rf.set(x + .7f * R, y + .7f * R, x + .7f * R + lw, y + 2.5f * R); p.setColor(0xE6FFFFFF); c.drawRoundRect(rf, .5f * R, .5f * R, p);
                    ts.setColor(0xFF111418); c.drawText("COMING", rf.left + .7f * R, rf.bottom - .55f * R, ts);
                }
                if (f) ring(c, rq, .8f * R);
                c.restore();
                small.setTextSize(1.35f * R); small.setColor(f ? Color.WHITE : 0x99FFFFFF);
                c.drawText(TextUtils.ellipsize(m.optString("name"), small, iw, TextUtils.TruncateAt.END).toString(), x, y + ih + (f ? 2.6f : 2.1f) * R, small);
                String ch = m.optString("character");
                String sub = (ch.isEmpty() ? "" : (m.optBoolean("_crew") ? ch : "as " + ch));
                String yr = m.optString("year") + (m.optInt("_eps") > 1 ? "  ·  " + m.optInt("_eps") + " eps" : "");
                small.setTextSize(1.15f * R); small.setColor(0x80FFFFFF);
                c.drawText(TextUtils.ellipsize(sub.isEmpty() ? yr : sub, small, iw, TextUtils.TruncateAt.END).toString(), x, y + ih + (f ? 4.3f : 3.8f) * R, small);
                if (!sub.isEmpty() && !yr.trim().isEmpty()) c.drawText(TextUtils.ellipsize(yr, small, iw, TextUtils.TruncateAt.END).toString(), x, y + ih + (f ? 5.9f : 5.4f) * R, small);
            }
        }
        private void drawBioOpen(Canvas c, int w, int hh, Person P) {
            p.setColor(0xE607080D); c.drawRect(0, 0, w, hh, p);
            float bw = Math.min(78 * R, w - 8 * R), x = (w - bw) / 2, y0 = 6 * R, y1 = hh - 6 * R;
            rf.set(x - 2.4f * R, y0 - 2 * R, x + bw + 2.4f * R, y1 + 1 * R); p.setColor(0xFF15171C); c.drawRoundRect(rf, 1.6f * R, 1.6f * R, p);
            big.setTextSize(2.6f * R); big.setColor(Color.WHITE); c.drawText(P.name, x, y0 + 2.2f * R, big);
            if (pBioFull == null) { body.setTextSize(1.55f * R); pBioFull = android.text.StaticLayout.Builder.obtain(P.bio, 0, P.bio.length(), body, Math.round(bw)).setLineSpacing(0, 1.4f).build(); }
            float ty = y0 + 5 * R, room = y1 - ty;
            P.bioScroll = Math.min(P.bioScroll, Math.max(0, pBioFull.getHeight() - room));
            body.setColor(0xE6FFFFFF);
            c.save(); c.clipRect(x - R, ty, x + bw + R, y1); c.translate(x, ty - P.bioScroll); pBioFull.draw(c); c.restore();
        }
        // ------------------------------------------------------------ cast: round photos, names and parts
        private void drawCast(Canvas c, int w, float top) {
            List<JSONObject> cs = cast();
            if (cs.isEmpty()) return;
            big.setTextSize(2.1f * R); big.setColor(Color.WHITE);
            c.drawText("Cast", 5 * R, top + 2f * R, big);
            boolean on = zone.equals("cast");
            float y = top + 4f * R, pitch = castD + castGap;
            for (int i = 0; i < cs.size(); i++) {
                float cx = 5 * R + i * pitch - castScroll;
                if (cx + castD < 0 || cx > w) continue;
                JSONObject m = cs.get(i);
                boolean f = on && i == castSel;
                float ccx = cx + castD / 2, ccy = y + castD / 2, rad = castD / 2 * (f ? 1.06f : 1f);
                hit(cx, y, cx + castD, y + castD, "cast", i, true);
                Bitmap b = thumb(m.optString("photo"), Math.round(castD), Math.round(castD));
                c.save(); clip.reset(); clip.addCircle(ccx, ccy, rad, android.graphics.Path.Direction.CW); c.clipPath(clip);
                if (b != null) {
                    float sc = Math.max(2 * rad / b.getWidth(), 2 * rad / b.getHeight());
                    float dw = b.getWidth() * sc, dh = b.getHeight() * sc;
                    c.drawBitmap(b, null, new RectF(ccx - dw / 2, ccy - rad, ccx + dw / 2, ccy - rad + dh), bp);
                } else {
                    p.setColor(0xFF2A2934); c.drawCircle(ccx, ccy, rad, p);
                    String nm = m.optString("name"); String ini = "";
                    for (String part : nm.split(" ")) if (!part.isEmpty() && ini.length() < 2) ini += part.charAt(0);
                    tq.setTextSize(2.6f * R); tq.setColor(0x80FFFFFF); c.drawText(ini.toUpperCase(Locale.US), ccx, ccy + .9f * R, tq);
                }
                c.restore();
                if (f) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(3, .22f * R)); p.setColor(Color.WHITE); c.drawCircle(ccx, ccy, rad, p); p.setStyle(Paint.Style.FILL); }
                tq.setTextSize(1.4f * R); tq.setColor(f ? Color.WHITE : 0xD9FFFFFF);
                float tw = castD + castGap * .8f;
                c.drawText(TextUtils.ellipsize(m.optString("name"), tq, tw, TextUtils.TruncateAt.END).toString(), ccx, y + castD + 2.2f * R, tq);
                if (!m.optString("character").isEmpty()) { tq.setTextSize(1.2f * R); tq.setColor(0x80FFFFFF); c.drawText(TextUtils.ellipsize(m.optString("character"), tq, tw, TextUtils.TruncateAt.END).toString(), ccx, y + castD + 3.9f * R, tq); }
            }
        }
        private String airDate(JSONObject e) {
            String r = e.optString("released", e.optString("firstAired"));
            if (r.length() >= 10) {
                try {
                    java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
                    java.util.Date d = in.parse(r.substring(0, 10));
                    if (d.getTime() > System.currentTimeMillis()) return "Airs " + new java.text.SimpleDateFormat("MMM d", Locale.US).format(d);
                    return new java.text.SimpleDateFormat("MMM d, yyyy", Locale.US).format(d);
                } catch (Exception ignored) { }
            }
            return "";
        }

        // ------------------------------------------------------------ the streams screen (like Nuvio's): the title on the left, a frosted panel of streams on the right
        private float panelX0() { return h.portrait ? 2.5f * R : getWidth() * .4f; }
        private float panelX1() { return getWidth() - (h.portrait ? 2.5f : 4) * R; }
        private float panelY0() { return h.portrait ? 23 * R : 4 * R; }
        private float panelY1() { return getHeight() - 4.6f * R; }
        private float chipsH() { return chipNames().size() > 2 ? 4.8f * R : 0; }
        private float listY0() { return panelY0() + 1.6f * R + chipsH(); }
        /* each card's text, laid out once for its width */
        private final java.util.IdentityHashMap<JSONObject, android.text.StaticLayout[]> lay = new java.util.IdentityHashMap<>();
        private float layW = -1;
        private final TextPaint nameP = new TextPaint(Paint.ANTI_ALIAS_FLAG), descP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        { nameP.setTypeface(h.fSemi); nameP.setTextSize(2f * R); nameP.setColor(Color.WHITE);
          descP.setTypeface(h.fReg); descP.setTextSize(1.55f * R); descP.setColor(0xFFB3B3B3); }
        private float cardPad() { return 1.6f * R; }
        private float addonColW() { return 11 * R; }
        private android.text.StaticLayout[] layoutOf(JSONObject s) {
            float tw = panelX1() - panelX0() - 2 * 1.4f * R - 2 * cardPad() - addonColW();
            if (tw != layW) { lay.clear(); layW = tw; }
            android.text.StaticLayout[] l = lay.get(s);
            if (l != null) return l;
            String name = s.optString("name").trim();
            String d = s.optString("description").isEmpty() ? s.optString("title") : s.optString("description");
            d = d.trim();
            if (name.isEmpty()) name = "Stream";
            int w = Math.max(1, Math.round(tw));
            android.text.StaticLayout ln = android.text.StaticLayout.Builder.obtain(name, 0, name.length(), nameP, w).setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.08f).build();
            android.text.StaticLayout ld = d.isEmpty() || d.equals(name) ? null
                    : android.text.StaticLayout.Builder.obtain(d, 0, d.length(), descP, w).setMaxLines(8).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.18f).build();
            l = new android.text.StaticLayout[] { ln, ld };
            lay.put(s, l);
            return l;
        }
        private float labelH() { return 2.9f * R; }
        private float cardH(JSONObject s) {
            android.text.StaticLayout[] l = layoutOf(s);
            return 2 * cardPad() + labelH() + l[0].getHeight() + (l[1] == null ? 0 : .5f * R + l[1].getHeight());
        }
        private void drawStreamsScreen(Canvas c, int w, int hh) {
            int a = Math.round(dim * 255);
            // the backdrop stays, softened at both sides (as Nuvio does)
            p.setShader(new LinearGradient(0, 0, w, 0, new int[] { 0xFF07080D, 0xD907080D, 0x6607080D, 0x2607080D, 0x6607080D, 0xD907080D, 0xFF07080D },
                    new float[] { 0f, .15f, .3f, .5f, .7f, .85f, 1f }, Shader.TileMode.CLAMP));
            p.setAlpha(a); c.drawRect(0, 0, w, hh, p); p.setShader(null); p.setAlpha(255);
            p.setColor(Color.argb(Math.round(dim * 90), 7, 8, 13)); c.drawRect(0, 0, w, hh, p);
            c.saveLayerAlpha(0, 0, w, hh, a);
            drawLeft(c, w, hh);
            // the frosted panel
            float px0 = panelX0(), px1 = panelX1(), py0 = panelY0(), py1 = panelY1();
            rf.set(px0, py0, px1, py1);
            p.setColor(0xB31C1C1E); c.drawRoundRect(rf, 1.3f * R, 1.3f * R, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1, .06f * R)); p.setColor(0x1FFFFFFF); c.drawRoundRect(rf, 1.3f * R, 1.3f * R, p); p.setStyle(Paint.Style.FILL);
            float ix0 = px0 + 1.4f * R, ix1 = px1 - 1.4f * R;
            // addon chips
            List<String> ch = chipNames();
            if (ch.size() > 2) {
                float cx = ix0, cy = py0 + 1.4f * R;
                th.setTextSize(1.4f * R);
                for (int i = 0; i < ch.size(); i++) {
                    String nm = i == 0 ? "All" : ch.get(i);
                    boolean cur = i == 0 ? filter == null : nm.equals(filter);
                    boolean foc = zone.equals("chips") && i == chipSel;
                    float cw = th.measureText(nm) + 3f * R;
                    rq.set(cx, cy, cx + cw, cy + 3.3f * R);
                    hit(cx, cy, cx + cw, cy + 3.3f * R, "chips", i, false);
                    p.setColor(foc ? Color.WHITE : cur ? 0x40FFFFFF : 0x1AFFFFFF); c.drawRoundRect(rq, 1.65f * R, 1.65f * R, p);
                    th.setColor(foc ? 0xFF111111 : cur ? Color.WHITE : 0xB3FFFFFF);
                    c.drawText(nm, cx + 1.5f * R, rq.centerY() + th.getTextSize() * .35f, th);
                    cx += cw + .7f * R;
                    if (cx > ix1) break;
                }
                th.setTextSize(1.3f * R);
            }
            List<JSONObject> l = shown();
            boolean waiting = asked.containsValue("…");
            float ly0 = listY0();
            if (l.isEmpty()) {
                nameP.setColor(0x99FFFFFF);
                String why = waiting ? "Finding streams…" : asked.containsKey("Addons") ? asked.get("Addons") : "No playable streams found.";
                if (!waiting && !asked.containsKey("Addons")) for (Map.Entry<String, String> e : asked.entrySet()) if (!e.getValue().matches("\\d+")) why += "\n\n" + e.getKey() + ": " + e.getValue();
                android.text.StaticLayout sl = android.text.StaticLayout.Builder.obtain(why, 0, why.length(), nameP, Math.round(ix1 - ix0)).build();
                c.save(); c.translate(ix0, ly0 + 1.5f * R); sl.draw(c); c.restore();
                nameP.setColor(Color.WHITE);
                c.restore();
                return;
            }
            // keep the chosen card in view (cards differ in height)
            float yy = 0, selTop = 0, selBot = 0;
            float[] tops = new float[l.size()];
            for (int i = 0; i < l.size(); i++) { tops[i] = yy; float ch2 = cardH(l.get(i)); if (i == strSel) { selTop = yy; selBot = yy + ch2; } yy += ch2 + 1f * R; }
            float vh = py1 - 1.2f * R - ly0;
            if (selTop < strScrollT) strScrollT = selTop; else if (selBot > strScrollT + vh) strScrollT = selBot - vh;
            if (Math.abs(strScroll - strScrollT) > .5f) { strScroll += (strScrollT - strScroll) * .25f; postInvalidateOnAnimation(); } else strScroll = strScrollT;
            c.save(); c.clipRect(px0, ly0 - .4f * R, px1, py1 - .5f * R);
            int best = -1;
            for (int i = 0; i < l.size(); i++) if (!l.get(i).optBoolean("_failed")) { best = i; break; }
            boolean on = zone.equals("streams");
            for (int i = 0; i < l.size(); i++) {
                float y = ly0 + tops[i] - strScroll;
                float hgt = cardH(l.get(i));
                if (y + hgt < ly0 - R || y > py1) continue;
                drawStream(c, l.get(i), i == strSel && on, i == best && filter == null, ix0, ix1, y, hgt);
                hit(ix0, Math.max(y, ly0 - .4f * R), ix1, Math.min(y + hgt, py1 - .5f * R), "streams", i, false);
            }
            c.restore();
            if (waiting) { ts.setTextSize(1.15f * R); ts.setColor(0x99FFFFFF); c.drawText("STILL ASKING " + (asked.size() - countAnswered()) + " ADDON" + (asked.size() - countAnswered() == 1 ? "" : "S") + "…", ix0, py1 + 1.9f * R, ts); }
            c.restore();
        }
        private int countAnswered() { int n = 0; for (String v : asked.values()) if (!v.equals("…")) n++; return n; }
        /** the left side: the title's logo (or name), and which episode */
        private void drawLeft(Canvas c, int w, int hh) {
            float lx0 = 3 * R, lx1 = h.portrait ? w - 3 * R : panelX0() - 3 * R, cx = (lx0 + lx1) / 2f, cy = h.portrait ? 11 * R : hh / 2f - 2 * R;
            float y = cy;
            Bitmap lb = logo.getDrawable() instanceof android.graphics.drawable.BitmapDrawable ? ((android.graphics.drawable.BitmapDrawable) logo.getDrawable()).getBitmap() : null;
            if (lb != null) {
                float maxW = (lx1 - lx0) * .9f, maxH = (h.portrait ? 8 : 12) * R;
                float sc = Math.min(maxW / lb.getWidth(), maxH / lb.getHeight());
                float dw = lb.getWidth() * sc, dh = lb.getHeight() * sc;
                c.drawBitmap(lb, null, new RectF(cx - dw / 2, y - dh, cx + dw / 2, y), bp);
            } else {
                String t = item.optString("name");
                TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG); tp.setTypeface(h.fSemi); tp.setTextSize(4.4f * R); tp.setColor(Color.WHITE);
                android.text.StaticLayout sl = android.text.StaticLayout.Builder.obtain(t, 0, t.length(), tp, Math.round(lx1 - lx0)).setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build();
                c.save(); c.translate(lx0, y - sl.getHeight()); sl.draw(c); c.restore();
            }
            y += 3.6f * R;
            tq.setColor(0xFFB3B3B3);
            JSONObject ep = null;
            if (series) for (JSONObject e : eps) if (e.optString("id").equals(streamsFor)) ep = e;
            if (ep != null) {
                tq.setTextSize(2.4f * R);
                c.drawText("Season " + ep.optInt("season") + "  ·  Episode " + ep.optInt("episode", ep.optInt("number")), cx, y, tq);
                String en = ep.optString("name", ep.optString("title"));
                if (!en.isEmpty()) { tq.setTextSize(2f * R); tq.setColor(Color.WHITE); c.drawText(TextUtils.ellipsize(en, tq, lx1 - lx0, TextUtils.TruncateAt.END).toString(), cx, y + 3.2f * R, tq); }
            } else {
                tq.setTextSize(1.75f * R);
                c.drawText(TextUtils.ellipsize(meta.getText().toString().replace("   ·   ", "  •  "), tq, lx1 - lx0, TextUtils.TruncateAt.END).toString(), cx, y, tq);
            }
            JSONObject pr = Progress.get(getContext(), videoId);
            if (pr != null && !Progress.done(pr) && Progress.fraction(pr) > 0) {
                String r = "Picks up at " + (pr.optLong("dur") > 0 ? clock(pr.optLong("pos")) : Math.round(pr.optDouble("pct")) + "%");
                th.setTextSize(1.5f * R);
                float rw = th.measureText(r) + 3.2f * R, ry = y + (ep != null ? 5.6f : 2.8f) * R;
                rq.set(cx - rw / 2, ry, cx + rw / 2, ry + 3.4f * R);
                p.setColor(0x33FFFFFF); c.drawRoundRect(rq, 1.7f * R, 1.7f * R, p);
                th.setColor(Color.WHITE); c.drawText(r, rq.left + 1.6f * R, rq.centerY() + th.getTextSize() * .35f, th);
                th.setTextSize(1.3f * R);
            }
        }
        private void drawStream(Canvas c, JSONObject s, boolean f, boolean best, float x0, float x1, float y, float hgt) {
            rf.set(x0, y, x1, y + hgt);
            float rad = 1.1f * R;
            p.setColor(f ? 0xFF24262C : 0xFF17191D); c.drawRoundRect(rf, rad, rad, p);
            if (f) {
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(3, .2f * R));
                p.setShader(new LinearGradient(rf.left, rf.top, rf.right, rf.bottom, new int[] { 0xFFFFFFFF, 0xFFB9A8FF, 0xFF7FB2FF }, null, Shader.TileMode.CLAMP));
                c.drawRoundRect(rf, rad, rad, p); p.setShader(null); p.setStyle(Paint.Style.FILL);
            }
            android.text.StaticLayout[] l = layoutOf(s);
            float tx = rf.left + cardPad(), ty = rf.top + cardPad();
            // a line of labels: best pick, cached, quality, size (what the addon's own words may bury)
            String all = text(s);
            List<String> bs = badges(all);
            float bx = tx, by = ty + 1.05f * R;
            if (best) bx = chip(c, "★ BEST PICK", bx, by, 0xFFF6C344, 0xFF1A1300);
            if (s.optBoolean("_failed")) bx = chip(c, "DIDN'T PLAY", bx, by, 0x40FF9F0A, 0xFFFFC46B);
            if (s.optBoolean("_last")) bx = chip(c, "LAST USED", bx, by, 0x40FF453A, 0xFFFF8A80);
            for (String q : new String[] { "4K", "1080p", "720p" }) if (bs.contains(q)) { bx = chip(c, q, bx, by, 0x33FFFFFF, Color.WHITE); break; }
            if (bs.contains("DV") && !bs.contains("Remux")) bx = chip(c, "DOLBY VISION", bx, by, 0xFF6A5CFF, Color.WHITE);
            else if (bs.contains("HDR10+")) bx = chip(c, "HDR10+", bx, by, 0x33FFFFFF, Color.WHITE);
            else if (bs.contains("DV") || bs.contains("HDR")) bx = chip(c, "HDR10", bx, by, 0x33FFFFFF, Color.WHITE);
            if (bs.contains("Cached")) bx = chip(c, "⚡ CACHED", bx, by, 0x3330D158, 0xFF6EE7A0);
            String sz = size(all);
            if (!sz.isEmpty()) chip(c, sz, bx, by, 0x1FFFFFFF, 0xD9FFFFFF);
            ty += labelH();
            // the addon's own words, as it wrote them
            c.save(); c.translate(tx, ty); l[0].draw(c); c.restore();
            ty += l[0].getHeight();
            if (l[1] != null) { descP.setColor(f ? 0xFFD0D0D0 : 0xFFA0A0A0); c.save(); c.translate(tx, ty + .5f * R); l[1].draw(c); c.restore(); }
            // the addon, on the right
            tq.setTextSize(1.25f * R); tq.setColor(0x80FFFFFF);
            tq.setTextAlign(Paint.Align.RIGHT);
            c.drawText(TextUtils.ellipsize(s.optString("_addon"), tq, addonColW() - R, TextUtils.TruncateAt.END).toString(), rf.right - cardPad(), rf.top + cardPad() + 1.4f * R, tq);
            tq.setTextAlign(Paint.Align.CENTER);
        }
        private float chip(Canvas c, String s, float x, float cy, int bgc, int fg) {
            if (s.isEmpty()) return x;
            tc.setTextSize(1.15f * R);
            float cw = tc.measureText(s) + 1.7f * R;
            rq.set(x, cy - 1.05f * R, x + cw, cy + 1.05f * R);
            p.setColor(bgc); c.drawRoundRect(rq, .55f * R, .55f * R, p);
            tc.setColor(fg); c.drawText(s, rq.centerX(), cy + .41f * R, tc);
            return x + cw + .6f * R;
        }
    }
    static String text(JSONObject s) { return (s.optString("name") + " " + s.optString("title") + " " + s.optString("description")).toUpperCase(Locale.US); }
    /** the kind of file, for remembering what this TV can't play: "dv-remux", "dv", "4k", "" */
    static String kind(JSONObject s) {
        String u = text(s);
        boolean dv = u.matches("(?s).*\\b(DV|DOLBY ?VISION|DOVI)\\b.*"), remux = u.contains("REMUX"), uhd = u.matches("(?s).*(2160P|\\b4K\\b|UHD).*");
        return dv && remux ? "dv-remux" : dv ? "dv" : uhd ? "4k" : "";
    }
    /** how good the picture is, as this TV will show it (higher first); what failed here sinks */
    private int score(JSONObject s) {
        String u = text(s);
        int v = u.matches("(?s).*(2160P|\\b4K\\b|UHD).*") ? 4000 : u.contains("1080P") ? 3000 : u.contains("720P") ? 2000 : u.matches("(?s).*\\b(480P|576P|SD)\\b.*") ? 1000 : 1500;
        if (u.matches("(?s).*\\b(CAM|HDCAM|TS|TELESYNC|TELECINE|HDTS|SCREENER|SCR)\\b.*")) v -= 6000;
        v += u.contains("REMUX") ? 500 : u.matches("(?s).*\\b(BLURAY|BLU-RAY|BDRIP|BRRIP)\\b.*") ? 350 : u.matches("(?s).*\\bWEB-?DL\\b.*") ? 300 : u.matches("(?s).*\\bWEB-?RIP\\b.*") ? 250 : u.contains("WEB") ? 260 : u.contains("HDTV") ? 100 : 0;
        boolean dv = u.matches("(?s).*\\b(DV|DOLBY ?VISION|DOVI)\\b.*");
        if (dv && !u.contains("REMUX")) v += 260;                 // streaming Dolby Vision: the TV plays it as Dolby Vision
        else if (u.matches("(?s).*\\bHDR10\\+.*")) v += 230;
        else if (dv || u.contains("HDR")) v += 200;               // (a Dolby Vision remux shows as HDR10 here)
        if (u.contains("ATMOS")) v += 30;
        if (u.matches("(?s).*\\[(RD|AD|PM|TB|DL|ED|OC)\\+\\].*") || u.contains("CACHED") || u.contains("⚡")) v += 800;   // ready on your debrid now
        if (u.matches("(?s).*\\[(RD|AD|PM|TB|DL|ED|OC) ?(DOWNLOAD|⏳)\\].*") || u.contains("UNCACHED") || u.contains("⏳")) v -= 800;
        String k = kind(s);
        if (!k.isEmpty() && h.prefs.getInt("vodBad." + k, 0) >= 2) v -= 3000;     // this TV has failed this kind of file before
        if (s.optBoolean("_failed")) v -= 20000;
        return v;
    }
    static List<String> badges(String u) {
        List<String> b = new ArrayList<>();
        if (u.matches("(?s).*(2160P|\\b4K\\b|UHD).*")) b.add("4K"); else if (u.contains("1080P")) b.add("1080p"); else if (u.contains("720P")) b.add("720p"); else if (u.contains("480P")) b.add("480p");
        if (u.matches("(?s).*\\b(DV|DOLBY ?VISION|DOVI)\\b.*")) b.add("DV");
        if (u.matches("(?s).*\\bHDR10\\+.*")) b.add("HDR10+"); else if (u.contains("HDR")) b.add("HDR");
        if (u.matches("(?s).*\\b(ATMOS)\\b.*")) b.add("Atmos");
        if (u.matches("(?s).*\\b(REMUX)\\b.*")) b.add("Remux");
        if (u.matches("(?s).*\\b(CAM|TS|TELESYNC|HDCAM)\\b.*")) b.add("CAM");
        if (u.matches("(?s).*\\[(RD|AD|PM|TB|DL|ED|OC)\\+\\].*") || u.contains("CACHED") || u.contains("⚡")) b.add("Cached");
        return b;
    }
    static String size(String u) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s?(GB|MB|GIB|MIB)").matcher(u);
        return m.find() ? m.group(1).replace(',', '.') + " " + m.group(2).replace("I", "") : "";
    }

    // ================================================================== pictures

    private static Bitmap bitmapOf(ImageView v) { android.graphics.drawable.Drawable d = v.getDrawable(); return d instanceof android.graphics.drawable.BitmapDrawable ? ((android.graphics.drawable.BitmapDrawable) d).getBitmap() : null; }
    /** a picture taken off the page is given back straight away (not left for the garbage collector) */
    private void drop(ImageView v) { Bitmap b = bitmapOf(v); v.setImageDrawable(null); h.reclaim(b); }
    private void loadPic(final String url, final ImageView into, final int w, final int hh) {
        if (url == null || url.isEmpty()) return;
        final int g = gen;
        h.imgs.run(new Runnable() { @Override public void run() {
            Bitmap b = null;
            try { byte[] d = HomeImages.http(url, null); if (d != null) b = HomeImages.decode(d, w / 2, hh / 2); } catch (Throwable ignored) { }
            final Bitmap fb = b;
            post(new Runnable() { @Override public void run() {
                if (g == gen && fb != null) { Bitmap old = bitmapOf(into); into.setImageBitmap(fb); into.setAlpha(0f); into.animate().alpha(1f).setDuration(300).start(); if (old != fb) h.reclaim(old); }
                else h.reclaim(fb);                       // (another title by now: not needed)
            } });
        } });
    }
    private void loadLogo(final String url) {
        final int g = gen;
        h.imgs.run(new Runnable() { @Override public void run() {
            Bitmap b = null;
            try { byte[] d = HomeImages.http(url, null); if (d != null) b = HomeImages.decode(d, h.px(42), h.px(10)); } catch (Throwable ignored) { }
            final Bitmap fb = b;
            post(new Runnable() { @Override public void run() {
                if (g == gen && fb != null) { Bitmap old = bitmapOf(logo); logo.setImageBitmap(fb); panel.invalidate(); if (old != fb) h.reclaim(old); }
                else h.reclaim(fb);
            } });
        } });
    }
}
