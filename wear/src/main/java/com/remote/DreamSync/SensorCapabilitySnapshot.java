package com.remote.DreamSync;

import android.os.Build;

import com.remote.DreamSync.shared.DeviceCompatibility;

import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class SensorCapabilitySnapshot {
    final String manufacturer;
    final String model;
    final boolean motion;
    final boolean standardHeartRate;
    final boolean samsungSdkPackaged;
    final boolean samsungSdkConnected;
    final Set<String> samsungTrackerTypes;
    final boolean liveSelfTestPassed;
    final String detail;

    SensorCapabilitySnapshot(
            boolean motion,
            boolean standardHeartRate,
            boolean samsungSdkPackaged,
            boolean samsungSdkConnected,
            Set<String> samsungTrackerTypes,
            boolean liveSelfTestPassed,
            String detail
    ) {
        this.manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        this.model = Build.MODEL == null ? "" : Build.MODEL;
        this.motion = motion;
        this.standardHeartRate = standardHeartRate;
        this.samsungSdkPackaged = samsungSdkPackaged;
        this.samsungSdkConnected = samsungSdkConnected;
        this.samsungTrackerTypes = samsungTrackerTypes == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new HashSet<>(samsungTrackerTypes));
        this.liveSelfTestPassed = liveSelfTestPassed;
        this.detail = detail == null ? "" : detail;
    }

    DeviceCompatibility.Capabilities compatibility() {
        boolean ibi = supports("HEART_RATE_CONTINUOUS");
        boolean temperature = supports("SKIN_TEMPERATURE_CONTINUOUS");
        boolean ppg = supports("PPG_CONTINUOUS");
        return new DeviceCompatibility.Capabilities(
                manufacturer,
                model,
                true,
                motion,
                standardHeartRate || ibi,
                samsungSdkPackaged && samsungSdkConnected,
                ibi,
                temperature,
                ppg,
                ibi,
                liveSelfTestPassed
        );
    }

    String toJson() {
        DeviceCompatibility checker = new DeviceCompatibility();
        DeviceCompatibility.Capabilities values = compatibility();
        JSONObject object = new JSONObject();
        try {
            object.put("manufacturer", manufacturer);
            object.put("model", model);
            object.put("motion", motion);
            object.put("standardHeartRate", standardHeartRate);
            object.put("samsungSdkPackaged", samsungSdkPackaged);
            object.put("samsungSdkConnected", samsungSdkConnected);
            object.put("ibi", values.ibi);
            object.put("skinTemperature", values.skinTemperature);
            object.put("rawPpg", values.rawPpg);
            object.put("offWrist", values.offWrist);
            object.put("liveSelfTestPassed", liveSelfTestPassed);
            object.put("status", checker.classify(values).name());
            object.put("featureProfile", checker.featureProfile(values));
            object.put("allSupportedFeaturesEnabled",
                    checker.classify(values)
                            == DeviceCompatibility.Status.FULLY_SUPPORTED);
            object.put("reason", checker.reason(values));
            object.put("detail", detail);
        } catch (Exception ignored) {
            return "{}";
        }
        return object.toString();
    }

    private boolean supports(String tracker) {
        return samsungTrackerTypes.contains(tracker);
    }
}
