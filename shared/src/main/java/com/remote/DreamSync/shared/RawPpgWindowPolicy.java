package com.remote.DreamSync.shared;

/** Keeps high-power raw PPG limited to short pre/post cue quality windows. */
public final class RawPpgWindowPolicy {
    public static final long PRE_CUE_MS = 30_000L;
    public static final long POST_CUE_MS = 5L * 60_000L;

    public long openPreCue(long nowElapsedMs) {
        return Math.max(0L, nowElapsedMs) + PRE_CUE_MS;
    }

    public long extendAfterAudioStart(long currentEndMs, long audioStartedElapsedMs) {
        return Math.max(currentEndMs,
                Math.max(0L, audioStartedElapsedMs) + POST_CUE_MS);
    }

    public boolean active(long nowElapsedMs, long windowEndElapsedMs) {
        return windowEndElapsedMs > 0L && nowElapsedMs < windowEndElapsedMs;
    }
}
