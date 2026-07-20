package com.remote.DreamSync.shared;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Deterministic CSV replay for exported eligibility epochs. */
public final class ReplayHarness {
    public static final String ENGINE_VERSION = "replay-v2";

    public static final class ReplayConfig {
        public final int protocolVersion;
        public final String featureVersion;
        public final String qualityVersion;
        public final String eligibilityVersion;
        public final String waveformVersion;
        public final double deepStartMin;
        public final double deepEndMin;
        public final double remStartMin;

        public ReplayConfig(
                int protocolVersion,
                String featureVersion,
                String qualityVersion,
                String eligibilityVersion,
                String waveformVersion,
                double deepStartMin,
                double deepEndMin,
                double remStartMin
        ) {
            this.protocolVersion = protocolVersion;
            this.featureVersion = safe(featureVersion);
            this.qualityVersion = safe(qualityVersion);
            this.eligibilityVersion = safe(eligibilityVersion);
            this.waveformVersion = safe(waveformVersion);
            this.deepStartMin = deepStartMin;
            this.deepEndMin = deepEndMin;
            this.remStartMin = remStartMin;
        }

        public static ReplayConfig defaults() {
            return new ReplayConfig(
                    Protocol.VERSION,
                    SleepFeatureEngine.VERSION,
                    SignalQualityMonitor.VERSION,
                    EligibilityEstimator.VERSION,
                    CueWaveforms.VERSION,
                    25.0,
                    145.0,
                    255.0);
        }

        String canonical() {
            return String.format(Locale.US,
                    "%s|p=%d|f=%s|q=%s|e=%s|w=%s|deep=%.3f:%.3f|rem=%.3f",
                    ENGINE_VERSION, protocolVersion, featureVersion, qualityVersion,
                    eligibilityVersion, waveformVersion, deepStartMin, deepEndMin,
                    remStartMin);
        }
    }

    public static final class Epoch {
        public final long elapsedMs;
        public final double motionRms;
        public final double heartMedian;
        public final double heartSd;
        public final double baselineHeart;
        public final boolean arousal;
        public final boolean motionReady;
        public final boolean heartReady;
        public final boolean offWrist;
        public final boolean ibiReady;
        public final boolean temperatureReady;
        public final double ibiRmssdMs;
        public final double baselineIbiRmssdMs;
        public final double skinTemperatureTrendCPerHour;

        public Epoch(
                long elapsedMs,
                double motionRms,
                double heartMedian,
                double heartSd,
                double baselineHeart,
                boolean arousal,
                boolean motionReady,
                boolean heartReady
        ) {
            this(elapsedMs, motionRms, heartMedian, heartSd, baselineHeart,
                    arousal, motionReady, heartReady, false, false, false,
                    Double.NaN, Double.NaN, Double.NaN);
        }

        public Epoch(
                long elapsedMs,
                double motionRms,
                double heartMedian,
                double heartSd,
                double baselineHeart,
                boolean arousal,
                boolean motionReady,
                boolean heartReady,
                boolean offWrist,
                boolean ibiReady,
                boolean temperatureReady,
                double ibiRmssdMs,
                double baselineIbiRmssdMs,
                double skinTemperatureTrendCPerHour
        ) {
            this.elapsedMs = elapsedMs;
            this.motionRms = motionRms;
            this.heartMedian = heartMedian;
            this.heartSd = heartSd;
            this.baselineHeart = baselineHeart;
            this.arousal = arousal;
            this.motionReady = motionReady;
            this.heartReady = heartReady;
            this.offWrist = offWrist;
            this.ibiReady = ibiReady;
            this.temperatureReady = temperatureReady;
            this.ibiRmssdMs = ibiRmssdMs;
            this.baselineIbiRmssdMs = baselineIbiRmssdMs;
            this.skinTemperatureTrendCPerHour = skinTemperatureTrendCPerHour;
        }
    }

    public static final class Decision {
        public final long elapsedMs;
        public final EligibilityEstimator.State state;
        public final double nremScore;
        public final double remScore;
        public final boolean deepAllowed;
        public final boolean lucidAllowed;

        Decision(long elapsedMs, EligibilityEstimator.Result result,
                 boolean deepAllowed, boolean lucidAllowed) {
            this.elapsedMs = elapsedMs;
            this.state = result.state;
            this.nremScore = result.nremScore;
            this.remScore = result.remScore;
            this.deepAllowed = deepAllowed;
            this.lucidAllowed = lucidAllowed;
        }

        String canonical() {
            return String.format(Locale.US, "%d|%s|%.8f|%.8f|%s|%s",
                    elapsedMs, state, nremScore, remScore, deepAllowed, lucidAllowed);
        }
    }

    public static List<Decision> replay(List<Epoch> epochs) {
        return replay(epochs, ReplayConfig.defaults());
    }

