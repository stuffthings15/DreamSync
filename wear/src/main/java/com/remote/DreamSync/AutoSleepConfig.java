package com.remote.DreamSync;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

final class AutoSleepConfig {
    static final String PREFS = "dreamsync_auto";

    boolean enabled;
    boolean passiveRegistered;
    String enrollmentId = "";
    String phoneNodeId = "";
    String watchNodeId = "";
    String mode = "DEEP";
    String status = "Off";
    float cueGain = 0.04f;
    long lastSessionUtcMs;
    int typicalSleepMinute = -1;
    boolean asleepStateSupported;
    boolean passiveHeartRateSupported;
    long firstLowHeartRateElapsedMs;
    long lastPassiveHeartRateElapsedMs;
    int lowHeartRateSamples;

    static AutoSleepConfig fromJson(String json) {
        AutoSleepConfig config = new AutoSleepConfig();
        try {
            JSONObject object = new JSONObject(json);
            config.enabled = object.optBoolean("enabled", false);
            config.enrollmentId = safe(object.optString("enrollmentId", ""));
            config.phoneNodeId = safe(object.optString("phoneNodeId", ""));
            config.watchNodeId = safe(object.optString("watchNodeId", ""));
            config.mode = sanitizeMode(object.optString("mode", "DEEP"));
            config.cueGain = clampGain((float) object.optDouble("cueGain", 0.04));
            config.typicalSleepMinute = clampMinute(
                    object.optInt("typicalSleepMinute", -1));
        } catch (Exception ignored) {
            config.enabled = false;
        }
        return config;
    }

    static AutoSleepConfig load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AutoSleepConfig config = new AutoSleepConfig();
        config.enabled = p.getBoolean("enabled", false);
        config.passiveRegistered = p.getBoolean("passiveRegistered", false);
        config.enrollmentId = p.getString("enrollmentId", "");
        config.phoneNodeId = p.getString("phoneNodeId", "");
        config.watchNodeId = p.getString("watchNodeId", "");
        config.mode = sanitizeMode(p.getString("mode", "DEEP"));
        config.status = p.getString("status", config.enabled ? "On" : "Off");
        config.cueGain = clampGain(p.getFloat("cueGain", 0.04f));
        config.lastSessionUtcMs = p.getLong("lastSessionUtcMs", 0L);
        config.typicalSleepMinute = clampMinute(
                p.getInt("typicalSleepMinute", -1));
        config.asleepStateSupported = p.getBoolean("asleepStateSupported", false);
        config.passiveHeartRateSupported = p.getBoolean(
                "passiveHeartRateSupported", false);
        config.firstLowHeartRateElapsedMs = p.getLong(
                "firstLowHeartRateElapsedMs", 0L);
        config.lastPassiveHeartRateElapsedMs = p.getLong(
                "lastPassiveHeartRateElapsedMs", 0L);
        config.lowHeartRateSamples = Math.max(0,
                p.getInt("lowHeartRateSamples", 0));
        return config;
    }

    boolean validBinding() {
        return enabled && !enrollmentId.isEmpty()
                && !phoneNodeId.isEmpty() && !watchNodeId.isEmpty();
    }

    void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", enabled)
                .putBoolean("passiveRegistered", passiveRegistered)
                .putString("enrollmentId", enrollmentId)
                .putString("phoneNodeId", phoneNodeId)
                .putString("watchNodeId", watchNodeId)
                .putString("mode", mode)
                .putString("status", status)
                .putFloat("cueGain", cueGain)
                .putLong("lastSessionUtcMs", lastSessionUtcMs)
                .putInt("typicalSleepMinute", typicalSleepMinute)
                .putBoolean("asleepStateSupported", asleepStateSupported)
                .putBoolean("passiveHeartRateSupported", passiveHeartRateSupported)
                .putLong("firstLowHeartRateElapsedMs", firstLowHeartRateElapsedMs)
                .putLong("lastPassiveHeartRateElapsedMs", lastPassiveHeartRateElapsedMs)
                .putInt("lowHeartRateSamples", lowHeartRateSamples)
                .apply();
    }

    boolean reserveSession(Context context, long nowUtcMs) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long current = p.getLong("lastSessionUtcMs", 0L);
        if (!enabled || current > lastSessionUtcMs) return false;
        lastSessionUtcMs = nowUtcMs;
        return p.edit().putLong("lastSessionUtcMs", nowUtcMs).commit();
    }

    static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static String sanitizeMode(String value) {
        if ("LUCID".equals(value) || "COMBINED".equals(value)) return value;
        return "DEEP";
    }

    private static float clampGain(float value) {
        return Math.max(0.005f, Math.min(0.10f, value));
    }

    private static int clampMinute(int value) {
        return value >= 0 && value < 24 * 60 ? value : -1;
    }

    private static String safe(String value) {
        if (value == null) return "";
        String cleaned = value.replaceAll("[^A-Za-z0-9._:-]", "");
        return cleaned.substring(0, Math.min(128, cleaned.length()));
    }
}
