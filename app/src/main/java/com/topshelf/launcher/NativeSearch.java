package com.topshelf.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Search, worked out by the app (it used to be done by the hidden page, which handed the rows over):
 * your apps, movies and shows (Cinemeta), Live TV channels and what's on them (the guide), and your
 * recent searches. NativeFind draws it; OK, hold OK, typing and the mic come back here.
 */
final class NativeSearch {
    private final NativeHome h;
    boolean active;
    private String q = "";
    private int seq;
    private List<String> recent = new ArrayList<>();
    // the results so far (null: still being looked up)
    private List<NativeHome.App> apps = new ArrayList<>();
    private List<JSONObject> movies, series;
    private JSONArray chans = new JSONArray();
    private List<JSONObject> progs = new ArrayList<>();          // {ch, p:[start,end,title,desc]}
    private final Map<String, List<JSONObject>> cache = new HashMap<>();
    /** what each row on screen holds (the "ri" NativeFind hands back) */
    private final List<Object[]> rows = new ArrayList<>();       // {kind, List<?>}
    private final java.util.concurrent.ExecutorService work = java.util.concurrent.Executors.newSingleThreadExecutor();

    NativeSearch(NativeHome host) { h = host; }

    void open(String startWith) {
        active = true;
        recent = readRecent();
        q = "";
        if (startWith != null && !startWith.trim().isEmpty()) query(startWith, false, true);
        else { clear(); push(true); }
    }
    void close() {
        active = false;
        seq++;
        h.findClose();
    }

    private void clear() { apps = new ArrayList<>(); movies = null; series = null; chans = new JSONArray(); progs = new ArrayList<>(); }

    /** the box changed (typing, the mic, the phone's keyboard) */
    void query(String text, boolean submit, boolean first) {
        final String nq = text == null ? "" : text.trim();
        if (submit) remember(nq);
        if (nq.equals(q) && !first) { if (submit) push(false); return; }
        q = nq;
        final int my = ++seq;
        clear();
        if (q.isEmpty()) { push(first); return; }
        final String ql = q.toLowerCase(Locale.ROOT);
        // apps: right away
        List<Object[]> hit = new ArrayList<>();
        for (NativeHome.App a : h.appsForSearch()) {
            String l = a.label.toLowerCase(Locale.ROOT);
            if (!l.contains(ql)) continue;
            hit.add(new Object[] { l.startsWith(ql) ? 0 : (" " + l).contains(" " + ql) ? 1 : 2, a });
        }
        Collections.sort(hit, new Comparator<Object[]>() { @Override public int compare(Object[] x, Object[] y) {
            int d = (Integer) x[0] - (Integer) y[0];
            return d != 0 ? d : ((NativeHome.App) x[1]).label.compareToIgnoreCase(((NativeHome.App) y[1]).label);
        } });
        for (int i = 0; i < hit.size() && i < 16; i++) apps.add((NativeHome.App) hit.get(i)[1]);
        // channels: right away too (a few thousand names compared, without copies)
        try { NativeLive lv = h.liveData(); if (lv != null) chans = lv.searchChannels(q, 40); } catch (Throwable ignored) { }
        if (q.length() < 2) { movies = new ArrayList<>(); series = new ArrayList<>(); }
        push(first);
        if (q.length() < 2) return;
        // what's on (the guide) and the movies and shows (Cinemeta): in the background
        work.execute(new Runnable() { @Override public void run() {
            if (my != seq) return;
            final List<String[]> rowsP = new ArrayList<>();
            try {
                if (h.bridge.live.epgReady()) {
                    long now = System.currentTimeMillis();
                    JSONArray r = new JSONArray(h.bridge.live.searchPrograms(q, now, now + 7 * 86400000L, 60));
                    for (int i = 0; i < r.length(); i++) { JSONArray x = r.optJSONArray(i); if (x != null) rowsP.add(new String[] { x.optString(0), x.optString(1), x.optString(2), x.optString(3), x.optString(4) }); }
                } else h.bridge.warmEpg();
            } catch (Throwable ignored) { }
            h.main.post(new Runnable() { @Override public void run() {
                if (my != seq) return;
                NativeLive lv = h.liveData();
                List<JSONObject> out = new ArrayList<>();
                if (lv != null) for (String[] x : rowsP) {
                    JSONObject ch = lv.channelForKey(x[0]);
                    if (ch == null) continue;
                    try { out.add(new JSONObject().put("ch", ch).put("p", new JSONArray().put(Long.parseLong(x[1])).put(Long.parseLong(x[2])).put(x[3]).put(x[4]))); } catch (Exception ignored) { }
                    if (out.size() >= 40) break;
                }
                progs = out;
                push(false);
            } });
        } });
        for (final String type : new String[] { "movie", "series" }) {
            final String key = type + ":" + ql;
            List<JSONObject> have = cache.get(key);
            if (have != null) { if (type.equals("movie")) movies = have; else series = have; continue; }
            work.execute(new Runnable() { @Override public void run() {
                if (my != seq) return;
                List<JSONObject> l = null;
                try {
                    byte[] d = HomeImages.http(NativeVod.CINEMETA + "/catalog/" + type + "/top/search=" + android.net.Uri.encode(q) + ".json", null);
                    if (d != null) l = NativeVod.parse(new String(d, StandardCharsets.UTF_8), type);
                } catch (Throwable ignored) { }
                final List<JSONObject> got = l == null ? new ArrayList<JSONObject>() : l.subList(0, Math.min(30, l.size()));
                h.main.post(new Runnable() { @Override public void run() {
                    if (l_ok(got)) cache.put(key, got);
                    if (my != seq) return;
                    if (type.equals("movie")) movies = got; else series = got;
                    push(false);
                } });
            } });
        }
        if (movies != null || series != null) push(false);
    }
    private static boolean l_ok(List<JSONObject> l) { return l != null && !l.isEmpty(); }

