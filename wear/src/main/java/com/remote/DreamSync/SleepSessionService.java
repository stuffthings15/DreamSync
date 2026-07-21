package com.remote.DreamSync;

import android.Manifest;
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
import android.content.pm.PackageManager;
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
import com.remote.DreamSync.shared.AdvancedSensorVerification;
import com.remote.DreamSync.shared.CapabilityReport;
import com.remote.DreamSync.shared.CuePolicy;
import com.remote.DreamSync.shared.DecisionSnapshot;
import com.remote.DreamSync.shared.EligibilityEstimator;
import com.remote.DreamSync.shared.PersonalBaseline;
import com.remote.DreamSync.shared.NightState;
import com.remote.DreamSync.shared.NightSummary;
import com.remote.DreamSync.shared.ProgressiveCuePolicy;
import com.remote.DreamSync.shared.ProtectionConfig;
import com.remote.DreamSync.shared.ProtectionMode;
import com.remote.DreamSync.shared.RawPpgWindowPolicy;
import com.remote.DreamSync.shared.ProtocolEnvelope;
import com.remote.DreamSync.shared.SessionCoordinator;
import com.remote.DreamSync.shared.SessionOutcome;
import com.remote.DreamSync.shared.SessionState;
import com.remote.DreamSync.shared.SensorPermissionGate;
import com.remote.DreamSync.shared.SensorProvider;
import com.remote.DreamSync.shared.SignalQualityMonitor;
import com.remote.DreamSync.shared.SleepContinuityTracker;
import com.remote.DreamSync.shared.SleepFeatureEngine;
import com.remote.DreamSync.shared.SleepOnsetEstimator;
import com.remote.DreamSync.shared.SleepPhaseMachine;
import com.remote.DreamSync.shared.SleepSessionClock;
import com.remote.DreamSync.shared.TimeProvider;
import com.remote.DreamSync.shared.WatchAudioCalibration;

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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final long CUE_AROUSAL_WINDOW_MS =
            ProtectionConfig.POST_CUE_OBSERVATION_MS;
    private static final RawPpgWindowPolicy RAW_PPG_POLICY = new RawPpgWindowPolicy();
    private static final String READ_HEART_RATE =
            "android.permission.health.READ_HEART_RATE";
    private static final String READ_ADDITIONAL_HEALTH_DATA =
            "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA";

    private static volatile boolean running;
    private static volatile String currentStatus = "DreamSync is On and waiting for sleep.";

    private final Object sampleLock = new Object();
    private final Deque<SleepFeatureEngine.Sample> heartSamples = new ArrayDeque<>();
    private final Deque<SleepFeatureEngine.Sample> motionSamples = new ArrayDeque<>();
    private final Deque<SleepFeatureEngine.Sample> ibiSamples = new ArrayDeque<>();
    private final Deque<SleepFeatureEngine.Sample> skinTemperatureSamples = new ArrayDeque<>();

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor activeMotionSensor;
    private List<Sensor> registeredMotionSensors = Collections.emptyList();
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
    private boolean rawPpgWindowActive;
    private long rawPpgWindowUntilMs;
    private long rawPpgWindowOpenedMs;
    private int sessionStartBatteryPercent = -1;
    private boolean storageHealthy = true;
    private boolean sessionInitialized;
    private boolean permissionSafetyLatched;

    private int deepBlocks;
    private int deepCues;
    private int lucidCues;
    private int cueLinkedArousals;
    private int lastProgressiveAllowance = -1;
    private boolean deepBlockActive;
    private int deepCuesInCurrentBlock;
    private boolean cuesDisabled;
    private boolean sessionFinished;
    private String latestStage = "SETTLING";
    private String latestSleepPhase = "ARMED";
    private String latestSignalQuality = "calibrating";
    private String lastPersistedSignalQuality = "";
    private String latestDecision = "BLOCKED: session not evaluated yet";
    private String terminalState = "RUNNING";
    private String lastDecisionContract = "";

    private final SignalQualityMonitor signalQualityMonitor = new SignalQualityMonitor();
    private final SleepOnsetEstimator sleepOnsetEstimator = new SleepOnsetEstimator();
    private final EligibilityEstimator eligibilityEstimator = new EligibilityEstimator();
    private final SleepFeatureEngine featureEngine = new SleepFeatureEngine();
    private final CuePolicy cuePolicy = new CuePolicy();
    private final ProgressiveCuePolicy progressiveCuePolicy = new ProgressiveCuePolicy();
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
    private ExecutorService cueAudioExecutor;
    private WearDao dao;
    private SamsungHealthSensorBridge samsungBridge;
    private WatchAudioOutput watchAudioOutput;
    private String motionRegistrationDetails = "";

    private final SensorProvider liveSensorProvider = new SensorProvider() {
        @Override public String id() {
            return samsungSdkConnected
                    ? "SAMSUNG_HEALTH_SENSOR_SDK" : "STANDARD_WEAR_OS";
        }

        @Override public CapabilityReport capabilities() {
            return capabilitySnapshot().report();
        }

        @Override public Snapshot latest() {
            synchronized (sampleLock) {
                return new Snapshot(
                        Math.max(lastMotionSampleMs, Math.max(lastHeartSampleMs,
                                Math.max(lastIbiSampleMs, lastTemperatureSampleMs))),
                        latestValue(motionSamples), latestValue(heartSamples),
                        latestValue(ibiSamples), latestValue(skinTemperatureSamples),
                        offWrist);
            }
        }
    };

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
        heartRate = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);

        sensorThread = new HandlerThread("DreamSyncSensors");
        sensorThread.start();
        sensorHandler = new Handler(sensorThread.getLooper());
        evaluatorHandler = new Handler(getMainLooper());
        databaseExecutor = Executors.newSingleThreadExecutor();
        cueAudioExecutor = Executors.newSingleThreadExecutor();
        watchAudioOutput = new WatchAudioOutput(this);
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
            broadcastStatus("Automatic session settings are missing or expired. DreamSync remains On; check the phone-watch connection.");
            WatchDiagnostics.local(this, "SESSION_START_BLOCKED",
                    "reason=missing_or_expired_configuration");
            stopSelf();
            return;
        }
        String requestedSession = startIntent.getStringExtra(EXTRA_START_SESSION_ID);
        String requestedHandshake = startIntent.getStringExtra(EXTRA_HANDSHAKE_MESSAGE_ID);
        if (!SessionConfig.consumeHandshake(
                this, requestedSession, requestedHandshake)) {
            sessionCoordinator.recoverAfterProcessDeath();
            terminalState = "PROCESS_INTERRUPTED";
            WatchDiagnostics.session(this, config, "SESSION_START_BLOCKED",
                    "reason=fresh_handshake_required");
            broadcastStatus("Fresh phone-watch confirmation is required. DreamSync remains On and will retry next sleep period.");
            stopSelf();
            return;
        }
        sessionCoordinator.beginFreshHandshake();
        sessionCoordinator.arm();
        sessionCoordinator.waitForSleep();
        boolean calibrationSetupMatches = new WatchAudioCalibration().setupMatches(
                config.watchAudioCalibrated,
                config.watchAudibleThreshold,
                config.watchMediaVolume,
                config.watchMediaVolumeMax,
                watchAudioOutput.mediaVolume(),
                watchAudioOutput.mediaVolumeMax());
        if (config.watchAudioCalibrated && !calibrationSetupMatches) {
            config.watchAudioCalibrated = false;
        }
        currentGain = config.cueGain;
        sessionStartMs = timeProvider.elapsedRealtimeMs();
        sessionStartUtcMs = timeProvider.utcMs();
        sessionStartBatteryPercent = batteryPercent();
        sessionInitialized = true;
        running = true;
        startAsForeground();
        acquireWakeLock();
        openLog();
        if (!calibrationSetupMatches
                && ProtectionMode.EXPERIMENTAL_AUDIO.name().equals(config.mode)) {
            persistEvent("WATCH_CALIBRATION_INVALIDATED", "",
                    "watch media volume changed; run the awake watch check again");
            WatchDiagnostics.session(this, config, "WATCH_CALIBRATION_INVALIDATED",
                    "calibratedVolume=" + config.watchMediaVolume
                            + "/" + config.watchMediaVolumeMax
                            + ";currentVolume=" + watchAudioOutput.mediaVolume()
                            + "/" + watchAudioOutput.mediaVolumeMax());
        }
        registerSensors();
        WatchDiagnostics.session(this, config, "SENSOR_REGISTRATION",
                "accelerometerPresent=" + (accelerometer != null)
                        + ";accelerometerRegistered=" + accelerometerRegistered
                        + ";heartRatePresent=" + (heartRate != null)
                        + ";heartRateRegistered=" + heartRateRegistered
                        + ";motionRegistrationDetails=" + motionRegistrationDetails
                        + ";hapticsDuringSleep=OFF"
                        + ";audioOutput=WATCH_SPEAKER_PRIMARY_PHONE_FALLBACK"
                        + ";watchAudio=" + watchAudioOutput.diagnostics());
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
        persistEvent("AUTO_SLEEP_CONFIRMATION_STARTED", "", "detectionTimeoutMin=" + config.armTimeoutMin);
        WatchDiagnostics.session(this, config, "FULL_SENSOR_SESSION_STARTED",
                "mode=" + config.mode
                        + ";gain=" + currentGain
                        + ";confirmationTimeoutMin=" + config.armTimeoutMin
                        + ";evaluationIntervalSec=5");
        logEvent("AUTO_SLEEP_CONFIRMATION_STARTED", String.format(
                Locale.US,
                "mode=%s gain=%.3f hrSensor=%s armTimeoutMin=%d",
                config.mode,
                currentGain,
                heartRate != null,
                config.armTimeoutMin
        ));
        broadcastStatus("Sleep detected • confirming sustained sleep signals • cues off");
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
            AutoSleepConfig automatic = AutoSleepConfig.load(this);
            automatic.baselineNights = result.nights;
            automatic.learnedRestingHeartRateBpm = result.restingHeartRate;
            automatic.save(this);
            persistEvent("PERSONAL_BASELINE_LOADED", "",
                    "nights=" + result.nights + ";confidence="
                            + String.format(Locale.US, "%.2f", result.confidence));
            WatchDiagnostics.session(this, config, "PERSONAL_BASELINE_LOADED",
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
                        WatchDiagnostics.session(SleepSessionService.this, config,
                                "SENSOR_CAPABILITIES", capabilitySnapshot().toJson());
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
                        WatchDiagnostics.session(SleepSessionService.this, config,
                                "SAMSUNG_SENSOR_ERROR", samsungDetail);
                    }
                });
        // A high-end watch must not be reduced to the core profile. The bridge still
        // attaches only tracker types reported by the device at runtime.
        boolean rawPpgPermission = ContextCompat.checkSelfPermission(
                this, READ_ADDITIONAL_HEALTH_DATA) == PackageManager.PERMISSION_GRANTED;
        if (config.enableRawPpg && SamsungHealthSensorBridge.isPackaged()
                && SamsungHealthSensorBridge.isSamsungWatch() && !rawPpgPermission) {
            persistEvent("ADVANCED_PERMISSION_MISSING", "",
                    "raw-PPG permission missing; raw PPG disabled");
            WatchDiagnostics.session(this, config, "ADVANCED_PERMISSION_MISSING",
                    "raw-PPG permission missing; raw PPG disabled");
        }
        // Core Samsung signals stay active. Raw PPG is opened only around a cue.
        samsungBridge.start(false);
    }

    private SensorCapabilitySnapshot capabilitySnapshot() {
        boolean rawPpgPermission = ContextCompat.checkSelfPermission(
                this, READ_ADDITIONAL_HEALTH_DATA) == PackageManager.PERMISSION_GRANTED;
        AdvancedSensorVerification.Result advanced =
                new AdvancedSensorVerification().evaluate(
                        samsungSdkConnected,
                        samsungTrackerTypes,
                        config != null && config.enableRawPpg,
                        rawPpgPermission,
                        lastIbiSampleMs > 0L ? 1 : 0,
                        lastTemperatureSampleMs > 0L ? 1 : 0,
                        validPpgSamples);
        boolean liveSelfTest = accelerometerRegistered
                && lastMotionSampleMs > 0L
                && heartRateRegistered
                && lastHeartSampleMs > 0L;
        boolean requiredPermissions = requiredSensorPermissionsReady();
        boolean watchSpeaker = watchAudioOutput != null
                && watchAudioOutput.preflight().success;
        return new SensorCapabilitySnapshot(
                accelerometerRegistered,
                heartRateRegistered,
                SamsungHealthSensorBridge.isPackaged(),
                samsungSdkConnected,
                samsungTrackerTypes,
                liveSelfTest,
                advanced.passed,
                watchSpeaker,
                requiredPermissions,
                samsungDetail + "; " + advanced.reason);
    }

    private void registerSensors() {
        MotionSensorRegistration.Result motion = MotionSensorRegistration.register(
                sensorManager, this, 200_000, 2_000_000, sensorHandler);
        accelerometer = motion.sensor;
        registeredMotionSensors = motion.sensors;
        accelerometerRegistered = motion.registered;
        motionRegistrationDetails = motion.details;
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
                WatchDiagnostics.session(this, config, "HEART_RATE_PERMISSION_ERROR",
                        error.getMessage());
                heartRate = null;
                heartRateRegistered = false;
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null || event.values == null) return;
        long now = event.timestamp > 0L
                ? event.timestamp / 1_000_000L : timeProvider.elapsedRealtimeMs();
        if (MotionSensorRegistration.accepts(registeredMotionSensors, event.sensor)
                && event.values.length >= 3) {
            if (activeMotionSensor == null) {
                activeMotionSensor = event.sensor;
                accelerometer = activeMotionSensor;
                WatchDiagnostics.session(this, config, "MOTION_SOURCE_CONFIRMED",
                        MotionSensorRegistration.describe(activeMotionSensor));
            }
            if (!MotionSensorRegistration.accepts(
                    Collections.singletonList(activeMotionSensor), event.sensor)) return;
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
        latestDecision = "BLOCKED: cues disabled: " + reason;
        WatchDiagnostics.session(this, config, "CUES_DISABLED", reason);
        closeRawPpgWindow("cueing disabled: " + reason);
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
        if (!enforceRequiredSensorPermissions()) {
            stopSelf();
            return;
        }
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
                    "minutesAfterConfirmationStart=%.1f;onsetUtcMs=%d;basis=sustained_low_motion",
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
                    "sleep was not confirmed before the safety timeout");
            persistEvent("SLEEP_NOT_CONFIRMED", "", "confirmation timeout reached; no cues sent");
            logEvent("SESSION_TIMEOUT", "Sleep was not confirmed before the safety timeout");
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

        latestDecision = decisionReason(now, sleepElapsedMin, stage, arousal, quality);
        persistDecisionContract(now, quality);
        closeRawPpgIfExpired(now);

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
            publishHeartbeat(quality.reason, features, nremScore, remScore, arousal);
        }
        if (now - lastHeartbeatMs >= 60_000L) {
            lastHeartbeatMs = now;
            publishHeartbeat(quality.reason, features, nremScore, remScore, arousal);
            verifyPhoneReachable();
        }

        String event = "TICK";
        writeLogRow(now, armedElapsedMin, features, nremScore, remScore,
                stage.name(), event);
        broadcastStatus(statusLine(armedElapsedMin, sleepElapsedMin, features, stage));
    }

    private boolean enforceRequiredSensorPermissions() {
        boolean activityPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACTIVITY_RECOGNITION)
                == PackageManager.PERMISSION_GRANTED;
        String heartPermission = Build.VERSION.SDK_INT >= 36
                ? READ_HEART_RATE : Manifest.permission.BODY_SENSORS;
        boolean heartPermissionGranted = ContextCompat.checkSelfPermission(
                this, heartPermission) == PackageManager.PERMISSION_GRANTED;
        SensorPermissionGate.Result result = new SensorPermissionGate().evaluate(
                activityPermission, heartPermissionGranted);
        if (result.action == SensorPermissionGate.Action.CONTINUE) return true;
        if (!permissionSafetyLatched) {
            permissionSafetyLatched = true;
            persistEvent("PERMISSION_REVOKED", "", result.reason);
            WatchDiagnostics.session(this, config, "PERMISSION_REVOKED", result.reason);
            logEvent("PERMISSION_REVOKED", result.reason);
        }
        if (result.action == SensorPermissionGate.Action.TERMINATE_SESSION) {
            terminateSession(SessionState.SENSOR_FAILED, "SENSOR_FAILED", result.reason);
            return false;
        }
        disableCueing(result.reason);
        latestSignalQuality = result.reason;
        return true;
    }

    private boolean requiredSensorPermissionsReady() {
        boolean activityPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACTIVITY_RECOGNITION)
                == PackageManager.PERMISSION_GRANTED;
        String heartPermission = Build.VERSION.SDK_INT >= 36
                ? READ_HEART_RATE : Manifest.permission.BODY_SENSORS;
        return activityPermission && ContextCompat.checkSelfPermission(
                this, heartPermission) == PackageManager.PERMISSION_GRANTED;
    }

    private void persistDecisionContract(
            long now, SignalQualityMonitor.Result quality
    ) {
        boolean fresh = quality.motionReady && quality.heartReady;
        boolean allowed = latestDecision.startsWith("ELIGIBLE:")
                && !cuesDisabled && sessionCoordinator.canCue();
        DecisionSnapshot decision = new DecisionSnapshot(
                now, nightState(), fresh, offWrist, allowed, latestDecision);
        SensorProvider.Snapshot sensors = liveSensorProvider.latest();
        String contract = "state=" + decision.state.name()
                + ";provider=" + liveSensorProvider.id()
                + ";profile=" + liveSensorProvider.capabilities().profile().name()
                + ";sensorElapsedMs=" + sensors.elapsedRealtimeMs
                + ";sensorsFresh=" + decision.sensorsFresh
                + ";offWrist=" + decision.offWrist
                + ";cueAllowed=" + decision.cueAllowed
                + ";reason=" + decision.reason;
        if (!contract.equals(lastDecisionContract)) {
            lastDecisionContract = contract;
            persistEvent("DECISION_SNAPSHOT", "", contract);
        }
    }

    private NightState nightState() {
        if (!"RUNNING".equals(terminalState)) return NightState.COMPLETE;
        if (estimatedSleepOnsetMs <= 0L) return NightState.CONFIRMING_SLEEP;
        if (SleepPhaseMachine.Phase.POSSIBLE_AROUSAL.name().equals(latestSleepPhase)) {
            return NightState.POSSIBLE_AROUSAL;
        }
        if (SleepPhaseMachine.Phase.RECOVERED.name().equals(latestSleepPhase)) {
            return NightState.QUIET_RECOVERY;
        }
        if (SleepPhaseMachine.Phase.STABLE_SLEEP.name().equals(latestSleepPhase)) {
            return NightState.STABLE_SLEEP;
        }
        return NightState.LIKELY_ASLEEP;
    }

    private boolean openRawPpgWindow(long now, long durationMs, String reason) {
        if (!config.enableRawPpg || samsungBridge == null
                || !samsungTrackerTypes.contains("PPG_CONTINUOUS")
                || ContextCompat.checkSelfPermission(this, READ_ADDITIONAL_HEALTH_DATA)
                != PackageManager.PERMISSION_GRANTED) return false;
        boolean started = samsungBridge.setRawPpgEnabled(true);
        if (!started) return false;
        rawPpgWindowActive = true;
        rawPpgWindowOpenedMs = now;
        rawPpgWindowUntilMs = now + durationMs;
        persistEvent("RAW_PPG_WINDOW_OPENED", "",
                "reason=" + reason + ";durationMs=" + durationMs);
        WatchDiagnostics.session(this, config, "RAW_PPG_WINDOW_OPENED",
                "reason=" + reason + ";durationMs=" + durationMs);
        return true;
    }

    private void extendRawPpgPostCue(long now) {
        if (!rawPpgWindowActive) {
            openRawPpgWindow(now, RawPpgWindowPolicy.POST_CUE_MS,
                    "POST_CUE_QUALITY");
            return;
        }
        rawPpgWindowUntilMs = RAW_PPG_POLICY.extendAfterAudioStart(
                rawPpgWindowUntilMs, now);
        persistEvent("RAW_PPG_WINDOW_EXTENDED", "",
                "reason=POST_CUE_QUALITY;durationMs=" + RawPpgWindowPolicy.POST_CUE_MS);
    }

    private void closeRawPpgIfExpired(long now) {
        if (rawPpgWindowActive && !RAW_PPG_POLICY.active(now, rawPpgWindowUntilMs)) {
            closeRawPpgWindow("window complete");
        }
    }

    private void closeRawPpgWindow(String reason) {
        if (!rawPpgWindowActive || samsungBridge == null) return;
        samsungBridge.setRawPpgEnabled(false);
        rawPpgWindowActive = false;
        rawPpgWindowUntilMs = 0L;
        persistEvent("RAW_PPG_WINDOW_CLOSED", "", reason);
        WatchDiagnostics.session(this, config, "RAW_PPG_WINDOW_CLOSED", reason);
    }

    private String decisionReason(
            long now,
            double sleepElapsedMin,
            Stage stage,
            boolean arousal,
            SignalQualityMonitor.Result quality
    ) {
        if (cuesDisabled || cuePolicy.isDisabled()) {
            return "BLOCKED: cues disabled: " + cuePolicy.getDisabledReason();
        }
        if (!quality.deepCueAllowed) return "BLOCKED: signal quality: " + quality.reason;
        if (estimatedSleepOnsetMs <= 0L) return "BLOCKED: sleep onset not confirmed";
        if (ProtectionMode.EXPERIMENTAL_AUDIO.name().equals(config.mode)
                && !config.watchAudioCalibrated) {
            return "BLOCKED: watch speaker needs a user-confirmed volume check";
        }
        if (arousal) return "BLOCKED: arousal detected; 20 minute pause";
        if (!SleepPhaseMachine.Phase.STABLE_SLEEP.name().equals(latestSleepPhase)) {
            return "BLOCKED: sleep phase=" + latestSleepPhase;
        }
        if (now < backoffUntilMs) {
            return "BLOCKED: safety backoff remainingMs=" + (backoffUntilMs - now);
        }
        if (stage == Stage.NREM_ELIGIBLE && config.usesDeepCues()) {
            if (sleepElapsedMin < config.deepStartMin || sleepElapsedMin > config.deepEndMin) {
                return "BLOCKED: outside deep cue window";
            }
            return "ELIGIBLE: NREM-like signals; stability and cue budget checked";
        }
        if (stage == Stage.REM_LIKE_ELIGIBLE && config.usesLucidCues()) {
            if (sleepElapsedMin < config.remStartMin) {
                return "BLOCKED: before lucid cue window";
            }
            if (!quality.lucidCueAllowed) return "BLOCKED: reliable cardiac data required";
            return "ELIGIBLE: REM-like signals; stability and cue budget checked";
        }
        return "BLOCKED: eligibility state=" + stage.name();
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
        latestDecision = "BLOCKED: arousal detected; 20 minute pause";
        if (deepBlockActive) endDeepBlock("arousal");
        closeRawPpgWindow("arousal detected");
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
        int maximumDeepCues = progressiveCuePolicy.allowedPairs(
                ProtectionMode.fromStored(config.mode),
                config.safeCueNights);
        if (maximumDeepCues != lastProgressiveAllowance) {
            lastProgressiveAllowance = maximumDeepCues;
            persistEvent("EXPERIMENTAL_AUDIO_ALLOWANCE", "",
                    "mode=" + config.mode
                            + ";baselineNights=" + personalBaseline.nights
                            + ";safeCueNights=" + config.safeCueNights
                            + ";allowedPairs=" + maximumDeepCues);
            WatchDiagnostics.session(this, config, "EXPERIMENTAL_AUDIO_ALLOWANCE",
                    "baselineNights=" + personalBaseline.nights
                            + ";safeCueNights=" + config.safeCueNights
                            + ";allowedPairs=" + maximumDeepCues);
        }
        if (!config.usesDeepCues()
                || !cuePolicy.canRequestDeep(maximumDeepCues,
                !cuesDisabled && sessionCoordinator.canCue())) {
            if (deepBlockActive && deepCues >= maximumDeepCues) {
                endDeepBlock("nightly_allowance_complete");
            }
            return;
        }
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
            if (deepCuesInCurrentBlock >= maximumDeepCues) {
                endDeepBlock("block_complete");
                backoffUntilMs = Math.max(backoffUntilMs,
                        now + ProtectionConfig.POST_CUE_OBSERVATION_MS);
                return;
            }
            if (now >= nextDeepCueMs) {
                nextDeepCueMs = now + config.deepCueIntervalSec * 1000L;
                sendCue("DEEP", "DEEP_PAIR", now, () -> {
                    cuePolicy.onAudioStarted("DEEP");
                    deepCues = cuePolicy.getDeepCues();
                    deepCuesInCurrentBlock++;
                    backoffUntilMs = Math.max(backoffUntilMs,
                            now + ProtectionConfig.POST_CUE_OBSERVATION_MS);
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
            nextDeepCueMs = openRawPpgWindow(
                    now, RawPpgWindowPolicy.PRE_CUE_MS, "PRE_CUE")
                    ? RAW_PPG_POLICY.openPreCue(now) : now;
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
        if (rawPpgWindowActive && lastCueMs < rawPpgWindowOpenedMs) {
            closeRawPpgWindow("pre-cue window cancelled: " + reason);
        }
        logEvent("DEEP_BLOCK_END", reason);
    }

    private void sendCue(
            String cueType,
            String label,
            long now,
            Runnable onAudioStarted
    ) {
        String cueId = UUID.randomUUID().toString();
        float requestedGain = (float) currentGain;
        latestDecision = "ACTION: " + cueType + " cue starting on watch speaker";
        persistEvent("CUE_REQUESTED", cueId, cueType + ";output=WATCH_SPEAKER_PRIMARY");
        WatchDiagnostics.session(this, config, "CUE_OUTPUT_SELECTED",
                "type=" + cueType + ";gain="
                        + String.format(Locale.US, "%.4f", requestedGain)
                        + ";vibration=NONE;primary=WATCH_SPEAKER;fallback=PHONE_SPEAKER"
                        + ";" + watchAudioOutput.diagnostics());
        cueAudioExecutor.execute(() -> {
            AtomicBoolean startHandled = new AtomicBoolean(false);
            WatchAudioOutput.Result result = watchAudioOutput.play(
                    cueType, requestedGain, () -> {
                        if (!startHandled.compareAndSet(false, true)) return;
                        evaluatorHandler.post(() -> {
                            lastCueMs = timeProvider.elapsedRealtimeMs();
                            continuityTracker.onCueStarted();
                            extendRawPpgPostCue(timeProvider.elapsedRealtimeMs());
                            onAudioStarted.run();
                            latestDecision = "ACTION: watch audio started for " + cueType;
                            persistEvent("AUDIO_STARTED", cueId,
                                    cueType + ";output=WATCH_SPEAKER");
                            WatchDiagnostics.session(this, config,
                                    "CUE_AUDIO_STARTED_LOCAL",
                                    "type=" + cueType + ";vibration=NONE;output=WATCH_SPEAKER;"
                                            + watchAudioOutput.diagnostics());
                            logEvent("AUDIO_STARTED", cueType + " cueId=" + cueId
                                    + " output=WATCH_SPEAKER");
                        });
                    });
            if (result.success) {
                evaluatorHandler.post(() -> {
                    latestDecision = "COMPLETE: watch audio completed for " + cueType;
                    persistEvent("AUDIO_COMPLETED", cueId,
                            cueType + ";output=WATCH_SPEAKER");
                    WatchDiagnostics.session(this, config,
                            "CUE_AUDIO_COMPLETED_LOCAL",
                            "type=" + cueType + ";reason=" + result.reason
                                    + ";" + result.diagnostics);
                });
                return;
            }
            if (!result.started) {
                WatchDiagnostics.session(this, config,
                        "CUE_WATCH_AUDIO_PRESTART_FAILED",
                        "type=" + cueType + ";reason=" + result.reason
                                + ";fallback=PHONE_SPEAKER;" + result.diagnostics);
                sendPhoneFallbackCue(cueId, cueType, label, now, onAudioStarted);
                return;
            }
            evaluatorHandler.post(() -> {
                latestDecision = "FAILED: watch audio: " + result.reason;
                persistEvent("AUDIO_FAILED", cueId,
                        result.reason + ";output=WATCH_SPEAKER;fallback=BLOCKED_AFTER_START");
                WatchDiagnostics.session(this, config,
                        "CUE_WATCH_AUDIO_FAILED_AFTER_START",
                        "type=" + cueType + ";reason=" + result.reason
                                + ";phoneFallback=false;" + result.diagnostics);
            });
        });
    }

    private void sendPhoneFallbackCue(
            String cueId,
            String cueType,
            String label,
            long now,
            Runnable onAudioStarted
    ) {
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
        evaluatorHandler.post(() -> {
            latestDecision = "ACTION: watch audio unavailable; " + cueType
                    + " cue requested from phone fallback";
            persistEvent("CUE_PHONE_FALLBACK_REQUESTED", cueId, cueType);
        });
        WatchDiagnostics.session(this, config, "CUE_PHONE_FALLBACK_REQUESTED",
                "type=" + cueType + ";gain="
                        + String.format(Locale.US, "%.4f", currentGain)
                        + ";vibration=NONE;audioOutput=PHONE_SPEAKER_FALLBACK");
        Wearable.getMessageClient(this)
                .sendMessage(config.phoneNodeId, com.remote.DreamSync.shared.Protocol.PATH_CUE,
                        envelope.encode())
                .addOnSuccessListener(messageId -> logEvent(
                        "CUE_TRANSPORT_SENT", cueType + " cueId=" + cueId))
                .addOnFailureListener(error -> {
                    pendingCues.remove(cueId);
                    String reason = error.getMessage() == null ? "send failed" : error.getMessage();
                    logEvent("CUE_SEND_ERROR", cueType + ": " + reason);
                    latestDecision = "FAILED: cue transport: " + reason;
                    WatchDiagnostics.session(this, config, "CUE_TRANSPORT_FAILED",
                            "type=" + cueType + ";reason=" + reason
                                    + ";output=PHONE_SPEAKER_FALLBACK");
                    persistEvent("CUE_FAILED", cueId, reason);
                });
    }

    private void handleCueAck(String cueId, String ackType, String reason) {
        if (cueId == null || ackType == null) return;
        PendingCue pending = pendingCues.get(cueId);
        if (pending == null) return;
        if (com.remote.DreamSync.shared.Protocol.TYPE_CUE_ACCEPTED.equals(ackType)) {
            latestDecision = "ACTION: phone accepted " + pending.cueType + " fallback";
            persistEvent("CUE_ACCEPTED", cueId,
                    pending.cueType + ";output=PHONE_SPEAKER_FALLBACK");
            return;
        }
        if (com.remote.DreamSync.shared.Protocol.TYPE_AUDIO_STARTED.equals(ackType)) {
            if (!pending.audioStarted) {
                pending.audioStarted = true;
                lastCueMs = timeProvider.elapsedRealtimeMs();
                continuityTracker.onCueStarted();
                extendRawPpgPostCue(lastCueMs);
                pending.onAudioStarted.run();
                latestDecision = "ACTION: phone fallback audio started for " + pending.cueType;
                persistEvent("AUDIO_STARTED", cueId,
                        pending.cueType + ";output=PHONE_SPEAKER_FALLBACK");
                WatchDiagnostics.session(this, config, "PHONE_FALLBACK_AUDIO_STARTED",
                        "type=" + pending.cueType + ";vibration=NONE");
                logEvent("AUDIO_STARTED", pending.cueType + " cueId=" + cueId
                        + " output=PHONE_SPEAKER_FALLBACK");
            }
            return;
        }
        if (com.remote.DreamSync.shared.Protocol.TYPE_AUDIO_COMPLETED.equals(ackType)) {
            pendingCues.remove(cueId);
            latestDecision = "COMPLETE: phone fallback audio completed for " + pending.cueType;
            persistEvent("AUDIO_COMPLETED", cueId,
                    pending.cueType + ";output=PHONE_SPEAKER_FALLBACK");
        } else if (com.remote.DreamSync.shared.Protocol.TYPE_AUDIO_FAILED.equals(ackType)) {
            pendingCues.remove(cueId);
            latestDecision = "FAILED: phone fallback audio: "
                    + (reason == null ? "" : reason);
            WatchDiagnostics.session(this, config, "PHONE_FALLBACK_AUDIO_FAILED",
                    "type=" + pending.cueType + ";reason=" + reason);
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
                    "Confirming sleep • %.0f min • %s • cues off",
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
        Notification notification = buildNotification("Sleep detected; conservative checks are active");
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
                            + "sleep_phase,event,gain,off_wrist,sensor_source,decision,"
                            + "motion_samples_30s,heart_samples_2m,ibi_samples_5m,"
                            + "temperature_samples_30m,haptics_action,audio_action\n"
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
                            + "%.3f,%.3f,%s,%s,%s,%.4f,%s,%s,%s,%d,%d,%d,%d,%s,%s\n",
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
                    samsungSdkConnected ? "SAMSUNG_HEALTH_SENSOR_SDK" : "STANDARD_WEAR_OS",
                    sanitizeCsv(latestDecision),
                    features.motionSampleCount30,
                    features.heartSampleCount2,
                    features.ibiSampleCount5,
                    features.temperatureSampleCount30,
                    "NONE",
                    "WATCH_SPEAKER_PRIMARY_PHONE_FALLBACK"
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
                            + "%s,%s,%s: %s,%.4f,%s,%s,%s,0,0,0,0,%s,%s\n",
                    DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(nowUtc)),
                    elapsedMin,
                    baselineHeartRate,
                    latestStage,
                    latestSleepPhase,
                    event,
                    sanitizeCsv(details),
                    currentGain,
                    offWrist,
                    samsungSdkConnected ? "SAMSUNG_HEALTH_SENSOR_SDK" : "STANDARD_WEAR_OS",
                    sanitizeCsv(latestDecision),
                    "NONE",
                    "WATCH_SPEAKER_PRIMARY_PHONE_FALLBACK"
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

    private void publishHeartbeat(
            String qualityReason,
            SleepFeatureEngine.Snapshot features,
            double nremScore,
            double remScore,
            boolean arousal
    ) {
        if (config == null) return;
        String state = monitoringState();
        persistSession(state, "");
        SleepContinuityTracker.Snapshot continuity = continuityTracker.snapshot(
                timeProvider.elapsedRealtimeMs());
        String status;
        if ("DEGRADED".equals(state)) {
            status = "Logging only: " + qualityReason;
        } else if ("WAITING_FOR_SLEEP".equals(state)) {
            status = "Sleep detected. Waiting for sustained confirmation signals.";
        } else if ("CUE_WINDOW_ACTIVE".equals(state)) {
            status = "Sleep estimated. Conservative cue window is active.";
        } else {
            status = "Sleep estimated. Cue safety checks are active.";
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("state", state);
        payload.put("nightState", nightState().name());
        payload.put("status", status);
        payload.put("signalQuality", qualityReason);
        payload.put("batteryPercent", Integer.toString(batteryPercent()));
        payload.put("sleepPhase", latestSleepPhase);
        payload.put("capabilities", capabilitySnapshot().toJson());
        payload.put("capabilityProfile",
                liveSensorProvider.capabilities().profile().name());
        payload.put("awakeningCount", Integer.toString(continuity.awakeningCount));
        payload.put("wakeAfterSleepOnsetMs", Long.toString(continuity.wakeAfterSleepOnsetMs));
        payload.put("baselineNights", Integer.toString(personalBaseline.nights));
        payload.put("heartBpm", finite(features.heartMedian2));
        payload.put("baselineHeartBpm", finite(baselineHeartRate));
        payload.put("motionRms30", finite(features.motionRms30));
        payload.put("heartSd2", finite(features.heartSd2));
        payload.put("heartSlopePerMin", finite(features.heartSlopePerMin));
        payload.put("medianIbiMs", finite(features.medianIbiMs));
        payload.put("ibiRmssdMs", finite(features.ibiRmssdMs));
        payload.put("skinTemperatureC", finite(features.medianSkinTemperatureC));
        payload.put("skinTemperatureTrend", finite(features.skinTemperatureTrendCPerHour));
        payload.put("motionSamples30s", Integer.toString(features.motionSampleCount30));
        payload.put("heartSamples2m", Integer.toString(features.heartSampleCount2));
        payload.put("ibiSamples5m", Integer.toString(features.ibiSampleCount5));
        payload.put("temperatureSamples30m", Integer.toString(features.temperatureSampleCount30));
        payload.put("nremScore", finite(nremScore));
        payload.put("remScore", finite(remScore));
        payload.put("eligibilityState", latestStage);
        payload.put("arousal", Boolean.toString(arousal));
        payload.put("offWrist", Boolean.toString(offWrist));
        payload.put("decision", latestDecision);
        payload.put("cuesDisabled", Boolean.toString(cuesDisabled));
        payload.put("deepCues", Integer.toString(deepCues));
        payload.put("lucidCues", Integer.toString(lucidCues));
        payload.put("pendingCues", Integer.toString(pendingCues.size()));
        payload.put("rawPpgWindowActive", Boolean.toString(rawPpgWindowActive));
        payload.put("hapticsAction", "NONE_DURING_SLEEP");
        payload.put("audioAction", "WATCH_SPEAKER_PRIMARY_PHONE_FALLBACK");
        payload.put("sensorSource", samsungSdkConnected
                ? "SAMSUNG_HEALTH_SENSOR_SDK" : "STANDARD_WEAR_OS");
        ProtocolEnvelope envelope = ProtocolEnvelope.create(
                com.remote.DreamSync.shared.Protocol.TYPE_HEARTBEAT,
                config.sessionId, "", config.watchNodeId, config.phoneNodeId,
                timeProvider.utcMs(),
                com.remote.DreamSync.shared.Protocol.CONTROL_TTL_MS,
                payload);
        PutDataMapRequest map = PutDataMapRequest.create(
                com.remote.DreamSync.shared.Protocol.PATH_STATE_PREFIX + config.sessionId);
        map.getDataMap().putByteArray("envelope", envelope.encode());
        map.getDataMap().putLong("updatedUtcMs", timeProvider.utcMs());
        Wearable.getDataClient(this).putDataItem(map.asPutDataRequest())
                .addOnFailureListener(error -> WatchDiagnostics.session(
                        this, config, "HEARTBEAT_DELIVERY_FAILED",
                        "error=" + (error.getMessage() == null
                                ? error.getClass().getSimpleName() : error.getMessage())));
    }

    private String finite(double value) {
        return Double.isFinite(value)
                ? String.format(Locale.US, "%.4f", value) : "NaN";
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
                        WatchDiagnostics.session(this, config, "PHONE_LOST",
                                "cueingDisabled=true;loggingContinues=true");
                        broadcastStatus("Phone lost. Logging only. DreamSync remains On and will retry next sleep period.");
                    }
                })
                .addOnFailureListener(error -> {
                    terminateSession(SessionState.PHONE_LOST, "PHONE_LOST",
                            "phone reachability check failed");
                    persistEvent("PHONE_QUERY_FAILED", "", error.getMessage());
                    WatchDiagnostics.session(this, config, "PHONE_QUERY_FAILED",
                            error.getMessage());
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
            summary.put("estimatedSleepOnsetMinutesAfterConfirmationStart", estimatedSleepOnsetMs <= 0L
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
            NightSummary contractSummary = new NightSummary(
                    config == null ? "" : config.sessionId,
                    estimatedSleepOnsetMs <= 0L ? 0L
                            : sessionStartUtcMs + estimatedSleepOnsetMs - sessionStartMs,
                    continuity.awakeningCount,
                    continuity.wakeAfterSleepOnsetMs,
                    continuity.longestUninterruptedBoutMs,
                    deepCues,
                    cueLinkedArousals);
            summary.put("summaryContract", "NightSummary-v1");
            summary.put("summarySessionId", contractSummary.sessionId);
            summary.put("nightState", nightState().name());
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
            summary.put("lastDecision", latestDecision);
            summary.put("hapticsAction", "NONE_DURING_SLEEP");
            summary.put("audioAction", "WATCH_SPEAKER_PRIMARY_PHONE_FALLBACK");
            summary.put("sensorSource", samsungSdkConnected
                    ? "SAMSUNG_HEALTH_SENSOR_SDK" : "STANDARD_WEAR_OS");
            summary.put("capabilities", new JSONObject(capabilitySnapshot().toJson()));
            summary.put("personalBaselineNights", personalBaseline.nights);
            summary.put("personalizedBaselineReady", personalBaseline.personalized());
            summary.put("validPpgSamples", validPpgSamples);
            summary.put("rawPpgContinuous", false);
            summary.put("rawPpgWindowActiveAtEnd", rawPpgWindowActive);
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
        Wearable.getDataClient(this).putDataItem(request)
                .addOnSuccessListener(item -> WatchDiagnostics.session(
                        this, config, "SESSION_SUMMARY_DELIVERED",
                        "terminalState=" + terminalState
                                + ";deepCues=" + deepCues
                                + ";lucidCues=" + lucidCues))
                .addOnFailureListener(error -> WatchDiagnostics.session(
                        this, config, "SESSION_SUMMARY_DELIVERY_FAILED",
                        "error=" + (error.getMessage() == null
                                ? error.getClass().getSimpleName() : error.getMessage())));
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
        databaseExecutor.execute(() -> {
            dao.insertBaseline(night);
            List<NightBaselineEntity> stored = dao.latestBaselines();
            List<PersonalBaseline.Night> nights = new ArrayList<>();
            for (NightBaselineEntity item : stored) {
                nights.add(new PersonalBaseline.Night(
                        item.restingHeartRate, item.medianIbiMs, item.ibiRmssdMs,
                        item.skinTemperatureC, item.sleepOnsetMinutes));
            }
            PersonalBaseline.Result result = personalBaselineEngine.calculate(nights);
            AutoSleepConfig automatic = AutoSleepConfig.load(this);
            automatic.baselineNights = result.nights;
            automatic.learnedRestingHeartRateBpm = result.restingHeartRate;
            automatic.save(this);
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
        if (!sessionInitialized) {
            if (evaluatorHandler != null) evaluatorHandler.removeCallbacks(evaluator);
            if (sensorManager != null) sensorManager.unregisterListener(this);
            if (samsungBridge != null) samsungBridge.stop();
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (sensorThread != null) sensorThread.quitSafely();
            try { unregisterReceiver(cueAckReceiver); } catch (IllegalArgumentException ignored) {}
            if (databaseExecutor != null) databaseExecutor.shutdown();
            if (cueAudioExecutor != null) cueAudioExecutor.shutdownNow();
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
        if (cueAudioExecutor != null) cueAudioExecutor.shutdownNow();
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

    private static double latestValue(Deque<SleepFeatureEngine.Sample> samples) {
        SleepFeatureEngine.Sample sample = samples.peekLast();
        return sample == null ? Double.NaN : sample.value;
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
