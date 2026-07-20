package com.remote.DreamSync.shared;

import java.util.Comparator;
import java.util.List;

/** Stable peer selection: keep the bound device, then prefer nearby, then stable ID order. */
public final class PeerSelector {
    public static final class Candidate {
        public final String id;
        public final boolean nearby;

        public Candidate(String id, boolean nearby) {
            this.id = id == null ? "" : id;
            this.nearby = nearby;
        }
    }

    public static String select(List<Candidate> candidates, String boundId) {
        if (candidates == null || candidates.isEmpty()) return "";
        String bound = boundId == null ? "" : boundId;
        if (!bound.isEmpty()) {
            for (Candidate candidate : candidates) {
                if (candidate != null && bound.equals(candidate.id)) return bound;
            }
        }
        return candidates.stream()
                .filter(candidate -> candidate != null && !candidate.id.isEmpty())
                .sorted(Comparator.comparing((Candidate candidate) -> !candidate.nearby)
                        .thenComparing(candidate -> candidate.id))
                .map(candidate -> candidate.id)
                .findFirst()
                .orElse("");
    }

    private PeerSelector() {}
}
