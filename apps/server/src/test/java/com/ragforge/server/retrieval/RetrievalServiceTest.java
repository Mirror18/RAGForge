package com.ragforge.server.retrieval;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ragforge.server.index.CandidateIndexStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalServiceTest {
    private static final UUID SPACE = UUID.fromString("018f0f70-8e10-7b14-8f1a-111111111111");
    private static final UUID FOREIGN_SPACE = UUID.fromString("018f0f70-8e10-7b14-8f1a-999999999999");
    private static final UUID INDEX = UUID.fromString("018f0f70-8e10-7b14-8f1a-222222222222");
    private static final UUID PROFILE_ID = UUID.fromString("018f0f70-8e10-7b14-8f1a-555555555555");
    private static final UUID PROFILE_VERSION_ID = UUID.fromString("018f0f70-8e10-7b14-8f1a-666666666666");
    private static final UUID REVISION = UUID.fromString("018f0f70-8e10-7b14-8f1a-333333333333");
    private static final UUID PARENT = UUID.fromString("018f0f70-8e10-7b14-8f1a-444444444444");
    private static final UUID CHILD_0 = UUID.fromString("018f0f70-8e10-7b14-8f1a-000000000000");
    private static final UUID CHILD_1 = UUID.fromString("018f0f70-8e10-7b14-8f1a-111111111112");
    private static final UUID CHILD_2 = UUID.fromString("018f0f70-8e10-7b14-8f1a-222222222223");
    private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void retrieveFusesCandidatesExpandsContextAndReturnsProvenanceOnly() {
        FakeDenseStore dense = new FakeDenseStore(List.of(new CandidateIndexStore.CandidateHit(
                CHILD_1, 0.95, SPACE, INDEX, REVISION, PARENT, ref(CHILD_1), HASH)));
        InMemoryBm25CandidateStore bm25 = new InMemoryBm25CandidateStore();
        bm25.upsert(new Bm25CandidateStore.Document(SPACE, INDEX, CHILD_1, REVISION, PARENT, ref(CHILD_1), HASH,
                "retrieval space design"));
        RetrievalService service = new RetrievalService(dense, bm25, new FakeCatalog(), new LexicalReranker());

        EvidenceBundle bundle = service.retrieve(new RetrievalService.Request(SPACE, INDEX,
                profile(ExpansionMode.PARENT_AND_NEIGHBOR, 1, 1, 100), " retrieval\r\nspace ", List.of(0.1, 0.2)));

        assertThat(bundle.originalQuery()).isEqualTo(" retrieval\r\nspace ");
        assertThat(bundle.normalizedQuery()).isEqualTo("retrieval space");
        assertThat(bundle.abstained()).isFalse();
        assertThat(bundle.evidence()).extracting(EvidenceBundle.Evidence::childChunkId)
                .containsExactly(CHILD_1, CHILD_0, CHILD_2);
        assertThat(bundle.evidence()).allSatisfy(evidence -> {
            assertThat(evidence.spaceId()).isEqualTo(SPACE);
            assertThat(evidence.indexVersionId()).isEqualTo(INDEX);
            assertThat(evidence.contentRef()).startsWith("s3://space/");
            assertThat(evidence.inclusionReason()).isNotBlank();
        });

        UUID evidenceId = bundle.evidence().get(0).evidenceId();
        CitationValidator.requireBundleCitations(bundle, SPACE, Set.of(evidenceId));
        assertThatThrownBy(() -> CitationValidator.requireBundleCitations(bundle, FOREIGN_SPACE, Set.of(evidenceId)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CitationValidator.requireBundleCitations(bundle, SPACE, Set.of(UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noVerifiedCandidateProducesExplicitAbstention() {
        RetrievalService service = new RetrievalService(new FakeDenseStore(List.of()),
                new InMemoryBm25CandidateStore(), new FakeCatalog(), new LexicalReranker());

        EvidenceBundle bundle = service.retrieve(new RetrievalService.Request(SPACE, INDEX,
                profile(ExpansionMode.NONE, 0, 0, 100), "unknown", List.of(0.1)));

        assertThat(bundle.abstained()).isTrue();
        assertThat(bundle.abstentionReason()).isEqualTo("NO_VERIFIED_EVIDENCE");
        assertThat(bundle.evidence()).isEmpty();
    }

    @Test
    void requestCannotUseProfileFromAnotherSpace() {
        assertThatThrownBy(() -> new RetrievalService.Request(SPACE, INDEX,
                new RetrievalProfileRepository.RetrievalProfileVersion(PROFILE_VERSION_ID, FOREIGN_SPACE, PROFILE_ID, 1,
                        5, 5, 60, 0.5, 0.5, 5, 3, ExpansionMode.NONE, 0, 0, 100, Instant.now()),
                "query", List.of(0.1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("space boundary");
    }

    @Test
    void requestRejectsNonFiniteQueryVector() {
        assertThatThrownBy(() -> new RetrievalService.Request(SPACE, INDEX, profile(ExpansionMode.NONE, 0, 0, 100),
                "query", List.of(Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void staleCandidateProvenanceIsExcludedAndCausesAbstention() {
        FakeDenseStore dense = new FakeDenseStore(List.of(new CandidateIndexStore.CandidateHit(
                CHILD_1, 0.95, SPACE, INDEX, REVISION, PARENT, "s3://space/revision/stale", HASH)));
        RetrievalService service = new RetrievalService(dense, new InMemoryBm25CandidateStore(),
                new FakeCatalog(), new LexicalReranker());

        EvidenceBundle bundle = service.retrieve(new RetrievalService.Request(SPACE, INDEX,
                profile(ExpansionMode.NONE, 0, 0, 100), "stale", List.of(0.1)));

        assertThat(bundle.abstained()).isTrue();
        assertThat(bundle.abstentionReason()).isEqualTo("NO_VERIFIED_EVIDENCE");
    }

    private static RetrievalProfileRepository.RetrievalProfileVersion profile(ExpansionMode mode,
            int maxParents, int maxNeighbors, int maxTokens) {
        return new RetrievalProfileRepository.RetrievalProfileVersion(PROFILE_VERSION_ID, SPACE, PROFILE_ID, 1,
                5, 5, 60, 0.5, 0.5, 5, 3, mode, maxParents, maxNeighbors, maxTokens,
                Instant.parse("2026-08-21T00:00:00Z"));
    }

    private static String ref(UUID child) {
        return "s3://space/revision/" + child;
    }

    private static ChunkCatalog.ChildMetadata child(UUID id, int index) {
        return new ChunkCatalog.ChildMetadata(id, SPACE, PARENT, REVISION, index, List.of("Design"), index * 4,
                index * 4 + 4, index * 20, index * 20 + 20, 1, null, null, null, null, null, ref(id), HASH);
    }

    private static final class FakeCatalog implements ChunkCatalog {
        private final Map<UUID, ChildMetadata> children = Map.of(
                CHILD_0, child(CHILD_0, 0), CHILD_1, child(CHILD_1, 1), CHILD_2, child(CHILD_2, 2));

        @Override
        public Optional<ChildMetadata> findChild(UUID spaceId, UUID childChunkId) {
            ChildMetadata value = children.get(childChunkId);
            return value != null && value.spaceId().equals(spaceId) ? Optional.of(value) : Optional.empty();
        }

        @Override
        public List<ChildMetadata> listChildrenByParent(UUID spaceId, UUID parentChunkId) {
            return children.values().stream()
                    .filter(value -> value.spaceId().equals(spaceId) && value.parentChunkId().equals(parentChunkId))
                    .sorted(Comparator.comparingInt(ChildMetadata::chunkIndex)).toList();
        }
    }

    private static final class FakeDenseStore implements CandidateIndexStore {
        private final List<CandidateHit> hits;

        private FakeDenseStore(List<CandidateHit> hits) {
            this.hits = List.copyOf(hits);
        }

        @Override
        public void createCollection(String collectionName, int dimension) {
        }

        @Override
        public void upsert(String collectionName, List<CandidatePoint> points) {
        }

        @Override
        public ValidationResult validate(String collectionName, UUID spaceId, UUID indexVersionId,
                int expectedPointCount, int expectedDimension, List<CandidatePoint> samples) {
            return new ValidationResult(hits.size(), expectedDimension, 0, true, true);
        }

        @Override
        public List<CandidateHit> search(String collectionName, UUID spaceId, UUID indexVersionId,
                List<Double> queryVector, int limit) {
            assertThat(collectionName).isEqualTo("ragforge_" + SPACE.toString().replace("-", "") + "_"
                    + INDEX.toString().replace("-", ""));
            assertThat(spaceId).isEqualTo(SPACE);
            assertThat(indexVersionId).isEqualTo(INDEX);
            return hits.stream().limit(limit).toList();
        }

        @Override
        public void deleteCollection(String collectionName) {
        }
    }
}
