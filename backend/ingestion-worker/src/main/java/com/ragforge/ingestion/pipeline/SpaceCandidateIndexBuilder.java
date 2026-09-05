package com.ragforge.ingestion.pipeline;

import com.ragforge.ingestion.common.UuidV7;
import com.ragforge.ingestion.objectstore.ContentAddressedObjectStore;
import com.ragforge.ingestion.objectstore.ObjectKey;
import com.ragforge.ingestion.objectstore.StoredObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** Builds one immutable candidate snapshot from every active revision in a space. */
@Service
@ConditionalOnProperty(name = "ragforge.ingestion.enabled", havingValue = "true")
public final class SpaceCandidateIndexBuilder {
    private final JdbcTemplate jdbc;
    private final ContentAddressedObjectStore store;
    private final OllamaEmbeddingClient embedding;
    private final QdrantIndexWriter qdrant;
    private final String embeddingProfileVersion;

    public SpaceCandidateIndexBuilder(JdbcTemplate jdbc, ContentAddressedObjectStore store,
                                      OllamaEmbeddingClient embedding, QdrantIndexWriter qdrant,
                                      @Value("${ragforge.ollama.embedding-model:nomic-embed-text:latest}")
                                      String embeddingProfileVersion) {
        this.jdbc = jdbc;
        this.store = store;
        this.embedding = embedding;
        this.qdrant = qdrant;
        this.embeddingProfileVersion = embeddingProfileVersion;
    }

    public IndexResult build(UUID spaceId, Instant now) {
        List<SourceChunk> chunks = loadActiveChunks(spaceId);
        if (chunks.isEmpty()) {
            throw new IllegalStateException("cannot build a candidate index without active parsed chunks");
        }

        List<QdrantIndexWriter.Point> points = chunks.stream().map(chunk -> {
            String text = materialize(spaceId, chunk);
            return new QdrantIndexWriter.Point(chunk.childId(), chunk.revisionId(), chunk.parentId(),
                    chunk.contentRef(), chunk.textHash(), embedding.embed(text), text);
        }).toList();
        int dimension = points.get(0).vector().size();
        if (points.stream().anyMatch(point -> point.vector().size() != dimension)) {
            throw new IllegalStateException("candidate index contains inconsistent embedding dimensions");
        }

        UUID indexId = UuidV7.random();
        int versionNo = next("SELECT COALESCE(MAX(version_no), 0) + 1 FROM index_versions WHERE space_id = ?", spaceId);
        String collection = "ragforge_" + compact(spaceId) + "_" + compact(indexId);
        int documentCount = (int) chunks.stream().map(SourceChunk::sourceDocumentId).collect(LinkedHashSet::new,
                LinkedHashSet::add, LinkedHashSet::addAll).size();
        jdbc.update("""
                INSERT INTO index_versions (id, space_id, version_no, index_state, candidate_collection,
                    embedding_profile_version, chunking_strategy_version, document_revision_count, child_chunk_count, created_at)
                VALUES (?, ?, ?, 'BUILDING', ?, ?, 'markdown-simple-v1', ?, ?, ?)
                """, indexId, spaceId, versionNo, collection, embeddingProfileVersion, documentCount,
                points.size(), Timestamp.from(now));

        qdrant.createAndUpsert(collection, dimension, spaceId, indexId, points);
        QdrantIndexWriter.Validation validation = qdrant.validateCandidate(collection, spaceId, indexId, points);
        if (!validation.sampleRetrievalPassed() || !validation.spaceFilterPassed()) {
            throw new IllegalStateException("candidate index validation failed");
        }
        jdbc.update("""
                UPDATE index_versions SET index_state = 'VALIDATING', validation_document_count = ?,
                    validation_child_chunk_count = ?, validation_vector_dimension = ?, validation_orphan_child_count = 0,
                    validation_sample_retrieval_passed = ?, validation_space_filter_passed = ?, validation_checked_at = ?
                WHERE space_id = ? AND id = ?
                """, documentCount, points.size(), dimension, validation.sampleRetrievalPassed(),
                validation.spaceFilterPassed(), Timestamp.from(Instant.now()), spaceId, indexId);
        requireSingleRow(jdbc.update("UPDATE index_versions SET index_state = 'READY' WHERE space_id = ? AND id = ?",
                spaceId, indexId), "candidate index READY transition");
        return new IndexResult(indexId, versionNo, documentCount, points.size(), dimension);
    }

