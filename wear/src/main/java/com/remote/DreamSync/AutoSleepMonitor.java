package com.remote.DreamSync;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import androidx.health.services.client.HealthServices;
import androidx.health.services.client.PassiveMonitoringClient;
import androidx.health.services.client.data.DataType;
import androidx.health.services.client.data.PassiveListenerConfig;
import androidx.health.services.client.data.UserActivityState;

import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.common.util.concurrent.ListenableFuture;
import com.remote.DreamSync.shared.ProtocolEnvelope;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

final class AutoSleepMonitor {
    private static final Executor DIRECT = Runnable::run;
    private static final String FEEDBACK_PREFS = "dreamsync_watch_feedback";

    interface SensorAccessCallback {
        void onReady(boolean passivePaused, String detail);
    }

    static void pauseForSensorAccess(
            Context input,
            AutoSleepConfig config,
            String reason,
            SensorAccessCallback callback
    ) {
        Context context = input.getApplicationContext();
        if (!config.passiveRegistered) {
            WatchDiagnostics.auto(context, config, "PASSIVE_SENSOR_ACCESS_NOT_REQUIRED",
                    "reason=" + reason + ";passiveRegistered=false");
            callback.onReady(true, "passive listener was not registered");
            return;
        }
        WatchDiagnostics.auto(context, config, "PASSIVE_SENSOR_ACCESS_PAUSE_REQUESTED",
                "reason=" + reason);
        PassiveMonitoringClient client = HealthServices.getClient(context)
                .getPassiveMonitoringClient();
        ListenableFuture<Void> clear = client.clearPassiveListenerServiceAsync();
        clear.addListener(() -> {
            boolean paused = false;
            String detail = "success=true";
            try {
                clear.get();
                paused = true;
                config.passiveRegistered = false;
                config.save(context);
            } catch (Exception error) {
                detail = "success=false;error=" + safeMessage(error);
            }
            WatchDiagnostics.auto(context, config,
                    paused ? "PASSIVE_SENSOR_ACCESS_PAUSED"
                            : "PASSIVE_SENSOR_ACCESS_PAUSE_FAILED",
                    "reason=" + reason + ";" + detail);
            callback.onReady(paused, detail);
        }, DIRECT);
    }

    static void resumeAfterSensorAccess(
            Context input,
            AutoSleepConfig config,
            String reason
    ) {
        Context context = input.getApplicationContext();
        if (!config.enabled) return;
        WatchDiagnostics.auto(context, config, "PASSIVE_SENSOR_ACCESS_RESUME_REQUESTED",
                "reason=" + reason);
        enable(context, config);
    }

    static void enable(Context input, AutoSleepConfig config) {
        Context context = input.getApplicationContext();
        config.enabled = true;
        config.status = "Checking automatic sleep support...";
        config.save(context);
        WatchDiagnostics.auto(context, config, "AUTO_MONITOR_ENABLE_REQUESTED",
                "Checking Health Services passive asleep-state and heart-rate support");
        broadcast(context, config.status);

        PassiveMonitoringClient client = HealthServices.getClient(context)
                .getPassiveMonitoringClient();
        ListenableFuture<androidx.health.services.client.data.PassiveMonitoringCapabilities>
                capabilities = client.getCapabilitiesAsync();
        capabilities.addListener(() -> {
            try {
                boolean asleepSupported = capabilities.get().getSupportedUserActivityStates()
                        .contains(UserActivityState.USER_ACTIVITY_ASLEEP);
                boolean passiveHeartRateSupported = capabilities.get()
                        .getSupportedDataTypesPassiveMonitoring()
                        .contains(DataType.HEART_RATE_BPM);
                WatchDiagnostics.auto(context, config, "PASSIVE_CAPABILITIES_CHECKED",
                        "asleepStateSupported=" + asleepSupported
                                + ";passiveHeartRateSupported=" + passiveHeartRateSupported);
                if (!asleepSupported && !passiveHeartRateSupported) {
                    fail(context, config,
                            "On, but this watch provides neither automatic asleep-state nor passive heart-rate signals.");
                    return;
                }
                PassiveListenerConfig.Builder listenerBuilder =
                        new PassiveListenerConfig.Builder()
                                .setShouldUserActivityInfoBeRequested(asleepSupported);
                if (passiveHeartRateSupported) {
                    listenerBuilder.setDataTypes(
                            Collections.singleton(DataType.HEART_RATE_BPM));
                }
                PassiveListenerConfig listenerConfig = listenerBuilder.build();
                ListenableFuture<Void> registration = client.setPassiveListenerServiceAsync(
                        AutoSleepPassiveService.class, listenerConfig);
                registration.addListener(() -> {
                    try {
                        registration.get();
                        config.passiveRegistered = true;
                        config.asleepStateSupported = asleepSupported;
                        config.passiveHeartRateSupported = passiveHeartRateSupported;
                        config.status = asleepSupported
                                ? "On - automatic asleep-state monitoring is active every night."
                                : "On - broad Wear OS heart-rate fallback is active every night.";
                        config.save(context);
                        WatchDiagnostics.auto(context, config, "PASSIVE_MONITORING_READY",
                                "registered=true;asleepStateSupported=" + asleepSupported
                                        + ";passiveHeartRateSupported="
                                        + passiveHeartRateSupported
                                        + ";hapticsDuringSleep=OFF"
                                        + ";audioOutput=WATCH_SPEAKER_PRIMARY_PHONE_FALLBACK");
                        confirmEnabledHaptic(context, config);
                        publishState(context, config, "ON", config.status);
                        broadcast(context, config.status);
                    } catch (Exception error) {
                        fail(context, config, "Automatic sleep monitoring could not start: "
                                + safeMessage(error));
                    }
                }, DIRECT);
            } catch (Exception error) {
                fail(context, config, "Automatic sleep support check failed: "
                        + safeMessage(error));
            }
        }, DIRECT);
    }

