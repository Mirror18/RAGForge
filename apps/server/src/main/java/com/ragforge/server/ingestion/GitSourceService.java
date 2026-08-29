package com.ragforge.server.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.common.ApiException;
import com.ragforge.server.common.CorrelationIdFilter;
import com.ragforge.server.common.UuidV7;
import com.ragforge.server.audit.AuditOutboxService;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.provider.SpaceAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAlias;

/** Space-scoped Git source configuration and synchronization command boundary. */
@Service
@EnableScheduling
public class GitSourceService {
    private final IngestionRepository repository;
    private final JdbcTemplate jdbc;
    private final SpaceAuthorization authorization;
    private final AuditOutboxService outbox;
    private final boolean syncEnabled;

    public GitSourceService(IngestionRepository repository, JdbcTemplate jdbc, SpaceAuthorization authorization,
                            AuditOutboxService outbox,
                            @Value("${ragforge.git-source.sync.enabled:false}") boolean syncEnabled) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.outbox = outbox;
        this.syncEnabled = syncEnabled;
    }

    /** Optional server-side scheduler; remote access remains disabled unless explicitly configured. */
    @Scheduled(fixedDelayString = "${ragforge.git-source.sync.interval-ms:3600000}")
    @Transactional
    public void scheduledIncrementalSync() {
        if (!syncEnabled) return;
        jdbc.query("""
                SELECT v.space_id, v.source_id FROM source_versions v
                WHERE v.connector_type = 'GIT' AND v.source_state = 'ACTIVE'
                  AND v.version_no = (SELECT MAX(v2.version_no) FROM source_versions v2
                      WHERE v2.space_id = v.space_id AND v2.source_id = v.source_id)
                """, (rs, row) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}).forEach(pair -> {
            Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM ingestion_jobs WHERE space_id = ? AND source_id = ? AND status IN ('REQUESTED', 'RUNNING', 'RETRY_SCHEDULED') AND document_revision_id IS NULL", Integer.class, pair[0], pair[1]);
            if (active == 0) enqueueScheduled(pair[0], pair[1]);
        });
    }

    private void enqueueScheduled(UUID spaceId, UUID sourceId) {
        var source = repository.listCurrentSources(spaceId).stream().filter(value -> value.sourceId().equals(sourceId)).findFirst().orElseThrow();
        UUID jobId = UuidV7.random(); UUID correlation = UuidV7.random(); UUID pipeline = UuidV7.random(); Instant now = Instant.now();
        repository.createPipelineVersion(new IngestionRepository.NewPipelineVersion(pipeline, spaceId, nextPipeline(spaceId), "git-source-sync", "connector", "1.0.0", "git-source-sync-v1", correlation, now));
        repository.createJob(new IngestionRepository.NewIngestionJob(jobId, spaceId, sourceId, null, null, pipeline, IngestionRepository.JobStatus.REQUESTED, "scheduled-" + sourceId + "-" + (now.toEpochMilli() / 3600000), correlation, null, 1, now));
        outbox.record("source.sync.requested.v1", null, spaceId, sourceId, correlation, jobId, java.util.Map.of("jobId", jobId, "sourceId", sourceId, "operation", "INCREMENTAL_SYNC"));
    }

    @Transactional
    public SourceView configure(UUID spaceId, GitSourceRequest request, SessionPrincipal principal,
                                HttpServletRequest httpRequest) {
        authorization.requireWrite(spaceId, principal);
        validate(request);
        UUID sourceId = request.sourceId() == null ? UuidV7.random() : request.sourceId();
        ensureSourceBelongsToSpace(spaceId, sourceId);
        Instant now = Instant.now();
        UUID correlation = UUID.fromString(CorrelationIdFilter.current(httpRequest));
        int version = jdbc.queryForObject("SELECT COALESCE(MAX(version_no), 0) + 1 FROM source_versions WHERE space_id = ? AND source_id = ?", Integer.class, spaceId, sourceId);
        var source = repository.createSourceVersion(new IngestionRepository.NewSourceVersion(
                UuidV7.random(), spaceId, sourceId, version, IngestionRepository.ConnectorType.GIT,
                request.displayName().trim(), IngestionRepository.SourceState.ACTIVE, request.remote().trim(),
                jsonArray(request.include()), jsonArray(request.exclude()), false, correlation, now, request.branch().trim()));
        if (repository.findCheckpoint(spaceId, sourceId).isEmpty()) {
            repository.createCheckpoint(new IngestionRepository.NewSourceCheckpoint(UuidV7.random(), spaceId, sourceId,
                    source.id(), source.versionNo(), IngestionRepository.CursorType.NONE, null, null, now));
        }
        return view(source, repository.findCheckpoint(spaceId, sourceId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<SourceView> list(UUID spaceId, SessionPrincipal principal) {
        authorization.requireMember(spaceId, principal);
        return repository.listCurrentSources(spaceId).stream().map(source -> view(source,
                repository.findCheckpoint(spaceId, source.sourceId()).orElse(null))).toList();
    }

    @Transactional(readOnly = true)
    public SourceView get(UUID spaceId, UUID sourceId, SessionPrincipal principal) {
        authorization.requireMember(spaceId, principal);
        return repository.listCurrentSources(spaceId).stream().filter(source -> source.sourceId().equals(sourceId))
                .findFirst().map(source -> view(source, repository.findCheckpoint(spaceId, sourceId).orElse(null)))
                .orElseThrow(() -> notFound("Git source not found"));
    }

    @Transactional
    public SyncCommand synchronize(UUID spaceId, UUID sourceId, String mode, SessionPrincipal principal,
                                   HttpServletRequest request) {
        return synchronize(spaceId, sourceId, mode, null, principal, request);
    }

    @Transactional
    public SyncCommand synchronize(UUID spaceId, UUID sourceId, String mode, String idempotencyKey,
                                   SessionPrincipal principal, HttpServletRequest request) {
        authorization.requireWrite(spaceId, principal);
        var source = repository.listCurrentSources(spaceId).stream().filter(value -> value.sourceId().equals(sourceId)
                && value.connectorType() == IngestionRepository.ConnectorType.GIT).findFirst()
                .orElseThrow(() -> notFound("Git source not found"));
        String operation = normalizeOperation(mode);
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? "sync-" + UUID.randomUUID() : idempotencyKey;
        if (!key.matches("[A-Za-z0-9._~-]{1,255}")) throw invalid("Idempotency-Key is invalid");
        var existing = jdbc.query("SELECT id, status FROM ingestion_jobs WHERE space_id = ? AND source_id = ? AND idempotency_key = ?", (rs, row) -> new SyncCommand(rs.getObject("id", UUID.class), sourceId, operation, rs.getString("status"), source.gitBranch(), source.rootRef()), spaceId, sourceId, key);
        if (!existing.isEmpty()) return existing.get(0);
        UUID jobId = UuidV7.random();
        UUID correlation = UUID.fromString(CorrelationIdFilter.current(request));
        UUID pipeline = UuidV7.random();
        Instant now = Instant.now();
        repository.createPipelineVersion(new IngestionRepository.NewPipelineVersion(pipeline, spaceId,
                nextPipeline(spaceId), "git-source-sync", "connector", "1.0.0",
                "git-source-sync-v1", correlation, now));
        repository.createJob(new IngestionRepository.NewIngestionJob(jobId, spaceId, sourceId, null, null, pipeline,
                IngestionRepository.JobStatus.REQUESTED, key, correlation, null, 1, now));
        // The durable outbox is the hand-off to the separately deployable worker.
        // The payload contains configuration identity only; credentials and source bytes never leave PostgreSQL.
        outbox.record("source.sync.requested.v1", principal.userId(), spaceId, sourceId, correlation, jobId,
                java.util.Map.of("jobId", jobId, "sourceId", sourceId, "operation", operation));
        return new SyncCommand(jobId, sourceId, operation, "REQUESTED", source.gitBranch(), source.rootRef());
    }

    private int nextPipeline(UUID spaceId) { return jdbc.queryForObject("SELECT COALESCE(MAX(version_no), 0) + 1 FROM pipeline_versions WHERE space_id = ? AND pipeline_name = ?", Integer.class, spaceId, "git-source-sync"); }
    private void ensureSourceBelongsToSpace(UUID spaceId, UUID sourceId) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sources WHERE space_id = ? AND id = ?", Integer.class, spaceId, sourceId) == 0
                && jdbc.queryForObject("SELECT COUNT(*) FROM sources WHERE id = ?", Integer.class, sourceId) > 0) {
            throw notFound("Git source not found");
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sources WHERE space_id = ? AND id = ?", Integer.class, spaceId, sourceId) == 0) {
            jdbc.update("INSERT INTO sources (id, space_id, created_at) VALUES (?, ?, ?)", sourceId, spaceId, java.sql.Timestamp.from(Instant.now()));
        }
    }
    private static void validate(GitSourceRequest r) {
        if (r == null || r.remote() == null || r.remote().isBlank() || r.displayName() == null || r.displayName().isBlank()
                || r.branch() == null || r.branch().isBlank()) throw invalid("Git source configuration is incomplete");
        if (r.connectorType() != null && !"GIT".equalsIgnoreCase(r.connectorType())) throw invalid("Only GIT sources are supported by this endpoint");
        if (r.displayName().trim().length() > 120 || r.remote().trim().length() > 512 || r.branch().trim().length() > 255)
            throw invalid("Git source fields exceed their limits");
        if (r.remote().chars().anyMatch(Character::isWhitespace)) throw invalid("Git remote must not contain whitespace");
        if (!(r.remote().startsWith("https://") || r.remote().startsWith("ssh://") || r.remote().startsWith("git@")))
            throw invalid("Git remote must use HTTPS or SSH");
        try {
            URI remote = URI.create(r.remote());
            if ((r.remote().startsWith("https://") || r.remote().startsWith("ssh://"))
                    && remote.getHost() == null)
                throw invalid("Git remote host is missing");
            if (r.remote().startsWith("https://") && remote.getUserInfo() != null)
                throw invalid("Git HTTPS remote must not contain embedded credentials");
            if (r.remote().startsWith("ssh://") && remote.getUserInfo() != null
                    && remote.getUserInfo().contains(":"))
                throw invalid("Git SSH remote must not contain an embedded password");
            if (r.remote().startsWith("git@") && !r.remote().matches("git@[^/:\\s]+:.+"))
                throw invalid("Git SSH remote is invalid");
        } catch (IllegalArgumentException exception) {
            throw invalid("Git remote is invalid");
        }
        if (r.include() != null && r.include().size() > 100 || r.exclude() != null && r.exclude().size() > 100) throw invalid("include/exclude rules are limited to 100 entries");
        validateRules(r.include());
        validateRules(r.exclude());
    }
    private static void validateRules(List<String> rules) {
        if (rules == null) return;
        if (rules.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 512 || value.indexOf('\0') >= 0))
            throw invalid("include/exclude rules contain an invalid entry");
    }
    private static String jsonArray(List<String> values) {
        try {
            return new ObjectMapper().writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw invalid("include/exclude rules cannot be serialized");
        }
    }
    private static SourceView view(IngestionRepository.SourceVersion s, IngestionRepository.SourceCheckpoint c) { return new SourceView(s, c); }
    private static ApiException invalid(String detail) { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "Validation failed", detail); }
    private static ApiException notFound(String detail) { return new ApiException(HttpStatus.NOT_FOUND, "resource_not_found", "Resource not found", detail); }
    private static String normalizeOperation(String mode) {
        if (mode == null || mode.isBlank() || "INCREMENTAL".equalsIgnoreCase(mode)
                || "INCREMENTAL_SYNC".equalsIgnoreCase(mode)) return "INCREMENTAL_SYNC";
        if ("FULL".equalsIgnoreCase(mode) || "FULL_SYNC".equalsIgnoreCase(mode)) return "FULL_SYNC";
        throw invalid("Sync mode must be FULL_SYNC or INCREMENTAL_SYNC");
    }
    public record GitSourceRequest(UUID sourceId, String displayName, @JsonAlias("rootRef") String remote,
                                   String branch, List<String> include, List<String> exclude, String connectorType) {
        public GitSourceRequest {
            branch = branch == null || branch.isBlank() ? "main" : branch;
            include = immutableList(include);
            exclude = immutableList(exclude);
        }
        public GitSourceRequest(UUID sourceId, String displayName, String remote, String branch,
                                List<String> include, List<String> exclude) {
            this(sourceId, displayName, remote, branch, include, exclude, "GIT");
        }
        private static List<String> immutableList(List<String> values) {
            return values == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
        }
    }
    public record SourceView(IngestionRepository.SourceVersion source, IngestionRepository.SourceCheckpoint checkpoint) {}
    public record SyncCommand(UUID jobId, UUID sourceId, String operation, String status, String branch, String remote) {}
    public record SyncRequest(String mode, UUID pipelineVersionId, List<UUID> documentIds) {}
}
