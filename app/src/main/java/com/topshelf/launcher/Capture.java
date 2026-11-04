package com.topshelf.launcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Saves a live channel to the TV's storage as it arrives, as a local HLS stream (short .ts pieces and a
 * playlist), which the player can then play from — paused, rewound, skipped.
 *
 *  - From an HLS channel: the channel's own pieces are copied as they appear in its playlist.
 *  - From a plain MPEG-TS channel: the stream is cut into pieces of about 4 seconds, each starting where a
 *    new picture can begin (and with the stream's table of contents, PAT/PMT, at its start).
 *
 * Each piece is stamped with the wall-clock time it was received (EXT-X-PROGRAM-DATE-TIME), so a moment
 * ("3 minutes ago") can be found in it. As a rolling buffer (pause & rewind) it keeps the last maxMs and
 * deletes older pieces; as a recording it keeps everything and is closed off (EXT-X-ENDLIST) at the end.
 * Plain Java (no Android), so it can be tested on its own.
 */
final class Capture {
    interface Listener { void onCapture(Capture c, String event); }

    static final class Seg { long seq; String file; double dur; long wall; boolean disc; String key; long bytes; }

    final File dir;
    final String src, ua;
    final boolean hls;
    final long maxMs;                 // rolling buffer length (0 = keep everything: a recording)
    private final Listener listener;
    private final List<Seg> segs = new ArrayList<>();
    private volatile boolean stopped;
    private volatile String error = "";
    private long nextSeq = 1;
    private boolean ended;
    private String mapLine = "";
    private long totalBytes;
    private Thread thread;
    volatile long stopAt;             // a recording's end (wall clock); 0 = until stopped
    boolean append;                   // carry on a recording already in dir (after the channel dropped)
    private boolean pendingDisc;      // (the first new piece after carrying on: the player resets there)

    Capture(File dir, String src, String ua, boolean hls, long maxMs, Listener l) {
        this.dir = dir; this.src = src; this.ua = ua == null ? "" : ua; this.hls = hls; this.maxMs = maxMs; this.listener = l;
    }

    void start() {
        dir.mkdirs();
        if (!(append && reload())) for (File f : list(dir)) f.delete();
        thread = new Thread(new Runnable() { @Override public void run() {
            try { if (hls) runHls(); else runTs(); }
            catch (Throwable t) { if (!stopped) { error = String.valueOf(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()); event("error"); } }
            finally { finish(); }
        } }, "capture");
        thread.setDaemon(true);
        thread.start();
    }
    void stop() { stopped = true; if (thread != null) thread.interrupt(); }
    /** a stopped rolling buffer's pieces are deleted */
    void discard() { stop(); for (File f : list(dir)) f.delete(); dir.delete(); }

    File playlist() { return new File(dir, "index.m3u8"); }
    String error() { return error; }
    boolean running() { return thread != null && thread.isAlive(); }
    synchronized boolean hasData() { return !segs.isEmpty(); }
    /** the first and last moments held (wall clock, ms) */
    synchronized long startWall() { return segs.isEmpty() ? 0 : segs.get(0).wall; }
    synchronized long endWall() { if (segs.isEmpty()) return 0; Seg s = segs.get(segs.size() - 1); return s.wall + Math.round(s.dur * 1000); }
    synchronized long bytes() { return totalBytes; }
    synchronized double seconds() { double d = 0; for (Seg s : segs) d += s.dur; return d; }

    private void event(String e) { if (listener != null) try { listener.onCapture(this, e); } catch (Throwable ignored) { } }

    // ================================================================== from an HLS channel

    /** the channel's playlist of pieces (the best quality, if it offers several): {its text, its address} */
    private String[] resolveMedia() throws Exception {
        String media = src;
        String text = get(media);
        media = landed;                                   // (a channel's link often redirects: its pieces are relative to where it went)
        if (text.contains("#EXT-X-STREAM-INF")) {
            String[] lines = text.split("\r?\n");
            long best = -1; String pick = null;
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
                long bw = 0;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("[^-]BANDWIDTH=(\\d+)").matcher(lines[i]);
                if (m.find()) bw = Long.parseLong(m.group(1));
                for (int j = i + 1; j < lines.length; j++) { String u = lines[j].trim(); if (u.isEmpty() || u.startsWith("#")) continue; if (bw > best) { best = bw; pick = u; } break; }
            }
            if (pick == null) throw new Exception("no stream in the channel's playlist");
            media = resolve(media, pick);
            text = get(media);
            media = landed;
        }
        return new String[] { text, media };
    }

    private void runHls() throws Exception {
        String[] rm = resolveMedia();
        String text = rm[0], media = rm[1];
        long lastSeq = -1;
        boolean first = true;
        long quietSince = System.currentTimeMillis();
        int pieceFails = 0, backwards = 0;
        while (!stopped) {
            if (stopAt > 0 && System.currentTimeMillis() >= stopAt) break;
            long mseq = 0; double target = 6; boolean disc = false; String key = null; double dur = 0;
            List<Object[]> fresh = new ArrayList<>();   // [seq, url, dur, disc, key]
            long seq = -1, maxSeq = -1;
            for (String raw : text.split("\r?\n")) {
                String l = raw.trim();
                if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) mseq = Long.parseLong(l.substring(22).trim());
                else if (l.startsWith("#EXT-X-TARGETDURATION:")) target = Double.parseDouble(l.substring(22).trim());
                else if (l.startsWith("#EXTINF:")) { String d = l.substring(8); int c = d.indexOf(','); dur = Double.parseDouble((c >= 0 ? d.substring(0, c) : d).trim()); }
                else if (l.startsWith("#EXT-X-DISCONTINUITY") && !l.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE")) disc = true;
                else if (l.startsWith("#EXT-X-KEY:")) key = absKey(media, l);
                else if (l.startsWith("#EXT-X-MAP:") && mapLine.isEmpty()) mapLine = saveMap(media, l);
                else if (l.equals("#EXT-X-ENDLIST")) ended = true;
                else if (!l.isEmpty() && !l.startsWith("#")) {
                    if (seq < 0) seq = mseq; else seq++;
                    maxSeq = seq;
                    if (seq > lastSeq) fresh.add(new Object[] { seq, resolve(media, l), dur, disc, key });
                    disc = false; dur = 0;
                }
            }
            // the channel started counting again (a server restart, a new session): carry on from its live edge
            // (only when it stays that way: one odd answer from a load-balanced server isn't a restart)
            if (lastSeq >= 0 && maxSeq >= 0 && maxSeq < lastSeq - 3) { if (++backwards >= 2) { backwards = 0; lastSeq = -1; first = true; pendingDisc = true; continue; } }
            else backwards = 0;
            // start near the live edge (the last two pieces), then take every new one in order
            if (first && fresh.size() > 2) fresh = fresh.subList(fresh.size() - 2, fresh.size());
            if (lastSeq >= 0 && !fresh.isEmpty() && (Long) fresh.get(0)[0] > lastSeq + 1) ((Object[]) fresh.get(0))[3] = true;    // a gap: the player resets there
            if (pendingDisc && !fresh.isEmpty()) { ((Object[]) fresh.get(0))[3] = true; pendingDisc = false; }
            first = false;
            for (Object[] f : fresh) {
                if (stopped) break;
                byte[] b;
                try { b = getBytes((String) f[1]); pieceFails = 0; }
                catch (Exception e) {
                    // one piece that won't come: skip it (the player resets at the gap) rather than giving up
                    lastSeq = (Long) f[0]; pendingDisc = true;
                    if (++pieceFails >= 3) break;
                    continue;
                }
                Seg s = new Seg();
                s.seq = nextSeq++; s.dur = (Double) f[2] > 0 ? (Double) f[2] : target; s.disc = (Boolean) f[3] || pendingDisc; s.key = (String) f[4];
                pendingDisc = false;
                s.file = "s" + s.seq + ".ts"; s.bytes = b.length;
                s.wall = nextWall(s.dur, s.disc);
                writeFile(new File(dir, s.file), b);
                add(s);
                lastSeq = (Long) f[0];
                quietSince = System.currentTimeMillis();
            }
            if (ended) break;
            if (System.currentTimeMillis() - quietSince > Math.max(45000, target * 6000)) throw new Exception("the channel stopped sending");
            sleep((long) Math.max(1000, target * 500));
            if (stopped) break;
            // the playlist again; if its address stopped working (links with a short-lived token), from the channel's own link
            String again = null;
            if (pieceFails < 3) try { again = get(media); if (!landed.isEmpty()) media = landed; } catch (Exception ignored) { }
            for (int t = 0; again == null && t < 3 && !stopped; t++) {
                try { String[] r2 = resolveMedia(); again = r2[0]; media = r2[1]; pieceFails = 0; }
                catch (Exception e) { if (t == 2) throw e; sleep(2000); }
            }
            if (again != null) text = again;
        }
    }

    /** a piece's start time: straight after the previous one, unless that's far from the clock (a gap) */
    private long wallEnd;
    private long nextWall(double dur, boolean disc) {
        long now = System.currentTimeMillis(), est = now - Math.round(dur * 1000);
        long w = wallEnd > 0 && !disc && Math.abs(wallEnd - est) < 15000 ? wallEnd : est;
        synchronized (this) { if (!segs.isEmpty()) { Seg l = segs.get(segs.size() - 1); w = Math.max(w, l.wall + Math.round(l.dur * 1000)); } }   // never earlier than what's held
        wallEnd = w + Math.round(dur * 1000);
        return w;
    }

    // ================================================================== from a plain MPEG-TS channel

    private byte[] pat, pmt; private int pmtPid = -1;

    private void runTs() throws Exception {
        int reconnects = 0;
        boolean disc = pendingDisc; pendingDisc = false;
        while (!stopped) {
            if (stopAt > 0 && System.currentTimeMillis() >= stopAt) break;
            HttpURLConnection c = open(src);
            InputStream in;
            try { in = new java.io.BufferedInputStream(c.getInputStream(), 65536); } catch (Exception e) { c.disconnect(); if (++reconnects > 8) throw e; sleep(1500); disc = true; continue; }
            // (a link without .m3u8 in it that answers with an HLS playlist after all: saved as HLS)
            in.mark(16); byte[] head = new byte[7]; int got = 0, nn;
            while (got < 7 && (nn = in.read(head, got, 7 - got)) > 0) got += nn;
            in.reset();
            if (got == 7 && new String(head, "US-ASCII").equals("#EXTM3U")) { try { in.close(); } catch (Exception ignored) { } c.disconnect(); runHls(); return; }
            try {
                byte[] pkt = new byte[188];
                // find the packets' rhythm (every 188 bytes starts with 0x47)
                if (!sync(in, pkt)) throw new Exception("not an MPEG-TS stream");
                OutputStream out = null; Seg cur = null; long chunkStart = 0;
                long minMs = segs.isEmpty() ? 1500 : 4000;          // the first piece is short: playback starts sooner
                long waitKey = System.currentTimeMillis();          // a new piece after a (re)connect waits for a keyframe (up to 3 s)
                while (!stopped) {
                    if (stopAt > 0 && System.currentTimeMillis() >= stopAt) break;
                    long now = System.currentTimeMillis();
                    boolean rai = (pkt[3] & 0x20) != 0 && (pkt[4] & 0xFF) > 0 && (pkt[5] & 0x40) != 0 && (pkt[1] & 0x40) != 0;
                    if (cur != null && ((now - chunkStart >= minMs && rai) || now - chunkStart >= 10000)) {
                        out.close(); out = null;
                        cur.dur = (now - chunkStart) / 1000.0;
                        add(cur); cur = null; minMs = 4000;
                    }
                    if (cur == null && waitKey > 0) {
                        if (!rai && now - waitKey < 3000) { tables(pkt); if (!readFully(in, pkt)) break; if (pkt[0] != 0x47 && !sync(in, pkt)) break; continue; }
                        waitKey = 0;
                    }
                    if (cur == null) {
                        cur = new Seg(); cur.seq = nextSeq++; cur.file = "s" + cur.seq + ".ts"; cur.wall = now; cur.disc = disc; disc = false;
                        chunkStart = now;
                        out = new java.io.BufferedOutputStream(new FileOutputStream(new File(dir, cur.file)), 65536);
                        if (pat != null) { out.write(pat); cur.bytes += 188; }
                        if (pmt != null) { out.write(pmt); cur.bytes += 188; }
                    }
                    tables(pkt);
                    out.write(pkt); cur.bytes += 188;
                    if (!readFully(in, pkt)) break;
                    if (pkt[0] != 0x47 && !sync(in, pkt)) break;
                    reconnects = 0;
                }
                if (out != null) { out.close(); if (cur != null && cur.bytes > 188 * 20) { cur.dur = Math.max(.5, (System.currentTimeMillis() - chunkStart) / 1000.0); add(cur); } }
            } finally { try { in.close(); } catch (Exception ignored) { } c.disconnect(); }
            if (stopped || (stopAt > 0 && System.currentTimeMillis() >= stopAt)) break;
            if (++reconnects > 8) throw new Exception("the channel keeps dropping");
            disc = true;
            sleep(1000);
        }
    }
    /** keeps the latest PAT and PMT, to start each piece with them */
    private void tables(byte[] p) {
        int pid = ((p[1] & 0x1F) << 8) | (p[2] & 0xFF);
        boolean start = (p[1] & 0x40) != 0;
        if (!start) return;
        if (pid == 0) {
            pat = p.clone();
            int off = 4; if ((p[3] & 0x20) != 0) off += 1 + (p[4] & 0xFF);
            off += 1 + (p[off] & 0xFF);                       // pointer field
            int secLen = ((p[off + 1] & 0x0F) << 8) | (p[off + 2] & 0xFF);
            for (int i = off + 8; i + 4 <= off + 3 + secLen - 4 && i + 4 <= 188; i += 4) {
                int prog = ((p[i] & 0xFF) << 8) | (p[i + 1] & 0xFF);
                if (prog != 0) { pmtPid = ((p[i + 2] & 0x1F) << 8) | (p[i + 3] & 0xFF); break; }
            }
        } else if (pid == pmtPid) pmt = p.clone();
    }
    private static boolean sync(InputStream in, byte[] pkt) throws Exception {
        // read until 0x47 appears at the same place three packets running
        byte[] win = new byte[188 * 3];
        if (!readFully(in, win)) return false;
        for (int tries = 0; tries < 2000; tries++) {
            for (int i = 0; i < 188; i++) {
                if (win[i] == 0x47 && win[i + 188] == 0x47 && win[i + 376] == 0x47) {
                    // realign: keep from i; the packet at i is returned
                    byte[] rest = new byte[i];
                    System.arraycopy(win, i, pkt, 0, 188);
                    if (i > 0 && !readFully(in, rest)) return false;
                    // (the two packets after it are dropped: harmless, the stream carries on)
                    return true;
                }
            }
            System.arraycopy(win, 188, win, 0, 376);
            if (!readFully(in, win, 376, 188)) return false;
        }
        return false;
    }
    private static boolean readFully(InputStream in, byte[] b) throws Exception { return readFully(in, b, 0, b.length); }
    private static boolean readFully(InputStream in, byte[] b, int off, int len) throws Exception {
        int got = 0;
        while (got < len) { int n = in.read(b, off + got, len - got); if (n < 0) return false; got += n; }
        return true;
    }

    // ================================================================== the playlist

    private synchronized void add(Seg s) {
        segs.add(s); totalBytes += s.bytes;
        if (maxMs > 0) {
            double keep = maxMs / 1000.0, total = 0;
            for (Seg x : segs) total += x.dur;
            while (segs.size() > 3 && total - segs.get(0).dur > keep) {
                Seg old = segs.remove(0); total -= old.dur; totalBytes -= old.bytes;
                new File(dir, old.file).delete();
            }
        }
        writePlaylist(false);
        event("segment");
    }
    private synchronized void finish() {
        // a recording's playlist is closed off at its end; not when the channel dropped (it's carried on into the same one)
        if (maxMs == 0 && !segs.isEmpty() && error.isEmpty()) writePlaylist(true);
        event("stopped");
    }
    private void writePlaylist(boolean end) {
        StringBuilder b = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:").append(mapLine.isEmpty() ? 3 : 6).append('\n');
        double maxDur = 1; for (Seg s : segs) maxDur = Math.max(maxDur, s.dur);
        b.append("#EXT-X-TARGETDURATION:").append((int) Math.ceil(maxDur)).append('\n');
        b.append("#EXT-X-MEDIA-SEQUENCE:").append(segs.isEmpty() ? 0 : segs.get(0).seq).append('\n');
        if (maxMs == 0) b.append("#EXT-X-PLAYLIST-TYPE:EVENT\n");
        if (!mapLine.isEmpty()) b.append(mapLine).append('\n');
        String lastKey = null;
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US); iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (Seg s : segs) {
            if (s.disc) b.append("#EXT-X-DISCONTINUITY\n");
            if (s.key != null && !s.key.equals(lastKey)) { b.append(s.key).append('\n'); lastKey = s.key; }
            b.append("#EXT-X-PROGRAM-DATE-TIME:").append(iso.format(new Date(s.wall))).append('\n');
            b.append("#EXTINF:").append(String.format(Locale.US, "%.3f", s.dur)).append(",\n").append(s.file).append('\n');
        }
        if (end) b.append("#EXT-X-ENDLIST\n");
        try {
            File tmp = new File(dir, "index.m3u8.tmp");
            writeFile(tmp, b.toString().getBytes("UTF-8"));
            if (!tmp.renameTo(playlist())) { playlist().delete(); tmp.renameTo(playlist()); }
        } catch (Exception ignored) { }
    }

    /** reads back the pieces of a recording that's being carried on */
    private boolean reload() {
        try {
            File pl = playlist();
            if (!pl.exists()) return false;
            String text = new String(java.nio.file.Files.readAllBytes(pl.toPath()), "UTF-8");
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US); iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            boolean disc = false; String key = null; long wall = 0; double dur = 0;
            List<Seg> got = new ArrayList<>();
            for (String raw : text.split("\r?\n")) {
                String l = raw.trim();
                if (l.startsWith("#EXT-X-DISCONTINUITY") && !l.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE")) disc = true;
                else if (l.startsWith("#EXT-X-KEY:")) key = l;
                else if (l.startsWith("#EXT-X-MAP:")) mapLine = l;
                else if (l.startsWith("#EXT-X-PROGRAM-DATE-TIME:")) { try { wall = iso.parse(l.substring(25).trim()).getTime(); } catch (Exception e) { wall = 0; } }
                else if (l.startsWith("#EXTINF:")) { String d = l.substring(8); int c = d.indexOf(','); dur = Double.parseDouble((c >= 0 ? d.substring(0, c) : d).trim()); }
                else if (!l.isEmpty() && !l.startsWith("#")) {
                    File f = new File(dir, l);
                    if (f.exists()) {
                        Seg sg = new Seg(); sg.file = l; sg.dur = dur; sg.wall = wall; sg.disc = disc; sg.key = key; sg.bytes = f.length();
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("s(\\d+)\\.ts").matcher(l);
                        sg.seq = m.matches() ? Long.parseLong(m.group(1)) : got.size() + 1;
                        got.add(sg);
                    }
                    disc = false; dur = 0; wall = 0;
                }
            }
            if (got.isEmpty()) return false;
            synchronized (this) {
                segs.clear(); segs.addAll(got); totalBytes = 0;
                for (Seg sg : got) { totalBytes += sg.bytes; nextSeq = Math.max(nextSeq, sg.seq + 1); }
            }
            pendingDisc = true;
            return true;
        } catch (Exception e) { return false; }
    }
    /** closes off a recording's playlist that was left open (it won't be carried on after all) */
    static void close(File dir) {
        try {
            File pl = new File(dir, "index.m3u8");
            if (!pl.exists()) return;
            String t = new String(java.nio.file.Files.readAllBytes(pl.toPath()), "UTF-8");
            if (t.contains("#EXT-X-ENDLIST")) return;
            java.nio.file.Files.write(pl.toPath(), (t.endsWith("\n") ? t : t + "\n").concat("#EXT-X-ENDLIST\n").getBytes("UTF-8"));
        } catch (Exception ignored) { }
    }
    /** the newest piece (null if none) */
    synchronized Seg last() { return segs.isEmpty() ? null : segs.get(segs.size() - 1); }
    synchronized List<Seg> pieces() { return new ArrayList<>(segs); }

    // ================================================================== bits

    private String absKey(String base, String line) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("URI=\"([^\"]+)\"").matcher(line);
        return m.find() ? line.replace(m.group(1), resolve(base, m.group(1))) : line;
    }
    private String saveMap(String base, String line) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("URI=\"([^\"]+)\"").matcher(line);
            if (!m.find()) return "";
            writeFile(new File(dir, "init.mp4"), getBytes(resolve(base, m.group(1))));
            return line.replace(m.group(1), "init.mp4");
        } catch (Exception e) { return ""; }
    }
    static String resolve(String base, String rel) {
        try { return new URL(new URL(base), rel).toString(); } catch (Exception e) { return rel; }
    }
    private volatile String landed = "";
    /** the same name the TV's player gives (IPTV servers often only answer the players they expect) */
    private String agent() { return ua.isEmpty() ? "VLC/3.0.20 LibVLC/3.0.20" : ua; }
    private HttpURLConnection open(String url) throws Exception {
        landed = url;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(20000); c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", agent());
        c.setRequestProperty("Accept", "*/*");
        // http → https and back: followed by hand (Java won't)
        int code = c.getResponseCode();
        landed = c.getURL().toString();
        for (int i = 0; i < 4 && code >= 300 && code < 400; i++) {
            String loc = c.getHeaderField("Location"); c.disconnect();
            if (loc == null) break;
            url = resolve(url, loc); landed = url;
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000); c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", agent());
            code = c.getResponseCode();
            landed = c.getURL().toString();
        }
        if (code >= 400) { c.disconnect(); throw new Exception("the channel answered " + code); }
        return c;
    }
    private String get(String url) throws Exception { return new String(getBytes(url), "UTF-8"); }
    private byte[] getBytes(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) { bo.write(buf, 0, n); if (stopped) break; }
            return bo.toByteArray();
        } finally { c.disconnect(); }
    }
    private static void writeFile(File f, byte[] b) throws Exception { try (FileOutputStream o = new FileOutputStream(f)) { o.write(b); } }
    private static File[] list(File d) { File[] f = d.listFiles(); return f == null ? new File[0] : f; }
    private void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { stopped = stopped || Thread.currentThread().isInterrupted(); } }
}
