package com.remote.DreamSync;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.SystemClock;

import com.remote.DreamSync.shared.CueWaveforms;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class AudioOutput {
    static final class Result {
        final boolean success;
        final String reason;
        Result(boolean success, String reason) {
            this.success = success;
            this.reason = reason;
        }
    }

    private static final class Attempt {
        final boolean success;
        final boolean started;
        final String reason;

        Attempt(boolean success, boolean started, String reason) {
            this.success = success;
            this.started = started;
            this.reason = reason;
        }
    }

    private final Context appContext;
    private final AudioManager audioManager;
    private final float[] deepWaveform;
    private final float[] lucidWaveform;
    private volatile String lastBackend = "none";
    private volatile int lastRouteType = -1;
    private volatile boolean lastPreferredDeviceAccepted;

    AudioOutput(Context context) {
        appContext = context.getApplicationContext();
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        deepWaveform = CueWaveforms.deepPinkPair(1.0f, 0x445245414dL);
        lucidWaveform = CueWaveforms.lucidChime(1.0f);
    }

    Result preflight() {
        if (audioManager == null) return new Result(false, "audio service unavailable");
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0) {
            return new Result(false, "phone media volume is muted");
        }
        // getDevices(GET_DEVICES_OUTPUTS) reports every available route, not the route
        // currently used by media. A paired watch can therefore expose Bluetooth types
        // even while the phone speaker is active. Only require a real phone speaker here;
        // each playback backend explicitly prefers and verifies that speaker below.
        if (phoneSpeaker() == null) return new Result(false, "phone speaker unavailable");
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
                + ";route=phone_speaker"
                + ";backend=" + lastBackend;
    }

    String diagnostics() {
        if (audioManager == null) return "audioService=unavailable";
        StringBuilder devices = new StringBuilder();
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (devices.length() > 0) devices.append(',');
            devices.append(device.getType());
        }
        return "mediaVolume=" + audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                + "/" + audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                + ";mode=" + audioManager.getMode()
                + ";musicActive=" + audioManager.isMusicActive()
                + ";outputDeviceTypes=" + devices
                + ";phoneSpeakerAvailable=" + (phoneSpeaker() != null)
                + ";preferredSpeakerAccepted=" + lastPreferredDeviceAccepted
                + ";routedDeviceType=" + lastRouteType
                + ";lastBackend=" + lastBackend;
    }

    private AudioDeviceInfo phoneSpeaker() {
        if (audioManager == null) return null;
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return device;
        }
        return null;
    }

    static boolean hasPhoneSpeaker(int... outputTypes) {
        if (outputTypes == null) return false;
        for (int type : outputTypes) {
            if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return true;
        }
        return false;
    }

    Result play(String cueType, float gain, Runnable onStarted) {
        Result preflight = preflight();
        if (!preflight.success) return preflight;
        float[] source = "LUCID".equals(cueType) ? lucidWaveform : deepWaveform;
        short[] samples = pcm16(source, gain);

        AtomicBoolean interrupted = new AtomicBoolean(false);
        AtomicBoolean startReported = new AtomicBoolean(false);
        Runnable reportStarted = () -> {
            if (startReported.compareAndSet(false, true)) onStarted.run();
        };
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

        try {
            // MediaPlayer cannot select a preferred output until Android 9.
            // AudioTrack supports speaker selection on every DreamSync phone version.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                Attempt stream = playWithStreamingAudioTrack(samples, reportStarted);
                if (stream.success && interrupted.get()) {
                    return new Result(false, "audio focus interrupted");
                }
                return new Result(stream.success, stream.reason);
            }
            Attempt mediaPlayer = playWavWithMediaPlayer(samples, reportStarted);
            if (mediaPlayer.success) {
                if (interrupted.get()) return new Result(false, "audio focus interrupted");
                return new Result(true, mediaPlayer.reason);
            }
            // Never play a second copy after any backend has begun. That preserves the
            // invariant that one cue request produces at most one audible cue.
            if (mediaPlayer.started) return new Result(false, mediaPlayer.reason);

            Attempt stream = playWithStreamingAudioTrack(samples, reportStarted);
            if (stream.success) {
                if (interrupted.get()) return new Result(false, "audio focus interrupted");
                return new Result(true, stream.reason);
            }
            return new Result(false, "MediaPlayer failed before playback ("
                    + mediaPlayer.reason + "); AudioTrack fallback failed ("
                    + stream.reason + ")");
        } finally {
            audioManager.abandonAudioFocusRequest(focusRequest);
        }
    }

    private Attempt playWavWithMediaPlayer(short[] samples, Runnable onStarted) {
        File wav = null;
        MediaPlayer player = null;
        boolean started = false;
        try {
            wav = File.createTempFile("dreamsync-cue-", ".wav", appContext.getCacheDir());
            try (FileOutputStream output = new FileOutputStream(wav)) {
                output.write(wavPcm16(samples));
                output.getFD().sync();
            }

            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<String> playerError = new AtomicReference<>("");
            player = new MediaPlayer();
            player.setAudioAttributes(attributes());
            player.setOnCompletionListener(ignored -> finished.countDown());
            player.setOnErrorListener((ignored, what, extra) -> {
                playerError.set("MediaPlayer error what=" + what + " extra=" + extra);
                finished.countDown();
                return true;
            });
            try (FileInputStream input = new FileInputStream(wav)) {
                player.setDataSource(input.getFD());
            }
            player.prepare();
            AudioDeviceInfo speaker = phoneSpeaker();
            if (speaker == null) return new Attempt(false, false, "phone speaker unavailable");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lastPreferredDeviceAccepted = player.setPreferredDevice(speaker);
            }
            player.setVolume(1.0f, 1.0f);
            player.start();
            started = player.isPlaying();
            if (!started) return new Attempt(false, false, "MediaPlayer did not enter playing state");
            AudioDeviceInfo routed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? player.getRoutedDevice() : null;
            if (routed != null) {
                lastRouteType = routed.getType();
                if (lastRouteType != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    return new Attempt(false, true,
                            "cue routed away from phone speaker;type=" + lastRouteType);
                }
            }
            lastBackend = "wav_mediaplayer";
            onStarted.run();

            long expectedMs = expectedDurationMs(samples.length);
            boolean callback = finished.await(expectedMs + 2_000L, TimeUnit.MILLISECONDS);
            String error = playerError.get();
            if (!error.isEmpty()) return new Attempt(false, true, error);
            if (!callback) return new Attempt(false, true, "MediaPlayer completion timed out");
            return new Attempt(true, true, "audio completed via WAV MediaPlayer");
        } catch (Exception error) {
            String message = error.getMessage();
            return new Attempt(false, started, error.getClass().getSimpleName()
                    + (message == null || message.isEmpty() ? "" : ": " + message));
        } finally {
            if (player != null) {
                try { player.stop(); } catch (IllegalStateException ignored) {}
                player.release();
            }
            if (wav != null && wav.exists()) {
                // Best effort only. Failure leaves a small app-private cache file.
                wav.delete();
            }
        }
    }

    private Attempt playWithStreamingAudioTrack(short[] samples, Runnable onStarted) {
        AudioTrack track = null;
        boolean started = false;
        try {
            int minimum = AudioTrack.getMinBufferSize(
                    CueWaveforms.SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) {
                return new Attempt(false, false, "invalid minimum buffer " + minimum);
            }
            int bufferBytes = Math.max(minimum, minimum * 4);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attributes())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(CueWaveforms.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                return new Attempt(false, false, "streaming AudioTrack initialization failed"
                        + ";minimumBuffer=" + minimum + ";requestedBuffer=" + bufferBytes);
            }
            AudioDeviceInfo speaker = phoneSpeaker();
            if (speaker == null) return new Attempt(false, false, "phone speaker unavailable");
            lastPreferredDeviceAccepted = track.setPreferredDevice(speaker);
            track.play();
            started = track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
            if (!started) return new Attempt(false, false, "streaming AudioTrack did not start");
            lastBackend = "audiotrack_stream";
            onStarted.run();

            int offset = 0;
            while (offset < samples.length) {
                int written = track.write(
                        samples, offset, samples.length - offset, AudioTrack.WRITE_BLOCKING);
                if (written <= 0) {
                    return new Attempt(false, true, "streaming audio write failed code=" + written);
                }
                offset += written;
            }
            AudioDeviceInfo routed = track.getRoutedDevice();
            if (routed != null) {
                lastRouteType = routed.getType();
                if (lastRouteType != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    return new Attempt(false, true,
                            "cue routed away from phone speaker;type=" + lastRouteType);
                }
            }
            long deadline = SystemClock.elapsedRealtime() + expectedDurationMs(samples.length) + 2_000L;
            while (SystemClock.elapsedRealtime() < deadline
                    && track.getPlaybackHeadPosition() < samples.length - 1) {
                SystemClock.sleep(10L);
            }
            if (track.getPlaybackHeadPosition() < samples.length - 1) {
                return new Attempt(false, true, "streaming audio completion timed out;frames="
                        + track.getPlaybackHeadPosition() + "/" + samples.length);
            }
            return new Attempt(true, true, "audio completed via streaming AudioTrack fallback");
        } catch (Exception error) {
            String message = error.getMessage();
            return new Attempt(false, started, error.getClass().getSimpleName()
                    + (message == null || message.isEmpty() ? "" : ": " + message));
        } finally {
            if (track != null) {
                try { track.stop(); } catch (IllegalStateException ignored) {}
                track.release();
            }
        }
    }

    private long expectedDurationMs(int sampleCount) {
        return (long) Math.ceil(sampleCount * 1000.0 / CueWaveforms.SAMPLE_RATE);
    }

    private AudioAttributes attributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
    }

    static short[] pcm16(float[] source, float gain) {
        float safeGain = Math.max(0.0f, Math.min(1.0f, gain));
        short[] output = new short[source.length];
        for (int i = 0; i < source.length; i++) {
            float sample = Math.max(-1.0f, Math.min(1.0f, source[i] * safeGain));
            output[i] = (short) Math.round(sample * Short.MAX_VALUE);
        }
        return output;
    }

    static byte[] wavPcm16(short[] samples) {
        int payloadBytes = samples.length * Short.BYTES;
        byte[] output = new byte[44 + payloadBytes];
        ascii(output, 0, "RIFF");
        littleEndianInt(output, 4, 36 + payloadBytes);
        ascii(output, 8, "WAVE");
        ascii(output, 12, "fmt ");
        littleEndianInt(output, 16, 16);
        littleEndianShort(output, 20, 1); // PCM
        littleEndianShort(output, 22, 1); // mono
        littleEndianInt(output, 24, CueWaveforms.SAMPLE_RATE);
        littleEndianInt(output, 28, CueWaveforms.SAMPLE_RATE * Short.BYTES);
        littleEndianShort(output, 32, Short.BYTES);
        littleEndianShort(output, 34, 16);
        ascii(output, 36, "data");
        littleEndianInt(output, 40, payloadBytes);
        int offset = 44;
        for (short sample : samples) {
            littleEndianShort(output, offset, sample);
            offset += Short.BYTES;
        }
        return output;
    }

    private static void ascii(byte[] output, int offset, String value) {
        for (int i = 0; i < value.length(); i++) output[offset + i] = (byte) value.charAt(i);
    }

    private static void littleEndianInt(byte[] output, int offset, int value) {
        output[offset] = (byte) value;
        output[offset + 1] = (byte) (value >>> 8);
        output[offset + 2] = (byte) (value >>> 16);
        output[offset + 3] = (byte) (value >>> 24);
    }

    private static void littleEndianShort(byte[] output, int offset, int value) {
        output[offset] = (byte) value;
        output[offset + 1] = (byte) (value >>> 8);
    }
}
