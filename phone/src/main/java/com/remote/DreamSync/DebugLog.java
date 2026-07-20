package com.remote.DreamSync;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

final class DebugLog {
    private static final String FILE_NAME = "dreamsync-debug.log";
    private static final long MAX_FILE_BYTES = 1024L * 1024L;
    private static final Object LOCK = new Object();

    private DebugLog() {}

    static void append(Context context, String event, String details) {
        if (context == null) return;
        String safeEvent = sanitize(event, 80);
        String safeDetails = sanitize(details, 4_000);
        String line = System.currentTimeMillis()
                + " | elapsed=" + SystemClock.elapsedRealtime()
                + " | " + safeEvent
                + " | " + safeDetails + "\n";
        synchronized (LOCK) {
            File file = new File(context.getFilesDir(), FILE_NAME);
            boolean append = file.length() < MAX_FILE_BYTES;
            try (FileOutputStream output = new FileOutputStream(file, append)) {
                if (!append) {
                    output.write((System.currentTimeMillis()
                            + " | LOG_ROTATED | Previous log exceeded 1 MiB\n")
                            .getBytes(StandardCharsets.UTF_8));
                }
                output.write(line.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // Debug logging must never interrupt the sleep workflow.
            }
        }
    }

    static String export(Context context, AudioOutput audioOutput) {
        SharedPreferences prefs = context.getSharedPreferences("dreamsync_phone", Context.MODE_PRIVATE);
        PackageInfo packageInfo = packageInfo(context);
        StringBuilder text = new StringBuilder(8_192);
        text.append("DreamSync phone debug log\n")
                .append("generatedUtcMs=").append(System.currentTimeMillis()).append('\n')
                .append("appVersion=").append(packageInfo == null ? "unknown" : packageInfo.versionName).append('\n')
                .append("versionCode=").append(versionCode(packageInfo)).append('\n')
                .append("androidSdk=").append(Build.VERSION.SDK_INT).append('\n')
                .append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                .append("autoEnabled=").append(prefs.getBoolean("auto_enabled", false)).append('\n')
                .append("watchBound=").append(!prefs.getString("bound_watch_node", "").isEmpty()).append('\n')
                .append("watchConfirmed=").append(prefs.getBoolean("watch_auto_confirmed", false)).append('\n')
                .append("audibleCalibrationConfirmed=").append(
                        prefs.getBoolean("last_calibration_audible_confirmed", false)).append('\n')
                .append("watchInstallPending=").append(prefs.getBoolean("watch_install_pending", false)).append('\n')
                .append("activeSession=").append(!prefs.getString("active_session_id", "").isEmpty()).append('\n')
                .append("lastCalibrationUtcMs=").append(prefs.getLong("last_calibration_utc", 0L)).append('\n')
                .append("lastAudioError=").append(sanitize(
                        prefs.getString("last_audio_error", ""), 1_500)).append('\n')
                .append("lastAudioErrorUtcMs=").append(prefs.getLong("last_audio_error_utc", 0L)).append('\n')
                .append("lastWatchDiagnosticEvent=").append(sanitize(
                        prefs.getString("last_watch_diagnostic_event", ""), 160)).append('\n')
                .append("lastWatchDiagnosticUtcMs=").append(
                        prefs.getLong("last_watch_diagnostic_utc", 0L)).append('\n')
                .append("lastWatchHeartbeatUtcMs=").append(
                        prefs.getLong("last_watch_heartbeat_utc", 0L)).append('\n')
                .append("lastSystemCheckStatus=").append(sanitize(
                        prefs.getString("last_system_check_status", "not run"), 32)).append('\n')
                .append("lastSystemCheckReason=").append(sanitize(
                        prefs.getString("last_system_check_reason", ""), 500)).append('\n')
                .append("lastSystemCheckUtcMs=").append(
                        prefs.getLong("last_system_check_utc", 0L)).append('\n')
                .append("lastSystemCheckMotionSamples=").append(
                        prefs.getInt("last_system_check_motion_samples", 0)).append('\n')
                .append("lastSystemCheckHeartSamples=").append(
                        prefs.getInt("last_system_check_heart_samples", 0)).append('\n')
                .append("lastSystemCheckMotionRegistered=").append(
                        prefs.getBoolean("last_system_check_motion_registered", false)).append('\n')
                .append("lastSystemCheckHeartRegistered=").append(
                        prefs.getBoolean("last_system_check_heart_registered", false)).append('\n')
                .append("lastSystemCheckWatchAudioStarted=").append(
                        prefs.getBoolean("last_system_check_watch_audio_started", false)).append('\n')
                .append("lastSystemCheckWatchAudioCompleted=").append(
                        prefs.getBoolean("last_system_check_watch_audio_completed", false)).append('\n')
                .append("lastSystemCheckWatchAudioReason=").append(sanitize(
                        prefs.getString("last_system_check_watch_audio_reason", ""), 1_000)).append('\n')
                .append("lastSystemCheckMeasureAvailability=").append(sanitize(
                        prefs.getString("last_system_check_measure_availability", ""), 500)).append('\n')
                .append("lastWatchAudioAudible=").append(
                        prefs.contains("last_watch_audio_audible")
                                ? Boolean.toString(prefs.getBoolean(
                                        "last_watch_audio_audible", false))
                                : "not_confirmed").append('\n')
                .append("lastSystemCheckDetails=").append(sanitize(
                        prefs.getString("last_system_check_details", ""), 8_000)).append('\n')
                .append("audio=").append(audioOutput.diagnostics()).append('\n')
                .append("awakeSetupHaptic=ONE_SHORT_CONFIRMATION_AFTER_WATCH_MONITORING_STARTS\n")
                .append("hapticsDuringSleep=OFF_BY_DESIGN\n")
                .append("sleepAudioOutput=WATCH_SPEAKER_PRIMARY_PHONE_FALLBACK\n");
        String eventsText;
        synchronized (LOCK) {
            File file = new File(context.getFilesDir(), FILE_NAME);
            if (!file.isFile()) {
                eventsText = "";
            } else try {
                eventsText = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            } catch (Exception error) {
                eventsText = "Could not read app events: "
                        + error.getClass().getSimpleName() + "\n";
            }
        }
        text.append("\n--- pipeline verification ---\n")
                .append(pipelineVerification(eventsText))
                .append("\n--- app and watch events ---\n")
                .append(eventsText.isEmpty() ? "No events recorded.\n" : eventsText);
        return text.toString();
    }

    static String pipelineVerification(String events) {
        StringBuilder out = new StringBuilder();
        out.append("No cue is expected until every sleep and safety gate passes. "
                + "A blocked cue is a valid safety result.\n");
        step(out, 1, "Phone fallback sound check (secondary)",
                events, new String[]{" | SOUND_CHECK_AUDIBLE_CONFIRMED |"},
                new String[]{" | SOUND_CHECK_NOT_AUDIBLE |",
                        " | SOUND_CHECK_FAILED |", " | SOUND_CHECK_BLOCKED |"});
        step(out, 2, "Wear OS watch discovered",
                events, new String[]{" | WATCH_FOUND |"},
                new String[]{" | WATCH_NOT_FOUND |"});
        step(out, 3, "Automatic configuration delivered",
                events, new String[]{" | AUTO_CONFIG_PUBLISHED |", " | WATCH_AUTO_CONFIG_RECEIVED |"},
                new String[]{" | AUTO_CONFIG_PUBLISH_FAILED |"});
        step(out, 4, "Watch passive monitoring registered",
                events, new String[]{" | WATCH_PASSIVE_MONITORING_READY |"},
                new String[]{" | WATCH_PASSIVE_MONITORING_FAILED |",
                        " | WATCH_HEALTH_PERMISSION_LOST |"});
        step(out, 5, "Passive sleep input received",
                events, new String[]{" | WATCH_PASSIVE_ACTIVITY_RECEIVED |",
                        " | WATCH_PASSIVE_HEART_RATE_BATCH |"},
                new String[]{});
        step(out, 6, "Likely-sleep candidate and handshake",
                events, new String[]{" | AUTO_SESSION_START_RECEIVED |",
                        " | WATCH_AUTO_SESSION_REQUEST_DELIVERED |"},
                new String[]{" | AUTO_SESSION_START_REJECTED |",
                        " | WATCH_AUTO_SESSION_REQUEST_FAILED |"});
        step(out, 7, "Full-rate watch sensors registered",
                events, new String[]{" | WATCH_SENSOR_REGISTRATION |"},
                new String[]{" | WATCH_FULL_SENSOR_SESSION_START_FAILED |",
                        " | WATCH_HEART_RATE_PERMISSION_ERROR |",
                        " | WATCH_PERMISSION_REVOKED |"});
        step(out, 8, "Sensor readings and quality received",
                events, new String[]{" | WATCH_HEARTBEAT |"},
                new String[]{" | WATCH_SENSOR_FAILED |",
                        " | WATCH_PERMISSION_REVOKED |"});
        step(out, 9, "Sleep state and cue decision evaluated",
                events, new String[]{"decision=", " | WATCH_HEARTBEAT |"},
                new String[]{});
        step(out, 10, "Cue request transported when eligible",
                events, new String[]{" | CUE_MESSAGE_RECEIVED |",
                        " | WATCH_CUE_OUTPUT_SELECTED |",
                        " | WATCH_CUE_PHONE_FALLBACK_REQUESTED |"},
                new String[]{" | CUE_TRANSPORT_FAILED |", " | CUE_REJECTED |"});
        step(out, 11, "Cue audio lifecycle (watch primary, phone fallback)",
                events, new String[]{" | WATCH_CUE_AUDIO_STARTED_LOCAL |",
                        " | WATCH_CUE_AUDIO_COMPLETED_LOCAL |",
                        " | CUE_AUDIO_STARTED |", " | CUE_PLAYBACK_COMPLETED |"},
                new String[]{" | WATCH_CUE_WATCH_AUDIO_FAILED_AFTER_START |",
                        " | CUE_PLAYBACK_FAILED |", " | AUDIO_FAILED |"});
        step(out, 12, "Awake watch setup haptic",
                events, new String[]{" | WATCH_SETUP_HAPTIC_CONFIRMED |",
                        " | WATCH_SYSTEM_CHECK_HAPTIC_REQUESTED |"},
                new String[]{" | WATCH_SETUP_HAPTIC_UNAVAILABLE |",
                        " | WATCH_SYSTEM_CHECK_HAPTIC_UNAVAILABLE |",
                        " | WATCH_SYSTEM_CHECK_HAPTIC_FAILED |"});
        out.append("    Sleep haptics: OFF by design to protect sleep continuity.\n");
        step(out, 13, "Terminal summary returned",
                events, new String[]{" | SESSION_SUMMARY_RECEIVED |", " | SUMMARY_RECEIVED |"},
                new String[]{" | SESSION_SUMMARY_FAILED |"});
        step(out, 14, "User-run live watch sensor check",
                events, new String[]{" | WATCH_SYSTEM_CHECK_RESULT | status=PASS"},
                new String[]{" | WATCH_SYSTEM_CHECK_RESULT | status=FAIL",
                        " | SYSTEM_CHECK_FAILED |", " | SYSTEM_CHECK_BLOCKED |"});
        step(out, 15, "Advanced sensor handling",
                events, new String[]{"advancedSensorState=VERIFIED",
                        "advancedSensorState=UNAVAILABLE"},
                new String[]{"advancedSensorState=FAILED",
                        " | WATCH_ADVANCED_PERMISSION_MISSING |",
                        " | WATCH_SAMSUNG_SENSOR_ERROR |"});
        step(out, 16, "Watch motion sensor registration",
                events, new String[]{" | WATCH_SYSTEM_CHECK_MOTION_REGISTERED |"},
                new String[]{" | WATCH_SYSTEM_CHECK_MOTION_REGISTRATION_FAILED |"});
        step(out, 17, "Health Services live heart-rate path",
                events, new String[]{" | WATCH_SYSTEM_CHECK_MEASURE_HEART_REGISTERED |",
                        " | WATCH_SYSTEM_CHECK_MEASURE_HEART_DATA |"},
                new String[]{" | WATCH_SYSTEM_CHECK_MEASURE_HEART_REGISTRATION_FAILED |",
                        " | WATCH_SYSTEM_CHECK_MEASURE_CAPABILITIES_FAILED |"});
        step(out, 18, "Watch speaker test",
                events, new String[]{" | WATCH_SYSTEM_CHECK_WATCH_AUDIO_COMPLETED |"},
                new String[]{" | WATCH_SYSTEM_CHECK_WATCH_AUDIO_PREFLIGHT_FAIL |",
                        " | WATCH_SYSTEM_CHECK_WATCH_AUDIO_FAILED |",
                        " | SYSTEM_CHECK_WATCH_AUDIO_NOT_AUDIBLE |"});
        step(out, 19, "Phone result report displayed",
                events, new String[]{" | SYSTEM_CHECK_REPORT_DISPLAYED |"},
                new String[]{});
        return out.toString();
    }

    private static void step(
            StringBuilder out,
            int number,
            String label,
            String events,
            String[] passTokens,
            String[] failTokens
    ) {
        int pass = lastIndexOfAny(events, passTokens);
        int fail = lastIndexOfAny(events, failTokens);
        String state = pass < 0 && fail < 0 ? "WAITING"
                : pass > fail ? "PASS" : "FAIL";
        out.append(number).append(". ").append(label).append(": ").append(state).append('\n');
    }

    private static int lastIndexOfAny(String text, String[] tokens) {
        int latest = -1;
        for (String token : tokens) latest = Math.max(latest, text.lastIndexOf(token));
        return latest;
    }

    static void clear(Context context) {
        synchronized (LOCK) {
            File file = new File(context.getFilesDir(), FILE_NAME);
            if (file.isFile()) file.delete();
        }
    }

    private static String sanitize(String value, int maxLength) {
        if (value == null) return "";
        String cleaned = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (cleaned.length() > maxLength) cleaned = cleaned.substring(0, maxLength);
        return cleaned.replaceAll("(?i)(node|session|message|cue)Id=[^ ;,|]+", "$1Id=[redacted]");
    }

    private static PackageInfo packageInfo(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        } catch (Exception ignored) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo packageInfo) {
        if (packageInfo == null) return 0L;
        if (Build.VERSION.SDK_INT >= 28) return packageInfo.getLongVersionCode();
        return packageInfo.versionCode;
    }
}
