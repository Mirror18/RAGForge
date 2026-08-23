package com.ragforge.server.ingestion;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Space-scoped persistence seam for the versioned ingestion pipeline.
 *
 * <p>Raw bytes and extracted text are intentionally absent from every method;
 * callers pass only object references, hashes and bounded metadata. The SQL
 * predicates repeat {@code space_id} even where a composite foreign key also
 * exists so an accidental cross-space lookup fails closed.</p>
 */
@Repository
public class IngestionRepository {
    private final JdbcTemplate jdbc;

    public IngestionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public SourceVersion createSourceVersion(NewSourceVersion input) {
        jdbc.update("""
                INSERT INTO sources (id, space_id, created_at)
                VALUES (?, ?, ?)
                ON CONFLICT (id, space_id) DO NOTHING
                """, input.sourceId(), input.spaceId(), timestamp(input.now()));
        jdbc.update("""
                INSERT INTO source_versions
                    (id, space_id, source_id, version_no, connector_type, display_name,
                     source_state, read_only, root_ref, include_rules, exclude_rules,
                     credential_configured, correlation_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?)
                """, input.id(), input.spaceId(), input.sourceId(), input.versionNo(),
                input.connectorType().name(), input.displayName(), input.sourceState().name(),
                input.rootRef(), jsonOrEmptyArray(input.includeRulesJson()),
                jsonOrEmptyArray(input.excludeRulesJson()), input.credentialConfigured(),
                input.correlationId(), timestamp(input.now()), timestamp(input.now()));
        return findSourceVersion(input.spaceId(), input.sourceId(), input.versionNo()).orElseThrow();
    }

