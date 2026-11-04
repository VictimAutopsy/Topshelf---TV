package com.topshelf.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After the TV starts (or TopShelf is updated), switch the Home button watcher back on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        HomeWatchService.startIfWanted(c);
        if (c.getPackageManager().hasSystemFeature("android.software.leanback")) ReadyService.startIfWanted(c);
        try { Recorder.get(c).rearm(); Recorder.get(c).scan(); } catch (Throwable ignored) { }
        // an update TopShelf installed itself: open it again (allowed when TopShelf may draw over other apps)
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())
                && c.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE).getLong("updStartedAt", 0) > System.currentTimeMillis() - 15 * 60000) {
            c.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE).edit().remove("updStartedAt").apply();
            try {
                Intent i = new Intent(c, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                c.startActivity(i);
            } catch (Exception ignored) { }
        }
    }
}
