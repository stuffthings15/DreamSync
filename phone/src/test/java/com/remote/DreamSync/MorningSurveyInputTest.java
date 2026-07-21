package com.remote.DreamSync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MorningSurveyInputTest {
    @Test public void completeReportPreservesLucidResultAndNotes() {
        MorningSurveyInput.Result result = MorningSurveyInput.validate(
                "8", "7", "2", "1", "yes", "3", "no", "none", "clear dream");

        assertTrue(result.valid);
        MorningSurveyEntity entity = result.toEntity("night-1", 123L);
        assertEquals("YES", entity.cueAwareness);
        assertEquals("NO", entity.lucidDream);
        assertEquals("clear dream", entity.dreamNotes);
    }

    @Test public void invalidRequiredRatingFailsInsteadOfSavingZero() {
        MorningSurveyInput.Result result = MorningSurveyInput.validate(
                "", "7", "2", "1", "no", "2", "unsure", "", "");

        assertFalse(result.valid);
        assertTrue(result.error.contains("sleep quality"));
    }

    @Test public void invalidChoiceFailsClearly() {
        MorningSurveyInput.Result result = MorningSurveyInput.validate(
                "8", "7", "2", "1", "maybe", "2", "unsure", "", "");

        assertFalse(result.valid);
        assertTrue(result.error.contains("cue awareness"));
    }
}
