package com.ragforge.server.prompt.modelprompt;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PromptManagementApiIntegrationTest {
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
    void createPublishAndRepublishPromptUsesV4StateMachine() throws Exception {
        register("prompt-admin@example.test", "correct horse battery", "Prompt Admin");
        Login admin = login("prompt-admin@example.test", "correct horse battery");
        UUID spaceId = createSpace(admin, "Prompt Space");
        UUID templateId = createTemplate(admin, spaceId, "Answer Prompt");

        mvc.perform(get("/api/v1/spaces/{spaceId}/prompt-templates", spaceId).cookie(admin.cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].promptTemplateId").value(templateId.toString()))
                .andExpect(jsonPath("$.items[0].currentVersion").value(org.hamcrest.Matchers.nullValue()));

        MvcResult created = mvc.perform(post("/api/v1/spaces/{spaceId}/prompt-templates/{templateId}/versions",
                        spaceId, templateId)
                        .cookie(admin.cookie)
                        .header("X-CSRF-Token", admin.csrfToken)
                        .header("Idempotency-Key", "prompt-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "messages", List.of(Map.of("role", "SYSTEM", "content", "Answer with evidence.")),
                                "variableSchema", Map.of("question", Map.of("type", "string")),
                                "outputContract", Map.of("type", "object"),
                                "changeDescription", "initial prompt"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.state").value("DRAFT"))
                .andExpect(jsonPath("$.immutableAfterPublish").value(true))
                .andReturn();
        UUID promptVersionId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString())
                .get("promptVersionId").asText());

        mvc.perform(post("/api/v1/spaces/{spaceId}/prompt-templates/{templateId}/versions/{version}/publish",
                        spaceId, templateId, 1)
                        .cookie(admin.cookie)
                        .header("X-CSRF-Token", admin.csrfToken)
                        .header("Idempotency-Key", "prompt-publish")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promptVersionId").value(promptVersionId.toString()))
                .andExpect(jsonPath("$.state").value("PUBLISHED"))
                .andExpect(jsonPath("$.publishedAt").exists());

        mvc.perform(get("/api/v1/spaces/{spaceId}/prompt-templates/{templateId}/versions/{version}",
                        spaceId, templateId, 1).cookie(admin.cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PUBLISHED"))
                .andExpect(jsonPath("$.messages[0].content").value("Answer with evidence."));

        mvc.perform(post("/api/v1/spaces/{spaceId}/prompt-templates/{templateId}/versions/{version}/publish",
                        spaceId, templateId, 1)
                        .cookie(admin.cookie)
                        .header("X-CSRF-Token", admin.csrfToken)
                        .header("Idempotency-Key", "prompt-publish-again")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROMPT_VERSION_STATE_CONFLICT"));

        String stored = jdbc.queryForObject("SELECT template FROM prompt_versions WHERE id = ?",
                String.class, promptVersionId);
        org.assertj.core.api.Assertions.assertThat(stored).contains("Answer with evidence.");
    }

    @Test
    void promptVersionFromAnotherSpaceIsNotFound() throws Exception {
        register("prompt-owner@example.test", "correct horse battery", "Owner");
        Login owner = login("prompt-owner@example.test", "correct horse battery");
        UUID spaceA = createSpace(owner, "Prompt A");
        UUID templateId = createTemplate(owner, spaceA, "Private Prompt");
        createVersion(owner, spaceA, templateId, "private prompt");

        register("prompt-other@example.test", "correct horse battery", "Other");
        Login other = login("prompt-other@example.test", "correct horse battery");
        UUID spaceB = createSpace(other, "Prompt B");
        mvc.perform(get("/api/v1/spaces/{spaceId}/prompt-templates/{templateId}/versions/1",
                        spaceB, templateId).cookie(other.cookie))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROMPT_VERSION_NOT_FOUND"));
    }

    @Test
    void viewerCannotCreatePromptTemplate() throws Exception {
        register("prompt-viewer-owner@example.test", "correct horse battery", "Owner");
        Login owner = login("prompt-viewer-owner@example.test", "correct horse battery");
        UUID spaceId = createSpace(owner, "Prompt Viewer Space");
        register("prompt-viewer@example.test", "correct horse battery", "Viewer");
        Login viewer = login("prompt-viewer@example.test", "correct horse battery");
        UUID viewerId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class,
                "prompt-viewer@example.test");
        mvc.perform(put("/api/v1/spaces/{spaceId}/members/{userId}", spaceId, viewerId)
                        .cookie(owner.cookie)
                        .header("X-CSRF-Token", owner.csrfToken)
                        .header("Idempotency-Key", "prompt-viewer-membership")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/spaces/{spaceId}/prompt-templates", spaceId)
                        .cookie(viewer.cookie)
                        .header("X-CSRF-Token", viewer.csrfToken)
                        .header("Idempotency-Key", "prompt-viewer-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Viewer attempt\",\"purpose\":\"CHAT\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SPACE_EDITOR_REQUIRED"));
    }

    private void createVersion(Login login, UUID spaceId, UUID templateId, String content) throws Exception {
        mvc.perform(post("/api/v1/spaces/{spaceId}/prompt-templates/{templateId}/versions", spaceId, templateId)
                        .cookie(login.cookie)
                        .header("X-CSRF-Token", login.csrfToken)
                        .header("Idempotency-Key", "prompt-create-" + content.replace(' ', '-'))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "messages", List.of(Map.of("role", "USER", "content", content)),
                                "variableSchema", Map.of(),
                                "outputContract", Map.of(),
                                "changeDescription", "test"))))
                .andExpect(status().isCreated());
    }

    private UUID createTemplate(Login login, UUID spaceId, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/spaces/{spaceId}/prompt-templates", spaceId)
                        .cookie(login.cookie)
                        .header("X-CSRF-Token", login.csrfToken)
                        .header("Idempotency-Key", "template-create-" + name.replace(' ', '-'))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name, "purpose", "CHAT"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentVersion").value(org.hamcrest.Matchers.nullValue()))
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("promptTemplateId").asText());
    }

    private UUID createSpace(Login login, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/spaces")
                        .cookie(login.cookie)
                        .header("X-CSRF-Token", login.csrfToken)
                        .header("Idempotency-Key", "prompt-space-" + name.replace(' ', '-'))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("spaceId").asText());
    }

    private void register(String email, String password, String displayName) throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .header("Idempotency-Key", "prompt-register-" + email.replace('@', '-'))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email, "password", password, "displayName", displayName))))
                .andExpect(status().isCreated());
    }

    private Login login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .header("Idempotency-Key", "prompt-login-" + email.replace('@', '-'))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Login(result.getResponse().getCookie("RAGFORGE_SESSION"),
                result.getResponse().getHeader("X-CSRF-Token"),
                UUID.fromString(body.get("user").get("userId").asText()));
    }

    private record Login(Cookie cookie, String csrfToken, UUID userId) {
    }
}
