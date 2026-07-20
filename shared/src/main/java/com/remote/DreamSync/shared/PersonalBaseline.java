package com.remote.DreamSync.shared;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Builds a conservative rolling baseline from at most the seven latest valid nights. */
public final class PersonalBaseline {
    public static final int TARGET_NIGHTS = 7;

    public static final class Night {
        public final double restingHeartRate;
        public final double medianIbiMs;
        public final double ibiRmssdMs;
        public final double skinTemperatureC;
        public final double sleepOnsetMinutes;

        public Night(
                double restingHeartRate,
                double medianIbiMs,
                double ibiRmssdMs,
                double skinTemperatureC,
                double sleepOnsetMinutes
        ) {
            this.restingHeartRate = restingHeartRate;
            this.medianIbiMs = medianIbiMs;
            this.ibiRmssdMs = ibiRmssdMs;
            this.skinTemperatureC = skinTemperatureC;
            this.sleepOnsetMinutes = sleepOnsetMinutes;
        }
    }

    public static final class Result {
        public final int nights;
        public final double restingHeartRate;
        public final double medianIbiMs;
        public final double ibiRmssdMs;
        public final double skinTemperatureC;
        public final double sleepOnsetMinutes;
        public final double confidence;

        Result(
                int nights,
                double restingHeartRate,
                double medianIbiMs,
                double ibiRmssdMs,
                double skinTemperatureC,
                double sleepOnsetMinutes
        ) {
            this.nights = nights;
            this.restingHeartRate = restingHeartRate;
            this.medianIbiMs = medianIbiMs;
            this.ibiRmssdMs = ibiRmssdMs;
            this.skinTemperatureC = skinTemperatureC;
            this.sleepOnsetMinutes = sleepOnsetMinutes;
            this.confidence = Math.min(1.0, nights / (double) TARGET_NIGHTS);
        }

        public boolean personalized() {
            return nights >= TARGET_NIGHTS;
        }
    }

    public Result calculate(List<Night> latestFirst) {
        if (latestFirst == null || latestFirst.isEmpty()) {
            return new Result(0, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN);
        }
        List<Night> selected = new ArrayList<>();
        for (Night night : latestFirst) {
            if (night == null || !hasAnyValidValue(night)) continue;
            selected.add(night);
            if (selected.size() == TARGET_NIGHTS) break;
        }
        return new Result(
                selected.size(),
                median(values(selected, Field.HEART_RATE)),
                median(values(selected, Field.IBI)),
                median(values(selected, Field.RMSSD)),
                median(values(selected, Field.TEMPERATURE)),
                median(values(selected, Field.ONSET))
        );
    }

    private enum Field { HEART_RATE, IBI, RMSSD, TEMPERATURE, ONSET }

    private static List<Double> values(List<Night> nights, Field field) {
        List<Double> values = new ArrayList<>();
        for (Night night : nights) {
            double value;
            switch (field) {
                case HEART_RATE: value = night.restingHeartRate; break;
                case IBI: value = night.medianIbiMs; break;
                case RMSSD: value = night.ibiRmssdMs; break;
                case TEMPERATURE: value = night.skinTemperatureC; break;
                default: value = night.sleepOnsetMinutes;
            }
            if (valid(value)) values.add(value);
        }
        return values;
    }

    private static boolean hasAnyValidValue(Night night) {
        return valid(night.restingHeartRate)
                || valid(night.medianIbiMs)
                || valid(night.ibiRmssdMs)
                || valid(night.skinTemperatureC)
                || valid(night.sleepOnsetMinutes);
    }

    private static boolean valid(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        Collections.sort(values);
        int middle = values.size() / 2;
        if ((values.size() & 1) == 1) return values.get(middle);
        return (values.get(middle - 1) + values.get(middle)) / 2.0;
    }
}
