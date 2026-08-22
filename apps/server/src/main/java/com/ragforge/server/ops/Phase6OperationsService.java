package com.ragforge.server.ops;

import com.ragforge.server.answer.persistence.JdbcAnswerPersistence;
import com.ragforge.server.run.JdbcRunEventStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 6 operator functions. Exports deliberately contain metadata and hashes,
 * never audit payload bodies or user prompts, and every query is space-scoped.
 */
@Service
@EnableScheduling
@ConditionalOnProperty(name = "ragforge.phase6.operations.enabled", havingValue = "true")
public class Phase6OperationsService {
    private final JdbcAnswerPersistence answers;
    private final JdbcRunEventStore runEvents;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public Phase6OperationsService(JdbcAnswerPersistence answers, JdbcRunEventStore runEvents,
                                   JdbcTemplate jdbc) {
        this(answers, runEvents, jdbc, Clock.systemUTC());
    }

    Phase6OperationsService(JdbcAnswerPersistence answers, JdbcRunEventStore runEvents,
                            JdbcTemplate jdbc, Clock clock) {
        this.answers = Objects.requireNonNull(answers, "answers");
        this.runEvents = Objects.requireNonNull(runEvents, "runEvents");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Purges expired answer aggregates and durable SSE events in one operator tick. */
    @Scheduled(fixedDelayString = "${ragforge.phase6.operations.cleanup.fixed-delay-ms:3600000}")
    public CleanupResult purgeExpiredData() {
        Instant now = Instant.now(clock);
        int answersPurged = 0;
        int eventsPurged = 0;
        for (UUID spaceId : jdbc.queryForList("SELECT id FROM knowledge_spaces", UUID.class)) {
            answersPurged += answers.purgeExpired(spaceId, now);
            eventsPurged += runEvents.purgeExpired(spaceId, now);
        }
        return new CleanupResult(now, answersPurged, eventsPurged);
    }

    /**
     * Exports a tamper-evident audit index. Payload bodies are intentionally not
     * included; operators can correlate by IDs without exporting sensitive data.
     */
    @Transactional(readOnly = true)
    public String exportAuditCsv(UUID spaceId, Instant fromInclusive, Instant toExclusive) {
        requireWindow(spaceId, fromInclusive, toExclusive);
        List<AuditExportRow> rows = jdbc.query("""
                SELECT id, event_type, actor_user_id, aggregate_id, correlation_id, occurred_at,
                       encode(digest(payload::text, 'sha256'), 'hex') AS payload_hash
                FROM audit_events
                WHERE space_id = ? AND occurred_at >= ? AND occurred_at < ?
                ORDER BY occurred_at, id
                """, (rs, rowNum) -> new AuditExportRow(
                rs.getObject("id", UUID.class), rs.getString("event_type"),
                rs.getObject("actor_user_id", UUID.class), rs.getObject("aggregate_id", UUID.class),
                rs.getObject("correlation_id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("payload_hash")), spaceId, timestamp(fromInclusive), timestamp(toExclusive));
        StringBuilder csv = new StringBuilder("id,event_type,actor_user_id,aggregate_id,correlation_id,occurred_at,payload_sha256\n");
        rows.forEach(row -> csv.append(row.csv()).append('\n'));
        return csv.toString();
    }

    /** Returns a space-scoped usage/cost report without request or response bodies. */
    @Transactional(readOnly = true)
    public List<UsageCostRow> usageCost(UUID spaceId, Instant fromInclusive, Instant toExclusive) {
        requireWindow(spaceId, fromInclusive, toExclusive);
        return jdbc.query("""
                SELECT usage_source, currency, COUNT(*) AS ledger_entries,
                       COALESCE(SUM(input_tokens), 0) AS input_tokens,
                       COALESCE(SUM(output_tokens), 0) AS output_tokens,
                       COALESCE(SUM(total_tokens), 0) AS total_tokens,
                       COALESCE(SUM(estimated_cost), 0) AS estimated_cost
                FROM usage_ledger
                WHERE space_id = ? AND created_at >= ? AND created_at < ?
                GROUP BY usage_source, currency
                ORDER BY usage_source, currency
                """, (rs, rowNum) -> new UsageCostRow(rs.getString("usage_source"), rs.getString("currency"),
                rs.getLong("ledger_entries"), rs.getLong("input_tokens"), rs.getLong("output_tokens"),
                rs.getLong("total_tokens"), rs.getBigDecimal("estimated_cost")),
                spaceId, timestamp(fromInclusive), timestamp(toExclusive));
    }

    private static void requireWindow(UUID spaceId, Instant fromInclusive, Instant toExclusive) {
        if (spaceId == null || fromInclusive == null || toExclusive == null || !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("space and a non-empty time window are required");
        }
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    public record CleanupResult(Instant completedAt, int answersPurged, int eventsPurged) {
    }

    public record AuditExportRow(UUID id, String eventType, UUID actorUserId, UUID aggregateId,
                                 UUID correlationId, Instant occurredAt, String payloadSha256) {
        String csv() {
            return String.join(",", csvValue(id), csvValue(eventType), csvValue(actorUserId),
                    csvValue(aggregateId), csvValue(correlationId), csvValue(occurredAt), csvValue(payloadSha256));
        }

        private static String csvValue(Object value) {
            if (value == null) {
                return "";
            }
            String text = value.toString().replace("\"", "\"\"");
            return text.indexOf(',') >= 0 ? '"' + text + '"' : text;
        }
    }

    public record UsageCostRow(String usageSource, String currency, long ledgerEntries, long inputTokens,
                               long outputTokens, long totalTokens, BigDecimal estimatedCost) {
    }
}
