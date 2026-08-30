package com.ragforge.server.ingestion;

import com.ragforge.server.audit.AuditOutboxService;
import com.ragforge.server.common.ApiException;
import com.ragforge.server.common.CorrelationIdFilter;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.provider.SpaceAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** API/application boundary for space-isolated source and task-center actions. */
@Service
public class SourceTaskCenterService {
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 100;

    private final JdbcTemplate jdbc;
    private final IngestionRepository ingestion;
    private final SpaceAuthorization authorization;
    private final AuditOutboxService outbox;

    public SourceTaskCenterService(JdbcTemplate jdbc, IngestionRepository ingestion,
                                   SpaceAuthorization authorization, AuditOutboxService outbox) {
        this.jdbc = jdbc;
        this.ingestion = ingestion;
        this.authorization = authorization;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public CursorPage<GitSourceService.SourceView> sources(UUID spaceId, String cursor, Integer limit,
                                                            String connectorType, String sourceState, String q,
                                                            SessionPrincipal principal) {
        authorization.requireMember(spaceId, principal);
        int pageSize = pageSize(limit);
        CursorCodec.Position position = cursor == null ? null : CursorCodec.decode(cursor);
        StringBuilder sql = new StringBuilder("""
                SELECT v.id, v.space_id, v.source_id, v.version_no, v.connector_type, v.display_name,
                       v.source_state, v.root_ref, v.include_rules::text, v.exclude_rules::text,
                       v.credential_configured, v.correlation_id, v.created_at, v.updated_at, v.git_branch
                FROM source_versions v
                JOIN sources s ON s.id = v.source_id AND s.space_id = v.space_id
                WHERE v.space_id = ? AND s.lifecycle_state = 'ACTIVE'
                  AND v.version_no = (SELECT MAX(v2.version_no) FROM source_versions v2
                      WHERE v2.space_id = v.space_id AND v2.source_id = v.source_id)
                """);
        List<Object> args = new ArrayList<>(List.of(spaceId));
        if (connectorType != null && !connectorType.isBlank()) {
            sql.append(" AND v.connector_type = ?");
            args.add(enumValue(connectorType, "connectorType"));
        }
        if (sourceState != null && !sourceState.isBlank()) {
            sql.append(" AND v.source_state = ?");
            args.add(enumValue(sourceState, "sourceState"));
        }
        String search = searchText(q);
        if (search != null) {
            sql.append(" AND (LOWER(v.display_name) LIKE ? OR LOWER(v.root_ref) LIKE ? OR LOWER(COALESCE(v.git_branch, '')) LIKE ? OR CAST(v.source_id AS text) LIKE ?)");
            String pattern = "%" + search + "%";
            args.add(pattern); args.add(pattern); args.add(pattern); args.add(pattern);
        }
        if (position != null) {
            sql.append(" AND (v.updated_at, v.source_id) < (?, ?)");
            args.add(Timestamp.from(position.sortTime()));
            args.add(position.id());
        }
        sql.append(" ORDER BY v.updated_at DESC, v.source_id DESC LIMIT ?");
        args.add(pageSize + 1);
        List<SourceRow> rows = jdbc.query(sql.toString(), (rs, row) -> new SourceRow(
                new IngestionRepository.SourceVersion(rs.getObject("id", UUID.class),
                        rs.getObject("space_id", UUID.class), rs.getObject("source_id", UUID.class),
                        rs.getInt("version_no"), IngestionRepository.ConnectorType.valueOf(rs.getString("connector_type")),
                        rs.getString("display_name"), IngestionRepository.SourceState.valueOf(rs.getString("source_state")),
                        rs.getString("root_ref"), rs.getString("include_rules"), rs.getString("exclude_rules"),
                        rs.getBoolean("credential_configured"), rs.getObject("correlation_id", UUID.class),
                        instant(rs, "created_at"), instant(rs, "updated_at"), rs.getString("git_branch")),
                instant(rs, "updated_at")), args.toArray());
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) rows = rows.subList(0, pageSize);
        List<GitSourceService.SourceView> items = rows.stream()
                .map(row -> new GitSourceService.SourceView(row.source(),
                        ingestion.findCheckpoint(spaceId, row.source().sourceId()).orElse(null)))
                .toList();
        String next = hasMore ? CursorCodec.encode(new CursorCodec.Position(
                rows.get(rows.size() - 1).sortTime(), rows.get(rows.size() - 1).source().sourceId())) : null;
        return new CursorPage<>(items, next);
    }

