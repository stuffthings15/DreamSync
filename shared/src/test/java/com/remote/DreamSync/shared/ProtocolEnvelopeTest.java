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

    @Test public void durableWatchResultAllowsReasonableClockSkew() {
        ProtocolEnvelope result = ProtocolEnvelope.create(
                Protocol.TYPE_SYSTEM_CHECK_RESULT, "enrollment", "check", "watch", "phone",
                120_000L, Protocol.SYSTEM_CHECK_TTL_MS, Map.of("status", "PASS"));

        assertEquals(MessageGuard.Result.ACCEPT,
                new MessageGuard().check(result, "enrollment", "phone", "watch", 1_000L));
    }

    @Test public void cueMessagesKeepStrictFutureClockLimit() {
        ProtocolEnvelope cue = ProtocolEnvelope.create(
                Protocol.TYPE_CUE_REQUEST, "session", "cue", "watch", "phone",
                20_000L, Protocol.CUE_TTL_MS, Map.of("cueType", "DEEP"));

        assertEquals(MessageGuard.Result.EXPIRED,
                new MessageGuard().check(cue, "session", "phone", "watch", 1_000L));
    }

    @Test public void debugSnapshotUsesBoundedAuthenticatedControlPaths() {
        assertEquals("DEBUG_SNAPSHOT_REQUEST", Protocol.TYPE_DEBUG_SNAPSHOT_REQUEST);
        assertEquals("DEBUG_SNAPSHOT_RESPONSE", Protocol.TYPE_DEBUG_SNAPSHOT_RESPONSE);
        assertEquals("/dreamsync/debug/snapshot/request", Protocol.PATH_DEBUG_SNAPSHOT_REQUEST);
        assertEquals("/dreamsync/debug/snapshot/response/",
                Protocol.PATH_DEBUG_SNAPSHOT_RESPONSE_PREFIX);
        assertTrue(Protocol.DEBUG_SNAPSHOT_TTL_MS <= Protocol.CONTROL_TTL_MS);
    }
}
