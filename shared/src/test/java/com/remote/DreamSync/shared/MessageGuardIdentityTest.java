package com.remote.DreamSync.shared;

import static org.junit.Assert.assertEquals;

import java.util.Map;
import org.junit.Test;

public final class MessageGuardIdentityTest {
    @Test public void senderIdentityMustMatchBoundPeer() {
        ProtocolEnvelope envelope = new ProtocolEnvelope(
                Protocol.VERSION, "message-1", Protocol.TYPE_HEARTBEAT,
                "session-1", "", "wrong-watch", "phone-1",
                1_000L, 5_000L, Map.of());
        assertEquals(MessageGuard.Result.WRONG_DEVICE,
                new MessageGuard().check(
                        envelope, "session-1", "phone-1", "watch-1", 2_000L));
    }

    @Test public void blankProtocolIdentityIsMalformed() {
        ProtocolEnvelope envelope = new ProtocolEnvelope(
                Protocol.VERSION, "", Protocol.TYPE_HEARTBEAT,
                "session-1", "", "watch-1", "phone-1",
                1_000L, 5_000L, Map.of());
        assertEquals(MessageGuard.Result.MALFORMED,
                new MessageGuard().check(
                        envelope, "session-1", "phone-1", "watch-1", 2_000L));
    }
}