    @Transactional(readOnly = true)
    public CursorPage<BusinessIngestionService.JobView> jobs(UUID spaceId, String cursor, Integer limit,
                                                               String status, UUID sourceId, String q,
                                                               SessionPrincipal principal) {
        authorization.requireMember(spaceId, principal);
        int pageSize = pageSize(limit);
        CursorCodec.Position position = cursor == null ? null : CursorCodec.decode(cursor);
        StringBuilder sql = new StringBuilder("""
                SELECT j.id, j.space_id, j.source_id, j.source_document_id, j.document_revision_id,
                       j.pipeline_version_id, j.status, j.idempotency_key, j.correlation_id, j.causation_id,
                       j.version_no, j.created_at, j.updated_at,
                       sv.display_name AS source_display_name,
                       sd.canonical_source_path AS source_document_path,
                       sd.basename AS source_document_name
                FROM ingestion_jobs j
                LEFT JOIN source_versions sv ON sv.space_id = j.space_id AND sv.source_id = j.source_id
                    AND sv.version_no = (SELECT MAX(sv2.version_no) FROM source_versions sv2
                        WHERE sv2.space_id = j.space_id AND sv2.source_id = j.source_id)
                LEFT JOIN source_documents sd ON sd.space_id = j.space_id AND sd.id = j.source_document_id
                WHERE j.space_id = ? AND j.lifecycle_state = 'ACTIVE'
                """);
        List<Object> args = new ArrayList<>(List.of(spaceId));
        if (status != null && !status.isBlank()) {
            sql.append(" AND j.status = ?");
            args.add(enumValue(status, "status"));
        }
        if (sourceId != null) {
            sql.append(" AND j.source_id = ?");
            args.add(sourceId);
        }
        String search = searchText(q);
        if (search != null) {
            sql.append(" AND (CAST(j.id AS text) LIKE ? OR CAST(j.source_id AS text) LIKE ? OR CAST(j.source_document_id AS text) LIKE ? OR CAST(j.document_revision_id AS text) LIKE ? OR LOWER(j.status) LIKE ? OR LOWER(COALESCE(sv.display_name, '')) LIKE ? OR LOWER(COALESCE(sd.canonical_source_path, '')) LIKE ? OR LOWER(COALESCE(sd.basename, '')) LIKE ? OR EXISTS (SELECT 1 FROM pipeline_step_executions pse WHERE pse.space_id = j.space_id AND pse.job_id = j.id AND (LOWER(pse.step_name) LIKE ? OR LOWER(COALESCE(pse.error_code, '')) LIKE ?)))");
            String pattern = "%" + search + "%";
            args.add(pattern); args.add(pattern); args.add(pattern); args.add(pattern); args.add(pattern);
            args.add(pattern); args.add(pattern); args.add(pattern);
            args.add(pattern); args.add(pattern);
        }
        if (position != null) {
            sql.append(" AND (j.created_at, j.id) < (?, ?)");
            args.add(Timestamp.from(position.sortTime()));
            args.add(position.id());
        }
        sql.append(" ORDER BY j.created_at DESC, j.id DESC LIMIT ?");
        args.add(pageSize + 1);
        List<JobRow> rows = jdbc.query(sql.toString(), (rs, row) -> new JobRow(mapJob(rs), instant(rs, "created_at")), args.toArray());
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) rows = rows.subList(0, pageSize);
        List<BusinessIngestionService.JobView> items = rows.stream()
                .map(row -> new BusinessIngestionService.JobView(row.job(),
                        ingestion.listAttempts(spaceId, row.job().id()), ingestion.listSteps(spaceId, row.job().id())))
                .toList();
        String next = hasMore ? CursorCodec.encode(new CursorCodec.Position(
                rows.get(rows.size() - 1).sortTime(), rows.get(rows.size() - 1).job().id())) : null;
        return new CursorPage<>(items, next);
    }

    @Transactional
    public TaskActionView command(UUID spaceId, ResourceType resourceType, UUID resourceId, Operation operation,
                                  ActionRequest requestBody, String idempotencyKey, String ifMatch,
                                  SessionPrincipal principal, HttpServletRequest request) {
        authorization.requireWrite(spaceId, principal);
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._~-]{1,255}")) {
            throw invalid("Idempotency-Key is required and has an invalid format");
        }
        int expectedVersion = expectedVersion(requestBody, ifMatch);
        String reason = requestBody == null || requestBody.reason() == null ? null : requestBody.reason().trim();
        if (reason != null && reason.length() > 500) throw invalid("reason is too long");
        String requestHash = sha256(resourceType + "|" + resourceId + "|" + operation + "|"
                + expectedVersion + "|" + (reason == null ? "" : reason));
        Optional<TaskActionView> existing = findByKey(spaceId, idempotencyKey);
        if (existing.isPresent()) {
            if (!requestHash.equals(existing.get().requestHash())) throw conflict("idempotency key conflict");
            return existing.get();
        }
        int currentVersion = lockAndReadVersion(spaceId, resourceType, resourceId);
        if (currentVersion != expectedVersion) throw precondition("resource version does not match If-Match");
        UUID actionId = UUID.randomUUID();
        Instant now = Instant.now();
        UUID actor = principal.userId();
        jdbc.update("""
                INSERT INTO source_task_actions
                    (id, space_id, resource_type, resource_id, operation, status, idempotency_key,
                     request_hash, expected_version, result_version, reason, actor_user_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'REQUESTED', ?, ?, ?, ?, ?, ?, ?, ?)
                """, actionId, spaceId, resourceType.name(), resourceId, operation.name(), idempotencyKey,
                requestHash, expectedVersion, expectedVersion, reason, actor, Timestamp.from(now), Timestamp.from(now));
        int resultVersion = apply(spaceId, resourceType, resourceId, operation, expectedVersion, actionId,
                actor, requestBody, request, now);
        String resultStatus = operation == Operation.ARCHIVE ? "ARCHIVED"
                : operation == Operation.DELETE ? "DELETED" : "ACCEPTED";
        jdbc.update("UPDATE source_task_actions SET status = ?, result_version = ?, updated_at = ? WHERE space_id = ? AND id = ?",
                resultStatus, resultVersion, Timestamp.from(now), spaceId, actionId);
        return findById(spaceId, actionId).orElseThrow();
    }

    private int apply(UUID spaceId, ResourceType type, UUID id, Operation operation, int expectedVersion,
                       UUID actionId, UUID actor, ActionRequest body, HttpServletRequest request, Instant now) {
        if (type == ResourceType.SOURCE) {
            if (operation == Operation.RETRY || operation == Operation.REPLAY || operation == Operation.RESYNC) {
                UUID correlation = UUID.fromString(CorrelationIdFilter.current(request));
                UUID pipeline = UUID.randomUUID();
                UUID job = UUID.randomUUID();
                String mode = operation == Operation.RETRY ? "INCREMENTAL_SYNC" : "FULL_SYNC";
                ingestion.createPipelineVersion(new IngestionRepository.NewPipelineVersion(pipeline, spaceId,
                        nextPipeline(spaceId), "source-task-center", "connector", "1.0.0",
                        "source-task-center-v1", correlation, now));
                ingestion.createJob(new IngestionRepository.NewIngestionJob(job, spaceId, id, null, null, pipeline,
                        IngestionRepository.JobStatus.REQUESTED, "action-" + actionId, correlation, actionId, 1, now));
                outbox.record("source.sync.requested.v1", actor, spaceId, id, correlation, job,
                        Map.of("jobId", job, "sourceId", id, "operation", mode));
                return expectedVersion;
            }
            int updated = jdbc.update("""
                    UPDATE sources SET lifecycle_state = ?, version_no = version_no + 1,
                        archived_at = CASE WHEN ? = 'ARCHIVED' THEN ? ELSE archived_at END,
                        deleted_at = CASE WHEN ? = 'DELETED' THEN ? ELSE deleted_at END
                    WHERE space_id = ? AND id = ? AND version_no = ? AND lifecycle_state = 'ACTIVE'
                    """, operation == Operation.ARCHIVE ? "ARCHIVED" : "DELETED",
                    operation == Operation.ARCHIVE ? "ARCHIVED" : "DELETED", Timestamp.from(now),
                    operation == Operation.DELETE ? "DELETED" : "ARCHIVED", Timestamp.from(now),
                    spaceId, id, expectedVersion);
            if (updated != 1) throw precondition("source was changed concurrently");
            return expectedVersion + 1;
        }
        if (type == ResourceType.JOB) {
            if (operation == Operation.RETRY || operation == Operation.REPLAY || operation == Operation.RESYNC) {
                int updated = jdbc.update("""
                        UPDATE ingestion_jobs SET status = 'REQUESTED', version_no = version_no + 1, updated_at = ?
                        WHERE space_id = ? AND id = ? AND version_no = ? AND lifecycle_state = 'ACTIVE'
                        """, Timestamp.from(now), spaceId, id, expectedVersion);
                if (updated != 1) throw precondition("job was changed concurrently");
                return expectedVersion + 1;
            }
            int updated = jdbc.update("""
                    UPDATE ingestion_jobs SET lifecycle_state = ?, version_no = version_no + 1, updated_at = ?
                    WHERE space_id = ? AND id = ? AND version_no = ? AND lifecycle_state = 'ACTIVE'
                    """, operation == Operation.ARCHIVE ? "ARCHIVED" : "DELETED", Timestamp.from(now),
                    spaceId, id, expectedVersion);
            if (updated != 1) throw precondition("job was changed concurrently");
            return expectedVersion + 1;
        }
        int updated = jdbc.update("""
                UPDATE index_versions SET lifecycle_state = ?, lifecycle_version = lifecycle_version + 1
                WHERE space_id = ? AND id = ? AND lifecycle_version = ? AND lifecycle_state = 'ACTIVE'
                """, operation == Operation.ARCHIVE ? "ARCHIVED" : "DELETED", spaceId, id, expectedVersion);
        if (updated != 1) throw precondition("index was changed concurrently");
        return expectedVersion + 1;
    }

    private int lockAndReadVersion(UUID spaceId, ResourceType type, UUID id) {
        String table = switch (type) {
            case SOURCE -> "sources";
            case JOB -> "ingestion_jobs";
            case INDEX -> "index_versions";
        };
        String column = type == ResourceType.INDEX ? "lifecycle_version" : "version_no";
        try {
            return jdbc.queryForObject("SELECT " + column + " FROM " + table
                    + " WHERE space_id = ? AND id = ? AND lifecycle_state = 'ACTIVE' FOR UPDATE",
                    Integer.class, spaceId, id);
        } catch (EmptyResultDataAccessException exception) {
            throw notFound("resource not found in requested space");
        }
    }

    private Optional<TaskActionView> findByKey(UUID spaceId, String key) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(actionSql("idempotency_key = ?"),
                    (rs, row) -> mapAction(rs), spaceId, key));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    private Optional<TaskActionView> findById(UUID spaceId, UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(actionSql("id = ?"),
                    (rs, row) -> mapAction(rs), spaceId, id));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    private static String actionSql(String predicate) {
        return "SELECT id, space_id, resource_type, resource_id, operation, status, idempotency_key, request_hash, "
                + "expected_version, result_version, reason, actor_user_id, created_at, updated_at FROM source_task_actions "
                + "WHERE space_id = ? AND " + predicate;
    }

    private static TaskActionView mapAction(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TaskActionView(rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                ResourceType.valueOf(rs.getString("resource_type")), rs.getObject("resource_id", UUID.class),
                Operation.valueOf(rs.getString("operation")), rs.getString("status"), rs.getString("idempotency_key"),
                rs.getInt("expected_version"), (Integer) rs.getObject("result_version"), rs.getString("reason"),
                rs.getObject("actor_user_id", UUID.class), instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getString("request_hash"));
    }

    private static IngestionRepository.IngestionJob mapJob(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new IngestionRepository.IngestionJob(rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getObject("source_id", UUID.class), rs.getObject("source_document_id", UUID.class),
                rs.getObject("document_revision_id", UUID.class), rs.getObject("pipeline_version_id", UUID.class),
                IngestionRepository.JobStatus.valueOf(rs.getString("status")), rs.getString("idempotency_key"),
                rs.getObject("correlation_id", UUID.class), rs.getObject("causation_id", UUID.class),
                rs.getInt("version_no"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private int nextPipeline(UUID spaceId) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(version_no), 0) + 1 FROM pipeline_versions WHERE space_id = ? AND pipeline_name = ?",
                Integer.class, spaceId, "source-task-center");
    }

    private static int pageSize(Integer limit) {
        if (limit == null) return DEFAULT_LIMIT;
        if (limit < 1 || limit > MAX_LIMIT) throw invalid("limit must be between 1 and 100");
        return limit;
    }

    private static String enumValue(String value, String field) {
        if (!value.matches("[A-Za-z_]{1,32}")) throw invalid(field + " is invalid");
        return value.toUpperCase(java.util.Locale.ROOT);
    }

    private static String searchText(String value) {
        if (value == null || value.isBlank()) return null;
        String search = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (search.length() > 120) throw invalid("q must be at most 120 characters");
        return search;
    }

    private static int expectedVersion(ActionRequest body, String ifMatch) {
        if (body != null && body.version() != null) return body.version();
        if (ifMatch != null) {
            String value = ifMatch.trim().replaceAll("^\"|\"$", "");
            try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { }
        }
        throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "precondition_required", "Precondition required",
                "version or If-Match is required");
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }
    private static ApiException invalid(String detail) { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "Validation failed", detail); }
    private static ApiException notFound(String detail) { return new ApiException(HttpStatus.NOT_FOUND, "resource_not_found", "Resource not found", detail); }
    private static ApiException conflict(String detail) { return new ApiException(HttpStatus.CONFLICT, "idempotency_key_conflict", "Idempotency key conflict", detail); }
    private static ApiException precondition(String detail) { return new ApiException(HttpStatus.PRECONDITION_FAILED, "optimistic_lock_failed", "Precondition failed", detail); }

    private record SourceRow(IngestionRepository.SourceVersion source, Instant sortTime) { }
    private record JobRow(IngestionRepository.IngestionJob job, Instant sortTime) { }
    public enum ResourceType { SOURCE, JOB, INDEX }
    public enum Operation { RETRY, REPLAY, RESYNC, ARCHIVE, DELETE }
    public record ActionRequest(Integer version, String reason, String mode) { }
    public record TaskActionView(UUID actionId, UUID spaceId, ResourceType resourceType, UUID resourceId,
                                 Operation operation, String status, String idempotencyKey, int expectedVersion,
                                 Integer resultVersion, String reason, UUID actorUserId, Instant createdAt,
                                 Instant updatedAt, String requestHash) { }
}
