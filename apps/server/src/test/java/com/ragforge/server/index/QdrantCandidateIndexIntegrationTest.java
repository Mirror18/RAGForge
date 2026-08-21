package com.ragforge.server.index;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real Qdrant proof for candidate collection, payload filters and validation. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QdrantCandidateIndexIntegrationTest {
    private static final GenericContainer<?> QDRANT = new GenericContainer<>("qdrant/qdrant:v1.11.5")
            .withExposedPorts(6333)
            .waitingFor(Wait.forHttp("/readyz").forPort(6333));

    private static final UUID SPACE_A = UUID.fromString("018f0f70-8e10-7b14-8f1a-111111111111");
    private static final UUID SPACE_B = UUID.fromString("018f0f70-8e10-7b14-8f1a-222222222222");
    private static final UUID VERSION = UUID.fromString("018f0f70-8e10-7b14-8f1a-333333333333");
    private static final UUID REVISION = UUID.fromString("018f0f70-8e10-7b14-8f1a-444444444444");
    private static final UUID PARENT = UUID.fromString("018f0f70-8e10-7b14-8f1a-555555555555");
    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private QdrantCandidateIndex client;
    private String collection;

    @BeforeAll
    void start() {
        QDRANT.start();
        client = new QdrantCandidateIndex(URI.create("http://" + QDRANT.getHost() + ":"
                + QDRANT.getMappedPort(6333)), new ObjectMapper());
        collection = "rf_test_" + UUID.randomUUID().toString().replace("-", "");
    }

    @AfterAll
    void stop() {
        if (client != null) {
            client.deleteCollection(collection);
        }
        QDRANT.stop();
    }

    @Test
    void candidateValidationAndSearchAlwaysApplySpaceAndIndexFilters() {
        CandidateIndexStore.CandidatePoint pointA = point(UUID.fromString("018f0f70-8e10-7b14-8f1a-666666666666"),
                SPACE_A, HASH_A, List.of(0.1, 0.2, 0.3));
        CandidateIndexStore.CandidatePoint pointB = point(UUID.fromString("018f0f70-8e10-7b14-8f1a-777777777777"),
                SPACE_B, HASH_B, List.of(0.1, 0.2, 0.3));

        client.createCollection(collection, 3);
        client.upsert(collection, List.of(pointA, pointB));
        CandidateIndexStore.ValidationResult validation = client.validate(
                collection, SPACE_A, VERSION, 1, 3, List.of(pointA));

        assertThat(validation.pointCount()).isEqualTo(1);
        assertThat(validation.vectorDimension()).isEqualTo(3);
        assertThat(validation.sampleRetrievalPassed()).isTrue();
        assertThat(validation.spaceFilterPassed()).isTrue();
        assertThat(client.search(collection, SPACE_A, VERSION, pointA.vector(), 10))
                .extracting(CandidateIndexStore.CandidateHit::id).containsExactly(pointA.id());
        assertThat(client.search(collection, SPACE_B, VERSION, pointB.vector(), 10))
                .extracting(CandidateIndexStore.CandidateHit::id).containsExactly(pointB.id());
        assertThatThrownBy(() -> client.search(collection, null, VERSION, pointA.vector(), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }

    private static CandidateIndexStore.CandidatePoint point(UUID id, UUID space, String hash, List<Double> vector) {
        return new CandidateIndexStore.CandidatePoint(id, space, VERSION, REVISION, PARENT,
                "s3://synthetic/" + id, hash, vector);
    }
}
