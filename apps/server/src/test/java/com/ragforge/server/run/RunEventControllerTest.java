package com.ragforge.server.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RunEventControllerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesSseEnvelopeWithPublicContractFieldsAndNoSensitiveKeys() throws Exception {
        RunEventController controller = new RunEventController(mock(RunEventService.class), objectMapper);
        RunEvent event = new RunEvent(UUID.randomUUID(), 7, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-08-13T00:00:00Z"), "run.status", 1,
                "{\"status\":\"CANCELLED\",\"text\":\"safe\"}");

        JsonNode envelope = controller.eventEnvelope(event);
        String serialized = objectMapper.writeValueAsString(envelope);

        assertThat(envelope.get("id").asText()).isEqualTo(event.eventId().toString());
        assertThat(envelope.has("eventId")).isFalse();
        assertThat(envelope.get("version").asText()).isEqualTo("v1");
        assertThat(envelope.get("type").asText()).isEqualTo("run.status");
        assertThat(envelope.get("payload").get("status").asText()).isEqualTo("CANCELLED");
        assertThat(serialized).doesNotContain("secret", "apiKey", "accessToken", "documentContent", "fullText",
                "rawDocument", "password");
    }
}
