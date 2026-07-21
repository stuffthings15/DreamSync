package com.remote.DreamSync.shared;

/** Capability-based watch support classification; model names alone never unlock cueing. */
public final class DeviceCompatibility {
    public enum Status { ENHANCED_PROTECTION, CORE_PROTECTION, MONITORING_ONLY,
        PHONE_PROTECTION_ONLY, NEEDS_ATTENTION }

    public static final class Capabilities {
        public final String manufacturer;
        public final String model;
        public final boolean wearOs;
        public final boolean motion;
        public final boolean heartRate;
        public final boolean samsungSdk;
        public final boolean ibi;
        public final boolean skinTemperature;
        public final boolean rawPpg;
        public final boolean offWrist;
        public final boolean liveSelfTestPassed;
        public final boolean watchSpeaker;
        public final boolean permissionsReady;

        public Capabilities(
                String manufacturer,
                String model,
                boolean wearOs,
                boolean motion,
                boolean heartRate,
                boolean samsungSdk,
                boolean ibi,
                boolean skinTemperature,
                boolean rawPpg,
                boolean offWrist,
                boolean liveSelfTestPassed
        ) {
            this(manufacturer, model, wearOs, motion, heartRate, samsungSdk, ibi,
                    skinTemperature, rawPpg, offWrist, liveSelfTestPassed,
                    true, true);
        }

        public Capabilities(
                String manufacturer,
                String model,
                boolean wearOs,
                boolean motion,
                boolean heartRate,
                boolean samsungSdk,
                boolean ibi,
                boolean skinTemperature,
                boolean rawPpg,
                boolean offWrist,
                boolean liveSelfTestPassed,
                boolean watchSpeaker,
                boolean permissionsReady
        ) {
            this.manufacturer = manufacturer == null ? "" : manufacturer;
            this.model = model == null ? "" : model;
            this.wearOs = wearOs;
            this.motion = motion;
            this.heartRate = heartRate;
            this.samsungSdk = samsungSdk;
            this.ibi = ibi;
            this.skinTemperature = skinTemperature;
            this.rawPpg = rawPpg;
            this.offWrist = offWrist;
            this.liveSelfTestPassed = liveSelfTestPassed;
            this.watchSpeaker = watchSpeaker;
            this.permissionsReady = permissionsReady;
        }
    }

    public Status classify(Capabilities capabilities) {
        if (capabilities == null) return Status.NEEDS_ATTENTION;
        return Status.valueOf(report(capabilities).profile().name());
    }

    public String reason(Capabilities capabilities) {
        return capabilities == null ? "No watch capability report." : report(capabilities).reason();
    }

    /** Features are selected from live capability checks, never a model allow-list. */
    public String featureProfile(Capabilities capabilities) {
        Status status = classify(capabilities);
        return status.name();
    }

    public boolean shouldUseRawPpg(Capabilities capabilities) {
        return classify(capabilities) == Status.ENHANCED_PROTECTION
                && capabilities.rawPpg;
    }

    private CapabilityReport report(Capabilities c) {
        return new CapabilityReport(c.wearOs, c.motion, c.heartRate,
                c.watchSpeaker, c.ibi, c.skinTemperature, c.offWrist, c.rawPpg,
                c.permissionsReady, c.liveSelfTestPassed);
    }
}
