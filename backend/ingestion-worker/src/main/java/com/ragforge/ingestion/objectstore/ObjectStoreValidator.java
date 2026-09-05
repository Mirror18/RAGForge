package com.ragforge.ingestion.objectstore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class ObjectStoreValidator {
    private ObjectStoreValidator() { }

    static void validate(ObjectKey key, String mediaType, byte[] content, ObjectStoreLimits limits) {
        if (mediaType == null || !mediaType.matches("^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$")) {
            throw new ObjectStoreException(ObjectStoreFailure.MIME_NOT_ALLOWED, "media type is invalid");
        }
        if (!limits.allowedMediaTypes().contains(mediaType.toLowerCase())) {
            throw new ObjectStoreException(ObjectStoreFailure.MIME_NOT_ALLOWED, "media type is not allowed");
        }
        if (content == null || content.length > limits.maxBytes()) {
            throw new ObjectStoreException(ObjectStoreFailure.CONTENT_TOO_LARGE, "object exceeds configured size limit");
        }
        String actual = sha256(content);
        if (!actual.equalsIgnoreCase(key.contentHash())) {
            throw new ObjectStoreException(ObjectStoreFailure.CHECKSUM_MISMATCH, "object checksum does not match content-addressed key");
        }
    }

    static String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the runtime", exception);
        }
    }
}
