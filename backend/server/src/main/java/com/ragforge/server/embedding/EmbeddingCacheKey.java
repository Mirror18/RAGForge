package com.ragforge.server.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, space-scoped identity for an embedding cache entry.
 * The normalized text itself never appears in the key or in logs.
 */
public record EmbeddingCacheKey(
        UUID spaceId,
        String normalizedTextHash,
        String modelProfileVersion,
        int dimension) {

    public EmbeddingCacheKey {
        Objects.requireNonNull(spaceId, "spaceId");
        normalizedTextHash = requireSha256(normalizedTextHash, "normalizedTextHash");
        modelProfileVersion = requireProfile(modelProfileVersion);
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive");
        }
    }

    public static EmbeddingCacheKey fromNormalizedText(
            UUID spaceId, String normalizedText, String modelProfileVersion, int dimension) {
        Objects.requireNonNull(normalizedText, "normalizedText");
        return new EmbeddingCacheKey(spaceId, sha256(normalizedText), modelProfileVersion, dimension);
    }

    /** Redis key contains only non-sensitive identity components. */
    public String redisKey() {
        return "ragforge:embedding:v1:space:" + spaceId
                + ":text:" + normalizedTextHash
                + ":profile:" + modelProfileVersion
                + ":dimension:" + dimension;
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requireSha256(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(field + " must be a SHA-256 hex digest");
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private static String requireProfile(String value) {
        Objects.requireNonNull(value, "modelProfileVersion");
        if (!value.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("modelProfileVersion contains unsupported cache-key characters");
        }
        return value;
    }
}
