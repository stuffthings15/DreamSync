package com.remote.DreamSync.shared;

/** Deterministic, research-conservative protection configuration. */
public final class ProtectionConfig {
    public static final long POST_CUE_OBSERVATION_MS = 5L * 60_000L;
    public static final long AROUSAL_QUIET_MS = 20L * 60_000L;

    public final ProtectionMode mode;
    public final double awakeAudibleThreshold;
    public final int consecutiveSafeCueNights;

    public ProtectionConfig(
            ProtectionMode mode,
            double awakeAudibleThreshold,
            int consecutiveSafeCueNights
    ) {
        this.mode = mode == null ? ProtectionMode.PROTECT_ONLY : mode;
        this.awakeAudibleThreshold = Math.max(0.005,
                Math.min(0.10, awakeAudibleThreshold));
        this.consecutiveSafeCueNights = Math.max(0,
                Math.min(30, consecutiveSafeCueNights));
    }

    public double initialOvernightGain() {
        return Math.min(0.04, awakeAudibleThreshold * 0.50);
    }
}
