package com.ragforge.server.retrieval;

import java.util.List;
import java.util.UUID;

/** Space/index scoped lexical candidate port. */
public interface Bm25CandidateStore {
    record Document(UUID spaceId, UUID indexVersionId, UUID childChunkId, UUID documentRevisionId,
            UUID parentChunkId, String contentRef, String textHash, String text) {
        public Document {
            if (spaceId == null || indexVersionId == null || childChunkId == null
                    || documentRevisionId == null || parentChunkId == null) {
                throw new NullPointerException("BM25 document identity must not be null");
            }
            if (contentRef == null || contentRef.isBlank() || text == null) {
                throw new IllegalArgumentException("BM25 document contentRef and text are required");
            }
            if (textHash == null || !textHash.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("textHash must be a SHA-256 hex digest");
            }
        }
    }

    void upsert(Document document);

    List<RetrievalCandidate> search(UUID spaceId, UUID indexVersionId, String query, int limit);
}
