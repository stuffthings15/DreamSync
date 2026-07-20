package com.remote.DreamSync.shared;

/** Capability-based watch support classification; model names alone never unlock cueing. */
public final class DeviceCompatibility {
    public enum Status {
        FULLY_SUPPORTED,
        BASIC_MONITORING_ONLY,
        NOT_SUPPORTED
    }

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
        }
    }

    public Status classify(Capabilities capabilities) {
        if (capabilities == null || !capabilities.wearOs
                || !capabilities.motion || !capabilities.heartRate) {
            return Status.NOT_SUPPORTED;
        }
        if (capabilities.samsungSdk && capabilities.ibi
                && capabilities.skinTemperature && capabilities.offWrist
                && capabilities.liveSelfTestPassed) {
            return Status.FULLY_SUPPORTED;
        }
        return Status.BASIC_MONITORING_ONLY;
    }

    public String reason(Capabilities capabilities) {
        Status status = classify(capabilities);
        if (status == Status.FULLY_SUPPORTED) {
            return "Motion, heart rate, IBI, skin temperature, and off-wrist checks passed.";
        }
        if (status == Status.NOT_SUPPORTED) {
            return "Wear OS motion and heart-rate sensors are required.";
        }
        return "Core motion and heart-rate monitoring is available; advanced sensor signals are incomplete or unverified.";
    }

    /** Features are selected from live capability checks, never a model allow-list. */
    public String featureProfile(Capabilities capabilities) {
        Status status = classify(capabilities);
        if (status == Status.FULLY_SUPPORTED) return "FULL_SENSOR_SUITE";
        if (status == Status.BASIC_MONITORING_ONLY) return "CORE_WEAR_OS";
        return "UNSUPPORTED";
    }

    public boolean shouldUseRawPpg(Capabilities capabilities) {
        return classify(capabilities) == Status.FULLY_SUPPORTED
                && capabilities.rawPpg;
    }
}
