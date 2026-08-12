package com.ragforge.server.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.provider.ProviderRepository;
import com.ragforge.server.prompt.PromptRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunExecutionControllerIntegrationTest {
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
        registry.add("spring.data.redis.url", () -> "redis://" + VALKEY.getHost() + ":" + VALKEY.getMappedPort(6379));
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ProviderRepository providers;
    @Autowired PromptRepository prompts;
    @Autowired RunRepository runs;
    @Autowired RunExecutionService executionService;
    @Autowired RunEventService eventService;

    private UUID user;
    private UUID space;
    private UUID otherSpace;
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
        jdbc.execute("TRUNCATE usage_ledger, model_invocations, run_steps, runs, conversations, "
                + "space_prompt_bindings, prompt_versions, space_model_bindings, model_route_candidates, "
                + "model_route_versions, model_profile_versions, provider_connections, space_memberships, "
                + "knowledge_spaces, sessions, users CASCADE");
        Instant now = Instant.now();
        user = UUID.randomUUID();
        space = UUID.randomUUID();
        otherSpace = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, display_name) VALUES (?, ?, ?, ?)",
                user, "run-test-" + user + "@example.test", "not-used", "Run Test");
        for (UUID value : new UUID[]{space, otherSpace}) {
            jdbc.update("""
                    INSERT INTO knowledge_spaces (id, name, description, status, created_at, updated_at, version)
                    VALUES (?, ?, ?, 'ACTIVE', ?, ?, 0)
                    """, value, "space-" + value, "run", java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        }
        jdbc.update("""
                INSERT INTO space_memberships (space_id, user_id, role, created_at, updated_at, version)
                VALUES (?, ?, 'EDITOR', ?, ?, 0)
                """, space, user, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        setup = setup(space, now);
        principal = new SessionPrincipal(user, UUID.randomUUID(), "run-test@example.test", "Run Test",
                "csrf", "USER", Instant.MAX);
    }

    @Test
    void mockMvcCreatesConversationAndExecutesSuccessfulFakeRunWithDedupedUsage() throws Exception {
        UUID conversation = createConversation(space, "No RAG chat");
        MvcResult created = withPrincipal(() -> mvc.perform(post("/api/v1/spaces/{space}/conversations/{conversation}/runs",
                        space, conversation).with(auth()).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", UUID.randomUUID().toString())
                        .content(runBody("hello")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.runId").value(org.hamcrest.Matchers.notNullValue()))
                .andExpect(jsonPath("$.conversationId").value(conversation.toString()))
                .andExpect(jsonPath("$.modelRouteId").value(setup.route.id().toString()))
                .andExpect(jsonPath("$.promptVersionId").value(setup.prompt.id().toString()))
                .andExpect(jsonPath("$.usageLedgerId").value(org.hamcrest.Matchers.notNullValue()))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andReturn());
        JsonNode createdBody = objectMapper.readTree(created.getResponse().getContentAsString());
        UUID runId = UUID.fromString(createdBody.get("runId").asText());
        UUID responseUsageLedgerId = UUID.fromString(createdBody.get("usageLedgerId").asText());
        assertThat(jdbc.queryForObject("""
                SELECT u.id
                FROM usage_ledger u
                JOIN model_invocations i ON i.id = u.model_invocation_id AND i.space_id = u.space_id
                WHERE u.space_id = ? AND i.run_id = ?
                ORDER BY CASE WHEN u.usage_source = 'PROVIDER_REPORTED' THEN 0 ELSE 1 END, u.created_at, u.id
                LIMIT 1
                """, UUID.class, space, runId)).isEqualTo(responseUsageLedgerId);

        withPrincipal(() -> mvc.perform(get("/api/v1/spaces/{space}/runs/{run}", space, runId).with(auth())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.steps[0].stepId").exists())
                .andExpect(jsonPath("$.lastSequence").isNumber());
        withPrincipal(() -> mvc.perform(get("/api/v1/spaces/{space}/runs/{run}/steps", space, runId).with(auth())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.items[0].stepId").exists())
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));

        UUID invocation = jdbc.queryForObject("SELECT id FROM model_invocations WHERE run_id = ?", UUID.class, runId);
        UUID correlation = UUID.randomUUID();
        RunRepository.NewUsageLedgerEntry duplicate = new RunRepository.NewUsageLedgerEntry(UUID.randomUUID(), space,
                invocation, "run-" + runId, RunRepository.UsageSource.PROVIDER_REPORTED, "dedupe-key-123", 1L, 2L,
                3L, java.math.BigDecimal.ZERO, "USD", "{}", Instant.now(), correlation);
        runs.recordUsage(duplicate);
        runs.recordUsage(new RunRepository.NewUsageLedgerEntry(UUID.randomUUID(), space, invocation,
                "run-" + runId, RunRepository.UsageSource.PROVIDER_REPORTED, "dedupe-key-123", 2L, 3L,
                5L, java.math.BigDecimal.ZERO, "USD", "{\"updated\":true}", Instant.now(), correlation));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM usage_ledger WHERE space_id = ? AND model_invocation_id = ?
                  AND usage_source = 'PROVIDER_REPORTED' AND dedupe_key = 'dedupe-key-123'
                """, Integer.class, space, invocation)).isEqualTo(1);
    }

    @Test
    void mockMvcPersistsStructuredFakeProviderErrorAndDeniesCrossSpace() throws Exception {
        UUID conversation = createConversation(space, "Error chat");
        withPrincipal(() -> mvc.perform(post("/api/v1/spaces/{space}/conversations/{conversation}/runs",
                        space, conversation).with(auth()).contentType(MediaType.APPLICATION_JSON)
                        .content(runBody("__fake_error__")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.errorClass").value("INVALID_RESPONSE"))
                .andExpect(jsonPath("$.message").doesNotExist()));

        withPrincipal(() -> mvc.perform(get("/api/v1/spaces/{space}/runs/{run}", otherSpace, UUID.randomUUID()).with(auth())))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelEndpointIsIdempotentCancelsProviderFutureAndPersistsConsistentState() throws Exception {
        UUID conversation = createConversation(space, "Cancellation chat");
        CompletableFuture<RunRepository.RunRecord> execution = CompletableFuture.supplyAsync(() ->
                executionService.createRun(space, conversation, principal,
                        runRequest("__fake_block__-" + UUID.randomUUID()), UUID.randomUUID()));
        UUID runId = awaitLatestRun();

        MvcResult cancelResult = withPrincipal(() -> mvc.perform(post("/api/v1/spaces/{space}/runs/{run}/cancel", space, runId)
                        .with(auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"client disconnected\"}"))).andReturn();
        if (cancelResult.getResolvedException() != null) {
            throw new AssertionError("cancel endpoint failed: "
                    + cancelResult.getResolvedException().getClass().getName() + ": "
                    + cancelResult.getResolvedException().getMessage(), cancelResult.getResolvedException());
        }
        assertThat(cancelResult.getResponse().getStatus()).isEqualTo(202);
        JsonNode cancelBody = objectMapper.readTree(cancelResult.getResponse().getContentAsString());
        assertThat(cancelBody.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelBody.has("runId")).isTrue();
        assertThat(cancelBody.get("usageLedgerId").isNull()).isTrue();
        assertThat(cancelBody.has("message")).isFalse();

        RunRepository.RunRecord cancelled = execution.get(10, TimeUnit.SECONDS);
        assertThat(cancelled.status()).isEqualTo(RunRepository.RunStatus.CANCELLED);
        assertThat(jdbc.queryForObject("SELECT status FROM run_steps WHERE run_id = ?", String.class, runId))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM model_invocations WHERE run_id = ?", String.class, runId))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM usage_ledger u
                JOIN model_invocations i ON i.id = u.model_invocation_id
                WHERE i.run_id = ?
                """, Integer.class, runId))
                .isEqualTo(0);

        withPrincipal(() -> mvc.perform(post("/api/v1/spaces/{space}/runs/{run}/cancel", space, runId)
                        .with(auth()).contentType(MediaType.APPLICATION_JSON)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        List<RunEvent> retained = eventService.replay(space, runId, null).events();
        assertThat(retained.stream().filter(event -> event.type().equals("run.status")
                && event.payloadJson().contains("CANCELLED")).count()).isEqualTo(1);
    }

    @Test
    void timeoutIsRetryableAndRetryCreatesNewInvocationWithOneUsageRow() throws Exception {
        UUID conversation = createConversation(space, "Retry chat");
        String message = "__fake_timeout_once__-" + UUID.randomUUID();
        MvcResult failedResult = withPrincipal(() -> mvc.perform(post("/api/v1/spaces/{space}/conversations/{conversation}/runs",
                        space, conversation).with(auth()).contentType(MediaType.APPLICATION_JSON)
                        .content(runBody(message))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.errorClass").value("TIMEOUT"))
                .andReturn();
        UUID failedRunId = responseId(failedResult);

        MvcResult retriedResult = withPrincipal(() -> mvc.perform(post("/api/v1/spaces/{space}/runs/{run}/retry",
                        space, failedRunId).with(auth()).header("X-Correlation-Id", UUID.randomUUID().toString())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.runId").exists())
                .andReturn();
        UUID retriedRunId = responseId(retriedResult);

        assertThat(retriedRunId).isNotEqualTo(failedRunId);
        assertThat(jdbc.queryForObject("SELECT status FROM runs WHERE id = ?", String.class, failedRunId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_invocations WHERE run_id IN (?, ?)", Integer.class,
                failedRunId, retriedRunId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM model_invocations WHERE run_id = ?", String.class, failedRunId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM model_invocations WHERE run_id = ?", String.class, retriedRunId))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usage_ledger WHERE space_id = ?", Integer.class, space))
                .isEqualTo(1);

        List<RunEvent> all = eventService.replay(space, retriedRunId, null).events();
        assertThat(all).isNotEmpty();
        RunEvent cursor = all.getFirst();
        RunEventStore.OpenedStream replay = eventService.openStream(space, retriedRunId,
                cursor.eventId().toString(), ignored -> { });
        assertThat(replay.replay().cursorStatus()).isEqualTo(RunEventStore.CursorStatus.AVAILABLE);
        assertThat(replay.replay().events()).allMatch(event -> event.sequence() > cursor.sequence());
        replay.subscription().close();

        withPrincipal(() -> mvc.perform(post("/api/v1/spaces/{space}/runs/{run}/retry", space, retriedRunId)
                        .with(auth())))
                .andExpect(status().isConflict());
    }

    @Test
    void disconnectedStreamStopsDeliveryAndCrossSpaceCancelIsDenied() throws Exception {
        UUID conversation = createConversation(space, "Replay chat");
        MvcResult result = withPrincipal(() -> mvc.perform(post("/api/v1/spaces/{space}/conversations/{conversation}/runs",
                        space, conversation).with(auth()).contentType(MediaType.APPLICATION_JSON)
                        .content(runBody("replay-" + UUID.randomUUID()))))
                .andExpect(status().isAccepted()).andReturn();
        UUID runId = responseId(result);
        List<RunEvent> all = eventService.replay(space, runId, null).events();
        RunEvent cursor = all.getFirst();
        List<RunEvent> delivered = new java.util.concurrent.CopyOnWriteArrayList<>();
        RunEventStore.OpenedStream opened = eventService.openStream(space, runId, cursor.eventId().toString(), delivered::add);
        opened.subscription().activate();
        opened.subscription().close();
        eventService.append(space, runId, UUID.randomUUID(), "step.status", 1,
                "{\"status\":\"SUCCEEDED\",\"step\":\"generate\"}");
        assertThat(delivered).isEmpty();
        assertThat(opened.replay().events()).allMatch(event -> event.sequence() > cursor.sequence());

        withPrincipal(() -> mvc.perform(post("/api/v1/spaces/{space}/runs/{run}/cancel", otherSpace, runId)
                        .with(auth()).contentType(MediaType.APPLICATION_JSON)))
                .andExpect(status().isNotFound());
    }

    private UUID createConversation(UUID requestedSpace, String title) throws Exception {
        MvcResult result = withPrincipal(() -> mvc.perform(post("/api/v1/spaces/{space}/conversations", requestedSpace).with(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}")))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private String runBody(String message) throws Exception {
        return objectMapper.writeValueAsString(new RunExecutionController.CreateRunRequest(setup.route.id(), setup.profile.id(),
                setup.provider.id(), setup.prompt.id(), message, false, 5));
    }

    private RunExecutionService.RunRequest runRequest(String message) {
        return new RunExecutionService.RunRequest(setup.route.id(), setup.profile.id(), setup.provider.id(),
                setup.prompt.id(), message, false, 5);
    }

    private UUID responseId(MvcResult result) throws Exception {
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("runId").asText());
    }

    private UUID awaitLatestRun() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM runs WHERE space_id = ?", Integer.class, space);
            if (count != null && count > 0) {
                return jdbc.queryForObject("SELECT id FROM runs WHERE space_id = ? ORDER BY created_at DESC LIMIT 1",
                        UUID.class, space);
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for queued run");
    }

    private Setup setup(UUID requestedSpace, Instant now) {
        UUID correlation = UUID.randomUUID();
        ProviderRepository.ProviderConnection provider = providers.createConnection(
                new ProviderRepository.NewProviderConnection(UUID.randomUUID(), requestedSpace, "fake-provider", "Fake",
                        ProviderRepository.ProviderType.AI_RUNTIME, "http://localhost", "fake-ref", null, "NONE", "{}",
                        ProviderRepository.ProviderStatus.ACTIVE, ProviderRepository.EgressPolicy.LOCAL_ONLY, now, correlation));
        ProviderRepository.ModelProfileVersion profile = providers.createProfileVersion(
                new ProviderRepository.NewModelProfileVersion(UUID.randomUUID(), requestedSpace, provider.id(), "fake", 1,
                        "fake-model", "[\"CHAT\"]", "{}", "{}", 4096, 256, null, null, "{}", null, "{}",
                        ProviderRepository.ModelProfileStatus.PUBLISHED, now, correlation));
        ProviderRepository.ModelRouteVersion route = providers.createRouteVersion(
                new ProviderRepository.NewModelRouteVersion(UUID.randomUUID(), requestedSpace, "chat", 1,
                        ProviderRepository.RoutePurpose.CHAT, ProviderRepository.EgressPolicy.LOCAL_ONLY, false,
                        ProviderRepository.SelectionPolicy.SINGLE, "{}", ProviderRepository.ModelRouteStatus.PUBLISHED, now, correlation));
        providers.addRouteCandidate(new ProviderRepository.NewRouteCandidate(UUID.randomUUID(), requestedSpace, route.id(),
                1, profile.id(), now, correlation));
        PromptRepository.PromptVersion prompt = prompts.createVersion(new PromptRepository.NewPromptVersion(
                UUID.randomUUID(), requestedSpace, "chat", 1, "You are a safe assistant.", "{}", "{}", "test",
                user, PromptRepository.PromptStatus.PUBLISHED, now, correlation));
        return new Setup(provider, profile, route, prompt);
    }

    private <T> T withPrincipal(java.util.concurrent.Callable<T> operation) throws Exception {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new TestingAuthenticationToken(principal, null));
        SecurityContextHolder.setContext(context);
        try {
            return operation.call();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private RequestPostProcessor auth() {
        return request -> {
            request.setUserPrincipal(new TestingAuthenticationToken(principal, null));
            return request;
        };
    }

    private record Setup(ProviderRepository.ProviderConnection provider, ProviderRepository.ModelProfileVersion profile,
                         ProviderRepository.ModelRouteVersion route, PromptRepository.PromptVersion prompt) {
    }
}
