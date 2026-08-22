package com.ragforge.server.provider.adapter;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ProviderEmbeddingRequest(UUID spaceId, RequestIdentity identity, String model, String input,
                                       Duration timeout, Set<ModelCapability> requiredCapabilities) {
    public ProviderEmbeddingRequest {
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(requiredCapabilities, "requiredCapabilities");
        if (model.isBlank() || model.length() > 200 || input.isBlank() || input.length() > 32_000
                || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Provider embedding request is invalid");
        }
        requiredCapabilities = Set.copyOf(requiredCapabilities);
    }

    public ProviderEmbeddingRequest(UUID spaceId, RequestIdentity identity, String model, String input,
                                    Duration timeout) {
        this(spaceId, identity, model, input, timeout, Set.of(ModelCapability.EMBEDDING));
    }
}
