package com.remote.DreamSync;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

final class ChargeReminderScheduler {
    static final int MIN_RECORDS = 3;
    static final int LEAD_MINUTES = 180;
    private static final int REQUEST_CODE = 9031;

    static boolean scheduleFromSessions(Context context, List<SessionEntity> sessions) {
        List<Integer> sleepMinutes = new ArrayList<>();
        for (SessionEntity session : sessions) {
            if (session.startUtcMs <= 0L || session.endUtcMs <= 0L) continue;
            Calendar local = Calendar.getInstance();
            local.setTimeInMillis(session.startUtcMs);
            int minute = local.get(Calendar.HOUR_OF_DAY) * 60 + local.get(Calendar.MINUTE);
            if (minute < 12 * 60) minute += 24 * 60;
            sleepMinutes.add(minute);
            if (sleepMinutes.size() >= 14) break;
        }
        if (sleepMinutes.size() < MIN_RECORDS) return false;
        Collections.sort(sleepMinutes);
        int typicalSleep = sleepMinutes.get(sleepMinutes.size() / 2) % (24 * 60);
        int reminderMinute = (typicalSleep - LEAD_MINUTES + 24 * 60) % (24 * 60);
        context.getSharedPreferences(PhoneAutoControl.PREFS, Context.MODE_PRIVATE).edit()
                .putInt("charge_reminder_minute", reminderMinute)
                .putInt("typical_sleep_minute", typicalSleep)
                .putInt("charge_reminder_record_count", sleepMinutes.size())
                .apply();
        scheduleNext(context, reminderMinute);
        return true;
    }

    static void scheduleStored(Context context) {
        int minute = context.getSharedPreferences(
                        PhoneAutoControl.PREFS, Context.MODE_PRIVATE)
                .getInt("charge_reminder_minute", -1);
        if (minute >= 0) scheduleNext(context, minute);
    }

    private static void scheduleNext(Context context, int minuteOfDay) {
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        next.set(Calendar.MINUTE, minuteOfDay % 60);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= System.currentTimeMillis()) {
            next.add(Calendar.DAY_OF_YEAR, 1);
        }
        PendingIntent reminder = PendingIntent.getBroadcast(
                context, REQUEST_CODE,
                new Intent(context, ChargeReminderReceiver.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms != null) alarms.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), reminder);
    }

    private ChargeReminderScheduler() {}
}