    public Optional<SourceVersion> findSourceVersion(UUID spaceId, UUID sourceId, int versionNo) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, space_id, source_id, version_no, connector_type, display_name,
                           source_state, root_ref, include_rules::text, exclude_rules::text,
                           credential_configured, correlation_id, created_at, updated_at
                    FROM source_versions
                    WHERE space_id = ? AND source_id = ? AND version_no = ?
                    """, (rs, row) -> new SourceVersion(
                    rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                    rs.getObject("source_id", UUID.class), rs.getInt("version_no"),
                    ConnectorType.valueOf(rs.getString("connector_type")), rs.getString("display_name"),
                    SourceState.valueOf(rs.getString("source_state")), rs.getString("root_ref"),
                    rs.getString("include_rules"), rs.getString("exclude_rules"),
                    rs.getBoolean("credential_configured"), rs.getObject("correlation_id", UUID.class),
                    instant(rs, "created_at"), instant(rs, "updated_at")),
                    spaceId, sourceId, versionNo));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Transactional
    public SourceCheckpoint createCheckpoint(NewSourceCheckpoint input) {
        jdbc.update("""
                INSERT INTO source_checkpoints
                    (id, space_id, source_id, source_version_id, version_no, cursor_type,
                     cursor_value, last_successful_changeset_id, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, input.id(), input.spaceId(), input.sourceId(), input.sourceVersionId(),
                input.versionNo(), input.cursorType().name(), input.cursor(), input.changeSetId(),
                timestamp(input.updatedAt()));
        return findCheckpoint(input.spaceId(), input.sourceId()).orElseThrow();
    }

    public Optional<SourceCheckpoint> findCheckpoint(UUID spaceId, UUID sourceId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, space_id, source_id, source_version_id, version_no, cursor_type,
                           cursor_value, last_successful_changeset_id, updated_at
                    FROM source_checkpoints WHERE space_id = ? AND source_id = ?
                    """, (rs, row) -> new SourceCheckpoint(
                    rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                    rs.getObject("source_id", UUID.class), rs.getObject("source_version_id", UUID.class),
                    rs.getInt("version_no"), CursorType.valueOf(rs.getString("cursor_type")),
                    rs.getString("cursor_value"), rs.getObject("last_successful_changeset_id", UUID.class),
                    instant(rs, "updated_at")), spaceId, sourceId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Transactional
    public SourceDocument createSourceDocument(NewSourceDocument input) {
        jdbc.update("""
                INSERT INTO source_documents
                    (id, space_id, source_id, stable_source_object_id, canonical_source_path,
                     basename, version_no, current_state, active_revision_id, correlation_id,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, input.id(), input.spaceId(), input.sourceId(), input.stableSourceObjectId(),
                input.canonicalSourcePath(), input.basename(), input.versionNo(), input.state().name(),
                input.activeRevisionId(), input.correlationId(), timestamp(input.now()), timestamp(input.now()));
        return findSourceDocument(input.spaceId(), input.id()).orElseThrow();
    }

    public Optional<SourceDocument> findSourceDocument(UUID spaceId, UUID documentId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, space_id, source_id, stable_source_object_id, canonical_source_path,
                           basename, version_no, current_state, active_revision_id, correlation_id,
                           created_at, updated_at
                    FROM source_documents WHERE space_id = ? AND id = ?
                    """, (rs, row) -> new SourceDocument(
                    rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                    rs.getObject("source_id", UUID.class), rs.getString("stable_source_object_id"),
                    rs.getString("canonical_source_path"), rs.getString("basename"),
                    rs.getInt("version_no"), DocumentState.valueOf(rs.getString("current_state")),
                    rs.getObject("active_revision_id", UUID.class), rs.getObject("correlation_id", UUID.class),
                    instant(rs, "created_at"), instant(rs, "updated_at")), spaceId, documentId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<SourceDocument> findSourceDocumentByPath(UUID spaceId, UUID sourceId, String path) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, space_id, source_id, stable_source_object_id, canonical_source_path,
                           basename, version_no, current_state, active_revision_id, correlation_id,
                           created_at, updated_at
                    FROM source_documents
                    WHERE space_id = ? AND source_id = ? AND stable_source_object_id = ?
                    """, (rs, row) -> new SourceDocument(
                    rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                    rs.getObject("source_id", UUID.class), rs.getString("stable_source_object_id"),
                    rs.getString("canonical_source_path"), rs.getString("basename"),
                    rs.getInt("version_no"), DocumentState.valueOf(rs.getString("current_state")),
                    rs.getObject("active_revision_id", UUID.class), rs.getObject("correlation_id", UUID.class),
                    instant(rs, "created_at"), instant(rs, "updated_at")), spaceId, sourceId, path));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public List<SourceDocument> listSourceDocuments(UUID spaceId) {
        return jdbc.query("""
                SELECT id, space_id, source_id, stable_source_object_id, canonical_source_path,
                       basename, version_no, current_state, active_revision_id, correlation_id,
                       created_at, updated_at
                FROM source_documents WHERE space_id = ? ORDER BY canonical_source_path, id
                """, (rs, row) -> new SourceDocument(
                rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getObject("source_id", UUID.class), rs.getString("stable_source_object_id"),
                rs.getString("canonical_source_path"), rs.getString("basename"), rs.getInt("version_no"),
                DocumentState.valueOf(rs.getString("current_state")), rs.getObject("active_revision_id", UUID.class),
                rs.getObject("correlation_id", UUID.class), instant(rs, "created_at"), instant(rs, "updated_at")),
                spaceId);
    }

    @Transactional
    public PipelineVersion createPipelineVersion(NewPipelineVersion input) {
        jdbc.update("""
                INSERT INTO pipeline_versions
                    (id, space_id, version_no, pipeline_name, parser_name, parser_version,
                     configuration_hash, correlation_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, input.id(), input.spaceId(), input.versionNo(), input.pipelineName(), input.parserName(),
                input.parserVersion(), input.configurationHash(), input.correlationId(), timestamp(input.createdAt()));
        return findPipelineVersion(input.spaceId(), input.id()).orElseThrow();
    }

    public Optional<PipelineVersion> findPipelineVersion(UUID spaceId, UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, space_id, version_no, pipeline_name, parser_name, parser_version,
                           configuration_hash, correlation_id, created_at
                    FROM pipeline_versions WHERE space_id = ? AND id = ?
                    """, (rs, row) -> new PipelineVersion(
                    rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class), rs.getInt("version_no"),
                    rs.getString("pipeline_name"), rs.getString("parser_name"), rs.getString("parser_version"),
                    rs.getString("configuration_hash"), rs.getObject("correlation_id", UUID.class),
                    instant(rs, "created_at")), spaceId, id));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    /** Creates the immutable discovery anchor required before the asynchronous job exists. */
    @Transactional
    public void createDiscoveryRevision(NewDiscoveryRevision input) {
        jdbc.update("""
                INSERT INTO document_revisions
                    (id, space_id, source_document_id, revision_no, source_version,
                     canonical_source_path, content_hash, revision_state, immutable,
                     discovered_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'DISCOVERED', TRUE, ?, ?)
                """, input.revisionId(), input.spaceId(), input.sourceDocumentId(), input.revisionNo(),
                input.sourceVersion(), input.canonicalSourcePath(), input.contentHash(),
                timestamp(input.discoveredAt()), timestamp(input.discoveredAt()));
    }

    /** Persists the mutually-referencing immutable revision/artifact/report set in one transaction. */
    @Transactional
    public RevisionBundle persistRevisionBundle(RevisionBundleInput input) {
        if (input.parseStatus() == ParseStatus.SUCCEEDED && input.extractedTextArtifactId() == null) {
            throw new IllegalArgumentException("successful parse requires an extracted text artifact reference");
        }
        if (input.parseStatus() != ParseStatus.SUCCEEDED && input.extractedTextArtifactId() != null) {
            throw new IllegalArgumentException("failed parse cannot publish an extracted text artifact reference");
        }
        jdbc.update("""
                INSERT INTO document_revisions
                    (id, space_id, source_document_id, revision_no, source_version,
                     canonical_source_path, content_hash, source_artifact_id, parse_report_id,
                     revision_state, immutable, git_commit_sha, discovered_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?, ?)
                """, input.revisionId(), input.spaceId(), input.sourceDocumentId(), input.revisionNo(),
                input.sourceVersion(), input.canonicalSourcePath(), input.contentHash(), input.artifactId(),
                input.parseReportId(), input.parseStatus() == ParseStatus.SUCCEEDED ? "PARSED" : "FAILED",
                input.gitCommitSha(), timestamp(input.discoveredAt()), timestamp(input.createdAt()));
        jdbc.update("""
                INSERT INTO artifacts
                    (id, space_id, source_document_id, document_revision_id, version_no, artifact_kind,
                     media_type, byte_length, sha256, storage_uri, metadata, immutable, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), TRUE, ?)
                """, input.artifactId(), input.spaceId(), input.sourceDocumentId(), input.revisionId(),
                input.artifactVersion(), input.artifactKind().name(), input.mediaType(), input.byteLength(),
                input.artifactSha256(), input.storageUri(), jsonOrEmptyObject(input.metadataJson()), timestamp(input.createdAt()));
        jdbc.update("""
                INSERT INTO parse_reports
                    (id, space_id, document_revision_id, source_artifact_id, version_no, status,
                     media_type, page_count, character_count, token_count, native_page_count, ocr_page_count,
                     parser_name, parser_version, duration_ms, warnings, errors, extracted_text_artifact_id,
                     ocr_status, ocr_engine, ocr_engine_version, ocr_trigger_reason, ocr_audit_state, immutable, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, TRUE, ?)
                """, input.parseReportId(), input.spaceId(), input.revisionId(), input.artifactId(), input.parseReportVersion(),
                input.parseStatus().name(), input.mediaType(), input.pageCount(), input.characterCount(), input.tokenCount(),
                input.nativePageCount(), input.ocrPageCount(), input.parserName(), input.parserVersion(), input.durationMs(),
                jsonOrEmptyArray(input.warningsJson()), jsonOrEmptyArray(input.errorsJson()), input.extractedTextArtifactId(),
                input.ocrStatus().name(), input.ocrEngine(), input.ocrEngineVersion(), input.ocrTriggerReason().name(),
                input.ocrAuditState().name(), timestamp(input.createdAt()));
        return new RevisionBundle(input.revisionId(), input.artifactId(), input.parseReportId());
    }

    public Optional<RevisionBundle> findRevisionBundle(UUID spaceId, UUID revisionId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT r.id AS revision_id, r.source_artifact_id, r.parse_report_id
                    FROM document_revisions r
                    WHERE r.space_id = ? AND r.id = ?
                    """, (rs, row) -> new RevisionBundle(rs.getObject("revision_id", UUID.class),
                    rs.getObject("source_artifact_id", UUID.class), rs.getObject("parse_report_id", UUID.class)),
                    spaceId, revisionId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Transactional
    public ActivePointer publishActivePointer(NewActivePointer input) {
        Boolean parsed = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM document_revisions
                    WHERE id = ? AND space_id = ? AND revision_state = 'PARSED')
                """, Boolean.class, input.activeRevisionId(), input.spaceId());
        if (!Boolean.TRUE.equals(parsed)) {
            throw new IllegalArgumentException("active pointer requires a parsed revision in the requested space");
        }
        jdbc.update("""
                INSERT INTO active_document_pointers
                    (id, space_id, source_document_id, active_revision_id, version_no, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (space_id, source_document_id) DO UPDATE
                    SET active_revision_id = EXCLUDED.active_revision_id,
                        version_no = EXCLUDED.version_no,
                        updated_at = EXCLUDED.updated_at
                """, input.id(), input.spaceId(), input.sourceDocumentId(), input.activeRevisionId(),
                input.versionNo(), timestamp(input.updatedAt()));
        int updated = jdbc.update("""
                UPDATE source_documents SET active_revision_id = ?, version_no = ?, updated_at = ?
                WHERE id = ? AND space_id = ?
                """, input.activeRevisionId(), input.versionNo(), timestamp(input.updatedAt()),
                input.sourceDocumentId(), input.spaceId());
        if (updated != 1) {
            throw new IllegalArgumentException("source document is not available in the requested space");
        }
        return new ActivePointer(input.id(), input.spaceId(), input.sourceDocumentId(), input.activeRevisionId(),
                input.versionNo(), input.updatedAt());
    }

    @Transactional
    public PipelineStepExecution createStep(NewPipelineStep input) {
        jdbc.update("""
                INSERT INTO pipeline_step_executions
                    (id, space_id, job_id, attempt_id, step_name, status, attempt_no,
                     input_artifact_id, output_artifact_id, parse_report_id, retryable,
                     error_code, started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, input.id(), input.spaceId(), input.jobId(), input.attemptId(), input.stepName().name(),
                input.status().name(), input.attemptNo(), input.inputArtifactId(), input.outputArtifactId(),
                input.parseReportId(), input.retryable(), input.errorCode(), timestamp(input.startedAt()),
                input.finishedAt() == null ? null : timestamp(input.finishedAt()));
        return findStep(input.spaceId(), input.id()).orElseThrow();
    }

    public Optional<PipelineStepExecution> findStep(UUID spaceId, UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, space_id, job_id, attempt_id, step_name, status, attempt_no,
                           input_artifact_id, output_artifact_id, parse_report_id, retryable,
                           error_code, started_at, finished_at
                    FROM pipeline_step_executions WHERE space_id = ? AND id = ?
                    """, (rs, row) -> new PipelineStepExecution(
                    rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class), rs.getObject("job_id", UUID.class),
                    rs.getObject("attempt_id", UUID.class), StepName.valueOf(rs.getString("step_name")),
                    StepStatus.valueOf(rs.getString("status")), rs.getInt("attempt_no"),
                    rs.getObject("input_artifact_id", UUID.class), rs.getObject("output_artifact_id", UUID.class),
                    rs.getObject("parse_report_id", UUID.class), rs.getBoolean("retryable"), rs.getString("error_code"),
                    instant(rs, "started_at"), rs.getTimestamp("finished_at") == null ? null : instant(rs, "finished_at")),
                    spaceId, id));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Transactional
    public IngestionJob createJob(NewIngestionJob input) {
        jdbc.update("""
                INSERT INTO ingestion_jobs
                    (id, space_id, source_id, source_document_id, document_revision_id,
                     pipeline_version_id, status, idempotency_key, correlation_id, causation_id,
                     version_no, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, input.id(), input.spaceId(), input.sourceId(), input.sourceDocumentId(),
                input.documentRevisionId(), input.pipelineVersionId(), input.status().name(), input.idempotencyKey(),
                input.correlationId(), input.causationId(), input.versionNo(), timestamp(input.now()), timestamp(input.now()));
        return findJob(input.spaceId(), input.id()).orElseThrow();
    }

    public Optional<IngestionJob> findJob(UUID spaceId, UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, space_id, source_id, source_document_id, document_revision_id,
                           pipeline_version_id, status, idempotency_key, correlation_id, causation_id,
                           version_no, created_at, updated_at
                    FROM ingestion_jobs WHERE space_id = ? AND id = ?
                    """, (rs, row) -> new IngestionJob(
                    rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class), rs.getObject("source_id", UUID.class),
                    rs.getObject("source_document_id", UUID.class), rs.getObject("document_revision_id", UUID.class),
                    rs.getObject("pipeline_version_id", UUID.class), JobStatus.valueOf(rs.getString("status")),
                    rs.getString("idempotency_key"), rs.getObject("correlation_id", UUID.class),
                    rs.getObject("causation_id", UUID.class), rs.getInt("version_no"), instant(rs, "created_at"), instant(rs, "updated_at")),
                    spaceId, id));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<ExistingUpload> findExistingUpload(UUID spaceId, UUID sourceId, String idempotencyKey, String contentHash) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT j.id, a.id AS attempt_id, j.document_revision_id, r.source_artifact_id,
                           j.source_document_id, d.canonical_source_path, j.status
                    FROM ingestion_jobs j
                    JOIN source_documents d ON d.id = j.source_document_id AND d.space_id = j.space_id
                    LEFT JOIN document_revisions r ON r.id = j.document_revision_id AND r.space_id = j.space_id
                    LEFT JOIN ingestion_job_attempts a ON a.space_id = j.space_id AND a.job_id = j.id
                    WHERE j.space_id = ? AND j.source_id = ?
                      AND (j.idempotency_key = ? OR r.content_hash = ?)
                    ORDER BY j.created_at DESC, a.attempt_no DESC LIMIT 1
                    """, (rs, row) -> new ExistingUpload(rs.getObject("id", UUID.class),
                    rs.getObject("attempt_id", UUID.class), rs.getObject("document_revision_id", UUID.class),
                    rs.getObject("source_artifact_id", UUID.class), rs.getObject("source_document_id", UUID.class),
                    rs.getString("canonical_source_path"), rs.getString("status")),
                    spaceId, sourceId, idempotencyKey, contentHash));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public List<IngestionJob> listJobs(UUID spaceId) {
        return jdbc.query("""
                SELECT id, space_id, source_id, source_document_id, document_revision_id,
                       pipeline_version_id, status, idempotency_key, correlation_id, causation_id,
                       version_no, created_at, updated_at
                FROM ingestion_jobs WHERE space_id = ? ORDER BY created_at DESC LIMIT 100
                """, (rs, row) -> new IngestionJob(
                rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getObject("source_id", UUID.class), rs.getObject("source_document_id", UUID.class),
                rs.getObject("document_revision_id", UUID.class), rs.getObject("pipeline_version_id", UUID.class),
                JobStatus.valueOf(rs.getString("status")), rs.getString("idempotency_key"),
                rs.getObject("correlation_id", UUID.class), rs.getObject("causation_id", UUID.class),
                rs.getInt("version_no"), instant(rs, "created_at"), instant(rs, "updated_at")), spaceId);
    }

    public List<JobAttempt> listAttempts(UUID spaceId, UUID jobId) {
        return jdbc.query("""
                SELECT id, space_id, job_id, attempt_no, status, idempotency_key,
                       correlation_id, started_at, finished_at
                FROM ingestion_job_attempts WHERE space_id = ? AND job_id = ? ORDER BY attempt_no
                """, (rs, row) -> new JobAttempt(
                rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getObject("job_id", UUID.class), rs.getInt("attempt_no"),
                AttemptStatus.valueOf(rs.getString("status")), rs.getString("idempotency_key"),
                rs.getObject("correlation_id", UUID.class), instant(rs, "started_at"),
                rs.getTimestamp("finished_at") == null ? null : instant(rs, "finished_at")), spaceId, jobId);
    }

    public List<PipelineStepExecution> listSteps(UUID spaceId, UUID jobId) {
        return jdbc.query("""
                SELECT id, space_id, job_id, attempt_id, step_name, status, attempt_no,
                       input_artifact_id, output_artifact_id, parse_report_id, retryable,
                       error_code, started_at, finished_at
                FROM pipeline_step_executions WHERE space_id = ? AND job_id = ?
                ORDER BY started_at, id
                """, (rs, row) -> new PipelineStepExecution(
                rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getObject("job_id", UUID.class), rs.getObject("attempt_id", UUID.class),
                StepName.valueOf(rs.getString("step_name")), StepStatus.valueOf(rs.getString("status")),
                rs.getInt("attempt_no"), rs.getObject("input_artifact_id", UUID.class),
                rs.getObject("output_artifact_id", UUID.class), rs.getObject("parse_report_id", UUID.class),
                rs.getBoolean("retryable"), rs.getString("error_code"), instant(rs, "started_at"),
                rs.getTimestamp("finished_at") == null ? null : instant(rs, "finished_at")), spaceId, jobId);
    }

    @Transactional
    public UploadedJob createUploadedJob(UploadedJobInput input) {
        jdbc.update("""
                INSERT INTO ingestion_jobs
                    (id, space_id, source_id, source_document_id, document_revision_id,
                     pipeline_version_id, status, idempotency_key, correlation_id, causation_id,
                     version_no, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'REQUESTED', ?, ?, ?, 1, ?, ?)
                """, input.jobId(), input.spaceId(), input.sourceId(), input.sourceDocumentId(), input.jobDocumentRevisionId(),
                input.pipelineVersionId(), input.idempotencyKey(), input.correlationId(), input.causationId(),
                timestamp(input.now()), timestamp(input.now()));
        jdbc.update("""
                INSERT INTO ingestion_job_attempts
                    (id, space_id, job_id, attempt_no, status, idempotency_key, correlation_id, started_at)
                VALUES (?, ?, ?, 1, 'RUNNING', ?, ?, ?)
                """, input.attemptId(), input.spaceId(), input.jobId(), input.idempotencyKey(),
                input.correlationId(), timestamp(input.now()));
        return new UploadedJob(input.jobId(), input.attemptId(), input.revisionId(), input.artifactId());
    }

    public Optional<UploadedArtifact> findArtifact(UUID spaceId, UUID artifactId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, space_id, source_document_id, document_revision_id, media_type,
                           byte_length, sha256, storage_uri
                    FROM artifacts WHERE space_id = ? AND id = ? AND immutable = TRUE
                    """, (rs, row) -> new UploadedArtifact(rs.getObject("id", UUID.class),
                    rs.getObject("space_id", UUID.class), rs.getObject("source_document_id", UUID.class),
                    rs.getObject("document_revision_id", UUID.class), rs.getString("media_type"),
                    rs.getLong("byte_length"), rs.getString("sha256"), rs.getString("storage_uri")),
                    spaceId, artifactId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Transactional
    public JobAttempt createAttempt(NewJobAttempt input) {
        jdbc.update("""
                INSERT INTO ingestion_job_attempts
                    (id, space_id, job_id, attempt_no, status, idempotency_key,
                     correlation_id, started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, input.id(), input.spaceId(), input.jobId(), input.attemptNo(), input.status().name(),
                input.idempotencyKey(), input.correlationId(), timestamp(input.startedAt()),
                input.finishedAt() == null ? null : timestamp(input.finishedAt()));
        return findAttempt(input.spaceId(), input.id()).orElseThrow();
    }

    public Optional<JobAttempt> findAttempt(UUID spaceId, UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, space_id, job_id, attempt_no, status, idempotency_key,
                           correlation_id, started_at, finished_at
                    FROM ingestion_job_attempts WHERE space_id = ? AND id = ?
                    """, (rs, row) -> new JobAttempt(
                    rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class), rs.getObject("job_id", UUID.class),
                    rs.getInt("attempt_no"), AttemptStatus.valueOf(rs.getString("status")), rs.getString("idempotency_key"),
                    rs.getObject("correlation_id", UUID.class), instant(rs, "started_at"),
                    rs.getTimestamp("finished_at") == null ? null : instant(rs, "finished_at")), spaceId, id));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    /** Advances a checkpoint only after the complete durable evidence set is true. */
    @Transactional
    public SourceCheckpoint commitCheckpoint(CheckpointCommit input) {
        if (!input.revisionsPersisted() || !input.artifactsPersisted() || !input.parseReportsPersisted()
                || !input.activePointerUpdated() || !input.outboxPersisted()) {
            throw new IllegalStateException("checkpoint evidence is incomplete; previous checkpoint is retained");
        }
        int updated = jdbc.update("""
                UPDATE source_checkpoints
                   SET source_version_id = ?, version_no = ?, cursor_type = ?, cursor_value = ?,
                       last_successful_changeset_id = ?, updated_at = ?
                 WHERE space_id = ? AND source_id = ?
                """, input.sourceVersionId(), input.versionNo(), input.cursorType().name(), input.cursor(),
                input.changeSetId(), timestamp(input.updatedAt()), input.spaceId(), input.sourceId());
        if (updated != 1) {
            throw new IllegalArgumentException("checkpoint is not available in the requested space");
        }
        return findCheckpoint(input.spaceId(), input.sourceId()).orElseThrow();
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static String jsonOrEmptyArray(String value) {
        return value == null || value.isBlank() ? "[]" : value;
    }

    private static String jsonOrEmptyObject(String value) {
        return value == null || value.isBlank() ? "{}" : value;
    }

    public enum ConnectorType { FILESYSTEM, LOCAL_DIRECTORY, GIT, OBSIDIAN_VAULT }
    public enum SourceState { ACTIVE, PAUSED, ERROR }
    public enum CursorType { NONE, FILESYSTEM_SCAN, GIT_COMMIT, REMOTE_ETAG, CONNECTOR_CURSOR }
    public enum DocumentState { ACTIVE, DELETED }
    public enum JobStatus { REQUESTED, RUNNING, SUCCEEDED, FAILED, RETRY_SCHEDULED, DLQ }
    public enum AttemptStatus { RUNNING, SUCCEEDED, FAILED, RETRY_SCHEDULED, DLQ }
    public enum ArtifactKind { SOURCE_BYTES, PARSED_TEXT, PARSE_REPORT, CHUNKS }
    public enum ParseStatus { SUCCEEDED, FAILED, OCR_REQUIRED, OCR_UNAVAILABLE, BLOCKED }
    public enum OcrStatus { NOT_REQUESTED, SUCCEEDED, UNAVAILABLE, FAILED }
    public enum OcrTriggerReason { NONE, IMAGE_ONLY_PDF, LOW_TEXT_QUALITY, SCANNED_PAGE, PARSER_FAILURE }
    public enum OcrAuditState { NOT_APPLICABLE, PENDING, COMPLETED, BLOCKED }
    public enum StepName { DISCOVER, FETCH, PARSE, OCR, PERSIST, PUBLISH }
    public enum StepStatus { RUNNING, SUCCEEDED, FAILED, SKIPPED }

    public record NewSourceVersion(UUID id, UUID spaceId, UUID sourceId, int versionNo, ConnectorType connectorType,
                                   String displayName, SourceState sourceState, String rootRef, String includeRulesJson,
                                   String excludeRulesJson, boolean credentialConfigured, UUID correlationId, Instant now) {}
    public record SourceVersion(UUID id, UUID spaceId, UUID sourceId, int versionNo, ConnectorType connectorType,
                                String displayName, SourceState sourceState, String rootRef, String includeRulesJson,
                                String excludeRulesJson, boolean credentialConfigured, UUID correlationId,
                                Instant createdAt, Instant updatedAt) {}
    public record NewSourceCheckpoint(UUID id, UUID spaceId, UUID sourceId, UUID sourceVersionId, int versionNo,
                                     CursorType cursorType, String cursor, UUID changeSetId, Instant updatedAt) {}
    public record SourceCheckpoint(UUID id, UUID spaceId, UUID sourceId, UUID sourceVersionId, int versionNo,
                                   CursorType cursorType, String cursor, UUID changeSetId, Instant updatedAt) {}
    public record NewSourceDocument(UUID id, UUID spaceId, UUID sourceId, String stableSourceObjectId,
                                    String canonicalSourcePath, String basename, int versionNo, DocumentState state,
                                    UUID activeRevisionId, UUID correlationId, Instant now) {}
    public record SourceDocument(UUID id, UUID spaceId, UUID sourceId, String stableSourceObjectId,
                                 String canonicalSourcePath, String basename, int versionNo, DocumentState state,
                                 UUID activeRevisionId, UUID correlationId, Instant createdAt, Instant updatedAt) {}
    public record NewPipelineVersion(UUID id, UUID spaceId, int versionNo, String pipelineName, String parserName,
                                     String parserVersion, String configurationHash, UUID correlationId, Instant createdAt) {}
    public record PipelineVersion(UUID id, UUID spaceId, int versionNo, String pipelineName, String parserName,
                                  String parserVersion, String configurationHash, UUID correlationId, Instant createdAt) {}
    public record NewIngestionJob(UUID id, UUID spaceId, UUID sourceId, UUID sourceDocumentId, UUID documentRevisionId,
                                  UUID pipelineVersionId, JobStatus status, String idempotencyKey, UUID correlationId,
                                  UUID causationId, int versionNo, Instant now) {}
    public record IngestionJob(UUID id, UUID spaceId, UUID sourceId, UUID sourceDocumentId, UUID documentRevisionId,
                               UUID pipelineVersionId, JobStatus status, String idempotencyKey, UUID correlationId,
                               UUID causationId, int versionNo, Instant createdAt, Instant updatedAt) {}
    public record NewJobAttempt(UUID id, UUID spaceId, UUID jobId, int attemptNo, AttemptStatus status,
                                String idempotencyKey, UUID correlationId, Instant startedAt, Instant finishedAt) {}
    public record JobAttempt(UUID id, UUID spaceId, UUID jobId, int attemptNo, AttemptStatus status,
                             String idempotencyKey, UUID correlationId, Instant startedAt, Instant finishedAt) {}
    public record RevisionBundleInput(UUID spaceId, UUID sourceDocumentId, UUID revisionId, int revisionNo,
                                      String sourceVersion, String canonicalSourcePath, String contentHash,
                                      UUID artifactId, int artifactVersion, ArtifactKind artifactKind, String mediaType,
                                      long byteLength, String artifactSha256, String storageUri, String metadataJson,
                                      UUID parseReportId, int parseReportVersion, ParseStatus parseStatus,
                                      int pageCount, long characterCount, long tokenCount, int nativePageCount,
                                      int ocrPageCount, String parserName, String parserVersion, long durationMs,
                                      String warningsJson, String errorsJson, UUID extractedTextArtifactId,
                                      OcrStatus ocrStatus, String ocrEngine, String ocrEngineVersion,
                                      OcrTriggerReason ocrTriggerReason, OcrAuditState ocrAuditState,
                                      String gitCommitSha, Instant discoveredAt, Instant createdAt) {}
    public record RevisionBundle(UUID revisionId, UUID artifactId, UUID parseReportId) {}
    public record NewActivePointer(UUID id, UUID spaceId, UUID sourceDocumentId, UUID activeRevisionId,
                                   int versionNo, Instant updatedAt) {}
    public record ActivePointer(UUID id, UUID spaceId, UUID sourceDocumentId, UUID activeRevisionId,
                                int versionNo, Instant updatedAt) {}
    public record NewPipelineStep(UUID id, UUID spaceId, UUID jobId, UUID attemptId, StepName stepName,
                                  StepStatus status, int attemptNo, UUID inputArtifactId, UUID outputArtifactId,
                                  UUID parseReportId, boolean retryable, String errorCode, Instant startedAt,
                                  Instant finishedAt) {}
    public record PipelineStepExecution(UUID id, UUID spaceId, UUID jobId, UUID attemptId, StepName stepName,
                                        StepStatus status, int attemptNo, UUID inputArtifactId, UUID outputArtifactId,
                                        UUID parseReportId, boolean retryable, String errorCode, Instant startedAt,
                                        Instant finishedAt) {}
    public record CheckpointCommit(UUID spaceId, UUID sourceId, UUID sourceVersionId, int versionNo,
                                   CursorType cursorType, String cursor, UUID changeSetId,
                                   boolean revisionsPersisted, boolean artifactsPersisted, boolean parseReportsPersisted,
                                   boolean activePointerUpdated, boolean outboxPersisted, Instant updatedAt) {}
    public record UploadedJob(UUID jobId, UUID attemptId, UUID revisionId, UUID artifactId) {}
    public record NewDiscoveryRevision(UUID revisionId, UUID spaceId, UUID sourceDocumentId, int revisionNo,
                                       String sourceVersion, String canonicalSourcePath, String contentHash,
                                       Instant discoveredAt) {}
    public record ExistingUpload(UUID jobId, UUID attemptId, UUID revisionId, UUID artifactId,
                                 UUID sourceDocumentId, String displayName, String status) {}
    public record UploadedArtifact(UUID id, UUID spaceId, UUID sourceDocumentId, UUID revisionId,
                                   String mediaType, long byteLength, String sha256, String storageUri) {}
    public record UploadedJobInput(UUID spaceId, UUID sourceId, UUID sourceDocumentId, UUID jobDocumentRevisionId,
                                   UUID revisionId,
                                   UUID artifactId, UUID pipelineVersionId, UUID jobId, UUID attemptId,
                                   int revisionNo, String sourceVersion, String canonicalPath, String contentHash,
                                   String mediaType, long byteLength, String storageUri, String idempotencyKey,
                                   UUID correlationId, UUID causationId, Instant now) {}
}
