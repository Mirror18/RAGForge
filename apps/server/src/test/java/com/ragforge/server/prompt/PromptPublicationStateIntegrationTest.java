package com.ragforge.server.prompt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL/Flyway proof for the PromptVersion publication state machine. */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PromptPublicationStateIntegrationTest {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.0.1-alpine")
            .withExposedPorts(6379);

    static {
        POSTGRES.start();
        try {
            VALKEY.start();
        } catch (RuntimeException exception) {
            POSTGRES.stop();
            throw exception;
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.url", () -> "redis://" + VALKEY.getHost() + ":"
                + VALKEY.getMappedPort(6379));
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PromptRepository prompts;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE space_prompt_bindings, prompt_versions, knowledge_spaces, users CASCADE");
    }

    @Test
    void draftPublishesOnceAndRetainsAuditAndVersionSemantics() {
        UUID spaceA = createSpace("prompt-state-a");
        UUID author = createUser("prompt-state@example.com");
        Instant createdAt = Instant.parse("2026-08-13T00:00:00Z");
        UUID createdCorrelation = UUID.randomUUID();
        PromptRepository.PromptVersion draft = createPrompt(spaceA, author,
                PromptRepository.PromptStatus.DRAFT, createdAt, createdCorrelation);

        Instant publishedAt = createdAt.plusSeconds(1);
        UUID publishedCorrelation = UUID.randomUUID();
        assertThat(jdbc.update("""
                UPDATE prompt_versions
                SET status = 'PUBLISHED', updated_at = ?, correlation_id = ?
                WHERE id = ? AND space_id = ?
                """, timestamp(publishedAt), publishedCorrelation, draft.id(), spaceA)).isEqualTo(1);

        PromptRepository.PromptVersion published = prompts.findVersion(spaceA, draft.id()).orElseThrow();
        assertThat(published.status()).isEqualTo(PromptRepository.PromptStatus.PUBLISHED);
        assertThat(published.versionNo()).isEqualTo(1);
        assertThat(published.createdAt()).isEqualTo(createdAt);
        assertThat(published.updatedAt()).isEqualTo(publishedAt);
        assertThat(published.correlationId()).isEqualTo(publishedCorrelation);

        assertThatThrownBy(() -> jdbc.update("UPDATE prompt_versions SET template = 'tampered' WHERE id = ?",
                draft.id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE prompt_versions SET template_hash = ? WHERE id = ?",
                "b".repeat(64), draft.id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE prompt_versions SET variables_schema = CAST(? AS jsonb) WHERE id = ?",
                "{\"tampered\":true}", draft.id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE prompt_versions SET output_contract = CAST(? AS jsonb) WHERE id = ?",
                "{\"tampered\":true}", draft.id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE prompt_versions SET prompt_key = 'tampered' WHERE id = ?",
                draft.id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE prompt_versions SET version_no = 2 WHERE id = ?",
                draft.id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE prompt_versions SET space_id = ? WHERE id = ?",
                createSpace("prompt-state-other"), draft.id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE prompt_versions SET created_by_user_id = NULL WHERE id = ?",
                draft.id())).isInstanceOf(DataAccessException.class);

        Instant retiredAt = publishedAt.plusSeconds(1);
        UUID retiredCorrelation = UUID.randomUUID();
        assertThat(jdbc.update("""
                UPDATE prompt_versions
                SET status = 'RETIRED', updated_at = ?, correlation_id = ?
                WHERE id = ? AND space_id = ?
                """, timestamp(retiredAt), retiredCorrelation, draft.id(), spaceA)).isEqualTo(1);

        PromptRepository.PromptVersion retired = prompts.findVersion(spaceA, draft.id()).orElseThrow();
        assertThat(retired.status()).isEqualTo(PromptRepository.PromptStatus.RETIRED);
        assertThat(retired.versionNo()).isEqualTo(published.versionNo());
        assertThat(retired.createdAt()).isEqualTo(published.createdAt());
        assertThat(retired.updatedAt()).isEqualTo(retiredAt);
        assertThat(retired.correlationId()).isEqualTo(retiredCorrelation);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE prompt_versions
                SET status = 'PUBLISHED', updated_at = ?, correlation_id = ?
                WHERE id = ?
                """, timestamp(retiredAt.plusSeconds(1)), UUID.randomUUID(), draft.id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE prompt_versions SET template = 'retired-tampered' WHERE id = ?",
                draft.id())).isInstanceOf(DataAccessException.class);
    }

    @Test
    void draftCannotSkipPublication() {
        UUID space = createSpace("prompt-state-draft");
        PromptRepository.PromptVersion draft = createPrompt(space, null, PromptRepository.PromptStatus.DRAFT,
                Instant.parse("2026-08-13T00:00:00Z"), UUID.randomUUID());

        assertThatThrownBy(() -> jdbc.update("UPDATE prompt_versions SET status = 'RETIRED' WHERE id = ?",
                draft.id())).isInstanceOf(DataAccessException.class);
    }

    @Test
    void promptBindingForeignKeyRejectsCrossSpaceReference() {
        UUID spaceA = createSpace("prompt-fk-a");
        UUID spaceB = createSpace("prompt-fk-b");
        PromptRepository.PromptVersion promptA = createPrompt(spaceA, null, PromptRepository.PromptStatus.DRAFT,
                Instant.parse("2026-08-13T00:00:00Z"), UUID.randomUUID());

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO space_prompt_bindings
                    (id, space_id, binding_key, version_no, prompt_version_id, status,
                     created_at, updated_at, correlation_id)
                VALUES (?, ?, 'cross-space', 1, ?, 'ACTIVE', ?, ?, ?)
                """, UUID.randomUUID(), spaceB, promptA.id(), timestamp(Instant.parse("2026-08-13T00:00:01Z")),
                timestamp(Instant.parse("2026-08-13T00:00:01Z")), UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
    }

    private PromptRepository.PromptVersion createPrompt(UUID spaceId, UUID authorId,
                                                         PromptRepository.PromptStatus status, Instant now,
                                                         UUID correlationId) {
        return prompts.createVersion(new PromptRepository.NewPromptVersion(
                UUID.randomUUID(), spaceId, "chat-system", 1, "You are a helpful assistant.",
                "{\"type\":\"object\"}", "{\"type\":\"object\"}", "initial", authorId, status, now,
                correlationId));
    }

    private UUID createSpace(String name) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        jdbc.update("""
                INSERT INTO knowledge_spaces (id, name, description, status, created_at, updated_at, version)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?, 0)
                """, id, name, name, timestamp(now), timestamp(now));
        return id;
    }

    private UUID createUser(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, display_name)
                VALUES (?, ?, 'not-a-real-password-hash', 'Prompt Test User')
                """, id, email);
        return id;
    }

    private static java.sql.Timestamp timestamp(Instant value) {
        return java.sql.Timestamp.from(value);
    }
}
