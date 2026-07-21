package com.remote.DreamSync;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;

import com.remote.DreamSync.shared.CueWaveforms;

/** Built-in watch-speaker playback. Phone playback is used only if this fails before start. */
final class WatchAudioOutput {
    static final float SYSTEM_CHECK_GAIN = 0.10f;
    static final class Result {
        final boolean success;
        final boolean started;
        final String reason;
        final String diagnostics;

        Result(boolean success, boolean started, String reason, String diagnostics) {
            this.success = success;
            this.started = started;
            this.reason = reason;
            this.diagnostics = diagnostics;
        }
    }

    private final AudioManager audioManager;
    private final float[] deepWaveform;
    private final float[] lucidWaveform;
    private final float[] systemCheckWaveform;
    private volatile boolean preferredSpeakerAccepted;
    private volatile int routedDeviceType = -1;
    private volatile String lastBackend = "none";

    WatchAudioOutput(Context context) {
        audioManager = (AudioManager) context.getApplicationContext()
                .getSystemService(Context.AUDIO_SERVICE);
        deepWaveform = CueWaveforms.deepPinkPair(1.0f, 0x445245414dL);
        lucidWaveform = CueWaveforms.lucidChime(1.0f);
        systemCheckWaveform = CueWaveforms.audibleSystemCheck(1.0f);
    }

    Result preflight() {
        if (audioManager == null) return result(false, false, "watch audio service unavailable");
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0
                || audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
            return result(false, false, "watch media volume is muted");
        }
        if (builtInSpeaker() == null) {
            return result(false, false, "watch built-in speaker is unavailable");
        }
        if (audioManager.getMode() != AudioManager.MODE_NORMAL) {
            return result(false, false, "watch call or audio interruption is active");
        }
        return result(true, false, "watch speaker route ready");
    }

    Result play(String cueType, float gain, Runnable onStarted) {
        float[] source = "LUCID".equals(cueType) ? lucidWaveform : deepWaveform;
        return playWaveform(source, gain, "watch_audiotrack_stream", onStarted);
    }

    Result playSystemCheck(Runnable onStarted) {
        return playWaveform(systemCheckWaveform, SYSTEM_CHECK_GAIN,
                "watch_audiotrack_system_check", onStarted);
    }

    private Result playWaveform(
            float[] source,
            float gain,
            String backend,
            Runnable onStarted
    ) {
        Result preflight = preflight();
        if (!preflight.success) return preflight;
        short[] samples = pcm16(source, gain);
        AudioFocusRequest focusRequest = new AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes())
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(change -> {})
                .build();
        if (audioManager.requestAudioFocus(focusRequest)
                != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return result(false, false, "watch audio focus denied");
        }

        AudioTrack track = null;
        boolean started = false;
        try {
            int minimum = AudioTrack.getMinBufferSize(
                    CueWaveforms.SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) {
                return result(false, false, "invalid watch AudioTrack buffer " + minimum);
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
                return result(false, false, "watch AudioTrack initialization failed");
            }
            AudioDeviceInfo speaker = builtInSpeaker();
            if (speaker == null) return result(false, false, "watch speaker disappeared");
            preferredSpeakerAccepted = track.setPreferredDevice(speaker);
            track.play();
            started = track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
            if (!started) return result(false, false, "watch AudioTrack did not start");
            lastBackend = backend;
            if (onStarted != null) onStarted.run();

            int offset = 0;
            while (offset < samples.length) {
                int written = track.write(samples, offset, samples.length - offset,
                        AudioTrack.WRITE_BLOCKING);
                if (written <= 0) {
                    return result(false, true, "watch audio write failed code=" + written);
                }
                offset += written;
            }
            AudioDeviceInfo routed = track.getRoutedDevice();
            if (routed != null) {
                routedDeviceType = routed.getType();
                if (routedDeviceType != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    return result(false, true,
                            "watch cue routed away from built-in speaker;type="
                                    + routedDeviceType);
                }
            }
            long expectedMs = (long) Math.ceil(
                    samples.length * 1000.0 / CueWaveforms.SAMPLE_RATE);
            long deadline = SystemClock.elapsedRealtime() + expectedMs + 2_000L;
            while (SystemClock.elapsedRealtime() < deadline
                    && track.getPlaybackHeadPosition() < samples.length - 1) {
                SystemClock.sleep(10L);
            }
            if (track.getPlaybackHeadPosition() < samples.length - 1) {
                return result(false, true, "watch audio completion timed out;frames="
                        + track.getPlaybackHeadPosition() + "/" + samples.length);
            }
            return result(true, true, "watch speaker playback completed");
        } catch (RuntimeException exception) {
            return result(false, started, exception.getClass().getSimpleName() + ": "
                    + safe(exception.getMessage()));
        } finally {
            if (track != null) {
                try { track.stop(); } catch (IllegalStateException ignored) {}
                track.release();
            }
            audioManager.abandonAudioFocusRequest(focusRequest);
        }
    }

    String diagnostics() {
        if (audioManager == null) return "audioService=unavailable";
        StringBuilder devices = new StringBuilder();
        for (AudioDeviceInfo device : audioManager.getDevices(
                AudioManager.GET_DEVICES_OUTPUTS)) {
            if (devices.length() > 0) devices.append(',');
            devices.append(device.getType()).append(':').append(safe(device.getProductName()));
        }
        return "mediaVolume=" + audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                + "/" + audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                + ";muted=" + audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
                + ";mode=" + audioManager.getMode()
                + ";musicActive=" + audioManager.isMusicActive()
                + ";outputDevices=" + devices
                + ";builtInSpeaker=" + (builtInSpeaker() != null)
                + ";preferredSpeakerAccepted=" + preferredSpeakerAccepted
                + ";routedDeviceType=" + routedDeviceType
                + ";backend=" + lastBackend;
    }

    int mediaVolume() {
        return audioManager == null ? -1
                : audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
    }

    int mediaVolumeMax() {
        return audioManager == null ? -1
                : audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    }

    private Result result(boolean success, boolean started, String reason) {
        return new Result(success, started, reason, diagnostics());
    }

    private AudioDeviceInfo builtInSpeaker() {
        if (audioManager == null) return null;
        for (AudioDeviceInfo device : audioManager.getDevices(
                AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return device;
        }
        return null;
    }

    private static AudioAttributes attributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
    }

    private static short[] pcm16(float[] source, float gain) {
        float safeGain = Math.max(0.0f, Math.min(0.10f, gain));
        short[] output = new short[source.length];
        for (int i = 0; i < source.length; i++) {
            float sample = Math.max(-1.0f, Math.min(1.0f, source[i] * safeGain));
            output[i] = (short) Math.round(sample * Short.MAX_VALUE);
        }
        return output;
    }

    private static String safe(Object value) {
        return value == null ? "" : value.toString().replace(';', '_').replace('|', '_');
    }
}
