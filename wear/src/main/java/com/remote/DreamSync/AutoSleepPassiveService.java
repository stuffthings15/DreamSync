package com.remote.DreamSync;

import android.os.SystemClock;

import androidx.health.services.client.PassiveListenerService;
import androidx.health.services.client.data.DataPointContainer;
import androidx.health.services.client.data.DataType;
import androidx.health.services.client.data.SampleDataPoint;
import androidx.health.services.client.data.UserActivityInfo;
import androidx.health.services.client.data.UserActivityState;

import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.remote.DreamSync.shared.AutoSessionGate;
import com.remote.DreamSync.shared.PassiveSleepGate;
import com.remote.DreamSync.shared.ProtocolEnvelope;

import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AutoSleepPassiveService extends PassiveListenerService {
    private final AutoSessionGate gate = new AutoSessionGate();
    private final PassiveSleepGate passiveSleepGate = new PassiveSleepGate();

    @Override public void onUserActivityInfoReceived(UserActivityInfo info) {
        boolean asleep = UserActivityState.USER_ACTIVITY_ASLEEP.equals(
                info.getUserActivityState());
        if (asleep) startAutomaticSession("WEAR_HEALTH_SERVICES_ASLEEP");
    }

    @Override public void onNewDataPointsReceived(DataPointContainer points) {
        AutoSleepConfig config = AutoSleepConfig.load(this);
        if (!config.validBinding() || !config.passiveHeartRateSupported
                || SleepSessionService.isRunning()) return;

        List<SampleDataPoint<Double>> heartRates = points.getData(
                DataType.HEART_RATE_BPM);
        long nowElapsed = SystemClock.elapsedRealtime();
        long nowUtc = System.currentTimeMillis();
        for (SampleDataPoint<Double> point : heartRates) {
            long sampleElapsed = point.getTimeDurationFromBoot().toMillis();
            if (sampleElapsed <= config.lastPassiveHeartRateElapsedMs) continue;
            config.lastPassiveHeartRateElapsedMs = sampleElapsed;
            long age = Math.max(0L, nowElapsed - sampleElapsed);
            long sampleUtc = nowUtc - age;
            Calendar local = Calendar.getInstance();
            local.setTimeInMillis(sampleUtc);
            int minuteOfDay = local.get(Calendar.HOUR_OF_DAY) * 60
                    + local.get(Calendar.MINUTE);
            PassiveSleepGate.Result result = passiveSleepGate.observe(
                    passiveSleepGate.inSleepWindow(
                            minuteOfDay, config.typicalSleepMinute),
                    point.getValue(),
                    sampleElapsed,
                    config.firstLowHeartRateElapsedMs,
                    config.lowHeartRateSamples);
            config.firstLowHeartRateElapsedMs = result.firstLowHeartRateElapsedMs;
            config.lowHeartRateSamples = result.lowHeartRateSamples;
            config.save(this);
            if (result.candidateReady) {
                startAutomaticSession(
                        "WEAR_HEALTH_SERVICES_PASSIVE_HR_FALLBACK");
                return;
            }
        }
    }

    private void startAutomaticSession(String source) {
        AutoSleepConfig config = AutoSleepConfig.load(this);
        long now = System.currentTimeMillis();
        if (!config.validBinding()
                || !gate.mayStart(config.enabled, true, SleepSessionService.isRunning(),
                config.lastSessionUtcMs, now)
                || !config.reserveSession(this, now)) return;

        String sessionId = UUID.randomUUID().toString();
        ProtocolEnvelope envelope = ProtocolEnvelope.create(
                com.remote.DreamSync.shared.Protocol.TYPE_AUTO_SESSION_START,
                sessionId, "", config.watchNodeId, config.phoneNodeId, now,
                com.remote.DreamSync.shared.Protocol.AUTO_SESSION_TTL_MS,
                Map.of(
                        "enrollmentId", config.enrollmentId,
                        "detectedAtUtcMs", Long.toString(now),
                        "source", source));
        PutDataMapRequest map = PutDataMapRequest.create(
                com.remote.DreamSync.shared.Protocol.PATH_AUTO_SESSION_PREFIX + sessionId);
        map.getDataMap().putByteArray("envelope", envelope.encode());
        PutDataRequest request = map.asPutDataRequest();
        request.setUrgent();
        Wearable.getDataClient(this).putDataItem(request);
        config.firstLowHeartRateElapsedMs = 0L;
        config.lowHeartRateSamples = 0;
        config.status = "Possible sleep detected. Confirming motion and heart rate before any cue.";
        config.save(this);
        AutoSleepMonitor.publishState(this, config, "SLEEP_DETECTED", config.status);
    }

    @Override public void onPermissionLost() {
        AutoSleepMonitor.permissionLost(this);
    }
}
