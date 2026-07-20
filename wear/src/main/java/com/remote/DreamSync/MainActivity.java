package com.remote.DreamSync;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
    private static final int CONFIG_SYNC_RETRY_LIMIT = 20;
    private static final long SYSTEM_CHECK_DURATION_MS = 45_000L;

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
        String checkId = safeIdentifier(data.getQueryParameter("check"));
        String enrollment = safeIdentifier(data.getQueryParameter("enrollment"));
        AutoSleepConfig config = AutoSleepConfig.load(this);
        if (checkId.isEmpty() || !config.validBinding()
                || !config.enrollmentId.equals(enrollment)) {
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
        startSystemCheckWhenPermissionsReady(config, checkId);
        return true;
    }

    private void startSystemCheckWhenPermissionsReady(
            AutoSleepConfig config,
            String checkId
    ) {
        List<String> missing = new ArrayList<>();
        addIfMissing(missing, Manifest.permission.ACTIVITY_RECOGNITION);
        addIfMissing(missing, heartPermission());
        if (!missing.isEmpty()) {
            pendingSystemCheckId = checkId;
            statusText.setText("Allow activity and heart-rate access to run the watch check.");
            requestPermissions(missing.toArray(new String[0]),
                    REQUEST_SYSTEM_CHECK_PERMISSIONS);
            WatchDiagnostics.auto(this, config, "SYSTEM_CHECK_PERMISSION_REQUESTED",
                    "activityMissing="
                            + !hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)
                            + ";heartMissing=" + !hasPermission(heartPermission()));
            return;
        }
        pendingSystemCheckId = "";
        if (liveSystemCheck != null) {
            liveSystemCheck.cancel("A newer check replaced this check.");
        }
        liveSystemCheck = new LiveSystemCheck(config, checkId);
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
            if (Build.VERSION.SDK_INT >= 36) addIfMissing(missing, READ_SKIN_TEMPERATURE);
            if (SamsungHealthSensorBridge.isSamsungWatch()
                    && SamsungHealthSensorBridge.isPackaged()) {
                addIfMissing(missing, READ_ADDITIONAL_HEALTH_DATA);
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
                            + " Returning to phone controls...");
                    statusText.postDelayed(
                            () -> launchPhoneControls(config.phoneNodeId), 1_500L);
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
                + ";heartRegistered="
                + payload.getOrDefault("heartRegistered", "false")
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

    private final class LiveSystemCheck implements SensorEventListener {
        private final AutoSleepConfig config;
        private final String checkId;
        private final SensorManager sensorManager;
        private final long startedElapsedMs = SystemClock.elapsedRealtime();
        private final Runnable finishRunnable = this::finish;
        private final Runnable progressFiveSeconds = () -> logProgress("5s");
        private final Runnable progressFifteenSeconds = () -> logProgress("15s");
        private final Runnable progressThirtySeconds = () -> logProgress("30s");
        private Sensor accelerometer;
        private Sensor heartRate;
        private MeasureClient measureClient;
        private MeasureCallback measureCallback;
        private WatchAudioOutput watchAudioOutput;
        private SamsungHealthSensorBridge samsungBridge;
        private Set<String> samsungTrackerTypes = new HashSet<>();
        private volatile int motionSamples;
        private volatile int standardHeartSamples;
        private volatile int measureHeartSamples;
        private volatile int samsungHeartSamples;
        private volatile int ibiSamples;
        private volatile int temperatureSamples;
        private volatile int ppgSamples;
        private volatile double lastHeartBpm = Double.NaN;
        private volatile boolean offWrist;
        private volatile boolean samsungConnected;
        private boolean activityPermission;
        private boolean heartPermissionGranted;
        private boolean additionalHealthPermission;
        private boolean motionRegistered;
        private boolean heartRegistered;
        private volatile boolean measureSupported;
        private volatile boolean measureRegistered;
        private boolean hapticAvailable;
        private boolean hapticRequested;
        private volatile boolean watchAudioStarted;
        private volatile WatchAudioOutput.Result watchAudioResult;
        private boolean finished;
        private String sensorInventory = "";
        private String motionRegistrationDetails = "";
        private String measureAvailability = "not_checked";
        private String samsungDetail = "";
        private String forcedFailure = "";

        LiveSystemCheck(AutoSleepConfig config, String checkId) {
            this.config = config;
            this.checkId = checkId;
            sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        }

        void start() {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            HapticResult haptic = requestAwakeSystemCheckHaptic();
            hapticAvailable = haptic.available;
            hapticRequested = haptic.requested;
            activityPermission = hasPermission(Manifest.permission.ACTIVITY_RECOGNITION);
            heartPermissionGranted = hasPermission(heartPermission());
            additionalHealthPermission = hasPermission(READ_ADDITIONAL_HEALTH_DATA);
            WatchDiagnostics.auto(MainActivity.this, config, "SYSTEM_CHECK_STARTED",
                    "checkId=" + checkId + ";durationSeconds=45"
                            + ";activityPermission=" + activityPermission
                            + ";heartPermission=" + heartPermissionGranted
                            + ";hapticAvailable=" + hapticAvailable
                            + ";hapticRequested=" + hapticRequested
                            + ";additionalHealthPermission="
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
            sensorInventory = MotionSensorRegistration.inventory(sensorManager);
            WatchDiagnostics.auto(MainActivity.this, config, "SYSTEM_CHECK_SENSOR_INVENTORY",
                    sensorInventory);
            MotionSensorRegistration.Result motion = MotionSensorRegistration.register(
                    sensorManager, this, 20_000, 0, null);
            accelerometer = motion.sensor;
            motionRegistered = motion.registered;
            motionRegistrationDetails = motion.details;
            WatchDiagnostics.auto(MainActivity.this, config,
                    motionRegistered ? "SYSTEM_CHECK_MOTION_REGISTERED"
                            : "SYSTEM_CHECK_MOTION_REGISTRATION_FAILED",
                    "selected=" + MotionSensorRegistration.describe(accelerometer)
                            + ";attempts=" + motionRegistrationDetails);
            heartRate = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
            if (heartRate != null) {
                try {
                    heartRegistered = sensorManager.registerListener(
                            this, heartRate, SensorManager.SENSOR_DELAY_GAME);
                    WatchDiagnostics.auto(MainActivity.this, config,
                            heartRegistered ? "SYSTEM_CHECK_STANDARD_HEART_REGISTERED"
                                    : "SYSTEM_CHECK_STANDARD_HEART_REGISTRATION_FAILED",
                            MotionSensorRegistration.describe(heartRate));
                } catch (RuntimeException error) {
                    heartRegistered = false;
                    WatchDiagnostics.auto(MainActivity.this, config,
                            "SYSTEM_CHECK_STANDARD_HEART_REGISTRATION_EXCEPTION",
                            MotionSensorRegistration.describe(heartRate) + ";error="
                                    + safeMessage(error));
                }
            } else {
                WatchDiagnostics.auto(MainActivity.this, config,
                        "SYSTEM_CHECK_STANDARD_HEART_MISSING", "type=HEART_RATE;default=null");
            }
            startHealthMeasureCheck();
            startSamsungCheck();
            startWatchAudioCheck();
            statusText.setText((hapticRequested
                    ? "Test vibration sent. " : "Watch vibration unavailable. ")
                    + "Playing watch sound. Checking motion 0, heart 0.");
            statusText.postDelayed(progressFiveSeconds, 5_000L);
            statusText.postDelayed(progressFifteenSeconds, 15_000L);
            statusText.postDelayed(progressThirtySeconds, 30_000L);
            statusText.postDelayed(finishRunnable, SYSTEM_CHECK_DURATION_MS);
        }

        private void startWatchAudioCheck() {
            watchAudioOutput = new WatchAudioOutput(MainActivity.this);
            WatchAudioOutput.Result preflight = watchAudioOutput.preflight();
            WatchDiagnostics.auto(MainActivity.this, config,
                    preflight.success ? "SYSTEM_CHECK_WATCH_AUDIO_PREFLIGHT_PASS"
                            : "SYSTEM_CHECK_WATCH_AUDIO_PREFLIGHT_FAIL",
                    "reason=" + preflight.reason + ";" + preflight.diagnostics);
            remoteExecutor.execute(() -> {
                WatchAudioOutput.Result result = watchAudioOutput.play(
                        "DEEP", Math.max(0.08f, config.cueGain), () -> {
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
            measureClient = HealthServices.getClient(MainActivity.this).getMeasureClient();
            measureCallback = new MeasureCallback() {
                @Override public void onRegistered() {
                    measureRegistered = true;
                    WatchDiagnostics.auto(MainActivity.this, config,
                            "SYSTEM_CHECK_MEASURE_HEART_REGISTERED",
                            "dataType=HEART_RATE_BPM");
                    if (finished) {
                        measureClient.unregisterMeasureCallbackAsync(
                                DataType.HEART_RATE_BPM, this);
                        return;
                    }
                    updateProgressText();
                }

                @Override public void onRegistrationFailed(Throwable error) {
                    measureRegistered = false;
                    WatchDiagnostics.auto(MainActivity.this, config,
                            "SYSTEM_CHECK_MEASURE_HEART_REGISTRATION_FAILED",
                            safeMessage(error));
                    updateProgressText();
                }

                @Override public void onAvailabilityChanged(
                        androidx.health.services.client.data.DeltaDataType<?, ?> dataType,
                        Availability availability) {
                    measureAvailability = availability == null
                            ? "null" : availability.toString();
                    if (DataTypeAvailability.UNAVAILABLE_DEVICE_OFF_BODY.equals(availability)) {
                        offWrist = true;
                    }
                    WatchDiagnostics.auto(MainActivity.this, config,
                            "SYSTEM_CHECK_MEASURE_HEART_AVAILABILITY",
                            "availability=" + measureAvailability);
                    updateProgressText();
                }

                @Override public void onDataReceived(DataPointContainer data) {
                    List<SampleDataPoint<Double>> points = data.getData(DataType.HEART_RATE_BPM);
                    int accepted = 0;
                    for (SampleDataPoint<Double> point : points) {
                        Double bpm = point.getValue();
                        if (bpm != null && Double.isFinite(bpm)
                                && bpm >= 30.0 && bpm <= 220.0) {
                            lastHeartBpm = bpm;
                            measureHeartSamples++;
                            accepted++;
                        }
                    }
                    WatchDiagnostics.auto(MainActivity.this, config,
                            "SYSTEM_CHECK_MEASURE_HEART_DATA",
                            "received=" + points.size() + ";accepted=" + accepted
                                    + ";total=" + measureHeartSamples
                                    + ";lastBpm=" + lastHeartBpm);
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
                        measureClient.registerMeasureCallback(
                                DataType.HEART_RATE_BPM,
                                ContextCompat.getMainExecutor(MainActivity.this),
                                measureCallback);
                    }
                } catch (Exception error) {
                    WatchDiagnostics.auto(MainActivity.this, config,
                            "SYSTEM_CHECK_MEASURE_CAPABILITIES_FAILED", safeMessage(error));
                }
            }, ContextCompat.getMainExecutor(MainActivity.this));
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
                            updateProgressText();
                        }

                        @Override public void onHeartRate(
                                long timestampMs, double bpm, List<Integer> ibiMs,
                                boolean watchOffWrist) {
                            offWrist = watchOffWrist;
                            if (Double.isFinite(bpm)) {
                                lastHeartBpm = bpm;
                                samsungHeartSamples++;
                            }
                            if (ibiMs != null) ibiSamples += ibiMs.size();
                            WatchDiagnostics.auto(MainActivity.this, config,
                                    "SYSTEM_CHECK_SAMSUNG_HEART_DATA",
                                    "bpm=" + bpm + ";heartSamples=" + samsungHeartSamples
                                            + ";ibiSamples=" + ibiSamples
                                            + ";offWrist=" + offWrist);
                            updateProgressText();
                        }

                        @Override public void onSkinTemperature(
                                long timestampMs, double skinC, double ambientC) {
                            if (Double.isFinite(skinC)) temperatureSamples++;
                        }

                        @Override public void onPpgSample(long timestampMs, boolean valid) {
                            if (valid) ppgSamples++;
                        }

                        @Override public void onBridgeError(String code, String detail) {
                            samsungDetail = code + ": " + detail;
                            WatchDiagnostics.auto(MainActivity.this, config,
                                    "SYSTEM_CHECK_SAMSUNG_SENSOR_ERROR", samsungDetail);
                        }
                    });
            samsungBridge.start(additionalHealthPermission);
        }

        @Override public void onSensorChanged(SensorEvent event) {
            if (finished || event == null || event.sensor == null) return;
            if (event.sensor == accelerometer
                    && event.values != null && event.values.length >= 3
                    && Float.isFinite(event.values[0])
                    && Float.isFinite(event.values[1])
                    && Float.isFinite(event.values[2])) {
                motionSamples++;
                if (motionSamples == 1 || motionSamples % 100 == 0) {
                    WatchDiagnostics.auto(MainActivity.this, config,
                            "SYSTEM_CHECK_MOTION_DATA",
                            "count=" + motionSamples + ";sensorType="
                                    + event.sensor.getType() + ";accuracy=" + event.accuracy
                                    + ";x=" + event.values[0] + ";y=" + event.values[1]
                                    + ";z=" + event.values[2]);
                }
            } else if (event.sensor.getType() == Sensor.TYPE_HEART_RATE
                    && event.values != null && event.values.length > 0
                    && Float.isFinite(event.values[0])
                    && event.values[0] >= 30f && event.values[0] <= 220f) {
                standardHeartSamples++;
                lastHeartBpm = event.values[0];
                WatchDiagnostics.auto(MainActivity.this, config,
                        "SYSTEM_CHECK_STANDARD_HEART_DATA",
                        "count=" + standardHeartSamples + ";bpm=" + lastHeartBpm
                                + ";accuracy=" + event.accuracy);
            }
            updateProgressText();
        }

        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
            if (sensor != null && sensor.getType() == Sensor.TYPE_HEART_RATE
                    && accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                WatchDiagnostics.auto(MainActivity.this, config,
                        "SYSTEM_CHECK_HEART_ACCURACY_UNRELIABLE", "checkId=" + checkId);
            }
        }

        private void updateProgressText() {
            if (finished) return;
            int totalHeart = standardHeartSamples + measureHeartSamples + samsungHeartSamples;
            String audio = watchAudioResult == null
                    ? watchAudioStarted ? "playing" : "waiting"
                    : watchAudioResult.success ? "passed" : "failed";
            statusText.setText("Watch check running. Motion " + motionSamples
                    + ", heart " + totalHeart + ", sound " + audio + ".");
        }

        private void logProgress(String checkpoint) {
            if (finished) return;
            WatchDiagnostics.auto(MainActivity.this, config,
                    "SYSTEM_CHECK_PROGRESS",
                    "checkpoint=" + checkpoint
                            + ";motionRegistered=" + motionRegistered
                            + ";motionSamples=" + motionSamples
                            + ";standardHeartRegistered=" + heartRegistered
                            + ";standardHeartSamples=" + standardHeartSamples
                            + ";measureSupported=" + measureSupported
                            + ";measureRegistered=" + measureRegistered
                            + ";measureAvailability=" + measureAvailability
                            + ";measureHeartSamples=" + measureHeartSamples
                            + ";samsungConnected=" + samsungConnected
                            + ";samsungHeartSamples=" + samsungHeartSamples
                            + ";watchAudioStarted=" + watchAudioStarted
                            + ";watchAudioFinished=" + (watchAudioResult != null));
            updateProgressText();
        }

        void cancel(String reason) {
            if (finished) return;
            forcedFailure = reason;
            finish();
        }

        private void finish() {
            if (finished) return;
            finished = true;
            statusText.removeCallbacks(finishRunnable);
            statusText.removeCallbacks(progressFiveSeconds);
            statusText.removeCallbacks(progressFifteenSeconds);
            statusText.removeCallbacks(progressThirtySeconds);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (sensorManager != null) sensorManager.unregisterListener(this);
            if (measureClient != null && measureCallback != null && measureRegistered) {
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
                }, ContextCompat.getMainExecutor(MainActivity.this));
            }
            if (samsungBridge != null) samsungBridge.stop();

            int totalHeartSamples = standardHeartSamples
                    + measureHeartSamples + samsungHeartSamples;
            boolean usableHeartRegistration = heartRegistered
                    || measureRegistered
                    || (samsungConnected
                    && samsungTrackerTypes.contains("HEART_RATE_CONTINUOUS"));
            SystemCheckEvaluator.Result evaluation = new SystemCheckEvaluator().evaluate(
                    activityPermission,
                    heartPermissionGranted,
                    motionRegistered,
                    usableHeartRegistration,
                    motionSamples,
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
                    motionSamples >= SystemCheckEvaluator.MIN_MOTION_SAMPLES,
                    totalHeartSamples >= SystemCheckEvaluator.MIN_HEART_SAMPLES,
                    SamsungHealthSensorBridge.isPackaged(),
                    samsungConnected,
                    samsungTrackerTypes,
                    passed && advanced.passed,
                    samsungDetail + "; " + advanced.reason);
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("status", passed ? "PASS" : "FAIL");
            payload.put("reason", reason);
            payload.put("motionSamples", Integer.toString(motionSamples));
            payload.put("heartSamples", Integer.toString(totalHeartSamples));
            payload.put("lastHeartBpm", Double.isFinite(lastHeartBpm)
                    ? String.format(java.util.Locale.US, "%.1f", lastHeartBpm) : "NaN");
            payload.put("motionRegistered", Boolean.toString(motionRegistered));
            payload.put("heartRegistered", Boolean.toString(usableHeartRegistration));
            payload.put("motionSensor", MotionSensorRegistration.describe(accelerometer));
            payload.put("motionRegistrationDetails", motionRegistrationDetails);
            payload.put("sensorInventory", sensorInventory);
            payload.put("standardHeartRegistered", Boolean.toString(heartRegistered));
            payload.put("standardHeartSamples", Integer.toString(standardHeartSamples));
            payload.put("measureSupported", Boolean.toString(measureSupported));
            payload.put("measureRegistered", Boolean.toString(measureRegistered));
            payload.put("measureAvailability", measureAvailability);
            payload.put("measureHeartSamples", Integer.toString(measureHeartSamples));
            payload.put("samsungHeartSamples", Integer.toString(samsungHeartSamples));
            payload.put("watchAudioStarted", Boolean.toString(watchAudioStarted));
            payload.put("watchAudioCompleted", Boolean.toString(
                    watchAudioResult != null && watchAudioResult.success));
            payload.put("watchAudioReason", watchAudioResult == null
                    ? "not_finished" : watchAudioResult.reason);
            payload.put("watchAudioDiagnostics", watchAudioResult == null
                    ? watchAudioOutput == null ? "not_started" : watchAudioOutput.diagnostics()
                    : watchAudioResult.diagnostics);
            payload.put("hapticAvailable", Boolean.toString(hapticAvailable));
            payload.put("hapticRequested", Boolean.toString(hapticRequested));
            payload.put("activityPermission", Boolean.toString(activityPermission));
            payload.put("heartPermission", Boolean.toString(heartPermissionGranted));
            payload.put("additionalHealthPermission",
                    Boolean.toString(additionalHealthPermission));
            payload.put("offWrist", Boolean.toString(offWrist));
            payload.put("durationMs", Long.toString(
                    Math.max(0L, SystemClock.elapsedRealtime() - startedElapsedMs)));
            payload.put("samsungSdkPackaged", Boolean.toString(
                    SamsungHealthSensorBridge.isPackaged()));
            payload.put("samsungSdkConnected", Boolean.toString(samsungConnected));
            payload.put("ibiSamples", Integer.toString(ibiSamples));
            payload.put("temperatureSamples", Integer.toString(temperatureSamples));
            payload.put("ppgSamples", Integer.toString(ppgSamples));
            payload.put("advancedSensorsExpected", Boolean.toString(advanced.expected));
            payload.put("advancedSensorsVerified", Boolean.toString(advanced.passed));
            payload.put("advancedSensorState", !advanced.expected
                    ? "UNAVAILABLE" : advanced.passed ? "VERIFIED" : "FAILED");
            payload.put("advancedSensorReason", advanced.reason);
            payload.put("capabilityJson", capability.toJson());
            publishSystemCheckResult(config, checkId, payload);
            liveSystemCheck = null;
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
        if (pendingEnrollment.isEmpty() && !systemCheckMode) refreshState();
    }

    @Override protected void onStop() {
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

    private String backgroundHeartPermission() {
        if (Build.VERSION.SDK_INT >= 36) return READ_HEALTH_DATA_IN_BACKGROUND;
        if (Build.VERSION.SDK_INT >= 33) return Manifest.permission.BODY_SENSORS_BACKGROUND;
        return null;
    }

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void addIfMissing(List<String> permissions, String permission) {
        if (!hasPermission(permission)) permissions.add(permission);
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
                startSystemCheckWhenPermissionsReady(config, checkId);
            }
            return;
        }
        if (!pendingEnable) return;
        if (requestCode == REQUEST_FOREGROUND_PERMISSIONS
                || requestCode == REQUEST_BACKGROUND_PERMISSION) ensurePermissionsThenEnable();
    }
}
