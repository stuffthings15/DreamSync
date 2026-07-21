package com.remote.DreamSync;

import android.content.Context;
import android.os.Build;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Optional Samsung Health Sensor SDK adapter.
 *
 * The proprietary AAR is loaded only when it is locally supplied. Reflection keeps the
 * standard Wear OS build usable while the Samsung partnership/package-signing approval is
 * pending. When present, this bridge uses the official continuous HR+IBI, skin temperature,
 * and optional raw PPG trackers.
 */
final class SamsungHealthSensorBridge {
    interface Listener {
        void onCapabilities(Set<String> trackerTypes, boolean connected, String detail);
        void onHeartRate(long timestampMs, double bpm, List<Integer> ibiMs, boolean offWrist);
        void onSkinTemperature(long timestampMs, double skinC, double ambientC);
        void onPpgSample(long timestampMs, boolean valid);
        void onBridgeError(String code, String detail);
    }

    private static final String ROOT = "com.samsung.android.service.health.tracking.";
    private static final String DATA = ROOT + "data.";

    private final Context context;
    private final Listener listener;
    private final Map<String, Object> trackers = new LinkedHashMap<>();
    private final Map<String, Object> trackerListeners = new LinkedHashMap<>();
    private Set<String> supportedTrackerTypes = Collections.emptySet();
    private Object service;
    private boolean connected;
    private boolean rawPpgRequested;

