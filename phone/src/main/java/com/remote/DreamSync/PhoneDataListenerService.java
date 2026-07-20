package com.remote.DreamSync;

import android.app.NotificationManager;
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
import com.remote.DreamSync.shared.MessageGuard;
import com.remote.DreamSync.shared.ProtocolEnvelope;
import com.remote.DreamSync.shared.SessionOutcome;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PhoneDataListenerService extends WearableListenerService {
    static final String ACTION_SUMMARY_CHANGED = "com.remote.DreamSync.SUMMARY_CHANGED";
    static final String ACTION_STATUS_CHANGED = "com.remote.DreamSync.SESSION_STATUS_CHANGED";
    private static final String PREFS = "dreamsync_phone";

    private final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor();
    private final MessageGuard messageGuard = new MessageGuard();

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
                String sourceNodeId = event.getDataItem().getUri().getHost();
                if (sourceNodeId == null || sourceNodeId.isEmpty()) continue;
                if (path.equals(com.remote.DreamSync.shared.Protocol.PATH_STATE_PREFIX
                        + envelope.sessionId)
                        && validEnvelope(envelope, sourceNodeId, false)) {
                    handleState(envelope);
                } else if (path.equals(com.remote.DreamSync.shared.Protocol.PATH_SUMMARY_PREFIX
                        + envelope.sessionId)
                        && validEnvelope(envelope, sourceNodeId, true)) {
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
        if (!com.remote.DreamSync.shared.Protocol.TYPE_CONFIG_ACK.equals(envelope.messageType)
                && !com.remote.DreamSync.shared.Protocol.TYPE_HEARTBEAT.equals(envelope.messageType)) return;
        if (!envelope.sessionId.equals(pref("active_session_id"))) return;
        if (com.remote.DreamSync.shared.Protocol.TYPE_HEARTBEAT.equals(envelope.messageType)) {
            String capabilities = envelope.payload.getOrDefault("capabilities", "{}");
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("watch_live_session_id", envelope.sessionId)
                    .putString("watch_capabilities", capabilities)
                    .apply();
        }
        String status = envelope.payload.getOrDefault(
                "status", "Watch acknowledged session");
        if (status.length() > 256) status = status.substring(0, 256);
        String state = SessionOutcome.normalizeLiveState(
                envelope.payload.getOrDefault("state", "ARMED"));
        databaseExecutor.execute(() -> {
            SessionEntity session = PhoneDatabase.get(this).dao().session(envelope.sessionId);
            if (session != null) {
                session.state = state;
                String previousCapabilities = session.watchCapabilityJson == null
                        ? "{}" : session.watchCapabilityJson;
                session.watchCapabilityJson = envelope.payload.getOrDefault(
                        "capabilities", previousCapabilities);
                PhoneDatabase.get(this).dao().upsertSession(session);
            }
            EventEntity event = new EventEntity();
            event.sessionId = envelope.sessionId;
            event.type = envelope.messageType;
            event.monotonicMs = SystemClock.elapsedRealtime();
            event.utcMs = System.currentTimeMillis();
            event.details = "state=" + envelope.payload.getOrDefault("state", "")
                    + ";signalQuality=" + envelope.payload.getOrDefault("signalQuality", "")
                    + ";batteryPercent=" + envelope.payload.getOrDefault("batteryPercent", "")
                    + ";sleepPhase=" + envelope.payload.getOrDefault("sleepPhase", "")
                    + ";awakeningCount=" + envelope.payload.getOrDefault("awakeningCount", "")
                    + ";baselineNights=" + envelope.payload.getOrDefault("baselineNights", "");
            PhoneDatabase.get(this).dao().insertEvent(event);
        });
        sendBroadcast(new Intent(ACTION_STATUS_CHANGED).setPackage(getPackageName())
                .putExtra("status", status)
                .putExtra("state", state)
                .putExtra("capabilities", envelope.payload.getOrDefault("capabilities", "{}"))
                .putExtra("sessionId", envelope.sessionId));
    }

    private void handleSummary(ProtocolEnvelope envelope, Asset logAsset) {
        String summary = envelope.payload.getOrDefault("summary", "{}");
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("last_summary", summary)
                .putString("last_summary_session", envelope.sessionId)
                .remove("active_session_id")
                .remove("pending_session_id")
                .remove("pending_arm_generation")
                .remove("cancelled_session_id")
                .remove("auto_opened_watch_session")
                .remove("watch_live_session_id")
                .remove("bound_watch_node")
                .remove("local_phone_node")
                .apply();
        stopService(new Intent(this, CuePlaybackService.class));
        restoreNotificationProtection();
        databaseExecutor.execute(() -> {
            PhoneDao dao = PhoneDatabase.get(this).dao();
            SessionEntity session = dao.session(envelope.sessionId);
            if (session == null) {
                session = new SessionEntity();
                session.sessionId = envelope.sessionId;
            }
            session.summaryJson = summary;
            session.state = com.remote.DreamSync.shared.SessionState.INTERNAL_ERROR.name();
            session.endUtcMs = System.currentTimeMillis();
            try {
                org.json.JSONObject json = new org.json.JSONObject(summary);
                session.watchLogPath = json.optString("log", "");
                session.state = SessionOutcome.normalizePersistentState(
                        json.optString("sessionState",
                                json.optString("terminalState", "")));
                session.endUtcMs = json.optLong("endUtcMs", session.endUtcMs);
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

    private boolean validEnvelope(
            ProtocolEnvelope envelope,
            String sourceNodeId,
            boolean summary
    ) {
        String active = pref("active_session_id");
        String expectedSession = active.isEmpty()
                ? pref("cancelled_session_id") : active;
        String boundWatch = pref("bound_watch_node");
        String localPhone = pref("local_phone_node");
        if (expectedSession.isEmpty() || boundWatch.isEmpty() || localPhone.isEmpty()
                || !boundWatch.equals(sourceNodeId)) return false;
        String expectedType = summary
                ? com.remote.DreamSync.shared.Protocol.TYPE_SUMMARY : "";
        if (!expectedType.isEmpty() && !expectedType.equals(envelope.messageType)) return false;
        if (!summary
                && !com.remote.DreamSync.shared.Protocol.TYPE_CONFIG_ACK.equals(envelope.messageType)
                && !com.remote.DreamSync.shared.Protocol.TYPE_HEARTBEAT.equals(envelope.messageType)) {
            return false;
        }
        return messageGuard.check(
                envelope, expectedSession, localPhone, boundWatch,
                System.currentTimeMillis()) == MessageGuard.Result.ACCEPT;
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
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(key, "");
    }

    private void restoreNotificationProtection() {
        if (!getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("notification_protection_active", false)) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && manager.isNotificationPolicyAccessGranted()) {
            int previous = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(
                    "previous_interruption_filter",
                    NotificationManager.INTERRUPTION_FILTER_ALL);
            manager.setInterruptionFilter(previous);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .remove("previous_interruption_filter")
                .putBoolean("notification_protection_active", false)
                .apply();
    }

    @Override public void onDestroy() {
        databaseExecutor.shutdown();
        super.onDestroy();
    }
}
