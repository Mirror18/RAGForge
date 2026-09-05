package com.ragforge.server.provider;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for the immutable, space-scoped binding aggregate. */
@Repository
public class SpaceBindingRepository {
    private final JdbcTemplate jdbc;

    public SpaceBindingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Serializes binding writes for one existing knowledge space. */
    public boolean lockSpace(UUID spaceId) {
        try {
            jdbc.queryForObject("SELECT id FROM knowledge_spaces WHERE id = ? FOR UPDATE", UUID.class, spaceId);
            return true;
        } catch (EmptyResultDataAccessException ignored) {
            return false;
        }
    }

    public Optional<SpaceBindingRecord> findCurrent(UUID spaceId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT sb.id, sb.space_id, sb.version_no,
                                   chat.model_route_version_id AS chat_route_id,
                                   embedding.model_route_version_id AS embedding_route_id,
                                   rerank.model_route_version_id AS rerank_route_id,
                                   prompt.prompt_version_id,
                                   sb.cloud_egress_enabled, sb.cloud_approval_id,
                                   sb.cloud_approved_by, sb.cloud_approved_at,
                                   sb.cloud_expires_at, sb.cloud_scope,
                                   sb.created_at, sb.updated_at, sb.correlation_id
                            FROM space_binding_versions sb
                            JOIN space_model_bindings chat
                              ON chat.id = sb.chat_model_binding_id
                             AND chat.space_id = sb.space_id
                            JOIN space_model_bindings embedding
                              ON embedding.id = sb.embedding_model_binding_id
                             AND embedding.space_id = sb.space_id
                            JOIN space_model_bindings rerank
                              ON rerank.id = sb.rerank_model_binding_id
                             AND rerank.space_id = sb.space_id
                            JOIN space_prompt_bindings prompt
                              ON prompt.id = sb.prompt_binding_id
                             AND prompt.space_id = sb.space_id
                            WHERE sb.space_id = ?
                            ORDER BY sb.version_no DESC
                            LIMIT 1
                            """, (rs, rowNum) -> map(rs), spaceId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public SpaceBindingRecord create(NewSpaceBinding input) {
        jdbc.update("""
                        INSERT INTO space_binding_versions
                            (id, space_id, version_no, chat_model_binding_id,
                             embedding_model_binding_id, rerank_model_binding_id, prompt_binding_id,
                             cloud_egress_enabled, cloud_approval_id, cloud_approved_by,
                             cloud_approved_at, cloud_expires_at, cloud_scope,
                             created_at, updated_at, correlation_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, input.id(), input.spaceId(), input.version(), input.chatModelBindingId(),
                input.embeddingModelBindingId(), input.rerankModelBindingId(), input.promptBindingId(),
                input.cloudEgressEnabled(), input.authorization() == null ? null : input.authorization().approvalId(),
                input.authorization() == null ? null : input.authorization().approvedBy(),
                input.authorization() == null ? null : timestamp(input.authorization().approvedAt()),
                input.authorization() == null ? null : timestamp(input.authorization().expiresAt()),
                input.authorization() == null ? null : input.authorization().scope(), timestamp(input.now()),
                timestamp(input.now()), input.correlationId());
        return findCurrent(input.spaceId()).orElseThrow();
    }

    private SpaceBindingRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID approvalId = rs.getObject("cloud_approval_id", UUID.class);
        CloudAuthorization authorization = approvalId == null ? null : new CloudAuthorization(
                approvalId, rs.getObject("cloud_approved_by", UUID.class),
                rs.getTimestamp("cloud_approved_at").toInstant(),
                rs.getTimestamp("cloud_expires_at").toInstant(), rs.getString("cloud_scope"));
        return new SpaceBindingRecord(
                rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getInt("version_no"), rs.getObject("chat_route_id", UUID.class),
                rs.getObject("embedding_route_id", UUID.class), rs.getObject("rerank_route_id", UUID.class),
                rs.getObject("prompt_version_id", UUID.class), rs.getBoolean("cloud_egress_enabled"),
                authorization, rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getObject("correlation_id", UUID.class));
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    public record NewSpaceBinding(UUID id, UUID spaceId, int version, UUID chatModelBindingId,
                                  UUID embeddingModelBindingId, UUID rerankModelBindingId, UUID promptBindingId,
                                  boolean cloudEgressEnabled, CloudAuthorization authorization, Instant now,
                                  UUID correlationId) {
    }

    public record SpaceBindingRecord(UUID id, UUID spaceId, int version, UUID chatRouteId,
                                     UUID embeddingRouteId, UUID rerankRouteId, UUID promptVersionId,
                                     boolean cloudEgressEnabled, CloudAuthorization authorization,
                                     Instant createdAt, Instant updatedAt, UUID correlationId) {
    }

    /** Cloud authorization metadata only; no bearer credential or provider secret is stored. */
    public record CloudAuthorization(UUID approvalId, UUID approvedBy, Instant approvedAt, Instant expiresAt,
                                     String scope) {
    }
}
