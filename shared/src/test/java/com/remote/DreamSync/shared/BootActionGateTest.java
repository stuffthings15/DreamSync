package com.remote.DreamSync.shared;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BootActionGateTest {
    @Test public void acceptsOnlyExpectedSystemActions() {
        assertTrue(BootActionGate.accepts(BootActionGate.BOOT_COMPLETED));
        assertTrue(BootActionGate.accepts(BootActionGate.PACKAGE_REPLACED));
        assertFalse(BootActionGate.accepts(null));
        assertFalse(BootActionGate.accepts(""));
        assertFalse(BootActionGate.accepts("com.attacker.START"));
    }
}
