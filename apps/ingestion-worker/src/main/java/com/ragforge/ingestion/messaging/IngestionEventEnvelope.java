package com.ragforge.ingestion.messaging;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record IngestionEventEnvelope(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        UUID correlationId,
        UUID causationId,
        UUID spaceId,
        UUID aggregateId,
        JsonNode payload) {

    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "credentialRef", "secret", "password", "apiKey", "accessToken", "fullText", "rawDocument", "documentContent");

    public void validate() {
        if (eventId == null || eventType == null || occurredAt == null || producer == null
                || correlationId == null || causationId == null || spaceId == null || aggregateId == null
                || payload == null || !payload.isObject()) {
            throw new EnvelopeValidationException("ENVELOPE_REQUIRED_FIELD_MISSING");
        }
        if (!eventType.equals("ingestion.job.requested.v1")) {
            throw new EnvelopeValidationException("UNSUPPORTED_INGESTION_EVENT");
        }
        payload.fieldNames().forEachRemaining(field -> {
            if (FORBIDDEN_FIELDS.contains(field)) {
                throw new EnvelopeValidationException("FORBIDDEN_EVENT_FIELD");
            }
        });
    }
}
