package com.ragforge.server.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpaceBindingApiIntegrationTest {
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

    @AfterAll
    static void stopContainers() {
        try {
            VALKEY.stop();
        } finally {
            POSTGRES.stop();
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
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE space_binding_versions, idempotency_records, outbox_events, audit_events, "
                + "space_memberships, knowledge_spaces, sessions, users CASCADE");
    }

    @Test
    void memberCanGetTheCurrentBinding() throws Exception {
        Fixture fixture = fixture("member-get");
        putLocalBinding(fixture, fixture.admin(), 1, "member-get-put")
                .andExpect(status().isOk());

        Login editor = registerAndLogin("member-get-editor");
        addMember(fixture.admin(), fixture.spaceId(), editor.userId(), "EDITOR", "member-get-editor-membership");

        mvc.perform(get("/api/v1/spaces/{spaceId}/space-bindings", fixture.spaceId())
                        .cookie(editor.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spaceId").value(fixture.spaceId().toString()))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.chatRouteId").value(fixture.chatRouteId().toString()))
                .andExpect(jsonPath("$.embeddingRouteId").value(fixture.embeddingRouteId().toString()))
                .andExpect(jsonPath("$.rerankRouteId").value(fixture.rerankRouteId().toString()))
                .andExpect(jsonPath("$.promptVersionId").value(fixture.promptVersionId().toString()))
                .andExpect(jsonPath("$.cloudEgressEnabled").value(false));
    }

    @Test
    void viewerCannotPutBinding() throws Exception {
        Fixture fixture = fixture("viewer-put");
        Login viewer = registerAndLogin("viewer-put-user");
        addMember(fixture.admin(), fixture.spaceId(), viewer.userId(), "VIEWER", "viewer-put-membership");

        putLocalBinding(fixture, viewer, 1, "viewer-put-attempt")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SPACE_EDITOR_REQUIRED"));
    }

    @Test
    void crossSpaceGetAndPutAreNotFound() throws Exception {
        Fixture fixtureA = fixture("cross-space-a");
        putLocalBinding(fixtureA, fixtureA.admin(), 1, "cross-space-a-put")
                .andExpect(status().isOk());
        Login ownerB = registerAndLogin("cross-space-b-owner");
        UUID spaceB = createSpace(ownerB, "Cross Space B");

        mvc.perform(get("/api/v1/spaces/{spaceId}/space-bindings", fixtureA.spaceId())
                        .cookie(ownerB.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SPACE_NOT_FOUND"));

        mvc.perform(put("/api/v1/spaces/{spaceId}/space-bindings", fixtureA.spaceId())
                        .cookie(ownerB.cookie())
                        .header("X-CSRF-Token", ownerB.csrfToken())
                        .header("If-Match", "1")
                        .header("Idempotency-Key", "cross-space-put")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindingJson(fixtureA, 1, false, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SPACE_NOT_FOUND"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_spaces WHERE id = ?",
                Integer.class, spaceB)).isEqualTo(1);
    }

    @Test
    void firstPutRequiresVersionOneAndIfMatchOneAndLocalBindingDefaultsCloudOff() throws Exception {
        Fixture fixture = fixture("first-put");

        putLocalBinding(fixture, fixture.admin(), 1, "first-put-success")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.cloudEgressEnabled").value(false))
                .andExpect(jsonPath("$.cloudEgressAuthorization").doesNotExist());

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM space_binding_versions
                WHERE space_id = ? AND version_no = 1 AND cloud_egress_enabled = FALSE
                  AND cloud_approval_id IS NULL AND cloud_approved_by IS NULL
                  AND cloud_approved_at IS NULL AND cloud_expires_at IS NULL AND cloud_scope IS NULL
                """, Integer.class, fixture.spaceId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'space_binding_versions'
                  AND column_name IN ('secret', 'token', 'api_key', 'credential', 'authorization_secret')
                """, Integer.class)).isZero();
    }

    @Test
    void versionMismatchReturns412WithoutCreatingAnotherVersion() throws Exception {
        Fixture fixture = fixture("version-mismatch");
        putLocalBinding(fixture, fixture.admin(), 1, "version-mismatch-initial")
                .andExpect(status().isOk());

        mvc.perform(put("/api/v1/spaces/{spaceId}/space-bindings", fixture.spaceId())
                        .cookie(fixture.admin().cookie())
                        .header("X-CSRF-Token", fixture.admin().csrfToken())
                        .header("If-Match", "2")
                        .header("Idempotency-Key", "version-mismatch-stale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindingJson(fixture, 2, false, null)))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("SPACE_BINDING_VERSION_MISMATCH"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM space_binding_versions WHERE space_id = ?",
                Integer.class, fixture.spaceId())).isEqualTo(1);
    }

    @Test
    void cloudEnabledWithoutAuthorizationIsRejected() throws Exception {
        Fixture fixture = fixture("cloud-missing-auth");

        putCloudBinding(fixture, fixture.admin(), null, "cloud-missing-auth")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLOUD_EGRESS_AUTHORIZATION_INVALID"));
    }

    @Test
    void cloudEnabledWithExpiredAuthorizationIsRejected() throws Exception {
        Fixture fixture = fixture("cloud-expired-auth");
        Instant approvedAt = Instant.now().minusSeconds(7200);
        CloudAuthorization expired = new CloudAuthorization(
                UUID.randomUUID(), fixture.admin().userId(), approvedAt, approvedAt.plusSeconds(60), "CHAT");

        putCloudBinding(fixture, fixture.admin(), expired, "cloud-expired-auth")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLOUD_EGRESS_AUTHORIZATION_INVALID"));
    }

    @Test
    void cloudEnabledWithWrongScopeIsRejected() throws Exception {
        Fixture fixture = fixture("cloud-wrong-scope");
        Instant approvedAt = Instant.now().minusSeconds(60);
        CloudAuthorization wrongScope = new CloudAuthorization(
                UUID.randomUUID(), fixture.admin().userId(), approvedAt, Instant.now().plusSeconds(3600), "EMBEDDING");

        putCloudBinding(fixture, fixture.admin(), wrongScope, "cloud-wrong-scope")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLOUD_EGRESS_AUTHORIZATION_INVALID"));
    }

    private MvcResultActions putLocalBinding(Fixture fixture, Login actor, int version, String key)
            throws Exception {
        return new MvcResultActions(mvc.perform(put("/api/v1/spaces/{spaceId}/space-bindings", fixture.spaceId())
                .cookie(actor.cookie())
                .header("X-CSRF-Token", actor.csrfToken())
                .header("If-Match", "1")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bindingJson(fixture, version, false, null))));
    }

    private MvcResultActions putCloudBinding(Fixture fixture, Login actor,
                                             CloudAuthorization authorization, String key) throws Exception {
        return new MvcResultActions(mvc.perform(put("/api/v1/spaces/{spaceId}/space-bindings", fixture.spaceId())
                .cookie(actor.cookie())
                .header("X-CSRF-Token", actor.csrfToken())
                .header("If-Match", "1")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bindingJson(fixture, 1, true, authorization))));
    }

    private String bindingJson(Fixture fixture, int version, boolean cloudEnabled,
                               CloudAuthorization authorization) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("version", version);
        body.put("chatRouteId", fixture.chatRouteId());
        body.put("embeddingRouteId", fixture.embeddingRouteId());
        body.put("rerankRouteId", fixture.rerankRouteId());
        body.put("promptVersionId", fixture.promptVersionId());
        body.put("cloudEgressEnabled", cloudEnabled);
        if (authorization != null) {
            body.put("cloudEgressAuthorization", Map.of(
                    "approvalId", authorization.approvalId(),
                    "approvedBy", authorization.approvedBy(),
                    "approvedAt", authorization.approvedAt(),
                    "expiresAt", authorization.expiresAt(),
                    "scope", authorization.scope()));
        }
        return objectMapper.writeValueAsString(body);
    }

    private Fixture fixture(String label) throws Exception {
        Login admin = registerAndLogin(label + "-admin");
        jdbc.update("UPDATE users SET platform_role = 'PLATFORM_ADMIN' WHERE id = ?", admin.userId());
        UUID spaceId = createSpace(admin, label + " space");
        UUID connectionId = createConnection(admin, spaceId, label);
        UUID chatProfile = createProfile(admin, spaceId, connectionId, "CHAT", label + " chat");
        UUID embeddingProfile = createProfile(admin, spaceId, connectionId, "EMBEDDING", label + " embedding");
        UUID rerankProfile = createProfile(admin, spaceId, connectionId, "RERANK", label + " rerank");
        UUID chatRoute = createRoute(admin, spaceId, "CHAT", chatProfile, label + " chat route");
        UUID embeddingRoute = createRoute(admin, spaceId, "EMBEDDING", embeddingProfile, label + " embedding route");
        UUID rerankRoute = createRoute(admin, spaceId, "RERANK", rerankProfile, label + " rerank route");
        UUID promptTemplate = createPromptTemplate(admin, spaceId, label + " prompt");
        UUID promptVersion = createPromptVersion(admin, spaceId, promptTemplate, label);
        publishPromptVersion(admin, spaceId, promptTemplate);
        return new Fixture(admin, spaceId, chatRoute, embeddingRoute, rerankRoute, promptVersion);
    }

    private UUID createConnection(Login login, UUID spaceId, String label) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/spaces/{spaceId}/provider-connections", spaceId)
                        .cookie(login.cookie())
                        .header("X-CSRF-Token", login.csrfToken())
                        .header("Idempotency-Key", label + "-connection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "displayName", label + " connection",
                                "providerType", "OLLAMA",
                                "egressClass", "LOCAL",
                                "endpoint", "http://ollama.test:11434",
                                "credentialRef", "vault:" + label,
                                "status", "ACTIVE"))))
                .andExpect(status().isCreated())
                .andReturn();
        return uuid(result, "providerConnectionId");
    }

    private UUID createProfile(Login login, UUID spaceId, UUID connectionId, String purpose, String label)
            throws Exception {
        jdbc.update("""
                INSERT INTO provider_connection_test_runs
                    (id, space_id, provider_connection_id, model_name, purpose, outcome,
                     verified_capabilities, embedding_dimension, error_class, retryable, duration_ms,
                     tested_by, tested_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, 'SUCCEEDED', CAST(? AS jsonb), ?, NULL, FALSE, 1, ?, NOW(), ?)
                """, UUID.randomUUID(), spaceId, connectionId, label, purpose,
                objectMapper.writeValueAsString(List.of(purpose)), "EMBEDDING".equals(purpose) ? 3 : null,
                login.userId(), UUID.randomUUID());
        Map<String, Object> profileBody = new java.util.HashMap<>(Map.of(
                "providerConnectionId", connectionId,
                "purpose", purpose,
                "modelName", label,
                "capabilities", List.of(purpose),
                "contextWindow", 8192,
                "maxOutputTokens", 1024,
                "usageReporting", "LOCAL_ESTIMATE",
                "status", "PUBLISHED"));
        if ("EMBEDDING".equals(purpose)) {
            profileBody.put("embeddingDimension", 3);
        }
        MvcResult result = mvc.perform(post("/api/v1/spaces/{spaceId}/model-profiles", spaceId)
                        .cookie(login.cookie())
                        .header("X-CSRF-Token", login.csrfToken())
                        .header("Idempotency-Key", label.replace(' ', '-') + "-profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(profileBody)))
                .andExpect(status().isCreated())
                .andReturn();
        return uuid(result, "modelProfileId");
    }

    private UUID createRoute(Login login, UUID spaceId, String purpose, UUID profileId, String label)
            throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/spaces/{spaceId}/model-routes", spaceId)
                        .cookie(login.cookie())
                        .header("X-CSRF-Token", login.csrfToken())
                        .header("Idempotency-Key", label.replace(' ', '-') + "-route")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "purpose", purpose,
                                "egressClass", "LOCAL",
                                "failoverPolicy", "NONE",
                                "candidates", List.of(Map.of(
                                        "modelProfileId", profileId,
                                        "priority", 1,
                                        "egressClass", "LOCAL")),
                                "status", "ACTIVE"))))
                .andExpect(status().isCreated())
                .andReturn();
        return uuid(result, "modelRouteId");
    }

    private UUID createPromptTemplate(Login login, UUID spaceId, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/spaces/{spaceId}/prompt-templates", spaceId)
                        .cookie(login.cookie())
                        .header("X-CSRF-Token", login.csrfToken())
                        .header("Idempotency-Key", name.replace(' ', '-') + "-template")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name, "purpose", "CHAT"))))
                .andExpect(status().isCreated())
                .andReturn();
        return uuid(result, "promptTemplateId");
    }

    private UUID createPromptVersion(Login login, UUID spaceId, UUID templateId, String label) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/spaces/{spaceId}/prompt-templates/{templateId}/versions",
                        spaceId, templateId)
                        .cookie(login.cookie())
                        .header("X-CSRF-Token", login.csrfToken())
                        .header("Idempotency-Key", label + "-prompt-version")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "messages", List.of(Map.of("role", "SYSTEM", "content", "Answer with evidence.")),
                                "variableSchema", Map.of(),
                                "outputContract", Map.of(),
                                "changeDescription", "Space binding integration test"))))
                .andExpect(status().isCreated())
                .andReturn();
        return uuid(result, "promptVersionId");
    }

    private void publishPromptVersion(Login login, UUID spaceId, UUID templateId) throws Exception {
        mvc.perform(post("/api/v1/spaces/{spaceId}/prompt-templates/{templateId}/versions/1/publish",
                        spaceId, templateId)
                        .cookie(login.cookie())
                        .header("X-CSRF-Token", login.csrfToken())
                        .header("Idempotency-Key", templateId + "-publish")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    private UUID createSpace(Login login, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/spaces")
                        .cookie(login.cookie())
                        .header("X-CSRF-Token", login.csrfToken())
                        .header("Idempotency-Key", name.replace(' ', '-') + "-space")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn();
        return uuid(result, "spaceId");
    }

    private void addMember(Login owner, UUID spaceId, UUID userId, String role, String key) throws Exception {
        mvc.perform(put("/api/v1/spaces/{spaceId}/members/{userId}", spaceId, userId)
                        .cookie(owner.cookie())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("role", role))))
                .andExpect(status().isOk());
    }

    private Login registerAndLogin(String label) throws Exception {
        String email = label + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        mvc.perform(post("/api/v1/auth/register")
                        .header("Idempotency-Key", label + "-register-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", "correct horse battery",
                                "displayName", label))))
                .andExpect(status().isCreated());
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .header("Idempotency-Key", label + "-login-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", "correct horse battery"))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Login(result.getResponse().getCookie("RAGFORGE_SESSION"),
                result.getResponse().getHeader("X-CSRF-Token"),
                UUID.fromString(body.get("user").get("userId").asText()));
    }

    private UUID uuid(MvcResult result, String field) throws Exception {
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get(field).asText());
    }

    private record Fixture(Login admin, UUID spaceId, UUID chatRouteId, UUID embeddingRouteId,
                           UUID rerankRouteId, UUID promptVersionId) {
    }

    private record Login(Cookie cookie, String csrfToken, UUID userId) {
    }

    private record CloudAuthorization(UUID approvalId, UUID approvedBy, Instant approvedAt,
                                      Instant expiresAt, String scope) {
    }

    private record MvcResultActions(org.springframework.test.web.servlet.ResultActions actions) {
        MvcResultActions andExpect(org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
            actions.andExpect(matcher);
            return this;
        }
    }
}
