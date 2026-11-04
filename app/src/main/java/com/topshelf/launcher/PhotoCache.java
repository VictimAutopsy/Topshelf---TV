package com.topshelf.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Background photos sized for the screen. Reddit's originals are often 20+ megapixels; decoding
 * and moving those on a TV chip costs a second or more and ~100 MB each time the photo changes.
 * Here they're downloaded once, shrunk to just above screen size (enough for the slow zoom, so
 * they look identical) and kept on disk.
 */
final class PhotoCache {
    private final File dir;
    private final int target;      // wanted width in pixels

    PhotoCache(File cacheDir, int screenW) {
        dir = new File(cacheDir, "photos");
        dir.mkdirs();
        // the photo layer is 6% wider than the screen and zooms in by up to 9% more
        target = Math.max(1280, Math.round(screenW * 1.18f));
    }

    byte[] get(String url, String ua) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return null;
        File f = new File(dir, key(url) + "_" + target + ".jpg");
        try {
            if (f.exists() && f.length() > 0) { f.setLastModified(System.currentTimeMillis()); return Files.readAllBytes(f.toPath()); }
            byte[] raw;
            HttpURLConnection c = LiveTv.open(url, ua, null);
            try {
                if (c.getResponseCode() >= 400) return null;
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream(1 << 20);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) { b.write(buf, 0, n); if (b.size() > 60 * 1024 * 1024) return null; }
                    raw = b.toByteArray();
                }
            } finally { c.disconnect(); }

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            if (o.outWidth <= target * 1.15f) { save(f, raw); return raw; }     // already small: use as is
            int sample = 1;
            while (o.outWidth / (sample * 2) >= target) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bm = BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
            if (bm == null) return null;
            if (bm.getWidth() > target * 1.15f) {
                Bitmap s = Bitmap.createScaledBitmap(bm, target, Math.round(bm.getHeight() * (target / (float) bm.getWidth())), true);
                if (s != bm) bm.recycle();
                bm = s;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, 92, out);
            bm.recycle();
            byte[] bytes = out.toByteArray();
            save(f, bytes);
            return bytes;
        } catch (Throwable e) {
            return null;
        }
    }

    private void save(File f, byte[] bytes) {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(bytes); } catch (Exception ignored) { }
        File[] all = dir.listFiles();
        if (all != null && all.length > 80) {
            Arrays.sort(all, new Comparator<File>() {
                @Override public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
            });
            for (int i = 0; i < all.length - 60; i++) all[i].delete();
        }
    }

    private static String key(String url) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) { return Integer.toHexString(url.hashCode()); }
    }
}
