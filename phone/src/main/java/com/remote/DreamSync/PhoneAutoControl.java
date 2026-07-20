package com.remote.DreamSync;

import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.remote.DreamSync.shared.ProtocolEnvelope;

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
        String mode = prefs.getString("auto_mode", "DEEP");
        float gain = Math.max(0.005f, Math.min(0.10f,
                prefs.getFloat("auto_gain", 0.04f)));
        try {
            return new JSONObject()
                    .put("sessionId", sessionId)
                    .put("phoneNodeId", phoneNode)
                    .put("watchNodeId", watchNode)
                    .put("mode", mode)
                    .put("createdUtcMs", createdUtcMs)
                    .put("expiresUtcMs", createdUtcMs + 10L * 60L * 1000L)
                    .put("cueGain", gain)
                    .put("armTimeoutMin", 30)
                    .put("deepStartMin", 25)
                    .put("deepEndMin", 145)
                    .put("remStartMin", 255)
                    .put("sessionEndMin", 510)
                    .put("deepStableMin", 10)
                    .put("remStableMin", 5)
                    .put("deepCueIntervalSec", 30)
                    .put("deepCuesPerBlock", 6)
                    .put("maxDeepBlocks", 2)
                    .put("lucidCueIntervalSec", 180)
                    .put("maxLucidCues", 2)
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

    static void startStandby(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
        Intent intent = new Intent(context, CuePlaybackService.class)
                .setAction(CuePlaybackService.ACTION_AUTO_ENABLE)
                .putExtra(CuePlaybackService.EXTRA_GAIN,
                        prefs.getFloat("auto_gain", 0.04f))
                .putExtra(CuePlaybackService.EXTRA_LOCAL_NODE_ID,
                        prefs.getString("local_phone_node", ""))
                .putExtra(CuePlaybackService.EXTRA_WATCH_NODE_ID,
                        prefs.getString("bound_watch_node", ""));
        ContextCompat.startForegroundService(context, intent);
    }

    private PhoneAutoControl() {}
}
