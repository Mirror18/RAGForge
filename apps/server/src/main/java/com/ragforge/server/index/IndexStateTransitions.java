package com.ragforge.server.index;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * IndexVersion state machine: BUILDING -> VALIDATING -> READY -> ACTIVE -> RETIRED,
 * with FAILED reachable from any build/validate step.
 *
 * <p>ACTIVE is only reachable when validation passed (sample retrieval and space
 * filter both green). Publishing switches the PostgreSQL active pointer
 * atomically; the old index stays retained for at least 24 hours.</p>
 */
public final class IndexStateTransitions {

    private static final Map<IndexState, Set<IndexState>> ALLOWED = new EnumMap<>(IndexState.class);

    static {
        ALLOWED.put(IndexState.BUILDING, Set.of(IndexState.VALIDATING, IndexState.FAILED));
        ALLOWED.put(IndexState.VALIDATING, Set.of(IndexState.READY, IndexState.FAILED));
        ALLOWED.put(IndexState.READY, Set.of(IndexState.ACTIVE, IndexState.FAILED));
        ALLOWED.put(IndexState.ACTIVE, Set.of(IndexState.RETIRED));
        ALLOWED.put(IndexState.RETIRED, Set.of());
        ALLOWED.put(IndexState.FAILED, Set.of());
    }

    private IndexStateTransitions() {
    }

    /** Returns true when the state machine permits {@code from -> to}. */
    public static boolean canTransition(IndexState from, IndexState to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /** Throws IllegalArgumentException for a forbidden transition. */
    public static IndexState requireTransition(IndexState from, IndexState to) {
        if (!canTransition(from, to)) {
            throw new IllegalArgumentException("forbidden index version transition " + from + " -> " + to);
        }
        return to;
    }

    /** ACTIVE requires a passed validation record (sample retrieval and space filter). */
    public static void requireActivationEligible(IndexValidation validation) {
        if (validation == null) {
            throw new IllegalArgumentException("index cannot become ACTIVE without a validation record");
        }
        if (!validation.sampleRetrievalPassed() || !validation.spaceFilterPassed()) {
            throw new IllegalArgumentException(
                    "index cannot become ACTIVE until sample retrieval and space filter validation passed");
        }
    }
}
