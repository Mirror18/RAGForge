package com.ragforge.ingestion.connector;

import java.util.UUID;

public record CheckpointCommitResult(
        UUID changeSetId,
        boolean revisionPersisted,
        boolean artifactPersisted,
        boolean parseReportPersisted,
        boolean activePointerUpdated,
        boolean outboxPersisted) {

    public boolean complete() {
        return revisionPersisted && artifactPersisted && parseReportPersisted
                && activePointerUpdated && outboxPersisted;
    }

    public static CheckpointCommitResult successful(UUID changeSetId) {
        return new CheckpointCommitResult(changeSetId, true, true, true, true, true);
    }
}
