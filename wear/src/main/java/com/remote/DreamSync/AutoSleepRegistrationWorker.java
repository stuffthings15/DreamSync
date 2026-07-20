package com.remote.DreamSync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Restores Health Services passive monitoring outside the boot receiver time limit. */
public final class AutoSleepRegistrationWorker extends Worker {
    public AutoSleepRegistrationWorker(
            @NonNull Context context,
            @NonNull WorkerParameters parameters
    ) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        AutoSleepConfig config = AutoSleepConfig.load(getApplicationContext());
        if (!config.validBinding()) return Result.success();
        return AutoSleepMonitor.restoreAfterBoot(getApplicationContext(), config)
                ? Result.success() : Result.retry();
    }
}
