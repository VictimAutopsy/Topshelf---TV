package com.topshelf.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityEvent;

/**
 * Google TV has no "default home app" setting, so this watches for Google's home screen
 * coming to the front (Home button, boot, backing out of an app) and swaps TopShelf in.
 * It only listens to the stock launcher packages (see res/xml/home_redirect_service.xml)
 * and never reads screen content.
 */
public class HomeRedirectService extends AccessibilityService {

    private long lastRedirect;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName();
        if (pkg == null) return;
        String p = pkg.toString();
        if (!p.equals(Bridge.STOCK_LAUNCHER)
                && !p.equals("com.google.android.tvlauncher")
                && !p.equals("com.google.android.leanbacklauncher")) return;

        // (Google TV's other screens in its home app — the ★ button's set-up and the like — are let be)
        String cls = e.getClassName() == null ? "" : e.getClassName().toString();
        if (!MainActivity.inFront) Diag.ev("home watch: " + p + " shows " + (cls.isEmpty() ? "(no name)" : cls));
        if (HomeWatchService.notHomeScreen(cls)) return;
        /* TopShelf is on screen already: Google's home only changed a window behind it (it does, now and then,
           in the background). Taking that for the Home button stopped Live TV and went back to Home mid-show. */
        if (MainActivity.inFront) return;
        SharedPreferences prefs = getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("redirect", true)) return;
        long now = System.currentTimeMillis();
        if (now < prefs.getLong("bypassUntil", 0)) return; // user asked for Google's home on purpose
        if (now - lastRedirect < 700) return;
        lastRedirect = now;

        Intent i = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .putExtra("redirect", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            startActivity(i);
        } catch (Exception ignored) { }
    }

    /* The Home button itself: answered here, before Android acts on it, so Google's home screen never
       shows (it used to come up for a moment before TopShelf was swapped in). A long press still opens
       Google TV's side panel. Every other key goes on untouched. */
    private long homeDownAt;
    @Override
    protected boolean onKeyEvent(android.view.KeyEvent e) {
        // (which code the remote's other buttons send — the ★ button's, to hook it up — in the report)
        int kc = e.getKeyCode();
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && (kc < 19 || kc > 23) && kc != 4 && kc != 3 && kc != 66 && kc != 24 && kc != 25)
            Diag.ev("remote button: " + android.view.KeyEvent.keyCodeToString(kc) + " (" + kc + ")");
        /* The remote's ★ button asks Claude (Settings › System › Assistant › ★ button), from anywhere: Google TV's own
           ★ set-up only lists ordinary apps, so it's taken here instead. */
        if (starKey(kc)) {
            SharedPreferences sp = getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE);
            if (sp.getBoolean("aiStar", true) && !Assistant.key(this).isEmpty()) {
                if (e.getAction() == android.view.KeyEvent.ACTION_UP) {
                    Intent ask = new Intent(Intent.ACTION_MAIN).setClassName(getPackageName(), getPackageName() + ".AskClaude")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    try { startActivity(ask); } catch (Exception ex) { Diag.ev("remote button: couldn't open Ask Claude (" + ex.getMessage() + ")"); }
                }
                return true;
            }
        }
        if (e.getKeyCode() != android.view.KeyEvent.KEYCODE_HOME) return false;
        SharedPreferences prefs = getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("redirect", true) || System.currentTimeMillis() < prefs.getLong("bypassUntil", 0)) return false;
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) homeDownAt = e.getEventTime();
            return true;
        }
        if (e.getAction() == android.view.KeyEvent.ACTION_UP) {
            if (e.getEventTime() - homeDownAt >= 650) { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); return true; }
            lastRedirect = System.currentTimeMillis();
            Intent i = new Intent(this, MainActivity.class)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .putExtra("homeKey", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            try { startActivity(i); } catch (Exception ex) { return false; }
            return true;
        }
        return true;
    }

    /** the codes a remote's ★ / shortcut button sends (Google TV Streamer and others) */
    static boolean starKey(int kc) {
        return kc == android.view.KeyEvent.KEYCODE_BOOKMARK || (kc >= 297 && kc <= 304) || (kc >= 313 && kc <= 316);
    }

    @Override
    public void onInterrupt() { }
}
