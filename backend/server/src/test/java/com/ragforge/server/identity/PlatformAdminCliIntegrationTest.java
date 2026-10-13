package com.ragforge.server.identity;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "ragforge.cli.mode=create-platform-admin",
        "ragforge.cli.admin-email=cli-admin@example.test",
        "ragforge.cli.admin-display-name=CLI Admin",
        "ragforge.cli.admin-password=cli correct horse battery",
        "spring.main.web-application-type=none"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlatformAdminCliIntegrationTest {
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
        registry.add("ragforge.bootstrap.admin.startup-issue-enabled", () -> "false");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void cliRunnerCreatesPlatformAdministrator() {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM users WHERE email = 'cli-admin@example.test'
                AND platform_role = 'PLATFORM_ADMIN'
                """, Integer.class)).isEqualTo(1);
    }
}
