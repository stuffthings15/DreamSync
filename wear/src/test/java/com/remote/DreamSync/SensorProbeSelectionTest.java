package com.remote.DreamSync;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SensorProbeSelectionTest {
    @Test public void allSelectionEnablesEveryProviderFamily() {
        SensorProbeSelection selection = SensorProbeSelection.fromQuery(null);

        assertTrue(selection.standardMotion());
        assertTrue(selection.samsungMotion());
        assertTrue(selection.healthServicesHeart());
        assertTrue(selection.standardHeart());
        assertTrue(selection.samsungHeart());
        assertTrue(selection.durationMs() == 75_000L);
        assertTrue(selection.allowsHeartFallback());
    }

    @Test public void physicalAccelerometerRunsWithoutCompetingProviders() {
        SensorProbeSelection selection =
                SensorProbeSelection.fromQuery("android-accelerometer");

        assertTrue(selection.standardMotion());
        assertTrue(selection.acceptsMotionType(android.hardware.Sensor.TYPE_ACCELEROMETER));
        assertFalse(selection.acceptsMotionType(android.hardware.Sensor.TYPE_LINEAR_ACCELERATION));
        assertFalse(selection.samsungMotion());
        assertFalse(selection.healthServicesHeart());
        assertFalse(selection.standardHeart());
        assertFalse(selection.samsungHeart());
        assertTrue(selection.durationMs() == 60_000L);
        assertFalse(selection.allowsHeartFallback());
    }

    @Test public void samsungHeartRunsAlone() {
        SensorProbeSelection selection = SensorProbeSelection.fromQuery("samsung-heart");

        assertTrue(selection.samsungHeart());
        assertFalse(selection.standardMotion());
        assertFalse(selection.samsungMotion());
        assertFalse(selection.healthServicesHeart());
        assertFalse(selection.standardHeart());
        assertTrue(selection.samsungTrackerTypes().contains("HEART_RATE_CONTINUOUS"));
        assertFalse(selection.samsungTrackerTypes().contains("ACCELEROMETER_CONTINUOUS"));
    }

    @Test public void unknownProbeFailsClosed() {
        SensorProbeSelection selection = SensorProbeSelection.fromQuery("not-a-provider");

        assertFalse(selection.valid());
        assertFalse(selection.standardMotion());
        assertFalse(selection.samsungMotion());
        assertFalse(selection.healthServicesHeart());
        assertFalse(selection.standardHeart());
        assertFalse(selection.samsungHeart());
    }
}
