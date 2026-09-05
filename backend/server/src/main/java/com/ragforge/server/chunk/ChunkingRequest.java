package com.ragforge.server.chunk;

import java.util.Objects;
import java.util.UUID;

/**
 * Versioned, space-scoped input to the deterministic chunking engine.
 *
 * <p>The engine does not infer tenant or revision context from text. Callers
 * must provide both identifiers and a logical content reference explicitly so
 * a candidate can be persisted without losing provenance.</p>
 */
public record ChunkingRequest(
        UUID spaceId,
        UUID documentRevisionId,
        int versionNo,
        String contentRefPrefix,
        String normalizedText) {

    public ChunkingRequest {
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(documentRevisionId, "documentRevisionId");
        if (versionNo <= 0) {
            throw new IllegalArgumentException("versionNo must be positive");
        }
        if (contentRefPrefix == null || contentRefPrefix.isBlank()) {
            throw new IllegalArgumentException("contentRefPrefix must not be blank");
        }
        normalizedText = normalizedText == null ? "" : normalizedText;
    }
}
