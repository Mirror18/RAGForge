package com.ragforge.server.retrieval;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Application boundary for creating and safely replaying retrieval snapshots. */
@Service
public class RetrievalExecutionSnapshotService {
    private final RetrievalExecutionSnapshotRepository repository;

    public RetrievalExecutionSnapshotService(RetrievalExecutionSnapshotRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Transactional
    public RetrievalExecutionSnapshot saveIfAbsent(RetrievalExecutionSnapshot snapshot) {
        requireSupported(snapshot);
        return repository.saveIfAbsent(snapshot);
    }

    public Optional<RetrievalExecutionSnapshot> find(UUID spaceId, UUID snapshotId) {
        if (spaceId == null || snapshotId == null) {
            return Optional.empty();
        }
        return repository.find(spaceId, snapshotId);
    }

    /**
     * Replay always requires a caller supplied current authorization check. A
     * stored snapshot never acts as a permission grant.
     */
    public RetrievalExecutionSnapshot requireForReplay(UUID spaceId, UUID snapshotId,
                                                        ReplayAuthorizer authorizer) {
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(authorizer, "authorizer");
        RetrievalExecutionSnapshot snapshot = repository.find(spaceId, snapshotId)
                .orElseThrow(() -> new IllegalArgumentException("retrieval snapshot not found"));
        requireSupported(snapshot);
        if (!spaceId.equals(snapshot.spaceId()) || !authorizer.canReplay(spaceId, snapshot)) {
            throw new SecurityException("retrieval snapshot replay is not currently authorized");
        }
        return snapshot;
    }

    private static void requireSupported(RetrievalExecutionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!RetrievalExecutionSnapshot.SCHEMA_VERSION.equals(snapshot.schemaVersion())) {
            throw new IllegalArgumentException("unknown retrieval snapshot version");
        }
    }

    @FunctionalInterface
    public interface ReplayAuthorizer {
        boolean canReplay(UUID spaceId, RetrievalExecutionSnapshot snapshot);
    }
}
