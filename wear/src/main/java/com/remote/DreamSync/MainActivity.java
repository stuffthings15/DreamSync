package com.remote.DreamSync;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    static final String ACTION_CONFIG_CHANGED = "com.remote.DreamSync.CONFIG_CHANGED";

    private static final int REQUEST_FOREGROUND_PERMISSIONS = 501;
    private static final int REQUEST_BACKGROUND_PERMISSION = 502;
    private static final String READ_HEART_RATE =
            "android.permission.health.READ_HEART_RATE";
    private static final String READ_HEALTH_DATA_IN_BACKGROUND =
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND";
    private static final String USER_PREFS = "dreamsync_watch_user";

    private TextView statusText;
    private TextView modeText;
    private boolean pendingStart;
    private boolean foregroundRequestAttempted;
    private boolean backgroundRequestAttempted;
    private boolean receiverRegistered;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (SleepSessionService.ACTION_STATUS.equals(intent.getAction())) {
                statusText.setText(intent.getStringExtra(SleepSessionService.EXTRA_STATUS));
            } else if (ACTION_CONFIG_CHANGED.equals(intent.getAction())) {
                refreshMode();
                statusText.setText("Phone settings received. Tap Start night session.");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        modeText = findViewById(R.id.modeText);
        Button startButton = findViewById(R.id.startButton);
        Button stopButton = findViewById(R.id.stopButton);
        Button privacyButton = findViewById(R.id.privacyButton);

        startButton.setOnClickListener(v -> {
            pendingStart = true;
            foregroundRequestAttempted = false;
            backgroundRequestAttempted = false;
            showDisclosureThenStart();
        });
        stopButton.setOnClickListener(v -> {
            pendingStart = false;
            stopService(new Intent(this, SleepSessionService.class));
            statusText.setText("Session stopped.");
        });
        privacyButton.setOnClickListener(v -> showPrivacyAndSafety());
        refreshMode();
    }

    private void showDisclosureThenStart() {
        if (getSharedPreferences(USER_PREFS, MODE_PRIVATE)
                .getBoolean("health_disclosure_accepted", false)) {
            ensurePermissionsThenStart();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Motion and heart-rate access")
                .setMessage("DreamSync uses watch motion and heart-rate readings during a night session to estimate cue-ready windows and pause cues when waking is detected. "
                        + "These readings and session records stay on your paired devices unless you choose Export on the phone. They are not used for diagnosis, ads, or automatic cloud upload.")
                .setPositiveButton("Continue", (dialog, which) -> {
                    getSharedPreferences(USER_PREFS, MODE_PRIVATE).edit()
                            .putBoolean("health_disclosure_accepted", true).apply();
                    ensurePermissionsThenStart();
                })
                .setNegativeButton("Not now", (dialog, which) -> pendingStart = false)
                .setCancelable(false)
                .show();
    }

    private void showPrivacyAndSafety() {
        new AlertDialog.Builder(this)
                .setTitle("Privacy & safety")
                .setMessage("Motion and heart-rate readings are used on your watch only for cue timing and safety. Session data stays on your paired devices unless you export it from the phone. "
                        + "DreamSync has no account, ads, analytics, or automatic cloud upload.\n\nWellness only. DreamSync does not diagnose or treat medical conditions.")
                .setPositiveButton("Done", null)
                .show();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(SleepSessionService.ACTION_STATUS);
        filter.addAction(ACTION_CONFIG_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        receiverRegistered = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshMode();
        if (SleepSessionService.isRunning()) {
            statusText.setText("Night session is running. You can turn the display off.");
        }
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    private void refreshMode() {
        SessionConfig config = SessionConfig.load(this);
        modeText.setText("Plan: " + config.mode.replace('_', ' ')
                + " • volume " + Math.round(config.cueGain * 100f) + "%");
    }

    private void ensurePermissionsThenStart() {
        String heartPermission = heartPermission();

        if (!foregroundRequestAttempted) {
            List<String> missing = new ArrayList<>();
            addIfMissing(missing, Manifest.permission.ACTIVITY_RECOGNITION);
            addIfMissing(missing, heartPermission);
            if (Build.VERSION.SDK_INT >= 33) {
                addIfMissing(missing, Manifest.permission.POST_NOTIFICATIONS);
            }

            if (!missing.isEmpty()) {
                foregroundRequestAttempted = true;
                requestPermissions(
                        missing.toArray(new String[0]),
                        REQUEST_FOREGROUND_PERMISSIONS
                );
                statusText.setText(
                        "Grant activity and heart-rate access. Heart rate is optional; motion-only fallback is supported."
                );
                return;
            }
        }

        boolean hasActivityPermission = hasPermission(Manifest.permission.ACTIVITY_RECOGNITION);
        boolean hasHeartPermission = hasPermission(heartPermission);
        if (!hasActivityPermission && !hasHeartPermission) {
            pendingStart = false;
            statusText.setText(
                    "DreamSync needs activity recognition or heart-rate access to start."
            );
            return;
        }

        String backgroundPermission = backgroundHeartPermission();
        if (hasHeartPermission
                && backgroundPermission != null
                && !hasPermission(backgroundPermission)
                && !backgroundRequestAttempted) {
            backgroundRequestAttempted = true;
            requestPermissions(
                    new String[]{backgroundPermission},
                    REQUEST_BACKGROUND_PERMISSION
            );
            statusText.setText(
                    "For overnight heart rate, choose Sensors → Allow all the time. You may continue with motion-only fallback if denied."
            );
            return;
        }

        startNightService(hasHeartPermission
                && (backgroundPermission == null || hasPermission(backgroundPermission)));
    }

    private String heartPermission() {
        if (Build.VERSION.SDK_INT >= 36) return READ_HEART_RATE;
        return Manifest.permission.BODY_SENSORS;
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

    private void startNightService(boolean overnightHeartRateExpected) {
        pendingStart = false;
        Intent intent = new Intent(this, SleepSessionService.class)
                .setAction(SleepSessionService.ACTION_START);
        try {
            startForegroundService(intent);
            statusText.setText(overnightHeartRateExpected
                    ? "Starting motion + heart-rate sensing… keep the watch snug, then turn the display off."
                    : "Starting motion-only fallback… heart rate may stop after screen-off; cues remain conservative.");
        } catch (SecurityException error) {
            statusText.setText("Could not start sensor service: " + error.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (!pendingStart) return;

        if (requestCode == REQUEST_FOREGROUND_PERMISSIONS) {
            ensurePermissionsThenStart();
        } else if (requestCode == REQUEST_BACKGROUND_PERMISSION) {
            // Background heart-rate access is valuable but not mandatory. If it is denied,
            // the service still starts and falls back to motion plus time-of-night priors.
            ensurePermissionsThenStart();
        }
    }
}
