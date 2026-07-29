package com.remote.DreamSync;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;

import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.remote.DreamSync.shared.ProtocolEnvelope;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Durable, bounded watch-side trace mirrored to the bound phone. */
final class WatchDiagnostics {
    private static final String FILE_NAME = "dreamsync-watch-debug.log";
    private static final long MAX_FILE_BYTES = 1024L * 1024L;
    private static final Object LOCK = new Object();
    private static final AtomicLong SEQUENCE = new AtomicLong(System.currentTimeMillis());

    static void auto(Context context, AutoSleepConfig config, String event, String details) {
        if (config == null) return;
        publish(context, config.enrollmentId, config.watchNodeId, config.phoneNodeId,
                event, details);
    }

    static void session(Context context, SessionConfig config, String event, String details) {
        if (config == null) return;
        publish(context, config.sessionId, config.watchNodeId, config.phoneNodeId,
                event, details);
    }

    static void local(Context context, String event, String details) {
        appendLocal(context, event, details);
    }

    private static void publish(
            Context input,
            String traceId,
            String watchNodeId,
            String phoneNodeId,
            String event,
            String details
    ) {
        Context context = input.getApplicationContext();
        String safeEvent = sanitize(event, 80);
        String safeDetails = sanitize(details, 8_000);
        appendLocal(context, safeEvent, safeDetails);
        if (traceId == null || traceId.isEmpty()
                || watchNodeId == null || watchNodeId.isEmpty()
                || phoneNodeId == null || phoneNodeId.isEmpty()) return;

        long sequence = SEQUENCE.incrementAndGet();
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("event", safeEvent);
        payload.put("details", safeDetails);
        payload.put("sequence", Long.toString(sequence));
        payload.put("watchUtcMs", Long.toString(System.currentTimeMillis()));
        payload.put("watchElapsedMs", Long.toString(SystemClock.elapsedRealtime()));
        payload.put("watchDevice", Build.MANUFACTURER + " " + Build.MODEL);
        payload.put("watchSdk", Integer.toString(Build.VERSION.SDK_INT));
        ProtocolEnvelope envelope = ProtocolEnvelope.create(
                com.remote.DreamSync.shared.Protocol.TYPE_DIAGNOSTIC,
                traceId, "", watchNodeId, phoneNodeId,
                System.currentTimeMillis(),
                com.remote.DreamSync.shared.Protocol.DIAGNOSTIC_TTL_MS,
                payload);
        String path = com.remote.DreamSync.shared.Protocol.PATH_DIAGNOSTIC_PREFIX + traceId;
        if (safeEvent.startsWith("SYSTEM_CHECK_")) path += "/" + sequence;
        PutDataMapRequest map = PutDataMapRequest.create(path);
        map.getDataMap().putByteArray("envelope", envelope.encode());
        map.getDataMap().putLong("sequence", sequence);
        PutDataRequest request = map.asPutDataRequest();
        request.setUrgent();
        Wearable.getDataClient(context).putDataItem(request);
    }

    private static void appendLocal(Context context, String event, String details) {
        if (context == null) return;
        String line = System.currentTimeMillis()
                + " | elapsed=" + SystemClock.elapsedRealtime()
                + " | " + sanitize(event, 80)
                + " | " + sanitize(details, 8_000) + "\n";
        synchronized (LOCK) {
            File file = new File(context.getFilesDir(), FILE_NAME);
            boolean append = file.length() < MAX_FILE_BYTES;
            try (FileOutputStream output = new FileOutputStream(file, append)) {
                if (!append) {
                    output.write((System.currentTimeMillis()
                            + " | WATCH_LOG_ROTATED | Previous log exceeded 1 MiB\n")
                            .getBytes(StandardCharsets.UTF_8));
                }
                output.write(line.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // Diagnostics never interrupt sensing or cue safety.
            }
        }
    }

    private static String sanitize(String value, int maximum) {
        if (value == null) return "";
        String cleaned = value.replace('\r', ' ').replace('\n', ' ').trim();
        return cleaned.substring(0, Math.min(maximum, cleaned.length()));
    }

    static String sanitizeSnapshot(String value, int maximum) {
        if (value == null || maximum <= 0) return "";
        String cleaned = value
                .replaceAll("(?i)(node|session|message|cue)Id=[^ ;,|\\r\\n]+",
                        "$1Id=[redacted]")
                .replaceAll("(?i)rawPpg=[^ ;,|\\r\\n]+", "rawPpg=[excluded]");
        if (cleaned.length() > maximum) {
            cleaned = cleaned.substring(0, maximum);
        }
        return cleaned;
    }

    static String snapshot(Context context) {
        String events = "";
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (file.isFile()) {
            try {
                events = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            } catch (Exception error) {
                events = "WATCH_SNAPSHOT_READ_FAILED | "
                        + error.getClass().getSimpleName() + "\n";
            }
        }
        PackageInfo info = null;
        try {
            info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        } catch (Exception ignored) {
            // The snapshot remains useful without package metadata.
        }
        BatteryManager battery = context.getSystemService(BatteryManager.class);
        PowerManager power = context.getSystemService(PowerManager.class);
        AutoSleepConfig auto = AutoSleepConfig.load(context);
        String header = "generatedUtcMs=" + System.currentTimeMillis()
                + "\nwatchVersion=" + (info == null ? "unknown" : info.versionName)
                + "\nversionCode=" + versionCode(info)
                + "\nwatchDevice=" + Build.MANUFACTURER + " " + Build.MODEL
                + "\nwatchSdk=" + Build.VERSION.SDK_INT
                + "\nbatteryPercent=" + (battery == null ? -1
                : battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                + "\npowerSaving=" + (power != null && power.isPowerSaveMode())
                + "\nsensorsOff=" + Settings.Global.getInt(
                context.getContentResolver(), "sensors_off", 0)
                + "\nautoEnabled=" + auto.enabled
                + "\npassiveRegistered=" + auto.passiveRegistered
                + "\nrawPpg=excluded\n\n--- bounded watch events ---\n";
        return sanitizeSnapshot(header + events, 24_000);
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo info) {
        if (info == null) return 0L;
        if (Build.VERSION.SDK_INT >= 28) return info.getLongVersionCode();
        return info.versionCode;
    }

    private WatchDiagnostics() {}
}
