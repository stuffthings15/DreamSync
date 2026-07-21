package com.remote.DreamSync;

import android.content.Context;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.remote.DreamSync.shared.ProtocolEnvelope;
import com.remote.DreamSync.shared.ProtectionConfig;
import com.remote.DreamSync.shared.ProtectionMode;
import com.remote.DreamSync.shared.ResearchMissionContract;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

final class PhoneAutoControl {
    static final String PREFS = "dreamsync_phone";

    static JSONObject autoConfig(
            boolean enabled,
            String enrollmentId,
            String phoneNode,
            String watchNode,
            String mode,
            float gain,
            int typicalSleepMinute
    ) {
        try {
            return new JSONObject()
                    .put("enabled", enabled)
                    .put("enrollmentId", enrollmentId)
                    .put("phoneNodeId", phoneNode)
                    .put("watchNodeId", watchNode)
                    .put("mode", mode)
                    .put("cueGain", gain)
                    .put("typicalSleepMinute", typicalSleepMinute);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static Task<DataItem> publishAutoConfig(
            Context context,
            boolean enabled,
            String enrollmentId,
            String phoneNode,
            String watchNode,
            String mode,
            float gain
    ) {
        int typicalSleepMinute = context.getSharedPreferences(
                        PREFS, Context.MODE_PRIVATE)
                .getInt("typical_sleep_minute", -1);
        JSONObject config = autoConfig(
                enabled, enrollmentId, phoneNode, watchNode, mode, gain,
                typicalSleepMinute);
        String type = enabled
                ? com.remote.DreamSync.shared.Protocol.TYPE_AUTO_CONFIG
                : com.remote.DreamSync.shared.Protocol.TYPE_AUTO_OFF;
        ProtocolEnvelope envelope = ProtocolEnvelope.create(
                type, enrollmentId, "", phoneNode, watchNode,
                System.currentTimeMillis(),
                com.remote.DreamSync.shared.Protocol.AUTO_CONFIG_TTL_MS,
                Map.of("config", config.toString(),
                        "enabled", Boolean.toString(enabled)));
        PutDataMapRequest map = PutDataMapRequest.create(
                com.remote.DreamSync.shared.Protocol.PATH_AUTO_CONFIG);
        map.getDataMap().putByteArray("envelope", envelope.encode());
        PutDataRequest request = map.asPutDataRequest();
        request.setUrgent();
        return Wearable.getDataClient(context).putDataItem(request);
    }

    static JSONObject sessionConfig(
            Context context,
            String sessionId,
            String phoneNode,
            String watchNode,
            long createdUtcMs
    ) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
        String mode = ProtectionMode.fromStored(
                prefs.getString("auto_mode", ProtectionMode.PROTECT_ONLY.name())).name();
        float gain = (float) new ProtectionConfig(
                ProtectionMode.fromStored(mode),
                prefs.getFloat("auto_gain", 0.04f),
                prefs.getInt("safe_cue_night_streak", 0))
                .initialOvernightGain();
        boolean watchAudioCalibrated = prefs.getBoolean(
                "watch_audio_calibrated", false);
        float watchAudibleThreshold = prefs.getFloat(
                "watch_audible_threshold", 0f);
        int watchMediaVolume = prefs.getInt(
                "watch_calibration_media_volume", -1);
        int watchMediaVolumeMax = prefs.getInt(
                "watch_calibration_media_volume_max", -1);
        try {
            return new JSONObject()
                    .put("sessionId", sessionId)
                    .put("phoneNodeId", phoneNode)
                    .put("watchNodeId", watchNode)
                    .put("mode", mode)
                    .put("createdUtcMs", createdUtcMs)
                    .put("expiresUtcMs", createdUtcMs + 10L * 60L * 1000L)
                    .put("cueGain", gain)
                    .put("watchAudioCalibrated", watchAudioCalibrated)
                    .put("watchAudibleThreshold", watchAudibleThreshold)
                    .put("watchMediaVolume", watchMediaVolume)
                    .put("watchMediaVolumeMax", watchMediaVolumeMax)
                    .put("armTimeoutMin", 30)
                    .put("deepStartMin", ResearchMissionContract.NREM_WINDOW_START_MIN)
                    .put("deepEndMin", ResearchMissionContract.NREM_WINDOW_END_MIN)
                    .put("remStartMin", 255)
                    .put("sessionEndMin", 510)
                    .put("deepStableMin", ResearchMissionContract.NREM_STABLE_MIN)
                    .put("remStableMin", 5)
                    .put("deepCueIntervalSec", ResearchMissionContract.CUE_INTERVAL_SEC)
                    .put("deepCuesPerBlock", 4)
                    .put("maxDeepBlocks", 1)
                    .put("lucidCueIntervalSec", 180)
                    .put("maxLucidCues", 0)
                    .put("safeCueNights", prefs.getInt("safe_cue_night_streak", 0))
                    // Capable watches use the full sensor suite. Unsupported trackers
                    // are ignored by the watch after its live capability check.
                    .put("enableRawPpg", true)
                    .put("calibration", prefs.getString("last_calibration", ""))
                    .put("automaticStart", true);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static Task<DataItem> publishSessionConfig(
            Context context,
            String sessionId,
            String phoneNode,
            String watchNode,
            JSONObject config
    ) {
        ProtocolEnvelope envelope = ProtocolEnvelope.create(
                com.remote.DreamSync.shared.Protocol.TYPE_CONFIG,
                sessionId, "", phoneNode, watchNode,
                System.currentTimeMillis(),
                com.remote.DreamSync.shared.Protocol.CONTROL_TTL_MS,
                Map.of("config", config.toString(), "automatic", "true"));
        PutDataMapRequest map = PutDataMapRequest.create(
                com.remote.DreamSync.shared.Protocol.PATH_CONFIG_PREFIX + sessionId);
        map.getDataMap().putByteArray("envelope", envelope.encode());
        PutDataRequest request = map.asPutDataRequest();
        request.setUrgent();
        return Wearable.getDataClient(context).putDataItem(request);
    }

    private PhoneAutoControl() {}
}
