package com.ragforge.server.knowledge.infrastructure;

import java.time.Duration;
import java.util.Optional;
import com.ragforge.server.knowledge.domain.EmbeddingCacheKey;
import com.ragforge.server.knowledge.domain.EmbeddingVector;

/** Storage port for versioned embedding vectors. */
public interface EmbeddingCacheStore {
    Optional<EmbeddingVector> get(EmbeddingCacheKey key);

    void put(EmbeddingCacheKey key, EmbeddingVector vector, Duration ttl);
}
