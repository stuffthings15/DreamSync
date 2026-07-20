package com.remote.DreamSync.shared;

/** Pure safety decision for permissions that can be revoked during a session. */
public final class SensorPermissionGate {
    public enum Action {
        CONTINUE,
        DISABLE_CUES,
        TERMINATE_SESSION
    }

    public static final class Result {
        public final Action action;
        public final String reason;

        Result(Action action, String reason) {
            this.action = action;
            this.reason = reason;
        }
    }

    public Result evaluate(boolean activityPermission, boolean heartPermission) {
        if (!activityPermission) {
            return new Result(Action.TERMINATE_SESSION,
                    "Activity-recognition permission was removed.");
        }
        if (!heartPermission) {
            return new Result(Action.DISABLE_CUES,
                    "Heart-rate permission was removed; motion logging may continue, but cues are disabled.");
        }
        return new Result(Action.CONTINUE, "Required sensor permissions are available.");
    }
}
