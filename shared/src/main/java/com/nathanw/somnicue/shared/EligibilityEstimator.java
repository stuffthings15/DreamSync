package com.nathanw.somnicue.shared;

public final class EligibilityEstimator {
    public enum State {
        SETTLING,
        AWAKE_OR_MOVING,
        QUIET_UNCERTAIN,
        NREM_ELIGIBLE,
        REM_LIKE_ELIGIBLE,
        AROUSAL,
        INSUFFICIENT_SIGNAL
    }

    public static final class Result {
        public final State state;
        public final double nremScore;
        public final double remScore;
        public final double stillness;

        Result(State state, double nremScore, double remScore, double stillness) {
            this.state = state;
            this.nremScore = nremScore;
            this.remScore = remScore;
            this.stillness = stillness;
        }
    }

    public Result estimate(
            double elapsedSinceOnsetMin,
            double motionRms30,
            double heartMedian2,
            double heartSd2,
            double baselineHeartRate,
            boolean arousal,
            SignalQualityMonitor.Result quality,
            double earlyPrior,
            double latePrior,
            double deepStartMin,
            double deepEndMin,
            double remStartMin
    ) {
        if (!quality.motionReady) {
            return new Result(State.INSUFFICIENT_SIGNAL, 0.0, 0.0, 0.0);
        }
        double stillness = clamp01(1.0 - motionRms30 / 0.65);
        double heartDrop = quality.heartReady && !Double.isNaN(baselineHeartRate)
                ? clamp01((baselineHeartRate - heartMedian2)
                / Math.max(1.0, baselineHeartRate * 0.12))
                : 0.40;
        double lowHeartVariation = quality.heartReady
                ? clamp01(1.0 - heartSd2 / 4.5)
                : 0.40;
        double heartIrregularity = quality.heartReady
                ? clamp01((heartSd2 - 1.5) / 4.0)
                : 0.0;
        double nremScore = 0.55 * stillness
                + 0.25 * heartDrop
                + 0.10 * lowHeartVariation
                + 0.10 * earlyPrior;
        double remScore = 0.55 * stillness
                + 0.20 * heartIrregularity
                + 0.25 * latePrior;

        State state;
        if (arousal) state = State.AROUSAL;
        else if (elapsedSinceOnsetMin < 0.0) state = State.SETTLING;
        else if (elapsedSinceOnsetMin >= deepStartMin
                && elapsedSinceOnsetMin <= deepEndMin
                && stillness >= 0.72
                && nremScore >= 0.72) state = State.NREM_ELIGIBLE;
        else if (elapsedSinceOnsetMin >= remStartMin
                && quality.lucidCueAllowed
                && stillness >= 0.68
                && remScore >= 0.68
                && heartIrregularity >= 0.20) state = State.REM_LIKE_ELIGIBLE;
        else if (stillness >= 0.62) state = State.QUIET_UNCERTAIN;
        else state = State.AWAKE_OR_MOVING;
        return new Result(state, nremScore, remScore, stillness);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
