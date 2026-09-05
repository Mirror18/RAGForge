package com.ragforge.server.index;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end candidate lifecycle proof across PostgreSQL, Qdrant and Valkey. */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CandidateIndexServiceIntegrationTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");
    private static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.0.1-alpine")
            .withExposedPorts(6379);
    private static final GenericContainer<?> QDRANT = new GenericContainer<>("qdrant/qdrant:v1.11.5")
            .withExposedPorts(6333)
            .waitingFor(Wait.forHttp("/readyz").forPort(6333));

    static {
        POSTGRES.start();
        try {
            VALKEY.start();
            QDRANT.start();
        } catch (RuntimeException exception) {
            QDRANT.stop();
            VALKEY.stop();
            POSTGRES.stop();
            throw exception;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.url", () -> "redis://" + VALKEY.getHost() + ":" + VALKEY.getMappedPort(6379));
        registry.add("ragforge.qdrant.url", () -> "http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333));
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CandidateIndexService candidates;

    @Autowired
    IndexRepository indexes;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE active_index_pointers, index_versions, knowledge_spaces CASCADE");
    }

    @Test
    void candidateIsValidatedAndOnlyExplicitPublishChangesActivePointer() {
        UUID space = UUID.randomUUID();
        UUID indexVersion = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO knowledge_spaces (id, name, description, status, created_at, updated_at, version)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?, 0)
                """, space, "p4-e2e-" + space, "synthetic", java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now()));
        CandidateIndexStore.CandidatePoint point = new CandidateIndexStore.CandidatePoint(
                UUID.randomUUID(), space, indexVersion, UUID.randomUUID(), UUID.randomUUID(),
                "s3://synthetic/child-0", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                List.of(0.1, 0.2, 0.3));

        CandidateIndexService.BuildResult built = candidates.build(new CandidateIndexService.BuildRequest(
                space, indexVersion, 1, "local-embedding-v3", "p4-default-v1", 1, 3,
                List.of(point), Instant.parse("2026-08-21T00:00:00Z")));

        assertThat(built.ready()).isTrue();
        assertThat(built.indexVersion().state()).isEqualTo(IndexState.READY);
        assertThat(indexes.findActivePointer(space)).isEmpty();
        assertThat(built.indexVersion().candidateCollection())
                .isEqualTo(CandidateIndexService.collectionFor(space, indexVersion));

        IndexRepository.ActiveIndexPointer pointer = candidates.publish(space, indexVersion,
                Instant.parse("2026-08-21T00:05:00Z"));

        assertThat(pointer.activeIndexVersionId()).isEqualTo(indexVersion);
        assertThat(indexes.findVersion(space, indexVersion).orElseThrow().state()).isEqualTo(IndexState.ACTIVE);
    }
}
