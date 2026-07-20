package com.remote.DreamSync.shared;

import java.util.Locale;

/** Pure terminal-state rules shared by the phone, watch, and replay tests. */
public final class SessionOutcome {
    private SessionOutcome() {}

    public static SessionState forConfiguredLimit(boolean sleepOnsetEstimated) {
        return sleepOnsetEstimated ? SessionState.COMPLETE : SessionState.TIMED_OUT;
    }

    public static String normalizePersistentState(String value) {
        String normalized = value == null
                ? "" : value.trim().toUpperCase(Locale.US);
        if ("STOPPED".equals(normalized)) normalized = "USER_STOPPED";
        if ("INTERRUPTED".equals(normalized)) normalized = "PROCESS_INTERRUPTED";
        if ("STORAGE_FAILED".equals(normalized)) normalized = "INTERNAL_ERROR";
        try {
            SessionState state = SessionState.valueOf(normalized);
            return SessionCoordinator.isTerminal(state)
                    ? state.name() : SessionState.INTERNAL_ERROR.name();
        } catch (IllegalArgumentException ignored) {
            return SessionState.INTERNAL_ERROR.name();
        }
    }

    public static String normalizeLiveState(String value) {
        String normalized = value == null
                ? "" : value.trim().toUpperCase(Locale.US);
        try {
            SessionState state = SessionState.valueOf(normalized);
            if (SessionCoordinator.isTerminal(state)
                    || state == SessionState.IDLE
                    || state == SessionState.STOPPING) {
                return SessionState.DEGRADED.name();
            }
            return state.name();
        } catch (IllegalArgumentException ignored) {
            return SessionState.DEGRADED.name();
        }
    }
}
