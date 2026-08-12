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
                .andExpect(jsonPath("$.conversationId").value(conversation.toString()))
                .andExpect(jsonPath("$.inputHash").exists())
                .andExpect(jsonPath("$.outputHash").exists())
                .andExpect(jsonPath("$.message").doesNotExist())
                .andReturn());
        UUID runId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        withPrincipal(() -> mvc.perform(get("/api/v1/spaces/{space}/runs/{run}", space, runId).with(auth())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        withPrincipal(() -> mvc.perform(get("/api/v1/spaces/{space}/runs/{run}/steps", space, runId).with(auth())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("SUCCEEDED"));

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
                .andExpect(jsonPath("$.errorClass").value("INVALID_RESPONSE"))
                .andExpect(jsonPath("$.errorCode").value("invalid_response"))
                .andExpect(jsonPath("$.message").doesNotExist()));

        withPrincipal(() -> mvc.perform(get("/api/v1/spaces/{space}/runs/{run}", otherSpace, UUID.randomUUID()).with(auth())))
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
