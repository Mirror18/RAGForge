package com.ragforge.ingestion.messaging;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionEventEnvelopeTest {
    @Test
    void rejectsContentAndCredentialFieldsBeforeProcessing() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("fullText", "synthetic content");
        IngestionEventEnvelope envelope = envelope(payload);

        assertThatThrownBy(envelope::validate)
                .isInstanceOf(EnvelopeValidationException.class)
                .hasMessage("FORBIDDEN_EVENT_FIELD");
    }

    @Test
    void requiresTheVersionedRequestedEventTypeAndEnvelopeIdentifiers() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        IngestionEventEnvelope envelope = new IngestionEventEnvelope(
                UUID.randomUUID(), "other.event.v1", Instant.now(), "test",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), payload);

        assertThatThrownBy(envelope::validate)
                .isInstanceOf(EnvelopeValidationException.class)
                .hasMessage("UNSUPPORTED_INGESTION_EVENT");
    }

    private IngestionEventEnvelope envelope(ObjectNode payload) {
        return new IngestionEventEnvelope(
                UUID.randomUUID(), "ingestion.job.requested.v1", Instant.now(), "test",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), payload);
    }
}
