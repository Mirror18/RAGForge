package com.ragforge.server.audit;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Versioned transport envelope. Content-bearing fields remain space scoped. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IngestionEventEnvelope(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        UUID correlationId,
        UUID causationId,
        UUID spaceId,
        UUID aggregateId,
        Map<String, Object> payload) {
}
