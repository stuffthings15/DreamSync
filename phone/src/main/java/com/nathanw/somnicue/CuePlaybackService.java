package com.nathanw.somnicue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.IBinder;

import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CuePlaybackService extends Service
        implements MessageClient.OnMessageReceivedListener {

    static final String ACTION_ARM = "com.nathanw.somnicue.ARM";
    static final String ACTION_TEST_DEEP = "com.nathanw.somnicue.TEST_DEEP";
    static final String ACTION_TEST_LUCID = "com.nathanw.somnicue.TEST_LUCID";
    static final String EXTRA_GAIN = "gain";

    private static final String CHANNEL_ID = "somnicue_audio";
    private static final int NOTIFICATION_ID = 2001;
    private static final int SAMPLE_RATE = 44_100;

    private ExecutorService playbackExecutor;
    private volatile float armedGain = 0.04f;

    @Override
    public void onCreate() {
        super.onCreate();
        playbackExecutor = Executors.newSingleThreadExecutor();
        Wearable.getMessageClient(this).addListener(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        if (intent != null) {
            armedGain = clampGain(intent.getFloatExtra(EXTRA_GAIN, armedGain));
            String action = intent.getAction();
            if (ACTION_TEST_DEEP.equals(action)) {
                playDeepCue(armedGain);
            } else if (ACTION_TEST_LUCID.equals(action)) {
                playLucidCue(armedGain);
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onMessageReceived(MessageEvent event) {
        float gain = gainFromPayload(event.getData(), armedGain);
        if (Protocol.PATH_DEEP_CUE.equals(event.getPath())) {
            playDeepCue(gain);
        } else if (Protocol.PATH_LUCID_CUE.equals(event.getPath())) {
            playLucidCue(gain);
        } else if (Protocol.PATH_SUMMARY.equals(event.getPath())) {
            String summary = new String(event.getData(), StandardCharsets.UTF_8);
            getSharedPreferences("somnicue_phone", MODE_PRIVATE)
                    .edit()
                    .putString("last_summary", summary)
                    .apply();
        }
    }

    private void playDeepCue(float gain) {
        playbackExecutor.execute(() -> playPcm(createDeepPinkPair(clampGain(gain))));
    }

    private void playLucidCue(float gain) {
        playbackExecutor.execute(() -> playPcm(createLucidChime(clampGain(gain))));
    }

    private float gainFromPayload(byte[] payload, float fallback) {
        try {
            JSONObject json = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            return clampGain((float) json.optDouble("gain", fallback));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private float[] createDeepPinkPair(float gain) {
        int burstSamples = (int) (0.050 * SAMPLE_RATE);
        int secondStart = (int) (1.075 * SAMPLE_RATE);
        int totalSamples = secondStart + burstSamples;
        float[] output = new float[totalSamples];
        float[] burst = createPinkBurst(burstSamples);
        copyBurst(output, burst, 0, gain);
        copyBurst(output, burst, secondStart, gain);
        return output;
    }

    private float[] createPinkBurst(int sampleCount) {
        float[] values = new float[sampleCount];
        Random random = new Random(System.nanoTime());
        double b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
        double max = 0.0001;

        for (int i = 0; i < sampleCount; i++) {
            double white = random.nextDouble() * 2.0 - 1.0;
            b0 = 0.99886 * b0 + white * 0.0555179;
            b1 = 0.99332 * b1 + white * 0.0750759;
            b2 = 0.96900 * b2 + white * 0.1538520;
            b3 = 0.86650 * b3 + white * 0.3104856;
            b4 = 0.55000 * b4 + white * 0.5329522;
            b5 = -0.7616 * b5 - white * 0.0168980;
            double pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362;
            b6 = white * 0.115926;
            values[i] = (float) pink;
            max = Math.max(max, Math.abs(pink));
        }

        int fadeSamples = Math.max(1, (int) (0.005 * SAMPLE_RATE));
        for (int i = 0; i < sampleCount; i++) {
            double envelope = 1.0;
            if (i < fadeSamples) envelope = i / (double) fadeSamples;
            if (i >= sampleCount - fadeSamples) {
                envelope = Math.min(envelope, (sampleCount - 1 - i) / (double) fadeSamples);
            }
            values[i] = (float) ((values[i] / max) * envelope);
        }
        return values;
    }

    private void copyBurst(float[] destination, float[] burst, int offset, float gain) {
        for (int i = 0; i < burst.length; i++) {
            destination[offset + i] = burst[i] * gain;
        }
    }

    private float[] createLucidChime(float gain) {
        int toneSamples = (int) (0.120 * SAMPLE_RATE);
        int gapSamples = (int) (0.100 * SAMPLE_RATE);
        int total = toneSamples * 2 + gapSamples;
        float[] output = new float[total];
        addSine(output, 0, toneSamples, 660.0, gain * 0.75f);
        addSine(output, toneSamples + gapSamples, toneSamples, 880.0, gain * 0.75f);
        return output;
    }

    private void addSine(float[] destination, int offset, int count, double hz, float gain) {
        int fadeSamples = Math.max(1, (int) (0.015 * SAMPLE_RATE));
        for (int i = 0; i < count; i++) {
            double envelope = 1.0;
            if (i < fadeSamples) envelope = i / (double) fadeSamples;
            if (i >= count - fadeSamples) {
                envelope = Math.min(envelope, (count - 1 - i) / (double) fadeSamples);
            }
            destination[offset + i] = (float) (
                    Math.sin(2.0 * Math.PI * hz * i / SAMPLE_RATE) * gain * envelope
            );
        }
    }

    private void playPcm(float[] samples) {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();

        AudioTrack track = null;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(samples.length * Float.BYTES)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            int written = track.write(samples, 0, samples.length, AudioTrack.WRITE_BLOCKING);
            if (written <= 0) return;
            track.play();
            long durationMs = (long) Math.ceil(samples.length * 1000.0 / SAMPLE_RATE);
            Thread.sleep(durationMs + 80L);
        } catch (Exception ignored) {
            // A cue failure is intentionally non-fatal; the watch continues logging the session.
        } finally {
            if (track != null) {
                try { track.stop(); } catch (IllegalStateException ignored) {}
                track.release();
            }
        }
    }

    private float clampGain(float gain) {
        return Math.max(0.005f, Math.min(0.20f, gain));
    }

    private void startAsForeground() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Dream Sync audio armed")
                .setContentText("Waiting for stage-gated cue commands from the watch")
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Dream Sync audio",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Keeps the phone cue engine available during a night session.");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        Wearable.getMessageClient(this).removeListener(this);
        playbackExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
