package com.remote.DreamSync.shared;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure, deterministic feature extraction from monotonic wearable samples. */
public final class SleepFeatureEngine {
    public static final String VERSION = "features-v2";

    public static final class Sample {
        public final long timeMs;
        public final double value;

        public Sample(long timeMs, double value) {
            this.timeMs = timeMs;
            this.value = value;
        }
    }

    public static final class Snapshot {
        public final double elapsedMin;
        public final double motionRms30;
        public final double motionRms10;
        public final double heartMedian2;
        public final double heartSd2;
        public final double heartSlopePerMin;
        public final int motionSampleCount30;
        public final int heartSampleCount2;
        public final int ibiSampleCount5;
        public final int temperatureSampleCount30;
        public final boolean hasHeartRate;
        public final boolean hasMotion;
        public final double baselineHeartRate;
        public final double medianIbiMs;
        public final double ibiRmssdMs;
        public final double medianSkinTemperatureC;
        public final double skinTemperatureTrendCPerHour;

        Snapshot(
                double elapsedMin,
                double motionRms30,
                double motionRms10,
                double heartMedian2,
                double heartSd2,
                double heartSlopePerMin,
                int motionSampleCount30,
                int heartSampleCount2,
                int ibiSampleCount5,
                int temperatureSampleCount30,
                boolean hasHeartRate,
                boolean hasMotion,
                double baselineHeartRate,
                double medianIbiMs,
                double ibiRmssdMs,
                double medianSkinTemperatureC,
                double skinTemperatureTrendCPerHour
        ) {
            this.elapsedMin = elapsedMin;
            this.motionRms30 = motionRms30;
            this.motionRms10 = motionRms10;
            this.heartMedian2 = heartMedian2;
            this.heartSd2 = heartSd2;
            this.heartSlopePerMin = heartSlopePerMin;
            this.motionSampleCount30 = motionSampleCount30;
            this.heartSampleCount2 = heartSampleCount2;
            this.ibiSampleCount5 = ibiSampleCount5;
            this.temperatureSampleCount30 = temperatureSampleCount30;
            this.hasHeartRate = hasHeartRate;
            this.hasMotion = hasMotion;
            this.baselineHeartRate = baselineHeartRate;
            this.medianIbiMs = medianIbiMs;
            this.ibiRmssdMs = ibiRmssdMs;
            this.medianSkinTemperatureC = medianSkinTemperatureC;
            this.skinTemperatureTrendCPerHour = skinTemperatureTrendCPerHour;
        }

        public Snapshot withBaseline(double baseline) {
            return new Snapshot(
                    elapsedMin, motionRms30, motionRms10, heartMedian2, heartSd2,
                    heartSlopePerMin, motionSampleCount30, heartSampleCount2,
                    ibiSampleCount5, temperatureSampleCount30, hasHeartRate, hasMotion,
                    baseline, medianIbiMs, ibiRmssdMs, medianSkinTemperatureC,
                    skinTemperatureTrendCPerHour);
        }
    }

    public static final class NightSummary {
        public final double restingHeartRate;
        public final double medianIbiMs;
        public final double ibiRmssdMs;
        public final double medianSkinTemperatureC;

        NightSummary(double restingHeartRate, double medianIbiMs,
                     double ibiRmssdMs, double medianSkinTemperatureC) {
            this.restingHeartRate = restingHeartRate;
            this.medianIbiMs = medianIbiMs;
            this.ibiRmssdMs = ibiRmssdMs;
            this.medianSkinTemperatureC = medianSkinTemperatureC;
        }
    }

    public Snapshot calculate(
            long nowMs,
            double elapsedMin,
            List<Sample> motionSamples,
            List<Sample> heartSamples,
            List<Sample> ibiSamples,
            List<Sample> temperatureSamples,
            double baselineHeartRate
    ) {
        List<Double> motion30 = valuesSince(motionSamples, nowMs - 30_000L);
        List<Double> motion10 = valuesSince(motionSamples, nowMs - 10_000L);
        List<Sample> heart2 = samplesSince(heartSamples, nowMs - 120_000L);
        List<Sample> ibi5 = samplesSince(ibiSamples, nowMs - 5L * 60_000L);
        List<Sample> temperature30 = samplesSince(
                temperatureSamples, nowMs - 30L * 60_000L);

        List<Double> heartValues = filteredHeartValues(heart2);
        List<Double> ibiValues = values(ibi5);
        List<Double> temperatureValues = values(temperature30);
        double medianHeart = median(heartValues);
        double heartSd = standardDeviation(heartValues, medianHeart);
        double heartSlope = slopePerMinute(heart2);

        return new Snapshot(
                elapsedMin,
                rms(motion30),
                rms(motion10),
                medianHeart,
                heartSd,
                heartSlope,
                motion30.size(),
                heartValues.size(),
                ibiValues.size(),
                temperatureValues.size(),
                heartValues.size() >= 3 && Double.isFinite(medianHeart),
                motion30.size() >= 20,
                baselineHeartRate,
                median(ibiValues),
                rmssd(ibiValues),
                median(temperatureValues),
                slopePerHour(temperature30));
    }

