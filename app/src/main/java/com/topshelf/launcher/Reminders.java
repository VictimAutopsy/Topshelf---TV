package com.topshelf.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Programme reminders, kept by the app (they were the page's): the list is the same saved list the
 * page used ("reminders"), each one has the same id, and the alarm is set the same way. The page
 * still pops up a reminder that's about to start while TopShelf is open, so it's told to read the
 * list again after every change here.
 */
final class Reminders {
    private Reminders() { }

    static JSONArray list(NativeHome h) {
        try { String s = h.prefs.getString("ui.reminders", null); return s == null ? new JSONArray() : new JSONArray(s); } catch (Exception e) { return new JSONArray(); }
    }
    private static void save(NativeHome h, JSONArray a) {
        h.prefs.edit().putString("ui.reminders", a.toString()).apply();
        h.page("window.REMS&&(REMS.list=readJSON('reminders',[]));typeof remRedraw==='function'&&remRedraw()");
    }

    /** the same id the page gave a reminder: a hash of the channel's address and the start time */
    static String id(String u, long start) {
        String s = u + "|" + start;
        int x = 0;
        for (int i = 0; i < s.length(); i++) x = x * 31 + s.charAt(i);
        return "r" + Long.toString(x & 0xffffffffL, 36);
    }

    static boolean has(NativeHome h, String u, long at) {
        JSONArray a = list(h);
        for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null && o.optString("u").equals(u) && o.optLong("at") == at) return true; }
        return false;
    }

    static String time(NativeHome h, long ms) {
        return new SimpleDateFormat("24".equals(h.S.optString("clock", "12")) ? "HH:mm" : "h:mm a", Locale.US).format(new Date(ms));
    }

    /** remind me: ch is {u, n, num} */
    static void add(NativeHome h, String u, String n, String num, long at, long end, String title) {
        if (title == null || title.isEmpty()) title = "Untitled";
        if (has(h, u, at)) return;
        String rid = id(u, at);
        List<JSONObject> l = new ArrayList<>();
        JSONArray a = list(h);
        for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null) l.add(o); }
        try { l.add(new JSONObject().put("id", rid).put("u", u).put("n", n).put("num", num).put("at", at).put("end", end).put("title", title)); } catch (Exception ignored) { }
        Collections.sort(l, new Comparator<JSONObject>() { @Override public int compare(JSONObject x, JSONObject y) { return Long.compare(x.optLong("at"), y.optLong("at")); } });
        JSONArray out = new JSONArray();
        for (JSONObject o : l) out.put(o);
        save(h, out);
        try { h.act.askNotifications(); } catch (Throwable ignored) { }
        try { ReminderReceiver.schedule(h.act, rid, at, "Starting soon: " + title, num + " · " + n + " at " + time(h, at), u); } catch (Throwable ignored) { }
        h.toast("We'll remind you when “" + title + "” starts (" + time(h, at) + ")");
    }

    static void remove(NativeHome h, String rid, boolean quiet) {
        JSONArray a = list(h), out = new JSONArray();
        String title = null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            if (o.optString("id").equals(rid)) { title = o.optString("title"); continue; }
            out.put(o);
        }
        save(h, out);
        try { ReminderReceiver.cancel(h.act, rid); } catch (Throwable ignored) { }
        if (title != null && !quiet) h.toast("Reminder for “" + title + "” canceled");
    }
}
