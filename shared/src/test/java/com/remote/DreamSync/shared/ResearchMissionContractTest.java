package com.remote.DreamSync.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ResearchMissionContractTest {
    @Test
    public void waveformAndWindowMatchSuppliedResearchShape() {
        assertTrue(ResearchMissionContract.researchWaveformMatches());
        assertEquals(120, ResearchMissionContract.NREM_WINDOW_END_MIN
                - ResearchMissionContract.NREM_WINDOW_START_MIN);
    }

    @Test
    public void sleepContinuityLimitsFailConservatively() {
        assertFalse(ResearchMissionContract.SLEEP_HAPTICS_ALLOWED);
        assertEquals(20L * 60_000L, ResearchMissionContract.AROUSAL_QUIET_MS);
        assertEquals(5L * 60_000L,
                ResearchMissionContract.POST_CUE_OBSERVATION_MS);
        assertEquals(1, ResearchMissionContract.FIRST_ELIGIBLE_NIGHT_PAIRS);
        assertEquals(0.04,
                ResearchMissionContract.MAX_INITIAL_OVERNIGHT_GAIN, 0.0);
    }

    @Test
    public void automaticDetectionRequiresStableFreshSignals() {
        assertEquals(10, ResearchMissionContract.SLEEP_ONSET_QUIET_MIN);
        assertEquals(10, ResearchMissionContract.NREM_STABLE_MIN);
        assertEquals(300, ResearchMissionContract.CUE_INTERVAL_SEC);

        DecisionSnapshot stale = new DecisionSnapshot(
                1L, NightState.STABLE_SLEEP, false, false,
                true, "stale sensors");
        DecisionSnapshot offWrist = new DecisionSnapshot(
                2L, NightState.STABLE_SLEEP, true, true,
                true, "off wrist");
        assertFalse(stale.cueAllowed);
        assertFalse(offWrist.cueAllowed);
    }
}
