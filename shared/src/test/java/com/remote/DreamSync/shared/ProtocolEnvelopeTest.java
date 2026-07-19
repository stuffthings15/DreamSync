package com.remote.DreamSync.shared;

import static org.junit.Assert.*;

import java.util.Map;
import org.junit.Test;

public class ProtocolEnvelopeTest {
    @Test public void roundTripPreservesFields() {
        ProtocolEnvelope original = ProtocolEnvelope.create(
                Protocol.TYPE_CUE_REQUEST, "s1", "c1", "watch", "phone",
                1_000L, 15_000L, Map.of("cueType", "DEEP", "gain", "0.04"));
        ProtocolEnvelope decoded = ProtocolEnvelope.decode(original.encode());
        assertEquals(original.messageId, decoded.messageId);
        assertEquals("s1", decoded.sessionId);
        assertEquals("c1", decoded.cueId);
        assertEquals("DEEP", decoded.payload.get("cueType"));
    }

    @Test public void guardRejectsDuplicateExpiredAndWrongSession() {
        MessageGuard guard = new MessageGuard();
        ProtocolEnvelope envelope = ProtocolEnvelope.create(
                Protocol.TYPE_CUE_REQUEST, "s1", "c1", "watch", "phone",
                1_000L, 15_000L, Map.of());
        assertEquals(MessageGuard.Result.ACCEPT, guard.check(envelope, "s1", "phone", 2_000L));
        assertEquals(MessageGuard.Result.DUPLICATE, guard.check(envelope, "s1", "phone", 2_001L));
        ProtocolEnvelope wrong = ProtocolEnvelope.create(
                Protocol.TYPE_CUE_REQUEST, "s2", "c2", "watch", "phone",
                1_000L, 15_000L, Map.of());
        assertEquals(MessageGuard.Result.WRONG_SESSION, guard.check(wrong, "s1", "phone", 2_000L));
        ProtocolEnvelope expired = ProtocolEnvelope.create(
                Protocol.TYPE_CUE_REQUEST, "s1", "c3", "watch", "phone",
                1_000L, 100L, Map.of());
        assertEquals(MessageGuard.Result.EXPIRED, guard.check(expired, "s1", "phone", 2_000L));
    }
}
