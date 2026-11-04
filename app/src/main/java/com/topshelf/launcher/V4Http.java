package com.topshelf.launcher;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A plain HTTPS request made over IPv4 only (as Nuvio does for YouTube). On a home connection with
 * IPv6, YouTube can single out the TV's own IPv6 address — asking it to "prove it's not a bot" and
 * refusing even its embedded player — while the household's shared IPv4 address is fine. A video's
 * address from YouTube only works from the address that asked for it, so the lookup and the video
 * both go this way. Android's own connections pick IPv6 first and can't be told otherwise, hence
 * this small client: one request per connection, HTTP/1.1, gzip, chunked answers, redirects.
 */
final class V4Http {
    private V4Http() { }

    static javax.net.ssl.HostnameVerifier verifier = HttpsURLConnection.getDefaultHostnameVerifier();
    static final class NoV4 extends IOException { NoV4(String m) { super(m); } }

    static final class Resp {
        int code; String message = "";
        final Map<String, String> headers = new HashMap<>();     // lower-case names
        InputStream body; Socket sock;
        String header(String n) { return headers.get(n.toLowerCase(Locale.US)); }
        void close() { try { if (body != null) body.close(); } catch (Throwable ignored) { } try { if (sock != null) sock.close(); } catch (Throwable ignored) { } }
        String text() throws IOException {
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[16384]; int n;
                while ((n = body.read(b)) > 0) bo.write(b, 0, n);
                return new String(bo.toByteArray(), StandardCharsets.UTF_8);
            } finally { close(); }
        }
    }

    private static final Map<String, Object[]> dns = new HashMap<>();     // host → {Inet4Address[], until}
    static Inet4Address[] v4(String host) throws IOException {
        synchronized (dns) { Object[] c = dns.get(host); if (c != null && (Long) c[1] > System.currentTimeMillis()) return (Inet4Address[]) c[0]; }
        java.util.List<Inet4Address> l = new java.util.ArrayList<>();
        for (InetAddress a : InetAddress.getAllByName(host)) if (a instanceof Inet4Address) l.add((Inet4Address) a);
        if (l.isEmpty()) throw new NoV4("no IPv4 address for " + host);
        Inet4Address[] out = l.toArray(new Inet4Address[0]);
        synchronized (dns) { dns.put(host, new Object[] { out, System.currentTimeMillis() + 5 * 60_000L }); }
        return out;
    }

    /** the request; the caller reads resp.body and calls resp.close() */
    static Resp open(String url, String method, Map<String, String> hd, byte[] body, int timeoutMs, boolean gzip) throws IOException {
        for (int hop = 0; hop < 5; hop++) {
            Resp r = once(url, method, hd, body, timeoutMs, gzip);
            if (r.code >= 300 && r.code < 400 && r.header("location") != null) {
                String to = new URL(new URL(url), r.header("location")).toString();
                r.close();
                url = to;
                if (r.code == 303) { method = "GET"; body = null; }
                continue;
            }
            return r;
        }
        throw new IOException("too many redirects");
    }

    private static Resp once(String url, String method, Map<String, String> hd, byte[] body, int timeoutMs, boolean gzip) throws IOException {
        URL u = new URL(url);
        if (!"https".equals(u.getProtocol())) throw new IOException("https only");
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : 443;
        Inet4Address[] addrs = v4(host);
        IOException last = null;
        Socket raw = null;
        for (int i = 0; i < addrs.length && i < 3 && raw == null; i++) {
            Socket s = new Socket();
            try { s.connect(new InetSocketAddress(addrs[i], port), timeoutMs); raw = s; }
            catch (IOException e) { last = e; try { s.close(); } catch (Throwable ignored) { } }
        }
        if (raw == null) throw last != null ? last : new IOException("couldn't connect");
        raw.setSoTimeout(timeoutMs);
        SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);   // (the name is sent for the right certificate)
        try {
            ssl.startHandshake();
            if (!verifier.verify(host, ssl.getSession())) throw new IOException("certificate isn't for " + host);
            StringBuilder rq = new StringBuilder();
            String path = u.getFile().isEmpty() ? "/" : u.getFile();
            rq.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            rq.append("Host: ").append(port == 443 ? host : host + ":" + port).append("\r\n");
            boolean ua = false;
            if (hd != null) for (Map.Entry<String, String> e : hd.entrySet()) {
                if (e.getKey().equalsIgnoreCase("accept-encoding") || e.getKey().equalsIgnoreCase("connection") || e.getKey().equalsIgnoreCase("host")) continue;
                if (e.getKey().equalsIgnoreCase("user-agent")) ua = true;
                rq.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
            if (!ua) rq.append("User-Agent: Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36\r\n");
            if (gzip) rq.append("Accept-Encoding: gzip\r\n");
            rq.append("Connection: close\r\n");
            if (body != null) rq.append("Content-Length: ").append(body.length).append("\r\n");
            rq.append("\r\n");
            OutputStream os = ssl.getOutputStream();
            os.write(rq.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (body != null) os.write(body);
            os.flush();
            InputStream in = new java.io.BufferedInputStream(ssl.getInputStream(), 32768);
            Resp r = new Resp();
            r.sock = ssl;
            String status = line(in);
            while (status != null && status.startsWith("HTTP/1.1 100")) { while (!line(in).isEmpty()) { } status = line(in); }
            if (status == null || !status.startsWith("HTTP/")) throw new IOException("odd answer");
            String[] sp = status.split(" ", 3);
            r.code = Integer.parseInt(sp[1]);
            r.message = sp.length > 2 ? sp[2] : "";
            String h;
            while ((h = line(in)) != null && !h.isEmpty()) {
                int c = h.indexOf(':');
                if (c > 0) r.headers.put(h.substring(0, c).trim().toLowerCase(Locale.US), h.substring(c + 1).trim());
            }
            InputStream b;
            if ("HEAD".equals(method) || r.code == 204 || r.code == 304) b = new java.io.ByteArrayInputStream(new byte[0]);
            else if ("chunked".equalsIgnoreCase(r.header("transfer-encoding"))) b = new Chunked(in);
            else if (r.header("content-length") != null) b = new Bounded(in, Long.parseLong(r.header("content-length")));
            else b = in;
            if ("gzip".equalsIgnoreCase(r.header("content-encoding"))) b = new java.util.zip.GZIPInputStream(b, 16384);
            r.body = b;
            return r;
        } catch (IOException e) {
            try { ssl.close(); } catch (Throwable ignored) { }
            throw e;
        }
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return c < 0 && sb.length() == 0 ? null : sb.toString();
    }

    static final class Bounded extends FilterInputStream {
        private long left;
        Bounded(InputStream in, long n) { super(in); left = n; }
        @Override public int read() throws IOException { if (left <= 0) return -1; int c = super.read(); if (c >= 0) left--; return c; }
        @Override public int read(byte[] b, int o, int l) throws IOException {
            if (left <= 0) return -1;
            int n = super.read(b, o, (int) Math.min(l, left));
            if (n > 0) left -= n;
            return n;
        }
    }

    static final class Chunked extends InputStream {
        private final InputStream in; private long left; private boolean done;
        Chunked(InputStream in) { this.in = in; }
        private boolean next() throws IOException {
            if (done) return false;
            if (left > 0) return true;
            String l = line(in);
            if (l != null && l.isEmpty()) l = line(in);          // (the line ending after the last piece)
            if (l == null) { done = true; return false; }
            int semi = l.indexOf(';');
            left = Long.parseLong((semi >= 0 ? l.substring(0, semi) : l).trim(), 16);
            if (left == 0) { done = true; return false; }
            return true;
        }
        @Override public int read() throws IOException { byte[] one = new byte[1]; int n = read(one, 0, 1); return n <= 0 ? -1 : one[0] & 255; }
        @Override public int read(byte[] b, int o, int l) throws IOException {
            if (!next()) return -1;
            int n = in.read(b, o, (int) Math.min(l, left));
            if (n > 0) left -= n;
            return n;
        }
        @Override public void close() throws IOException { in.close(); }
    }

    /** a whole answer as text (the code in code[0]) */
    static String text(String url, String method, Map<String, String> hd, String body, int timeoutMs, int[] code) throws IOException {
        Resp r = open(url, method, hd == null ? new LinkedHashMap<String, String>() : hd, body == null ? null : body.getBytes(StandardCharsets.UTF_8), timeoutMs, true);
        code[0] = r.code;
        return r.text();
    }
}
