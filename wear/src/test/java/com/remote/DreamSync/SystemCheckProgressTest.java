package com.remote.DreamSync;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SystemCheckProgressTest {
    @Test public void waitingDisplayChangesWithElapsedTime() {
        String first = SystemCheckProgress.format(
                1_000L, 75_000L, 0, false, 0, "ACQUIRING", "passed");
        String later = SystemCheckProgress.format(
                9_000L, 75_000L, 0, true, 0, "FALLBACK", "passed");

        assertTrue(first.contains("1/75s"));
        assertTrue(first.contains("Motion 0 waiting"));
        assertTrue(later.contains("9/75s"));
        assertTrue(later.contains("Motion 0 fallback active"));
        assertTrue(later.contains("heart 0 fallback"));
    }

    @Test public void liveCountsAreShown() {
        String text = SystemCheckProgress.format(
                20_000L, 75_000L, 321, false, 4, "READING", "passed");

        assertTrue(text.contains("Motion 321 reading"));
        assertTrue(text.contains("heart 4 reading"));
        assertTrue(text.contains("sound passed"));
    }
}
