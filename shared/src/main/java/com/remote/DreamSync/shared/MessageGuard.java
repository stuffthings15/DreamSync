package com.remote.DreamSync.shared;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MessageGuard {
    public enum Result {
        ACCEPT,
        MALFORMED,
        BAD_VERSION,
        EXPIRED,
        WRONG_SESSION,
        WRONG_DEVICE,
        DUPLICATE
    }

    private final Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 256;
        }
    };

    public synchronized Result check(
            ProtocolEnvelope envelope,
            String expectedSession,
            String localDevice,
            long nowUtcMs
    ) {
        return check(envelope, expectedSession, localDevice, "", nowUtcMs);
    }

    public synchronized Result check(
            ProtocolEnvelope envelope,
            String expectedSession,
            String localDevice,
            String expectedSender,
            long nowUtcMs
    ) {
        if (envelope == null
                || blank(envelope.messageId)
                || blank(envelope.messageType)
                || blank(envelope.sessionId)
                || blank(envelope.senderDeviceId)
                || blank(envelope.targetDeviceId)) return Result.MALFORMED;
        if (envelope.protocolVersion != Protocol.VERSION) return Result.BAD_VERSION;
        if (envelope.isExpired(nowUtcMs)) return Result.EXPIRED;
        if (!expectedSession.isEmpty() && !expectedSession.equals(envelope.sessionId)) {
            return Result.WRONG_SESSION;
        }
        if (!localDevice.isEmpty() && !localDevice.equals(envelope.targetDeviceId)) {
            return Result.WRONG_DEVICE;
        }
        if (!expectedSender.isEmpty() && !expectedSender.equals(envelope.senderDeviceId)) {
            return Result.WRONG_DEVICE;
        }
        if (seen.containsKey(envelope.messageId)) return Result.DUPLICATE;
        seen.put(envelope.messageId, Boolean.TRUE);
        return Result.ACCEPT;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank() || value.length() > 128;
    }
}
