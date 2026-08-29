package com.ragforge.server.ingestion;

import com.ragforge.server.common.ApiException;
import com.ragforge.server.common.CorrelationIdFilter;
import com.ragforge.server.common.UuidV7;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.provider.SpaceAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Space-scoped Git source configuration and synchronization command boundary. */
@Service
public class GitSourceService {
    private final IngestionRepository repository;
    private final JdbcTemplate jdbc;
    private final SpaceAuthorization authorization;

    public GitSourceService(IngestionRepository repository, JdbcTemplate jdbc, SpaceAuthorization authorization) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.authorization = authorization;
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

    @Transactional
    public SyncCommand synchronize(UUID spaceId, UUID sourceId, String mode, SessionPrincipal principal,
                                   HttpServletRequest request) {
        authorization.requireWrite(spaceId, principal);
        var source = repository.listCurrentSources(spaceId).stream().filter(value -> value.sourceId().equals(sourceId)
                && value.connectorType() == IngestionRepository.ConnectorType.GIT).findFirst()
                .orElseThrow(() -> notFound("Git source not found"));
        String operation = "FULL".equalsIgnoreCase(mode) ? "FULL_SYNC" : "INCREMENTAL_SYNC";
        UUID jobId = UuidV7.random();
        UUID correlation = UUID.fromString(CorrelationIdFilter.current(request));
        UUID pipeline = UuidV7.random();
        Instant now = Instant.now();
        repository.createPipelineVersion(new IngestionRepository.NewPipelineVersion(pipeline, spaceId,
                nextPipeline(spaceId), "git-source-sync", "connector", "1.0.0",
                "git-source-sync-v1", correlation, now));
        repository.createJob(new IngestionRepository.NewIngestionJob(jobId, spaceId, sourceId, null, null, pipeline,
                IngestionRepository.JobStatus.REQUESTED, "sync-" + jobId, correlation, null, 1, now));
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
        if (!(r.remote().startsWith("https://") || r.remote().startsWith("ssh://") || r.remote().startsWith("git@")))
            throw invalid("Git remote must use HTTPS or SSH");
        if (r.include() != null && r.include().size() > 100 || r.exclude() != null && r.exclude().size() > 100) throw invalid("include/exclude rules are limited to 100 entries");
    }
    private static String jsonArray(List<String> values) { if (values == null || values.isEmpty()) return "[]"; return "[" + values.stream().map(v -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").reduce((a,b) -> a + "," + b).orElse("") + "]"; }
    private static SourceView view(IngestionRepository.SourceVersion s, IngestionRepository.SourceCheckpoint c) { return new SourceView(s, c); }
    private static ApiException invalid(String detail) { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "Validation failed", detail); }
    private static ApiException notFound(String detail) { return new ApiException(HttpStatus.NOT_FOUND, "resource_not_found", "Resource not found", detail); }
    public record GitSourceRequest(UUID sourceId, String displayName, String remote, String branch, List<String> include, List<String> exclude) {}
    public record SourceView(IngestionRepository.SourceVersion source, IngestionRepository.SourceCheckpoint checkpoint) {}
    public record SyncCommand(UUID jobId, UUID sourceId, String operation, String status, String branch, String remote) {}
}
