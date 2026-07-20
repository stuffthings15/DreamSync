package com.remote.DreamSync;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public final class WearWorkflowInstrumentationTest {
    private final Context context = InstrumentationRegistry.getInstrumentation()
            .getTargetContext();

    @After public void clearConfig() {
        SessionConfig.clear(context);
        AutoSleepConfig.clear(context);
    }

    @Test public void handshakeCanBeConsumedOnlyOnce() {
        SessionConfig config = new SessionConfig();
        config.sessionId = "session";
        config.phoneNodeId = "phone";
        config.watchNodeId = "watch";
        config.handshakeMessageId = "handshake";
        config.createdUtcMs = System.currentTimeMillis();
        config.expiresUtcMs = config.createdUtcMs + 60_000L;
        config.save(context);

        assertTrue(SessionConfig.consumeHandshake(
                context, "session", "handshake"));
        assertFalse(SessionConfig.consumeHandshake(
                context, "session", "handshake"));
    }

    @Test public void roundScreenWorkflowIsScrollableAndPhoneControlled() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                View content = activity.findViewById(android.R.id.content);
                assertNotNull(content);
                assertNull(activity.getActionBar());
                assertTrue(containsScrollView(content));
                assertNotNull(activity.findViewById(R.id.openPhoneButton));
                ScrollView scroll = activity.findViewById(R.id.watchScroll);
                TextView status = activity.findViewById(R.id.statusText);
                assertNotNull(scroll);
                assertNotNull(status);
                assertEquals(0, scroll.getScrollY());
                assertTrue(status.getGlobalVisibleRect(new android.graphics.Rect()));
                assertEquals(1, countButtons(content));
                assertFalse(containsWatchSetting(content));
            });
        }
    }

    @Test public void unboundSystemCheckLinkFailsClosed() {
        Intent intent = new Intent(context, MainActivity.class)
                .setData(Uri.parse("dreamsync://watch/system-check"
                        + "?check=check-1&enrollment=enrollment-1"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                TextView status = activity.findViewById(R.id.statusText);
                assertNotNull(status);
                assertTrue(status.getText().toString().contains("could not start"));
            });
        }
    }

    @Test public void manifestDeclaresCoreAndAdvancedHealthPermissions() throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(
                context.getPackageName(), PackageManager.GET_PERMISSIONS);
        assertNotNull(info.requestedPermissions);
        java.util.List<String> permissions = Arrays.asList(info.requestedPermissions);
        assertTrue(permissions.contains(Manifest.permission.ACTIVITY_RECOGNITION));
        assertTrue(permissions.contains(Manifest.permission.VIBRATE));
        assertTrue(permissions.contains("android.permission.health.READ_HEART_RATE"));
        assertTrue(permissions.contains("android.permission.health.READ_SKIN_TEMPERATURE"));
        assertTrue(permissions.contains(
                "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"));
    }

    @Test public void processInterruptionIsPersistedFailClosed() {
        WearDao dao = WearDatabase.get(context).dao();
        WearSessionEntity session = new WearSessionEntity();
        session.sessionId = "instrumentation-process-interruption";
        session.state = "RUNNING";
        session.startUtcMs = 1L;
        dao.upsertSession(session);

        dao.markInterrupted(2L);

        WearSessionEntity stored = dao.session(session.sessionId);
        assertNotNull(stored);
        assertEquals("PROCESS_INTERRUPTED", stored.state);
        assertEquals("process restarted", stored.terminationReason);
        assertEquals(2L, stored.endUtcMs);
        dao.deleteAllSessions();
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

    private static int countButtons(View view) {
        int count = view instanceof android.widget.Button ? 1 : 0;
        if (!(view instanceof android.view.ViewGroup)) return count;
        android.view.ViewGroup group = (android.view.ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            count += countButtons(group.getChildAt(i));
        }
        return count;
    }

    private static boolean containsWatchSetting(View view) {
        if (view instanceof android.widget.SeekBar
                || view instanceof android.widget.RadioGroup
                || view instanceof android.widget.EditText
                || view instanceof android.widget.Switch) return true;
        if (!(view instanceof android.view.ViewGroup)) return false;
        android.view.ViewGroup group = (android.view.ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (containsWatchSetting(group.getChildAt(i))) return true;
        }
        return false;
    }
}
