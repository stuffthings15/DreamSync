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
        if (watchSpeaker && liveSelfTestPassed && ibi && skinTemperature && offWrist) {
            return Profile.ENHANCED_PROTECTION;
        }
        return Profile.CORE_PROTECTION;
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
