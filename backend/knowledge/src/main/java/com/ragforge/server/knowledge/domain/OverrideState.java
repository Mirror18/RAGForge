package com.ragforge.server.knowledge.domain;
import com.ragforge.server.knowledge.application.ChunkOverrideTransitions;

/** Auditable state of a manual chunk override (see {@link ChunkOverrideTransitions}). */
public enum OverrideState {
    NONE,
    ACTIVE,
    NEEDS_REVIEW,
    DISCARDED
}
