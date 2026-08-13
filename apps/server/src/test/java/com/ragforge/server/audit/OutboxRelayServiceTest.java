package com.ragforge.server.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxRelayServiceTest {
    @Mock
    JdbcTemplate jdbc;

    @Test
    void publishesOnlyAfterBrokerConfirmAndMarksTheOutboxRow() throws Exception {
        UUID eventId = UUID.randomUUID();
        OutboxRelayService.OutboxRecord record = new OutboxRelayService.OutboxRecord(
                eventId, "ingestion.job.requested.v1", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, "{\"jobId\":\"00000000-0000-0000-0000-000000000001\"}",
                Instant.parse("2026-08-13T00:00:00Z"), 0);
        OutboxRelayService.OutboxPublisher publisher = (exchange, routingKey, message, processor, correlation) ->
                correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
        OutboxRelayService relay = new OutboxRelayService(jdbc, new ObjectMapper().findAndRegisterModules(), null,
                "ragforge.test.exchange", 10, (now, limit) -> List.of(record), publisher);

        assertThat(relay.publishDueBatch(10)).isEqualTo(1);
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void failedPublishLeavesTheRowUnpublishedAndSchedulesBoundedRetry() {
        OutboxRelayService.OutboxRecord record = new OutboxRelayService.OutboxRecord(
                UUID.randomUUID(), "ingestion.job.requested.v1", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, "{}", Instant.now(), 0);
        OutboxRelayService.OutboxPublisher publisher = (exchange, routingKey, message, processor, correlation) -> {
            throw new IllegalStateException("broker unavailable");
        };
        OutboxRelayService relay = new OutboxRelayService(jdbc, new ObjectMapper().findAndRegisterModules(), null,
                "ragforge.test.exchange", 10, (now, limit) -> List.of(record), publisher);

        assertThat(relay.publishDueBatch(10)).isZero();
        verify(jdbc).update(anyString(), any(Object[].class));
    }

}
