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

import androidx.core.content.ContextCompat;

import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.remote.DreamSync.shared.CuePolicy;
import com.remote.DreamSync.shared.EligibilityEstimator;
import com.remote.DreamSync.shared.PersonalBaseline;
import com.remote.DreamSync.shared.ProtocolEnvelope;
import com.remote.DreamSync.shared.SessionCoordinator;
import com.remote.DreamSync.shared.SessionOutcome;
import com.remote.DreamSync.shared.SessionState;
import com.remote.DreamSync.shared.SignalQualityMonitor;
import com.remote.DreamSync.shared.SleepContinuityTracker;
import com.remote.DreamSync.shared.SleepFeatureEngine;
import com.remote.DreamSync.shared.SleepOnsetEstimator;
import com.remote.DreamSync.shared.SleepPhaseMachine;
import com.remote.DreamSync.shared.SleepSessionClock;
import com.remote.DreamSync.shared.TimeProvider;

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
import java.util.Set;
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
    static final String EXTRA_START_SESSION_ID = "startSessionId";
    static final String EXTRA_HANDSHAKE_MESSAGE_ID = "handshakeMessageId";

    private static final String CHANNEL_ID = "dreamsync_watch_session";
    private static final int NOTIFICATION_ID = 3001;
    private static final long EVALUATION_INTERVAL_MS = 5_000L;
    private static final long WAKELOCK_MARGIN_MS = 30L * 60L * 1000L;
    private static final long CUE_AROUSAL_WINDOW_MS = 20_000L;

    private static volatile boolean running;
    private static volatile String currentStatus = "Not armed.";

    private final Object sampleLock = new Object();
    private final Deque<SleepFeatureEngine.Sample> heartSamples = new ArrayDeque<>();
    private final Deque<SleepFeatureEngine.Sample> motionSamples = new ArrayDeque<>();
    private final Deque<SleepFeatureEngine.Sample> ibiSamples = new ArrayDeque<>();
    private final Deque<SleepFeatureEngine.Sample> skinTemperatureSamples = new ArrayDeque<>();

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
    private long lastIbiSampleMs;
    private long lastTemperatureSampleMs;
    private long lastHeartbeatMs;
    private long probableNremSinceMs;
    private long probableRemSinceMs;
    private long backoffUntilMs;
    private long lastCueMs;
    private long lastLucidCueMs;
    private long nextDeepCueMs;
    private long lastCueArousalCountedForMs;
    private long estimatedSleepOnsetMs;
    private double baselineHeartRate = Double.NaN;
    private double currentGain;
    private double gravityX;
    private double gravityY;
    private double gravityZ;
    private boolean gravityInitialized;
    private boolean accelerometerRegistered;
    private boolean heartRateRegistered;
    private volatile boolean offWrist;
    private volatile boolean samsungSdkConnected;
    private volatile Set<String> samsungTrackerTypes = Collections.emptySet();
    private volatile String samsungDetail = "standard Wear OS sensors";
    private int validPpgSamples;
    private int sessionStartBatteryPercent = -1;
    private boolean storageHealthy = true;
    private boolean sessionInitialized;

    private int deepBlocks;
    private int deepCues;
    private int lucidCues;
    private int cueLinkedArousals;
    private boolean deepBlockActive;
    private int deepCuesInCurrentBlock;
    private boolean cuesDisabled;
    private boolean sessionFinished;
    private String latestStage = "SETTLING";
    private String latestSleepPhase = "ARMED";
    private String latestSignalQuality = "calibrating";
    private String lastPersistedSignalQuality = "";
    private String terminalState = "RUNNING";

    private final SignalQualityMonitor signalQualityMonitor = new SignalQualityMonitor();
    private final SleepOnsetEstimator sleepOnsetEstimator = new SleepOnsetEstimator();
    private final EligibilityEstimator eligibilityEstimator = new EligibilityEstimator();
    private final SleepFeatureEngine featureEngine = new SleepFeatureEngine();
    private final CuePolicy cuePolicy = new CuePolicy();
    private final SessionCoordinator sessionCoordinator = new SessionCoordinator();
    private final TimeProvider timeProvider = new AndroidTimeProvider();
    private final PersonalBaseline personalBaselineEngine = new PersonalBaseline();
    private volatile PersonalBaseline.Result personalBaseline =
            new PersonalBaseline().calculate(Collections.emptyList());
    private final SleepPhaseMachine sleepPhaseMachine =
            new SleepPhaseMachine(5L * 60_000L, 5L * 60_000L);
    private final SleepContinuityTracker continuityTracker = new SleepContinuityTracker();
    private final Map<String, PendingCue> pendingCues = new ConcurrentHashMap<>();
    private ExecutorService databaseExecutor;
    private WearDao dao;
    private SamsungHealthSensorBridge samsungBridge;

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
                terminateSession(SessionState.INTERNAL_ERROR, "INTERNAL_ERROR",
                        error.getClass().getSimpleName() + ": " + error.getMessage());
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

    static String currentStatus() {
        return currentStatus;
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
        databaseExecutor.execute(() -> dao.markInterrupted(timeProvider.utcMs()));
        IntentFilter ackFilter = new IntentFilter(ACTION_CUE_ACK);
        ContextCompat.registerReceiver(
                this, cueAckReceiver, ackFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            sessionCoordinator.recoverAfterProcessDeath();
            terminalState = "PROCESS_INTERRUPTED";
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            terminalState = intent == null
                    ? "USER_STOPPED" : intent.getStringExtra("terminationReason");
            if (terminalState == null || terminalState.isEmpty()) terminalState = "USER_STOPPED";
            sessionCoordinator.terminate(SessionState.USER_STOPPED, terminalState);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            sessionCoordinator.recoverAfterProcessDeath();
            terminalState = "PROCESS_INTERRUPTED";
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) startSession(intent);
        return START_NOT_STICKY;
    }

    private void startSession(Intent startIntent) {
        config = SessionConfig.load(this);
        if (config.sessionId.isEmpty()
                || config.phoneNodeId.isEmpty()
                || config.watchNodeId.isEmpty()
                || !config.isCurrent(timeProvider.utcMs())) {
            broadcastStatus("Tonight's settings are missing or expired. Tap Arm for tonight on the phone.");
            stopSelf();
            return;
        }
        String requestedSession = startIntent.getStringExtra(EXTRA_START_SESSION_ID);
        String requestedHandshake = startIntent.getStringExtra(EXTRA_HANDSHAKE_MESSAGE_ID);
        if (!SessionConfig.consumeHandshake(
                this, requestedSession, requestedHandshake)) {
            sessionCoordinator.recoverAfterProcessDeath();
            terminalState = "PROCESS_INTERRUPTED";
            broadcastStatus("Fresh phone-watch handshake required. Tap Arm for tonight again.");
            stopSelf();
            return;
        }
        sessionCoordinator.beginFreshHandshake();
        sessionCoordinator.arm();
        sessionCoordinator.waitForSleep();
        currentGain = config.cueGain;
        sessionStartMs = timeProvider.elapsedRealtimeMs();
        sessionStartUtcMs = timeProvider.utcMs();
        sessionStartBatteryPercent = batteryPercent();
        sessionInitialized = true;
        running = true;
        startAsForeground();
        acquireWakeLock();
        openLog();
        registerSensors();
        loadPersonalBaseline();
        startSamsungSensors();
        persistSession("WAITING_FOR_SLEEP", "");
        if (!accelerometerRegistered) {
            terminateSession(SessionState.SENSOR_FAILED, "SENSOR_FAILED",
                    "accelerometer did not register");
            persistEvent("SENSOR_FAILED", "", "accelerometer did not register");
            logEvent("SENSOR_FAILED", "Accelerometer did not register");
            stopSelf();
            return;
        }
        persistEvent("AUTO_SLEEP_ESTIMATION_ARMED", "", "armTimeoutMin=" + config.armTimeoutMin);
        logEvent("AUTO_SLEEP_ESTIMATION_ARMED", String.format(
                Locale.US,
                "mode=%s gain=%.3f hrSensor=%s armTimeoutMin=%d",
                config.mode,
                currentGain,
                heartRate != null,
                config.armTimeoutMin
        ));
        broadcastStatus("Automatic sleep estimation armed • waiting for sustained sleep signals");
        evaluatorHandler.postDelayed(evaluator, EVALUATION_INTERVAL_MS);
    }

    private void loadPersonalBaseline() {
        databaseExecutor.execute(() -> {
            List<NightBaselineEntity> stored = dao.latestBaselines();
            List<PersonalBaseline.Night> nights = new ArrayList<>();
            for (NightBaselineEntity night : stored) {
                nights.add(new PersonalBaseline.Night(
                        night.restingHeartRate,
                        night.medianIbiMs,
                        night.ibiRmssdMs,
                        night.skinTemperatureC,
                        night.sleepOnsetMinutes));
            }
            PersonalBaseline.Result result = personalBaselineEngine.calculate(nights);
            personalBaseline = result;
            if (result.nights > 0 && Double.isFinite(result.restingHeartRate)) {
                baselineHeartRate = result.restingHeartRate;
            }
            persistEvent("PERSONAL_BASELINE_LOADED", "",
                    "nights=" + result.nights + ";confidence="
                            + String.format(Locale.US, "%.2f", result.confidence));
        });
    }

    private void startSamsungSensors() {
        samsungBridge = new SamsungHealthSensorBridge(this,
                new SamsungHealthSensorBridge.Listener() {
                    @Override public void onCapabilities(
                            Set<String> trackerTypes, boolean connected, String detail) {
                        samsungTrackerTypes = trackerTypes;
                        samsungSdkConnected = connected;
                        samsungDetail = detail;
                        persistEvent("SENSOR_CAPABILITIES", "", capabilitySnapshot().toJson());
                    }

                    @Override public void onHeartRate(
                            long timestampMs, double bpm, List<Integer> ibiMs,
                            boolean watchOffWrist) {
                        long now = timeProvider.elapsedRealtimeMs();
                        offWrist = watchOffWrist;
                        if (Double.isFinite(bpm) && bpm >= 30.0 && bpm <= 220.0) {
                            lastHeartSampleMs = now;
                            heartRateRegistered = true;
                            synchronized (sampleLock) {
                                heartSamples.addLast(new SleepFeatureEngine.Sample(now, bpm));
                                prune(heartSamples, now - 20L * 60_000L);
                            }
                        }
                        if (ibiMs != null && !ibiMs.isEmpty()) {
                            synchronized (sampleLock) {
                                for (int ibi : ibiMs) {
                                    if (ibi >= 250 && ibi <= 2_000) {
                                        ibiSamples.addLast(new SleepFeatureEngine.Sample(now, ibi));
                                        lastIbiSampleMs = now;
                                    }
                                }
                                prune(ibiSamples, now - 30L * 60_000L);
                            }
                        }
                    }

                    @Override public void onSkinTemperature(
                            long timestampMs, double skinC, double ambientC) {
                        if (!Double.isFinite(skinC) || skinC < 20.0 || skinC > 45.0) return;
                        long now = timeProvider.elapsedRealtimeMs();
                        lastTemperatureSampleMs = now;
                        synchronized (sampleLock) {
                            skinTemperatureSamples.addLast(
                                    new SleepFeatureEngine.Sample(now, skinC));
                            prune(skinTemperatureSamples, now - 60L * 60_000L);
                        }
                    }

                    @Override public void onPpgSample(long timestampMs, boolean valid) {
                        if (valid) validPpgSamples++;
                    }

                    @Override public void onBridgeError(String code, String detail) {
                        samsungDetail = code + ": " + detail;
                        persistEvent("SAMSUNG_SENSOR_ERROR", "", samsungDetail);
                    }
                });
        samsungBridge.start(config.enableRawPpg);
    }

    private SensorCapabilitySnapshot capabilitySnapshot() {
        boolean liveSelfTest = accelerometerRegistered
                && lastMotionSampleMs > 0L
                && heartRateRegistered
                && lastHeartSampleMs > 0L
                && (!samsungSdkConnected
                || !samsungTrackerTypes.contains("HEART_RATE_CONTINUOUS")
                || lastIbiSampleMs > 0L);
        return new SensorCapabilitySnapshot(
                accelerometerRegistered,
                heartRateRegistered,
                SamsungHealthSensorBridge.isPackaged(),
                samsungSdkConnected,
                samsungTrackerTypes,
                liveSelfTest,
                samsungDetail);
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
                ? event.timestamp / 1_000_000L : timeProvider.elapsedRealtimeMs();
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
                motionSamples.addLast(new SleepFeatureEngine.Sample(now, dynamicAcceleration));
                prune(motionSamples, now - 10L * 60L * 1000L);
            }
        } else if (event.sensor.getType() == Sensor.TYPE_HEART_RATE
                && event.values.length > 0
                && event.accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE) {
            double bpm = event.values[0];
            if (bpm >= 30.0 && bpm <= 220.0) {
                lastHeartSampleMs = now;
                synchronized (sampleLock) {
                    heartSamples.addLast(new SleepFeatureEngine.Sample(now, bpm));
                    prune(heartSamples, now - 20L * 60L * 1000L);
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Accuracy is reflected indirectly by missing or invalid samples.
    }

    private void disableCueing(String reason) {
        cuesDisabled = true;
        cuePolicy.disable(reason);
        sessionCoordinator.disableCues(reason);
    }

    private void terminateSession(
            SessionState state,
            String terminalCode,
            String reason
    ) {
        if (SessionCoordinator.isTerminal(sessionCoordinator.state())) return;
        disableCueing(reason);
        terminalState = terminalCode;
        sessionCoordinator.terminate(state, reason);
        persistSession(state.name(), terminalCode);
    }

    private void evaluateAndAct() {
        long now = timeProvider.elapsedRealtimeMs();
        double armedElapsedMin = (now - sessionStartMs) / 60_000.0;
        int battery = batteryPercent();
        if (battery >= 0 && battery <= 10) {
            terminateSession(SessionState.LOW_BATTERY, "LOW_BATTERY",
                    "watch battery reached shutdown threshold");
            logEvent("LOW_BATTERY", "Watch battery reached shutdown threshold");
            stopSelf();
            return;
        }

        SleepFeatureEngine.Snapshot features = calculateFeatures(now, armedElapsedMin);
        updateBaseline(now, armedElapsedMin, features);
        features = features.withBaseline(baselineHeartRate);

        boolean arousal = isArousal(features);
        SignalQualityMonitor.Result quality = signalQualityMonitor.evaluate(
                now, lastMotionSampleMs, lastHeartSampleMs,
                features.motionSampleCount30, features.heartSampleCount2,
                accelerometerRegistered, heartRateRegistered,
                offWrist, lastIbiSampleMs, features.ibiSampleCount5,
                lastTemperatureSampleMs, features.temperatureSampleCount30);
        latestSignalQuality = quality.reason;
        if (!quality.reason.equals(lastPersistedSignalQuality)) {
            lastPersistedSignalQuality = quality.reason;
            persistEvent("SIGNAL_QUALITY", "", quality.reason);
        }
        boolean quietCandidate = quality.deepCueAllowed
                && !arousal && !Double.isNaN(features.motionRms30)
                && features.motionRms30 < 0.30;
        long onset = sleepOnsetEstimator.update(
                quietCandidate, now, 10L * 60_000L);
        boolean onsetJustDetected = estimatedSleepOnsetMs == 0L && onset > 0L;
        if (onsetJustDetected) {
            estimatedSleepOnsetMs = onset;
            sessionCoordinator.markSleepOnset();
            continuityTracker.onSleepOnset(onset);
            long onsetUtcMs = sessionStartUtcMs + Math.max(0L, onset - sessionStartMs);
            String details = String.format(
                    Locale.US,
                    "minutesAfterArming=%.1f;onsetUtcMs=%d;basis=sustained_low_motion",
                    (onset - sessionStartMs) / 60_000.0,
                    onsetUtcMs
            );
            persistEvent("SLEEP_ONSET_ESTIMATED", "", details);
            persistSession("SLEEP_ONSET_ESTIMATED", "");
            logEvent("SLEEP_ONSET_ESTIMATED", details);
            updateSessionNotification("Sleep estimated • cue safety checks active");
        }
        double sleepElapsedMin = SleepSessionClock.sleepElapsedMinutes(onset, now);
        if (SleepSessionClock.armTimedOut(
                sessionStartMs, onset, now, config.armTimeoutMin)) {
            terminateSession(SessionOutcome.forConfiguredLimit(false), "TIMED_OUT",
                    "sleep was not detected before the arm timeout");
            persistEvent("SLEEP_NOT_DETECTED", "", "arm timeout reached; no cues sent");
            logEvent("SESSION_TIMEOUT", "Sleep was not detected before the arm timeout");
            stopSelf();
            return;
        }
        if (SleepSessionClock.sleepTimedOut(onset, now, config.sessionEndMin)) {
            if (!SessionCoordinator.isTerminal(sessionCoordinator.state())) {
                terminateSession(SessionOutcome.forConfiguredLimit(true), "COMPLETE",
                        "configured sleep session completed");
            }
            logEvent("SESSION_COMPLETE", "Configured time since estimated sleep onset reached");
            stopSelf();
            return;
        }
        EligibilityEstimator.Result estimate = eligibilityEstimator.estimate(
                sleepElapsedMin,
                features.motionRms30,
                features.heartMedian2,
                features.heartSd2,
                baselineHeartRate,
                arousal,
                quality,
                earlyNightPrior(sleepElapsedMin),
                remPrior(sleepElapsedMin),
                config.deepStartMin,
                config.deepEndMin,
                config.remStartMin,
                features.ibiRmssdMs,
                personalBaseline.ibiRmssdMs,
                features.skinTemperatureTrendCPerHour);
        double nremScore = estimate.nremScore;
        double remScore = estimate.remScore;
        Stage stage = Stage.valueOf(estimate.state.name());
        latestStage = stage.name();
        updateStability(stage, now);

        continuityTracker.update(now, arousal);
        boolean stableCandidate = stage == Stage.NREM_ELIGIBLE
                || stage == Stage.REM_LIKE_ELIGIBLE;
        latestSleepPhase = sleepPhaseMachine.update(
                now,
                estimatedSleepOnsetMs > 0L,
                stableCandidate,
                arousal,
                quality.motionReady && quality.heartReady && !quality.offWrist
        ).name();
        if (estimatedSleepOnsetMs > 0L
                && sessionCoordinator.state() == SessionState.SLEEP_ONSET_ESTIMATED) {
            sessionCoordinator.markRunning();
        }

        if (arousal) {
            handleArousal(now);
        } else {
            handleDeepCueing(now, sleepElapsedMin, stage);
            handleLucidCueing(now, sleepElapsedMin, stage);
        }
        if (!quality.motionReady && armedElapsedMin >= 15.0) {
            terminateSession(SessionState.SENSOR_FAILED, "SENSOR_FAILED", quality.reason);
            persistEvent("SENSOR_DEGRADED", "", quality.reason);
            logEvent("SENSOR_FAILED", quality.reason);
            stopSelf();
            return;
        }
        if (!storageHealthy) {
            terminateSession(SessionState.INTERNAL_ERROR, "STORAGE_FAILED",
                    "session log write failed");
            persistEvent("STORAGE_FAILED", "", "session log write failed");
            stopSelf();
            return;
        }
        expirePendingCues(now);
        if (onsetJustDetected) {
            publishHeartbeat(quality.reason);
        }
        if (now - lastHeartbeatMs >= 60_000L) {
            lastHeartbeatMs = now;
            publishHeartbeat(quality.reason);
            verifyPhoneReachable();
        }

        String event = "TICK";
        writeLogRow(now, armedElapsedMin, features, nremScore, remScore,
                stage.name(), event);
        broadcastStatus(statusLine(armedElapsedMin, sleepElapsedMin, features, stage));
    }

    private SleepFeatureEngine.Snapshot calculateFeatures(long now, double elapsedMin) {
        synchronized (sampleLock) {
            return featureEngine.calculate(
                    now,
                    elapsedMin,
                    new ArrayList<>(motionSamples),
                    new ArrayList<>(heartSamples),
                    new ArrayList<>(ibiSamples),
                    new ArrayList<>(skinTemperatureSamples),
                    baselineHeartRate);
        }
    }

    private void updateBaseline(
            long now,
            double elapsedMin,
            SleepFeatureEngine.Snapshot features
    ) {
        double earliestBaselineMin = 5.0;
        if (!Double.isNaN(baselineHeartRate) || elapsedMin < earliestBaselineMin) return;
        double estimated;
        synchronized (sampleLock) {
            estimated = featureEngine.estimateSessionBaseline(
                    new ArrayList<>(heartSamples), sessionStartMs, now, elapsedMin, features);
        }
        if (Double.isFinite(estimated)) {
            baselineHeartRate = estimated;
            logEvent("BASELINE_SET", String.format(Locale.US, "%.1f bpm", baselineHeartRate));
        }
    }

    private boolean isArousal(SleepFeatureEngine.Snapshot features) {
        return featureEngine.isArousal(features, baselineHeartRate);
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
        long arousalBackoffMs = 20L * 60L * 1000L;
        backoffUntilMs = Math.max(backoffUntilMs, now + arousalBackoffMs);
        if (lastCueMs > 0L
                && now - lastCueMs <= CUE_AROUSAL_WINDOW_MS
                && lastCueArousalCountedForMs != lastCueMs) {
            lastCueArousalCountedForMs = lastCueMs;
            currentGain = cuePolicy.onCueLinkedArousal(currentGain);
            cueLinkedArousals = cuePolicy.getCueLinkedArousals();
            continuityTracker.onCueLinkedArousal();
            logEvent("CUE_LINKED_AROUSAL", String.format(
                    Locale.US,
                    "count=%d newGain=%.3f",
                    cueLinkedArousals,
                    currentGain
            ));
            disableCueing("first cue-linked arousal");
            logEvent("CUES_DISABLED", "First cue-linked arousal; sleep protection takes priority");
        }
    }

    private void handleDeepCueing(long now, double elapsedMin, Stage stage) {
        int maximumDeepCues = config.maxDeepBlocks * config.deepCuesPerBlock;
        if (!config.usesDeepCues()
                || !cuePolicy.canRequestDeep(maximumDeepCues,
                !cuesDisabled && sessionCoordinator.canCue())) return;
        if (!SleepPhaseMachine.Phase.STABLE_SLEEP.name().equals(latestSleepPhase)) return;
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
                    cuePolicy.onAudioStarted("DEEP");
                    deepCues = cuePolicy.getDeepCues();
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
        if (!config.usesLucidCues()
                || !cuePolicy.canRequestLucid(config.maxLucidCues,
                !cuesDisabled && sessionCoordinator.canCue())
                || deepBlockActive) return;
        if (!SleepPhaseMachine.Phase.STABLE_SLEEP.name().equals(latestSleepPhase)) return;
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
            sendCue("LUCID", "LUCID_CHIME", now, () -> {
                cuePolicy.onAudioStarted("LUCID");
                lucidCues = cuePolicy.getLucidCues();
            });
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
                timeProvider.utcMs(),
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
                lastCueMs = timeProvider.elapsedRealtimeMs();
                continuityTracker.onCueStarted();
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
            double armedElapsedMin,
            double sleepElapsedMin,
            SleepFeatureEngine.Snapshot features,
            Stage stage
    ) {
        String heart = features.hasHeartRate
                ? String.format(Locale.US, "HR %.0f", features.heartMedian2)
                : "HR --";
        if (sleepElapsedMin < 0.0) {
            return String.format(
                    Locale.US,
                    "Waiting for sleep • %.0f min armed • %s • cues off",
                    armedElapsedMin,
                    heart
            );
        }
        String cueState = cuesDisabled
                ? " • cues off"
                : String.format(Locale.US, " • D%d/L%d", deepCues, lucidCues);
        return String.format(
                Locale.US,
                "%s • %.0f min since sleep estimate • %s%s",
                stage.name().replace('_', ' '),
                sleepElapsedMin,
                heart,
                cueState
        );
    }

    private void broadcastStatus(String status) {
        currentStatus = status;
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
        Notification notification = buildNotification("Automatic sleep estimation is armed");
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

    private Notification buildNotification(String contentText) {
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
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("DreamSync night session")
                .setContentText(contentText)
                .setOngoing(true)
                .setContentIntent(openPendingIntent)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                        "Stop",
                        stopPendingIntent
                ).build())
                .build();
    }

    private void updateSessionNotification(String contentText) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(contentText));
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
        // Cover both the pre-sleep arm window and the post-onset session.
        long monitoringMs = (config.armTimeoutMin + (long) config.sessionEndMin) * 60_000L;
        wakeLock.acquire(monitoringMs + WAKELOCK_MARGIN_MS);
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
                            + "heart_slope_per_min,median_ibi_ms,ibi_rmssd_ms,skin_temp_c,"
                            + "skin_temp_trend_c_per_hour,nrem_score,rem_score,eligibility_state,"
                            + "sleep_phase,event,gain,off_wrist,sensor_source\n"
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
            SleepFeatureEngine.Snapshot features,
            double nremScore,
            double remScore,
            String state,
            String event
    ) {
        if (logWriter == null) return;
        try {
            logWriter.write(String.format(
                    Locale.US,
                    "%s,%.2f,%.2f,%.2f,%.4f,%.3f,%.3f,%.2f,%.2f,%.2f,%.3f,"
                            + "%.3f,%.3f,%s,%s,%s,%.4f,%s,%s\n",
                    DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(
                            sessionStartUtcMs + (now - sessionStartMs))),
                    elapsedMin,
                    features.heartMedian2,
                    baselineHeartRate,
                    features.motionRms30,
                    features.heartSd2,
                    features.heartSlopePerMin,
                    features.medianIbiMs,
                    features.ibiRmssdMs,
                    features.medianSkinTemperatureC,
                    features.skinTemperatureTrendCPerHour,
                    nremScore,
                    remScore,
                    state,
                    latestSleepPhase,
                    event,
                    currentGain,
                    offWrist,
                    samsungSdkConnected ? "SAMSUNG_HEALTH_SENSOR_SDK" : "STANDARD_WEAR_OS"
            ));
            logWriter.flush();
        } catch (IOException error) {
            storageHealthy = false;
            disableCueing("session log write failed");
        }
    }

    private void logEvent(String event, String details) {
        if (logWriter == null) return;
        long monotonicNow = timeProvider.elapsedRealtimeMs();
        long nowUtc = timeProvider.utcMs();
        double elapsedMin = sessionStartMs == 0L
                ? 0.0 : (monotonicNow - sessionStartMs) / 60_000.0;
        try {
            logWriter.write(String.format(
                    Locale.US,
                    "%s,%.2f,NaN,%.2f,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,"
                            + "%s,%s,%s: %s,%.4f,%s,%s\n",
                    DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(nowUtc)),
                    elapsedMin,
                    baselineHeartRate,
                    latestStage,
                    latestSleepPhase,
                    event,
                    sanitizeCsv(details),
                    currentGain,
                    offWrist,
                    samsungSdkConnected ? "SAMSUNG_HEALTH_SENSOR_SDK" : "STANDARD_WEAR_OS"
            ));
            logWriter.flush();
        } catch (IOException error) {
            storageHealthy = false;
            disableCueing("session log write failed");
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
        String state = monitoringState();
        persistSession(state, "");
        SleepContinuityTracker.Snapshot continuity = continuityTracker.snapshot(
                timeProvider.elapsedRealtimeMs());
        String status;
        if ("DEGRADED".equals(state)) {
            status = "Logging only: " + qualityReason;
        } else if ("WAITING_FOR_SLEEP".equals(state)) {
            status = "Automatic sleep estimation armed. Waiting for sustained sleep signals.";
        } else if ("CUE_WINDOW_ACTIVE".equals(state)) {
            status = "Sleep estimated. Conservative cue window is active.";
        } else {
            status = "Sleep estimated. Cue safety checks are active.";
        }
        ProtocolEnvelope envelope = ProtocolEnvelope.create(
                com.remote.DreamSync.shared.Protocol.TYPE_HEARTBEAT,
                config.sessionId, "", config.watchNodeId, config.phoneNodeId,
                timeProvider.utcMs(),
                com.remote.DreamSync.shared.Protocol.CONTROL_TTL_MS,
                Map.of(
                        "state", state,
                        "status", status,
                        "signalQuality", qualityReason,
                        "batteryPercent", Integer.toString(batteryPercent()),
                        "sleepPhase", latestSleepPhase,
                        "capabilities", capabilitySnapshot().toJson(),
                        "awakeningCount", Integer.toString(continuity.awakeningCount),
                        "wakeAfterSleepOnsetMs", Long.toString(continuity.wakeAfterSleepOnsetMs),
                        "baselineNights", Integer.toString(personalBaseline.nights)
                ));
        PutDataMapRequest map = PutDataMapRequest.create(
                com.remote.DreamSync.shared.Protocol.PATH_STATE_PREFIX + config.sessionId);
        map.getDataMap().putByteArray("envelope", envelope.encode());
        map.getDataMap().putLong("updatedUtcMs", timeProvider.utcMs());
        Wearable.getDataClient(this).putDataItem(map.asPutDataRequest());
    }

    private String monitoringState() {
        if (cuesDisabled) return "DEGRADED";
        if (estimatedSleepOnsetMs <= 0L) return "WAITING_FOR_SLEEP";
        if ("NREM_ELIGIBLE".equals(latestStage)
                || "REM_LIKE_ELIGIBLE".equals(latestStage)) {
            return "CUE_WINDOW_ACTIVE";
        }
        return "SLEEP_ONSET_ESTIMATED";
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
                        terminateSession(SessionState.PHONE_LOST, "PHONE_LOST",
                                "bound phone disconnected; logging continues");
                        persistEvent("PHONE_LOST", "", "bound phone disconnected; logging continues");
                        logEvent("PHONE_LOST", "Cueing disabled until a new session handshake");
                        broadcastStatus("Phone lost. Logging only. Start a new session to re-arm cues.");
                    }
                })
                .addOnFailureListener(error -> {
                    terminateSession(SessionState.PHONE_LOST, "PHONE_LOST",
                            "phone reachability check failed");
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
                ? 0L : Math.max(0L, timeProvider.elapsedRealtimeMs() - sessionStartMs);
        event.utcMs = timeProvider.utcMs();
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
        snapshot.endUtcMs = isActiveState(state) ? 0L : timeProvider.utcMs();
        snapshot.deepCues = deepCues;
        snapshot.lucidCues = lucidCues;
        snapshot.cueLinkedArousals = cueLinkedArousals;
        SleepContinuityTracker.Snapshot continuity = continuityTracker.snapshot(
                timeProvider.elapsedRealtimeMs());
        snapshot.awakeningCount = continuity.awakeningCount;
        snapshot.wakeAfterSleepOnsetMs = continuity.wakeAfterSleepOnsetMs;
        snapshot.longestUninterruptedBoutMs = continuity.longestUninterruptedBoutMs;
        snapshot.gain = currentGain;
        snapshot.sleepPhase = latestSleepPhase;
        snapshot.estimatedSleepOnsetElapsedMs = estimatedSleepOnsetMs;
        snapshot.baselineNights = personalBaseline.nights;
        snapshot.sensorSource = samsungSdkConnected
                ? "SAMSUNG_HEALTH_SENSOR_SDK" : "STANDARD_WEAR_OS";
        snapshot.capabilityJson = capabilitySnapshot().toJson();
        snapshot.terminationReason = terminationReason == null ? "" : terminationReason;
        databaseExecutor.execute(() -> dao.upsertSession(snapshot));
    }

    private boolean isActiveState(String state) {
        return "RUNNING".equals(state)
                || "WAITING_FOR_SLEEP".equals(state)
                || "SLEEP_ONSET_ESTIMATED".equals(state)
                || "CUE_WINDOW_ACTIVE".equals(state)
                || "DEGRADED".equals(state);
    }

    private void sendSummary() {
        JSONObject summary = new JSONObject();
        try {
            long now = timeProvider.elapsedRealtimeMs();
            SleepFeatureEngine.Snapshot finalFeatures = calculateFeatures(
                    now, sessionStartMs == 0L ? 0.0 : (now - sessionStartMs) / 60_000.0);
            summary.put("mode", config == null ? "unknown" : config.mode);
            summary.put("minutes", sessionStartMs == 0L
                    ? 0
                    : Math.round((timeProvider.elapsedRealtimeMs() - sessionStartMs) / 60_000.0));
            summary.put("automaticSleepOnsetEstimation", true);
            summary.put("sleepOnsetEstimated", estimatedSleepOnsetMs > 0L);
            summary.put("estimatedSleepOnsetMinutesAfterArming", estimatedSleepOnsetMs <= 0L
                    ? -1
                    : Math.round((estimatedSleepOnsetMs - sessionStartMs) / 60_000.0));
            summary.put("sleepMinutes", estimatedSleepOnsetMs <= 0L
                    ? 0
                    : Math.round((timeProvider.elapsedRealtimeMs()
                    - estimatedSleepOnsetMs) / 60_000.0));
            summary.put("deepBlocks", deepBlocks);
            summary.put("deepCues", deepCues);
            summary.put("lucidCues", lucidCues);
            summary.put("cueLinkedArousals", cueLinkedArousals);
            SleepContinuityTracker.Snapshot continuity = continuityTracker.snapshot(
                    timeProvider.elapsedRealtimeMs());
            summary.put("awakeningCount", continuity.awakeningCount);
            summary.put("wakeAfterSleepOnsetMinutes",
                    continuity.wakeAfterSleepOnsetMs / 60_000.0);
            summary.put("longestUninterruptedBoutMinutes",
                    continuity.longestUninterruptedBoutMs / 60_000.0);
            summary.put("cueLinkedArousalRate", continuity.cueLinkedArousalRate());
            summary.put("finalGain", currentGain);
            summary.put("lastState", latestStage);
            summary.put("sleepPhase", latestSleepPhase);
            summary.put("signalQuality", latestSignalQuality);
            summary.put("sensorSource", samsungSdkConnected
                    ? "SAMSUNG_HEALTH_SENSOR_SDK" : "STANDARD_WEAR_OS");
            summary.put("capabilities", new JSONObject(capabilitySnapshot().toJson()));
            summary.put("personalBaselineNights", personalBaseline.nights);
            summary.put("personalizedBaselineReady", personalBaseline.personalized());
            summary.put("validPpgSamples", validPpgSamples);
            JSONObject sensorQuality = new JSONObject();
            sensorQuality.put("motionRegistered", accelerometerRegistered);
            sensorQuality.put("heartRateRegistered", heartRateRegistered);
            sensorQuality.put("offWrist", offWrist);
            sensorQuality.put("motionSamples30s", finalFeatures.motionSampleCount30);
            sensorQuality.put("observedMotionSampleRateHz",
                    finalFeatures.motionSampleCount30 / 30.0);
            sensorQuality.put("heartSamples2m", finalFeatures.heartSampleCount2);
            sensorQuality.put("observedHeartSampleRateHz",
                    finalFeatures.heartSampleCount2 / 120.0);
            sensorQuality.put("ibiSamples5m", finalFeatures.ibiSampleCount5);
            sensorQuality.put("temperatureSamples30m",
                    finalFeatures.temperatureSampleCount30);
            sensorQuality.put("motionFresh", lastMotionSampleMs > 0L
                    && now - lastMotionSampleMs <= SignalQualityMonitor.MOTION_STALE_MS);
            sensorQuality.put("heartFresh", lastHeartSampleMs > 0L
                    && now - lastHeartSampleMs <= SignalQualityMonitor.HEART_STALE_MS);
            summary.put("sensorQualityDetail", sensorQuality);
            summary.put("startBatteryPercent", sessionStartBatteryPercent);
            summary.put("endBatteryPercent", batteryPercent());
            summary.put("endUtcMs", timeProvider.utcMs());
            summary.put("sessionState", SessionOutcome.normalizePersistentState(
                    sessionCoordinator.state().name()));
            summary.put("terminalState", terminalState);
            summary.put("protocolVersion", com.remote.DreamSync.shared.Protocol.VERSION);
            summary.put("waveformVersion", com.remote.DreamSync.shared.CueWaveforms.VERSION);
            summary.put("log", logFilePath);
        } catch (Exception ignored) {}
        if (config == null) return;
        ProtocolEnvelope envelope = ProtocolEnvelope.create(
                com.remote.DreamSync.shared.Protocol.TYPE_SUMMARY,
                config.sessionId, "", config.watchNodeId, config.phoneNodeId,
                timeProvider.utcMs(),
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
        map.getDataMap().putLong("updatedUtcMs", timeProvider.utcMs());
        PutDataRequest request = map.asPutDataRequest();
        request.setUrgent();
        Wearable.getDataClient(this).putDataItem(request);
    }

    private void persistNightBaseline() {
        if (config == null || estimatedSleepOnsetMs <= 0L
                || databaseExecutor == null || databaseExecutor.isShutdown()) return;
        SleepFeatureEngine.NightSummary summary;
        int heartCount;
        synchronized (sampleLock) {
            heartCount = heartSamples.size();
            summary = featureEngine.summarizeNight(
                    new ArrayList<>(heartSamples),
                    new ArrayList<>(ibiSamples),
                    new ArrayList<>(skinTemperatureSamples),
                    baselineHeartRate);
        }
        NightBaselineEntity night = new NightBaselineEntity();
        night.sessionId = config.sessionId;
        night.completedUtcMs = timeProvider.utcMs();
        night.restingHeartRate = summary.restingHeartRate;
        night.medianIbiMs = summary.medianIbiMs;
        night.ibiRmssdMs = summary.ibiRmssdMs;
        night.skinTemperatureC = summary.medianSkinTemperatureC;
        night.sleepOnsetMinutes = (estimatedSleepOnsetMs - sessionStartMs) / 60_000.0;
        night.valid = heartCount >= 8
                && Double.isFinite(night.restingHeartRate)
                && night.sleepOnsetMinutes > 0.0
                && !"SENSOR_FAILED".equals(terminalState)
                && !"INTERNAL_ERROR".equals(terminalState);
        databaseExecutor.execute(() -> dao.insertBaseline(night));
    }

    @Override
    public void onDestroy() {
        if (sessionFinished) {
            super.onDestroy();
            return;
        }
        sessionFinished = true;
        running = false;
        if (!sessionInitialized) {
            if (evaluatorHandler != null) evaluatorHandler.removeCallbacks(evaluator);
            if (sensorManager != null) sensorManager.unregisterListener(this);
            if (samsungBridge != null) samsungBridge.stop();
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (sensorThread != null) sensorThread.quitSafely();
            try { unregisterReceiver(cueAckReceiver); } catch (IllegalArgumentException ignored) {}
            if (databaseExecutor != null) databaseExecutor.shutdown();
            super.onDestroy();
            return;
        }
        evaluatorHandler.removeCallbacks(evaluator);
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (samsungBridge != null) samsungBridge.stop();
        endDeepBlock("service_stopped");
        latestSleepPhase = sleepPhaseMachine.stop().name();
        if ("RUNNING".equals(terminalState)) {
            terminateSession(SessionState.PROCESS_INTERRUPTED, "INTERRUPTED",
                    "service interrupted; fresh handshake required");
        }
        logEvent("SESSION_END", terminalState);
        persistSession(SessionOutcome.normalizePersistentState(
                sessionCoordinator.state().name()), terminalState);
        persistNightBaseline();
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

    private static void prune(
            Deque<SleepFeatureEngine.Sample> deque,
            long cutoffMs
    ) {
        while (!deque.isEmpty() && deque.peekFirst().timeMs < cutoffMs) {
            deque.removeFirst();
        }
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

}