    // ------------------------------------------------------------------ what NativeFind shows

    private void push(boolean first) {
        if (!active) return;
        JSONObject o = new JSONObject();
        rows.clear();
        try {
            JSONArray secs = new JSONArray();
            String big = "";
            if (q.isEmpty()) {
                if (!recent.isEmpty()) {
                    JSONArray it = new JSONArray(); for (String r : recent) it.put(new JSONObject().put("t", "chip").put("label", r));
                    sec(secs, "Recent searches", "q", it, recent, "");
                } else big = "Search your apps, movies, TV shows and Live TV channels.";
            } else {
                if (!apps.isEmpty()) {
                    JSONArray it = new JSONArray();
                    for (NativeHome.App a : apps) it.put(new JSONObject().put("t", "app").put("label", a.label).put("id", a.special ? "" : a.id).put("tint", a.tint == null ? "#2a2a2e" : a.tint));
                    sec(secs, "Apps", "app", it, apps, "");
                }
                posters(secs, "Movies", "movie", movies);
                posters(secs, "TV Shows", "series", series);
                if (chans.length() > 0) {
                    JSONArray it = new JSONArray(); List<JSONObject> l = new ArrayList<>();
                    for (int i = 0; i < chans.length(); i++) {
                        JSONObject c = chans.optJSONObject(i); l.add(c);
                        it.put(new JSONObject().put("t", "chan").put("label", c.optString("n") + (c.optBoolean("fav") ? " ★" : "")).put("sub", c.optString("now").isEmpty() ? c.optString("g") : c.optString("now")).put("img", c.optString("l")));
                    }
                    sec(secs, "Live TV channels", "chan", it, l, "");
                }
                if (!progs.isEmpty()) {
                    JSONArray it = new JSONArray();
                    long now = System.currentTimeMillis();
                    NativeLive lv = h.liveData();
                    for (JSONObject x : progs) {
                        JSONObject ch = x.optJSONObject("ch"); JSONArray p = x.optJSONArray("p");
                        long s = p.optLong(0), e = p.optLong(1);
                        boolean live = s <= now && now < e;
                        it.put(new JSONObject().put("t", "chan").put("label", p.optString(2).isEmpty() ? "Untitled" : p.optString(2))
                                .put("sub", (live ? "On now" : when(s)) + " · " + ch.optString("n")).put("live", live)
                                .put("bell", lv != null && lv.hasReminder(ch.optString("u"), s)).put("img", ch.optString("l")));
                    }
                    sec(secs, "On Live TV", "prog", it, progs, "");
                }
                if (movies != null && series != null && rows.isEmpty()) big = "Nothing found for “" + q + "”.";
            }
            o.put("kind", "search").put("q", q).put("ph", "Apps, movies, TV shows, channels…").put("big", big).put("start", -1).put("sections", secs);
        } catch (Exception ignored) { }
        h.searchShow(o, first);
    }
    private void sec(JSONArray secs, String title, String kind, JSONArray items, List<?> backing, String note) throws Exception {
        int ri = items.length() > 0 ? rows.size() : -1;
        if (items.length() > 0) rows.add(new Object[] { kind, backing });
        secs.put(new JSONObject().put("title", title).put("kind", kind).put("ri", ri).put("note", note).put("items", items));
    }
    private void posters(JSONArray secs, String title, String kind, List<JSONObject> l) throws Exception {
        if (l == null) { sec(secs, title, kind, new JSONArray(), null, "Searching…"); return; }
        if (l.isEmpty()) return;
        JSONArray it = new JSONArray();
        NativeVod v = h.vodForSearch();
        for (JSONObject m : l) {
            String y = m.optString("year");
            it.put(new JSONObject().put("t", "poster").put("label", m.optString("name")).put("sub", y.length() > 9 ? y.substring(0, 9) : y)
                    .put("img", v != null ? v.posterUrl(m) : m.optString("poster")).put("img2", m.optString("poster")));
        }
        sec(secs, title, kind, it, l, "");
    }
    private String when(long at) {
        Calendar c = Calendar.getInstance(), n = Calendar.getInstance();
        c.setTimeInMillis(at);
        boolean h24 = "24".equals(h.S.optString("clock", "12"));
        String t = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm a", Locale.US).format(c.getTime());
        int dd = c.get(Calendar.DAY_OF_YEAR) - n.get(Calendar.DAY_OF_YEAR) + (c.get(Calendar.YEAR) - n.get(Calendar.YEAR)) * 365;
        if (dd == 0) return "Today " + t;
        if (dd == 1) return "Tomorrow " + t;
        return new SimpleDateFormat("EEE", Locale.US).format(c.getTime()) + " " + t;
    }

