package com.topshelf.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** A recording's start time (or the six-hourly look through the guide for series episodes). */
public class RecordReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent in) {
        String a = in.getAction() == null ? "" : in.getAction();
        if (a.equals("topshelf.record.scan")) { Recorder.get(c).scan(); return; }
        String id = in.getStringExtra("id");
        if (id != null) RecordService.begin(c, id);
    }
}
