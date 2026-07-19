package com.remote.DreamSync;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ForegroundServiceStartNotAllowedException;
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
    private boolean activityResumed;
    private Intent pendingAutoArmIntent;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (SleepSessionService.ACTION_STATUS.equals(intent.getAction())) {
                statusText.setText(intent.getStringExtra(SleepSessionService.EXTRA_STATUS));
            } else if (ACTION_CONFIG_CHANGED.equals(intent.getAction())) {
                refreshMode();
                statusText.setText("Tonight's settings are ready. Automatic sleep estimation can arm now.");
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

        startButton.setOnClickListener(v -> beginAutomaticDetection());
        stopButton.setOnClickListener(v -> {
            pendingStart = false;
            stopService(new Intent(this, SleepSessionService.class));
            SessionConfig.clear(this);
            statusText.setText("Session stopped.");
        });
        privacyButton.setOnClickListener(v -> showPrivacyAndSafety());
        refreshMode();
        queueAutoArm(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        queueAutoArm(intent);
    }

    private void queueAutoArm(Intent intent) {
        pendingAutoArmIntent = intent;
        if (activityResumed) {
            getWindow().getDecorView().post(this::runPendingAutoArm);
        }
    }

    private void runPendingAutoArm() {
        Intent intent = pendingAutoArmIntent;
        pendingAutoArmIntent = null;
        if (intent != null) maybeAutoArm(intent);
    }

    private void maybeAutoArm(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null
                || !"dreamsync".equals(data.getScheme())
                || !"watch".equals(data.getHost())
                || !"/arm".equals(data.getPath())) return;
        SessionConfig config = SessionConfig.load(this);
        String requestedSession = data.getQueryParameter("session");
        if (requestedSession == null || !requestedSession.equals(config.sessionId)) {
            statusText.setText("Arm request did not match tonight's settings. Try again from the phone.");
            return;
        }
        if (SleepSessionService.isRunning()) {
            statusText.setText(SleepSessionService.currentStatus());
            return;
        }
        statusText.setText("Phone connected. Arming automatic sleep estimation...");
        beginAutomaticDetection();
    }

    private void beginAutomaticDetection() {
        if (SleepSessionService.isRunning()) {
            statusText.setText(SleepSessionService.currentStatus());
            return;
        }
        SessionConfig config = SessionConfig.load(this);
        if (config.sessionId.isEmpty() || !config.isCurrent(System.currentTimeMillis())) {
            pendingStart = false;
            statusText.setText("Tonight's settings are missing or expired. Tap Arm for tonight on the phone.");
            return;
        }
        pendingStart = true;
        foregroundRequestAttempted = false;
        backgroundRequestAttempted = false;
        showDisclosureThenStart();
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
        ContextCompat.registerReceiver(
                this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshMode();
        if (SleepSessionService.isRunning()) {
            statusText.setText(SleepSessionService.currentStatus());
        }
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        activityResumed = true;
        runPendingAutoArm();
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        super.onPause();
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
                        "Grant activity and heart-rate access. Without reliable heart data, DreamSync logs only and sends no cues."
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
                    "For overnight heart rate, choose Sensors → Allow all the time. If denied, DreamSync logs motion but sends no cues."
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
                    ? "Automatic detection armed. Keep the watch snug, then turn the display off."
                    : "Motion logging armed. Heart rate is unavailable, so cues stay off.");
        } catch (ForegroundServiceStartNotAllowedException error) {
            statusText.setText("Android blocked background sensing. Keep DreamSync open and tap Arm sleep detection again.");
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
            // The service may still log motion if background heart data is denied,
            // but cue eligibility requires fresh heart-rate data.
            ensurePermissionsThenStart();
        }
    }
}
