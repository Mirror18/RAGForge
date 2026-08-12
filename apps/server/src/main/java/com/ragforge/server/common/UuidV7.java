package com.ragforge.server.common;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/** Application-owned UUIDv7 generator; IDs are never database-generated. */
public final class UuidV7 {
    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID random() {
        return random(Clock.systemUTC().millis());
    }

    public static boolean isUuidV7(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            UUID uuid = UUID.fromString(value);
            return uuid.toString().equalsIgnoreCase(value)
                    && uuid.version() == 7
                    && uuid.variant() == 2;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static UUID random(long epochMillis) {
        long timestamp = epochMillis & 0x0000FFFFFFFFFFFFL;
        long mostSignificant = (timestamp << 16)
                | 0x7000L
                | (RANDOM.nextLong() & 0x0FFFL);
        long leastSignificant = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL)
                | 0x8000000000000000L;
        return new UUID(mostSignificant, leastSignificant);
    }
}
