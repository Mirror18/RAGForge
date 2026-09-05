package com.ragforge.server.provider;

import com.ragforge.server.prompt.PromptRepository;
import com.ragforge.server.run.RunRepository;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL proof for Flyway V3 and the three Phase 2 persistence boundaries. */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Phase2PersistenceIntegrationTest {
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
    ProviderRepository providers;

    @Autowired
    PromptRepository prompts;

    @Autowired
    RunRepository runs;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE usage_ledger, model_invocations, run_steps, runs, space_prompt_bindings, "
                + "prompt_versions, space_model_bindings, model_route_candidates, model_route_versions, "
                + "model_profile_versions, provider_connections, knowledge_spaces, users CASCADE");
    }

    @Test
    void flywayV3CreatesSpaceScopedSchemaAndRepositoriesPreserveInvariants() {
        Set<String> tables = Set.copyOf(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN (
                    'provider_connections', 'model_profile_versions', 'model_route_versions',
                    'model_route_candidates', 'space_model_bindings', 'prompt_versions',
                    'space_prompt_bindings', 'runs', 'run_steps', 'model_invocations', 'usage_ledger'
                )
                """, String.class));
        assertThat(tables).containsExactlyInAnyOrder(
                "provider_connections", "model_profile_versions", "model_route_versions",
                "model_route_candidates", "space_model_bindings", "prompt_versions",
                "space_prompt_bindings", "runs", "run_steps", "model_invocations", "usage_ledger");

        UUID spaceA = createSpace("phase2-a");
        UUID spaceB = createSpace("phase2-b");
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        UUID correlation = UUID.randomUUID();

        ProviderRepository.ProviderConnection providerA = providers.createConnection(
                new ProviderRepository.NewProviderConnection(UUID.randomUUID(), spaceA, "ollama-a", "Ollama A",
                        ProviderRepository.ProviderType.OLLAMA, "http://ollama-a:11434", null, null, "NONE", "{}",
                        ProviderRepository.ProviderStatus.ACTIVE, ProviderRepository.EgressPolicy.LOCAL_ONLY, now,
                        correlation));
        ProviderRepository.ModelProfileVersion profileA = providers.createProfileVersion(
                new ProviderRepository.NewModelProfileVersion(UUID.randomUUID(), spaceA, providerA.id(),
                        "chat-local", 1, "qwen3.5:9b", "[\"CHAT\",\"STREAMING\"]", "{}", "{}",
                        32768, 4096, null, "sentencepiece", "{}", null, "{}",
                        ProviderRepository.ModelProfileStatus.PUBLISHED, now, correlation));
        ProviderRepository.ModelRouteVersion routeA = providers.createRouteVersion(
                new ProviderRepository.NewModelRouteVersion(UUID.randomUUID(), spaceA, "chat-default", 1,
                        ProviderRepository.RoutePurpose.CHAT, ProviderRepository.EgressPolicy.LOCAL_ONLY, false,
                        ProviderRepository.SelectionPolicy.SINGLE, "{}", ProviderRepository.ModelRouteStatus.PUBLISHED,
                        now, correlation));
        providers.addRouteCandidate(new ProviderRepository.NewRouteCandidate(UUID.randomUUID(), spaceA, routeA.id(),
                1, profileA.id(), now, correlation));
        providers.bindRoute(new ProviderRepository.NewSpaceModelBinding(UUID.randomUUID(), spaceA, "chat", 1,
                ProviderRepository.RoutePurpose.CHAT, routeA.id(), ProviderRepository.BindingStatus.ACTIVE, now,
                correlation));

        PromptRepository.PromptVersion promptA = prompts.createVersion(new PromptRepository.NewPromptVersion(
                UUID.randomUUID(), spaceA, "chat-system", 1, "You are a helpful assistant.", "{}", "{}",
                "initial", null, PromptRepository.PromptStatus.PUBLISHED, now, correlation));
        prompts.bind(new PromptRepository.NewSpacePromptBinding(UUID.randomUUID(), spaceA, "chat", 1,
                promptA.id(), PromptRepository.BindingStatus.ACTIVE, now, correlation));

        RunRepository.RunRecord runA = runs.createRun(new RunRepository.NewRun(UUID.randomUUID(), spaceA, null,
                correlation, RunRepository.RequestKind.CHAT, RunRepository.RunStatus.QUEUED, routeA.id(), promptA.id(),
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", null, null, null, null, null,
                now));
        RunRepository.StepRecord stepA = runs.createStep(new RunRepository.NewStep(UUID.randomUUID(), spaceA,
                runA.id(), "generate", RunRepository.StepType.GENERATE, 1, 1, RunRepository.RunStatus.QUEUED,
                null, null, now, correlation));
        RunRepository.ModelInvocationRecord invocationA = runs.createInvocation(
                new RunRepository.NewModelInvocation(UUID.randomUUID(), spaceA, runA.id(), stepA.id(), providerA.id(),
                        profileA.id(), routeA.id(), promptA.id(), "ollama-request-1",
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "{}",
                        null, RunRepository.InvocationStatus.SUCCEEDED, null, null, now, correlation));

        String dedupeKeyA = "provider-request-1-final";
        String dedupeKeyB = "provider-request-1-retry";
        runs.recordUsage(new RunRepository.NewUsageLedgerEntry(UUID.randomUUID(), spaceA, invocationA.id(),
                "ollama-request-1", RunRepository.UsageSource.PROVIDER_REPORTED, dedupeKeyA, 10L, 20L, 30L,
                new java.math.BigDecimal("0.00000000"), "USD", "{}", now, correlation));
        RunRepository.UsageLedgerRecord updatedUsage = runs.recordUsage(
                new RunRepository.NewUsageLedgerEntry(UUID.randomUUID(), spaceA, invocationA.id(),
                "ollama-request-1", RunRepository.UsageSource.PROVIDER_REPORTED, dedupeKeyA, 11L, 21L, 32L,
                new java.math.BigDecimal("0.00000001"), "USD", "{\"replayed\":true}", now.plusSeconds(1),
                UUID.randomUUID()));
        runs.recordUsage(new RunRepository.NewUsageLedgerEntry(UUID.randomUUID(), spaceA, invocationA.id(),
                "ollama-request-1", RunRepository.UsageSource.PROVIDER_REPORTED, dedupeKeyB, 13L, 23L, 36L,
                new java.math.BigDecimal("0.00000002"), "USD", "{}", now.plusSeconds(2), correlation));
        runs.recordUsage(new RunRepository.NewUsageLedgerEntry(UUID.randomUUID(), spaceA, invocationA.id(),
                "ollama-request-1", RunRepository.UsageSource.LOCAL_ESTIMATE, "local-estimate-1", 11L, 21L, 32L,
                new java.math.BigDecimal("0.00000002"), "USD", "{}", now, correlation));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM usage_ledger
                WHERE space_id = ? AND provider_request_identity = 'ollama-request-1'
                """, Integer.class, spaceA)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM usage_ledger
                WHERE space_id = ? AND model_invocation_id = ?
                  AND usage_source = 'PROVIDER_REPORTED'
                """, Integer.class, spaceA, invocationA.id())).isEqualTo(2);
        assertThat(updatedUsage.id()).isEqualTo(runs.findUsage(
                spaceA, invocationA.id(), RunRepository.UsageSource.PROVIDER_REPORTED, dedupeKeyA).orElseThrow().id());
        assertThat(updatedUsage.totalTokens()).isEqualTo(32L);
        assertThat(runs.findUsage(spaceA, invocationA.id(), RunRepository.UsageSource.PROVIDER_REPORTED, dedupeKeyB))
                .get().extracting(RunRepository.UsageLedgerRecord::totalTokens).isEqualTo(36L);

        assertThat(providers.findProfileVersion(spaceB, profileA.id())).isEmpty();
        assertThat(prompts.findVersion(spaceB, promptA.id())).isEmpty();
        assertThat(runs.findRun(spaceB, runA.id())).isEmpty();
        assertThat(runs.findUsage(spaceB, invocationA.id(), RunRepository.UsageSource.PROVIDER_REPORTED, dedupeKeyA))
                .isEmpty();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO space_model_bindings
                    (id, space_id, binding_key, version_no, purpose, model_route_version_id,
                     status, created_at, updated_at, correlation_id)
                VALUES (?, ?, 'cross-space', 1, 'CHAT', ?, 'ACTIVE', ?, ?, ?)
                """, UUID.randomUUID(), spaceB, routeA.id(), now, now, UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);

        ProviderRepository.ProviderConnection providerB = providers.createConnection(
                new ProviderRepository.NewProviderConnection(UUID.randomUUID(), spaceB, "ollama-b", "Ollama B",
                        ProviderRepository.ProviderType.OLLAMA, "http://ollama-b:11434", null, null, "NONE", "{}",
                        ProviderRepository.ProviderStatus.ACTIVE, ProviderRepository.EgressPolicy.LOCAL_ONLY, now,
                        correlation));
        ProviderRepository.ModelProfileVersion profileB = providers.createProfileVersion(
                new ProviderRepository.NewModelProfileVersion(UUID.randomUUID(), spaceB, providerB.id(),
                        "chat-local", 1, "qwen3.5:9b", "[\"CHAT\"]", "{}", "{}", 32768, 4096, null,
                        "sentencepiece", "{}", null, "{}", ProviderRepository.ModelProfileStatus.PUBLISHED, now,
                        correlation));
        RunRepository.RunRecord runB = runs.createRun(new RunRepository.NewRun(UUID.randomUUID(), spaceB, null,
                correlation, RunRepository.RequestKind.CHAT, RunRepository.RunStatus.QUEUED, null, null, null, null,
                null, null, null, null, now));
        RunRepository.StepRecord stepB = runs.createStep(new RunRepository.NewStep(UUID.randomUUID(), spaceB,
                runB.id(), "generate", RunRepository.StepType.GENERATE, 1, 1, RunRepository.RunStatus.QUEUED,
                null, null, now, correlation));
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO model_invocations
                    (id, space_id, run_id, step_id, provider_connection_id, model_profile_version_id,
                     provider_request_identity, request_metadata, status, created_at, updated_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, 'cross-space-provider', '{}'::jsonb, 'SUCCEEDED', ?, ?, ?)
                """, UUID.randomUUID(), spaceB, runB.id(), stepB.id(), providerA.id(), profileB.id(), now, now,
                UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbc.update("UPDATE model_profile_versions SET model_name = 'tampered' WHERE id = ?",
                profileA.id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE prompt_versions SET template = 'tampered' WHERE id = ?",
                promptA.id())).isInstanceOf(DataAccessException.class);
    }

    private UUID createSpace(String name) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        jdbc.update("""
                INSERT INTO knowledge_spaces (id, name, description, status, created_at, updated_at, version)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?, 0)
                """, id, name, name, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        return id;
    }
}
