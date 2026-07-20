package com.remote.DreamSync;

import android.content.Context;
import android.content.SharedPreferences;

import com.remote.DreamSync.shared.SleepSessionClock;

import org.json.JSONObject;

final class SessionConfig {
    static final String PREFS = "dreamsync_config";

    String sessionId = "";
    String phoneNodeId = "";
    String watchNodeId = "";
    String mode = "DEEP";
    long createdUtcMs;
    long expiresUtcMs;
    float cueGain = 0.04f;
    int armTimeoutMin = 180;
    int deepStartMin = 25;
    int deepEndMin = 145;
    int remStartMin = 255;
    int sessionEndMin = 510;
    int deepStableMin = 10;
    int remStableMin = 5;
    int deepCueIntervalSec = 30;
    int deepCuesPerBlock = 6;
    int maxDeepBlocks = 2;
    int lucidCueIntervalSec = 180;
    int maxLucidCues = 2;
    boolean enableRawPpg;

    static SessionConfig fromJson(String json) {
        SessionConfig config = new SessionConfig();
        try {
            JSONObject object = new JSONObject(json);
            config.sessionId = object.optString("sessionId", config.sessionId);
            config.phoneNodeId = object.optString("phoneNodeId", config.phoneNodeId);
            config.watchNodeId = object.optString("watchNodeId", config.watchNodeId);
            config.mode = object.optString("mode", config.mode);
            config.createdUtcMs = object.optLong("createdUtcMs", config.createdUtcMs);
            config.expiresUtcMs = object.optLong("expiresUtcMs", config.expiresUtcMs);
            config.cueGain = (float) object.optDouble("cueGain", config.cueGain);
            config.armTimeoutMin = object.optInt("armTimeoutMin", config.armTimeoutMin);
            config.deepStartMin = object.optInt("deepStartMin", config.deepStartMin);
            config.deepEndMin = object.optInt("deepEndMin", config.deepEndMin);
            config.remStartMin = object.optInt("remStartMin", config.remStartMin);
            config.sessionEndMin = object.optInt("sessionEndMin", config.sessionEndMin);
            config.deepStableMin = object.optInt("deepStableMin", config.deepStableMin);
            config.remStableMin = object.optInt("remStableMin", config.remStableMin);
            config.deepCueIntervalSec = object.optInt("deepCueIntervalSec", config.deepCueIntervalSec);
            config.deepCuesPerBlock = object.optInt("deepCuesPerBlock", config.deepCuesPerBlock);
            config.maxDeepBlocks = object.optInt("maxDeepBlocks", config.maxDeepBlocks);
            config.lucidCueIntervalSec = object.optInt("lucidCueIntervalSec", config.lucidCueIntervalSec);
            config.maxLucidCues = object.optInt("maxLucidCues", config.maxLucidCues);
            config.enableRawPpg = object.optBoolean("enableRawPpg", false);
        } catch (Exception ignored) {
            // Invalid fields retain conservative defaults.
        }
        config.clamp();
        return config;
    }

    // Every path that produces a usable config must end here, so out-of-range
    // values can never reach the cue engine regardless of where they came from.
    private void clamp() {
        sessionId = safeIdentifier(sessionId);
        phoneNodeId = safeIdentifier(phoneNodeId);
        watchNodeId = safeIdentifier(watchNodeId);
        mode = sanitizeMode(mode);
        cueGain = clampFloat(cueGain, 0.005f, 0.10f);
        armTimeoutMin = clampInt(armTimeoutMin, 30, 360);
        deepStartMin = clampInt(deepStartMin, 10, 120);
        deepEndMin = clampInt(deepEndMin, 60, 300);
        remStartMin = clampInt(remStartMin, 180, 420);
        sessionEndMin = clampInt(sessionEndMin, 240, 720);
        deepStableMin = clampInt(deepStableMin, 3, 20);
        remStableMin = clampInt(remStableMin, 1, 10);
        deepCueIntervalSec = clampInt(deepCueIntervalSec, 10, 60);
        deepCuesPerBlock = clampInt(deepCuesPerBlock, 1, 20);
        maxDeepBlocks = clampInt(maxDeepBlocks, 0, 6);
        lucidCueIntervalSec = clampInt(lucidCueIntervalSec, 45, 600);
        maxLucidCues = clampInt(maxLucidCues, 0, 10);
        if (deepEndMin <= deepStartMin) {
            deepEndMin = deepStartMin + 60;
        }
        if (sessionEndMin <= remStartMin) {
            sessionEndMin = remStartMin + 120;
        }
    }