    // ------------------------------------------------------------------ OK, hold OK

    private Object item(int ri, int c) {
        if (ri < 0 || ri >= rows.size()) return null;
        List<?> l = (List<?>) rows.get(ri)[1];
        return l != null && c >= 0 && c < l.size() ? l.get(c) : null;
    }
    private String kind(int ri) { return ri >= 0 && ri < rows.size() ? (String) rows.get(ri)[0] : ""; }

    void ok(int ri, int c) {
        Object it = item(ri, c);
        if (it == null) return;
        String k = kind(ri);
        if (k.equals("q")) { query((String) it, false, false); return; }
        remember(q);
        if (k.equals("app")) { NativeHome.App a = (NativeHome.App) it; close(); h.launchFromSearch(a); }
        else if (k.equals("movie") || k.equals("series")) { NativeVod v = h.vodForSearch(); if (v != null) v.openFromSearch((JSONObject) it); }
        else if (k.equals("chan")) { close(); h.livePlayUrl(((JSONObject) it).optString("u")); }
        else if (k.equals("prog")) {
            JSONObject x = (JSONObject) it;
            if (x.optJSONArray("p").optLong(0) <= System.currentTimeMillis()) { close(); h.livePlayUrl(x.optJSONObject("ch").optString("u")); }
            else hold(ri, c);
        }
    }

