package com.ragforge.server.provider.modelprompt;

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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelProfileRouteApiIntegrationTest {
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
        jdbc.execute("TRUNCATE idempotency_records, outbox_events, audit_events, space_memberships, "
                + "knowledge_spaces, sessions, users CASCADE");
    }

    @Test
    void profileRouteListAndEgressGateAreSpaceScoped() throws Exception {
        register("model-admin@example.test", "correct horse battery", "Model Admin");
        Login admin = login("model-admin@example.test", "correct horse battery");
        UUID spaceA = createSpace(admin, "Model Space A");
        UUID localConnection = createConnection(admin, spaceA, "LOCAL", "model-local");
        UUID localProfile = createProfile(admin, spaceA, localConnection, "PUBLISHED", "profile-local");
        UUID secondLocalProfile = createProfile(admin, spaceA, localConnection, "PUBLISHED", "profile-local-second");

        MvcResult route = mvc.perform(post("/api/v1/spaces/{spaceId}/model-routes", spaceA)
                        .cookie(admin.cookie)
                        .header("X-CSRF-Token", admin.csrfToken)
                        .header("Idempotency-Key", "route-local")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "purpose", "CHAT", "egressClass", "LOCAL", "failoverPolicy", "NONE",
                                "candidates", List.of(Map.of("modelProfileId", localProfile,
                                        "priority", 1, "egressClass", "LOCAL")),
                                "status", "ACTIVE"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.candidates", hasSize(1)))
                .andExpect(jsonPath("$.candidates[0].egressClass").value("LOCAL"))
                .andReturn();
        UUID routeId = UUID.fromString(objectMapper.readTree(route.getResponse().getContentAsString())
                .get("modelRouteId").asText());

        mvc.perform(post("/api/v1/spaces/{spaceId}/model-routes", spaceA)
                        .cookie(admin.cookie)
                        .header("X-CSRF-Token", admin.csrfToken)
                        .header("Idempotency-Key", "route-local-same-egress-failover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "purpose", "CHAT", "egressClass", "LOCAL", "failoverPolicy", "SAME_EGRESS_ONLY",
                                "candidates", List.of(
                                        Map.of("modelProfileId", localProfile, "priority", 1, "egressClass", "LOCAL"),
                                        Map.of("modelProfileId", secondLocalProfile, "priority", 2, "egressClass", "LOCAL")),
                                "status", "ACTIVE"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.failoverPolicy").value("SAME_EGRESS_ONLY"))
                .andExpect(jsonPath("$.candidates", hasSize(2)));

        mvc.perform(get("/api/v1/spaces/{spaceId}/model-profiles", spaceA).cookie(admin.cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[*].modelProfileId", hasItem(localProfile.toString())))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
        mvc.perform(get("/api/v1/spaces/{spaceId}/model-routes", spaceA).cookie(admin.cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].modelRouteId", hasItem(routeId.toString())));

        UUID cloudConnection = createConnection(admin, spaceA, "CLOUD", "model-cloud");
        UUID cloudProfile = createProfile(admin, spaceA, cloudConnection, "PUBLISHED", "profile-cloud");
        mvc.perform(post("/api/v1/spaces/{spaceId}/model-routes", spaceA)
                        .cookie(admin.cookie)
                        .header("X-CSRF-Token", admin.csrfToken)
                        .header("Idempotency-Key", "route-local-cloud-rejected")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "purpose", "CHAT", "egressClass", "LOCAL", "failoverPolicy", "NONE",
                                "candidates", List.of(Map.of("modelProfileId", cloudProfile,
                                        "priority", 1, "egressClass", "CLOUD")),
                                "status", "ACTIVE"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        UUID draftProfile = createProfile(admin, spaceA, localConnection, "DRAFT", "profile-draft");
        mvc.perform(post("/api/v1/spaces/{spaceId}/model-routes", spaceA)
                        .cookie(admin.cookie)
                        .header("X-CSRF-Token", admin.csrfToken)
                        .header("Idempotency-Key", "route-published-draft-candidate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "purpose", "CHAT", "egressClass", "LOCAL", "failoverPolicy", "NONE",
                                "candidates", List.of(Map.of("modelProfileId", draftProfile,
                                        "priority", 1, "egressClass", "LOCAL")),
                                "status", "ACTIVE"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void viewerCannotWriteAndCrossSpaceProfileIsNotFound() throws Exception {
        register("model-owner@example.test", "correct horse battery", "Owner");
        Login owner = login("model-owner@example.test", "correct horse battery");
        UUID spaceA = createSpace(owner, "Owner Space");
        UUID connection = createConnection(owner, spaceA, "LOCAL", "space-a-connection");
        UUID profile = createProfile(owner, spaceA, connection, "PUBLISHED", "space-a-profile");

        register("model-viewer@example.test", "correct horse battery", "Viewer");
        Login viewer = login("model-viewer@example.test", "correct horse battery");
        UUID viewerId = userId("model-viewer@example.test");
        mvc.perform(put("/api/v1/spaces/{spaceId}/members/{userId}", spaceA, viewerId)
                        .cookie(owner.cookie)
                        .header("X-CSRF-Token", owner.csrfToken)
                        .header("Idempotency-Key", "model-viewer-membership")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/spaces/{spaceId}/model-profiles", spaceA)
                        .cookie(viewer.cookie)
                        .header("X-CSRF-Token", viewer.csrfToken)
                        .header("Idempotency-Key", "viewer-profile-write")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileRequest(connection, "viewer-attempt")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SPACE_EDITOR_REQUIRED"));

        register("model-other@example.test", "correct horse battery", "Other");
        Login other = login("model-other@example.test", "correct horse battery");
        UUID spaceB = createSpace(other, "Other Space");
        mvc.perform(get("/api/v1/spaces/{spaceId}/model-profiles", spaceB).cookie(other.cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(post("/api/v1/spaces/{spaceId}/model-routes", spaceB)
                        .cookie(other.cookie)
                        .header("X-CSRF-Token", other.csrfToken)
                        .header("Idempotency-Key", "cross-space-route")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "purpose", "CHAT", "egressClass", "LOCAL", "failoverPolicy", "NONE",
                                "candidates", List.of(Map.of("modelProfileId", profile,
                                        "priority", 1, "egressClass", "LOCAL")),
                                "status", "ACTIVE"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MODEL_PROFILE_NOT_FOUND"));
    }

    private UUID createConnection(Login login, UUID spaceId, String egressClass, String key) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/spaces/{spaceId}/provider-connections", spaceId)
                        .cookie(login.cookie)
                        .header("X-CSRF-Token", login.csrfToken)
                        .header("Idempotency-Key", key + "-connection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "displayName", key,
                                "providerType", "OLLAMA",
                                "egressClass", egressClass,
                                "endpoint", "CLOUD".equals(egressClass) ? "https://cloud.test/v1"
                                        : "http://ollama.test:11434",
                                "credentialRef", "vault:" + key,
                                "status", "ACTIVE"))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("providerConnectionId").asText());
    }

    private UUID createProfile(Login login, UUID spaceId, UUID connectionId, String status, String key)
            throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/spaces/{spaceId}/model-profiles", spaceId)
                        .cookie(login.cookie)
                        .header("X-CSRF-Token", login.csrfToken)
                        .header("Idempotency-Key", key + "-profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileRequest(connectionId, key, status)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("modelProfileId").asText());
    }

    private String profileRequest(UUID connectionId, String modelName) throws Exception {
        return profileRequest(connectionId, modelName, "PUBLISHED");
    }

    private String profileRequest(UUID connectionId, String modelName, String status) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "providerConnectionId", connectionId,
                "purpose", "CHAT",
                "modelName", modelName,
                "capabilities", List.of("CHAT", "STREAMING"),
                "contextWindow", 8192,
                "maxOutputTokens", 1024,
                "usageReporting", "LOCAL_ESTIMATE",
                "status", status));
    }

    private UUID createSpace(Login login, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/spaces")
                        .cookie(login.cookie)
                        .header("X-CSRF-Token", login.csrfToken)
                        .header("Idempotency-Key", "space-" + name.replace(' ', '-'))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("spaceId").asText());
    }

    private void register(String email, String password, String displayName) throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .header("Idempotency-Key", "register-" + email.replace('@', '-'))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email, "password", password, "displayName", displayName))))
                .andExpect(status().isCreated());
    }

    private Login login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .header("Idempotency-Key", "login-" + email.replace('@', '-'))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Login(result.getResponse().getCookie("RAGFORGE_SESSION"),
                result.getResponse().getHeader("X-CSRF-Token"),
                UUID.fromString(body.get("user").get("userId").asText()));
    }

    private UUID userId(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private record Login(Cookie cookie, String csrfToken, UUID userId) {
    }
}
