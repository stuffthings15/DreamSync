package com.remote.DreamSync.shared;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ResearchPlanCoreTest {
    @Test public void baselineUsesSevenLatestValidNights() {
        List<PersonalBaseline.Night> nights = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nights.add(new PersonalBaseline.Night(60 + i, 900 + i,
                    40 + i, 33 + i * 0.1, 20 + i));
        }
        PersonalBaseline.Result result = new PersonalBaseline().calculate(nights);
        assertEquals(7, result.nights);
        assertEquals(63.0, result.restingHeartRate, 0.001);
        assertTrue(result.personalized());
        assertEquals(1.0, result.confidence, 0.001);
    }

    @Test public void baselineRemainsConservativeBeforeSevenNights() {
        PersonalBaseline.Result result = new PersonalBaseline().calculate(List.of(
                new PersonalBaseline.Night(61, Double.NaN, Double.NaN, 33.1, 24),
                new PersonalBaseline.Night(59, Double.NaN, Double.NaN, 33.3, 20)
        ));
        assertEquals(2, result.nights);
        assertFalse(result.personalized());
        assertEquals(60.0, result.restingHeartRate, 0.001);
    }

    @Test public void phaseMachineRequiresStabilityAndRecovery() {
        SleepPhaseMachine machine = new SleepPhaseMachine(10_000L, 5_000L);
        assertEquals(SleepPhaseMachine.Phase.PREPARING_FOR_SLEEP,
                machine.update(1_000L, false, false, false, true));
        assertEquals(SleepPhaseMachine.Phase.LIKELY_ASLEEP,
                machine.update(2_000L, true, true, false, true));
        assertEquals(SleepPhaseMachine.Phase.STABLE_SLEEP,
                machine.update(12_000L, true, true, false, true));
        assertEquals(SleepPhaseMachine.Phase.POSSIBLE_AROUSAL,
                machine.update(13_000L, true, false, true, true));
        assertEquals(SleepPhaseMachine.Phase.RECOVERED,
                machine.update(14_000L, true, true, false, true));
        assertEquals(SleepPhaseMachine.Phase.STABLE_SLEEP,
                machine.update(19_000L, true, true, false, true));
    }

    @Test public void continuityCountsOnlySustainedAwakenings() {
        SleepContinuityTracker tracker = new SleepContinuityTracker(60_000L);
        tracker.onSleepOnset(1_000L);
        tracker.update(101_000L, true);
        tracker.update(140_000L, false);
        assertEquals(0, tracker.snapshot(140_000L).awakeningCount);
        tracker.update(200_000L, true);
        tracker.update(261_000L, true);
        tracker.update(280_000L, false);
        SleepContinuityTracker.Snapshot result = tracker.snapshot(400_000L);
        assertEquals(1, result.awakeningCount);
        assertEquals(80_000L, result.wakeAfterSleepOnsetMs);
        assertEquals(199_000L, result.longestUninterruptedBoutMs);
    }

    @Test public void compatibilityFailsClosedUntilRichSelfTestPasses() {
        DeviceCompatibility checker = new DeviceCompatibility();
        DeviceCompatibility.Capabilities basic = new DeviceCompatibility.Capabilities(
                "Samsung", "Galaxy Watch Ultra", true, true, true,
                true, true, true, true, true, false);
        assertEquals(DeviceCompatibility.Status.MONITORING_ONLY,
                checker.classify(basic));
        DeviceCompatibility.Capabilities full = new DeviceCompatibility.Capabilities(
                "Samsung", "Galaxy Watch Ultra", true, true, true,
                true, true, true, true, true, true);
        assertEquals(DeviceCompatibility.Status.ENHANCED_PROTECTION,
                checker.classify(full));
        assertEquals("ENHANCED_PROTECTION", checker.featureProfile(full));
        assertTrue(checker.shouldUseRawPpg(full));
    }

    @Test public void fullFeaturesDependOnSensorsNotWatchBrand() {
        DeviceCompatibility checker = new DeviceCompatibility();
        DeviceCompatibility.Capabilities capable = new DeviceCompatibility.Capabilities(
                "Any Wear OS maker", "Future high-end watch", true, true, true,
                false, true, true, true, true, true);
        assertEquals(DeviceCompatibility.Status.ENHANCED_PROTECTION,
                checker.classify(capable));

        DeviceCompatibility.Capabilities core = new DeviceCompatibility.Capabilities(
                "Any Wear OS maker", "Core watch", true, true, true,
                false, false, false, false, false, true);
        assertEquals(DeviceCompatibility.Status.CORE_PROTECTION,
                checker.classify(core));
        assertEquals("CORE_PROTECTION", checker.featureProfile(core));
        assertFalse(checker.shouldUseRawPpg(core));
    }

    @Test public void offWristAlwaysBlocksCueEligibility() {
        SignalQualityMonitor.Result result = new SignalQualityMonitor().evaluate(
                200_000L,
                199_000L,
                190_000L,
                30,
                5,
                true,
                true,
                true,
                195_000L,
                5,
                180_000L,
                3);
        assertTrue(result.offWrist);
        assertFalse(result.deepCueAllowed);
        assertFalse(result.lucidCueAllowed);
        assertEquals("watch is off wrist", result.reason);
    }

    @Test public void continuityIncludesOngoingSustainedWake() {
        SleepContinuityTracker tracker = new SleepContinuityTracker(60_000L);
        tracker.onSleepOnset(1_000L);
        tracker.update(100_000L, true);
        tracker.update(161_000L, true);
        SleepContinuityTracker.Snapshot result = tracker.snapshot(200_000L);
        assertEquals(1, result.awakeningCount);
        assertEquals(100_000L, result.wakeAfterSleepOnsetMs);
    }
}
