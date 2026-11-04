package com.topshelf.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rows for Movies & TV Shows from sources that stay current, answered in Cinemeta's own shape
 * ({"metas": [...]}) so the rows read them like any catalog:
 *  - "trakt:/movies/trending…" — Trakt's lists (trending is what people are watching right now; also
 *    popular, most anticipated, this weekend's box office, most watched this week). Needs only the
 *    Trakt app ID already set up for Trakt.
 *  - "tmdb:/movie/now_playing…" — TMDB's lists (in theaters, coming soon, airing today, on the air,
 *    and what's on each streaming service). Only with a TMDB key (Settings).
 * Each title keeps its IMDb id, so posters, details, trailers and streams work as for Cinemeta's.
 */
final class Feeds {
    private Feeds() { }

    /** how long a row's copy is good for: trending moves quickly, the rest less so */
    static long ttl(String url) {
        if (url.contains("/trending") || url.contains("airing_today")) return 3600000L;
        if (url.startsWith("trakt:") || url.startsWith("tmdb:")) return 6 * 3600000L;
        return 6 * 3600000L;
    }

    /** the list, as Cinemeta would give it; null if it couldn't be had */
    static String fetch(NativeHome h, String url, String type) throws Exception {
        if (url.startsWith("trakt:")) return trakt(h, url.substring(6), type);
        if (url.startsWith("tmdb:")) return tmdb(h, url.substring(5), type);
        byte[] d = HomeImages.http(url, null);
        return d == null ? null : new String(d, StandardCharsets.UTF_8);
    }

    // ================================================================== Trakt

    static boolean traktOn(NativeHome h) { return h.act.trakt != null && h.act.trakt.hasApp(); }
    private static final Pattern YT = Pattern.compile("(?:v=|youtu\\.be/|/embed/)([A-Za-z0-9_-]{11})");

