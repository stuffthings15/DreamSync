package com.remote.DreamSync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.remote.DreamSync.shared.CueRequestValidator;
import com.remote.DreamSync.shared.MessageGuard;
import com.remote.DreamSync.shared.ProtocolEnvelope;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CuePlaybackService extends Service
        implements MessageClient.OnMessageReceivedListener {
    static final String ACTION_AUTO_ENABLE = "com.remote.DreamSync.AUTO_ENABLE";
    static final String ACTION_AUTO_SESSION = "com.remote.DreamSync.AUTO_SESSION";
    static final String ACTION_STANDBY = "com.remote.DreamSync.STANDBY";
    static final String ACTION_PREVIEW_DEEP = "com.remote.DreamSync.PREVIEW_DEEP";
    static final String ACTION_PREVIEW_LUCID = "com.remote.DreamSync.PREVIEW_LUCID";
    static final String ACTION_STOP = "com.remote.DreamSync.STOP_AUDIO";
    static final String EXTRA_GAIN = "gain";
    static final String EXTRA_SESSION_ID = "sessionId";
    static final String EXTRA_LOCAL_NODE_ID = "localNodeId";
    static final String EXTRA_WATCH_NODE_ID = "watchNodeId";

    private static final String CHANNEL_ID = "dreamsync_audio";
    private static final String PREFS = "dreamsync_phone";
    private static final int NOTIFICATION_ID = 2001;
    private static final long ARM_CONFIRM_TIMEOUT_MS = 10L * 60L * 1000L;

    private final MessageGuard messageGuard = new MessageGuard();
    private final CueRequestValidator cueRequestValidator = new CueRequestValidator();
    private ExecutorService playbackExecutor;
    private ExecutorService databaseExecutor;
    private AudioOutput audioOutput;
    private PhoneDao dao;
    private Handler mainHandler;
    private volatile float armedGain = 0.04f;
    private volatile String armedSessionId = "";

    private final Runnable armWatchdog = () -> {
        String sessionId = armedSessionId;
        if (sessionId.isEmpty()
                || sessionId.equals(prefs("watch_live_session_id"))) return;
        EventEntity event = new EventEntity();
        event.sessionId = sessionId;
        event.type = "ARM_TIMEOUT";
        event.monotonicMs = SystemClock.elapsedRealtime();
        event.utcMs = System.currentTimeMillis();
        event.details = "No watch heartbeat within 10 minutes; phone audio service stopped";
        databaseExecutor.execute(() -> {
            dao.insertEvent(event);
            SessionEntity session = dao.session(sessionId);
            if (session != null) {
                session.state = "ARM_TIMEOUT";
                session.endUtcMs = System.currentTimeMillis();
                dao.upsertSession(session);
            }
        });
        if (sessionId.equals(prefs("active_session_id"))) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .remove("active_session_id")
                    .remove("watch_live_session_id")
                    .apply();
        }
        sendBroadcast(new Intent(PhoneDataListenerService.ACTION_STATUS_CHANGED)
                .setPackage(getPackageName())
                .putExtra("status", "Watch confirmation timed out. No cues will play. DreamSync remains On for the next sleep period.")
                .putExtra("state", "AUTO_START_TIMEOUT")
                .putExtra("sessionId", sessionId));
        restoreNotificationProtection();
        armedSessionId = "";
        if (getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("auto_enabled", false)) startAsForeground(false);
        else stopSelf();
    };

    @Override public void onCreate() {
        super.onCreate();
        playbackExecutor = Executors.newSingleThreadExecutor();
        databaseExecutor = Executors.newSingleThreadExecutor();
        audioOutput = new AudioOutput(this);
        dao = PhoneDatabase.get(this).dao();
        mainHandler = new Handler(Looper.getMainLooper());
        Wearable.getMessageClient(this).addListener(this);
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            if (getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean("auto_enabled", false)) {
                startAsForeground(false);
                return START_STICKY;
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            armedSessionId = "";
            mainHandler.removeCallbacks(armWatchdog);
            restoreNotificationProtection();
            stopSelf();
            return START_NOT_STICKY;
        }
        armedGain = clampGain(intent.getFloatExtra(EXTRA_GAIN, armedGain));
        String action = intent.getAction();
        if (ACTION_AUTO_ENABLE.equals(action)) {
            armedSessionId = "";
            mainHandler.removeCallbacks(armWatchdog);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("auto_enabled", true)
                    .putString("local_phone_node", safeExtra(intent, EXTRA_LOCAL_NODE_ID))
                    .putString("bound_watch_node", safeExtra(intent, EXTRA_WATCH_NODE_ID))
                    .putFloat("armed_gain", armedGain)
                    .apply();
            startAsForeground(false);
        } else if (ACTION_AUTO_SESSION.equals(action)) {
            armedSessionId = intent.getStringExtra(EXTRA_SESSION_ID);
            if (armedSessionId == null) armedSessionId = "";
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("active_session_id", armedSessionId)
                    .putString("local_phone_node", intent.getStringExtra(EXTRA_LOCAL_NODE_ID))
                    .putString("bound_watch_node", intent.getStringExtra(EXTRA_WATCH_NODE_ID))
                    .remove("watch_live_session_id")
                    .putFloat("armed_gain", armedGain)
                    .apply();
            mainHandler.removeCallbacks(armWatchdog);
            mainHandler.postDelayed(armWatchdog, ARM_CONFIRM_TIMEOUT_MS);
            startAsForeground(false);
        } else if (ACTION_STANDBY.equals(action)) {
            armedSessionId = "";
            mainHandler.removeCallbacks(armWatchdog);
            startAsForeground(false);
        } else if (ACTION_PREVIEW_DEEP.equals(action) || ACTION_PREVIEW_LUCID.equals(action)) {
            startAsForeground(true);
            String type = ACTION_PREVIEW_LUCID.equals(action) ? "LUCID" : "DEEP";
            playbackExecutor.execute(() -> {
                AudioOutput.Result result = audioOutput.play(type, armedGain, () -> {});
                if (result.success) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString("last_calibration", audioOutput.calibrationRecord(armedGain))
                            .putLong("last_calibration_utc", System.currentTimeMillis())
                            .apply();
                }
                if (getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getBoolean("auto_enabled", false)) startAsForeground(false);
                else if (armedSessionId.isEmpty()) stopSelf(startId);
            });
        }
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("auto_enabled", false) ? START_STICKY : START_NOT_STICKY;
    }

    @Override public void onMessageReceived(MessageEvent event) {
        if (!Protocol.PATH_DEEP_CUE.equals(event.getPath())) return;
        ProtocolEnvelope envelope;
        try {
            envelope = ProtocolEnvelope.decode(event.getData());
        } catch (IllegalArgumentException error) {
            return;
        }
        if (!com.remote.DreamSync.shared.Protocol.TYPE_CUE_REQUEST.equals(envelope.messageType)) return;
        String sessionId = prefs("active_session_id");
        String localNode = prefs("local_phone_node");
        String watchNode = prefs("bound_watch_node");
        if (!watchNode.equals(event.getSourceNodeId())) {
            sendAck(event.getSourceNodeId(), envelope,
                    com.remote.DreamSync.shared.Protocol.TYPE_AUDIO_FAILED, "wrong watch node");
            return;
        }
        MessageGuard.Result guard = messageGuard.check(
                envelope, sessionId, localNode, watchNode, System.currentTimeMillis());
        if (guard != MessageGuard.Result.ACCEPT) {
            if (guard == MessageGuard.Result.DUPLICATE) {
                playbackExecutor.execute(() -> sendLatestState(watchNode, envelope));
            } else {
                sendAck(watchNode, envelope,
                        com.remote.DreamSync.shared.Protocol.TYPE_AUDIO_FAILED, guard.name());
            }
            return;
        }

        playbackExecutor.execute(() -> handleCue(watchNode, envelope));
    }

    private void handleCue(String watchNode, ProtocolEnvelope envelope) {
        CueRequestValidator.Result validation = cueRequestValidator.validate(envelope);
        if (!validation.valid) {
            record(envelope, "CUE_REJECTED", validation.reason);
            sendAck(watchNode, envelope,
                    com.remote.DreamSync.shared.Protocol.TYPE_AUDIO_FAILED,
                    validation.reason);
            return;
        }
        if (dao.playedCueCount(envelope.cueId) > 0) {
            sendLatestState(watchNode, envelope);
            return;
        }
        String cueType = validation.cueType;
        record(envelope, com.remote.DreamSync.shared.Protocol.TYPE_CUE_ACCEPTED, "accepted");
        sendAck(watchNode, envelope,
                com.remote.DreamSync.shared.Protocol.TYPE_CUE_ACCEPTED, "accepted");
        float gain = validation.gain;
        startAsForeground(true);
        AudioOutput.Result result = audioOutput.play(cueType, gain, () -> {
            record(envelope, com.remote.DreamSync.shared.Protocol.TYPE_AUDIO_STARTED,
                    audioOutput.calibrationRecord(gain));
            sendAck(watchNode, envelope,
                    com.remote.DreamSync.shared.Protocol.TYPE_AUDIO_STARTED, "started");
        });
        String type = result.success
                ? com.remote.DreamSync.shared.Protocol.TYPE_AUDIO_COMPLETED
                : com.remote.DreamSync.shared.Protocol.TYPE_AUDIO_FAILED;
        record(envelope, type, result.reason);
        sendAck(watchNode, envelope, type, result.reason);
        startAsForeground(false);
    }

    private void sendLatestState(String watchNode, ProtocolEnvelope envelope) {
        String state = dao.latestCueState(envelope.cueId);
        String ackType;
        if (com.remote.DreamSync.shared.Protocol.TYPE_AUDIO_COMPLETED.equals(state)) {
            ackType = com.remote.DreamSync.shared.Protocol.TYPE_AUDIO_COMPLETED;
        } else if (com.remote.DreamSync.shared.Protocol.TYPE_AUDIO_STARTED.equals(state)) {
            ackType = com.remote.DreamSync.shared.Protocol.TYPE_AUDIO_STARTED;
        } else if (com.remote.DreamSync.shared.Protocol.TYPE_AUDIO_FAILED.equals(state)) {
            ackType = com.remote.DreamSync.shared.Protocol.TYPE_AUDIO_FAILED;
        } else {
            ackType = com.remote.DreamSync.shared.Protocol.TYPE_CUE_ACCEPTED;
        }
        sendAck(watchNode, envelope, ackType, "duplicate suppressed; returning stored state");
    }

    private void sendAck(String targetNode, ProtocolEnvelope request, String type, String reason) {
        if (targetNode == null || targetNode.isEmpty()) return;
        ProtocolEnvelope ack = ProtocolEnvelope.create(
                type, request.sessionId, request.cueId,
                prefs("local_phone_node"), targetNode, System.currentTimeMillis(),
                com.remote.DreamSync.shared.Protocol.CONTROL_TTL_MS,
                Map.of("reason", reason));
        Wearable.getMessageClient(this).sendMessage(targetNode, Protocol.PATH_ACK, ack.encode());
    }

    private void record(ProtocolEnvelope envelope, String type, String details) {
        EventEntity event = new EventEntity();
        event.sessionId = envelope.sessionId;
        event.cueId = envelope.cueId;
        event.type = type;
        event.monotonicMs = SystemClock.elapsedRealtime();
        event.utcMs = System.currentTimeMillis();
        event.details = details == null ? "" : details;
        dao.insertEvent(event);
    }

    private String prefs(String key) {
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

    private float clampGain(float gain) {
        return Math.max(0.005f, Math.min(0.10f, gain));
    }

    private String safeExtra(Intent intent, String key) {
        String value = intent.getStringExtra(key);
        return value == null ? "" : value;
    }

    private void startAsForeground(boolean audioPlaying) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean autoEnabled = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("auto_enabled", false);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(autoEnabled ? "DreamSync is On" : "DreamSync cue preview")
                .setContentText(audioPlaying
                        ? "Playing a calibrated cue"
                        : armedSessionId.isEmpty()
                        ? "Waiting for sleep automatically"
                        : "Sleep detected; conservative checks are active")
                .setOngoing(true)
                .setContentIntent(open)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            int types = audioPlaying
                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if (audioPlaying && autoEnabled) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            }
            startForeground(NOTIFICATION_ID, notification,
                    types);
        } else startForeground(NOTIFICATION_ID, notification);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "DreamSync automatic sleep readiness",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows when DreamSync is On and ready for automatic sleep detection.");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public void onDestroy() {
        if (mainHandler != null) mainHandler.removeCallbacks(armWatchdog);
        Wearable.getMessageClient(this).removeListener(this);
        playbackExecutor.shutdownNow();
        databaseExecutor.shutdown();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
