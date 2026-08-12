package com.ragforge.server.run;

import com.ragforge.server.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Space-authorized application boundary for run events, replay and cancellation. */
@Service
public class RunEventService {
    private final RunRepository runRepository;
    private final RunEventStore eventStore;
    private final ConcurrentHashMap<RunKey, Object> cancellationLocks = new ConcurrentHashMap<>();

    public RunEventService(RunRepository runRepository, RunEventStore eventStore) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
    }

    public RunEvent append(UUID spaceId, UUID runId, UUID correlationId, String type, int version,
                           String payloadJson) {
        requireRun(spaceId, runId);
        return eventStore.append(runId, spaceId, correlationId, type, version, payloadJson);
    }

    public RunEventStore.ReplayResult replay(UUID spaceId, UUID runId, String lastEventId) {
        RunRepository.RunRecord run = requireRun(spaceId, runId);
        return withRunStatus(eventStore.replay(spaceId, runId, lastEventId), run);
    }

    public RunEventStore.OpenedStream openStream(UUID spaceId, UUID runId, String lastEventId,
                                                  Consumer<RunEvent> consumer) {
        RunRepository.RunRecord run = requireRun(spaceId, runId);
        RunEventStore.OpenedStream opened = eventStore.openStream(spaceId, runId, lastEventId, consumer);
        return new RunEventStore.OpenedStream(withRunStatus(opened.replay(), run), opened.subscription());
    }

    public RunEventStore.Subscription subscribe(UUID spaceId, UUID runId, Consumer<RunEvent> consumer) {
        requireRun(spaceId, runId);
        return eventStore.subscribe(spaceId, runId, consumer);
    }

    @Transactional
    public RunEventStore.CancellationResult cancel(UUID spaceId, UUID runId, UUID correlationId) {
        requireRun(spaceId, runId);
        Object lock = cancellationLocks.computeIfAbsent(new RunKey(spaceId, runId), ignored -> new Object());
        synchronized (lock) {
            RunRepository.RunRecord current = requireRun(spaceId, runId);
            if (current.status() != RunRepository.RunStatus.CANCELLED) {
                try {
                    runRepository.transitionRun(spaceId, runId, RunRepository.RunStatus.CANCELLED,
                            RunRepository.ErrorClass.CANCELLED, "run_cancelled", java.time.Instant.now(),
                            current.version());
                } catch (IllegalStateException exception) {
                    RunRepository.RunRecord afterRace = requireRun(spaceId, runId);
                    if (afterRace.status() != RunRepository.RunStatus.CANCELLED) {
                        throw exception;
                    }
                }
            }
            return eventStore.cancel(spaceId, runId, correlationId);
        }
    }

    public Optional<RunEvent> find(UUID spaceId, UUID runId, UUID eventId) {
        requireRun(spaceId, runId);
        return eventStore.find(spaceId, runId, eventId);
    }

    private RunRepository.RunRecord requireRun(UUID spaceId, UUID runId) {
        return runRepository.findRun(spaceId, runId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "run_not_found", "Run not found", "Run not found"));
    }

    private RunEventStore.ReplayResult withRunStatus(RunEventStore.ReplayResult replay,
                                                     RunRepository.RunRecord run) {
        if (replay.snapshotRecovery() == null) {
            return replay;
        }
        RunEventStore.SnapshotRecovery snapshot = replay.snapshotRecovery();
        return new RunEventStore.ReplayResult(replay.events(), replay.cursorStatus(),
                new RunEventStore.SnapshotRecovery(snapshot.runId(), snapshot.spaceId(), run.status().name(),
                        snapshot.latestSequence(), snapshot.earliestSequence(), snapshot.reason()),
                replay.latestSequence(), replay.earliestSequence());
    }

    private record RunKey(UUID spaceId, UUID runId) {
    }
}
