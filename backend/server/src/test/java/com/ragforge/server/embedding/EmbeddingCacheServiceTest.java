package com.ragforge.server.embedding;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingCacheServiceTest {
    private static final UUID SPACE_A = UUID.fromString("018f0f70-8e10-7b14-8f1a-111111111111");

    @Test
    void sameNormalizedTextAndProfileHitsWithoutCallingProviderAgain() {
        MapStore store = new MapStore();
        EmbeddingCacheService service = new EmbeddingCacheService(store);
        AtomicInteger calls = new AtomicInteger();

        EmbeddingCacheService.Result first = service.getOrCompute(
                SPACE_A, "  Alpha\r\nBeta  ", "local-embedding-v3", 3, Duration.ofMinutes(5), text -> {
                    calls.incrementAndGet();
                    assertThat(text).isEqualTo("Alpha\nBeta");
                    return java.util.List.of(0.1, 0.2, 0.3);
                });
        EmbeddingCacheService.Result second = service.getOrCompute(
                SPACE_A, "Alpha\nBeta", "local-embedding-v3", 3, text -> {
                    calls.incrementAndGet();
                    return java.util.List.of(9.0, 9.0, 9.0);
                });

        assertThat(first.cacheHit()).isFalse();
        assertThat(second.cacheHit()).isTrue();
        assertThat(second.vector().values()).containsExactly(0.1, 0.2, 0.3);
        assertThat(calls).hasValue(1);
    }

    @Test
    void spaceProfileAndDimensionChangesCannotReuseAnOldEntry() {
        MapStore store = new MapStore();
        EmbeddingCacheService service = new EmbeddingCacheService(store);
        AtomicInteger calls = new AtomicInteger();
        var provider = (java.util.function.Function<String, java.util.List<Double>>) ignored -> {
            calls.incrementAndGet();
            return java.util.List.of(1.0, 2.0);
        };

        service.getOrCompute(SPACE_A, "same text", "profile-v1", 2, provider);
        var differentSpace = service.getOrCompute(UUID.fromString("018f0f70-8e10-7b14-8f1a-222222222222"),
                "same text", "profile-v1", 2, provider);
        var differentProfile = service.getOrCompute(SPACE_A, "same text", "profile-v2", 2, provider);
        var differentDimension = service.getOrCompute(SPACE_A, "same text", "profile-v1", 3,
                ignored -> {
                    calls.incrementAndGet();
                    return java.util.List.of(1.0, 2.0, 3.0);
                });

        assertThat(differentSpace.cacheHit()).isFalse();
        assertThat(differentProfile.cacheHit()).isFalse();
        assertThat(differentDimension.cacheHit()).isFalse();
        assertThat(calls).hasValue(4);
        assertThat(differentProfile.key().redisKey()).contains("space:" + SPACE_A, ":profile:profile-v2", ":dimension:2");
        assertThat(differentDimension.key().redisKey()).contains(":dimension:3");
    }

    private static final class MapStore implements EmbeddingCacheStore {
        private final Map<String, EmbeddingVector> values = new HashMap<>();

        @Override
        public Optional<EmbeddingVector> get(EmbeddingCacheKey key) {
            return Optional.ofNullable(values.get(key.redisKey()));
        }

        @Override
        public void put(EmbeddingCacheKey key, EmbeddingVector vector, Duration ttl) {
            values.put(key.redisKey(), vector);
        }
    }
}
