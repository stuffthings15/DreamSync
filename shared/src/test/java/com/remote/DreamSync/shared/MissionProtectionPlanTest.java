package com.remote.DreamSync.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MissionProtectionPlanTest {
    @Test public void historicalModesMigrateToProtectionOnly() {
        assertEquals(ProtectionMode.PROTECT_ONLY, ProtectionMode.fromStored("DEEP"));
        assertEquals(ProtectionMode.PROTECT_ONLY, ProtectionMode.fromStored("LUCID"));
        assertEquals(ProtectionMode.PROTECT_ONLY, ProtectionMode.fromStored("COMBINED"));
        assertEquals(ProtectionMode.EXPERIMENTAL_AUDIO,
                ProtectionMode.fromStored("EXPERIMENTAL_AUDIO"));
        assertEquals(ProtectionMode.EXPERIMENTAL_AUDIO,
                ProtectionMode.fromHistoricalRecord("DEEP"));
        assertEquals(ProtectionMode.PROTECT_ONLY,
                ProtectionMode.fromHistoricalRecord("LUCID"));
    }

    @Test public void experimentalSoundStartsOnNightOneAndProgressesSlowly() {
        ProgressiveCuePolicy policy = new ProgressiveCuePolicy();
        assertEquals(0, policy.allowedPairs(ProtectionMode.PROTECT_ONLY, 20));
        assertEquals(1, policy.allowedPairs(ProtectionMode.EXPERIMENTAL_AUDIO, 0));
        assertEquals(2, policy.allowedPairs(ProtectionMode.EXPERIMENTAL_AUDIO, 2));
        assertEquals(4, policy.allowedPairs(ProtectionMode.EXPERIMENTAL_AUDIO, 4));
        assertEquals(0, policy.nextSafeNightStreak(4, 1, 0));
        assertEquals(0, policy.nextSafeNightStreak(4, 0, 3));
    }

    @Test public void calibrationStartsAtHalfThresholdAndNeverAboveFourPercent() {
        assertEquals(0.04, new ProtectionConfig(
                ProtectionMode.EXPERIMENTAL_AUDIO, 0.10, 0)
                .initialOvernightGain(), 0.0001);
        assertEquals(0.02, new ProtectionConfig(
                ProtectionMode.EXPERIMENTAL_AUDIO, 0.04, 0)
                .initialOvernightGain(), 0.0001);
    }

    @Test public void premiumSignalsAddFeaturesWithoutRemovingCoreSupport() {
        CapabilityReport core = new CapabilityReport(
                true, true, true, true,
                false, false, false, false, true, true);
        assertEquals(CapabilityReport.Profile.CORE_PROTECTION, core.profile());
        assertTrue(core.experimentalAudioEligible());
        assertFalse(core.features().get("ibiHrv"));

        CapabilityReport enhanced = new CapabilityReport(
                true, true, true, true,
                true, true, true, true, true, true);
        assertEquals(CapabilityReport.Profile.ENHANCED_PROTECTION, enhanced.profile());
        assertTrue(enhanced.features().get("rawPpg"));

        CapabilityReport monitoring = new CapabilityReport(
                true, true, true, false,
                false, false, false, false, true, true);
        assertEquals(CapabilityReport.Profile.MONITORING_ONLY, monitoring.profile());
        CapabilityReport phoneOnly = new CapabilityReport(
                false, false, false, true,
                false, false, false, false, true, true);
        assertEquals(CapabilityReport.Profile.PHONE_PROTECTION_ONLY, phoneOnly.profile());
        CapabilityReport attention = new CapabilityReport(
                true, true, true, true,
                false, false, false, false, false, true);
        assertEquals(CapabilityReport.Profile.NEEDS_ATTENTION, attention.profile());
    }

    @Test public void decisionAlwaysFailsClosedForStaleOrOffWristSignals() {
        assertFalse(new DecisionSnapshot(1L, NightState.STABLE_SLEEP,
                false, false, true, "stale").cueAllowed);
        assertFalse(new DecisionSnapshot(1L, NightState.STABLE_SLEEP,
                true, true, true, "off wrist").cueAllowed);
    }
}
