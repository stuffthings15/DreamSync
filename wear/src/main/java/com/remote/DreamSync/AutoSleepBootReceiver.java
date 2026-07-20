package com.remote.DreamSync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.remote.DreamSync.shared.BootActionGate;

public final class AutoSleepBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!BootActionGate.accepts(intent.getAction())) return;
        AutoSleepConfig config = AutoSleepConfig.load(context);
        if (!config.validBinding()) return;
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                AutoSleepRegistrationWorker.class).build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                "dreamsync-passive-registration",
                ExistingWorkPolicy.REPLACE,
                request);
    }
}
