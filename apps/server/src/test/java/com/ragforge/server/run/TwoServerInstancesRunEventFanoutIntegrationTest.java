package com.ragforge.server.run;

import com.ragforge.server.RagForgeServerApplication;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Starts two independent server contexts against shared PostgreSQL and Valkey infrastructure. */
class TwoServerInstancesRunEventFanoutIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");
    private static final String CHANNEL = "ragforge:test:run-events:two-server-contexts";
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");
    private static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.0.1-alpine")
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

    @Test
    void publishesAcrossTwoServerContextsWithSharedDurableStore() {
        ConfigurableApplicationContext serverA = startServer();
        ConfigurableApplicationContext serverB = startServer();
        try {
            RunEventFanout fanoutA = serverA.getBean(RunEventFanout.class);
            RunEventFanout fanoutB = serverB.getBean(RunEventFanout.class);
            fanoutA.start();
            fanoutB.start();
            assertThat(fanoutA.metrics().listenerRunning()).as("server A property=%s metrics=%s",
                    serverA.getEnvironment().getProperty("ragforge.run-events.fanout.enabled"), fanoutA.metrics())
                    .isTrue();
            assertThat(fanoutB.metrics().listenerRunning()).as("server B property=%s metrics=%s",
                    serverB.getEnvironment().getProperty("ragforge.run-events.fanout.enabled"), fanoutB.metrics())
                    .isTrue();
            JdbcTemplate jdbc = serverA.getBean(JdbcTemplate.class);
            jdbc.execute("TRUNCATE rag_run_events, rag_run_event_streams, runs, knowledge_spaces, users CASCADE");
            RunRepository runs = serverA.getBean(RunRepository.class);
            UUID space = createSpace(jdbc);
            UUID run = runs.createRun(new RunRepository.NewRun(UUID.randomUUID(), space, null, UUID.randomUUID(),
                    RunRepository.RequestKind.CHAT, RunRepository.RunStatus.RUNNING, null, null, null, null, null,
                    null, null, null, NOW)).id();

            // Listener start is asynchronous. A valid non-business hint makes readiness explicit
            // without putting prompt or answer data on the channel.
            RunEvent warmup = new RunEvent(UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), NOW, "run.status", 1, "{\"status\":\"PROBE\"}");
            Awaitility.await().pollInterval(Duration.ofMillis(100)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                fanoutA.publishAfterCommit(warmup);
                assertThat(fanoutB.metrics().received()).isGreaterThanOrEqualTo(1);
            });

            JdbcRunEventStore storeB = serverB.getBean(JdbcRunEventStore.class);
            List<RunEvent> received = new CopyOnWriteArrayList<>();
            RunEventStore.Subscription subscription = storeB.openStream(space, run, null, received::add).subscription();
            subscription.activate();
            RunEvent event = serverA.getBean(JdbcRunEventStore.class).append(new RunEventDraft(
                    run, space, UUID.randomUUID(), "answer.done", 1, "{\"status\":\"COMPLETED\"}"));

            Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(received).extracting(RunEvent::eventId).containsExactly(event.eventId()));
            assertThat(serverA.getBean(RunEventFanout.class).isRunning()).isTrue();
            assertThat(serverB.getBean(RunEventFanout.class).isRunning()).isTrue();
            assertThat(serverB.getBean(RunEventFanout.class).metrics().invalid()).isZero();
            subscription.close();
        } finally {
            serverB.close();
            serverA.close();
        }
    }

    private ConfigurableApplicationContext startServer() {
        return new SpringApplicationBuilder(RagForgeServerApplication.class)
                .properties(
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword(),
                        "spring.data.redis.url=redis://" + VALKEY.getHost() + ":" + VALKEY.getMappedPort(6379),
                        "spring.main.web-application-type=none",
                        "spring.main.banner-mode=off",
                        "ragforge.run-events.fanout.enabled=true",
                        "ragforge.run-events.fanout.channel=" + CHANNEL,
                        "RAGFORGE_RUN_EVENT_FANOUT_ENABLED=true",
                        "RAGFORGE_RUN_EVENT_FANOUT_CHANNEL=" + CHANNEL)
                .run();
    }

    private UUID createSpace(JdbcTemplate jdbc) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO knowledge_spaces (id, name, description, status, created_at, updated_at, version)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?, 0)
                """, id, "two-server-space", "two-server-space", java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
        return id;
    }
}
