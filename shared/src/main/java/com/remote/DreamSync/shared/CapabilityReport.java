package com.remote.DreamSync.shared;

import java.util.LinkedHashMap;
import java.util.Map;

/** Independent feature report; a missing premium signal never disables working core signals. */
public final class CapabilityReport {
    public enum Profile {
        ENHANCED_PROTECTION,
        CORE_PROTECTION,
        MONITORING_ONLY,
        PHONE_PROTECTION_ONLY,
        NEEDS_ATTENTION
    }

    public final boolean wearOs;
    public final boolean motion;
    public final boolean heartRate;
    public final boolean watchSpeaker;
    public final boolean ibi;
    public final boolean skinTemperature;
    public final boolean offWrist;
    public final boolean rawPpg;
    public final boolean permissionsReady;
    public final boolean liveSelfTestPassed;

    public CapabilityReport(
            boolean wearOs,
            boolean motion,
            boolean heartRate,
            boolean watchSpeaker,
            boolean ibi,
            boolean skinTemperature,
            boolean offWrist,
            boolean rawPpg,
            boolean permissionsReady,
            boolean liveSelfTestPassed
    ) {
        this.wearOs = wearOs;
        this.motion = motion;
        this.heartRate = heartRate;
        this.watchSpeaker = watchSpeaker;
        this.ibi = ibi;
        this.skinTemperature = skinTemperature;
        this.offWrist = offWrist;
        this.rawPpg = rawPpg;
        this.permissionsReady = permissionsReady;
        this.liveSelfTestPassed = liveSelfTestPassed;
    }

    public Profile profile() {
        if (!permissionsReady) return Profile.NEEDS_ATTENTION;
        if (!wearOs) return Profile.PHONE_PROTECTION_ONLY;
        if (!motion) return Profile.NEEDS_ATTENTION;
        if (!heartRate) return Profile.MONITORING_ONLY;
        if (!watchSpeaker || !liveSelfTestPassed) return Profile.MONITORING_ONLY;
        if (watchSpeaker && liveSelfTestPassed && ibi && skinTemperature && offWrist) {
            return Profile.ENHANCED_PROTECTION;
        }
        return Profile.CORE_PROTECTION;
    }

    public String reason() {
        switch (profile()) {
            case ENHANCED_PROTECTION:
                return "Enhanced protection: core sensors, watch speaker, IBI, temperature, and off-wrist checks passed.";
            case CORE_PROTECTION:
                return "Core protection: motion, heart rate, watch speaker, permissions, and live check passed.";
            case MONITORING_ONLY:
                return heartRate
                        ? "Monitoring only: watch audio or the live self-test is not ready."
                        : "Monitoring only: motion is available but heart rate is not ready.";
            case PHONE_PROTECTION_ONLY:
                return "Phone protection only: no compatible Wear OS watch is connected.";
            default:
                return permissionsReady
                        ? "Needs attention: required motion hardware is unavailable."
                        : "Needs attention: required permissions are missing.";
        }
    }

    public boolean experimentalAudioEligible() {
        return permissionsReady && wearOs && motion && heartRate
                && watchSpeaker && liveSelfTestPassed;
    }

    public Map<String, Boolean> features() {
        Map<String, Boolean> values = new LinkedHashMap<>();
        values.put("motion", motion);
        values.put("heartRate", heartRate);
        values.put("watchSpeaker", watchSpeaker);
        values.put("ibiHrv", ibi);
        values.put("skinTemperature", skinTemperature);
        values.put("offWrist", offWrist);
        values.put("rawPpg", rawPpg);
        return values;
    }
}
