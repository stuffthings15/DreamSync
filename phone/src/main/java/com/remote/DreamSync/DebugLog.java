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
    private static final long MAX_FILE_BYTES = 512L * 1024L;
    private static final Object LOCK = new Object();

    private DebugLog() {}

    static void append(Context context, String event, String details) {
        if (context == null) return;
        String safeEvent = sanitize(event, 80);
        String safeDetails = sanitize(details, 1_500);
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
                            + " | LOG_ROTATED | Previous log exceeded 512 KiB\n")
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
        StringBuilder text = new StringBuilder(4_096);
        text.append("DreamSync phone debug log\n")
                .append("generatedUtcMs=").append(System.currentTimeMillis()).append('\n')
                .append("appVersion=").append(packageInfo == null ? "unknown" : packageInfo.versionName).append('\n')
                .append("versionCode=").append(versionCode(packageInfo)).append('\n')
                .append("androidSdk=").append(Build.VERSION.SDK_INT).append('\n')
                .append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                .append("autoEnabled=").append(prefs.getBoolean("auto_enabled", false)).append('\n')
                .append("watchBound=").append(!prefs.getString("bound_watch_node", "").isEmpty()).append('\n')
                .append("watchConfirmed=").append(prefs.getBoolean("watch_auto_confirmed", false)).append('\n')
                .append("watchInstallPending=").append(prefs.getBoolean("watch_install_pending", false)).append('\n')
                .append("activeSession=").append(!prefs.getString("active_session_id", "").isEmpty()).append('\n')
                .append("lastCalibrationUtcMs=").append(prefs.getLong("last_calibration_utc", 0L)).append('\n')
                .append("lastAudioError=").append(sanitize(
                        prefs.getString("last_audio_error", ""), 1_500)).append('\n')
                .append("lastAudioErrorUtcMs=").append(prefs.getLong("last_audio_error_utc", 0L)).append('\n')
                .append("audio=").append(audioOutput.diagnostics()).append('\n')
                .append("\n--- app events ---\n");
        synchronized (LOCK) {
            File file = new File(context.getFilesDir(), FILE_NAME);
            if (!file.isFile()) return text.append("No app events recorded.\n").toString();
            try {
                text.append(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            } catch (Exception error) {
                text.append("Could not read app events: ")
                        .append(error.getClass().getSimpleName()).append('\n');
            }
        }
        return text.toString();
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
