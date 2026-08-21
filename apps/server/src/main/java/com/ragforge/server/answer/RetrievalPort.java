package com.ragforge.server.answer;

import com.ragforge.server.provider.adapter.CancellationToken;

import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface RetrievalPort {
    EvidenceBundleSnapshot retrieve(RetrievalRequest request, CancellationToken cancellationToken);

    record RetrievalRequest(UUID spaceId, UUID runId, UUID correlationId, String query, List<Double> queryEmbedding) {
        public RetrievalRequest {
            if (spaceId == null || runId == null || correlationId == null || query == null || query.isBlank()
                    || queryEmbedding == null || queryEmbedding.isEmpty()
                    || queryEmbedding.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
                throw new IllegalArgumentException("Retrieval request is invalid");
            }
            queryEmbedding = List.copyOf(queryEmbedding);
        }
    }
}
