package com.ragforge.server.run;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable event envelope retained for a single space-scoped run. */
public record RunEvent(UUID eventId, long sequence, UUID runId, UUID spaceId, UUID correlationId,
                       Instant occurredAt, String type, int version,
                       @JsonProperty("payload") String payloadJson) {
    public RunEvent {
        Objects.requireNonNull(eventId, "eventId");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        PayloadPolicy.validate(payloadJson);
    }

    /** Alias used by callers that treat the payload as the public event field. */
    @JsonIgnore
    public String payload() {
        return payloadJson;
    }
}
