package com.remote.DreamSync.shared;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Deterministic CSV replay for exported eligibility epochs. */
public final class ReplayHarness {
    public static final String ENGINE_VERSION = "eligibility-v1";

    public static final class Epoch {
        public final long elapsedMs;
        public final double motionRms;
        public final double heartMedian;
        public final double heartSd;
        public final double baselineHeart;
        public final boolean arousal;
        public final boolean motionReady;
        public final boolean heartReady;

        public Epoch(long elapsedMs, double motionRms, double heartMedian, double heartSd,
                     double baselineHeart, boolean arousal,
                     boolean motionReady, boolean heartReady) {
            this.elapsedMs = elapsedMs;
            this.motionRms = motionRms;
            this.heartMedian = heartMedian;
            this.heartSd = heartSd;
            this.baselineHeart = baselineHeart;
            this.arousal = arousal;
            this.motionReady = motionReady;
            this.heartReady = heartReady;
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
        EligibilityEstimator estimator = new EligibilityEstimator();
        List<Decision> out = new ArrayList<>();
        for (Epoch epoch : epochs) {
            SignalQualityMonitor.Result quality = new SignalQualityMonitor.Result(
                    epoch.motionReady,
                    epoch.heartReady,
                    epoch.motionReady ? (epoch.heartReady ? "good" : "heart unavailable")
                            : "motion unavailable");
            double minutes = epoch.elapsedMs / 60_000.0;
            double earlyPrior = minutes <= 120.0 ? 1.0 : Math.max(0.0, (240.0 - minutes) / 120.0);
            double remPrior = minutes < 180.0 ? 0.0 : Math.min(1.0, (minutes - 180.0) / 120.0);
            EligibilityEstimator.Result result = estimator.estimate(
                    minutes, epoch.motionRms, epoch.heartMedian, epoch.heartSd,
                    epoch.baselineHeart, epoch.arousal, quality,
                    earlyPrior, remPrior, 25, 240, 255);
            out.add(new Decision(epoch.elapsedMs, result,
                    result.state == EligibilityEstimator.State.NREM_ELIGIBLE
                            && quality.deepCueAllowed,
                    result.state == EligibilityEstimator.State.REM_LIKE_ELIGIBLE
                            && quality.lucidCueAllowed));
        }
        return out;
    }

    public static String fingerprint(List<Decision> decisions) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(ENGINE_VERSION.getBytes(StandardCharsets.UTF_8));
            for (Decision decision : decisions) {
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
            System.err.println("elapsedMs,motionRms,heartMedian,heartSd,baselineHeart,arousal,motionReady,heartReady");
            System.exit(2);
        }
        List<Epoch> epochs = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(args[0]))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) { first = false; continue; }
                if (line.isBlank()) continue;
                String[] f = line.split(",", -1);
                if (f.length != 8) throw new IllegalArgumentException("Expected 8 fields");
                epochs.add(new Epoch(Long.parseLong(f[0]), Double.parseDouble(f[1]),
                        Double.parseDouble(f[2]), Double.parseDouble(f[3]),
                        Double.parseDouble(f[4]), Boolean.parseBoolean(f[5]),
                        Boolean.parseBoolean(f[6]), Boolean.parseBoolean(f[7])));
            }
        }
        List<Decision> decisions = replay(epochs);
        for (Decision decision : decisions) System.out.println(decision.canonical());
        System.out.println("sha256=" + fingerprint(decisions));
    }

    private ReplayHarness() {}
}
