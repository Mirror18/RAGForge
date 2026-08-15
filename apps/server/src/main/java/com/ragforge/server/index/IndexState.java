package com.ragforge.server.index;

/** IndexVersion lifecycle (see {@link IndexStateTransitions}). */
public enum IndexState {
    BUILDING,
    VALIDATING,
    READY,
    ACTIVE,
    RETIRED,
    FAILED
}
