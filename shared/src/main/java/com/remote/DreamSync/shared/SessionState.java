package com.remote.DreamSync.shared;

public enum SessionState {
    IDLE,
    PREFLIGHT,
    ARMING,
    ARMED,
    RUNNING,
    DEGRADED,
    PAUSED,
    STOPPING,
    COMPLETE,
    USER_STOPPED,
    TIMED_OUT,
    PHONE_LOST,
    SENSOR_FAILED,
    LOW_BATTERY,
    PROCESS_INTERRUPTED,
    INTERNAL_ERROR
}
