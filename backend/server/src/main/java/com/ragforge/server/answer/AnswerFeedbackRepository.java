package com.ragforge.server.answer;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.ragforge.server.common.ApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AnswerFeedbackRepository {
    private final JdbcTemplate jdbc;

    public AnswerFeedbackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public FeedbackRecord save(UUID spaceId, UUID runId, UUID evidenceId, UUID actorUserId,
                               String sentiment, String reason, String idempotencyKey, Long expectedVersion) {
        if (!"HELPFUL".equals(sentiment) && !"NOT_HELPFUL".equals(sentiment)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_feedback", "Invalid feedback",
                    "sentiment must be HELPFUL or NOT_HELPFUL");
        }
        Integer valid = jdbc.queryForObject("""
                SELECT COUNT(*) FROM rag_answer_citations
                WHERE space_id = ? AND run_id = ? AND evidence_id = ?
                """, Integer.class, spaceId, runId, evidenceId);
        if (valid == null || valid != 1) {
            throw new ApiException(HttpStatus.NOT_FOUND, "evidence_not_found", "Not found",
                    "The evidence is not part of the requested answer in this space");
        }
        String requestHash = sha256(runId + "|" + evidenceId + "|" + sentiment + "|" + (reason == null ? "" : reason));
        FeedbackRecord byKey = findByIdempotency(spaceId, idempotencyKey).orElse(null);
        if (byKey != null) {
            if (!requestHash.equals(byKey.requestHash()) || !byKey.runId().equals(runId)
                    || !byKey.evidenceId().equals(evidenceId)) {
                throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_conflict", "Feedback conflict",
                        "Idempotency key is already bound to another feedback");
            }
            return byKey;
        }
        FeedbackRecord existing = findForTarget(spaceId, runId, evidenceId, actorUserId).orElse(null);
        Instant now = Instant.now();
        if (existing != null) {
            if (expectedVersion == null) return existing;
            int updated = jdbc.update("""
                    UPDATE answer_feedback SET sentiment = ?, reason = ?, request_hash = ?, idempotency_key = ?,
                           version = version + 1, updated_at = ?
                    WHERE id = ? AND space_id = ? AND actor_user_id = ? AND version = ?
                    """, sentiment, reason, requestHash, idempotencyKey, Timestamp.from(now), existing.id(), spaceId,
                    actorUserId, expectedVersion);
            if (updated != 1) throw new ApiException(HttpStatus.PRECONDITION_FAILED,
                    "feedback_version_conflict", "Feedback version conflict", "Refresh feedback before changing it");
            return findByIdempotency(spaceId, idempotencyKey).orElseGet(() ->
                    findForTarget(spaceId, runId, evidenceId, actorUserId).orElseThrow());
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO answer_feedback
                    (id, space_id, run_id, evidence_id, actor_user_id, sentiment, reason,
                     idempotency_key, request_hash, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """, id, spaceId, runId, evidenceId, actorUserId, sentiment, reason,
                idempotencyKey, requestHash, Timestamp.from(now), Timestamp.from(now));
        return findByIdempotency(spaceId, idempotencyKey).orElseThrow();
    }

    public Optional<FeedbackRecord> findByIdempotency(UUID spaceId, String key) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(select("idempotency_key = ?"),
                    (rs, row) -> map(rs), spaceId, key));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private Optional<FeedbackRecord> findForTarget(UUID spaceId, UUID runId, UUID evidenceId, UUID actorUserId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(select("run_id = ? AND evidence_id = ? AND actor_user_id = ?"),
                    (rs, row) -> map(rs), spaceId, runId, evidenceId, actorUserId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private String select(String predicate) {
        return """
                SELECT id, space_id, run_id, evidence_id, actor_user_id, sentiment, reason,
                       idempotency_key, request_hash, version, created_at, updated_at
                FROM answer_feedback WHERE space_id = ? AND """ + predicate;
    }

    private FeedbackRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FeedbackRecord(rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getObject("run_id", UUID.class), rs.getObject("evidence_id", UUID.class),
                rs.getObject("actor_user_id", UUID.class), rs.getString("sentiment"), rs.getString("reason"),
                rs.getString("idempotency_key"), rs.getString("request_hash"), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is required by the runtime", exception);
        }
    }

    public record FeedbackRecord(UUID id, UUID spaceId, UUID runId, UUID evidenceId, UUID actorUserId,
                                 String sentiment, String reason, String idempotencyKey, String requestHash,
                                 long version, Instant createdAt, Instant updatedAt) {
    }
}
