package com.remote.DreamSync;

import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Registers standard heart-rate sensors one at a time. */
final class HeartRateSensorRegistration {
    static final class Result {
        final Sensor primary;
        final List<Sensor> sensors;
        final boolean registered;
        final String details;

        Result(Sensor primary, List<Sensor> sensors, boolean registered, String details) {
            this.primary = primary;
            this.sensors = Collections.unmodifiableList(new ArrayList<>(sensors));
            this.registered = registered;
            this.details = details;
        }
    }

    static Result register(
            SensorManager manager,
            SensorEventListener listener,
            int samplingPeriodUs,
            int maximumLatencyUs,
            Handler handler
    ) {
        if (manager == null) {
            return new Result(null, Collections.emptyList(), false, "sensorManager=null");
        }
        List<Sensor> candidates = candidates(manager);
        if (candidates == null || candidates.isEmpty()) {
            return new Result(null, Collections.emptyList(), false, "type=HEART_RATE:none");
        }
        StringBuilder details = new StringBuilder();
        for (Sensor candidate : candidates) {
            Result result = registerCandidate(manager, listener, candidate,
                    samplingPeriodUs, maximumLatencyUs, handler);
            if (details.length() > 0) details.append(" || ");
            details.append(result.details);
            if (result.registered) {
                return new Result(candidate, Collections.singletonList(candidate), true,
                        details.toString());
            }
        }
        return new Result(null, Collections.emptyList(), false, details.toString());
    }

    static Result registerCandidate(
            SensorManager manager,
            SensorEventListener listener,
            Sensor candidate,
            int samplingPeriodUs,
            int maximumLatencyUs,
            Handler handler
    ) {
        if (manager == null || candidate == null) {
            return new Result(null, Collections.emptyList(), false,
                    manager == null ? "sensorManager=null" : "candidate=null");
        }
        boolean success = false;
        String error = "";
        try {
            success = handler == null
                    ? manager.registerListener(listener, candidate, samplingPeriodUs)
                    : manager.registerListener(listener, candidate, samplingPeriodUs,
                    maximumLatencyUs, handler);
        } catch (RuntimeException exception) {
            error = exception.getClass().getSimpleName() + ":"
                    + safe(exception.getMessage());
        }
        String details = MotionSensorRegistration.describe(candidate)
                + ";samplePeriodUs=" + samplingPeriodUs
                + ";registered=" + success
                + (error.isEmpty() ? "" : ";error=" + error);
        return new Result(candidate,
                success ? Collections.singletonList(candidate) : Collections.emptyList(),
                success, details);
    }

    static List<Sensor> candidates(SensorManager manager) {
        if (manager == null) return Collections.emptyList();
        List<Sensor> sensors = manager.getSensorList(Sensor.TYPE_HEART_RATE);
        if (sensors == null || sensors.isEmpty()) return Collections.emptyList();
        List<Sensor> ordered = new ArrayList<>(sensors);
        ordered.sort((first, second) -> Boolean.compare(
                first.isWakeUpSensor(), second.isWakeUpSensor()));
        return Collections.unmodifiableList(ordered);
    }

    static boolean accepts(List<Sensor> registeredSensors, Sensor eventSensor) {
        if (eventSensor == null || registeredSensors == null) return false;
        for (Sensor registered : registeredSensors) {
            if (registered == eventSensor) return true;
            if (registered != null
                    && registered.getType() == eventSensor.getType()
                    && safe(registered.getName()).equals(safe(eventSensor.getName()))
                    && safe(registered.getVendor()).equals(safe(eventSensor.getVendor()))) {
                return true;
            }
        }
        return false;
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace(';', '_').replace('|', '_');
    }

    private HeartRateSensorRegistration() {}
}
