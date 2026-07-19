package com.remote.DreamSync.shared;

import static org.junit.Assert.*;

import org.junit.Test;

public class SafetyCoreTest {
    @Test public void cuesRequireFreshHeartData() {
        SignalQualityMonitor monitor = new SignalQualityMonitor();
        SignalQualityMonitor.Result noHeart = monitor.evaluate(
                10_000L, 9_900L, 0L, 40, 0, true, true);
        assertFalse(noHeart.deepCueAllowed);
        assertFalse(noHeart.lucidCueAllowed);
    }

    @Test public void noCueEligibilityExistsBeforeEstimatedOnset() {
        SignalQualityMonitor.Result ready = new SignalQualityMonitor().evaluate(
                10_000L, 9_900L, 9_900L, 40, 4, true, true);
        EligibilityEstimator.Result result = new EligibilityEstimator().estimate(
                -1.0, 0.05, 58.0, 1.0, 66.0,
                false, ready, 0.0, 0.0, 25.0, 145.0, 255.0);
        assertEquals(EligibilityEstimator.State.SETTLING, result.state);
    }

    @Test public void sleepOnsetRequiresSustainedQuiet() {
        SleepOnsetEstimator estimator = new SleepOnsetEstimator();
        assertEquals(0L, estimator.update(true, 1_000L, 10_000L));
        assertEquals(0L, estimator.update(false, 5_000L, 10_000L));
        assertEquals(0L, estimator.update(true, 6_000L, 10_000L));
        assertEquals(6_000L, estimator.update(true, 16_000L, 10_000L));
        assertEquals(6_000L, estimator.update(false, 20_000L, 10_000L));
    }

    @Test public void automaticSessionSeparatesArmAndSleepTimeouts() {
        long armed = 60_000L;
        assertEquals(-1.0, SleepSessionClock.sleepElapsedMinutes(0L, armed), 0.0);
        assertFalse(SleepSessionClock.armTimedOut(armed, 0L, armed + 179L * 60_000L, 180));
        assertTrue(SleepSessionClock.armTimedOut(armed, 0L, armed + 180L * 60_000L, 180));

        long onset = armed + 30L * 60_000L;
        assertFalse(SleepSessionClock.armTimedOut(armed, onset, armed + 240L * 60_000L, 180));
        assertEquals(15.0,
                SleepSessionClock.sleepElapsedMinutes(onset, onset + 15L * 60_000L), 0.0);
        assertFalse(SleepSessionClock.sleepTimedOut(onset, onset + 509L * 60_000L, 510));
        assertTrue(SleepSessionClock.sleepTimedOut(onset, onset + 510L * 60_000L, 510));
    }

    @Test public void staleOrHostileArmWindowsAreRejected() {
        long now = 1_000_000_000L;
        assertTrue(SleepSessionClock.isCurrentArmWindow(
                now - 60_000L, now + 6L * 60L * 60_000L, now));
        assertFalse(SleepSessionClock.isCurrentArmWindow(
                now - 7L * 60L * 60_000L, now - 1L, now));
        assertFalse(SleepSessionClock.isCurrentArmWindow(
                now, now + 25L * 60L * 60_000L, now));
        assertFalse(SleepSessionClock.isCurrentArmWindow(0L, 0L, now));
    }

    @Test public void firstLinkedArousalDisablesCues() {
        CuePolicy policy = new CuePolicy();
        double gain = policy.onCueLinkedArousal(0.04);
        assertTrue(policy.isDisabled());
        assertTrue(gain >= 0.005);
    }

    @Test public void waveformKeepsLocalPairTiming() {
        float[] deep = CueWaveforms.deepPinkPair(0.04f, 1L);
        int secondStart = CueWaveforms.SAMPLE_RATE * CueWaveforms.DEEP_PAIR_INTERVAL_MS / 1_000;
        int burstSamples = CueWaveforms.SAMPLE_RATE * CueWaveforms.DEEP_BURST_MS / 1_000;
        assertEquals(secondStart + burstSamples, deep.length);
        assertNotEquals(0f, deep[secondStart + 20], 0f);
        float[] lucid = CueWaveforms.lucidChime(0.04f);
        assertEquals((int) (0.120 * CueWaveforms.SAMPLE_RATE) * 2
                + (int) (0.100 * CueWaveforms.SAMPLE_RATE), lucid.length);
    }
}
