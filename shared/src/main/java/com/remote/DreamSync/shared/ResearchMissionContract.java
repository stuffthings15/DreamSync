package com.remote.DreamSync.shared;

/**
 * Research-conservative limits for protecting sleep continuity.
 *
 * <p>These values keep the live phone/watch pipeline aligned with the supplied
 * study protocol while acknowledging that wrist sensors cannot reproduce EEG
 * phase locking.</p>
 */
public final class ResearchMissionContract {
    public static final int SLEEP_ONSET_QUIET_MIN = 10;
    public static final int NREM_WINDOW_START_MIN = 25;
    public static final int NREM_WINDOW_END_MIN = 145;
    public static final int NREM_STABLE_MIN = 10;
    public static final int CUE_INTERVAL_SEC = 300;
    public static final int FIRST_ELIGIBLE_NIGHT_PAIRS = 1;
    public static final long POST_CUE_OBSERVATION_MS = 5L * 60_000L;
    public static final long AROUSAL_QUIET_MS = 20L * 60_000L;
    public static final double MAX_AWAKE_TEST_GAIN = 0.10;
    public static final double MAX_INITIAL_OVERNIGHT_GAIN = 0.04;

    /** Vibration is an awake setup signal only; it is never a sleep cue. */
    public static final boolean SLEEP_HAPTICS_ALLOWED = false;

    private ResearchMissionContract() {}

    public static boolean researchWaveformMatches() {
        return CueWaveforms.DEEP_BURST_MS == 50
                && CueWaveforms.DEEP_PAIR_INTERVAL_MS == 1_075;
    }
}
