package com.remote.DreamSync.shared;

/** Deterministic, monotonic-time sleep-continuity state machine. */
public final class SleepPhaseMachine {
    public enum Phase {
        ARMED,
        PREPARING_FOR_SLEEP,
        LIKELY_ASLEEP,
        STABLE_SLEEP,
        POSSIBLE_AROUSAL,
        RECOVERED,
        STOPPED
    }

    private final long stableRequiredMs;
    private final long recoveryRequiredMs;
    private Phase phase = Phase.ARMED;
    private long stableSinceMs;
    private long recoveredSinceMs;

    public SleepPhaseMachine(long stableRequiredMs, long recoveryRequiredMs) {
        this.stableRequiredMs = Math.max(1L, stableRequiredMs);
        this.recoveryRequiredMs = Math.max(1L, recoveryRequiredMs);
    }

    public Phase update(
            long nowElapsedMs,
            boolean onsetEstimated,
            boolean stableCandidate,
            boolean arousal,
            boolean signalsReady
    ) {
        if (phase == Phase.STOPPED) return phase;
        if (!onsetEstimated || !signalsReady) {
            stableSinceMs = 0L;
            recoveredSinceMs = 0L;
            phase = Phase.PREPARING_FOR_SLEEP;
            return phase;
        }
        if (arousal) {
            stableSinceMs = 0L;
            recoveredSinceMs = 0L;
            phase = Phase.POSSIBLE_AROUSAL;
            return phase;
        }
        if (phase == Phase.POSSIBLE_AROUSAL) {
            recoveredSinceMs = nowElapsedMs;
            phase = Phase.RECOVERED;
            return phase;
        }
        if (phase == Phase.RECOVERED) {
            if (!stableCandidate) {
                recoveredSinceMs = 0L;
                phase = Phase.LIKELY_ASLEEP;
            } else if (nowElapsedMs - recoveredSinceMs >= recoveryRequiredMs) {
                stableSinceMs = recoveredSinceMs;
                phase = Phase.STABLE_SLEEP;
            }
            return phase;
        }
        if (!stableCandidate) {
            stableSinceMs = 0L;
            phase = Phase.LIKELY_ASLEEP;
            return phase;
        }
        if (stableSinceMs == 0L) stableSinceMs = nowElapsedMs;
        phase = nowElapsedMs - stableSinceMs >= stableRequiredMs
                ? Phase.STABLE_SLEEP : Phase.LIKELY_ASLEEP;
        return phase;
    }

    public Phase stop() {
        phase = Phase.STOPPED;
        return phase;
    }

    public Phase current() {
        return phase;
    }
}
