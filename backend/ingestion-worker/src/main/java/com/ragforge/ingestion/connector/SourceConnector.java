package com.ragforge.ingestion.connector;

import java.io.IOException;

public interface SourceConnector {
    SourceChangeSet discover(SourceCheckpoint checkpoint, DiscoveryRules rules);

    FetchedContent fetch(SourceReference sourceRef, String expectedVersion) throws IOException;

    SourceCheckpoint commitCheckpoint(SourceChangeSet changeSet, CheckpointCommitResult result);

    SourceCheckpoint currentCheckpoint();
}
