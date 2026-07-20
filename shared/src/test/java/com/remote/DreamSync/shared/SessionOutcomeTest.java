package com.remote.DreamSync.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public final class SessionOutcomeTest {
    @Test public void plannedEndIsCompleteAndMissingOnsetIsTimedOut() {
        assertEquals(SessionState.COMPLETE, SessionOutcome.forConfiguredLimit(true));
        assertEquals(SessionState.TIMED_OUT, SessionOutcome.forConfiguredLimit(false));
    }

    @Test public void terminalStateNormalizationIsFailClosed() {
        assertEquals("PHONE_LOST", SessionOutcome.normalizePersistentState("phone_lost"));
        assertEquals("USER_STOPPED", SessionOutcome.normalizePersistentState("stopped"));
        assertEquals("PROCESS_INTERRUPTED", SessionOutcome.normalizePersistentState("interrupted"));
        assertEquals("INTERNAL_ERROR", SessionOutcome.normalizePersistentState("storage_failed"));
        assertEquals("INTERNAL_ERROR", SessionOutcome.normalizePersistentState("running"));
        assertEquals("INTERNAL_ERROR", SessionOutcome.normalizePersistentState("hostile-value"));
    }

    @Test public void firstTerminalCauseCannotBeOverwritten() {
        SessionCoordinator coordinator = new SessionCoordinator();
        coordinator.beginFreshHandshake();
        coordinator.arm();
        coordinator.terminate(SessionState.PHONE_LOST, "bound phone disconnected");
        coordinator.terminate(SessionState.COMPLETE, "later configured end");

        assertEquals(SessionState.PHONE_LOST, coordinator.state());
        assertEquals("bound phone disconnected", coordinator.terminationReason());
        assertFalse(coordinator.canCue());
    }

    @Test public void hostileLiveStateDegradesInsteadOfCompletingSession() {
        assertEquals("ARMED", SessionOutcome.normalizeLiveState("armed"));
        assertEquals("DEGRADED", SessionOutcome.normalizeLiveState("complete"));
        assertEquals("DEGRADED", SessionOutcome.normalizeLiveState("hostile-value"));
    }
}
