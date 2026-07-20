package com.remote.DreamSync.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public final class PlanCompletionCoreTest {
    @Test public void featureEngineIsDeterministicAndUsesMonotonicWindows() {
        SleepFeatureEngine engine = new SleepFeatureEngine();
        List<SleepFeatureEngine.Sample> motion = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            motion.add(new SleepFeatureEngine.Sample(70_000L + i * 1_000L, 0.05));
        }
        List<SleepFeatureEngine.Sample> heart = List.of(
                new SleepFeatureEngine.Sample(90_000L, 62.0),
                new SleepFeatureEngine.Sample(95_000L, 61.0),
                new SleepFeatureEngine.Sample(100_000L, 60.0));
        List<SleepFeatureEngine.Sample> ibi = List.of(
                new SleepFeatureEngine.Sample(90_000L, 960.0),
                new SleepFeatureEngine.Sample(95_000L, 980.0),
                new SleepFeatureEngine.Sample(100_000L, 970.0));
        List<SleepFeatureEngine.Sample> temperature = List.of(
                new SleepFeatureEngine.Sample(70_000L, 33.0),
                new SleepFeatureEngine.Sample(100_000L, 33.2));

        SleepFeatureEngine.Snapshot first = engine.calculate(
                100_000L, 20.0, motion, heart, ibi, temperature, 66.0);
        SleepFeatureEngine.Snapshot second = engine.calculate(
                100_000L, 20.0, motion, heart, ibi, temperature, 66.0);

        assertEquals(first.motionRms30, second.motionRms30, 0.0);
        assertEquals(31, first.motionSampleCount30);
        assertEquals(3, first.heartSampleCount2);
        assertEquals(3, first.ibiSampleCount5);
        assertEquals(2, first.temperatureSampleCount30);
        assertTrue(first.hasMotion);
        assertTrue(first.hasHeartRate);
        assertFalse(engine.isArousal(first, 66.0));
    }

    @Test public void featureEngineIgnoresHostileSamples() {
        SleepFeatureEngine.Snapshot result = new SleepFeatureEngine().calculate(
                10_000L,
                1.0,
                List.of(new SleepFeatureEngine.Sample(9_000L, Double.NaN)),
                List.of(new SleepFeatureEngine.Sample(9_000L, Double.POSITIVE_INFINITY)),
                List.of(),
                List.of(),
                60.0);
        assertEquals(0, result.motionSampleCount30);
        assertEquals(0, result.heartSampleCount2);
        assertFalse(result.hasMotion);
        assertFalse(result.hasHeartRate);
    }

    @Test public void coordinatorRequiresFreshHandshakeAfterProcessDeath() {
        SessionCoordinator coordinator = new SessionCoordinator();
        coordinator.beginFreshHandshake();
        coordinator.arm();
        coordinator.waitForSleep();
        assertTrue(coordinator.canCue());
        coordinator.recoverAfterProcessDeath();
        assertFalse(coordinator.canCue());
        assertTrue(coordinator.requiresFreshHandshake());
        assertEquals(SessionState.PROCESS_INTERRUPTED, coordinator.state());
        assertThrows(IllegalStateException.class, coordinator::arm);
    }

    @Test public void everyTerminalStateDisablesCues() {
        SessionState[] terminalStates = {
                SessionState.COMPLETE,
                SessionState.USER_STOPPED,
                SessionState.TIMED_OUT,
                SessionState.PHONE_LOST,
                SessionState.SENSOR_FAILED,
                SessionState.LOW_BATTERY,
                SessionState.PROCESS_INTERRUPTED,
                SessionState.INTERNAL_ERROR
        };
        for (SessionState state : terminalStates) {
            SessionCoordinator coordinator = new SessionCoordinator();
            coordinator.beginFreshHandshake();
            coordinator.arm();
            coordinator.terminate(state, state.name());
            assertFalse(state.name(), coordinator.canCue());
        }
    }

    @Test public void cueBudgetChangesOnlyAfterAudioStarted() {
        CuePolicy policy = new CuePolicy();
        assertTrue(policy.canRequestDeep(1, true));
        assertEquals(0, policy.getDeepCues());
        policy.onAudioStarted("DEEP");
        assertEquals(1, policy.getDeepCues());
        assertFalse(policy.canRequestDeep(1, true));
    }

    @Test public void invalidCueRequestsAlwaysFailClosed() {
        CueRequestValidator validator = new CueRequestValidator();
        String[] hostileGains = {"", "NaN", "Infinity", "-1", "0", "0.5", "x"};
        for (String gain : hostileGains) {
            ProtocolEnvelope request = new ProtocolEnvelope(
                    Protocol.VERSION, "message", Protocol.TYPE_CUE_REQUEST,
                    "session", "cue", "watch", "phone", 1_000L, 15_000L,
                    Map.of("cueType", "DEEP", "gain", gain));
            assertFalse(gain, validator.validate(request).valid);
        }
        ProtocolEnvelope missingCueId = new ProtocolEnvelope(
                Protocol.VERSION, "message", Protocol.TYPE_CUE_REQUEST,
                "session", "", "watch", "phone", 1_000L, 15_000L,
                Map.of("cueType", "DEEP", "gain", "0.04"));
        assertFalse(validator.validate(missingCueId).valid);
    }

    @Test public void replayFingerprintIncludesVersionsAndThresholds() {
        List<ReplayHarness.Epoch> epochs = List.of(new ReplayHarness.Epoch(
                30L * 60_000L, 0.05, 58.0, 1.0, 66.0,
                false, true, true, false, true, true,
                35.0, 38.0, 0.15));
        ReplayHarness.ReplayConfig defaults = ReplayHarness.ReplayConfig.defaults();
        List<ReplayHarness.Decision> decisions = ReplayHarness.replay(epochs, defaults);
        String first = ReplayHarness.fingerprint(decisions, defaults);
        String repeated = ReplayHarness.fingerprint(
                ReplayHarness.replay(epochs, defaults), defaults);
        assertEquals(first, repeated);

        ReplayHarness.ReplayConfig changed = new ReplayHarness.ReplayConfig(
                defaults.protocolVersion, defaults.featureVersion,
                defaults.qualityVersion, defaults.eligibilityVersion,
                defaults.waveformVersion, 35.0, defaults.deepEndMin,
                defaults.remStartMin);
        assertNotEquals(first, ReplayHarness.fingerprint(
                ReplayHarness.replay(epochs, changed), changed));
    }
}
