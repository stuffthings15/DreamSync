package com.remote.DreamSync.shared;

/** Tracks estimated awakenings and continuity without claiming EEG sleep staging. */
public final class SleepContinuityTracker {
    public static final long DEFAULT_AWAKENING_MIN_MS = 60_000L;

    public static final class Snapshot {
        public final int awakeningCount;
        public final long wakeAfterSleepOnsetMs;
        public final long longestUninterruptedBoutMs;
        public final int cuesStarted;
        public final int cueLinkedArousals;

        Snapshot(
                int awakeningCount,
                long wakeAfterSleepOnsetMs,
                long longestUninterruptedBoutMs,
                int cuesStarted,
                int cueLinkedArousals
        ) {
            this.awakeningCount = awakeningCount;
            this.wakeAfterSleepOnsetMs = wakeAfterSleepOnsetMs;
            this.longestUninterruptedBoutMs = longestUninterruptedBoutMs;
            this.cuesStarted = cuesStarted;
            this.cueLinkedArousals = cueLinkedArousals;
        }

        public double cueLinkedArousalRate() {
            return cuesStarted == 0 ? 0.0 : cueLinkedArousals / (double) cuesStarted;
        }
    }

    private final long awakeningMinMs;
    private long onsetMs;
    private long sleepBoutStartMs;
    private long possibleWakeStartMs;
    private long wakeAfterSleepOnsetMs;
    private long longestBoutMs;
    private int awakeningCount;
    private int cuesStarted;
    private int cueLinkedArousals;
    private boolean awakeningCounted;

    public SleepContinuityTracker() {
        this(DEFAULT_AWAKENING_MIN_MS);
    }

    public SleepContinuityTracker(long awakeningMinMs) {
        this.awakeningMinMs = Math.max(1L, awakeningMinMs);
    }

    public void onSleepOnset(long onsetElapsedMs) {
        if (onsetMs != 0L || onsetElapsedMs <= 0L) return;
        onsetMs = onsetElapsedMs;
        sleepBoutStartMs = onsetElapsedMs;
    }

    public void update(long nowElapsedMs, boolean possibleArousal) {
        if (onsetMs == 0L || nowElapsedMs < onsetMs) return;
        if (possibleArousal) {
            if (possibleWakeStartMs == 0L) possibleWakeStartMs = nowElapsedMs;
            if (!awakeningCounted
                    && nowElapsedMs - possibleWakeStartMs >= awakeningMinMs) {
                awakeningCount++;
                awakeningCounted = true;
                longestBoutMs = Math.max(longestBoutMs,
                        Math.max(0L, possibleWakeStartMs - sleepBoutStartMs));
            }
            return;
        }
        if (possibleWakeStartMs > 0L) {
            if (awakeningCounted) {
                wakeAfterSleepOnsetMs += Math.max(0L, nowElapsedMs - possibleWakeStartMs);
                sleepBoutStartMs = nowElapsedMs;
            }
            possibleWakeStartMs = 0L;
            awakeningCounted = false;
        }
    }

    public void onCueStarted() {
        cuesStarted++;
    }

    public void onCueLinkedArousal() {
        cueLinkedArousals++;
    }

    public Snapshot snapshot(long nowElapsedMs) {
        long waso = wakeAfterSleepOnsetMs;
        long longest = longestBoutMs;
        if (onsetMs > 0L) {
            if (possibleWakeStartMs > 0L && awakeningCounted) {
                waso += Math.max(0L, nowElapsedMs - possibleWakeStartMs);
            } else {
                longest = Math.max(longest, Math.max(0L, nowElapsedMs - sleepBoutStartMs));
            }
        }
        return new Snapshot(awakeningCount, waso, longest,
                cuesStarted, cueLinkedArousals);
    }
}
