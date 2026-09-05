package com.ragforge.server.provider.adapter;

import java.util.List;
import java.util.Objects;

public record ProviderEmbeddingResponse(RequestIdentity identity, String model, List<Double> embedding,
                                        ProviderUsage usage) {
    public ProviderEmbeddingResponse {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(embedding, "embedding");
        if (model.isBlank() || embedding.isEmpty() || embedding.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IllegalArgumentException("Provider embedding response is invalid");
        }
        embedding = List.copyOf(embedding);
    }
}
