package com.ragforge.ingestion.connector;

import java.util.UUID;

public record SourceChange(
        UUID spaceId,
        UUID sourceId,
        UUID stableSourceObjectId,
        ChangeKind kind,
        String canonicalPath,
        String previousCanonicalPath,
        String sourceVersion,
        String contentHash,
        long byteLength,
        String mediaType,
        String provenance) {

    public SourceChange {
        if (spaceId == null || sourceId == null || stableSourceObjectId == null || kind == null
                || canonicalPath == null || sourceVersion == null || contentHash == null
                || byteLength < 0 || provenance == null) {
            throw new ConnectorException(ConnectorFailure.SOURCE_UNAVAILABLE, "source change is incomplete");
        }
        CanonicalPath.require(canonicalPath);
        if (previousCanonicalPath != null) {
            CanonicalPath.require(previousCanonicalPath);
        }
        if (kind == ChangeKind.MOVE && previousCanonicalPath == null) {
            throw new ConnectorException(ConnectorFailure.SOURCE_UNAVAILABLE, "move must have a previous path");
        }
    }

    public SourceReference reference() {
        return new SourceReference(spaceId, sourceId, stableSourceObjectId, canonicalPath,
                sourceVersion, contentHash);
    }
}
