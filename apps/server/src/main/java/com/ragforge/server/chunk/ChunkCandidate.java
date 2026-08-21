package com.ragforge.server.chunk;

import java.util.List;
import java.util.UUID;

/**
 * Deterministic chunking output candidate. Text is kept so the caller can
 * embed children and persist hashes/content refs; the persistence layer owns
 * object storage placement and stable IDs.
 */
public record ChunkCandidate(
        UUID spaceId,
        UUID documentRevisionId,
        int versionNo,
        String contentRef,
        Kind kind,
        UUID id,
        UUID parentId,
        int parentIndex,
        int chunkIndex,
        List<String> headingPath,
        int tokenStart,
        int tokenEnd,
        int charStart,
        int charEnd,
        int startLine,
        int endLine,
        String text,
        String textHash) {

    public enum Kind { PARENT, CHILD }

    /** Compatibility constructor for pure tests that do not provide provenance. */
    public ChunkCandidate(Kind kind, UUID id, UUID parentId, int parentIndex, int chunkIndex,
            List<String> headingPath, int tokenStart, int tokenEnd, int charStart, int charEnd,
            int startLine, int endLine, String text, String textHash) {
        this(UUID.fromString("00000000-0000-7000-8000-000000000001"),
                UUID.fromString("00000000-0000-7000-8000-000000000002"), 1,
                "chunk://legacy/" + kind.name().toLowerCase() + "/" + chunkIndex,
                kind, id, parentId, parentIndex, chunkIndex, headingPath, tokenStart, tokenEnd,
                charStart, charEnd, startLine, endLine, text, textHash);
    }
}
