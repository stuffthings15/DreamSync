package com.nathanw.somnicue;

import android.content.Intent;
import android.os.SystemClock;

import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import com.nathanw.somnicue.shared.ProtocolEnvelope;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PhoneDataListenerService extends WearableListenerService {
    static final String ACTION_SUMMARY_CHANGED = "com.nathanw.somnicue.SUMMARY_CHANGED";
    static final String ACTION_STATUS_CHANGED = "com.nathanw.somnicue.SESSION_STATUS_CHANGED";

    private final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor();

    @Override public void onDataChanged(DataEventBuffer events) {
        try {
            for (DataEvent event : events) {
                if (event.getType() != DataEvent.TYPE_CHANGED) continue;
                String path = event.getDataItem().getUri().getPath();
                DataMap data = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                byte[] encoded = data.getByteArray("envelope");
                if (encoded == null) continue;
                ProtocolEnvelope envelope;
                try { envelope = ProtocolEnvelope.decode(encoded); }
                catch (IllegalArgumentException ignored) { continue; }
                if (!pref("local_phone_node").equals(envelope.targetDeviceId)) continue;
                if (path.startsWith(com.nathanw.somnicue.shared.Protocol.PATH_STATE_PREFIX)) {
                    handleState(envelope);
                } else if (path.startsWith(com.nathanw.somnicue.shared.Protocol.PATH_SUMMARY_PREFIX)) {
                    handleSummary(envelope, data.getAsset("sessionLog"));
                }
            }
        } finally {
            events.release();
        }
    }

    @Override public void onMessageReceived(MessageEvent event) {
        // Cue acknowledgements flow watchward. Durable state and summaries use DataItems.
    }

    private void handleState(ProtocolEnvelope envelope) {
        if (!com.nathanw.somnicue.shared.Protocol.TYPE_CONFIG_ACK.equals(envelope.messageType)
                && !com.nathanw.somnicue.shared.Protocol.TYPE_HEARTBEAT.equals(envelope.messageType)) return;
        String status = envelope.payload.getOrDefault("status", "Watch acknowledged session");
        databaseExecutor.execute(() -> {
            SessionEntity session = PhoneDatabase.get(this).dao().session(envelope.sessionId);
            if (session != null) {
                session.state = envelope.payload.getOrDefault("state", "ARMED");
                PhoneDatabase.get(this).dao().upsertSession(session);
            }
            EventEntity event = new EventEntity();
            event.sessionId = envelope.sessionId;
            event.type = envelope.messageType;
            event.monotonicMs = SystemClock.elapsedRealtime();
            event.utcMs = System.currentTimeMillis();
            event.details = "state=" + envelope.payload.getOrDefault("state", "")
                    + ";signalQuality=" + envelope.payload.getOrDefault("signalQuality", "")
                    + ";batteryPercent=" + envelope.payload.getOrDefault("batteryPercent", "");
            PhoneDatabase.get(this).dao().insertEvent(event);
        });
        sendBroadcast(new Intent(ACTION_STATUS_CHANGED).setPackage(getPackageName())
                .putExtra("status", status));
    }

    private void handleSummary(ProtocolEnvelope envelope, Asset logAsset) {
        String summary = envelope.payload.getOrDefault("summary", "{}");
        getSharedPreferences("somnicue_phone", MODE_PRIVATE).edit()
                .putString("last_summary", summary)
                .putString("last_summary_session", envelope.sessionId)
                .apply();
        stopService(new Intent(this, CuePlaybackService.class));
        databaseExecutor.execute(() -> {
            PhoneDao dao = PhoneDatabase.get(this).dao();
            SessionEntity session = dao.session(envelope.sessionId);
            if (session == null) {
                session = new SessionEntity();
                session.sessionId = envelope.sessionId;
            }
            session.summaryJson = summary;
            session.state = "COMPLETE";
            session.endUtcMs = System.currentTimeMillis();
            try {
                org.json.JSONObject json = new org.json.JSONObject(summary);
                session.watchLogPath = json.optString("log", "");
            } catch (org.json.JSONException ignored) {}
            dao.upsertSession(session);
            EventEntity received = new EventEntity();
            received.sessionId = envelope.sessionId;
            received.type = "SUMMARY_RECEIVED";
            received.monotonicMs = SystemClock.elapsedRealtime();
            received.utcMs = System.currentTimeMillis();
            received.details = "durable DataItem";
            dao.insertEvent(received);
        });
        if (logAsset != null) receiveLog(envelope.sessionId, logAsset);
        sendBroadcast(new Intent(ACTION_SUMMARY_CHANGED).setPackage(getPackageName())
                .putExtra("summary", summary)
                .putExtra("sessionId", envelope.sessionId));
    }

    private void receiveLog(String sessionId, Asset asset) {
        Wearable.getDataClient(this).getFdForAsset(asset)
                .addOnSuccessListener(response -> databaseExecutor.execute(() -> {
                    File directory = new File(getFilesDir(), "sessions");
                    if (!directory.exists() && !directory.mkdirs()) {
                        response.release();
                        return;
                    }
                    File target = new File(directory, "session-" + sessionId + ".csv");
                    try (InputStream input = response.getInputStream();
                         FileOutputStream output = new FileOutputStream(target)) {
                        byte[] buffer = new byte[16 * 1024];
                        int read;
                        while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
                        EventEntity event = new EventEntity();
                        event.sessionId = sessionId;
                        event.type = "WATCH_LOG_RECEIVED";
                        event.monotonicMs = SystemClock.elapsedRealtime();
                        event.utcMs = System.currentTimeMillis();
                        event.details = target.getAbsolutePath();
                        PhoneDatabase.get(this).dao().insertEvent(event);
                    } catch (Exception ignored) {
                    } finally {
                        response.release();
                    }
                }));
    }

    private String pref(String key) {
        return getSharedPreferences("somnicue_phone", MODE_PRIVATE).getString(key, "");
    }

    @Override public void onDestroy() {
        databaseExecutor.shutdown();
        super.onDestroy();
    }
}
