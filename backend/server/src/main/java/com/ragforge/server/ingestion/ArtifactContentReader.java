package com.ragforge.server.ingestion;

import com.ragforge.server.provider.adapter.CancellationToken;

import java.util.UUID;

/**
 * Reads one immutable artifact through a server-owned storage adapter.
 * Implementations must enforce the requested space and content hash before
 * returning bytes and must not expose storage credentials to callers.
 */
@FunctionalInterface
public interface ArtifactContentReader {
    byte[] read(UUID spaceId, String storageUri, String expectedSha256,
                long expectedByteLength, CancellationToken cancellationToken);
}
