package com.remote.DreamSync;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
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
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                View content = activity.findViewById(android.R.id.content);
                assertNotNull(content);
                assertTrue(containsScrollView(content));
                assertNotNull(activity.findViewById(R.id.powerButton));
                assertNotNull(activity.findViewById(R.id.exportButton));
                assertNotNull(activity.findViewById(R.id.exportDebugButton));
                assertNotNull(activity.findViewById(R.id.deleteDataButton));
            });
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
        assertTrue(debug.contains("watchBound="));
        assertTrue(debug.contains("TEST_EVENT"));
        assertFalse(debug.contains("sensitive-node-value"));
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
