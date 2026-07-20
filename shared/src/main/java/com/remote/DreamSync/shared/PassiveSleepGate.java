package com.remote.DreamSync.shared;

/** Conservative coarse gate for watches without a passive asleep-state signal. */
public final class PassiveSleepGate {
    public static final double MAX_SLEEP_CANDIDATE_BPM = 75.0;
    public static final long REQUIRED_SPAN_MS = 10L * 60_000L;
    private static final long MAX_SEQUENCE_GAP_MS = 90L * 60_000L;

    public static final class Result {
        public final long firstLowHeartRateElapsedMs;
        public final int lowHeartRateSamples;
        public final boolean candidateReady;

        Result(long firstLowHeartRateElapsedMs, int lowHeartRateSamples,
               boolean candidateReady) {
            this.firstLowHeartRateElapsedMs = firstLowHeartRateElapsedMs;
            this.lowHeartRateSamples = lowHeartRateSamples;
            this.candidateReady = candidateReady;
        }
    }

    public boolean inSleepWindow(int minuteOfDay, int typicalSleepMinute) {
        int minute = normalize(minuteOfDay);
        int start = typicalSleepMinute < 0
                ? 20 * 60 : normalize(typicalSleepMinute - 180);
        int end = typicalSleepMinute < 0
                ? 5 * 60 : normalize(typicalSleepMinute + 300);
        return start <= end
                ? minute >= start && minute <= end
                : minute >= start || minute <= end;
    }

    public Result observe(
            boolean inSleepWindow,
            double bpm,
            long sampleElapsedMs,
            long previousFirstElapsedMs,
            int previousSamples
    ) {
        if (!inSleepWindow || !Double.isFinite(bpm)
                || bpm < 30.0 || bpm > MAX_SLEEP_CANDIDATE_BPM
                || sampleElapsedMs <= 0L) {
            return new Result(0L, 0, false);
        }
        long first = previousFirstElapsedMs;
        int count = previousSamples;
        if (first <= 0L || sampleElapsedMs < first
                || sampleElapsedMs - first > MAX_SEQUENCE_GAP_MS) {
            first = sampleElapsedMs;
            count = 1;
        } else {
            count++;
        }
        boolean ready = count >= 3 && sampleElapsedMs - first >= REQUIRED_SPAN_MS;
        return new Result(first, count, ready);
    }

    private static int normalize(int minute) {
        int value = minute % (24 * 60);
        return value < 0 ? value + 24 * 60 : value;
    }
}
