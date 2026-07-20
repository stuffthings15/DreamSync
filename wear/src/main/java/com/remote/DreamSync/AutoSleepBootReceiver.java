package com.remote.DreamSync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.remote.DreamSync.shared.BootActionGate;

public final class AutoSleepBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!BootActionGate.accepts(intent.getAction())) return;
        AutoSleepConfig config = AutoSleepConfig.load(context);
        if (config.validBinding()) AutoSleepMonitor.enable(context, config);
    }
}
