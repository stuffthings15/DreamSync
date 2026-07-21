package com.remote.DreamSync.shared;

/** Deterministic, research-conservative protection configuration. */
public final class ProtectionConfig {
    public static final long POST_CUE_OBSERVATION_MS =
            ResearchMissionContract.POST_CUE_OBSERVATION_MS;
    public static final long AROUSAL_QUIET_MS =
            ResearchMissionContract.AROUSAL_QUIET_MS;

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
                Math.min(ResearchMissionContract.MAX_AWAKE_TEST_GAIN,
                        awakeAudibleThreshold));
        this.consecutiveSafeCueNights = Math.max(0,
                Math.min(30, consecutiveSafeCueNights));
    }

    public double initialOvernightGain() {
        return Math.min(ResearchMissionContract.MAX_INITIAL_OVERNIGHT_GAIN,
                awakeAudibleThreshold * 0.50);
    }
}
