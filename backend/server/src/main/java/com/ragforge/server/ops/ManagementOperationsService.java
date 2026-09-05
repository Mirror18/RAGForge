package com.ragforge.server.ops;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Read-only management projections. Every query is explicitly scoped to one
 * space and deliberately excludes prompts, document text and audit payloads.
 */
@Service
public class ManagementOperationsService {
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;
    private final JdbcTemplate jdbc;

    public ManagementOperationsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public HealthAggregate aggregateHealth(UUID spaceId, Instant fromInclusive, Instant toExclusive) {
        Window window = requireWindow(spaceId, fromInclusive, toExclusive);
        ProviderHealth providers = jdbc.queryForObject("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE status = 'ACTIVE') AS active,
                       COUNT(*) FILTER (WHERE status = 'UNHEALTHY') AS unhealthy,
                       COUNT(*) FILTER (WHERE status = 'DISABLED') AS disabled
                FROM provider_connections
                WHERE space_id = ?
                """, (rs, rowNum) -> new ProviderHealth(rs.getLong("total"), rs.getLong("active"),
                rs.getLong("unhealthy"), rs.getLong("disabled")), spaceId);
        RunHealth runs = jdbc.queryForObject("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded,
                       COUNT(*) FILTER (WHERE status = 'FAILED') AS failed,
                       COUNT(*) FILTER (WHERE status IN ('QUEUED', 'RUNNING')) AS in_flight
                FROM runs
                WHERE space_id = ? AND created_at >= ? AND created_at < ?
                """, (rs, rowNum) -> new RunHealth(rs.getLong("total"), rs.getLong("succeeded"),
                rs.getLong("failed"), rs.getLong("in_flight")), spaceId,
                window.fromTimestamp(), window.toTimestamp());
        return new HealthAggregate(spaceId, window.fromInclusive(), window.toExclusive(),
                providers == null ? ProviderHealth.zero() : providers,
                runs == null ? RunHealth.zero() : runs);
    }

    @Transactional(readOnly = true)
    public UsageCostPage costUsage(UUID spaceId, Instant fromInclusive, Instant toExclusive) {
        Window window = requireWindow(spaceId, fromInclusive, toExclusive);
        List<UsageCostRow> rows = jdbc.query("""
                SELECT usage_source, currency, COUNT(*) AS ledger_entries,
                       COALESCE(SUM(input_tokens), 0) AS input_tokens,
                       COALESCE(SUM(output_tokens), 0) AS output_tokens,
                       COALESCE(SUM(total_tokens), 0) AS total_tokens,
                       COALESCE(SUM(estimated_cost), 0) AS estimated_cost
                FROM usage_ledger
                WHERE space_id = ? AND created_at >= ? AND created_at < ?
                GROUP BY usage_source, currency
                ORDER BY usage_source ASC, currency ASC
                """, (rs, rowNum) -> new UsageCostRow(rs.getString("usage_source"), rs.getString("currency"),
                rs.getLong("ledger_entries"), rs.getLong("input_tokens"), rs.getLong("output_tokens"),
                rs.getLong("total_tokens"), rs.getBigDecimal("estimated_cost")), spaceId,
                window.fromTimestamp(), window.toTimestamp());
        return new UsageCostPage(spaceId, window.fromInclusive(), window.toExclusive(), rows);
    }