    static SessionConfig load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SessionConfig c = new SessionConfig();
        c.sessionId = p.getString("sessionId", c.sessionId);
        c.phoneNodeId = p.getString("phoneNodeId", c.phoneNodeId);
        c.watchNodeId = p.getString("watchNodeId", c.watchNodeId);
        c.mode = sanitizeMode(p.getString("mode", c.mode));
        c.createdUtcMs = p.getLong("createdUtcMs", c.createdUtcMs);
        c.expiresUtcMs = p.getLong("expiresUtcMs", c.expiresUtcMs);
        c.cueGain = p.getFloat("cueGain", c.cueGain);
        c.armTimeoutMin = p.getInt("armTimeoutMin", c.armTimeoutMin);
        c.deepStartMin = p.getInt("deepStartMin", c.deepStartMin);
        c.deepEndMin = p.getInt("deepEndMin", c.deepEndMin);
        c.remStartMin = p.getInt("remStartMin", c.remStartMin);
        c.sessionEndMin = p.getInt("sessionEndMin", c.sessionEndMin);
        c.deepStableMin = p.getInt("deepStableMin", c.deepStableMin);
        c.remStableMin = p.getInt("remStableMin", c.remStableMin);
        c.deepCueIntervalSec = p.getInt("deepCueIntervalSec", c.deepCueIntervalSec);
        c.deepCuesPerBlock = p.getInt("deepCuesPerBlock", c.deepCuesPerBlock);
        c.maxDeepBlocks = p.getInt("maxDeepBlocks", c.maxDeepBlocks);
        c.lucidCueIntervalSec = p.getInt("lucidCueIntervalSec", c.lucidCueIntervalSec);
        c.maxLucidCues = p.getInt("maxLucidCues", c.maxLucidCues);
        c.enableRawPpg = p.getBoolean("enableRawPpg", false);
        c.clamp();
        return c;
    }

    void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("sessionId", sessionId)
                .putString("phoneNodeId", phoneNodeId)
                .putString("watchNodeId", watchNodeId)
                .putString("mode", mode)
                .putLong("createdUtcMs", createdUtcMs)
                .putLong("expiresUtcMs", expiresUtcMs)
                .putFloat("cueGain", cueGain)
                .putInt("armTimeoutMin", armTimeoutMin)
                .putInt("deepStartMin", deepStartMin)
                .putInt("deepEndMin", deepEndMin)
                .putInt("remStartMin", remStartMin)
                .putInt("sessionEndMin", sessionEndMin)
                .putInt("deepStableMin", deepStableMin)
                .putInt("remStableMin", remStableMin)
                .putInt("deepCueIntervalSec", deepCueIntervalSec)
                .putInt("deepCuesPerBlock", deepCuesPerBlock)
                .putInt("maxDeepBlocks", maxDeepBlocks)
                .putInt("lucidCueIntervalSec", lucidCueIntervalSec)
                .putInt("maxLucidCues", maxLucidCues)
                .putBoolean("enableRawPpg", enableRawPpg)
                .apply();
    }

    static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    boolean isCurrent(long nowUtcMs) {
        return SleepSessionClock.isCurrentArmWindow(createdUtcMs, expiresUtcMs, nowUtcMs);
    }

    boolean usesDeepCues() {
        return "DEEP".equals(mode) || "COMBINED".equals(mode);
    }

    boolean usesLucidCues() {
        return "LUCID".equals(mode) || "COMBINED".equals(mode);
    }

    private static String sanitizeMode(String input) {
        if ("DEEP".equals(input)
                || "LUCID".equals(input)
                || "COMBINED".equals(input)) {
            return input;
        }
        return "DEEP";
    }

    private static String safeIdentifier(String value) {
        if (value == null) return "";
        return value.replaceAll("[^A-Za-z0-9._:-]", "").substring(
                0, Math.min(128, value.replaceAll("[^A-Za-z0-9._:-]", "").length()));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
