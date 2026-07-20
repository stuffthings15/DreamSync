package com.remote.DreamSync.shared;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Pure fail-closed verification for optional high-end watch sensor readings. */
public final class AdvancedSensorVerification {
    public static final class Result {
        public final boolean expected;
        public final boolean passed;
        public final String reason;

        Result(boolean expected, boolean passed, String reason) {
            this.expected = expected;
            this.passed = passed;
            this.reason = reason;
        }
    }

    public Result evaluate(
            boolean sdkConnected,
            Set<String> trackerTypes,
            boolean rawPpgRequested,
            boolean rawPpgPermissionGranted,
            int ibiSamples,
            int temperatureSamples,
            int ppgSamples
    ) {
        if (!sdkConnected) {
            return new Result(false, false,
                    "Samsung advanced sensor service is not connected.");
        }
        Set<String> trackers = trackerTypes == null
                ? Collections.emptySet() : trackerTypes;
        boolean expected = trackers.contains("HEART_RATE_CONTINUOUS")
                || trackers.contains("SKIN_TEMPERATURE_CONTINUOUS")
                || (rawPpgRequested && trackers.contains("PPG_CONTINUOUS"));
        if (!expected) {
            return new Result(false, false,
                    "No advanced tracker was reported by this watch.");
        }
        List<String> missing = new ArrayList<>();
        if (trackers.contains("HEART_RATE_CONTINUOUS") && ibiSamples < 1) {
            missing.add("no valid IBI reading");
        }
        if (trackers.contains("SKIN_TEMPERATURE_CONTINUOUS")
                && temperatureSamples < 1) {
            missing.add("no valid skin-temperature reading");
        }
        if (rawPpgRequested && trackers.contains("PPG_CONTINUOUS")) {
            if (!rawPpgPermissionGranted) {
                missing.add("raw-PPG permission missing");
            } else if (ppgSamples < 1) {
                missing.add("no valid raw-PPG reading");
            }
        }
        if (!missing.isEmpty()) {
            return new Result(true, false, String.join("; ", missing));
        }
        return new Result(true, true,
                "All reported advanced sensor readings were verified.");
    }
}