    @Transactional(readOnly = true)
    public CursorPage<FeedbackItem> listFeedback(UUID spaceId, Instant fromInclusive, Instant toExclusive,
                                                  String cursor, Integer requestedLimit) {
        Window window = requireWindow(spaceId, fromInclusive, toExclusive);
        int limit = pageSize(requestedLimit);
        CursorPosition position = decodeCursor(cursor);
        String predicate = position == null ? "" : " AND (created_at, id) < (?, ?)";
        Object[] args = position == null
                ? new Object[]{spaceId, window.fromTimestamp(), window.toTimestamp(), limit + 1}
                : new Object[]{spaceId, window.fromTimestamp(), window.toTimestamp(),
                position.timestamp(), position.id(), limit + 1};
        List<FeedbackItem> rows = jdbc.query("""
                SELECT id, space_id, run_id, evidence_id, actor_user_id, sentiment, version,
                       created_at, updated_at
                FROM answer_feedback
                WHERE space_id = ? AND created_at >= ? AND created_at < ?
                """ + predicate + " ORDER BY created_at DESC, id DESC LIMIT ?",
                (rs, rowNum) -> new FeedbackItem(rs.getObject("id", UUID.class),
                        rs.getObject("space_id", UUID.class), rs.getObject("run_id", UUID.class),
                        rs.getObject("evidence_id", UUID.class), rs.getObject("actor_user_id", UUID.class),
                        rs.getString("sentiment"), rs.getLong("version"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), args);
        boolean hasMore = rows.size() > limit;
        List<FeedbackItem> items = hasMore ? rows.subList(0, limit) : rows;
        return new CursorPage<>(items, hasMore ? encodeCursor(items.get(items.size() - 1).createdAt(),
                items.get(items.size() - 1).id()) : null);
    }

    @Transactional(readOnly = true)
    public CursorPage<AuditExportItem> exportAudit(UUID spaceId, Instant fromInclusive, Instant toExclusive,
                                                    String cursor, Integer requestedLimit) {
        Window window = requireWindow(spaceId, fromInclusive, toExclusive);
        int limit = pageSize(requestedLimit);
        CursorPosition position = decodeCursor(cursor);
        String predicate = position == null ? "" : " AND (occurred_at, id) > (?, ?)";
        Object[] args = position == null
                ? new Object[]{spaceId, window.fromTimestamp(), window.toTimestamp(), limit + 1}
                : new Object[]{spaceId, window.fromTimestamp(), window.toTimestamp(),
                position.timestamp(), position.id(), limit + 1};
        List<AuditExportItem> rows = jdbc.query("""
                SELECT id, event_type, actor_user_id, aggregate_id, correlation_id, occurred_at,
                       encode(digest(payload::text, 'sha256'), 'hex') AS payload_sha256
                FROM audit_events
                WHERE space_id = ? AND occurred_at >= ? AND occurred_at < ?
                """ + predicate + " ORDER BY occurred_at ASC, id ASC LIMIT ?",
                (rs, rowNum) -> new AuditExportItem(rs.getObject("id", UUID.class), rs.getString("event_type"),
                        rs.getObject("actor_user_id", UUID.class), rs.getObject("aggregate_id", UUID.class),
                        rs.getObject("correlation_id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("payload_sha256")), args);
        boolean hasMore = rows.size() > limit;
        List<AuditExportItem> items = hasMore ? rows.subList(0, limit) : rows;
        return new CursorPage<>(items, hasMore ? encodeCursor(items.get(items.size() - 1).occurredAt(),
                items.get(items.size() - 1).id()) : null);
    }

    private static Window requireWindow(UUID spaceId, Instant fromInclusive, Instant toExclusive) {
        if (spaceId == null || fromInclusive == null || toExclusive == null || !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("space and a non-empty time window are required");
        }
        return new Window(spaceId, fromInclusive, toExclusive);
    }

    private static int pageSize(Integer requestedLimit) {
        int value = requestedLimit == null ? DEFAULT_PAGE_SIZE : requestedLimit;
        if (value < 1 || value > MAX_PAGE_SIZE) throw new IllegalArgumentException("limit must be between 1 and 100");
        return value;
    }

    private static String encodeCursor(Instant timestamp, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (timestamp.toString() + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static CursorPosition decodeCursor(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int separator = decoded.indexOf('|');
            if (separator <= 0 || separator == decoded.length() - 1) throw new IllegalArgumentException();
            return new CursorPosition(Instant.parse(decoded.substring(0, separator)),
                    UUID.fromString(decoded.substring(separator + 1)));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("cursor is invalid", exception);
        }
    }

    private record Window(UUID spaceId, Instant fromInclusive, Instant toExclusive) {
        java.sql.Timestamp fromTimestamp() { return java.sql.Timestamp.from(fromInclusive); }
        java.sql.Timestamp toTimestamp() { return java.sql.Timestamp.from(toExclusive); }
    }

    private record CursorPosition(Instant createdAt, UUID id) {
        java.sql.Timestamp timestamp() { return java.sql.Timestamp.from(createdAt); }
    }

    public record HealthAggregate(UUID spaceId, Instant from, Instant to,
                                  ProviderHealth providers, RunHealth runs) { }

    public record ProviderHealth(long total, long active, long unhealthy, long disabled) {
        static ProviderHealth zero() { return new ProviderHealth(0, 0, 0, 0); }
    }

    public record RunHealth(long total, long succeeded, long failed, long inFlight) {
        static RunHealth zero() { return new RunHealth(0, 0, 0, 0); }
    }

    public record UsageCostPage(UUID spaceId, Instant from, Instant to, List<UsageCostRow> items) {
        public UsageCostPage { items = List.copyOf(items); }
    }

    public record UsageCostRow(String usageSource, String currency, long ledgerEntries, long inputTokens,
                               long outputTokens, long totalTokens, BigDecimal estimatedCost) { }

    public record FeedbackItem(UUID id, UUID spaceId, UUID runId, UUID evidenceId, UUID actorUserId,
                               String sentiment, long version, Instant createdAt, Instant updatedAt) { }

    public record AuditExportItem(UUID id, String eventType, UUID actorUserId, UUID aggregateId,
                                  UUID correlationId, Instant occurredAt, String payloadSha256) { }

    public record CursorPage<T>(List<T> items, String nextCursor) {
        public CursorPage { items = List.copyOf(items); }
    }
}
