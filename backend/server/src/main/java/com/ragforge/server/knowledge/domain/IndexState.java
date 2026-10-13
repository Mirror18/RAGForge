package com.ragforge.server.knowledge.domain;
import com.ragforge.server.knowledge.application.IndexStateTransitions;

/** IndexVersion lifecycle (see {@link IndexStateTransitions}). */
public enum IndexState {
    BUILDING,
    VALIDATING,
    READY,
    ACTIVE,
    RETIRED,
    FAILED
}
