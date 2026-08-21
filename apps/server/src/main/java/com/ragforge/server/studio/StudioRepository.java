package com.ragforge.server.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only adapters for the studio projections. Every query repeats the space boundary. */
@Repository
public class StudioRepository {
    public record ChildStudioRow(UUID spaceId, UUID childChunkId, UUID parentChunkId,
            UUID documentRevisionId, int childIndex, String childContentRef, String childTextHash,
            UUID sourceId, UUID documentId, String sourcePath, int revisionVersion,
            String parentContentRef, List<String> headingPath, Integer pageNumber, String sheet,
            Integer slideNumber, Integer lineStart, Integer lineEnd, String tableCell, Instant createdAt) {
    }

    public record VectorStatus(String state, UUID indexVersionId, Integer vectorDimension, Instant updatedAt) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public StudioRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<ChildStudioRow> findChild(UUID spaceId, UUID childChunkId) {
        requireScope(spaceId, childChunkId);
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT c.space_id, c.id AS child_chunk_id, c.parent_chunk_id, c.document_revision_id,
                           c.chunk_index AS child_index, c.content_ref AS child_content_ref, c.text_hash AS child_text_hash,
                           s.id AS source_id, sd.id AS document_id, dr.canonical_source_path AS source_path,
                           dr.revision_no AS revision_version, p.content_ref AS parent_content_ref,
                           c.heading_path::text, c.page_number, c.sheet, c.slide_number,
                           c.line_start, c.line_end, c.table_cell, c.created_at
                    FROM child_chunks c
                    JOIN parent_chunks p ON p.id = c.parent_chunk_id AND p.space_id = c.space_id
                    JOIN document_revisions dr ON dr.id = c.document_revision_id AND dr.space_id = c.space_id
                    JOIN source_documents sd ON sd.id = dr.source_document_id AND sd.space_id = dr.space_id
                    JOIN sources s ON s.id = sd.source_id AND s.space_id = sd.space_id
                    WHERE c.space_id = ? AND c.id = ?
                    """, (rs, row) -> new ChildStudioRow(
                    rs.getObject("space_id", UUID.class), rs.getObject("child_chunk_id", UUID.class),
                    rs.getObject("parent_chunk_id", UUID.class), rs.getObject("document_revision_id", UUID.class),
                    rs.getInt("child_index"), rs.getString("child_content_ref"), rs.getString("child_text_hash"),
                    rs.getObject("source_id", UUID.class), rs.getObject("document_id", UUID.class),
                    rs.getString("source_path"), rs.getInt("revision_version"), rs.getString("parent_content_ref"),
                    stringList(rs.getString("heading_path")), (Integer) rs.getObject("page_number"),
                    rs.getString("sheet"), (Integer) rs.getObject("slide_number"),
                    (Integer) rs.getObject("line_start"), (Integer) rs.getObject("line_end"),
                    rs.getString("table_cell"), instant(rs, "created_at")), spaceId, childChunkId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public boolean documentRevisionExists(UUID spaceId, UUID documentRevisionId) {
        requireScope(spaceId, documentRevisionId);
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM document_revisions
                               WHERE space_id = ? AND id = ?)
                """, Boolean.class, spaceId, documentRevisionId);
        return Boolean.TRUE.equals(exists);
    }

    /** Reads only index metadata; vector values are never queried. */
    public VectorStatus findVectorStatus(UUID spaceId, UUID childChunkId, Instant fallbackUpdatedAt) {
        requireScope(spaceId, childChunkId);
        try {
            return jdbc.queryForObject("""
                    SELECT i.id AS index_version_id, i.index_state, i.validation_vector_dimension,
                           COALESCE(i.activated_at, i.created_at) AS updated_at
                    FROM active_index_pointers p
                    JOIN index_versions i ON i.id = p.active_index_version_id AND i.space_id = p.space_id
                    WHERE p.space_id = ?
                      AND EXISTS (SELECT 1 FROM child_chunks c
                                  WHERE c.space_id = ? AND c.id = ?)
                    """, (rs, row) -> new VectorStatus(
                    mapIndexState(rs.getString("index_state")), rs.getObject("index_version_id", UUID.class),
                    (Integer) rs.getObject("validation_vector_dimension"), instant(rs, "updated_at")),
                    spaceId, spaceId, childChunkId);
        } catch (EmptyResultDataAccessException ignored) {
            return new VectorStatus("NOT_INDEXED", null, null, fallbackUpdatedAt);
        }
    }

    /** Content references are opaque and are retained in the studio audit payload until a schema field exists. */
    public Optional<String> findOverrideContentRef(UUID spaceId, UUID overrideId) {
        requireScope(spaceId, overrideId);
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT payload->>'contentRef'
                    FROM audit_events
                    WHERE space_id = ? AND aggregate_id = ?
                      AND event_type IN ('chunk.override.created', 'chunk.override.transitioned')
                      AND payload ? 'contentRef'
                    ORDER BY occurred_at DESC, id DESC
                    LIMIT 1
                    """, String.class, spaceId, overrideId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private List<String> stringList(String json) throws java.sql.SQLException {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception exception) {
            throw new java.sql.SQLException("invalid heading_path", exception);
        }
    }

    private static String mapIndexState(String state) {
        return switch (state) {
            case "ACTIVE" -> "INDEXED";
            case "BUILDING", "VALIDATING", "READY" -> "PENDING";
            case "FAILED" -> "FAILED";
            case "RETIRED" -> "STALE";
            default -> "NOT_INDEXED";
        };
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static void requireScope(UUID spaceId, UUID id) {
        if (spaceId == null || id == null) {
            throw new IllegalArgumentException("spaceId and resource identity are required");
        }
    }
}
