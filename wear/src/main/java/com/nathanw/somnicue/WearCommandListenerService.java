package com.nathanw.somnicue;

import android.content.Intent;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.charset.StandardCharsets;

public final class WearCommandListenerService extends WearableListenerService {
    @Override
    public void onMessageReceived(MessageEvent event) {
        if (Protocol.PATH_CONFIG.equals(event.getPath())) {
            String json = new String(event.getData(), StandardCharsets.UTF_8);
            SessionConfig.fromJson(json).save(this);
            sendBroadcast(new Intent(MainActivity.ACTION_CONFIG_CHANGED)
                    .setPackage(getPackageName()));
        } else if (Protocol.PATH_STOP.equals(event.getPath())) {
            stopService(new Intent(this, SleepSessionService.class));
        }
    }
}
