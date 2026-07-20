package com.remote.DreamSync.shared;

/** Platform sensor boundary shared by standard Wear OS and optional vendor providers. */
public interface SensorProvider {
    String id();
    CapabilityReport capabilities();
    Snapshot latest();

    final class Snapshot {
        public final long elapsedRealtimeMs;
        public final double motionRms;
        public final double heartRateBpm;
        public final double ibiMs;
        public final double skinTemperatureC;
        public final boolean offWrist;

        public Snapshot(
                long elapsedRealtimeMs,
                double motionRms,
                double heartRateBpm,
                double ibiMs,
                double skinTemperatureC,
                boolean offWrist
        ) {
            this.elapsedRealtimeMs = elapsedRealtimeMs;
            this.motionRms = motionRms;
            this.heartRateBpm = heartRateBpm;
            this.ibiMs = ibiMs;
            this.skinTemperatureC = skinTemperatureC;
            this.offWrist = offWrist;
        }
    }
}
