package com.ragforge.server.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RunEventFanoutTest {
    @Test
    void publishesOnlyTheMinimalEnvelope() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RunEventFanout fanout = new RunEventFanout(redis, new ObjectMapper(),
                mock(RedisConnectionFactory.class), "test:run-events", true);
        UUID spaceId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        RunEvent event = new RunEvent(UUID.randomUUID(), 3, runId, spaceId, UUID.randomUUID(),
                java.time.Instant.parse("2026-08-22T00:00:00Z"), "answer.done", 1,
                "{\"answer\":\"must not cross pubsub\"}");

        fanout.publishAfterCommit(event);

        var body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(org.mockito.ArgumentMatchers.eq("test:run-events"), body.capture());
        assertThat(body.getValue()).contains("run-event-hint.v1", event.eventId().toString(),
                event.runId().toString(), event.spaceId().toString()).doesNotContain("must not cross pubsub")
                .doesNotContain("answer");
    }

    @Test
    void envelopeRejectsAChangedSchema() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new RunEventFanoutEnvelope(
                "run-event-hint.v2", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
