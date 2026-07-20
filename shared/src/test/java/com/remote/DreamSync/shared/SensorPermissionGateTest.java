package com.remote.DreamSync.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SensorPermissionGateTest {
    private final SensorPermissionGate gate = new SensorPermissionGate();

    @Test public void activityPermissionLossTerminatesSensorSession() {
        SensorPermissionGate.Result result = gate.evaluate(false, true);
        assertEquals(SensorPermissionGate.Action.TERMINATE_SESSION, result.action);
        assertTrue(result.reason.contains("Activity-recognition"));
    }

    @Test public void heartPermissionLossKeepsLoggingButDisablesCues() {
        SensorPermissionGate.Result result = gate.evaluate(true, false);
        assertEquals(SensorPermissionGate.Action.DISABLE_CUES, result.action);
        assertTrue(result.reason.contains("cues are disabled"));
    }

    @Test public void completePermissionsContinueNormally() {
        assertEquals(SensorPermissionGate.Action.CONTINUE,
                gate.evaluate(true, true).action);
    }
}
