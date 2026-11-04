package com.topshelf.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catch-up: shows that already aired, played from the provider's archive (TiviMate's "catch-up").
 *
 * Two ways a playlist says a channel has one:
 *  - an Xtream Codes service (the playlist link is …/get.php?username=…&password=…, or the channels are
 *    …/live/user/pass/123.ts): its API lists which channels keep an archive and for how many days, and a past
 *    show plays from …/timeshift/user/pass/{minutes}/{yyyy-MM-dd:HH-mm}/{id}.m3u8 (the start in the server's time zone);
 *  - the playlist's own catch-up tags (catchup="default|append|shift|flussonic|xc", catchup-days,
 *    catchup-source with {utc} {duration} {Y}-{m}-{d} … placeholders), as Kodi and TiviMate read them.
 */
final class CatchUp {
    private CatchUp() { }

    /** an Xtream service: where it is, the login, and its clock's time zone */
    static final class Xt { String base = "", user = "", pass = "", tz = ""; }

    private static final Pattern GET_PHP = Pattern.compile("(?i)^(https?://[^/?#]+)(?:/[^?#]*)?/get\\.php\\?(.*)$");
    private static final Pattern LIVE_URL = Pattern.compile("(?i)^(https?://[^/?#]+)/(?:live/)?([^/?#]+)/([^/?#]+)/(\\d+)(?:\\.(\\w+))?$");

    /** the service's address and login from the playlist link, or null */
    static Xt fromPlaylist(String m3u) {
        Matcher m = GET_PHP.matcher(m3u == null ? "" : m3u.trim());
        if (!m.find()) return null;
        Xt x = new Xt(); x.base = m.group(1);
        for (String kv : m.group(2).split("&")) {
            int e = kv.indexOf('=');
            if (e <= 0) continue;
            String k = kv.substring(0, e), v = android.net.Uri.decode(kv.substring(e + 1));
            if (k.equalsIgnoreCase("username")) x.user = v; else if (k.equalsIgnoreCase("password")) x.pass = v;
        }
        return x.user.isEmpty() || x.pass.isEmpty() ? null : x;
    }
    /** {base, user, pass, streamId, extension} from a channel's address in Xtream form, or null */
    static String[] fromChannel(String url) {
        Matcher m = LIVE_URL.matcher(url == null ? "" : url.trim());
        if (!m.find()) return null;
        return new String[] { m.group(1), m.group(2), m.group(3), m.group(4), m.group(5) == null ? "" : m.group(5) };
    }

    /* ---------------------------------------------------------------- the Xtream service's own lists */

    private static String api(Xt x, String action) {
        try {
            return x.base + "/player_api.php?username=" + URLEncoder.encode(x.user, "UTF-8") + "&password=" + URLEncoder.encode(x.pass, "UTF-8") + (action == null ? "" : "&action=" + action);
        } catch (Exception e) { return x.base; }
    }

    /** how many streams the account may play at once (user_info.max_connections); 0 if it doesn't say */
    static int maxConnections(Xt x) {
        try {
            HttpURLConnection c = LiveTv.open(api(x, null), null, null);
            try {
                if (c.getResponseCode() != 200) return 0;
                JSONObject o = new JSONObject(slurp(c));
                JSONObject ui = o.optJSONObject("user_info");
                if (ui == null) return 0;
                try { return Integer.parseInt(ui.optString("max_connections", "0").trim()); } catch (Exception e) { return 0; }
            } finally { c.disconnect(); }
        } catch (Exception e) { return 0; }
    }
    /** the service's time zone ("" if it doesn't say) */
    static String timezone(Xt x) {
        try {
            HttpURLConnection c = LiveTv.open(api(x, null), null, null);
            try {
                if (c.getResponseCode() != 200) return "";
                JSONObject o = new JSONObject(slurp(c));
                JSONObject si = o.optJSONObject("server_info");
                return si == null ? "" : si.optString("timezone");
            } finally { c.disconnect(); }
        } catch (Exception e) { return ""; }
    }

