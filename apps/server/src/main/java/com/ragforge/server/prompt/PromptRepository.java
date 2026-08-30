package com.ragforge.server.prompt;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for immutable prompt versions and space bindings. */
@Repository
public class PromptRepository {
    private final JdbcTemplate jdbc;

    public PromptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public PromptVersion createVersion(NewPromptVersion input) {
        String templateHash = sha256(input.template());
        jdbc.update("""
                        INSERT INTO prompt_versions
                            (id, space_id, prompt_key, version_no, template, template_hash,
                             variables_schema, output_contract, change_note, created_by_user_id, status,
                             created_at, updated_at, correlation_id)
                        VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?, ?, ?)
                        """, input.id(), input.spaceId(), input.promptKey(), input.versionNo(), input.template(),
                templateHash, jsonOrEmpty(input.variablesSchemaJson()), jsonOrEmpty(input.outputContractJson()),
                input.changeNote(), input.createdByUserId(), input.status().name(), timestamp(input.now()),
                timestamp(input.now()), input.correlationId());
        return findVersion(input.spaceId(), input.id()).orElseThrow();
    }

    /**
     * Stores the redacted prompt identity consumed by a RAG run.  The RAG
     * projection deliberately has no template/content argument; callers must
     * provide a content hash and an opaque address instead.
     */
    @Transactional
    public RagPromptVersion createRagVersion(NewRagPromptVersion input) {
        jdbc.update("""
                        INSERT INTO rag_prompt_versions
                            (id, space_id, prompt_key, version_no, purpose, prompt_opaque_ref, prompt_hash,
                             variables_schema, output_contract, created_by_user_id, created_at, correlation_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?)
                        """, input.id(), input.spaceId(), input.promptKey(), input.versionNo(), input.purpose(),
                input.promptOpaqueRef(), input.promptHash(), jsonOrEmpty(input.variablesSchemaJson()),
                jsonOrEmpty(input.outputContractJson()), input.createdByUserId(), timestamp(input.now()),
                input.correlationId());
        return findRagVersion(input.spaceId(), input.id()).orElseThrow();
    }

    /** Materializes the immutable modern prompt identity for V11 provenance FKs. */
    @Transactional
    public RagPromptVersion ensureRagVersion(PromptVersion current, UUID correlationId) {
        if (current == null || current.id() == null || current.spaceId() == null
                || current.templateHash() == null || !current.templateHash().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Modern prompt identity is incomplete");
        }
        jdbc.update("""
                        INSERT INTO rag_prompt_versions
                            (id, space_id, prompt_key, version_no, purpose, prompt_opaque_ref, prompt_hash,
                             variables_schema, output_contract, created_by_user_id, created_at, correlation_id)
                        VALUES (?, ?, ?, ?, 'RAG_ANSWER', ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?)
                        ON CONFLICT (id) DO NOTHING
                        """, current.id(), current.spaceId(), current.promptKey(), current.versionNo(),
                "prompt-version:" + current.id(), current.templateHash(), jsonOrEmpty(current.variablesSchemaJson()),
                jsonOrEmpty(current.outputContractJson()), current.createdByUserId(), timestamp(current.createdAt()),
                correlationId == null ? current.correlationId() : correlationId);
        return findRagVersion(current.spaceId(), current.id())
                .orElseThrow(() -> new IllegalArgumentException("Modern prompt identity could not be projected"));
    }

