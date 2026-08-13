package com.ragforge.ingestion.messaging;

import com.ragforge.ingestion.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Service
public class JdbcIngestionIdempotencyStore {
    private final JdbcTemplate jdbc;

    public JdbcIngestionIdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public ProcessResult process(IngestionEventEnvelope envelope,
                                 IngestionJobRequestedPayload payload,
                                 String stepName,
                                 String idempotencyKey,
                                 IngestionSideEffectHandler handler) {
        lockIdentity(envelope.spaceId() + ":" + payload.jobId() + ":" + payload.attemptId() + ":" + stepName + ":" + idempotencyKey);
        List<String> existing = jdbc.query("""
                SELECT id::text FROM ingestion_idempotency
                 WHERE space_id = ? AND job_id = ? AND attempt_id = ?
                   AND step_name = ? AND idempotency_key = ?
                """, (rs, rowNum) -> rs.getString("id"), envelope.spaceId(), payload.jobId(),
                payload.attemptId(), stepName, idempotencyKey);
        if (!existing.isEmpty()) {
            return ProcessResult.DUPLICATE;
        }

        handler.handle(envelope, payload);
        jdbc.update("""
                INSERT INTO ingestion_idempotency
                    (id, space_id, job_id, attempt_id, step_name, idempotency_key, result_reference, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (space_id, job_id, attempt_id, step_name, idempotency_key) DO NOTHING
                """, UuidV7.random(), envelope.spaceId(), payload.jobId(), payload.attemptId(), stepName,
                idempotencyKey, payload.documentRevisionId(), Timestamp.from(Instant.now()));
        return ProcessResult.PROCESSED;
    }

    private void lockIdentity(String identity) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", resultSet -> {
            if (resultSet.next()) {
                resultSet.getObject(1);
            }
            return null;
        }, identity);
    }

    public enum ProcessResult { PROCESSED, DUPLICATE }
}
