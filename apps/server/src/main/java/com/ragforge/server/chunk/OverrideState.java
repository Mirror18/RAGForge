package com.ragforge.server.chunk;

/** Auditable state of a manual chunk override (see {@link ChunkOverrideTransitions}). */
public enum OverrideState {
    NONE,
    ACTIVE,
    NEEDS_REVIEW,
    DISCARDED
}
