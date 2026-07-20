package com.remote.DreamSync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ChargeReminderReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "dreamsync_charge";

    @Override public void onReceive(Context context, Intent intent) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Watch charge reminders", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Reminds you to charge the watch before your usual sleep time.");
            manager.createNotificationChannel(channel);
            PendingIntent open = PendingIntent.getActivity(
                    context, 0, new Intent(context, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification = new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                    .setContentTitle("Charge your watch for DreamSync")
                    .setContentText("Your usual sleep time is a few hours away. Charge now, then wear it snugly.")
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build();
            manager.notify(9032, notification);
        }
        ChargeReminderScheduler.scheduleStored(context);
    }
}
