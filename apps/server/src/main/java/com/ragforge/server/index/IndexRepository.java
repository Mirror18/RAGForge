package com.ragforge.server.index;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Space-scoped persistence seam for candidate index versions, validation and
 * the atomic active pointer. A new index is built in an isolated candidate
 * state, validated, then published by switching the PostgreSQL active pointer;
 * the previous index stays retained for at least 24 hours.
 */
@Repository
public class IndexRepository {

    public record IndexVersion(UUID id, UUID spaceId, int versionNo, IndexState state, String candidateCollection,
            String embeddingProfileVersion, String chunkingStrategyVersion, int documentRevisionCount,
            int childChunkCount, IndexValidation validation, Instant activatedAt, Instant retiredAt,
            Instant retentionDeadline, Instant createdAt) {
    }

    public record ActiveIndexPointer(UUID id, UUID spaceId, UUID activeIndexVersionId, UUID previousIndexVersionId,
            int versionNo, Instant updatedAt) {
    }

    public record NewIndexVersion(UUID id, UUID spaceId, int versionNo, String candidateCollection,
            String embeddingProfileVersion, String chunkingStrategyVersion, int documentRevisionCount,
            int childChunkCount, Instant createdAt) {
    }

    private static final java.time.Duration RETENTION = java.time.Duration.ofHours(24);

    private final JdbcTemplate jdbc;

    public IndexRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public IndexVersion createVersion(NewIndexVersion input) {
        jdbc.update("""
                INSERT INTO index_versions
                    (id, space_id, version_no, index_state, candidate_collection, embedding_profile_version,
                     chunking_strategy_version, document_revision_count, child_chunk_count, created_at)
                VALUES (?, ?, ?, 'BUILDING', ?, ?, ?, ?, ?, ?)
                """, input.id(), input.spaceId(), input.versionNo(), input.candidateCollection(),
                input.embeddingProfileVersion(), input.chunkingStrategyVersion(), input.documentRevisionCount(),
                input.childChunkCount(), timestamp(input.createdAt()));
        return findVersion(input.spaceId(), input.id()).orElseThrow();
    }

