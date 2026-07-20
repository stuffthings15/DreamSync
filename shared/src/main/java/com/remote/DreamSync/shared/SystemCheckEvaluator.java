package com.remote.DreamSync.shared;

/** Pure, deterministic pass/fail rules for the user-run watch sensor check. */
public final class SystemCheckEvaluator {
    public static final int MIN_MOTION_SAMPLES = 5;
    public static final int MIN_HEART_SAMPLES = 1;

    public static final class Result {
        public final boolean passed;
        public final String status;
        public final String reason;

        private Result(boolean passed, String status, String reason) {
            this.passed = passed;
            this.status = status;
            this.reason = reason;
        }
    }

    public Result evaluate(
            boolean activityPermission,
            boolean heartPermission,
            boolean motionRegistered,
            boolean heartRegistered,
            int motionSamples,
            int heartSamples,
            boolean offWrist
    ) {
        if (!activityPermission) {
            return fail("Activity permission is missing on the watch.");
        }
        if (!heartPermission) {
            return fail("Heart-rate permission is missing on the watch.");
        }
        if (!motionRegistered) {
            return fail("The watch accelerometer did not register.");
        }
        if (!heartRegistered) {
            return fail("The watch heart-rate sensor did not register.");
        }
        if (offWrist) {
            return fail("The watch reported off-wrist. Wear it snugly and retry.");
        }
        if (Math.max(0, motionSamples) < MIN_MOTION_SAMPLES) {
            return fail("No usable motion stream arrived during the check.");
        }
        if (Math.max(0, heartSamples) < MIN_HEART_SAMPLES) {
            return fail("No usable heart-rate reading arrived during the check.");
        }
        return new Result(true, "PASS",
                "Motion and heart-rate readings passed the live watch check.");
    }

    private Result fail(String reason) {
        return new Result(false, "FAIL", reason);
    }
}
