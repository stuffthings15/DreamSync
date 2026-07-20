package com.remote.DreamSync;

import android.content.Intent;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import com.remote.DreamSync.shared.MessageGuard;
import com.remote.DreamSync.shared.ProtocolEnvelope;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WearCommandListenerService extends WearableListenerService {
    private final MessageGuard guard = new MessageGuard();
    private final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor();

    @Override public void onDataChanged(DataEventBuffer events) {
        try {
            for (DataEvent event : events) {
                if (event.getType() != DataEvent.TYPE_CHANGED) continue;
                String path = event.getDataItem().getUri().getPath();
                boolean deleteRequest = path.startsWith(
                        com.remote.DreamSync.shared.Protocol.PATH_DELETE_DATA_PREFIX);
                if (!path.startsWith(com.remote.DreamSync.shared.Protocol.PATH_CONFIG_PREFIX)
                        && !com.remote.DreamSync.shared.Protocol.PATH_AUTO_CONFIG.equals(path)
                        && !deleteRequest) {
                    continue;
                }
                DataMap map = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                byte[] bytes = map.getByteArray("envelope");
                if (bytes == null) continue;
                ProtocolEnvelope envelope;
                try { envelope = ProtocolEnvelope.decode(bytes); }
                catch (IllegalArgumentException ignored) { continue; }
                String sourceNodeId = event.getDataItem().getUri().getHost();
                if (sourceNodeId == null || sourceNodeId.isEmpty()) continue;
                Wearable.getNodeClient(this).getLocalNode().addOnSuccessListener(node -> {
                    if (deleteRequest) {
                        applyDeleteRequest(envelope, node.getId(), sourceNodeId);
                    } else if (com.remote.DreamSync.shared.Protocol.PATH_AUTO_CONFIG.equals(path)) {
                        applyAutoConfiguration(envelope, node.getId(), sourceNodeId);
                    } else {
                        applySessionConfiguration(envelope, node.getId(), sourceNodeId);
                    }
                });
            }
        } finally {
            events.release();
        }
    }

    private void applyDeleteRequest(
            ProtocolEnvelope envelope,
            String localNodeId,
            String sourceNodeId
    ) {
        if (!com.remote.DreamSync.shared.Protocol.TYPE_DELETE_DATA.equals(
                envelope.messageType)) return;
        AutoSleepConfig config = AutoSleepConfig.load(this);
        if (config.enabled || SleepSessionService.isRunning() || !config.validBinding()) return;
        MessageGuard.Result result = guard.check(
                envelope, config.enrollmentId, localNodeId,
                sourceNodeId, System.currentTimeMillis());
        if (result != MessageGuard.Result.ACCEPT) return;
        databaseExecutor.execute(() -> {
            WearDao dao = WearDatabase.get(this).dao();
            dao.deleteAllEvents();
            dao.deleteAllSessions();
            dao.deleteAllBaselines();
            SessionConfig.clear(this);
            AutoSleepConfig.clear(this);
            deleteSessionLogs(getExternalFilesDir("sessions"));
            deleteSessionLogs(new File(getFilesDir(), "sessions"));
            sendBroadcast(new Intent(MainActivity.ACTION_CONFIG_CHANGED)
                    .setPackage(getPackageName())
                    .putExtra("status", "Watch data deleted from the phone controls."));
        });
    }

    private static void deleteSessionLogs(File directory) {
        if (directory == null) return;
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith("dreamsync-")) file.delete();
        }
    }

    @Override public void onMessageReceived(MessageEvent event) {
        ProtocolEnvelope envelope;
        try { envelope = ProtocolEnvelope.decode(event.getData()); }
        catch (IllegalArgumentException ignored) { return; }
        SessionConfig config = SessionConfig.load(this);
        if (!config.phoneNodeId.equals(event.getSourceNodeId())) return;
        MessageGuard.Result result = guard.check(
                envelope, config.sessionId, config.watchNodeId,
                config.phoneNodeId, System.currentTimeMillis());
        if (result != MessageGuard.Result.ACCEPT) return;
        if (Protocol.PATH_STOP.equals(event.getPath())
                && com.remote.DreamSync.shared.Protocol.TYPE_STOP.equals(envelope.messageType)) {
            SessionConfig.clear(this);
            startService(new Intent(this, SleepSessionService.class)
                    .setAction(SleepSessionService.ACTION_STOP)
                    .putExtra("terminationReason", "USER_STOPPED_CURRENT_SESSION"));
        } else if (Protocol.PATH_ACK.equals(event.getPath())) {
            sendBroadcast(new Intent(SleepSessionService.ACTION_CUE_ACK)
                    .setPackage(getPackageName())
                    .putExtra("sessionId", envelope.sessionId)
                    .putExtra("cueId", envelope.cueId)
                    .putExtra("ackType", envelope.messageType)
                    .putExtra("reason", envelope.payload.getOrDefault("reason", "")));
        }
    }

    private void applyAutoConfiguration(
            ProtocolEnvelope envelope,
            String localNodeId,
            String sourceNodeId
    ) {
        if (!com.remote.DreamSync.shared.Protocol.TYPE_AUTO_CONFIG.equals(envelope.messageType)
                && !com.remote.DreamSync.shared.Protocol.TYPE_AUTO_OFF.equals(
                envelope.messageType)) return;
        MessageGuard.Result result = guard.check(
                envelope, "", localNodeId, sourceNodeId, System.currentTimeMillis());
        if (result != MessageGuard.Result.ACCEPT) return;
        AutoSleepConfig current = AutoSleepConfig.load(this);
        if (com.remote.DreamSync.shared.Protocol.TYPE_AUTO_OFF.equals(envelope.messageType)) {
            if (!current.enrollmentId.isEmpty()
                    && !current.enrollmentId.equals(envelope.sessionId)) return;
            if (SleepSessionService.isRunning()) {
                startService(new Intent(this, SleepSessionService.class)
                        .setAction(SleepSessionService.ACTION_STOP)
                        .putExtra("terminationReason", "USER_TURNED_OFF"));
            }
            AutoSleepMonitor.disable(this, true);
            return;
        }
        String json = envelope.payload.get("config");
        if (json == null || json.isEmpty()) return;
        AutoSleepConfig config = AutoSleepConfig.fromJson(json);
        if (!config.enabled || !envelope.sessionId.equals(config.enrollmentId)
                || !sourceNodeId.equals(config.phoneNodeId)
                || !localNodeId.equals(config.watchNodeId)) return;
        if (current.enrollmentId.equals(config.enrollmentId)) {
            config.lastSessionUtcMs = current.lastSessionUtcMs;
        }
        config.save(this);
        AutoSleepMonitor.enable(this, config);
        sendBroadcast(new Intent(MainActivity.ACTION_CONFIG_CHANGED)
                .setPackage(getPackageName())
                .putExtra("status", "Phone setup received. Approve watch access once."));
    }

    private void applySessionConfiguration(
            ProtocolEnvelope envelope,
            String localNodeId,
            String sourceNodeId
    ) {
        if (!com.remote.DreamSync.shared.Protocol.TYPE_CONFIG.equals(envelope.messageType)) return;
        MessageGuard.Result result = guard.check(
                envelope, "", localNodeId, sourceNodeId, System.currentTimeMillis());
        if (result != MessageGuard.Result.ACCEPT) return;
        AutoSleepConfig auto = AutoSleepConfig.load(this);
        if (!auto.validBinding() || !auto.phoneNodeId.equals(sourceNodeId)
                || !"true".equals(envelope.payload.get("automatic"))) return;
        String json = envelope.payload.get("config");
        if (json == null || json.isEmpty()) return;
        SessionConfig config = SessionConfig.fromJson(json);
        if (!envelope.sessionId.equals(config.sessionId)
                || !envelope.senderDeviceId.equals(config.phoneNodeId)
                || !localNodeId.equals(config.watchNodeId)
                || !config.isCurrent(System.currentTimeMillis())) return;
        if (SleepSessionService.isRunning()) return;
        SessionConfig current = SessionConfig.load(this);
        if (current.isCurrent(System.currentTimeMillis())
                && !current.sessionId.equals(config.sessionId)
                && current.createdUtcMs >= config.createdUtcMs) return;
        config.handshakeMessageId = envelope.messageId;
        config.save(this);
        databaseExecutor.execute(() -> {
            WearSessionEntity session = new WearSessionEntity();
            session.sessionId = config.sessionId;
            session.mode = config.mode;
            session.state = "AUTO_STARTING";
            session.gain = config.cueGain;
            WearDatabase.get(this).dao().upsertSession(session);
            WearEventEntity event = new WearEventEntity();
            event.sessionId = config.sessionId;
            event.type = "AUTO_CONFIG_ACK";
            event.elapsedMs = SystemClock.elapsedRealtime();
            event.utcMs = System.currentTimeMillis();
            event.details = "protocol v" + envelope.protocolVersion;
            WearDatabase.get(this).dao().insertEvent(event);
            publishState(config, "AUTO_STARTING",
                    "Sleep detected. Starting conservative signal confirmation.");
            startAutomaticSession(config);
        });
    }

    private void startAutomaticSession(SessionConfig config) {
        Intent start = new Intent(this, SleepSessionService.class)
                .setAction(SleepSessionService.ACTION_START)
                .putExtra(SleepSessionService.EXTRA_START_SESSION_ID, config.sessionId)
                .putExtra(SleepSessionService.EXTRA_HANDSHAKE_MESSAGE_ID,
                        config.handshakeMessageId);
        try {
            ContextCompat.startForegroundService(this, start);
            sendBroadcast(new Intent(MainActivity.ACTION_CONFIG_CHANGED)
                    .setPackage(getPackageName())
                    .putExtra("status", "Sleep detected. Confirming signals before any cue."));
        } catch (RuntimeException error) {
            publishState(config, "AUTO_START_FAILED",
                    "Android blocked automatic sensor confirmation. Open DreamSync on the watch.");
        }
    }

    private void publishState(SessionConfig config, String state, String status) {
        ProtocolEnvelope ack = ProtocolEnvelope.create(
                com.remote.DreamSync.shared.Protocol.TYPE_CONFIG_ACK,
                config.sessionId, "", config.watchNodeId, config.phoneNodeId,
                System.currentTimeMillis(),
                com.remote.DreamSync.shared.Protocol.CONTROL_TTL_MS,
                Map.of("state", state, "status", status));
        PutDataMapRequest map = PutDataMapRequest.create(
                com.remote.DreamSync.shared.Protocol.PATH_STATE_PREFIX + config.sessionId);
        map.getDataMap().putByteArray("envelope", ack.encode());
        PutDataRequest request = map.asPutDataRequest();
        request.setUrgent();
        Wearable.getDataClient(this).putDataItem(request);
    }

    @Override public void onDestroy() {
        databaseExecutor.shutdown();
        super.onDestroy();
    }
}
