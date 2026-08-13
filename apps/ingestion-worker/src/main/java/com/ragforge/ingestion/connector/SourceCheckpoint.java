package com.ragforge.ingestion.connector;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SourceCheckpoint(
        UUID spaceId,
        UUID sourceId,
        String sourceVersion,
        Map<String, SourceObjectSnapshot> objects) {

    public SourceCheckpoint {
        if (spaceId == null || sourceId == null || sourceVersion == null || objects == null) {
            throw new ConnectorException(ConnectorFailure.CHECKPOINT_INVALID, "checkpoint is incomplete");
        }
        objects = Map.copyOf(objects);
        objects.forEach((path, snapshot) -> {
            CanonicalPath.require(path);
            if (!spaceId.equals(snapshot.spaceId()) || !sourceId.equals(snapshot.sourceId())
                    || !path.equals(snapshot.canonicalPath())) {
                throw new ConnectorException(ConnectorFailure.CHECKPOINT_INVALID, "checkpoint crosses a source boundary");
            }
        });
    }

    public static SourceCheckpoint empty(UUID spaceId, UUID sourceId) {
        return new SourceCheckpoint(spaceId, sourceId, "", Map.of());
    }
}
