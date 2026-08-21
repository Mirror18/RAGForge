package com.ragforge.server.retrieval;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RrfMergerTest {
    private static final UUID SPACE = UUID.fromString("018f0f70-8e10-7b14-8f1a-111111111111");
    private static final UUID FOREIGN_SPACE = UUID.fromString("018f0f70-8e10-7b14-8f1a-999999999999");
    private static final UUID INDEX = UUID.fromString("018f0f70-8e10-7b14-8f1a-222222222222");
    private static final UUID REVISION = UUID.fromString("018f0f70-8e10-7b14-8f1a-333333333333");
    private static final UUID PARENT = UUID.fromString("018f0f70-8e10-7b14-8f1a-444444444444");
    private static final UUID CHILD_A = UUID.fromString("018f0f70-8e10-7b14-8f1a-aaaaaaaaaaaa");
    private static final UUID CHILD_B = UUID.fromString("018f0f70-8e10-7b14-8f1a-bbbbbbbbbbbb");
    private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void weightedRrfDeduplicatesIntersectionAndKeepsRanks() {
        List<RrfMerger.MergedCandidate> merged = RrfMerger.merge(SPACE, INDEX,
                List.of(candidate(CHILD_A, 0.9, RetrievalCandidate.Source.DENSE, ""),
                        candidate(CHILD_B, 0.8, RetrievalCandidate.Source.DENSE, "")),
                List.of(candidate(CHILD_A, 4.0, RetrievalCandidate.Source.BM25, "matching text")),
                60, 0.5, 0.5);

        assertThat(merged).hasSize(2);
        assertThat(merged.get(0).childChunkId()).isEqualTo(CHILD_A);
        assertThat(merged.get(0).denseRank()).isEqualTo(1);
        assertThat(merged.get(0).bm25Rank()).isEqualTo(1);
        assertThat(merged.get(0).rrfScore()).isEqualTo(1.0 / 61.0);
        assertThat(merged.get(1).bm25Rank()).isZero();
        assertThat(merged.get(0).searchableText()).isEqualTo("matching text");
    }

    @Test
    void foreignCandidateIsRejectedBeforeFusion() {
        RetrievalCandidate foreign = new RetrievalCandidate(FOREIGN_SPACE, INDEX, CHILD_A, REVISION, PARENT,
                "s3://foreign/child", HASH, 1.0, RetrievalCandidate.Source.DENSE, "");

        assertThatThrownBy(() -> RrfMerger.merge(SPACE, INDEX, List.of(foreign), List.of(), 60, 0.5, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope");
    }

    private static RetrievalCandidate candidate(UUID child, double score, RetrievalCandidate.Source source,
            String searchableText) {
        return new RetrievalCandidate(SPACE, INDEX, child, REVISION, PARENT,
                "s3://space/revision/" + child, HASH, score, source, searchableText);
    }
}
