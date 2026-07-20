package com.remote.DreamSync.shared;

/** Minimal platform-neutral sleep-continuity result. */
public final class NightSummary {
    public final String sessionId;
    public final long estimatedSleepOnsetUtcMs;
    public final int awakeningCount;
    public final long wakeAfterSleepOnsetMs;
    public final long longestUninterruptedBoutMs;
    public final int cuePairs;
    public final int cueLinkedArousals;

    public NightSummary(
            String sessionId,
            long estimatedSleepOnsetUtcMs,
            int awakeningCount,
            long wakeAfterSleepOnsetMs,
            long longestUninterruptedBoutMs,
            int cuePairs,
            int cueLinkedArousals
    ) {
        this.sessionId = sessionId == null ? "" : sessionId;
        this.estimatedSleepOnsetUtcMs = Math.max(0L, estimatedSleepOnsetUtcMs);
        this.awakeningCount = Math.max(0, awakeningCount);
        this.wakeAfterSleepOnsetMs = Math.max(0L, wakeAfterSleepOnsetMs);
        this.longestUninterruptedBoutMs = Math.max(0L, longestUninterruptedBoutMs);
        this.cuePairs = Math.max(0, cuePairs);
        this.cueLinkedArousals = Math.max(0, cueLinkedArousals);
    }
}
