package com.ragforge.server.retrieval;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Space-scoped persistence seam for immutable, versioned retrieval profiles.
 *
 * <p>A profile change creates a new version; runs reference the exact profile
 * version and index version. A/B comparisons use candidate profiles without
 * changing the single active pointer per space.</p>
 */
@Repository
public class RetrievalProfileRepository {

    public record RetrievalProfileVersion(UUID id, UUID spaceId, UUID profileId, int versionNo,
            int denseTopK, int bm25TopK, int rrfK, double rrfDenseWeight, double rrfBm25Weight,
            int rerankTopK, int maxContextChildren, ExpansionMode expansionMode,
            int maxParentsPerChild, int maxNeighborsPerParent, int maxContextTokens, Instant createdAt) {
    }

    public record ActiveProfilePointer(UUID id, UUID spaceId, UUID activeProfileVersionId, int activeVersionNo,
            Instant updatedAt) {
    }

    public record NewRetrievalProfileVersion(UUID id, UUID spaceId, UUID profileId, int versionNo,
            int denseTopK, int bm25TopK, int rrfK, double rrfDenseWeight, double rrfBm25Weight,
            int rerankTopK, int maxContextChildren, ExpansionMode expansionMode,
            int maxParentsPerChild, int maxNeighborsPerParent, int maxContextTokens, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;

    public RetrievalProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public RetrievalProfileVersion createVersion(NewRetrievalProfileVersion input) {
        validateBounds(input);
        jdbc.update("""
                INSERT INTO retrieval_profiles
                    (id, space_id, profile_id, version_no, dense_top_k, bm25_top_k, rrf_k,
                     rrf_dense_weight, rrf_bm25_weight, rerank_top_k, max_context_children,
                     expansion_mode, max_parents_per_child, max_neighbors_per_parent,
                     max_context_tokens, immutable, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)
                """, input.id(), input.spaceId(), input.profileId(), input.versionNo(), input.denseTopK(),
                input.bm25TopK(), input.rrfK(), input.rrfDenseWeight(), input.rrfBm25Weight(), input.rerankTopK(),
                input.maxContextChildren(), input.expansionMode().name(), input.maxParentsPerChild(),
                input.maxNeighborsPerParent(), input.maxContextTokens(), timestamp(input.createdAt()));
        return findVersion(input.spaceId(), input.profileId(), input.versionNo()).orElseThrow();
    }

    public Optional<RetrievalProfileVersion> findVersion(UUID spaceId, UUID profileId, int versionNo) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, space_id, profile_id, version_no, dense_top_k, bm25_top_k, rrf_k,
                           rrf_dense_weight, rrf_bm25_weight, rerank_top_k, max_context_children,
                           expansion_mode, max_parents_per_child, max_neighbors_per_parent,
                           max_context_tokens, created_at
                    FROM retrieval_profiles WHERE space_id = ? AND profile_id = ? AND version_no = ?
                    """, (rs, row) -> mapProfile(rs), spaceId, profileId, versionNo));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<RetrievalProfileVersion> findLatestVersion(UUID spaceId, UUID profileId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, space_id, profile_id, version_no, dense_top_k, bm25_top_k, rrf_k,
                           rrf_dense_weight, rrf_bm25_weight, rerank_top_k, max_context_children,
                           expansion_mode, max_parents_per_child, max_neighbors_per_parent,
                           max_context_tokens, created_at
                    FROM retrieval_profiles WHERE space_id = ? AND profile_id = ?
                    ORDER BY version_no DESC LIMIT 1
                    """, (rs, row) -> mapProfile(rs), spaceId, profileId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    /** Publishes a profile version as the single active pointer for the space. */
    @Transactional
    public ActiveProfilePointer activateProfile(UUID spaceId, UUID profileId, int versionNo, Instant now) {
        RetrievalProfileVersion target = findVersion(spaceId, profileId, versionNo)
                .orElseThrow(() -> new IllegalArgumentException("retrieval profile version not found in space " + spaceId));
        jdbc.update("""
                INSERT INTO active_profile_pointers
                    (id, space_id, active_profile_version_id, active_version_no, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (space_id) DO UPDATE SET
                    active_profile_version_id = EXCLUDED.active_profile_version_id,
                    active_version_no = EXCLUDED.active_version_no,
                    updated_at = EXCLUDED.updated_at
                """, UUID.randomUUID(), spaceId, target.id(), target.versionNo(), timestamp(now));
        return findActivePointer(spaceId).orElseThrow();
    }

    public Optional<ActiveProfilePointer> findActivePointer(UUID spaceId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, space_id, active_profile_version_id, active_version_no, updated_at
                    FROM active_profile_pointers WHERE space_id = ?
                    """, (rs, row) -> new ActiveProfilePointer(
                    rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                    rs.getObject("active_profile_version_id", UUID.class), rs.getInt("active_version_no"),
                    instant(rs, "updated_at")), spaceId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private void validateBounds(NewRetrievalProfileVersion input) {
        if (input.denseTopK() < 1 || input.denseTopK() > 100 || input.bm25TopK() < 1 || input.bm25TopK() > 100
                || input.rerankTopK() < 1 || input.rerankTopK() > 100) {
            throw new IllegalArgumentException("top-k values must be within [1, 100]");
        }
        if (input.rrfK() < 1 || input.rrfK() > 1000
                || input.rrfDenseWeight() < 0 || input.rrfDenseWeight() > 1
                || input.rrfBm25Weight() < 0 || input.rrfBm25Weight() > 1) {
            throw new IllegalArgumentException("invalid RRF parameters");
        }
        if (input.maxContextChildren() < 1 || input.maxContextChildren() > 20 || input.maxContextTokens() < 0) {
            throw new IllegalArgumentException("invalid context budget");
        }
        if (input.maxParentsPerChild() < 0 || input.maxParentsPerChild() > 8
                || input.maxNeighborsPerParent() < 0 || input.maxNeighborsPerParent() > 16) {
            throw new IllegalArgumentException("invalid expansion limits");
        }
    }

    private RetrievalProfileVersion mapProfile(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RetrievalProfileVersion(
                rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getObject("profile_id", UUID.class), rs.getInt("version_no"),
                rs.getInt("dense_top_k"), rs.getInt("bm25_top_k"), rs.getInt("rrf_k"),
                rs.getDouble("rrf_dense_weight"), rs.getDouble("rrf_bm25_weight"),
                rs.getInt("rerank_top_k"), rs.getInt("max_context_children"),
                ExpansionMode.valueOf(rs.getString("expansion_mode")),
                rs.getInt("max_parents_per_child"), rs.getInt("max_neighbors_per_parent"),
                rs.getInt("max_context_tokens"), instant(rs, "created_at"));
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
