package com.remote.DreamSync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.remote.DreamSync.shared.EligibilityEstimator;
import com.remote.DreamSync.shared.ProtocolEnvelope;
import com.remote.DreamSync.shared.SignalQualityMonitor;
import com.remote.DreamSync.shared.SleepOnsetEstimator;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SleepSessionService extends Service implements SensorEventListener {
    static final String ACTION_START = "com.remote.DreamSync.START_SESSION";
    static final String ACTION_STOP = "com.remote.DreamSync.STOP_SESSION";
    static final String ACTION_STATUS = "com.remote.DreamSync.STATUS";
    static final String ACTION_CUE_ACK = "com.remote.DreamSync.CUE_ACK";
    static final String EXTRA_STATUS = "status";

    private static final String CHANNEL_ID = "dreamsync_watch_session";
    private static final int NOTIFICATION_ID = 3001;
    private static final long EVALUATION_INTERVAL_MS = 5_000L;
    private static final long WAKELOCK_MARGIN_MS = 30L * 60L * 1000L;
    private static final long CUE_AROUSAL_WINDOW_MS = 20_000L;

    private static volatile boolean running;

    private final Object sampleLock = new Object();
    private final Deque<TimedValue> heartSamples = new ArrayDeque<>();
    private final Deque<TimedValue> motionSamples = new ArrayDeque<>();

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor heartRate;
    private HandlerThread sensorThread;
    private Handler sensorHandler;
    private Handler evaluatorHandler;
    private PowerManager.WakeLock wakeLock;
    private SessionConfig config;

    private long sessionStartMs;
    private long sessionStartUtcMs;
    private long lastMotionSampleMs;
    private long lastHeartSampleMs;
    private long lastHeartbeatMs;
    private long probableNremSinceMs;
    private long probableRemSinceMs;
    private long backoffUntilMs;
    private long lastCueMs;
    private long lastLucidCueMs;
    private long nextDeepCueMs;
    private long lastCueArousalCountedForMs;
    private double baselineHeartRate = Double.NaN;
    private double currentGain;
    private double gravityX;
    private double gravityY;
    private double gravityZ;
    private boolean gravityInitialized;
    private boolean accelerometerRegistered;
    private boolean heartRateRegistered;
    private boolean storageHealthy = true;

    private int deepBlocks;
    private int deepCues;
    private int lucidCues;
    private int cueLinkedArousals;
    private boolean deepBlockActive;
    private int deepCuesInCurrentBlock;
    private boolean cuesDisabled;
    private boolean sessionFinished;
    private String latestStage = "SETTLING";
    private String latestSignalQuality = "calibrating";
    private String terminalState = "RUNNING";

    private final SignalQualityMonitor signalQualityMonitor = new SignalQualityMonitor();
    private final SleepOnsetEstimator sleepOnsetEstimator = new SleepOnsetEstimator();
    private final EligibilityEstimator eligibilityEstimator = new EligibilityEstimator();
    private final Map<String, PendingCue> pendingCues = new ConcurrentHashMap<>();
    private ExecutorService databaseExecutor;
    private WearDao dao;

    private FileWriter logWriter;
    private String logFilePath = "";

    private final BroadcastReceiver cueAckReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_CUE_ACK.equals(intent.getAction())) return;
            if (config == null) return;
            if (!config.sessionId.equals(intent.getStringExtra("sessionId"))) return;
            handleCueAck(
                    intent.getStringExtra("cueId"),
                    intent.getStringExtra("ackType"),
                    intent.getStringExtra("reason"));
        }
    };

    private final Runnable evaluator = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                evaluateAndAct();
            } catch (RuntimeException error) {
                terminalState = "INTERNAL_ERROR";
                cuesDisabled = true;
                persistEvent("INTERNAL_ERROR", "", error.getClass().getSimpleName()
                        + ": " + error.getMessage());
                logEvent("INTERNAL_ERROR", error.getMessage());
                stopSelf();
                return;
            }
            evaluatorHandler.postDelayed(this, EVALUATION_INTERVAL_MS);
        }
    };

    static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        heartRate = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);

        sensorThread = new HandlerThread("DreamSyncSensors");
        sensorThread.start();
        sensorHandler = new Handler(sensorThread.getLooper());
        evaluatorHandler = new Handler(getMainLooper());
        databaseExecutor = Executors.newSingleThreadExecutor();
        dao = WearDatabase.get(this).dao();
        databaseExecutor.execute(() -> dao.markInterrupted(System.currentTimeMillis()));
        IntentFilter ackFilter = new IntentFilter(ACTION_CUE_ACK);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(cueAckReceiver, ackFilter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(cueAckReceiver, ackFilter);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            terminalState = intent == null
                    ? "USER_STOPPED" : intent.getStringExtra("terminationReason");
            if (terminalState == null || terminalState.isEmpty()) terminalState = "USER_STOPPED";
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) startSession();
        return START_NOT_STICKY;
    }

    private void startSession() {
        config = SessionConfig.load(this);
        if (config.sessionId.isEmpty() || config.phoneNodeId.isEmpty() || config.watchNodeId.isEmpty()) {
            broadcastStatus("No acknowledged phone session. Send settings from the phone first.");
            stopSelf();
            return;
        }
        currentGain = config.cueGain;
        sessionStartMs = SystemClock.elapsedRealtime();
        sessionStartUtcMs = System.currentTimeMillis();
        running = true;
        startAsForeground();
        acquireWakeLock();
        openLog();
        registerSensors();
        persistSession("RUNNING", "");
        if (!accelerometerRegistered) {
            terminalState = "SENSOR_FAILED";
            cuesDisabled = true;
            persistEvent("SENSOR_FAILED", "", "accelerometer did not register");
            logEvent("SENSOR_FAILED", "Accelerometer did not register");
            stopSelf();
            return;
        }
        logEvent("SESSION_START", String.format(
                Locale.US,
                "mode=%s gain=%.3f hrSensor=%s",
                config.mode,
                currentGain,
                heartRate != null
        ));
        broadcastStatus("Session running • calibrating motion and heart rate");
        evaluatorHandler.postDelayed(evaluator, EVALUATION_INTERVAL_MS);
    }

    private void registerSensors() {
        if (accelerometer != null) {
            accelerometerRegistered = sensorManager.registerListener(
                    this,
                    accelerometer,
                    200_000,
                    2_000_000,
                    sensorHandler
            );
        }
        if (heartRate != null) {
            try {
                heartRateRegistered = sensorManager.registerListener(
                        this,
                        heartRate,
                        1_000_000,
                        5_000_000,
                        sensorHandler
                );
            } catch (SecurityException error) {
                logEvent("HEART_RATE_PERMISSION_ERROR", error.getMessage());
                heartRate = null;
                heartRateRegistered = false;
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        long now = event.timestamp > 0L
                ? event.timestamp / 1_000_000L : SystemClock.elapsedRealtime();
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            lastMotionSampleMs = now;
            double x = event.values[0];
            double y = event.values[1];
            double z = event.values[2];
            if (!gravityInitialized) {
                gravityX = x;
                gravityY = y;
                gravityZ = z;
                gravityInitialized = true;
            }
            double alpha = 0.90;
            gravityX = alpha * gravityX + (1.0 - alpha) * x;
            gravityY = alpha * gravityY + (1.0 - alpha) * y;
            gravityZ = alpha * gravityZ + (1.0 - alpha) * z;
            double linearX = x - gravityX;
            double linearY = y - gravityY;
            double linearZ = z - gravityZ;
            double dynamicAcceleration = Math.sqrt(
                    linearX * linearX + linearY * linearY + linearZ * linearZ
            );
            synchronized (sampleLock) {
                motionSamples.addLast(new TimedValue(now, dynamicAcceleration));
                prune(motionSamples, now - 10L * 60L * 1000L);
            }
        } else if (event.sensor.getType() == Sensor.TYPE_HEART_RATE
                && event.values.length > 0
                && event.accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE) {
            double bpm = event.values[0];
            if (bpm >= 30.0 && bpm <= 220.0) {
                lastHeartSampleMs = now;
                synchronized (sampleLock) {
                    heartSamples.addLast(new TimedValue(now, bpm));
                    prune(heartSamples, now - 20L * 60L * 1000L);
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Accuracy is reflected indirectly by missing or invalid samples.
    }

    private void evaluateAndAct() {
        long now = SystemClock.elapsedRealtime();
        double elapsedMin = (now - sessionStartMs) / 60_000.0;
        if (elapsedMin >= config.sessionEndMin) {
            terminalState = "TIMED_OUT";
            logEvent("SESSION_TIMEOUT", "Configured end reached");
            stopSelf();
            return;
        }
        if (batteryPercent() >= 0 && batteryPercent() <= 10) {
            terminalState = "LOW_BATTERY";
            cuesDisabled = true;
            logEvent("LOW_BATTERY", "Watch battery reached shutdown threshold");
            stopSelf();
            return;
        }

        FeatureSnapshot features = calculateFeatures(now, elapsedMin);
        updateBaseline(now, elapsedMin, features);
        features = features.withBaseline(baselineHeartRate);

        boolean arousal = isArousal(features);
        SignalQualityMonitor.Result quality = signalQualityMonitor.evaluate(
                now, lastMotionSampleMs, lastHeartSampleMs,
                features.motionSampleCount30, features.heartSampleCount2,
                accelerometerRegistered, heartRateRegistered);
        latestSignalQuality = quality.reason;
        boolean quietCandidate = quality.motionReady
                && !arousal && !Double.isNaN(features.motionRms30)
                && features.motionRms30 < 0.30;
        long onset = sleepOnsetEstimator.update(
                quietCandidate, now, 10L * 60_000L);
        double sleepElapsedMin = onset > 0L ? (now - onset) / 60_000.0 : -1.0;
        double priorElapsed = Math.max(0.0, sleepElapsedMin);
        EligibilityEstimator.Result estimate = eligibilityEstimator.estimate(
                sleepElapsedMin,
                features.motionRms30,
                features.heartMedian2,
                features.heartSd2,
                baselineHeartRate,
                arousal,
                quality,
                earlyNightPrior(priorElapsed),
                remPrior(priorElapsed),
                config.deepStartMin,
                config.deepEndMin,
                config.remStartMin);
        double nremScore = estimate.nremScore;
        double remScore = estimate.remScore;
        Stage stage = Stage.valueOf(estimate.state.name());
        latestStage = stage.name();
        updateStability(stage, now);

        if (arousal) {
            handleArousal(now);
        } else {
            handleDeepCueing(now, priorElapsed, stage);
            handleLucidCueing(now, priorElapsed, stage);
        }
        if (!quality.motionReady && elapsedMin >= 15.0) {
            cuesDisabled = true;
            terminalState = "SENSOR_FAILED";
            persistEvent("SENSOR_DEGRADED", "", quality.reason);
            logEvent("SENSOR_FAILED", quality.reason);
            stopSelf();
            return;
        }
        if (!storageHealthy) {
            cuesDisabled = true;
            terminalState = "INTERNAL_ERROR";
            persistEvent("STORAGE_FAILED", "", "session log write failed");
            stopSelf();
            return;
        }
        expirePendingCues(now);
        if (now - lastHeartbeatMs >= 60_000L) {
            lastHeartbeatMs = now;
            publishHeartbeat(quality.reason);
            verifyPhoneReachable();
        }

        String event = "TICK";
        writeLogRow(now, elapsedMin, features, nremScore, remScore, stage.name(), event);
        broadcastStatus(statusLine(elapsedMin, features, nremScore, remScore, stage));
    }

    private FeatureSnapshot calculateFeatures(long now, double elapsedMin) {
        List<Double> motion30 = new ArrayList<>();
        List<Double> motion10 = new ArrayList<>();
        List<TimedValue> hr2 = new ArrayList<>();
        synchronized (sampleLock) {
            for (TimedValue sample : motionSamples) {
                if (sample.timeMs >= now - 30_000L) motion30.add(sample.value);
                if (sample.timeMs >= now - 10_000L) motion10.add(sample.value);
            }
            for (TimedValue sample : heartSamples) {
                if (sample.timeMs >= now - 120_000L) hr2.add(sample);
            }
        }

        double motionRms30 = rms(motion30);
        double motionRms10 = rms(motion10);
        List<Double> heartValues = new ArrayList<>();
        for (TimedValue sample : hr2) heartValues.add(sample.value);
        double medianHeart = median(heartValues);
        double heartSd = standardDeviation(heartValues, medianHeart);
        double heartSlope = 0.0;
        if (hr2.size() >= 2) {
            TimedValue first = hr2.get(0);
            TimedValue last = hr2.get(hr2.size() - 1);
            double minutes = Math.max(0.1, (last.timeMs - first.timeMs) / 60_000.0);
            heartSlope = (last.value - first.value) / minutes;
        }
        boolean hasHeartRate = heartValues.size() >= 3 && !Double.isNaN(medianHeart);
        boolean hasMotion = motion30.size() >= 20;
        return new FeatureSnapshot(
                elapsedMin,
                motionRms30,
                motionRms10,
                medianHeart,
                heartSd,
                heartSlope,
                motion30.size(),
                heartValues.size(),
                hasHeartRate,
                hasMotion,
                baselineHeartRate
        );
    }

    private void updateBaseline(long now, double elapsedMin, FeatureSnapshot features) {
        double earliestBaselineMin = 5.0;
        if (!Double.isNaN(baselineHeartRate) || elapsedMin < earliestBaselineMin) return;
        List<Double> calibration = new ArrayList<>();
        synchronized (sampleLock) {
            for (TimedValue sample : heartSamples) {
                if (sample.timeMs >= sessionStartMs && sample.timeMs <= now) {
                    calibration.add(sample.value);
                }
            }
        }
        if (calibration.size() >= 8) {
            baselineHeartRate = percentile(calibration, 0.75);
            logEvent("BASELINE_SET", String.format(Locale.US, "%.1f bpm", baselineHeartRate));
        } else if (features.hasHeartRate && elapsedMin >= 10.0) {
            baselineHeartRate = features.heartMedian2;
            logEvent("BASELINE_FALLBACK", String.format(Locale.US, "%.1f bpm", baselineHeartRate));
        }
    }

    private boolean isArousal(FeatureSnapshot f) {
        boolean movementArousal = f.hasMotion && f.motionRms10 > 1.20;
        boolean heartArousal = f.hasHeartRate
                && !Double.isNaN(baselineHeartRate)
                && f.heartMedian2 > baselineHeartRate + 14.0
                && f.heartSlopePerMin > 2.0;
        return movementArousal || heartArousal;
    }

    private Stage classifyStage(
            double elapsedMin,
            FeatureSnapshot features,
            boolean arousal,
            double stillness,
            double heartIrregularity,
            double nremScore,
            double remScore
    ) {
        if (arousal) return Stage.AROUSAL;
        if (!features.hasMotion) return Stage.INSUFFICIENT_SIGNAL;
        if (elapsedMin < 10.0) return Stage.SETTLING;
        if (elapsedMin >= config.deepStartMin
                && elapsedMin <= config.deepEndMin
                && stillness >= 0.72
                && nremScore >= 0.72) {
            return Stage.NREM_ELIGIBLE;
        }
        if (elapsedMin >= config.remStartMin
                && stillness >= 0.68
                && remScore >= 0.68
                && (heartIrregularity >= 0.20 || !features.hasHeartRate)) {
            return Stage.REM_LIKE_ELIGIBLE;
        }
        if (stillness >= 0.62) return Stage.QUIET_UNCERTAIN;
        return Stage.AWAKE_OR_MOVING;
    }

    private void updateStability(Stage stage, long now) {
        if (stage == Stage.NREM_ELIGIBLE) {
            if (probableNremSinceMs == 0L) probableNremSinceMs = now;
        } else {
            probableNremSinceMs = 0L;
        }
        if (stage == Stage.REM_LIKE_ELIGIBLE) {
            if (probableRemSinceMs == 0L) probableRemSinceMs = now;
        } else {
            probableRemSinceMs = 0L;
        }
    }

    private void handleArousal(long now) {
        if (deepBlockActive) endDeepBlock("arousal");
        long arousalBackoffMs = 10L * 60L * 1000L;
        backoffUntilMs = Math.max(backoffUntilMs, now + arousalBackoffMs);
        if (lastCueMs > 0L
                && now - lastCueMs <= CUE_AROUSAL_WINDOW_MS
                && lastCueArousalCountedForMs != lastCueMs) {
            lastCueArousalCountedForMs = lastCueMs;
            cueLinkedArousals++;
            currentGain = Math.max(0.005, currentGain * 0.70);
            logEvent("CUE_LINKED_AROUSAL", String.format(
                    Locale.US,
                    "count=%d newGain=%.3f",
                    cueLinkedArousals,
                    currentGain
            ));
            if (cueLinkedArousals >= 3) {
                cuesDisabled = true;
                logEvent("CUES_DISABLED", "Three cue-linked arousals");
            }
        }
    }

    private void handleDeepCueing(long now, double elapsedMin, Stage stage) {
        if (!config.usesDeepCues() || cuesDisabled) return;
        if (elapsedMin < config.deepStartMin || elapsedMin > config.deepEndMin) {
            if (deepBlockActive) endDeepBlock("outside_window");
            return;
        }

        if (deepBlockActive) {
            if (stage != Stage.NREM_ELIGIBLE) {
                endDeepBlock("state_changed");
                return;
            }
            if (deepCuesInCurrentBlock >= config.deepCuesPerBlock) {
                endDeepBlock("block_complete");
                long blockBackoffMs = 15L * 60L * 1000L;
                backoffUntilMs = Math.max(backoffUntilMs, now + blockBackoffMs);
                return;
            }
            if (now >= nextDeepCueMs) {
                nextDeepCueMs = now + config.deepCueIntervalSec * 1000L;
                sendCue("DEEP", "DEEP_PAIR", now, () -> {
                    deepCues++;
                    deepCuesInCurrentBlock++;
                });
            }
            return;
        }

        boolean stableEnough = probableNremSinceMs > 0L
                && now - probableNremSinceMs >= config.deepStableMin * 60_000L;
        if (stage == Stage.NREM_ELIGIBLE
                && stableEnough
                && now >= backoffUntilMs
                && deepBlocks < config.maxDeepBlocks) {
            deepBlocks++;
            deepBlockActive = true;
            deepCuesInCurrentBlock = 0;
            nextDeepCueMs = now;
            logEvent("DEEP_BLOCK_START", "block=" + deepBlocks);
        }
    }

    private void handleLucidCueing(long now, double elapsedMin, Stage stage) {
        if (!config.usesLucidCues() || cuesDisabled || deepBlockActive) return;
        if (elapsedMin < config.remStartMin
                || lucidCues >= config.maxLucidCues) return;
        if (now < backoffUntilMs) return;

        boolean stableEnough = probableRemSinceMs > 0L
                && now - probableRemSinceMs >= config.remStableMin * 60_000L;
        boolean intervalPassed = lastLucidCueMs == 0L
                || now - lastLucidCueMs >= config.lucidCueIntervalSec * 1000L;
        if (stage == Stage.REM_LIKE_ELIGIBLE && stableEnough && intervalPassed) {
            // Pacing advances regardless of delivery; the budget does not.
            lastLucidCueMs = now;
            sendCue("LUCID", "LUCID_CHIME", now, () -> lucidCues++);
        }
    }

    private void endDeepBlock(String reason) {
        if (!deepBlockActive) return;
        deepBlockActive = false;
        deepCuesInCurrentBlock = 0;
        logEvent("DEEP_BLOCK_END", reason);
    }

    private void sendCue(
            String cueType,
            String label,
            long now,
            Runnable onAudioStarted
    ) {
        String cueId = UUID.randomUUID().toString();
        ProtocolEnvelope envelope = ProtocolEnvelope.create(
                com.remote.DreamSync.shared.Protocol.TYPE_CUE_REQUEST,
                config.sessionId,
                cueId,
                config.watchNodeId,
                config.phoneNodeId,
                System.currentTimeMillis(),
                com.remote.DreamSync.shared.Protocol.CUE_TTL_MS,
                Map.of(
                        "cueType", cueType,
                        "label", label,
                        "gain", String.format(Locale.US, "%.4f", currentGain),
                        "elapsedMs", Long.toString(now - sessionStartMs),
                        "policyVersion", "eligibility-v1"
                ));
        pendingCues.put(cueId, new PendingCue(cueId, cueType, now, onAudioStarted));
        persistEvent("CUE_REQUESTED", cueId, cueType);
        Wearable.getMessageClient(this)
                .sendMessage(config.phoneNodeId, com.remote.DreamSync.shared.Protocol.PATH_CUE,
                        envelope.encode())
                .addOnSuccessListener(messageId -> logEvent(
                        "CUE_TRANSPORT_SENT", cueType + " cueId=" + cueId))
                .addOnFailureListener(error -> {
                    pendingCues.remove(cueId);
                    String reason = error.getMessage() == null ? "send failed" : error.getMessage();
                    logEvent("CUE_SEND_ERROR", cueType + ": " + reason);
                    persistEvent("CUE_FAILED", cueId, reason);
                });
    }

    private void handleCueAck(String cueId, String ackType, String reason) {
        if (cueId == null || ackType == null) return;
        PendingCue pending = pendingCues.get(cueId);
        if (pending == null) return;
        if (com.remote.DreamSync.shared.Protocol.TYPE_CUE_ACCEPTED.equals(ackType)) {
            persistEvent("CUE_ACCEPTED", cueId, pending.cueType);
            return;
        }
        if (com.remote.DreamSync.shared.Protocol.TYPE_AUDIO_STARTED.equals(ackType)) {
            if (!pending.audioStarted) {
                pending.audioStarted = true;
                lastCueMs = SystemClock.elapsedRealtime();
                pending.onAudioStarted.run();
                persistEvent("AUDIO_STARTED", cueId, pending.cueType);
                logEvent("AUDIO_STARTED", pending.cueType + " cueId=" + cueId);
            }
            return;
        }
        if (com.remote.DreamSync.shared.Protocol.TYPE_AUDIO_COMPLETED.equals(ackType)) {
            pendingCues.remove(cueId);
            persistEvent("AUDIO_COMPLETED", cueId, pending.cueType);
        } else if (com.remote.DreamSync.shared.Protocol.TYPE_AUDIO_FAILED.equals(ackType)) {
            pendingCues.remove(cueId);
            persistEvent("AUDIO_FAILED", cueId, reason == null ? "" : reason);
        }
    }

    private String statusLine(
            double elapsedMin,
            FeatureSnapshot features,
            double nremScore,
            double remScore,
            Stage stage
    ) {
        String heart = features.hasHeartRate
                ? String.format(Locale.US, "HR %.0f", features.heartMedian2)
                : "HR --";
        String cueState = cuesDisabled
                ? " • cues off"
                : String.format(Locale.US, " • D%d/L%d", deepCues, lucidCues);
        return String.format(
                Locale.US,
                "%s • %.0f min • %s • N %.2f R %.2f%s",
                stage.name().replace('_', ' '),
                elapsedMin,
                heart,
                nremScore,
                remScore,
                cueState
        );
    }

    private void broadcastStatus(String status) {
        sendBroadcast(new Intent(ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_STATUS, status));
    }

    private double earlyNightPrior(double elapsedMin) {
        if (elapsedMin < config.deepStartMin) return 0.0;
        if (elapsedMin <= 120.0) return 1.0;
        if (elapsedMin >= config.deepEndMin) return 0.0;
        return clamp01((config.deepEndMin - elapsedMin)
                / Math.max(1.0, config.deepEndMin - 120.0));
    }

    private double remPrior(double elapsedMin) {
        if (elapsedMin < config.remStartMin) return 0.0;
        double phase = (elapsedMin - config.remStartMin) % 90.0;
        double cyclePrior;
        if (phase <= 25.0) cyclePrior = 1.0;
        else if (phase <= 45.0) cyclePrior = 1.0 - (phase - 25.0) / 20.0;
        else cyclePrior = 0.25;
        double lateBoost = clamp01((elapsedMin - config.remStartMin) / 180.0);
        return clamp01(0.65 * cyclePrior + 0.35 * lateBoost);
    }

    private void startAsForeground() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        Intent stopIntent = new Intent(this, SleepSessionService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("DreamSync night session")
                .setContentText("Motion and heart-rate staging is active")
                .setOngoing(true)
                .setContentIntent(openPendingIntent)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                        "Stop",
                        stopPendingIntent
                ).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "DreamSync night session",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Overnight sensor collection and conservative cue gating.");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void acquireWakeLock() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "DreamSync::NightSession"
        );
        wakeLock.setReferenceCounted(false);
        // Cover the whole configured session (sessionEndMin is clamped to <= 720 min)
        // plus margin, so the evaluator cannot outlive the lock in doze.
        wakeLock.acquire(config.sessionEndMin * 60_000L + WAKELOCK_MARGIN_MS);
    }

    private void openLog() {
        File base = getExternalFilesDir("sessions");
        if (base == null) base = new File(getFilesDir(), "sessions");
        if (!base.exists() && !base.mkdirs()) {
            storageHealthy = false;
            return;
        }
        File logFile = new File(base, "dreamsync-" + config.sessionId + ".csv");
        logFilePath = logFile.getAbsolutePath();
        try {
            logWriter = new FileWriter(logFile, false);
            logWriter.write(
                    "utc,elapsed_min,heart_bpm,baseline_bpm,motion_rms_30s,heart_sd_2m,"
                            + "heart_slope_per_min,nrem_score,rem_score,state,event,gain\n"
            );
            logWriter.flush();
        } catch (IOException error) {
            logWriter = null;
            storageHealthy = false;
        }
    }

    private void writeLogRow(
            long now,
            double elapsedMin,
            FeatureSnapshot features,
            double nremScore,
            double remScore,
            String state,
            String event
    ) {
        if (logWriter == null) return;
        try {
            logWriter.write(String.format(
                    Locale.US,
                    "%s,%.2f,%.2f,%.2f,%.4f,%.3f,%.3f,%.3f,%.3f,%s,%s,%.4f\n",
                    DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(
                            sessionStartUtcMs + (now - sessionStartMs))),
                    elapsedMin,
                    features.heartMedian2,
                    baselineHeartRate,
                    features.motionRms30,
                    features.heartSd2,
                    features.heartSlopePerMin,
                    nremScore,
                    remScore,
                    state,
                    event,
                    currentGain
            ));
            logWriter.flush();
        } catch (IOException error) {
            storageHealthy = false;
            cuesDisabled = true;
        }
    }

    private void logEvent(String event, String details) {
        if (logWriter == null) return;
        long monotonicNow = SystemClock.elapsedRealtime();
        long nowUtc = System.currentTimeMillis();
        double elapsedMin = sessionStartMs == 0L
                ? 0.0 : (monotonicNow - sessionStartMs) / 60_000.0;
        try {
            logWriter.write(String.format(
                    Locale.US,
                    "%s,%.2f,NaN,%.2f,NaN,NaN,NaN,NaN,NaN,%s,%s: %s,%.4f\n",
                    DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(nowUtc)),
                    elapsedMin,
                    baselineHeartRate,
                    latestStage,
                    event,
                    sanitizeCsv(details),
                    currentGain
            ));
            logWriter.flush();
        } catch (IOException error) {
            storageHealthy = false;
            cuesDisabled = true;
        }
    }

    private String sanitizeCsv(String value) {
        if (value == null) return "";
        return value.replace(',', ';').replace('\n', ' ');
    }

    private int batteryPercent() {
        BatteryManager battery = (BatteryManager) getSystemService(BATTERY_SERVICE);
        return battery == null ? -1
                : battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }

    private void expirePendingCues(long now) {
        for (PendingCue cue : new ArrayList<>(pendingCues.values())) {
            if (now - cue.requestedElapsedMs <= 20_000L) continue;
            if (pendingCues.remove(cue.cueId) != null) {
                persistEvent("CUE_FAILED", cue.cueId, "ack timeout");
                logEvent("CUE_ACK_TIMEOUT", cue.cueType + " cueId=" + cue.cueId);
            }
        }
    }

    private void publishHeartbeat(String qualityReason) {
        if (config == null) return;
        ProtocolEnvelope envelope = ProtocolEnvelope.create(
                com.remote.DreamSync.shared.Protocol.TYPE_HEARTBEAT,
                config.sessionId, "", config.watchNodeId, config.phoneNodeId,
                System.currentTimeMillis(),
                com.remote.DreamSync.shared.Protocol.CONTROL_TTL_MS,
                Map.of(
                        "state", cuesDisabled ? "DEGRADED" : "RUNNING",
                        "status", cuesDisabled
                                ? "Logging only: " + qualityReason
                                : "Watch live: " + latestStage,
                        "signalQuality", qualityReason,
                        "batteryPercent", Integer.toString(batteryPercent())
                ));
        PutDataMapRequest map = PutDataMapRequest.create(
                com.remote.DreamSync.shared.Protocol.PATH_STATE_PREFIX + config.sessionId);
        map.getDataMap().putByteArray("envelope", envelope.encode());
        map.getDataMap().putLong("updatedUtcMs", System.currentTimeMillis());
        Wearable.getDataClient(this).putDataItem(map.asPutDataRequest());
    }

    private void verifyPhoneReachable() {
        if (config == null || cuesDisabled) return;
        Wearable.getNodeClient(this).getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    boolean found = false;
                    for (Node node : nodes) {
                        if (config.phoneNodeId.equals(node.getId())) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        cuesDisabled = true;
                        terminalState = "PHONE_LOST";
                        persistEvent("PHONE_LOST", "", "bound phone disconnected; logging continues");
                        logEvent("PHONE_LOST", "Cueing disabled until a new session handshake");
                        broadcastStatus("Phone lost. Logging only. Start a new session to re-arm cues.");
                    }
                })
                .addOnFailureListener(error -> {
                    cuesDisabled = true;
                    terminalState = "PHONE_LOST";
                    persistEvent("PHONE_QUERY_FAILED", "", error.getMessage());
                });
    }

    private void persistEvent(String type, String cueId, String details) {
        if (databaseExecutor == null || databaseExecutor.isShutdown()
                || config == null) return;
        WearEventEntity event = new WearEventEntity();
        event.sessionId = config.sessionId;
        event.cueId = cueId == null ? "" : cueId;
        event.type = type == null ? "" : type;
        event.elapsedMs = sessionStartMs == 0L
                ? 0L : Math.max(0L, SystemClock.elapsedRealtime() - sessionStartMs);
        event.utcMs = System.currentTimeMillis();
        event.details = details == null ? "" : details;
        databaseExecutor.execute(() -> dao.insertEvent(event));
    }

    private void persistSession(String state, String terminationReason) {
        if (databaseExecutor == null || databaseExecutor.isShutdown()
                || config == null) return;
        WearSessionEntity snapshot = new WearSessionEntity();
        snapshot.sessionId = config.sessionId;
        snapshot.mode = config.mode;
        snapshot.state = state;
        snapshot.startUtcMs = sessionStartUtcMs;
        snapshot.startElapsedMs = sessionStartMs;
        snapshot.endUtcMs = "RUNNING".equals(state) ? 0L : System.currentTimeMillis();
        snapshot.deepCues = deepCues;
        snapshot.lucidCues = lucidCues;
        snapshot.cueLinkedArousals = cueLinkedArousals;
        snapshot.gain = currentGain;
        snapshot.terminationReason = terminationReason == null ? "" : terminationReason;
        databaseExecutor.execute(() -> dao.upsertSession(snapshot));
    }

    private void sendSummary() {
        JSONObject summary = new JSONObject();
        try {
            summary.put("mode", config == null ? "unknown" : config.mode);
            summary.put("minutes", sessionStartMs == 0L
                    ? 0
                    : Math.round((SystemClock.elapsedRealtime() - sessionStartMs) / 60_000.0));
            summary.put("deepBlocks", deepBlocks);
            summary.put("deepCues", deepCues);
            summary.put("lucidCues", lucidCues);
            summary.put("cueLinkedArousals", cueLinkedArousals);
            summary.put("finalGain", currentGain);
            summary.put("lastState", latestStage);
            summary.put("signalQuality", latestSignalQuality);
            summary.put("terminalState", terminalState);
            summary.put("protocolVersion", com.remote.DreamSync.shared.Protocol.VERSION);
            summary.put("waveformVersion", com.remote.DreamSync.shared.CueWaveforms.VERSION);
            summary.put("log", logFilePath);
        } catch (Exception ignored) {}
        if (config == null) return;
        ProtocolEnvelope envelope = ProtocolEnvelope.create(
                com.remote.DreamSync.shared.Protocol.TYPE_SUMMARY,
                config.sessionId, "", config.watchNodeId, config.phoneNodeId,
                System.currentTimeMillis(),
                7L * 24L * 60L * 60L * 1000L,
                Map.of("summary", summary.toString()));
        PutDataMapRequest map = PutDataMapRequest.create(
                com.remote.DreamSync.shared.Protocol.PATH_SUMMARY_PREFIX + config.sessionId);
        map.getDataMap().putByteArray("envelope", envelope.encode());
        try {
            if (!logFilePath.isEmpty()) {
                File log = new File(logFilePath);
                if (log.isFile()) {
                    map.getDataMap().putAsset("sessionLog", Asset.createFromBytes(
                            Files.readAllBytes(log.toPath())));
                }
            }
        } catch (IOException error) {
            persistEvent("SUMMARY_LOG_ATTACH_FAILED", "", error.getMessage());
        }
        map.getDataMap().putLong("updatedUtcMs", System.currentTimeMillis());
        PutDataRequest request = map.asPutDataRequest();
        request.setUrgent();
        Wearable.getDataClient(this).putDataItem(request);
    }

    @Override
    public void onDestroy() {
        if (sessionFinished) {
            super.onDestroy();
            return;
        }
        sessionFinished = true;
        running = false;
        evaluatorHandler.removeCallbacks(evaluator);
        if (sensorManager != null) sensorManager.unregisterListener(this);
        endDeepBlock("service_stopped");
        if ("RUNNING".equals(terminalState)) terminalState = "INTERRUPTED";
        logEvent("SESSION_END", terminalState);
        persistSession("COMPLETE", terminalState);
        if (logWriter != null) {
            try { logWriter.close(); } catch (IOException ignored) {}
            logWriter = null;
        }
        sendSummary();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (sensorThread != null) sensorThread.quitSafely();
        try { unregisterReceiver(cueAckReceiver); } catch (IllegalArgumentException ignored) {}
        if (databaseExecutor != null) databaseExecutor.shutdown();
        broadcastStatus("Session stopped • D" + deepCues + " / L" + lucidCues);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static void prune(Deque<TimedValue> deque, long cutoffMs) {
        while (!deque.isEmpty() && deque.peekFirst().timeMs < cutoffMs) {
            deque.removeFirst();
        }
    }

    private static double rms(List<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        double sum = 0.0;
        for (double value : values) sum += value * value;
        return Math.sqrt(sum / values.size());
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) return sorted.get(middle);
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    private static double percentile(List<Double> values, double percentile) {
        if (values.isEmpty()) return Double.NaN;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        double index = percentile * (sorted.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) return sorted.get(lower);
        double weight = index - lower;
        return sorted.get(lower) * (1.0 - weight) + sorted.get(upper) * weight;
    }

    private static double standardDeviation(List<Double> values, double center) {
        if (values.size() < 2 || Double.isNaN(center)) return Double.NaN;
        double sum = 0.0;
        for (double value : values) {
            double delta = value - center;
            sum += delta * delta;
        }
        return Math.sqrt(sum / values.size());
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private enum Stage {
        SETTLING,
        AWAKE_OR_MOVING,
        QUIET_UNCERTAIN,
        NREM_ELIGIBLE,
        REM_LIKE_ELIGIBLE,
        AROUSAL,
        INSUFFICIENT_SIGNAL
    }

    private static final class TimedValue {
        final long timeMs;
        final double value;

        TimedValue(long timeMs, double value) {
            this.timeMs = timeMs;
            this.value = value;
        }
    }

    private static final class PendingCue {
        final String cueId;
        final String cueType;
        final long requestedElapsedMs;
        final Runnable onAudioStarted;
        boolean audioStarted;

        PendingCue(String cueId, String cueType, long requestedElapsedMs, Runnable onAudioStarted) {
            this.cueId = cueId;
            this.cueType = cueType;
            this.requestedElapsedMs = requestedElapsedMs;
            this.onAudioStarted = onAudioStarted;
        }
    }

    private static final class FeatureSnapshot {
        final double elapsedMin;
        final double motionRms30;
        final double motionRms10;
        final double heartMedian2;
        final double heartSd2;
        final double heartSlopePerMin;
        final int motionSampleCount30;
        final int heartSampleCount2;
        final boolean hasHeartRate;
        final boolean hasMotion;
        final double baselineHeartRate;

        FeatureSnapshot(
                double elapsedMin,
                double motionRms30,
                double motionRms10,
                double heartMedian2,
                double heartSd2,
                double heartSlopePerMin,
                int motionSampleCount30,
                int heartSampleCount2,
                boolean hasHeartRate,
                boolean hasMotion,
                double baselineHeartRate
        ) {
            this.elapsedMin = elapsedMin;
            this.motionRms30 = motionRms30;
            this.motionRms10 = motionRms10;
            this.heartMedian2 = heartMedian2;
            this.heartSd2 = heartSd2;
            this.heartSlopePerMin = heartSlopePerMin;
            this.motionSampleCount30 = motionSampleCount30;
            this.heartSampleCount2 = heartSampleCount2;
            this.hasHeartRate = hasHeartRate;
            this.hasMotion = hasMotion;
            this.baselineHeartRate = baselineHeartRate;
        }

        FeatureSnapshot withBaseline(double baseline) {
            return new FeatureSnapshot(
                    elapsedMin,
                    motionRms30,
                    motionRms10,
                    heartMedian2,
                    heartSd2,
                    heartSlopePerMin,
                    motionSampleCount30,
                    heartSampleCount2,
                    hasHeartRate,
                    hasMotion,
                    baseline
            );
        }
    }
}
