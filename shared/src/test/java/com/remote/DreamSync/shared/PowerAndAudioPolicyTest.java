package com.remote.DreamSync.shared;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PowerAndAudioPolicyTest {
    @Test public void rawPpgIsBoundedToShortCueWindows() {
        RawPpgWindowPolicy policy = new RawPpgWindowPolicy();
        long preEnd = policy.openPreCue(1_000L);
        assertEquals(31_000L, preEnd);
        assertTrue(policy.active(30_999L, preEnd));
        assertFalse(policy.active(31_000L, preEnd));
        assertEquals(331_000L, policy.extendAfterAudioStart(preEnd, 31_000L));
    }

    @Test public void watchAudioNeedsUserConfirmedExactThreshold() {
        WatchAudioCalibration calibration = new WatchAudioCalibration();
        assertFalse(calibration.valid(false, 0.04));
        assertFalse(calibration.valid(true, 0.0));
        assertTrue(calibration.valid(true, 0.04));
        assertEquals(0.02, calibration.overnightGain(0.04), 0.0001);
        assertTrue(calibration.setupMatches(true, 0.04, 4, 10, 4, 10));
        assertFalse(calibration.setupMatches(true, 0.04, 4, 10, 5, 10));
        assertFalse(calibration.setupMatches(true, 0.04, 4, 10, 4, 15));
    }
}
