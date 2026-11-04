package com.topshelf.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Photos that don't come from Reddit (Reddit shut its open feeds in 2026): Wikimedia Commons' featured
 * pictures by theme, and Bing's photos of the day. A source's name starts with "@" where a subreddit's
 * name would be ("@landscapes", "@bing"). Same JSON as Reddit's: [{img, full, title, author, sub, src, link, w, h}].
 */
final class PhotoSources {
    private PhotoSources() { }

    /** "@id" → [label, Commons search words ("" = every featured picture)] */
    static final Map<String, String[]> SOURCES = new LinkedHashMap<>();
    static {
        SOURCES.put("@landscapes", new String[] { "Landscapes", "landscape OR valley OR meadow OR countryside" });
        SOURCES.put("@mountains", new String[] { "Mountains", "mountain OR mountains OR peak OR alps" });
        SOURCES.put("@water", new String[] { "Lakes & coasts", "lake OR waterfall OR coast OR beach OR fjord" });
        SOURCES.put("@night", new String[] { "Night skies", "\"milky way\" OR \"night sky\" OR aurora OR stars" });
        SOURCES.put("@cities", new String[] { "Cities", "skyline OR cityscape OR \"at night\" city" });
        SOURCES.put("@featured", new String[] { "Featured pictures", "" });
        SOURCES.put("@bing", new String[] { "Bing photo of the day", null });
    }
    static boolean is(String sub) { return sub != null && sub.startsWith("@"); }
    static String label(String sub) { String[] s = SOURCES.get(sub); return s == null ? sub : s[0]; }

    static String fetch(String sub, String sort, String version) throws Exception {
        String[] s = SOURCES.get(sub);
        if (s == null) throw new IOException("unknown photo source " + sub);
        return s[1] == null ? bing() : commons(s[1], sort, version);
    }

    // ------------------------------------------------------------------ Wikimedia Commons (featured pictures)

    private static String commons(String words, String sort, String version) throws Exception {
        String q = "incategory:Featured_pictures_on_Wikimedia_Commons filemime:image/jpeg filew:>1919" + (words.isEmpty() ? "" : " " + words);
        String api = "https://commons.wikimedia.org/w/api.php?action=query&format=json&formatversion=2"
                + "&generator=search&gsrnamespace=6&gsrlimit=120&gsrsort=" + (sort.equals("new") ? "create_timestamp_desc" : "random")
                + "&gsrsearch=" + URLEncoder.encode(q, "UTF-8")
                + "&prop=imageinfo&iiprop=url%7Csize%7Cuser%7Cextmetadata&iiextmetadatafilter=ObjectName%7CArtist";
        String body = get(api, "TopShelf/" + version + " (personal TV launcher; https://github.com/VictimAutopsy/Topshelf---TV)");
        JSONObject r = new JSONObject(body);
        JSONArray pages = r.optJSONObject("query") == null ? null : r.getJSONObject("query").optJSONArray("pages");
        if (pages == null || pages.length() == 0) throw new IOException("Wikimedia Commons found no photos");
        JSONArray out = new JSONArray();
        for (int i = 0; i < pages.length(); i++) {
            JSONObject pg = pages.optJSONObject(i);
            JSONArray ii = pg == null ? null : pg.optJSONArray("imageinfo");
            if (ii == null || ii.length() == 0) continue;
            JSONObject f = ii.getJSONObject(0);
            int w = f.optInt("width"), h = f.optInt("height");
            String url = f.optString("url");
            if (w < 1920 || h <= 0 || w < h * 1.3 || !url.contains("/commons/")) continue;    // landscape-shaped photos only
            String file = url.substring(url.lastIndexOf('/') + 1);
            String thumb = url.replace("/commons/", "/commons/thumb/") + "/";
            JSONObject em = f.optJSONObject("extmetadata");
            String title = plain(em, "ObjectName");
            if (title.isEmpty()) title = pg.optString("title").replaceFirst("^File:", "").replaceFirst("\\.[A-Za-z]+$", "").replace('_', ' ');
            String artist = plain(em, "Artist");
            if (artist.isEmpty() || artist.length() > 60) artist = f.optString("user");
            JSONObject o = new JSONObject();
            o.put("img", thumb + "1920px-" + file);
            o.put("full", w >= 3840 ? thumb + "3840px-" + file : url);
            o.put("title", title);
            o.put("author", artist);
            o.put("src", "Wikimedia Commons");
            o.put("link", f.optString("descriptionurl"));
            o.put("w", w); o.put("h", h);
            out.put(o);
        }
        if (out.length() == 0) throw new IOException("Wikimedia Commons found no landscape photos");
        return out.toString();
    }
    private static String plain(JSONObject em, String k) {
        JSONObject x = em == null ? null : em.optJSONObject(k);
        if (x == null) return "";
        return android.text.Html.fromHtml(x.optString("value"), android.text.Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------ Bing (photo of the day, the last couple of weeks)

    private static String bing() throws Exception {
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int idx : new int[] { 0, 7 }) {
            String body = get("https://www.bing.com/HPImageArchive.aspx?format=js&idx=" + idx + "&n=8&mkt=en-US", null);
            JSONArray imgs = new JSONObject(body).optJSONArray("images");
            if (imgs == null) continue;
            for (int i = 0; i < imgs.length(); i++) {
                JSONObject b = imgs.getJSONObject(i);
                String base = b.optString("urlbase");
                if (base.isEmpty() || !seen.add(base)) continue;
                String cr = b.optString("copyright"), title = b.optString("title"), by = "";
                int p = cr.lastIndexOf("(©");
                if (p > 0) { by = cr.substring(p + 2).replaceAll("\\)\\s*$", "").trim(); if (title.isEmpty()) title = cr.substring(0, p).trim(); else title = title + " · " + cr.substring(0, p).trim(); }
                else if (title.isEmpty()) title = cr;
                JSONObject o = new JSONObject();
                o.put("img", "https://www.bing.com" + base + "_1920x1080.jpg");
                o.put("full", "https://www.bing.com" + base + "_UHD.jpg");
                o.put("title", title);
                o.put("author", by);
                o.put("src", "Bing");
                o.put("link", b.optString("copyrightlink"));
                o.put("w", 3840); o.put("h", 2160);
                out.put(o);
            }
        }
        if (out.length() == 0) throw new IOException("Bing sent no photos");
        return out.toString();
    }

    private static String get(String url, String ua) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        if (ua != null) c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toString("UTF-8");
            }
        } finally { c.disconnect(); }
    }
    static String lower(String s) { return s.toLowerCase(Locale.US); }
}
