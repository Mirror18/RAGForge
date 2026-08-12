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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProviderConnectionApiIntegrationTest {
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

    @Autowired
    ProviderRepository providers;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE idempotency_records, outbox_events, audit_events, space_memberships, "
                + "knowledge_spaces, sessions, users CASCADE");
    }

    @Test
    void adminCreatesLocalConnectionAndResponseContainsNoCredentialMaterial() throws Exception {
        register("provider-admin@example.test", "correct horse battery", "Provider Admin");
        Login admin = login("provider-admin@example.test", "correct horse battery");
        UUID spaceId = createSpace(admin, "Provider Space");

        String credentialRef = "vault:provider-secret";
        MvcResult result = mvc.perform(post("/api/v1/spaces/{spaceId}/provider-connections", spaceId)
                        .cookie(admin.cookie)
                        .header("X-CSRF-Token", admin.csrfToken)
                        .header("Idempotency-Key", "provider-create-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "displayName", "Local Ollama",
                                "providerType", "OLLAMA",
                                "endpoint", "http://ollama.test:11434",
                                "credentialRef", credentialRef,
                                "status", "ACTIVE"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.providerConnectionId").exists())
                .andExpect(jsonPath("$.spaceId").value(spaceId.toString()))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.providerType").value("OLLAMA"))
                .andExpect(jsonPath("$.egressClass").value("LOCAL"))
                .andExpect(jsonPath("$.endpoint").value("http://ollama.test:11434"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.credentialRef").doesNotExist())
                .andExpect(jsonPath("$.credentialHash").doesNotExist())
                .andExpect(jsonPath("$.headers").doesNotExist())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).doesNotContain(credentialRef, "credentialHash", "nonSecretHeaders", "headers");
        UUID connectionId = UUID.fromString(objectMapper.readTree(responseBody)
                .get("providerConnectionId").asText());

        assertThat(jdbc.queryForObject("SELECT display_name FROM provider_connections WHERE id = ? AND space_id = ?",
                String.class, connectionId, spaceId)).isEqualTo("Local Ollama");
        assertThat(jdbc.queryForObject("SELECT credential_ref FROM provider_connections WHERE id = ? AND space_id = ?",
                String.class, connectionId, spaceId)).isEqualTo(credentialRef);
        assertThat(jdbc.queryForObject("SELECT egress_policy FROM provider_connections WHERE id = ? AND space_id = ?",
                String.class, connectionId, spaceId)).isEqualTo("LOCAL_ONLY");

        mvc.perform(get("/api/v1/spaces/{spaceId}/provider-connections", spaceId).cookie(admin.cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].providerConnectionId").value(connectionId.toString()))
                .andExpect(jsonPath("$.items[0].credentialRef").doesNotExist())
                .andExpect(jsonPath("$.items[0].credentialHash").doesNotExist())
                .andExpect(jsonPath("$.items[0].headers").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));

        mvc.perform(get("/api/v1/spaces/{spaceId}/provider-connections/{connectionId}", spaceId, connectionId)
                        .cookie(admin.cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerConnectionId").value(connectionId.toString()))
                .andExpect(jsonPath("$.credentialRef").doesNotExist());
    }

    @Test
    void viewerCannotCreateProviderConnection() throws Exception {
        register("viewer-admin@example.test", "correct horse battery", "Admin");
        Login admin = login("viewer-admin@example.test", "correct horse battery");
        UUID spaceId = createSpace(admin, "Viewer Space");

        register("provider-viewer@example.test", "correct horse battery", "Viewer");
        Login viewer = login("provider-viewer@example.test", "correct horse battery");
        UUID viewerId = userId("provider-viewer@example.test");
        mvc.perform(put("/api/v1/spaces/{spaceId}/members/{userId}", spaceId, viewerId)
                        .cookie(admin.cookie)
                        .header("X-CSRF-Token", admin.csrfToken)
                        .header("Idempotency-Key", "viewer-membership")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"));

        mvc.perform(post("/api/v1/spaces/{spaceId}/provider-connections", spaceId)
                        .cookie(viewer.cookie)
                        .header("X-CSRF-Token", viewer.csrfToken)
                        .header("Idempotency-Key", "provider-create-viewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(providerRequest("Viewer attempt")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SPACE_EDITOR_REQUIRED"));
    }

    @Test
    void connectionFromAnotherSpaceIsNotFoundAndDoesNotLeakThroughList() throws Exception {
        register("space-a-admin@example.test", "correct horse battery", "Space A Admin");
        Login adminA = login("space-a-admin@example.test", "correct horse battery");
        UUID spaceA = createSpace(adminA, "Space A");
        MvcResult created = mvc.perform(post("/api/v1/spaces/{spaceId}/provider-connections", spaceA)
                        .cookie(adminA.cookie)
                        .header("X-CSRF-Token", adminA.csrfToken)
                        .header("Idempotency-Key", "provider-create-space-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(providerRequest("Space A Local")))
                .andExpect(status().isCreated())
                .andReturn();
        UUID connectionA = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString())
                .get("providerConnectionId").asText());

        register("space-b-admin@example.test", "correct horse battery", "Space B Admin");
        Login adminB = login("space-b-admin@example.test", "correct horse battery");
        UUID spaceB = createSpace(adminB, "Space B");

        mvc.perform(get("/api/v1/spaces/{spaceId}/provider-connections/{connectionId}", spaceB, connectionA)
                        .cookie(adminB.cookie))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROVIDER_CONNECTION_NOT_FOUND"));
        assertThat(providers.findConnectionInSpace(spaceB, connectionA)).isEmpty();

        mvc.perform(get("/api/v1/spaces/{spaceId}/provider-connections", spaceB).cookie(adminB.cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    private String providerRequest(String displayName) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "displayName", displayName,
                "providerType", "OLLAMA",
                "endpoint", "http://ollama.test:11434",
                "credentialRef", "vault:provider-secret",
                "status", "ACTIVE"));
    }

    private UUID createSpace(Login login, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/spaces")
                        .cookie(login.cookie)
                        .header("X-CSRF-Token", login.csrfToken)
                        .header("Idempotency-Key", "space-create-" + name.replace(' ', '-'))
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
