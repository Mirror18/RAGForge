package com.ragforge.server.retrieval;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryBm25CandidateStoreTest {
    private static final UUID SPACE = UUID.fromString("018f0f70-8e10-7b14-8f1a-111111111111");
    private static final UUID FOREIGN_SPACE = UUID.fromString("018f0f70-8e10-7b14-8f1a-999999999999");
    private static final UUID INDEX = UUID.fromString("018f0f70-8e10-7b14-8f1a-222222222222");
    private static final UUID OTHER_INDEX = UUID.fromString("018f0f70-8e10-7b14-8f1a-888888888888");
    private static final UUID REVISION = UUID.fromString("018f0f70-8e10-7b14-8f1a-333333333333");
    private static final UUID PARENT = UUID.fromString("018f0f70-8e10-7b14-8f1a-444444444444");
    private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void searchIsScopedAndOrdersByBm25Score() {
        InMemoryBm25CandidateStore store = new InMemoryBm25CandidateStore();
        UUID best = UUID.fromString("018f0f70-8e10-7b14-8f1a-aaaaaaaaaaaa");
        UUID weaker = UUID.fromString("018f0f70-8e10-7b14-8f1a-bbbbbbbbbbbb");
        store.upsert(document(SPACE, INDEX, best, "space retrieval retrieval"));
        store.upsert(document(SPACE, INDEX, weaker, "space only"));
        store.upsert(document(FOREIGN_SPACE, INDEX, UUID.randomUUID(), "space retrieval retrieval"));
        store.upsert(document(SPACE, OTHER_INDEX, UUID.randomUUID(), "space retrieval retrieval"));

        List<RetrievalCandidate> results = store.search(SPACE, INDEX, "space retrieval", 10);

        assertThat(results).extracting(RetrievalCandidate::childChunkId).containsExactly(best, weaker);
        assertThat(results).allSatisfy(result -> {
            assertThat(result.spaceId()).isEqualTo(SPACE);
            assertThat(result.indexVersionId()).isEqualTo(INDEX);
            assertThat(result.source()).isEqualTo(RetrievalCandidate.Source.BM25);
        });
    }

    @Test
    void missingScopeAndInvalidLimitAreRejected() {
        InMemoryBm25CandidateStore store = new InMemoryBm25CandidateStore();

        assertThatThrownBy(() -> store.search(null, INDEX, "query", 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.search(SPACE, INDEX, "query", 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shortChineseTermsRemainSearchable() {
        InMemoryBm25CandidateStore store = new InMemoryBm25CandidateStore();
        UUID child = UUID.fromString("018f0f70-8e10-7b14-8f1a-cccccccccccc");
        store.upsert(document(SPACE, INDEX, child, "空间检索隔离"));

        assertThat(store.search(SPACE, INDEX, "检索", 10))
                .extracting(RetrievalCandidate::childChunkId).containsExactly(child);
    }

    private static Bm25CandidateStore.Document document(UUID space, UUID index, UUID child, String text) {
        return new Bm25CandidateStore.Document(space, index, child, REVISION, PARENT,
                "s3://space/revision/" + child, HASH, text);
    }
}
