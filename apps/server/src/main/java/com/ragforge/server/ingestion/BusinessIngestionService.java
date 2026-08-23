package com.ragforge.server.ingestion;

import com.ragforge.server.audit.AuditOutboxService;
import com.ragforge.server.common.ApiException;
import com.ragforge.server.common.CorrelationIdFilter;
import com.ragforge.server.common.IdempotencyRepository;
import com.ragforge.server.common.UuidV7;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.provider.SpaceAuthorization;
import io.minio.MinioClient;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.PutObjectArgs;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.HexFormat;

@Service
public class BusinessIngestionService {
    private static final long MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
    private static final String PARSER_VERSION = "1.0.0";
    private final IngestionRepository ingestion;
    private final JdbcTemplate jdbc;
    private final AuditOutboxService outbox;
    private final SpaceAuthorization authorization;
    private final ObjectProvider<MinioClient> minio;
    private final IdempotencyRepository idempotency;
    private final String bucket;
    private final String prefix;

    public BusinessIngestionService(IngestionRepository ingestion, JdbcTemplate jdbc, AuditOutboxService outbox,
                                    SpaceAuthorization authorization, ObjectProvider<MinioClient> minio,
                                    IdempotencyRepository idempotency,
                                    @org.springframework.beans.factory.annotation.Value("${ragforge.object-storage.bucket:ragforge}") String bucket,
                                    @org.springframework.beans.factory.annotation.Value("${ragforge.object-storage.prefix:phase3}") String prefix) {
        this.ingestion = ingestion;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.authorization = authorization;
        this.minio = minio;
        this.idempotency = idempotency;
        this.bucket = bucket;
        this.prefix = prefix.replaceAll("/+$", "");
    }

