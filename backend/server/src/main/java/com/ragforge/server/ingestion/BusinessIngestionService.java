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
            throw invalid("file", "文件不能为空且必须不超过 10 MiB");
        }
        String name = safeName(file.getOriginalFilename());
        String mediaType = mediaType(name);
        if ("application/octet-stream".equals(mediaType)) {
            throw invalid("file", "仅支持 Markdown、TXT、PDF、DOCX、PPTX 和 XLSX 文件");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (java.io.IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "upload_read_failed", "Upload failed", "The upload could not be read");
        }
        if (content.length == 0 || content.length > MAX_UPLOAD_BYTES) {
            throw invalid("file", "文件内容为空或超过 10 MiB");
        }
        if (isTextMediaType(mediaType) && !isUtf8(content)) {
            throw invalid("file", "文本文件不是有效 UTF-8；二进制文档必须使用受支持格式");
        }
        return ingestBytes(spaceId, name, name, mediaType, content, idempotencyKey, principal, request,
                IngestionRepository.ConnectorType.FILESYSTEM, "upload://" + name);
    }

    @Transactional
    public UploadView ingestFetched(UUID spaceId, String displayName, String canonicalPath, String mediaType,
                                    byte[] content, String idempotencyKey, SessionPrincipal principal,
                                    HttpServletRequest request, String rootRef) {
        authorization.requireWrite(spaceId, principal);
        if (content == null || content.length == 0 || content.length > MAX_UPLOAD_BYTES || !isUtf8(content)) {
            throw invalid("content", "网页内容为空、超过 10 MiB 或不是有效 UTF-8");
        }
        return ingestBytes(spaceId, displayName, canonicalPath, mediaType, content, idempotencyKey, principal,
                request, IngestionRepository.ConnectorType.WEB, rootRef);
    }

    private UploadView ingestBytes(UUID spaceId, String displayName, String canonicalPath, String mediaType,
                                   byte[] content, String idempotencyKey, SessionPrincipal principal,
                                   HttpServletRequest request, IngestionRepository.ConnectorType connectorType,
                                   String rootRef) {
        String name = safeName(canonicalPath);
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
        UUID sourceId = findSource(spaceId, name, rootRef).orElseGet(UuidV7::random);
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
                sourceVersionNo, connectorType, displayName,
                IngestionRepository.SourceState.ACTIVE, rootRef, "[]", "[]", false, correlationId, now));
        IngestionRepository.SourceDocument document = ingestion.findSourceDocumentByPath(spaceId, sourceId, name)
                .orElseGet(() -> ingestion.createSourceDocument(new IngestionRepository.NewSourceDocument(
                        UuidV7.random(), spaceId, sourceId, name, canonicalPath, displayName, 1,
                        IngestionRepository.DocumentState.ACTIVE, null, correlationId, now)));
        UUID pipelineId = UuidV7.random();
        String pipelineHash = sha256("native-document|" + PARSER_VERSION);
        ingestion.createPipelineVersion(new IngestionRepository.NewPipelineVersion(pipelineId, spaceId,
                next("SELECT COALESCE(MAX(version_no), 0) + 1 FROM pipeline_versions WHERE space_id = ? AND pipeline_name = ?", spaceId, "native-document-upload"),
                "native-document-upload", "ragforge-native-parser", PARSER_VERSION, pipelineHash, correlationId, now));
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
                document.id(), discoveryRevisionNo, Integer.toString(sourceVersionNo), canonicalPath, hash, now));
        ingestion.createUploadedJob(new IngestionRepository.UploadedJobInput(spaceId, sourceId, document.id(),
                discoveryRevisionId, revisionId, artifactId, pipelineId, jobId, attemptId, discoveryRevisionNo,
                Integer.toString(sourceVersionNo), displayName, hash, mediaType, content.length, storageUri, key, correlationId, null, now));
        if (ingestion.findCheckpoint(spaceId, sourceId).isEmpty()) {
            ingestion.createCheckpoint(new IngestionRepository.NewSourceCheckpoint(UuidV7.random(), spaceId, sourceId,
                    sourceVersionId, sourceVersionNo, IngestionRepository.CursorType.NONE, null, null, now));
        }
        outbox.record("ingestion.job.requested.v1", principal.userId(), spaceId, jobId, correlationId,
                Map.of("jobId", jobId, "sourceId", sourceId, "documentRevisionId", revisionId,
                        "pipelineVersionId", pipelineId, "attemptId", attemptId, "operation", "DOCUMENT_UPSERT",
                        "artifactRef", Map.of("artifactId", artifactId, "mediaType", mediaType,
                                "byteLength", content.length, "sha256", hash, "storageUri", storageUri)));
        return new UploadView(spaceId, sourceId, document.id(), revisionId, jobId, attemptId, displayName, "REQUESTED");
    }

    @Transactional(readOnly = true)
    public List<IngestionRepository.SourceDocument> documents(UUID spaceId, SessionPrincipal principal) {
        authorization.requireMember(spaceId, principal);
        return ingestion.listSourceDocuments(spaceId);
    }

    @Transactional
    public IngestionRepository.SourceVersion updateSource(UUID spaceId, UUID sourceId, SourceUpdateRequest request,
                                                          String ifMatch, SessionPrincipal principal,
                                                          HttpServletRequest servletRequest) {
        authorization.requireWrite(spaceId, principal);
        requireVersion(request.version(), ifMatch);
        IngestionRepository.SourceVersion current = ingestion.findCurrentSourceVersion(spaceId, sourceId)
                .orElseThrow(() -> notFound("Source not found"));
        if (current.versionNo() != request.version()) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "source_version_conflict",
                    "Source version conflict", "The source version is stale");
        }
        return ingestion.createSourceVersion(new IngestionRepository.NewSourceVersion(
                UuidV7.random(), spaceId, sourceId, current.versionNo() + 1, current.connectorType(),
                request.displayName().trim(), sourceState(request.sourceState()), request.rootRef().trim(),
                jsonArray(request.include()), jsonArray(request.exclude()), current.credentialConfigured(),
                UUID.fromString(CorrelationIdFilter.current(servletRequest)), Instant.now(), current.gitBranch()));
    }

    @Transactional(readOnly = true)
    public CursorPage<IngestionRepository.SourceDocument> documentPage(UUID spaceId, SessionPrincipal principal) {
        authorization.requireMember(spaceId, principal);
        return new CursorPage<>(ingestion.listSourceDocuments(spaceId), null);
    }

    @Transactional(readOnly = true)
    public IngestionRepository.SourceDocument document(UUID spaceId, UUID documentId, SessionPrincipal principal) {
        authorization.requireMember(spaceId, principal);
        return ingestion.findSourceDocument(spaceId, documentId)
                .orElseThrow(() -> notFound("Source document not found"));
    }

    @Transactional(readOnly = true)
    public CursorPage<IngestionRepository.DocumentRevision> revisionPage(UUID spaceId, UUID documentId,
                                                                          SessionPrincipal principal) {
        document(spaceId, documentId, principal);
        return new CursorPage<>(ingestion.listDocumentRevisions(spaceId, documentId), null);
    }

    @Transactional(readOnly = true)
    public IngestionRepository.DocumentRevision revision(UUID spaceId, UUID documentId, UUID revisionId,
                                                         SessionPrincipal principal) {
        document(spaceId, documentId, principal);
        return ingestion.findDocumentRevision(spaceId, documentId, revisionId)
                .orElseThrow(() -> notFound("Document revision not found"));
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

    private Optional<UUID> findSource(UUID spaceId, String name, String rootRef) {
        return jdbc.query("SELECT id FROM sources s WHERE s.space_id = ? AND EXISTS (SELECT 1 FROM source_versions v WHERE v.space_id = s.space_id AND v.source_id = s.id AND (v.display_name = ? OR v.root_ref = ?)) ORDER BY s.created_at LIMIT 1",
                (rs, row) -> rs.getObject("id", UUID.class), spaceId, name, rootRef).stream().findFirst();
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
    static String mediaType(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "text/markdown";
        if (lower.endsWith(".txt") || lower.endsWith(".text")) return "text/plain";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return "application/octet-stream";
    }
    private static boolean isTextMediaType(String value) {
        return "text/plain".equalsIgnoreCase(value) || "text/markdown".equalsIgnoreCase(value)
                || "text/html".equalsIgnoreCase(value) || "application/xhtml+xml".equalsIgnoreCase(value);
    }
    private static boolean isUtf8(byte[] value) { try { StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(value)); return true; } catch (Exception e) { return false; } }
    private static String sha256(byte[] value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }
    private static String multipartProvisionalHash(HttpServletRequest request) {
        return sha256((request.getMethod() + "\n" + request.getRequestURI() + "\n").getBytes(StandardCharsets.UTF_8));
    }
    private static ApiException invalid(String field, String detail) { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "Validation failed", field + ": " + detail); }
    private static ApiException notFound(String detail) { return new ApiException(HttpStatus.NOT_FOUND, "resource_not_found", "Resource not found", detail); }

    private static void requireVersion(int version, String ifMatch) {
        if (version < 1 || ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "source_version_required",
                    "Version precondition required", "A matching version and If-Match header are required");
        }
        String value = ifMatch.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            if (Integer.parseInt(value) != version) throw new ApiException(HttpStatus.PRECONDITION_FAILED,
                    "source_version_conflict", "Source version conflict", "If-Match does not match the request version");
        } catch (NumberFormatException exception) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "source_version_conflict",
                    "Source version conflict", "If-Match must contain the source version");
        }
    }

    private static IngestionRepository.SourceState sourceState(String value) {
        try {
            return IngestionRepository.SourceState.valueOf(value);
        } catch (RuntimeException exception) {
            throw invalid("sourceState", "must be ACTIVE, PAUSED or ERROR");
        }
    }

    private static String jsonArray(List<String> values) {
        return values.stream().map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    public record SourceUpdateRequest(
            @jakarta.validation.constraints.Min(1) int version,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 120) String displayName,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 512) String rootRef,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Size(max = 100) List<@jakarta.validation.constraints.NotBlank String> include,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Size(max = 100) List<@jakarta.validation.constraints.NotBlank String> exclude,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp = "ACTIVE|PAUSED|ERROR") String sourceState) {}

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
