package com.remote.DreamSync;

import java.util.Locale;

/** Validates the complete documented morning report before it reaches Room. */
final class MorningSurveyInput {
    static final class Result {
        final boolean valid;
        final String error;
        final int sleepQuality;
        final int restedness;
        final int rememberedAwakenings;
        final int disturbanceRating;
        final String cueAwareness;
        final int dreamRecall;
        final String lucidDream;
        final String adverseEffects;
        final String dreamNotes;

        private Result(
                boolean valid,
                String error,
                int sleepQuality,
                int restedness,
                int rememberedAwakenings,
                int disturbanceRating,
                String cueAwareness,
                int dreamRecall,
                String lucidDream,
                String adverseEffects,
                String dreamNotes
        ) {
            this.valid = valid;
            this.error = error;
            this.sleepQuality = sleepQuality;
            this.restedness = restedness;
            this.rememberedAwakenings = rememberedAwakenings;
            this.disturbanceRating = disturbanceRating;
            this.cueAwareness = cueAwareness;
            this.dreamRecall = dreamRecall;
            this.lucidDream = lucidDream;
            this.adverseEffects = adverseEffects;
            this.dreamNotes = dreamNotes;
        }

        MorningSurveyEntity toEntity(String sessionId, long submittedUtcMs) {
            MorningSurveyEntity survey = new MorningSurveyEntity();
            survey.sessionId = sessionId;
            survey.sleepQuality = sleepQuality;
            survey.restedness = restedness;
            survey.rememberedAwakenings = rememberedAwakenings;
            survey.disturbanceRating = disturbanceRating;
            survey.cueAwareness = cueAwareness;
            survey.dreamRecall = dreamRecall;
            survey.lucidDream = lucidDream;
            survey.adverseEffects = adverseEffects;
            survey.dreamNotes = dreamNotes;
            survey.submittedUtcMs = submittedUtcMs;
            return survey;
        }
    }

    static Result validate(
            String sleepQuality,
            String restedness,
            String rememberedAwakenings,
            String disturbanceRating,
            String cueAwareness,
            String dreamRecall,
            String lucidDream,
            String adverseEffects,
            String dreamNotes
    ) {
        Integer sleep = integerInRange(sleepQuality, 1, 10);
        if (sleep == null) return invalid("Enter sleep quality from 1 to 10.");
        Integer rested = integerInRange(restedness, 1, 10);
        if (rested == null) return invalid("Enter restedness from 1 to 10.");
        Integer awakenings = integerInRange(rememberedAwakenings, 0, 100);
        if (awakenings == null) return invalid("Enter remembered awakenings from 0 to 100.");
        Integer disturbance = integerInRange(disturbanceRating, 0, 10);
        if (disturbance == null) return invalid("Enter cue disturbance from 0 to 10.");
        String awareness = choice(cueAwareness);
        if (awareness == null) return invalid("Enter cue awareness as no, unsure, or yes.");
        Integer recall = integerInRange(dreamRecall, 0, 3);
        if (recall == null) return invalid("Enter dream recall from 0 to 3.");
        String lucid = choice(lucidDream);
        if (lucid == null) return invalid("Enter lucid dream as no, unsure, or yes.");
        return new Result(true, "", sleep, rested, awakenings, disturbance,
                awareness, recall, lucid, clean(adverseEffects), clean(dreamNotes));
    }

    private static Result invalid(String error) {
        return new Result(false, error, 0, 0, 0, 0,
                "UNSURE", 0, "UNSURE", "", "");
    }

    private static Integer integerInRange(String value, int minimum, int maximum) {
        try {
            int parsed = Integer.parseInt(clean(value));
            return parsed >= minimum && parsed <= maximum ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String choice(String value) {
        String normalized = clean(value).toUpperCase(Locale.US);
        return "YES".equals(normalized) || "NO".equals(normalized)
                || "UNSURE".equals(normalized) ? normalized : null;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private MorningSurveyInput() {}
}
