package com.nathanw.somnicue;

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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.wear.remote.interactions.RemoteActivityHelper;

import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 101;

    private TextView statusText;
    private TextView gainLabel;
    private RadioGroup modeGroup;
    private SeekBar gainSeek;
    private CheckBox dryRunCheck;
    private EditText watchAddressInput;
    private Button deployWatchButton;
    private ExecutorService remoteExecutor;
    private boolean summaryReceiverRegistered;

    private final BroadcastReceiver summaryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String summary = intent.getStringExtra("summary");
            if (summary != null && !summary.isEmpty()) {
                statusText.setText("Last watch summary: " + summary);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        gainLabel = findViewById(R.id.gainLabel);
        modeGroup = findViewById(R.id.modeGroup);
        gainSeek = findViewById(R.id.gainSeek);
        dryRunCheck = findViewById(R.id.dryRunCheck);
        Button testDeepButton = findViewById(R.id.testDeepButton);
        Button testLucidButton = findViewById(R.id.testLucidButton);
        Button openWatchButton = findViewById(R.id.openWatchButton);
        Button armButton = findViewById(R.id.armButton);
        Button stopButton = findViewById(R.id.stopButton);
        watchAddressInput = findViewById(R.id.watchAddressInput);
        deployWatchButton = findViewById(R.id.deployWatchButton);

        remoteExecutor = Executors.newSingleThreadExecutor();
        requestNotificationPermissionIfNeeded();

        watchAddressInput.setText(getSharedPreferences("somnicue_phone", MODE_PRIVATE)
                .getString("watch_adb_address", ""));
        deployWatchButton.setOnClickListener(v -> deployWatchApp());
        findViewById(R.id.watchHelpButton).setOnClickListener(v -> showWatchHelp());

        gainSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                gainLabel.setText("Cue gain: " + progress
                        + "% (also set phone media volume low)");
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        testDeepButton.setOnClickListener(v -> {
            armAudioService(CuePlaybackService.ACTION_TEST_DEEP, cueGain());
            statusText.setText("Played the deep cue. Make it barely audible, then reduce one step.");
        });

        testLucidButton.setOnClickListener(v -> {
            armAudioService(CuePlaybackService.ACTION_TEST_LUCID, cueGain());
            statusText.setText("Lucid cue played. Pair this exact chime with a reality check 10 times.");
        });

        openWatchButton.setOnClickListener(v -> openWatchApp());

        armButton.setOnClickListener(v -> {
            armAudioService(CuePlaybackService.ACTION_ARM, cueGain());
            sendConfiguration();
        });

        stopButton.setOnClickListener(v -> {
            sendToAllNodes(Protocol.PATH_STOP, new byte[0], "Stop command sent to watch.");
            stopService(new Intent(this, CuePlaybackService.class));
            statusText.setText("Phone audio disarmed; watch stop requested.");
        });
    }


    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(PhoneDataListenerService.ACTION_SUMMARY_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(summaryReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(summaryReceiver, filter);
        }
        summaryReceiverRegistered = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        String summary = getSharedPreferences("somnicue_phone", MODE_PRIVATE)
                .getString("last_summary", "");
        if (!summary.isEmpty()) {
            statusText.setText("Last watch summary: " + summary);
        }
    }

    private void armAudioService(String action, float gain) {
        Intent intent = new Intent(this, CuePlaybackService.class)
                .setAction(action)
                .putExtra(CuePlaybackService.EXTRA_GAIN, gain);
        startForegroundService(intent);
    }

    private void sendConfiguration() {
        JSONObject json = new JSONObject();
        try {
            boolean dryRun = dryRunCheck.isChecked();
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
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }

        sendToAllNodes(
                Protocol.PATH_CONFIG,
                json.toString().getBytes(StandardCharsets.UTF_8),
                "Settings sent. Open the watch app and tap Start night session."
        );
    }

    private void sendToAllNodes(String path, byte[] data, String successMessage) {
        Wearable.getNodeClient(this).getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    if (nodes.isEmpty()) {
                        statusText.setText("No connected Wear OS watch found. Check Bluetooth/Wi-Fi pairing.");
                        return;
                    }
                    for (Node node : nodes) {
                        Wearable.getMessageClient(this)
                                .sendMessage(node.getId(), path, data)
                                .addOnFailureListener(error -> statusText.setText(
                                        "Watch message failed: " + error.getMessage()));
                    }
                    statusText.setText(successMessage);
                })
                .addOnFailureListener(error -> statusText.setText(
                        "Could not query connected watches: " + error.getMessage()));
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
            try {
                port = Integer.parseInt(address.substring(colon + 1).trim());
            } catch (NumberFormatException invalid) {
                statusText.setText("That address has an odd port. Use the exact IP:port the watch shows, e.g. 192.168.1.42:5555.");
                return;
            }
        }
        if (!host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            statusText.setText("That doesn't look like the watch's IP. Enter it exactly as shown under Debug over Wi-Fi, e.g. 192.168.1.42:5555.");
            return;
        }
        getSharedPreferences("somnicue_phone", MODE_PRIVATE)
                .edit()
                .putString("watch_adb_address", address)
                .apply();

        deployWatchButton.setEnabled(false);
        deployWatchButton.setText("Installing…");
        statusText.setText("Starting watch deploy…");
        final String finalHost = host;
        final int finalPort = port;
        remoteExecutor.execute(() -> {
            WatchDeployer.deploy(this, finalHost, finalPort,
                    message -> runOnUiThread(() -> statusText.setText(message)));
            runOnUiThread(() -> {
                deployWatchButton.setText("Install watch app over Wi-Fi");
                deployWatchButton.setEnabled(true);
            });
        });
    }

    private void showWatchHelp() {
        String steps =
                "One-time setup, then it's a single tap.\n\n"
                + "ON THE WATCH\n"
                + "1. Settings ▸ System ▸ About\n"
                + "2. Tap Build number 7 times (unlocks Developer options)\n"
                + "3. Settings ▸ Developer options\n"
                + "4. Turn on ADB debugging\n"
                + "5. Turn on Debug over Wi-Fi — it shows an address like 192.168.1.42:5555\n\n"
                + "ON THIS PHONE\n"
                + "6. Make sure the phone is on the SAME Wi-Fi as the watch\n"
                + "7. Type that IP:port into the box, tap Install\n"
                + "8. On the watch, accept the debugging prompt (tick \"Always allow\")\n\n"
                + "Trouble?\n"
                + "• Times out: the two devices aren't on the same network, or the router blocks device-to-device traffic (guest Wi-Fi often does).\n"
                + "• Nothing shown under Debug over Wi-Fi: some watches (e.g. Pixel Watch) only support pairing-code debugging and need a PC once.";
        new AlertDialog.Builder(this)
                .setTitle("Install the watch app")
                .setMessage(steps)
                .setPositiveButton("Got it", null)
                .show();
    }

    private void openWatchApp() {
        Intent remoteIntent = new Intent(Intent.ACTION_VIEW)
                .setData(Uri.parse("somnicue://watch/start"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            RemoteActivityHelper helper = new RemoteActivityHelper(this, remoteExecutor);
            helper.startRemoteActivity(remoteIntent, null);
            statusText.setText("Watch launch requested. Unlock the watch if needed.");
        } catch (RuntimeException error) {
            statusText.setText("Remote launch failed: " + error.getMessage());
        }
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

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS
            );
        }
    }


    @Override
    protected void onStop() {
        if (summaryReceiverRegistered) {
            unregisterReceiver(summaryReceiver);
            summaryReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (remoteExecutor != null) remoteExecutor.shutdownNow();
        super.onDestroy();
    }
}
