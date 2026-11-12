package com.topshelf.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phone ↔ TV link. Every TopShelf runs a tiny HTTP server on the home network and advertises
 * itself (mDNS, "_topshelf._tcp"), so a phone can find the TV, pair with a 4-digit code shown on
 * the TV, and then send commands (remote keys, typed text, "watch this", syncing lists).
 * Only paired devices (holding a token) can send commands.
 */
final class LinkServer {

    static final String TYPE = "_topshelf._tcp";
    static final int PORT = 8790;

    private final MainActivity act;
    private final SharedPreferences prefs;
    private final ExecutorService pool = new java.util.concurrent.ThreadPoolExecutor(3, 3, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.LinkedBlockingQueue<Runnable>(), new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());   // (nothing taken after stop)
    private final Map<String, String[]> pending = new ConcurrentHashMap<>();   // pair id -> {code, name, time}
    private final SecureRandom rnd = new SecureRandom();
    private volatile long lastPairStart;
    private volatile long pairLockedUntil;
    private final java.util.ArrayDeque<Long> wrongAt = new java.util.ArrayDeque<>();
    private volatile ServerSocket server;
    private volatile int port;
    private NsdManager.RegistrationListener reg;
    volatile String serviceName;

    LinkServer(MainActivity act) {
        this.act = act;
        this.prefs = act.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE);
    }

    static String deviceName(Context c) {
        String n = null;
        try { n = Settings.Global.getString(c.getContentResolver(), "device_name"); } catch (Exception ignored) { }
        if (n == null || n.trim().isEmpty()) n = Build.MODEL;
        return n;
    }

    int port() { return port; }

    void start() {
        Thread t = new Thread(new Runnable() { @Override public void run() { serve(); } }, "topshelf-link");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        try {
            NsdManager nsd = (NsdManager) act.getSystemService(Context.NSD_SERVICE);
            if (nsd != null && reg != null) nsd.unregisterService(reg);
        } catch (Exception ignored) { }
        pool.shutdownNow();
    }

    private void serve() {
        for (int p = PORT; p < PORT + 6 && server == null; p++) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(p));
                server = ss;
                port = p;
            } catch (Exception ignored) { }
        }
        if (server == null) return;
        advertise();
        while (!server.isClosed()) {
            try {
                final Socket s = server.accept();
                pool.execute(new Runnable() { @Override public void run() { handle(s); } });
            } catch (Exception e) {
                if (server.isClosed()) return;
            }
        }
    }

    private void advertise() {
        try {
            NsdManager nsd = (NsdManager) act.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) return;
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("TopShelf · " + deviceName(act));
            info.setServiceType(TYPE);
            info.setPort(port);
            reg = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo i) { serviceName = i.getServiceName(); }
                @Override public void onRegistrationFailed(NsdServiceInfo i, int e) { }
                @Override public void onServiceUnregistered(NsdServiceInfo i) { }
                @Override public void onUnregistrationFailed(NsdServiceInfo i, int e) { }
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg);
        } catch (Exception ignored) { }
    }

    /** This device's address on the home network (for typing it in by hand). */
    static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    // ---------------------------------------------------------------- HTTP

    private void handle(Socket s) {
        try {
            s.setSoTimeout(8000);
            InputStream in = s.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int b, state = 0;
            while ((b = in.read()) != -1) {                    // read up to the blank line
                head.write(b);
                state = (b == '\r' || b == '\n') ? state + 1 : 0;
                if (state >= 4 || head.size() > 16384) break;
            }
            String[] lines = head.toString("UTF-8").split("\r\n");
            if (lines.length == 0) { s.close(); return; }
            String[] rl = lines[0].split(" ");
            String method = rl.length > 0 ? rl[0] : "", path = rl.length > 1 ? rl[1] : "/";
            int len = 0;
            for (String l : lines) if (l.toLowerCase().startsWith("content-length:")) len = Math.min(256 * 1024, Integer.parseInt(l.substring(15).trim()));
            byte[] body = new byte[len];
            int got = 0;
            while (got < len) { int n = in.read(body, got, len - got); if (n < 0) break; got += n; }
            String res;
            int code = 200;
            try {
                res = route(method, path, new String(body, 0, got, "UTF-8"));
            } catch (SecurityException e) {
                code = 403; res = "{\"error\":\"not paired\"}";
            } catch (Exception e) {
                code = 400; res = "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
            }
            byte[] out = res.getBytes("UTF-8");
            OutputStream os = s.getOutputStream();
            String type = res.startsWith("<!doctype") ? "text/html" : "application/json";
            os.write(("HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\nContent-Type: " + type + "; charset=utf-8\r\n" +
                    "Content-Length: " + out.length + "\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            os.write(out);
            os.flush();
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    private String route(String method, String path, String body) throws Exception {
        if (path.startsWith("/fantasy")) return fantasy(method, path, body);
        if (path.startsWith("/drive")) return drive(method, path, body);
        if (path.startsWith("/addons")) return addons(method, path, body);
        if (path.startsWith("/trakt")) return trakt(method, path, body);
        if (path.startsWith("/ring")) return ring(method, path, body);
        if (path.startsWith("/info")) {
            JSONObject o = new JSONObject();
            o.put("app", "topshelf");
            o.put("name", deviceName(act));
            o.put("tv", act.isTv());
            return o.toString();
        }
        JSONObject in = body.isEmpty() ? new JSONObject() : new JSONObject(body);
        /* Pairing pauses for an hour after ten wrong codes (across every attempt): a 4-digit code could otherwise be
           worn down by something on the network starting pairing after pairing. */
        if ((path.startsWith("/pair/start") || path.startsWith("/pair/finish")) && System.currentTimeMillis() < pairLockedUntil)
            throw new Exception("pairing is paused for a while after too many wrong codes");
        if (path.startsWith("/pair/start")) {
            // (one new code every few seconds at most: anything on the network could otherwise keep popping it up)
            long now = System.currentTimeMillis();
            if (now - lastPairStart < 4000) throw new Exception("wait a moment and try again");
            lastPairStart = now;
            for (java.util.Iterator<Map.Entry<String, String[]>> it = pending.entrySet().iterator(); it.hasNext(); ) { if (now - Long.parseLong(it.next().getValue()[2]) > 180000) it.remove(); }
            String id = token(12), code = String.valueOf(1000 + rnd.nextInt(9000));
            String name = in.optString("name", "a phone");
            pending.put(id, new String[]{code, name, String.valueOf(System.currentTimeMillis())});
            act.bringToFront();
            act.js("window.tsLinkPairCode && tsLinkPairCode(" + JSONObject.quote(code) + "," + JSONObject.quote(name) + ")");
            return new JSONObject().put("id", id).put("name", deviceName(act)).toString();
        }
        if (path.startsWith("/pair/finish")) {
            String[] p = pending.get(in.optString("id"));
            if (p == null || System.currentTimeMillis() - Long.parseLong(p[2]) > 180000) throw new Exception("pairing expired, start again");
            if (!p[0].equals(in.optString("code").trim())) {
                // a 4-digit code can't be guessed in five tries: after that this pairing is over
                long now0 = System.currentTimeMillis();
                synchronized (wrongAt) {
                    wrongAt.addLast(now0);
                    while (!wrongAt.isEmpty() && now0 - wrongAt.peekFirst() > 3600000L) wrongAt.removeFirst();
                    if (wrongAt.size() >= 10) { pairLockedUntil = now0 + 3600000L; wrongAt.clear(); pending.clear(); Diag.ev("phone link: pairing paused for an hour (too many wrong codes)"); }
                }
                int tries = p.length > 3 ? Integer.parseInt(p[3]) + 1 : 1;
                if (tries >= 5) { pending.remove(in.optString("id")); throw new Exception("too many wrong codes, start again"); }
                pending.put(in.optString("id"), new String[] { p[0], p[1], p[2], String.valueOf(tries) });
                throw new Exception("wrong code");
            }
            pending.remove(in.optString("id"));
            String tok = token(24);
            JSONObject all = tokens();
            all.put(tok, p[1]);
            prefs.edit().putString("link.tokens", all.toString()).apply();
            act.js("window.tsLinkPaired && tsLinkPaired(" + JSONObject.quote(p[1]) + ")");
            return new JSONObject().put("token", tok).put("name", deviceName(act)).toString();
        }
        String tok = in.optString("token", "");
        if (tok.isEmpty() || !tokens().has(tok)) throw new SecurityException();
        if (path.startsWith("/unpair")) {
            JSONObject all = tokens();
            all.remove(tok);
            prefs.edit().putString("link.tokens", all.toString()).apply();
            return "{\"ok\":true}";
        }
        if (path.startsWith("/cmd")) {
            String type = in.optString("type");
            if (!"key".equals(type) && !"text".equals(type) && !"sync".equals(type) && !"diagget".equals(type)) act.bringToFront();
            in.remove("token");
            in.put("from", tokens().optString(tok));
            String r = act.jsResult("window.tsLinkCmd ? tsLinkCmd(" + JSONObject.quote(in.toString()) + ") : ''", 3000);
            return r == null || r.isEmpty() ? "{\"ok\":true}" : r;
        }
        throw new Exception("unknown request");
    }

    /* Yahoo Fantasy setup, from the phone: the page itself, then its steps. Only with the key in the QR code
       the TV shows (Settings › Sports › Yahoo Fantasy), for half an hour. */
    private String fantasy(String method, String path, String body) throws Exception {
        final Fantasy f = act.fantasy;
        if (f == null) throw new Exception("not available");
        if (method.equals("GET")) {
            String k = path.contains("k=") ? path.substring(path.indexOf("k=") + 2).replaceAll("[&#].*$", "") : "";
            if (!f.setupOk(k)) return "<!doctype html><meta name=viewport content='width=device-width'><body style='font:17px sans-serif;padding:24px;background:#0d0e13;color:#eee'>This setup link has expired. On the TV, open Settings › Sports › Yahoo Fantasy again and scan the new code.</body>";
            if (path.startsWith("/fantasy/players")) new Thread(new Runnable() { @Override public void run() { try { f.players(); } catch (Exception ignored) { } } }).start();   // (the rosters, ready for searching)
            java.io.InputStream in = act.getAssets().open(path.startsWith("/fantasy/players") ? "players.html" : "fantasy-setup.html");
            ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toString("UTF-8");
        }
        JSONObject in = body.isEmpty() ? new JSONObject() : new JSONObject(body);
        if (!f.setupOk(in.optString("k"))) throw new Exception("This setup link has expired — scan the code on the TV again.");
        JSONObject out = new JSONObject();
        if (path.startsWith("/fantasy/app")) {
            String e = f.saveApp(in.optString("id"), in.optString("secret"));
            if (!e.isEmpty()) throw new Exception(e);
            out.put("auth", f.authUrl());
        } else if (path.startsWith("/fantasy/code")) {
            String e = f.finish(in.optString("code"));
            if (!e.isEmpty()) throw new Exception(e);
            JSONObject st = f.status();
            out.put("teams", new JSONArray().put(st.optString("teams")));
        } else if (path.startsWith("/fantasy/search")) {
            out.put("players", f.search(in.optString("q")));
        } else if (path.startsWith("/fantasy/mine")) {
            out.put("mine", f.mine());
        } else if (path.startsWith("/fantasy/add")) {
            JSONObject p = in.optJSONObject("p");
            if (p == null || p.optString("id").isEmpty()) throw new Exception("pick a player");
            out.put("mine", f.addMine(p));
        } else if (path.startsWith("/fantasy/bench")) {
            out.put("mine", f.benchMine(in.optString("id"), in.optBoolean("bench")));
        } else if (path.startsWith("/fantasy/remove")) {
            out.put("mine", f.removeMine(in.optString("id")));
        } else if (path.startsWith("/fantasy/state")) {
            JSONObject st = f.status();
            out.put("connected", st.optBoolean("connected")).put("teams", st.optString("teams"))
               .put("hasApp", !act.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE).getString("fy.id", "").isEmpty()).put("auth", f.authUrl());
        } else throw new Exception("unknown request");
        act.runOnUiThread(new Runnable() { @Override public void run() { NativeHome h = act.nativeHome(); if (h != null) h.fantasySetupRefresh(); } });
        return out.toString();
    }

    /* Google Drive setup, from the phone (the same way: only with the key in the TV's QR code) */
    private String drive(String method, String path, String body) throws Exception {
        final Drive d = act.drive;
        if (d == null) throw new Exception("not available");
        if (method.equals("GET")) {
            String k = path.contains("k=") ? path.substring(path.indexOf("k=") + 2).replaceAll("[&#].*$", "") : "";
            if (!d.setupOk(k)) return "<!doctype html><meta name=viewport content='width=device-width'><body style='font:17px sans-serif;padding:24px;background:#0d0e13;color:#eee'>This setup link has expired. On the TV, open Settings › Live TV › Google Drive again and scan the new code.</body>";
            java.io.InputStream in = act.getAssets().open("drive-setup.html");
            ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toString("UTF-8");
        }
        JSONObject in = body.isEmpty() ? new JSONObject() : new JSONObject(body);
        if (!d.setupOk(in.optString("k"))) throw new Exception("This setup link has expired — scan the code on the TV again.");
        JSONObject out;
        if (path.startsWith("/drive/app")) {
            String e = d.saveApp(in.optString("id"), in.optString("secret"));
            if (!e.isEmpty()) throw new Exception(e);
            out = d.status();
        } else if (path.startsWith("/drive/again")) {
            String e = d.startSignIn();
            if (!e.isEmpty()) throw new Exception(e);
            out = d.status();
        } else if (path.startsWith("/drive/state")) {
            out = d.status();
        } else throw new Exception("unknown request");
        act.runOnUiThread(new Runnable() { @Override public void run() { NativeHome h = act.nativeHome(); if (h != null) h.setupRefresh(); } });
        return out.toString();
    }

    /* Trakt setup, from the phone (with the key in the TV's QR code: Settings › Trakt) */
    private String trakt(String method, String path, String body) throws Exception {
        final Trakt t = act.trakt;
        if (t == null) throw new Exception("not available");
        if (method.equals("GET")) {
            String k = path.contains("k=") ? path.substring(path.indexOf("k=") + 2).replaceAll("[&#].*$", "") : "";
            if (!t.setupOk(k)) return "<!doctype html><meta name=viewport content='width=device-width'><body style='font:17px sans-serif;padding:24px;background:#0d0e13;color:#eee'>This link has expired. On the TV, open Settings › Trakt again and scan the new code.</body>";
            java.io.InputStream in = act.getAssets().open("trakt-setup.html");
            ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toString("UTF-8");
        }
        JSONObject in = body.isEmpty() ? new JSONObject() : new JSONObject(body);
        if (!t.setupOk(in.optString("k"))) throw new Exception("This link has expired — scan the code on the TV again.");
        if (path.startsWith("/trakt/app")) { String e = t.saveApp(in.optString("id"), in.optString("secret")); if (!e.isEmpty()) throw new Exception(e); }
        else if (path.startsWith("/trakt/again")) { String e = t.startSignIn(); if (!e.isEmpty()) throw new Exception(e); }
        else if (path.startsWith("/trakt/code")) { String e = t.finishPkce(in.optString("code")); if (!e.isEmpty()) throw new Exception(e); }
        else if (!path.startsWith("/trakt/state")) throw new Exception("unknown request");
        act.runOnUiThread(new Runnable() { @Override public void run() { NativeHome h = act.nativeHome(); if (h != null) h.setupRefresh(); } });
        return t.status().toString();
    }

    /* Ring doorbell sign-in, from the phone (with the key in the TV's QR code: Settings › Ring doorbell). The password
       goes to Ring once and isn't kept: only the sign-in token Ring hands back is. */
    private String ring(String method, String path, String body) throws Exception {
        final Ring r = act.ring;
        if (r == null) throw new Exception("not available");
        if (method.equals("GET")) {
            String k = path.contains("k=") ? path.substring(path.indexOf("k=") + 2).replaceAll("[&#].*$", "") : "";
            if (!r.setupOk(k)) return "<!doctype html><meta name=viewport content='width=device-width'><body style='font:17px sans-serif;padding:24px;background:#0d0e13;color:#eee'>This link has expired. On the TV, open Settings › Ring doorbell again and scan the new code.</body>";
            java.io.InputStream in = act.getAssets().open("ring-setup.html");
            ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toString("UTF-8");
        }
        JSONObject in = body.isEmpty() ? new JSONObject() : new JSONObject(body);
        if (!r.setupOk(in.optString("k"))) throw new Exception("This link has expired — scan the code on the TV again.");
        String res = "";
        if (path.startsWith("/ring/login")) res = r.login(in.optString("email"), in.optString("password"));
        else if (path.startsWith("/ring/code")) res = r.code(in.optString("code"));
        else if (!path.startsWith("/ring/state")) throw new Exception("unknown request");
        act.runOnUiThread(new Runnable() { @Override public void run() { NativeHome h = act.nativeHome(); if (h != null) h.setupRefresh(); } });
        JSONObject out = r.status();
        if (res.startsWith("code: ")) out.put("needCode", true).put("prompt", res.substring(6));
        else if (!res.isEmpty()) out.put("error", res);
        return out.toString();
    }

    /* Addons, from the phone (only with the key in the TV's QR code: Settings › Addons) */
    private String addons(String method, String path, String body) throws Exception {
        final Addons a = Addons.get(act);
        if (method.equals("GET")) {
            String k = path.contains("k=") ? path.substring(path.indexOf("k=") + 2).replaceAll("[&#].*$", "") : "";
            if (!a.setupOk(k)) return "<!doctype html><meta name=viewport content='width=device-width'><body style='font:17px sans-serif;padding:24px;background:#0d0e13;color:#eee'>This link has expired. On the TV, open Settings › Addons again and scan the new code.</body>";
            java.io.InputStream in = act.getAssets().open("addons.html");
            ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            String page = bo.toString("UTF-8");
            // (opened on the phone itself, for the phone's own addons: its words say so)
            if (path.contains("here=1")) page = page.replace("On the TV, choose", "Choose").replace("Add to the TV", "Add to TopShelf").replace("Open Nuvio on the TV", "Open Nuvio")
                    .replace("not on the TV, not anywhere", "not on this phone, not anywhere").replace("Added on the TV.", "Added.").replace("Copied to the TV.", "Copied.")
                    .replace("The TV gave an odd answer", "TopShelf gave an odd answer").replace("<h2>On the TV <span", "<h2>On this phone <span");
            return page;
        }
        JSONObject in = body.isEmpty() ? new JSONObject() : new JSONObject(body);
        if (!a.setupOk(in.optString("k"))) throw new Exception("This link has expired — scan the code on the TV again.");
        if (path.startsWith("/addons/add")) { String e = a.install(in.optString("url")); if (!e.isEmpty()) throw new Exception(e); }
        else if (path.startsWith("/addons/stremio")) { String e = a.importStremio(in.optString("email"), in.optString("password")); if (!e.isEmpty()) throw new Exception(e); }
        else if (path.startsWith("/addons/remove")) a.remove(in.optString("url"));
        else if (path.startsWith("/addons/toggle")) a.set(in.optString("url"), "on", in.optBoolean("on"));
        else if (!path.startsWith("/addons/list")) throw new Exception("unknown request");
        act.runOnUiThread(new Runnable() { @Override public void run() { NativeHome h = act.nativeHome(); if (h != null) h.addonsRefresh(); } });
        return new JSONObject().put("addons", a.list()).toString();
    }

    JSONObject tokens() {
        try { return new JSONObject(prefs.getString("link.tokens", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    private String token(int n) {
        String a = "abcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(a.charAt(rnd.nextInt(a.length())));
        return b.toString();
    }
}
