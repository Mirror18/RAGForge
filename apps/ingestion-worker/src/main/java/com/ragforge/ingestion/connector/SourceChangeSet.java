package com.ragforge.ingestion.connector;

import java.util.List;
import java.util.UUID;

public record SourceChangeSet(
        UUID changeSetId,
        UUID spaceId,
        UUID sourceId,
        String previousSourceVersion,
        String sourceVersion,
        List<SourceChange> changes) {

    public SourceChangeSet {
        if (changeSetId == null || spaceId == null || sourceId == null
                || previousSourceVersion == null || sourceVersion == null || changes == null) {
            throw new ConnectorException(ConnectorFailure.SOURCE_UNAVAILABLE, "change set is incomplete");
        }
        changes = List.copyOf(changes);
        changes.forEach(change -> {
            if (!spaceId.equals(change.spaceId()) || !sourceId.equals(change.sourceId())) {
                throw new ConnectorException(ConnectorFailure.CHECKPOINT_INVALID, "change set crosses a space or source boundary");
            }
        });
    }
}
