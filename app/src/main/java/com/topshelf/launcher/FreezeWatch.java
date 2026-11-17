package com.topshelf.launcher;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Notes what TopShelf's main thread is stuck on when it stops answering for a few seconds (the TV gives up on it
 * after about five, and the trace Android keeps can be empty or cut short). A helper thread asks the main thread to
 * answer every second; if it hasn't after 3 s, the main thread's stack is written down once (prefs "lastFreeze"),
 * and the crash report sends it with the next start if Android did call it a freeze. Only code positions: nothing
 * watched, no links or keys.
 */
final class FreezeWatch extends Thread {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences pr;
    private volatile long answered = SystemClock.uptimeMillis();
    private final Runnable pong = new Runnable() { @Override public void run() { answered = SystemClock.uptimeMillis(); } };

    private FreezeWatch(SharedPreferences prefs) { super("freeze-watch"); pr = prefs; setDaemon(true); setPriority(MIN_PRIORITY); }

    private static FreezeWatch one;
    static synchronized void start(SharedPreferences prefs) { if (one == null) { one = new FreezeWatch(prefs); one.start(); } }

    @Override public void run() {
        boolean noted = false;
        while (true) {
            long asked = SystemClock.uptimeMillis();
            main.post(pong);
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            long stuck = SystemClock.uptimeMillis() - answered;
            if (stuck < 2500) { noted = false; continue; }
            if (stuck >= 3000 && !noted && answered < asked) {
                noted = true;
                try {
                    StringBuilder b = new StringBuilder("main thread busy for " + stuck / 1000 + " s:\n");
                    int i = 0;
                    for (StackTraceElement e : Looper.getMainLooper().getThread().getStackTrace()) { if (i++ >= 40) break; b.append("  at ").append(e).append('\n'); }
                    pr.edit().putString("lastFreeze", b.toString()).putLong("lastFreezeAt", System.currentTimeMillis()).apply();
                    StackTraceElement[] st = Looper.getMainLooper().getThread().getStackTrace();
                    String top = "";
                    for (StackTraceElement e : st) if (e.getClassName().startsWith("com.topshelf")) { top = e.getClassName().replace("com.topshelf.launcher.", "") + "." + e.getMethodName() + ":" + e.getLineNumber(); break; }
                    Diag.ev("freeze: the app was stuck " + stuck / 1000 + " s" + (top.isEmpty() ? "" : " in " + top));
                } catch (Throwable ignored) { }
            }
        }
    }
}
