package com.remote.DreamSync;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.wear.remote.interactions.RemoteActivityHelper;

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
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

    private TextView statusText;
    private boolean pendingEnable;
    private boolean foregroundRequestAttempted;
    private boolean backgroundRequestAttempted;
    private boolean receiverRegistered;
    private String pendingEnrollment = "";
    private int configSyncAttempt;
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
        boolean setupLink = maybeHandleSetupLink(getIntent());
        if (!setupLink) statusText.postDelayed(this::openPhoneApp, 350L);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        maybeHandleSetupLink(intent);
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
        if (pendingEnrollment.isEmpty()) refreshState();
    }

    @Override protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
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
