package com.remote.DreamSync;

import android.os.Build;

import com.remote.DreamSync.shared.DeviceCompatibility;
import com.remote.DreamSync.shared.CapabilityReport;

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
    final boolean advancedSelfTestPassed;
    final boolean watchSpeaker;
    final boolean permissionsReady;
    final String detail;

    SensorCapabilitySnapshot(
            boolean motion,
            boolean standardHeartRate,
            boolean samsungSdkPackaged,
            boolean samsungSdkConnected,
            Set<String> samsungTrackerTypes,
            boolean liveSelfTestPassed,
            boolean advancedSelfTestPassed,
            boolean watchSpeaker,
            boolean permissionsReady,
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
        this.advancedSelfTestPassed = advancedSelfTestPassed;
        this.watchSpeaker = watchSpeaker;
        this.permissionsReady = permissionsReady;
        this.detail = detail == null ? "" : detail;
    }

    CapabilityReport report() {
        boolean ibi = supports("HEART_RATE_CONTINUOUS") && advancedSelfTestPassed;
        return new CapabilityReport(true, motion, standardHeartRate || ibi,
                watchSpeaker, ibi,
                supports("SKIN_TEMPERATURE_CONTINUOUS") && advancedSelfTestPassed,
                ibi, supports("PPG_CONTINUOUS"), permissionsReady,
                liveSelfTestPassed);
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
        CapabilityReport report = report();
        JSONObject object = new JSONObject();
        try {
            object.put("manufacturer", manufacturer);
            object.put("model", model);
            object.put("motion", motion);
            object.put("standardHeartRate", standardHeartRate);
            object.put("samsungSdkPackaged", samsungSdkPackaged);
            object.put("samsungSdkConnected", samsungSdkConnected);
            object.put("ibi", report.ibi);
            object.put("skinTemperature", report.skinTemperature);
            object.put("rawPpg", report.rawPpg && advancedSelfTestPassed);
            object.put("offWrist", report.offWrist);
            object.put("liveSelfTestPassed", liveSelfTestPassed);
            object.put("advancedSelfTestPassed", advancedSelfTestPassed);
            object.put("ibiAvailable", supports("HEART_RATE_CONTINUOUS"));
            object.put("skinTemperatureAvailable",
                    supports("SKIN_TEMPERATURE_CONTINUOUS"));
            object.put("rawPpgAvailable", supports("PPG_CONTINUOUS"));
            object.put("offWristProtectionAvailable",
                    standardHeartRate || supports("HEART_RATE_CONTINUOUS"));
            object.put("watchSpeaker", watchSpeaker);
            object.put("permissionsReady", permissionsReady);
            object.put("status", report.profile().name());
            object.put("profile", report.profile().name());
            object.put("featureProfile", report.profile().name());
            object.put("allSupportedFeaturesEnabled",
                    report.profile() == CapabilityReport.Profile.ENHANCED_PROTECTION);
            object.put("experimentalAudioEligible", report.experimentalAudioEligible());
            object.put("reason", report.reason());
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
