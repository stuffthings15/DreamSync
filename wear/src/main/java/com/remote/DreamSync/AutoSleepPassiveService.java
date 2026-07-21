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
        AutoSleepConfig config = AutoSleepConfig.load(this);
        WatchDiagnostics.auto(this, config, "PASSIVE_ACTIVITY_RECEIVED",
                "state=" + info.getUserActivityState() + ";asleep=" + asleep
                        + ";sessionRunning=" + SleepSessionService.isRunning());
        if (asleep) startAutomaticSession("WEAR_HEALTH_SERVICES_ASLEEP");
    }

    @Override public void onNewDataPointsReceived(DataPointContainer points) {
        AutoSleepConfig config = AutoSleepConfig.load(this);
        if (!config.validBinding()) {
            WatchDiagnostics.local(this, "PASSIVE_DATA_IGNORED", "bindingInvalid=true");
            return;
        }
        if (!config.passiveHeartRateSupported) {
            WatchDiagnostics.auto(this, config, "PASSIVE_DATA_IGNORED",
                    "passiveHeartRateSupported=false");
            return;
        }
        if (SleepSessionService.isRunning()) {
            WatchDiagnostics.auto(this, config, "PASSIVE_DATA_IGNORED",
                    "activeSession=true;full-rate sensors already running");
            return;
        }

        List<SampleDataPoint<Double>> heartRates = points.getData(
                DataType.HEART_RATE_BPM);
        long nowElapsed = SystemClock.elapsedRealtime();
        long nowUtc = System.currentTimeMillis();
        int newSamples = 0;
        double lastBpm = Double.NaN;
        boolean lastInWindow = false;
        boolean candidateReady = false;
        double candidateThresholdBpm = passiveSleepGate.candidateThresholdBpm(
                config.learnedRestingHeartRateBpm, config.baselineNights);
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
            boolean inWindow = passiveSleepGate.inSleepWindow(
                    minuteOfDay, config.typicalSleepMinute);
            PassiveSleepGate.Result result = passiveSleepGate.observe(
                    inWindow,
                    point.getValue(),
                    candidateThresholdBpm,
                    sampleElapsed,
                    config.firstLowHeartRateElapsedMs,
                    config.lowHeartRateSamples);
            config.firstLowHeartRateElapsedMs = result.firstLowHeartRateElapsedMs;
            config.lowHeartRateSamples = result.lowHeartRateSamples;
            config.save(this);
            newSamples++;
            lastBpm = point.getValue();
            lastInWindow = inWindow;
            candidateReady = result.candidateReady;
            if (result.candidateReady) {
                WatchDiagnostics.auto(this, config, "PASSIVE_SLEEP_CANDIDATE_READY",
                        "source=heart_rate_fallback;bpm=" + point.getValue()
                                + ";lowHeartRateSamples=" + result.lowHeartRateSamples
                                + ";inSleepWindow=" + inWindow
                                + ";candidateThresholdBpm=" + candidateThresholdBpm
                                + ";baselineNights=" + config.baselineNights);
                startAutomaticSession(
                        "WEAR_HEALTH_SERVICES_PASSIVE_HR_FALLBACK");
                return;
            }
        }
        WatchDiagnostics.auto(this, config, "PASSIVE_HEART_RATE_BATCH",
                "newSamples=" + newSamples
                        + ";lastBpm=" + lastBpm
                        + ";inSleepWindow=" + lastInWindow
                        + ";lowHeartRateSamples=" + config.lowHeartRateSamples
                        + ";candidateReady=" + candidateReady
                        + ";candidateThresholdBpm=" + candidateThresholdBpm
                        + ";baselineNights=" + config.baselineNights
                        + ";required=3 samples spanning 10 minutes");
    }

    private void startAutomaticSession(String source) {
        AutoSleepConfig config = AutoSleepConfig.load(this);
        long now = System.currentTimeMillis();
        if (!config.validBinding()) {
            WatchDiagnostics.local(this, "AUTO_SESSION_BLOCKED", "bindingInvalid=true");
            return;
        }
        boolean running = SleepSessionService.isRunning();
        if (!gate.mayStart(config.enabled, true, running,
                config.lastSessionUtcMs, now)) {
            WatchDiagnostics.auto(this, config, "AUTO_SESSION_BLOCKED",
                    "enabled=" + config.enabled
                            + ";sessionRunning=" + running
                            + ";lastSessionUtcMs=" + config.lastSessionUtcMs
                            + ";minimumGapHours=4;source=" + source);
            return;
        }
        if (!config.reserveSession(this, now)) {
            WatchDiagnostics.auto(this, config, "AUTO_SESSION_BLOCKED",
                    "reason=atomic reservation failed;source=" + source);
            return;
        }

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
        WatchDiagnostics.auto(this, config, "AUTO_SESSION_REQUESTED",
                "source=" + source + ";phoneHandshake=pending");
        Wearable.getDataClient(this).putDataItem(request)
                .addOnSuccessListener(item -> WatchDiagnostics.auto(
                        this, config, "AUTO_SESSION_REQUEST_DELIVERED",
                        "source=" + source + ";durableDataItem=true"))
                .addOnFailureListener(error -> WatchDiagnostics.auto(
                        this, config, "AUTO_SESSION_REQUEST_FAILED",
                        "source=" + source + ";error=" + safeMessage(error)));
        config.firstLowHeartRateElapsedMs = 0L;
        config.lowHeartRateSamples = 0;
        config.status = "Possible sleep detected. Confirming motion and heart rate before any cue.";
        config.save(this);
        AutoSleepMonitor.publishState(this, config, "SLEEP_DETECTED", config.status);
    }

    @Override public void onPermissionLost() {
        AutoSleepMonitor.permissionLost(this);
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown";
        Throwable cause = error.getCause() == null ? error : error.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName() : message;
    }
}
