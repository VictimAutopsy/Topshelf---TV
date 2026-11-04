package com.topshelf.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Where you got to in each movie and episode (prefs "ui.vodProgress": video id → {pos, dur, pct, at, show,
 * season, episode, title: {id, type, name, poster, background, logo}, label, stream: {addon, name, title},
 * next: {id, label}}), and the Continue watching row made from it (plus Trakt's, when connected).
 */
final class Progress {
    private static SharedPreferences p(Context c) { return c.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE); }
    /* Read once and kept: the title page asks for each episode's progress as it draws (several times a frame
       while it scrolls), and reading the stored text again every time cost a full parse of up to 400 records. */
    private static JSONObject cache;
    private static synchronized JSONObject all(Context c) {
        if (cache == null) { try { cache = new JSONObject(p(c).getString("ui.vodProgress", "{}")); } catch (Exception e) { cache = new JSONObject(); } }
        return cache;
    }
    /** a backup was put back: read everything again */
    static synchronized void reset() { cache = null; watchedCache = null; }
    private static synchronized void store(Context c, JSONObject a) { cache = a; p(c).edit().putString("ui.vodProgress", a.toString()).apply(); Runnable r = onChange; if (r != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(r); }
    /** told when it changes (Movies & TV and Home redraw Continue watching) */
    static volatile Runnable onChange;

    /** a copy of everything (for work on another thread) */
    static synchronized JSONObject snapshot(Context c) { try { return new JSONObject(all(c).toString()); } catch (Exception e) { return new JSONObject(); } }
    static synchronized JSONObject get(Context c, String id) { return id == null || id.isEmpty() ? null : all(c).optJSONObject(id); }
    /** finished (or nearly: the last 4 minutes or 8% are credits) */
    static boolean done(JSONObject p) {
        if (p.optBoolean("watched")) return true;
        long pos = p.optLong("pos"), dur = p.optLong("dur");
        if (dur <= 0) return p.optDouble("pct", 0) >= 92;
        return pos > dur * .92 || dur - pos < 4 * 60000L;
    }
    static double fraction(JSONObject p) {
        long pos = p.optLong("pos"), dur = p.optLong("dur");
        return dur > 0 ? Math.min(1, pos / (double) dur) : Math.min(1, p.optDouble("pct", 0) / 100.0);
    }
    /** where to start (ms): carry on unless it was finished or barely begun. From Trakt only a percentage is known: -1 then (the player works it out once it knows the length) */
    static long startAt(Context c, String id) {
        JSONObject x = get(c, id);
        if (x == null || done(x)) return 0;
        if (x.optLong("dur") <= 0) return x.optDouble("pct", 0) > 2 ? -1 : 0;
        return x.optLong("pos") < 60000 ? 0 : x.optLong("pos") - 3000;
    }
    static double pct(Context c, String id) { JSONObject x = get(c, id); return x == null ? 0 : x.optDouble("pct", 0); }

    /** saves where you are; info = {title, label, stream, next} */
    static synchronized void put(Context c, String id, long pos, long dur, String show, int season, int episode, JSONObject info) {
        if (id == null || id.isEmpty() || dur <= 0) return;
        JSONObject a = all(c);
        try {
            JSONObject o = new JSONObject().put("id", id).put("pos", pos).put("dur", dur).put("pct", dur > 0 ? pos * 100.0 / dur : 0).put("at", System.currentTimeMillis())
                    .put("show", show).put("season", season).put("episode", episode);
            JSONObject old = a.optJSONObject(id);
            JSONObject src = info != null ? info : old;
            if (src != null) for (String k : new String[] { "title", "label", "stream", "next" }) if (src.has(k)) o.put(k, src.get(k));
            a.put(id, o);
            trim(a);
        } catch (Exception ignored) { }
        store(c, a);
    }
    /** from Trakt: where you got to on another device (kept unless this TV's own record is newer) */
    static synchronized void putRemote(Context c, String id, double pct, long at, String show, int season, int episode, JSONObject title, String label) {
        JSONObject a = all(c);
        try {
            JSONObject old = a.optJSONObject(id);
            if (old != null && old.optLong("at") >= at) return;
            JSONObject o = new JSONObject().put("id", id).put("pos", 0).put("dur", 0).put("pct", pct).put("at", at).put("show", show).put("season", season).put("episode", episode).put("trakt", true);
            if (title != null) o.put("title", title);
            if (label != null) o.put("label", label);
            if (old != null) for (String k : new String[] { "stream", "next" }) if (old.has(k)) o.put(k, old.get(k));
            a.put(id, o);
            trim(a);
        } catch (Exception ignored) { }
        store(c, a);
    }
    /** watched (from Trakt's history): ticks in the episode lists */
    static synchronized void markWatched(Context c, Set<String> ids) {
        Set<String> s = new HashSet<>(ids);
        p(c).edit().putString("ui.vodWatched", new JSONArray(s).toString()).apply();
        watchedCache = s;
    }
    private static Set<String> watchedCache;
    /** no longer watched (you unmarked it here) */
    static synchronized void unmarkWatched(Context c, String id) {
        if (!watched(c, id)) return;
        Set<String> s = new HashSet<>(watchedCache); s.remove(id);
        p(c).edit().putString("ui.vodWatched", new JSONArray(s).toString()).apply();
        watchedCache = s;
    }
    /** marked watched / not watched here (an episode, a season, a whole show) */
    static synchronized void setWatched(Context c, java.util.Collection<String> ids, boolean on) {
        watched(c, "");
        Set<String> s = new HashSet<>(watchedCache);
        JSONObject a = all(c);
        boolean changed = false;
        for (String id : ids) {
            if (on) s.add(id); else s.remove(id);
            if (!on && a.has(id)) { a.remove(id); changed = true; }                    // (and where you'd got to)
            if (on && a.optJSONObject(id) != null) { try { a.optJSONObject(id).put("watched", true); changed = true; } catch (Exception ignored) { } }
        }
        p(c).edit().putString("ui.vodWatched", new JSONArray(s).toString()).apply();
        watchedCache = s;
        if (changed) store(c, a);
        else { Runnable r = onChange; if (r != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(r); }
    }
    static synchronized boolean watched(Context c, String id) {
        if (watchedCache == null) {
            Set<String> s = new HashSet<>();
            try { JSONArray a = new JSONArray(p(c).getString("ui.vodWatched", "[]")); for (int i = 0; i < a.length(); i++) s.add(a.optString(i)); } catch (Exception ignored) { }
            watchedCache = s;
        }
        return watchedCache.contains(id);
    }
    static synchronized void forget(Context c, String id) { JSONObject a = all(c); a.remove(id); store(c, a); }

    private static void trim(JSONObject a) {
        while (a.length() > 400) {
            String oldest = null; long t = Long.MAX_VALUE;
            for (Iterator<String> it = a.keys(); it.hasNext(); ) { String k = it.next(); long at = a.optJSONObject(k).optLong("at"); if (at < t) { t = at; oldest = k; } }
            if (oldest == null) break;
            a.remove(oldest);
        }
    }

    /** a show's most recently watched episode */
    static synchronized JSONObject lastEpisode(Context c, String show) {
        JSONObject a = all(c), best = null;
        for (Iterator<String> it = a.keys(); it.hasNext(); ) {
            JSONObject x = a.optJSONObject(it.next());
            if (x != null && show.equals(x.optString("show")) && (best == null || x.optLong("at") > best.optLong("at"))) best = x;
        }
        return best;
    }

    /**
     * Continue watching, newest first: movies and episodes part-watched, and for a show whose latest episode
     * you finished, its next one. type: "movie", "series" or null for both. Each is the title (as Movies & TV
     * list it) plus {video, cwPct, cwLabel, cwStream}.
     */
    static synchronized List<JSONObject> continueList(Context c, String type, int max) {
        JSONObject a = all(c);
        List<JSONObject> rec = new ArrayList<>();
        for (Iterator<String> it = a.keys(); it.hasNext(); ) { JSONObject x = a.optJSONObject(it.next()); if (x != null && x.optJSONObject("title") != null) rec.add(x); }
        Collections.sort(rec, new Comparator<JSONObject>() { @Override public int compare(JSONObject x, JSONObject y) { return Long.compare(y.optLong("at"), x.optLong("at")); } });
        List<JSONObject> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long cutoff = System.currentTimeMillis() - 60L * 86400000L;          // (two months)
        for (JSONObject x : rec) {
            if (x.optLong("at") < cutoff || x.optBoolean("hidden")) continue;
            JSONObject t = x.optJSONObject("title");
            String tt = t.optString("type");
            if (type != null && !type.equals(tt)) continue;
            String key = x.optString("show").isEmpty() ? x.optString("id") : x.optString("show");
            if (!seen.add(key)) continue;                 // (a show once: its latest episode)
            try {
                JSONObject m = new JSONObject(t.toString());
                if (!done(x)) {
                    m.put("video", x.optString("id")).put("cwPct", fraction(x)).put("cwLabel", x.optString("label"));
                    if (x.optJSONObject("stream") != null) m.put("cwStream", x.optJSONObject("stream"));
                } else if (x.optJSONObject("next") != null && !x.optJSONObject("next").optString("id").isEmpty()) {
                    JSONObject nx = x.optJSONObject("next");
                    if (watched(c, nx.optString("id"))) continue;
                    JSONObject np = a.optJSONObject(nx.optString("id"));
                    if (np != null && done(np)) continue;
                    m.put("video", nx.optString("id")).put("cwPct", 0).put("cwLabel", "Next · " + nx.optString("label"));
                    if (x.optJSONObject("stream") != null) m.put("cwStream", x.optJSONObject("stream"));
                } else continue;
                out.add(m);
                if (out.size() >= max) break;
            } catch (Exception ignored) { }
        }
        return out;
    }
    /** how many titles Continue watching shows now */
    static int continueCount(Context c) { return continueList(c, null, 500).size(); }
    /** "Clear Continue watching": everything in it, in one go (watching something again brings it back) */
    static synchronized void hideAll(Context c) {
        JSONObject a = all(c);
        for (Iterator<String> it = a.keys(); it.hasNext(); ) {
            JSONObject x = a.optJSONObject(it.next());
            if (x != null) try { x.put("hidden", true); } catch (Exception ignored) { }
        }
        store(c, a);
    }
    /** "Remove from Continue watching" */
    static synchronized void hide(Context c, String video) {
        JSONObject a = all(c);
        for (Iterator<String> it = a.keys(); it.hasNext(); ) {
            JSONObject x = a.optJSONObject(it.next());
            if (x == null) continue;
            boolean match = x.optString("id").equals(video) || (x.optJSONObject("next") != null && x.optJSONObject("next").optString("id").equals(video));
            if (match) try { x.put("hidden", true); } catch (Exception ignored) { }
        }
        store(c, a);
    }
}
