package com.topshelf.launcher;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Updates and reports through a private GitHub repository (owner/repo + an access token that only
 * reaches that repository):
 *  - latest.json + TopShelf.apk in the repository: the TV offers the newer version and installs it
 *    (Android asks for one confirmation);
 *  - diagnostic reports are uploaded to reports/ there.
 */
final class Updater {
    static final String DEFAULT_REPO = "VictimAutopsy/topshelf-updates";
    private static final String API = "https://api.github.com/repos/";

    interface Progress { void step(String state, int pct, String msg); }

    private final Context ctx;
    private final SharedPreferences prefs;

    Updater(Context ctx, SharedPreferences prefs) { this.ctx = ctx; this.prefs = prefs; }

    String token() { return prefs.getString("ghToken", ""); }
    String repo() { String r = prefs.getString("ghRepo", ""); return r.isEmpty() ? DEFAULT_REPO : r; }
    void setToken(String t) { prefs.edit().putString("ghToken", t == null ? "" : t.trim()).apply(); }
    void setRepo(String r) { prefs.edit().putString("ghRepo", r == null ? "" : r.trim()).apply(); }

    private HttpURLConnection open(String path, String method, boolean raw) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(API + repo() + "/" + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Authorization", "Bearer " + token());
        c.setRequestProperty("Accept", raw ? "application/vnd.github.raw" : "application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        c.setRequestProperty("User-Agent", "TopShelf");
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[16384]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String err(HttpURLConnection c, int code) {
        String body = "";
        try { body = readAll(c.getErrorStream()); } catch (Exception ignored) { }
        if (code == 401) return "the access token isn't valid (or has expired)";
        if (code == 403) return "the access token can't reach that repository";
        if (code == 404) return "repository or file not found (check the token's repository access)";
        return "GitHub said " + code + (body.length() > 0 ? ": " + body.substring(0, Math.min(120, body.length())) : "");
    }

    /** {ok, code, name, notes, newer, current} or {ok:false, error} */
    String check(int currentCode) {
        JSONObject o = new JSONObject();
        try {
            if (token().isEmpty()) { o.put("ok", false); o.put("error", "no token"); return o.toString(); }
            HttpURLConnection c = open("contents/latest.json", "GET", true);
            int code = c.getResponseCode();
            if (code != 200) { o.put("ok", false); o.put("error", err(c, code)); return o.toString(); }
            JSONObject l = new JSONObject(readAll(c.getInputStream()));
            o.put("ok", true);
            o.put("code", l.optInt("code"));
            o.put("name", l.optString("name"));
            o.put("notes", l.optString("notes"));
            o.put("apk", l.optString("apk", "TopShelf.apk"));
            o.put("current", currentCode);
            o.put("newer", l.optInt("code") > currentCode);
            JSONObject fb = l.optJSONObject("fallback");
            // (the version before the latest, rebuilt with a newer number: for going back)
            if (fb != null && fb.optInt("code") > currentCode) o.put("fallback", fb);
        } catch (Exception e) {
            try { o.put("ok", false); o.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) { }
        }
        return o.toString();
    }

    /** Puts a text file into the repository (reports/…). Returns "" or an error. */
    String upload(String path, String text, String message) { return uploadBytes(path, text.getBytes(StandardCharsets.UTF_8), message); }

    /* one at a time, and again if GitHub refused because another upload (the screenshot) landed first:
       two at once made the report itself fail while its screenshot went through */
    synchronized String uploadBytes(String path, byte[] data, String message) {
        String r = "";
        for (int i = 0; i < 4; i++) {
            r = uploadOnce(path, data, message);
            if (r.isEmpty() || !(r.contains("409") || r.contains("422") || r.toLowerCase(java.util.Locale.US).contains("conflict") || r.contains("timeout") || r.contains("reset"))) return r;
            try { Thread.sleep(1500L * (i + 1)); } catch (InterruptedException ignored) { return r; }
        }
        return r;
    }
    private String uploadOnce(String path, byte[] data, String message) {
        try {
            if (token().isEmpty()) return "no token";
            JSONObject body = new JSONObject();
            body.put("message", message);
            body.put("content", Base64.encodeToString(data, Base64.NO_WRAP));
            HttpURLConnection c = open("contents/" + path, "PUT", false);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.close();
            int code = c.getResponseCode();
            if (code == 200 || code == 201) { readAll(c.getInputStream()); return ""; }
            return err(c, code);
        } catch (Exception e) { return String.valueOf(e.getMessage()); }
    }

    /** May this app install updates? (Android's "install unknown apps" for TopShelf) */
    boolean canInstall() {
        return ctx.getPackageManager().canRequestPackageInstalls();
    }

    void openInstallPermission() {
        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { ctx.startActivity(i); }
        catch (Exception e) {
            Intent j = new Intent(Settings.ACTION_SECURITY_SETTINGS);
            j.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { ctx.startActivity(j); } catch (Exception ignored) { }
        }
    }

    /** Downloads the APK and hands it to Android's installer (which asks to confirm). Runs on a worker thread. */
    void install(String apkPath, Progress p) {
        File f = new File(ctx.getCacheDir(), "update.apk");
        try {
            p.step("download", 0, "");
            HttpURLConnection c = open("contents/" + apkPath, "GET", true);
            int code = c.getResponseCode();
            if (code != 200) { p.step("error", 0, err(c, code)); return; }
            long len = c.getContentLength();
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(f);
            byte[] b = new byte[65536]; int n; long got = 0; int last = -1;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n); got += n;
                int pct = len > 0 ? (int) (got * 100 / len) : 0;
                if (pct / 10 != last / 10) { last = pct; p.step("download", pct, ""); }
            }
            out.close(); in.close();
            if (len > 0 && got != len) { p.step("error", 0, "download was cut short"); return; }
            if (ctx.getPackageManager().getPackageArchiveInfo(f.getPath(), 0) == null) { p.step("error", 0, "the downloaded file isn't an app"); return; }

            p.step("install", 100, "");
            PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(ctx.getPackageName());
            sp.setSize(f.length());
            if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            int sid = pi.createSession(sp);
            PackageInstaller.Session s = pi.openSession(sid);
            OutputStream so = s.openWrite("TopShelf.apk", 0, f.length());
            InputStream fi = new FileInputStream(f);
            while ((n = fi.read(b)) > 0) so.write(b, 0, n);
            s.fsync(so); so.close(); fi.close();
            Intent cb = new Intent(ctx, InstallReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pend = PendingIntent.getBroadcast(ctx, sid, cb, flags);
            s.commit(pend.getIntentSender());
            s.close();
        } catch (Exception e) {
            p.step("error", 0, String.valueOf(e.getMessage()));
        }
    }

    /** Android's answer about an install: shows its confirmation screen when it needs one. */
    public static final class InstallReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { c.startActivity(confirm); } catch (Exception ignored) { }
                }
            } else if (st != PackageInstaller.STATUS_SUCCESS) {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                c.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE).edit()
                        .putString("updError", "install " + st + (msg != null ? ": " + msg : "")).apply();
                MainActivity a = MainActivity.current();
                if (a != null) a.js("window.tsUpdate && tsUpdate('error',0," + JSONObject.quote(msg == null ? "the install didn't finish" : msg) + ")");
            }
        }
    }
}
