package com.ragforge.ingestion.messaging;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL proof that duplicate deliveries have one durable side effect. */
@Testcontainers
@SpringBootTest(properties = "ragforge.ingestion.enabled=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdempotencyConcurrencyIntegrationTest {
    private static final UUID SPACE = UUID.fromString("018f0f00-0000-7000-8000-000000000031");
    private static final UUID JOB = UUID.fromString("018f0f00-0000-7000-8000-000000000032");
    private static final UUID ATTEMPT = UUID.fromString("018f0f00-0000-7000-8000-000000000033");
    private static final UUID REVISION = UUID.fromString("018f0f00-0000-7000-8000-000000000034");
    private static final UUID SOURCE = UUID.fromString("018f0f00-0000-7000-8000-000000000035");
    private static final UUID PIPELINE = UUID.fromString("018f0f00-0000-7000-8000-000000000036");
    private static final UUID ARTIFACT = UUID.fromString("018f0f00-0000-7000-8000-000000000037");

    static final GenericContainer<?> POSTGRES = new GenericContainer<>("postgres:16.4-alpine")
            .withEnv("POSTGRES_DB", "ragforge")
            .withEnv("POSTGRES_USER", "ragforge")
            .withEnv("POSTGRES_PASSWORD", "ragforge")
            .withExposedPorts(5432)
            .waitingFor(Wait.forListeningPort());

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/ragforge");
        registry.add("spring.datasource.username", () -> "ragforge");
        registry.add("spring.datasource.password", () -> "ragforge");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JdbcIngestionIdempotencyStore idempotencyStore;

    @BeforeEach
    void prepareTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ingestion_idempotency (
                    id UUID PRIMARY KEY,
                    space_id UUID NOT NULL,
                    job_id UUID NOT NULL,
                    attempt_id UUID NOT NULL,
                    step_name VARCHAR(32) NOT NULL,
                    idempotency_key VARCHAR(255) NOT NULL,
                    result_reference UUID,
                    created_at TIMESTAMPTZ NOT NULL,
                    UNIQUE (space_id, job_id, attempt_id, step_name, idempotency_key)
                )
                """);
        jdbc.update("TRUNCATE ingestion_idempotency");
    }

    @Test
    void twentyConcurrentDeliveriesProduceOneDurableSideEffect() throws Exception {
        IngestionEventEnvelope envelope = new IngestionEventEnvelope(
                UUID.fromString("018f0f00-0000-7000-8000-000000000038"),
                "ingestion.job.requested.v1", Instant.parse("2026-08-13T00:00:00Z"), "test",
                UUID.fromString("018f0f00-0000-7000-8000-000000000039"),
                UUID.fromString("018f0f00-0000-7000-8000-00000000003a"), SPACE,
                UUID.fromString("018f0f00-0000-7000-8000-00000000003b"),
                JsonNodeFactory.instance.objectNode());
        IngestionJobRequestedPayload payload = new IngestionJobRequestedPayload(
                JOB, SOURCE, REVISION, PIPELINE, ATTEMPT, "DOCUMENT_UPSERT",
                new IngestionJobRequestedPayload.ArtifactReference(
                        ARTIFACT, "text/plain", 7,
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        AtomicInteger sideEffects = new AtomicInteger();
        IngestionSideEffectHandler handler = (ignoredEnvelope, ignoredPayload) -> {
            sideEffects.incrementAndGet();
            try {
                Thread.sleep(25);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch ready = new CountDownLatch(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<JdbcIngestionIdempotencyStore.ProcessResult>> futures = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return idempotencyStore.process(envelope, payload, "FETCH", "artifact-side-effect-v1", handler);
            }));
        }
        ready.await();
        start.countDown();
        List<JdbcIngestionIdempotencyStore.ProcessResult> results = new ArrayList<>();
        for (Future<JdbcIngestionIdempotencyStore.ProcessResult> future : futures) {
            results.add(future.get());
        }
        executor.shutdownNow();

        assertThat(results).containsOnlyOnce(JdbcIngestionIdempotencyStore.ProcessResult.PROCESSED)
                .containsOnly(JdbcIngestionIdempotencyStore.ProcessResult.PROCESSED,
                        JdbcIngestionIdempotencyStore.ProcessResult.DUPLICATE);
        assertThat(sideEffects).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ingestion_idempotency", Integer.class)).isEqualTo(1);
    }
}
