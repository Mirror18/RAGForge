package com.ragforge.server.embedding;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingCacheKeyTest {
    @Test
    void keyContainsOnlyVersionedNonSensitiveIdentity() {
        UUID space = UUID.fromString("018f0f70-8e10-7b14-8f1a-111111111111");
        EmbeddingCacheKey key = EmbeddingCacheKey.fromNormalizedText(space, "private source text", "profile-v1", 768);

        assertThat(key.normalizedTextHash()).hasSize(64);
        assertThat(key.redisKey()).contains(space.toString(), key.normalizedTextHash(), "profile-v1", "768");
        assertThat(key.redisKey()).doesNotContain("private source text");
    }

    @Test
    void invalidDimensionsAndProfilesAreRejected() {
        assertThatThrownBy(() -> EmbeddingCacheKey.fromNormalizedText(
                UUID.randomUUID(), "text", "profile/with/path", 768))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmbeddingCacheKey.fromNormalizedText(
                UUID.randomUUID(), "text", "profile-v1", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
