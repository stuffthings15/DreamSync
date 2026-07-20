package com.remote.DreamSync;

import android.os.SystemClock;

import com.remote.DreamSync.shared.TimeProvider;

final class AndroidTimeProvider implements TimeProvider {
    @Override public long elapsedRealtimeMs() {
        return SystemClock.elapsedRealtime();
    }

    @Override public long utcMs() {
        return System.currentTimeMillis();
    }
}
