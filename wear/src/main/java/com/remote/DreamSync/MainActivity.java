package com.remote.DreamSync;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.os.BatteryManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener2;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.health.services.client.HealthServices;
import androidx.health.services.client.MeasureCallback;
import androidx.health.services.client.MeasureClient;
import androidx.health.services.client.data.Availability;
import androidx.health.services.client.data.DataPointContainer;
import androidx.health.services.client.data.DataType;
import androidx.health.services.client.data.DataTypeAvailability;
import androidx.health.services.client.data.SampleDataPoint;
import androidx.wear.remote.interactions.RemoteActivityHelper;

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.common.util.concurrent.ListenableFuture;
import com.remote.DreamSync.shared.AdvancedSensorVerification;
import com.remote.DreamSync.shared.PeerSelector;
import com.remote.DreamSync.shared.ProtocolEnvelope;
import com.remote.DreamSync.shared.SystemCheckEvaluator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    static final String ACTION_CONFIG_CHANGED = "com.remote.DreamSync.CONFIG_CHANGED";
    static final String ACTION_AUTO_STATUS = "com.remote.DreamSync.AUTO_STATUS";

    private static final int REQUEST_FOREGROUND_PERMISSIONS = 501;
    private static final int REQUEST_BACKGROUND_PERMISSION = 502;
    private static final int REQUEST_SYSTEM_CHECK_PERMISSIONS = 503;
    private static final String READ_HEART_RATE = "android.permission.health.READ_HEART_RATE";
    private static final String READ_HEALTH_DATA_IN_BACKGROUND =
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND";
    private static final String READ_SKIN_TEMPERATURE =
            "android.permission.health.READ_SKIN_TEMPERATURE";
    private static final String READ_ADDITIONAL_HEALTH_DATA =
            "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA";
    private static final String USER_PREFS = "dreamsync_watch_user";
    private static final int CONFIG_SYNC_RETRY_LIMIT = 80;
    private static final long SYSTEM_CHECK_DURATION_MS = 75_000L;

    private TextView statusText;
    private boolean pendingEnable;
    private boolean foregroundRequestAttempted;
    private boolean backgroundRequestAttempted;
    private boolean receiverRegistered;
    private String pendingEnrollment = "";
    private int configSyncAttempt;
    private LiveSystemCheck liveSystemCheck;
    private boolean systemCheckMode;
    private String pendingSystemCheckId = "";
    private SensorProbeSelection pendingSystemCheckProbe = SensorProbeSelection.fromQuery(null);
    private final ExecutorService remoteExecutor = Executors.newSingleThreadExecutor();

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra("status");
            if (status != null && !status.isBlank()) statusText.setText(status);
            else refreshState();
            if (!pendingEnrollment.isEmpty()) continueSetupWhenSynced();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusText = findViewById(R.id.statusText);
        Button openPhoneButton = findViewById(R.id.openPhoneButton);
        ScrollView watchScroll = findViewById(R.id.watchScroll);
        watchScroll.requestFocus();
        watchScroll.post(() -> watchScroll.scrollTo(0, 0));
        openPhoneButton.setOnClickListener(v -> openPhoneApp());
        refreshState();
        boolean controlledLink = maybeHandleSystemCheckLink(getIntent())
                || maybeHandleSetupLink(getIntent());
        if (!controlledLink) statusText.postDelayed(this::openPhoneApp, 350L);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!maybeHandleSystemCheckLink(intent)) maybeHandleSetupLink(intent);
    }

    private boolean maybeHandleSystemCheckLink(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null || !"dreamsync".equals(data.getScheme())
                || !"watch".equals(data.getHost())
                || !"/system-check".equals(data.getPath())) return false;
        systemCheckMode = true;
        SensorProbeSelection probe =
                SensorProbeSelection.fromQuery(data.getQueryParameter("provider"));
        if (!probe.valid()) {
            statusText.setText("Unknown sensor provider. Return to the DreamSync phone app.");
            WatchDiagnostics.local(this, "SYSTEM_CHECK_BLOCKED",
                    "reason=unknown sensor provider");
            return true;
        }
        String checkId = safeIdentifier(data.getQueryParameter("check"));
        String enrollment = safeIdentifier(data.getQueryParameter("enrollment"));
        AutoSleepConfig config = AutoSleepConfig.load(this);
        boolean debugLab = (getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && "true".equals(data.getQueryParameter("lab"));
        if (debugLab && checkId.isEmpty()) checkId = "local-provider-probe";
        if (!debugLab && (checkId.isEmpty() || !config.validBinding()
                || !config.enrollmentId.equals(enrollment))) {
            statusText.setText("System check could not start. Return to the DreamSync phone app.");
            WatchDiagnostics.local(this, "SYSTEM_CHECK_BLOCKED",
                    "reason=missing or invalid phone-watch binding");
            return true;
        }
        if (SleepSessionService.isRunning()) {
            statusText.setText("A sleep session is active. Run the system check after it ends.");
            publishImmediateSystemCheckFailure(config, checkId,
                    "A sleep session is active.");
            return true;
        }
        pendingSystemCheckProbe = probe;
        startSystemCheckWhenPermissionsReady(config, checkId, probe);
        return true;
    }

    private void startSystemCheckWhenPermissionsReady(
            AutoSleepConfig config,
            String checkId,
            SensorProbeSelection probe
    ) {
        List<String> missing = new ArrayList<>();
        addIfMissing(missing, Manifest.permission.ACTIVITY_RECOGNITION);
        addIfMissing(missing, heartPermission());
        boolean samsungSdkAvailable = SamsungHealthSensorBridge.isSamsungWatch()
                && SamsungHealthSensorBridge.isPackaged();
        if (samsungSdkAvailable) {
            if (Build.VERSION.SDK_INT >= 36) {
                addIfMissing(missing, READ_SKIN_TEMPERATURE);
            }
            addIfMissing(missing, samsungPpgPermission());
        }
        if (!missing.isEmpty()) {
            pendingSystemCheckId = checkId;
            statusText.setText("Allow the listed watch sensor access to run the check.");
            requestPermissions(missing.toArray(new String[0]),
                    REQUEST_SYSTEM_CHECK_PERMISSIONS);
            WatchDiagnostics.auto(this, config, "SYSTEM_CHECK_PERMISSION_REQUESTED",
                    "activityMissing="
                            + !hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)
                            + ";heartMissing=" + !hasPermission(heartPermission())
                            + ";skinTemperatureMissing=" + (samsungSdkAvailable
                            && Build.VERSION.SDK_INT >= 36
                            && !hasPermission(READ_SKIN_TEMPERATURE))
                            + ";samsungPpgPermission=" + samsungPpgPermission()
                            + ";samsungPpgPermissionMissing=" + (samsungSdkAvailable
                            && !hasPermission(samsungPpgPermission())));
            return;
        }
        startLiveSystemCheck(config, checkId, probe);
    }

    private void startLiveSystemCheck(
            AutoSleepConfig config,
            String checkId,
            SensorProbeSelection probe
    ) {
        pendingSystemCheckId = "";
        if (liveSystemCheck != null) {
            liveSystemCheck.cancel("A newer check replaced this check.");
        }
        liveSystemCheck = new LiveSystemCheck(config, checkId, probe);
        liveSystemCheck.start();
    }

    private boolean maybeHandleSetupLink(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null || !"dreamsync".equals(data.getScheme())
                || !"watch".equals(data.getHost()) || !"/enable".equals(data.getPath())) {
            return false;
        }
        String enrollment = data.getQueryParameter("enrollment");
        pendingEnrollment = enrollment == null ? "" : enrollment;
        configSyncAttempt = 0;
        continueSetupWhenSynced();
        return true;
    }

    private void continueSetupWhenSynced() {
        if (pendingEnrollment.isEmpty()) {
            statusText.setText("Phone setup is incomplete. Return to the DreamSync phone app.");
            return;
        }
        AutoSleepConfig config = AutoSleepConfig.load(this);
        if (pendingEnrollment.equals(config.enrollmentId)) {
            pendingEnrollment = "";
            beginEnable();
            return;
        }
        configSyncAttempt++;
        if (configSyncAttempt >= CONFIG_SYNC_RETRY_LIMIT) {
            pendingEnrollment = "";
            statusText.setText("Setup did not reach the watch. Return to the phone and tap Turn DreamSync On again.");
            return;
        }
        statusText.setText("Receiving setup from the DreamSync phone app...");
        statusText.postDelayed(this::continueSetupWhenSynced, 750L);
    }

    private void beginEnable() {
        AutoSleepConfig config = AutoSleepConfig.load(this);
        if (config.enrollmentId.isEmpty() || config.phoneNodeId.isEmpty()) {
            statusText.setText("Setup is controlled by the DreamSync phone app.");
            return;
        }
        pendingEnable = true;
        foregroundRequestAttempted = false;
        backgroundRequestAttempted = false;
        if (getSharedPreferences(USER_PREFS, MODE_PRIVATE)
                .getBoolean("health_disclosure_accepted", false)) {
            ensurePermissionsThenEnable();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Automatic sleep-signal access")
                .setMessage("While DreamSync is On, the watch uses low-power activity signals to notice sleep every night. After likely sleep, it starts motion and heart-rate checks. Supported Galaxy Watches can also use IBI, skin temperature, and raw optical pulse readings. Cues stay off when data is stale, permission is removed, heart data is missing, or waking is detected. Data stays on your paired devices unless you export it from the phone.")
                .setPositiveButton("Continue", (dialog, which) -> {
                    getSharedPreferences(USER_PREFS, MODE_PRIVATE).edit()
                            .putBoolean("health_disclosure_accepted", true).apply();
                    ensurePermissionsThenEnable();
                })
                .setNegativeButton("Not now", (dialog, which) -> {
                    pendingEnable = false;
                    statusText.setText("Access was not approved. DreamSync remains inactive on this watch.");
                })
                .setCancelable(false)
                .show();
    }

    private void ensurePermissionsThenEnable() {
        String heartPermission = heartPermission();
        if (!foregroundRequestAttempted) {
            List<String> missing = new ArrayList<>();
            addIfMissing(missing, Manifest.permission.ACTIVITY_RECOGNITION);
            addIfMissing(missing, heartPermission);
            if (SamsungHealthSensorBridge.isSamsungWatch()
                    && SamsungHealthSensorBridge.isPackaged()) {
                if (Build.VERSION.SDK_INT >= 36) {
                    addIfMissing(missing, READ_SKIN_TEMPERATURE);
                }
                addIfMissing(missing, samsungPpgPermission());
            }
            if (Build.VERSION.SDK_INT >= 33) addIfMissing(
                    missing, Manifest.permission.POST_NOTIFICATIONS);
            if (!missing.isEmpty()) {
                foregroundRequestAttempted = true;
                requestPermissions(missing.toArray(new String[0]),
                        REQUEST_FOREGROUND_PERMISSIONS);
                statusText.setText("Allow the listed sleep-signal permissions.");
                return;
            }
        }
        if (!hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
            pendingEnable = false;
            statusText.setText("Activity access is required. Change it from the watch system settings, then retry from the phone.");
            return;
        }
        String backgroundPermission = backgroundHeartPermission();
        if (hasPermission(heartPermission) && backgroundPermission != null
                && !hasPermission(backgroundPermission) && !backgroundRequestAttempted) {
            backgroundRequestAttempted = true;
            requestPermissions(new String[]{backgroundPermission}, REQUEST_BACKGROUND_PERMISSION);
            statusText.setText("Choose Allow all the time for overnight heart-rate safety checks.");
            return;
        }
        pendingEnable = false;
        AutoSleepConfig config = AutoSleepConfig.load(this);
        config.enabled = true;
        config.save(this);
        AutoSleepMonitor.enable(this, config);
        statusText.setText("DreamSync is ready. All controls are on the phone.");
    }

    private void refreshState() {
        AutoSleepConfig config = AutoSleepConfig.load(this);
        if (SleepSessionService.isRunning()) statusText.setText(SleepSessionService.currentStatus());
        else if (!config.status.isBlank()) statusText.setText(config.status);
        else statusText.setText("DreamSync is controlled by the phone app.");
    }

    private void openPhoneApp() {
        statusText.setText("Opening DreamSync controls on the phone...");
        String boundPhone = AutoSleepConfig.load(this).phoneNodeId;
        Wearable.getCapabilityClient(this).getCapability(
                        com.remote.DreamSync.shared.Protocol.PHONE_CAPABILITY,
                        CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener(capability -> {
                    Node phone = chooseNode(capability.getNodes(), boundPhone);
                    if (phone != null) launchPhoneControls(phone.getId());
                    else openPhoneStoreOnConnectedDevice();
                })
                .addOnFailureListener(error -> openPhoneStoreOnConnectedDevice());
    }

    private void publishImmediateSystemCheckFailure(
            AutoSleepConfig config,
            String checkId,
            String reason
    ) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("status", "FAIL");
        payload.put("reason", reason);
        payload.put("motionSamples", "0");
        payload.put("heartSamples", "0");
        payload.put("motionRegistered", "false");
        payload.put("heartRegistered", "false");
        payload.put("hapticAvailable", "false");
        payload.put("hapticRequested", "false");
        payload.put("offWrist", "false");
        payload.put("additionalHealthPermission", "false");
        payload.put("advancedSensorsExpected", "false");
        payload.put("advancedSensorsVerified", "false");
        payload.put("advancedSensorState", "FAILED");
        payload.put("advancedSensorReason", reason);
        payload.put("durationMs", "0");
        payload.put("capabilityJson", "{}");
        publishSystemCheckResult(config, checkId, payload);
    }

    private void publishSystemCheckResult(
            AutoSleepConfig config,
            String checkId,
            Map<String, String> payload
    ) {
        ProtocolEnvelope envelope = ProtocolEnvelope.create(
                com.remote.DreamSync.shared.Protocol.TYPE_SYSTEM_CHECK_RESULT,
                config.enrollmentId,
                checkId,
                config.watchNodeId,
                config.phoneNodeId,
                System.currentTimeMillis(),
                com.remote.DreamSync.shared.Protocol.SYSTEM_CHECK_TTL_MS,
                payload);
        PutDataMapRequest map = PutDataMapRequest.create(
                com.remote.DreamSync.shared.Protocol.PATH_SYSTEM_CHECK_RESULT_PREFIX
                        + config.enrollmentId);
        map.getDataMap().putByteArray("envelope", envelope.encode());
        map.getDataMap().putLong("updatedUtcMs", System.currentTimeMillis());
        PutDataRequest request = map.asPutDataRequest();
        request.setUrgent();
        Wearable.getDataClient(this).putDataItem(request)
                .addOnSuccessListener(item -> {
                    String status = payload.getOrDefault("status", "FAIL");
                    String reason = payload.getOrDefault("reason", "No reason returned.");
                    WatchDiagnostics.auto(this, config, "SYSTEM_CHECK_RESULT",
                            "status=" + status + ";" + systemCheckDetails(payload));
                    statusText.setText("System check " + status + ": " + reason
                            + " Result sent. Read the full report on the phone.");
                })
                .addOnFailureListener(error -> {
                    WatchDiagnostics.auto(this, config, "SYSTEM_CHECK_RESULT_DELIVERY_FAILED",
                            safeMessage(error));
                    statusText.setText("System check finished, but the result could not reach the phone.");
                });
    }

    private String systemCheckDetails(Map<String, String> payload) {
        return "reason=" + payload.getOrDefault("reason", "")
                + ";motionSamples=" + payload.getOrDefault("motionSamples", "0")
                + ";heartSamples=" + payload.getOrDefault("heartSamples", "0")
                + ";lastHeartBpm=" + payload.getOrDefault("lastHeartBpm", "NaN")
                + ";motionRegistered="
                + payload.getOrDefault("motionRegistered", "false")
                + ";activeMotionSource="
                + payload.getOrDefault("activeMotionSource", "NONE")
                + ";standardMotionSamples="
                + payload.getOrDefault("standardMotionSamples", "0")
                + ";samsungMotionSamples="
                + payload.getOrDefault("samsungMotionSamples", "0")
                + ";heartRegistered="
                + payload.getOrDefault("heartRegistered", "false")
                + ";activeHeartSource="
                + payload.getOrDefault("activeHeartSource", "NONE")
                + ";motionSensor=" + payload.getOrDefault("motionSensor", "")
                + ";motionRegistrationDetails="
                + payload.getOrDefault("motionRegistrationDetails", "")
                + ";sensorInventory=" + payload.getOrDefault("sensorInventory", "")
                + ";measureSupported="
                + payload.getOrDefault("measureSupported", "false")
                + ";measureRegistered="
                + payload.getOrDefault("measureRegistered", "false")
                + ";measureAvailability="
                + payload.getOrDefault("measureAvailability", "")
                + ";watchAudioStarted="
                + payload.getOrDefault("watchAudioStarted", "false")
                + ";watchAudioCompleted="
                + payload.getOrDefault("watchAudioCompleted", "false")
                + ";watchAudioReason="
                + payload.getOrDefault("watchAudioReason", "")
                + ";watchAudioDiagnostics="
                + payload.getOrDefault("watchAudioDiagnostics", "")
                + ";offWrist=" + payload.getOrDefault("offWrist", "false")
                + ";hapticAvailable="
                + payload.getOrDefault("hapticAvailable", "false")
                + ";hapticRequested="
                + payload.getOrDefault("hapticRequested", "false")
                + ";samsungSdkConnected="
                + payload.getOrDefault("samsungSdkConnected", "false")
                + ";ibiSamples=" + payload.getOrDefault("ibiSamples", "0")
                + ";temperatureSamples="
                + payload.getOrDefault("temperatureSamples", "0")
                + ";ppgSamples=" + payload.getOrDefault("ppgSamples", "0")
                + ";samsungPpgPermission="
                + payload.getOrDefault("samsungPpgPermission", "")
                + ";samsungPpgPermissionGranted="
                + payload.getOrDefault("samsungPpgPermissionGranted", "false")
                + ";additionalHealthPermission="
                + payload.getOrDefault("additionalHealthPermission", "false")
                + ";advancedSensorsExpected="
                + payload.getOrDefault("advancedSensorsExpected", "false")
                + ";advancedSensorsVerified="
                + payload.getOrDefault("advancedSensorsVerified", "false")
                + ";advancedSensorState="
                + payload.getOrDefault("advancedSensorState", "UNAVAILABLE")
                + ";advancedSensorReason="
                + payload.getOrDefault("advancedSensorReason", "");
    }

    private final class LiveSystemCheck implements SensorEventListener2 {
        private static final long SENSOR_RELEASE_SETTLE_MS = 2_500L;
        private static final long MOTION_PROBE_TIMEOUT_MS = 6_000L;
        private static final long MEASURE_HEART_TIMEOUT_MS = 25_000L;
        private static final long HEART_RELEASE_SETTLE_MS = 2_000L;
        private static final long STANDARD_HEART_PROBE_TIMEOUT_MS = 12_000L;
        private static final long SAMSUNG_MOTION_STALE_MS = 3_000L;
        private static final long STANDARD_MOTION_STALE_MS = 6_000L;
        private static final long HEART_SOURCE_STALE_MS = 10_000L;
        private final AutoSleepConfig config;
        private final String checkId;
        private final SensorProbeSelection probe;
        private final SensorManager sensorManager;
        private final long startedElapsedMs = SystemClock.elapsedRealtime();
        private final Runnable finishRunnable = this::finish;
        private final Runnable progressFiveSeconds = () -> flushAndLogProgress("5s");
        private final Runnable progressFifteenSeconds = () -> flushAndLogProgress("15s");
        private final Runnable progressThirtySeconds = () -> flushAndLogProgress("30s");
        private final Runnable progressSixtySeconds = () -> flushAndLogProgress("60s");
        private final Runnable beginAfterSensorRelease = this::beginSensorCheck;
        private final Runnable delayedHealthMeasureStart = this::startHealthMeasureCheck;
        private final Runnable motionProbeTimeout =
                () -> advanceMotionProbe("no callback before timeout");
        private final Runnable measureHeartTimeout =
                () -> startStandardHeartFallback("Health Services timed out without data");
        private final Runnable standardHeartProbeTimeout =
                () -> advanceStandardHeartProbe("no valid callback before timeout");
        private final Runnable progressTick = new Runnable() {
            @Override public void run() {
                if (finished) return;
                updateProgressText();
                statusText.postDelayed(this, 1_000L);
            }
        };
        private Sensor accelerometer;
        private Sensor motionEventSensor;
        private List<Sensor> availableMotionSensors = Collections.emptyList();
        private List<Sensor> motionSensors = Collections.emptyList();
        private int motionProbeIndex = -1;
        private String motionProbeState = "waiting";
        private Sensor heartRate;
        private Sensor activeHeartRateSensor;
        private List<Sensor> availableHeartRateSensors = Collections.emptyList();
        private List<Sensor> registeredHeartRateSensors = Collections.emptyList();
        private int heartProbeIndex = -1;
        private String heartProbeState = "waiting";
        private MeasureClient measureClient;
        private MeasureCallback measureCallback;
        private WatchAudioOutput watchAudioOutput;
        private SamsungHealthSensorBridge samsungBridge;
        private Set<String> samsungTrackerTypes = new HashSet<>();
        private volatile int motionSamples;
        private volatile int currentMotionProbeSamples;
        private volatile int standardMotionSamples;
        private volatile int samsungMotionSamples;
        private volatile int standardHeartSamples;
        private volatile int measureHeartSamples;
        private volatile int samsungHeartSamples;
        private volatile int allSensorCallbacks;
        private volatile int rejectedMotionCallbacks;
        private volatile int rejectedHeartCallbacks;
        private volatile long firstSensorCallbackLatencyMs = -1L;
        private volatile int ibiSamples;
        private volatile int temperatureSamples;
        private volatile int ppgSamples;
        private volatile double lastHeartBpm = Double.NaN;
        private volatile double lastStandardHeartBpm = Double.NaN;
        private volatile double lastMeasureHeartBpm = Double.NaN;
        private volatile double lastSamsungHeartBpm = Double.NaN;
        private volatile float lastMotionX = Float.NaN;
        private volatile float lastMotionY = Float.NaN;
        private volatile float lastMotionZ = Float.NaN;
        private volatile double lastMotionMagnitude = Double.NaN;
        private volatile float lastStandardMotionX = Float.NaN;
        private volatile float lastStandardMotionY = Float.NaN;
        private volatile float lastStandardMotionZ = Float.NaN;
        private volatile double lastStandardMotionMagnitude = Double.NaN;
        private volatile float lastSamsungMotionX = Float.NaN;
        private volatile float lastSamsungMotionY = Float.NaN;
        private volatile float lastSamsungMotionZ = Float.NaN;
        private volatile double lastSamsungMotionMagnitude = Double.NaN;
        private volatile long lastStandardMotionElapsedMs = -1L;
        private volatile long lastSamsungMotionElapsedMs = -1L;
        private volatile long lastStandardHeartElapsedMs = -1L;
        private volatile long lastMeasureHeartElapsedMs = -1L;
        private volatile long lastSamsungHeartElapsedMs = -1L;
        private volatile boolean offWrist;
        private volatile boolean offWristKnown;
        private volatile boolean samsungConnected;
        private volatile boolean samsungPolicyBlocked;
        private volatile String activeMotionSource = "NONE";
        private volatile String activeHeartSource = "NONE";
        private volatile String standardMotionProbeState = "waiting";
        private volatile String samsungMotionState = "waiting";
        private boolean activityPermission;
        private boolean heartPermissionGranted;
        private boolean additionalHealthPermission;
        private boolean motionRegistered;
        private boolean standardMotionRegistered;
        private boolean motionRetryAttempted;
        private boolean heartRegistered;
        private boolean standardHeartFallbackAttempted;
        private boolean standardHeartRegistrationStarted;
        private boolean measureUnregisterRequested;
        private boolean passiveWasRegistered;
        private boolean passiveResumeRequested;
        private volatile boolean passivePauseCompleted;
        private volatile boolean passivePauseSucceeded;
        private String passivePauseDetail = "not_attempted";
        private volatile boolean measureSupported;
        private volatile boolean measureRegistered;
        private boolean sensorSilenceDetected;
        private boolean powerInteractive;
        private boolean powerSaveMode;
        private boolean deviceIdleMode;
        private int batteryPercent = -1;
        private boolean charging;
        private int flushRequests;
        private int flushCompletions;
        private int samsungFlushRequests;
        private int samsungFlushAccepted;
        private int samsungFlushCompletions;
        private boolean hapticAvailable;
        private boolean hapticRequested;
        private volatile boolean watchAudioStarted;
        private volatile WatchAudioOutput.Result watchAudioResult;
        private boolean finished;
        private String sensorInventory = "";
        private String motionRegistrationDetails = "";
        private String heartRegistrationDetails = "";
        private String measureAvailability = "not_checked";
        private String samsungDetail = "";
        private String forcedFailure = "";

        LiveSystemCheck(
                AutoSleepConfig config,
                String checkId,
                SensorProbeSelection probe
        ) {
            this.config = config;
            this.checkId = checkId;
            this.probe = probe;
            sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        }

        void start() {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            HapticResult haptic = requestAwakeSystemCheckHaptic();
            hapticAvailable = haptic.available;
            hapticRequested = haptic.requested;
            activityPermission = hasPermission(Manifest.permission.ACTIVITY_RECOGNITION);
            heartPermissionGranted = hasPermission(heartPermission());
            additionalHealthPermission = hasPermission(samsungPpgPermission());
            WatchDiagnostics.auto(MainActivity.this, config, "SYSTEM_CHECK_STARTED",
                    "checkId=" + checkId + ";durationSeconds=" + probe.durationMs() / 1_000L
                            + ";providerProbe=" + probe.provider()
                            + ";activityPermission=" + activityPermission
                            + ";heartPermission=" + heartPermissionGranted
                            + ";hapticAvailable=" + hapticAvailable
                            + ";hapticRequested=" + hapticRequested
                            + ";samsungPpgPermission=" + samsungPpgPermission()
                            + ";samsungPpgPermissionGranted="
                            + additionalHealthPermission);
            if (!activityPermission || !heartPermissionGranted || sensorManager == null) {
                if (sensorManager == null) forcedFailure = "Watch sensor service is unavailable.";
                if (!activityPermission) forcedFailure = "Activity permission is missing on the watch.";
                if (!heartPermissionGranted) forcedFailure = "Heart-rate permission is missing on the watch.";
                WatchDiagnostics.auto(MainActivity.this, config,
                        "SYSTEM_CHECK_PREREQUISITE_FAILED", forcedFailure);
                finish();
                return;
            }
            passiveWasRegistered = config.passiveRegistered;
            statusText.setText("Preparing exclusive sensor access for the live check...");
            AutoSleepMonitor.pauseForSensorAccess(MainActivity.this, config,
                    "live_system_check", (paused, detail) -> runOnUiThread(() -> {
                        passivePauseCompleted = true;
                        passivePauseSucceeded = paused;
                        passivePauseDetail = detail;
                        if (finished) {
                            resumePassiveIfNeeded();
                        } else {
                            statusText.setText("Preparing watch sensors. Keep the watch awake and worn snugly...");
                            WatchDiagnostics.auto(MainActivity.this, config,
                                    "SYSTEM_CHECK_SENSOR_RELEASE_SETTLE_STARTED",
                                    "delayMs=" + SENSOR_RELEASE_SETTLE_MS
                                            + ";pauseSucceeded=" + paused
                                            + ";detail=" + detail);
                            statusText.postDelayed(
                                    beginAfterSensorRelease, SENSOR_RELEASE_SETTLE_MS);
                        }
                    }));
        }

        private void beginSensorCheck() {
            if (finished) return;
            captureDeviceState();
            sensorInventory = MotionSensorRegistration.inventory(sensorManager);
            WatchDiagnostics.auto(MainActivity.this, config, "SYSTEM_CHECK_SENSOR_INVENTORY",
                    sensorInventory);
            WatchDiagnostics.auto(MainActivity.this, config, "SYSTEM_CHECK_DEVICE_STATE",
                    deviceStateDetail());
            List<Sensor> selectedMotionSensors = new ArrayList<>();
            if (probe.standardMotion()) {
                for (Sensor sensor : MotionSensorRegistration.candidates(sensorManager)) {
                    if (probe.acceptsMotionType(sensor.getType())) {
                        selectedMotionSensors.add(sensor);
                    }
                }
            }
            availableMotionSensors = Collections.unmodifiableList(selectedMotionSensors);
            startNextMotionProbe("initial probe");
            availableHeartRateSensors = probe.standardHeart()
                    ? HeartRateSensorRegistration.candidates(sensorManager)
                    : Collections.emptyList();
            heartRate = availableHeartRateSensors.isEmpty()
                    ? null : availableHeartRateSensors.get(0);
            if (!availableHeartRateSensors.isEmpty()) {
                WatchDiagnostics.auto(MainActivity.this, config,
                        "SYSTEM_CHECK_STANDARD_HEART_FALLBACK_READY",
                        "candidates=" + availableHeartRateSensors.size());
            } else {
                WatchDiagnostics.auto(MainActivity.this, config,
                        "SYSTEM_CHECK_STANDARD_HEART_MISSING", "type=HEART_RATE;list=empty");
            }
            if (probe.samsungMotion() || probe.samsungHeart()) startSamsungCheck();
            statusText.setText((hapticRequested
                    ? "Test vibration sent. " : "Watch vibration unavailable. ")
                    + "Move your wrist now. Waiting for live motion values.");
            if (probe.healthServicesHeart()) {
                statusText.postDelayed(delayedHealthMeasureStart, 8_000L);
            } else if (probe.standardHeart()) {
                statusText.postDelayed(
                        () -> startStandardHeartFallback("isolated standard heart probe"),
                        8_000L);
            }
            statusText.postDelayed(this::startWatchAudioCheck, 8_000L);
            statusText.postDelayed(progressFiveSeconds, 5_000L);
            statusText.postDelayed(progressFifteenSeconds, 15_000L);
            statusText.postDelayed(progressThirtySeconds, 30_000L);
            statusText.postDelayed(progressSixtySeconds, 60_000L);
            statusText.post(progressTick);
            statusText.postDelayed(finishRunnable, probe.durationMs());
        }

        private void startNextMotionProbe(String reason) {
            if (finished || currentMotionProbeSamples
                    >= SystemCheckEvaluator.MIN_MOTION_SAMPLES) return;
            statusText.removeCallbacks(motionProbeTimeout);
            if (!motionSensors.isEmpty()) {
                sensorManager.unregisterListener(this, motionSensors.get(0));
            }
            standardMotionRegistered = false;
            motionSensors = Collections.emptyList();
            motionEventSensor = null;
            currentMotionProbeSamples = 0;
            standardMotionSamples = 0;
            lastStandardMotionX = Float.NaN;
            lastStandardMotionY = Float.NaN;
            lastStandardMotionZ = Float.NaN;
            lastStandardMotionMagnitude = Double.NaN;
            lastStandardMotionElapsedMs = -1L;
            motionRetryAttempted = motionProbeIndex >= 0;
            while (++motionProbeIndex < availableMotionSensors.size()) {
                Sensor candidate = availableMotionSensors.get(motionProbeIndex);
                accelerometer = candidate;
                MotionSensorRegistration.Result result =
                        MotionSensorRegistration.registerCandidate(
                                sensorManager, this, candidate,
                                SensorManager.SENSOR_DELAY_NORMAL, 0, null);
                motionRegistrationDetails = appendDetails(
                        motionRegistrationDetails,
                        "probe=" + motionProbeIndex + ";reason=" + reason
                                + ";" + result.details);
                WatchDiagnostics.auto(MainActivity.this, config,
                        result.registered ? "SYSTEM_CHECK_MOTION_PROBE_STARTED"
                                : "SYSTEM_CHECK_MOTION_PROBE_REGISTRATION_FAILED",
                        "probe=" + motionProbeIndex + ";reason=" + reason
                                + ";sensor=" + MotionSensorRegistration.describe(candidate)
                                + ";registered=" + result.registered);
                if (!result.registered) continue;
                standardMotionRegistered = true;
                motionSensors = result.sensors;
                standardMotionProbeState = "trying " + safeSensorName(candidate);
                refreshActiveMotionSource();
                requestSensorFlush("motion_probe_" + motionProbeIndex);
                statusText.postDelayed(motionProbeTimeout, MOTION_PROBE_TIMEOUT_MS);
                updateProgressText();
                return;
            }
            standardMotionRegistered = false;
            standardMotionProbeState = "blocked: check Sensors Off and Power saving";
            refreshActiveMotionSource();
            sensorSilenceDetected = allSensorCallbacks == 0;
            WatchDiagnostics.auto(MainActivity.this, config,
                    "SYSTEM_CHECK_MOTION_PROBES_EXHAUSTED",
                    "reason=" + reason + ";candidateCount="
                            + availableMotionSensors.size()
                            + ";allSensorCallbacks=" + allSensorCallbacks);
            updateProgressText();
        }

        private void advanceMotionProbe(String reason) {
            if (finished || currentMotionProbeSamples
                    >= SystemCheckEvaluator.MIN_MOTION_SAMPLES) return;
            startNextMotionProbe(reason);
        }

        private void startWatchAudioCheck() {
            watchAudioOutput = new WatchAudioOutput(MainActivity.this);
            WatchAudioOutput.Result preflight = watchAudioOutput.preflight();
            WatchDiagnostics.auto(MainActivity.this, config,
                    preflight.success ? "SYSTEM_CHECK_WATCH_AUDIO_PREFLIGHT_PASS"
                            : "SYSTEM_CHECK_WATCH_AUDIO_PREFLIGHT_FAIL",
                    "reason=" + preflight.reason + ";" + preflight.diagnostics);
            remoteExecutor.execute(() -> {
                WatchAudioOutput.Result result = watchAudioOutput.playSystemCheck(() -> {
                            watchAudioStarted = true;
                            WatchDiagnostics.auto(MainActivity.this, config,
                                    "SYSTEM_CHECK_WATCH_AUDIO_STARTED",
                                    watchAudioOutput.diagnostics());
                        });
                watchAudioResult = result;
                WatchDiagnostics.auto(MainActivity.this, config,
                        result.success ? "SYSTEM_CHECK_WATCH_AUDIO_COMPLETED"
                                : "SYSTEM_CHECK_WATCH_AUDIO_FAILED",
                        "started=" + result.started + ";reason=" + result.reason
                                + ";" + result.diagnostics);
                runOnUiThread(this::updateProgressText);
            });
        }

        private void startHealthMeasureCheck() {
            if (finished || standardHeartFallbackAttempted || measureClient != null) return;
            heartProbeState = "Health Services starting";
            measureClient = HealthServices.getClient(MainActivity.this).getMeasureClient();
            measureCallback = new MeasureCallback() {
                @Override public void onRegistered() {
                    measureRegistered = true;
                    WatchDiagnostics.auto(MainActivity.this, config,
                            "SYSTEM_CHECK_MEASURE_HEART_REGISTERED",
                            "dataType=HEART_RATE_BPM");
                    if (finished || standardHeartFallbackAttempted) {
                        unregisterMeasureThenStartStandard(
                                "registration completed after fallback request");
                        return;
                    }
                    heartProbeState = "Health Services acquiring";
                    statusText.removeCallbacks(measureHeartTimeout);
                    statusText.postDelayed(measureHeartTimeout, MEASURE_HEART_TIMEOUT_MS);
                    updateProgressText();
                }

                @Override public void onRegistrationFailed(Throwable error) {
                    measureRegistered = false;
                    WatchDiagnostics.auto(MainActivity.this, config,
                            "SYSTEM_CHECK_MEASURE_HEART_REGISTRATION_FAILED",
                            safeMessage(error));
                    startStandardHeartFallback("Health Services registration failed");
                    updateProgressText();
                }

                @Override public void onAvailabilityChanged(
                        androidx.health.services.client.data.DeltaDataType<?, ?> dataType,
                        Availability availability) {
                    measureAvailability = availability == null
                            ? "null" : availability.toString();
                    if (DataTypeAvailability.UNAVAILABLE_DEVICE_OFF_BODY.equals(availability)) {
                        offWrist = true;
                        offWristKnown = true;
                    } else if (availability != null
                            && !DataTypeAvailability.ACQUIRING.equals(availability)) {
                        offWrist = false;
                        offWristKnown = true;
                    }
                    heartProbeState = "Health Services " + measureAvailability;
                    WatchDiagnostics.auto(MainActivity.this, config,
                            "SYSTEM_CHECK_MEASURE_HEART_AVAILABILITY",
                            "availability=" + measureAvailability);
                    updateProgressText();
                }

                @Override public void onDataReceived(DataPointContainer data) {
                    List<SampleDataPoint<Double>> points = data.getData(DataType.HEART_RATE_BPM);
                    int accepted = 0;
                    boolean firstAcceptedReading = measureHeartSamples == 0;
                    for (SampleDataPoint<Double> point : points) {
                        Double bpm = point.getValue();
                        if (bpm != null && Double.isFinite(bpm)
                                && bpm >= 30.0 && bpm <= 220.0) {
                            lastMeasureHeartBpm = bpm;
                            lastMeasureHeartElapsedMs = SystemClock.elapsedRealtime();
                            measureHeartSamples++;
                            accepted++;
                        }
                    }
                    if (accepted > 0) refreshActiveHeartSource();
                    WatchDiagnostics.auto(MainActivity.this, config,
                            "SYSTEM_CHECK_MEASURE_HEART_DATA",
                            "received=" + points.size() + ";accepted=" + accepted
                                    + ";total=" + measureHeartSamples
                                    + ";lastBpm=" + lastMeasureHeartBpm
                                    + ";elapsedSinceCheckMs="
                                    + Math.max(0L, SystemClock.elapsedRealtime()
                                    - startedElapsedMs)
                                    + ";thread=" + Thread.currentThread().getName());
                    if (accepted > 0) {
                        heartProbeState = "Health Services reading";
                        statusText.removeCallbacks(measureHeartTimeout);
                        if (firstAcceptedReading) {
                            WatchDiagnostics.auto(MainActivity.this, config,
                                    "SYSTEM_CHECK_HEART_READING_CONFIRMED",
                                    "source=HEALTH_SERVICES;bpm=" + lastMeasureHeartBpm);
                        }
                    }
                    updateProgressText();
                }
            };
            ListenableFuture<androidx.health.services.client.data.MeasureCapabilities>
                    capabilities = measureClient.getCapabilitiesAsync();
            capabilities.addListener(() -> {
                try {
                    measureSupported = capabilities.get().getSupportedDataTypesMeasure()
                            .contains(DataType.HEART_RATE_BPM);
                    WatchDiagnostics.auto(MainActivity.this, config,
                            "SYSTEM_CHECK_MEASURE_CAPABILITIES",
                            "heartRateSupported=" + measureSupported + ";supported="
                                    + capabilities.get().getSupportedDataTypesMeasure());
                    if (measureSupported && !finished) {
                        heartProbeState = "Health Services registering";
                        measureClient.registerMeasureCallback(
                                DataType.HEART_RATE_BPM,
                                ContextCompat.getMainExecutor(MainActivity.this),
                                measureCallback);
                    } else if (!measureSupported) {
                        startStandardHeartFallback("Health Services unsupported");
                    }
                } catch (Exception error) {
                    WatchDiagnostics.auto(MainActivity.this, config,
                            "SYSTEM_CHECK_MEASURE_CAPABILITIES_FAILED", safeMessage(error));
                    startStandardHeartFallback("Health Services capability check failed");
                }
            }, ContextCompat.getMainExecutor(MainActivity.this));
            statusText.removeCallbacks(measureHeartTimeout);
            statusText.postDelayed(measureHeartTimeout, MEASURE_HEART_TIMEOUT_MS + 5_000L);
        }

        private void startStandardHeartFallback(String reason) {
            if (finished || standardHeartFallbackAttempted || measureHeartSamples > 0) return;
            standardHeartFallbackAttempted = true;
            heartProbeState = "releasing Health Services";
            statusText.removeCallbacks(measureHeartTimeout);
            WatchDiagnostics.auto(MainActivity.this, config,
                    "SYSTEM_CHECK_MEASURE_HEART_FALLBACK_REQUESTED",
                    "reason=" + reason + ";availability=" + measureAvailability
                            + ";registered=" + measureRegistered);
            unregisterMeasureThenStartStandard(reason);
        }

        private void unregisterMeasureThenStartStandard(String reason) {
            if (measureClient != null && measureCallback != null
                    && measureRegistered && !measureUnregisterRequested) {
                measureUnregisterRequested = true;
                ListenableFuture<Void> unregister = measureClient.unregisterMeasureCallbackAsync(
                        DataType.HEART_RATE_BPM, measureCallback);
                unregister.addListener(() -> {
                    String detail = "success=true";
                    try {
                        unregister.get();
                    } catch (Exception error) {
                        detail = "success=false;error=" + safeMessage(error);
                    }
                    measureRegistered = false;
                    WatchDiagnostics.auto(MainActivity.this, config,
                            "SYSTEM_CHECK_MEASURE_HEART_RELEASED_FOR_FALLBACK",
                            "reason=" + reason + ";" + detail
                                    + ";settleMs=" + HEART_RELEASE_SETTLE_MS);
                    if (finished) {
                        resumePassiveIfNeeded();
                    } else if (!standardHeartRegistrationStarted) {
                        statusText.postDelayed(
                                () -> startNextStandardHeartProbe(reason),
                                HEART_RELEASE_SETTLE_MS);
                    }
                }, ContextCompat.getMainExecutor(MainActivity.this));
                return;
            }
            measureRegistered = false;
            if (finished) {
                resumePassiveIfNeeded();
                return;
            }
            if (!standardHeartRegistrationStarted) {
                statusText.postDelayed(() -> startNextStandardHeartProbe(reason),
                        HEART_RELEASE_SETTLE_MS);
            }
        }

        private void startNextStandardHeartProbe(String reason) {
            if (finished || standardHeartSamples > 0) return;
            standardHeartRegistrationStarted = true;
            statusText.removeCallbacks(standardHeartProbeTimeout);
            if (!registeredHeartRateSensors.isEmpty()) {
                sensorManager.unregisterListener(this, registeredHeartRateSensors.get(0));
            }
            registeredHeartRateSensors = Collections.emptyList();
            activeHeartRateSensor = null;
            while (++heartProbeIndex < availableHeartRateSensors.size()) {
                Sensor candidate = availableHeartRateSensors.get(heartProbeIndex);
                heartRate = candidate;
                HeartRateSensorRegistration.Result result =
                        HeartRateSensorRegistration.registerCandidate(
                                sensorManager, this, candidate,
                                SensorManager.SENSOR_DELAY_NORMAL, 0, null);
                heartRegistrationDetails = appendDetails(
                        heartRegistrationDetails,
                        "probe=" + heartProbeIndex + ";reason=" + reason
                                + ";" + result.details);
                WatchDiagnostics.auto(MainActivity.this, config,
                        result.registered ? "SYSTEM_CHECK_STANDARD_HEART_PROBE_STARTED"
                                : "SYSTEM_CHECK_STANDARD_HEART_PROBE_REGISTRATION_FAILED",
                        "probe=" + heartProbeIndex + ";reason=" + reason
                                + ";sensor=" + MotionSensorRegistration.describe(candidate)
                                + ";registered=" + result.registered);
                if (!result.registered) continue;
                heartRegistered = true;
                registeredHeartRateSensors = result.sensors;
                heartProbeState = "trying " + safeSensorName(candidate);
                requestSensorFlush("heart_probe_" + heartProbeIndex);
                statusText.postDelayed(
                        standardHeartProbeTimeout, STANDARD_HEART_PROBE_TIMEOUT_MS);
                updateProgressText();
                return;
            }
            heartRegistered = false;
            heartProbeState = "blocked: wear watch snugly and check Sensors Off";
            WatchDiagnostics.auto(MainActivity.this, config,
                    "SYSTEM_CHECK_STANDARD_HEART_PROBES_EXHAUSTED",
                    "reason=" + reason + ";candidateCount="
                            + availableHeartRateSensors.size()
                            + ";allSensorCallbacks=" + allSensorCallbacks);
            updateProgressText();
        }

        private void advanceStandardHeartProbe(String reason) {
            if (finished || standardHeartSamples > 0) return;
            startNextStandardHeartProbe(reason);
        }

        private void startSamsungCheck() {
            samsungBridge = new SamsungHealthSensorBridge(MainActivity.this,
                    new SamsungHealthSensorBridge.Listener() {
                        @Override public void onCapabilities(
                                Set<String> trackerTypes, boolean connected, String detail) {
                            samsungTrackerTypes = trackerTypes == null
                                    ? new HashSet<>() : new HashSet<>(trackerTypes);
                            samsungConnected = connected;
                            samsungDetail = detail == null ? "" : detail;
                            WatchDiagnostics.auto(MainActivity.this, config,
                                    "SYSTEM_CHECK_SAMSUNG_CAPABILITIES",
                                    "connected=" + connected + ";trackers="
                                            + samsungTrackerTypes + ";detail=" + samsungDetail);
                            runOnUiThread(() -> {
                                refreshActiveMotionSource();
                                refreshActiveHeartSource();
                                updateProgressText();
                            });
                        }

                        @Override public void onAccelerometer(
                                long timestampMs, double xMs2, double yMs2, double zMs2) {
                            if (finished || !Double.isFinite(xMs2)
                                    || !Double.isFinite(yMs2) || !Double.isFinite(zMs2)) {
                                return;
                            }
                            boolean firstReading = samsungMotionSamples == 0;
                            samsungMotionSamples++;
                            lastSamsungMotionX = (float) xMs2;
                            lastSamsungMotionY = (float) yMs2;
                            lastSamsungMotionZ = (float) zMs2;
                            lastSamsungMotionMagnitude = Math.sqrt(
                                    xMs2 * xMs2 + yMs2 * yMs2 + zMs2 * zMs2);
                            lastSamsungMotionElapsedMs = SystemClock.elapsedRealtime();
                            samsungMotionState = samsungMotionSamples
                                    >= SystemCheckEvaluator.MIN_MOTION_SAMPLES
                                    ? "reading Samsung Health Sensor SDK"
                                    : "verifying Samsung motion " + samsungMotionSamples + "/"
                                    + SystemCheckEvaluator.MIN_MOTION_SAMPLES;
                            refreshActiveMotionSource();
                            if (firstReading || samsungMotionSamples % 100 == 0) {
                                WatchDiagnostics.auto(MainActivity.this, config,
                                        "SYSTEM_CHECK_SAMSUNG_MOTION_DATA",
                                        "count=" + samsungMotionSamples + ";x=" + xMs2
                                                + ";y=" + yMs2 + ";z=" + zMs2
                                                + ";timestampMs=" + timestampMs);
                            }
                            runOnUiThread(LiveSystemCheck.this::updateProgressText);
                        }

                        @Override public void onHeartRate(
                                long timestampMs, double bpm, List<Integer> ibiMs,
                                boolean watchOffWrist) {
                            offWrist = watchOffWrist;
                            offWristKnown = true;
                            boolean firstAcceptedReading = samsungHeartSamples == 0;
                            if (Double.isFinite(bpm) && bpm >= 30.0 && bpm <= 220.0) {
                                lastSamsungHeartBpm = bpm;
                                lastSamsungHeartElapsedMs = SystemClock.elapsedRealtime();
                                samsungHeartSamples++;
                                refreshActiveHeartSource();
                                if (firstAcceptedReading) {
                                    WatchDiagnostics.auto(MainActivity.this, config,
                                            "SYSTEM_CHECK_HEART_READING_CONFIRMED",
                                            "source=SAMSUNG_HEALTH_SENSOR_SDK;bpm=" + bpm);
                                }
                            }
                            if (ibiMs != null) ibiSamples += ibiMs.size();
                            WatchDiagnostics.auto(MainActivity.this, config,
                                    "SYSTEM_CHECK_SAMSUNG_HEART_DATA",
                                    "bpm=" + bpm + ";heartSamples=" + samsungHeartSamples
                                            + ";ibiSamples=" + ibiSamples
                                            + ";offWrist=" + offWrist);
                            runOnUiThread(LiveSystemCheck.this::updateProgressText);
                        }

                        @Override public void onSkinTemperature(
                                long timestampMs, double skinC, double ambientC) {
                            if (Double.isFinite(skinC)) temperatureSamples++;
                        }

                        @Override public void onPpgSample(long timestampMs, boolean valid) {
                            if (valid) ppgSamples++;
                        }

                        @Override public void onTrackerFlush(
                                String trackerName, boolean requestAccepted,
                                boolean completed, String detail) {
                            if (completed) {
                                samsungFlushCompletions++;
                            } else {
                                samsungFlushRequests++;
                                if (requestAccepted) samsungFlushAccepted++;
                            }
                            WatchDiagnostics.auto(MainActivity.this, config,
                                    completed
                                            ? "SYSTEM_CHECK_SAMSUNG_FLUSH_COMPLETED"
                                            : "SYSTEM_CHECK_SAMSUNG_FLUSH_REQUESTED",
                                    "tracker=" + trackerName
                                            + ";accepted=" + requestAccepted
                                            + ";completed=" + completed
                                            + ";detail=" + detail);
                        }

                        @Override public void onBridgeError(String code, String detail) {
                            samsungDetail = code + ": " + detail;
                            String normalized = samsungDetail.toLowerCase(java.util.Locale.US);
                            if (normalized.contains("policy")
                                    || normalized.contains("signature is not registered")) {
                                samsungPolicyBlocked = true;
                            }
                            WatchDiagnostics.auto(MainActivity.this, config,
                                    "SYSTEM_CHECK_SAMSUNG_SENSOR_ERROR", samsungDetail);
                        }
                    });
            samsungBridge.start(additionalHealthPermission, probe.samsungTrackerTypes());
        }

        private void flushAndLogProgress(String checkpoint) {
            if (!finished && samsungBridge != null && samsungConnected) {
                int accepted = samsungBridge.flushTrackers("system_check_" + checkpoint);
                WatchDiagnostics.auto(MainActivity.this, config,
                        "SYSTEM_CHECK_SAMSUNG_FLUSH_SUMMARY",
                        "checkpoint=" + checkpoint + ";acceptedTrackers=" + accepted
                                + ";knownTrackers=" + samsungTrackerTypes);
            }
            logProgress(checkpoint);
        }

        @Override public void onSensorChanged(SensorEvent event) {
            if (finished || event == null || event.sensor == null) return;
            allSensorCallbacks++;
            if (allSensorCallbacks == 1) {
                firstSensorCallbackLatencyMs = Math.max(0L,
                        SystemClock.elapsedRealtime() - startedElapsedMs);
                WatchDiagnostics.auto(MainActivity.this, config,
                        "SYSTEM_CHECK_FIRST_RAW_SENSOR_CALLBACK",
                        "latencyMs=" + firstSensorCallbackLatencyMs
                                + ";sensor=" + MotionSensorRegistration.describe(event.sensor)
                                + ";valueCount="
                                + (event.values == null ? -1 : event.values.length)
                                + ";accuracy=" + event.accuracy
                                + ";thread=" + Thread.currentThread().getName());
            }
            if (MotionSensorRegistration.accepts(motionSensors, event.sensor)
                    && event.values != null && event.values.length >= 3
                    && Float.isFinite(event.values[0])
                    && Float.isFinite(event.values[1])
                    && Float.isFinite(event.values[2])) {
                if (motionEventSensor == null) {
                    motionEventSensor = event.sensor;
                    standardMotionProbeState = "receiving " + safeSensorName(event.sensor);
                    WatchDiagnostics.auto(MainActivity.this, config,
                            "SYSTEM_CHECK_MOTION_SOURCE_CONFIRMED",
                            MotionSensorRegistration.describe(motionEventSensor));
                }
                if (!MotionSensorRegistration.accepts(
                        Collections.singletonList(motionEventSensor), event.sensor)) return;
                standardMotionSamples++;
                currentMotionProbeSamples++;
                lastStandardMotionX = event.values[0];
                lastStandardMotionY = event.values[1];
                lastStandardMotionZ = event.values[2];
                lastStandardMotionMagnitude = Math.sqrt(
                        lastStandardMotionX * lastStandardMotionX
                                + lastStandardMotionY * lastStandardMotionY
                                + lastStandardMotionZ * lastStandardMotionZ);
                lastStandardMotionElapsedMs = SystemClock.elapsedRealtime();
                if (currentMotionProbeSamples >= SystemCheckEvaluator.MIN_MOTION_SAMPLES) {
                    statusText.removeCallbacks(motionProbeTimeout);
                    standardMotionProbeState = "reading " + safeSensorName(event.sensor);
                }
                refreshActiveMotionSource();
                if (standardMotionSamples == 1 || standardMotionSamples % 100 == 0) {
                    WatchDiagnostics.auto(MainActivity.this, config,
                            "SYSTEM_CHECK_STANDARD_MOTION_DATA",
                            "count=" + standardMotionSamples + ";sensorType="
                                    + event.sensor.getType() + ";accuracy=" + event.accuracy
                                    + ";x=" + event.values[0] + ";y=" + event.values[1]
                                    + ";z=" + event.values[2]);
                }
            } else if (HeartRateSensorRegistration.accepts(
                    registeredHeartRateSensors, event.sensor)
                    && event.values != null && event.values.length > 0
                    && event.accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE
                    && Float.isFinite(event.values[0])
                    && event.values[0] >= 30f && event.values[0] <= 220f) {
                if (activeHeartRateSensor == null) {
                    activeHeartRateSensor = event.sensor;
                    statusText.removeCallbacks(standardHeartProbeTimeout);
                    heartProbeState = "reading " + safeSensorName(event.sensor);
                    WatchDiagnostics.auto(MainActivity.this, config,
                            "SYSTEM_CHECK_STANDARD_HEART_SOURCE_CONFIRMED",
                            MotionSensorRegistration.describe(activeHeartRateSensor));
                    WatchDiagnostics.auto(MainActivity.this, config,
                            "SYSTEM_CHECK_HEART_READING_CONFIRMED",
                            "source=STANDARD_SENSOR_MANAGER;bpm=" + event.values[0]);
                }
                if (!HeartRateSensorRegistration.accepts(
                        Collections.singletonList(activeHeartRateSensor), event.sensor)) return;
                standardHeartSamples++;
                lastStandardHeartBpm = event.values[0];
                lastStandardHeartElapsedMs = SystemClock.elapsedRealtime();
                refreshActiveHeartSource();
                WatchDiagnostics.auto(MainActivity.this, config,
                        "SYSTEM_CHECK_STANDARD_HEART_DATA",
                        "count=" + standardHeartSamples + ";bpm=" + lastStandardHeartBpm
                                + ";accuracy=" + event.accuracy);
            } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER
                    || event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION
                    || event.sensor.getType() == Sensor.TYPE_GRAVITY) {
                rejectedMotionCallbacks++;
                if (rejectedMotionCallbacks == 1) {
                    WatchDiagnostics.auto(MainActivity.this, config,
                            "SYSTEM_CHECK_MOTION_CALLBACK_REJECTED",
                            "sensor=" + MotionSensorRegistration.describe(event.sensor)
                                    + ";valueCount="
                                    + (event.values == null ? -1 : event.values.length)
                                    + ";accuracy=" + event.accuracy);
                }
            } else if (event.sensor.getType() == Sensor.TYPE_HEART_RATE) {
                rejectedHeartCallbacks++;
                if (rejectedHeartCallbacks == 1) {
                    WatchDiagnostics.auto(MainActivity.this, config,
                            "SYSTEM_CHECK_HEART_CALLBACK_REJECTED",
                            "sensor=" + MotionSensorRegistration.describe(event.sensor)
                                    + ";valueCount="
                                    + (event.values == null ? -1 : event.values.length)
                                    + ";accuracy=" + event.accuracy
                                    + ";value=" + (event.values == null
                                    || event.values.length == 0 ? "missing" : event.values[0]));
                }
            }
            updateProgressText();
        }

        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
            WatchDiagnostics.auto(MainActivity.this, config,
                    "SYSTEM_CHECK_SENSOR_ACCURACY_CHANGED",
                    "sensor=" + MotionSensorRegistration.describe(sensor)
                            + ";accuracy=" + accuracy);
        }

        @Override public void onFlushCompleted(Sensor sensor) {
            flushCompletions++;
            WatchDiagnostics.auto(MainActivity.this, config,
                    "SYSTEM_CHECK_SENSOR_FLUSH_COMPLETED",
                    "sensor=" + MotionSensorRegistration.describe(sensor)
                            + ";flushCompletions=" + flushCompletions);
        }

        private void refreshActiveMotionSource() {
            long now = SystemClock.elapsedRealtime();
            long samsungAgeMs = sourceAgeMs(now, lastSamsungMotionElapsedMs);
            long standardAgeMs = sourceAgeMs(now, lastStandardMotionElapsedMs);
            boolean samsungStable = samsungMotionSamples
                    >= SystemCheckEvaluator.MIN_MOTION_SAMPLES;
            boolean samsungFresh = samsungConnected && samsungStable
                    && samsungAgeMs >= 0L && samsungAgeMs <= SAMSUNG_MOTION_STALE_MS;
            boolean standardFresh = standardMotionSamples > 0
                    && standardAgeMs >= 0L && standardAgeMs <= STANDARD_MOTION_STALE_MS;
            String nextSource = samsungFresh ? "SAMSUNG_HEALTH_SENSOR_SDK"
                    : standardFresh ? "STANDARD_SENSOR_MANAGER" : "NONE";
            if (!nextSource.equals(activeMotionSource)) {
                String reason = samsungFresh ? "Samsung stream is stable and fresh"
                        : standardFresh ? "Samsung stream unavailable or stale; using standard"
                        : "no fresh motion stream";
                WatchDiagnostics.auto(MainActivity.this, config,
                        "SYSTEM_CHECK_MOTION_SOURCE_SWITCHED",
                        "from=" + activeMotionSource + ";to=" + nextSource
                                + ";reason=" + reason
                                + ";standardSamples=" + standardMotionSamples
                                + ";standardAgeMs=" + standardAgeMs
                                + ";samsungSamples=" + samsungMotionSamples
                                + ";samsungAgeMs=" + samsungAgeMs
                                + ";standardProbeKept=" + standardMotionRegistered);
                activeMotionSource = nextSource;
            }
            motionRegistered = standardMotionRegistered || samsungMotionSamples > 0;
            if (samsungFresh || (!standardFresh && samsungMotionSamples > 0
                    && lastSamsungMotionElapsedMs >= lastStandardMotionElapsedMs)) {
                motionSamples = samsungMotionSamples;
                lastMotionX = lastSamsungMotionX;
                lastMotionY = lastSamsungMotionY;
                lastMotionZ = lastSamsungMotionZ;
                lastMotionMagnitude = lastSamsungMotionMagnitude;
                motionProbeState = samsungFresh ? samsungMotionState
                        : "waiting for fresh Samsung motion";
            } else if (standardMotionSamples > 0) {
                motionSamples = standardMotionSamples;
                lastMotionX = lastStandardMotionX;
                lastMotionY = lastStandardMotionY;
                lastMotionZ = lastStandardMotionZ;
                lastMotionMagnitude = lastStandardMotionMagnitude;
                motionProbeState = standardFresh ? standardMotionProbeState
                        : "waiting for fresh standard motion";
            } else {
                motionSamples = 0;
                motionProbeState = standardMotionProbeState;
            }
        }

        private void refreshActiveHeartSource() {
            long now = SystemClock.elapsedRealtime();
            long samsungAgeMs = sourceAgeMs(now, lastSamsungHeartElapsedMs);
            long measureAgeMs = sourceAgeMs(now, lastMeasureHeartElapsedMs);
            long standardAgeMs = sourceAgeMs(now, lastStandardHeartElapsedMs);
            boolean samsungFresh = samsungConnected && samsungHeartSamples > 0
                    && samsungAgeMs >= 0L && samsungAgeMs <= HEART_SOURCE_STALE_MS;
            boolean measureFresh = measureHeartSamples > 0
                    && measureAgeMs >= 0L && measureAgeMs <= HEART_SOURCE_STALE_MS;
            boolean standardFresh = standardHeartSamples > 0
                    && standardAgeMs >= 0L && standardAgeMs <= HEART_SOURCE_STALE_MS;
            String nextSource = samsungFresh ? "SAMSUNG_HEALTH_SENSOR_SDK"
                    : measureFresh ? "HEALTH_SERVICES"
                    : standardFresh ? "STANDARD_SENSOR_MANAGER" : "NONE";
            if (!nextSource.equals(activeHeartSource)) {
                WatchDiagnostics.auto(MainActivity.this, config,
                        "SYSTEM_CHECK_HEART_SOURCE_SWITCHED",
                        "from=" + activeHeartSource + ";to=" + nextSource
                                + ";standardSamples=" + standardHeartSamples
                                + ";standardAgeMs=" + standardAgeMs
                                + ";measureSamples=" + measureHeartSamples
                                + ";measureAgeMs=" + measureAgeMs
                                + ";samsungSamples=" + samsungHeartSamples
                                + ";samsungAgeMs=" + samsungAgeMs);
                activeHeartSource = nextSource;
            }
            if (samsungFresh) lastHeartBpm = lastSamsungHeartBpm;
            else if (measureFresh) lastHeartBpm = lastMeasureHeartBpm;
            else if (standardFresh) lastHeartBpm = lastStandardHeartBpm;
        }

        private long sourceAgeMs(long nowElapsedMs, long sampleElapsedMs) {
            return sampleElapsedMs < 0L ? -1L : Math.max(0L, nowElapsedMs - sampleElapsedMs);
        }

        private int usableMotionSamples() {
            return Math.max(standardMotionSamples, samsungMotionSamples);
        }

        private void updateProgressText() {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                statusText.post(this::updateProgressText);
                return;
            }
            if (finished) return;
            refreshActiveMotionSource();
            refreshActiveHeartSource();
            int totalHeart = standardHeartSamples + measureHeartSamples + samsungHeartSamples;
            String audio = watchAudioResult == null
                    ? watchAudioStarted ? "playing" : "waiting"
                    : watchAudioResult.success ? "passed" : "failed";
            String heartState = totalHeart > 0 && !"NONE".equals(activeHeartSource)
                    ? "reading " + activeHeartSource : heartProbeState;
            statusText.setText(SystemCheckProgress.format(
                    SystemClock.elapsedRealtime() - startedElapsedMs,
                    probe.durationMs(),
                    motionSamples,
                    motionProbeState,
                    lastMotionX,
                    lastMotionY,
                    lastMotionZ,
                    lastMotionMagnitude,
                    totalHeart,
                    heartState,
                    lastHeartBpm,
                    audio));
        }

        private void logProgress(String checkpoint) {
            if (finished) return;
            WatchDiagnostics.auto(MainActivity.this, config,
                    "SYSTEM_CHECK_PROGRESS",
                    "checkpoint=" + checkpoint
                            + ";motionRegistered=" + motionRegistered
                            + ";motionProbeState=" + motionProbeState
                            + ";activeMotionSource=" + activeMotionSource
                            + ";motionSamples=" + motionSamples
                            + ";usableMotionSamples=" + usableMotionSamples()
                            + ";standardMotionRegistered=" + standardMotionRegistered
                            + ";standardMotionProbeState=" + standardMotionProbeState
                            + ";standardMotionSamples=" + standardMotionSamples
                            + ";standardMotionAgeMs=" + sourceAgeMs(
                            SystemClock.elapsedRealtime(), lastStandardMotionElapsedMs)
                            + ";samsungMotionSamples=" + samsungMotionSamples
                            + ";samsungMotionState=" + samsungMotionState
                            + ";samsungMotionAgeMs=" + sourceAgeMs(
                            SystemClock.elapsedRealtime(), lastSamsungMotionElapsedMs)
                            + ";motionX=" + lastMotionX
                            + ";motionY=" + lastMotionY
                            + ";motionZ=" + lastMotionZ
                            + ";motionMagnitude=" + lastMotionMagnitude
                            + ";allSensorCallbacks=" + allSensorCallbacks
                            + ";rejectedMotionCallbacks=" + rejectedMotionCallbacks
                            + ";standardHeartRegistered=" + heartRegistered
                            + ";standardHeartSamples=" + standardHeartSamples
                            + ";lastStandardHeartBpm=" + lastStandardHeartBpm
                            + ";heartProbeState=" + heartProbeState
                            + ";activeHeartSource=" + activeHeartSource
                            + ";lastHeartBpm=" + lastHeartBpm
                            + ";measureSupported=" + measureSupported
                            + ";measureRegistered=" + measureRegistered
                            + ";measureAvailability=" + measureAvailability
                            + ";measureHeartSamples=" + measureHeartSamples
                            + ";lastMeasureHeartBpm=" + lastMeasureHeartBpm
                            + ";rejectedHeartCallbacks=" + rejectedHeartCallbacks
                            + ";passivePauseCompleted=" + passivePauseCompleted
                            + ";passivePauseSucceeded=" + passivePauseSucceeded
                            + ";samsungConnected=" + samsungConnected
                            + ";samsungHeartSamples=" + samsungHeartSamples
                            + ";lastSamsungHeartBpm=" + lastSamsungHeartBpm
                            + ";watchAudioStarted=" + watchAudioStarted
                            + ";watchAudioFinished=" + (watchAudioResult != null)
                            + ";flushRequests=" + flushRequests
                            + ";flushCompletions=" + flushCompletions
                            + ";deviceState=" + deviceStateDetail());
            updateProgressText();
        }

        void cancel(String reason) {
            if (finished) return;
            forcedFailure = reason;
            finish();
        }

        void logActivityState(String state) {
            captureDeviceState();
            WatchDiagnostics.auto(MainActivity.this, config,
                    "SYSTEM_CHECK_ACTIVITY_STATE",
                    "state=" + state + ";" + deviceStateDetail());
        }

        private void logProviderProbeResult() {
            boolean available = false;
            boolean registered = false;
            int callbacks = 0;
            int validSamples = 0;
            if (probe.standardMotion()) {
                available = !availableMotionSensors.isEmpty();
                registered = standardMotionRegistered;
                callbacks = standardMotionSamples + rejectedMotionCallbacks;
                validSamples = standardMotionSamples;
            } else if (probe.samsungMotion()) {
                available = samsungTrackerTypes.contains("ACCELEROMETER_CONTINUOUS");
                registered = samsungConnected && available;
                callbacks = samsungMotionSamples;
                validSamples = samsungMotionSamples;
            } else if (probe.healthServicesHeart()) {
                available = measureSupported;
                registered = measureRegistered;
                callbacks = measureHeartSamples;
                validSamples = measureHeartSamples;
            } else if (probe.standardHeart()) {
                available = !availableHeartRateSensors.isEmpty();
                registered = heartRegistered;
                callbacks = standardHeartSamples + rejectedHeartCallbacks;
                validSamples = standardHeartSamples;
            } else if (probe.samsungHeart()) {
                available = samsungTrackerTypes.contains("HEART_RATE_CONTINUOUS");
                registered = samsungConnected && available;
                callbacks = samsungHeartSamples;
                validSamples = samsungHeartSamples;
            }
            boolean permissionGranted = probe.standardMotion() || probe.samsungMotion()
                    ? activityPermission : heartPermissionGranted;
            WatchDiagnostics.local(MainActivity.this, "PROVIDER_PROBE_RESULT",
                    "provider=" + probe.provider()
                            + ";available=" + available
                            + ";permissionGranted=" + permissionGranted
                            + ";policyBlocked=" + samsungPolicyBlocked
                            + ";registered=" + registered
                            + ";callbacks=" + callbacks
                            + ";validSamples=" + validSamples
                            + ";offWrist=" + offWrist
                            + ";deviceState=" + deviceStateDetail()
                            + ";detail=" + samsungDetail);
        }

        private void finish() {
            if (finished) return;
            finished = true;
            statusText.removeCallbacks(finishRunnable);
            statusText.removeCallbacks(progressFiveSeconds);
            statusText.removeCallbacks(progressFifteenSeconds);
            statusText.removeCallbacks(progressThirtySeconds);
            statusText.removeCallbacks(progressSixtySeconds);
            statusText.removeCallbacks(beginAfterSensorRelease);
            statusText.removeCallbacks(delayedHealthMeasureStart);
            statusText.removeCallbacks(motionProbeTimeout);
            statusText.removeCallbacks(measureHeartTimeout);
            statusText.removeCallbacks(standardHeartProbeTimeout);
            statusText.removeCallbacks(progressTick);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (sensorManager != null) sensorManager.unregisterListener(this);
            if (measureClient != null && measureCallback != null && measureRegistered
                    && !measureUnregisterRequested) {
                measureUnregisterRequested = true;
                ListenableFuture<Void> unregister = measureClient.unregisterMeasureCallbackAsync(
                        DataType.HEART_RATE_BPM, measureCallback);
                unregister.addListener(() -> {
                    try {
                        unregister.get();
                        WatchDiagnostics.auto(MainActivity.this, config,
                                "SYSTEM_CHECK_MEASURE_HEART_UNREGISTERED", "success=true");
                    } catch (Exception error) {
                        WatchDiagnostics.auto(MainActivity.this, config,
                                "SYSTEM_CHECK_MEASURE_HEART_UNREGISTER_FAILED",
                                safeMessage(error));
                    }
                    measureRegistered = false;
                    resumePassiveIfNeeded();
                }, ContextCompat.getMainExecutor(MainActivity.this));
            } else if (!measureRegistered) {
                resumePassiveIfNeeded();
            }
            if (samsungBridge != null) samsungBridge.stop();

            int totalHeartSamples = standardHeartSamples
                    + measureHeartSamples + samsungHeartSamples;
            int usableMotionSamples = usableMotionSamples();
            if (probe.isolated()) logProviderProbeResult();
            boolean usableMotionRegistration = standardMotionRegistered
                    || samsungMotionSamples > 0;
            sensorSilenceDetected = allSensorCallbacks == 0 && usableMotionSamples == 0
                    && measureHeartSamples == 0 && samsungHeartSamples == 0;
            boolean usableHeartRegistration = heartRegistered
                    || measureRegistered
                    || (samsungConnected
                    && samsungTrackerTypes.contains("HEART_RATE_CONTINUOUS"));
            SystemCheckEvaluator.Result evaluation = new SystemCheckEvaluator().evaluate(
                    activityPermission,
                    heartPermissionGranted,
                    usableMotionRegistration,
                    usableHeartRegistration,
                    usableMotionSamples,
                    totalHeartSamples,
                    offWrist);
            boolean passed = evaluation.passed && forcedFailure.isEmpty();
            AdvancedSensorVerification.Result advanced =
                    new AdvancedSensorVerification().evaluate(
                            samsungConnected,
                            samsungTrackerTypes,
                            true,
                            additionalHealthPermission,
                            ibiSamples,
                            temperatureSamples,
                            ppgSamples);
            String reason = forcedFailure.isEmpty() ? evaluation.reason : forcedFailure;
            if (sensorSilenceDetected) {
                String silenceReason = "The watch delivered no sensor data. Turn off Sensors "
                        + "Off and Power saving, wear the watch snugly, then retry.";
                reason = appendReason(reason, silenceReason);
                WatchDiagnostics.auto(MainActivity.this, config,
                        "SYSTEM_CHECK_GLOBAL_SENSOR_SILENCE",
                        "classification=NO_SENSOR_CALLBACKS;" + deviceStateDetail()
                                + ";motionCandidates=" + availableMotionSensors.size()
                                + ";heartCandidates=" + availableHeartRateSensors.size()
                                + ";measureAvailability=" + measureAvailability);
            }
            if (watchAudioResult == null) {
                passed = false;
                reason = appendReason(reason, "The watch audio check did not finish.");
            } else if (!watchAudioResult.success) {
                passed = false;
                reason = appendReason(reason,
                        "Watch audio failed: " + watchAudioResult.reason);
            }
            if (passed && advanced.expected && !advanced.passed) {
                reason = "Core readings passed. Advanced suite not verified: "
                        + advanced.reason;
            }
            SensorCapabilitySnapshot capability = new SensorCapabilitySnapshot(
                    usableMotionSamples >= SystemCheckEvaluator.MIN_MOTION_SAMPLES,
                    totalHeartSamples >= SystemCheckEvaluator.MIN_HEART_SAMPLES,
                    SamsungHealthSensorBridge.isPackaged(),
                    samsungConnected,
                    samsungTrackerTypes,
                    passed,
                    advanced.passed,
                    watchAudioResult != null && watchAudioResult.success,
                    activityPermission && heartPermissionGranted,
                    samsungDetail + "; " + advanced.reason);
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("status", passed ? "PASS" : "FAIL");
            payload.put("providerProbe", probe.provider());
            payload.put("reason", reason);
            payload.put("motionSamples", Integer.toString(usableMotionSamples));
            payload.put("motionProbeState", motionProbeState);
            payload.put("activeMotionSource", activeMotionSource);
            payload.put("motionX", Float.toString(lastMotionX));
            payload.put("motionY", Float.toString(lastMotionY));
            payload.put("motionZ", Float.toString(lastMotionZ));
            payload.put("motionMagnitude", Double.toString(lastMotionMagnitude));
            payload.put("allSensorCallbacks", Integer.toString(allSensorCallbacks));
            payload.put("firstSensorCallbackLatencyMs",
                    Long.toString(firstSensorCallbackLatencyMs));
            payload.put("rejectedMotionCallbacks",
                    Integer.toString(rejectedMotionCallbacks));
            payload.put("heartSamples", Integer.toString(totalHeartSamples));
            payload.put("lastHeartBpm", Double.isFinite(lastHeartBpm)
                    ? String.format(java.util.Locale.US, "%.1f", lastHeartBpm) : "NaN");
            payload.put("motionRegistered", Boolean.toString(usableMotionRegistration));
            payload.put("standardMotionRegistered",
                    Boolean.toString(standardMotionRegistered));
            payload.put("motionRetryAttempted", Boolean.toString(motionRetryAttempted));
            payload.put("heartRegistered", Boolean.toString(usableHeartRegistration));
            payload.put("motionSensor", "SAMSUNG_HEALTH_SENSOR_SDK".equals(
                    activeMotionSource)
                    ? "Samsung Health Sensor SDK ACCELEROMETER_CONTINUOUS"
                    : MotionSensorRegistration.describe(
                    motionEventSensor == null ? accelerometer : motionEventSensor));
            payload.put("motionRegistrationDetails", motionRegistrationDetails);
            payload.put("standardMotionSamples", Integer.toString(standardMotionSamples));
            payload.put("standardMotionProbeState", standardMotionProbeState);
            payload.put("samsungMotionSamples", Integer.toString(samsungMotionSamples));
            payload.put("samsungMotionState", samsungMotionState);
            payload.put("sensorInventory", sensorInventory);
            payload.put("standardHeartRegistered", Boolean.toString(heartRegistered));
            payload.put("standardHeartSamples", Integer.toString(standardHeartSamples));
            payload.put("lastStandardHeartBpm", Double.toString(lastStandardHeartBpm));
            payload.put("rejectedHeartCallbacks",
                    Integer.toString(rejectedHeartCallbacks));
            payload.put("standardHeartSensor", MotionSensorRegistration.describe(
                    activeHeartRateSensor == null ? heartRate : activeHeartRateSensor));
            payload.put("heartRegistrationDetails", heartRegistrationDetails);
            payload.put("heartProbeState", heartProbeState);
            payload.put("measureSupported", Boolean.toString(measureSupported));
            payload.put("measureRegistered", Boolean.toString(measureRegistered));
            payload.put("measureAvailability", measureAvailability);
            payload.put("measureHeartSamples", Integer.toString(measureHeartSamples));
            payload.put("lastMeasureHeartBpm", Double.toString(lastMeasureHeartBpm));
            payload.put("passiveWasRegistered", Boolean.toString(passiveWasRegistered));
            payload.put("passivePauseCompleted", Boolean.toString(passivePauseCompleted));
            payload.put("passivePauseSucceeded", Boolean.toString(passivePauseSucceeded));
            payload.put("passivePauseDetail", passivePauseDetail);
            payload.put("samsungHeartSamples", Integer.toString(samsungHeartSamples));
            payload.put("lastSamsungHeartBpm", Double.toString(lastSamsungHeartBpm));
            payload.put("activeHeartSource", activeHeartSource);
            payload.put("watchAudioStarted", Boolean.toString(watchAudioStarted));
            payload.put("watchAudioCompleted", Boolean.toString(
                    watchAudioResult != null && watchAudioResult.success));
            payload.put("watchAudioReason", watchAudioResult == null
                    ? "not_finished" : watchAudioResult.reason);
            payload.put("watchAudioGain", String.format(
                    java.util.Locale.US, "%.4f", WatchAudioOutput.SYSTEM_CHECK_GAIN));
            payload.put("watchAudioWaveform",
                    com.remote.DreamSync.shared.CueWaveforms.SYSTEM_CHECK_VERSION);
            payload.put("watchMediaVolume", Integer.toString(
                    watchAudioOutput == null ? -1 : watchAudioOutput.mediaVolume()));
            payload.put("watchMediaVolumeMax", Integer.toString(
                    watchAudioOutput == null ? -1 : watchAudioOutput.mediaVolumeMax()));
            payload.put("watchAudioDiagnostics", watchAudioResult == null
                    ? watchAudioOutput == null ? "not_started" : watchAudioOutput.diagnostics()
                    : watchAudioResult.diagnostics);
            payload.put("hapticAvailable", Boolean.toString(hapticAvailable));
            payload.put("hapticRequested", Boolean.toString(hapticRequested));
            payload.put("activityPermission", Boolean.toString(activityPermission));
            payload.put("heartPermission", Boolean.toString(heartPermissionGranted));
            payload.put("additionalHealthPermission",
                    Boolean.toString(additionalHealthPermission));
            payload.put("samsungPpgPermission", samsungPpgPermission());
            payload.put("samsungPpgPermissionGranted",
                    Boolean.toString(additionalHealthPermission));
            payload.put("offWrist", Boolean.toString(offWrist));
            payload.put("offWristKnown", Boolean.toString(offWristKnown));
            payload.put("sensorSilenceDetected", Boolean.toString(sensorSilenceDetected));
            payload.put("deviceState", deviceStateDetail());
            payload.put("flushRequests", Integer.toString(flushRequests));
            payload.put("flushCompletions", Integer.toString(flushCompletions));
            payload.put("durationMs", Long.toString(
                    Math.max(0L, SystemClock.elapsedRealtime() - startedElapsedMs)));
            payload.put("samsungSdkPackaged", Boolean.toString(
                    SamsungHealthSensorBridge.isPackaged()));
            payload.put("samsungSdkConnected", Boolean.toString(samsungConnected));
            payload.put("ibiSamples", Integer.toString(ibiSamples));
            payload.put("temperatureSamples", Integer.toString(temperatureSamples));
            payload.put("ppgSamples", Integer.toString(ppgSamples));
            payload.put("samsungFlushRequests", Integer.toString(samsungFlushRequests));
            payload.put("samsungFlushAccepted", Integer.toString(samsungFlushAccepted));
            payload.put("samsungFlushCompletions",
                    Integer.toString(samsungFlushCompletions));
            payload.put("advancedSensorsExpected", Boolean.toString(advanced.expected));
            payload.put("advancedSensorsVerified", Boolean.toString(advanced.passed));
            payload.put("advancedSensorState", !advanced.expected
                    ? "UNAVAILABLE" : advanced.passed ? "VERIFIED" : "FAILED");
            payload.put("advancedSensorReason", advanced.reason);
            payload.put("capabilityJson", capability.toJson());
            publishSystemCheckResult(config, checkId, payload);
            liveSystemCheck = null;
        }

        private void resumePassiveIfNeeded() {
            if (!passiveWasRegistered || !passivePauseCompleted
                    || passiveResumeRequested) return;
            passiveResumeRequested = true;
            AutoSleepMonitor.resumeAfterSensorAccess(MainActivity.this, config,
                    "live_system_check_finished");
        }

        private void captureDeviceState() {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            powerInteractive = power != null && power.isInteractive();
            powerSaveMode = power != null && power.isPowerSaveMode();
            deviceIdleMode = power != null && power.isDeviceIdleMode();
            BatteryManager battery = getSystemService(BatteryManager.class);
            batteryPercent = battery == null ? -1
                    : battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            Intent state = registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int status = state == null ? -1
                    : state.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
        }

        private String deviceStateDetail() {
            ActivityManager.RunningAppProcessInfo process =
                    new ActivityManager.RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(process);
            return "interactive=" + powerInteractive
                    + ";powerSave=" + powerSaveMode
                    + ";deviceIdle=" + deviceIdleMode
                    + ";batteryPercent=" + batteryPercent
                    + ";charging=" + charging
                    + ";windowFocus=" + hasWindowFocus()
                    + ";processImportance=" + process.importance
                    + ";mainThread=" + (Looper.myLooper() == Looper.getMainLooper());
        }

        private void requestSensorFlush(String source) {
            flushRequests++;
            boolean accepted = false;
            String error = "";
            try {
                accepted = sensorManager.flush(this);
            } catch (RuntimeException exception) {
                error = exception.getClass().getSimpleName() + ":" + safeMessage(exception);
            }
            WatchDiagnostics.auto(MainActivity.this, config,
                    "SYSTEM_CHECK_SENSOR_FLUSH_REQUESTED",
                    "source=" + source + ";accepted=" + accepted
                            + ";flushRequests=" + flushRequests
                            + (error.isEmpty() ? "" : ";error=" + error));
        }

        private String appendDetails(String existing, String detail) {
            if (existing == null || existing.isBlank()) return detail;
            return existing + " || " + detail;
        }

        private String safeSensorName(Sensor sensor) {
            if (sensor == null) return "sensor";
            String name = sensor.getName() == null ? "sensor" : sensor.getName();
            return name.replace(';', '_').replace('|', '_');
        }

        private String appendReason(String existing, String additional) {
            if (existing == null || existing.isBlank()
                    || "Motion and heart-rate readings passed the live watch check."
                    .equals(existing)) return additional;
            return existing + " " + additional;
        }
    }

    private static String safeIdentifier(String value) {
        if (value == null) return "";
        String cleaned = value.replaceAll("[^A-Za-z0-9._:-]", "");
        return cleaned.substring(0, Math.min(128, cleaned.length()));
    }

    private HapticResult requestAwakeSystemCheckHaptic() {
        Vibrator vibrator;
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager manager = (VibratorManager) getSystemService(
                    Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = manager == null ? null : manager.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
        boolean available = vibrator != null && vibrator.hasVibrator();
        if (!available) {
            AutoSleepConfig config = AutoSleepConfig.load(this);
            WatchDiagnostics.auto(this, config, "SYSTEM_CHECK_HAPTIC_UNAVAILABLE",
                    "watchHasVibrator=false;awakeCheckOnly=true");
            return new HapticResult(false, false);
        }
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(
                    240L, VibrationEffect.DEFAULT_AMPLITUDE));
            AutoSleepConfig config = AutoSleepConfig.load(this);
            WatchDiagnostics.auto(this, config, "SYSTEM_CHECK_HAPTIC_REQUESTED",
                    "durationMs=240;awakeCheckOnly=true;hapticsDuringSleep=OFF");
            return new HapticResult(true, true);
        } catch (RuntimeException error) {
            WatchDiagnostics.auto(this, AutoSleepConfig.load(this),
                    "SYSTEM_CHECK_HAPTIC_FAILED", safeMessage(error));
            return new HapticResult(true, false);
        }
    }

    private static final class HapticResult {
        final boolean available;
        final boolean requested;

        HapticResult(boolean available, boolean requested) {
            this.available = available;
            this.requested = requested;
        }
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown";
        Throwable cause = error.getCause() == null ? error : error.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName() : message;
    }

    private void openPhoneStoreOnConnectedDevice() {
        Wearable.getNodeClient(this).getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    Node phone = chooseNode(nodes, AutoSleepConfig.load(this).phoneNodeId);
                    if (phone == null) {
                        statusText.setText("No paired Android phone is connected.");
                        return;
                    }
                    Intent store = new Intent(Intent.ACTION_VIEW)
                            .setData(Uri.parse("market://details?id=" + getPackageName()))
                            .addCategory(Intent.CATEGORY_BROWSABLE);
                    launchRemote(store, phone.getId(),
                            "Open DreamSync on the phone to control this watch.");
                })
                .addOnFailureListener(error -> statusText.setText(
                        "Could not reach the paired phone."));
    }

    private void launchPhoneControls(String phoneNodeId) {
        Intent controls = new Intent(Intent.ACTION_VIEW)
                .setData(Uri.parse("dreamsync://phone/control"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        launchRemote(controls, phoneNodeId, "DreamSync controls opened on the phone.");
    }

    private void launchRemote(Intent intent, String nodeId, String successMessage) {
        try {
            ListenableFuture<Void> launch = new RemoteActivityHelper(this, remoteExecutor)
                    .startRemoteActivity(intent, nodeId);
            launch.addListener(() -> {
                try {
                    launch.get();
                    runOnUiThread(() -> statusText.setText(successMessage));
                } catch (Exception error) {
                    runOnUiThread(() -> statusText.setText(
                            "Could not open the phone. Use the DreamSync phone app directly."));
                }
            }, remoteExecutor);
        } catch (RuntimeException error) {
            statusText.setText("Could not open the phone. Use the DreamSync phone app directly.");
        }
    }

    private Node chooseNode(Iterable<Node> nodes, String boundNodeId) {
        if (nodes == null) return null;
        List<Node> source = new ArrayList<>();
        List<PeerSelector.Candidate> candidates = new ArrayList<>();
        for (Node node : nodes) {
            source.add(node);
            candidates.add(new PeerSelector.Candidate(node.getId(), node.isNearby()));
        }
        String selected = PeerSelector.select(candidates, boundNodeId);
        for (Node node : source) if (selected.equals(node.getId())) return node;
        return null;
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(SleepSessionService.ACTION_STATUS);
        filter.addAction(ACTION_CONFIG_CHANGED);
        filter.addAction(ACTION_AUTO_STATUS);
        ContextCompat.registerReceiver(
                this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    @Override protected void onResume() {
        super.onResume();
        if (liveSystemCheck != null) liveSystemCheck.logActivityState("RESUMED");
        if (pendingEnrollment.isEmpty() && !systemCheckMode) refreshState();
    }

    @Override protected void onStop() {
        if (liveSystemCheck != null) liveSystemCheck.logActivityState("STOPPED");
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (liveSystemCheck != null) {
            liveSystemCheck.cancel("The watch system check was interrupted.");
        }
        remoteExecutor.shutdown();
        super.onDestroy();
    }

    private String heartPermission() {
        return Build.VERSION.SDK_INT >= 36 ? READ_HEART_RATE : Manifest.permission.BODY_SENSORS;
    }

    private String samsungPpgPermission() {
        return Build.VERSION.SDK_INT >= 36
                ? READ_ADDITIONAL_HEALTH_DATA : Manifest.permission.BODY_SENSORS;
    }

    private String backgroundHeartPermission() {
        if (Build.VERSION.SDK_INT >= 36) return READ_HEALTH_DATA_IN_BACKGROUND;
        if (Build.VERSION.SDK_INT >= 33) return Manifest.permission.BODY_SENSORS_BACKGROUND;
        return null;
    }

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void addIfMissing(List<String> permissions, String permission) {
        if (!hasPermission(permission) && !permissions.contains(permission)) {
            permissions.add(permission);
        }
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_SYSTEM_CHECK_PERMISSIONS) {
            String checkId = pendingSystemCheckId;
            pendingSystemCheckId = "";
            AutoSleepConfig config = AutoSleepConfig.load(this);
            if (checkId.isEmpty() || !config.validBinding()) return;
            if (!hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)
                    || !hasPermission(heartPermission())) {
                publishImmediateSystemCheckFailure(config, checkId,
                        "Activity and heart-rate permissions are required on the watch.");
            } else {
                // Advanced Samsung permissions are optional. If the user denies one,
                // run the core motion/heart check once and report that capability as
                // unavailable instead of reopening the same permission request loop.
                startLiveSystemCheck(config, checkId, pendingSystemCheckProbe);
            }
            return;
        }
        if (!pendingEnable) return;
        if (requestCode == REQUEST_FOREGROUND_PERMISSIONS
                || requestCode == REQUEST_BACKGROUND_PERMISSION) ensurePermissionsThenEnable();
    }
}
