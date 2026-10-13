package com.ragforge.server.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

final class BootstrapTokenSupport {
    private static final SecureRandom RANDOM = new SecureRandom();

    private BootstrapTokenSupport() {
    }

    static String generatePlaintext() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("Bootstrap token must not be blank");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the runtime", exception);
        }
    }

    static boolean matches(String plaintext, String expectedHash) {
        if (expectedHash == null || expectedHash.length() != 64) {
            return false;
        }
        if (plaintext == null || plaintext.length() < 32 || plaintext.length() > 512) {
            return false;
        }
        return MessageDigest.isEqual(hash(plaintext).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
