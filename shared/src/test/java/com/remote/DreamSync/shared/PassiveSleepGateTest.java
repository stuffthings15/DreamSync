package com.remote.DreamSync.shared;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PassiveSleepGateTest {
    @Test public void defaultWindowCrossesMidnight() {
        PassiveSleepGate gate = new PassiveSleepGate();
        assertTrue(gate.inSleepWindow(23 * 60, -1));
        assertTrue(gate.inSleepWindow(3 * 60, -1));
        assertFalse(gate.inSleepWindow(12 * 60, -1));
    }

    @Test public void learnedWindowFollowsTypicalSleepTime() {
        PassiveSleepGate gate = new PassiveSleepGate();
        assertTrue(gate.inSleepWindow(22 * 60, 23 * 60));
        assertTrue(gate.inSleepWindow(2 * 60, 23 * 60));
        assertFalse(gate.inSleepWindow(14 * 60, 23 * 60));
    }

    @Test public void requiresThreeLowSamplesAcrossTenMinutes() {
        PassiveSleepGate gate = new PassiveSleepGate();
        PassiveSleepGate.Result first = gate.observe(true, 64.0, 1_000L, 0L, 0);
        PassiveSleepGate.Result second = gate.observe(true, 63.0, 301_000L,
                first.firstLowHeartRateElapsedMs, first.lowHeartRateSamples);
        PassiveSleepGate.Result third = gate.observe(true, 62.0, 601_000L,
                second.firstLowHeartRateElapsedMs, second.lowHeartRateSamples);
        assertFalse(first.candidateReady);
        assertFalse(second.candidateReady);
        assertTrue(third.candidateReady);
    }

    @Test public void highHeartRateResetsCandidate() {
        PassiveSleepGate gate = new PassiveSleepGate();
        PassiveSleepGate.Result result = gate.observe(true, 92.0, 601_000L,
                1_000L, 2);
        assertFalse(result.candidateReady);
        assertTrue(result.firstLowHeartRateElapsedMs == 0L);
        assertTrue(result.lowHeartRateSamples == 0);
    }
}
