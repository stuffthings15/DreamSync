package com.remote.DreamSync.shared;

/** Pure session lifecycle gate. A process restart cannot re-enable cues without a handshake. */
public final class SessionCoordinator {
    private SessionState state = SessionState.IDLE;
    private boolean freshHandshake;
    private boolean cueingEnabled;
    private String terminationReason = "";
    private String detail = "";

    public void beginFreshHandshake() {
        freshHandshake = true;
        cueingEnabled = false;
        terminationReason = "";
        detail = "";
        state = SessionState.PREFLIGHT;
    }

    public void arm() {
        requireFreshHandshake();
        state = SessionState.ARMED;
        cueingEnabled = true;
    }

    public void waitForSleep() {
        requireFreshHandshake();
        state = SessionState.WAITING_FOR_SLEEP;
    }

    public void markSleepOnset() {
        requireFreshHandshake();
        state = SessionState.SLEEP_ONSET_ESTIMATED;
    }

    public void markRunning() {
        requireFreshHandshake();
        state = SessionState.RUNNING;
    }

    public void degrade(String reason) {
        state = SessionState.DEGRADED;
        cueingEnabled = false;
        detail = safe(reason);
    }

    public void disableCues(String reason) {
        cueingEnabled = false;
        detail = safe(reason);
    }

    public void terminate(SessionState terminalState, String reason) {
        if (!isTerminal(terminalState)) {
            throw new IllegalArgumentException("State is not terminal: " + terminalState);
        }
        state = terminalState;
        terminationReason = safe(reason);
        detail = terminationReason;
        cueingEnabled = false;
        freshHandshake = false;
    }

    public void recoverAfterProcessDeath() {
        terminate(SessionState.PROCESS_INTERRUPTED,
                "process interrupted; fresh phone-watch handshake required");
    }

    public boolean canCue() {
        return freshHandshake && cueingEnabled && !isTerminal(state);
    }

    public boolean requiresFreshHandshake() {
        return !freshHandshake;
    }

    public SessionState state() {
        return state;
    }

    public String terminationReason() {
        return terminationReason;
    }

    public String detail() {
        return detail;
    }

    public static boolean isTerminal(SessionState value) {
        return value == SessionState.COMPLETE
                || value == SessionState.USER_STOPPED
                || value == SessionState.TIMED_OUT
                || value == SessionState.PHONE_LOST
                || value == SessionState.SENSOR_FAILED
                || value == SessionState.LOW_BATTERY
                || value == SessionState.PROCESS_INTERRUPTED
                || value == SessionState.INTERNAL_ERROR;
    }

    private void requireFreshHandshake() {
        if (!freshHandshake) {
            throw new IllegalStateException("fresh handshake required");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
