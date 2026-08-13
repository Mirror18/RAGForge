package com.ragforge.ingestion.connector;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

abstract class AbstractReadOnlyConnector implements SourceConnector {
    protected final UUID spaceId;
    protected final UUID sourceId;
    private final AtomicReference<SourceCheckpoint> committedCheckpoint;

    protected AbstractReadOnlyConnector(UUID spaceId, UUID sourceId) {
        if (spaceId == null || sourceId == null) {
            throw new ConnectorException(ConnectorFailure.RULES_INVALID, "spaceId and sourceId are required");
        }
        this.spaceId = spaceId;
        this.sourceId = sourceId;
        this.committedCheckpoint = new AtomicReference<>(SourceCheckpoint.empty(spaceId, sourceId));
    }

    @Override
    public final SourceChangeSet discover(SourceCheckpoint checkpoint, DiscoveryRules rules) {
        ConnectorValidation.checkpoint(spaceId, sourceId, checkpoint);
        if (rules == null) {
            throw new ConnectorException(ConnectorFailure.RULES_INVALID, "discovery rules are required");
        }
        List<ScannedObject> scanned = scan(rules);
        Map<String, SourceObjectSnapshot> current = new HashMap<>();
        String sourceVersion = sourceVersionFor(scanned);
        for (ScannedObject object : scanned) {
            SourceObjectSnapshot snapshot = object.snapshot(spaceId, sourceId, sourceVersion);
            current.put(snapshot.canonicalPath(), snapshot);
        }
        if (current.size() > rules.maxChanges()) {
            throw new ConnectorException(ConnectorFailure.RULES_INVALID, "change set exceeds configured limit");
        }
        return classify(checkpoint, current, sourceVersion, rules.maxChanges());
    }

    @Override
    public final SourceCheckpoint commitCheckpoint(SourceChangeSet changeSet, CheckpointCommitResult result) {
        if (changeSet == null || result == null || !changeSet.spaceId().equals(spaceId)
                || !changeSet.sourceId().equals(sourceId)) {
            throw new ConnectorException(ConnectorFailure.CHECKPOINT_INVALID, "change set boundary is invalid");
        }
        if (!changeSet.changeSetId().equals(result.changeSetId())) {
            throw new ConnectorException(ConnectorFailure.PERSISTENCE_INCOMPLETE, "checkpoint result does not identify the change set");
        }
        if (!result.complete()) {
            throw new ConnectorException(ConnectorFailure.PERSISTENCE_INCOMPLETE,
                    "checkpoint requires complete persistence evidence");
        }
        while (true) {
            SourceCheckpoint previous = committedCheckpoint.get();
            if (!previous.sourceVersion().equals(changeSet.previousSourceVersion())) {
                throw new ConnectorException(ConnectorFailure.CHECKPOINT_STALE, "checkpoint is stale");
            }
            SourceCheckpoint next = checkpointFrom(previous, changeSet);
            if (committedCheckpoint.compareAndSet(previous, next)) {
                return next;
            }
        }
    }

    @Override
    public final SourceCheckpoint currentCheckpoint() {
        return committedCheckpoint.get();
    }

    protected abstract List<ScannedObject> scan(DiscoveryRules rules);

    protected abstract FetchedContent fetchCurrent(SourceReference sourceRef, String expectedVersion,
                                                   long maxObjectBytes) throws IOException;

    @Override
    public final FetchedContent fetch(SourceReference sourceRef, String expectedVersion) throws IOException {
        ConnectorValidation.reference(spaceId, sourceId, sourceRef);
        if (expectedVersion == null || !expectedVersion.equals(sourceRef.sourceVersion())) {
            throw new ConnectorException(ConnectorFailure.VERSION_MISMATCH, "expected source version does not match reference");
        }
        return fetchCurrent(sourceRef, expectedVersion, 10L * 1024 * 1024 * 1024);
    }