    public double estimateSessionBaseline(
            List<Sample> heartSamples,
            long sessionStartMs,
            long nowMs,
            double elapsedMin,
            Snapshot snapshot
    ) {
        if (elapsedMin < 5.0) return Double.NaN;
        List<Double> calibration = new ArrayList<>();
        for (Sample sample : safe(heartSamples)) {
            if (sample.timeMs >= sessionStartMs && sample.timeMs <= nowMs
                    && validHeart(sample.value)) calibration.add(sample.value);
        }
        if (calibration.size() >= 8) return percentile(calibration, 0.75);
        if (elapsedMin >= 10.0 && snapshot != null && snapshot.hasHeartRate) {
            return snapshot.heartMedian2;
        }
        return Double.NaN;
    }

    public boolean isArousal(Snapshot snapshot, double baselineHeartRate) {
        if (snapshot == null) return false;
        boolean movement = snapshot.hasMotion && snapshot.motionRms10 > 1.20;
        boolean cardiac = snapshot.hasHeartRate
                && Double.isFinite(baselineHeartRate)
                && snapshot.heartMedian2 > baselineHeartRate + 14.0
                && snapshot.heartSlopePerMin > 2.0;
        return movement || cardiac;
    }

    public NightSummary summarizeNight(
            List<Sample> heartSamples,
            List<Sample> ibiSamples,
            List<Sample> temperatureSamples,
            double fallbackHeartRate
    ) {
        List<Double> heart = filteredHeartValues(heartSamples);
        List<Double> ibi = values(ibiSamples);
        List<Double> temperature = values(temperatureSamples);
        return new NightSummary(
                heart.isEmpty() ? fallbackHeartRate : percentile(heart, 0.25),
                median(ibi),
                rmssd(ibi),
                median(temperature));
    }

    private static List<Sample> samplesSince(List<Sample> samples, long cutoffMs) {
        List<Sample> output = new ArrayList<>();
        for (Sample sample : safe(samples)) {
            if (sample.timeMs >= cutoffMs && Double.isFinite(sample.value)) output.add(sample);
        }
        return output;
    }

    private static List<Double> valuesSince(List<Sample> samples, long cutoffMs) {
        return values(samplesSince(samples, cutoffMs));
    }

    private static List<Double> filteredHeartValues(List<Sample> samples) {
        List<Double> output = new ArrayList<>();
        for (Sample sample : safe(samples)) {
            if (validHeart(sample.value)) output.add(sample.value);
        }
        return output;
    }

    private static List<Double> values(List<Sample> samples) {
        List<Double> output = new ArrayList<>();
        for (Sample sample : safe(samples)) {
            if (Double.isFinite(sample.value)) output.add(sample.value);
        }
        return output;
    }

    private static List<Sample> safe(List<Sample> samples) {
        return samples == null ? Collections.emptyList() : samples;
    }

    private static boolean validHeart(double value) {
        return Double.isFinite(value) && value >= 30.0 && value <= 220.0;
    }

    private static double rms(List<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        double sum = 0.0;
        for (double value : values) sum += value * value;
        return Math.sqrt(sum / values.size());
    }

    private static double rmssd(List<Double> values) {
        if (values.size() < 3) return Double.NaN;
        double sum = 0.0;
        for (int i = 1; i < values.size(); i++) {
            double delta = values.get(i) - values.get(i - 1);
            sum += delta * delta;
        }
        return Math.sqrt(sum / (values.size() - 1));
    }

    private static double slopePerMinute(List<Sample> values) {
        if (values.size() < 2) return 0.0;
        Sample first = values.get(0);
        Sample last = values.get(values.size() - 1);
        double minutes = Math.max(0.1, (last.timeMs - first.timeMs) / 60_000.0);
        return (last.value - first.value) / minutes;
    }

    private static double slopePerHour(List<Sample> values) {
        if (values.size() < 2) return Double.NaN;
        Sample first = values.get(0);
        Sample last = values.get(values.size() - 1);
        double hours = (last.timeMs - first.timeMs) / 3_600_000.0;
        return hours <= 0.0 ? Double.NaN : (last.value - first.value) / hours;
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) return sorted.get(middle);
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    private static double percentile(List<Double> values, double p) {
        if (values.isEmpty()) return Double.NaN;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        double index = Math.max(0.0, Math.min(1.0, p)) * (sorted.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) return sorted.get(lower);
        double weight = index - lower;
        return sorted.get(lower) * (1.0 - weight) + sorted.get(upper) * weight;
    }

    private static double standardDeviation(List<Double> values, double center) {
        if (values.size() < 2 || !Double.isFinite(center)) return Double.NaN;
        double sum = 0.0;
        for (double value : values) {
            double delta = value - center;
            sum += delta * delta;
        }
        return Math.sqrt(sum / values.size());
    }
}
