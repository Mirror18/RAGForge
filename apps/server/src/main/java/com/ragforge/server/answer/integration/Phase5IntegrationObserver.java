package com.ragforge.server.answer.integration;

import com.ragforge.server.provider.adapter.EgressDecision;

import java.util.UUID;

/**
 * Redacted observation seam for production wiring. Implementations must not log
 * query, prompt, evidence material, credentials, or provider response bodies.
 */
@FunctionalInterface
public interface Phase5IntegrationObserver {
    void record(Decision decision);

    record Decision(UUID spaceId, UUID runId, UUID correlationId, String component,
                    String outcome, String reason, EgressDecision egressDecision) {
        public Decision {
            if (spaceId == null || runId == null || correlationId == null
                    || component == null || component.isBlank()
                    || outcome == null || outcome.isBlank()
                    || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Integration observation is incomplete");
            }
        }
    }

    static Phase5IntegrationObserver noop() {
        return ignored -> { };
    }
}
