package com.ragforge.server.retrieval;

import java.util.Objects;
import java.util.UUID;

/** Candidate metadata crossing dense/BM25 boundaries; searchable text is internal only. */
public record RetrievalCandidate(
        UUID spaceId,
        UUID indexVersionId,
        UUID childChunkId,
        UUID documentRevisionId,
        UUID parentChunkId,
        String contentRef,
        String textHash,
        double sourceScore,
        Source source,
        String searchableText) {
    public enum Source {
        DENSE,
        BM25
    }

    public RetrievalCandidate {
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(indexVersionId, "indexVersionId");
        Objects.requireNonNull(childChunkId, "childChunkId");
        Objects.requireNonNull(documentRevisionId, "documentRevisionId");
        Objects.requireNonNull(parentChunkId, "parentChunkId");
        if (contentRef == null || contentRef.isBlank()) {
            throw new IllegalArgumentException("contentRef must not be blank");
        }
        if (textHash == null || !textHash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("textHash must be a SHA-256 hex digest");
        }
        if (!Double.isFinite(sourceScore)) {
            throw new IllegalArgumentException("sourceScore must be finite");
        }
        Objects.requireNonNull(source, "source");
        searchableText = searchableText == null ? "" : searchableText;
    }
}
