package com.remote.DreamSync;

import android.hardware.Sensor;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Selects one sensor provider for diagnostic isolation, or every provider for normal checks. */
final class SensorProbeSelection {
    private static final String ALL = "all";
    private final String provider;
    private final boolean valid;

    private SensorProbeSelection(String provider, boolean valid) {
        this.provider = provider;
        this.valid = valid;
    }

    static SensorProbeSelection fromQuery(String value) {
        String provider = value == null || value.isBlank()
                ? ALL : value.trim().toLowerCase(Locale.US);
        boolean valid = provider.equals(ALL)
                || provider.equals("android-accelerometer")
                || provider.equals("android-linear-acceleration")
                || provider.equals("android-gravity")
                || provider.equals("samsung-accelerometer")
                || provider.equals("health-services-heart")
                || provider.equals("android-heart")
                || provider.equals("samsung-heart");
        return new SensorProbeSelection(provider, valid);
    }

    boolean valid() {
        return valid;
    }

    String provider() {
        return provider;
    }

    boolean isolated() {
        return valid && !ALL.equals(provider);
    }

    boolean standardMotion() {
        return valid && (ALL.equals(provider) || provider.startsWith("android-")
                && !provider.equals("android-heart"));
    }

    boolean acceptsMotionType(int type) {
        if (!standardMotion()) return false;
        if (ALL.equals(provider)) return type == Sensor.TYPE_ACCELEROMETER
                || type == Sensor.TYPE_LINEAR_ACCELERATION || type == Sensor.TYPE_GRAVITY;
        if (provider.equals("android-accelerometer")) return type == Sensor.TYPE_ACCELEROMETER;
        if (provider.equals("android-linear-acceleration")) {
            return type == Sensor.TYPE_LINEAR_ACCELERATION;
        }
        return provider.equals("android-gravity") && type == Sensor.TYPE_GRAVITY;
    }

    boolean samsungMotion() {
        return valid && (ALL.equals(provider) || provider.equals("samsung-accelerometer"));
    }

    boolean healthServicesHeart() {
        return valid && (ALL.equals(provider) || provider.equals("health-services-heart"));
    }

    boolean standardHeart() {
        return valid && (ALL.equals(provider) || provider.equals("android-heart"));
    }

    boolean samsungHeart() {
        return valid && (ALL.equals(provider) || provider.equals("samsung-heart"));
    }

    Set<String> samsungTrackerTypes() {
        if (!valid) return Collections.emptySet();
        Set<String> trackers = new LinkedHashSet<>();
        if (samsungMotion()) trackers.add("ACCELEROMETER_CONTINUOUS");
        if (samsungHeart()) trackers.add("HEART_RATE_CONTINUOUS");
        if (ALL.equals(provider)) trackers.add("SKIN_TEMPERATURE_CONTINUOUS");
        return Collections.unmodifiableSet(trackers);
    }
}
