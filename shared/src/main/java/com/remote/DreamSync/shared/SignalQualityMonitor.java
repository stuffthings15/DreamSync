package com.remote.DreamSync.shared;

public final class SignalQualityMonitor {
    public static final long MOTION_STALE_MS = 5_000L;
    public static final long HEART_STALE_MS = 180_000L;

    public static final class Result {
        public final boolean motionReady;
        public final boolean heartReady;
        public final boolean deepCueAllowed;
        public final boolean lucidCueAllowed;
        public final String reason;

        Result(boolean motionReady, boolean heartReady, String reason) {
            this.motionReady = motionReady;
            this.heartReady = heartReady;
            this.deepCueAllowed = motionReady && heartReady;
            this.lucidCueAllowed = motionReady && heartReady;
            this.reason = reason;
        }
    }

    public Result evaluate(
            long nowElapsedMs,
            long lastMotionElapsedMs,
            long lastHeartElapsedMs,
            int motionSamples30s,
            int heartSamples2m,
            boolean accelerometerRegistered,
            boolean heartSensorRegistered
    ) {
        boolean motionFresh = lastMotionElapsedMs > 0L
                && nowElapsedMs - lastMotionElapsedMs <= MOTION_STALE_MS;
        boolean heartFresh = lastHeartElapsedMs > 0L
                && nowElapsedMs - lastHeartElapsedMs <= HEART_STALE_MS;
        boolean motionReady = accelerometerRegistered && motionFresh && motionSamples30s >= 20;
        boolean heartReady = heartSensorRegistered && heartFresh && heartSamples2m >= 3;
        String reason;
        if (!accelerometerRegistered) reason = "accelerometer unavailable";
        else if (!motionFresh) reason = "motion stream stale";
        else if (motionSamples30s < 20) reason = "motion coverage low";
        else if (!heartSensorRegistered) reason = "heart sensor unavailable";
        else if (!heartFresh) reason = "heart stream stale";
        else if (heartSamples2m < 3) reason = "heart coverage low";
        else reason = "signal ready";
        return new Result(motionReady, heartReady, reason);
    }
}
