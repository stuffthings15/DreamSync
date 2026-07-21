package com.remote.DreamSync;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DebugLogTest {
    @Test public void pipelineShowsLatestSoundResultAndSafeHapticsPolicy() {
        String events = "1 | SOUND_CHECK_FAILED | first\n"
                + "2 | SOUND_CHECK_AUDIBLE_CONFIRMED | recovered\n"
                + "3 | WATCH_FOUND | reachable\n"
                + "4 | WATCH_HEARTBEAT | decision=BLOCKED: sleep onset not confirmed\n"
                + "5 | WATCH_SETUP_HAPTIC_CONFIRMED | awakeSetupOnly=true\n";

        String report = DebugLog.pipelineVerification(events);

        assertTrue(report.contains("1. Phone fallback sound check (secondary): PASS"));
        assertTrue(report.contains("2. Wear OS watch discovered: PASS"));
        assertTrue(report.contains("8. Sensor readings and quality received: PASS"));
        assertTrue(report.contains("12. Awake watch setup haptic: PASS"));
        assertTrue(report.contains("Sleep haptics: OFF by design"));
    }

    @Test public void pipelineKeepsMissingNightStepsWaiting() {
        String report = DebugLog.pipelineVerification("");

        assertTrue(report.contains("5. Passive sleep input received: WAITING"));
        assertTrue(report.contains("10. Cue request transported when eligible: WAITING"));
    }

    @Test public void pipelineReportsUserRunLiveSensorCheck() {
        String report = DebugLog.pipelineVerification(
                "1 | WATCH_SYSTEM_CHECK_HAPTIC_REQUESTED | durationMs=240\n"
                        + "2 | WATCH_SYSTEM_CHECK_RESULT | status=PASS;motionSamples=30;heartSamples=2\n");

        assertTrue(report.contains("12. Awake watch setup haptic: PASS"));
        assertTrue(report.contains("14. User-run live watch sensor check: PASS"));
    }

    @Test public void pipelineSeparatesCorePassFromAdvancedSensorFailure() {
        String report = DebugLog.pipelineVerification(
                "1 | WATCH_SYSTEM_CHECK_RESULT | status=PASS;advancedSensorState=FAILED\n");

        assertTrue(report.contains("14. User-run live watch sensor check: PASS"));
        assertTrue(report.contains(
                "15. Advanced sensor handling: FAIL"));
    }

    @Test public void pipelineReportsVerboseWatchSensorAudioAndReportStages() {
        String report = DebugLog.pipelineVerification(
                "1 | WATCH_SYSTEM_CHECK_MOTION_REGISTERED | selected=accelerometer\n"
                        + "2 | WATCH_SYSTEM_CHECK_MEASURE_HEART_REGISTERED | HEART_RATE_BPM\n"
                        + "3 | WATCH_SYSTEM_CHECK_MEASURE_HEART_DATA | accepted=1\n"
                        + "4 | WATCH_SYSTEM_CHECK_WATCH_AUDIO_COMPLETED | builtInSpeaker=true\n"
                        + "5 | SYSTEM_CHECK_REPORT_DISPLAYED | status=PASS\n");

        assertTrue(report.contains("16. Watch motion sensor registration: PASS"));
        assertTrue(report.contains("17. Health Services live heart-rate path: PASS"));
        assertTrue(report.contains("18. Watch speaker test: PASS"));
        assertTrue(report.contains("19. Phone result report displayed: PASS"));
    }

    @Test public void pipelineSeparatesRequestedHapticFromPhysicalConfirmation() {
        String requested = DebugLog.pipelineVerification(
                "1 | WATCH_SYSTEM_CHECK_HAPTIC_REQUESTED | durationMs=240\n");
        String felt = DebugLog.pipelineVerification(
                "1 | WATCH_SYSTEM_CHECK_HAPTIC_REQUESTED | durationMs=240\n"
                        + "2 | SYSTEM_CHECK_WATCH_HAPTIC_FELT | confirmed=true\n");
        String notFelt = DebugLog.pipelineVerification(
                "1 | SYSTEM_CHECK_WATCH_HAPTIC_NOT_FELT | confirmed=false\n");

        assertTrue(requested.contains(
                "20. User-confirmed physical watch vibration: WAITING"));
        assertTrue(felt.contains(
                "20. User-confirmed physical watch vibration: PASS"));
        assertTrue(notFelt.contains(
                "20. User-confirmed physical watch vibration: FAIL"));
    }
}
