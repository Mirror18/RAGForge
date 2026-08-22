package com.ragforge.server.run;

import com.ragforge.server.common.UuidV7;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** PostgreSQL-backed event stream; live delivery remains process-local while replay is durable. */
@Component
public class JdbcRunEventStore implements RunEventStore {
    public static final Duration DEFAULT_RETENTION = Duration.ofMinutes(15);

    private final JdbcTemplate jdbc;
    private final Duration retention;
    private final Clock clock;
    private final Map<RunKey, StreamState> streams = new ConcurrentHashMap<>();
    private final AtomicLong subscriberIds = new AtomicLong();

    @Autowired
    public JdbcRunEventStore(JdbcTemplate jdbc,
                             @Value("${ragforge.run-events.retention:PT15M}") Duration retention) {
        this(jdbc, retention, Clock.systemUTC());
    }

    JdbcRunEventStore(JdbcTemplate jdbc, Duration retention, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        this.retention = retention;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional
    public RunEvent append(RunEventDraft draft) {
        Objects.requireNonNull(draft, "draft");
        Instant now = Instant.now(clock);
        RunEvent event;
        StreamState state = state(draft.spaceId(), draft.runId());
        synchronized (state) {
            StreamCursor cursor = lockStream(draft.spaceId(), draft.runId());
            if (cursor.cancelled() && "answer.delta".equals(draft.type())) {
                throw new RunCancelledException(draft.runId());
            }
            event = new RunEvent(UuidV7.random(), cursor.latestSequence() + 1, draft.runId(), draft.spaceId(),
                    draft.correlationId(), now, draft.type(), draft.version(), draft.payloadJson());
            insert(event, now.plus(retention));
            updateCursor(draft.spaceId(), draft.runId(), event.sequence(), null);
            publishLocked(state, event);
        }
        return event;
    }

    @Override
    @Transactional
    public RunEvent snapshot(UUID spaceId, UUID runId, UUID correlationId, String status, String reason) {
        Objects.requireNonNull(correlationId, "correlationId");
        Instant now = Instant.now(clock);
        StreamState state = state(spaceId, runId);
        synchronized (state) {
            StreamCursor cursor = lockStream(spaceId, runId);
            RunEvent event = new RunEvent(UuidV7.random(), cursor.latestSequence() + 1, runId, spaceId,
                    correlationId, now, "run.snapshot", 1,
                    "{\"status\":\"%s\",\"reason\":\"%s\"}".formatted(status, reason));
            insert(event, now.plus(retention));
            updateCursor(spaceId, runId, event.sequence(), null);
            return event;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ReplayResult replay(UUID spaceId, UUID runId, String lastEventId) {
        return replayLocked(spaceId, runId, lastEventId, Instant.now(clock));
    }

    @Override
    @Transactional(readOnly = true)
    public OpenedStream openStream(UUID spaceId, UUID runId, String lastEventId, Consumer<RunEvent> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        StreamState state = state(spaceId, runId);
        synchronized (state) {
            ReplayResult replay = replayLocked(spaceId, runId, lastEventId, Instant.now(clock));
            Subscriber subscriber = new Subscriber(state, consumer);
            state.subscribers.put(subscriber.id, subscriber);
            return new OpenedStream(replay, subscriber);
        }
    }

    @Override
    public Subscription subscribe(UUID spaceId, UUID runId, Consumer<RunEvent> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        StreamState state = state(spaceId, runId);
        synchronized (state) {
            Subscriber subscriber = new Subscriber(state, consumer);
            subscriber.active = true;
            state.subscribers.put(subscriber.id, subscriber);
            return subscriber;
        }
    }

    @Override
    @Transactional
    public CancellationResult cancel(UUID spaceId, UUID runId, UUID correlationId) {
        Objects.requireNonNull(correlationId, "correlationId");
        Instant now = Instant.now(clock);
        StreamState state = state(spaceId, runId);
        synchronized (state) {
            StreamCursor cursor = lockStream(spaceId, runId);
            if (cursor.cancellationEventId() != null) {
                return new CancellationResult(false, find(spaceId, runId, cursor.cancellationEventId()).orElse(null));
            }
            RunEvent event = new RunEvent(UuidV7.random(), cursor.latestSequence() + 1, runId, spaceId,
                    correlationId, now, RUN_STATUS_EVENT_TYPE, 1,
                    "{\"runId\":\"%s\",\"spaceId\":\"%s\",\"status\":\"CANCELLED\"}"
                            .formatted(runId, spaceId));
            insert(event, now.plus(retention));
            updateCursor(spaceId, runId, event.sequence(), event.eventId());
            publishLocked(state, event);
            return new CancellationResult(true, event);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RunEvent> find(UUID spaceId, UUID runId, UUID eventId) {
        return jdbc.query("""
                SELECT id, sequence_no, run_id, space_id, correlation_id, occurred_at,
                       event_type, event_version, payload::text
                FROM rag_run_events
                WHERE id = ? AND run_id = ? AND space_id = ? AND expires_at > ?
                """, (rs, rowNum) -> mapEvent(rs.getObject("id", UUID.class), rs.getLong("sequence_no"),
                rs.getObject("run_id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getObject("correlation_id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("event_type"), rs.getInt("event_version"), rs.getString("payload")),
                eventId, runId, spaceId, timestamp(Instant.now(clock))).stream().findFirst();
    }

    private ReplayResult replayLocked(UUID spaceId, UUID runId, String lastEventId, Instant now) {
        List<RunEvent> retained = jdbc.query("""
                SELECT id, sequence_no, run_id, space_id, correlation_id, occurred_at,
                       event_type, event_version, payload::text
                FROM rag_run_events
                WHERE run_id = ? AND space_id = ? AND expires_at > ?
                ORDER BY sequence_no
                """, (rs, rowNum) -> mapEvent(rs.getObject("id", UUID.class), rs.getLong("sequence_no"),
                rs.getObject("run_id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getObject("correlation_id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("event_type"), rs.getInt("event_version"), rs.getString("payload")),
                runId, spaceId, timestamp(now));
        long latest = latestSequence(spaceId, runId);
        long earliest = retained.isEmpty() ? 0 : retained.getFirst().sequence();
        if (lastEventId == null || lastEventId.isBlank()) {
            return new ReplayResult(retained, CursorStatus.NO_CURSOR, null, latest, earliest);
        }

        Optional<Long> cursor = cursorSequence(spaceId, runId, lastEventId);
        if (cursor.isPresent() && cursor.get() <= latest
                && cursorWithinRetention(cursor.get(), earliest, latest, retained)) {
            return new ReplayResult(retained.stream().filter(event -> event.sequence() > cursor.get()).toList(),
                    CursorStatus.AVAILABLE, null, latest, earliest);
        }
        String reason = cursorExpired(spaceId, runId, lastEventId, cursor, earliest, latest)
                ? "cursor_expired" : "cursor_unavailable";
        return new ReplayResult(List.of(), CursorStatus.UNAVAILABLE,
                new SnapshotRecovery(runId, spaceId, "UNKNOWN", latest, earliest, reason), latest, earliest);
    }

    private Optional<Long> cursorSequence(UUID spaceId, UUID runId, String cursor) {
        try {
            return Optional.of(Long.parseLong(cursor));
        } catch (NumberFormatException ignored) {
            try {
                UUID eventId = UUID.fromString(cursor);
                return jdbc.query("SELECT sequence_no FROM rag_run_events WHERE id = ? AND run_id = ? AND space_id = ?",
                        (rs, rowNum) -> rs.getLong(1), eventId, runId, spaceId).stream().findFirst();
            } catch (IllegalArgumentException ignoredUuid) {
                return Optional.empty();
            }
        }
    }

    private boolean cursorWithinRetention(long cursor, long earliest, long latest, List<RunEvent> retained) {
        if (retained.isEmpty()) {
            return cursor == 0 && latest == 0;
        }
        return cursor >= earliest;
    }

    private boolean cursorExpired(UUID spaceId, UUID runId, String rawCursor, Optional<Long> cursor,
                                  long earliest, long latest) {
        if (cursor.isPresent()) {
            return cursor.get() <= latest && (earliest == 0 || cursor.get() < earliest);
        }
        try {
            UUID eventId = UUID.fromString(rawCursor);
            return jdbc.query("SELECT 1 FROM rag_run_events WHERE id = ? AND run_id = ? AND space_id = ?",
                    (rs, rowNum) -> 1, eventId, runId, spaceId).stream().findFirst().isPresent();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private StreamCursor lockStream(UUID spaceId, UUID runId) {
        jdbc.update("""
                INSERT INTO rag_run_event_streams (run_id, space_id)
                VALUES (?, ?) ON CONFLICT (run_id, space_id) DO NOTHING
                """, runId, spaceId);
        return jdbc.queryForObject("""
                SELECT latest_sequence, cancelled_at, cancellation_event_id
                FROM rag_run_event_streams
                WHERE run_id = ? AND space_id = ? FOR UPDATE
                """, (rs, rowNum) -> new StreamCursor(rs.getLong("latest_sequence"),
                rs.getTimestamp("cancelled_at") != null,
                rs.getObject("cancellation_event_id", UUID.class)), runId, spaceId);
    }

    private long latestSequence(UUID spaceId, UUID runId) {
        return jdbc.query("SELECT latest_sequence FROM rag_run_event_streams WHERE run_id = ? AND space_id = ?",
                (rs, rowNum) -> rs.getLong(1), runId, spaceId).stream().findFirst().orElse(0L);
    }

    private void insert(RunEvent event, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO rag_run_events
                    (id, run_id, space_id, correlation_id, sequence_no, occurred_at,
                     expires_at, event_type, event_version, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, event.eventId(), event.runId(), event.spaceId(), event.correlationId(), event.sequence(),
                timestamp(event.occurredAt()), timestamp(expiresAt), event.type(), event.version(), event.payloadJson());
    }

    private void updateCursor(UUID spaceId, UUID runId, long sequence, UUID cancellationEventId) {
        if (cancellationEventId == null) {
            jdbc.update("UPDATE rag_run_event_streams SET latest_sequence = ? WHERE run_id = ? AND space_id = ?",
                    sequence, runId, spaceId);
        } else {
            jdbc.update("""
                    UPDATE rag_run_event_streams
                    SET latest_sequence = ?, cancelled_at = ?, cancellation_event_id = ?
                    WHERE run_id = ? AND space_id = ?
                    """, sequence, timestamp(Instant.now(clock)), cancellationEventId, runId, spaceId);
        }
    }

    private StreamState state(UUID spaceId, UUID runId) {
        return streams.computeIfAbsent(new RunKey(spaceId, runId), ignored -> new StreamState());
    }

    private void publishLocked(StreamState state, RunEvent event) {
        for (Subscriber subscriber : new ArrayList<>(state.subscribers.values())) {
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

    private static RunEvent mapEvent(UUID eventId, long sequence, UUID runId, UUID spaceId, UUID correlationId,
                                     Instant occurredAt, String type, int version, String payloadJson) {
        return new RunEvent(eventId, sequence, runId, spaceId, correlationId, occurredAt, type, version, payloadJson);
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private final class Subscriber implements Subscription {
        private final long id = subscriberIds.incrementAndGet();
        private final StreamState state;
        private final Consumer<RunEvent> consumer;
        private final Deque<RunEvent> backlog = new ArrayDeque<>();
        private boolean active;
        private boolean closed;

        private Subscriber(StreamState state, Consumer<RunEvent> consumer) {
            this.state = state;
            this.consumer = consumer;
        }

        @Override
        public void activate() {
            while (true) {
                List<RunEvent> pending;
                synchronized (state) {
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
            synchronized (state) {
                return active && !closed;
            }
        }

        @Override
        public void close() {
            synchronized (state) {
                closeLocked();
            }
        }

        private void closeLocked() {
            closed = true;
            active = false;
            state.subscribers.remove(id);
            backlog.clear();
        }
    }

    private final class StreamState {
        private final Map<Long, Subscriber> subscribers = new ConcurrentHashMap<>();
    }

    private record StreamCursor(long latestSequence, boolean cancelled, UUID cancellationEventId) {
    }

    private record RunKey(UUID spaceId, UUID runId) {
        private RunKey {
            Objects.requireNonNull(spaceId, "spaceId");
            Objects.requireNonNull(runId, "runId");
        }
    }
}
