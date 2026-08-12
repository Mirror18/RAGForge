package com.ragforge.server.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.provider.ProviderRepository;
import com.ragforge.server.provider.SpaceBindingRepository;
import com.ragforge.server.prompt.PromptRepository;
import com.ragforge.server.space.SpaceRepository;
import com.ragforge.server.space.SpaceRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real local-Ollama acceptance proof for the Phase 2 no-RAG execution chain.
 *
 * <p>This test intentionally uses the application service and persisted adapters rather than
 * calling Ollama as a standalone HTTP test. Its output is a safe evidence summary: it never
 * prints the provider response, the synthetic user message, or any credential value.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalOllamaRunAcceptanceTest {
    private static final String OLLAMA_ENDPOINT = "http://127.0.0.1:11434";
    private static final String MODEL_NAME = "qwen3.5:9b";
    private static final String EXPECTED_MODEL_DIGEST =
            "6488c96fa5faab64bb65cbd30d4289e20e6130ef535a93ef9a49f42eda893ea7";
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");
    private static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.0.1-alpine")
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
    ObjectMapper objectMapper;

    @Autowired
    ProviderRepository providers;

    @Autowired
    SpaceBindingRepository bindings;

    @Autowired
    PromptRepository prompts;

    @Autowired
    SpaceRepository spaces;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    RunRepository runs;

    @Autowired
    RunEventService eventService;

    @Autowired
    RunExecutionService executionService;

    private UUID user;
    private UUID space;
    private Setup setup;
    private SessionPrincipal principal;

    @AfterAll
    static void stopContainers() {
        try {
            VALKEY.stop();
        } finally {
            POSTGRES.stop();
        }
    }

    @BeforeEach
    void prepare() {
        jdbc.execute("TRUNCATE space_binding_versions, usage_ledger, model_invocations, run_steps, runs, conversations, "
                + "space_prompt_bindings, prompt_versions, space_model_bindings, model_route_candidates, "
                + "model_route_versions, model_profile_versions, provider_connections, space_memberships, "
                + "knowledge_spaces, sessions, users CASCADE");
        Instant now = Instant.now();
        user = UUID.randomUUID();
        space = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, display_name) VALUES (?, ?, ?, ?)",
                user, "ollama-accept-" + user + "@example.test", "not-used", "Ollama Acceptance");
        spaces.create(space, "space-" + space, "local Ollama acceptance", now);
        spaces.addMembership(space, user, SpaceRole.EDITOR, now);
        setup = setup(space, now);
        principal = new SessionPrincipal(user, UUID.randomUUID(), "ollama-accept@example.test",
                "Ollama Acceptance", "csrf", "USER", Instant.MAX);
    }

    @Test
    @Timeout(value = 130)
    void realLocalOllamaNoRagRunPersistsSafeExecutionChain() throws Exception {
        ModelTag model = readLocalModelTag();
        assertThat(model.name()).isEqualTo(MODEL_NAME);
        assertThat(model.digest()).isEqualTo(EXPECTED_MODEL_DIGEST);

        ProviderRepository.ProviderConnection provider = setup.provider();
        assertThat(provider.spaceId()).isEqualTo(space);
        assertThat(provider.providerType()).isEqualTo(ProviderRepository.ProviderType.OLLAMA);
        assertThat(provider.endpointUri()).isEqualTo(OLLAMA_ENDPOINT);
        assertThat(provider.authScheme()).isEqualTo("NONE");
        assertThat(provider.status()).isEqualTo(ProviderRepository.ProviderStatus.ACTIVE);
        assertThat(provider.egressPolicy()).isEqualTo(ProviderRepository.EgressPolicy.LOCAL_ONLY);

        SpaceBindingRepository.SpaceBindingRecord binding = bindings.findCurrent(space).orElseThrow();
        assertThat(binding.spaceId()).isEqualTo(space);
        assertThat(binding.chatRouteId()).isEqualTo(setup.chatRoute().id());
        assertThat(binding.embeddingRouteId()).isEqualTo(setup.embeddingRoute().id());
        assertThat(binding.rerankRouteId()).isEqualTo(setup.rerankRoute().id());
        assertThat(binding.promptVersionId()).isEqualTo(setup.prompt().id());
        assertThat(binding.cloudEgressEnabled()).isFalse();
        assertThat(binding.authorization()).isNull();

        String syntheticMessage = "Say hello briefly.";
        ConversationRepository.ConversationRecord conversation = executionService.createConversation(
                space, principal, "Local Ollama no-RAG acceptance");
        RunRepository.RunRecord run = executionService.createRun(space, conversation.id(), principal,
                new RunExecutionService.RunRequest(setup.chatRoute().id(), setup.chatProfile().id(), provider.id(),
                        setup.prompt().id(), syntheticMessage, false, 120), UUID.randomUUID());

        assertThat(run.spaceId()).isEqualTo(space);
        assertThat(run.conversationId()).isEqualTo(conversation.id());
        assertThat(run.status()).isEqualTo(RunRepository.RunStatus.SUCCEEDED);
        assertThat(run.routeVersionId()).isEqualTo(setup.chatRoute().id());
        assertThat(run.promptVersionId()).isEqualTo(setup.prompt().id());
        assertThat(run.inputHash()).matches("[0-9a-f]{64}");
        assertThat(run.outputHash()).matches("[0-9a-f]{64}");

        List<RunRepository.StepRecord> steps = executionService.getSteps(space, run.id(), principal);
        assertThat(steps).hasSize(1);
        RunRepository.StepRecord step = steps.getFirst();
        assertThat(step.spaceId()).isEqualTo(space);
        assertThat(step.runId()).isEqualTo(run.id());
        assertThat(step.stepType()).isEqualTo(RunRepository.StepType.GENERATE);
        assertThat(step.status()).isEqualTo(RunRepository.RunStatus.SUCCEEDED);

        Map<String, Object> invocation = jdbc.queryForMap("""
                SELECT id, space_id, run_id, step_id, provider_connection_id, model_profile_version_id,
                       model_route_version_id, prompt_version_id, provider_request_identity,
                       prompt_render_hash, request_metadata, response_hash, status
                FROM model_invocations
                WHERE space_id = ? AND run_id = ?
                """, space, run.id());
        UUID invocationId = (UUID) invocation.get("id");
        assertThat(invocation.get("space_id")).isEqualTo(space);
        assertThat(invocation.get("run_id")).isEqualTo(run.id());
        assertThat(invocation.get("step_id")).isEqualTo(step.id());
        assertThat(invocation.get("provider_connection_id")).isEqualTo(provider.id());
        assertThat(invocation.get("model_profile_version_id")).isEqualTo(setup.chatProfile().id());
        assertThat(invocation.get("model_route_version_id")).isEqualTo(setup.chatRoute().id());
        assertThat(invocation.get("prompt_version_id")).isEqualTo(setup.prompt().id());
        assertThat(invocation.get("status")).isEqualTo("SUCCEEDED");
        assertThat(invocation.get("prompt_render_hash").toString()).matches("[0-9a-f]{64}");
        assertThat(invocation.get("response_hash").toString()).matches("[0-9a-f]{64}");
        assertThat(objectMapper.readTree(invocation.get("request_metadata").toString()).path("messageCount").asInt())
                .isEqualTo(2);

        List<Map<String, Object>> usageRows = jdbc.queryForList("""
                SELECT id, space_id, model_invocation_id, provider_request_identity, usage_source,
                       input_tokens, output_tokens, total_tokens, metadata
                FROM usage_ledger
                WHERE space_id = ? AND model_invocation_id = ?
                """, space, invocationId);
        assertThat(usageRows).hasSize(1);
        Map<String, Object> usage = usageRows.getFirst();
        assertThat(usage.get("space_id")).isEqualTo(space);
        assertThat(usage.get("model_invocation_id")).isEqualTo(invocationId);
        assertThat(usage.get("usage_source")).isEqualTo("PROVIDER_REPORTED");
        long inputTokens = ((Number) usage.get("input_tokens")).longValue();
        long outputTokens = ((Number) usage.get("output_tokens")).longValue();
        long totalTokens = ((Number) usage.get("total_tokens")).longValue();
        assertThat(inputTokens).isPositive();
        assertThat(outputTokens).isPositive();
        assertThat(totalTokens).isEqualTo(inputTokens + outputTokens);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'model_invocations'
                  AND column_name IN ('response', 'raw_response', 'raw_output', 'output')
                """, Integer.class)).isZero();
        String persistedRequestMetadata = invocation.values().toString() + usage.values();
        assertThat(persistedRequestMetadata).doesNotContain(syntheticMessage);
        String storedPromptTemplate = jdbc.queryForObject(
                "SELECT template FROM prompt_versions WHERE id = ? AND space_id = ?",
                String.class, setup.prompt().id(), space);
        assertThat(storedPromptTemplate).doesNotContain(syntheticMessage);
        assertThat(eventService.replay(space, run.id(), null).events())
                .allSatisfy(event -> assertThat(event.payloadJson()).doesNotContain(syntheticMessage));

        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("schemaVersion", "phase2-local-ollama-run-v1");
        evidence.put("providerType", provider.providerType().name());
        evidence.put("endpoint", OLLAMA_ENDPOINT);
        evidence.put("authScheme", provider.authScheme());
        evidence.put("egressPolicy", provider.egressPolicy().name());
        evidence.put("providerStatus", provider.status().name());
        evidence.put("model", model.name());
        evidence.put("modelDigest", model.digest());
        evidence.put("spaceBindingVerified", true);
        evidence.put("cloudEgressEnabled", binding.cloudEgressEnabled());
        evidence.put("conversationKind", "NO_RAG");
        evidence.put("runStatus", run.status().name());
        evidence.put("stepCount", steps.size());
        evidence.put("stepStatus", step.status().name());
        evidence.put("modelInvocationRows", 1);
        evidence.put("modelInvocationStatus", invocation.get("status").toString());
        evidence.put("usageLedgerRows", usageRows.size());
        evidence.put("usageSource", usage.get("usage_source").toString());
        evidence.put("inputTokens", inputTokens);
        evidence.put("outputTokens", outputTokens);
        evidence.put("totalTokens", totalTokens);
        evidence.put("inputHashPresent", true);
        evidence.put("promptRenderHashPresent", true);
        evidence.put("responseHashPresent", true);
        evidence.put("rawResponsePersisted", false);
        evidence.put("rawPromptPersisted", false);
        evidence.put("secretPersisted", false);
        System.out.println("PHASE2_LOCAL_OLLAMA_EVIDENCE\n" + objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(evidence));
    }

    private ModelTag readLocalModelTag() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(OLLAMA_ENDPOINT + "/api/tags"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        final HttpResponse<String> response;
        try {
            response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception exception) {
            Assumptions.assumeTrue(false, "Ollama /api/tags unavailable: " + exception.getClass().getSimpleName());
            throw new AssertionError("unreachable after assumption");
        }
        Assumptions.assumeTrue(response.statusCode() == 200,
                "Ollama /api/tags returned status " + response.statusCode());
        JsonNode models = objectMapper.readTree(response.body()).path("models");
        for (JsonNode item : models) {
            if (MODEL_NAME.equals(item.path("name").asText())) {
                String digest = item.path("digest").asText();
                Assumptions.assumeTrue(digest.matches("[0-9a-f]{64}"),
                        "Ollama model digest is unavailable");
                return new ModelTag(item.path("name").asText(), digest);
            }
        }
        Assumptions.assumeTrue(false, "Ollama model " + MODEL_NAME + " is not installed");
        throw new AssertionError("unreachable after assumption");
    }

    private Setup setup(UUID requestedSpace, Instant now) {
        UUID correlation = UUID.randomUUID();
        ProviderRepository.ProviderConnection provider = providers.createConnection(
                new ProviderRepository.NewProviderConnection(UUID.randomUUID(), requestedSpace, "ollama-local",
                        "Local Ollama", ProviderRepository.ProviderType.OLLAMA, OLLAMA_ENDPOINT, "ollama-local-ref",
                        null, "NONE", "{}", ProviderRepository.ProviderStatus.ACTIVE,
                        ProviderRepository.EgressPolicy.LOCAL_ONLY, now, correlation));
        ProviderRepository.ModelProfileVersion chatProfile = providers.createProfileVersion(
                new ProviderRepository.NewModelProfileVersion(UUID.randomUUID(), requestedSpace, provider.id(),
                        "chat-local-qwen35-9b", 1, MODEL_NAME, "[\"CHAT\"]", "{}", "{}", 32768, 128,
                        null, "sentencepiece", "{}", null, "{}", ProviderRepository.ModelProfileStatus.PUBLISHED,
                        now, correlation));
        ProviderRepository.ModelProfileVersion embeddingProfile = providers.createProfileVersion(
                new ProviderRepository.NewModelProfileVersion(UUID.randomUUID(), requestedSpace, provider.id(),
                        "embedding-fixture", 1, "local-embedding-fixture", "[\"EMBEDDING\"]", "{}", "{}",
                        4096, 256, 768, "fixture", "{}", null, "{}", ProviderRepository.ModelProfileStatus.PUBLISHED,
                        now, correlation));
        ProviderRepository.ModelProfileVersion rerankProfile = providers.createProfileVersion(
                new ProviderRepository.NewModelProfileVersion(UUID.randomUUID(), requestedSpace, provider.id(),
                        "rerank-fixture", 1, "local-rerank-fixture", "[\"RERANK\"]", "{}", "{}", 4096, 128,
                        null, "fixture", "{}", null, "{}", ProviderRepository.ModelProfileStatus.PUBLISHED,
                        now, correlation));

        ProviderRepository.ModelRouteVersion chatRoute = createRoute(requestedSpace, "chat-local",
                ProviderRepository.RoutePurpose.CHAT, now, correlation);
        providers.addRouteCandidate(new ProviderRepository.NewRouteCandidate(UUID.randomUUID(), requestedSpace,
                chatRoute.id(), 1, chatProfile.id(), now, correlation));
        ProviderRepository.ModelRouteVersion embeddingRoute = createRoute(requestedSpace, "embedding-local",
                ProviderRepository.RoutePurpose.EMBEDDING, now, correlation);
        providers.addRouteCandidate(new ProviderRepository.NewRouteCandidate(UUID.randomUUID(), requestedSpace,
                embeddingRoute.id(), 1, embeddingProfile.id(), now, correlation));
        ProviderRepository.ModelRouteVersion rerankRoute = createRoute(requestedSpace, "rerank-local",
                ProviderRepository.RoutePurpose.RERANK, now, correlation);
        providers.addRouteCandidate(new ProviderRepository.NewRouteCandidate(UUID.randomUUID(), requestedSpace,
                rerankRoute.id(), 1, rerankProfile.id(), now, correlation));

        PromptRepository.PromptVersion prompt = prompts.createVersion(new PromptRepository.NewPromptVersion(
                UUID.randomUUID(), requestedSpace, "no-rag-chat", 1, "Reply briefly.", "{}", "{}",
                "local Ollama acceptance", user, PromptRepository.PromptStatus.PUBLISHED, now, correlation));
        ProviderRepository.SpaceModelBinding chatBinding = providers.bindRoute(new ProviderRepository.NewSpaceModelBinding(
                UUID.randomUUID(), requestedSpace, "local-chat", 1, ProviderRepository.RoutePurpose.CHAT,
                chatRoute.id(), ProviderRepository.BindingStatus.ACTIVE, now, correlation));
        ProviderRepository.SpaceModelBinding embeddingBinding = providers.bindRoute(new ProviderRepository.NewSpaceModelBinding(
                UUID.randomUUID(), requestedSpace, "local-embedding", 1, ProviderRepository.RoutePurpose.EMBEDDING,
                embeddingRoute.id(), ProviderRepository.BindingStatus.ACTIVE, now, correlation));
        ProviderRepository.SpaceModelBinding rerankBinding = providers.bindRoute(new ProviderRepository.NewSpaceModelBinding(
                UUID.randomUUID(), requestedSpace, "local-rerank", 1, ProviderRepository.RoutePurpose.RERANK,
                rerankRoute.id(), ProviderRepository.BindingStatus.ACTIVE, now, correlation));
        PromptRepository.SpacePromptBinding promptBinding = prompts.bind(new PromptRepository.NewSpacePromptBinding(
                UUID.randomUUID(), requestedSpace, "local-chat", 1, prompt.id(), PromptRepository.BindingStatus.ACTIVE,
                now, correlation));
        bindings.create(new SpaceBindingRepository.NewSpaceBinding(UUID.randomUUID(), requestedSpace, 1,
                chatBinding.id(), embeddingBinding.id(), rerankBinding.id(), promptBinding.id(), false, null, now,
                correlation));
        return new Setup(provider, chatProfile, chatRoute, embeddingRoute, rerankRoute, prompt);
    }

    private ProviderRepository.ModelRouteVersion createRoute(UUID requestedSpace, String routeKey,
                                                               ProviderRepository.RoutePurpose purpose, Instant now,
                                                               UUID correlation) {
        return providers.createRouteVersion(new ProviderRepository.NewModelRouteVersion(UUID.randomUUID(),
                requestedSpace, routeKey, 1, purpose, ProviderRepository.EgressPolicy.LOCAL_ONLY, false,
                ProviderRepository.SelectionPolicy.SINGLE, "{}", ProviderRepository.ModelRouteStatus.PUBLISHED,
                now, correlation));
    }

    private record ModelTag(String name, String digest) {
    }

    private record Setup(ProviderRepository.ProviderConnection provider,
                         ProviderRepository.ModelProfileVersion chatProfile,
                         ProviderRepository.ModelRouteVersion chatRoute,
                         ProviderRepository.ModelRouteVersion embeddingRoute,
                         ProviderRepository.ModelRouteVersion rerankRoute,
                         PromptRepository.PromptVersion prompt) {
    }
}
