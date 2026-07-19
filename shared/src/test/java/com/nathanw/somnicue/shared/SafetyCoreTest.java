package com.nathanw.somnicue.shared;

import static org.junit.Assert.*;

import org.junit.Test;

public class SafetyCoreTest {
    @Test public void lucidRequiresFreshHeartData() {
        SignalQualityMonitor monitor = new SignalQualityMonitor();
        SignalQualityMonitor.Result noHeart = monitor.evaluate(
                10_000L, 9_900L, 0L, 40, 0, true, true);
        assertTrue(noHeart.deepCueAllowed);
        assertFalse(noHeart.lucidCueAllowed);
    }

    @Test public void sleepOnsetRequiresSustainedQuiet() {
        SleepOnsetEstimator estimator = new SleepOnsetEstimator();
        assertEquals(0L, estimator.update(true, 1_000L, 10_000L));
        assertEquals(0L, estimator.update(false, 5_000L, 10_000L));
        assertEquals(0L, estimator.update(true, 6_000L, 10_000L));
        assertEquals(6_000L, estimator.update(true, 16_000L, 10_000L));
    }

    @Test public void threeLinkedArousalsDisableCues() {
        CuePolicy policy = new CuePolicy();
        double gain = 0.04;
        gain = policy.onCueLinkedArousal(gain);
        gain = policy.onCueLinkedArousal(gain);
        gain = policy.onCueLinkedArousal(gain);
        assertTrue(policy.isDisabled());
        assertTrue(gain >= 0.005);
    }

    @Test public void waveformKeepsLocalPairTiming() {
        float[] deep = CueWaveforms.deepPinkPair(0.04f, 1L);
        assertEquals((int) (1.075 * CueWaveforms.SAMPLE_RATE)
                + (int) (0.050 * CueWaveforms.SAMPLE_RATE), deep.length);
        assertNotEquals(0f, deep[(int) (1.075 * CueWaveforms.SAMPLE_RATE) + 20], 0f);
        float[] lucid = CueWaveforms.lucidChime(0.04f);
        assertEquals((int) (0.120 * CueWaveforms.SAMPLE_RATE) * 2
                + (int) (0.100 * CueWaveforms.SAMPLE_RATE), lucid.length);
    }
}
