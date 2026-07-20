package com.remote.DreamSync.shared;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AutoSessionGateTest {
    private final AutoSessionGate gate = new AutoSessionGate();

    @Test public void requiresPersistentOnAndAsleepState() {
        assertFalse(gate.mayStart(false, true, false, 0L, 10_000L));
        assertFalse(gate.mayStart(true, false, false, 0L, 10_000L));
        assertTrue(gate.mayStart(true, true, false, 0L, 10_000L));
    }

    @Test public void blocksRunningAndRapidDuplicateSessions() {
        long now = 20_000_000L;
        assertFalse(gate.mayStart(true, true, true, 0L, now));
        assertFalse(gate.mayStart(true, true, false,
                now - AutoSessionGate.MIN_START_GAP_MS + 1L, now));
        assertTrue(gate.mayStart(true, true, false,
                now - AutoSessionGate.MIN_START_GAP_MS, now));
    }
}
