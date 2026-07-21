package com.remote.DreamSync;

import android.Manifest;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SleepSessionServiceSensorPolicyTest {
    @Test
    public void samsungPpgPermissionFollowsAndroidVersion() {
        assertEquals(Manifest.permission.BODY_SENSORS,
                SleepSessionService.samsungPpgPermissionForSdk(35));
        assertEquals(
                "com.samsung.android.hardware.sensormanager.permission."
                        + "READ_ADDITIONAL_HEALTH_DATA",
                SleepSessionService.samsungPpgPermissionForSdk(36));
    }

    @Test
    public void samsungMotionRequiresSamplesAndTimeBeforeSwitching() {
        assertFalse(SleepSessionService.isStableSamsungMotion(
                SleepSessionService.SAMSUNG_MOTION_STABLE_SAMPLES - 1,
                SleepSessionService.SAMSUNG_MOTION_STABLE_WINDOW_MS));
        assertFalse(SleepSessionService.isStableSamsungMotion(
                SleepSessionService.SAMSUNG_MOTION_STABLE_SAMPLES,
                SleepSessionService.SAMSUNG_MOTION_STABLE_WINDOW_MS - 1));
        assertTrue(SleepSessionService.isStableSamsungMotion(
                SleepSessionService.SAMSUNG_MOTION_STABLE_SAMPLES,
                SleepSessionService.SAMSUNG_MOTION_STABLE_WINDOW_MS));
    }
}
