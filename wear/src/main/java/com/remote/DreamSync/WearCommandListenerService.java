package com.remote.DreamSync;

import android.content.Intent;
import android.os.SystemClock;

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
                if (!path.startsWith(com.remote.DreamSync.shared.Protocol.PATH_CONFIG_PREFIX)) continue;
                DataMap map = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                byte[] bytes = map.getByteArray("envelope");
                if (bytes == null) continue;
                ProtocolEnvelope envelope;
                try { envelope = ProtocolEnvelope.decode(bytes); }
                catch (IllegalArgumentException ignored) { continue; }
                Wearable.getNodeClient(this).getLocalNode()
                        .addOnSuccessListener(node -> applyConfiguration(envelope, node.getId()));
            }
        } finally {
            events.release();
        }
    }

    @Override public void onMessageReceived(MessageEvent event) {
        ProtocolEnvelope envelope;
        try { envelope = ProtocolEnvelope.decode(event.getData()); }
        catch (IllegalArgumentException ignored) { return; }
        SessionConfig config = SessionConfig.load(this);
        if (!config.phoneNodeId.equals(event.getSourceNodeId())) return;
        MessageGuard.Result result = guard.check(
                envelope, config.sessionId, config.watchNodeId, System.currentTimeMillis());
        if (result != MessageGuard.Result.ACCEPT) return;
        if (Protocol.PATH_STOP.equals(event.getPath())
                && com.remote.DreamSync.shared.Protocol.TYPE_STOP.equals(envelope.messageType)) {
            stopService(new Intent(this, SleepSessionService.class)
                    .setAction(SleepSessionService.ACTION_STOP)
                    .putExtra("terminationReason", "USER_STOPPED"));
        } else if (Protocol.PATH_ACK.equals(event.getPath())) {
            sendBroadcast(new Intent(SleepSessionService.ACTION_CUE_ACK)
                    .setPackage(getPackageName())
                    .putExtra("sessionId", envelope.sessionId)
                    .putExtra("cueId", envelope.cueId)
                    .putExtra("ackType", envelope.messageType)
                    .putExtra("reason", envelope.payload.getOrDefault("reason", "")));
        }
    }

    private void applyConfiguration(ProtocolEnvelope envelope, String localNodeId) {
        MessageGuard.Result result = guard.check(envelope, "", localNodeId, System.currentTimeMillis());
        if (result != MessageGuard.Result.ACCEPT) return;
        String json = envelope.payload.get("config");
        if (json == null || json.isEmpty()) return;
        SessionConfig config = SessionConfig.fromJson(json);
        if (!envelope.sessionId.equals(config.sessionId)
                || !envelope.senderDeviceId.equals(config.phoneNodeId)
                || !localNodeId.equals(config.watchNodeId)) return;
        config.save(this);
        databaseExecutor.execute(() -> {
            WearSessionEntity session = new WearSessionEntity();
            session.sessionId = config.sessionId;
            session.mode = config.mode;
            session.state = "ARMED";
            session.gain = config.cueGain;
            WearDatabase.get(this).dao().upsertSession(session);
            WearEventEntity event = new WearEventEntity();
            event.sessionId = config.sessionId;
            event.type = "CONFIG_ACK";
            event.elapsedMs = SystemClock.elapsedRealtime();
            event.utcMs = System.currentTimeMillis();
            event.details = "protocol v" + envelope.protocolVersion;
            WearDatabase.get(this).dao().insertEvent(event);
        });
        publishState(config, "ARMED", "Watch acknowledged settings. Tap Start night session.");
        sendBroadcast(new Intent(MainActivity.ACTION_CONFIG_CHANGED).setPackage(getPackageName()));
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
