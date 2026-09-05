package com.ragforge.server.retrieval;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only, space-scoped chunk metadata required to build evidence anchors. */
public interface ChunkCatalog {
    record ChildMetadata(UUID id, UUID spaceId, UUID parentChunkId, UUID documentRevisionId, int chunkIndex,
            List<String> headingPath, int tokenStart, int tokenEnd, int charStart, int charEnd,
            Integer pageNumber, String sheet, Integer slideNumber, Integer lineStart, Integer lineEnd,
            String tableCell, String contentRef, String textHash) {
        public ChildMetadata {
            if (id == null || spaceId == null || parentChunkId == null || documentRevisionId == null) {
                throw new NullPointerException("child metadata identity must not be null");
            }
            if (contentRef == null || contentRef.isBlank() || textHash == null
                    || !textHash.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("child metadata provenance is invalid");
            }
            headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
        }
    }

    Optional<ChildMetadata> findChild(UUID spaceId, UUID childChunkId);

    List<ChildMetadata> listChildrenByParent(UUID spaceId, UUID parentChunkId);
}