    static void disable(Context input, boolean notifyPhone) {
        Context context = input.getApplicationContext();
        AutoSleepConfig config = AutoSleepConfig.load(context);
        WatchDiagnostics.auto(context, config, "AUTO_MONITOR_DISABLE_REQUESTED",
                "notifyPhone=" + notifyPhone);
        PassiveMonitoringClient client = HealthServices.getClient(context)
                .getPassiveMonitoringClient();
        ListenableFuture<Void> clear = client.clearPassiveListenerServiceAsync();
        clear.addListener(() -> {
            try { clear.get(); } catch (Exception ignored) {}
            if (notifyPhone && config.validBinding()) {
                publishState(context, config, "OFF", "DreamSync is Off on the watch.");
            }
            context.getSharedPreferences(FEEDBACK_PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean("enabled_haptic_sent", false).apply();
            AutoSleepConfig.clear(context);
            broadcast(context, "DreamSync is Off.");
        }, DIRECT);
    }

    static boolean restoreAfterBoot(Context input, AutoSleepConfig config) {
        Context context = input.getApplicationContext();
        config.status = "On - restoring automatic sleep monitoring after restart...";
        config.save(context);
        WatchDiagnostics.auto(context, config, "PASSIVE_RESTORE_WORK_STARTED",
                "worker=true;receiverRegistration=false");
        try {
            PassiveMonitoringClient client = HealthServices.getClient(context)
                    .getPassiveMonitoringClient();
            androidx.health.services.client.data.PassiveMonitoringCapabilities capabilities =
                    client.getCapabilitiesAsync().get(45, TimeUnit.SECONDS);
            boolean asleepSupported = capabilities.getSupportedUserActivityStates()
                    .contains(UserActivityState.USER_ACTIVITY_ASLEEP);
            boolean passiveHeartRateSupported = capabilities
                    .getSupportedDataTypesPassiveMonitoring()
                    .contains(DataType.HEART_RATE_BPM);
            if (!asleepSupported && !passiveHeartRateSupported) {
                fail(context, config,
                        "On, but this watch provides neither automatic asleep-state nor passive heart-rate signals.");
                return true;
            }
            PassiveListenerConfig.Builder builder = new PassiveListenerConfig.Builder()
                    .setShouldUserActivityInfoBeRequested(asleepSupported);
            if (passiveHeartRateSupported) {
                builder.setDataTypes(Collections.singleton(DataType.HEART_RATE_BPM));
            }
            client.setPassiveListenerServiceAsync(
                    AutoSleepPassiveService.class, builder.build())
                    .get(45, TimeUnit.SECONDS);
            config.passiveRegistered = true;
            config.asleepStateSupported = asleepSupported;
            config.passiveHeartRateSupported = passiveHeartRateSupported;
            config.status = asleepSupported
                    ? "On - automatic asleep-state monitoring restored after restart."
                    : "On - Wear OS heart-rate fallback restored after restart.";
            config.save(context);
            WatchDiagnostics.auto(context, config, "PASSIVE_RESTORE_WORK_COMPLETE",
                    "registered=true;asleepStateSupported=" + asleepSupported
                            + ";passiveHeartRateSupported=" + passiveHeartRateSupported);
            publishState(context, config, "ON", config.status);
            broadcast(context, config.status);
            return true;
        } catch (Exception error) {
            config.passiveRegistered = false;
            config.status = "On - automatic monitoring restore will retry: "
                    + safeMessage(error);
            config.save(context);
            WatchDiagnostics.auto(context, config, "PASSIVE_RESTORE_WORK_RETRY",
                    safeMessage(error));
            broadcast(context, config.status);
            return false;
        }
    }

    static void permissionLost(Context input) {
        Context context = input.getApplicationContext();
        AutoSleepConfig config = AutoSleepConfig.load(context);
        config.passiveRegistered = false;
        config.status = "On, but watch health permission was removed. Open DreamSync to repair it.";
        config.save(context);
        WatchDiagnostics.auto(context, config, "HEALTH_PERMISSION_LOST", config.status);
        publishState(context, config, "PERMISSION_LOST", config.status);
        broadcast(context, config.status);
    }

    static void publishState(
            Context context,
            AutoSleepConfig config,
            String state,
            String status
    ) {
        if (config.enrollmentId.isEmpty() || config.phoneNodeId.isEmpty()
                || config.watchNodeId.isEmpty()) return;
        ProtocolEnvelope envelope = ProtocolEnvelope.create(
                com.remote.DreamSync.shared.Protocol.TYPE_AUTO_CONFIG_ACK,
                config.enrollmentId, "", config.watchNodeId, config.phoneNodeId,
                System.currentTimeMillis(),
                com.remote.DreamSync.shared.Protocol.AUTO_CONFIG_TTL_MS,
                Map.of("state", state, "status", status));
        PutDataMapRequest map = PutDataMapRequest.create(
                com.remote.DreamSync.shared.Protocol.PATH_AUTO_STATE_PREFIX
                        + config.enrollmentId);
        map.getDataMap().putByteArray("envelope", envelope.encode());
        PutDataRequest request = map.asPutDataRequest();
        request.setUrgent();
        Wearable.getDataClient(context).putDataItem(request);
    }

    private static void fail(Context context, AutoSleepConfig config, String status) {
        config.passiveRegistered = false;
        config.status = status;
        config.save(context);
        WatchDiagnostics.auto(context, config, "PASSIVE_MONITORING_FAILED", status);
        publishState(context, config, "UNAVAILABLE", status);
        broadcast(context, status);
    }

    private static void broadcast(Context context, String status) {
        context.sendBroadcast(new Intent(MainActivity.ACTION_AUTO_STATUS)
                .setPackage(context.getPackageName())
                .putExtra("status", status));
    }

    private static void confirmEnabledHaptic(Context context, AutoSleepConfig config) {
        android.content.SharedPreferences feedback = context.getSharedPreferences(
                FEEDBACK_PREFS, Context.MODE_PRIVATE);
        if (feedback.getBoolean("enabled_haptic_sent", false)) return;
        Vibrator vibrator;
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager manager = (VibratorManager) context.getSystemService(
                    Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = manager == null ? null : manager.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }
        if (vibrator == null || !vibrator.hasVibrator()) {
            WatchDiagnostics.auto(context, config, "SETUP_HAPTIC_UNAVAILABLE",
                    "watchHasVibrator=false;hapticsDuringSleep=OFF");
            return;
        }
        vibrator.vibrate(VibrationEffect.createOneShot(
                180L, VibrationEffect.DEFAULT_AMPLITUDE));
        feedback.edit().putBoolean("enabled_haptic_sent", true).apply();
        WatchDiagnostics.auto(context, config, "SETUP_HAPTIC_CONFIRMED",
                "awakeSetupOnly=true;durationMs=180;hapticsDuringSleep=OFF");
    }

    private static String safeMessage(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName() : message;
    }

    private AutoSleepMonitor() {}
}
