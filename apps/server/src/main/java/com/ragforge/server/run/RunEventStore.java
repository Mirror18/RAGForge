package com.ragforge.server.run;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Replaceable port for short-lived run event retention and live delivery. */
public interface RunEventStore {
    String RUN_STATUS_EVENT_TYPE = "run.status";

    RunEvent append(RunEventDraft draft);

    default RunEvent append(UUID runId, UUID spaceId, UUID correlationId, String type, int version,
                            String payloadJson) {
        return append(new RunEventDraft(runId, spaceId, correlationId, type, version, payloadJson));
    }

    ReplayResult replay(UUID spaceId, UUID runId, String lastEventId);

    OpenedStream openStream(UUID spaceId, UUID runId, String lastEventId, Consumer<RunEvent> consumer);

    Subscription subscribe(UUID spaceId, UUID runId, Consumer<RunEvent> consumer);

    CancellationResult cancel(UUID spaceId, UUID runId, UUID correlationId);

    Optional<RunEvent> find(UUID spaceId, UUID runId, UUID eventId);

    record OpenedStream(ReplayResult replay, Subscription subscription) {
    }

    interface Subscription extends AutoCloseable {
        /** Makes events published after registration visible to the consumer in sequence order. */
        void activate();

        boolean active();

        @Override
        void close();
    }

    enum CursorStatus {
        NO_CURSOR,
        AVAILABLE,
        UNAVAILABLE
    }

    record ReplayResult(List<RunEvent> events, CursorStatus cursorStatus, SnapshotRecovery snapshotRecovery,
                        long latestSequence, long earliestSequence) {
        public ReplayResult {
            events = List.copyOf(events);
        }

        public boolean cursorAvailable() {
            return cursorStatus != CursorStatus.UNAVAILABLE;
        }

        public boolean cursorExpired() {
            return cursorStatus == CursorStatus.UNAVAILABLE && snapshotRecovery != null
                    && "cursor_expired".equals(snapshotRecovery.reason());
        }

        public boolean hasSnapshotRecovery() {
            return snapshotRecovery != null;
        }

        public SnapshotRecovery snapshot() {
            return snapshotRecovery;
        }
    }

    record SnapshotRecovery(UUID runId, UUID spaceId, String status, long latestSequence, long earliestSequence,
                            String reason) {
        public String payloadJson() {
            return "{\"runId\":\"%s\",\"spaceId\":\"%s\",\"status\":\"%s\","
                    .formatted(runId, spaceId, status) +
                    "\"latestSequence\":%d,\"earliestSequence\":%d,\"reason\":\"%s\","
                            .formatted(latestSequence, earliestSequence, reason) +
                    "\"resumeFromSequence\":%d}".formatted(latestSequence);
        }
    }

    record CancellationResult(boolean firstCancellation, RunEvent event) {
    }
}