    private SourceChangeSet classify(SourceCheckpoint checkpoint, Map<String, SourceObjectSnapshot> current,
                                     String sourceVersion, int maxChanges) {
        Map<String, SourceObjectSnapshot> previous = checkpoint.objects();
        Set<String> consumedPrevious = new HashSet<>();
        List<SourceChange> changes = new ArrayList<>();
        List<String> paths = current.keySet().stream().sorted().toList();
        for (String path : paths) {
            SourceObjectSnapshot now = current.get(path);
            SourceObjectSnapshot before = previous.get(path);
            if (before != null && before.contentHash().equals(now.contentHash())
                    && before.byteLength() == now.byteLength()) {
                consumedPrevious.add(path);
                changes.add(change(now, ChangeKind.UNCHANGED, null, sourceVersion));
                continue;
            }
            if (before != null) {
                consumedPrevious.add(path);
                changes.add(change(now, ChangeKind.MODIFY, null, sourceVersion));
                continue;
            }
            SourceObjectSnapshot movedFrom = uniqueUnconsumedHash(previous, consumedPrevious, now.contentHash());
            if (movedFrom != null) {
                consumedPrevious.add(movedFrom.canonicalPath());
                SourceChange moved = change(new SourceObjectSnapshot(
                        now.spaceId(), now.sourceId(), movedFrom.stableSourceObjectId(), now.canonicalPath(),
                        now.sourceVersion(), now.contentHash(), now.byteLength(), now.mediaType(), now.provenance()),
                        ChangeKind.MOVE, movedFrom.canonicalPath(), sourceVersion);
                changes.add(moved);
            } else {
                changes.add(change(now, ChangeKind.ADD, null, sourceVersion));
            }
        }
        previous.keySet().stream().sorted().filter(path -> !consumedPrevious.contains(path)).forEach(path -> {
            SourceObjectSnapshot before = previous.get(path);
            changes.add(change(before, ChangeKind.DELETE, null, sourceVersion));
        });
        if (changes.size() > maxChanges) {
            throw new ConnectorException(ConnectorFailure.RULES_INVALID, "change set exceeds configured limit");
        }
        return new SourceChangeSet(UUID.randomUUID(), spaceId, sourceId, checkpoint.sourceVersion(), sourceVersion, changes);
    }

    private SourceObjectSnapshot uniqueUnconsumedHash(Map<String, SourceObjectSnapshot> previous,
                                                       Set<String> consumed, String contentHash) {
        List<SourceObjectSnapshot> matches = previous.values().stream()
                .filter(snapshot -> !consumed.contains(snapshot.canonicalPath()))
                .filter(snapshot -> snapshot.contentHash().equals(contentHash))
                .sorted(Comparator.comparing(SourceObjectSnapshot::canonicalPath))
                .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private SourceCheckpoint checkpointFrom(SourceCheckpoint previous, SourceChangeSet changeSet) {
        Map<String, SourceObjectSnapshot> objects = new HashMap<>(previous.objects());
        for (SourceChange change : changeSet.changes()) {
            if (change.kind() == ChangeKind.DELETE) {
                objects.remove(change.canonicalPath());
            } else {
                if (change.previousCanonicalPath() != null) {
                    objects.remove(change.previousCanonicalPath());
                }
                objects.put(change.canonicalPath(), new SourceObjectSnapshot(
                        change.spaceId(), change.sourceId(), change.stableSourceObjectId(), change.canonicalPath(),
                        change.sourceVersion(), change.contentHash(), change.byteLength(), change.mediaType(),
                        change.provenance()));
            }
        }
        return new SourceCheckpoint(spaceId, sourceId, changeSet.sourceVersion(), objects);
    }

    private SourceChange change(SourceObjectSnapshot snapshot, ChangeKind kind, String previousPath,
                                String sourceVersion) {
        return new SourceChange(snapshot.spaceId(), snapshot.sourceId(), snapshot.stableSourceObjectId(), kind,
                snapshot.canonicalPath(), previousPath, sourceVersion, snapshot.contentHash(), snapshot.byteLength(),
                snapshot.mediaType(), snapshot.provenance());
    }

    protected String sourceVersionFor(List<ScannedObject> scanned) {
        String material = scanned.stream().sorted(Comparator.comparing(ScannedObject::canonicalPath))
                .map(object -> object.canonicalPath() + "\n" + ConnectorIdentity.sha256(object.content()))
                .reduce("", (left, right) -> left + right + "\n");
        return ConnectorIdentity.sha256(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    protected record ScannedObject(
            String canonicalPath,
            byte[] content,
            long byteLength,
            String mediaType,
            Instant lastModified,
            String provenance,
            UUID stableSourceObjectId) {

        protected SourceObjectSnapshot snapshot(UUID spaceId, UUID sourceId, String sourceVersion) {
            String hash = ConnectorIdentity.sha256(content);
            return new SourceObjectSnapshot(
                    spaceId, sourceId, stableSourceObjectId, canonicalPath, sourceVersion, hash,
                    byteLength, mediaType, provenance);
        }
    }
}
