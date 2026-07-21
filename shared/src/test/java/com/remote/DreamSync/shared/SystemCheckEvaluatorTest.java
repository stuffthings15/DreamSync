package com.remote.DreamSync.shared;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SystemCheckEvaluatorTest {
    private final SystemCheckEvaluator evaluator = new SystemCheckEvaluator();

    @Test public void passesOnlyAfterRealMotionAndHeartSamples() {
        SystemCheckEvaluator.Result result = evaluator.evaluate(
                true, true, true, true, 30, 2, false);
        assertTrue(result.passed);
        assertTrue(result.reason.contains("passed"));
    }

    @Test public void missingHeartReadingFailsWithActionableReason() {
        SystemCheckEvaluator.Result result = evaluator.evaluate(
                true, true, true, true, 30, 0, false);
        assertFalse(result.passed);
        assertTrue(result.reason.contains("heart-rate reading"));
    }

    @Test public void missingBothStreamsReportsBothFailures() {
        SystemCheckEvaluator.Result result = evaluator.evaluate(
                true, true, true, true, 0, 0, false);
        assertFalse(result.passed);
        assertTrue(result.reason.contains("motion stream"));
        assertTrue(result.reason.contains("heart-rate reading"));
    }

    @Test public void offWristAlwaysFailsClosed() {
        SystemCheckEvaluator.Result result = evaluator.evaluate(
                true, true, true, true, 30, 2, true);
        assertFalse(result.passed);
        assertTrue(result.reason.contains("off-wrist"));
    }

    @Test public void missingPermissionsFailBeforeSensorClaims() {
        SystemCheckEvaluator.Result result = evaluator.evaluate(
                false, true, true, true, 30, 2, false);
        assertFalse(result.passed);
        assertTrue(result.reason.contains("Activity permission"));
    }
}