    SamsungHealthSensorBridge(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    static boolean isPackaged() {
        try {
            Class.forName(ROOT + "HealthTrackingService");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isSamsungWatch() {
        return Build.MANUFACTURER != null
                && Build.MANUFACTURER.toLowerCase(Locale.US).contains("samsung");
    }

    void start(boolean enableRawPpg) {
        rawPpgRequested = enableRawPpg;
        if (!isSamsungWatch()) {
            listener.onCapabilities(Collections.emptySet(), false,
                    "Not a Samsung watch; standard Wear OS sensors active");
            return;
        }
        if (!isPackaged()) {
            listener.onCapabilities(Collections.emptySet(), false,
                    "Samsung SDK AAR not packaged; standard Wear OS sensors active");
            return;
        }
        try {
            Class<?> connectionType = Class.forName(ROOT + "ConnectionListener");
            Object connectionListener = Proxy.newProxyInstance(
                    connectionType.getClassLoader(),
                    new Class<?>[]{connectionType},
                    connectionHandler(enableRawPpg));
            Class<?> serviceType = Class.forName(ROOT + "HealthTrackingService");
            Constructor<?> constructor = serviceType.getConstructor(
                    connectionType, Context.class);
            service = constructor.newInstance(connectionListener, context);
            serviceType.getMethod("connectService").invoke(service);
        } catch (Throwable error) {
            listener.onBridgeError("SDK_CONNECT_START_FAILED", safe(error));
            listener.onCapabilities(Collections.emptySet(), false, safe(error));
        }
    }

    private InvocationHandler connectionHandler(boolean enableRawPpg) {
        return (proxy, method, args) -> {
            String name = method.getName();
            if ("onConnectionSuccess".equals(name)) {
                connected = true;
                configureTrackers(enableRawPpg);
            } else if ("onConnectionFailed".equals(name)) {
                connected = false;
                listener.onBridgeError("SDK_CONNECTION_FAILED",
                        args == null || args.length == 0 ? "unknown" : safe(args[0]));
                listener.onCapabilities(Collections.emptySet(), false,
                        args == null || args.length == 0 ? "connection failed" : safe(args[0]));
            } else if ("onConnectionEnded".equals(name)) {
                connected = false;
                listener.onBridgeError("SDK_CONNECTION_ENDED", "Health Platform disconnected");
            }
            return null;
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void configureTrackers(boolean enableRawPpg) {
        Set<String> supported = new HashSet<>();
        try {
            Object capability = service.getClass().getMethod("getTrackingCapability")
                    .invoke(service);
            Object result = capability.getClass().getMethod("getSupportHealthTrackerTypes")
                    .invoke(capability);
            if (result instanceof Iterable) {
                for (Object tracker : (Iterable<?>) result) supported.add(tracker.toString());
            }
            supportedTrackerTypes = Collections.unmodifiableSet(new HashSet<>(supported));
            listener.onCapabilities(supported, true, "Samsung Health Sensor SDK connected");
            if (supported.contains("HEART_RATE_CONTINUOUS")) {
                attach("HEART_RATE_CONTINUOUS", this::handleHeartRate);
            }
            if (supported.contains("SKIN_TEMPERATURE_CONTINUOUS")) {
                attach("SKIN_TEMPERATURE_CONTINUOUS", this::handleSkinTemperature);
            }
            if (enableRawPpg && supported.contains("PPG_CONTINUOUS")) {
                attach("PPG_CONTINUOUS", this::handlePpg);
            }
        } catch (Throwable error) {
            listener.onBridgeError("SDK_CAPABILITY_FAILED", safe(error));
            listener.onCapabilities(supported, connected, safe(error));
        }
    }

    private interface DataHandler {
        void handle(List<?> dataPoints) throws Exception;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void attach(String trackerName, DataHandler handler) {
        if (trackers.containsKey(trackerName)) return;
        try {
            Class<?> trackerTypeClass = Class.forName(DATA + "HealthTrackerType");
            Object trackerType = Enum.valueOf((Class<? extends Enum>) trackerTypeClass,
                    trackerName);
            Object tracker = service.getClass()
                    .getMethod("getHealthTracker", trackerTypeClass)
                    .invoke(service, trackerType);
            Class<?> listenerType = Class.forName(ROOT + "HealthTracker$TrackerEventListener");
            Object trackerListener = Proxy.newProxyInstance(
                    listenerType.getClassLoader(),
                    new Class<?>[]{listenerType},
                    (proxy, method, args) -> {
                        if ("onDataReceived".equals(method.getName())
                                && args != null && args.length > 0
                                && args[0] instanceof List) {
                            try {
                                handler.handle((List<?>) args[0]);
                            } catch (Throwable error) {
                                listener.onBridgeError(trackerName + "_DATA_FAILED", safe(error));
                            }
                        } else if ("onError".equals(method.getName())) {
                            listener.onBridgeError(trackerName + "_TRACKER_ERROR",
                                    args == null || args.length == 0 ? "unknown" : safe(args[0]));
                        }
                        return null;
                    });
            tracker.getClass().getMethod("setEventListener", listenerType)
                    .invoke(tracker, trackerListener);
            trackers.put(trackerName, tracker);
            trackerListeners.put(trackerName, trackerListener);
        } catch (Throwable error) {
            listener.onBridgeError(trackerName + "_START_FAILED", safe(error));
        }
    }

    /** Raw PPG is power-heavy, so sessions open it only for short quality windows. */
    synchronized boolean setRawPpgEnabled(boolean enabled) {
        rawPpgRequested = enabled;
        if (!connected || !supportedTrackerTypes.contains("PPG_CONTINUOUS")) return false;
        if (enabled) {
            attach("PPG_CONTINUOUS", this::handlePpg);
            return trackers.containsKey("PPG_CONTINUOUS");
        }
        detach("PPG_CONTINUOUS");
        return false;
    }

    private void detach(String trackerName) {
        Object tracker = trackers.remove(trackerName);
        trackerListeners.remove(trackerName);
        if (tracker == null) return;
        try {
            tracker.getClass().getMethod("unsetEventListener").invoke(tracker);
        } catch (Throwable error) {
            listener.onBridgeError(trackerName + "_STOP_FAILED", safe(error));
        }
    }

    private void handleHeartRate(List<?> points) throws Exception {
        for (Object point : points) {
            long timestamp = timestamp(point);
            Integer bpm = integerValue(point, "ValueKey$HeartRateSet", "HEART_RATE");
            Integer status = integerValue(point, "ValueKey$HeartRateSet", "HEART_RATE_STATUS");
            List<Integer> ibi = integerListValue(point,
                    "ValueKey$HeartRateSet", "IBI_LIST");
            List<Integer> ibiStatus = integerListValue(point,
                    "ValueKey$HeartRateSet", "IBI_STATUS_LIST");
            List<Integer> validIbi = new ArrayList<>();
            for (int i = 0; i < ibi.size(); i++) {
                int value = ibi.get(i);
                int valueStatus = i < ibiStatus.size() ? ibiStatus.get(i) : 0;
                if (valueStatus == 0 && value >= 250 && value <= 2_000) validIbi.add(value);
            }
            boolean offWrist = status != null && status == -3;
            if (bpm != null && bpm >= 30 && bpm <= 220) {
                listener.onHeartRate(timestamp, bpm, validIbi, offWrist);
            } else if (offWrist) {
                listener.onHeartRate(timestamp, Double.NaN, validIbi, true);
            }
        }
    }

    private void handleSkinTemperature(List<?> points) throws Exception {
        for (Object point : points) {
            Float skin = floatValue(point,
                    "ValueKey$SkinTemperatureSet", "OBJECT_TEMPERATURE");
            Float ambient = floatValue(point,
                    "ValueKey$SkinTemperatureSet", "AMBIENT_TEMPERATURE");
            if (skin != null && Float.isFinite(skin)) {
                listener.onSkinTemperature(timestamp(point), skin,
                        ambient == null ? Double.NaN : ambient);
            }
        }
    }

    private void handlePpg(List<?> points) throws Exception {
        for (Object point : points) {
            Integer greenStatus = integerValue(point, "ValueKey$PpgSet", "GREEN_STATUS");
            Integer redStatus = integerValue(point, "ValueKey$PpgSet", "RED_STATUS");
            Integer irStatus = integerValue(point, "ValueKey$PpgSet", "IR_STATUS");
            boolean valid = (greenStatus == null || greenStatus == 0)
                    && (redStatus == null || redStatus == 0)
                    && (irStatus == null || irStatus == 0);
            listener.onPpgSample(timestamp(point), valid);
        }
    }

    void stop() {
        for (Object tracker : trackers.values()) {
            try {
                tracker.getClass().getMethod("unsetEventListener").invoke(tracker);
            } catch (Throwable ignored) {
                // Best-effort cleanup; disconnect below releases the service.
            }
        }
        trackers.clear();
        trackerListeners.clear();
        supportedTrackerTypes = Collections.emptySet();
        if (service != null) {
            try {
                service.getClass().getMethod("disconnectService").invoke(service);
            } catch (Throwable ignored) {
                // Health Platform may already be disconnected.
            }
            service = null;
        }
        connected = false;
    }

    private static long timestamp(Object point) throws Exception {
        Object value = point.getClass().getMethod("getTimestamp").invoke(point);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static Integer integerValue(Object point, String keyClass, String field)
            throws Exception {
        Object value = value(point, keyClass, field);
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    private static Float floatValue(Object point, String keyClass, String field)
            throws Exception {
        Object value = value(point, keyClass, field);
        return value instanceof Number ? ((Number) value).floatValue() : null;
    }

    private static List<Integer> integerListValue(
            Object point, String keyClass, String field
    ) throws Exception {
        Object value = value(point, keyClass, field);
        if (!(value instanceof Iterable)) return Collections.emptyList();
        List<Integer> output = new ArrayList<>();
        for (Object item : (Iterable<?>) value) {
            if (item instanceof Number) output.add(((Number) item).intValue());
        }
        return output;
    }

    private static Object value(Object point, String keyClass, String field)
            throws Exception {
        Class<?> keys = Class.forName(DATA + keyClass);
        Field keyField = keys.getField(field);
        Object key = keyField.get(null);
        Method getValue = null;
        for (Method method : point.getClass().getMethods()) {
            if ("getValue".equals(method.getName()) && method.getParameterCount() == 1) {
                getValue = method;
                break;
            }
        }
        if (getValue == null) throw new NoSuchMethodException("DataPoint.getValue");
        return getValue.invoke(point, key);
    }

    private static String safe(Object error) {
        if (error == null) return "unknown";
        if (error instanceof Throwable) {
            Throwable throwable = (Throwable) error;
            Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
            String message = cause.getMessage();
            return cause.getClass().getSimpleName()
                    + (message == null || message.isEmpty() ? "" : ": " + message);
        }
        return error.toString();
    }
}
