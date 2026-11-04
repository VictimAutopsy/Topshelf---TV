package com.topshelf.launcher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Live TV recordings: what's recorded, coming up and on series rules (recordings.json), the alarms that start
 * them, and the recordings in progress (each a Capture, kept going by RecordService).
 *
 * A recording is {id, n (channel), u (stream), ua, k (guide key), l (logo), title, desc, ep, start, end,
 * status: scheduled | recording | done | failed | cancelled, dir, bytes, secs, err, rule, pos}.
 * A series rule is {id, title, n, u, ua, k, l, mode: "new" | "all", eps: [episode keys already taken]}.
 *
 * Storage: a USB drive when one is plugged in, otherwise the TV's own storage; recordings stop rather than
 * leave the TV with less than 3 GB free.
 */
final class Recorder {
    static final long PRE = 60_000L, POST = 3 * 60_000L;          // start a minute early, run three minutes over
    static final long KEEP_FREE = 3L << 30;
    private static Recorder inst;

    static synchronized Recorder get(Context c) {
        if (inst == null) inst = new Recorder(c.getApplicationContext());
        return inst;
    }

    private final Context app;
    private final File meta;
    private final Handler main = new Handler(Looper.getMainLooper());
    private JSONArray recs = new JSONArray(), rules = new JSONArray();
    private final Map<String, Capture> active = new HashMap<>();
    private final Map<String, Integer> retries = new HashMap<>();
    /** the TV's Live TV screen, told when anything changes (on the main thread) */
    static volatile Runnable onChange;
    /** the guide (the TV's, already read) for the series rules */
    static volatile LiveTv guide;
    static volatile Uploader uploader;
    /** the recording on screen right now (its files mustn't be moved away under the player) */
    static volatile String watching = "";
    interface Uploader { void recordingPiece(JSONObject rec, File dir, Capture.Seg seg); void recordingDone(JSONObject rec, File dir); void deleted(JSONObject rec); }

    private Recorder(Context c) {
        app = c;
        meta = new File(c.getFilesDir(), "recordings.json");
        try {
            JSONObject o = new JSONObject(new String(java.nio.file.Files.readAllBytes(meta.toPath()), "UTF-8"));
            recs = o.optJSONArray("recs") != null ? o.optJSONArray("recs") : new JSONArray();
            rules = o.optJSONArray("rules") != null ? o.optJSONArray("rules") : new JSONArray();
        } catch (Exception ignored) { }
        // anything marked as recording when the app was last stopped was cut short
        long now = System.currentTimeMillis();
        for (int i = 0; i < recs.length(); i++) {
            JSONObject r = recs.optJSONObject(i);
            if (r != null && "recording".equals(r.optString("status"))) {
                try {
                    if (r.optLong("end") + POST > now) r.put("status", "scheduled");            // (it's picked up again)
                    else { r.put("status", hasData(r) ? "done" : "failed"); if (!hasData(r)) r.put("err", "TopShelf was stopped while recording"); else r.put("err", "Cut short: TopShelf was stopped"); }
                } catch (Exception ignored) { }
            }
        }
    }

    // ================================================================== saved state

    private synchronized void save() {
        try {
            JSONObject o = new JSONObject(); o.put("recs", recs); o.put("rules", rules);
            File tmp = new File(meta.getPath() + ".tmp");
            java.nio.file.Files.write(tmp.toPath(), o.toString().getBytes("UTF-8"));
            if (!tmp.renameTo(meta)) { meta.delete(); tmp.renameTo(meta); }
        } catch (Exception ignored) { }
        final Runnable r = onChange;
        if (r != null) main.post(r);
    }