    /** stream id → days of archive, for the channels that keep one (read as a stream: the list can be large) */
    static void archives(Xt x, Map<String, Integer> out) throws Exception {
        HttpURLConnection c = LiveTv.open(api(x, "get_live_streams"), null, null);
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            android.util.JsonReader r = new android.util.JsonReader(new InputStreamReader(new java.io.BufferedInputStream(c.getInputStream(), 65536), "UTF-8"));
            r.setLenient(true);
            if (r.peek() != android.util.JsonToken.BEGIN_ARRAY) { r.close(); return; }
            r.beginArray();
            while (r.hasNext()) {
                if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) { r.skipValue(); continue; }
                String id = ""; int arch = 0, days = 0;
                r.beginObject();
                while (r.hasNext()) {
                    String k = r.nextName();
                    android.util.JsonToken t = r.peek();
                    if (t == android.util.JsonToken.NULL) { r.nextNull(); continue; }
                    if ((k.equals("stream_id") || k.equals("tv_archive") || k.equals("tv_archive_duration")) && (t == android.util.JsonToken.NUMBER || t == android.util.JsonToken.STRING)) {
                        String v = r.nextString();
                        if (k.equals("stream_id")) id = v;
                        else { int n = 0; try { n = (int) Double.parseDouble(v); } catch (Exception ignored) { } if (k.equals("tv_archive")) arch = n; else days = n; }
                    } else r.skipValue();
                }
                r.endObject();
                if (arch > 0 && !id.isEmpty()) out.put(id, Math.max(1, days));
            }
            r.close();
        } finally { c.disconnect(); }
    }

    /** a past show: [start ms, end ms, title, description, server-local start "yyyy-MM-dd HH:mm:ss"] */
    static final class Show { long start, end; String title = "", desc = "", local = ""; }

    /** the channel's shows from its archive (the service's own guide, past ones), newest first */
    static List<Show> pastShows(Xt x, String streamId, int days) throws Exception {
        List<Show> out = new ArrayList<>();
        HttpURLConnection c = LiveTv.open(api(x, "get_simple_data_table&stream_id=" + streamId), null, null);
        JSONObject o;
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            o = new JSONObject(slurp(c));
        } finally { c.disconnect(); }
        JSONArray l = o.optJSONArray("epg_listings");
        long now = System.currentTimeMillis(), oldest = now - Math.max(1, days) * 86400000L;
        if (l != null) for (int i = 0; i < l.length(); i++) {
            JSONObject e = l.optJSONObject(i);
            if (e == null) continue;
            Show s = new Show();
            s.start = e.optLong("start_timestamp") * 1000L; s.end = e.optLong("stop_timestamp") * 1000L;
            if (s.start <= 0 || s.start > now || s.end < oldest) continue;
            if (e.has("has_archive") && e.optInt("has_archive", 1) == 0 && s.end <= now) continue;
            s.title = b64(e.optString("title")); s.desc = b64(e.optString("description"));
            s.local = e.optString("start");
            out.add(s);
        }
        java.util.Collections.sort(out, new java.util.Comparator<Show>() { @Override public int compare(Show a, Show b) { return Long.compare(b.start, a.start); } });
        return out;
    }
    private static String b64(String s) {
        if (s == null || s.isEmpty()) return "";
        try { return new String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), "UTF-8").trim(); } catch (Exception e) { return s; }
    }
    private static String slurp(HttpURLConnection c) throws Exception {
        java.io.InputStream in = c.getInputStream();
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[32768]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    /* ---------------------------------------------------------------- the address that plays a past show */

    /**
     * The address for [start, end) on this channel, or null if it has no catch-up.
     * type: the playlist's catchup type ("xc" for an Xtream archive); source: catchup-source; local: the
     * service's own start time text when known (Xtream). hls: the HLS form (for Xtream, also offered as .ts).
     */
    static String url(String chUrl, String type, String source, Xt x, String streamId, long start, long end, String local, boolean hls) {
        long now = System.currentTimeMillis();
        if (end <= start) end = start + 3600000L;
        long dur = Math.max(1, end - start) / 1000L;                            // seconds
        String t = type == null ? "" : type.toLowerCase(Locale.US);
        if ((t.isEmpty() || t.equals("xc")) && x != null && streamId != null && !streamId.isEmpty()) {
            String when = xtStart(start, local, x.tz);
            long minutes = (dur + 59) / 60 + 2;                                     // (a little extra at the end)
            return x.base + "/timeshift/" + enc(x.user) + "/" + enc(x.pass) + "/" + minutes + "/" + when + "/" + streamId + (hls ? ".m3u8" : ".ts");
        }
        if (t.equals("xc") && x == null) {
            String[] p = fromChannel(chUrl);
            if (p == null) return null;
            Xt y = new Xt(); y.base = p[0]; y.user = p[1]; y.pass = p[2];
            return url(chUrl, "xc", source, y, p[3], start, end, local, hls);
        }
        if (t.equals("default")) return source == null || source.isEmpty() ? null : fill(source, start, end, now, chUrl);
        if (t.equals("append")) return source == null || source.isEmpty() ? null : chUrl + fill(source, start, end, now, chUrl);
        if (t.equals("shift") || t.equals("timeshift")) return chUrl + (chUrl.contains("?") ? "&" : "?") + "utc=" + start / 1000 + "&lutc=" + now / 1000;
        if (t.equals("flussonic") || t.equals("flussonic-hls") || t.equals("flussonic-ts") || t.equals("fs")) {
            String s = start / 1000 + "";
            if (chUrl.matches("(?i).*/(index|video|mono)\\.m3u8.*")) return chUrl.replaceFirst("(?i)/(index|video|mono)\\.m3u8", "/$1-" + s + "-" + dur + ".m3u8");
            if (chUrl.matches("(?i).*/mpegts.*")) return chUrl.replaceFirst("(?i)/mpegts", "/timeshift_abs-" + s + ".ts");
            return chUrl.replaceFirst("(?i)(\\.m3u8)", "-" + s + "-" + dur + "$1");
        }
        return null;
    }
    /** the Xtream start as "yyyy-MM-dd:HH-mm" in the service's time */
    static String xtStart(long start, String local, String tz) {
        if (local != null && local.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}.*")) return local.substring(0, 10) + ":" + local.substring(11, 13) + "-" + local.substring(14, 16);
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US);
        f.setTimeZone(tz == null || tz.isEmpty() ? TimeZone.getDefault() : TimeZone.getTimeZone(tz));
        return f.format(new Date(start));
    }
    /** a catchup-source with its placeholders filled in (Kodi's set: {utc} {utcend} {lutc} {start} {end} {duration} {offset:N} {Y} {m} {d} {H} {M} {S}, and ${start} ${end} ${timestamp}) */
    static String fill(String src, long start, long end, long now, String chUrl) {
        long s = start / 1000, e = end / 1000, n = now / 1000, dur = Math.max(1, e - s);
        java.util.Calendar c = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")); c.setTimeInMillis(start);
        java.util.Calendar ce = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")); ce.setTimeInMillis(end);
        String out = src
                .replace("{utc}", "" + s).replace("${start}", "" + s).replace("{start}", "" + s)
                .replace("{utcend}", "" + e).replace("${end}", "" + e).replace("{end}", "" + e)
                .replace("{lutc}", "" + n).replace("${now}", "" + n).replace("${timestamp}", "" + n).replace("{timestamp}", "" + n)
                .replace("{duration}", "" + dur).replace("${duration}", "" + dur)
                .replace("{Y}", String.format(Locale.US, "%04d", c.get(java.util.Calendar.YEAR))).replace("{m}", String.format(Locale.US, "%02d", c.get(java.util.Calendar.MONTH) + 1))
                .replace("{d}", String.format(Locale.US, "%02d", c.get(java.util.Calendar.DAY_OF_MONTH))).replace("{H}", String.format(Locale.US, "%02d", c.get(java.util.Calendar.HOUR_OF_DAY)))
                .replace("{M}", String.format(Locale.US, "%02d", c.get(java.util.Calendar.MINUTE))).replace("{S}", String.format(Locale.US, "%02d", c.get(java.util.Calendar.SECOND)))
                .replace("{Ye}", String.format(Locale.US, "%04d", ce.get(java.util.Calendar.YEAR))).replace("{me}", String.format(Locale.US, "%02d", ce.get(java.util.Calendar.MONTH) + 1))
                .replace("{de}", String.format(Locale.US, "%02d", ce.get(java.util.Calendar.DAY_OF_MONTH))).replace("{He}", String.format(Locale.US, "%02d", ce.get(java.util.Calendar.HOUR_OF_DAY)))
                .replace("{Me}", String.format(Locale.US, "%02d", ce.get(java.util.Calendar.MINUTE))).replace("{Se}", String.format(Locale.US, "%02d", ce.get(java.util.Calendar.SECOND)));
        // {duration:60} → minutes; {offset:1} → seconds ago (divided)
        Matcher m = Pattern.compile("\\{duration:(\\d+)\\}").matcher(out); StringBuffer sb = new StringBuffer();
        while (m.find()) { long d = Math.max(1, Long.parseLong(m.group(1))); m.appendReplacement(sb, "" + (dur + d - 1) / d); }
        m.appendTail(sb); out = sb.toString();
        m = Pattern.compile("\\{offset:(\\d+)\\}").matcher(out); sb = new StringBuffer();
        while (m.find()) { long d = Math.max(1, Long.parseLong(m.group(1))); m.appendReplacement(sb, "" + Math.max(0, n - s) / d); }
        m.appendTail(sb); out = sb.toString();
        // a template without its own server is relative to the channel's
        if (!out.matches("(?i)^https?://.*") && !out.startsWith("?") && !out.startsWith("&") && chUrl != null) {
            try { out = new java.net.URL(new java.net.URL(chUrl), out).toString(); } catch (Exception ignored) { }
        }
        return out;
    }
    private static String enc(String s) { return s.replace("/", "%2F"); }

    /* ---------------------------------------------------------------- remembered Xtream services */

    static List<Xt> readServices(File dir) {
        List<Xt> l = new ArrayList<>();
        try {
            File f = new File(dir, "xtream.json");
            if (!f.exists()) return l;
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f); int n = 0; while (n < b.length) { int k = in.read(b, n, b.length - n); if (k < 0) break; n += k; } in.close();
            JSONArray a = new JSONArray(new String(b, 0, n, "UTF-8"));
            for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o == null) continue; Xt x = new Xt(); x.base = o.optString("base"); x.user = o.optString("user"); x.pass = o.optString("pass"); x.tz = o.optString("tz"); l.add(x); }
        } catch (Exception ignored) { }
        return l;
    }
    static void writeServices(File dir, List<Xt> l) {
        try {
            JSONArray a = new JSONArray();
            for (Xt x : l) a.put(new JSONObject().put("base", x.base).put("user", x.user).put("pass", x.pass).put("tz", x.tz));
            java.io.FileOutputStream o = new java.io.FileOutputStream(new File(dir, "xtream.json")); o.write(a.toString().getBytes("UTF-8")); o.close();
        } catch (Exception ignored) { }
    }
}
