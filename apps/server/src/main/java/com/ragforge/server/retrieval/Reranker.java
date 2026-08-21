package com.ragforge.server.retrieval;

import java.util.List;

/** Provider-neutral rerank port; implementations must be deterministic for an evaluation config. */
public interface Reranker {
    record Result(RrfMerger.MergedCandidate candidate, double score, String reason) {
    }

    List<Result> rerank(String normalizedQuery, List<RrfMerger.MergedCandidate> candidates, int limit);
}
