package com.ragforge.server.chunk;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ChunkOverride state machine: NONE -> ACTIVE -> NEEDS_REVIEW -> ACTIVE | DISCARDED.
 *
 * <p>A source revision update never silently re-applies an old override to new
 * text; the old override must first transition to NEEDS_REVIEW and be resolved
 * by a human. Terminal DISCARDED cannot return to ACTIVE.</p>
 */
public final class ChunkOverrideTransitions {

    private static final Map<OverrideState, Set<OverrideState>> ALLOWED = new EnumMap<>(OverrideState.class);

    static {
        ALLOWED.put(OverrideState.NONE, Set.of(OverrideState.ACTIVE));
        ALLOWED.put(OverrideState.ACTIVE, Set.of(OverrideState.NEEDS_REVIEW));
        ALLOWED.put(OverrideState.NEEDS_REVIEW, Set.of(OverrideState.ACTIVE, OverrideState.DISCARDED));
        ALLOWED.put(OverrideState.DISCARDED, Set.of());
    }

    private ChunkOverrideTransitions() {
    }

    /** Returns true when the state machine permits {@code from -> to}. */
    public static boolean canTransition(OverrideState from, OverrideState to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /** Throws IllegalArgumentException for a forbidden transition. */
    public static OverrideState requireTransition(OverrideState from, OverrideState to) {
        if (!canTransition(from, to)) {
            throw new IllegalArgumentException(
                    "forbidden chunk override transition " + from + " -> " + to);
        }
        return to;
    }

    /** An override that targets a different revision than its child chunk must start in NEEDS_REVIEW. */
    public static OverrideState initialStateForRevision(UUID documentRevisionId, UUID childDocumentRevisionId) {
        if (!documentRevisionId.equals(childDocumentRevisionId)) {
            return OverrideState.NEEDS_REVIEW;
        }
        return OverrideState.ACTIVE;
    }
}
