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
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.common.util.concurrent.ListenableFuture;
import com.remote.DreamSync.shared.ProtocolEnvelope;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 101;
    private static final int REQUEST_EXPORT = 602;
    private static final String PREFS = "dreamsync_phone";

    private TextView statusText;
    private TextView readinessText;
    private TextView gainLabel;
    private RadioGroup modeGroup;
    private SeekBar gainSeek;
    private ExecutorService remoteExecutor;
    private ExecutorService databaseExecutor;
    private PhoneDao dao;
    private boolean summaryReceiverRegistered;
    private String pendingExportJson = "";

    private final BroadcastReceiver summaryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (PhoneDataListenerService.ACTION_STATUS_CHANGED.equals(intent.getAction())) {
                String status = intent.getStringExtra("status");
                if (status != null) statusText.setText(status);
                String state = intent.getStringExtra("state");
                String sessionId = intent.getStringExtra("sessionId");
                if ("ARMED".equals(state)
                        && sessionId != null
                        && sessionId.equals(pref("active_session_id"))
                        && !sessionId.equals(pref("auto_opened_watch_session"))) {
                    statusText.setText("Watch ready. Opening automatic sleep estimation...");
                    openWatchApp(true, sessionId);
                }
                updateReadiness();
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
        remoteExecutor = Executors.newSingleThreadExecutor();
        databaseExecutor = Executors.newSingleThreadExecutor();
        dao = PhoneDatabase.get(this).dao();
        requestNotificationPermissionIfNeeded();
        if (pref("active_session_id").isEmpty()) restoreNotificationProtection();
        showOnboardingOnce();
        wireActions();
        updateReadiness();
    }

    private void bindViews() {
        statusText = findViewById(R.id.statusText);
        readinessText = findViewById(R.id.readinessText);
        gainLabel = findViewById(R.id.gainLabel);
        modeGroup = findViewById(R.id.modeGroup);
        gainSeek = findViewById(R.id.gainSeek);
    }

    private void wireActions() {
        gainSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                gainLabel.setText("Cue volume: " + progress + "%");
                updateReadiness();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        findViewById(R.id.previewDeepButton).setOnClickListener(v -> previewCue("DEEP"));
        findViewById(R.id.practiceLucidButton).setOnClickListener(v -> runLucidPractice());
        findViewById(R.id.openWatchButton).setOnClickListener(v -> openWatchApp(false, ""));
        findViewById(R.id.armButton).setOnClickListener(v -> startTransactionalSession());
        findViewById(R.id.stopButton).setOnClickListener(v -> stopSession());
        findViewById(R.id.morningButton).setOnClickListener(v -> {
            String sessionId = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString("last_summary_session", "");
            if (sessionId.isEmpty()) statusText.setText("No completed session is ready for a survey.");
            else showMorningSurvey(sessionId);
        });
        findViewById(R.id.historyButton).setOnClickListener(v -> showHistory());
        findViewById(R.id.exportButton).setOnClickListener(v -> prepareExport());
        findViewById(R.id.deleteDataButton).setOnClickListener(v -> confirmDeleteAll());
        findViewById(R.id.scienceButton).setOnClickListener(v -> showScience());
        findViewById(R.id.sleepProtectionButton).setOnClickListener(
                v -> showSleepProtectionAndCoaching());
        findViewById(R.id.compatibilityButton).setOnClickListener(
                v -> showWatchCompatibility());
        findViewById(R.id.privacyButton).setOnClickListener(v -> showPrivacyAndSafety());
    }

    private void previewCue(String type) {
        AudioOutput.Result preflight = new AudioOutput(this).preflight();
        if (!preflight.success) {
            statusText.setText("Cannot preview cue: " + preflight.reason + ".");
            return;
        }
        String action = "LUCID".equals(type)
                ? CuePlaybackService.ACTION_PREVIEW_LUCID : CuePlaybackService.ACTION_PREVIEW_DEEP;
        armAudioService(action, cueGain(), "", "", "");
        statusText.setText("Cue played on the phone speaker. Set it barely audible, then one step lower.");
        readinessText.postDelayed(this::updateReadiness, 1_500L);
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

    private void startTransactionalSession() {
        if (!pref("active_session_id").isEmpty()) {
            statusText.setText("A night is already armed. Tap Stop before starting another.");
            return;
        }
        AudioOutput audio = new AudioOutput(this);
        AudioOutput.Result preflight = audio.preflight();
        if (!preflight.success) {
            statusText.setText("Not ready: " + preflight.reason + ".");
            return;
        }
        String calibration = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("last_calibration", "");
        if (calibration.isEmpty()) {
            statusText.setText("Preview a cue at the final volume before starting.");
            return;
        }
        if (("LUCID".equals(selectedMode()) || "COMBINED".equals(selectedMode()))
                && getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt("lucid_practice_count", 0) < 10) {
            statusText.setText("Complete the 10-step lucid conditioning block first.");
            return;
        }
        String armGeneration = UUID.randomUUID().toString();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("pending_arm_generation", armGeneration)
                .remove("pending_session_id")
                .apply();
        statusText.setText("Ready. Finding the DreamSync watch...");
        Wearable.getCapabilityClient(this).getCapability(
                com.remote.DreamSync.shared.Protocol.WATCH_CAPABILITY,
                CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener(capability ->
                        onWatchCapability(capability, armGeneration))
                .addOnFailureListener(error -> failPendingArm(
                        armGeneration,
                        "Watch capability check failed: " + safeMessage(error)));
    }

    private void onWatchCapability(CapabilityInfo capability, String armGeneration) {
        if (!isCurrentArmGeneration(armGeneration)) return;
        Node watch = chooseNode(capability.getNodes());
        if (watch == null) {
            failPendingArm(armGeneration,
                    "DreamSync is not reachable on the paired watch. Open it there first.");
            return;
        }
        Wearable.getNodeClient(this).getLocalNode()
                .addOnSuccessListener(phone ->
                        createAndSendSession(phone, watch, armGeneration))
                .addOnFailureListener(error -> failPendingArm(
                        armGeneration,
                        "Phone identity check failed: " + safeMessage(error)));
    }

    private void createAndSendSession(
            Node phone,
            Node watch,
            String armGeneration
    ) {
        if (!isCurrentArmGeneration(armGeneration)) return;
        String sessionId = UUID.randomUUID().toString();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("pending_session_id", sessionId)
                .apply();
        String mode = selectedMode();
        float gain = cueGain();
        JSONObject config = buildConfig(
                sessionId, phone.getId(), watch.getId(), mode, gain);
        SessionEntity session = new SessionEntity();
        session.sessionId = sessionId;
        session.mode = mode;
        session.state = "ARMING";
        session.startUtcMs = System.currentTimeMillis();
        session.configurationJson = config.toString();
        session.calibrationRecord = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("last_calibration", "");
        session.protocolVersion = com.remote.DreamSync.shared.Protocol.VERSION;
        session.waveformVersion = com.remote.DreamSync.shared.CueWaveforms.VERSION;
        databaseExecutor.execute(() -> {
            try {
                dao.upsertSession(session);
                runOnUiThread(() -> sendPreparedSession(
                        phone, watch, sessionId, mode, gain, config, armGeneration));
            } catch (RuntimeException error) {
                runOnUiThread(() -> failPendingArm(
                        armGeneration,
                        "Could not save tonight's session: " + safeMessage(error)));
            }
        });
    }

    private void sendPreparedSession(
            Node phone,
            Node watch,
            String sessionId,
            String mode,
            float gain,
            JSONObject config,
            String armGeneration
    ) {
        if (!isCurrentArmGeneration(armGeneration)
                || !sessionId.equals(pref("pending_session_id"))) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("active_session_id", sessionId)
                .putString("local_phone_node", phone.getId())
                .putString("bound_watch_node", watch.getId())
                .putString("active_mode", mode)
                .remove("pending_arm_generation")
                .remove("pending_session_id")
                .apply();
        applyNotificationProtection();
        try {
            armAudioService(CuePlaybackService.ACTION_ARM, gain,
                    sessionId, phone.getId(), watch.getId());
        } catch (RuntimeException error) {
            cancelArming(sessionId, "Could not arm phone audio: " + safeMessage(error));
            return;
        }

        ProtocolEnvelope envelope = ProtocolEnvelope.create(
                com.remote.DreamSync.shared.Protocol.TYPE_CONFIG,
                sessionId, "", phone.getId(), watch.getId(),
                System.currentTimeMillis(),
                com.remote.DreamSync.shared.Protocol.CONTROL_TTL_MS,
                Map.of("config", config.toString(), "mode", mode));
        PutDataMapRequest map = PutDataMapRequest.create(
                com.remote.DreamSync.shared.Protocol.PATH_CONFIG_PREFIX + sessionId);
        map.getDataMap().putByteArray("envelope", envelope.encode());
        PutDataRequest request = map.asPutDataRequest();
        request.setUrgent();
        Wearable.getDataClient(this).putDataItem(request)
                .addOnSuccessListener(item -> {
                    statusText.setText("Settings sent. Waiting for the watch, then it will open automatically.");
                    updateReadiness();
                })
                .addOnFailureListener(error -> cancelArming(
                        sessionId, "Could not synchronize settings: " + safeMessage(error)));
    }

    private JSONObject buildConfig(
            String sessionId,
            String phoneNode,
            String watchNode,
            String mode,
            float gain
    ) {
        JSONObject json = new JSONObject();
        try {
            long createdUtcMs = System.currentTimeMillis();
            json.put("sessionId", sessionId);
            json.put("phoneNodeId", phoneNode);
            json.put("watchNodeId", watchNode);
            json.put("mode", mode);
            json.put("createdUtcMs", createdUtcMs);
            json.put("expiresUtcMs", createdUtcMs + 6L * 60L * 60L * 1000L);
            json.put("cueGain", gain);
            json.put("armTimeoutMin", 180);
            json.put("deepStartMin", 25);
            json.put("deepEndMin", 145);
            json.put("remStartMin", 255);
            json.put("sessionEndMin", 510);
            json.put("deepStableMin", 10);
            json.put("remStableMin", 5);
            json.put("deepCueIntervalSec", 30);
            json.put("deepCuesPerBlock", 6);
            json.put("maxDeepBlocks", 2);
            json.put("lucidCueIntervalSec", 180);
            json.put("maxLucidCues", 2);
            json.put("enableRawPpg", false);
            json.put("calibration", getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString("last_calibration", ""));
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return json;
    }

    private void stopSession() {
        String activeSessionId = pref("active_session_id");
        String sessionId = activeSessionId.isEmpty()
                ? pref("pending_session_id") : activeSessionId;
        String watchNode = pref("bound_watch_node");
        String phoneNode = pref("local_phone_node");
        if (!sessionId.isEmpty() && !watchNode.isEmpty()) {
            ProtocolEnvelope envelope = ProtocolEnvelope.create(
                    com.remote.DreamSync.shared.Protocol.TYPE_STOP,
                    sessionId, "", phoneNode, watchNode,
                    System.currentTimeMillis(),
                    com.remote.DreamSync.shared.Protocol.CONTROL_TTL_MS, Map.of());
            Wearable.getMessageClient(this).sendMessage(
                    watchNode, Protocol.PATH_STOP, envelope.encode());
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("cancelled_session_id", sessionId)
                .remove("active_session_id")
                .remove("pending_arm_generation")
                .remove("pending_session_id")
                .remove("auto_opened_watch_session")
                .apply();
        stopService(new Intent(this, CuePlaybackService.class));
        restoreNotificationProtection();
        databaseExecutor.execute(() -> {
            SessionEntity session = dao.session(sessionId);
            if (session != null) {
                session.state = "USER_STOPPED";
                session.endUtcMs = System.currentTimeMillis();
                dao.upsertSession(session);
            }
        });
        statusText.setText("Stop requested. Cues are disabled.");
    }

    private void cancelArming(String sessionId, String message) {
        runOnUiThread(() -> {
            if (!sessionId.equals(pref("active_session_id"))
                    && !sessionId.equals(pref("pending_session_id"))) return;
            sendStopToWatch(sessionId, pref("local_phone_node"), pref("bound_watch_node"));
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("cancelled_session_id", sessionId)
                    .remove("active_session_id")
                    .remove("pending_arm_generation")
                    .remove("pending_session_id")
                    .remove("auto_opened_watch_session")
                    .apply();
            stopService(new Intent(this, CuePlaybackService.class));
            restoreNotificationProtection();
            databaseExecutor.execute(() -> {
                SessionEntity session = dao.session(sessionId);
                if (session != null) {
                    session.state = "ARM_FAILED";
                    session.endUtcMs = System.currentTimeMillis();
                    dao.upsertSession(session);
                }
            });
            statusText.setText(message + " Audio is off. Tap Arm for tonight to retry.");
        });
    }

    private boolean isCurrentArmGeneration(String generation) {
        return generation != null
                && !generation.isEmpty()
                && generation.equals(pref("pending_arm_generation"));
    }

    private void failPendingArm(String generation, String message) {
        if (!isCurrentArmGeneration(generation)) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .remove("pending_arm_generation")
                .remove("pending_session_id")
                .apply();
        statusText.setText(message);
    }

    private void sendStopToWatch(String sessionId, String phoneNode, String watchNode) {
        if (sessionId.isEmpty() || phoneNode.isEmpty() || watchNode.isEmpty()) return;
        ProtocolEnvelope envelope = ProtocolEnvelope.create(
                com.remote.DreamSync.shared.Protocol.TYPE_STOP,
                sessionId, "", phoneNode, watchNode,
                System.currentTimeMillis(),
                com.remote.DreamSync.shared.Protocol.CONTROL_TTL_MS, Map.of());
        Wearable.getMessageClient(this).sendMessage(
                watchNode, Protocol.PATH_STOP, envelope.encode());
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

    private JSONObject buildExportJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", 2);
        root.put("generatedUtcMs", System.currentTimeMillis());
        root.put("productLanguage", "wellness sleep-cue tool; non-medical");
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
            item.put("watchCapabilities", new JSONObject(
                    session.watchCapabilityJson == null
                            || session.watchCapabilityJson.isEmpty()
                            ? "{}" : session.watchCapabilityJson));
            if (!session.configurationJson.isEmpty()) {
                JSONObject safeConfig = new JSONObject(session.configurationJson);
                safeConfig.remove("phoneNodeId");
                safeConfig.remove("watchNodeId");
                item.put("configuration", safeConfig);
            }
            if (!session.summaryJson.isEmpty()) item.put("summary", new JSONObject(session.summaryJson));
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
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new IllegalStateException("No output stream");
            output.write(pendingExportJson.getBytes(StandardCharsets.UTF_8));
            statusText.setText("Data export saved.");
        } catch (Exception error) {
            statusText.setText("Could not save export: " + safeMessage(error));
        }
    }

    private void confirmDeleteAll() {
        if (!pref("active_session_id").isEmpty()
                || !pref("pending_session_id").isEmpty()) {
            statusText.setText("Stop the current session before deleting phone data.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete phone data?")
                .setMessage("This removes phone session history, cue events, surveys, and received watch logs. Delete watch history separately with Delete watch data on the watch. It cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> databaseExecutor.execute(() -> {
                    dao.deleteAllSurveys();
                    dao.deleteAllEvents();
                    dao.deleteAllSessions();
                    deleteReceivedLogs();
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .remove("last_summary").remove("last_summary_session").apply();
                    runOnUiThread(() -> statusText.setText(
                            "All phone session data deleted. Delete watch data on the watch if needed."));
                }))
                .setNegativeButton("Cancel", null)
                .show();
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
        readinessText.setText("Readiness: speaker " + (audio.success ? "ready" : audio.reason)
                + " | calibration " + (calibration.isEmpty() ? "needed" : "saved")
                + " | lucid practice " + practice + "/10"
                + " | watch " + compatibility);
    }

    private void showOnboardingOnce() {
        if (getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("onboarding_seen", false)) return;
        new AlertDialog.Builder(this)
                .setTitle("Welcome to DreamSync")
                .setMessage("Dreams Redefined. DreamSync's mission is to protect sleep continuity. It estimates likely sleep from wearable signals, stays quiet when confidence is low, and uses gentle research-shaped cues only when strict safety checks indicate a low risk of disturbance. "
                        + "Tap Arm for tonight once. DreamSync opens the watch, waits for sustained sleep signals, estimates sleep onset, then activates conservative cue timing automatically. "
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
                                    statusText.setText("Allow DreamSync notification access, then Arm for tonight.");
                                    startActivity(new Intent(
                                            Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                                } else {
                                    statusText.setText("Notification protection will turn on only during an armed night.");
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
            live = "No live sensor check yet. Arm a night with the watch open to run it.";
        } else {
            try {
                JSONObject values = new JSONObject(json);
                String status = values.optString("status", "BASIC_MONITORING_ONLY")
                        .replace('_', ' ');
                live = values.optString("manufacturer", "") + " "
                        + values.optString("model", "") + "\n"
                        + status + "\n" + values.optString("reason", "");
            } catch (JSONException error) {
                live = "Live compatibility data could not be read. Run a new armed test.";
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Watch compatibility")
                .setMessage(live + "\n\nValidation order:\n"
                        + "1. Galaxy Watch Ultra 2024 and 2025\n"
                        + "2. Galaxy Watch8, Watch8 Classic, and Watch7\n"
                        + "3. Watch4, Watch5, and Watch6 only when the live check passes\n\n"
                        + "Basic monitoring uses standard Wear OS motion and heart rate. Full support requires Samsung IBI, skin temperature, off-wrist checks, and a passed live self-test. Apple Watch, Pixel Watch, Fitbit, and Garmin are not supported in this release.")
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
                        + "First validation targets Galaxy Watch Ultra 2024 and 2025. Galaxy Watch8, Watch8 Classic, and Watch7 are next. Watch4, Watch5, and Watch6 are conditional: the in-app motion, heart-rate, software, permission, and Samsung-signal check must pass. A watch can show Fully supported, Basic monitoring only, or Not supported. Apple Watch, Pixel Watch, Fitbit, Garmin, Galaxy Fit, and older Tizen watches are not supported in this release.<br><br>"
                        + "<b>What DreamSync does</b><br>"
                        + "1. Tap <b>Arm for tonight</b> once. DreamSync opens the watch and waits quietly for sustained sleep signals.<br>"
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
        String summary = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("last_summary", "");
        if (!summary.isEmpty()) showFriendlySummary(summary);
        updateReadiness();
    }

    private void openWatchApp(boolean autoArm, String sessionId) {
        String targetNodeId = pref("bound_watch_node");
        if (targetNodeId.isEmpty()) {
            statusText.setText("No watch is bound yet. Tap Arm for tonight first.");
            return;
        }
        Intent remoteIntent = new Intent(Intent.ACTION_VIEW)
                .setData(autoArm
                        ? Uri.parse("dreamsync://watch/arm").buildUpon()
                        .appendQueryParameter("session", sessionId).build()
                        : Uri.parse("dreamsync://watch/start"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            ListenableFuture<Void> launch = new RemoteActivityHelper(this, remoteExecutor)
                    .startRemoteActivity(remoteIntent, targetNodeId);
            launch.addListener(() -> {
                try {
                    launch.get();
                    if (autoArm && !sessionId.equals(pref("active_session_id"))) {
                        runOnUiThread(() -> statusText.setText(
                                "Arm was cancelled. The watch will not start this session."));
                        return;
                    }
                    if (autoArm) {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putString("auto_opened_watch_session", sessionId)
                                .apply();
                    }
                    runOnUiThread(() -> statusText.setText(autoArm
                            ? "Watch opened. Approve any first-use prompts; sleep estimation then runs automatically."
                            : "Watch opened."));
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    if (autoArm) {
                        cancelArming(sessionId,
                                "Could not open the bound watch: " + safeMessage(cause));
                    } else {
                        runOnUiThread(() -> statusText.setText(
                                "Could not open the bound watch: " + safeMessage(cause)));
                    }
                }
            }, remoteExecutor);
        } catch (RuntimeException error) {
            if (autoArm) {
                cancelArming(sessionId, "Remote launch failed: " + safeMessage(error));
            } else {
                statusText.setText("Remote launch failed: " + safeMessage(error));
            }
        }
    }

    private Node chooseNode(Set<Node> nodes) {
        if (nodes == null || nodes.isEmpty()) return null;
        for (Node node : nodes) if (node.isNearby()) return node;
        return nodes.iterator().next();
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
