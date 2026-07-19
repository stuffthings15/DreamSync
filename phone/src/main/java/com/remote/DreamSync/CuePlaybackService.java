package com.remote.DreamSync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.remote.DreamSync.shared.MessageGuard;
import com.remote.DreamSync.shared.ProtocolEnvelope;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CuePlaybackService extends Service
        implements MessageClient.OnMessageReceivedListener {
    static final String ACTION_ARM = "com.remote.DreamSync.ARM";
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

    private final MessageGuard messageGuard = new MessageGuard();
    private ExecutorService playbackExecutor;
    private AudioOutput audioOutput;
    private PhoneDao dao;
    private volatile float armedGain = 0.04f;

    @Override public void onCreate() {
        super.onCreate();
        playbackExecutor = Executors.newSingleThreadExecutor();
        audioOutput = new AudioOutput(this);
        dao = PhoneDatabase.get(this).dao();
        Wearable.getMessageClient(this).addListener(this);
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        armedGain = clampGain(intent.getFloatExtra(EXTRA_GAIN, armedGain));
        String action = intent.getAction();
        if (ACTION_ARM.equals(action)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("active_session_id", intent.getStringExtra(EXTRA_SESSION_ID))
                    .putString("local_phone_node", intent.getStringExtra(EXTRA_LOCAL_NODE_ID))
                    .putString("bound_watch_node", intent.getStringExtra(EXTRA_WATCH_NODE_ID))
                    .putFloat("armed_gain", armedGain)
                    .apply();
        } else if (ACTION_PREVIEW_DEEP.equals(action) || ACTION_PREVIEW_LUCID.equals(action)) {
            String type = ACTION_PREVIEW_LUCID.equals(action) ? "LUCID" : "DEEP";
            playbackExecutor.execute(() -> {
                AudioOutput.Result result = audioOutput.play(type, armedGain, () -> {});
                if (result.success) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString("last_calibration", audioOutput.calibrationRecord(armedGain))
                            .putLong("last_calibration_utc", System.currentTimeMillis())
                            .apply();
                }
            });
        }
        return START_NOT_STICKY;
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
                envelope, sessionId, localNode, System.currentTimeMillis());
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
        if (dao.playedCueCount(envelope.cueId) > 0) {
            sendLatestState(watchNode, envelope);
            return;
        }
        String cueType = envelope.payload.getOrDefault("cueType", "");
        if (!"DEEP".equals(cueType) && !"LUCID".equals(cueType)) {
            sendAck(watchNode, envelope,
                    com.remote.DreamSync.shared.Protocol.TYPE_AUDIO_FAILED, "unsupported cue type");
            return;
        }
        record(envelope, com.remote.DreamSync.shared.Protocol.TYPE_CUE_ACCEPTED, "accepted");
        sendAck(watchNode, envelope,
                com.remote.DreamSync.shared.Protocol.TYPE_CUE_ACCEPTED, "accepted");
        float gain = clampGain(parseFloat(envelope.payload.get("gain"), armedGain));
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

    private float parseFloat(String value, float fallback) {
        try {
            float parsed = Float.parseFloat(value);
            return Float.isFinite(parsed) ? parsed : fallback;
        }
        catch (Exception ignored) { return fallback; }
    }

    private float clampGain(float gain) {
        return Math.max(0.005f, Math.min(0.10f, gain));
    }

    private void startAsForeground() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stopIntent = new Intent(this, CuePlaybackService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("DreamSync audio ready")
                .setContentText("Verified speaker route; waiting for current-session cues")
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else startForeground(NOTIFICATION_ID, notification);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "DreamSync audio", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the verified phone cue engine ready during a session.");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public void onDestroy() {
        Wearable.getMessageClient(this).removeListener(this);
        playbackExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
