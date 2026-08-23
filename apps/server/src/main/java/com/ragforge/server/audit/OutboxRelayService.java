package com.ragforge.server.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Publishes only ingestion events from the durable outbox. Other audit events
 * remain persisted until their owning transport is introduced; they are never
 * silently routed through the ingestion queue.
 */
@Service
@EnableScheduling
@ConditionalOnProperty(name = "ragforge.outbox.relay.enabled", havingValue = "true")
public class OutboxRelayService {
    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 20;
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc;
    private final OutboxRecordLoader recordLoader;
    private final OutboxPublisher publisher;
    private final ObjectMapper objectMapper;
    private final String exchange;
    private final int batchSize;

    @Autowired
    public OutboxRelayService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            RabbitTemplate rabbitTemplate,
            @Value("${ragforge.outbox.relay.exchange:ragforge.ingestion}") String exchange,
            @Value("${ragforge.outbox.relay.batch-size:50}") int batchSize) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.exchange = exchange;
        this.batchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        this.recordLoader = this::loadDueRecords;
        this.publisher = rabbitTemplate::convertAndSend;
    }

    OutboxRelayService(JdbcTemplate jdbc, ObjectMapper objectMapper, RabbitTemplate rabbitTemplate,
                       String exchange, int batchSize, OutboxRecordLoader recordLoader,
                       OutboxPublisher publisher) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.exchange = exchange;
        this.batchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        this.recordLoader = recordLoader;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${ragforge.outbox.relay.fixed-delay-ms:1000}")
    public void scheduledRelay() {
        publishDueBatch(batchSize);
    }

    @Transactional
    public int publishDueBatch(int limit) {
        Instant now = Instant.now();
        List<OutboxRecord> records = recordLoader.load(now, Math.max(1, limit));

        int published = 0;
        for (OutboxRecord record : records) {
            try {
                String envelope = serialize(record);
                CorrelationData correlation = new CorrelationData(record.id().toString());
                publisher.publish(exchange, record.eventType(), envelope, message -> {
                    message.getMessageProperties().setContentType("application/json");
                    message.getMessageProperties().setHeader("x-ragforge-event-id", record.id().toString());
                    message.getMessageProperties().setHeader("x-ragforge-delivery-attempt",
                            record.deliveryAttempts() + 1);
                    return message;
                }, correlation);
                CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
                if (!confirm.isAck()) {
                    throw new IllegalStateException("RabbitMQ broker did not confirm outbox event");
                }
                if (correlation.getReturned() != null) {
                    throw new IllegalStateException("RabbitMQ returned outbox event without a bound queue");
                }
                jdbc.update("UPDATE outbox_events SET published_at = ?, "
                                + "delivery_attempts = delivery_attempts + 1, next_attempt_at = NULL, last_error = NULL "
                                + "WHERE id = ? AND published_at IS NULL", Timestamp.from(now), record.id());
                published++;
            } catch (RuntimeException exception) {
                recordFailure(record, now, exception);
            } catch (InterruptedException | ExecutionException | TimeoutException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                recordFailure(record, now, new IllegalStateException("RabbitMQ publisher confirmation failed", exception));
            }
        }
        return published;
    }

    private List<OutboxRecord> loadDueRecords(Instant now, int limit) {
        String dueSql = "SELECT id, event_type, aggregate_id, space_id, correlation_id, "
                + "causation_id, payload::text AS payload, occurred_at, delivery_attempts "
                + "FROM outbox_events WHERE published_at IS NULL "
                + "AND dead_lettered_at IS NULL AND event_type LIKE 'ingestion.%' "
                + "AND aggregate_id IS NOT NULL AND space_id IS NOT NULL "
                + "AND correlation_id IS NOT NULL "
                + "AND (next_attempt_at IS NULL OR next_attempt_at <= ?) "
                + "ORDER BY occurred_at, id LIMIT ? FOR UPDATE SKIP LOCKED";
        return jdbc.query(dueSql, this::mapRecord, Timestamp.from(now), limit);
    }

    private void recordFailure(OutboxRecord record, Instant now, RuntimeException exception) {
        int attempts = record.deliveryAttempts() + 1;
        String safeMessage = safeMessage(exception);
        if (attempts >= MAX_ATTEMPTS) {
            jdbc.update("UPDATE outbox_events SET delivery_attempts = ?, dead_lettered_at = ?, "
                            + "last_error = ?, next_attempt_at = NULL WHERE id = ? AND published_at IS NULL",
                    attempts, Timestamp.from(now), safeMessage, record.id());
            return;
        }
        jdbc.update("UPDATE outbox_events SET delivery_attempts = ?, next_attempt_at = ?, last_error = "
                        + "? WHERE id = ? AND published_at IS NULL",
                attempts, Timestamp.from(now.plus(backoff(attempts))), safeMessage, record.id());
    }

    private Duration backoff(int attempts) {
        long baseMillis = Math.min(MAX_BACKOFF.toMillis(), 1000L * (1L << Math.min(8, Math.max(0, attempts - 1))));
        long jitterMillis = Math.max(1, baseMillis / 4);
        long jitter = Math.floorMod(recordedJitter(attempts), jitterMillis + 1);
        return Duration.ofMillis(Math.min(MAX_BACKOFF.toMillis(), baseMillis + jitter));
    }

    private long recordedJitter(int attempts) {
        return (long) attempts * 1103515245L + 12345L;
    }

    private String serialize(OutboxRecord record) {
        try {
            Map<String, Object> payload = objectMapper.readValue(record.payload(), new TypeReference<>() { });
            UUID causationId = record.causationId() == null ? record.id() : record.causationId();
            return objectMapper.writeValueAsString(new IngestionEventEnvelope(
                    record.id(), record.eventType(), record.occurredAt(), "ragforge-server",
                    record.correlationId(), causationId, record.spaceId(), record.aggregateId(), payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Outbox ingestion payload is not valid JSON", exception);
        }
    }

    private OutboxRecord mapRecord(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp occurredAt = resultSet.getTimestamp("occurred_at");
        return new OutboxRecord(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("event_type"),
                resultSet.getObject("aggregate_id", UUID.class),
                resultSet.getObject("space_id", UUID.class),
                resultSet.getObject("correlation_id", UUID.class),
                resultSet.getObject("causation_id", UUID.class),
                resultSet.getString("payload"),
                occurredAt.toInstant(),
                resultSet.getInt("delivery_attempts"));
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return message.replaceAll("(?i)(password|secret|token|api[_-]?key|credential)[^,; ]*", "$1=[REDACTED]")
                .substring(0, Math.min(500, message.length()));
    }

    record OutboxRecord(UUID id, String eventType, UUID aggregateId, UUID spaceId,
                        UUID correlationId, UUID causationId, String payload,
                        Instant occurredAt, int deliveryAttempts) {
    }

    @FunctionalInterface
    interface OutboxRecordLoader {
        List<OutboxRecord> load(Instant now, int limit);
    }

    @FunctionalInterface
    interface OutboxPublisher {
        void publish(String exchange, String routingKey, Object message,
                     MessagePostProcessor messagePostProcessor, CorrelationData correlationData);
    }
}
