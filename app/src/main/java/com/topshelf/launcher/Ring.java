package com.topshelf.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * The Ring doorbell (Settings › Ring doorbell): when it's pressed, TopShelf shows its camera's picture and pauses what's
 * playing (see Doorbell).
 *
 * Ring has no official way in for other apps. This works the way the open-source Ring tools do (ring-client-api, which
 * Home Assistant and Homebridge use): it signs in as Ring's Android app (email, password and the code Ring texts —
 * typed on your phone, on the TV's setup page; only the sign-in token Ring hands back is kept, never the password),
 * then signs up for Ring's push notifications the way a browser does with Google's push service (a check-in, a
 * registration, and a connection to mtalk.google.com that stays open), and tells Ring to send its notifications there.
 * A ring arrives as an encrypted message (web push, "aesgcm"); its snapshot is then fetched from Ring.
 *
 * The TV shows as "TopShelf" in the Ring app's Control Center (Authorized client devices), where it can be removed.
 */
final class Ring {
    private static final String P = "ring.";
    private static final String UA = "android:com.ringapp";
    // Ring's Android app's Firebase project (from ring-client-api): its notifications are sent through it
    private static final String FB_KEY = "AIzaSyCv-hdFBmmdBBJadNy-TFwB-xN_H5m3Bk8", FB_PROJECT = "ring-17770", FB_APP = "1:876313859327:android:e10ec6ddb3c81f39";
    private static final String VAPID = "BDOU99-h67HcA6JeFXHbSNMu7e2yNNu3RzoMj8TM4W88jITfq7ZmPvIM1Iv-4_l2LxQcYwhqby2xGpWwzjfAnG4";
    private static final String DING = "com.ring.pn.live-event.ding";

    interface Listener { void ding(String device, String snapshotUuid, String dingId, String deviceId); }

    private final MainActivity act;
    private final SharedPreferences prefs;
    volatile Listener listener;
    volatile String state = "";          // what's happening, for Settings and the setup page
    private String access = ""; private long accessUntil;
    private volatile boolean running;
    private Thread thread;

