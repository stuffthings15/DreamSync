package com.nathanw.somnicue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;

import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

public final class SleepSessionService extends Service implements SensorEventListener {
    static final String ACTION_START = "com.nathanw.somnicue.START_SESSION";
    static final String ACTION_STOP = "com.nathanw.somnicue.STOP_SESSION";
    static final String ACTION_STATUS = "com.nathanw.somnicue.STATUS";
    static final String EXTRA_STATUS = "status";

    private static final String CHANNEL_ID = "somnicue_watch_session";
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

    private int deepBlocks;
    private int deepCues;
    private int lucidCues;
    private int cueLinkedArousals;
    private boolean deepBlockActive;
    private int deepCuesInCurrentBlock;
    private boolean cuesDisabled;
    private boolean sessionFinished;
    private String latestStage = "SETTLING";

    private FileWriter logWriter;
    private String logFilePath = "";

    private final Runnable evaluator = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            evaluateAndAct();
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
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) startSession();
        return START_NOT_STICKY;
    }

    private void startSession() {
        config = SessionConfig.load(this);
        currentGain = config.cueGain;
        sessionStartMs = System.currentTimeMillis();
        running = true;
        startAsForeground();
        acquireWakeLock();
        openLog();
        registerSensors();
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
            sensorManager.registerListener(
                    this,
                    accelerometer,
                    200_000,
                    2_000_000,
                    sensorHandler
            );
        }
        if (heartRate != null) {
            try {
                sensorManager.registerListener(
                        this,
                        heartRate,
                        1_000_000,
                        5_000_000,
                        sensorHandler
                );
            } catch (SecurityException error) {
                logEvent("HEART_RATE_PERMISSION_ERROR", error.getMessage());
                heartRate = null;
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        long now = System.currentTimeMillis();
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
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
        long now = System.currentTimeMillis();
        double elapsedMin = (now - sessionStartMs) / 60_000.0;
        if (elapsedMin >= config.sessionEndMin) {
            logEvent("SESSION_TIMEOUT", "Configured end reached");
            stopSelf();
            return;
        }

        FeatureSnapshot features = calculateFeatures(now, elapsedMin);
        updateBaseline(now, elapsedMin, features);
        features = features.withBaseline(baselineHeartRate);

        boolean arousal = isArousal(features);
        double stillness = clamp01(1.0 - features.motionRms30 / 0.65);
        double earlyPrior = earlyNightPrior(elapsedMin);
        double heartDrop = features.hasHeartRate && !Double.isNaN(baselineHeartRate)
                ? clamp01((baselineHeartRate - features.heartMedian2)
                / Math.max(1.0, baselineHeartRate * 0.12))
                : 0.40;
        double lowHeartVariation = features.hasHeartRate
                ? clamp01(1.0 - features.heartSd2 / 4.5)
                : 0.40;
        double heartIrregularity = features.hasHeartRate
                ? clamp01((features.heartSd2 - 1.5) / 4.0)
                : 0.30;
        double latePrior = remPrior(elapsedMin);

        double nremScore = 0.55 * stillness
                + 0.25 * heartDrop
                + 0.10 * lowHeartVariation
                + 0.10 * earlyPrior;
        double remScore = 0.55 * stillness
                + 0.20 * heartIrregularity
                + 0.25 * latePrior;

        Stage stage = classifyStage(
                elapsedMin,
                features,
                arousal,
                stillness,
                heartIrregularity,
                nremScore,
                remScore
        );
        latestStage = stage.name();
        updateStability(stage, now);

        if (arousal) {
            handleArousal(now);
        } else {
            handleDeepCueing(now, elapsedMin, stage);
            handleLucidCueing(now, elapsedMin, stage);
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
                hasHeartRate,
                hasMotion,
                baselineHeartRate
        );
    }

    private void updateBaseline(long now, double elapsedMin, FeatureSnapshot features) {
        double earliestBaselineMin = config.dryRun ? 1.0 : 5.0;
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
        } else if (features.hasHeartRate && elapsedMin >= (config.dryRun ? 2.0 : 10.0)) {
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
        if (!features.hasMotion) return Stage.UNKNOWN;
        if (elapsedMin < (config.dryRun ? 0.5 : 10.0)) return Stage.SETTLING;
        if (elapsedMin >= config.deepStartMin
                && elapsedMin <= config.deepEndMin
                && stillness >= 0.72
                && nremScore >= 0.72) {
            return Stage.PROBABLE_NREM;
        }
        if (elapsedMin >= config.remStartMin
                && stillness >= 0.68
                && remScore >= 0.68
                && (heartIrregularity >= 0.20 || !features.hasHeartRate)) {
            return Stage.PROBABLE_REM;
        }
        if (stillness >= 0.62) return Stage.QUIET_SLEEP;
        return Stage.AWAKE_OR_MOVING;
    }

    private void updateStability(Stage stage, long now) {
        if (stage == Stage.PROBABLE_NREM) {
            if (probableNremSinceMs == 0L) probableNremSinceMs = now;
        } else {
            probableNremSinceMs = 0L;
        }
        if (stage == Stage.PROBABLE_REM) {
            if (probableRemSinceMs == 0L) probableRemSinceMs = now;
        } else {
            probableRemSinceMs = 0L;
        }
    }

    private void handleArousal(long now) {
        if (deepBlockActive) endDeepBlock("arousal");
        long arousalBackoffMs = config.dryRun ? 60_000L : 10L * 60L * 1000L;
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
            if (stage != Stage.PROBABLE_NREM) {
                endDeepBlock("state_changed");
                return;
            }
            if (deepCuesInCurrentBlock >= config.deepCuesPerBlock) {
                endDeepBlock("block_complete");
                long blockBackoffMs = config.dryRun ? 30_000L : 15L * 60L * 1000L;
                backoffUntilMs = Math.max(backoffUntilMs, now + blockBackoffMs);
                return;
            }
            if (now >= nextDeepCueMs) {
                nextDeepCueMs = now + config.deepCueIntervalSec * 1000L;
                sendCue(Protocol.PATH_DEEP_CUE, "DEEP_PAIR", now, () -> {
                    deepCues++;
                    deepCuesInCurrentBlock++;
                });
            }
            return;
        }

        boolean stableEnough = probableNremSinceMs > 0L
                && now - probableNremSinceMs >= config.deepStableMin * 60_000L;
        if (stage == Stage.PROBABLE_NREM
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
        if (elapsedMin < config.remStartMin || lucidCues >= config.maxLucidCues) return;
        if (now < backoffUntilMs) return;

        boolean stableEnough = probableRemSinceMs > 0L
                && now - probableRemSinceMs >= config.remStableMin * 60_000L;
        boolean intervalPassed = lastLucidCueMs == 0L
                || now - lastLucidCueMs >= config.lucidCueIntervalSec * 1000L;
        if (stage == Stage.PROBABLE_REM && stableEnough && intervalPassed) {
            // Pacing advances regardless of delivery; the budget does not.
            lastLucidCueMs = now;
            sendCue(Protocol.PATH_LUCID_CUE, "LUCID_CHIME", now, () -> lucidCues++);
        }
    }

    private void endDeepBlock(String reason) {
        if (!deepBlockActive) return;
        deepBlockActive = false;
        deepCuesInCurrentBlock = 0;
        logEvent("DEEP_BLOCK_END", reason);
    }

    // Cue budgets and arousal-linking must only reflect cues the phone actually
    // received, so lastCueMs and onDelivered fire on send success, not on attempt.
    private void sendCue(String path, String label, long now, Runnable onDelivered) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("gain", currentGain);
            payload.put("elapsedMs", now - sessionStartMs);
            payload.put("label", label);
        } catch (Exception ignored) {}
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);

        boolean[] delivered = new boolean[1];
        Wearable.getNodeClient(this).getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    if (nodes.isEmpty()) {
                        logEvent("CUE_NOT_SENT", label + ": no connected phone");
                        return;
                    }
                    for (Node node : nodes) {
                        Wearable.getMessageClient(this)
                                .sendMessage(node.getId(), path, bytes)
                                .addOnSuccessListener(messageId -> {
                                    if (delivered[0]) return;
                                    delivered[0] = true;
                                    lastCueMs = System.currentTimeMillis();
                                    logEvent(label, String.format(
                                            Locale.US, "gain=%.3f", currentGain));
                                    onDelivered.run();
                                })
                                .addOnFailureListener(error -> logEvent(
                                        "CUE_SEND_ERROR",
                                        label + ": " + error.getMessage()
                                ));
                    }
                })
                .addOnFailureListener(error -> logEvent(
                        "NODE_QUERY_ERROR",
                        error.getMessage()
                ));
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
                .setContentTitle("Dream Sync night session")
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
                "Dream Sync night session",
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
        if (!base.exists() && !base.mkdirs()) return;
        File logFile = new File(base, "somnicue-" + sessionStartMs + ".csv");
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
                    DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(now)),
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
        } catch (IOException ignored) {}
    }

    private void logEvent(String event, String details) {
        if (logWriter == null) return;
        long now = System.currentTimeMillis();
        double elapsedMin = sessionStartMs == 0L ? 0.0 : (now - sessionStartMs) / 60_000.0;
        try {
            logWriter.write(String.format(
                    Locale.US,
                    "%s,%.2f,NaN,%.2f,NaN,NaN,NaN,NaN,NaN,%s,%s: %s,%.4f\n",
                    DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(now)),
                    elapsedMin,
                    baselineHeartRate,
                    latestStage,
                    event,
                    sanitizeCsv(details),
                    currentGain
            ));
            logWriter.flush();
        } catch (IOException ignored) {}
    }

    private String sanitizeCsv(String value) {
        if (value == null) return "";
        return value.replace(',', ';').replace('\n', ' ');
    }

    private void sendSummary() {
        JSONObject summary = new JSONObject();
        try {
            summary.put("mode", config == null ? "unknown" : config.mode);
            summary.put("minutes", sessionStartMs == 0L
                    ? 0
                    : Math.round((System.currentTimeMillis() - sessionStartMs) / 60_000.0));
            summary.put("deepBlocks", deepBlocks);
            summary.put("deepCues", deepCues);
            summary.put("lucidCues", lucidCues);
            summary.put("cueLinkedArousals", cueLinkedArousals);
            summary.put("finalGain", currentGain);
            summary.put("lastState", latestStage);
            summary.put("log", logFilePath);
        } catch (Exception ignored) {}
        byte[] data = summary.toString().getBytes(StandardCharsets.UTF_8);
        Wearable.getNodeClient(this).getConnectedNodes().addOnSuccessListener(nodes -> {
            for (Node node : nodes) {
                Wearable.getMessageClient(this)
                        .sendMessage(node.getId(), Protocol.PATH_SUMMARY, data);
            }
        });
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
        logEvent("SESSION_END", "normal stop");
        sendSummary();
        if (logWriter != null) {
            try { logWriter.close(); } catch (IOException ignored) {}
            logWriter = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (sensorThread != null) sensorThread.quitSafely();
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
        QUIET_SLEEP,
        PROBABLE_NREM,
        PROBABLE_REM,
        AROUSAL,
        UNKNOWN
    }

    private static final class TimedValue {
        final long timeMs;
        final double value;

        TimedValue(long timeMs, double value) {
            this.timeMs = timeMs;
            this.value = value;
        }
    }

    private static final class FeatureSnapshot {
        final double elapsedMin;
        final double motionRms30;
        final double motionRms10;
        final double heartMedian2;
        final double heartSd2;
        final double heartSlopePerMin;
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
                    hasHeartRate,
                    hasMotion,
                    baseline
            );
        }
    }
}
