package com.remote.DreamSync;

import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Registers every standard heart-rate sensor and uses whichever emits valid data. */
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
        List<Sensor> candidates = manager.getSensorList(Sensor.TYPE_HEART_RATE);
        if (candidates == null || candidates.isEmpty()) {
            return new Result(null, Collections.emptyList(), false, "type=HEART_RATE:none");
        }
        List<Sensor> registered = new ArrayList<>();
        StringBuilder details = new StringBuilder();
        for (Sensor candidate : candidates) {
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
            if (details.length() > 0) details.append(" || ");
            details.append(MotionSensorRegistration.describe(candidate))
                    .append(";samplePeriodUs=").append(samplingPeriodUs)
                    .append(";registered=").append(success);
            if (!error.isEmpty()) details.append(";error=").append(error);
            if (success) registered.add(candidate);
        }
        return new Result(registered.isEmpty() ? null : registered.get(0),
                registered, !registered.isEmpty(), details.toString());
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
