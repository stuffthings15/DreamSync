package com.remote.DreamSync.shared;

/** User-confirmed watch-speaker threshold used by the fail-closed cue gate. */
public final class WatchAudioCalibration {
    public boolean valid(boolean userConfirmed, double audibleThreshold) {
        return userConfirmed && Double.isFinite(audibleThreshold)
                && audibleThreshold >= 0.005 && audibleThreshold <= 0.10;
    }

    public double overnightGain(double audibleThreshold) {
        return Math.min(0.04, Math.max(0.005, audibleThreshold) * 0.50);
    }
}
