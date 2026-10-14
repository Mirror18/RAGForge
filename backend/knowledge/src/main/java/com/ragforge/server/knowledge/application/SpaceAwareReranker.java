package com.ragforge.server.knowledge.application;

import java.util.List;
import java.util.UUID;

/** Optional space-aware extension used by the production retrieval pipeline. */
public interface SpaceAwareReranker {
    List<Reranker.Result> rerank(UUID spaceId, String normalizedQuery,
                                 List<RrfMerger.MergedCandidate> candidates, int limit);
}
