package com.remote.DreamSync.shared;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public final class PeerSelectorTest {
    @Test public void keepsBoundPeerEvenWhenAnotherPeerIsNearby() {
        assertEquals("bound", PeerSelector.select(List.of(
                new PeerSelector.Candidate("new", true),
                new PeerSelector.Candidate("bound", false)), "bound"));
    }

    @Test public void selectionIsNearbyFirstAndDeterministic() {
        List<PeerSelector.Candidate> forward = List.of(
                new PeerSelector.Candidate("z", false),
                new PeerSelector.Candidate("b", true),
                new PeerSelector.Candidate("a", true));
        List<PeerSelector.Candidate> reversed = List.of(
                new PeerSelector.Candidate("a", true),
                new PeerSelector.Candidate("b", true),
                new PeerSelector.Candidate("z", false));
        assertEquals("a", PeerSelector.select(forward, "missing"));
        assertEquals("a", PeerSelector.select(reversed, "missing"));
        assertEquals("", PeerSelector.select(List.of(), "missing"));
    }
}
