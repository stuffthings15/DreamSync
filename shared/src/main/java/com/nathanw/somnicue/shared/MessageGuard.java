package com.nathanw.somnicue.shared;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MessageGuard {
    public enum Result {
        ACCEPT,
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
        if (envelope.protocolVersion != Protocol.VERSION) return Result.BAD_VERSION;
        if (envelope.isExpired(nowUtcMs)) return Result.EXPIRED;
        if (!expectedSession.isEmpty() && !expectedSession.equals(envelope.sessionId)) {
            return Result.WRONG_SESSION;
        }
        if (!localDevice.isEmpty() && !localDevice.equals(envelope.targetDeviceId)) {
            return Result.WRONG_DEVICE;
        }
        if (seen.containsKey(envelope.messageId)) return Result.DUPLICATE;
        seen.put(envelope.messageId, Boolean.TRUE);
        return Result.ACCEPT;
    }
}
