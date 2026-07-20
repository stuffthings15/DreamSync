package com.remote.DreamSync.shared;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.Test;

public class ProtocolPropertyTest {
    @Test public void reorderedUniqueMessagesAreAcceptedOnce() {
        List<ProtocolEnvelope> messages = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            messages.add(ProtocolEnvelope.create(Protocol.TYPE_CUE_REQUEST,
                    "session", "cue-" + i, "watch", "phone",
                    10_000L, 15_000L, Map.of("gain", "0.04")));
        }
        Collections.shuffle(messages, new Random(7L));
        MessageGuard guard = new MessageGuard();
        for (ProtocolEnvelope message : messages) {
            assertEquals(MessageGuard.Result.ACCEPT,
                    guard.check(message, "session", "phone", 11_000L));
            assertEquals(MessageGuard.Result.DUPLICATE,
                    guard.check(message, "session", "phone", 11_001L));
        }
    }

    @Test public void hostileEnvelopesFailClosed() {
        MessageGuard guard = new MessageGuard();
        ProtocolEnvelope wrongVersion = new ProtocolEnvelope(999, "m", "CUE_REQUEST",
                "session", "cue", "watch", "phone", 1_000L, Long.MAX_VALUE, Map.of());
        assertEquals(MessageGuard.Result.BAD_VERSION,
                guard.check(wrongVersion, "session", "phone", 2_000L));
        ProtocolEnvelope wrongDevice = ProtocolEnvelope.create(Protocol.TYPE_CUE_REQUEST,
                "session", "cue", "watch", "other", 1_000L, 15_000L, Map.of());
        assertEquals(MessageGuard.Result.WRONG_DEVICE,
                guard.check(wrongDevice, "session", "phone", 2_000L));
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolEnvelope.decode("not-an-envelope".getBytes()));
    }

    @Test public void expiryWrongSessionAndReconnectFailClosed() {
        ProtocolEnvelope request = new ProtocolEnvelope(
                Protocol.VERSION, "message", Protocol.TYPE_CUE_REQUEST,
                "session-a", "cue-a", "watch", "phone",
                1_000L, 15_000L, Map.of("cueType", "DEEP", "gain", "0.04"));
        MessageGuard firstConnection = new MessageGuard();
        assertEquals(MessageGuard.Result.EXPIRED,
                firstConnection.check(request, "session-a", "phone", 20_001L));
        assertEquals(MessageGuard.Result.WRONG_SESSION,
                new MessageGuard().check(request, "session-b", "phone", 2_000L));

        MessageGuard reconnected = new MessageGuard();
        assertEquals(MessageGuard.Result.WRONG_SESSION,
                reconnected.check(request, "fresh-session", "phone", 2_000L));
    }
}