    public Optional<IndexVersion> findVersion(UUID spaceId, UUID indexVersionId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, space_id, version_no, index_state, candidate_collection,
                           embedding_profile_version, chunking_strategy_version,
                           document_revision_count, child_chunk_count,
                           validation_document_count, validation_child_chunk_count,
                           validation_vector_dimension, validation_orphan_child_count,
                           validation_sample_retrieval_passed, validation_space_filter_passed,
                           validation_checked_at, activated_at, retired_at, retention_deadline, created_at
                    FROM index_versions WHERE space_id = ? AND id = ?
                    """, (rs, row) -> mapVersion(rs), spaceId, indexVersionId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    /** Moves BUILDING -> VALIDATING -> READY, or any build/validate state -> FAILED. */
    @Transactional
    public IndexVersion transitionState(UUID spaceId, UUID indexVersionId, IndexState target) {
        IndexVersion current = findVersion(spaceId, indexVersionId)
                .orElseThrow(() -> new IllegalArgumentException("index version not found in space " + spaceId));
        IndexStateTransitions.requireTransition(current.state(), target);
        jdbc.update("UPDATE index_versions SET index_state = ? WHERE space_id = ? AND id = ?",
                target.name(), spaceId, indexVersionId);
        return findVersion(spaceId, indexVersionId).orElseThrow();
    }

    /** Records the VALIDATING gate result. */
    @Transactional
    public IndexVersion recordValidation(UUID spaceId, UUID indexVersionId, IndexValidation validation) {
        IndexVersion current = findVersion(spaceId, indexVersionId)
                .orElseThrow(() -> new IllegalArgumentException("index version not found in space " + spaceId));
        if (current.state() != IndexState.VALIDATING && current.state() != IndexState.READY) {
            throw new IllegalStateException("validation only applies to VALIDATING/READY index, was " + current.state());
        }
        jdbc.update("""
                UPDATE index_versions SET
                    validation_document_count = ?, validation_child_chunk_count = ?,
                    validation_vector_dimension = ?, validation_orphan_child_count = ?,
                    validation_sample_retrieval_passed = ?, validation_space_filter_passed = ?,
                    validation_checked_at = ?
                WHERE space_id = ? AND id = ?
                """, validation.documentCount(), validation.childChunkCount(), validation.vectorDimension(),
                validation.orphanChildCount(), validation.sampleRetrievalPassed(), validation.spaceFilterPassed(),
                timestamp(validation.checkedAt()), spaceId, indexVersionId);
        return findVersion(spaceId, indexVersionId).orElseThrow();
    }

    /** Publishes a READY index: switches the PostgreSQL active pointer atomically. */
    @Transactional
    public ActiveIndexPointer activate(UUID spaceId, UUID indexVersionId, Instant now) {
        IndexVersion current = findVersion(spaceId, indexVersionId)
                .orElseThrow(() -> new IllegalArgumentException("index version not found in space " + spaceId));
        IndexStateTransitions.requireTransition(current.state(), IndexState.ACTIVE);
        IndexStateTransitions.requireActivationEligible(current.validation());
        Instant retentionDeadline = now.plus(RETENTION);
        jdbc.update("""
                UPDATE index_versions
                SET index_state = 'ACTIVE', activated_at = ?, retention_deadline = ?
                WHERE space_id = ? AND id = ?
                """, timestamp(now), timestamp(retentionDeadline), spaceId, indexVersionId);
        UUID previous = findActivePointer(spaceId)
                .map(ActiveIndexPointer::activeIndexVersionId).orElse(null);
        jdbc.update("""
                INSERT INTO active_index_pointers
                    (id, space_id, active_index_version_id, previous_index_version_id, version_no, updated_at)
                VALUES (?, ?, ?, ?, 1, ?)
                ON CONFLICT (space_id) DO UPDATE SET
                    active_index_version_id = EXCLUDED.active_index_version_id,
                    previous_index_version_id = EXCLUDED.previous_index_version_id,
                    version_no = active_index_pointers.version_no + 1,
                    updated_at = EXCLUDED.updated_at
                """, UUID.randomUUID(), spaceId, indexVersionId, previous, timestamp(now));
        return findActivePointer(spaceId).orElseThrow();
    }

    /** Retires an ACTIVE index (kept for retention; cleanup happens after the deadline). */
    @Transactional
    public IndexVersion retire(UUID spaceId, UUID indexVersionId, Instant now) {
        IndexVersion current = findVersion(spaceId, indexVersionId)
                .orElseThrow(() -> new IllegalArgumentException("index version not found in space " + spaceId));
        IndexStateTransitions.requireTransition(current.state(), IndexState.RETIRED);
        jdbc.update("UPDATE index_versions SET index_state = 'RETIRED', retired_at = ? WHERE space_id = ? AND id = ?",
                timestamp(now), spaceId, indexVersionId);
        return findVersion(spaceId, indexVersionId).orElseThrow();
    }

    public Optional<ActiveIndexPointer> findActivePointer(UUID spaceId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, space_id, active_index_version_id, previous_index_version_id, version_no, updated_at
                    FROM active_index_pointers WHERE space_id = ?
                    """, (rs, row) -> new ActiveIndexPointer(
                    rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                    rs.getObject("active_index_version_id", UUID.class),
                    rs.getObject("previous_index_version_id", UUID.class),
                    rs.getInt("version_no"), instant(rs, "updated_at")), spaceId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private IndexVersion mapVersion(java.sql.ResultSet rs) throws java.sql.SQLException {
        IndexValidation validation = rs.getObject("validation_checked_at") == null ? null : new IndexValidation(
                rs.getInt("validation_document_count"), rs.getInt("validation_child_chunk_count"),
                rs.getInt("validation_vector_dimension"), rs.getInt("validation_orphan_child_count"),
                rs.getBoolean("validation_sample_retrieval_passed"),
                rs.getBoolean("validation_space_filter_passed"), instant(rs, "validation_checked_at"));
        return new IndexVersion(
                rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class), rs.getInt("version_no"),
                IndexState.valueOf(rs.getString("index_state")), rs.getString("candidate_collection"),
                rs.getString("embedding_profile_version"), rs.getString("chunking_strategy_version"),
                rs.getInt("document_revision_count"), rs.getInt("child_chunk_count"), validation,
                instant(rs, "activated_at"), instant(rs, "retired_at"), instant(rs, "retention_deadline"),
                instant(rs, "created_at"));
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
