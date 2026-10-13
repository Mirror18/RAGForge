package com.ragforge.server.answer.application;

import com.ragforge.server.space.infrastructure.CancellationToken;
import com.ragforge.server.space.infrastructure.EgressDecision;

import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface QueryEmbeddingProvider {
    List<Double> embed(EmbeddingRequest request, EgressDecision egressDecision, CancellationToken cancellationToken);

    record EmbeddingRequest(UUID spaceId, UUID runId, UUID correlationId, String query) {
        public EmbeddingRequest {
            if (spaceId == null || runId == null || correlationId == null || query == null || query.isBlank()) {
                throw new IllegalArgumentException("Embedding request is invalid");
            }
        }
    }
}
