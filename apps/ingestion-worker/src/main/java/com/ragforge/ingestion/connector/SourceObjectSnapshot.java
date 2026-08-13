package com.ragforge.ingestion.connector;

import java.util.UUID;

public record SourceObjectSnapshot(
        UUID spaceId,
        UUID sourceId,
        UUID stableSourceObjectId,
        String canonicalPath,
        String sourceVersion,
        String contentHash,
        long byteLength,
        String mediaType,
        String provenance) {

    public SourceObjectSnapshot {
        if (spaceId == null || sourceId == null || stableSourceObjectId == null
                || canonicalPath == null || sourceVersion == null || contentHash == null
                || byteLength < 0 || provenance == null) {
            throw new ConnectorException(ConnectorFailure.CHECKPOINT_INVALID, "source snapshot is incomplete");
        }
        CanonicalPath.require(canonicalPath);
    }
}
