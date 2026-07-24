package com.remote.DreamSync;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.view.View;
import android.widget.ScrollView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class PhoneWorkflowInstrumentationTest {
    @Test public void manifestDeclaresRequiredCompanionAndAudioPermissions() throws Exception {
        String packageName = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getPackageName();
        PackageInfo info = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getPackageManager()
                .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS);
        assertNotNull(info.requestedPermissions);
        assertTrue(Arrays.asList(info.requestedPermissions).contains(
                Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK));
        assertTrue(Arrays.asList(info.requestedPermissions).contains(
                Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE));
    }

    @Test public void largeTextWorkflowRemainsScrollable() {
        InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSharedPreferences("dreamsync_phone", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("auto_enabled", false)
                .putBoolean("watch_auto_confirmed", false).apply();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                View content = activity.findViewById(android.R.id.content);
                assertNotNull(content);
                assertTrue(containsScrollView(content));
                assertNotNull(activity.findViewById(R.id.powerButton));
                android.widget.TextView powerState = activity.findViewById(R.id.powerStateText);
                assertNotNull(powerState);
                assertTrue(powerState.getText().toString().contains("OFF"));
                assertNotNull(activity.findViewById(R.id.systemCheckButton));
                assertNotNull(activity.findViewById(R.id.exportButton));
                assertNotNull(activity.findViewById(R.id.exportDebugButton));
                assertNotNull(activity.findViewById(R.id.deleteDataButton));
                assertNotNull(activity.findViewById(R.id.openWatchButton));
                assertNotNull(activity.findViewById(R.id.previewDeepButton));
                assertNotNull(activity.findViewById(R.id.practiceLucidButton));
                assertNotNull(activity.findViewById(R.id.morningButton));
                assertNotNull(activity.findViewById(R.id.historyButton));
                assertNotNull(activity.findViewById(R.id.scienceButton));
                assertNotNull(activity.findViewById(R.id.sleepProtectionButton));
                assertNotNull(activity.findViewById(R.id.compatibilityButton));
                assertNotNull(activity.findViewById(R.id.privacyButton));
                assertNotNull(activity.findViewById(R.id.modeDeep));
                assertNotNull(activity.findViewById(R.id.modeLucid));
                assertNotNull(activity.findViewById(R.id.modeCombined));
                assertNotNull(activity.findViewById(R.id.gainSeek));
                String visibleText = allText(content);
                assertTrue(visibleText.contains("Dreams Redefined"));
                assertFalse(visibleText.toLowerCase().contains("somnicue"));
                assertFalse(visibleText.toLowerCase().contains("nathan"));
                assertFalse(visibleText.toLowerCase().contains("sham"));
            });
        }
    }

    @Test public void pendingWatchSetupIsNeverShownAsFullyOn() {
        android.content.Context context = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        context.getSharedPreferences("dreamsync_phone", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("auto_enabled", true)
                .putBoolean("watch_auto_confirmed", false)
                .putString("pending_enable_generation", "test-generation")
                .putString("pending_enable_enrollment", "test-enrollment")
                .apply();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                android.widget.TextView state = activity.findViewById(R.id.powerStateText);
                assertNotNull(state);
                assertTrue(state.getText().toString().contains("WAITING FOR WATCH"));
                assertFalse(state.getText().toString().contains("MONITORING EVERY NIGHT"));
            });
        } finally {
            context.getSharedPreferences("dreamsync_phone", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("auto_enabled", false)
                    .putBoolean("watch_auto_confirmed", false)
                    .remove("pending_enable_generation")
                    .remove("pending_enable_enrollment")
                    .remove("watch_setup_retry_attempt")
                    .apply();
        }
    }

    @Test public void debugExportContainsActionableDeviceStateWithoutNodeIds() {
        android.content.Context context = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        DebugLog.append(context, "TEST_EVENT", "sessionId=sensitive-node-value");

        String debug = DebugLog.export(context, new AudioOutput(context));

        assertTrue(debug.contains("DreamSync phone debug log"));
        assertTrue(debug.contains("appVersion="));
        assertTrue(debug.contains("audio=mediaVolume="));
        assertTrue(debug.contains("awakeSetupHaptic=ONE_SHORT_CONFIRMATION"));
        assertTrue(debug.contains("watchBound="));
        assertTrue(debug.contains("TEST_EVENT"));
        assertFalse(debug.contains("sensitive-node-value"));
    }

    @Test public void morningCheckInAlwaysOpensEvenWithoutCompletedNight() {
        android.content.Context context = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        context.getSharedPreferences("dreamsync_phone", android.content.Context.MODE_PRIVATE)
                .edit().remove("last_summary_session").apply();

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                activity.findViewById(R.id.morningButton).performClick();
                assertTrue(activity.isMorningSurveyShowingForTest());
                assertTrue(activity.morningSurveySessionIdForTest().startsWith("manual-"));
                activity.dismissMorningSurveyForTest();
                assertFalse(activity.isMorningSurveyShowingForTest());
            });
        }
    }

    @Test public void dataExportPreservesSessionWhenStoredJsonIsMalformed() throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        PhoneDao dao = PhoneDatabase.get(context).dao();
        dao.deleteAllSurveys();
        dao.deleteAllEvents();
        dao.deleteAllSessions();
        SessionEntity session = new SessionEntity();
        session.sessionId = "malformed-export-session";
        session.configurationJson = "{bad";
        session.summaryJson = "not-json";
        session.watchCapabilityJson = "[wrong-shape]";
        dao.upsertSession(session);

        AtomicReference<MainActivity> activity = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity::set);
            org.json.JSONObject export = activity.get().buildExportJson();
            assertEquals(3, export.getInt("schemaVersion"));
            org.json.JSONObject exportedSession = export.getJSONArray("sessions").getJSONObject(0);
            assertTrue(exportedSession.getJSONObject("configuration").getBoolean("parseError"));
            assertTrue(exportedSession.getJSONObject("summary").getBoolean("parseError"));
            assertTrue(exportedSession.getJSONObject("watchCapabilities").getBoolean("parseError"));
        } finally {
            dao.deleteAllSessions();
        }
    }

    @Test public void completedWatchCheckIsRestoredWhenPhoneReturns() {
        android.content.Context context = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        long completedUtcMs = System.currentTimeMillis();
        context.getSharedPreferences("dreamsync_phone", android.content.Context.MODE_PRIVATE)
                .edit()
                .putLong("last_system_check_utc", completedUtcMs)
                .putLong("last_system_check_displayed_utc", 0L)
                .putString("last_system_check_status", "FAIL")
                .putString("last_system_check_reason", "Heart-rate permission is missing.")
                .putInt("last_system_check_motion_samples", 20)
                .putInt("last_system_check_heart_samples", 0)
                .putBoolean("last_system_check_motion_registered", true)
                .putBoolean("last_system_check_heart_registered", false)
                .putBoolean("last_system_check_watch_audio_started", true)
                .putBoolean("last_system_check_watch_audio_completed", true)
                .putString("last_system_check_watch_audio_reason", "completed")
                .apply();

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                android.widget.TextView status = activity.findViewById(R.id.statusText);
                assertTrue(status.getText().toString().contains("Watch system check FAIL"));
                assertTrue(status.getText().toString().contains("Heart-rate permission"));
                assertTrue(activity.isSystemCheckReportShowingForTest());
                assertEquals(0L, context.getSharedPreferences(
                        "dreamsync_phone", android.content.Context.MODE_PRIVATE)
                        .getLong("last_system_check_displayed_utc", 0L));
                activity.acknowledgeSystemCheckReportForTest();
                assertEquals(completedUtcMs, context.getSharedPreferences(
                        "dreamsync_phone", android.content.Context.MODE_PRIVATE)
                        .getLong("last_system_check_displayed_utc", 0L));
                assertTrue(!activity.isSystemCheckReportShowingForTest());
            });
        }
    }

    @Test public void sessionConfigCarriesWatchVolumeCalibration() throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        context.getSharedPreferences("dreamsync_phone", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("watch_audio_calibrated", true)
                .putFloat("watch_audible_threshold", 0.04f)
                .putInt("watch_calibration_media_volume", 4)
                .putInt("watch_calibration_media_volume_max", 10)
                .apply();

        org.json.JSONObject config = PhoneAutoControl.sessionConfig(
                context, "session-volume", "phone", "watch", 1000L);

        assertTrue(config.getBoolean("watchAudioCalibrated"));
        assertEquals(0.04, config.getDouble("watchAudibleThreshold"), 0.0001);
        assertEquals(4, config.getInt("watchMediaVolume"));
        assertEquals(10, config.getInt("watchMediaVolumeMax"));
    }

    @Test public void compatibilitySeparatesDetectedHardwareFromVerifiedReadings() {
        android.content.Context context = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        context.getSharedPreferences("dreamsync_phone", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("watch_capabilities", "{\"manufacturer\":\"samsung\","
                        + "\"model\":\"SM-L705U\",\"profile\":\"NEEDS_ATTENTION\","
                        + "\"motion\":false,\"standardHeartRate\":false,"
                        + "\"samsungSdkPackaged\":false}")
                .putBoolean("last_system_check_motion_registered", true)
                .putBoolean("last_system_check_heart_registered", true)
                .putInt("last_system_check_motion_samples", 0)
                .putInt("last_system_check_heart_samples", 0)
                .putString("last_system_check_status", "FAIL")
                .putString("last_system_check_reason", "No readings arrived.")
                .apply();

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                String text = activity.watchCompatibilityTextForTest();
                assertTrue(text.contains("Motion: DETECTED, NOT VERIFIED"));
                assertTrue(text.contains("Heart rate: DETECTED, NOT VERIFIED"));
                assertTrue(text.contains("Samsung sensor package missing"));
                assertFalse(text.contains("Motion: unavailable"));
            });
        }
    }

    private static boolean containsScrollView(View view) {
        if (view instanceof ScrollView) return true;
        if (!(view instanceof android.view.ViewGroup)) return false;
        android.view.ViewGroup group = (android.view.ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (containsScrollView(group.getChildAt(i))) return true;
        }
        return false;
    }

    private static String allText(View view) {
        StringBuilder result = new StringBuilder();
        if (view instanceof android.widget.TextView) {
            result.append(((android.widget.TextView) view).getText()).append('\n');
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                result.append(allText(group.getChildAt(i)));
            }
        }
        return result.toString();
    }
}
