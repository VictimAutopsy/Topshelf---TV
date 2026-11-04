package com.topshelf.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls landscape photos from a subreddit. Reddit sometimes refuses one kind of request
 * and allows another, so this tries the JSON listing on www and old reddit with a normal
 * browser identity, then the RSS feed, and reports every failure if all of them fail.
 */
final class Reddit {

    private Reddit() { }

    /**
     * @param sub  subreddit name without "r/"
     * @param sort "hot", "top-week", "top-month", "top-year", "top-all" or "new"
     * @return JSON array of {img, full, title, author, sub, link, w, h}
     */
    static String fetch(String sub, String sort, String version, String browserUA, String cookies) throws Exception {
        signedCookies = cookies != null && cookies.contains("reddit_session=") ? cookies : null;
        sub = sub.replaceAll("[^A-Za-z0-9_+]", "");
        if (sub.isEmpty()) throw new IOException("No subreddit");
        String listing, t = "";
        if (sort.startsWith("top-")) { listing = "top"; t = sort.substring(4); }
        else if (sort.equals("new")) listing = "new";
        else listing = "hot";

        String json = "/r/" + sub + "/" + listing + ".json?limit=100&raw_json=1" + (t.isEmpty() ? "" : "&t=" + t);
        String rss = "/r/" + sub + "/" + listing + "/.rss?limit=100" + (t.isEmpty() ? "" : "&t=" + t);
        String appUA = "android:com.topshelf.launcher:v" + version + " (personal TV launcher)";
        String ua = (browserUA == null || browserUA.isEmpty()) ? appUA : browserUA;

        List<String> errors = new ArrayList<>();
        String[][] attempts = {
                {"www", "https://www.reddit.com" + json, ua, "json"},
                {"rss", "https://www.reddit.com" + rss, ua, "rss"},
                {"old", "https://old.reddit.com" + json, appUA, "json"},
        };
        for (String[] a : attempts) {
            try {
                String body = get(a[1], a[2], a[3].equals("rss") ? "application/atom+xml,text/xml" : "application/json");
                return a[3].equals("rss") ? parseRss(body, sub) : parse(body);
            } catch (Exception e) {
                errors.add(a[0] + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        throw new IOException(String.join("; ", errors));
    }

    /** signed in on the TV (Settings › Reddit account): requests go as you */
    private static volatile String signedCookies;
    private static String get(String url, String ua, String accept) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        String ck = signedCookies;
        if (ck != null && url.contains("reddit.com")) c.setRequestProperty("Cookie", ck);
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
        } finally {
            c.disconnect();
        }
    }

    static String parse(String body) throws Exception {
        String trimmed = body.trim();
        if (!trimmed.startsWith("{")) throw new IOException("blocked (got a web page, not data)");
        JSONArray children = new JSONObject(trimmed).getJSONObject("data").getJSONArray("children");
        JSONArray landscape = new JSONArray(), any = new JSONArray();
        for (int i = 0; i < children.length(); i++) {
            JSONObject d = children.getJSONObject(i).optJSONObject("data");
            if (d == null || d.optBoolean("over_18") || d.optBoolean("is_video") || d.optBoolean("stickied")) continue;

            String img = null, full = null;
            int w = 0, h = 0;
            String url = d.optString("url", "");
            if (isImageUrl(url)) full = url;

            JSONObject preview = d.optJSONObject("preview");
            JSONArray images = preview == null ? null : preview.optJSONArray("images");
            if (images != null && images.length() > 0) {
                JSONObject im = images.getJSONObject(0);
                JSONObject src = im.optJSONObject("source");
                if (src != null) { img = src.optString("url", null); w = src.optInt("width"); h = src.optInt("height"); }
                // Prefer the smallest rendition that is still at least 1920px wide (faster on TV).
                JSONArray res = im.optJSONArray("resolutions");
                if (res != null) {
                    for (int r = 0; r < res.length(); r++) {
                        JSONObject o = res.getJSONObject(r);
                        if (o.optInt("width") >= 1920) {
                            img = o.optString("url", img); w = o.optInt("width"); h = o.optInt("height");
                            break;
                        }
                    }
                }
            } else if (d.optBoolean("is_gallery") && d.optJSONObject("media_metadata") != null) {
                JSONObject mm = d.getJSONObject("media_metadata");
                JSONArray names = mm.names();
                if (names != null && names.length() > 0) {
                    JSONObject s = mm.getJSONObject(names.getString(0)).optJSONObject("s");
                    if (s != null) { img = s.optString("u", null); w = s.optInt("x"); h = s.optInt("y"); }
                }
            }
            if (img == null || img.isEmpty()) img = full;
            if (img == null || img.isEmpty()) continue;
            if (w == 0 || h == 0) { int[] wh = sizeFromTitle(d.optString("title")); w = wh[0]; h = wh[1]; }

            JSONObject o = new JSONObject();
            o.put("img", img.replace("&amp;", "&"));
            if (full != null && !full.equals(img)) o.put("full", full);
            o.put("title", d.optString("title"));
            o.put("author", d.optString("author"));
            o.put("sub", d.optString("subreddit"));
            o.put("link", "https://reddit.com" + d.optString("permalink"));
            o.put("w", w);
            o.put("h", h);
            any.put(o);
            if (w == 0 || h == 0 || w >= h * 1.15) landscape.put(o);
        }
        return pick(landscape, any);
    }

    private static final Pattern ENTRY = Pattern.compile("<entry>(.*?)</entry>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern AUTHOR = Pattern.compile("<name>\\s*/?u/([^<\\s]+)\\s*</name>");
    private static final Pattern LINK = Pattern.compile("<link href=\"([^\"]+)\"");
    private static final Pattern IMG_HREF = Pattern.compile("href=\"(https://(?:i\\.redd\\.it|i\\.imgur\\.com)/[^\"]+)\"");

    static String parseRss(String body, String sub) throws Exception {
        if (!body.contains("<feed")) throw new IOException("blocked (got a web page, not a feed)");
        JSONArray landscape = new JSONArray(), any = new JSONArray();
        Matcher m = ENTRY.matcher(body);
        while (m.find()) {
            String e = m.group(1);
            String content = unescape(unescape(e)); // content is HTML escaped inside XML
            Matcher im = IMG_HREF.matcher(content);
            String img = null;
            while (im.find()) { if (isImageUrl(im.group(1))) { img = im.group(1); break; } }
            if (img == null) continue;
            Matcher tm = TITLE.matcher(e);
            String title = tm.find() ? unescape(tm.group(1)).trim() : "";
            Matcher am = AUTHOR.matcher(e);
            Matcher lm = LINK.matcher(e);
            int[] wh = sizeFromTitle(title);
            JSONObject o = new JSONObject();
            o.put("img", img);
            o.put("title", title);
            o.put("author", am.find() ? am.group(1) : "");
            o.put("sub", sub);
            o.put("link", lm.find() ? lm.group(1) : "");
            o.put("w", wh[0]);
            o.put("h", wh[1]);
            any.put(o);
            if (wh[0] == 0 || wh[0] >= wh[1] * 1.15) landscape.put(o);
        }
        return pick(landscape, any);
    }

    static String pick(JSONArray landscape, JSONArray any) throws IOException {
        JSONArray out = landscape.length() >= 6 ? landscape : any;
        if (out.length() == 0) throw new IOException("no photos in that subreddit");
        return out.toString();
    }

    private static boolean isImageUrl(String u) {
        String lu = u.toLowerCase(Locale.US);
        int q = lu.indexOf('?');
        if (q >= 0) lu = lu.substring(0, q);
        return lu.endsWith(".jpg") || lu.endsWith(".jpeg") || lu.endsWith(".png") || lu.endsWith(".webp");
    }

    private static final Pattern SIZE = Pattern.compile("(\\d{3,5})\\s*[x×X]\\s*(\\d{3,5})");

    /** r/EarthPorn-style titles carry the resolution, e.g. "[4000x3000]". */
    private static int[] sizeFromTitle(String title) {
        Matcher m = SIZE.matcher(title == null ? "" : title);
        if (m.find()) {
            try { return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))}; }
            catch (NumberFormatException ignored) { }
        }
        return new int[]{0, 0};
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&#32;", " ").replace("&amp;", "&");
    }
}
