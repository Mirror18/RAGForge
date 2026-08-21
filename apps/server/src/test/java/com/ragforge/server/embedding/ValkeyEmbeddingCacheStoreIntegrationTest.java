package com.ragforge.server.embedding;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Valkey proof for embedding cache serialization, TTL and key isolation. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ValkeyEmbeddingCacheStoreIntegrationTest {
    private static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.0.1-alpine")
            .withExposedPorts(6379);
    private static final UUID SPACE_A = UUID.fromString("018f0f70-8e10-7b14-8f1a-111111111111");

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private ValkeyEmbeddingCacheStore store;

    @BeforeAll
    void start() {
        VALKEY.start();
        connectionFactory = new LettuceConnectionFactory(VALKEY.getHost(), VALKEY.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        store = new ValkeyEmbeddingCacheStore(redis);
    }

    @AfterAll
    void stop() {
        if (redis != null) {
            redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        VALKEY.stop();
    }

    @Test
    void vectorRoundTripsAndProfileChangeMisses() {
        EmbeddingCacheKey key = EmbeddingCacheKey.fromNormalizedText(SPACE_A, "normalized text", "profile-v1", 3);
        EmbeddingVector vector = new EmbeddingVector(3, List.of(0.1, -0.2, 0.3));

        store.put(key, vector, Duration.ofMinutes(5));

        assertThat(store.get(key)).contains(vector);
        assertThat(redis.getExpire(key.redisKey())).isPositive();
        assertThat(store.get(EmbeddingCacheKey.fromNormalizedText(
                SPACE_A, "normalized text", "profile-v2", 3))).isEmpty();
        assertThat(redis.keys("*normalized text*")).isEmpty();
    }

    @Test
    void corruptEntryIsDroppedAsCacheMiss() {
        EmbeddingCacheKey key = EmbeddingCacheKey.fromNormalizedText(SPACE_A, "corrupt", "profile-v1", 2);
        redis.opsForValue().set(key.redisKey(), "not-a-vector");

        Optional<EmbeddingVector> result = store.get(key);

        assertThat(result).isEmpty();
        assertThat(redis.hasKey(key.redisKey())).isFalse();
    }
}
