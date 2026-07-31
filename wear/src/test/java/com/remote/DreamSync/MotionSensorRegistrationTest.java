package com.remote.DreamSync;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class MotionSensorRegistrationTest {
    @Test
    public void choosesRateInsideGalaxyWatchAccelerometerRange() {
        assertEquals(100_000,
                MotionSensorRegistration.safeSamplingPeriodUs(10_000, 160_000));
    }

    @Test
    public void treatsZeroMaximumDelayAsUnbounded() {
        assertEquals(100_000,
                MotionSensorRegistration.safeSamplingPeriodUs(0, 0));
    }
}
