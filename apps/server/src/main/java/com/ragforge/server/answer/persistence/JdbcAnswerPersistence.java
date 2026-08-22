package com.ragforge.server.answer.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.answer.Abstention;
import com.ragforge.server.answer.Answer;
import com.ragforge.server.answer.AnswerPersistencePort;
import com.ragforge.server.answer.AnswerProvenance;
import com.ragforge.server.answer.AnswerStatus;
import com.ragforge.server.answer.Citation;
import com.ragforge.server.answer.Claim;
import com.ragforge.server.retrieval.EvidenceBundle;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL answer-history projection. Every read includes space_id and every
 * child write carries the answer's space/run lineage. This class deliberately
 * has no method that accepts a prompt, provider body, document text, URL, or
 * arbitrary citation metadata.
 */
@Repository
public class JdbcAnswerPersistence implements AnswerPersistencePort {
    private static final Duration DEFAULT_RETENTION = Duration.ofDays(30);
    private static final TypeReference<List<UUID>> UUID_LIST = new TypeReference<>() { };
    private static final String BANNED_CONTENT =
            "(?i)(raw_prompt|raw_document|raw_output|request_body|response_body|provider_body|fulltext|"
                    + "authorization|cookie|https?://|www\\.|\\.pdf|\\.docx|\\.md)";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Duration retention;

    @Autowired
    public JdbcAnswerPersistence(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this(jdbc, objectMapper, DEFAULT_RETENTION);
    }

    public JdbcAnswerPersistence(JdbcTemplate jdbc, ObjectMapper objectMapper, Duration retention) {
        if (jdbc == null || objectMapper == null || retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("answer persistence dependencies and retention are required");
        }
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.retention = retention;
    }

