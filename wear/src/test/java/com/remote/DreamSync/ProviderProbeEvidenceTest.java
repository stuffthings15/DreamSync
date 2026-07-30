package com.remote.DreamSync;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ProviderProbeEvidenceTest {
    @Test public void laterFailureDoesNotEraseEarlierRegistration() {
        ProviderProbeEvidence evidence = new ProviderProbeEvidence();

        evidence.recordRegistration(true);
        evidence.recordRegistration(false);

        assertTrue(evidence.everRegistered());
    }
}
