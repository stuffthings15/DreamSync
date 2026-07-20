package com.remote.DreamSync;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
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
                assertTrue(containsScrollView(content));
                assertNotNull(activity.findViewById(R.id.openPhoneButton));
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

    private static boolean containsScrollView(View view) {
        if (view instanceof ScrollView) return true;
        if (!(view instanceof android.view.ViewGroup)) return false;
        android.view.ViewGroup group = (android.view.ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (containsScrollView(group.getChildAt(i))) return true;
        }
        return false;
    }
}
