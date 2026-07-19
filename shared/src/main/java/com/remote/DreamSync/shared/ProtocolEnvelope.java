package com.remote.DreamSync.shared;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public final class ProtocolEnvelope {
    public final int protocolVersion;
    public final String messageId;
    public final String messageType;
    public final String sessionId;
    public final String cueId;
    public final String senderDeviceId;
    public final String targetDeviceId;
    public final long sentUtcMs;
    public final long ttlMs;
    public final Map<String, String> payload;

    public ProtocolEnvelope(
            int protocolVersion,
            String messageId,
            String messageType,
            String sessionId,
            String cueId,
            String senderDeviceId,
            String targetDeviceId,
            long sentUtcMs,
            long ttlMs,
            Map<String, String> payload
    ) {
        this.protocolVersion = protocolVersion;
        this.messageId = safe(messageId);
        this.messageType = safe(messageType);
        this.sessionId = safe(sessionId);
        this.cueId = safe(cueId);
        this.senderDeviceId = safe(senderDeviceId);
        this.targetDeviceId = safe(targetDeviceId);
        this.sentUtcMs = sentUtcMs;
        this.ttlMs = ttlMs;
        this.payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    public static ProtocolEnvelope create(
            String type,
            String sessionId,
            String cueId,
            String sender,
            String target,
            long nowUtcMs,
            long ttlMs,
            Map<String, String> payload
    ) {
        return new ProtocolEnvelope(
                Protocol.VERSION,
                UUID.randomUUID().toString(),
                type,
                sessionId,
                cueId,
                sender,
                target,
                nowUtcMs,
                ttlMs,
                payload
        );
    }

    public boolean isExpired(long nowUtcMs) {
        if (ttlMs <= 0L) return true;
        long age = nowUtcMs - sentUtcMs;
        return age < -5_000L || age > ttlMs;
    }

    public byte[] encode() {
        StringBuilder out = new StringBuilder();
        line(out, "v", Integer.toString(protocolVersion));
        line(out, "id", messageId);
        line(out, "type", messageType);
        line(out, "session", sessionId);
        line(out, "cue", cueId);
        line(out, "sender", senderDeviceId);
        line(out, "target", targetDeviceId);
        line(out, "sent", Long.toString(sentUtcMs));
        line(out, "ttl", Long.toString(ttlMs));
        for (Map.Entry<String, String> entry : new TreeMap<>(payload).entrySet()) {
            out.append("p=")
                    .append(b64(entry.getKey()))
                    .append(':')
                    .append(b64(entry.getValue()))
                    .append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static ProtocolEnvelope decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 128 * 1024) {
            throw new IllegalArgumentException("Empty or oversized envelope");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, String> payload = new LinkedHashMap<>();
        String text = new String(bytes, StandardCharsets.UTF_8);
        for (String line : text.split("\\n")) {
            if (line.isEmpty()) continue;
            int equals = line.indexOf('=');
            if (equals <= 0) throw new IllegalArgumentException("Malformed envelope line");
            String key = line.substring(0, equals);
            String value = line.substring(equals + 1);
            if ("p".equals(key)) {
                int colon = value.indexOf(':');
                if (colon <= 0) throw new IllegalArgumentException("Malformed payload line");
                payload.put(unb64(value.substring(0, colon)), unb64(value.substring(colon + 1)));
            } else {
                fields.put(key, unb64(value));
            }
        }
        return new ProtocolEnvelope(
                parseInt(required(fields, "v"), "version"),
                required(fields, "id"),
                required(fields, "type"),
                required(fields, "session"),
                fields.getOrDefault("cue", ""),
                required(fields, "sender"),
                required(fields, "target"),
                parseLong(required(fields, "sent"), "sent"),
                parseLong(required(fields, "ttl"), "ttl"),
                payload
        );
    }

    private static void line(StringBuilder out, String key, String value) {
        out.append(key).append('=').append(b64(value)).append('\n');
    }

    private static String b64(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(safe(value).getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String value) {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid base64 field", error);
        }
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    private static int parseInt(String value, String label) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid " + label, error); }
    }

    private static long parseLong(String value, String label) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid " + label, error); }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
