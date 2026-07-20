package com.remote.DreamSync;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.ScrollView;

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

    @Test public void roundScreenWorkflowIsScrollableAndHasStopControl() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                View content = activity.findViewById(android.R.id.content);
                assertNotNull(content);
                assertTrue(containsScrollView(content));
                assertNotNull(activity.findViewById(R.id.startButton));
                assertNotNull(activity.findViewById(R.id.stopButton));
                assertNotNull(activity.findViewById(R.id.deleteDataButton));
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
