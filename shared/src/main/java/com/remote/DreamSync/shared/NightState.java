package com.remote.DreamSync.shared;

/** User-facing automatic-night state. */
public enum NightState {
    OFF,
    ON_WAITING,
    POSSIBLE_SLEEP,
    CONFIRMING_SLEEP,
    LIKELY_ASLEEP,
    STABLE_SLEEP,
    POSSIBLE_AROUSAL,
    QUIET_RECOVERY,
    COMPLETE
}
