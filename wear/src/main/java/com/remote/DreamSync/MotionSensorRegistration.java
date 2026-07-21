package com.remote.DreamSync;

import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Tries standard motion sensors one at a time and records every registration attempt. */
final class MotionSensorRegistration {
    static final class Result {
        final Sensor sensor;
        final List<Sensor> sensors;
        final boolean registered;
        final String details;

        Result(Sensor sensor, List<Sensor> sensors, boolean registered, String details) {
            this.sensor = sensor;
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
        StringBuilder details = new StringBuilder();
        for (Sensor candidate : candidates(manager)) {
            boolean registered = tryRegister(manager, listener, candidate,
                    samplingPeriodUs, maximumLatencyUs, handler, details);
            if (!registered && samplingPeriodUs != SensorManager.SENSOR_DELAY_NORMAL) {
                registered = tryRegister(manager, listener, candidate,
                        SensorManager.SENSOR_DELAY_NORMAL, maximumLatencyUs, handler, details);
            }
            if (registered) {
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
        StringBuilder details = new StringBuilder();
        boolean registered = tryRegister(manager, listener, candidate,
                samplingPeriodUs, maximumLatencyUs, handler, details);
        return new Result(candidate,
                registered ? Collections.singletonList(candidate) : Collections.emptyList(),
                registered, details.toString());
    }

    static List<Sensor> candidates(SensorManager manager) {
        if (manager == null) return Collections.emptyList();
        List<Sensor> ordered = new ArrayList<>();
        addUnique(ordered, manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER));
        int[] types = new int[]{
                Sensor.TYPE_ACCELEROMETER,
                Sensor.TYPE_LINEAR_ACCELERATION,
                Sensor.TYPE_GRAVITY
        };
        for (int type : types) {
            List<Sensor> sensors = manager.getSensorList(type);
            if (sensors == null) continue;
            for (Sensor sensor : sensors) addUnique(ordered, sensor);
        }
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

    private static boolean tryRegister(
            SensorManager manager,
            SensorEventListener listener,
            Sensor candidate,
            int samplingPeriodUs,
            int maximumLatencyUs,
            Handler handler,
            StringBuilder details
    ) {
        boolean registered = false;
        String error = "";
        try {
            registered = handler == null
                    ? manager.registerListener(listener, candidate, samplingPeriodUs)
                    : manager.registerListener(listener, candidate, samplingPeriodUs,
                    maximumLatencyUs, handler);
        } catch (RuntimeException exception) {
            error = exception.getClass().getSimpleName() + ":"
                    + safe(exception.getMessage());
        }
        append(details, describe(candidate) + ";samplePeriodUs=" + samplingPeriodUs
                + ";registered=" + registered
                + (error.isEmpty() ? "" : ";error=" + error));
        return registered;
    }

    static String inventory(SensorManager manager) {
        if (manager == null) return "sensorManager=null";
        StringBuilder out = new StringBuilder();
        int[] types = new int[]{
                Sensor.TYPE_ACCELEROMETER,
                Sensor.TYPE_LINEAR_ACCELERATION,
                Sensor.TYPE_GRAVITY,
                Sensor.TYPE_HEART_RATE
        };
        for (int type : types) {
            List<Sensor> sensors = manager.getSensorList(type);
            if (sensors == null || sensors.isEmpty()) {
                append(out, "type=" + type + ":none");
            } else {
                for (Sensor sensor : sensors) append(out, describe(sensor));
            }
        }
        return out.toString();
    }

    static String describe(Sensor sensor) {
        if (sensor == null) return "sensor=null";
        return "type=" + sensor.getType()
                + ";name=" + safe(sensor.getName())
                + ";vendor=" + safe(sensor.getVendor())
                + ";version=" + sensor.getVersion()
                + ";minDelayUs=" + sensor.getMinDelay()
                + ";maxDelayUs=" + sensor.getMaxDelay()
                + ";wakeUp=" + sensor.isWakeUpSensor()
                + ";reportingMode=" + sensor.getReportingMode();
    }

    private static void append(StringBuilder output, String value) {
        if (output.length() > 0) output.append(" || ");
        output.append(value);
    }

    private static void addUnique(List<Sensor> sensors, Sensor candidate) {
        if (candidate == null) return;
        for (Sensor existing : sensors) {
            if (existing == candidate) return;
            if (existing.getType() == candidate.getType()
                    && safe(existing.getName()).equals(safe(candidate.getName()))
                    && safe(existing.getVendor()).equals(safe(candidate.getVendor()))) return;
        }
        sensors.add(candidate);
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace(';', '_').replace('|', '_');
    }

    private MotionSensorRegistration() {}
}
