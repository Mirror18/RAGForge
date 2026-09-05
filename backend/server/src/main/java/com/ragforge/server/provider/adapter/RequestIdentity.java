package com.ragforge.server.provider.adapter;

import java.util.Objects;
import java.util.UUID;

/** Stable identity propagated to the provider without including request content. */
public record RequestIdentity(UUID requestId, UUID correlationId, String idempotencyKey) {

    public RequestIdentity {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(correlationId, "correlationId");
        if (idempotencyKey != null
                && (idempotencyKey.isBlank() || idempotencyKey.length() > 255
                || !idempotencyKey.matches("^[A-Za-z0-9._~-]+$"))) {
            throw new IllegalArgumentException("Idempotency key is invalid");
        }
    }

    @Override
    public String toString() {
        return "RequestIdentity[requestId=%s, correlationId=%s, idempotencyKey=%s]"
                .formatted(requestId, correlationId, idempotencyKey == null ? "<none>" : "<present>");
    }
}
