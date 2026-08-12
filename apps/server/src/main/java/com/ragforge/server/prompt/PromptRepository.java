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

    public record NewSpacePromptBinding(UUID id, UUID spaceId, String bindingKey, int versionNo,
                                        UUID promptVersionId, BindingStatus status, Instant now,
                                        UUID correlationId) {
    }

    public record SpacePromptBinding(UUID id, UUID spaceId, String bindingKey, int versionNo,
                                     UUID promptVersionId, BindingStatus status, Instant createdAt,
                                     Instant updatedAt, UUID correlationId) {
    }
}
