package com.ragforge.server.run;

import com.ragforge.server.prompt.PromptRepository;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL/Flyway proof for the Phase 5 immutable RAG provenance seam. */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Phase5PersistenceIntegrationTest {
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-21T08:00:00Z");

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

    @Autowired
    RunRepository runs;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE rag_model_invocation_provenance, rag_step_provenance, rag_run_provenance, "
                + "rag_prompt_versions, usage_ledger, model_invocations, run_steps, runs, "
                + "retrieval_profiles, index_versions, model_route_candidates, model_route_versions, "
                + "model_profile_versions, provider_connections, prompt_versions, prompt_templates, "
                + "knowledge_spaces, users CASCADE");
    }

    @Test
    void migrationAppliesAndProjectionIsSpaceScopedImmutableReplayableAndRedacted() {
        Set<String> tables = Set.copyOf(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN (
                    'rag_prompt_versions', 'rag_run_provenance', 'rag_step_provenance',
                    'rag_model_invocation_provenance')
                """, String.class));
        assertThat(tables).containsExactlyInAnyOrder(
                "rag_prompt_versions", "rag_run_provenance", "rag_step_provenance",
                "rag_model_invocation_provenance");
        assertThat(jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE version = '11'", String.class))
                .isEqualTo("11");

        UUID spaceA = createSpace("phase5-persistence-a");
        UUID spaceB = createSpace("phase5-persistence-b");
        UUID provider = createProvider(spaceA);
        UUID profile = createModelProfile(spaceA, provider);
        UUID route = createRoute(spaceA);
        UUID index = createIndex(spaceA);
        UUID retrievalProfile = createRetrievalProfile(spaceA);
        PromptRepository.PromptVersion noRagPrompt = prompts.createVersion(
                new PromptRepository.NewPromptVersion(UUID.randomUUID(), spaceA, "chat-system", 1,
                        "safe no-rag prompt", "{}", "{}", "baseline", null,
                        PromptRepository.PromptStatus.PUBLISHED, NOW, UUID.randomUUID()));
        PromptRepository.RagPromptVersion ragPrompt = prompts.createRagVersion(
                new PromptRepository.NewRagPromptVersion(UUID.randomUUID(), spaceA, "answer", 1,
                        "RAG_ANSWER", "opaque://prompt/rag-answer-v1", HASH_A, "{\"query\":{}}",
                        "{\"type\":\"object\"}", null, NOW, UUID.randomUUID()));

        RunRepository.RunRecord run = runs.createRun(new RunRepository.NewRun(
                UUID.randomUUID(), spaceA, null, UUID.randomUUID(), RunRepository.RequestKind.CHAT,
                RunRepository.RunStatus.QUEUED, route, noRagPrompt.id(), HASH_A, null, null, null,
                null, null, NOW));
        RunRepository.StepRecord step = runs.createStep(new RunRepository.NewStep(
                UUID.randomUUID(), spaceA, run.id(), "retrieve", RunRepository.StepType.RETRIEVE, 1, 1,
                RunRepository.RunStatus.QUEUED, null, null, NOW, UUID.randomUUID()));
        RunRepository.ModelInvocationRecord invocation = runs.createInvocation(new RunRepository.NewModelInvocation(
                UUID.randomUUID(), spaceA, run.id(), step.id(), provider, profile, route, noRagPrompt.id(),
                "provider-request-phase5", HASH_B, "{}", null, RunRepository.InvocationStatus.SUCCEEDED,
                null, null, NOW, UUID.randomUUID()));

        RunRepository.NewRagRunProvenance runInput = ragRun(spaceA, run.id(), ragPrompt.id(), index,
                retrievalProfile, route, profile);
        RunRepository.RagRunProvenance persistedRun = runs.createRagRunProvenance(runInput);
        RunRepository.RagStepProvenance persistedStep = runs.createRagStepProvenance(
                ragStep(spaceA, run.id(), step.id(), ragPrompt.id(), index, retrievalProfile, route, profile));
        RunRepository.RagModelInvocationProvenance persistedInvocation = runs.createRagModelInvocationProvenance(
                ragInvocation(spaceA, run.id(), step.id(), invocation.id(), ragPrompt.id(), index,
                        retrievalProfile, route, profile));

        RunRepository.RagReplayProjection replay = runs.findRagReplayProjection(spaceA, run.id()).orElseThrow();
        assertThat(replay.run()).isEqualTo(persistedRun);
        assertThat(replay.steps()).containsExactly(persistedStep);
        assertThat(replay.modelInvocations()).containsExactly(persistedInvocation);
        assertThat(replay.run().indexVersionId()).isEqualTo(index);
        assertThat(replay.run().retrievalProfileId()).isEqualTo(retrievalProfile);
        assertThat(replay.run().retrievalProfileVersion()).isEqualTo(1);
        assertThat(replay.run().evidenceBundleVersion()).isEqualTo(3);
        assertThat(replay.run().evidenceBundleHash()).isEqualTo(HASH_B);
        assertThat(replay.run().toolSchemaVersionsJson())
                .contains("\"knowledge.search\"")
                .contains("\"v1\"");

        assertThat(runs.findRagReplayProjection(spaceB, run.id())).isEmpty();
        assertThat(runs.findRagRunProvenance(spaceB, run.id())).isEmpty();
        assertThatThrownBy(() -> runs.createRagRunProvenance(ragRun(
                spaceB, run.id(), ragPrompt.id(), index, retrievalProfile, route, profile)))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE rag_run_provenance SET config_hash = ? WHERE id = ?", HASH_B, persistedRun.id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE rag_prompt_versions SET prompt_hash = ? WHERE id = ?", HASH_B, ragPrompt.id()))
                .isInstanceOf(DataAccessException.class);

        Set<String> columns = Set.copyOf(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name IN (
                    'rag_prompt_versions', 'rag_run_provenance', 'rag_step_provenance',
                    'rag_model_invocation_provenance')
                """, String.class));
        assertThat(columns).doesNotContain("template", "raw_prompt", "document", "raw_document",
                "output", "raw_output", "request_body", "response_body", "tool_schema");
        assertThatThrownBy(() -> prompts.createRagVersion(new PromptRepository.NewRagPromptVersion(
                UUID.randomUUID(), spaceA, "redaction", 2, "RAG_ANSWER", "opaque://raw_prompt/1",
                HASH_A, "{}", "{}", null, NOW, UUID.randomUUID())))
                .isInstanceOf(DataAccessException.class);
    }

    private RunRepository.NewRagRunProvenance ragRun(UUID space, UUID run, UUID prompt, UUID index,
                                                     UUID retrievalProfile, UUID route, UUID profile) {
        return new RunRepository.NewRagRunProvenance(UUID.randomUUID(), space, run, prompt, HASH_A, index,
                retrievalProfile, 1, route, profile, 3, HASH_B, "opaque://bundle/3",
                "{\"knowledge.search\":\"v1\"}", HASH_A, HASH_B, UUID.randomUUID(), UUID.randomUUID(), NOW);
    }

    private RunRepository.NewRagStepProvenance ragStep(UUID space, UUID run, UUID step, UUID prompt, UUID index,
                                                        UUID retrievalProfile, UUID route, UUID profile) {
        return new RunRepository.NewRagStepProvenance(UUID.randomUUID(), space, run, step, prompt, HASH_A, index,
                retrievalProfile, 1, route, profile, 3, HASH_B, "opaque://bundle/3",
                "{\"knowledge.search\":\"v1\"}", HASH_A, HASH_B, UUID.randomUUID(), UUID.randomUUID(), NOW);
    }

    private RunRepository.NewRagModelInvocationProvenance ragInvocation(UUID space, UUID run, UUID step,
                                                                          UUID invocation, UUID prompt, UUID index,
                                                                          UUID retrievalProfile, UUID route,
                                                                          UUID profile) {
        return new RunRepository.NewRagModelInvocationProvenance(UUID.randomUUID(), space, run, step, invocation,
                prompt, HASH_A, index, retrievalProfile, 1, route, profile, 3, HASH_B, "opaque://bundle/3",
                "{\"knowledge.search\":\"v1\"}", HASH_A, HASH_B, UUID.randomUUID(), UUID.randomUUID(), NOW);
    }

    private UUID createProvider(UUID space) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO provider_connections
                    (id, space_id, provider_key, display_name, provider_type, endpoint_uri, status,
                     egress_policy, created_at, updated_at, correlation_id)
                VALUES (?, ?, 'phase5-provider', 'Phase5 Provider', 'AI_RUNTIME', 'http://localhost',
                        'ACTIVE', 'LOCAL_ONLY', ?, ?, ?)
                """, id, space, timestamp(NOW), timestamp(NOW), UUID.randomUUID());
        return id;
    }

    private UUID createModelProfile(UUID space, UUID provider) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO model_profile_versions
                    (id, space_id, provider_connection_id, profile_key, version_no, model_name,
                     capabilities, declared_capabilities, verified_capabilities, context_window,
                     max_output_tokens, status, created_at, updated_at, correlation_id)
                VALUES (?, ?, ?, 'phase5-model', 1, 'phase5-model', '[\"CHAT\"]', '{}', '{}',
                        4096, 512, 'PUBLISHED', ?, ?, ?)
                """, id, space, provider, timestamp(NOW), timestamp(NOW), UUID.randomUUID());
        return id;
    }

    private UUID createRoute(UUID space) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO model_route_versions
                    (id, space_id, route_key, version_no, purpose, egress_policy, allow_cloud_egress,
                     selection_policy, compatibility, status, created_at, updated_at, correlation_id)
                VALUES (?, ?, 'phase5-route', 1, 'CHAT', 'LOCAL_ONLY', FALSE, 'SINGLE', '{}',
                        'PUBLISHED', ?, ?, ?)
                """, id, space, timestamp(NOW), timestamp(NOW), UUID.randomUUID());
        return id;
    }

    private UUID createIndex(UUID space) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO index_versions
                    (id, space_id, version_no, index_state, candidate_collection,
                     embedding_profile_version, chunking_strategy_version, validation_sample_retrieval_passed,
                     validation_space_filter_passed, created_at)
                VALUES (?, ?, 1, 'ACTIVE', 'qdrant://phase5', 'embed-v1', 'chunk-v1', TRUE, TRUE, ?)
                """, id, space, timestamp(NOW));
        return id;
    }

    private UUID createRetrievalProfile(UUID space) {
        UUID id = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO retrieval_profiles
                    (id, space_id, profile_id, version_no, dense_top_k, bm25_top_k, rrf_k,
                     rrf_dense_weight, rrf_bm25_weight, rerank_top_k, max_context_children,
                     expansion_mode, max_parents_per_child, max_neighbors_per_parent, max_context_tokens,
                     created_at)
                VALUES (?, ?, ?, 1, 10, 10, 60, 0.7, 0.3, 10, 8, 'PARENT', 2, 2, 2000, ?)
                """, id, space, profileId, timestamp(NOW));
        return profileId;
    }

    private UUID createSpace(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO knowledge_spaces (id, name, description, status, created_at, updated_at, version)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?, 0)
                """, id, name, name, timestamp(NOW), timestamp(NOW));
        return id;
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }
}
