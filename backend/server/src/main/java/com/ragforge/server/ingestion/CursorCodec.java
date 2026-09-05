package com.ragforge.server.ingestion;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Opaque cursor codec used by the forward-only resource pages. */
public final class CursorCodec {
    private CursorCodec() {
    }

    public record Position(Instant sortTime, UUID id) {
    }

    public static String encode(Position position) {
        String value = position.sortTime().toEpochMilli() + "|" + position.id();
        return "v1." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public static Position decode(String cursor) {
        if (cursor == null || !cursor.startsWith("v1.")) {
            throw new IllegalArgumentException("cursor is invalid");
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor.substring(3)), StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("cursor is invalid");
            }
            return new Position(Instant.ofEpochMilli(Long.parseLong(parts[0])), UUID.fromString(parts[1]));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("cursor is invalid", exception);
        }
    }
}
