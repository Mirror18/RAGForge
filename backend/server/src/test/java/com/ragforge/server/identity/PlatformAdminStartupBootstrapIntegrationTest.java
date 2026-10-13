package com.ragforge.server.identity;

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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlatformAdminStartupBootstrapIntegrationTest {
    private static final String STARTUP_TOKEN = "startup-bootstrap-token-with-at-least-32-characters";

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
        registry.add("ragforge.bootstrap.admin.token", () -> "");
        registry.add("ragforge.bootstrap.admin.startup-issue-enabled", () -> "true");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformAdminStartupTokenRepository startupTokens;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE idempotency_records, outbox_events, audit_events, space_memberships, knowledge_spaces, sessions, users, platform_admin_startup_tokens CASCADE");
        startupTokens.replace(BootstrapTokenSupport.hash(STARTUP_TOKEN), java.time.Instant.now());
    }

    @Test
    void startupIssuedTokenBootstrapsOnceAndClearsStoredHash() throws Exception {
        mvc.perform(get("/api/v1/bootstrap/platform-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.required").value(true))
                .andExpect(jsonPath("$.available").value(true));

        String body = "{\"email\":\"startup-admin@example.test\",\"password\":\"new correct horse battery\",\"displayName\":\"Startup Admin\"}";
        mvc.perform(post("/api/v1/bootstrap/platform-admin")
                        .header("Idempotency-Key", "startup-bootstrap")
                        .header("X-RAGForge-Bootstrap-Token", STARTUP_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("CREATED"));

        assertThat(startupTokens.hasActiveToken()).isFalse();

        mvc.perform(get("/api/v1/bootstrap/platform-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.required").value(false));

        String auditPayload = jdbc.queryForObject("""
                SELECT payload::text FROM audit_events WHERE event_type = 'platform.admin.bootstrapped.v1'
                """, String.class);
        assertThat(auditPayload).contains("STARTUP_TOKEN");
    }
}
