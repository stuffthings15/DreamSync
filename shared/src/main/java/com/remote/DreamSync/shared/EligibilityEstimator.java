package com.remote.DreamSync.shared;

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
        return estimate(elapsedSinceOnsetMin, motionRms30, heartMedian2,
                heartSd2, baselineHeartRate, arousal, quality, earlyPrior,
                latePrior, deepStartMin, deepEndMin, remStartMin,
                Double.NaN, Double.NaN, Double.NaN);
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
            double remStartMin,
            double ibiRmssdMs,
            double baselineIbiRmssdMs,
            double skinTemperatureTrendCPerHour
    ) {
        if (!quality.motionReady) {
            return new Result(State.INSUFFICIENT_SIGNAL, 0.0, 0.0, 0.0);
        }
        if (!quality.deepCueAllowed) {
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
        boolean advancedReady = quality.ibiReady
                && quality.temperatureReady
                && Double.isFinite(ibiRmssdMs)
                && Double.isFinite(skinTemperatureTrendCPerHour);
        double ibiStability = advancedReady && Double.isFinite(baselineIbiRmssdMs)
                ? clamp01(1.0 - Math.abs(ibiRmssdMs - baselineIbiRmssdMs)
                / Math.max(10.0, baselineIbiRmssdMs))
                : 0.5;
        double warmingTrend = advancedReady
                ? clamp01((skinTemperatureTrendCPerHour + 0.10) / 0.40)
                : 0.5;
        double nremScore = (advancedReady ? 0.45 : 0.55) * stillness
                + (advancedReady ? 0.20 : 0.25) * heartDrop
                + 0.10 * lowHeartVariation
                + 0.10 * earlyPrior
                + (advancedReady ? 0.10 * ibiStability : 0.0)
                + (advancedReady ? 0.05 * warmingTrend : 0.0);
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
