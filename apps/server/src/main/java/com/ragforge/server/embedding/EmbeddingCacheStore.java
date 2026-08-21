package com.ragforge.server.embedding;

import java.time.Duration;
import java.util.Optional;

/** Storage port for versioned embedding vectors. */
public interface EmbeddingCacheStore {
    Optional<EmbeddingVector> get(EmbeddingCacheKey key);

    void put(EmbeddingCacheKey key, EmbeddingVector vector, Duration ttl);
}
