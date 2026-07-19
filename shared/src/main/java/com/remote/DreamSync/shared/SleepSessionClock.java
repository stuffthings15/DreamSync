package com.remote.DreamSync.shared;

public final class SleepSessionClock {
    private static final long MINUTE_MS = 60_000L;
    private static final long MAX_ARM_WINDOW_MS = 24L * 60L * MINUTE_MS;
    private static final long CLOCK_SKEW_MS = 5L * MINUTE_MS;

    public static double sleepElapsedMinutes(long onsetElapsedMs, long nowElapsedMs) {
        if (onsetElapsedMs <= 0L || nowElapsedMs < onsetElapsedMs) return -1.0;
        return (nowElapsedMs - onsetElapsedMs) / (double) MINUTE_MS;
    }

    public static boolean armTimedOut(
            long armedElapsedMs,
            long onsetElapsedMs,
            long nowElapsedMs,
            int armTimeoutMin
    ) {
        return onsetElapsedMs <= 0L
                && armedElapsedMs > 0L
                && nowElapsedMs >= armedElapsedMs
                && nowElapsedMs - armedElapsedMs >= armTimeoutMin * MINUTE_MS;
    }

    public static boolean sleepTimedOut(
            long onsetElapsedMs,
            long nowElapsedMs,
            int sessionEndMin
    ) {
        return onsetElapsedMs > 0L
                && nowElapsedMs >= onsetElapsedMs
                && nowElapsedMs - onsetElapsedMs >= sessionEndMin * MINUTE_MS;
    }

    public static boolean isCurrentArmWindow(
            long createdUtcMs,
            long expiresUtcMs,
            long nowUtcMs
    ) {
        if (createdUtcMs <= 0L || expiresUtcMs <= createdUtcMs) return false;
        if (expiresUtcMs - createdUtcMs > MAX_ARM_WINDOW_MS) return false;
        return nowUtcMs + CLOCK_SKEW_MS >= createdUtcMs && nowUtcMs < expiresUtcMs;
    }

    private SleepSessionClock() {}
}
