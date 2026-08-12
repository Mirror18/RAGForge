package com.ragforge.server.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditOutboxService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditOutboxService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UUID record(String eventType, UUID actorUserId, UUID spaceId, UUID aggregateId,
                       UUID correlationId, Map<String, ?> payload) {
        Instant occurredAt = Instant.now();
        UUID auditId = UuidV7.random();
        UUID eventId = UuidV7.random();
        String serialized = serialize(payload);
        jdbc.update("""
                        INSERT INTO audit_events
                            (id, event_type, actor_user_id, space_id, aggregate_id, correlation_id, payload, occurred_at)
                        VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                        """, auditId, eventType, actorUserId, spaceId, aggregateId, correlationId,
                serialized, Timestamp.from(occurredAt));
        jdbc.update("""
                        INSERT INTO outbox_events
                            (id, event_type, aggregate_id, space_id, correlation_id, payload, occurred_at)
                        VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                        """, eventId, eventType, aggregateId, spaceId, correlationId, serialized,
                Timestamp.from(occurredAt));
        return eventId;
    }

    private String serialize(Map<String, ?> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Audit payload is not serializable", exception);
        }
    }
}
