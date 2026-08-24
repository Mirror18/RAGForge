package com.ragforge.ingestion.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.ingestion.common.UuidV7;
import com.ragforge.ingestion.messaging.IngestionEventEnvelope;
import com.ragforge.ingestion.messaging.IngestionJobRequestedPayload;
import com.ragforge.ingestion.messaging.IngestionSideEffectHandler;
import com.ragforge.ingestion.objectstore.ContentAddressedObjectStore;
import com.ragforge.ingestion.objectstore.ObjectKey;
import com.ragforge.ingestion.objectstore.StoredObject;
import com.ragforge.ingestion.parser.NativeDocumentParser;
import com.ragforge.ingestion.parser.ParseRequest;
import com.ragforge.ingestion.parser.ParseStatus;
import com.ragforge.ingestion.parser.ParsedDocument;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "ragforge.ingestion.enabled", havingValue = "true")
public class BusinessIngestionSideEffectHandler implements IngestionSideEffectHandler {
    private static final String PARSER_VERSION = "1.0.0";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ContentAddressedObjectStore store;
    private final SpaceCandidateIndexBuilder indexBuilder;
    private final NativeDocumentParser parser = new NativeDocumentParser();

    public BusinessIngestionSideEffectHandler(JdbcTemplate jdbc, ObjectMapper mapper,
                                              ContentAddressedObjectStore store,
                                              SpaceCandidateIndexBuilder indexBuilder) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.store = store;
        this.indexBuilder = indexBuilder;
    }

    @Override
    @Transactional
    public void handle(IngestionEventEnvelope envelope, IngestionJobRequestedPayload payload) {
        UUID spaceId = envelope.spaceId();
        UUID revisionId = payload.documentRevisionId();
        UUID artifactId = payload.artifactRef().artifactId();
        UUID sourceId = payload.sourceId();
        UUID jobId = payload.jobId();
        UUID attemptId = payload.attemptId();
        Instant now = Instant.now();
        ObjectKey key = validateArtifactReference(spaceId, payload);
        DocumentContext context = loadContext(spaceId, sourceId, jobId);
        step(spaceId, jobId, attemptId, "DISCOVER", "SUCCEEDED", null, null, null, now);
        step(spaceId, jobId, attemptId, "FETCH", "RUNNING", null, null, null, now);
        StoredObject source = store.get(key);
        validateStoredArtifact(source, payload);
        step(spaceId, jobId, attemptId, "FETCH", "SUCCEEDED", null, null, null, Instant.now());
        step(spaceId, jobId, attemptId, "PARSE", "RUNNING", null, null, null, Instant.now());
        ParsedDocument parsed = parser.parse(new ParseRequest(spaceId, revisionId, artifactId,
                payload.artifactRef().mediaType(), source.content(), 1, PARSER_VERSION), null);
        if (parsed.report().status() != ParseStatus.SUCCEEDED) throw new IllegalStateException("markdown parsing failed");
        UUID textArtifactId = parsed.report().extractedTextArtifactId();
        byte[] textBytes = parsed.extractedText().getBytes(StandardCharsets.UTF_8);
        String textHash = sha256(textBytes);
        ObjectKey textKey = new ObjectKey(spaceId, sourceId, revisionId, textArtifactId, textHash);
        StoredObject textArtifact = store.put(textKey, "text/plain", textBytes);
        persistRevision(spaceId, sourceId, context.sourceDocumentId(), revisionId, context.revisionNo(),
                context.sourceVersion(), context.path(), payload, parsed, textArtifact, textKey.value(), now);
        step(spaceId, jobId, attemptId, "PARSE", "SUCCEEDED", artifactId, textArtifactId, parsed.report().parseReportId(), Instant.now());
        step(spaceId, jobId, attemptId, "PERSIST", "SUCCEEDED", artifactId, textArtifactId, parsed.report().parseReportId(), Instant.now());

        persistChunk(spaceId, revisionId, textArtifactId, parsed.extractedText(), now);
        jdbc.update("UPDATE source_documents SET active_revision_id = ?, version_no = ?, current_state = 'ACTIVE', updated_at = ? WHERE space_id = ? AND id = ?",
                revisionId, context.revisionNo(), Timestamp.from(now), spaceId, context.sourceDocumentId());
        jdbc.update("""
                INSERT INTO active_document_pointers (id, space_id, source_document_id, active_revision_id, version_no, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (space_id, source_document_id) DO UPDATE SET active_revision_id = EXCLUDED.active_revision_id,
                    version_no = EXCLUDED.version_no, updated_at = EXCLUDED.updated_at
                """, UuidV7.random(), spaceId, context.sourceDocumentId(), revisionId, context.revisionNo(), Timestamp.from(now));
        ensureRetrievalProfile(spaceId, now);
        indexBuilder.build(spaceId, now);
        jdbc.update("UPDATE ingestion_jobs SET status = 'SUCCEEDED', document_revision_id = ?, updated_at = ?, version_no = version_no + 1 WHERE space_id = ? AND id = ?",
                revisionId, Timestamp.from(now), spaceId, jobId);
        jdbc.update("UPDATE ingestion_job_attempts SET status = 'SUCCEEDED', finished_at = ? WHERE space_id = ? AND id = ?",
                Timestamp.from(now), spaceId, attemptId);
        step(spaceId, jobId, attemptId, "PUBLISH", "SUCCEEDED", null, null, parsed.report().parseReportId(), now);
        requireSingleRow(jdbc.update("UPDATE source_checkpoints SET source_version_id = (SELECT id FROM source_versions WHERE space_id = ? AND source_id = ? ORDER BY version_no DESC LIMIT 1), version_no = version_no + 1, cursor_type = 'FILESYSTEM_SCAN', cursor_value = ?, last_successful_changeset_id = ?, updated_at = ? WHERE space_id = ? AND source_id = ?",
                spaceId, sourceId, payload.artifactRef().sha256(), envelope.eventId(), Timestamp.from(now), spaceId, sourceId),
                "source checkpoint advancement");
    }

    private ObjectKey validateArtifactReference(UUID spaceId, IngestionJobRequestedPayload payload) {
        ObjectKey key = ObjectKey.parse(spaceId, payload.artifactRef().storageUri());
        if (!payload.sourceId().equals(key.sourceId())
                || !payload.documentRevisionId().equals(key.documentRevisionId())
                || !payload.artifactRef().artifactId().equals(key.artifactId())
                || !payload.artifactRef().sha256().equalsIgnoreCase(key.contentHash())) {
            throw new IllegalStateException("artifact reference identity validation failed");
        }
        return key;
    }

    private void validateStoredArtifact(StoredObject source, IngestionJobRequestedPayload payload) {
        IngestionJobRequestedPayload.ArtifactReference reference = payload.artifactRef();
        if (!source.sha256().equalsIgnoreCase(reference.sha256())
                || source.byteLength() != reference.byteLength()
                || !source.mediaType().equalsIgnoreCase(reference.mediaType())) {
            throw new IllegalStateException("stored artifact metadata validation failed");
        }
    }

    private static void requireSingleRow(int updated, String operation) {
        if (updated != 1) throw new IllegalStateException(operation + " affected an unexpected number of rows");
    }

    private DocumentContext loadContext(UUID spaceId, UUID sourceId, UUID jobId) {
        return jdbc.queryForObject("""
                SELECT j.source_document_id, d.canonical_source_path,
                       COALESCE(MAX(r.revision_no), 0) + 1 AS revision_no,
                       COALESCE((SELECT sv.id FROM source_versions sv WHERE sv.space_id = j.space_id AND sv.source_id = j.source_id ORDER BY sv.version_no DESC LIMIT 1), NULL) AS source_version_id,
                       COALESCE((SELECT sv.version_no::text FROM source_versions sv WHERE sv.space_id = j.space_id AND sv.source_id = j.source_id ORDER BY sv.version_no DESC LIMIT 1), '1') AS source_version
                FROM ingestion_jobs j JOIN source_documents d ON d.id = j.source_document_id AND d.space_id = j.space_id
                LEFT JOIN document_revisions r ON r.source_document_id = d.id AND r.space_id = d.space_id
                WHERE j.space_id = ? AND j.id = ? AND j.source_id = ?
                GROUP BY j.source_document_id, d.canonical_source_path, j.space_id, j.source_id
                """, (rs, row) -> new DocumentContext(rs.getObject("source_document_id", UUID.class),
                rs.getString("canonical_source_path"), rs.getInt("revision_no"), rs.getString("source_version")), spaceId, jobId, sourceId);
    }

    private void persistRevision(UUID spaceId, UUID sourceId, UUID documentId, UUID revisionId, int revisionNo,
                                 String sourceVersion, String path, IngestionJobRequestedPayload payload,
                                 ParsedDocument parsed, StoredObject textArtifact, String textUri, Instant now) {
        UUID reportId = parsed.report().parseReportId();
        jdbc.update("""
                INSERT INTO document_revisions (id, space_id, source_document_id, revision_no, source_version,
                    canonical_source_path, content_hash, source_artifact_id, parse_report_id, revision_state, immutable, discovered_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PARSED', TRUE, ?, ?)
                """, revisionId, spaceId, documentId, revisionNo, sourceVersion, path, payload.artifactRef().sha256(),
                payload.artifactRef().artifactId(), reportId, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO artifacts (id, space_id, source_document_id, document_revision_id, version_no, artifact_kind,
                    media_type, byte_length, sha256, storage_uri, metadata, immutable, created_at)
                VALUES (?, ?, ?, ?, 1, 'SOURCE_BYTES', ?, ?, ?, ?, '{}'::jsonb, TRUE, ?)
                """, payload.artifactRef().artifactId(), spaceId, documentId, revisionId, payload.artifactRef().mediaType(),
                payload.artifactRef().byteLength(), payload.artifactRef().sha256(), payload.artifactRef().storageUri(), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO artifacts (id, space_id, source_document_id, document_revision_id, version_no, artifact_kind,
                    media_type, byte_length, sha256, storage_uri, metadata, immutable, created_at)
                VALUES (?, ?, ?, ?, 1, 'PARSED_TEXT', 'text/plain', ?, ?, ?, '{}'::jsonb, TRUE, ?)
                """, textArtifact.key().artifactId(), spaceId, documentId, revisionId, textArtifact.byteLength(),
                textArtifact.sha256(), textUri, Timestamp.from(now));
        var report = parsed.report();
        jdbc.update("""
                INSERT INTO parse_reports (id, space_id, document_revision_id, source_artifact_id, version_no, status,
                    media_type, page_count, character_count, token_count, native_page_count, ocr_page_count,
                    parser_name, parser_version, duration_ms, warnings, errors, extracted_text_artifact_id,
                    ocr_status, ocr_engine, ocr_engine_version, ocr_trigger_reason, ocr_audit_state, immutable, created_at)
                VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, TRUE, ?)
                """, report.parseReportId(), spaceId, revisionId, payload.artifactRef().artifactId(), report.status().name(),
                report.mediaType(), report.pageCount(), report.characterCount(), report.tokenCount(), report.nativePageCount(),
                report.ocrPageCount(), report.parserName(), report.parserVersion(), report.durationMs(), json(report.warnings()),
                json(report.errors()), report.extractedTextArtifactId(), report.ocr().status().name(), report.ocr().engine(),
                report.ocr().engineVersion(), report.ocr().triggerReason().name(), report.ocr().auditState().name(), Timestamp.from(now));
    }

    private Chunk persistChunk(UUID spaceId, UUID revisionId, UUID textArtifactId, String text, Instant now) {
        UUID parentId = UuidV7.random();
        UUID childId = UuidV7.random();
        String ref = "spaces/" + spaceId + "/revisions/" + revisionId + "/chunks/child/" + childId;
        String hash = sha256(text.getBytes(StandardCharsets.UTF_8));
        String headings = headingJson(text);
        int tokens = Math.max(1, text.strip().split("\\s+").length);
        jdbc.update("""
                INSERT INTO parent_chunks (id, space_id, document_revision_id, chunk_index, version_no, heading_path,
                    token_start, token_end, char_start, char_end, content_ref, immutable, created_at)
                VALUES (?, ?, ?, 0, 1, CAST(? AS jsonb), 0, ?, 0, ?, ?, TRUE, ?)
                """, parentId, spaceId, revisionId, headings, tokens, text.length(), ref.replace("child/", "parent/"), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO child_chunks (id, space_id, parent_chunk_id, document_revision_id, chunk_index, version_no,
                    heading_path, token_start, token_end, char_start, char_end, line_start, line_end, content_ref, text_hash, immutable, created_at)
                VALUES (?, ?, ?, ?, 0, 1, CAST(? AS jsonb), 0, ?, 0, ?, 1, ?, ?, ?, TRUE, ?)
                """, childId, spaceId, parentId, revisionId, headings, tokens, text.length(), countLines(text), ref, hash, Timestamp.from(now));
        return new Chunk(parentId, childId, ref, hash, text);
    }

    private void ensureRetrievalProfile(UUID spaceId, Instant now) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM active_profile_pointers WHERE space_id = ?", Integer.class, spaceId) > 0) return;
        UUID profileId = UuidV7.random();
        UUID profileVersionId = UuidV7.random();
        jdbc.update("""
                INSERT INTO retrieval_profiles (id, space_id, profile_id, version_no, dense_top_k, bm25_top_k, rrf_k,
                    rrf_dense_weight, rrf_bm25_weight, rerank_top_k, max_context_children, expansion_mode,
                    max_parents_per_child, max_neighbors_per_parent, max_context_tokens, immutable, created_at)
                VALUES (?, ?, ?, 1, 5, 5, 60, 1.0, 0.2, 5, 5, 'PARENT', 1, 0, 512, TRUE, ?)
                """, profileVersionId, spaceId, profileId, Timestamp.from(now));
        jdbc.update("INSERT INTO active_profile_pointers (id, space_id, active_profile_version_id, active_version_no, updated_at) VALUES (?, ?, ?, 1, ?)",
                UuidV7.random(), spaceId, profileVersionId, Timestamp.from(now));
    }

    private void step(UUID spaceId, UUID jobId, UUID attemptId, String name, String status, UUID input,
                      UUID output, UUID report, Instant now) {
        jdbc.update("""
                INSERT INTO pipeline_step_executions (id, space_id, job_id, attempt_id, step_name, status, attempt_no,
                    input_artifact_id, output_artifact_id, parse_report_id, retryable, started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, FALSE, ?, ?)
                ON CONFLICT (space_id, job_id, attempt_id, step_name, attempt_no) DO UPDATE SET status = EXCLUDED.status,
                    output_artifact_id = EXCLUDED.output_artifact_id, parse_report_id = EXCLUDED.parse_report_id,
                    finished_at = EXCLUDED.finished_at
                """, UuidV7.random(), spaceId, jobId, attemptId, name, status, input, output, report,
                Timestamp.from(now), "RUNNING".equals(status) ? null : Timestamp.from(now));
    }

    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("diagnostic serialization failed", e); } }
    private int next(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private static int countLines(String value) { return (int) value.chars().filter(c -> c == '\n').count() + 1; }
    private static String headingJson(String text) { String first = text.lines().filter(line -> line.startsWith("#")).findFirst().orElse(""); String heading = first.replaceFirst("^#+\\s*", "").replace("\"", ""); return heading.isBlank() ? "[]" : "[\"" + heading + "\"]"; }
    private static String sha256(byte[] value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch (Exception e) { throw new IllegalStateException(e); } }

    private record DocumentContext(UUID sourceDocumentId, String path, int revisionNo, String sourceVersion) { }
    private record Chunk(UUID parentId, UUID childId, String contentRef, String textHash, String text) { }
}
