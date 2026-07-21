package com.remote.DreamSync.shared;

/** Night-one and disturbance gates for the optional experimental sound path. */
public final class ProgressiveCuePolicy {
    public int allowedPairs(
            ProtectionMode mode,
            int consecutiveSafeCueNights
    ) {
        if (mode != ProtectionMode.EXPERIMENTAL_AUDIO) return 0;
        if (consecutiveSafeCueNights >= 4) return 4;
        if (consecutiveSafeCueNights >= 2) return 2;
        return ResearchMissionContract.FIRST_ELIGIBLE_NIGHT_PAIRS;
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
        return Math.max(0.005,
                Math.min(ResearchMissionContract.MAX_AWAKE_TEST_GAIN,
                        currentGain) * 0.70);
    }
}
