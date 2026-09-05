package com.ragforge.ingestion.connector;

import java.util.UUID;

public record SourceReference(
        UUID spaceId,
        UUID sourceId,
        UUID stableSourceObjectId,
        String canonicalPath,
        String sourceVersion,
        String contentHash) {

    public SourceReference {
        if (spaceId == null || sourceId == null || stableSourceObjectId == null
                || sourceVersion == null || contentHash == null) {
            throw new ConnectorException(ConnectorFailure.PATH_INVALID, "source reference is incomplete");
        }
        CanonicalPath.require(canonicalPath);
    }
}
