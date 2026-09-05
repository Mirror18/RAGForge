package com.ragforge.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;

import jakarta.servlet.http.Cookie;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServerIntegrationTest {
    private static final String BOOTSTRAP_TOKEN = "test-bootstrap-secret-with-at-least-32-characters";
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
        registry.add("ragforge.bootstrap.admin.token", () -> BOOTSTRAP_TOKEN);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    org.springframework.data.redis.core.StringRedisTemplate redis;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE idempotency_records, outbox_events, audit_events, space_memberships, knowledge_spaces, sessions, users CASCADE");
    }

    @Test
    void healthAndMigrationAreRealPostgresBacked() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        Set<String> tables = Set.copyOf(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN
                ('users', 'sessions', 'idempotency_records', 'knowledge_spaces', 'space_memberships', 'audit_events', 'outbox_events')
                """, String.class));
        org.assertj.core.api.Assertions.assertThat(tables).containsExactlyInAnyOrder(
                "users", "sessions", "idempotency_records", "knowledge_spaces", "space_memberships",
                "audit_events", "outbox_events");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_name = 'users' AND column_name = 'id'",
                String.class)).isEqualTo("uuid");
    }

    @Test
    void firstPlatformAdminBootstrapIsSecretGuardedAuditedAndOneTime() throws Exception {
        mvc.perform(get("/api/v1/bootstrap/platform-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.required").value(true))
                .andExpect(jsonPath("$.available").value(true));

        String body = "{\"email\":\"first-admin@example.test\",\"password\":\"new correct horse battery\",\"displayName\":\"First Admin\"}";
        mvc.perform(post("/api/v1/bootstrap/platform-admin")
                        .header("Idempotency-Key", "bootstrap-invalid-token")
                        .header("X-RAGForge-Bootstrap-Token", "invalid-bootstrap-secret-value-000")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BOOTSTRAP_TOKEN_INVALID"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class))
                .isZero();

        mvc.perform(post("/api/v1/bootstrap/platform-admin")
                        .header("Idempotency-Key", "bootstrap-first-admin")
                        .header("X-RAGForge-Bootstrap-Token", BOOTSTRAP_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("first-admin@example.test"))
                .andExpect(jsonPath("$.platformRole").value("PLATFORM_ADMIN"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.mode").value("CREATED"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist());

        mvc.perform(get("/api/v1/bootstrap/platform-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.required").value(false))
                .andExpect(jsonPath("$.available").value(false));
        login("first-admin@example.test", "new correct horse battery", "/sessions");

        mvc.perform(post("/api/v1/bootstrap/platform-admin")
                        .header("Idempotency-Key", "bootstrap-cannot-repeat")
                        .header("X-RAGForge-Bootstrap-Token", BOOTSTRAP_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOTSTRAP_ALREADY_COMPLETED"));

        String auditPayload = jdbc.queryForObject("""
                SELECT payload::text FROM audit_events WHERE event_type = 'platform.admin.bootstrapped.v1'
                """, String.class);
        org.assertj.core.api.Assertions.assertThat(auditPayload)
                .contains("userId", "CREATED")
                .doesNotContain("first-admin@example.test", BOOTSTRAP_TOKEN, "password");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM users WHERE platform_role = 'PLATFORM_ADMIN' AND status = 'ACTIVE'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void bootstrapCanPromoteExistingActiveUserAndReplacesPassword() throws Exception {
        register("promote@example.test", "correct horse battery", "Before Promote");

        mvc.perform(post("/api/v1/bootstrap/platform-admin")
                        .header("Idempotency-Key", "bootstrap-promote-existing")
                        .header("X-RAGForge-Bootstrap-Token", BOOTSTRAP_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"PROMOTE@example.test\",\"password\":\"replacement password 123\",\"displayName\":\"Promoted Admin\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("PROMOTED"))
                .andExpect(jsonPath("$.displayName").value("Promoted Admin"))
                .andExpect(jsonPath("$.platformRole").value("PLATFORM_ADMIN"));

        mvc.perform(post("/api/v1/sessions")
                        .header("Idempotency-Key", "bootstrap-old-password-rejected")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"promote@example.test\",\"password\":\"correct horse battery\"}"))
                .andExpect(status().isUnauthorized());
        login("promote@example.test", "replacement password 123", "/sessions");
    }

    @Test
    void bootstrapRefusesToReactivateDisabledUser() throws Exception {
        register("disabled-bootstrap@example.test", "correct horse battery", "Disabled User");
        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE email = ?", "disabled-bootstrap@example.test");

        mvc.perform(post("/api/v1/bootstrap/platform-admin")
                        .header("Idempotency-Key", "bootstrap-disabled-user")
                        .header("X-RAGForge-Bootstrap-Token", BOOTSTRAP_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"disabled-bootstrap@example.test\",\"password\":\"replacement password 123\",\"displayName\":\"Must Stay Disabled\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOTSTRAP_USER_DISABLED"));

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM users WHERE platform_role = 'PLATFORM_ADMIN' AND status = 'ACTIVE'
                """, Integer.class)).isZero();
    }

    @Test
    void concurrentBootstrapCreatesExactlyOnePlatformAdmin() throws Exception {
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> bootstrapStatus(start, "concurrent-a@example.test",
                    "bootstrap-concurrent-a"));
            Future<Integer> second = executor.submit(() -> bootstrapStatus(start, "concurrent-b@example.test",
                    "bootstrap-concurrent-b"));
            start.countDown();

            org.assertj.core.api.Assertions.assertThat(java.util.List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(201, 409);
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM users WHERE platform_role = 'PLATFORM_ADMIN' AND status = 'ACTIVE'
                    """, Integer.class)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sessionCookieCsrfAndSpaceLifecycleAreEnforced() throws Exception {
        register("alice@example.test", "correct horse battery", "Alice");
        Login login = login("alice@example.test", "correct horse battery", "/auth/login");

        mvc.perform(get("/api/v1/sessions/current").cookie(login.cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("alice@example.test"))
                .andExpect(jsonPath("$.user.userId").value(login.userId.toString()))
                .andExpect(jsonPath("$.session.sessionId").value(login.sessionId.toString()))
                .andExpect(jsonPath("$.session.csrfToken").value(login.csrfToken))
                .andExpect(jsonPath("$.email").doesNotExist());

        mvc.perform(post("/api/v1/spaces").cookie(login.cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Alpha\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CSRF_FAILED"));

        MvcResult created = mvc.perform(post("/api/v1/spaces").cookie(login.cookie)
                        .header("X-CSRF-Token", login.csrfToken)
                        .header("Idempotency-Key", "space-create-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Alpha\",\"description\":\"Alpha space\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Alpha"))
                .andExpect(jsonPath("$.spaceId").exists())
                .andExpect(jsonPath("$.description").value("Alpha space"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.role").value("SPACE_ADMIN"))
                .andExpect(jsonPath("$.updatedAt").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();
        UUID spaceId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString())
                .get("spaceId").asText());

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE space_id = ?", Integer.class, spaceId)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE space_id = ?", Integer.class, spaceId)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_records WHERE principal_scope = ?", Integer.class,
                login.userId.toString())).isGreaterThanOrEqualTo(1);

        mvc.perform(get("/api/v1/spaces").cookie(login.cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].spaceId").value(spaceId.toString()))
                .andExpect(jsonPath("$.items[0].name").value("Alpha"))
                .andExpect(jsonPath("$.items[0].updatedAt").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));

        mvc.perform(delete("/api/v1/sessions/current").cookie(login.cookie))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/sessions/current").cookie(login.cookie)
                        .header("X-CSRF-Token", login.csrfToken)
                        .header("Idempotency-Key", "session-delete-alpha"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
        mvc.perform(get("/api/v1/sessions/current").cookie(login.cookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonMemberCannotReadOrMutateAnotherSpace() throws Exception {
        register("alice@example.test", "correct horse battery", "Alice");
        Login alice = login("alice@example.test", "correct horse battery", "/sessions");
        MvcResult created = mvc.perform(post("/api/v1/spaces").cookie(alice.cookie)
                        .header("X-CSRF-Token", alice.csrfToken)
                        .header("Idempotency-Key", "space-create-private")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Private Alpha\",\"description\":\"secret\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        String spaceId = body.get("spaceId").asText();

        register("bob@example.test", "correct horse battery", "Bob");
        Login bob = login("bob@example.test", "correct horse battery", "/sessions");

        mvc.perform(put("/api/v1/spaces/{spaceId}/members/{userId}", spaceId, userId("bob@example.test"))
                        .cookie(alice.cookie)
                        .header("X-CSRF-Token", alice.csrfToken)
                        .header("Idempotency-Key", "member-add-bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spaceId").value(spaceId))
                .andExpect(jsonPath("$.userId").value(userId("bob@example.test").toString()))
                .andExpect(jsonPath("$.role").value("VIEWER"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.updatedAt").doesNotExist());

        mvc.perform(get("/api/v1/spaces").cookie(bob.cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].spaceId").value(spaceId));

        register("eve@example.test", "correct horse battery", "Eve");
        Login eve = login("eve@example.test", "correct horse battery", "/auth/login");

        mvc.perform(put("/api/v1/spaces/{spaceId}/members/{userId}", spaceId, userId("alice@example.test"))
                        .cookie(eve.cookie)
                        .header("X-CSRF-Token", eve.csrfToken)
                        .header("Idempotency-Key", "member-cross-space-eve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("SPACE_NOT_FOUND")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Private Alpha"))));

        mvc.perform(get("/api/v1/spaces").cookie(eve.cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void sourceTaskApiRejectsUnauthenticatedPrincipal() throws Exception {
        mvc.perform(get("/api/v1/spaces/{spaceId}/sources", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sourceTaskApiRejectsCrossSpaceNonMember() throws Exception {
        register("source-owner@example.test", "correct horse battery", "Source Owner");
        Login owner = login("source-owner@example.test", "correct horse battery", "/sessions");
        MvcResult created = mvc.perform(post("/api/v1/spaces").cookie(owner.cookie)
                        .header("X-CSRF-Token", owner.csrfToken)
                        .header("Idempotency-Key", "source-space-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Source Space\",\"description\":\"private\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID spaceId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString())
                .get("spaceId").asText());

        register("source-outsider@example.test", "correct horse battery", "Source Outsider");
        Login outsider = login("source-outsider@example.test", "correct horse battery", "/sessions");
        mvc.perform(get("/api/v1/spaces/{spaceId}/sources", spaceId).cookie(outsider.cookie))
                .andExpect(status().isNotFound());
    }

    @Test
    void sourceTaskApiRejectsDisabledUser() throws Exception {
        register("source-disabled@example.test", "correct horse battery", "Source Disabled");
        Login disabled = login("source-disabled@example.test", "correct horse battery", "/sessions");
        MvcResult created = mvc.perform(post("/api/v1/spaces").cookie(disabled.cookie)
                        .header("X-CSRF-Token", disabled.csrfToken)
                        .header("Idempotency-Key", "source-disabled-space")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Disabled Source Space\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID spaceId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString())
                .get("spaceId").asText());
        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", disabled.userId);

        mvc.perform(get("/api/v1/spaces/{spaceId}/sources", spaceId).cookie(disabled.cookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void activeSessionAuthenticationSourceIsValkey() throws Exception {
        register("valkey@example.test", "correct horse battery", "Valkey");
        Login login = login("valkey@example.test", "correct horse battery", "/auth/login");
        String key = "ragforge:session:" + com.ragforge.server.identity.SessionAuthenticationFilter
                .hash(login.cookie.getValue());
        org.assertj.core.api.Assertions.assertThat(redis.hasKey(key)).isTrue();

        redis.delete(key);
        mvc.perform(get("/api/v1/sessions/current").cookie(login.cookie))
                .andExpect(status().isUnauthorized());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT revoked_at FROM sessions WHERE id = ?", Object.class, login.sessionId)).isNull();
    }

    @Test
    void platformAdminCanManageUsersButRegularUserCannot() throws Exception {
        register("admin@example.test", "correct horse battery", "Admin");
        UUID adminId = userId("admin@example.test");
        jdbc.update("UPDATE users SET platform_role = 'PLATFORM_ADMIN' WHERE id = ?", adminId);
        Login admin = login("admin@example.test", "correct horse battery", "/auth/login");

        mvc.perform(get("/api/v1/users").cookie(admin.cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].createdAt").exists())
                .andExpect(jsonPath("$.items[0].passwordHash").doesNotExist());

        MvcResult created = mvc.perform(post("/api/v1/users").cookie(admin.cookie)
                        .header("X-CSRF-Token", admin.csrfToken)
                        .header("Idempotency-Key", "admin-create-managed-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"managed@example.test\",\"displayName\":\"Managed\",\"password\":\"correct horse battery\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        UUID managedId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("userId").asText());

        mvc.perform(put("/api/v1/users/{userId}", managedId).cookie(admin.cookie)
                        .header("X-CSRF-Token", admin.csrfToken)
                        .header("Idempotency-Key", "admin-update-managed-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Managed Updated\",\"platformRole\":\"USER\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Managed Updated"));

        mvc.perform(delete("/api/v1/users/{userId}", managedId).cookie(admin.cookie)
                        .header("X-CSRF-Token", admin.csrfToken)
                        .header("Idempotency-Key", "admin-disable-managed-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        mvc.perform(post("/api/v1/auth/login")
                        .header("Idempotency-Key", "disabled-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"managed@example.test\",\"password\":\"correct horse battery\"}"))
                .andExpect(status().isUnauthorized());

        register("regular@example.test", "correct horse battery", "Regular");
        Login regular = login("regular@example.test", "correct horse battery", "/sessions");
        mvc.perform(get("/api/v1/users").cookie(regular.cookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_ADMIN_REQUIRED"));
    }

    @Test
    void spaceAdminCanUpdateArchiveAndManageMembersWithVersionChecks() throws Exception {
        register("space-admin@example.test", "correct horse battery", "Space Admin");
        Login admin = login("space-admin@example.test", "correct horse battery", "/sessions");
        MvcResult created = mvc.perform(post("/api/v1/spaces").cookie(admin.cookie)
                        .header("X-CSRF-Token", admin.csrfToken)
                        .header("Idempotency-Key", "space-crud-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"CRUD Space\",\"description\":\"before\"}"))
                .andExpect(status().isCreated()).andReturn();
        String spaceId = objectMapper.readTree(created.getResponse().getContentAsString()).get("spaceId").asText();

        mvc.perform(put("/api/v1/spaces/{spaceId}", spaceId).cookie(admin.cookie)
                        .header("X-CSRF-Token", admin.csrfToken)
                        .header("Idempotency-Key", "space-crud-update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"CRUD Space Updated\",\"description\":\"after\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("CRUD Space Updated"))
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(get("/api/v1/spaces/{spaceId}/members", spaceId).cookie(admin.cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].email").value("space-admin@example.test"))
                .andExpect(jsonPath("$.items[0].role").value("SPACE_ADMIN"));

        mvc.perform(delete("/api/v1/spaces/{spaceId}", spaceId).cookie(admin.cookie)
                        .header("X-CSRF-Token", admin.csrfToken)
                        .header("Idempotency-Key", "space-crud-archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/spaces").cookie(admin.cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void spaceAdminCanAddActiveMemberByExactEmailWithoutExposingUserDirectory() throws Exception {
        register("member-admin@example.test", "correct horse battery", "Member Admin");
        register("editor@example.test", "correct horse battery", "Editor User");
        register("viewer@example.test", "correct horse battery", "Viewer User");
        register("outsider@example.test", "correct horse battery", "Outsider");
        Login admin = login("member-admin@example.test", "correct horse battery", "/sessions");
        MvcResult created = mvc.perform(post("/api/v1/spaces").cookie(admin.cookie)
                        .header("X-CSRF-Token", admin.csrfToken)
                        .header("Idempotency-Key", "member-space-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Member Space\",\"description\":\"membership\"}"))
                .andExpect(status().isCreated()).andReturn();
        String spaceId = objectMapper.readTree(created.getResponse().getContentAsString()).get("spaceId").asText();

        mvc.perform(post("/api/v1/spaces/{spaceId}/members", spaceId).cookie(admin.cookie)
                        .header("X-CSRF-Token", admin.csrfToken)
                        .header("Idempotency-Key", "member-add-editor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"EDITOR@example.test\",\"role\":\"EDITOR\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(userId("editor@example.test").toString()))
                .andExpect(jsonPath("$.role").value("EDITOR"))
                .andExpect(jsonPath("$.version").value(0));

        mvc.perform(get("/api/v1/spaces/{spaceId}/members", spaceId).cookie(admin.cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[1].email").value("editor@example.test"))
                .andExpect(jsonPath("$.items[1].role").value("EDITOR"));

        mvc.perform(post("/api/v1/spaces/{spaceId}/members", spaceId).cookie(admin.cookie)
                        .header("X-CSRF-Token", admin.csrfToken)
                        .header("Idempotency-Key", "member-add-viewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"viewer@example.test\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("VIEWER"));
        Login viewer = login("viewer@example.test", "correct horse battery", "/sessions");
        mvc.perform(get("/api/v1/spaces").cookie(viewer.cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].spaceId").value(spaceId))
                .andExpect(jsonPath("$.items[0].role").value("VIEWER"));

        mvc.perform(post("/api/v1/spaces/{spaceId}/members", spaceId).cookie(admin.cookie)
                        .header("X-CSRF-Token", admin.csrfToken)
                        .header("Idempotency-Key", "member-add-editor-again")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"editor@example.test\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SPACE_MEMBER_ALREADY_EXISTS"));

        Login editor = login("editor@example.test", "correct horse battery", "/sessions");
        mvc.perform(get("/api/v1/spaces").cookie(editor.cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].spaceId").value(spaceId))
                .andExpect(jsonPath("$.items[0].role").value("EDITOR"));
        mvc.perform(post("/api/v1/spaces/{spaceId}/members", spaceId).cookie(editor.cookie)
                        .header("X-CSRF-Token", editor.csrfToken)
                        .header("Idempotency-Key", "member-editor-cannot-add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"outsider@example.test\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SPACE_ADMIN_REQUIRED"));

        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE email = 'outsider@example.test'");
        mvc.perform(post("/api/v1/spaces/{spaceId}/members", spaceId).cookie(admin.cookie)
                        .header("X-CSRF-Token", admin.csrfToken)
                        .header("Idempotency-Key", "member-disabled-not-found")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"outsider@example.test\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACTIVE_USER_NOT_FOUND"));
    }

    @Test
    void idempotencyBindsAnonymousRequestHashAndRejectsReuse() throws Exception {
        String body = "{\"email\":\"idempotent@example.test\",\"password\":\"correct horse battery\",\"displayName\":\"Idempotent\"}";
        mvc.perform(post("/api/v1/auth/register")
                        .header("Idempotency-Key", "register-idempotent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/auth/register")
                        .header("Idempotency-Key", "register-idempotent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        mvc.perform(post("/api/v1/auth/register")
                        .header("Idempotency-Key", "register-idempotent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("Idempotent", "Changed")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM idempotency_records
                WHERE principal_scope = 'anonymous' AND idempotency_key = 'register-idempotent'
                """, Integer.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                SELECT request_hash FROM idempotency_records
                WHERE principal_scope = 'anonymous' AND idempotency_key = 'register-idempotent'
                """, String.class)).hasSize(64);
    }

    private void register(String email, String password, String displayName) throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .header("Idempotency-Key", "register-" + email.replace("@", "-"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "email", email, "password", password, "displayName", displayName))))
                .andExpect(status().isCreated());
    }

    private int bootstrapStatus(CountDownLatch start, String email, String idempotencyKey) throws Exception {
        start.await();
        return mvc.perform(post("/api/v1/bootstrap/platform-admin")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-RAGForge-Bootstrap-Token", BOOTSTRAP_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "email", email, "password", "concurrent password 123",
                                "displayName", "Concurrent Admin"))))
                .andReturn().getResponse().getStatus();
    }

    private Login login(String email, String password, String path) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1" + path)
                        .header("Idempotency-Key", "login-" + email.replace("@", "-"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.session.sessionId").exists())
                .andExpect(jsonPath("$.session.userId").exists())
                .andExpect(jsonPath("$.session.expiresAt").exists())
                .andExpect(jsonPath("$.session.csrfToken").exists())
                .andExpect(jsonPath("$.user.userId").exists())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.session.email").doesNotExist())
                .andReturn();
        String csrf = result.getResponse().getHeader("X-CSRF-Token");
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Login(result.getResponse().getCookie("RAGFORGE_SESSION"), csrf,
                UUID.fromString(body.get("session").get("sessionId").asText()),
                UUID.fromString(body.get("user").get("userId").asText()));
    }

    private UUID userId(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private record Login(Cookie cookie, String csrfToken, UUID sessionId, UUID userId) {
    }
}
