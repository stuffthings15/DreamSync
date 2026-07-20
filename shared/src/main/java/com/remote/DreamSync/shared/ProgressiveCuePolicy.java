package com.remote.DreamSync.shared;

/** Baseline and disturbance gates for the optional experimental sound path. */
public final class ProgressiveCuePolicy {
    public int allowedPairs(
            ProtectionMode mode,
            int validBaselineNights,
            int consecutiveSafeCueNights
    ) {
        if (mode != ProtectionMode.EXPERIMENTAL_AUDIO
                || validBaselineNights < ProtectionConfig.REQUIRED_BASELINE_NIGHTS) {
            return 0;
        }
        if (consecutiveSafeCueNights >= 4) return 4;
        if (consecutiveSafeCueNights >= 2) return 2;
        return 1;
    }

    public int nextSafeNightStreak(
            int current,
            int cueLinkedArousals,
            int morningDisturbanceRating
    ) {
        if (cueLinkedArousals > 0 || morningDisturbanceRating > 2) return 0;
        return Math.min(30, Math.max(0, current) + 1);
    }

    public double gainAfterCueLinkedArousal(double currentGain) {
        return Math.max(0.005, Math.min(0.10, currentGain) * 0.70);
    }
}