    @Transactional
    public UploadView upload(UUID spaceId, MultipartFile file, String idempotencyKey,
                             SessionPrincipal principal, HttpServletRequest request) {
        authorization.requireWrite(spaceId, principal);
        if (file == null || file.isEmpty() || file.getSize() > MAX_UPLOAD_BYTES) {
            throw invalid("file", "Markdown file is required and must be at most 10 MiB");
        }
        String name = safeName(file.getOriginalFilename());
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".md")
                && !name.toLowerCase(java.util.Locale.ROOT).endsWith(".markdown")) {
            throw invalid("file", "Only Markdown uploads are supported in the first vertical slice");
        }
        String mediaType = "text/markdown";
        byte[] content;
        try {
            content = file.getBytes();
        } catch (java.io.IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "upload_read_failed", "Upload failed", "The upload could not be read");
        }
        if (content.length == 0 || content.length > MAX_UPLOAD_BYTES || !isUtf8(content)) {
            throw invalid("file", "The Markdown content is empty, too large, or not valid UTF-8");
        }
        String hash = sha256(content);
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? "upload-" + hash : idempotencyKey;
        if (!key.matches("[A-Za-z0-9._~-]{1,255}")) {
            throw invalid("Idempotency-Key", "must contain only letters, digits, dot, underscore, hyphen or tilde");
        }
        IdempotencyRepository.Record idempotencyRecord = idempotency.find(principal.userId().toString(), key).orElse(null);
        if (idempotencyRecord != null
                && !idempotencyRecord.requestHash().equals(hash)
                && !idempotencyRecord.requestHash().equals(multipartProvisionalHash(request))) {
            throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_conflict", "Idempotency key conflict",
                    "The idempotency key was already used for a different upload");
        }
        idempotency.updateRequestHash(principal.userId().toString(), key, hash);
        UUID correlationId = UUID.fromString(CorrelationIdFilter.current(request));
        UUID sourceId = findSource(spaceId, name).orElseGet(UuidV7::random);
        Optional<IngestionRepository.ExistingUpload> existing = ingestion.findExistingUpload(spaceId, sourceId, key, hash);
        if (existing.isPresent()) {
            IngestionRepository.ExistingUpload value = existing.get();
            return new UploadView(spaceId, sourceId, value.sourceDocumentId(), value.revisionId(),
                    value.jobId(), value.attemptId(), value.displayName(), value.status());
        }
        int sourceVersionNo = next("SELECT COALESCE(MAX(version_no), 0) + 1 FROM source_versions WHERE space_id = ? AND source_id = ?", spaceId, sourceId);
        UUID sourceVersionId = UuidV7.random();
        Instant now = Instant.now();
        ingestion.createSourceVersion(new IngestionRepository.NewSourceVersion(sourceVersionId, spaceId, sourceId,
                sourceVersionNo, IngestionRepository.ConnectorType.FILESYSTEM, name,
                IngestionRepository.SourceState.ACTIVE, "upload://" + name, "[]", "[]", false, correlationId, now));
        IngestionRepository.SourceDocument document = ingestion.findSourceDocumentByPath(spaceId, sourceId, name)
                .orElseGet(() -> ingestion.createSourceDocument(new IngestionRepository.NewSourceDocument(
                        UuidV7.random(), spaceId, sourceId, name, name, name, 1,
                        IngestionRepository.DocumentState.ACTIVE, null, correlationId, now)));
        UUID pipelineId = UuidV7.random();
        String pipelineHash = sha256("markdown|" + PARSER_VERSION);
        ingestion.createPipelineVersion(new IngestionRepository.NewPipelineVersion(pipelineId, spaceId,
                next("SELECT COALESCE(MAX(version_no), 0) + 1 FROM pipeline_versions WHERE space_id = ? AND pipeline_name = ?", spaceId, "markdown-upload"),
                "markdown-upload", "ragforge-native-parser", PARSER_VERSION, pipelineHash, correlationId, now));
        UUID revisionId = UuidV7.random();
        UUID discoveryRevisionId = UuidV7.random();
        UUID artifactId = UuidV7.random();
        UUID jobId = UuidV7.random();
        UUID attemptId = UuidV7.random();
        String storageUri = "spaces/" + spaceId + "/sources/" + sourceId + "/revisions/" + revisionId
                + "/artifacts/" + artifactId + "/sha256/" + hash;
        store(spaceId, sourceId, revisionId, artifactId, hash, mediaType, content);
        int discoveryRevisionNo = next("SELECT COALESCE(MAX(revision_no), 0) + 1 FROM document_revisions WHERE space_id = ? AND source_document_id = ?",
                spaceId, document.id());
        ingestion.createDiscoveryRevision(new IngestionRepository.NewDiscoveryRevision(discoveryRevisionId, spaceId,
                document.id(), discoveryRevisionNo, Integer.toString(sourceVersionNo), name, hash, now));
        ingestion.createUploadedJob(new IngestionRepository.UploadedJobInput(spaceId, sourceId, document.id(),
                discoveryRevisionId, revisionId, artifactId, pipelineId, jobId, attemptId, discoveryRevisionNo,
                Integer.toString(sourceVersionNo), name, hash, mediaType, content.length, storageUri, key, correlationId, null, now));
        if (ingestion.findCheckpoint(spaceId, sourceId).isEmpty()) {
            ingestion.createCheckpoint(new IngestionRepository.NewSourceCheckpoint(UuidV7.random(), spaceId, sourceId,
                    sourceVersionId, sourceVersionNo, IngestionRepository.CursorType.NONE, null, null, now));
        }
        outbox.record("ingestion.job.requested.v1", principal.userId(), spaceId, jobId, correlationId,
                Map.of("jobId", jobId, "sourceId", sourceId, "documentRevisionId", revisionId,
                        "pipelineVersionId", pipelineId, "attemptId", attemptId, "operation", "DOCUMENT_UPSERT",
                        "artifactRef", Map.of("artifactId", artifactId, "mediaType", mediaType,
                                "byteLength", content.length, "sha256", hash, "storageUri", storageUri)));
        return new UploadView(spaceId, sourceId, document.id(), revisionId, jobId, attemptId, name, "REQUESTED");
    }

    @Transactional(readOnly = true)
    public List<IngestionRepository.SourceDocument> documents(UUID spaceId, SessionPrincipal principal) {
        authorization.requireMember(spaceId, principal);
        return ingestion.listSourceDocuments(spaceId);
    }

    @Transactional(readOnly = true)
    public JobView job(UUID spaceId, UUID jobId, SessionPrincipal principal) {
        authorization.requireMember(spaceId, principal);
        IngestionRepository.IngestionJob job = ingestion.findJob(spaceId, jobId).orElseThrow(() -> notFound("Ingestion job not found"));
        return new JobView(job, ingestion.listAttempts(spaceId, jobId), ingestion.listSteps(spaceId, jobId));
    }

    @Transactional(readOnly = true)
    public List<JobView> jobs(UUID spaceId, SessionPrincipal principal) {
        authorization.requireMember(spaceId, principal);
        return ingestion.listJobs(spaceId).stream().map(job -> new JobView(job,
                ingestion.listAttempts(spaceId, job.id()), ingestion.listSteps(spaceId, job.id()))).toList();
    }

    @Transactional(readOnly = true)
    public ParseReportView parseReport(UUID spaceId, UUID revisionId, SessionPrincipal principal) {
        authorization.requireMember(spaceId, principal);
        try {
            return jdbc.queryForObject("""
                    SELECT id, document_revision_id, status, media_type, page_count, character_count,
                           token_count, parser_name, parser_version, duration_ms, warnings::text, errors::text,
                           extracted_text_artifact_id, ocr_status, ocr_engine, ocr_engine_version,
                           created_at
                    FROM parse_reports WHERE space_id = ? AND document_revision_id = ?
                    ORDER BY version_no DESC LIMIT 1
                    """, (rs, row) -> new ParseReportView(rs.getObject("id", UUID.class),
                    rs.getObject("document_revision_id", UUID.class), rs.getString("status"), rs.getString("media_type"),
                    rs.getInt("page_count"), rs.getLong("character_count"), rs.getLong("token_count"),
                    rs.getString("parser_name"), rs.getString("parser_version"), rs.getLong("duration_ms"),
                    rs.getString("warnings"), rs.getString("errors"), rs.getObject("extracted_text_artifact_id", UUID.class),
                    rs.getString("ocr_status"), rs.getString("ocr_engine"), rs.getString("ocr_engine_version"),
                    rs.getTimestamp("created_at").toInstant()), spaceId, revisionId);
        } catch (org.springframework.dao.EmptyResultDataAccessException exception) {
            throw notFound("Parse report not found");
        }
    }

    private void store(UUID spaceId, UUID sourceId, UUID revisionId, UUID artifactId, String hash,
                       String mediaType, byte[] content) {
        MinioClient client = minio.getIfAvailable();
        if (client == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "object_storage_unavailable", "Upload unavailable",
                    "Object storage is not enabled; start the local stack with RAGFORGE_OBJECT_STORAGE_ENABLED=true");
        }
        String key = prefix.isBlank() ? objectKey(spaceId, sourceId, revisionId, artifactId, hash)
                : prefix + "/" + objectKey(spaceId, sourceId, revisionId, artifactId, hash);
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).contentType(mediaType)
                    .stream(new ByteArrayInputStream(content), content.length, -1).build());
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "object_storage_unavailable", "Upload unavailable",
                    "The source could not be stored safely");
        }
    }

    private static String objectKey(UUID spaceId, UUID sourceId, UUID revisionId, UUID artifactId, String hash) {
        return "spaces/" + spaceId + "/sources/" + sourceId + "/revisions/" + revisionId
                + "/artifacts/" + artifactId + "/sha256/" + hash;
    }

    private Optional<UUID> findSource(UUID spaceId, String name) {
        return jdbc.query("SELECT id FROM sources s WHERE s.space_id = ? AND EXISTS (SELECT 1 FROM source_versions v WHERE v.space_id = s.space_id AND v.source_id = s.id AND v.display_name = ?) ORDER BY s.created_at LIMIT 1",
                (rs, row) -> rs.getObject("id", UUID.class), spaceId, name).stream().findFirst();
    }

    private int next(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    static String safeName(String value) {
        if (value == null || value.isBlank() || value.length() > 2048
                || value.startsWith("/") || value.matches("^[A-Za-z]:.*")
                || value.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw invalid("file", "File path is invalid");
        }
        String normalized = value.replace('\\', '/');
        String[] segments = normalized.split("/", -1);
        if (java.util.Arrays.stream(segments)
                .anyMatch(segment -> segment.isBlank() || segment.equals(".") || segment.equals(".."))) {
            throw invalid("file", "File path is invalid");
        }
        return String.join("/", segments);
    }
    private static boolean isUtf8(byte[] value) { try { StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(value)); return true; } catch (Exception e) { return false; } }
    private static String sha256(byte[] value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }
    private static String multipartProvisionalHash(HttpServletRequest request) {
        return sha256((request.getMethod() + "\n" + request.getRequestURI() + "\n").getBytes(StandardCharsets.UTF_8));
    }
    private static ApiException invalid(String field, String detail) { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "Validation failed", field + ": " + detail); }
    private static ApiException notFound(String detail) { return new ApiException(HttpStatus.NOT_FOUND, "resource_not_found", "Resource not found", detail); }

    public record UploadView(UUID spaceId, UUID sourceId, UUID sourceDocumentId, UUID documentRevisionId,
                             UUID jobId, UUID attemptId, String displayName, String status) {}
    public record JobView(IngestionRepository.IngestionJob job, List<IngestionRepository.JobAttempt> attempts,
                          List<IngestionRepository.PipelineStepExecution> steps) {}
    public record ParseReportView(UUID parseReportId, UUID documentRevisionId, String status, String mediaType,
                                  int pageCount, long characterCount, long tokenCount, String parserName,
                                  String parserVersion, long durationMs, String warnings, String errors,
                                  UUID extractedTextArtifactId, String ocrStatus, String ocrEngine,
                                  String ocrEngineVersion, Instant createdAt) {}
}
