package com.nathanw.somnicue.shared;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class ReplayHarnessTest {
    @Test public void identicalInputProducesIdenticalFingerprint() {
        List<ReplayHarness.Epoch> input = List.of(
                new ReplayHarness.Epoch(60_000L, 0.08, 57, 1.2, 64,
                        false, true, true),
                new ReplayHarness.Epoch(16_200_000L, 0.09, 62, 5.0, 64,
                        false, true, true));
        String first = ReplayHarness.fingerprint(ReplayHarness.replay(input));
        String second = ReplayHarness.fingerprint(ReplayHarness.replay(input));
        assertEquals(first, second);
    }
}
