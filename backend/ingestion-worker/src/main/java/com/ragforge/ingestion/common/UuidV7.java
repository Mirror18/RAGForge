package com.ragforge.ingestion.common;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/** Worker-local UUIDv7 generator; worker IDs must remain valid contract identifiers. */
public final class UuidV7 {
    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() { }

    public static UUID random() {
        long timestamp = Clock.systemUTC().millis() & 0x0000FFFFFFFFFFFFL;
        long mostSignificant = (timestamp << 16) | 0x7000L | (RANDOM.nextLong() & 0x0FFFL);
        long leastSignificant = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(mostSignificant, leastSignificant);
    }
}
