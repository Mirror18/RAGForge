package com.ragforge.server.embedding;

import java.text.Normalizer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.springframework.stereotype.Service;

/**
 * Space-scoped embedding cache facade. A second lookup under a per-key lock
 * prevents duplicate provider calls when concurrent ingestion workers share a
 * JVM. The cache key remains safe to distribute to Valkey.
 */
@Service
public final class EmbeddingCacheService {
    public record Result(EmbeddingCacheKey key, EmbeddingVector vector, boolean cacheHit) {
    }

    private static final Duration DEFAULT_TTL = Duration.ofDays(30);

    private final EmbeddingCacheStore store;
    private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();

    public EmbeddingCacheService(EmbeddingCacheStore store) {
        this.store = store;
    }

    public Result getOrCompute(
            java.util.UUID spaceId,
            String text,
            String modelProfileVersion,
            int dimension,
            Function<String, java.util.List<Double>> provider) {
        return getOrCompute(spaceId, text, modelProfileVersion, dimension, DEFAULT_TTL, provider);
    }

    public Result getOrCompute(
            java.util.UUID spaceId,
            String text,
            String modelProfileVersion,
            int dimension,
            Duration ttl,
            Function<String, java.util.List<Double>> provider) {
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(ttl, "ttl");
        Objects.requireNonNull(provider, "provider");
        String normalized = normalize(text);
        EmbeddingCacheKey key = EmbeddingCacheKey.fromNormalizedText(
                spaceId, normalized, modelProfileVersion, dimension);
        var cached = store.get(key);
        if (cached.isPresent()) {
            return new Result(key, cached.get(), true);
        }

        Object lock = keyLocks.computeIfAbsent(key.redisKey(), ignored -> new Object());
        try {
            synchronized (lock) {
                cached = store.get(key);
                if (cached.isPresent()) {
                    return new Result(key, cached.get(), true);
                }
                EmbeddingVector computed = new EmbeddingVector(dimension, provider.apply(normalized));
                store.put(key, computed, ttl);
                return new Result(key, computed, false);
            }
        } finally {
            keyLocks.remove(key.redisKey(), lock);
        }
    }

    /** Normalization is deterministic and does not retain the source text. */
    public static String normalize(String text) {
        return Normalizer.normalize(text.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC)
                .lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }
}
