package com.ragforge.server.provider.adapter;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Verified response from a rerank provider; scores retain candidate identity. */
public record ProviderRerankResponse(String modelName, Set<ModelCapability> capabilities,
                                     List<ScoredCandidate> candidates) {
    public record ScoredCandidate(UUID candidateId, double score) {
        public ScoredCandidate {
            Objects.requireNonNull(candidateId, "candidateId");
            if (!Double.isFinite(score)) {
                throw new IllegalArgumentException("rerank score must be finite");
            }
        }
    }

    public ProviderRerankResponse {
        if (modelName == null || modelName.isBlank() || capabilities == null
                || !capabilities.contains(ModelCapability.RERANK) || candidates == null || candidates.isEmpty()
                || candidates.size() > ProviderRerankRequest.MAX_CANDIDATES) {
            throw new IllegalArgumentException("rerank response metadata is invalid");
        }
        capabilities = Set.copyOf(capabilities);
        candidates = List.copyOf(candidates);
    }
}
