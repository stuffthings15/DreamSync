package com.remote.DreamSync;

import android.content.Context;
import android.content.Intent;

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

final class AutoSleepMonitor {
    private static final Executor DIRECT = Runnable::run;

    static void enable(Context input, AutoSleepConfig config) {
        Context context = input.getApplicationContext();
        config.enabled = true;
        config.status = "Checking automatic sleep support...";
        config.save(context);
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
        PassiveMonitoringClient client = HealthServices.getClient(context)
                .getPassiveMonitoringClient();
        ListenableFuture<Void> clear = client.clearPassiveListenerServiceAsync();
        clear.addListener(() -> {
            try { clear.get(); } catch (Exception ignored) {}
            if (notifyPhone && config.validBinding()) {
                publishState(context, config, "OFF", "DreamSync is Off on the watch.");
            }
            AutoSleepConfig.clear(context);
            broadcast(context, "DreamSync is Off.");
        }, DIRECT);
    }

    static void permissionLost(Context input) {
        Context context = input.getApplicationContext();
        AutoSleepConfig config = AutoSleepConfig.load(context);
        config.passiveRegistered = false;
        config.status = "On, but watch health permission was removed. Open DreamSync to repair it.";
        config.save(context);
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
        publishState(context, config, "UNAVAILABLE", status);
        broadcast(context, status);
    }

    private static void broadcast(Context context, String status) {
        context.sendBroadcast(new Intent(MainActivity.ACTION_AUTO_STATUS)
                .setPackage(context.getPackageName())
                .putExtra("status", status));
    }

    private static String safeMessage(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName() : message;
    }

    private AutoSleepMonitor() {}
}
