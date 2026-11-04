package com.topshelf.launcher;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Live TV reminders. An alarm goes off a couple of minutes before a programme starts and posts a
 * notification; tapping it opens TopShelf on that channel. While TopShelf is on screen the page
 * shows its own pop-up instead, so no notification is posted then.
 */
public class ReminderReceiver extends BroadcastReceiver {

    static final String EXTRA_CHANNEL = "com.topshelf.launcher.CHANNEL";
    private static final String CHANNEL_ID = "reminders";
    private static final long EARLY = 2 * 60 * 1000L;

    static void schedule(Context ctx, String id, long atMs, String title, String text, String channelUrl) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long when = Math.max(System.currentTimeMillis() + 5000, atMs - EARLY);
        PendingIntent pi = alarmIntent(ctx, id, title, text, channelUrl);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        } catch (Exception e) {
            am.set(AlarmManager.RTC_WAKEUP, when, pi);
        }
    }

    static void cancel(Context ctx, String id) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(alarmIntent(ctx, id, "", "", ""));
    }

    private static PendingIntent alarmIntent(Context ctx, String id, String title, String text, String channelUrl) {
        Intent i = new Intent(ctx, ReminderReceiver.class).setAction("topshelf.reminder." + id)
                .putExtra("id", id).putExtra("title", title).putExtra("text", text).putExtra(EXTRA_CHANNEL, channelUrl);
        return PendingIntent.getBroadcast(ctx, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context ctx, Intent in) {
        if (MainActivity.inFront) return;                    // the page shows its own pop-up
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Live TV reminders", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("When a programme you asked about is about to start");
            nm.createNotificationChannel(ch);
        }
        String id = in.getStringExtra("id");
        Intent open = new Intent(ctx, MainActivity.class).putExtra(EXTRA_CHANNEL, in.getStringExtra(EXTRA_CHANNEL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, id == null ? 0 : id.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, CHANNEL_ID) : new Notification.Builder(ctx);
        int icon = ctx.getResources().getIdentifier("ic_notif", "drawable", ctx.getPackageName());
        b.setSmallIcon(icon != 0 ? icon : android.R.drawable.ic_popup_reminder)
         .setContentTitle(in.getStringExtra("title"))
         .setContentText(in.getStringExtra("text"))
         .setContentIntent(pi)
         .setAutoCancel(true)
         .setCategory(Notification.CATEGORY_REMINDER);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH);
        try { nm.notify(id == null ? 1 : id.hashCode(), b.build()); } catch (SecurityException ignored) { }
    }
}
