package com.topshelf.launcher;

import android.content.Context;
import android.os.Debug;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What fills TopShelf's memory after it has been running a while. TopShelf's own (Java) memory starts near 60 MB and
 * was seen at 150–460 MB after two days: this says which kinds of things hold it, so the fix goes where the memory is.
 *
 * Android lets an app write a copy of its own memory (Debug.dumpHprofData). It is read here, on the TV, and only a
 * summary is kept: for each kind of object, how many and how much memory (the biggest 60), how many pictures
 * (Bitmaps) are held and their sizes, and the biggest single arrays. No contents: no text, no links, no names
 * beyond the code's own class names. The copy is deleted straight after. It takes a few seconds and stops the app
 * meanwhile, so it runs only while the screensaver is on, after TopShelf has run 6 hours, at most once a day.
 * The summary goes to reports/heap/ in the private updates repository.
 */
final class HeapReport {
    private HeapReport() { }

    private static volatile boolean running;

    /** the screensaver came on: a memory summary if one is due */
    static void maybe(final Context ctx) {
        if (running) return;
        final android.content.SharedPreferences pr = ctx.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE);
        if (SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime() < 6 * 3600000L) return;
        if (System.currentTimeMillis() - pr.getLong("heapReportAt", 0) < 20 * 3600000L) return;
        Runtime rt = Runtime.getRuntime();
        if (rt.totalMemory() - rt.freeMemory() < 120L * 1048576L) return;        // (not grown: nothing to look for)
        running = true;
        pr.edit().putLong("heapReportAt", System.currentTimeMillis()).apply();
        new Thread(new Runnable() { @Override public void run() {
            try { HeapReport.run(ctx, "screensaver after " + Math.round((SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()) / 3600000.0) + " h"); }
            catch (Throwable t) { Diag.ev("memory report: couldn't make it (" + t.getClass().getSimpleName() + ")"); }
            finally { running = false; }
        } }, "heap-report").start();
    }

    static void run(Context ctx, String why) throws Exception {
        File f = new File(ctx.getCacheDir(), "heap.hprof");
        if (f.getParentFile() != null && f.getParentFile().getUsableSpace() < 1500L * 1048576L) { Diag.ev("memory report: not enough free space"); return; }
        Runtime rt = Runtime.getRuntime();
        System.gc(); System.runFinalization(); System.gc();
        long used = rt.totalMemory() - rt.freeMemory();
        long t0 = SystemClock.elapsedRealtime();
        try {
            Debug.dumpHprofData(f.getAbsolutePath());
            long dumpMs = SystemClock.elapsedRealtime() - t0;
            JSONObject o = read(f);
            o.put("why", why).put("version", version(ctx)).put("javaUsedAfterGcMB", Math.round(used / 1048576.0))
             .put("fileMB", Math.round(f.length() / 1048576.0)).put("dumpMs", dumpMs).put("readMs", SystemClock.elapsedRealtime() - t0 - dumpMs);
            Diag.ev("memory report: java " + Math.round(used / 1048576.0) + " MB after clean-up · biggest: " + o.optString("top3"));
            String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm", java.util.Locale.US).format(new java.util.Date());
            Updater u = new Updater(ctx, ctx.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE));
            if (!u.token().isEmpty()) u.upload("reports/heap/" + stamp + "-" + version(ctx) + ".json", Diag.scrub(o.toString(1)), "Memory report " + version(ctx));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    // ------------------------------------------------------------------ reading the copy (the HPROF format)

    private static final class Cls { String name = "?"; long count, bytes; int instSize; long superId; String[] fieldNames; int[] fieldTypes; }

    private static JSONObject read(File f) throws IOException, org.json.JSONException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16));
        try {
            while (in.readByte() != 0) { }                   // "JAVA PROFILE 1.0.3"
            final int idSize = in.readInt();
            in.readLong();
            Map<Long, String> strings = new HashMap<>();
            Map<Long, Long> classNameId = new HashMap<>();   // class object id → name string id
            Map<Long, Cls> classes = new HashMap<>();
            Map<String, long[]> prim = new HashMap<>();      // "byte[]" → {count, bytes}
            Map<Long, long[]> objArr = new HashMap<>();      // array class id → {count, bytes}
            List<long[]> bitmaps = new ArrayList<>();        // {width, height}
            Map<Long, String> bigPics = new HashMap<>();     // pictures of 1 MB or more: id → "WxH"
            List<long[]> bigArrays = new ArrayList<>();      // {bytes, type}
            List<byte[]> bitmapData = new ArrayList<>();     // Bitmap instances' field data (read once Bitmap's layout is known)
            List<Long> bitmapIds = new ArrayList<>();
            long appBytes = 0;
            boolean appHeap = true;
            Long bitmapClass = null;
            while (true) {
                int tag;
                try { tag = in.readUnsignedByte(); } catch (EOFException e) { break; }
                in.readInt();
                long len = in.readInt() & 0xFFFFFFFFL;
                if (tag == 0x01) {                           // a name
                    long id = id(in, idSize);
                    byte[] b = new byte[(int) (len - idSize)]; in.readFully(b);
                    strings.put(id, new String(b, "UTF-8"));
                } else if (tag == 0x02) {                    // a class
                    in.readInt(); long cid = id(in, idSize); in.readInt(); long nid = id(in, idSize);
                    classNameId.put(cid, nid);
                } else if (tag == 0x0C || tag == 0x1C) {     // the memory itself
                    long end = len;
                    while (end > 0) {
                        int st = in.readUnsignedByte(); end -= 1;
                        switch (st) {
                            case 0xFF: case 0x05: case 0x07: case 0x89: case 0x8A: case 0x8B: case 0x8C: case 0x8D: case 0x90:
                                skip(in, idSize); end -= idSize; break;
                            case 0x01: skip(in, idSize * 2); end -= idSize * 2; break;
                            case 0x02: case 0x03: case 0x08: case 0x8E: skip(in, idSize + 8); end -= idSize + 8; break;
                            case 0x04: case 0x06: skip(in, idSize + 4); end -= idSize + 4; break;
                            case 0xFE: {                     // which heap follows: the app's own, or Android's shared ones
                                int heap = in.readInt(); long nid = id(in, idSize); end -= 4 + idSize;
                                String hn = strings.get(nid);
                                appHeap = hn == null ? heap == 'A' : hn.equals("app");
                                break;
                            }
                            case 0x20: {                     // a class's layout
                                long cid = id(in, idSize); in.readInt(); long sup = id(in, idSize);
                                skip(in, idSize * 5); int inst = in.readInt();
                                end -= idSize * 7 + 8;
                                int cp = in.readUnsignedShort(); end -= 2;
                                for (int i = 0; i < cp; i++) { in.readUnsignedShort(); int t = in.readUnsignedByte(); int s = size(t, idSize); skip(in, s); end -= 3 + s; }
                                int sf = in.readUnsignedShort(); end -= 2;
                                for (int i = 0; i < sf; i++) { skip(in, idSize); int t = in.readUnsignedByte(); int s = size(t, idSize); skip(in, s); end -= idSize + 1 + s; }
                                int nf = in.readUnsignedShort(); end -= 2;
                                Cls c = classes.get(cid); if (c == null) { c = new Cls(); classes.put(cid, c); }
                                c.instSize = inst; c.superId = sup; c.fieldNames = new String[nf]; c.fieldTypes = new int[nf];
                                for (int i = 0; i < nf; i++) { long fn = id(in, idSize); c.fieldNames[i] = strings.get(fn); c.fieldTypes[i] = in.readUnsignedByte(); end -= idSize + 1; }
                                break;
                            }
                            case 0x21: {                     // an object
                                long oid = id(in, idSize); in.readInt(); long cid = id(in, idSize); int n = in.readInt();
                                end -= idSize * 2 + 8 + n;
                                if (appHeap) {
                                    Cls c = classes.get(cid); if (c == null) { c = new Cls(); classes.put(cid, c); }
                                    c.count++; c.bytes += n; appBytes += n;
                                    if (bitmapClass == null) { String nm = className(cid, classNameId, strings); if ("android.graphics.Bitmap".equals(nm)) bitmapClass = cid; }
                                    if (bitmapClass != null && bitmapClass == cid && bitmapData.size() < 5000) { byte[] b = new byte[n]; in.readFully(b); bitmapData.add(b); bitmapIds.add(oid); break; }
                                }
                                skip(in, n);
                                break;
                            }
                            case 0x22: {                     // an array of objects
                                skip(in, idSize); in.readInt(); int num = in.readInt(); long acid = id(in, idSize);
                                long b = (long) num * idSize; skip(in, b); end -= idSize * 2 + 8 + b;
                                if (appHeap) { long[] a = objArr.get(acid); if (a == null) { a = new long[2]; objArr.put(acid, a); } a[0]++; a[1] += b; appBytes += b; if (b > 1048576) bigArrays.add(new long[] { b, 2 }); }
                                break;
                            }
                            case 0x23: case 0xC3: {          // an array of numbers or characters
                                skip(in, idSize); in.readInt(); int num = in.readInt(); int t = in.readUnsignedByte();
                                long b = (long) num * size(t, idSize);
                                end -= idSize + 9;
                                if (st == 0x23) { skip(in, b); end -= b; }
                                if (appHeap) {
                                    String k = primName(t) + "[]"; long[] a = prim.get(k); if (a == null) { a = new long[2]; prim.put(k, a); } a[0]++; a[1] += b; appBytes += b;
                                    if (b > 1048576) bigArrays.add(new long[] { b, t });
                                }
                                break;
                            }
                            default: throw new IOException("unknown part " + Integer.toHexString(st));
                        }
                    }
                } else {
                    skip(in, len);
                }
            }
            // the pictures' sizes (a Bitmap's width and height fields)
            if (bitmapClass != null) {
                Cls bc = classes.get(bitmapClass);
                int wOff = -1, hOff = -1, off = 0;
                if (bc != null && bc.fieldNames != null) for (int i = 0; i < bc.fieldNames.length; i++) {
                    if ("mWidth".equals(bc.fieldNames[i])) wOff = off;
                    if ("mHeight".equals(bc.fieldNames[i])) hOff = off;
                    off += size(bc.fieldTypes[i], idSize);
                }
                if (wOff >= 0 && hOff >= 0) for (int k = 0; k < bitmapData.size(); k++) {
                    byte[] b = bitmapData.get(k);
                    if (b.length < Math.max(wOff, hOff) + 4) continue;
                    bitmaps.add(new long[] { int4(b, wOff), int4(b, hOff) });
                    long px = (long) int4(b, wOff) * int4(b, hOff);
                    if (px * 4 >= 1048576L && bigPics.size() < 150) bigPics.put(bitmapIds.get(k), int4(b, wOff) + "x" + int4(b, hOff));
                }
            }
            // the summary
            List<Object[]> rows = new ArrayList<>();
            for (Map.Entry<Long, Cls> e : classes.entrySet()) if (e.getValue().count > 0) rows.add(new Object[] { className(e.getKey(), classNameId, strings), e.getValue().count, e.getValue().bytes });
            for (Map.Entry<String, long[]> e : prim.entrySet()) rows.add(new Object[] { e.getKey(), e.getValue()[0], e.getValue()[1] });
            for (Map.Entry<Long, long[]> e : objArr.entrySet()) rows.add(new Object[] { className(e.getKey(), classNameId, strings), e.getValue()[0], e.getValue()[1] });
            Collections.sort(rows, new Comparator<Object[]>() { @Override public int compare(Object[] a, Object[] b) { return Long.compare((Long) b[2], (Long) a[2]); } });
            JSONArray top = new JSONArray(); StringBuilder top3 = new StringBuilder();
            for (int i = 0; i < Math.min(60, rows.size()); i++) {
                Object[] r = rows.get(i);
                top.put(new JSONObject().put("kind", r[0]).put("count", r[1]).put("MB", Math.round((Long) r[2] / 104857.6) / 10.0));
                if (i < 3) top3.append(i > 0 ? ", " : "").append(r[0]).append(' ').append(Math.round((Long) r[2] / 1048576.0)).append(" MB");
            }
            Map<String, Integer> bmSizes = new HashMap<>(); long bmPixels = 0;
            for (long[] b : bitmaps) { String k = b[0] + "x" + b[1]; Integer c = bmSizes.get(k); bmSizes.put(k, c == null ? 1 : c + 1); bmPixels += b[0] * b[1]; }
            List<Map.Entry<String, Integer>> bl = new ArrayList<>(bmSizes.entrySet());
            Collections.sort(bl, new Comparator<Map.Entry<String, Integer>>() { @Override public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) { return b.getValue() - a.getValue(); } });
            JSONObject bms = new JSONObject();
            for (int i = 0; i < Math.min(25, bl.size()); i++) bms.put(bl.get(i).getKey(), bl.get(i).getValue());
            Collections.sort(bigArrays, new Comparator<long[]>() { @Override public int compare(long[] a, long[] b) { return Long.compare(b[0], a[0]); } });
            JSONArray big = new JSONArray();
            for (int i = 0; i < Math.min(20, bigArrays.size()); i++) big.put(primName((int) bigArrays.get(i)[1]) + "[] " + Math.round(bigArrays.get(i)[0] / 104857.6) / 10.0 + " MB");
            // who holds the big pictures (the first of TopShelf's own parts found going up from each)
            JSONArray held = new JSONArray();
            try {
                in.close();
                Map<String, Integer> by = owners(f, idSize, classes, classNameId, strings, bigPics);
                List<Map.Entry<String, Integer>> hl = new ArrayList<>(by.entrySet());
                Collections.sort(hl, new Comparator<Map.Entry<String, Integer>>() { @Override public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) { return b.getValue() - a.getValue(); } });
                for (Map.Entry<String, Integer> e : hl) held.put(e.getValue() + " × " + e.getKey());
            } catch (Throwable t) { held.put("couldn't trace: " + t.getClass().getSimpleName()); }
            return new JSONObject().put("bigPicturesHeldBy", held).put("appHeapMB", Math.round(appBytes / 1048576.0)).put("top3", top3.toString()).put("kinds", top)
                    .put("pictures", new JSONObject().put("count", bitmaps.size()).put("pixelMB", Math.round(bmPixels * 4 / 1048576.0)).put("sizes", bms))
                    .put("biggestArrays", big);
        } finally {
            in.close();
        }
    }

    /* Who holds each big picture: the file is read again (a few times), each time finding what points at the
       pictures (or at what pointed at them last time), until one of TopShelf's own parts is reached. */
    private static Map<String, Integer> owners(File f, int idSize, Map<Long, Cls> classes, Map<Long, Long> names, Map<Long, String> strings, Map<Long, String> targets) throws IOException {
        Map<String, Integer> out = new HashMap<>();
        Map<Long, String> frontier = new HashMap<>(targets);       // id → what it leads to ("1920x1080 ← …")
        Map<Long, Integer> many = new HashMap<>();                 // id → how many pictures it leads to
        Map<Long, String[]> layouts = new HashMap<>();
        for (int pass = 0; pass < 6 && !frontier.isEmpty(); pass++) {
            Map<Long, String> found = new HashMap<>();             // frontier id → its holder's description
            Map<Long, Long> holder = new HashMap<>();              // frontier id → holder id (0: a class's static field)
            DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16));
            try {
                while (in.readByte() != 0) { }
                in.readInt(); in.readLong();
                while (true) {
                    int tag;
                    try { tag = in.readUnsignedByte(); } catch (EOFException e) { break; }
                    in.readInt();
                    long len = in.readInt() & 0xFFFFFFFFL;
                    if (tag != 0x0C && tag != 0x1C) { skip(in, len); continue; }
                    long end = len;
                    while (end > 0) {
                        int st = in.readUnsignedByte(); end -= 1;
                        switch (st) {
                            case 0xFF: case 0x05: case 0x07: case 0x89: case 0x8A: case 0x8B: case 0x8C: case 0x8D: case 0x90: skip(in, idSize); end -= idSize; break;
                            case 0x01: skip(in, idSize * 2); end -= idSize * 2; break;
                            case 0x02: case 0x03: case 0x08: case 0x8E: skip(in, idSize + 8); end -= idSize + 8; break;
                            case 0x04: case 0x06: skip(in, idSize + 4); end -= idSize + 4; break;
                            case 0xFE: skip(in, 4 + idSize); end -= 4 + idSize; break;
                            case 0x20: {
                                long cid = id(in, idSize); in.readInt(); id(in, idSize); skip(in, idSize * 5); in.readInt();
                                end -= idSize * 7 + 8;
                                int cp = in.readUnsignedShort(); end -= 2;
                                for (int i = 0; i < cp; i++) { in.readUnsignedShort(); int t = in.readUnsignedByte(); int sz = size(t, idSize); skip(in, sz); end -= 3 + sz; }
                                int sf = in.readUnsignedShort(); end -= 2;
                                for (int i = 0; i < sf; i++) {
                                    long fn = id(in, idSize); int t = in.readUnsignedByte(); int sz = size(t, idSize); end -= idSize + 1 + sz;
                                    if (t == 2) { long v = id(in, idSize); if (frontier.containsKey(v) && !found.containsKey(v)) { found.put(v, className(cid, names, strings) + "." + strings.get(fn) + " (static)"); holder.put(v, 0L); } }
                                    else skip(in, sz);
                                }
                                int nf = in.readUnsignedShort(); skip(in, (long) nf * (idSize + 1)); end -= 2 + (long) nf * (idSize + 1);
                                break;
                            }
                            case 0x21: {
                                long oid = id(in, idSize); in.readInt(); long cid = id(in, idSize); int n = in.readInt();
                                end -= idSize * 2 + 8 + n;
                                byte[] b = new byte[n]; in.readFully(b);
                                String[] lay = layouts.get(cid);
                                if (lay == null) { lay = layout(cid, classes, idSize); layouts.put(cid, lay); }
                                for (int i = 0; i < lay.length; i += 2) {
                                    int off = Integer.parseInt(lay[i + 1]);
                                    if (off + idSize > n) break;
                                    long v = idSize == 4 ? (int4(b, off) & 0xFFFFFFFFL) : (((long) int4(b, off)) << 32) | (int4(b, off + 4) & 0xFFFFFFFFL);
                                    if (v != 0 && frontier.containsKey(v) && !found.containsKey(v)) { found.put(v, className(cid, names, strings) + "." + lay[i]); holder.put(v, oid); }
                                }
                                break;
                            }
                            case 0x22: {
                                long aid = id(in, idSize); in.readInt(); int num = in.readInt(); long acid = id(in, idSize);
                                end -= idSize * 2 + 8 + (long) num * idSize;
                                for (int i = 0; i < num; i++) { long v = id(in, idSize); if (v != 0 && frontier.containsKey(v) && !found.containsKey(v)) { found.put(v, className(acid, names, strings)); holder.put(v, aid); } }
                                break;
                            }
                            case 0x23: case 0xC3: {
                                skip(in, idSize); in.readInt(); int num = in.readInt(); int t = in.readUnsignedByte();
                                long bb = (long) num * size(t, idSize); end -= idSize + 9;
                                if (st == 0x23) { skip(in, bb); end -= bb; }
                                break;
                            }
                            default: throw new IOException("unknown part " + Integer.toHexString(st));
                        }
                    }
                }
            } finally { in.close(); }
            Map<Long, String> next = new HashMap<>();
            Map<Long, Integer> nextMany = new HashMap<>();
            for (Map.Entry<Long, String> e : frontier.entrySet()) {
                int n = many.containsKey(e.getKey()) ? many.get(e.getKey()) : 1;
                String via = found.get(e.getKey());
                if (via == null) { add(out, e.getValue() + " ← (nothing found)", n); continue; }
                String chain = e.getValue() + " ← " + via;
                Long hid = holder.get(e.getKey());
                if (via.startsWith("com.topshelf") || hid == null || hid == 0L || pass == 5) add(out, shorten(chain), n);
                else if (!next.containsKey(hid)) { next.put(hid, chain); nextMany.put(hid, n); }
                else nextMany.put(hid, nextMany.get(hid) + n);          // (several pictures in one list: followed once)
            }
            frontier = next; many = nextMany;
        }
        for (Map.Entry<Long, String> e : frontier.entrySet()) add(out, shorten(e.getValue()), many.containsKey(e.getKey()) ? many.get(e.getKey()) : 1);
        return out;
    }
    private static void add(Map<String, Integer> m, String k, int n) { Integer c = m.get(k); m.put(k, c == null ? n : c + n); }
    /** "1920x1080 ← a ← b ← … ← TopShelf part" kept short: the size, then the last three steps */
    private static String shorten(String chain) {
        String[] p = chain.split(" ← ");
        if (p.length <= 4) return chain.replace("com.topshelf.launcher.", "");
        return (p[0] + " ← … ← " + p[p.length - 3] + " ← " + p[p.length - 2] + " ← " + p[p.length - 1]).replace("com.topshelf.launcher.", "");
    }
    /** an object's reference fields: {name, offset, name, offset…} — its own class's fields first, then its parents' */
    private static String[] layout(long cid, Map<Long, Cls> classes, int idSize) {
        List<String> l = new ArrayList<>();
        int off = 0; long c = cid; int guard = 0;
        while (c != 0 && guard++ < 40) {
            Cls k = classes.get(c);
            if (k == null || k.fieldNames == null) break;
            for (int i = 0; i < k.fieldNames.length; i++) {
                if (k.fieldTypes[i] == 2) { l.add(String.valueOf(k.fieldNames[i])); l.add(String.valueOf(off)); }
                off += size(k.fieldTypes[i], idSize);
            }
            c = k.superId;
        }
        return l.toArray(new String[0]);
    }
    private static String version(Context c) { try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; } }
    private static long id(DataInputStream in, int n) throws IOException { return n == 4 ? in.readInt() & 0xFFFFFFFFL : in.readLong(); }
    private static void skip(DataInputStream in, long n) throws IOException {
        while (n > 0) { long s = in.skip(n); if (s <= 0) { in.readByte(); s = 1; } n -= s; }
    }
    private static int size(int t, int idSize) {
        switch (t) { case 2: return idSize; case 4: case 8: return 1; case 5: case 9: return 2; case 6: case 10: return 4; case 7: case 11: return 8; default: return 0; }
    }
    private static String primName(int t) {
        switch (t) { case 2: return "Object"; case 4: return "boolean"; case 5: return "char"; case 6: return "float"; case 7: return "double"; case 8: return "byte"; case 9: return "short"; case 10: return "int"; case 11: return "long"; default: return "?"; }
    }
    private static int int4(byte[] b, int o) { return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF); }
    private static String className(long cid, Map<Long, Long> names, Map<Long, String> strings) {
        Long n = names.get(cid); String s = n == null ? null : strings.get(n);
        return s == null ? "?" : s.replace('/', '.');
    }
}
