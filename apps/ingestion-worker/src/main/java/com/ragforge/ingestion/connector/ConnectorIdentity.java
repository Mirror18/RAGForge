package com.ragforge.ingestion.connector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

final class ConnectorIdentity {
    private ConnectorIdentity() { }

    static UUID stableObjectId(UUID spaceId, UUID sourceId, String canonicalPath) {
        return UUID.nameUUIDFromBytes((spaceId + "\n" + sourceId + "\n" + canonicalPath)
                .getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the runtime", exception);
        }
    }
}
