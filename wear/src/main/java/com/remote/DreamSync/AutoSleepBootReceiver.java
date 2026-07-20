package com.remote.DreamSync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class AutoSleepBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        AutoSleepConfig config = AutoSleepConfig.load(context);
        if (config.validBinding()) AutoSleepMonitor.enable(context, config);
    }
}
