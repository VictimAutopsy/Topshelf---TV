package com.topshelf.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Coming up": the next new episode of each show you watch (an idea from ARVIO's release calendar,
 * Apache 2.0). The shows are the ones you've watched lately (what TopShelf and Trakt have recorded)
 * and the shows on My List; their episodes and air dates come from Cinemeta. One card per show: the
 * episode out in the last two days that you haven't seen, or else the next one to air in the coming
 * three weeks (a whole season dropping on one day is one card). Worked out on a worker thread; the
 * episode lists are kept on the TV for 12 hours.
 */
final class Upcoming {
    private Upcoming() { }

    static final String CINEMETA = "https://v3-cinemeta.strem.io";
    private static final long DAY = 86400000L, KEEP = 12 * 3600000L;
    private static volatile List<JSONObject> last = new ArrayList<>();
    /** the last list worked out (empty until then) */
    static List<JSONObject> cached() { return last; }

    /** the shows to look at: watched in the last four months, then My List; newest first */
    private static List<JSONObject> shows(Context c, JSONArray myList) {
        Map<String, Object[]> by = new LinkedHashMap<>();      // id → {meta, at}
        long since = System.currentTimeMillis() - 120 * DAY;
        JSONObject all = Progress.snapshot(c);
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            JSONObject x = all.optJSONObject(it.next());
            JSONObject t = x == null ? null : x.optJSONObject("title");
            if (t == null || !"series".equals(t.optString("type")) || x.optLong("at") < since) continue;
            String id = x.optString("show"); if (id.isEmpty()) id = t.optString("id");
            if (!id.startsWith("tt")) continue;
            Object[] o = by.get(id);
            if (o == null || (Long) o[1] < x.optLong("at")) by.put(id, new Object[] { t, x.optLong("at") });
        }
        List<Object[]> l = new ArrayList<>(by.values());
        Collections.sort(l, new Comparator<Object[]>() { @Override public int compare(Object[] a, Object[] b) { return Long.compare((Long) b[1], (Long) a[1]); } });
        List<JSONObject> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object[] o : l) { JSONObject t = (JSONObject) o[0]; String id = t.optString("id").split(":")[0]; if (seen.add(id)) out.add(t); }
        if (myList != null) for (int i = 0; i < myList.length(); i++) {
            JSONObject t = myList.optJSONObject(i);
            if (t != null && "series".equals(t.optString("type")) && t.optString("id").startsWith("tt") && seen.add(t.optString("id"))) out.add(t);
        }
        return out.size() > 40 ? out.subList(0, 40) : out;
    }

    /** a show's episodes from Cinemeta: [[season, episode, releasedMs, name, id]], kept for 12 hours */
    private static JSONArray episodes(Context c, String id) {
        File f = new File(new File(c.getCacheDir(), "upcoming"), id + ".json");
        try {
            if (f.exists() && System.currentTimeMillis() - f.lastModified() < KEEP) return new JSONArray(new String(read(f), StandardCharsets.UTF_8));
        } catch (Throwable ignored) { }
        JSONArray out = new JSONArray();
        try {
            byte[] d = HomeImages.http(CINEMETA + "/meta/series/" + id + ".json", null);
            if (d == null) return stale(f);
            JSONObject meta = new JSONObject(new String(d, StandardCharsets.UTF_8)).optJSONObject("meta");
            JSONArray v = meta == null ? null : meta.optJSONArray("videos");
            if (v != null) for (int i = 0; i < v.length(); i++) {
                JSONObject e = v.optJSONObject(i);
                if (e == null || e.optInt("season") <= 0) continue;
                Long at = SportsData.parseDate(e.optString("released", e.optString("firstAired", "")));
                if (at == null) continue;
                // a date with no time (stored as midnight UTC, which is the evening before here): that day, here
                Calendar u = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")); u.setTimeInMillis(at);
                boolean dateOnly = u.get(Calendar.HOUR_OF_DAY) == 0 && u.get(Calendar.MINUTE) == 0;
                if (dateOnly) {
                    Calendar l = Calendar.getInstance(); l.clear();
                    l.set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH), 0, 0, 0);
                    at = l.getTimeInMillis();
                }
                out.put(new JSONArray().put(e.optInt("season")).put(e.optInt("episode")).put(at).put(e.optString("name", e.optString("title", ""))).put(e.optString("id")).put(dateOnly ? 1 : 0));
            }
            f.getParentFile().mkdirs();
            FileOutputStream o = new FileOutputStream(f); o.write(out.toString().getBytes(StandardCharsets.UTF_8)); o.close();
        } catch (Throwable e) { return stale(f); }
        return out;
    }
    private static JSONArray stale(File f) { try { return f.exists() ? new JSONArray(new String(read(f), StandardCharsets.UTF_8)) : new JSONArray(); } catch (Throwable e) { return new JSONArray(); } }
    private static byte[] read(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try { java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] b = new byte[16384]; int n; while ((n = in.read(b)) > 0) bo.write(b, 0, n); return bo.toByteArray(); }
        finally { in.close(); }
    }

    /** the cards (worker thread): each show's meta plus calLabel ("Fri · S2 · E5"), calAt and calVideo */
    static List<JSONObject> compute(Context c, JSONArray myList, boolean clock24) {
        long now = System.currentTimeMillis();
        Calendar d0 = Calendar.getInstance(); d0.set(Calendar.HOUR_OF_DAY, 0); d0.set(Calendar.MINUTE, 0); d0.set(Calendar.SECOND, 0); d0.set(Calendar.MILLISECOND, 0);
        long today = d0.getTimeInMillis(), from = today - 2 * DAY, to = now + 21 * DAY;
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject show : shows(c, myList)) {
            String id = show.optString("id").split(":")[0];
            JSONArray eps = episodes(c, id);
            List<JSONArray> cand = new ArrayList<>();
            for (int i = 0; i < eps.length(); i++) {
                JSONArray e = eps.optJSONArray(i);
                if (e == null) continue;
                long at = e.optLong(2);
                if (at < from || at > to) continue;
                if (at <= now && Progress.watched(c, e.optString(4))) continue;      // (out already, and seen)
                cand.add(e);
            }
            if (cand.isEmpty()) continue;
            Collections.sort(cand, new Comparator<JSONArray>() { @Override public int compare(JSONArray a, JSONArray b) {
                int k = Long.compare(a.optLong(2), b.optLong(2)); if (k != 0) return k;
                k = a.optInt(0) - b.optInt(0); return k != 0 ? k : a.optInt(1) - b.optInt(1);
            } });
            JSONArray first = cand.get(0);
            long at = first.optLong(2);
            // the same season the same day (a whole season at once): one card, "E1–8"
            int lastEp = first.optInt(1);
            for (JSONArray e : cand) if (sameDay(e.optLong(2), at) && e.optInt(0) == first.optInt(0)) lastEp = Math.max(lastEp, e.optInt(1));
            String ep = "S" + first.optInt(0) + " · E" + first.optInt(1) + (lastEp > first.optInt(1) ? "–" + lastEp : "");
            try {
                JSONObject m = new JSONObject();
                for (String k : new String[] { "id", "type", "name", "poster", "background", "logo", "tmdb", "description", "desc", "year", "rating", "runtime", "releaseInfo", "imdbRating", "genres", "yt" })
                    if (show.has(k)) m.put(k, show.get(k));
                m.put("id", id).put("type", "series");
                m.put("calAt", at).put("calVideo", first.optString(4)).put("calEp", first.optString(3));
                m.put("calLabel", day(at, today, first.optInt(5) == 1, clock24) + " · " + ep);
                out.add(m);
            } catch (Exception ignored) { }
        }
        Collections.sort(out, new Comparator<JSONObject>() { @Override public int compare(JSONObject a, JSONObject b) { return Long.compare(a.optLong("calAt"), b.optLong("calAt")); } });
        last = out;
        return out;
    }
    private static boolean sameDay(long a, long b) {
        Calendar x = Calendar.getInstance(), y = Calendar.getInstance(); x.setTimeInMillis(a); y.setTimeInMillis(b);
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR);
    }
    /** "New" (out in the last two days), "Today 9 PM", "Tomorrow", "Fri", "Nov 12" */
    static String day(long at, long today, boolean dateOnly, boolean clock24) {
        if (at < today) return "New";
        if (at < today + DAY) return dateOnly ? "Today" : "Today " + new SimpleDateFormat(clock24 ? "HH:mm" : "h:mm a", Locale.US).format(new Date(at));
        if (at < today + 2 * DAY) return "Tomorrow";
        if (at < today + 7 * DAY) return new SimpleDateFormat("EEE", Locale.US).format(new Date(at));
        return new SimpleDateFormat("MMM d", Locale.US).format(new Date(at));
    }
}
