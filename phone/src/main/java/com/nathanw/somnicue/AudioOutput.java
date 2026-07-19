package com.nathanw.somnicue;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import com.nathanw.somnicue.shared.CueWaveforms;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class AudioOutput {
    static final class Result {
        final boolean success;
        final String reason;
        Result(boolean success, String reason) {
            this.success = success;
            this.reason = reason;
        }
    }

    private final AudioManager audioManager;
    private final float[] deepWaveform;
    private final float[] lucidWaveform;

    AudioOutput(Context context) {
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        deepWaveform = CueWaveforms.deepPinkPair(1.0f, 0x445245414dL);
        lucidWaveform = CueWaveforms.lucidChime(1.0f);
    }

    Result preflight() {
        if (audioManager == null) return new Result(false, "audio service unavailable");
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0) {
            return new Result(false, "phone media volume is muted");
        }
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    || type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                return new Result(false, "disconnect headphones or Bluetooth audio");
            }
        }
        if (audioManager.getMode() != AudioManager.MODE_NORMAL) {
            return new Result(false, "phone call or audio interruption is active");
        }
        return new Result(true, "speaker route ready");
    }

    String calibrationRecord(float gain) {
        return "waveform=" + CueWaveforms.VERSION
                + ";volumeStep=" + audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                + ";maxVolume=" + audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                + ";appGain=" + gain
                + ";route=phone_speaker";
    }

    Result play(String cueType, float gain, Runnable onStarted) {
        Result preflight = preflight();
        if (!preflight.success) return preflight;
        float[] source = "LUCID".equals(cueType) ? lucidWaveform : deepWaveform;
        float[] samples = new float[source.length];
        for (int i = 0; i < source.length; i++) samples[i] = source[i] * gain;

        AtomicBoolean interrupted = new AtomicBoolean(false);
        AudioFocusRequest focusRequest = new AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes())
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS
                            || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        interrupted.set(true);
                    }
                })
                .build();
        int focus = audioManager.requestAudioFocus(focusRequest);
        if (focus != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return new Result(false, "audio focus denied");
        }

        AudioTrack track = null;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attributes())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(CueWaveforms.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(samples.length * Float.BYTES)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                return new Result(false, "AudioTrack initialization failed");
            }
            int written = track.write(samples, 0, samples.length, AudioTrack.WRITE_BLOCKING);
            if (written != samples.length) return new Result(false, "audio buffer write failed");

            CountDownLatch completed = new CountDownLatch(1);
            AtomicBoolean started = new AtomicBoolean(false);
            track.setNotificationMarkerPosition(samples.length - 1);
            track.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
                @Override public void onMarkerReached(AudioTrack ignored) { completed.countDown(); }
                @Override public void onPeriodicNotification(AudioTrack ignored) {}
            });
            track.play();
            if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                started.set(true);
                onStarted.run();
            }
            long expectedMs = (long) Math.ceil(samples.length * 1000.0 / CueWaveforms.SAMPLE_RATE);
            boolean marker = completed.await(expectedMs + 1_000L, TimeUnit.MILLISECONDS);
            if (!started.get()) return new Result(false, "audio did not start");
            if (interrupted.get()) return new Result(false, "audio focus interrupted");
            if (!marker && track.getPlaybackHeadPosition() < samples.length - 1) {
                return new Result(false, "audio did not complete");
            }
            return new Result(true, "audio completed");
        } catch (Exception error) {
            return new Result(false, error.getClass().getSimpleName() + ": " + error.getMessage());
        } finally {
            if (track != null) {
                try { track.stop(); } catch (IllegalStateException ignored) {}
                track.release();
            }
            audioManager.abandonAudioFocusRequest(focusRequest);
        }
    }

    private AudioAttributes attributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
    }
}
