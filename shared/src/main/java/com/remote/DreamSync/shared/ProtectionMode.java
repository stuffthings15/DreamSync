package com.remote.DreamSync.shared;

/** Public operating modes. Historical research modes always migrate fail-closed. */
public enum ProtectionMode {
    PROTECT_ONLY,
    EXPERIMENTAL_AUDIO;

    public static ProtectionMode fromStored(String value) {
        return EXPERIMENTAL_AUDIO.name().equals(value)
                ? EXPERIMENTAL_AUDIO : PROTECT_ONLY;
    }

    /** Historical DEEP rows described experimental audio; this never changes live settings. */
    public static ProtectionMode fromHistoricalRecord(String value) {
        return EXPERIMENTAL_AUDIO.name().equals(value) || "DEEP".equals(value)
                ? EXPERIMENTAL_AUDIO : PROTECT_ONLY;
    }
}
