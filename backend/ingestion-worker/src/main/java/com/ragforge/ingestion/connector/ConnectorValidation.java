package com.ragforge.ingestion.connector;

import java.util.UUID;

final class ConnectorValidation {
    private ConnectorValidation() { }

    static void reference(UUID spaceId, UUID sourceId, SourceReference reference) {
        if (!spaceId.equals(reference.spaceId()) || !sourceId.equals(reference.sourceId())) {
            throw new ConnectorException(ConnectorFailure.CHECKPOINT_INVALID, "source reference crosses a space or source boundary");
        }
    }

    static void checkpoint(UUID spaceId, UUID sourceId, SourceCheckpoint checkpoint) {
        if (checkpoint == null) {
            throw new ConnectorException(ConnectorFailure.CHECKPOINT_INVALID, "checkpoint is required");
        }
        if (!spaceId.equals(checkpoint.spaceId()) || !sourceId.equals(checkpoint.sourceId())) {
            throw new ConnectorException(ConnectorFailure.CHECKPOINT_INVALID, "checkpoint crosses a space or source boundary");
        }
    }
}
