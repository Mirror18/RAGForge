package com.ragforge.server.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.common.CorrelationIdFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunEventControllerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesSseEnvelopeWithPublicContractFieldsAndNoSensitiveKeys() throws Exception {
        RunEventController controller = new RunEventController(mock(RunEventService.class), objectMapper,
                mock(RunExecutionService.class));
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

    @Test
    void initialSnapshotIsACompleteSseEventAndResponsePreservesCorrelationHeaders() throws Exception {
        RunEventService service = mock(RunEventService.class);
        RunEventController controller = new RunEventController(service, objectMapper, mock(RunExecutionService.class));
        UUID runId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        RunEvent snapshot = new RunEvent(UUID.randomUUID(), 1, runId, spaceId, correlationId,
                Instant.parse("2026-08-13T00:00:00Z"), "run.snapshot", 1,
                "{\"status\":\"RUNNING\",\"reason\":\"initial\"}");
        RunEventStore.Subscription subscription = mock(RunEventStore.Subscription.class);
        when(service.openStream(eq(spaceId), eq(runId), eq(null), any()))
                .thenReturn(new RunEventStore.OpenedStream(
                        new RunEventStore.ReplayResult(List.of(snapshot), RunEventStore.CursorStatus.NO_CURSOR,
                                null, 1, 1), subscription));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CorrelationIdFilter.ATTRIBUTE, correlationId.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.events(spaceId, runId, null, request, response);

        JsonNode envelope = controller.eventEnvelope(snapshot);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(correlationId.toString());
        assertThat(envelope.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "sequence", "runId", "spaceId", "correlationId",
                        "occurredAt", "type", "version", "payload");
        assertThat(envelope.get("type").asText()).isEqualTo("run.snapshot");
        assertThat(envelope.get("version").asText()).isEqualTo("v1");
        assertThat(objectMapper.writeValueAsString(envelope))
                .doesNotContain("secret", "apiKey", "accessToken", "documentContent", "fullText",
                        "rawDocument", "password");
    }
}