    @Override
    public Optional<PersistedAnswer> find(UUID spaceId, String idempotencyKey) {
        if (spaceId == null || idempotencyKey == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jdbc.queryForObject(answerSelect("idempotency_key = ?"),
                    (rs, row) -> mapPersisted(rs), spaceId, idempotencyKey));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Compatibility path for the original redacted port. A completed record
     * cannot be represented without its answer body, so callers must use the
     * explicit Answer overload instead of silently losing history.
     */
    @Override
    @Transactional
    public PersistedAnswer saveIfAbsent(PersistedAnswer record) {
        if (record == null) {
            throw new IllegalArgumentException("persisted answer is required");
        }
        if (record.status() == AnswerStatus.COMPLETED) {
            throw new IllegalArgumentException("completed answer requires the full Answer history overload");
        }
        Instant createdAt = Instant.now();
        insertAnswer(record.answerId(), record.spaceId(), record.runId(), record.provenance().correlationId(),
                record.idempotencyKey(), record.status(), null, record.answerHash(), record.citationHash(),
                record.provenance(), "[]", createdAt, createdAt.plus(retention));
        return find(record.spaceId(), record.idempotencyKey()).orElseThrow();
    }

    @Override
    @Transactional
    public PersistedAnswer saveIfAbsent(Answer answer) {
        validateAnswer(answer);
        PersistedAnswer existing = find(answer.spaceId(), answer.idempotencyKey()).orElse(null);
        if (existing != null) {
            return existing;
        }
        String answerHash = sha256(answer.answerText() == null ? "" : answer.answerText());
        String citationHash = sha256(citationCanonical(answer.citations()));
        Instant createdAt = answer.createdAt();
        insertAnswer(answer.answerId(), answer.spaceId(), answer.runId(), answer.correlationId(),
                answer.idempotencyKey(), answer.status(), answer.answerText(), answerHash, citationHash,
                answer.provenance(), json(answer.toolCallIds()), createdAt, createdAt.plus(retention));
        PersistedAnswer inserted = find(answer.spaceId(), answer.idempotencyKey()).orElseThrow();
        if (!inserted.answerId().equals(answer.answerId())) {
            return inserted;
        }
        insertChildren(answer);
        return inserted;
    }

    @Override
    public Optional<Answer> findAnswer(UUID spaceId, UUID answerId) {
        if (spaceId == null || answerId == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jdbc.queryForObject(answerSelect("id = ?"),
                    (rs, row) -> mapAnswer(rs), spaceId, answerId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Answer> findAnswerByRun(UUID spaceId, UUID runId) {
        if (spaceId == null || runId == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jdbc.queryForObject(answerSelect("run_id = ?"),
                    (rs, row) -> mapAnswer(rs), spaceId, runId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<CitationPreview> findCitationPreview(UUID spaceId, UUID runId, UUID evidenceId) {
        if (spaceId == null || runId == null || evidenceId == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT c.evidence_id, c.claim_id, c.space_id, c.run_id,
                           c.evidence_bundle_id, c.evidence_bundle_version, c.evidence_bundle_hash,
                           c.index_version_id, c.retrieval_profile_id, c.retrieval_profile_version,
                           c.document_revision_id, c.parent_chunk_id, c.child_chunk_id,
                           c.content_ref, c.text_hash, c.anchor,
                           c.answer_char_start, c.answer_char_end, c.created_at
                    FROM rag_answer_citations c
                    JOIN rag_answers a ON a.id = c.answer_id AND a.space_id = c.space_id
                    WHERE c.space_id = ? AND c.run_id = ? AND c.evidence_id = ?
                    ORDER BY c.created_at, c.id
                    LIMIT 1
                    """, (rs, row) -> mapCitationPreview(rs), spaceId, runId, evidenceId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public AnswerEvent appendEvent(AnswerEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("answer event is required");
        }
        validateSafeJson(event.metadataJson());
        Optional<AnswerEvent> existing = findEvent(event.spaceId(), event.answerId(), event.sequence());
        if (existing.isPresent()) {
            if (!existing.get().eventId().equals(event.eventId())
                    || !existing.get().payloadHash().equals(event.payloadHash())) {
                throw new IllegalStateException("answer event sequence is already occupied");
            }
            return existing.get();
        }
        jdbc.update("""
                INSERT INTO rag_answer_events
                    (id, answer_id, space_id, run_id, sequence_no, event_type, payload_hash, metadata, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, event.eventId(), event.answerId(), event.spaceId(), event.runId(), event.sequence(),
                event.type().name(), event.payloadHash(), event.metadataJson(), timestamp(event.createdAt()));
        return findEvent(event.spaceId(), event.answerId(), event.sequence()).orElseThrow();
    }

    @Override
    public List<AnswerEvent> replayEvents(UUID spaceId, UUID runId, long afterSequence) {
        if (spaceId == null || runId == null || afterSequence < -1) {
            return List.of();
        }
        return jdbc.query("""
                SELECT e.id, e.answer_id, e.space_id, e.run_id, e.sequence_no,
                       e.event_type, e.payload_hash, e.metadata, e.created_at
                FROM rag_answer_events e
                WHERE e.space_id = ? AND e.run_id = ? AND e.sequence_no > ?
                ORDER BY e.sequence_no, e.id
                """, (rs, row) -> mapEvent(rs), spaceId, runId, afterSequence);
    }

    @Override
    public int purgeExpired(UUID spaceId, Instant now) {
        if (spaceId == null || now == null) {
            throw new IllegalArgumentException("space and purge time are required");
        }
        Integer deleted = jdbc.queryForObject("SELECT ragforge_purge_expired_answers(?, ?)", Integer.class,
                spaceId, timestamp(now));
        return deleted == null ? 0 : deleted;
    }

    private void insertAnswer(UUID answerId, UUID spaceId, UUID runId, UUID correlationId,
                              String idempotencyKey, AnswerStatus status, String answerText,
                              String answerHash, String citationHash, AnswerProvenance provenance,
                              String toolCallIdsJson, Instant createdAt, Instant retentionDeadline) {
        jdbc.update("""
                INSERT INTO rag_answers
                    (id, space_id, run_id, correlation_id, idempotency_key, schema_version, status,
                     answer_text, answer_hash, citation_hash, evidence_bundle_id, evidence_bundle_version,
                     evidence_bundle_hash, evidence_bundle_ref, index_version_id, retrieval_profile_id,
                     retrieval_profile_version, rag_prompt_version_id, prompt_hash, model_route_version_id,
                     model_profile_version_id, model_version, tool_schema_versions, tool_call_ids, dataset_hash, config_hash,
                     trace_id, retention_deadline, created_at)
                VALUES (?, ?, ?, ?, ?, 'v1', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?, ?)
                ON CONFLICT (space_id, idempotency_key) DO NOTHING
                """, answerId, spaceId, runId, correlationId, idempotencyKey, status.name(), answerText,
                answerHash, citationHash, provenance.evidenceBundleId(), provenance.evidenceBundleVersion(),
                provenance.evidenceBundleHash(), provenance.evidenceBundleRef(), provenance.indexVersionId(),
                provenance.retrievalProfileId(), provenance.retrievalProfileVersion(), provenance.ragPromptVersionId(),
                provenance.promptHash(), provenance.modelRouteVersionId(), provenance.modelProfileVersionId(),
                provenance.modelVersion(), provenance.toolSchemaVersionsJson(), toolCallIdsJson,
                provenance.datasetHash(), provenance.configHash(), provenance.traceId(), timestamp(retentionDeadline),
                timestamp(createdAt));
    }

    private void insertChildren(Answer answer) {
        for (Claim claim : answer.claims()) {
            jdbc.update("""
                    INSERT INTO rag_answer_claims
                        (id, answer_id, space_id, run_id, claim_text, citation_tokens,
                         answer_char_start, answer_char_end, created_at)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                    """, claim.claimId(), answer.answerId(), answer.spaceId(), answer.runId(), claim.claimText(),
                    json(claim.citationTokens()), claim.answerCharStart(), claim.answerCharEnd(),
                    timestamp(answer.createdAt()));
        }
        for (Citation citation : answer.citations()) {
            jdbc.update("""
                    INSERT INTO rag_answer_citations
                        (id, answer_id, claim_id, space_id, run_id, evidence_id, evidence_bundle_id,
                         evidence_bundle_version, evidence_bundle_hash, index_version_id, retrieval_profile_id,
                         retrieval_profile_version, document_revision_id, parent_chunk_id, child_chunk_id,
                         content_ref, text_hash, anchor, answer_char_start, answer_char_end, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                    """, UUID.randomUUID(), answer.answerId(), citation.claimId(), answer.spaceId(), answer.runId(),
                    citation.evidenceId(), citation.evidenceBundleId(), citation.evidenceBundleVersion(),
                    citation.evidenceBundleHash(), citation.indexVersionId(), citation.retrievalProfileId(),
                    citation.retrievalProfileVersion(), citation.documentRevisionId(), citation.parentChunkId(),
                    citation.childChunkId(), citation.contentRef(), citation.textHash(), json(citation.anchor()),
                    citation.answerCharStart(), citation.answerCharEnd(), timestamp(answer.createdAt()));
        }
        if (answer.abstention() != null) {
            jdbc.update("""
                    INSERT INTO rag_answer_abstentions
                        (id, answer_id, space_id, run_id, reason_code, evidence_ids, message, created_at)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                    """, answer.abstention().abstentionId(), answer.answerId(), answer.spaceId(), answer.runId(),
                    answer.abstention().reasonCode().name(), json(answer.abstention().evidenceIds()),
                    answer.abstention().message(), timestamp(answer.createdAt()));
        }
    }

    private Answer mapAnswer(ResultSet rs) throws SQLException {
        UUID spaceId = rs.getObject("space_id", UUID.class);
        UUID runId = rs.getObject("run_id", UUID.class);
        UUID answerId = rs.getObject("id", UUID.class);
        String idempotencyKey = rs.getString("idempotency_key");
        AnswerProvenance provenance = mapProvenance(rs);
        List<Claim> claims = jdbc.query("""
                SELECT id, claim_text, citation_tokens, answer_char_start, answer_char_end
                FROM rag_answer_claims WHERE space_id = ? AND answer_id = ? AND run_id = ? ORDER BY id
                """, (child, row) -> new Claim("v1", child.getObject("id", UUID.class), spaceId,
                rs.getObject("correlation_id", UUID.class), runId, idempotencyKey, child.getString("claim_text"),
                readJson(child.getString("citation_tokens"), UUID_LIST), child.getInt("answer_char_start"),
                child.getInt("answer_char_end")), spaceId, answerId, runId);
        List<Citation> citations = jdbc.query("""
                SELECT id, claim_id, evidence_id, evidence_bundle_id, evidence_bundle_version,
                       evidence_bundle_hash, index_version_id, retrieval_profile_id, retrieval_profile_version,
                       document_revision_id, parent_chunk_id, child_chunk_id, content_ref, text_hash, anchor,
                       answer_char_start, answer_char_end
                FROM rag_answer_citations WHERE space_id = ? AND answer_id = ? AND run_id = ? ORDER BY id
                """, (child, row) -> new Citation("v1", child.getObject("evidence_id", UUID.class),
                child.getObject("claim_id", UUID.class), spaceId, rs.getObject("correlation_id", UUID.class), runId,
                idempotencyKey, child.getObject("evidence_bundle_id", UUID.class), child.getInt("evidence_bundle_version"),
                child.getString("evidence_bundle_hash"), child.getObject("index_version_id", UUID.class),
                child.getObject("retrieval_profile_id", UUID.class), child.getInt("retrieval_profile_version"),
                child.getObject("document_revision_id", UUID.class), child.getObject("parent_chunk_id", UUID.class),
                child.getObject("child_chunk_id", UUID.class), child.getString("content_ref"), child.getString("text_hash"),
                readJson(child.getString("anchor"), EvidenceBundle.Anchor.class), child.getInt("answer_char_start"),
                child.getInt("answer_char_end"), true), spaceId, answerId, runId);
        Abstention abstention = mapAbstention(spaceId, runId, answerId, idempotencyKey,
                rs.getObject("correlation_id", UUID.class));
        List<UUID> toolCallIds = readJson(rs.getString("tool_call_ids"), UUID_LIST);
        return new Answer(rs.getString("schema_version"), answerId, spaceId,
                rs.getObject("correlation_id", UUID.class), runId, idempotencyKey,
                AnswerStatus.valueOf(rs.getString("status")), rs.getString("answer_text"), claims, citations,
                abstention, toolCallIds, provenance, rs.getTimestamp("created_at").toInstant());
    }

    private Abstention mapAbstention(UUID spaceId, UUID runId, UUID answerId, String idempotencyKey,
                                     UUID correlationId) {
        try {
            return jdbc.queryForObject("""
                    SELECT id, reason_code, evidence_ids, message
                    FROM rag_answer_abstentions WHERE space_id = ? AND answer_id = ? AND run_id = ?
                    """, (rs, row) -> new Abstention("v1", rs.getObject("id", UUID.class), spaceId,
                    correlationId, runId, idempotencyKey,
                    com.ragforge.server.answer.AbstentionReason.valueOf(rs.getString("reason_code")),
                    readJson(rs.getString("evidence_ids"), UUID_LIST), rs.getString("message")),
                    spaceId, answerId, runId);
        } catch (EmptyResultDataAccessException ignored) {
            return null;
        }
    }

    private PersistedAnswer mapPersisted(ResultSet rs) throws SQLException {
        return new PersistedAnswer(rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getObject("run_id", UUID.class), rs.getString("idempotency_key"),
                AnswerStatus.valueOf(rs.getString("status")), rs.getString("answer_hash"),
                rs.getString("citation_hash"), mapProvenance(rs));
    }

    private AnswerProvenance mapProvenance(ResultSet rs) throws SQLException {
        UUID evidenceId = rs.getObject("evidence_bundle_id", UUID.class);
        return new AnswerProvenance("v1", rs.getObject("space_id", UUID.class),
                rs.getObject("correlation_id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getString("idempotency_key"), evidenceId, rs.getInt("evidence_bundle_version"),
                rs.getString("evidence_bundle_hash"), rs.getString("evidence_bundle_ref"),
                rs.getObject("index_version_id", UUID.class), rs.getObject("retrieval_profile_id", UUID.class),
                rs.getInt("retrieval_profile_version"), rs.getObject("rag_prompt_version_id", UUID.class),
                rs.getString("prompt_hash"), rs.getObject("model_route_version_id", UUID.class),
                rs.getObject("model_profile_version_id", UUID.class), rs.getString("model_version"),
                rs.getString("tool_schema_versions"), rs.getString("dataset_hash"), rs.getString("config_hash"),
                rs.getObject("trace_id", UUID.class));
    }

    private CitationPreview mapCitationPreview(ResultSet rs) throws SQLException {
        return new CitationPreview(rs.getObject("evidence_id", UUID.class), rs.getObject("claim_id", UUID.class),
                rs.getObject("space_id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getObject("evidence_bundle_id", UUID.class), rs.getInt("evidence_bundle_version"),
                rs.getString("evidence_bundle_hash"), rs.getObject("index_version_id", UUID.class),
                rs.getObject("retrieval_profile_id", UUID.class), rs.getInt("retrieval_profile_version"),
                rs.getObject("document_revision_id", UUID.class), rs.getObject("parent_chunk_id", UUID.class),
                rs.getObject("child_chunk_id", UUID.class), rs.getString("content_ref"), rs.getString("text_hash"),
                readJson(rs.getString("anchor"), EvidenceBundle.Anchor.class), rs.getInt("answer_char_start"),
                rs.getInt("answer_char_end"), rs.getTimestamp("created_at").toInstant());
    }

    private AnswerEvent mapEvent(ResultSet rs) throws SQLException {
        return new AnswerEvent(rs.getObject("id", UUID.class), rs.getObject("answer_id", UUID.class),
                rs.getObject("space_id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getLong("sequence_no"), AnswerPersistencePort.EventType.valueOf(rs.getString("event_type")),
                rs.getString("payload_hash"), rs.getString("metadata"), rs.getTimestamp("created_at").toInstant());
    }

    private Optional<AnswerEvent> findEvent(UUID spaceId, UUID answerId, long sequence) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, answer_id, space_id, run_id, sequence_no, event_type,
                           payload_hash, metadata, created_at
                    FROM rag_answer_events
                    WHERE space_id = ? AND answer_id = ? AND sequence_no = ?
                    """, (rs, row) -> mapEvent(rs), spaceId, answerId, sequence));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private String answerSelect(String predicate) {
        return """
                SELECT id, space_id, run_id, correlation_id, idempotency_key, schema_version, status,
                       answer_text, answer_hash, citation_hash, evidence_bundle_id, evidence_bundle_version,
                       evidence_bundle_hash, evidence_bundle_ref, index_version_id, retrieval_profile_id,
                       retrieval_profile_version, rag_prompt_version_id, prompt_hash, model_route_version_id,
                       model_profile_version_id, model_version, tool_schema_versions, tool_call_ids, dataset_hash, config_hash,
                       trace_id, created_at
                FROM rag_answers WHERE space_id = ? AND """ + " " + predicate;
    }

    private void validateAnswer(Answer answer) {
        if (answer == null || answer.answerId() == null || answer.createdAt() == null) {
            throw new IllegalArgumentException("answer is required");
        }
        if (answer.status() == AnswerStatus.COMPLETED && answer.answerText() == null) {
            throw new IllegalArgumentException("completed answer text is required");
        }
        validateSafeJson(answer.provenance().toolSchemaVersionsJson());
        validateAnswerLineage(answer);
    }

    private void validateSafeJson(String value) {
        if (value == null || value.length() > 16_384 || value.matches(".*" + BANNED_CONTENT + ".*")) {
            throw new IllegalArgumentException("history metadata contains forbidden content");
        }
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("history metadata must be a JSON object");
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("history metadata is not valid JSON", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("history value cannot be serialized", exception);
        }
    }

    private <T> T readJson(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored history JSON is invalid", exception);
        }
    }

    private <T> T readJson(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored history JSON is invalid", exception);
        }
    }

    private static void validateAnswerLineage(Answer answer) {
        List<UUID> claimIds = answer.claims().stream().map(Claim::claimId).toList();
        if (answer.citations().stream().anyMatch(citation -> !claimIds.contains(citation.claimId()))) {
            throw new IllegalArgumentException("citation claim is outside the answer");
        }
    }

    private static String citationCanonical(List<Citation> citations) {
        return citations.stream().map(citation -> citation.claimId() + "|" + citation.evidenceId() + "|"
                + citation.answerCharStart() + "|" + citation.answerCharEnd()).sorted().reduce("",
                String::concat);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is required by the runtime", exception);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }
}
