package com.remote.DreamSync;

import java.util.Locale;

/** Small, deterministic formatter for the live watch-check display. */
final class SystemCheckProgress {
    static String format(
            long elapsedMs,
            long durationMs,
            int motionSamples,
            boolean motionRetryAttempted,
            int heartSamples,
            String heartState,
            String audioState
    ) {
        long elapsedSeconds = Math.max(0L, elapsedMs / 1_000L);
        long totalSeconds = Math.max(1L, durationMs / 1_000L);
        int percent = (int) Math.min(100L,
                Math.round(elapsedSeconds * 100.0 / totalSeconds));
        String motionState = motionSamples > 0 ? "reading"
                : motionRetryAttempted ? "fallback active" : "waiting";
        String safeHeartState = heartState == null || heartState.isBlank()
                ? "waiting" : heartState.toLowerCase(Locale.US);
        String safeAudioState = audioState == null || audioState.isBlank()
                ? "waiting" : audioState;
        return "Watch check " + percent + "% (" + elapsedSeconds + "/"
                + totalSeconds + "s). Motion " + motionSamples + " " + motionState
                + ", heart " + heartSamples + " " + safeHeartState
                + ", sound " + safeAudioState + ".";
    }

    private SystemCheckProgress() {}
}
