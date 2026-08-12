package com.ragforge.server.run;

import com.ragforge.server.common.UuidV7;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Process-local event store used by the vertical slice. The port keeps the service independent from this
 * retention mechanism so a durable or shared implementation can replace it later.
 */
@Component
public class InMemoryRunEventStore implements RunEventStore {
    public static final int DEFAULT_MAX_EVENTS_PER_RUN = 256;
    public static final Duration DEFAULT_RETENTION = Duration.ofMinutes(15);

    private final int maxEventsPerRun;
    private final Duration retention;
    private final Clock clock;
    private final Map<RunKey, RunBuffer> runs = new ConcurrentHashMap<>();
    private final AtomicLong subscriberIds = new AtomicLong();

    public InMemoryRunEventStore() {
        this(DEFAULT_MAX_EVENTS_PER_RUN, DEFAULT_RETENTION, Clock.systemUTC());
    }

    public InMemoryRunEventStore(int maxEventsPerRun) {
        this(maxEventsPerRun, DEFAULT_RETENTION, Clock.systemUTC());
    }

    public InMemoryRunEventStore(int maxEventsPerRun, Duration retention) {
        this(maxEventsPerRun, retention, Clock.systemUTC());
    }

    public InMemoryRunEventStore(int maxEventsPerRun, Duration retention, Clock clock) {
        if (maxEventsPerRun < 1) {
            throw new IllegalArgumentException("maxEventsPerRun must be positive");
        }
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        this.maxEventsPerRun = maxEventsPerRun;
        this.retention = retention;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public RunEvent append(RunEventDraft draft) {
        Objects.requireNonNull(draft, "draft");
        RunBuffer buffer = buffer(draft.spaceId(), draft.runId());
        synchronized (buffer) {
            Instant now = Instant.now(clock);
            purge(buffer, now);
            if (buffer.cancelled && "answer.delta".equals(draft.type())) {
                throw new RunCancelledException(draft.runId());
            }
            RunEvent event = new RunEvent(UuidV7.random(), ++buffer.latestSequence, draft.runId(), draft.spaceId(),
                    draft.correlationId(), now, draft.type(), draft.version(), draft.payloadJson());
            buffer.events.addLast(event);
            trim(buffer);
            publishLocked(buffer, event);
            return event;
        }
    }

    @Override
    public RunEvent snapshot(UUID spaceId, UUID runId, UUID correlationId, String status, String reason) {
        Objects.requireNonNull(correlationId, "correlationId");
        RunBuffer buffer = buffer(spaceId, runId);
        synchronized (buffer) {
            Instant now = Instant.now(clock);
            purge(buffer, now);
            RunEvent event = new RunEvent(UuidV7.random(), ++buffer.latestSequence, runId, spaceId, correlationId,
                    now, "run.snapshot", 1,
                    "{\"status\":\"%s\",\"reason\":\"%s\"}".formatted(status, reason));
            buffer.events.addLast(event);
            trim(buffer);
            return event;
        }
    }

    @Override
    public ReplayResult replay(UUID spaceId, UUID runId, String lastEventId) {
        RunBuffer buffer = buffer(spaceId, runId);
        synchronized (buffer) {
            purge(buffer, Instant.now(clock));
            return replayLocked(spaceId, runId, buffer, lastEventId);
        }
    }

    @Override
    public OpenedStream openStream(UUID spaceId, UUID runId, String lastEventId, Consumer<RunEvent> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        RunBuffer buffer = buffer(spaceId, runId);
        synchronized (buffer) {
            purge(buffer, Instant.now(clock));
            ReplayResult replay = replayLocked(spaceId, runId, buffer, lastEventId);
            Subscriber subscriber = new Subscriber(buffer, consumer);
            buffer.subscribers.put(subscriber.id, subscriber);
            return new OpenedStream(replay, subscriber);
        }
    }

    @Override
    public Subscription subscribe(UUID spaceId, UUID runId, Consumer<RunEvent> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        RunBuffer buffer = buffer(spaceId, runId);
        synchronized (buffer) {
            purge(buffer, Instant.now(clock));
            Subscriber subscriber = new Subscriber(buffer, consumer);
            subscriber.active = true;
            buffer.subscribers.put(subscriber.id, subscriber);
            return subscriber;
        }
    }

    @Override
    public CancellationResult cancel(UUID spaceId, UUID runId, UUID correlationId) {
        Objects.requireNonNull(correlationId, "correlationId");
        RunBuffer buffer = buffer(spaceId, runId);
        synchronized (buffer) {
            Instant now = Instant.now(clock);
            purge(buffer, now);
            if (buffer.cancelled) {
                return new CancellationResult(false, buffer.cancellationEvent);
            }
            buffer.cancelled = true;
            RunEvent event = new RunEvent(UuidV7.random(), ++buffer.latestSequence, runId, spaceId, correlationId,
                    now, RUN_STATUS_EVENT_TYPE, 1,
                    "{\"runId\":\"%s\",\"spaceId\":\"%s\",\"status\":\"CANCELLED\"}"
                            .formatted(runId, spaceId));
            buffer.cancellationEvent = event;
            buffer.events.addLast(event);
            trim(buffer);
            publishLocked(buffer, event);
            return new CancellationResult(true, event);
        }
    }

    @Override
    public Optional<RunEvent> find(UUID spaceId, UUID runId, UUID eventId) {
        RunBuffer buffer = buffer(spaceId, runId);
        synchronized (buffer) {
            purge(buffer, Instant.now(clock));
            return buffer.events.stream().filter(event -> event.eventId().equals(eventId)).findFirst();
        }
    }

    private RunBuffer buffer(UUID spaceId, UUID runId) {
        return runs.computeIfAbsent(new RunKey(spaceId, runId), ignored -> new RunBuffer());
    }

    private ReplayResult replayLocked(UUID spaceId, UUID runId, RunBuffer buffer, String lastEventId) {
        List<RunEvent> retained = List.copyOf(buffer.events);
        long latest = buffer.latestSequence;
        long earliest = retained.isEmpty() ? 0 : retained.getFirst().sequence();
        if (lastEventId == null || lastEventId.isBlank()) {
            return new ReplayResult(retained, CursorStatus.NO_CURSOR, null, latest, earliest);
        }

        Optional<Long> cursorSequence = cursorSequence(retained, lastEventId);
        if (cursorSequence.isPresent()) {
            long cursor = cursorSequence.get();
            boolean cursorWithinRetention = cursor >= earliest
                    || (cursor == 0 && buffer.evictedThroughSequence == 0);
            if (cursor <= latest && cursorWithinRetention) {
                List<RunEvent> afterCursor = retained.stream()
                        .filter(event -> event.sequence() > cursor)
                        .toList();
                return new ReplayResult(afterCursor, CursorStatus.AVAILABLE, null, latest, earliest);
            }
        }

        String reason = latest > 0 && isExpired(retained, lastEventId, buffer, earliest, latest)
                ? "cursor_expired" : "cursor_unavailable";
        SnapshotRecovery snapshot = new SnapshotRecovery(runId, spaceId, "UNKNOWN", latest, earliest, reason);
        return new ReplayResult(List.of(), CursorStatus.UNAVAILABLE, snapshot, latest, earliest);
    }

    private Optional<Long> cursorSequence(List<RunEvent> retained, String cursor) {
        try {
            long sequence = Long.parseLong(cursor);
            return Optional.of(sequence);
        } catch (NumberFormatException ignored) {
            try {
                UUID eventId = UUID.fromString(cursor);
                return retained.stream().filter(event -> event.eventId().equals(eventId))
                        .map(RunEvent::sequence).findFirst();
            } catch (IllegalArgumentException ignoredUuid) {
                return Optional.empty();
            }
        }
    }

    private boolean isExpired(List<RunEvent> retained, String cursor, RunBuffer buffer, long earliest, long latest) {
        try {
            long sequence = Long.parseLong(cursor);
            return sequence < earliest && sequence <= buffer.evictedThroughSequence;
        } catch (NumberFormatException ignored) {
            return !retained.isEmpty() || latest > 0;
        }
    }

    private void purge(RunBuffer buffer, Instant now) {
        Instant cutoff = now.minus(retention);
        while (!buffer.events.isEmpty() && buffer.events.getFirst().occurredAt().isBefore(cutoff)) {
            buffer.evictedThroughSequence = buffer.events.removeFirst().sequence();
        }
    }

    private void trim(RunBuffer buffer) {
        while (buffer.events.size() > maxEventsPerRun) {
            buffer.evictedThroughSequence = buffer.events.removeFirst().sequence();
        }
    }

    /** Called while the run buffer monitor is held, preserving per-subscriber sequence order. */
    private void publishLocked(RunBuffer buffer, RunEvent event) {
        for (Subscriber subscriber : new ArrayList<>(buffer.subscribers.values())) {
            if (!subscriber.active) {
                subscriber.backlog.addLast(event);
                continue;
            }
            try {
                subscriber.consumer.accept(event);
            } catch (RuntimeException ignored) {
                subscriber.closeLocked();
            }
        }
    }

    private final class Subscriber implements Subscription {
        private final long id = subscriberIds.incrementAndGet();
        private final RunBuffer buffer;
        private final Consumer<RunEvent> consumer;
        private final Deque<RunEvent> backlog = new ArrayDeque<>();
        private boolean active;
        private boolean closed;

        private Subscriber(RunBuffer buffer, Consumer<RunEvent> consumer) {
            this.buffer = buffer;
            this.consumer = consumer;
        }

        @Override
        public void activate() {
            while (true) {
                List<RunEvent> pending;
                synchronized (buffer) {
                    if (closed) {
                        return;
                    }
                    pending = new ArrayList<>(backlog);
                    backlog.clear();
                    if (pending.isEmpty()) {
                        active = true;
                        return;
                    }
                }
                for (RunEvent event : pending) {
                    try {
                        consumer.accept(event);
                    } catch (RuntimeException ignored) {
                        close();
                        return;
                    }
                }
            }
        }

        @Override
        public boolean active() {
            synchronized (buffer) {
                return active && !closed;
            }
        }

        @Override
        public void close() {
            synchronized (buffer) {
                closeLocked();
            }
        }

        private void closeLocked() {
            closed = true;
            active = false;
            buffer.subscribers.remove(id);
            backlog.clear();
        }
    }

    private final class RunBuffer {
        private final Deque<RunEvent> events = new ArrayDeque<>();
        private final Map<Long, Subscriber> subscribers = new HashMap<>();
        private long latestSequence;
        private long evictedThroughSequence;
        private boolean cancelled;
        private RunEvent cancellationEvent;
    }

    private record RunKey(UUID spaceId, UUID runId) {
        private RunKey {
            Objects.requireNonNull(spaceId, "spaceId");
            Objects.requireNonNull(runId, "runId");
        }
    }
}