    private List<SourceChunk> loadActiveChunks(UUID spaceId) {
        return jdbc.query("""
                SELECT d.id AS source_document_id, c.id AS child_id, c.document_revision_id,
                       c.parent_chunk_id, c.content_ref, lower(c.text_hash) AS text_hash,
                       c.char_start, c.char_end, a.storage_uri, a.sha256, a.byte_length, a.media_type
                FROM source_documents d
                JOIN document_revisions r
                  ON r.id = d.active_revision_id AND r.space_id = d.space_id
                 AND r.revision_state = 'PARSED' AND r.immutable = TRUE
                JOIN child_chunks c
                  ON c.document_revision_id = r.id AND c.space_id = r.space_id
                JOIN parse_reports p
                  ON p.document_revision_id = r.id AND p.space_id = r.space_id
                 AND p.status = 'SUCCEEDED' AND p.extracted_text_artifact_id IS NOT NULL
                JOIN artifacts a
                  ON a.id = p.extracted_text_artifact_id AND a.space_id = p.space_id
                 AND a.document_revision_id = r.id AND a.artifact_kind = 'PARSED_TEXT'
                 AND a.immutable = TRUE
                WHERE d.space_id = ? AND d.current_state = 'ACTIVE'
                ORDER BY d.canonical_source_path, c.chunk_index
                """, (rs, row) -> new SourceChunk(
                rs.getObject("source_document_id", UUID.class), rs.getObject("child_id", UUID.class),
                rs.getObject("document_revision_id", UUID.class), rs.getObject("parent_chunk_id", UUID.class),
                rs.getString("content_ref"), rs.getString("text_hash"), rs.getInt("char_start"),
                rs.getInt("char_end"), rs.getString("storage_uri"), rs.getString("sha256"),
                rs.getLong("byte_length"), rs.getString("media_type")), spaceId);
    }

    private String materialize(UUID spaceId, SourceChunk chunk) {
        ObjectKey key = ObjectKey.parse(spaceId, chunk.storageUri());
        if (!chunk.sha256().equalsIgnoreCase(key.contentHash())) {
            throw new IllegalStateException("parsed text artifact hash does not match storage URI");
        }
        StoredObject object = store.get(key);
        if (!object.sha256().equalsIgnoreCase(chunk.sha256()) || object.byteLength() != chunk.byteLength()
                || !object.mediaType().equalsIgnoreCase(chunk.mediaType())) {
            throw new IllegalStateException("parsed text artifact metadata validation failed");
        }
        String text = decodeUtf8(object.content());
        if (chunk.charStart() < 0 || chunk.charEnd() < chunk.charStart() || chunk.charEnd() > text.length()) {
            throw new IllegalStateException("child chunk character range is invalid");
        }
        String slice = text.substring(chunk.charStart(), chunk.charEnd());
        if (!sha256(slice.getBytes(StandardCharsets.UTF_8)).equalsIgnoreCase(chunk.textHash())) {
            throw new IllegalStateException("child chunk material hash validation failed");
        }
        return slice;
    }

    private int next(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static void requireSingleRow(int updated, String operation) {
        if (updated != 1) throw new IllegalStateException(operation + " affected an unexpected number of rows");
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            CharBuffer chars = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return chars.toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalStateException("parsed text artifact is not valid UTF-8", exception);
        }
    }

    private static String compact(UUID id) {
        return id.toString().replace("-", "");
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is required by the runtime", exception);
        }
    }

    public record IndexResult(UUID indexId, int versionNo, int documentCount, int childChunkCount, int dimension) { }

    private record SourceChunk(UUID sourceDocumentId, UUID childId, UUID revisionId, UUID parentId,
                               String contentRef, String textHash, int charStart, int charEnd,
                               String storageUri, String sha256, long byteLength, String mediaType) { }
}