    public static List<Decision> replay(List<Epoch> epochs, ReplayConfig config) {
        validateConfig(config);
        EligibilityEstimator estimator = new EligibilityEstimator();
        List<Decision> output = new ArrayList<>();
        for (Epoch epoch : epochs == null ? List.<Epoch>of() : epochs) {
            SignalQualityMonitor.Result quality = new SignalQualityMonitor.Result(
                    epoch.motionReady,
                    epoch.heartReady,
                    epoch.offWrist,
                    epoch.ibiReady,
                    epoch.temperatureReady,
                    qualityReason(epoch));
            double minutes = epoch.elapsedMs / 60_000.0;
            double earlyPrior = minutes <= 120.0
                    ? 1.0 : Math.max(0.0, (240.0 - minutes) / 120.0);
            double remPrior = minutes < 180.0
                    ? 0.0 : Math.min(1.0, (minutes - 180.0) / 120.0);
            EligibilityEstimator.Result result = estimator.estimate(
                    minutes,
                    epoch.motionRms,
                    epoch.heartMedian,
                    epoch.heartSd,
                    epoch.baselineHeart,
                    epoch.arousal,
                    quality,
                    earlyPrior,
                    remPrior,
                    config.deepStartMin,
                    config.deepEndMin,
                    config.remStartMin,
                    epoch.ibiRmssdMs,
                    epoch.baselineIbiRmssdMs,
                    epoch.skinTemperatureTrendCPerHour);
            output.add(new Decision(
                    epoch.elapsedMs,
                    result,
                    result.state == EligibilityEstimator.State.NREM_ELIGIBLE
                            && quality.deepCueAllowed,
                    result.state == EligibilityEstimator.State.REM_LIKE_ELIGIBLE
                            && quality.lucidCueAllowed));
        }
        return output;
    }

    public static String fingerprint(List<Decision> decisions) {
        return fingerprint(decisions, ReplayConfig.defaults());
    }

    public static String fingerprint(List<Decision> decisions, ReplayConfig config) {
        validateConfig(config);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(config.canonical().getBytes(StandardCharsets.UTF_8));
            for (Decision decision : decisions == null
                    ? List.<Decision>of() : decisions) {
                digest.update((byte) '\n');
                digest.update(decision.canonical().getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: ReplayHarness <epochs.csv>");
            System.err.println("Supports the legacy 8-field or current 14-field export.");
            System.exit(2);
        }
        List<Epoch> epochs = readEpochs(Path.of(args[0]));
        ReplayConfig config = ReplayConfig.defaults();
        List<Decision> decisions = replay(epochs, config);
        System.out.println("config=" + config.canonical());
        for (Decision decision : decisions) System.out.println(decision.canonical());
        System.out.println("sha256=" + fingerprint(decisions, config));
    }

    static List<Epoch> readEpochs(Path path) throws Exception {
        List<Epoch> epochs = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) { first = false; continue; }
                if (line.isBlank()) continue;
                String[] fields = line.split(",", -1);
                if (fields.length == 8) {
                    epochs.add(new Epoch(
                            Long.parseLong(fields[0]), Double.parseDouble(fields[1]),
                            Double.parseDouble(fields[2]), Double.parseDouble(fields[3]),
                            Double.parseDouble(fields[4]), Boolean.parseBoolean(fields[5]),
                            Boolean.parseBoolean(fields[6]), Boolean.parseBoolean(fields[7])));
                } else if (fields.length == 14) {
                    epochs.add(new Epoch(
                            Long.parseLong(fields[0]), Double.parseDouble(fields[1]),
                            Double.parseDouble(fields[2]), Double.parseDouble(fields[3]),
                            Double.parseDouble(fields[4]), Boolean.parseBoolean(fields[5]),
                            Boolean.parseBoolean(fields[6]), Boolean.parseBoolean(fields[7]),
                            Boolean.parseBoolean(fields[8]), Boolean.parseBoolean(fields[9]),
                            Boolean.parseBoolean(fields[10]), Double.parseDouble(fields[11]),
                            Double.parseDouble(fields[12]), Double.parseDouble(fields[13])));
                } else {
                    throw new IllegalArgumentException("Expected 8 or 14 fields");
                }
            }
        }
        return epochs;
    }

    private static String qualityReason(Epoch epoch) {
        if (epoch.offWrist) return "watch is off wrist";
        if (!epoch.motionReady) return "motion unavailable";
        if (!epoch.heartReady) return "heart unavailable";
        return "signal ready";
    }

    private static void validateConfig(ReplayConfig config) {
        if (config == null
                || config.protocolVersion <= 0
                || config.featureVersion.isBlank()
                || config.qualityVersion.isBlank()
                || config.eligibilityVersion.isBlank()
                || config.waveformVersion.isBlank()
                || !Double.isFinite(config.deepStartMin)
                || !Double.isFinite(config.deepEndMin)
                || !Double.isFinite(config.remStartMin)
                || config.deepStartMin < 0.0
                || config.deepEndMin <= config.deepStartMin
                || config.remStartMin <= config.deepEndMin) {
            throw new IllegalArgumentException("Invalid replay configuration");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private ReplayHarness() {}
}
