package com.remote.DreamSync;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class WatchDiagnosticsSnapshotTest {
    @Test public void snapshotSanitizerRedactsIdentifiersAndBoundsText() {
        String input = "sessionId=secret nodeId=node-1 messageId=m-1 cueId=c-1\n"
                + "rawPpg=never-export\n"
                + "motionSamples=5\n"
                + "x".repeat(30_000);

        String result = WatchDiagnostics.sanitizeSnapshot(input, 24_000);

        assertFalse(result.contains("secret"));
        assertFalse(result.contains("node-1"));
        assertFalse(result.contains("m-1"));
        assertFalse(result.contains("c-1"));
        assertFalse(result.contains("never-export"));
        assertTrue(result.contains("motionSamples=5"));
        assertTrue(result.length() <= 24_000);
    }
}
