package com.nathanw.somnicue;

import android.content.Intent;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.charset.StandardCharsets;

/**
 * Receives watch summaries even after the phone audio foreground service has been stopped.
 */
public final class PhoneDataListenerService extends WearableListenerService {
    static final String ACTION_SUMMARY_CHANGED = "com.nathanw.somnicue.SUMMARY_CHANGED";

    @Override
    public void onMessageReceived(MessageEvent event) {
        if (!Protocol.PATH_SUMMARY.equals(event.getPath())) return;
        String summary = new String(event.getData(), StandardCharsets.UTF_8);
        stopService(new Intent(this, CuePlaybackService.class));
        getSharedPreferences("somnicue_phone", MODE_PRIVATE)
                .edit()
                .putString("last_summary", summary)
                .apply();
        sendBroadcast(new Intent(ACTION_SUMMARY_CHANGED)
                .setPackage(getPackageName())
                .putExtra("summary", summary));
    }
}
