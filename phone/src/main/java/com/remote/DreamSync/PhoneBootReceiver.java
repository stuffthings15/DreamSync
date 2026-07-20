package com.remote.DreamSync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class PhoneBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (context.getSharedPreferences(PhoneAutoControl.PREFS, Context.MODE_PRIVATE)
                .getBoolean("auto_enabled", false)) {
            PhoneAutoControl.startStandby(context);
        }
        ChargeReminderScheduler.scheduleStored(context);
    }
}
