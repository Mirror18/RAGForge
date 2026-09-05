package com.ragforge.server.embedding;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Valkey/Redis implementation that stores only vector values, never source text. */
@Component
public final class ValkeyEmbeddingCacheStore implements EmbeddingCacheStore {
    private final StringRedisTemplate redis;

    public ValkeyEmbeddingCacheStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<EmbeddingVector> get(EmbeddingCacheKey key) {
        String encoded = redis.opsForValue().get(key.redisKey());
        if (encoded == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(decode(encoded));
        } catch (RuntimeException exception) {
            // A corrupt entry is a cache miss; source data remains authoritative.
            redis.delete(key.redisKey());
            return Optional.empty();
        }
    }

    @Override
    public void put(EmbeddingCacheKey key, EmbeddingVector vector, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("embedding cache ttl must be positive");
        }
        if (vector == null) {
            throw new NullPointerException("vector");
        }
        redis.opsForValue().set(key.redisKey(), encode(vector), ttl);
    }

    private static String encode(EmbeddingVector vector) {
        return vector.dimension() + ":" + vector.values().stream()
                .map(value -> Double.toString(value))
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
    }

    private static EmbeddingVector decode(String encoded) {
        int separator = encoded.indexOf(':');
        if (separator <= 0 || separator == encoded.length() - 1) {
            throw new IllegalArgumentException("invalid embedding cache value");
        }
        int dimension = Integer.parseInt(encoded.substring(0, separator));
        var values = Arrays.stream(encoded.substring(separator + 1).split(",", -1))
                .map(Double::parseDouble)
                .toList();
        return new EmbeddingVector(dimension, values);
    }
}
