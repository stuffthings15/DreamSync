package com.remote.DreamSync;

import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;

import java.util.List;

/** Tries the standard motion sensors in order and records every registration attempt. */
final class MotionSensorRegistration {
    static final class Result {
        final Sensor sensor;
        final boolean registered;
        final String details;

        Result(Sensor sensor, boolean registered, String details) {
            this.sensor = sensor;
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
        if (manager == null) return new Result(null, false, "sensorManager=null");
        int[] types = new int[]{
                Sensor.TYPE_ACCELEROMETER,
                Sensor.TYPE_LINEAR_ACCELERATION,
                Sensor.TYPE_GRAVITY
        };
        StringBuilder details = new StringBuilder();
        for (int type : types) {
            List<Sensor> candidates = manager.getSensorList(type);
            if (candidates == null || candidates.isEmpty()) {
                append(details, "type=" + type + ":none");
                continue;
            }
            for (Sensor candidate : candidates) {
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
                append(details, describe(candidate) + ";registered=" + registered
                        + (error.isEmpty() ? "" : ";error=" + error));
                if (registered) return new Result(candidate, true, details.toString());
            }
        }
        return new Result(null, false, details.toString());
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

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace(';', '_').replace('|', '_');
    }

    private MotionSensorRegistration() {}
}
