package com.remote.DreamSync.shared;

public final class SleepOnsetEstimator {
    private long quietSinceElapsedMs;
    private long estimatedOnsetElapsedMs;

    public long update(boolean quietAndValid, long nowElapsedMs, long requiredQuietMs) {
        if (estimatedOnsetElapsedMs > 0L) return estimatedOnsetElapsedMs;
        if (!quietAndValid) {
            quietSinceElapsedMs = 0L;
            return 0L;
        }
        if (quietSinceElapsedMs == 0L) quietSinceElapsedMs = nowElapsedMs;
        if (nowElapsedMs - quietSinceElapsedMs >= requiredQuietMs) {
            estimatedOnsetElapsedMs = quietSinceElapsedMs;
        }
        return estimatedOnsetElapsedMs;
    }

    public long getEstimatedOnsetElapsedMs() {
        return estimatedOnsetElapsedMs;
    }
}