    synchronized List<JSONObject> list() {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < recs.length(); i++) { JSONObject r = recs.optJSONObject(i); if (r != null && !"cancelled".equals(r.optString("status"))) out.add(copy(r)); }
        return out;
    }
    synchronized List<JSONObject> rules() {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < rules.length(); i++) { JSONObject r = rules.optJSONObject(i); if (r != null) out.add(copy(r)); }
        return out;
    }
    synchronized JSONObject find(String id) { JSONObject r = raw(id); return r == null ? null : copy(r); }
    private JSONObject raw(String id) {
        for (int i = 0; i < recs.length(); i++) { JSONObject r = recs.optJSONObject(i); if (r != null && r.optString("id").equals(id)) return r; }
        return null;
    }
    private static JSONObject copy(JSONObject o) { try { return new JSONObject(o.toString()); } catch (Exception e) { return new JSONObject(); } }

    /** the scheduled or running recording of this channel covering the moment `at` (or null) */
    synchronized JSONObject at(String u, long at) {
        for (int i = 0; i < recs.length(); i++) {
            JSONObject r = recs.optJSONObject(i);
            if (r == null || !r.optString("u").equals(u)) continue;
            String st = r.optString("status");
            if (!st.equals("scheduled") && !st.equals("recording")) continue;
            if (r.optLong("start") <= at && at < r.optLong("end")) return copy(r);
        }
        return null;
    }
    synchronized JSONObject ruleFor(String u, String title) {
        for (int i = 0; i < rules.length(); i++) {
            JSONObject r = rules.optJSONObject(i);
            if (r != null && r.optString("u").equals(u) && r.optString("title").equalsIgnoreCase(title.trim())) return copy(r);
        }
        return null;
    }
    synchronized int activeCount() { return active.size(); }
    synchronized Capture capture(String id) { return active.get(id); }

    // ================================================================== what to record

    /** records a programme (or, with start in the past, from now until its end) */
    synchronized JSONObject add(JSONObject ch, long start, long end, String title, String desc, String ep, String rule) {
        try {
            JSONObject have = at(ch.optString("u"), Math.max(start, System.currentTimeMillis()));
            if (have != null && Math.abs(have.optLong("start") - start) < 60000) return have;
            JSONObject r = new JSONObject();
            String id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
            r.put("id", id);
            for (String k : new String[] { "n", "u", "ua", "k", "l" }) r.put(k, ch.optString(k));
            r.put("title", title == null || title.isEmpty() ? ch.optString("n") : title);
            r.put("desc", desc == null ? "" : desc); r.put("ep", ep == null ? "" : ep);
            r.put("start", start); r.put("end", end);
            r.put("status", "scheduled"); r.put("rule", rule == null ? "" : rule);
            r.put("added", System.currentTimeMillis());
            recs.put(r);
            save();
            arm(r);
            Diag.ev("record: scheduled " + r.optString("title") + " on " + r.optString("n"));
            return copy(r);
        } catch (Exception e) { return null; }
    }

    /** a recording that's coming up is dropped; one in progress is stopped (and kept) */
    synchronized void cancel(String id) {
        JSONObject r = raw(id);
        if (r == null) return;
        Capture c = active.get(id);
        try {
            if (c != null) { r.put("stopAsked", true); c.stop(); }
            else if ("scheduled".equals(r.optString("status"))) { r.put("status", "cancelled"); disarm(id); }
        } catch (Exception ignored) { }
        save();
    }

    synchronized void delete(String id) {
        JSONObject r = raw(id);
        if (r == null) return;
        Capture c = active.remove(id);
        if (c != null) c.stop();
        disarm(id);
        if (!r.optString("dir").isEmpty()) wipe(new File(r.optString("dir")));
        Uploader up = uploader; if (up != null) up.deleted(copy(r));
        JSONArray keep = new JSONArray();
        for (int i = 0; i < recs.length(); i++) { JSONObject x = recs.optJSONObject(i); if (x != null && !x.optString("id").equals(id)) keep.put(x); }
        recs = keep;
        save();
        RecordService.idleCheck(app);
    }

    synchronized void setPos(String id, long wall) {
        JSONObject r = raw(id);
        if (r == null) return;
        try { r.put("pos", wall); } catch (Exception ignored) { }
        save();
    }
    synchronized void put(String id, String key, Object v) {
        JSONObject r = raw(id);
        if (r == null) return;
        try { r.put(key, v); } catch (Exception ignored) { }
        save();
    }

    // ================================================================== series

    synchronized JSONObject addRule(JSONObject ch, String title, String mode) {
        try {
            JSONObject have = ruleFor(ch.optString("u"), title);
            if (have != null) return have;
            JSONObject r = new JSONObject();
            r.put("id", "r" + Long.toString(System.currentTimeMillis(), 36));
            for (String k : new String[] { "n", "u", "ua", "k", "l" }) r.put(k, ch.optString(k));
            r.put("title", title.trim()); r.put("mode", mode); r.put("eps", new JSONArray());
            rules.put(r);
            save();
            scan();
            return copy(r);
        } catch (Exception e) { return null; }
    }
    synchronized void removeRule(String id) {
        JSONArray keep = new JSONArray();
        for (int i = 0; i < rules.length(); i++) { JSONObject x = rules.optJSONObject(i); if (x != null && !x.optString("id").equals(id)) keep.put(x); }
        rules = keep;
        // its recordings still to come are dropped (the ones already recorded stay)
        for (int i = 0; i < recs.length(); i++) {
            JSONObject x = recs.optJSONObject(i);
            if (x != null && x.optString("rule").equals(id) && "scheduled".equals(x.optString("status"))) { try { x.put("status", "cancelled"); } catch (Exception ignored) { } disarm(x.optString("id")); }
        }
        save();
    }

    /** an episode's identity: its name or number when the guide gives one, else its description */
    static String epKey(String title, String desc, String extra, long start) {
        String[] x = (extra == null ? "" : extra).split("\t", -1);
        String sub = x.length > 1 ? x[1].trim() : "", num = x.length > 2 ? x[2].trim() : "";
        String t = title.trim().toLowerCase(Locale.ROOT);
        if (!num.isEmpty()) return t + "|#" + num.toLowerCase(Locale.ROOT);
        if (!sub.isEmpty()) return t + "|" + sub.toLowerCase(Locale.ROOT);
        if (desc != null && desc.trim().length() > 20) return t + "|d" + desc.trim().toLowerCase(Locale.ROOT).hashCode();
        return t + "|@" + start;
    }

    /** looks through the guide for the series rules' episodes and schedules them */
    void scan() {
        if (guide == null) guide = new LiveTv(app);
        final LiveTv g = guide;
        new Thread(new Runnable() { @Override public void run() {
            try { g.loadEpgNow(); scanNow(g); } catch (Throwable t) { Diag.ev("record: series scan failed " + t); }
        } }, "rec-scan").start();
    }
    private synchronized void scanNow(LiveTv g) throws Exception {
        long now = System.currentTimeMillis();
        int added = 0;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule == null) continue;
            List<long[]> tt = new ArrayList<>(); List<String[]> xx = new ArrayList<>();
            g.progs(rule.optString("k"), now, now + 3 * 86400_000L, tt, xx);
            boolean guideMarksNew = false;
            for (String[] x : xx) if (x.length > 2 && (x[2].startsWith("N") || x[2].startsWith("P"))) guideMarksNew = true;
            JSONArray eps = rule.optJSONArray("eps"); if (eps == null) { eps = new JSONArray(); rule.put("eps", eps); }
            Set<String> seen = new HashSet<>(); for (int j = 0; j < eps.length(); j++) seen.add(eps.optString(j));
            String title = rule.optString("title");
            for (int j = 0; j < tt.size(); j++) {
                long[] se = tt.get(j); String[] x = xx.get(j);
                if (!x[0].trim().equalsIgnoreCase(title)) continue;
                if (se[1] <= now + 60000) continue;
                String extra = x.length > 2 ? x[2] : "";
                if ("new".equals(rule.optString("mode"))) {
                    if (guideMarksNew && !extra.startsWith("N")) continue;            // the guide says it's a repeat
                }
                String key = epKey(x[0], x[1], extra, se[0]);
                if ("new".equals(rule.optString("mode")) && seen.contains(key)) continue;   // that episode's already taken
                if (at(rule.optString("u"), se[0] + 1000) != null) continue;
                JSONObject ch = new JSONObject();
                for (String k : new String[] { "n", "u", "ua", "k", "l" }) ch.put(k, rule.optString(k));
                if (add(ch, se[0], se[1], x[0], x[1], extra, rule.optString("id")) != null) {
                    added++;
                    if (!seen.contains(key)) { eps.put(key); seen.add(key); }
                }
            }
            while (eps.length() > 400) eps.remove(0);
        }
        if (added > 0) { Diag.ev("record: series rules scheduled " + added); save(); }
        // look again in six hours (and whenever the guide's refreshed)
        armScan();
    }

    // ================================================================== alarms

    private PendingIntent pi(String id) {
        Intent i = new Intent(app, RecordReceiver.class).setAction("topshelf.record." + id).putExtra("id", id);
        return PendingIntent.getBroadcast(app, ("rec" + id).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private void arm(JSONObject r) {
        long when = r.optLong("start") - PRE;
        if (when <= System.currentTimeMillis() + 2000) { RecordService.begin(app, r.optString("id")); return; }
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent p = pi(r.optString("id"));
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, p);
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, p);
        } catch (Exception e) { am.set(AlarmManager.RTC_WAKEUP, when, p); }
    }
    private void disarm(String id) {
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pi(id));
    }
    private void armScan() {
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null || rules.length() == 0) return;
        Intent i = new Intent(app, RecordReceiver.class).setAction("topshelf.record.scan");
        PendingIntent p = PendingIntent.getBroadcast(app, 7301, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 6 * 3600_000L, p); } catch (Exception ignored) { }
    }

    /** after a restart (or the TV waking): the alarms again, and anything that should be recording now */
    synchronized void rearm() {
        long now = System.currentTimeMillis();
        boolean dirty = false;
        for (int i = 0; i < recs.length(); i++) {
            JSONObject r = recs.optJSONObject(i);
            if (r == null || !"scheduled".equals(r.optString("status"))) continue;
            if (r.optLong("end") + POST <= now) {
                try { r.put("status", "failed"); r.put("err", "Missed: the TV was off or TopShelf wasn't running"); } catch (Exception ignored) { }
                dirty = true;
                continue;
            }
            arm(r);
        }
        if (dirty) save();
        armScan();
    }

    // ================================================================== recording (RecordService calls these)

    /** where recordings go: a USB drive if one's plugged in, else the TV's own storage */
    File root() {
        try {
            File[] ext = app.getExternalFilesDirs(null);
            if (ext != null) for (int i = 1; i < ext.length; i++) {
                File d = ext[i];
                if (d == null) continue;
                if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(d))) continue;
                File r = new File(d, "Recordings");
                r.mkdirs();
                if (r.canWrite()) return r;
            }
        } catch (Throwable ignored) { }
        File r = new File(app.getFilesDir(), "recordings");
        r.mkdirs();
        return r;
    }
    boolean onUsb(File f) {
        try {
            File[] ext = app.getExternalFilesDirs(null);
            if (ext != null) for (int i = 1; i < ext.length; i++) if (ext[i] != null && f.getAbsolutePath().startsWith(ext[i].getParentFile().getParentFile().getParent())) return true;
        } catch (Throwable ignored) { }
        return false;
    }
    String storageLabel() {
        File r = root();
        return (onUsb(r) ? "USB drive" : "TV storage") + " · " + (r.getUsableSpace() >> 30) + " GB free";
    }

    synchronized boolean begin(String id) {
        JSONObject r = raw(id);
        if (r == null) return false;
        String st = r.optString("status");
        if (!st.equals("scheduled") && !st.equals("recording")) return false;
        if (active.containsKey(id)) return true;
        long now = System.currentTimeMillis();
        if (r.optLong("end") + POST <= now) {
            try { r.put("status", hasData(r) ? "done" : "failed"); if (!hasData(r)) r.put("err", "Missed"); } catch (Exception ignored) { }
            save(); return false;
        }
        File dir;
        if (r.optString("dir").isEmpty()) {
            File root = root();
            if (root.getUsableSpace() < KEEP_FREE + (500L << 20)) {
                try { r.put("status", "failed"); r.put("err", "Not enough space (" + (root.getUsableSpace() >> 20) + " MB free; 3 GB is kept free)"); } catch (Exception ignored) { }
                save(); return false;
            }
            dir = new File(root, id);
            try { r.put("dir", dir.getAbsolutePath()); } catch (Exception ignored) { }
        } else dir = new File(r.optString("dir"));
        String u = r.optString("u");
        boolean hls = u.matches("(?i).*\\.m3u8?(\\?.*)?$") || u.toLowerCase(Locale.ROOT).contains(".m3u8");
        final String fid = id;
        Capture c = new Capture(dir, u, r.optString("ua"), hls, 0, new Capture.Listener() {
            int n;
            @Override public void onCapture(Capture cap, String event) {
                if (event.equals("segment")) {
                    if (++n % 15 == 0) progress(fid, cap);
                    Uploader up = uploader; Capture.Seg s = cap.last();
                    if (up != null && s != null) { JSONObject rr = find(fid); if (rr != null) up.recordingPiece(rr, cap.dir, s); }
                    if (n % 15 == 0 && cap.dir.getUsableSpace() < KEEP_FREE) { put(fid, "err", "Stopped early: storage full (3 GB is kept free)"); put(fid, "stopAsked", true); cap.stop(); }
                } else if (event.equals("stopped")) ended(fid, cap);
            }
        });
        c.append = dir.exists() && new File(dir, "index.m3u8").exists();
        c.stopAt = r.optLong("end") + POST;
        active.put(id, c);
        try { r.put("status", "recording"); if (!r.has("began")) r.put("began", now); } catch (Exception ignored) { }
        save();
        c.start();
        Diag.ev("record: started " + r.optString("title") + " → " + dir);
        return true;
    }

    private synchronized void progress(String id, Capture c) {
        JSONObject r = raw(id);
        if (r == null) return;
        try { r.put("bytes", c.bytes()); r.put("secs", Math.round(c.seconds())); r.put("from", c.startWall()); r.put("to", c.endWall()); } catch (Exception ignored) { }
        save();
    }

    private void ended(final String id, Capture c) {
        boolean retry = false;
        synchronized (this) {
            if (active.get(id) != c) return;
            active.remove(id);
            JSONObject r = raw(id);
            if (r == null) { RecordService.idleCheck(app); return; }
            long now = System.currentTimeMillis();
            String err = c.error();
            try {
                r.put("bytes", c.bytes()); r.put("secs", Math.round(c.seconds()));
                if (c.hasData()) { r.put("from", c.startWall()); r.put("to", c.endWall()); }
                boolean early = now < r.optLong("end") + POST - 20000 && !r.optBoolean("stopAsked");
                int tries = retries.containsKey(id) ? retries.get(id) : 0;
                if (early && tries < 30) {
                    // the channel dropped: carry on (into the same recording) in a few seconds
                    retries.put(id, tries + 1);
                    r.put("status", "scheduled");
                    r.put("drops", r.optInt("drops") + 1);
                    if (!err.isEmpty()) r.put("lastErr", err);
                    retry = true;
                } else {
                    retries.remove(id);
                    boolean has = c.hasData();
                    r.put("status", has ? "done" : "failed");
                    if (!has && !err.isEmpty()) r.put("err", err);
                    else if (!has) r.put("err", "Nothing was received from the channel");
                    else if (early || r.optBoolean("stopAsked")) { if (!r.has("err")) r.put("err", "Stopped early"); }
                    r.remove("stopAsked");
                    if (has) Capture.close(c.dir);
                    Diag.ev("record: finished " + r.optString("title") + " (" + (c.bytes() >> 20) + " MB, " + Math.round(c.seconds() / 60) + " min)");
                    Uploader up = uploader;
                    if (up != null && has) up.recordingDone(copy(r), c.dir);
                }
            } catch (Exception ignored) { }
            save();
        }
        if (retry) main.postDelayed(new Runnable() { @Override public void run() {
            new Thread(new Runnable() { @Override public void run() { if (!begin(id)) RecordService.idleCheck(app); } }).start();
        } }, 5000);
        else RecordService.idleCheck(app);
    }

    static boolean hasData(JSONObject r) {
        String d = r.optString("dir");
        return !d.isEmpty() && new File(d, "index.m3u8").exists();
    }
    /** can it be played here now (the files are on this TV, the USB drive is plugged in) */
    static boolean playable(JSONObject r) { return hasData(r); }

    static void wipe(File f) {
        if (f == null || !f.exists()) return;
        File[] l = f.listFiles();
        if (l != null) for (File x : l) { if (x.isDirectory()) wipe(x); else x.delete(); }
        f.delete();
    }
}
