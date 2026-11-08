package com.topshelf.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pictures for the native screens: decoded off the main thread at the size they're shown, kept in memory. */
final class HomeImages {
    interface Source { byte[] bytes() throws Exception; }
    interface Done { void done(Bitmap b); }
    interface Maker { Bitmap make() throws Exception; }

    /** like load(), for pictures drawn in code */
    void make(final String key, final Maker m, final Done done) {
        load(key, null, 0, 0, done, m);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(48 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private final Map<String, List<Done>> waiting = new HashMap<>();
    private final Runnable trimmer = new Runnable() { @Override public void run() { cache.evictAll(); } };
    { Diag.TRIMMERS.add(trimmer); }
    /** this Home is being replaced (a phone turned): its pictures and threads go */
    void dispose() { Diag.TRIMMERS.remove(trimmer); main.removeCallbacksAndMessages(null); pool.shutdownNow(); cache.evictAll(); }

    Bitmap cached(String key) { return cache.get(key); }
    /** how much the picture cache holds (MB, items) */
    String cacheInfo() { return (cache.size() / 1048576) + " MB, " + cache.snapshot().size() + " pictures"; }

    /** maxW/maxH: the size it's drawn at (decoded no bigger than about twice that) */
    void load(final String key, final Source src, final int maxW, final int maxH, final Done done) { load(key, src, maxW, maxH, done, null); }

    private void load(final String key, final Source src, final int maxW, final int maxH, final Done done, final Maker maker) {
        Bitmap b = cache.get(key);
        if (b != null) { done.done(b); return; }
        synchronized (waiting) {
            List<Done> l = waiting.get(key);
            if (l != null) { l.add(done); return; }
            l = new ArrayList<>(); l.add(done); waiting.put(key, l);
        }
        pool.execute(new Runnable() { @Override public void run() {
            Bitmap out = null;
            try {
                if (maker != null) out = maker.make();
                else {
                    byte[] d = src.bytes();
                    if (d != null && d.length > 0) out = decode(d, maxW, maxH);
                }
            } catch (Throwable ignored) { }
            if (out != null) cache.put(key, out);
            final Bitmap res = out;
            final List<Done> l;
            synchronized (waiting) { l = waiting.remove(key); }
            main.post(new Runnable() { @Override public void run() { if (l != null) for (Done x : l) x.done(res); } });
        } });
    }

    static Bitmap decode(byte[] d, int maxW, int maxH) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(d, 0, d.length, o);
        int s = 1;
        if (maxW > 0 && maxH > 0) while (o.outWidth / (s * 2) >= maxW && o.outHeight / (s * 2) >= maxH) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        return BitmapFactory.decodeByteArray(d, 0, d.length, o2);
    }

    static byte[] http(String url, String ua) throws Exception {
        HttpURLConnection c = LiveTv.open(url, ua, null);
        try {
            if (c.getResponseCode() >= 400) return null;
            InputStream in = c.getInputStream();
            // (sized from the reply's length when it says it: the picture is read into one block, not a growing
            // buffer copied over and over — less for the memory cleaner to sweep up behind every picture)
            int len = c.getContentLength();
            if (len > 0 && len < 48 * 1024 * 1024) {
                byte[] d = new byte[len]; int off = 0, n;
                while (off < len && (n = in.read(d, off, len - off)) > 0) off += n;
                in.close();
                return off == len ? d : java.util.Arrays.copyOf(d, off);
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream(256 * 1024);
            byte[] buf = new byte[32768]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } finally { c.disconnect(); }
    }

    /** a picture's size {width, height} from its first bytes only (a JPEG / PNG / WebP says it near the start):
     *  {0, 0} if it couldn't be read. Much cheaper than fetching the whole picture to measure it. */
    static int[] size(String url) {
        HttpURLConnection c = null;
        try {
            c = LiveTv.open(url, null, "bytes=0-131071");
            if (c.getResponseCode() >= 400) return new int[] { 0, 0 };
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n, got = 0;
            while (got < 131072 && (n = in.read(buf)) > 0) { bo.write(buf, 0, n); got += n; }
            in.close();
            byte[] d = bo.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(d, 0, d.length, o);
            return new int[] { Math.max(0, o.outWidth), Math.max(0, o.outHeight) };
        } catch (Throwable e) { return new int[] { 0, 0 }; }
        finally { if (c != null) c.disconnect(); }
    }

    void run(Runnable r) { pool.execute(r); }
}
