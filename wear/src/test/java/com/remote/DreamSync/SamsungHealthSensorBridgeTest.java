package com.remote.DreamSync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class SamsungHealthSensorBridgeTest {
    @Test
    public void accelerometerRawConversionMatchesSamsungSpecification() {
        double scale = 9.81 * 4.0 / 16_383.75;
        assertEquals(0.0,
                SamsungHealthSensorBridge.accelerometerRawToMetersPerSecondSquared(0),
                0.0);
        assertEquals(4096 * scale,
                SamsungHealthSensorBridge.accelerometerRawToMetersPerSecondSquared(4096),
                1e-9);
        assertEquals(-4096 * scale,
                SamsungHealthSensorBridge.accelerometerRawToMetersPerSecondSquared(-4096),
                1e-9);
    }

    @Test
    public void heartRateRequiresSamsungSuccessStatusAndPlausibleBpm() {
        assertTrue(SamsungHealthSensorBridge.isUsableHeartRateSample(1, 60));
        assertFalse(SamsungHealthSensorBridge.isUsableHeartRateSample(0, 60));
        assertFalse(SamsungHealthSensorBridge.isUsableHeartRateSample(-2, 60));
        assertFalse(SamsungHealthSensorBridge.isUsableHeartRateSample(-3, 60));
        assertFalse(SamsungHealthSensorBridge.isUsableHeartRateSample(null, 60));
        assertFalse(SamsungHealthSensorBridge.isUsableHeartRateSample(1, 29));
        assertFalse(SamsungHealthSensorBridge.isUsableHeartRateSample(1, 221));
    }

    @Test
    public void offWristIsOnlySamsungMinusThreeStatus() {
        assertTrue(SamsungHealthSensorBridge.isOffWristHeartRateStatus(-3));
        assertFalse(SamsungHealthSensorBridge.isOffWristHeartRateStatus(1));
        assertFalse(SamsungHealthSensorBridge.isOffWristHeartRateStatus(null));
    }

    @Test
    public void ibiIsAcceptedOnlyForSuccessfulHeartRateAndValidIbiStatus() {
        assertEquals(Arrays.asList(600, 2_000),
                SamsungHealthSensorBridge.filterUsableIbi(
                        1,
                        Arrays.asList(249, 600, 900, 2_000, 2_001),
                        Arrays.asList(0, 0, 1, 0, 0)));
        assertEquals(Collections.emptyList(),
                SamsungHealthSensorBridge.filterUsableIbi(
                        0, Collections.singletonList(600), Collections.singletonList(0)));
        assertEquals(Collections.emptyList(),
                SamsungHealthSensorBridge.filterUsableIbi(
                        -3, Collections.singletonList(600), Collections.singletonList(0)));
        assertEquals(Collections.emptyList(),
                SamsungHealthSensorBridge.filterUsableIbi(
                        1, Collections.singletonList(600), Collections.emptyList()));
    }
}