    Ring(MainActivity a) {
        act = a;
        prefs = a.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE);
        if (prefs.getString(P + "hid", "").isEmpty()) prefs.edit().putString(P + "hid", UUID.randomUUID().toString()).apply();
    }

    boolean connected() { return !prefs.getString(P + "rt", "").isEmpty(); }
    JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            o.put("connected", connected()).put("state", state).put("listening", listening);
            JSONArray d = devices(); o.put("doorbells", d.length());
            if (d.length() > 0) o.put("name", d.optJSONObject(0).optString("name"));
        } catch (Exception ignored) { }
        return o;
    }
    /** a doorbell's id by its name (or the only one there is) */
    String idFor(String name) {
        JSONArray a = devices();
        for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null && a.optJSONObject(i).optString("name").equalsIgnoreCase(name)) return a.optJSONObject(i).optString("id");
        return a.length() == 1 && a.optJSONObject(0) != null ? a.optJSONObject(0).optString("id") : "";
    }
    JSONArray devices() { try { return new JSONArray(prefs.getString(P + "devices", "[]")); } catch (Exception e) { return new JSONArray(); } }
    void disconnect() {
        stop();
        prefs.edit().remove(P + "rt").remove(P + "push").remove(P + "pids").remove(P + "devices").apply();
        access = ""; state = "";
        Diag.ev("ring: disconnected");
    }

    // ================================================================== setup (the phone page)

    private String setupKey = ""; private long setupUntil;
    String setupUrl() {
        if (act.link == null || act.link.port() == 0 || LinkServer.localIp().isEmpty()) return "";
        if (setupKey.isEmpty() || System.currentTimeMillis() > setupUntil) {
            String a = "abcdefghjkmnpqrstuvwxyz23456789"; StringBuilder b = new StringBuilder(); SecureRandom r = new SecureRandom();
            for (int i = 0; i < 10; i++) b.append(a.charAt(r.nextInt(a.length())));
            setupKey = b.toString();
        }
        setupUntil = System.currentTimeMillis() + 20 * 60000L;
        return "http://" + LinkServer.localIp() + ":" + act.link.port() + "/ring?k=" + setupKey;
    }
    boolean setupOk(String k) { return k != null && !setupKey.isEmpty() && setupKey.equals(k) && System.currentTimeMillis() < setupUntil; }

    private String pendEmail = "", pendPass = "";
    /** step 1: email and password ("" = signed in; "code: …" = Ring wants the code it sent; anything else = what's wrong) */
    String login(String email, String password) {
        pendEmail = email == null ? "" : email.trim(); pendPass = password == null ? "" : password;
        if (pendEmail.isEmpty() || pendPass.isEmpty()) return "Type your Ring email and password.";
        return token(null);
    }
    /** step 2: the code Ring texted or emailed */
    String code(String code) {
        if (pendEmail.isEmpty()) return "Start again: type your email and password.";
        return token(code == null ? "" : code.trim());
    }
    private String token(String code) {
        try {
            JSONObject body = new JSONObject().put("client_id", "ring_official_android").put("scope", "client")
                    .put("grant_type", "password").put("username", pendEmail).put("password", pendPass);
            HttpURLConnection c = open("https://oauth.ring.com/oauth/token", "POST");
            c.setRequestProperty("2fa-support", "true");
            c.setRequestProperty("2fa-code", code == null ? "" : code);
            send(c, body.toString());
            int st = c.getResponseCode();
            String res = read(c);
            if (st == 200) {
                JSONObject o = new JSONObject(res);
                saveTokens(o);
                pendPass = ""; pendEmail = "";        // (the password is let go as soon as Ring answers)
                state = "Signed in";
                Diag.ev("ring: signed in");
                start();
                return "";
            }
            JSONObject o; try { o = new JSONObject(res); } catch (Exception e) { o = new JSONObject(); }
            if (st == 412) {
                String ts = o.optString("tsv_state");
                state = "Waiting for the code";
                return "code: " + (ts.equals("totp") ? "Type the code from your authenticator app." : "Type the code Ring sent" + (o.optString("phone").isEmpty() ? "" : " to " + o.optString("phone")) + ".");
            }
            if (st == 400 && o.optString("error").startsWith("Verification Code")) return "code: That code didn't work — try again.";
            if (o.optString("error_description").contains("too many requests")) return "Ring has had too many code requests. Wait 10 minutes and try again.";
            return "Ring didn't accept that email and password (" + (o.optString("error").isEmpty() ? "HTTP " + st : o.optString("error")) + ").";
        } catch (Throwable t) { return "Couldn't reach Ring: " + t.getMessage(); }
    }
    private void saveTokens(JSONObject o) {
        access = o.optString("access_token");
        accessUntil = System.currentTimeMillis() + (o.optLong("expires_in", 3600) - 60) * 1000L;
        if (!o.optString("refresh_token").isEmpty()) prefs.edit().putString(P + "rt", o.optString("refresh_token")).apply();
    }
    /** a fresh access token from the saved sign-in (Ring hands back a new sign-in token each time: kept) */
    private synchronized String auth() throws Exception {
        if (!access.isEmpty() && System.currentTimeMillis() < accessUntil) return access;
        String rt = prefs.getString(P + "rt", "");
        if (rt.isEmpty()) throw new Exception("not signed in");
        HttpURLConnection c = open("https://oauth.ring.com/oauth/token", "POST");
        c.setRequestProperty("2fa-support", "true"); c.setRequestProperty("2fa-code", "");
        send(c, new JSONObject().put("client_id", "ring_official_android").put("scope", "client").put("grant_type", "refresh_token").put("refresh_token", rt).toString());
        int st = c.getResponseCode(); String res = read(c);
        if (st != 200) {
            if (st == 400 || st == 401) { prefs.edit().remove(P + "rt").apply(); state = "Ring asked to sign in again"; Diag.ev("ring: the sign-in ran out — sign in again"); }
            throw new Exception("Ring sign-in: HTTP " + st);
        }
        saveTokens(new JSONObject(res));
        return access;
    }

    // ================================================================== talking to Ring

    private HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(20000);
        try { c.setRequestMethod(method); }
        catch (java.net.ProtocolException pe) {        // (a phone's older HTTP library without PATCH: set it underneath)
            java.lang.reflect.Field f = HttpURLConnection.class.getDeclaredField("method"); f.setAccessible(true); f.set(c, method);
        }
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("hardware_id", prefs.getString(P + "hid", ""));
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        return c;
    }
    private static void send(HttpURLConnection c, String body) throws Exception {
        c.setDoOutput(true);
        OutputStream os = c.getOutputStream(); os.write(body.getBytes("UTF-8")); os.close();
    }
    private static String read(HttpURLConnection c) throws Exception {
        InputStream in; try { in = c.getInputStream(); } catch (Exception e) { in = c.getErrorStream(); }
        if (in == null) return "";
        byte[] d = readAll(in);
        return new String(d, "UTF-8");
    }
    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return bo.toByteArray();
    }
    private JSONObject api(String method, String url, JSONObject body) throws Exception {
        HttpURLConnection c = open(url, method);
        c.setRequestProperty("authorization", "Bearer " + auth());
        if (body != null) send(c, body.toString());
        int st = c.getResponseCode(); String res = read(c);
        if (st == 401) { access = ""; throw new Exception("HTTP 401"); }
        if (st >= 400) throw new Exception("HTTP " + st + " " + (res.length() > 120 ? res.substring(0, 120) : res));
        try { return res.trim().startsWith("{") ? new JSONObject(res) : new JSONObject(); } catch (Exception e) { return new JSONObject(); }
    }
    private JSONObject meta() throws Exception { return new JSONObject().put("api_version", 11).put("device_model", "TopShelf"); }

    /** a one-time ticket for Ring's live-video signalling (see Doorbell's live view) */
    String liveTicket() throws Exception {
        JSONObject o = api("POST", "https://prd-api-us.prd.rings.solutions/api/v1/clap/ticket/request/signalsocket", new JSONObject());
        String t = o.optString("ticket");
        if (t.isEmpty()) throw new Exception("no ticket");
        return t;
    }

    /* The picture of whoever rang. First the one Ring attached to the ring (when it attaches one: it can take a
       few seconds to be ready); otherwise the doorbell is asked for a fresh picture and TopShelf waits for one taken
       after the bell was pressed (as ring-client-api does). A battery doorbell takes it only with "Snapshot capture"
       on in the Ring app. Every step goes in the report. */
    byte[] snapshot(String uuid, String deviceId, long rangAt) {
        String why = "";
        if (uuid != null && !uuid.isEmpty()) {
            String u = uuid.replaceAll(":\\d+$", "");     // (ring-client-api: "uuid:1234" → "uuid")
            int last = 0;
            for (int i = 0; i < 6; i++) {
                try {
                    HttpURLConnection c = open("https://api.ring.com/clients_api/snapshots/uuid?uuid=" + URLEncoder.encode(u, "UTF-8"), "GET");
                    c.setRequestProperty("authorization", "Bearer " + auth());
                    c.setRequestProperty("Accept", "image/jpeg");
                    last = c.getResponseCode();
                    if (last == 200) { byte[] d = readAll(c.getInputStream()); if (d.length > 1000) { Diag.ev("ring: the ring's picture (" + d.length / 1024 + " KB)"); return d; } last = -d.length; }
                } catch (Throwable t) { last = -1; }
                try { Thread.sleep(1500); } catch (InterruptedException e) { return null; }
            }
            why = "the ring's picture didn't come (" + (last == -1 ? "no answer" : last < 0 ? "empty" : "HTTP " + last) + ")";
            Diag.ev("ring: " + why);
        }
        if (deviceId == null || deviceId.isEmpty()) return null;
        try {
            JSONObject ids = new JSONObject().put("doorbot_ids", new JSONArray().put(Long.parseLong(deviceId)));
            try { api("PUT", "https://api.ring.com/clients_api/snapshots/update_all", new JSONObject(ids.toString()).put("refresh", true)); }
            catch (Throwable t) { Diag.ev("ring: asking for a fresh picture didn't work (" + t.getMessage() + ")"); }
            long newest = 0;
            for (int i = 0; i < 12; i++) {
                try { Thread.sleep(1500); } catch (InterruptedException e) { return null; }
                JSONObject ts = api("POST", "https://api.ring.com/clients_api/snapshots/timestamps", ids);
                JSONArray a = ts.optJSONArray("timestamps");
                if (a != null) for (int k = 0; k < a.length(); k++) newest = Math.max(newest, a.optJSONObject(k) == null ? 0 : a.optJSONObject(k).optLong("timestamp"));
                if (newest >= rangAt - 10000) break;
            }
            if (newest < rangAt - 10000) {
                Diag.ev("ring: no fresh picture from the doorbell (newest " + (newest == 0 ? "none" : (System.currentTimeMillis() - newest) / 60000 + " min old") + ") · is Snapshot capture on in the Ring app?");
                return null;
            }
            HttpURLConnection c = open("https://api.ring.com/clients_api/snapshots/image/" + deviceId, "GET");
            c.setRequestProperty("authorization", "Bearer " + auth());
            c.setRequestProperty("Accept", "image/jpeg");
            int st = c.getResponseCode();
            if (st == 200) { byte[] d = readAll(c.getInputStream()); if (d.length > 1000) { Diag.ev("ring: a fresh picture from the doorbell (" + d.length / 1024 + " KB)"); return d; } }
            Diag.ev("ring: the fresh picture didn't come (HTTP " + st + ")");
        } catch (Throwable t) { Diag.ev("ring: the fresh picture didn't come (" + t.getMessage() + ")"); }
        return null;
    }

    // ================================================================== listening for the bell

    void start() {
        if (!connected() || running) return;
        running = true;
        thread = new Thread(new Runnable() { @Override public void run() { loop(); } }, "ring");
        thread.setDaemon(true);
        thread.start();
    }
    void stop() {
        running = false;
        try { if (sock != null) sock.close(); } catch (Throwable ignored) { }
        if (thread != null) thread.interrupt();
    }
    private volatile boolean listening;
    private volatile SSLSocket sock;

    private void loop() {
        int fails = 0;
        while (running && connected()) {
            try {
                JSONObject pc = pushCredentials();
                // the session (this TV, as a Ring client) and where to send its notifications
                api("POST", "https://api.ring.com/clients_api/session", new JSONObject().put("device", new JSONObject()
                        .put("hardware_id", prefs.getString(P + "hid", "")).put("metadata", meta()).put("os", "android")));
                api("PATCH", "https://api.ring.com/clients_api/device", new JSONObject().put("device", new JSONObject()
                        .put("metadata", meta().put("pn_dict_version", "2.0.0").put("pn_service", "fcm"))
                        .put("os", "android").put("push_notification_token", pc.getString("fcm"))));
                readDevices();
                state = "Listening for the doorbell";
                Diag.ev("ring: listening for the doorbell (" + devices().length() + " found)");
                listen(pc);
                fails = 0;
            } catch (Throwable t) {
                listening = false;
                if (!running) return;
                fails++;
                state = connected() ? "Reconnecting…" : state;
                Diag.ev("ring: " + t.getMessage() + " · trying again");
            }
            try { Thread.sleep(Math.min(300000L, 5000L * fails * fails)); } catch (InterruptedException e) { return; }
        }
        running = false; listening = false;
    }

    private void readDevices() {
        try {
            JSONObject o = api("GET", "https://api.ring.com/clients_api/ring_devices", null);
            JSONArray out = new JSONArray();
            for (String k : new String[] { "doorbots", "authorized_doorbots" }) {
                JSONArray a = o.optJSONArray(k);
                if (a != null) for (int i = 0; i < a.length(); i++) { JSONObject d = a.optJSONObject(i); if (d != null) out.put(new JSONObject().put("id", d.optLong("id")).put("name", d.optString("description", "Doorbell"))); }
            }
            prefs.edit().putString(P + "devices", out.toString()).apply();
        } catch (Throwable t) { Diag.ev("ring: couldn't list doorbells (" + t.getMessage() + ")"); }
    }

    // ---- Google's push service, the way a browser signs up for it

    /** {androidId, securityToken, fcm (the token Ring sends to), priv, pub, auth} — made once, kept */
    private JSONObject pushCredentials() throws Exception {
        JSONObject pc = null;
        try { pc = new JSONObject(prefs.getString(P + "push", "")); } catch (Exception ignored) { }
        if (pc != null && !pc.optString("fcm").isEmpty()) {
            checkin(pc.optString("androidId"), pc.optString("securityToken"));        // (keeps the registration alive)
            return pc;
        }
        String[] ids = checkin(null, null);
        // keys for the encrypted messages
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        byte[] pub = uncompressed((ECPublicKey) kp.getPublic()), authSecret = new byte[16];
        new SecureRandom().nextBytes(authSecret);
        String gcm = gcmRegister(ids[0], ids[1]);
        // Firebase: an installation, then the web push registration that gives the token Ring needs
        String fid = fid();
        HttpURLConnection c = (HttpURLConnection) new URL("https://firebaseinstallations.googleapis.com/v1/projects/" + FB_PROJECT + "/installations").openConnection();
        c.setRequestMethod("POST"); c.setRequestProperty("x-goog-api-key", FB_KEY); c.setRequestProperty("x-firebase-client", heartbeat()); c.setRequestProperty("Content-Type", "application/json");
        send(c, new JSONObject().put("appId", FB_APP).put("authVersion", "FIS_v2").put("fid", fid).put("sdkVersion", "w:0.6.6").toString());
        int st = c.getResponseCode(); String res = read(c);
        if (st != 200) throw new Exception("Firebase installation: HTTP " + st);
        String itok = new JSONObject(res).getJSONObject("authToken").getString("token");
        c = (HttpURLConnection) new URL("https://fcmregistrations.googleapis.com/v1/projects/" + FB_PROJECT + "/registrations").openConnection();
        c.setRequestMethod("POST"); c.setRequestProperty("x-goog-api-key", FB_KEY); c.setRequestProperty("x-goog-firebase-installations-auth", itok); c.setRequestProperty("Content-Type", "application/json");
        send(c, new JSONObject().put("web", new JSONObject().put("auth", b64u(authSecret)).put("endpoint", "https://fcm.googleapis.com/fcm/send/" + gcm).put("p256dh", b64u(pub))).toString());
        st = c.getResponseCode(); res = read(c);
        if (st != 200) throw new Exception("push registration: HTTP " + st);
        String fcm = new JSONObject(res).getString("token");
        pc = new JSONObject().put("androidId", ids[0]).put("securityToken", ids[1]).put("fcm", fcm)
                .put("priv", android.util.Base64.encodeToString(kp.getPrivate().getEncoded(), android.util.Base64.NO_WRAP))
                .put("pub", b64u(pub)).put("auth", b64u(authSecret));
        prefs.edit().putString(P + "push", pc.toString()).apply();
        Diag.ev("ring: signed up for push notifications");
        return pc;
    }
    private static String heartbeat() { return android.util.Base64.encodeToString("{\"heartbeats\":[],\"version\":2}".getBytes(), android.util.Base64.NO_WRAP); }
    private static String fid() {
        byte[] b = new byte[17]; new SecureRandom().nextBytes(b);
        b[0] = (byte) (0x70 | (b[0] & 0x0F));
        return b64u(b).substring(0, 22);
    }

    /** Google's check-in, as a Chrome browser: {androidId, securityToken} (decimal) */
    private String[] checkin(String androidId, String securityToken) throws Exception {
        Pb chrome = new Pb().int32(1, 2).str(2, "63.0.3234.0").int32(3, 1);
        Pb checkin = new Pb().int64(2, 0).int32(9, 0).int32(12, 3).msg(13, chrome);
        Pb req = new Pb();
        if (androidId != null && !androidId.isEmpty()) req.int64(2, new BigInteger(androidId).longValue());
        req.msg(4, checkin).int64(7, 0).str(12, java.util.TimeZone.getDefault().getID());
        if (securityToken != null && !securityToken.isEmpty()) req.fixed64(13, new BigInteger(securityToken).longValue());
        req.int32(14, 3).int32(20, 0).int32(22, 0);
        HttpURLConnection c = (HttpURLConnection) new URL("https://android.clients.google.com/checkin").openConnection();
        c.setRequestMethod("POST"); c.setRequestProperty("Content-Type", "application/x-protobuf"); c.setDoOutput(true);
        OutputStream os = c.getOutputStream(); os.write(req.bytes()); os.close();
        if (c.getResponseCode() != 200) throw new Exception("Google check-in: HTTP " + c.getResponseCode());
        Pb.Read r = new Pb.Read(readAll(c.getInputStream()));
        long aid = 0, tok = 0;
        while (r.more()) { int f = r.field(); if (f == 7) aid = r.fixed64(); else if (f == 8) tok = r.fixed64(); else r.skip(); }
        if (aid == 0) throw new Exception("Google check-in: no device id");
        return new String[] { Long.toUnsignedString(aid), Long.toUnsignedString(tok) };
    }
    private String gcmRegister(String androidId, String token) throws Exception {
        String body = "app=org.chromium.linux&X-subtype=" + URLEncoder.encode("wp:receiver.push.com#" + UUID.randomUUID(), "UTF-8")
                + "&device=" + androidId + "&sender=" + VAPID;
        for (int i = 0; i < 5; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL("https://android.clients.google.com/c2dm/register3").openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Authorization", "AidLogin " + androidId + ":" + token);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            send(c, body);
            String res = read(c);
            if (res.startsWith("token=")) return res.substring(6).trim();
            Thread.sleep(1000);
        }
        throw new Exception("Google push registration failed");
    }

    /* The connection to Google's push servers that stays open (mtalk.google.com:5228, Google's own message format):
       a login, then messages as they come; a heartbeat every few minutes keeps it open. */
    private void listen(JSONObject pc) throws Exception {
        String aid = pc.getString("androidId"), tok = pc.getString("securityToken");
        SSLSocket s = (SSLSocket) SSLSocketFactory.getDefault().createSocket("mtalk.google.com", 5228);
        sock = s;
        try {
            s.setSoTimeout(5 * 60000 + 30000);
            s.startHandshake();
            final OutputStream os = s.getOutputStream();
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(s.getInputStream()));
            Pb login = new Pb().str(1, "chrome-94.0.4606.51").str(2, "mcs.android.com").str(3, aid).str(4, aid).str(5, tok)
                    .str(6, "android-" + new BigInteger(aid).toString(16))
                    .msg(8, new Pb().str(1, "new_vc").str(2, "1"));
            for (String pid : persistentIds()) login.str(10, pid);
            login.bool(12, false).msg(13, new Pb().str(1, "").bool(2, true).int32(3, 300000)).bool(14, true).int32(16, 2).int32(17, 1);
            byte[] lb = login.bytes();
            ByteArrayOutputStream first = new ByteArrayOutputStream();
            first.write(41); first.write(2); Pb.varint(first, lb.length); first.write(lb);
            synchronized (os) { os.write(first.toByteArray()); os.flush(); }
            int version = in.readUnsignedByte();
            if (version < 38) throw new Exception("push server: version " + version);
            int streamId = 0, idle = 0;
            long loggedAt = 0;
            while (running) {
                int tag, size; byte[] body;
                try {
                    tag = in.readUnsignedByte();
                    size = Pb.readVarint(in);
                    body = new byte[size]; in.readFully(body);
                    idle = 0;
                } catch (java.net.SocketTimeoutException te) {
                    // quiet for a while: a heartbeat (twice quiet in a row: start again)
                    if (++idle >= 2) throw new Exception("push connection went quiet");
                    byte[] hb = new Pb().int32(2, streamId).bytes();
                    ByteArrayOutputStream b = new ByteArrayOutputStream(); b.write(0); Pb.varint(b, hb.length); b.write(hb);
                    synchronized (os) { os.write(b.toByteArray()); os.flush(); }
                    continue;
                }
                streamId++;
                if (tag == 3) { listening = true; loggedAt = System.currentTimeMillis(); savePids(new ArrayList<String>()); }        // login answered
                else if (tag == 0) {                       // their heartbeat: answered
                    byte[] ack = new Pb().int32(2, streamId).bytes();
                    ByteArrayOutputStream b = new ByteArrayOutputStream(); b.write(1); Pb.varint(b, ack.length); b.write(ack);
                    synchronized (os) { os.write(b.toByteArray()); os.flush(); }
                }
                else if (tag == 4) throw new Exception("push server closed the connection");
                else if (tag == 8) message(pc, body, System.currentTimeMillis() - loggedAt < 2000);
            }
        } finally {
            listening = false;
            try { s.close(); } catch (Throwable ignored) { }
            sock = null;
        }
    }

    private List<String> persistentIds() {
        List<String> l = new ArrayList<>();
        try { JSONArray a = new JSONArray(prefs.getString(P + "pids", "[]")); for (int i = 0; i < a.length(); i++) l.add(a.optString(i)); } catch (Exception ignored) { }
        return l;
    }
    private void savePids(List<String> l) { JSONArray a = new JSONArray(); for (int i = Math.max(0, l.size() - 40); i < l.size(); i++) a.put(l.get(i)); prefs.edit().putString(P + "pids", a.toString()).apply(); }
    private final Set<String> seenDings = new HashSet<>();

    /** one message from the push service: decrypted; a doorbell press is passed on */
    private void message(JSONObject pc, byte[] stanza, boolean justLoggedIn) {
        try {
            Pb.Read r = new Pb.Read(stanza);
            String pid = "", cryptoKey = "", encryption = ""; byte[] raw = null;
            while (r.more()) {
                int f = r.field();
                if (f == 7) {
                    Pb.Read a = new Pb.Read(r.bytes()); String k = "", v = "";
                    while (a.more()) { int g = a.field(); if (g == 1) k = new String(a.bytes(), "UTF-8"); else if (g == 2) v = new String(a.bytes(), "UTF-8"); else a.skip(); }
                    if (k.equals("crypto-key")) cryptoKey = v; else if (k.equals("encryption")) encryption = v;
                } else if (f == 9) pid = new String(r.bytes(), "UTF-8");
                else if (f == 21) raw = r.bytes();
                else r.skip();
            }
            if (!pid.isEmpty()) { List<String> l = persistentIds(); if (l.contains(pid)) return; l.add(pid); savePids(l); }
            if (raw == null || justLoggedIn) return;          // (messages in the first moments are old ones, sent again)
            JSONObject msg = new JSONObject(new String(decrypt(pc, raw, param(cryptoKey, "dh"), param(encryption, "salt")), "UTF-8"));
            JSONObject d = msg.optJSONObject("data");
            if (d == null) return;
            JSONObject ac = obj(d.opt("android_config")), data = obj(d.opt("data")), img = obj(d.opt("img"));
            if (!DING.equals(ac.optString("category"))) return;
            JSONObject dev = data.optJSONObject("device"), ev = data.optJSONObject("event");
            JSONObject ding = ev == null ? null : ev.optJSONObject("ding");
            String dingId = ding == null ? pid : ding.optString("id", pid);
            synchronized (seenDings) { if (!seenDings.add(dingId)) return; }
            String name = dev == null ? "Doorbell" : dev.optString("name", "Doorbell");
            String snap = img.optString("snapshot_uuid");
            String devId = dev == null ? "" : dev.optString("id");
            if (devId.isEmpty()) devId = idFor(name);
            Diag.ev("ring: the doorbell (" + name + ") · " + (snap.isEmpty() ? "no picture attached" : "picture attached") + (devId.isEmpty() ? " · no device id" : ""));
            Listener l = listener;
            if (l != null) l.ding(name, snap, dingId, devId);
        } catch (Throwable t) { Diag.ev("ring: a message couldn't be read (" + t.getMessage() + ")"); }
    }
    private static JSONObject obj(Object o) {
        if (o instanceof JSONObject) return (JSONObject) o;
        if (o instanceof String) try { return new JSONObject((String) o); } catch (Exception ignored) { }
        return new JSONObject();
    }
    /** "dh=BASE64; p256ecdsa=…" → the value of one part */
    private static String param(String header, String name) {
        for (String part : header.split(";")) { String p = part.trim(); if (p.startsWith(name + "=")) return p.substring(name.length() + 1); }
        return "";
    }

    /* Web push decryption, "aesgcm" (draft-ietf-webpush-encryption-03): the shared secret from our key and the
       sender's, mixed with our auth secret, gives the content key and nonce; each record is AES-GCM with 2 bytes
       of padding length in front. */
    private static byte[] decrypt(JSONObject pc, byte[] data, String dh, String salt) throws Exception {
        PrivateKey priv = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(android.util.Base64.decode(pc.getString("priv"), android.util.Base64.NO_WRAP)));
        byte[] recv = b64ud(pc.getString("pub")), send = b64ud(dh), auth = b64ud(pc.getString("auth")), s = b64ud(salt);
        KeyFactory kf = KeyFactory.getInstance("EC");
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC"); g.initialize(new ECGenParameterSpec("secp256r1"));
        java.security.spec.ECParameterSpec spec = ((ECPublicKey) g.generateKeyPair().getPublic()).getParams();
        byte[] x = java.util.Arrays.copyOfRange(send, 1, 33), y = java.util.Arrays.copyOfRange(send, 33, 65);
        PublicKey sk = kf.generatePublic(new ECPublicKeySpec(new ECPoint(new BigInteger(1, x), new BigInteger(1, y)), spec));
        KeyAgreement ka = KeyAgreement.getInstance("ECDH"); ka.init(priv); ka.doPhase(sk, true);
        byte[] shared = ka.generateSecret();
        byte[] ikm = hkdf(auth, shared, cat("Content-Encoding: auth\0".getBytes("US-ASCII")), 32);
        ByteArrayOutputStream ctx = new ByteArrayOutputStream();
        ctx.write("P-256\0".getBytes("US-ASCII"));
        ctx.write(0); ctx.write(recv.length); ctx.write(recv);
        ctx.write(0); ctx.write(send.length); ctx.write(send);
        byte[] c = ctx.toByteArray();
        byte[] cek = hkdf(s, ikm, cat("Content-Encoding: aesgcm\0".getBytes("US-ASCII"), c), 16);
        byte[] nonce = hkdf(s, ikm, cat("Content-Encoding: nonce\0".getBytes("US-ASCII"), c), 12);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int rs = 4096 + 16;
        for (int off = 0, i = 0; off < data.length; off += rs, i++) {
            byte[] n = nonce.clone();
            for (int k = 0; k < 6; k++) n[11 - k] ^= (byte) ((long) i >>> (8 * k));
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, n));
            byte[] p = ci.doFinal(data, off, Math.min(rs, data.length - off));
            int pad = ((p[0] & 255) << 8) | (p[1] & 255);
            out.write(p, 2 + pad, p.length - 2 - pad);
        }
        return out.toByteArray();
    }
    private static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int len) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = m.doFinal(ikm);
        m.init(new SecretKeySpec(prk, "HmacSHA256"));
        m.update(info); m.update((byte) 1);
        return java.util.Arrays.copyOf(m.doFinal(), len);
    }
    private static byte[] cat(byte[]... a) { ByteArrayOutputStream b = new ByteArrayOutputStream(); for (byte[] x : a) b.write(x, 0, x.length); return b.toByteArray(); }
    private static byte[] uncompressed(ECPublicKey k) {
        byte[] out = new byte[65]; out[0] = 4;
        byte[] x = fixed(k.getW().getAffineX().toByteArray()), y = fixed(k.getW().getAffineY().toByteArray());
        System.arraycopy(x, 0, out, 1, 32); System.arraycopy(y, 0, out, 33, 32);
        return out;
    }
    private static byte[] fixed(byte[] b) {
        if (b.length == 32) return b;
        byte[] o = new byte[32];
        if (b.length > 32) System.arraycopy(b, b.length - 32, o, 0, 32); else System.arraycopy(b, 0, o, 32 - b.length, b.length);
        return o;
    }
    private static String b64u(byte[] b) { return android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING); }
    private static byte[] b64ud(String s) { return android.util.Base64.decode(s.replace('+', '-').replace('/', '_').replace("=", ""), android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP); }

    /** a little protobuf writer and reader: only what Google's push service needs */
    static final class Pb {
        private final ByteArrayOutputStream b = new ByteArrayOutputStream();
        static void varint(ByteArrayOutputStream o, long v) { while ((v & ~0x7FL) != 0) { o.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; } o.write((int) v); }
        private void key(int f, int w) { varint(b, ((long) f << 3) | w); }
        Pb int32(int f, int v) { key(f, 0); varint(b, v < 0 ? (long) v : v); return this; }
        Pb int64(int f, long v) { key(f, 0); varint(b, v); return this; }
        Pb bool(int f, boolean v) { key(f, 0); b.write(v ? 1 : 0); return this; }
        Pb fixed64(int f, long v) { key(f, 1); for (int i = 0; i < 8; i++) b.write((int) (v >>> (8 * i)) & 255); return this; }
        Pb str(int f, String s) { byte[] d; try { d = s.getBytes("UTF-8"); } catch (Exception e) { d = new byte[0]; } return raw(f, d); }
        Pb msg(int f, Pb m) { return raw(f, m.bytes()); }
        Pb raw(int f, byte[] d) { key(f, 2); varint(b, d.length); b.write(d, 0, d.length); return this; }
        byte[] bytes() { return b.toByteArray(); }
        static int readVarint(DataInputStream in) throws Exception {
            int v = 0, s = 0;
            for (int i = 0; i < 5; i++) { int c = in.readUnsignedByte(); v |= (c & 0x7F) << s; if ((c & 0x80) == 0) return v; s += 7; }
            throw new Exception("bad size");
        }
        static final class Read {
            private final byte[] d; private int p, wire;
            Read(byte[] d) { this.d = d; }
            boolean more() { return p < d.length; }
            long varint() { long v = 0; int s = 0; while (p < d.length) { int c = d[p++] & 255; v |= (long) (c & 0x7F) << s; if ((c & 0x80) == 0) break; s += 7; } return v; }
            int field() { long k = varint(); wire = (int) (k & 7); return (int) (k >>> 3); }
            long fixed64() { long v = 0; for (int i = 0; i < 8; i++) v |= (long) (d[p + i] & 255) << (8 * i); p += 8; return v; }
            byte[] bytes() { int n = (int) varint(); byte[] o = java.util.Arrays.copyOfRange(d, p, p + n); p += n; return o; }
            void skip() { if (wire == 0) varint(); else if (wire == 1) p += 8; else if (wire == 2) { int n = (int) varint(); p += n; } else if (wire == 5) p += 4; else p = d.length; }
        }
    }
}
