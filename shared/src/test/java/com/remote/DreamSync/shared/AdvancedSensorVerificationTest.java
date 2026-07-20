package com.remote.DreamSync.shared;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

public final class AdvancedSensorVerificationTest {
    private final AdvancedSensorVerification verifier = new AdvancedSensorVerification();

    @Test public void allReportedAdvancedReadingsMustActuallyArrive() {
        Set<String> trackers = Set.of(
                "HEART_RATE_CONTINUOUS",
                "SKIN_TEMPERATURE_CONTINUOUS",
                "PPG_CONTINUOUS");
        assertTrue(verifier.evaluate(true, trackers, true, true,
                2, 1, 10).passed);
        assertFalse(verifier.evaluate(true, trackers, true, true,
                0, 1, 10).passed);
        assertFalse(verifier.evaluate(true, trackers, true, true,
                2, 0, 10).passed);
        assertFalse(verifier.evaluate(true, trackers, true, true,
                2, 1, 0).passed);
    }

    @Test public void rawPpgPermissionIsRequiredWhenPpgWasRequested() {
        AdvancedSensorVerification.Result result = verifier.evaluate(
                true, Set.of("PPG_CONTINUOUS"), true, false,
                0, 0, 20);
        assertFalse(result.passed);
        assertTrue(result.reason.contains("permission"));
    }

    @Test public void disconnectedSdkCannotClaimAdvancedVerification() {
        AdvancedSensorVerification.Result result = verifier.evaluate(
                false, Set.of(), true, false, 0, 0, 0);
        assertFalse(result.expected);
        assertFalse(result.passed);
    }
}
