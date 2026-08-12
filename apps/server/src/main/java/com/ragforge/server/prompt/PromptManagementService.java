package com.ragforge.server.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.common.ApiException;
import com.ragforge.server.common.CorrelationIdFilter;
import com.ragforge.server.common.UuidV7;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.provider.SpaceAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Application service for real prompt templates, immutable versions, and the V4 DB state machine. */
@Service
public class PromptManagementService {
    private final PromptRepository prompts;
    private final SpaceAuthorization authorization;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PromptManagementService(PromptRepository prompts, SpaceAuthorization authorization,
                                   JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.prompts = prompts;
        this.authorization = authorization;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PromptTemplateView createTemplate(UUID spaceId, PromptTemplateRequest request,
                                             SessionPrincipal principal, HttpServletRequest servletRequest) {
        authorization.requireWrite(spaceId, principal);
        UUID id = UuidV7.random();
        Instant now = Instant.now();
        try {
            jdbc.update("""
                    INSERT INTO prompt_templates
                        (id, space_id, name, purpose, created_at, updated_at, correlation_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, id, spaceId, request.name().trim(), purpose(request.purpose()),
                    java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), correlationId(servletRequest));
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "prompt_template_name_conflict", "Prompt template already exists",
                    "A prompt template with this name already exists in the requested space");
        }
        return new PromptTemplateView(id, spaceId, request.name().trim(), purpose(request.purpose()), null, now, now);
    }

    public PromptTemplatePage listTemplates(UUID spaceId, SessionPrincipal principal) {
        authorization.requireMember(spaceId, principal);
        List<PromptTemplateView> items = jdbc.query("""
                SELECT t.id, t.space_id, t.name, t.purpose, MAX(v.version_no) AS current_version,
                       t.created_at, t.updated_at
                FROM prompt_templates t
                LEFT JOIN prompt_versions v
                  ON v.prompt_template_id = t.id AND v.space_id = t.space_id
                WHERE t.space_id = ?
                GROUP BY t.id, t.space_id, t.name, t.purpose, t.created_at, t.updated_at
                ORDER BY t.created_at DESC, t.id DESC
                """, (rs, rowNum) -> new PromptTemplateView(
                rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getString("name"), rs.getString("purpose"),
                (Integer) rs.getObject("current_version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), spaceId);
        return new PromptTemplatePage(items, null);
    }

    @Transactional
    public PromptVersionView createVersion(UUID spaceId, UUID promptTemplateId, PromptVersionRequest request,
                                           SessionPrincipal principal, HttpServletRequest servletRequest) {
        authorization.requireWrite(spaceId, principal);
        findTemplate(spaceId, promptTemplateId);
        int nextVersion = nextVersion(spaceId, promptTemplateId);
        Instant now = Instant.now();
        UUID versionId = UuidV7.random();
        String template = json(request.messages());
        jdbc.update("""
                INSERT INTO prompt_versions
                    (id, space_id, prompt_template_id, prompt_key, version_no, template, template_hash,
                     variables_schema, output_contract, change_note, created_by_user_id, status,
                     created_at, updated_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, 'DRAFT', ?, ?, ?)
                """, versionId, spaceId, promptTemplateId, promptTemplateId.toString(), nextVersion,
                template, sha256(template), json(request.variableSchema()), json(request.outputContract()),
                request.changeDescription().trim(), principal.userId(), java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now), correlationId(servletRequest));
        PromptRepository.PromptVersion version = prompts.findVersion(spaceId, versionId).orElseThrow();
        return toView(promptTemplateId, version);
    }

    public PromptVersionView getVersion(UUID spaceId, UUID promptTemplateId, int promptVersion,
                                        SessionPrincipal principal) {
        authorization.requireMember(spaceId, principal);
        return toView(promptTemplateId, findVersion(spaceId, promptTemplateId, promptVersion));
    }

    @Transactional
    public PromptVersionView publishVersion(UUID spaceId, UUID promptTemplateId, int promptVersion,
                                            SessionPrincipal principal, HttpServletRequest servletRequest) {
        authorization.requireWrite(spaceId, principal);
        PromptRepository.PromptVersion version = findVersion(spaceId, promptTemplateId, promptVersion);
        int updated = jdbc.update("""
                UPDATE prompt_versions
                SET status = 'PUBLISHED', updated_at = ?, correlation_id = ?
                WHERE id = ? AND space_id = ? AND status = 'DRAFT'
                """, java.sql.Timestamp.from(Instant.now()), correlationId(servletRequest),
                version.id(), spaceId);
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "prompt_version_state_conflict", "Prompt version cannot be published",
                    "Only a draft prompt version can be published");
        }
        return toView(promptTemplateId, prompts.findVersion(spaceId, version.id()).orElseThrow());
    }

    private PromptRepository.PromptVersion findVersion(UUID spaceId, UUID promptTemplateId, int promptVersion) {
        try {
            UUID id = jdbc.queryForObject("""
                    SELECT id FROM prompt_versions
                    WHERE space_id = ? AND prompt_template_id = ? AND version_no = ?
                    """, UUID.class, spaceId, promptTemplateId, promptVersion);
            return prompts.findVersion(spaceId, id).orElseThrow();
        } catch (EmptyResultDataAccessException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "prompt_version_not_found", "Prompt version not found",
                    "Prompt version not found in the requested space");
        }
    }

    private int nextVersion(UUID spaceId, UUID promptTemplateId) {
        Integer current = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_no), 0) FROM prompt_versions
                WHERE space_id = ? AND prompt_template_id = ?
                """, Integer.class, spaceId, promptTemplateId);
        return current + 1;
    }

    private PromptTemplateView findTemplate(UUID spaceId, UUID templateId) {
        try {
            return jdbc.queryForObject("""
                    SELECT id, space_id, name, purpose, created_at, updated_at
                    FROM prompt_templates
                    WHERE id = ? AND space_id = ?
                    """, (rs, rowNum) -> new PromptTemplateView(
                    rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                    rs.getString("name"), rs.getString("purpose"), null,
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                    templateId, spaceId);
        } catch (EmptyResultDataAccessException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "prompt_template_not_found", "Prompt template not found",
                    "Prompt template not found in the requested space");
        }
    }

    private PromptVersionView toView(UUID promptTemplateId, PromptRepository.PromptVersion version) {
        return new PromptVersionView(version.id(), version.spaceId(), promptTemplateId, version.versionNo(),
                version.status().name(), messages(version.template()), object(version.variablesSchemaJson()),
                object(version.outputContractJson()), version.templateHash(), true,
                version.status() == PromptRepository.PromptStatus.DRAFT ? null : version.updatedAt(),
                version.createdAt());
    }

    private List<PromptMessage> messages(String template) {
        try {
            return objectMapper.readValue(template,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, PromptMessage.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored prompt template is not valid JSON", exception);
        }
    }

    private Map<String, Object> object(String value) {
        try {
            return objectMapper.readValue(value == null || value.isBlank() ? "{}" : value,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored prompt metadata is not valid JSON", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "Validation failed",
                    "Prompt content cannot be serialized");
        }
    }

    private static String purpose(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
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

    private static UUID correlationId(HttpServletRequest request) {
        return UUID.fromString(CorrelationIdFilter.current(request));
    }

    public record PromptMessage(
            @NotBlank @Pattern(regexp = "SYSTEM|USER|ASSISTANT|TOOL") String role,
            @NotBlank @Size(max = 100_000) String content) {
    }

    public record PromptTemplateRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "CHAT|EMBEDDING|RERANK") String purpose) {
    }

    public record PromptTemplateView(UUID promptTemplateId, UUID spaceId, String name, String purpose,
                                     Integer currentVersion, Instant createdAt, Instant updatedAt) {
    }

    public record PromptTemplatePage(List<PromptTemplateView> items, String nextCursor) {
    }

    public record PromptVersionRequest(
            @NotEmpty @Size(max = 50) List<@Valid PromptMessage> messages,
            @NotNull Map<String, Object> variableSchema,
            @NotNull Map<String, Object> outputContract,
            @NotBlank @Size(max = 2_000) String changeDescription) {
    }

    public record PromptVersionView(UUID promptVersionId, UUID spaceId, UUID promptTemplateId, int version,
                                    String state, List<PromptMessage> messages, Map<String, Object> variableSchema,
                                    Map<String, Object> outputContract, String contentHash,
                                    boolean immutableAfterPublish, Instant publishedAt, Instant createdAt) {
    }
}
