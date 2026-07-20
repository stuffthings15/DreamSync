package com.remote.DreamSync;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DebugLogTest {
    @Test public void pipelineShowsLatestSoundResultAndSafeHapticsPolicy() {
        String events = "1 | SOUND_CHECK_FAILED | first\n"
                + "2 | SOUND_CHECK_AUDIBLE_CONFIRMED | recovered\n"
                + "3 | WATCH_FOUND | reachable\n"
                + "4 | WATCH_HEARTBEAT | decision=BLOCKED: sleep onset not confirmed\n";

        String report = DebugLog.pipelineVerification(events);

        assertTrue(report.contains("1. Phone sound check: PASS"));
        assertTrue(report.contains("2. Wear OS watch discovered: PASS"));
        assertTrue(report.contains("8. Sensor readings and quality received: PASS"));
        assertTrue(report.contains("12. Watch vibration/haptics: PASS"));
    }

    @Test public void pipelineKeepsMissingNightStepsWaiting() {
        String report = DebugLog.pipelineVerification("");

        assertTrue(report.contains("5. Passive sleep input received: WAITING"));
        assertTrue(report.contains("10. Cue request transported when eligible: WAITING"));
    }

    @Test public void pipelineReportsUserRunLiveSensorCheck() {
        String report = DebugLog.pipelineVerification(
                "1 | WATCH_SYSTEM_CHECK_RESULT | status=PASS;motionSamples=30;heartSamples=2\n");

        assertTrue(report.contains("14. User-run live watch sensor check: PASS"));
    }
}
