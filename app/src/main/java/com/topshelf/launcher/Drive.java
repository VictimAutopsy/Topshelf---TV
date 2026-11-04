package com.topshelf.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Recordings to Google Drive. You make a Google Cloud app of your own (the phone page walks you through
 * it), and the TV signs in with Google's TV sign-in (a code you enter at google.com/device). Its access is
 * "drive.file": only files TopShelf made itself, in a "TopShelf Recordings" folder.
 *
 * Pieces of a recording are uploaded as they're recorded (each a few seconds of video); when a recording's
 * finished and all of it is on Drive, the copy on the TV is deleted (a setting) and only a small playlist
 * pointing at Drive stays, so it plays from Drive. Anything that fails is tried again later.
 */
final class Drive implements Recorder.Uploader {
    private static final String P = "gd.";
    static final String API = "https://www.googleapis.com/drive/v3/files";
    private final MainActivity act;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedHashSet<String> queue = new LinkedHashSet<>();
    private Thread worker;

    volatile String setupKey = ""; volatile long setupUntil;
    volatile String setupState = "";
    volatile String userCode = "", verifyUrl = "";
    private volatile int pollGen;

    Drive(MainActivity a) {
        act = a;
        prefs = a.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE);
        Recorder.uploader = this;
        NativePlayer.driveToken = new NativePlayer.Token() { @Override public String get() { try { return token(); } catch (Exception e) { return ""; } } };
        // catch up on anything recorded while offline (in a minute, once the app's settled)
        main.postDelayed(new Runnable() { @Override public void run() { catchUp(); } }, 60000);
    }

    // ================================================================== settings

    boolean connected() { return !prefs.getString(P + "refresh", "").isEmpty(); }
    boolean uploadOn() { return connected() && prefs.getBoolean(P + "on", true); }
    boolean deleteLocal() { return prefs.getBoolean(P + "delLocal", true); }
    void toggle(String what) {
        if (what.equals("on")) prefs.edit().putBoolean(P + "on", !prefs.getBoolean(P + "on", true)).apply();
        else if (what.equals("delLocal")) prefs.edit().putBoolean(P + "delLocal", !deleteLocal()).apply();
        if (uploadOn()) catchUp();
    }
    JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            o.put("connected", connected()); o.put("email", prefs.getString(P + "email", ""));
            o.put("on", prefs.getBoolean(P + "on", true)); o.put("delLocal", deleteLocal());
            o.put("hasApp", !prefs.getString(P + "id", "").isEmpty());
            o.put("state", setupState); o.put("code", userCode); o.put("url", verifyUrl);
            o.put("free", prefs.getLong(P + "free", -1)); o.put("err", prefs.getString(P + "err", ""));
            int waiting; synchronized (queue) { waiting = queue.size(); } o.put("queue", waiting);
        } catch (Exception ignored) { }
        return o;
    }
    void disconnect() {
        final String rt = prefs.getString(P + "refresh", "");
        SharedPreferences.Editor e = prefs.edit();
        for (String k : new String[] { "refresh", "access", "exp", "email", "root", "err", "free" }) e.remove(P + k);
        e.apply();
        if (!rt.isEmpty()) new Thread(new Runnable() { @Override public void run() { try { post("https://oauth2.googleapis.com/revoke", "token=" + enc(rt)); } catch (Exception ignored) { } } }).start();
        Diag.ev("drive: disconnected");
    }

    // ================================================================== setup (from the phone)

    String newSetupKey() {
        if (!setupKey.isEmpty() && System.currentTimeMillis() < setupUntil) { setupUntil = System.currentTimeMillis() + 30 * 60000L; return setupKey; }
        String a = "abcdefghjkmnpqrstuvwxyz23456789"; StringBuilder b = new StringBuilder();
        java.security.SecureRandom r = new java.security.SecureRandom();
        for (int i = 0; i < 10; i++) b.append(a.charAt(r.nextInt(a.length())));
        setupKey = b.toString(); setupUntil = System.currentTimeMillis() + 30 * 60000L;
        setupState = connected() ? "Connected" : "Waiting for your phone";
        return setupKey;
    }
    String setupUrl() {
        if (act.link == null || act.link.port() == 0 || LinkServer.localIp().isEmpty()) return "";
        return "http://" + LinkServer.localIp() + ":" + act.link.port() + "/drive?k=" + newSetupKey();
    }
    boolean setupOk(String k) { return k != null && !setupKey.isEmpty() && setupKey.equals(k) && System.currentTimeMillis() < setupUntil; }

    /** step 1: the Google app's Client ID and secret; then Google's TV sign-in starts (a code to enter) */
    String saveApp(String id, String secret) {
        id = id == null ? "" : id.trim(); secret = secret == null ? "" : secret.trim();
        if (!id.endsWith(".apps.googleusercontent.com")) return "That doesn't look like a Client ID — it ends in .apps.googleusercontent.com. Copy it again from the Clients page.";
        if (secret.length() < 10) return "Paste the Client secret too (it's on the same page, starting GOCSPX-).";
        prefs.edit().putString(P + "id", id).putString(P + "secret", secret).apply();
        return startSignIn();
    }
    String startSignIn() {
        try {
            JSONObject o = new JSONObject(post("https://oauth2.googleapis.com/device/code",
                    "client_id=" + enc(prefs.getString(P + "id", "")) + "&scope=" + enc("https://www.googleapis.com/auth/drive.file")));
            if (o.has("error")) return "Google said: " + o.optString("error_description", o.optString("error")) + (o.optString("error").equals("invalid_client") ? " — check that the client's type is “TVs and Limited Input devices”." : "");
            userCode = o.optString("user_code"); verifyUrl = o.optString("verification_url", "https://www.google.com/device");
            setupState = "Enter the code " + userCode + " at " + verifyUrl.replace("https://", "");
            final String dc = o.optString("device_code");
            final long interval = Math.max(5, o.optLong("interval", 5)) * 1000L, until = System.currentTimeMillis() + o.optLong("expires_in", 1800) * 1000L;
            final int gen = ++pollGen;
            new Thread(new Runnable() { @Override public void run() { poll(gen, dc, interval, until); } }, "drive-signin").start();
            refreshUi();
            return "";
        } catch (Exception e) { return "Couldn't reach Google: " + e.getMessage(); }
    }
    private void poll(int gen, String dc, long interval, long until) {
        long wait = interval;
        while (gen == pollGen && System.currentTimeMillis() < until) {
            try { Thread.sleep(wait); } catch (InterruptedException e) { return; }
            try {
                JSONObject o = new JSONObject(post("https://oauth2.googleapis.com/token",
                        "client_id=" + enc(prefs.getString(P + "id", "")) + "&client_secret=" + enc(prefs.getString(P + "secret", ""))
                        + "&device_code=" + enc(dc) + "&grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code")));
                String err = o.optString("error");
                if (err.equals("authorization_pending")) continue;
                if (err.equals("slow_down")) { wait += 5000; continue; }
                if (!err.isEmpty()) { setupState = err.equals("access_denied") ? "You chose not to allow it — start again" : "Google said: " + o.optString("error_description", err); userCode = ""; refreshUi(); return; }
                prefs.edit().putString(P + "refresh", o.optString("refresh_token")).putString(P + "access", o.optString("access_token"))
                        .putLong(P + "exp", System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000L).remove(P + "err").apply();
                userCode = "";
                try { about(); root(); } catch (Exception e) { Diag.ev("drive: after sign-in " + e.getMessage()); }
                setupState = "Connected" + (prefs.getString(P + "email", "").isEmpty() ? "" : " · " + prefs.getString(P + "email", ""));
                Diag.ev("drive: connected");
                refreshUi();
                catchUp();
                return;
            } catch (Exception e) { /* (a network blip: keep asking) */ }
        }
        if (gen == pollGen && !connected()) { setupState = "The code expired — start again"; userCode = ""; refreshUi(); }
    }
    private void refreshUi() {
        main.post(new Runnable() { @Override public void run() { NativeHome h = act.nativeHome(); if (h != null) h.setupRefresh(); } });
    }

    // ================================================================== Google's API

    synchronized String token() throws Exception {
        if (!connected()) throw new Exception("not connected to Google Drive");
        if (System.currentTimeMillis() < prefs.getLong(P + "exp", 0) - 120000) return prefs.getString(P + "access", "");
        JSONObject o = new JSONObject(post("https://oauth2.googleapis.com/token",
                "client_id=" + enc(prefs.getString(P + "id", "")) + "&client_secret=" + enc(prefs.getString(P + "secret", ""))
                + "&refresh_token=" + enc(prefs.getString(P + "refresh", "")) + "&grant_type=refresh_token"));
        if (o.has("error")) {
            String e = o.optString("error");
            if (e.equals("invalid_grant")) { prefs.edit().putString(P + "err", "Google signed TopShelf out (if your Google app is still in “Testing”, set it to “In production”). Set up Drive again.").remove(P + "refresh").apply(); }
            throw new Exception("Google: " + o.optString("error_description", e));
        }
        prefs.edit().putString(P + "access", o.optString("access_token")).putLong(P + "exp", System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000L).apply();
        return o.optString("access_token");
    }
    private void about() throws Exception {
        JSONObject o = new JSONObject(api("GET", "https://www.googleapis.com/drive/v3/about?fields=user(emailAddress),storageQuota", null, null));
        JSONObject q = o.optJSONObject("storageQuota");
        SharedPreferences.Editor e = prefs.edit();
        if (o.optJSONObject("user") != null) e.putString(P + "email", o.optJSONObject("user").optString("emailAddress"));
        if (q != null && q.has("limit")) e.putLong(P + "free", q.optLong("limit") - q.optLong("usage"));
        e.apply();
    }
    /** the "TopShelf Recordings" folder (made once) */
    private synchronized String root() throws Exception {
        String id = prefs.getString(P + "root", "");
        if (!id.isEmpty()) {
            try { JSONObject o = new JSONObject(api("GET", API + "/" + id + "?fields=id,trashed", null, null)); if (!o.optBoolean("trashed")) return id; }
            catch (Exception e) { if (!String.valueOf(e.getMessage()).contains("404")) throw e; }
        }
        id = folder("TopShelf Recordings", null);
        prefs.edit().putString(P + "root", id).apply();
        return id;
    }
    private String folder(String name, String parent) throws Exception {
        JSONObject m = new JSONObject(); m.put("name", name); m.put("mimeType", "application/vnd.google-apps.folder");
        if (parent != null) m.put("parents", new org.json.JSONArray().put(parent));
        return new JSONObject(api("POST", API + "?fields=id", "application/json; charset=UTF-8", m.toString().getBytes("UTF-8"))).getString("id");
    }
    /** uploads one file (multipart: its name, then its bytes) and returns its Drive id */
    private String upload(File f, String name, String parent, String mime) throws Exception {
        String bnd = "ts" + Long.toHexString(System.nanoTime());
        JSONObject m = new JSONObject(); m.put("name", name); m.put("parents", new org.json.JSONArray().put(parent));
        byte[] head = ("--" + bnd + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + m + "\r\n--" + bnd + "\r\nContent-Type: " + mime + "\r\n\r\n").getBytes("UTF-8");
        byte[] tail = ("\r\n--" + bnd + "--\r\n").getBytes("UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id").openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(15000); c.setReadTimeout(60000);
        c.setRequestProperty("Authorization", "Bearer " + token());
        c.setRequestProperty("Content-Type", "multipart/related; boundary=" + bnd);
        long len = head.length + f.length() + tail.length;
        c.setFixedLengthStreamingMode(len);
        try (OutputStream o = c.getOutputStream(); InputStream in = new FileInputStream(f)) {
            o.write(head);
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            o.write(tail);
        }
        String r = read(c);
        return new JSONObject(r).getString("id");
    }
    void deleteFile(String id) throws Exception { api("DELETE", API + "/" + id, null, null); }

    private String api(String method, String url, String type, byte[] body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setRequestProperty("Authorization", "Bearer " + token());
        if (body != null) { c.setDoOutput(true); c.setRequestProperty("Content-Type", type); try (OutputStream o = c.getOutputStream()) { o.write(body); } }
        return read(c);
    }
    private static String post(String url, String form) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(15000); c.setReadTimeout(20000);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream o = c.getOutputStream()) { o.write(form.getBytes("UTF-8")); }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String s = in == null ? "{}" : slurp(in);
        c.disconnect();
        return s.isEmpty() ? "{}" : s;
    }
    private static String read(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String s = in == null ? "" : slurp(in);
        c.disconnect();
        if (code >= 400) {
            String msg = s;
            try { msg = new JSONObject(s).getJSONObject("error").optString("message", s); } catch (Exception ignored) { }
            throw new Exception(code + " " + msg);
        }
        return s;
    }
    private static String slurp(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }
    private static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; } }

    // ================================================================== uploading recordings

    @Override public void recordingPiece(JSONObject rec, File dir, Capture.Seg seg) { if (uploadOn()) enqueue(rec.optString("id")); }
    @Override public void recordingDone(JSONObject rec, File dir) { if (uploadOn()) enqueue(rec.optString("id")); }
    @Override public void deleted(final JSONObject rec) {
        final String folder = rec.optString("driveFolder");
        if (folder.isEmpty() || !connected()) return;
        new Thread(new Runnable() { @Override public void run() {
            try { deleteFile(folder); Diag.ev("drive: deleted " + rec.optString("title")); } catch (Exception e) { Diag.ev("drive: delete failed " + e.getMessage()); }
        } }).start();
    }

    void catchUp() {
        if (!uploadOn()) return;
        for (JSONObject r : Recorder.get(act).list()) {
            String st = r.optString("status");
            if ((st.equals("done") || st.equals("recording")) && !r.optBoolean("cloud") && Recorder.hasData(r)) enqueue(r.optString("id"));
        }
    }
    private void enqueue(String id) {
        synchronized (queue) {
            queue.add(id);
            if (worker == null || !worker.isAlive()) {
                worker = new Thread(new Runnable() { @Override public void run() { work(); } }, "drive-upload");
                worker.setPriority(Thread.MIN_PRIORITY);
                worker.start();
            }
        }
    }
    private void work() {
        int fails = 0;
        while (true) {
            String id;
            synchronized (queue) {
                if (queue.isEmpty()) { worker = null; return; }
                id = queue.iterator().next(); queue.remove(id);
            }
            try {
                sync(id);
                fails = 0;
            } catch (Exception e) {
                fails++;
                String m = String.valueOf(e.getMessage());
                Diag.ev("drive: upload failed (" + m + "), trying again");
                Recorder.get(act).put(id, "cloudErr", m.contains("storageQuotaExceeded") || m.toLowerCase(Locale.US).contains("quota") ? "Google Drive is full" : m);
                // try again later (sooner while it's still recording)
                final String fid = id;
                main.postDelayed(new Runnable() { @Override public void run() { enqueue(fid); } }, Math.min(15 * 60000L, 30000L * fails));
            }
        }
    }

    /** puts whatever's new of one recording on Drive; when it's all there and finished, plays from Drive */
    private void sync(String id) throws Exception {
        Recorder rc = Recorder.get(act);
        JSONObject r = rc.find(id);
        if (r == null || r.optBoolean("cloud") || !uploadOn()) return;
        File dir = new File(r.optString("dir"));
        File pl = new File(dir, "index.m3u8");
        if (!pl.exists()) return;
        // which pieces are already up (drive.json: {folder, files: {name: id}})
        File mf = new File(dir, "drive.json");
        JSONObject map = mf.exists() ? new JSONObject(new String(java.nio.file.Files.readAllBytes(mf.toPath()), "UTF-8")) : new JSONObject();
        JSONObject files = map.optJSONObject("files"); if (files == null) { files = new JSONObject(); map.put("files", files); }
        String folder = map.optString("folder");
        if (folder.isEmpty()) {
            String name = r.optString("title") + " · " + new SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(new Date(r.optLong("start"))) + " · " + r.optString("n");
            folder = folder(name.replace('/', '-'), root());
            map.put("folder", folder);
            write(mf, map.toString());
            rc.put(id, "driveFolder", folder);
        }
        String text = new String(java.nio.file.Files.readAllBytes(pl.toPath()), "UTF-8");
        boolean finished = text.contains("#EXT-X-ENDLIST") && "done".equals(r.optString("status"));
        List<String> names = new ArrayList<>();
        for (String l : text.split("\\r?\\n")) {
            l = l.trim();
            if (!l.isEmpty() && !l.startsWith("#")) names.add(l);
            else if (l.startsWith("#EXT-X-MAP:")) { java.util.regex.Matcher m = java.util.regex.Pattern.compile("URI=\"([^\"]+)\"").matcher(l); if (m.find()) names.add(m.group(1)); }
        }
        int sent = 0;
        for (String n : names) {
            if (files.has(n)) continue;
            File f = new File(dir, n);
            if (!f.exists()) continue;
            files.put(n, upload(f, n, folder, n.endsWith(".mp4") ? "video/mp4" : "video/mp2t"));
            write(mf, map.toString());
            if (++sent % 20 == 0) rc.put(id, "cloudPieces", files.length());
            if (!uploadOn()) return;
        }
        rc.put(id, "cloudPieces", files.length());
        if (!finished) return;
        // all of it's there: the playlist (as a backup, with the pieces' names), then play from Drive
        for (String n : names) if (!files.has(n)) return;      // (a piece missing locally and on Drive: leave it as it is)
        if (!map.has("playlist")) { map.put("playlist", upload(pl, "index.m3u8", folder, "application/vnd.apple.mpegurl")); write(mf, map.toString()); }
        StringBuilder cloud = new StringBuilder();
        for (String raw : text.split("\\r?\\n")) {
            String l = raw.trim();
            if (!l.isEmpty() && !l.startsWith("#")) cloud.append(API).append('/').append(files.getString(l)).append("?alt=media");
            else if (l.startsWith("#EXT-X-MAP:")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("URI=\"([^\"]+)\"").matcher(l);
                cloud.append(m.find() && files.has(m.group(1)) ? l.replace(m.group(1), API + "/" + files.getString(m.group(1)) + "?alt=media") : l);
            } else cloud.append(l);
            cloud.append('\n');
        }
        try { about(); } catch (Exception ignored) { }
        if (deleteLocal() && id.equals(Recorder.watching)) {
            // (it's being watched from the TV's copy: move it to Drive once you've stopped)
            final String fid = id;
            main.postDelayed(new Runnable() { @Override public void run() { enqueue(fid); } }, 5 * 60000L);
            return;
        }
        if (deleteLocal()) {
            // keep just the small playlist (on the TV itself, so it plays even with the USB drive unplugged)
            File nd = new File(new File(act.getFilesDir(), "cloud"), id);
            nd.mkdirs();
            write(new File(nd, "index.m3u8"), cloud.toString());
            write(new File(nd, "drive.json"), map.toString());
            rc.put(id, "dir", nd.getAbsolutePath());
            rc.put(id, "cloud", true);
            rc.put(id, "cloudErr", "");
            if (!nd.getAbsolutePath().equals(dir.getAbsolutePath())) Recorder.wipe(dir);
            Diag.ev("drive: " + r.optString("title") + " is on Drive (" + files.length() + " pieces); removed from the TV");
        } else {
            write(new File(dir, "cloud.m3u8"), cloud.toString());
            rc.put(id, "cloud", true);
            rc.put(id, "cloudErr", "");
            Diag.ev("drive: " + r.optString("title") + " is on Drive (" + files.length() + " pieces); kept on the TV too");
        }
    }
    private static void write(File f, String s) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        java.nio.file.Files.write(tmp.toPath(), s.getBytes("UTF-8"));
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
    }
}
