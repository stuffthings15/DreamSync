package com.remote.DreamSync.shared;

/** Versionable replay record for every automatic protection decision. */
public final class DecisionSnapshot {
    public final long elapsedRealtimeMs;
    public final NightState state;
    public final boolean sensorsFresh;
    public final boolean offWrist;
    public final boolean cueAllowed;
    public final String reason;

    public DecisionSnapshot(
            long elapsedRealtimeMs,
            NightState state,
            boolean sensorsFresh,
            boolean offWrist,
            boolean cueAllowed,
            String reason
    ) {
        this.elapsedRealtimeMs = Math.max(0L, elapsedRealtimeMs);
        this.state = state == null ? NightState.OFF : state;
        this.sensorsFresh = sensorsFresh;
        this.offWrist = offWrist;
        this.cueAllowed = cueAllowed && sensorsFresh && !offWrist;
        this.reason = reason == null ? "" : reason;
    }
}
