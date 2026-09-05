package com.ragforge.ingestion.connector;

import java.time.Instant;

public record SourceMetadata(
        String mediaType,
        long byteLength,
        Instant lastModified,
        String sourceVersion,
        String contentHash,
        String provenance) {

    public SourceMetadata {
        if (byteLength < 0 || sourceVersion == null || contentHash == null || provenance == null) {
            throw new ConnectorException(ConnectorFailure.SOURCE_UNAVAILABLE, "source metadata is incomplete");
        }
    }
}
