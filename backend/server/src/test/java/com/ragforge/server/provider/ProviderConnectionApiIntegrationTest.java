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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

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
        promoteToPlatformAdmin("provider-admin@example.test");
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
    void regularSpaceAdminAndViewerCannotCreateProviderConnection() throws Exception {
        register("viewer-admin@example.test", "correct horse battery", "Admin");
        Login admin = login("viewer-admin@example.test", "correct horse battery");
        UUID spaceId = createSpace(admin, "Viewer Space");

        mvc.perform(post("/api/v1/spaces/{spaceId}/provider-connections", spaceId)
                        .cookie(admin.cookie)
                        .header("X-CSRF-Token", admin.csrfToken)
                        .header("Idempotency-Key", "provider-create-space-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(providerRequest("Space admin attempt")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_ADMIN_REQUIRED"));

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
                .andExpect(jsonPath("$.code").value("PLATFORM_ADMIN_REQUIRED"));
    }

    @Test
    void connectionFromAnotherSpaceIsNotFoundAndDoesNotLeakThroughList() throws Exception {
        register("space-a-admin@example.test", "correct horse battery", "Space A Admin");
        promoteToPlatformAdmin("space-a-admin@example.test");
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

    @Test
    void successfulSyntheticProbePersistsRedactedCapabilitiesAndUnlocksPublishedProfile() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicBoolean failProbe = new AtomicBoolean(false);
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (failProbe.get()) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            byte[] response = "{\"model\":\"probe-model\",\"message\":{\"content\":\"OK\"},\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":2,\"eval_count\":1}\n"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            register("probe-admin@example.test", "correct horse battery", "Probe Admin");
            promoteToPlatformAdmin("probe-admin@example.test");
            Login admin = login("probe-admin@example.test", "correct horse battery");
            UUID spaceId = createSpace(admin, "Probe Space");
            MvcResult created = mvc.perform(post("/api/v1/spaces/{spaceId}/provider-connections", spaceId)
                            .cookie(admin.cookie).header("X-CSRF-Token", admin.csrfToken)
                            .header("Idempotency-Key", "probe-provider")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "displayName", "Probe Ollama", "providerType", "OLLAMA",
                                    "egressClass", "LOCAL",
                                    "endpoint", "http://127.0.0.1:" + server.getAddress().getPort(),
                                    "credentialRef", "local:no-auth", "status", "ACTIVE"))))
                    .andExpect(status().isCreated()).andReturn();
            UUID connectionId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString())
                    .get("providerConnectionId").asText());

            String profile = objectMapper.writeValueAsString(Map.of(
                    "providerConnectionId", connectionId, "purpose", "CHAT", "modelName", "probe-model",
                    "capabilities", java.util.List.of("CHAT", "STREAMING", "USAGE_REPORTING"), "contextWindow", 8192,
                    "maxOutputTokens", 128, "usageReporting", "PROVIDER_REPORTED", "status", "PUBLISHED"));
            mvc.perform(post("/api/v1/spaces/{spaceId}/model-profiles", spaceId)
                            .cookie(admin.cookie).header("X-CSRF-Token", admin.csrfToken)
                            .header("Idempotency-Key", "probe-profile-before-test")
                            .contentType(MediaType.APPLICATION_JSON).content(profile))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

            mvc.perform(post("/api/v1/spaces/{spaceId}/provider-connections/{connectionId}/test",
                            spaceId, connectionId)
                            .cookie(admin.cookie).header("X-CSRF-Token", admin.csrfToken)
                            .header("Idempotency-Key", "probe-provider-test")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"modelName\":\"probe-model\",\"purpose\":\"CHAT\",\"timeoutSeconds\":5,\"allowCloudProbe\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("SUCCEEDED"))
                    .andExpect(jsonPath("$.verifiedCapabilities[0]").value("CHAT"))
                    .andExpect(jsonPath("$.verifiedCapabilities[1]").value("STREAMING"))
                    .andExpect(jsonPath("$.verifiedCapabilities[2]").value("USAGE_REPORTING"))
                    .andExpect(jsonPath("$.errorClass").value(org.hamcrest.Matchers.nullValue()));

            failProbe.set(true);
            mvc.perform(post("/api/v1/spaces/{spaceId}/provider-connections/{connectionId}/test",
                            spaceId, connectionId)
                            .cookie(admin.cookie).header("X-CSRF-Token", admin.csrfToken)
                            .header("Idempotency-Key", "probe-provider-test-failed")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"modelName\":\"probe-model\",\"purpose\":\"CHAT\",\"timeoutSeconds\":5,\"allowCloudProbe\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("FAILED"))
                    .andExpect(jsonPath("$.errorClass").value("UNAVAILABLE"));
            mvc.perform(post("/api/v1/spaces/{spaceId}/model-profiles", spaceId)
                            .cookie(admin.cookie).header("X-CSRF-Token", admin.csrfToken)
                            .header("Idempotency-Key", "probe-profile-after-failed-retest")
                            .contentType(MediaType.APPLICATION_JSON).content(profile))
                    .andExpect(status().isUnprocessableEntity());

            failProbe.set(false);
            mvc.perform(post("/api/v1/spaces/{spaceId}/provider-connections/{connectionId}/test",
                            spaceId, connectionId)
                            .cookie(admin.cookie).header("X-CSRF-Token", admin.csrfToken)
                            .header("Idempotency-Key", "probe-provider-test-recovered")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"modelName\":\"probe-model\",\"purpose\":\"CHAT\",\"timeoutSeconds\":5,\"allowCloudProbe\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("SUCCEEDED"));

            mvc.perform(post("/api/v1/spaces/{spaceId}/model-profiles", spaceId)
                            .cookie(admin.cookie).header("X-CSRF-Token", admin.csrfToken)
                            .header("Idempotency-Key", "probe-profile-after-test")
                            .contentType(MediaType.APPLICATION_JSON).content(profile))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("PUBLISHED"))
                    .andExpect(jsonPath("$.verifiedCapabilities[0]").value("CHAT"));

            assertThat(receivedBody.get()).contains("Reply with OK.").doesNotContain("example.test");
            String stored = jdbc.queryForObject("""
                    SELECT verified_capabilities::text FROM provider_connection_test_runs
                    WHERE outcome = 'SUCCEEDED' ORDER BY tested_at DESC LIMIT 1
                    """,
                    String.class);
            assertThat(stored).contains("CHAT", "STREAMING", "USAGE_REPORTING")
                    .doesNotContain("OK", "probe-provider-test");

            MvcResult cloudCreated = mvc.perform(post("/api/v1/spaces/{spaceId}/provider-connections", spaceId)
                            .cookie(admin.cookie).header("X-CSRF-Token", admin.csrfToken)
                            .header("Idempotency-Key", "cloud-probe-provider")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "displayName", "Cloud Probe", "providerType", "OPENAI_COMPATIBLE",
                                    "egressClass", "CLOUD", "endpoint", "https://provider.invalid",
                                    "credentialRef", "vault:cloud-probe", "status", "ACTIVE"))))
                    .andExpect(status().isCreated()).andReturn();
            UUID cloudConnectionId = UUID.fromString(objectMapper.readTree(
                    cloudCreated.getResponse().getContentAsString()).get("providerConnectionId").asText());
            mvc.perform(post("/api/v1/spaces/{spaceId}/provider-connections/{connectionId}/test",
                            spaceId, cloudConnectionId)
                            .cookie(admin.cookie).header("X-CSRF-Token", admin.csrfToken)
                            .header("Idempotency-Key", "cloud-probe-without-approval")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"modelName\":\"cloud-model\",\"purpose\":\"CHAT\",\"timeoutSeconds\":5,\"allowCloudProbe\":false}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CLOUD_PROBE_APPROVAL_REQUIRED"));
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM provider_connection_test_runs WHERE provider_connection_id = ?
                    """, Integer.class, cloudConnectionId)).isZero();

            Map<String, String> unsafeLocalEndpoints = Map.of(
                    "public", "http://1.1.1.1",
                    "metadata", "http://169.254.169.254");
            for (Map.Entry<String, String> unsafeEndpoint : unsafeLocalEndpoints.entrySet()) {
                MvcResult unsafeLocalCreated = mvc.perform(post(
                                "/api/v1/spaces/{spaceId}/provider-connections", spaceId)
                                .cookie(admin.cookie).header("X-CSRF-Token", admin.csrfToken)
                                .header("Idempotency-Key", "unsafe-local-provider-" + unsafeEndpoint.getKey())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "displayName", "Unsafe Local", "providerType", "OLLAMA",
                                        "egressClass", "LOCAL", "endpoint", unsafeEndpoint.getValue(),
                                        "credentialRef", "local:no-auth", "status", "ACTIVE"))))
                        .andExpect(status().isCreated()).andReturn();
                UUID unsafeLocalId = UUID.fromString(objectMapper.readTree(
                        unsafeLocalCreated.getResponse().getContentAsString()).get("providerConnectionId").asText());
                mvc.perform(post("/api/v1/spaces/{spaceId}/provider-connections/{connectionId}/test",
                                spaceId, unsafeLocalId)
                                .cookie(admin.cookie).header("X-CSRF-Token", admin.csrfToken)
                                .header("Idempotency-Key", "unsafe-local-probe-" + unsafeEndpoint.getKey())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"modelName\":\"unsafe\",\"purpose\":\"CHAT\",\"timeoutSeconds\":5,\"allowCloudProbe\":false}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.outcome").value("FAILED"))
                        .andExpect(jsonPath("$.errorClass").value("SPACE_EGRESS_DENIED"));
            }
        } finally {
            server.stop(0);
        }
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

    private void promoteToPlatformAdmin(String email) {
        jdbc.update("UPDATE users SET platform_role = 'PLATFORM_ADMIN' WHERE email = ?", email);
    }

    private record Login(Cookie cookie, String csrfToken, UUID userId) {
    }
}
