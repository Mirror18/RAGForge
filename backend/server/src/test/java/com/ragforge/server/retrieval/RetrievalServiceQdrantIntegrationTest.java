package com.ragforge.server.retrieval;

import java.net.URI;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.index.CandidateIndexService;
import com.ragforge.server.index.CandidateIndexStore;
import com.ragforge.server.index.QdrantCandidateIndex;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Qdrant proof for the dense side of the P4-F retrieval pipeline. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetrievalServiceQdrantIntegrationTest {
    private static final GenericContainer<?> QDRANT = new GenericContainer<>("qdrant/qdrant:v1.11.5")
            .withExposedPorts(6333)
            .waitingFor(Wait.forHttp("/readyz").forPort(6333));

    private static final UUID SPACE = UUID.fromString("018f0f70-8e10-7b14-8f1a-111111111111");
    private static final UUID FOREIGN_SPACE = UUID.fromString("018f0f70-8e10-7b14-8f1a-999999999999");
    private static final UUID INDEX = UUID.fromString("018f0f70-8e10-7b14-8f1a-222222222222");
    private static final UUID PROFILE_ID = UUID.fromString("018f0f70-8e10-7b14-8f1a-555555555555");
    private static final UUID PROFILE_VERSION_ID = UUID.fromString("018f0f70-8e10-7b14-8f1a-666666666666");
    private static final UUID REVISION = UUID.fromString("018f0f70-8e10-7b14-8f1a-333333333333");
    private static final UUID PARENT = UUID.fromString("018f0f70-8e10-7b14-8f1a-444444444444");
    private static final UUID CHILD = UUID.fromString("018f0f70-8e10-7b14-8f1a-aaaaaaaaaaaa");
    private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private QdrantCandidateIndex dense;
    private String collection;

    @BeforeAll
    void start() {
        QDRANT.start();
        dense = new QdrantCandidateIndex(URI.create("http://" + QDRANT.getHost() + ":"
                + QDRANT.getMappedPort(6333)), new ObjectMapper());
        collection = CandidateIndexService.collectionFor(SPACE, INDEX);
    }

    @AfterAll
    void stop() {
        if (dense != null) {
            dense.deleteCollection(collection);
        }
        QDRANT.stop();
    }

    @Test
    void retrievalPipelineUsesQdrantScopeFilterBeforeFusion() {
        CandidateIndexStore.CandidatePoint local = new CandidateIndexStore.CandidatePoint(
                CHILD, SPACE, INDEX, REVISION, PARENT, ref(CHILD), HASH, List.of(1.0, 0.0, 0.0));
        CandidateIndexStore.CandidatePoint foreign = new CandidateIndexStore.CandidatePoint(
                UUID.fromString("018f0f70-8e10-7b14-8f1a-bbbbbbbbbbbb"), FOREIGN_SPACE, INDEX, REVISION, PARENT,
                "s3://foreign/child", HASH, List.of(1.0, 0.0, 0.0));
        dense.createCollection(collection, 3);
        dense.upsert(collection, List.of(local, foreign));

        InMemoryBm25CandidateStore bm25 = new InMemoryBm25CandidateStore();
        bm25.upsert(new Bm25CandidateStore.Document(SPACE, INDEX, CHILD, REVISION, PARENT, ref(CHILD), HASH,
                "space isolated retrieval"));
        RetrievalService service = new RetrievalService(dense, bm25, new LocalCatalog(), new LexicalReranker());

        EvidenceBundle bundle = service.retrieve(new RetrievalService.Request(SPACE, INDEX,
                new RetrievalProfileRepository.RetrievalProfileVersion(PROFILE_VERSION_ID, SPACE, PROFILE_ID, 1,
                        10, 10, 60, 0.5, 0.5, 10, 8, ExpansionMode.NONE, 0, 0, 100,
                        Instant.parse("2026-08-21T00:00:00Z")),
                "isolated retrieval", List.of(1.0, 0.0, 0.0)));

        assertThat(bundle.abstained()).isFalse();
        assertThat(bundle.evidence()).extracting(EvidenceBundle.Evidence::childChunkId).containsExactly(CHILD);
        assertThat(bundle.evidence().get(0).spaceId()).isEqualTo(SPACE);
        assertThat(bundle.evidence().get(0).denseScore()).isGreaterThan(0.99);
    }

    private static String ref(UUID child) {
        return "s3://space/revision/" + child;
    }

    private static final class LocalCatalog implements ChunkCatalog {
        private final ChildMetadata child = new ChildMetadata(CHILD, SPACE, PARENT, REVISION, 0,
                List.of("Retrieval"), 0, 4, 0, 20, 1, null, null, null, null, null, ref(CHILD), HASH);

        @Override
        public Optional<ChildMetadata> findChild(UUID spaceId, UUID childChunkId) {
            return SPACE.equals(spaceId) && CHILD.equals(childChunkId) ? Optional.of(child) : Optional.empty();
        }

        @Override
        public List<ChildMetadata> listChildrenByParent(UUID spaceId, UUID parentChunkId) {
            return SPACE.equals(spaceId) && PARENT.equals(parentChunkId)
                    ? List.of(child).stream().sorted(Comparator.comparingInt(ChildMetadata::chunkIndex)).toList()
                    : List.of();
        }
    }
}