    void hold(final int ri, final int c) {
        final Object it = item(ri, c);
        if (it == null) return;
        String k = kind(ri);
        List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
        String title = "";
        if (k.equals("movie") || k.equals("series")) { NativeVod v = h.vodForSearch(); if (v != null) v.holdFor((JSONObject) it, null, 1f); return; }
        if (k.equals("chan")) {
            final JSONObject ch = (JSONObject) it;
            final NativeLive lv = h.liveData();
            final boolean fav = lv != null && lv.isFav(ch.optString("u"));
            title = ch.optString("n");
            l.add("Watch"); a.add(new Runnable() { @Override public void run() { remember(q); close(); h.livePlayUrl(ch.optString("u")); } });
            if (h.linkOn) { l.add("Watch on your other TV"); a.add(new Runnable() { @Override public void run() {
                h.page("lkSend({type:'open',what:'channel',u:" + JSONObject.quote(ch.optString("u")) + ",n:" + JSONObject.quote(ch.optString("n")) + "}).then(r=>{if(r)toast('Sent to '+lkTvName())})");
            } }); }
            l.add(fav ? "Remove from Favorites" : "Add to Favorites"); a.add(new Runnable() { @Override public void run() {
                if (lv != null) lv.toggleFavUrl(ch.optString("u"));
                try { ch.put("fav", !fav); } catch (Exception ignored) { }
                push(false);
            } });
            l.add("Open in another player"); a.add(new Runnable() { @Override public void run() { h.bridge.openExternal(ch.optString("u"), ch.optString("n")); } });
        } else if (k.equals("prog")) {
            final JSONObject x = (JSONObject) it;
            final JSONObject ch = x.optJSONObject("ch"); final JSONArray p = x.optJSONArray("p");
            final long s = p.optLong(0), e = p.optLong(1);
            final boolean future = s > System.currentTimeMillis();
            NativeLive lv = h.liveData();
            final boolean has = lv != null && lv.hasReminder(ch.optString("u"), s);
            title = (p.optString(2).isEmpty() ? "Untitled" : p.optString(2)) + " · " + ch.optString("n");
            if (future) { l.add(has ? "Cancel reminder" : "Remind me at " + when(s).replaceFirst("^(Today|Tomorrow|\\w{3}) ", "")); a.add(new Runnable() { @Override public void run() {
                NativeLive lv2 = h.liveData();
                if (lv2 != null) lv2.toggleReminder(ch.optString("u"), s, e, p.optString(2));
                push(false);
            } }); }
            l.add(future ? "Watch this channel now" : "Watch"); a.add(new Runnable() { @Override public void run() { remember(q); close(); h.livePlayUrl(ch.optString("u")); } });
        } else if (k.equals("app")) {
            final NativeHome.App ap = (NativeHome.App) it;
            title = ap.label;
            l.add("Open"); a.add(new Runnable() { @Override public void run() { ok(ri, c); } });
            if (!ap.special) { l.add("App info"); a.add(new Runnable() { @Override public void run() { h.bridge.appInfo(ap.id); } }); }
        } else if (k.equals("q")) {
            final String r = (String) it;
            title = r;
            l.add("Remove from recent searches"); a.add(new Runnable() { @Override public void run() { recent.remove(r); saveRecent(); push(false); } });
        }
        if (!l.isEmpty()) h.showMenu(l, a, null, 1f, title);
    }

    // ------------------------------------------------------------------ recent searches (shared with the page)

    private List<String> readRecent() {
        List<String> l = new ArrayList<>();
        try { JSONArray a = new JSONArray(h.prefs.getString("ui.recentSearches", "[]")); for (int i = 0; i < a.length(); i++) l.add(a.optString(i)); } catch (Exception ignored) { }
        return l;
    }
    private void saveRecent() {
        JSONArray a = new JSONArray(); for (String r : recent) a.put(r);
        h.prefs.edit().putString("ui.recentSearches", a.toString()).apply();
    }
    private void remember(String s) {
        s = s == null ? "" : s.trim();
        if (s.length() < 2) return;
        for (int i = recent.size() - 1; i >= 0; i--) if (recent.get(i).equalsIgnoreCase(s)) recent.remove(i);
        recent.add(0, s);
        while (recent.size() > 10) recent.remove(recent.size() - 1);
        saveRecent();
    }
}
