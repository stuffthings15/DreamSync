package com.remote.DreamSync;

/** Preserves registration evidence across sequential attempts. */
final class ProviderProbeEvidence {
    private boolean everRegistered;

    void recordRegistration(boolean registered) {
        everRegistered |= registered;
    }

    boolean everRegistered() {
        return everRegistered;
    }
}
