package com.ragforge.server.answer.integration;

import com.ragforge.server.retrieval.ExpansionMode;
import com.ragforge.server.retrieval.RetrievalProfileRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;

/** Exact row-id loader for active retrieval profile versions. */
public final class JdbcRetrievalProfileVersionLoader implements ActiveRetrievalExecutionResolver.ProfileVersionLoader {
    private final JdbcTemplate jdbc;

    public JdbcRetrievalProfileVersionLoader(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<RetrievalProfileRepository.RetrievalProfileVersion> find(UUID spaceId,
                                                                               UUID profileVersionId,
                                                                               UUID correlationId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, space_id, profile_id, version_no, dense_top_k, bm25_top_k, rrf_k,
                           rrf_dense_weight, rrf_bm25_weight, rerank_top_k, max_context_children,
                           expansion_mode, max_parents_per_child, max_neighbors_per_parent,
                           max_context_tokens, created_at
                    FROM retrieval_profiles WHERE space_id = ? AND id = ?
                    """, (rs, row) -> new RetrievalProfileRepository.RetrievalProfileVersion(
                    rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                    rs.getObject("profile_id", UUID.class), rs.getInt("version_no"),
                    rs.getInt("dense_top_k"), rs.getInt("bm25_top_k"), rs.getInt("rrf_k"),
                    rs.getDouble("rrf_dense_weight"), rs.getDouble("rrf_bm25_weight"),
                    rs.getInt("rerank_top_k"), rs.getInt("max_context_children"),
                    ExpansionMode.valueOf(rs.getString("expansion_mode")),
                    rs.getInt("max_parents_per_child"), rs.getInt("max_neighbors_per_parent"),
                    rs.getInt("max_context_tokens"), rs.getTimestamp("created_at").toInstant()),
                    spaceId, profileVersionId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }
}
