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
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.wear.remote.interactions.RemoteActivityHelper;

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.common.util.concurrent.ListenableFuture;
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
    private static final String READ_HEART_RATE = "android.permission.health.READ_HEART_RATE";
    private static final String READ_HEALTH_DATA_IN_BACKGROUND =
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND";
    private static final String READ_SKIN_TEMPERATURE =
            "android.permission.health.READ_SKIN_TEMPERATURE";
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
        if (liveSystemCheck != null) liveSystemCheck.cancel("A newer check replaced this check.");
        liveSystemCheck = new LiveSystemCheck(config, checkId);
        liveSystemCheck.start();
        return true;
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
                .setMessage("While DreamSync is On, the watch uses low-power activity signals to notice sleep every night. After likely sleep, it starts motion and heart-rate checks. Cues stay off when data is stale, heart data is missing, or waking is detected. Data stays on your paired devices unless you export it from the phone.")
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
            if (Build.VERSION.SDK_INT >= 33) addIfMissing(
                    missing, Manifest.permission.POST_NOTIFICATIONS);
            if (!missing.isEmpty()) {
                foregroundRequestAttempted = true;
                requestPermissions(missing.toArray(new String[0]),
                        REQUEST_FOREGROUND_PERMISSIONS);
                statusText.setText("Allow activity and heart-rate access.");
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
        Wearable.getCapabilityClient(this).getCapability(
                        com.remote.DreamSync.shared.Protocol.PHONE_CAPABILITY,
                        CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener(capability -> {
                    Node phone = chooseNode(capability.getNodes());
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
        payload.put("offWrist", "false");
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
                + ";offWrist=" + payload.getOrDefault("offWrist", "false")
                + ";samsungSdkConnected="
                + payload.getOrDefault("samsungSdkConnected", "false")
                + ";ibiSamples=" + payload.getOrDefault("ibiSamples", "0")
                + ";temperatureSamples="
                + payload.getOrDefault("temperatureSamples", "0")
                + ";ppgSamples=" + payload.getOrDefault("ppgSamples", "0");
    }

    private final class LiveSystemCheck implements SensorEventListener {
        private final AutoSleepConfig config;
        private final String checkId;
        private final SensorManager sensorManager;
        private final long startedElapsedMs = SystemClock.elapsedRealtime();
        private final Runnable finishRunnable = this::finish;
        private Sensor accelerometer;
        private Sensor heartRate;
        private SamsungHealthSensorBridge samsungBridge;
        private Set<String> samsungTrackerTypes = new HashSet<>();
        private volatile int motionSamples;
        private volatile int standardHeartSamples;
        private volatile int samsungHeartSamples;
        private volatile int ibiSamples;
        private volatile int temperatureSamples;
        private volatile int ppgSamples;
        private volatile double lastHeartBpm = Double.NaN;
        private volatile boolean offWrist;
        private volatile boolean samsungConnected;
        private boolean activityPermission;
        private boolean heartPermissionGranted;
        private boolean motionRegistered;
        private boolean heartRegistered;
        private boolean finished;
        private String samsungDetail = "";
        private String forcedFailure = "";

        LiveSystemCheck(AutoSleepConfig config, String checkId) {
            this.config = config;
            this.checkId = checkId;
            sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        }

        void start() {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            activityPermission = hasPermission(Manifest.permission.ACTIVITY_RECOGNITION);
            heartPermissionGranted = hasPermission(heartPermission());
            WatchDiagnostics.auto(MainActivity.this, config, "SYSTEM_CHECK_STARTED",
                    "checkId=" + checkId + ";durationSeconds=45"
                            + ";activityPermission=" + activityPermission
                            + ";heartPermission=" + heartPermissionGranted);
            if (!activityPermission || !heartPermissionGranted || sensorManager == null) {
                if (sensorManager == null) forcedFailure = "Watch sensor service is unavailable.";
                finish();
                return;
            }
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            heartRate = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
            if (accelerometer != null) {
                motionRegistered = sensorManager.registerListener(
                        this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
            }
            if (heartRate != null) {
                try {
                    heartRegistered = sensorManager.registerListener(
                            this, heartRate, SensorManager.SENSOR_DELAY_NORMAL);
                } catch (SecurityException error) {
                    heartRegistered = false;
                    forcedFailure = "Heart-rate permission was rejected by the watch.";
                }
            }
            startSamsungCheck();
            statusText.setText("System check running. Wear the watch snugly and hold still. "
                    + "Motion 0, heart 0.");
            statusText.postDelayed(finishRunnable, SYSTEM_CHECK_DURATION_MS);
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
                        }
                    });
            samsungBridge.start(true);
        }

        @Override public void onSensorChanged(SensorEvent event) {
            if (finished || event == null || event.sensor == null) return;
            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER
                    && event.values != null && event.values.length >= 3
                    && Float.isFinite(event.values[0])
                    && Float.isFinite(event.values[1])
                    && Float.isFinite(event.values[2])) {
                motionSamples++;
            } else if (event.sensor.getType() == Sensor.TYPE_HEART_RATE
                    && event.values != null && event.values.length > 0
                    && Float.isFinite(event.values[0])
                    && event.values[0] >= 30f && event.values[0] <= 220f) {
                standardHeartSamples++;
                lastHeartBpm = event.values[0];
            }
            if (motionSamples % 10 == 0 || standardHeartSamples > 0) {
                statusText.setText("System check running. Wear the watch snugly and hold still. "
                        + "Motion " + motionSamples + ", heart "
                        + (standardHeartSamples + samsungHeartSamples) + ".");
            }
        }

        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
            if (sensor != null && sensor.getType() == Sensor.TYPE_HEART_RATE
                    && accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                WatchDiagnostics.auto(MainActivity.this, config,
                        "SYSTEM_CHECK_HEART_ACCURACY_UNRELIABLE", "checkId=" + checkId);
            }
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
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (sensorManager != null) sensorManager.unregisterListener(this);
            if (samsungBridge != null) samsungBridge.stop();

            int totalHeartSamples = standardHeartSamples + samsungHeartSamples;
            boolean usableHeartRegistration = heartRegistered
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
            String reason = forcedFailure.isEmpty() ? evaluation.reason : forcedFailure;
            SensorCapabilitySnapshot capability = new SensorCapabilitySnapshot(
                    motionSamples >= SystemCheckEvaluator.MIN_MOTION_SAMPLES,
                    standardHeartSamples >= SystemCheckEvaluator.MIN_HEART_SAMPLES,
                    SamsungHealthSensorBridge.isPackaged(),
                    samsungConnected,
                    samsungTrackerTypes,
                    passed,
                    samsungDetail);
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("status", passed ? "PASS" : "FAIL");
            payload.put("reason", reason);
            payload.put("motionSamples", Integer.toString(motionSamples));
            payload.put("heartSamples", Integer.toString(totalHeartSamples));
            payload.put("lastHeartBpm", Double.isFinite(lastHeartBpm)
                    ? String.format(java.util.Locale.US, "%.1f", lastHeartBpm) : "NaN");
            payload.put("motionRegistered", Boolean.toString(motionRegistered));
            payload.put("heartRegistered", Boolean.toString(usableHeartRegistration));
            payload.put("activityPermission", Boolean.toString(activityPermission));
            payload.put("heartPermission", Boolean.toString(heartPermissionGranted));
            payload.put("offWrist", Boolean.toString(offWrist));
            payload.put("durationMs", Long.toString(
                    Math.max(0L, SystemClock.elapsedRealtime() - startedElapsedMs)));
            payload.put("samsungSdkPackaged", Boolean.toString(
                    SamsungHealthSensorBridge.isPackaged()));
            payload.put("samsungSdkConnected", Boolean.toString(samsungConnected));
            payload.put("ibiSamples", Integer.toString(ibiSamples));
            payload.put("temperatureSamples", Integer.toString(temperatureSamples));
            payload.put("ppgSamples", Integer.toString(ppgSamples));
            payload.put("capabilityJson", capability.toJson());
            publishSystemCheckResult(config, checkId, payload);
            liveSystemCheck = null;
        }
    }

    private static String safeIdentifier(String value) {
        if (value == null) return "";
        String cleaned = value.replaceAll("[^A-Za-z0-9._:-]", "");
        return cleaned.substring(0, Math.min(128, cleaned.length()));
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
                    Node phone = chooseNode(nodes);
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

    private Node chooseNode(Iterable<Node> nodes) {
        if (nodes == null) return null;
        Node first = null;
        for (Node node : nodes) {
            if (first == null) first = node;
            if (node.isNearby()) return node;
        }
        return first;
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
        if (!pendingEnable) return;
        if (requestCode == REQUEST_FOREGROUND_PERMISSIONS
                || requestCode == REQUEST_BACKGROUND_PERMISSION) ensurePermissionsThenEnable();
    }
}
