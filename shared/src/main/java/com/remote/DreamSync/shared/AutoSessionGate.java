package com.remote.DreamSync.shared;

/** Pure guard for starting one automatic session from a passive asleep signal. */
public final class AutoSessionGate {
    public static final long MIN_START_GAP_MS = 4L * 60L * 60L * 1000L;

    public boolean mayStart(
            boolean enabled,
            boolean asleep,
            boolean sessionRunning,
            long lastStartUtcMs,
            long nowUtcMs
    ) {
        if (!enabled || !asleep || sessionRunning || nowUtcMs <= 0L) return false;
        return lastStartUtcMs <= 0L || nowUtcMs - lastStartUtcMs >= MIN_START_GAP_MS;
    }
}
