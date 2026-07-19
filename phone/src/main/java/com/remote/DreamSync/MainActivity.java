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
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.wear.remote.interactions.RemoteActivityHelper;

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.remote.DreamSync.shared.ExperimentCondition;
import com.remote.DreamSync.shared.ProtocolEnvelope;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 101;
    private static final int REQUEST_EXPORT = 602;
    private static final int REQUEST_INSTALL_LOG_EXPORT = 603;

    private TextView statusText;
    private TextView readinessText;
    private TextView gainLabel;
    private RadioGroup modeGroup;
    private SeekBar gainSeek;
    private CheckBox dryRunCheck;
    private CheckBox blindTrialCheck;
    private EditText watchAddressInput;
    private Button deployWatchButton;
    private ExecutorService remoteExecutor;
    private ExecutorService databaseExecutor;
    private PhoneDao dao;
    private boolean summaryReceiverRegistered;
    private String pendingExportJson = "";
    private String pendingInstallLog = "";

    private final BroadcastReceiver summaryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (PhoneDataListenerService.ACTION_STATUS_CHANGED.equals(intent.getAction())) {
                String status = intent.getStringExtra("status");
                if (status != null) statusText.setText(status);
                updateReadiness();
                return;
            }
            String summary = intent.getStringExtra("summary");
            String sessionId = intent.getStringExtra("sessionId");
            if (summary != null && !summary.isEmpty()) {
                showFriendlySummary(summary, false);
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
        dryRunCheck = findViewById(R.id.dryRunCheck);
        blindTrialCheck = findViewById(R.id.blindTrialCheck);
        watchAddressInput = findViewById(R.id.watchAddressInput);
        deployWatchButton = findViewById(R.id.deployWatchButton);
        watchAddressInput.setText(getSharedPreferences("somnicue_phone", MODE_PRIVATE)
                .getString("watch_adb_address", ""));
    }

    private void wireActions() {
        deployWatchButton.setOnClickListener(v -> deployWatchApp());
        findViewById(R.id.exportInstallLogButton).setOnClickListener(v -> exportInstallLog());
        findViewById(R.id.watchHelpButton).setOnClickListener(v -> showWatchHelp());
        gainSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                gainLabel.setText("Cue gain: " + progress + "% (phone media volume must stay low)");
                updateReadiness();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        findViewById(R.id.testDeepButton).setOnClickListener(v -> testCue("DEEP"));
        findViewById(R.id.testLucidButton).setOnClickListener(v -> runLucidPractice());
        findViewById(R.id.openWatchButton).setOnClickListener(v -> openWatchApp());
        findViewById(R.id.armButton).setOnClickListener(v -> startTransactionalSession());
        findViewById(R.id.stopButton).setOnClickListener(v -> stopSession());
        findViewById(R.id.morningButton).setOnClickListener(v -> {
            String sessionId = getSharedPreferences("somnicue_phone", MODE_PRIVATE)
                    .getString("last_summary_session", "");
            if (sessionId.isEmpty()) statusText.setText("No completed session is ready for a survey.");
            else showMorningSurvey(sessionId);
        });
        findViewById(R.id.historyButton).setOnClickListener(v -> showHistory());
        findViewById(R.id.exportButton).setOnClickListener(v -> prepareExport());
        findViewById(R.id.deleteDataButton).setOnClickListener(v -> confirmDeleteAll());
    }

    private void testCue(String type) {
        AudioOutput.Result preflight = new AudioOutput(this).preflight();
        if (!preflight.success) {
            statusText.setText("Cannot test cue: " + preflight.reason + ".");
            return;
        }
        String action = "LUCID".equals(type)
                ? CuePlaybackService.ACTION_TEST_LUCID : CuePlaybackService.ACTION_TEST_DEEP;
        armAudioService(action, cueGain(), "", "", "");
        statusText.setText("Cue played on the phone speaker. Set it barely audible, then one step lower.");
        readinessText.postDelayed(this::updateReadiness, 1_500L);
    }

    private void runLucidPractice() {
        int count = getSharedPreferences("somnicue_phone", MODE_PRIVATE)
                .getInt("lucid_practice_count", 0);
        new AlertDialog.Builder(this)
                .setTitle("Lucid cue conditioning")
                .setMessage("Current block: " + count + "/10\n\n"
                        + "Play the exact chime. Ask: Am I dreaming? Check text or a clock twice. "
                        + "Picture recognizing the chime inside a dream.")
                .setPositiveButton(count >= 10 ? "Practice again" : "Play + record", (dialog, which) -> {
                    testCue("LUCID");
                    int next = Math.min(10, getSharedPreferences("somnicue_phone", MODE_PRIVATE)
                            .getInt("lucid_practice_count", 0) + 1);
                    getSharedPreferences("somnicue_phone", MODE_PRIVATE).edit()
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
        AudioOutput audio = new AudioOutput(this);
        AudioOutput.Result preflight = audio.preflight();
        if (!"SENSOR_ONLY".equals(selectedMode()) && !preflight.success) {
            statusText.setText("Not ready: " + preflight.reason + ".");
            return;
        }
        String calibration = getSharedPreferences("somnicue_phone", MODE_PRIVATE)
                .getString("last_calibration", "");
        if (!"SENSOR_ONLY".equals(selectedMode()) && calibration.isEmpty()) {
            statusText.setText("Test a cue at the final volume before arming.");
            return;
        }
        if (("LUCID".equals(selectedMode()) || "COMBINED".equals(selectedMode()))
                && getSharedPreferences("somnicue_phone", MODE_PRIVATE)
                .getInt("lucid_practice_count", 0) < 10) {
            statusText.setText("Complete the 10-step lucid conditioning block first.");
            return;
        }
        statusText.setText("Preflight passed. Finding the Dream Sync watch...");
        Wearable.getCapabilityClient(this).getCapability(
                com.remote.DreamSync.shared.Protocol.WATCH_CAPABILITY,
                CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener(this::onWatchCapability)
                .addOnFailureListener(error -> statusText.setText(
                        "Watch capability check failed: " + safeMessage(error)));
    }

    private void onWatchCapability(CapabilityInfo capability) {
        Node watch = chooseNode(capability.getNodes());
        if (watch == null) {
            statusText.setText("Dream Sync watch app is not reachable. Open it on the paired watch.");
            return;
        }
        Wearable.getNodeClient(this).getLocalNode()
                .addOnSuccessListener(phone -> createAndSendSession(phone, watch))
                .addOnFailureListener(error -> statusText.setText(
                        "Phone identity check failed: " + safeMessage(error)));
    }

    private void createAndSendSession(Node phone, Node watch) {
        String sessionId = UUID.randomUUID().toString();
        ExperimentCondition condition;
        if ("SENSOR_ONLY".equals(selectedMode())) condition = ExperimentCondition.SHAM;
        else if (blindTrialCheck.isChecked()) {
            condition = new SecureRandom().nextBoolean()
                    ? ExperimentCondition.ACTIVE : ExperimentCondition.SHAM;
        } else condition = ExperimentCondition.ACTIVE;

        JSONObject config = buildConfig(sessionId, phone.getId(), watch.getId(), condition.name());
        SessionEntity session = new SessionEntity();
        session.sessionId = sessionId;
        session.condition = condition.name();
        session.mode = selectedMode();
        session.state = "ARMING";
        session.startUtcMs = System.currentTimeMillis();
        session.configurationJson = config.toString();
        session.calibrationRecord = getSharedPreferences("somnicue_phone", MODE_PRIVATE)
                .getString("last_calibration", "");
        session.protocolVersion = com.remote.DreamSync.shared.Protocol.VERSION;
        session.waveformVersion = com.remote.DreamSync.shared.CueWaveforms.VERSION;
        databaseExecutor.execute(() -> dao.upsertSession(session));

        getSharedPreferences("somnicue_phone", MODE_PRIVATE).edit()
                .putString("active_session_id", sessionId)
                .putString("local_phone_node", phone.getId())
                .putString("bound_watch_node", watch.getId())
                .putString("active_condition", condition.name())
                .putString("active_mode", selectedMode())
                .apply();
        armAudioService(CuePlaybackService.ACTION_ARM, cueGain(),
                sessionId, phone.getId(), watch.getId());

        ProtocolEnvelope envelope = ProtocolEnvelope.create(
                com.remote.DreamSync.shared.Protocol.TYPE_CONFIG,
                sessionId, "", phone.getId(), watch.getId(),
                System.currentTimeMillis(),
                com.remote.DreamSync.shared.Protocol.CONTROL_TTL_MS,
                Map.of("config", config.toString(),
                        "condition", condition.name(),
                        "mode", selectedMode()));
        PutDataMapRequest map = PutDataMapRequest.create(
                com.remote.DreamSync.shared.Protocol.PATH_CONFIG_PREFIX + sessionId);
        map.getDataMap().putByteArray("envelope", envelope.encode());
        PutDataRequest request = map.asPutDataRequest();
        request.setUrgent();
        Wearable.getDataClient(this).putDataItem(request)
                .addOnSuccessListener(item -> {
                    statusText.setText("Settings synchronized. Waiting for the watch to acknowledge this session.");
                    updateReadiness();
                })
                .addOnFailureListener(error -> statusText.setText(
                        "Could not synchronize settings: " + safeMessage(error)));
    }

    private JSONObject buildConfig(
            String sessionId, String phoneNode, String watchNode, String condition
    ) {
        JSONObject json = new JSONObject();
        try {
            boolean dryRun = dryRunCheck.isChecked();
            json.put("sessionId", sessionId);
            json.put("phoneNodeId", phoneNode);
            json.put("watchNodeId", watchNode);
            json.put("condition", condition);
            json.put("mode", selectedMode());
            json.put("cueGain", cueGain());
            json.put("deepStartMin", dryRun ? 1 : 25);
            json.put("deepEndMin", dryRun ? 3 : 180);
            json.put("remStartMin", dryRun ? 4 : 255);
            json.put("sessionEndMin", dryRun ? 8 : 510);
            json.put("deepStableMin", dryRun ? 1 : 8);
            json.put("remStableMin", dryRun ? 1 : 3);
            json.put("deepCueIntervalSec", 15);
            json.put("deepCuesPerBlock", dryRun ? 2 : 8);
            json.put("maxDeepBlocks", dryRun ? 1 : 2);
            json.put("lucidCueIntervalSec", dryRun ? 60 : 90);
            json.put("maxLucidCues", dryRun ? 1 : 3);
            json.put("dryRun", dryRun);
            json.put("calibration", getSharedPreferences("somnicue_phone", MODE_PRIVATE)
                    .getString("last_calibration", ""));
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return json;
    }

    private void stopSession() {
        String sessionId = pref("active_session_id");
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
        stopService(new Intent(this, CuePlaybackService.class));
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

    private void showFriendlySummary(String summaryText, boolean revealCondition) {
        try {
            JSONObject summary = new JSONObject(summaryText);
            String line = "Session " + summary.optLong("minutes", 0) + " min"
                    + " | Deep " + summary.optInt("deepCues", 0)
                    + " | Lucid " + summary.optInt("lucidCues", 0)
                    + " | Cue-linked arousals " + summary.optInt("cueLinkedArousals", 0)
                    + " | Signal " + summary.optString("signalQuality", "unknown")
                    + " | End " + summary.optString("terminalState", "complete");
            if (revealCondition) line += " | Condition " + summary.optString("condition", "unknown");
            statusText.setText(line);
        } catch (JSONException error) {
            statusText.setText("Session summary received. Complete the morning survey to reveal the condition.");
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
        EditText awareness = field(form, "Cue noticed: no / unsure / yes", false);
        EditText recall = field(form, "Dream recall 0-3", true);
        EditText lucid = field(form, "Lucid dream: no / unsure / yes", false);
        EditText adverse = field(form, "Headache, nightmare, anxiety, sleepiness, or none", false);
        EditText notes = field(form, "Dream journal and notes", false);

        new AlertDialog.Builder(this)
                .setTitle("Morning survey")
                .setMessage("Answer before the active/sham condition is shown.")
                .setView(form)
                .setPositiveButton("Save + reveal", (dialog, which) -> {
                    MorningSurveyEntity survey = new MorningSurveyEntity();
                    survey.sessionId = sessionId;
                    survey.sleepQuality = boundedInt(sleep.getText().toString(), 1, 10, 0);
                    survey.restedness = boundedInt(rested.getText().toString(), 1, 10, 0);
                    survey.rememberedAwakenings = boundedInt(
                            awakenings.getText().toString(), 0, 100, 0);
                    survey.cueAwareness = choice(awareness.getText().toString());
                    survey.dreamRecall = boundedInt(recall.getText().toString(), 0, 3, 0);
                    survey.lucidDream = choice(lucid.getText().toString());
                    survey.adverseEffects = adverse.getText().toString().trim();
                    survey.dreamNotes = notes.getText().toString().trim();
                    survey.submittedUtcMs = System.currentTimeMillis();
                    databaseExecutor.execute(() -> {
                        dao.upsertSurvey(survey);
                        SessionEntity session = dao.session(sessionId);
                        if (session != null) {
                            session.conditionRevealed = true;
                            dao.upsertSession(session);
                            runOnUiThread(() -> {
                                String summary = session.summaryJson;
                                showFriendlySummary(summary, true);
                                new AlertDialog.Builder(this)
                                        .setTitle("Condition revealed")
                                        .setMessage(session.condition + "\n\nThis is one observation, not proof of benefit.")
                                        .setPositiveButton("Done", null)
                                        .show();
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

    private void showHistory() {
        databaseExecutor.execute(() -> {
            List<SessionEntity> sessions = dao.sessions();
            StringBuilder text = new StringBuilder();
            double activeSleep = 0, shamSleep = 0;
            int activeN = 0, shamN = 0;
            for (SessionEntity session : sessions) {
                MorningSurveyEntity survey = dao.survey(session.sessionId);
                String condition = session.conditionRevealed ? session.condition : "HIDDEN";
                text.append(condition).append(" | ").append(session.mode)
                        .append(" | ").append(session.state);
                if (survey != null) {
                    text.append(" | sleep ").append(survey.sleepQuality)
                            .append(" | rested ").append(survey.restedness);
                    if (session.conditionRevealed && "ACTIVE".equals(session.condition)) {
                        activeSleep += survey.sleepQuality; activeN++;
                    } else if (session.conditionRevealed) {
                        shamSleep += survey.sleepQuality; shamN++;
                    }
                }
                text.append('\n');
            }
            if (activeN > 0 || shamN > 0) {
                text.append("\nExploratory mean sleep quality: ")
                        .append(activeN > 0 ? String.format("active %.1f", activeSleep / activeN) : "active --")
                        .append("; ")
                        .append(shamN > 0 ? String.format("sham %.1f", shamSleep / shamN) : "sham --");
            }
            String result = text.length() == 0 ? "No saved sessions." : text.toString();
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("Research history")
                    .setMessage(result)
                    .setPositiveButton("Done", null)
                    .show());
        });
    }

    private void prepareExport() {
        statusText.setText("Preparing de-identified research export...");
        databaseExecutor.execute(() -> {
            try {
                pendingExportJson = buildExportJson().toString(2);
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, "dreamsync-research-export.json");
                runOnUiThread(() -> startActivityForResult(intent, REQUEST_EXPORT));
            } catch (JSONException error) {
                runOnUiThread(() -> statusText.setText("Export failed: " + safeMessage(error)));
            }
        });
    }

    private void exportInstallLog() {
        pendingInstallLog = WatchDeployer.exportLog(this);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, "dreamsync-watch-install-error-log.txt");
        startActivityForResult(intent, REQUEST_INSTALL_LOG_EXPORT);
    }

    private JSONObject buildExportJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", 1);
        root.put("generatedUtcMs", System.currentTimeMillis());
        root.put("productLanguage", "experimental stage-gated sleep-cue research; non-medical");
        JSONArray sessionArray = new JSONArray();
        for (SessionEntity session : dao.sessions()) {
            JSONObject item = new JSONObject();
            item.put("sessionId", session.sessionId);
            item.put("condition", session.conditionRevealed ? session.condition : "HIDDEN_PENDING_SURVEY");
            item.put("mode", session.mode);
            item.put("state", session.state);
            item.put("startUtcMs", session.startUtcMs);
            item.put("endUtcMs", session.endUtcMs);
            item.put("protocolVersion", session.protocolVersion);
            item.put("waveformVersion", session.waveformVersion);
            item.put("calibration", session.calibrationRecord);
            if (!session.configurationJson.isEmpty()) {
                JSONObject safeConfig = new JSONObject(session.configurationJson);
                safeConfig.remove("phoneNodeId");
                safeConfig.remove("watchNodeId");
                if (!session.conditionRevealed) {
                    safeConfig.put("condition", "HIDDEN_PENDING_SURVEY");
                }
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
                .put("cueAwareness", survey.cueAwareness)
                .put("dreamRecall", survey.dreamRecall)
                .put("lucidDream", survey.lucidDream)
                .put("adverseEffects", survey.adverseEffects)
                .put("dreamNotes", survey.dreamNotes)
                .put("submittedUtcMs", survey.submittedUtcMs);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != REQUEST_EXPORT && requestCode != REQUEST_INSTALL_LOG_EXPORT)
                || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new IllegalStateException("No output stream");
            String content = requestCode == REQUEST_INSTALL_LOG_EXPORT
                    ? pendingInstallLog : pendingExportJson;
            output.write(content.getBytes(StandardCharsets.UTF_8));
            statusText.setText(requestCode == REQUEST_INSTALL_LOG_EXPORT
                    ? "Watch install error log saved." : "Research export saved.");
        } catch (Exception error) {
            statusText.setText("Could not save export: " + safeMessage(error));
        }
    }

    private void confirmDeleteAll() {
        new AlertDialog.Builder(this)
                .setTitle("Delete all local research data?")
                .setMessage("This removes session history, cue events, surveys, and received watch logs. It cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> databaseExecutor.execute(() -> {
                    dao.deleteAllSurveys();
                    dao.deleteAllEvents();
                    dao.deleteAllSessions();
                    deleteReceivedLogs();
                    getSharedPreferences("somnicue_phone", MODE_PRIVATE).edit()
                            .remove("last_summary").remove("last_summary_session").apply();
                    runOnUiThread(() -> statusText.setText("All local research data deleted."));
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
        String calibration = getSharedPreferences("somnicue_phone", MODE_PRIVATE)
                .getString("last_calibration", "");
        int practice = getSharedPreferences("somnicue_phone", MODE_PRIVATE)
                .getInt("lucid_practice_count", 0);
        readinessText.setText("Readiness: speaker " + (audio.success ? "ready" : audio.reason)
                + " | calibration " + (calibration.isEmpty() ? "needed" : "saved")
                + " | lucid practice " + practice + "/10"
                + " | watch " + (pref("bound_watch_node").isEmpty() ? "not bound" : "bound"));
    }

    private void showOnboardingOnce() {
        if (getSharedPreferences("somnicue_phone", MODE_PRIVATE)
                .getBoolean("onboarding_seen", false)) return;
        new AlertDialog.Builder(this)
                .setTitle("Dream Sync research prototype")
                .setMessage("Dream Sync estimates cue-eligible windows from wrist motion and heart rate. "
                        + "It does not measure EEG, diagnose sleep, guarantee deeper sleep, induce lucid dreams, "
                        + "or provide medical care. Stop if cues disturb sleep or cause adverse effects. Data stays local unless you export it.")
                .setPositiveButton("I understand", (dialog, which) ->
                        getSharedPreferences("somnicue_phone", MODE_PRIVATE).edit()
                                .putBoolean("onboarding_seen", true).apply())
                .setCancelable(false)
                .show();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(PhoneDataListenerService.ACTION_SUMMARY_CHANGED);
        filter.addAction(PhoneDataListenerService.ACTION_STATUS_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(summaryReceiver, filter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(summaryReceiver, filter);
        summaryReceiverRegistered = true;
    }

    @Override protected void onResume() {
        super.onResume();
        String summary = getSharedPreferences("somnicue_phone", MODE_PRIVATE)
                .getString("last_summary", "");
        if (!summary.isEmpty()) showFriendlySummary(summary, false);
        updateReadiness();
    }

    private void deployWatchApp() {
        String address = watchAddressInput.getText().toString().trim();
        if (address.isEmpty()) {
            statusText.setText("Enter the watch address shown under Debug over Wi-Fi first.");
            return;
        }
        String host = address;
        int port = 5555;
        int colon = address.lastIndexOf(':');
        if (colon > 0) {
            host = address.substring(0, colon);
            try { port = Integer.parseInt(address.substring(colon + 1).trim()); }
            catch (NumberFormatException invalid) {
                statusText.setText("Use the exact IP:port shown by the watch.");
                return;
            }
        }
        if (!host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            statusText.setText("Enter the watch IP exactly, such as 192.168.1.42:5555.");
            return;
        }
        getSharedPreferences("somnicue_phone", MODE_PRIVATE).edit()
                .putString("watch_adb_address", address).apply();
        deployWatchButton.setEnabled(false);
        deployWatchButton.setText("Installing...");
        final String finalHost = host;
        final int finalPort = port;
        remoteExecutor.execute(() -> {
            WatchDeployer.deploy(this, finalHost, finalPort,
                    message -> runOnUiThread(() -> {
                        statusText.setText(message);
                        if (message.startsWith("Step 1/")) deployWatchButton.setText("Connecting...");
                        else if (message.startsWith("Step 2/")) deployWatchButton.setText("Uploading...");
                        else if (message.startsWith("Step 3/")) deployWatchButton.setText("Installing on watch...");
                        else if (message.startsWith("Step 4/")) deployWatchButton.setText("Installed");
                    }));
            runOnUiThread(() -> {
                deployWatchButton.setText("Install watch app over Wi-Fi");
                deployWatchButton.setEnabled(true);
            });
        });
    }

    private void showWatchHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Install the watch app")
                .setMessage("Internal capstone build only.\n\n"
                        + "1. On watch: unlock Developer options.\n"
                        + "2. Enable ADB debugging and Debug over Wi-Fi.\n"
                        + "3. Put phone and watch on the same Wi-Fi.\n"
                        + "4. Enter the shown IP:port and accept the watch prompt.\n\n"
                        + "This installer is not intended for a public release.")
                .setPositiveButton("Done", null)
                .show();
    }

    private void openWatchApp() {
        Intent remoteIntent = new Intent(Intent.ACTION_VIEW)
                .setData(Uri.parse("somnicue://watch/start"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            new RemoteActivityHelper(this, remoteExecutor).startRemoteActivity(remoteIntent, null);
            statusText.setText("Watch launch requested.");
        } catch (RuntimeException error) {
            statusText.setText("Remote launch failed: " + safeMessage(error));
        }
    }

    private Node chooseNode(Set<Node> nodes) {
        if (nodes == null || nodes.isEmpty()) return null;
        for (Node node : nodes) if (node.isNearby()) return node;
        return nodes.iterator().next();
    }

    private String selectedMode() {
        int checked = modeGroup.getCheckedRadioButtonId();
        if (checked == R.id.modeSensor) return "SENSOR_ONLY";
        if (checked == R.id.modeLucid) return "LUCID";
        if (checked == R.id.modeCombined) return "COMBINED";
        return "DEEP";
    }

    private float cueGain() {
        return Math.max(0.01f, Math.min(0.20f, gainSeek.getProgress() / 100f));
    }

    private String pref(String key) {
        return getSharedPreferences("somnicue_phone", MODE_PRIVATE).getString(key, "");
    }

    private int boundedInt(String value, int min, int max, int fallback) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(value.trim()))); }
        catch (Exception ignored) { return fallback; }
    }

    private String choice(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
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