    private static String trakt(NativeHome h, String path, String type) throws Exception {
        Trakt t = h.act.trakt;
        String body = t == null ? null : t.pub(path + (path.contains("?") ? "&" : "?") + "extended=full");
        if (body == null) return null;
        JSONArray a = new JSONArray(body), metas = new JSONArray();
        String key = type.equals("series") ? "show" : "movie";
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x == null) continue;
            JSONObject m = x.optJSONObject(key) != null ? x.optJSONObject(key) : x;     // (popular lists come bare, the rest wrapped)
            JSONObject ids = m.optJSONObject("ids");
            String im = ids == null ? "" : ids.optString("imdb");
            if (!im.startsWith("tt")) continue;
            JSONObject o = new JSONObject();
            o.put("id", im).put("type", type).put("name", m.optString("title"));
            o.put("poster", "https://images.metahub.space/poster/medium/" + im + "/img");
            o.put("background", "https://images.metahub.space/background/medium/" + im + "/img");
            o.put("logo", "https://images.metahub.space/logo/medium/" + im + "/img");
            if (m.optInt("year") > 0) o.put("releaseInfo", String.valueOf(m.optInt("year")));
            if (m.optDouble("rating", 0) > 0) o.put("imdbRating", String.format(Locale.US, "%.1f", m.optDouble("rating")));
            if (m.optInt("runtime") > 0) o.put("runtime", m.optInt("runtime") + " min");
            if (ids.optInt("tmdb") > 0) o.put("moviedb_id", String.valueOf(ids.optInt("tmdb")));
            o.put("description", m.optString("overview"));
            JSONArray gs = m.optJSONArray("genres"), g = new JSONArray();
            if (gs != null) for (int k = 0; k < gs.length(); k++) g.put(cap(gs.optString(k)));
            o.put("genres", g);
            Matcher ym = YT.matcher(m.optString("trailer"));
            if (ym.find()) o.put("trailers", new JSONArray().put(new JSONObject().put("source", ym.group(1)).put("type", "Trailer")));
            metas.put(o);
        }
        return new JSONObject().put("metas", metas).toString();
    }
    private static String cap(String s) {
        if (s.isEmpty()) return s;
        if (s.equals("science-fiction")) return "Sci-Fi";
        String[] w = s.split("-"); StringBuilder b = new StringBuilder();
        for (String x : w) { if (x.isEmpty()) continue; if (b.length() > 0) b.append(' '); b.append(Character.toUpperCase(x.charAt(0))).append(x.substring(1)); }
        return b.toString();
    }

    // ================================================================== TMDB

    static String tmdbKey(NativeHome h) { return h.S.optString("tmdbKey", "").trim(); }
    static boolean tmdbOn(NativeHome h) { return !tmdbKey(h).isEmpty(); }

    private static String tmdbGet(String key, String path) throws Exception {
        boolean bearer = key.startsWith("eyJ");      // (a "read access token" instead of an API key)
        String u = "https://api.themoviedb.org/3" + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + key);
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(12000); c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            if (c.getResponseCode() >= 400) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] b = new byte[16384]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } finally { c.disconnect(); }
    }
    /** a TMDB answer as it comes (null without a key, or on an error) */
    static String tmdbRaw(NativeHome h, String path) { String k = tmdbKey(h); if (k.isEmpty()) return null; try { return tmdbGet(k, path); } catch (Exception e) { return null; } }
    private static final Map<String, String> IMDB = new HashMap<>();      // "movie:123" → "tt…" (kept while TopShelf runs)
    private static final String[][] GENRES = {
        { "28", "Action" }, { "12", "Adventure" }, { "16", "Animation" }, { "35", "Comedy" }, { "80", "Crime" }, { "99", "Documentary" }, { "18", "Drama" },
        { "10751", "Family" }, { "14", "Fantasy" }, { "36", "History" }, { "27", "Horror" }, { "10402", "Music" }, { "9648", "Mystery" }, { "10749", "Romance" },
        { "878", "Sci-Fi" }, { "53", "Thriller" }, { "10752", "War" }, { "37", "Western" }, { "10759", "Action" }, { "10762", "Kids" }, { "10765", "Sci-Fi" },
        { "10764", "Reality" }, { "10766", "Soap" }, { "10767", "Talk" }, { "10768", "War" } };
    private static String genre(int id) { for (String[] g : GENRES) if (g[0].equals(String.valueOf(id))) return g[1]; return null; }

    private static String tmdb(NativeHome h, String path, String type) throws Exception {
        String key = tmdbKey(h);
        if (key.isEmpty()) return null;
        // a service named in the path ({svc3}): its numbers as TMDB has them now
        java.util.regex.Matcher sm = java.util.regex.Pattern.compile("\\{svc(\\d+)\\}").matcher(path);
        if (sm.find()) path = path.substring(0, sm.start()) + java.net.URLEncoder.encode(serviceIds(h, Integer.parseInt(sm.group(1))), "UTF-8") + path.substring(sm.end());
        String body = tmdbGet(key, path);
        if (body == null) return null;
        JSONArray rs = new JSONObject(body).optJSONArray("results"), metas = new JSONArray();
        String kind = type.equals("series") ? "tv" : "movie";
        if (rs != null) for (int i = 0; i < rs.length() && metas.length() < 30; i++) {
            JSONObject x = rs.optJSONObject(i);
            if (x == null || x.optString("poster_path").isEmpty()) continue;
            int id = x.optInt("id");
            // the IMDb id (what addons and the rest of TopShelf go by): one more question per title, remembered
            String ck = kind + ":" + id, im;
            synchronized (IMDB) { im = IMDB.get(ck); }
            if (im == null) {
                try { String e = tmdbGet(key, "/" + kind + "/" + id + "/external_ids"); im = e == null ? "" : new JSONObject(e).optString("imdb_id"); } catch (Throwable t) { im = ""; }
                synchronized (IMDB) { IMDB.put(ck, im); }
            }
            if (im == null || !im.startsWith("tt")) continue;
            JSONObject o = new JSONObject();
            o.put("id", im).put("type", type).put("name", x.optString(kind.equals("tv") ? "name" : "title"));
            o.put("poster", "https://image.tmdb.org/t/p/w500" + x.optString("poster_path"));
            if (!x.optString("backdrop_path").isEmpty()) o.put("background", "https://image.tmdb.org/t/p/original" + x.optString("backdrop_path"));
            o.put("logo", "https://images.metahub.space/logo/medium/" + im + "/img");
            String date = x.optString(kind.equals("tv") ? "first_air_date" : "release_date");
            if (date.length() >= 4) o.put("releaseInfo", date.substring(0, 4));
            if (x.optDouble("vote_average", 0) > 0) o.put("imdbRating", String.format(Locale.US, "%.1f", x.optDouble("vote_average")));
            o.put("moviedb_id", String.valueOf(id));
            o.put("description", x.optString("overview"));
            JSONArray gi = x.optJSONArray("genre_ids"), g = new JSONArray();
            if (gi != null) for (int k = 0; k < gi.length(); k++) { String n = genre(gi.optInt(k)); if (n != null) g.put(n); }
            o.put("genres", g);
            metas.put(o);
        }
        return new JSONObject().put("metas", metas).toString();
    }

    /* The streaming services, found by name in TMDB's list of services (their numbers change: Paramount+ is
       now "Paramount Plus Premium" and "Paramount Plus Essential", HBO Max became Max…). Every matching
       service counts for the row (TMDB takes "a|b" as either); the logo is the best-known one's. */
    static final String[][] SERVICES = {
        { "Netflix", "8", "netflix" }, { "Max", "1899", "max", "hbomax" }, { "Disney+", "337", "disneyplus" },
        { "Prime Video", "9", "amazonprimevideo", "primevideo" }, { "Apple TV+", "350", "appletvplus", "appletv" }, { "Hulu", "15", "hulu" },
        { "Paramount+", "531", "paramountplus" }, { "Peacock", "386", "peacock" } };
    private static volatile List<Object[]> provs;          // {id, normalised name, logo, display priority}
    private static String norm(String s) { return s.toLowerCase(Locale.US).replace("+", "plus").replaceAll("[^a-z0-9]", ""); }
    private static List<Object[]> providers(NativeHome h) {
        List<Object[]> l = provs;
        if (l != null) return l;
        String key = tmdbKey(h);
        if (key.isEmpty()) return new ArrayList<>();
        l = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (String kind : new String[] { "movie", "tv" }) {
            try {
                String b = tmdbGet(key, "/watch/providers/" + kind + "?watch_region=US");
                JSONArray rs = b == null ? null : new JSONObject(b).optJSONArray("results");
                if (rs != null) for (int i = 0; i < rs.length(); i++) {
                    JSONObject x = rs.optJSONObject(i);
                    if (x == null || !seen.add(x.optInt("provider_id"))) continue;
                    JSONObject dp = x.optJSONObject("display_priorities");
                    int pr = dp != null && dp.has("US") ? dp.optInt("US") : x.optInt("display_priority", 999);
                    l.add(new Object[] { x.optInt("provider_id"), norm(x.optString("provider_name")), x.optString("logo_path"), pr });
                }
            } catch (Throwable ignored) { }
        }
        if (!l.isEmpty()) provs = l;
        return l;
    }
    /** TMDB's services that are this one (by its place in SERVICES), best known first */
    private static List<Object[]> matches(NativeHome h, int idx) {
        List<Object[]> out = new ArrayList<>();
        if (idx < 0 || idx >= SERVICES.length) return out;
        for (Object[] p : providers(h)) {
            String n = (String) p[1];
            if (n.contains("channel") || n.contains("store")) continue;            // (the same service sold through Amazon, Roku…)
            for (int k = 2; k < SERVICES[idx].length; k++) if (n.equals(SERVICES[idx][k]) || n.startsWith(SERVICES[idx][k])) { out.add(p); break; }
        }
        // the number we know first (its logo is the service's own), then the rest, best known first
        final int def = Integer.parseInt(SERVICES[idx][1]);
        java.util.Collections.sort(out, new java.util.Comparator<Object[]>() { @Override public int compare(Object[] a, Object[] b) {
            if ((Integer) a[0] == def) return -1; if ((Integer) b[0] == def) return 1;
            return (Integer) a[3] - (Integer) b[3];
        } });
        return out;
    }
    static String serviceIds(NativeHome h, int idx) {
        StringBuilder b = new StringBuilder();
        for (Object[] p : matches(h, idx)) { if (b.length() > 0) b.append('|'); b.append(p[0]); }
        return b.length() > 0 ? b.toString() : SERVICES[idx][1];
    }
    /** a service's logo (TMDB's own picture of it); null without a key */
    static String providerLogo(NativeHome h, String idx) {
        int i; try { i = Integer.parseInt(idx); } catch (Exception e) { return null; }
        for (Object[] p : matches(h, i)) if (!((String) p[2]).isEmpty()) return "https://image.tmdb.org/t/p/w500" + p[2];
        return null;
    }
}
