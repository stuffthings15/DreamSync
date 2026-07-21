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
        String motionState = motionSamples > 0 ? "reading"
                : motionRetryAttempted ? "fallback active" : "waiting";
        return format(elapsedMs, durationMs, motionSamples, motionState,
                Float.NaN, Float.NaN, Float.NaN, Double.NaN,
                heartSamples, heartState, Double.NaN, audioState);
    }

    static String format(
            long elapsedMs,
            long durationMs,
            int motionSamples,
            String motionState,
            float motionX,
            float motionY,
            float motionZ,
            double motionMagnitude,
            int heartSamples,
            String heartState,
            double heartBpm,
            String audioState
    ) {
        long elapsedSeconds = Math.max(0L, elapsedMs / 1_000L);
        long totalSeconds = Math.max(1L, durationMs / 1_000L);
        int percent = (int) Math.min(100L,
                Math.round(elapsedSeconds * 100.0 / totalSeconds));
        String safeMotionState = motionState == null || motionState.isBlank()
                ? "waiting" : motionState;
        String safeHeartState = heartState == null || heartState.isBlank()
                ? "waiting" : heartState.toLowerCase(Locale.US);
        String safeAudioState = audioState == null || audioState.isBlank()
                ? "waiting" : audioState;
        String motionValues = Float.isFinite(motionX) && Float.isFinite(motionY)
                && Float.isFinite(motionZ) && Double.isFinite(motionMagnitude)
                ? String.format(Locale.US, " [x %.2f, y %.2f, z %.2f, |m| %.2f]",
                motionX, motionY, motionZ, motionMagnitude)
                : "";
        String heartValue = Double.isFinite(heartBpm)
                ? String.format(Locale.US, " [%.1f BPM]", heartBpm) : "";
        return "Watch check " + percent + "% (" + elapsedSeconds + "/"
                + totalSeconds + "s). Motion " + motionSamples + " " + safeMotionState
                + motionValues + ", heart " + heartSamples + " " + safeHeartState
                + heartValue
                + ", sound " + safeAudioState + ".";
    }

    private SystemCheckProgress() {}
}
