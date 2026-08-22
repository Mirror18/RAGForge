package com.ragforge.server.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Two independent store/listener pairs prove Valkey hints plus PostgreSQL replay semantics. */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiInstanceRunEventFanoutIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-22T00:00:00Z");
    private static final String CHANNEL = "ragforge:test:run-events:multi-instance";
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

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.url", () -> "redis://" + VALKEY.getHost() + ":"
                + VALKEY.getMappedPort(6379));
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RunRepository runs;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    RedisConnectionFactory connectionFactory;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PlatformTransactionManager transactionManager;

    private RunEventFanout fanoutA;
    private RunEventFanout fanoutB;
    private JdbcRunEventStore storeA;
    private JdbcRunEventStore storeB;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE rag_run_events, rag_run_event_streams, runs, knowledge_spaces, users CASCADE");
    }

    @AfterEach
    void stopInstances() {
        if (fanoutA != null) fanoutA.stop();
        if (fanoutB != null) fanoutB.stop();
    }

    @Test
    void crossesInstancesKeepsSpaceIsolationRecoversReorderingAndSurvivesValkeyPause() {
        UUID spaceA = createSpace("fanout-a");
        UUID spaceB = createSpace("fanout-b");
        UUID runA = createRun(spaceA);
        UUID runB = createRun(spaceB);
        fanoutA = new RunEventFanout(redis, objectMapper, connectionFactory, CHANNEL, true);
        fanoutB = new RunEventFanout(redis, objectMapper, connectionFactory, CHANNEL, true);
        storeA = new JdbcRunEventStore(jdbc, Duration.ofMinutes(15), Clock.systemUTC(), fanoutA);
        storeB = new JdbcRunEventStore(jdbc, Duration.ofMinutes(15), Clock.systemUTC(), fanoutB);
        fanoutA.start();
        fanoutB.start();

        List<RunEvent> received = new CopyOnWriteArrayList<>();
        RunEventStore.Subscription subscription = storeB.openStream(spaceA, runA, null, received::add).subscription();
        subscription.activate();
        RunEvent first = storeA.append(new RunEventDraft(runA, spaceA, UUID.randomUUID(), "run.status", 1,
                "{\"status\":\"RUNNING\"}"));
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(received).extracting(RunEvent::eventId).containsExactly(first.eventId()));

        storeA.append(new RunEventDraft(runB, spaceB, UUID.randomUUID(), "run.status", 1,
                "{\"status\":\"RUNNING\"}"));
        Awaitility.await().pollDelay(Duration.ofMillis(100)).atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(received).hasSize(1));

        // Stop the second listener: the next two committed events remain durable but their hints are lost.
        fanoutB.stop();
        RunEvent second = storeA.append(new RunEventDraft(runA, spaceA, UUID.randomUUID(), "answer.delta", 1,
                "{\"text\":\"second\"}"));
        RunEvent third = storeA.append(new RunEventDraft(runA, spaceA, UUID.randomUUID(), "answer.done", 1,
                "{\"status\":\"COMPLETED\"}"));
        assertThat(second.sequence()).isEqualTo(first.sequence() + 1);
        assertThat(third.sequence()).isEqualTo(second.sequence() + 1);

        // A later, out-of-order hint causes a PostgreSQL read that fills both missing sequences.
        storeB.handleRemoteHint(RunEventFanoutEnvelope.from(third));
        storeB.handleRemoteHint(RunEventFanoutEnvelope.from(first));
        storeB.handleRemoteHint(RunEventFanoutEnvelope.from(second));
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(received).extracting(RunEvent::sequence).containsExactly(1L, 2L, 3L));
        assertThat(storeB.fanoutDeliveryMetrics().remoteDuplicates()).isGreaterThanOrEqualTo(2);

        // Restarting the listener cannot restore lost Pub/Sub hints, but Last-Event-ID durable replay does.
        fanoutB.start();
        RunEvent fourth = storeA.append(new RunEventDraft(runA, spaceA, UUID.randomUUID(), "answer.done", 1,
                "{\"status\":\"ARCHIVED\"}"));
        List<RunEvent> replayed = storeB.replay(spaceA, runA, third.eventId().toString()).events();
        assertThat(replayed).extracting(RunEvent::eventId).containsExactly(fourth.eventId());
        assertThat(fanoutB.metrics().published()).isZero();
        assertThat(fanoutB.metrics().invalid()).isZero();
        subscription.close();
    }

    @Test
    void rollbackDoesNotLeakLocalOrRemoteHints() {
        UUID space = createSpace("fanout-rollback");
        UUID run = createRun(space);
        fanoutA = new RunEventFanout(redis, objectMapper, connectionFactory, CHANNEL, true);
        fanoutB = new RunEventFanout(redis, objectMapper, connectionFactory, CHANNEL, true);
        storeA = new JdbcRunEventStore(jdbc, Duration.ofMinutes(15), Clock.systemUTC(), fanoutA);
        storeB = new JdbcRunEventStore(jdbc, Duration.ofMinutes(15), Clock.systemUTC(), fanoutB);
        fanoutA.start();
        fanoutB.start();
        List<RunEvent> received = new CopyOnWriteArrayList<>();
        RunEventStore.Subscription subscription = storeB.openStream(space, run, null, received::add).subscription();
        subscription.activate();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .execute(status -> {
                    storeA.append(new RunEventDraft(run, space, UUID.randomUUID(), "answer.delta", 1,
                            "{\"text\":\"rolled back\"}"));
                    throw new IllegalStateException("test rollback");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(storeA.replay(space, run, null).events()).isEmpty();
        assertThat(received).isEmpty();
        subscription.close();
    }

    private UUID createSpace(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO knowledge_spaces (id, name, description, status, created_at, updated_at, version)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?, 0)
                """, id, name, name, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        return id;
    }

    private UUID createRun(UUID spaceId) {
        return runs.createRun(new RunRepository.NewRun(UUID.randomUUID(), spaceId, null, UUID.randomUUID(),
                RunRepository.RequestKind.CHAT, RunRepository.RunStatus.RUNNING, null, null, null, null, null,
                null, null, null, NOW)).id();
    }
}
