package com.remote.DreamSync;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Html;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.wear.remote.interactions.RemoteActivityHelper;
import androidx.core.content.ContextCompat;

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.common.util.concurrent.ListenableFuture;
import com.remote.DreamSync.shared.PeerSelector;
import com.remote.DreamSync.shared.ProtocolEnvelope;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 101;
    private static final int REQUEST_EXPORT = 602;
    private static final int REQUEST_DEBUG_EXPORT = 603;
    private static final String PREFS = "dreamsync_phone";
    private static final int WATCH_INSTALL_POLL_LIMIT = 40;
    private static final long SYSTEM_CHECK_TIMEOUT_MS = 2L * 60L * 1000L;

    private TextView statusText;
    private TextView readinessText;
    private TextView gainLabel;
    private RadioGroup modeGroup;
    private SeekBar gainSeek;
    private Button powerButton;
    private ExecutorService remoteExecutor;
    private ExecutorService databaseExecutor;
    private PhoneDao dao;
    private boolean summaryReceiverRegistered;
    private boolean calibrationPromptShowing;
    private boolean calibrationEnablePending;
    private boolean audioPreviewInProgress;
    private int watchInstallPollAttempt;
    private String pendingExportJson = "";
    private String pendingDebugLog = "";

    private final BroadcastReceiver summaryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (PhoneDataListenerService.ACTION_STATUS_CHANGED.equals(intent.getAction())) {
                String status = intent.getStringExtra("status");
                if (status != null) {
                    statusText.setText(status);
                    DebugLog.append(MainActivity.this, "WATCH_STATUS", status);
                }
                updateReadiness();
                updatePowerState();
                return;
            }
            String summary = intent.getStringExtra("summary");
            String sessionId = intent.getStringExtra("sessionId");
            if (summary != null && !summary.isEmpty()) {
                restoreNotificationProtection();
                applySummaryGain(summary, sessionId);
                showFriendlySummary(summary);
                if (sessionId != null && !sessionId.isEmpty()) showMorningSurvey(sessionId);
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        restoreSavedAutoControls();
        remoteExecutor = Executors.newSingleThreadExecutor();
        databaseExecutor = Executors.newSingleThreadExecutor();
        dao = PhoneDatabase.get(this).dao();
        DebugLog.append(this, "APP_OPEN", "DreamSync phone app opened");
        requestNotificationPermissionIfNeeded();
        if (pref("active_session_id").isEmpty()) restoreNotificationProtection();
        showOnboardingOnce();
        wireActions();
        if (autoEnabled()) PhoneAutoControl.startStandby(this);
        else retryPendingRemoteDisable();
        updateReadiness();
    }

    private void bindViews() {
        statusText = findViewById(R.id.statusText);
        readinessText = findViewById(R.id.readinessText);
        gainLabel = findViewById(R.id.gainLabel);
        modeGroup = findViewById(R.id.modeGroup);
        gainSeek = findViewById(R.id.gainSeek);
        powerButton = findViewById(R.id.powerButton);
    }

    private void restoreSavedAutoControls() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        gainSeek.setProgress(Math.max(1, Math.min(10,
                Math.round(prefs.getFloat("auto_gain", 0.04f) * 100f))));
        String mode = prefs.getString("auto_mode", "DEEP");
        if ("LUCID".equals(mode)) modeGroup.check(R.id.modeLucid);
        else if ("COMBINED".equals(mode)) modeGroup.check(R.id.modeCombined);
        else modeGroup.check(R.id.modeDeep);
    }

    private void wireActions() {
        gainSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                gainLabel.setText("Cue volume: " + progress + "%");
                updateReadiness();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                syncAutoSettingsIfEnabled();
            }
        });
        findViewById(R.id.previewDeepButton).setOnClickListener(v -> previewCue("DEEP"));
        findViewById(R.id.practiceLucidButton).setOnClickListener(v -> runLucidPractice());
        findViewById(R.id.openWatchButton).setOnClickListener(v -> setupOrOpenWatch());
        findViewById(R.id.systemCheckButton).setOnClickListener(v -> runSystemCheck());
        powerButton.setOnClickListener(v -> toggleAutomaticMode());
        modeGroup.setOnCheckedChangeListener((group, checkedId) -> syncAutoSettingsIfEnabled());
        findViewById(R.id.morningButton).setOnClickListener(v -> {
            String sessionId = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString("last_summary_session", "");
            if (sessionId.isEmpty()) statusText.setText("No completed session is ready for a survey.");
            else showMorningSurvey(sessionId);
        });
        findViewById(R.id.historyButton).setOnClickListener(v -> showHistory());
        findViewById(R.id.exportButton).setOnClickListener(v -> prepareExport());
        findViewById(R.id.exportDebugButton).setOnClickListener(v -> prepareDebugExport());
        findViewById(R.id.deleteDataButton).setOnClickListener(v -> confirmDeleteAll());
        findViewById(R.id.scienceButton).setOnClickListener(v -> showScience());
        findViewById(R.id.sleepProtectionButton).setOnClickListener(
                v -> showSleepProtectionAndCoaching());
        findViewById(R.id.compatibilityButton).setOnClickListener(
                v -> showWatchCompatibility());
        findViewById(R.id.privacyButton).setOnClickListener(v -> showPrivacyAndSafety());
    }

    private void previewCue(String type) {
        playCuePreview(type, false);
    }

    private void playCuePreview(String type, boolean continueEnable) {
        playCuePreview(type, continueEnable, null);
    }

    private void playCuePreview(
            String type,
            boolean continueEnable,
            Runnable onSuccess
    ) {
        if (audioPreviewInProgress) {
            statusText.setText("A sound check is already running.");
            return;
        }
        AudioOutput audio = new AudioOutput(getApplicationContext());
        DebugLog.append(this, "SOUND_CHECK_REQUESTED", "type=" + type + ";" + audio.diagnostics());
        AudioOutput.Result preflight = audio.preflight();
        if (!preflight.success) {
            calibrationEnablePending = false;
            recordAudioFailure(preflight.reason);
            DebugLog.append(this, "SOUND_CHECK_BLOCKED", preflight.reason + ";" + audio.diagnostics());
            statusText.setText("Sound check blocked: " + preflight.reason + ".");
            return;
        }
        audioPreviewInProgress = true;
        float gain = cueGain();
        statusText.setText(continueEnable
                ? "Playing the test cue. Setup will continue after confirmed playback."
                : "Playing the cue on the phone speaker...");
        remoteExecutor.execute(() -> {
            AudioOutput.Result result = audio.play(type, gain, () -> {});
            if (result.success) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("last_calibration", audio.calibrationRecord(gain))
                        .putLong("last_calibration_utc", System.currentTimeMillis())
                        .remove("last_audio_error")
                        .remove("last_audio_error_utc")
                        .apply();
            } else recordAudioFailure(result.reason);
            DebugLog.append(this, result.success ? "SOUND_CHECK_PASSED" : "SOUND_CHECK_FAILED",
                    result.reason + ";" + audio.diagnostics());
            runOnUiThread(() -> {
                audioPreviewInProgress = false;
                if (!result.success) {
                    calibrationEnablePending = false;
                    statusText.setText("Sound check failed: " + result.reason
                            + ". Raise phone media volume, close other audio, and try again.");
                    updateReadiness();
                    return;
                }
                statusText.setText("Sound check passed. Set the cue barely audible, then one step lower.");
                updateReadiness();
                if (continueEnable && calibrationEnablePending) {
                    calibrationEnablePending = false;
                    turnDreamSyncOn();
                }
                if (onSuccess != null) onSuccess.run();
            });
        });
    }

    private void runSystemCheck() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!autoEnabled()) {
            statusText.setText("Turn DreamSync On before running the full system check.");
            DebugLog.append(this, "SYSTEM_CHECK_BLOCKED", "reason=DreamSync is Off");
            return;
        }
        if (!prefs.getBoolean("watch_auto_confirmed", false)) {
            DebugLog.append(this, "SYSTEM_CHECK_WATCH_UNCONFIRMED",
                    "continuing so missing setup or permissions can be diagnosed");
        }
        if (!pref("active_session_id").isEmpty()) {
            statusText.setText("A sleep session is active. Run the system check after it ends.");
            DebugLog.append(this, "SYSTEM_CHECK_BLOCKED", "reason=sleep session active");
            return;
        }
        if (pref("bound_watch_node").isEmpty()) {
            statusText.setText("No watch is bound. Tap Set up watch app first.");
            DebugLog.append(this, "SYSTEM_CHECK_BLOCKED", "reason=no bound watch");
            return;
        }
        statusText.setText("System check 1 of 2: testing the phone speaker...");
        DebugLog.append(this, "SYSTEM_CHECK_REQUESTED",
                "phoneAudio=true;watchMotion=true;watchHeartRate=true");
        playCuePreview("DEEP", false, () -> confirmCueWasAudible(false));
    }

    private void confirmCueWasAudible(boolean continueEnable) {
        new AlertDialog.Builder(this)
                .setTitle("Did you hear the test cue?")
                .setMessage("Choose Yes only if the quiet pink-noise pair was audible from the phone speaker.")
                .setPositiveButton("Yes", (dialog, which) -> {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putBoolean("last_calibration_audible_confirmed", true)
                            .putLong("last_calibration_audible_confirmed_utc",
                                    System.currentTimeMillis())
                            .apply();
                    DebugLog.append(this, "SOUND_CHECK_AUDIBLE_CONFIRMED",
                            "confirmedByUser=true");
                    if (continueEnable) {
                        calibrationEnablePending = false;
                        turnDreamSyncOn();
                    } else {
                        startWatchSystemCheck();
                    }
                })
                .setNegativeButton("No", (dialog, which) -> {
                    calibrationEnablePending = false;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .remove("last_calibration")
                            .remove("last_calibration_audible_confirmed")
                            .remove("last_calibration_audible_confirmed_utc")
                            .putString("last_audio_error", "User reported no audible test cue")
                            .putLong("last_audio_error_utc", System.currentTimeMillis())
                            .apply();
                    DebugLog.append(this, "SOUND_CHECK_NOT_AUDIBLE",
                            "OS playback completed but user heard no sound");
                    DebugLog.append(this, "SYSTEM_CHECK_FAILED",
                            "reason=user heard no phone cue");
                    statusText.setText("System check failed: no audible phone cue. Export the debug log.");
                    updateReadiness();
                })
                .setCancelable(false)
                .show();
    }

    private void startWatchSystemCheck() {
        String targetNodeId = pref("bound_watch_node");
        String enrollmentId = pref("auto_enrollment_id");
        if (targetNodeId.isEmpty() || enrollmentId.isEmpty()) {
            statusText.setText("System check stopped: watch binding is incomplete.");
            DebugLog.append(this, "SYSTEM_CHECK_FAILED", "reason=incomplete binding");
            return;
        }
        String checkId = UUID.randomUUID().toString();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("pending_system_check_id", checkId)
                .putLong("last_system_check_requested_utc", System.currentTimeMillis())
                .putString("last_system_check_status", "RUNNING")
                .apply();
        statusText.postDelayed(this::expireSystemCheckIfNeeded,
                SYSTEM_CHECK_TIMEOUT_MS + 1_000L);
        Intent remoteIntent = new Intent(Intent.ACTION_VIEW)
                .setData(Uri.parse("dreamsync://watch/system-check").buildUpon()
                        .appendQueryParameter("check", checkId)
                        .appendQueryParameter("enrollment", enrollmentId)
                        .build())
                .addCategory(Intent.CATEGORY_BROWSABLE);
        launchRemoteIntent(
                remoteIntent,
                targetNodeId,
                "System check 2 of 2: keep the watch snug and still for about 45 seconds.",
                "Could not start the watch system check: ",
                () -> DebugLog.append(this, "WATCH_SYSTEM_CHECK_OPENED",
                        "checkId=" + checkId + ";durationSeconds=45"));
    }

    private void expireSystemCheckIfNeeded() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String pending = prefs.getString("pending_system_check_id", "");
        long requested = prefs.getLong("last_system_check_requested_utc", 0L);
        if (pending.isEmpty() || requested <= 0L
                || System.currentTimeMillis() - requested < SYSTEM_CHECK_TIMEOUT_MS) return;
        String reason = "No watch result arrived within two minutes.";
        prefs.edit()
                .remove("pending_system_check_id")
                .putString("last_system_check_status", "FAIL")
                .putString("last_system_check_reason", reason)
                .putLong("last_system_check_utc", System.currentTimeMillis())
                .apply();
        DebugLog.append(this, "SYSTEM_CHECK_FAILED",
                "reason=watch result timeout;checkId=" + pending);
        statusText.setText("System check failed: " + reason + " Export the debug log.");
        updateReadiness();
    }

    private void recordAudioFailure(String reason) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("last_audio_error", reason == null ? "unknown audio error" : reason)
                .putLong("last_audio_error_utc", System.currentTimeMillis())
                .apply();
    }

    private void runLucidPractice() {
        int count = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt("lucid_practice_count", 0);
        new AlertDialog.Builder(this)
                .setTitle("Lucid cue conditioning")
                .setMessage("Current block: " + count + "/10\n\n"
                        + "Play the exact chime. Ask: Am I dreaming? Check text or a clock twice. "
                        + "Picture recognizing the chime inside a dream.")
                .setPositiveButton(count >= 10 ? "Practice again" : "Play + record", (dialog, which) -> {
                    previewCue("LUCID");
                    int next = Math.min(10, getSharedPreferences(PREFS, MODE_PRIVATE)
                            .getInt("lucid_practice_count", 0) + 1);
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putInt("lucid_practice_count", next)
                            .putLong("lucid_practice_utc", System.currentTimeMillis())
                            .apply();
                    statusText.setText("Lucid conditioning recorded: " + next + "/10.");
                    updateReadiness();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void toggleAutomaticMode() {
        if (autoEnabled()) turnDreamSyncOff();
        else turnDreamSyncOn();
    }

    private void turnDreamSyncOn() {
        DebugLog.append(this, "TURN_ON_REQUESTED", "Finding compatible watch");
        AudioOutput audio = new AudioOutput(this);
        AudioOutput.Result preflight = audio.preflight();
        if (!preflight.success) {
            DebugLog.append(this, "TURN_ON_BLOCKED", "reason=" + preflight.reason);
            statusText.setText("Not ready: " + preflight.reason + ".");
            return;
        }
        String calibration = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("last_calibration", "");
        boolean calibrationHeard = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("last_calibration_audible_confirmed", false);
        if (calibration.isEmpty() || !calibrationHeard) {
            DebugLog.append(this, "TURN_ON_BLOCKED",
                    "reason=audible sound calibration required");
            beginGuidedCalibration();
            return;
        }
        if (("LUCID".equals(selectedMode()) || "COMBINED".equals(selectedMode()))
                && getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt("lucid_practice_count", 0) < 10) {
            statusText.setText("Complete the 10-step lucid conditioning block first.");
            return;
        }
        String generation = UUID.randomUUID().toString();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("pending_enable_generation", generation)
                .apply();
        statusText.setText("Finding the paired Wear OS watch...");
        findWatchForEnable(generation, CapabilityClient.FILTER_REACHABLE);
    }

    private void beginGuidedCalibration() {
        if (calibrationPromptShowing || calibrationEnablePending) return;
        calibrationPromptShowing = true;
        new AlertDialog.Builder(this)
                .setTitle("One quick sound check")
                .setMessage("DreamSync must play one quiet test cue before it can turn on. "
                        + "Set the cue volume low. Setup continues automatically after the sound.")
                .setPositiveButton("Play test cue", (dialog, which) -> {
                    calibrationPromptShowing = false;
                    calibrationEnablePending = true;
                    playCuePreview("DEEP", false, () -> confirmCueWasAudible(true));
                })
                .setNegativeButton("Not now", (dialog, which) -> {
                    calibrationPromptShowing = false;
                    statusText.setText("DreamSync stays Off until the sound check is complete.");
                })
                .setOnCancelListener(dialog -> calibrationPromptShowing = false)
                .show();
    }

    private void findWatchForEnable(String generation, int filter) {
        Wearable.getCapabilityClient(this).getCapability(
                com.remote.DreamSync.shared.Protocol.WATCH_CAPABILITY,
                filter)
                .addOnSuccessListener(capability -> {
                    if (!isCurrentEnableGeneration(generation)) return;
                    Node watch = chooseNode(capability.getNodes());
                    if (watch != null) {
                        DebugLog.append(this, "WATCH_FOUND", "reachable=" + watch.isNearby());
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putBoolean("watch_install_pending", false)
                                .putString("bound_watch_node", watch.getId())
                                .apply();
                        watchInstallPollAttempt = 0;
                        enableForWatch(watch, generation);
                    } else if (filter == CapabilityClient.FILTER_REACHABLE) {
                        findWatchForEnable(generation, CapabilityClient.FILTER_ALL);
                    } else if (getSharedPreferences(PREFS, MODE_PRIVATE)
                            .getBoolean("watch_install_pending", false)) {
                        scheduleWatchInstallCheck(generation);
                    } else {
                        DebugLog.append(this, "WATCH_NOT_FOUND", "No capable Wear OS node found");
                        openWatchPlayStoreForInstall(generation);
                    }
                })
                .addOnFailureListener(error -> failPendingEnable(
                        generation,
                        "Watch capability check failed: " + safeMessage(error)));
    }

    private void enableForWatch(Node watch, String generation) {
        if (!isCurrentEnableGeneration(generation)) return;
        Wearable.getNodeClient(this).getLocalNode()
                .addOnSuccessListener(phone ->
                        enableForDevices(phone, watch, generation))
                .addOnFailureListener(error -> failPendingEnable(
                        generation,
                        "Phone identity check failed: " + safeMessage(error)));
    }

    private void enableForDevices(Node phone, Node watch, String generation) {
        if (!isCurrentEnableGeneration(generation)) return;
        String enrollment = pref("auto_enrollment_id");
        if (enrollment.isEmpty()) enrollment = UUID.randomUUID().toString();
        String mode = selectedMode();
        float gain = cueGain();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("auto_enabled", true)
                .putBoolean("watch_auto_confirmed", false)
                .putString("auto_enrollment_id", enrollment)
                .putString("local_phone_node", phone.getId())
                .putString("bound_watch_node", watch.getId())
                .putString("auto_mode", mode)
                .putFloat("auto_gain", gain)
                .remove("pending_enable_generation")
                .apply();
        try {
            PhoneAutoControl.startStandby(this);
            DebugLog.append(this, "PHONE_STANDBY_STARTED",
                    "waiting continuously for watch sleep detection");
        } catch (RuntimeException error) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("auto_enabled", false).apply();
            statusText.setText("Could not keep DreamSync ready: " + safeMessage(error));
            DebugLog.append(this, "PHONE_STANDBY_FAILED", safeMessage(error));
            updatePowerState();
            return;
        }
        final String enrollmentId = enrollment;
        DebugLog.append(this, "AUTO_CONFIG_PUBLISH_REQUESTED",
                "mode=" + mode + ";gain=" + gain + ";automaticEveryNight=true");
        PhoneAutoControl.publishAutoConfig(this, true, enrollmentId,
                        phone.getId(), watch.getId(), mode, gain)
                .addOnSuccessListener(item -> {
                    DebugLog.append(this, "AUTO_CONFIG_PUBLISHED",
                            "durableDataItem=true;watchConfirmation=pending");
                    statusText.setText("DreamSync is On. Approve watch access once; then it runs every night.");
                    openWatchApp(true, enrollmentId);
                    updatePowerState();
                    updateReadiness();
                })
                .addOnFailureListener(error -> {
                    DebugLog.append(this, "AUTO_CONFIG_PUBLISH_FAILED", safeMessage(error));
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putBoolean("auto_enabled", false).apply();
                    stopService(new Intent(this, CuePlaybackService.class));
                    statusText.setText("Could not synchronize automatic setup: "
                            + safeMessage(error));
                    updatePowerState();
                });
    }

    private void openWatchPlayStoreForInstall(String generation) {
        Wearable.getNodeClient(this).getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    if (!generation.isEmpty() && !isCurrentEnableGeneration(generation)) return;
                    Node watch = chooseNode(nodes);
                    if (watch == null) {
                        if (!generation.isEmpty()) failPendingEnable(generation,
                                "Pair and connect a Wear OS watch, then tap Turn DreamSync On again.");
                        else statusText.setText(
                                "No connected Wear OS watch was found. Pair a watch first.");
                        return;
                    }
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString("pending_watch_node", watch.getId())
                            .putBoolean("watch_install_pending", true)
                            .apply();
                    Intent storeIntent = new Intent(Intent.ACTION_VIEW)
                            .setData(Uri.parse("market://details?id=" + getPackageName()))
                            .addCategory(Intent.CATEGORY_BROWSABLE);
                    launchRemoteIntent(storeIntent, watch.getId(),
                            "DreamSync's official Play Store page opened on the watch. Tap Install; setup will continue automatically.",
                            "Could not open the watch Play Store: ",
                            () -> {
                                if (!generation.isEmpty()) scheduleWatchInstallCheck(generation);
                            });
                })
                .addOnFailureListener(error -> {
                    if (!generation.isEmpty()) failPendingEnable(generation,
                            "Could not find the paired watch: " + safeMessage(error));
                    else statusText.setText("Could not find the paired watch: " + safeMessage(error));
                });
    }

    private void scheduleWatchInstallCheck(String generation) {
        if (!isCurrentEnableGeneration(generation)) return;
        watchInstallPollAttempt++;
        if (watchInstallPollAttempt > WATCH_INSTALL_POLL_LIMIT) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .remove("pending_enable_generation").apply();
            statusText.setText("Finish installing DreamSync on the watch, then tap Turn DreamSync On again.");
            return;
        }
        statusText.postDelayed(() -> {
            if (!isCurrentEnableGeneration(generation)) return;
            Wearable.getCapabilityClient(this).getCapability(
                            com.remote.DreamSync.shared.Protocol.WATCH_CAPABILITY,
                            CapabilityClient.FILTER_REACHABLE)
                    .addOnSuccessListener(capability -> {
                        Node watch = chooseNode(capability.getNodes());
                        if (watch == null) scheduleWatchInstallCheck(generation);
                        else {
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putBoolean("watch_install_pending", false)
                                    .putString("bound_watch_node", watch.getId())
                                    .apply();
                            enableForWatch(watch, generation);
                        }
                    })
                    .addOnFailureListener(error -> scheduleWatchInstallCheck(generation));
        }, 3_000L);
    }

    private void turnDreamSyncOff() {
        DebugLog.append(this, "TURN_OFF_REQUESTED", "Stopping automatic mode");
        String sessionId = pref("active_session_id");
        String watchNode = pref("bound_watch_node");
        String phoneNode = pref("local_phone_node");
        String enrollment = pref("auto_enrollment_id");
        if (!sessionId.isEmpty()) sendStopToWatch(sessionId, phoneNode, watchNode);
        if (!enrollment.isEmpty() && !phoneNode.isEmpty() && !watchNode.isEmpty()) {
            PhoneAutoControl.publishAutoConfig(this, false, enrollment,
                            phoneNode, watchNode, selectedMode(), cueGain())
                    .addOnSuccessListener(item -> {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putBoolean("remote_disable_pending", false).apply();
                        DebugLog.append(this, "AUTO_OFF_PUBLISHED",
                                "durableDataItem=true");
                    })
                    .addOnFailureListener(error -> {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putBoolean("remote_disable_pending", true).apply();
                        DebugLog.append(this, "AUTO_OFF_PUBLISH_FAILED",
                                safeMessage(error));
                        statusText.setText("DreamSync is Off on this phone. Watch sync is pending; keep both devices connected.");
                    });
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("auto_enabled", false)
                .putString("cancelled_session_id", sessionId)
                .remove("active_session_id")
                .remove("watch_live_session_id")
                .remove("pending_enable_generation")
                .apply();
        stopService(new Intent(this, CuePlaybackService.class));
        restoreNotificationProtection();
        if (!sessionId.isEmpty()) databaseExecutor.execute(() -> {
            SessionEntity session = dao.session(sessionId);
            if (session != null) {
                session.state = "USER_TURNED_OFF";
                session.endUtcMs = System.currentTimeMillis();
                dao.upsertSession(session);
            }
        });
        statusText.setText("DreamSync is Off. Automatic sleep detection and cues are disabled.");
        updatePowerState();
        updateReadiness();
    }

    private void retryPendingRemoteDisable() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("remote_disable_pending", false)) return;
        String enrollment = pref("auto_enrollment_id");
        String phone = pref("local_phone_node");
        String watch = pref("bound_watch_node");
        if (enrollment.isEmpty() || phone.isEmpty() || watch.isEmpty()) {
            prefs.edit().putBoolean("remote_disable_pending", false).apply();
            DebugLog.append(this, "AUTO_OFF_RETRY_SKIPPED", "binding incomplete");
            return;
        }
        DebugLog.append(this, "AUTO_OFF_RETRY_REQUESTED", "durableDataItem=true");
        PhoneAutoControl.publishAutoConfig(this, false, enrollment,
                        phone, watch, selectedMode(), cueGain())
                .addOnSuccessListener(item -> {
                    prefs.edit().putBoolean("remote_disable_pending", false).apply();
                    DebugLog.append(this, "AUTO_OFF_RETRY_PUBLISHED",
                            "durableDataItem=true");
                })
                .addOnFailureListener(error -> DebugLog.append(this,
                        "AUTO_OFF_RETRY_FAILED", safeMessage(error)));
    }

    private boolean isCurrentEnableGeneration(String generation) {
        return generation != null
                && !generation.isEmpty()
                && generation.equals(pref("pending_enable_generation"));
    }

    private void failPendingEnable(String generation, String message) {
        if (!isCurrentEnableGeneration(generation)) return;
        DebugLog.append(this, "TURN_ON_FAILED", message);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .remove("pending_enable_generation")
                .apply();
        statusText.setText(message);
    }

    private void syncAutoSettingsIfEnabled() {
        if (!autoEnabled()) return;
        String enrollment = pref("auto_enrollment_id");
        String phone = pref("local_phone_node");
        String watch = pref("bound_watch_node");
        if (enrollment.isEmpty() || phone.isEmpty() || watch.isEmpty()) return;
        String mode = selectedMode();
        float gain = cueGain();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("auto_mode", mode)
                .putFloat("auto_gain", gain)
                .apply();
        PhoneAutoControl.publishAutoConfig(this, true, enrollment, phone, watch, mode, gain)
                .addOnSuccessListener(item -> DebugLog.append(this,
                        "AUTO_SETTINGS_PUBLISHED", "mode=" + mode + ";gain=" + gain))
                .addOnFailureListener(error -> DebugLog.append(this,
                        "AUTO_SETTINGS_PUBLISH_FAILED", safeMessage(error)));
    }

    private void sendStopToWatch(String sessionId, String phoneNode, String watchNode) {
        if (sessionId.isEmpty() || phoneNode.isEmpty() || watchNode.isEmpty()) return;
        ProtocolEnvelope envelope = ProtocolEnvelope.create(
                com.remote.DreamSync.shared.Protocol.TYPE_STOP,
                sessionId, "", phoneNode, watchNode,
                System.currentTimeMillis(),
                com.remote.DreamSync.shared.Protocol.CONTROL_TTL_MS, Map.of());
        Wearable.getMessageClient(this).sendMessage(
                        watchNode, Protocol.PATH_STOP, envelope.encode())
                .addOnSuccessListener(requestId -> DebugLog.append(this,
                        "WATCH_STOP_DELIVERED", "sessionId=" + sessionId))
                .addOnFailureListener(error -> DebugLog.append(this,
                        "WATCH_STOP_FAILED", safeMessage(error)));
    }

    private void armAudioService(
            String action, float gain, String sessionId, String localNode, String watchNode
    ) {
        Intent intent = new Intent(this, CuePlaybackService.class)
                .setAction(action)
                .putExtra(CuePlaybackService.EXTRA_GAIN, gain)
                .putExtra(CuePlaybackService.EXTRA_SESSION_ID, sessionId)
                .putExtra(CuePlaybackService.EXTRA_LOCAL_NODE_ID, localNode)
                .putExtra(CuePlaybackService.EXTRA_WATCH_NODE_ID, watchNode);
        startForegroundService(intent);
    }

    private void showFriendlySummary(String summaryText) {
        try {
            JSONObject summary = new JSONObject(summaryText);
            String line = "Session " + summary.optLong("minutes", 0) + " min"
                    + " | Deep " + summary.optInt("deepCues", 0)
                    + " | Lucid " + summary.optInt("lucidCues", 0)
                    + " | Awakenings " + summary.optInt("awakeningCount", 0)
                    + " | WASO " + Math.round(summary.optDouble(
                    "wakeAfterSleepOnsetMinutes", 0.0)) + " min"
                    + " | Longest bout " + Math.round(summary.optDouble(
                    "longestUninterruptedBoutMinutes", 0.0)) + " min"
                    + " | Cue-linked wake rate " + Math.round(100.0 * summary.optDouble(
                    "cueLinkedArousalRate", 0.0)) + "%"
                    + " | Signal " + summary.optString("signalQuality", "unknown")
                    + " | End " + summary.optString("terminalState", "complete");
            statusText.setText(line);
        } catch (JSONException error) {
            statusText.setText("Session complete. Your morning check-in is ready.");
        }
    }

    private void showMorningSurvey(String sessionId) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        form.setPadding(pad, 0, pad, 0);
        EditText sleep = field(form, "Sleep quality 1-10", true);
        EditText rested = field(form, "Restedness 1-10", true);
        EditText awakenings = field(form, "Remembered awakenings", true);
        EditText disturbance = field(form, "How disturbing were cues? 0-10", true);
        EditText awareness = field(form, "Cue noticed: no / unsure / yes", false);
        EditText recall = field(form, "Dream recall 0-3", true);
        EditText lucid = field(form, "Lucid dream: no / unsure / yes", false);
        EditText adverse = field(form, "Headache, nightmare, anxiety, sleepiness, or none", false);
        EditText notes = field(form, "Dream journal and notes", false);

        new AlertDialog.Builder(this)
                .setTitle("Morning check-in")
                .setMessage("Save how the night felt and any dream notes.")
                .setView(form)
                .setPositiveButton("Save", (dialog, which) -> {
                    MorningSurveyEntity survey = new MorningSurveyEntity();
                    survey.sessionId = sessionId;
                    survey.sleepQuality = boundedInt(sleep.getText().toString(), 1, 10, 0);
                    survey.restedness = boundedInt(rested.getText().toString(), 1, 10, 0);
                    survey.rememberedAwakenings = boundedInt(
                            awakenings.getText().toString(), 0, 100, 0);
                    survey.disturbanceRating = boundedInt(
                            disturbance.getText().toString(), 0, 10, 0);
                    survey.cueAwareness = choice(awareness.getText().toString());
                    survey.dreamRecall = boundedInt(recall.getText().toString(), 0, 3, 0);
                    survey.lucidDream = choice(lucid.getText().toString());
                    survey.adverseEffects = adverse.getText().toString().trim();
                    survey.dreamNotes = notes.getText().toString().trim();
                    survey.submittedUtcMs = System.currentTimeMillis();
                    applyDisturbanceGain(survey.disturbanceRating, sessionId);
                    databaseExecutor.execute(() -> {
                        dao.upsertSurvey(survey);
                        SessionEntity session = dao.session(sessionId);
                        if (session != null) {
                            dao.upsertSession(session);
                            runOnUiThread(() -> {
                                showFriendlySummary(session.summaryJson);
                                statusText.setText("Morning check-in saved.");
                            });
                        }
                    });
                })
                .setNegativeButton("Later", null)
                .show();
    }

    private EditText field(LinearLayout form, String hint, boolean numeric) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextColor(getColor(R.color.ds_text));
        input.setHintTextColor(getColor(R.color.ds_muted));
        input.setSingleLine(!hint.contains("journal"));
        if (numeric) input.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return input;
    }

    private void applySummaryGain(String summaryText, String sessionId) {
        if (sessionId == null || sessionId.isEmpty()
                || sessionId.equals(pref("gain_adjusted_session"))) return;
        try {
            JSONObject summary = new JSONObject(summaryText);
            int finalPercent = (int) Math.round(100.0 * summary.optDouble(
                    "finalGain", gainSeek.getProgress() / 100.0));
            if (finalPercent < gainSeek.getProgress()) {
                gainSeek.setProgress(Math.max(1, finalPercent));
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .remove("last_calibration")
                        .putString("gain_adjusted_session", sessionId)
                        .apply();
            } else {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("gain_adjusted_session", sessionId).apply();
            }
        } catch (JSONException ignored) {}
    }

    private void applyDisturbanceGain(int disturbanceRating, String sessionId) {
        if (disturbanceRating <= 0) return;
        int reduction = Math.max(1, (int) Math.ceil(disturbanceRating / 2.0));
        gainSeek.setProgress(Math.max(1, gainSeek.getProgress() - reduction));
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .remove("last_calibration")
                .putString("disturbance_adjusted_session", sessionId)
                .apply();
    }

    private void showHistory() {
        databaseExecutor.execute(() -> {
            List<SessionEntity> sessions = dao.sessions();
            StringBuilder text = new StringBuilder();
            for (SessionEntity session : sessions) {
                MorningSurveyEntity survey = dao.survey(session.sessionId);
                text.append(session.mode)
                        .append(" | ").append(session.state);
                if (survey != null) {
                    text.append(" | sleep ").append(survey.sleepQuality)
                            .append(" | rested ").append(survey.restedness)
                            .append(" | disturbance ").append(survey.disturbanceRating);
                }
                text.append('\n');
            }
            String result = text.length() == 0 ? "No saved sessions." : text.toString();
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("Sleep history")
                    .setMessage(result)
                    .setPositiveButton("Done", null)
                    .show());
        });
    }

    private void prepareExport() {
        statusText.setText("Preparing your local data export...");
        databaseExecutor.execute(() -> {
            try {
                pendingExportJson = buildExportJson().toString(2);
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, "dreamsync-data-export.json");
                runOnUiThread(() -> startActivityForResult(intent, REQUEST_EXPORT));
            } catch (JSONException error) {
                runOnUiThread(() -> statusText.setText("Export failed: " + safeMessage(error)));
            }
        });
    }

    private void prepareDebugExport() {
        statusText.setText("Preparing debug log...");
        remoteExecutor.execute(() -> {
            pendingDebugLog = DebugLog.export(
                    getApplicationContext(), new AudioOutput(getApplicationContext()));
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TITLE, "dreamsync-phone-debug-log.txt");
            runOnUiThread(() -> startActivityForResult(intent, REQUEST_DEBUG_EXPORT));
        });
    }

    JSONObject buildExportJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", 3);
        root.put("generatedUtcMs", System.currentTimeMillis());
        root.put("productLanguage", "wellness sleep-cue tool; non-medical");
        root.put("deviceIdentifiersRemoved", true);
        root.put("containsUserEnteredText", true);
        root.put("versions", new JSONObject()
                .put("protocol", com.remote.DreamSync.shared.Protocol.VERSION)
                .put("featureEngine", com.remote.DreamSync.shared.SleepFeatureEngine.VERSION)
                .put("qualityMonitor", com.remote.DreamSync.shared.SignalQualityMonitor.VERSION)
                .put("eligibilityEstimator", com.remote.DreamSync.shared.EligibilityEstimator.VERSION)
                .put("replayHarness", com.remote.DreamSync.shared.ReplayHarness.ENGINE_VERSION)
                .put("waveform", com.remote.DreamSync.shared.CueWaveforms.VERSION));
        root.put("automaticModeEnabled", autoEnabled());
        root.put("chargeReminderLeadMinutes", ChargeReminderScheduler.LEAD_MINUTES);
        root.put("chargeReminderRecordCount", getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt("charge_reminder_record_count", 0));
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        root.put("audioDiagnostics", new JSONObject()
                .put("lastCalibration", prefs.getString("last_calibration", ""))
                .put("lastCalibrationUtcMs", prefs.getLong("last_calibration_utc", 0L))
                .put("lastError", prefs.getString("last_audio_error", ""))
                .put("lastErrorUtcMs", prefs.getLong("last_audio_error_utc", 0L)));
        JSONArray sessionArray = new JSONArray();
        for (SessionEntity session : dao.sessions()) {
            JSONObject item = new JSONObject();
            item.put("sessionId", session.sessionId);
            item.put("mode", session.mode);
            item.put("state", session.state);
            item.put("startUtcMs", session.startUtcMs);
            item.put("endUtcMs", session.endUtcMs);
            item.put("protocolVersion", session.protocolVersion);
            item.put("waveformVersion", session.waveformVersion);
            item.put("calibration", session.calibrationRecord);
            item.put("watchCapabilities", safeStoredObject(session.watchCapabilityJson));
            if (session.configurationJson != null && !session.configurationJson.isEmpty()) {
                JSONObject safeConfig = safeStoredObject(session.configurationJson);
                safeConfig.remove("phoneNodeId");
                safeConfig.remove("watchNodeId");
                item.put("configuration", safeConfig);
            }
            if (session.summaryJson != null && !session.summaryJson.isEmpty()) {
                item.put("summary", safeStoredObject(session.summaryJson));
            }
            JSONArray events = new JSONArray();
            for (EventEntity event : dao.events(session.sessionId)) {
                events.put(new JSONObject()
                        .put("cueId", event.cueId)
                        .put("type", event.type)
                        .put("monotonicMs", event.monotonicMs)
                        .put("utcMs", event.utcMs)
                        .put("details", event.details));
            }
            item.put("events", events);
            java.io.File watchLog = new java.io.File(
                    new java.io.File(getFilesDir(), "sessions"),
                    "session-" + session.sessionId + ".csv");
            if (watchLog.isFile() && watchLog.length() <= 2L * 1024L * 1024L) {
                try {
                    item.put("eligibilityEpochCsv", new String(
                            java.nio.file.Files.readAllBytes(watchLog.toPath()),
                            StandardCharsets.UTF_8));
                } catch (java.io.IOException error) {
                    item.put("eligibilityEpochReadError", safeMessage(error));
                }
            }
            MorningSurveyEntity survey = dao.survey(session.sessionId);
            if (survey != null) item.put("morningSurvey", surveyJson(survey));
            sessionArray.put(item);
        }
        root.put("sessions", sessionArray);
        return root;
    }

    private JSONObject safeStoredObject(String value) throws JSONException {
        if (value == null || value.isBlank()) return new JSONObject();
        try {
            return new JSONObject(value);
        } catch (JSONException error) {
            return new JSONObject()
                    .put("parseError", true)
                    .put("reason", "Stored data was malformed; other export data was preserved.");
        }
    }

    private JSONObject surveyJson(MorningSurveyEntity survey) throws JSONException {
        return new JSONObject()
                .put("sleepQuality", survey.sleepQuality)
                .put("restedness", survey.restedness)
                .put("rememberedAwakenings", survey.rememberedAwakenings)
                .put("disturbanceRating", survey.disturbanceRating)
                .put("cueAwareness", survey.cueAwareness)
                .put("dreamRecall", survey.dreamRecall)
                .put("lucidDream", survey.lucidDream)
                .put("adverseEffects", survey.adverseEffects)
                .put("dreamNotes", survey.dreamNotes)
                .put("submittedUtcMs", survey.submittedUtcMs);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != REQUEST_EXPORT && requestCode != REQUEST_DEBUG_EXPORT)
                || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new IllegalStateException("No output stream");
            String contents = requestCode == REQUEST_DEBUG_EXPORT
                    ? pendingDebugLog : pendingExportJson;
            output.write(contents.getBytes(StandardCharsets.UTF_8));
            statusText.setText(requestCode == REQUEST_DEBUG_EXPORT
                    ? "Debug log saved. Send that file so the failure can be traced."
                    : "Data export saved.");
        } catch (Exception error) {
            statusText.setText("Could not save export: " + safeMessage(error));
        }
    }

    private void confirmDeleteAll() {
        if (autoEnabled() || !pref("active_session_id").isEmpty()) {
            statusText.setText("Turn DreamSync Off before deleting data.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete DreamSync data?")
                .setMessage("This removes session history, cue events, surveys, and received logs from the phone. It also sends a secure deletion request to the bound watch. It cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> databaseExecutor.execute(() -> {
                    dao.deleteAllSurveys();
                    dao.deleteAllEvents();
                    dao.deleteAllSessions();
                    deleteReceivedLogs();
                    DebugLog.clear(getApplicationContext());
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .remove("last_summary").remove("last_summary_session").apply();
                    runOnUiThread(this::publishWatchDeleteRequest);
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void publishWatchDeleteRequest() {
        String enrollment = pref("auto_enrollment_id");
        String phoneNode = pref("local_phone_node");
        String watchNode = pref("bound_watch_node");
        if (enrollment.isEmpty() || phoneNode.isEmpty() || watchNode.isEmpty()) {
            statusText.setText("Phone data deleted. No bound watch data was found.");
            return;
        }
        ProtocolEnvelope envelope = ProtocolEnvelope.create(
                com.remote.DreamSync.shared.Protocol.TYPE_DELETE_DATA,
                enrollment, "", phoneNode, watchNode,
                System.currentTimeMillis(),
                com.remote.DreamSync.shared.Protocol.CONTROL_TTL_MS,
                Map.of("reason", "USER_CONFIRMED_DELETE"));
        PutDataMapRequest map = PutDataMapRequest.create(
                com.remote.DreamSync.shared.Protocol.PATH_DELETE_DATA_PREFIX
                        + envelope.messageId);
        map.getDataMap().putByteArray("envelope", envelope.encode());
        PutDataRequest request = map.asPutDataRequest();
        request.setUrgent();
        Wearable.getDataClient(this).putDataItem(request)
                .addOnSuccessListener(item -> statusText.setText(
                        "Phone data deleted. Watch deletion will finish when the watch connects."))
                .addOnFailureListener(error -> statusText.setText(
                        "Phone data deleted. Watch deletion could not be queued: "
                                + safeMessage(error)));
    }

    private void deleteReceivedLogs() {
        java.io.File directory = new java.io.File(getFilesDir(), "sessions");
        java.io.File[] files = directory.listFiles();
        if (files == null) return;
        for (java.io.File file : files) if (file.isFile()) file.delete();
    }

    private void updateReadiness() {
        AudioOutput.Result audio = new AudioOutput(this).preflight();
        String calibration = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("last_calibration", "");
        boolean calibrationHeard = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("last_calibration_audible_confirmed", false);
        int practice = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt("lucid_practice_count", 0);
        String compatibility = "not checked";
        String capabilityJson = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("watch_capabilities", "");
        try {
            if (!capabilityJson.isEmpty()) {
                compatibility = new JSONObject(capabilityJson)
                        .optString("status", "not checked").replace('_', ' ')
                        .toLowerCase(Locale.US);
            }
        } catch (JSONException ignored) {}
        int chargeRecords = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt("charge_reminder_record_count", 0);
        String systemCheck = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("last_system_check_status", "not run");
        readinessText.setText((autoEnabled() ? "ON" : "OFF")
                + " | speaker " + (audio.success ? "ready" : audio.reason)
                + " | calibration " + (calibration.isEmpty() || !calibrationHeard
                ? "needed" : "heard + saved")
                + " | lucid practice " + practice + "/10"
                + " | watch " + compatibility
                + " | system check " + systemCheck.toLowerCase(Locale.US)
                + " | charge reminder " + (chargeRecords >= ChargeReminderScheduler.MIN_RECORDS
                ? "learned" : chargeRecords + "/3 nights"));
        updatePowerState();
    }

    private void updatePowerState() {
        if (powerButton != null) powerButton.setText(
                autoEnabled() ? "Turn DreamSync Off" : "Turn DreamSync On");
    }

    private boolean autoEnabled() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("auto_enabled", false);
    }

    private void showOnboardingOnce() {
        if (getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("onboarding_seen", false)) return;
        new AlertDialog.Builder(this)
                .setTitle("Welcome to DreamSync")
                .setMessage("Dreams Redefined. DreamSync's mission is to protect sleep continuity. It estimates likely sleep from wearable signals, stays quiet when confidence is low, and uses gentle research-shaped cues only when strict safety checks indicate a low risk of disturbance. "
                        + "Turn DreamSync On once. It passively watches for supported sleep signals every night and starts conservative confirmation after likely sleep. Turn it Off at any time. "
                        + "After three recorded sleep starts, the phone learns your usual sleep time and reminds you to charge the watch three hours earlier. Until then, charge it several hours before your normal sleep time. "
                        + "First validation targets Galaxy Watch Ultra 2024 and 2025. Other Galaxy Watch4-and-later models receive basic monitoring unless DreamSync's live sensor check confirms advanced support. Apple Watch is planned but is not supported in this release. "
                        + "DreamSync is an adult wellness app that estimates cue-ready windows from wrist motion and heart rate. "
                        + "Its deep cue pattern is shaped by peer-reviewed EEG research, but DreamSync does not measure EEG or reproduce laboratory phase locking. "
                        + "It is not a medical device and does not diagnose, treat, cure, or prevent any medical condition. "
                        + "Stop if cues disturb sleep or cause adverse effects, and consult a qualified healthcare professional for medical advice. "
                        + "Data stays local unless you export it.")
                .setPositiveButton("I understand", (dialog, which) ->
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putBoolean("onboarding_seen", true).apply())
                .setCancelable(false)
                .show();
    }

    private void showSleepProtectionAndCoaching() {
        boolean enabled = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("protect_notifications", false);
        new AlertDialog.Builder(this)
                .setTitle("Sleep protection")
                .setMessage("Before bed:\n\n"
                        + "- Keep a regular sleep schedule.\n"
                        + "- Get daylight and exercise earlier in the day.\n"
                        + "- Try a warm bath 1-2 hours before bed.\n"
                        + "- Use slow, comfortable breathing while winding down.\n\n"
                        + "Charge your watch several hours before your normal sleep time. After three recorded nights, DreamSync schedules this reminder three hours before your learned usual sleep time.\n\n"
                        + "During sleep DreamSync keeps screens and haptics off, pauses cues for 20 minutes after an arousal sign, and stops after the first cue-linked wake sign.\n\n"
                        + "Phone notification protection: " + (enabled ? "ON" : "OFF")
                        + ". If Samsung's Sync Do not disturb with phone setting is enabled, the watch follows the phone. Alarms and allowed priority contacts follow your system settings.\n\n"
                        + "Frequent gasping, choking, or severe sleep fragmentation needs professional medical advice; DreamSync does not diagnose apnea.")
                .setPositiveButton(enabled ? "Turn protection off" : "Turn protection on",
                        (dialog, which) -> {
                            boolean next = !enabled;
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putBoolean("protect_notifications", next).apply();
                            if (next) {
                                NotificationManager manager = getSystemService(NotificationManager.class);
                                if (manager == null
                                        || !manager.isNotificationPolicyAccessGranted()) {
                                    statusText.setText("Allow notification access. Protection applies automatically during detected sleep.");
                                    startActivity(new Intent(
                                            Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                                } else {
                                    statusText.setText("Notification protection will turn on only during a detected sleep session.");
                                }
                            } else {
                                restoreNotificationProtection();
                                statusText.setText("Notification protection is off.");
                            }
                        })
                .setNegativeButton("Done", null)
                .show();
    }

    private void applyNotificationProtection() {
        if (!getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("protect_notifications", false)) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || !manager.isNotificationPolicyAccessGranted()) return;
        int current = manager.getCurrentInterruptionFilter();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt("previous_interruption_filter", current)
                .putBoolean("notification_protection_active", true)
                .apply();
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
    }

    private void restoreNotificationProtection() {
        if (!getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("notification_protection_active", false)) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && manager.isNotificationPolicyAccessGranted()) {
            int previous = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(
                    "previous_interruption_filter",
                    NotificationManager.INTERRUPTION_FILTER_ALL);
            manager.setInterruptionFilter(previous);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .remove("previous_interruption_filter")
                .putBoolean("notification_protection_active", false)
                .apply();
    }

    private void showWatchCompatibility() {
        String json = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("watch_capabilities", "");
        String live;
        if (json.isEmpty()) {
            live = "No live sensor check yet. Turn DreamSync On and open the watch once.";
        } else {
            try {
                JSONObject values = new JSONObject(json);
                String status = values.optString("status", "BASIC_MONITORING_ONLY")
                        .replace('_', ' ');
                live = values.optString("manufacturer", "") + " "
                        + values.optString("model", "") + "\n"
                        + status + "\n" + values.optString("reason", "");
            } catch (JSONException error) {
                live = "Live compatibility data could not be read. Run a new automatic session.";
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Watch compatibility")
                .setMessage(live + "\n\nCore support:\n"
                        + "Android 8.0+ phone with Google Play services\n"
                        + "Wear OS 3+ watch with motion and heart rate\n\n"
                        + "DreamSync checks the actual watch sensors. Core watches use standard motion and heart rate. More capable watches automatically keep every available approved feature, including IBI/HRV, skin temperature, off-wrist checks, and raw PPG when the sensor provider supports them. Apple Watch, Fitbit, Garmin, Galaxy Fit, and older Tizen watches are not supported in this release.")
                .setPositiveButton("Done", null)
                .show();
    }

    private void showScience() {
        TextView content = new TextView(this);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        content.setTextColor(getColor(R.color.ds_ice));
        content.setLinkTextColor(getColor(R.color.ds_violet));
        content.setTextSize(15f);
        content.setLineSpacing(0f, 1.2f);
        content.setMovementMethod(LinkMovementMethod.getInstance());
        content.setText(Html.fromHtml(
                "<b>Dreams Redefined.</b><br>"
                        + "Our mission is to protect sleep continuity. DreamSync estimates likely sleep from wearable signals, stays quiet when confidence is low, and uses gentle research-shaped cues only when strict safety checks indicate a low risk of disturbance.<br><br>"
                        + "<b>Watch compatibility</b><br>"
                        + "DreamSync supports Android 8.0+ phones with Google Play services and targets Wear OS 3+ watches that pass the live motion, heart-rate, software, and permission checks. Core watches use standard Wear OS signals. High-end watches automatically keep every available approved advanced feature; Galaxy Watch models can add IBI/HRV, skin-temperature, off-wrist, and raw-PPG signals when the Samsung sensor package and live self-test pass. Apple Watch, Fitbit, Garmin, Galaxy Fit, and older Tizen watches are not supported in this release.<br><br>"
                        + "<b>What DreamSync does</b><br>"
                        + "1. Turn <b>DreamSync On</b> once. Low-power watch monitoring checks for an asleep-state signal, or uses a conservative passive heart-rate fallback on compatible watches, every night until you turn it Off.<br>"
                        + "2. Your watch estimates sleep onset from sustained low motion, heart-rate change, signal quality, time of night, and—when the approved Samsung SDK is packaged—IBI stability, skin-temperature trend, and off-wrist status. This is an estimate, not a sleep-stage diagnosis.<br>"
                        + "3. Cues are allowed only after a stable, low-motion period, from 25 to 145 minutes after estimated sleep onset.<br>"
                        + "4. A deep cue is two 50 ms pink-noise bursts, 1.075 seconds apart, with 5 ms fades.<br>"
                        + "5. Default deep mode requests no more than 12 paired cues. Any arousal sign starts a 20-minute quiet period. The first cue-linked wake sign stops cues for the night and lowers the saved session gain. A seven-night rolling baseline personalizes safe estimates; earlier nights use conservative defaults.<br><br>"
                        + "<b>What the peer-reviewed study found</b><br>"
                        + "In a randomized crossover laboratory study of 14 healthy young men, EEG phase-locked paired tones increased slow-oscillation power and amplitude without changing time spent in the measured sleep stages. About 172 paired stimulations were delivered on average across 120 minutes. Hormone analyses used 10 to 14 participants, and immune-cell results used a nine-person subset; growth hormone was unchanged. DreamSync does not measure or claim those outcomes.<br><br>"
                        + "<b>The important difference</b><br>"
                        + "The laboratory system used EEG, in-ear headphones, and sound calibrated to 50 dB SPL. DreamSync has no EEG, blocks headphone routes, and uses a user-calibrated phone speaker level rather than a laboratory SPL value. It copies the published acoustic pattern, not the phase-locking method. Open-loop research did not show a word-pair memory benefit and some participants noticed the sounds; this is why DreamSync uses fewer, barely audible cues and stops early when waking is detected.<br><br>"
                        + "Lucid cues are a separate conditioning feature. These studies do not prove that DreamSync causes lucid dreams.<br><br>"
                        + "<b>Peer-reviewed sources</b><br>"
                        + "<a href=\"https://doi.org/10.1038/s41467-017-02170-3\">Besedovsky et al., Nature Communications (2017)</a><br>"
                        + "<a href=\"https://doi.org/10.1016/j.neuron.2013.03.006\">Ngo et al., Neuron (2013)</a><br>"
                        + "<a href=\"https://doi.org/10.1523/JNEUROSCI.3133-14.2015\">Ngo et al., Journal of Neuroscience (2015)</a><br>"
                        + "<a href=\"https://doi.org/10.1111/ejn.13334\">Weigenand et al., European Journal of Neuroscience (2016)</a><br><br>"
                        + "<b>Safety</b><br>DreamSync is not a medical device and does not diagnose, treat, cure, or prevent any medical condition. Consult a qualified healthcare professional for medical advice, diagnosis, or treatment.",
                Html.FROM_HTML_MODE_LEGACY));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        new AlertDialog.Builder(this)
                .setTitle("How DreamSync works")
                .setView(scroll)
                .setPositiveButton("Done", null)
                .show();
    }

    private void showPrivacyAndSafety() {
        new AlertDialog.Builder(this)
                .setTitle("Privacy & safety")
                .setMessage("DreamSync uses motion and heart-rate readings from your watch only to estimate cue-ready windows during a night session. "
                        + "Session history, cue events, check-ins, and dream notes stay on your devices unless you choose Export. "
                        + "DreamSync has no account, ads, analytics, or automatic cloud upload. You can export or delete your data at any time.\n\n"
                        + "Wellness only: DreamSync does not measure EEG or guarantee deeper sleep, memory, immune, or lucid-dream outcomes. "
                        + "It is not a medical device and does not diagnose, treat, cure, or prevent any medical condition. "
                        + "Stop using it if cues disturb your sleep, and consult a qualified healthcare professional for medical advice, diagnosis, or treatment.")
                .setPositiveButton("Done", null)
                .show();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(PhoneDataListenerService.ACTION_SUMMARY_CHANGED);
        filter.addAction(PhoneDataListenerService.ACTION_STATUS_CHANGED);
        ContextCompat.registerReceiver(
                this, summaryReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        summaryReceiverRegistered = true;
    }

    @Override protected void onResume() {
        super.onResume();
        expireSystemCheckIfNeeded();
        String summary = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("last_summary", "");
        if (!summary.isEmpty()) showFriendlySummary(summary);
        updateReadiness();
        updatePowerState();
        String pendingGeneration = pref("pending_enable_generation");
        if (!pendingGeneration.isEmpty() && !autoEnabled()
                && getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("watch_install_pending", false)
                && watchInstallPollAttempt == 0) {
            scheduleWatchInstallCheck(pendingGeneration);
        }
    }

    private void setupOrOpenWatch() {
        statusText.setText("Finding the paired Wear OS watch...");
        findWatchForOpen(CapabilityClient.FILTER_REACHABLE);
    }

    private void findWatchForOpen(int filter) {
        Wearable.getCapabilityClient(this).getCapability(
                        com.remote.DreamSync.shared.Protocol.WATCH_CAPABILITY, filter)
                .addOnSuccessListener(capability -> {
                    Node watch = chooseNode(capability.getNodes());
                    if (watch != null) {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putString("bound_watch_node", watch.getId())
                                .putBoolean("watch_install_pending", false)
                                .apply();
                        openWatchApp(false, "", watch.getId());
                    } else if (filter == CapabilityClient.FILTER_REACHABLE) {
                        findWatchForOpen(CapabilityClient.FILTER_ALL);
                    } else {
                        openWatchPlayStoreForInstall("");
                    }
                })
                .addOnFailureListener(error -> statusText.setText(
                        "Could not check the paired watch: " + safeMessage(error)));
    }

    private void openWatchApp(boolean setup, String enrollmentId) {
        String targetNodeId = pref("bound_watch_node");
        if (targetNodeId.isEmpty()) {
            if (setup) statusText.setText("Watch setup is waiting for the Play Store install.");
            else setupOrOpenWatch();
            return;
        }
        openWatchApp(setup, enrollmentId, targetNodeId);
    }

    private void openWatchApp(boolean setup, String enrollmentId, String targetNodeId) {
        Intent remoteIntent = new Intent(Intent.ACTION_VIEW)
                .setData(setup
                        ? Uri.parse("dreamsync://watch/enable").buildUpon()
                        .appendQueryParameter("enrollment", enrollmentId).build()
                        : Uri.parse("dreamsync://watch/start"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        launchRemoteIntent(remoteIntent, targetNodeId,
                setup
                        ? "Watch opened. Approve health access once. DreamSync is then controlled by the phone."
                        : "Watch opened. DreamSync controls remain on the phone.",
                "Could not open the watch: ", null);
    }

    private void launchRemoteIntent(
            Intent intent,
            String targetNodeId,
            String successMessage,
            String failurePrefix,
            Runnable onDelivered
    ) {
        try {
            ListenableFuture<Void> launch = new RemoteActivityHelper(this, remoteExecutor)
                    .startRemoteActivity(intent, targetNodeId);
            launch.addListener(() -> {
                try {
                    launch.get();
                    runOnUiThread(() -> {
                        statusText.setText(successMessage);
                        if (onDelivered != null) onDelivered.run();
                    });
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    runOnUiThread(() -> statusText.setText(
                            failurePrefix + safeMessage(cause)));
                }
            }, remoteExecutor);
        } catch (RuntimeException error) {
            statusText.setText(failurePrefix + safeMessage(error));
        }
    }

    private Node chooseNode(Iterable<Node> nodes) {
        if (nodes == null) return null;
        List<Node> source = new ArrayList<>();
        List<PeerSelector.Candidate> candidates = new ArrayList<>();
        for (Node node : nodes) {
            source.add(node);
            candidates.add(new PeerSelector.Candidate(node.getId(), node.isNearby()));
        }
        String selected = PeerSelector.select(candidates, pref("bound_watch_node"));
        for (Node node : source) if (selected.equals(node.getId())) return node;
        return null;
    }

    private String selectedMode() {
        int checked = modeGroup.getCheckedRadioButtonId();
        if (checked == R.id.modeLucid) return "LUCID";
        if (checked == R.id.modeCombined) return "COMBINED";
        return "DEEP";
    }

    private float cueGain() {
        return Math.max(0.01f, Math.min(0.10f, gainSeek.getProgress() / 100f));
    }

    private String pref(String key) {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(key, "");
    }

    private int boundedInt(String value, int min, int max, int fallback) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(value.trim()))); }
        catch (Exception ignored) { return fallback; }
    }

    private String choice(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.US);
        if ("YES".equals(normalized) || "NO".equals(normalized)) return normalized;
        return "UNSURE";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String safeMessage(Throwable error) {
        if (error == null) return "unknown error";
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    @Override protected void onStop() {
        if (summaryReceiverRegistered) {
            unregisterReceiver(summaryReceiver);
            summaryReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (remoteExecutor != null) remoteExecutor.shutdownNow();
        if (databaseExecutor != null) databaseExecutor.shutdown();
        super.onDestroy();
    }
}
