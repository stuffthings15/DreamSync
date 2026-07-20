package com.remote.DreamSync;

import android.app.NotificationManager;
import android.content.Intent;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

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

import org.json.JSONObject;

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

                if (path.equals(com.remote.DreamSync.shared.Protocol.PATH_AUTO_STATE_PREFIX
                        + envelope.sessionId) && validAutoEnvelope(envelope, sourceNodeId,
                        com.remote.DreamSync.shared.Protocol.TYPE_AUTO_CONFIG_ACK)) {
                    handleAutoState(envelope);
                } else if (path.equals(
                        com.remote.DreamSync.shared.Protocol.PATH_AUTO_SESSION_PREFIX
                                + envelope.sessionId)
                        && validAutoEnvelope(envelope, sourceNodeId,
                        com.remote.DreamSync.shared.Protocol.TYPE_AUTO_SESSION_START)) {
                    handleAutoSessionStart(envelope);
                } else if (path.equals(com.remote.DreamSync.shared.Protocol.PATH_STATE_PREFIX
                        + envelope.sessionId) && validSessionEnvelope(
                        envelope, sourceNodeId, false)) {
                    handleState(envelope);
                } else if (path.equals(com.remote.DreamSync.shared.Protocol.PATH_SUMMARY_PREFIX
                        + envelope.sessionId) && validSessionEnvelope(
                        envelope, sourceNodeId, true)) {
                    handleSummary(envelope, data.getAsset("sessionLog"));
                }
            }
        } finally {
            events.release();
        }
    }

    @Override public void onMessageReceived(MessageEvent event) {
        // Cue acknowledgements flow watchward. Durable state uses DataItems.
    }

    private void handleAutoState(ProtocolEnvelope envelope) {
        String state = envelope.payload.getOrDefault("state", "ON");
        String status = cleanStatus(envelope.payload.getOrDefault(
                "status", "Watch automatic monitoring updated."));
        if ("OFF".equals(state)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("auto_enabled", false)
                    .remove("active_session_id")
                    .remove("watch_live_session_id")
                    .apply();
            stopService(new Intent(this, CuePlaybackService.class));
            restoreNotificationProtection();
        } else if ("ON".equals(state)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("watch_auto_confirmed", true).apply();
        }
        sendBroadcast(new Intent(ACTION_STATUS_CHANGED).setPackage(getPackageName())
                .putExtra("status", status)
                .putExtra("state", "AUTO_" + state)
                .putExtra("sessionId", envelope.sessionId));
    }

    private void handleAutoSessionStart(ProtocolEnvelope envelope) {
        if (!getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("auto_enabled", false)) return;
        String enrollment = envelope.payload.getOrDefault("enrollmentId", "");
        if (!enrollment.equals(pref("auto_enrollment_id"))) return;
        String active = pref("active_session_id");
        if (!active.isEmpty() && !active.equals(envelope.sessionId)) return;

        String phoneNode = pref("local_phone_node");
        String watchNode = pref("bound_watch_node");
        long now = System.currentTimeMillis();
        JSONObject config = PhoneAutoControl.sessionConfig(
                this, envelope.sessionId, phoneNode, watchNode, now);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("active_session_id", envelope.sessionId)
                .remove("watch_live_session_id")
                .apply();
        applyNotificationProtection();

        SessionEntity session = new SessionEntity();
        session.sessionId = envelope.sessionId;
        session.mode = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("auto_mode", "DEEP");
        session.state = "AUTO_STARTING";
        session.startUtcMs = now;
        session.configurationJson = config.toString();
        session.calibrationRecord = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("last_calibration", "");
        session.protocolVersion = com.remote.DreamSync.shared.Protocol.VERSION;
        session.waveformVersion = com.remote.DreamSync.shared.CueWaveforms.VERSION;
        databaseExecutor.execute(() -> PhoneDatabase.get(this).dao().upsertSession(session));

        Intent audio = new Intent(this, CuePlaybackService.class)
                .setAction(CuePlaybackService.ACTION_AUTO_SESSION)
                .putExtra(CuePlaybackService.EXTRA_SESSION_ID, envelope.sessionId)
                .putExtra(CuePlaybackService.EXTRA_LOCAL_NODE_ID, phoneNode)
                .putExtra(CuePlaybackService.EXTRA_WATCH_NODE_ID, watchNode)
                .putExtra(CuePlaybackService.EXTRA_GAIN,
                        getSharedPreferences(PREFS, MODE_PRIVATE)
                                .getFloat("auto_gain", 0.04f));
        try {
            ContextCompat.startForegroundService(this, audio);
        } catch (RuntimeException error) {
            failAutoSession(envelope.sessionId,
                    "Phone standby could not start. No cues will play.");
            return;
        }
        PhoneAutoControl.publishSessionConfig(
                        this, envelope.sessionId, phoneNode, watchNode, config)
                .addOnSuccessListener(item -> sendBroadcast(
                        new Intent(ACTION_STATUS_CHANGED).setPackage(getPackageName())
                                .putExtra("status",
                                        "Sleep detected. Confirming signals before any cue.")
                                .putExtra("state", "AUTO_STARTING")
                                .putExtra("sessionId", envelope.sessionId)))
                .addOnFailureListener(error -> failAutoSession(
                        envelope.sessionId, "Could not start the watch session. No cues will play."));
    }

    private void failAutoSession(String sessionId, String status) {
        if (sessionId.equals(pref("active_session_id"))) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .remove("active_session_id").apply();
        }
        restoreNotificationProtection();
        sendBroadcast(new Intent(ACTION_STATUS_CHANGED).setPackage(getPackageName())
                .putExtra("status", status)
                .putExtra("state", "AUTO_START_FAILED")
                .putExtra("sessionId", sessionId));
    }

    private void handleState(ProtocolEnvelope envelope) {
        if (!com.remote.DreamSync.shared.Protocol.TYPE_CONFIG_ACK.equals(envelope.messageType)
                && !com.remote.DreamSync.shared.Protocol.TYPE_HEARTBEAT.equals(
                envelope.messageType)) return;
        if (!envelope.sessionId.equals(pref("active_session_id"))) return;
        if (com.remote.DreamSync.shared.Protocol.TYPE_HEARTBEAT.equals(envelope.messageType)) {
            String capabilities = envelope.payload.getOrDefault("capabilities", "{}");
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("watch_live_session_id", envelope.sessionId)
                    .putString("watch_capabilities", capabilities)
                    .apply();
        }
        String status = cleanStatus(envelope.payload.getOrDefault(
                "status", "Watch acknowledged session"));
        String state = SessionOutcome.normalizeLiveState(
                envelope.payload.getOrDefault("state", "AUTO_STARTING"));
        databaseExecutor.execute(() -> {
            PhoneDao dao = PhoneDatabase.get(this).dao();
            SessionEntity session = dao.session(envelope.sessionId);
            if (session != null) {
                session.state = state;
                String previous = session.watchCapabilityJson == null
                        ? "{}" : session.watchCapabilityJson;
                session.watchCapabilityJson = envelope.payload.getOrDefault(
                        "capabilities", previous);
                dao.upsertSession(session);
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
            dao.insertEvent(event);
        });
        sendBroadcast(new Intent(ACTION_STATUS_CHANGED).setPackage(getPackageName())
                .putExtra("status", status)
                .putExtra("state", state)
                .putExtra("capabilities", envelope.payload.getOrDefault("capabilities", "{}"))
                .putExtra("sessionId", envelope.sessionId));
    }

    private void handleSummary(ProtocolEnvelope envelope, Asset logAsset) {
        String summary = envelope.payload.getOrDefault("summary", "{}");
        boolean autoEnabled = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("auto_enabled", false);
        android.content.SharedPreferences.Editor edit = getSharedPreferences(
                        PREFS, MODE_PRIVATE).edit()
                .putString("last_summary", summary)
                .putString("last_summary_session", envelope.sessionId)
                .remove("active_session_id")
                .remove("cancelled_session_id")
                .remove("watch_live_session_id");
        if (!autoEnabled) {
            edit.remove("bound_watch_node").remove("local_phone_node");
        }
        edit.apply();
        if (autoEnabled) {
            startService(new Intent(this, CuePlaybackService.class)
                    .setAction(CuePlaybackService.ACTION_STANDBY));
        } else {
            stopService(new Intent(this, CuePlaybackService.class));
        }
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
                JSONObject json = new JSONObject(summary);
                session.watchLogPath = json.optString("log", "");
                session.state = SessionOutcome.normalizePersistentState(
                        json.optString("sessionState",
                                json.optString("terminalState", "")));
                session.endUtcMs = json.optLong("endUtcMs", session.endUtcMs);
            } catch (Exception ignored) {}
            dao.upsertSession(session);
            ChargeReminderScheduler.scheduleFromSessions(this, dao.sessions());
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

    private boolean validAutoEnvelope(
            ProtocolEnvelope envelope,
            String sourceNodeId,
            String expectedType
    ) {
        String watch = pref("bound_watch_node");
        String phone = pref("local_phone_node");
        if (watch.isEmpty() || phone.isEmpty() || !watch.equals(sourceNodeId)
                || !expectedType.equals(envelope.messageType)) return false;
        if (com.remote.DreamSync.shared.Protocol.TYPE_AUTO_CONFIG_ACK.equals(expectedType)
                && !pref("auto_enrollment_id").equals(envelope.sessionId)) return false;
        return messageGuard.check(envelope, "", phone, watch,
                System.currentTimeMillis()) == MessageGuard.Result.ACCEPT;
    }

    private boolean validSessionEnvelope(
            ProtocolEnvelope envelope,
            String sourceNodeId,
            boolean summary
    ) {
        String active = pref("active_session_id");
        String expectedSession = active.isEmpty() ? pref("cancelled_session_id") : active;
        String watch = pref("bound_watch_node");
        String phone = pref("local_phone_node");
        if (expectedSession.isEmpty() || watch.isEmpty() || phone.isEmpty()
                || !watch.equals(sourceNodeId)) return false;
        if (summary) {
            if (!com.remote.DreamSync.shared.Protocol.TYPE_SUMMARY.equals(
                    envelope.messageType)) return false;
        } else if (!com.remote.DreamSync.shared.Protocol.TYPE_CONFIG_ACK.equals(
                envelope.messageType)
                && !com.remote.DreamSync.shared.Protocol.TYPE_HEARTBEAT.equals(
                envelope.messageType)) return false;
        return messageGuard.check(envelope, expectedSession, phone, watch,
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

    private void applyNotificationProtection() {
        if (!getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("protect_notifications", false)) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || !manager.isNotificationPolicyAccessGranted()) return;
        int current = manager.getCurrentInterruptionFilter();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt("previous_interruption_filter", current)
                .putBoolean("notification_protection_active", true)
                .apply();
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
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

    private String cleanStatus(String status) {
        return status.length() > 256 ? status.substring(0, 256) : status;
    }

    private String pref(String key) {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(key, "");
    }

    @Override public void onDestroy() {
        databaseExecutor.shutdown();
        super.onDestroy();
    }
}