    public Optional<RagPromptVersion> findRagVersion(UUID spaceId, UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT id, space_id, prompt_key, version_no, purpose, prompt_opaque_ref, prompt_hash,
                                   variables_schema, output_contract, created_by_user_id, created_at, correlation_id
                            FROM rag_prompt_versions
                            WHERE id = ? AND space_id = ?
                            """, (rs, rowNum) -> new RagPromptVersion(
                    rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                    rs.getString("prompt_key"), rs.getInt("version_no"), rs.getString("purpose"),
                    rs.getString("prompt_opaque_ref"), rs.getString("prompt_hash"),
                    rs.getString("variables_schema"), rs.getString("output_contract"),
                    rs.getObject("created_by_user_id", UUID.class), instant(rs, "created_at"),
                    rs.getObject("correlation_id", UUID.class)), id, spaceId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<PromptVersion> findVersion(UUID spaceId, UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT id, space_id, prompt_key, version_no, template, template_hash,
                                   variables_schema, output_contract, change_note, created_by_user_id, status,
                                   created_at, updated_at, correlation_id
                            FROM prompt_versions WHERE id = ? AND space_id = ?
                            """, (rs, rowNum) -> new PromptVersion(
                    rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                    rs.getString("prompt_key"), rs.getInt("version_no"), rs.getString("template"),
                    rs.getString("template_hash"), rs.getString("variables_schema"),
                    rs.getString("output_contract"), rs.getString("change_note"),
                    rs.getObject("created_by_user_id", UUID.class), PromptStatus.valueOf(rs.getString("status")),
                    instant(rs, "created_at"), instant(rs, "updated_at"),
                    rs.getObject("correlation_id", UUID.class)), id, spaceId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Resolves a published template by its immutable content hash and space.
     * The opaque ref remains an audit identity; the hash is the binding to the
     * actual prompt body and prevents a cross-space or mutable lookup.
     */
    public Optional<String> findPublishedTemplateByHash(UUID spaceId, String promptHash) {
        if (spaceId == null || promptHash == null || !promptHash.matches("[0-9a-fA-F]{64}")) {
            return Optional.empty();
        }
        List<String> templates = jdbc.query("""
                SELECT template
                FROM prompt_versions
                WHERE space_id = ? AND lower(template_hash) = lower(?) AND status = 'PUBLISHED'
                ORDER BY version_no DESC, id DESC
                """, (rs, rowNum) -> rs.getString("template"), spaceId, promptHash);
        if (templates.isEmpty() || new HashSet<>(templates).size() != 1) {
            return Optional.empty();
        }
        return Optional.of(templates.get(0));
    }

    @Transactional
    public SpacePromptBinding bind(NewSpacePromptBinding input) {
        int inserted = jdbc.update("""
                        INSERT INTO space_prompt_bindings
                            (id, space_id, binding_key, version_no, prompt_version_id, status,
                             created_at, updated_at, correlation_id)
                        SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?
                        WHERE EXISTS (
                            SELECT 1 FROM prompt_versions
                            WHERE id = ? AND space_id = ?
                        )
                        """, input.id(), input.spaceId(), input.bindingKey(), input.versionNo(),
                input.promptVersionId(), input.status().name(), timestamp(input.now()), timestamp(input.now()),
                input.correlationId(), input.promptVersionId(), input.spaceId());
        if (inserted != 1) {
            throw new IllegalArgumentException("Prompt version must belong to the requested space");
        }
        return findBinding(input.spaceId(), input.id()).orElseThrow();
    }

    public Optional<SpacePromptBinding> findBinding(UUID spaceId, UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT id, space_id, binding_key, version_no, prompt_version_id, status,
                                   created_at, updated_at, correlation_id
                            FROM space_prompt_bindings WHERE id = ? AND space_id = ?
                            """, (rs, rowNum) -> new SpacePromptBinding(
                    rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                    rs.getString("binding_key"), rs.getInt("version_no"),
                    rs.getObject("prompt_version_id", UUID.class), BindingStatus.valueOf(rs.getString("status")),
                    instant(rs, "created_at"), instant(rs, "updated_at"),
                    rs.getObject("correlation_id", UUID.class)), id, spaceId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<SpacePromptBinding> findLatestBinding(UUID spaceId, String bindingKey) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT id, space_id, binding_key, version_no, prompt_version_id, status,
                                   created_at, updated_at, correlation_id
                            FROM space_prompt_bindings
                            WHERE space_id = ? AND binding_key = ?
                            ORDER BY version_no DESC, id DESC
                            LIMIT 1
                            """, (rs, rowNum) -> new SpacePromptBinding(
                    rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                    rs.getString("binding_key"), rs.getInt("version_no"),
                    rs.getObject("prompt_version_id", UUID.class), BindingStatus.valueOf(rs.getString("status")),
                    instant(rs, "created_at"), instant(rs, "updated_at"),
                    rs.getObject("correlation_id", UUID.class)), spaceId, bindingKey));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static String jsonOrEmpty(String value) {
        return value == null || value.isBlank() ? "{}" : value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the runtime", exception);
        }
    }

    public enum PromptStatus { DRAFT, PUBLISHED, RETIRED }

    public enum BindingStatus { ACTIVE, RETIRED }

    public record NewPromptVersion(UUID id, UUID spaceId, String promptKey, int versionNo, String template,
                                   String variablesSchemaJson, String outputContractJson, String changeNote,
                                   UUID createdByUserId, PromptStatus status, Instant now, UUID correlationId) {
    }

    public record PromptVersion(UUID id, UUID spaceId, String promptKey, int versionNo, String template,
                                String templateHash, String variablesSchemaJson, String outputContractJson,
                                String changeNote, UUID createdByUserId, PromptStatus status, Instant createdAt,
                                Instant updatedAt, UUID correlationId) {
    }

    public record NewRagPromptVersion(UUID id, UUID spaceId, String promptKey, int versionNo, String purpose,
                                      String promptOpaqueRef, String promptHash, String variablesSchemaJson,
                                      String outputContractJson, UUID createdByUserId, Instant now,
                                      UUID correlationId) {
    }

    public record RagPromptVersion(UUID id, UUID spaceId, String promptKey, int versionNo, String purpose,
                                   String promptOpaqueRef, String promptHash, String variablesSchemaJson,
                                   String outputContractJson, UUID createdByUserId, Instant createdAt,
                                   UUID correlationId) {
    }

    public record NewSpacePromptBinding(UUID id, UUID spaceId, String bindingKey, int versionNo,
                                        UUID promptVersionId, BindingStatus status, Instant now,
                                        UUID correlationId) {
    }

    public record SpacePromptBinding(UUID id, UUID spaceId, String bindingKey, int versionNo,
                                     UUID promptVersionId, BindingStatus status, Instant createdAt,
                                     Instant updatedAt, UUID correlationId) {
    }
}
