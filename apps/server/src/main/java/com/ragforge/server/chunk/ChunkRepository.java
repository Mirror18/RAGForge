package com.ragforge.server.chunk;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Space-scoped persistence seam for parent/child chunks and chunk overrides.
 *
 * <p>Chunks are immutable version records: a changed document revision produces
 * a new set of chunks instead of updating rows in place. Overrides are
 * append-only versions whose transitions follow
 * {@link ChunkOverrideTransitions}. Every SQL predicate repeats {@code space_id}
 * so an accidental cross-space lookup fails closed.</p>
 */
@Repository
public class ChunkRepository {

    /** Immutable semantic parent chunk (1000-1500 tokens). */
    public record ParentChunk(UUID id, UUID spaceId, UUID documentRevisionId, int chunkIndex, int versionNo,
            List<String> headingPath, int tokenStart, int tokenEnd, int charStart, int charEnd,
            String contentRef, Instant createdAt) {
    }

    /** Immutable child chunk (300-500 tokens) that is embedded and directly recalled. */
    public record ChildChunk(UUID id, UUID spaceId, UUID parentChunkId, UUID documentRevisionId, int chunkIndex,
            int versionNo, List<String> headingPath, int tokenStart, int tokenEnd, int charStart, int charEnd,
            Integer pageNumber, String sheet, Integer slideNumber, Integer lineStart, Integer lineEnd, String tableCell,
            String contentRef, String textHash, Instant createdAt) {
    }

    /** Auditable manual chunk edit; versions are append-only. */
    public record ChunkOverride(UUID id, UUID spaceId, UUID childChunkId, UUID documentRevisionId, int versionNo,
            OverrideState state, String reason, String replacedTextHash, UUID createdBy,
            Instant createdAt, Instant updatedAt) {
    }

    public record NewParentChunk(UUID id, UUID spaceId, UUID documentRevisionId, int chunkIndex, int versionNo,
            List<String> headingPath, int tokenStart, int tokenEnd, int charStart, int charEnd,
            String contentRef, Instant createdAt) {
    }

    public record NewChildChunk(UUID id, UUID spaceId, UUID parentChunkId, UUID documentRevisionId, int chunkIndex,
            int versionNo, List<String> headingPath, int tokenStart, int tokenEnd, int charStart, int charEnd,
            Integer pageNumber, String sheet, Integer slideNumber, Integer lineStart, Integer lineEnd, String tableCell,
            String contentRef, String textHash, Instant createdAt) {
    }

    public record NewChunkOverride(UUID id, UUID spaceId, UUID childChunkId, UUID documentRevisionId,
            String reason, String replacedTextHash, UUID createdBy, Instant now) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ChunkRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void insertParents(List<NewParentChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        for (NewParentChunk chunk : chunks) {
            jdbc.update("""
                    INSERT INTO parent_chunks
                        (id, space_id, document_revision_id, chunk_index, version_no, heading_path,
                         token_start, token_end, char_start, char_end, content_ref, immutable, created_at)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, TRUE, ?)
                    """, chunk.id(), chunk.spaceId(), chunk.documentRevisionId(), chunk.chunkIndex(),
                    chunk.versionNo(), jsonArray(chunk.headingPath()), chunk.tokenStart(), chunk.tokenEnd(),
                    chunk.charStart(), chunk.charEnd(), chunk.contentRef(), timestamp(chunk.createdAt()));
        }
    }

    @Transactional
    public void insertChildren(List<NewChildChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        for (NewChildChunk chunk : chunks) {
            jdbc.update("""
                    INSERT INTO child_chunks
                        (id, space_id, parent_chunk_id, document_revision_id, chunk_index, version_no, heading_path,
                         token_start, token_end, char_start, char_end, page_number, sheet, slide_number,
                         line_start, line_end, table_cell, content_ref, text_hash, immutable, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)
                    """, chunk.id(), chunk.spaceId(), chunk.parentChunkId(), chunk.documentRevisionId(),
                    chunk.chunkIndex(), chunk.versionNo(), jsonArray(chunk.headingPath()), chunk.tokenStart(),
                    chunk.tokenEnd(), chunk.charStart(), chunk.charEnd(), chunk.pageNumber(), chunk.sheet(),
                    chunk.slideNumber(), chunk.lineStart(), chunk.lineEnd(), chunk.tableCell(), chunk.contentRef(),
                    chunk.textHash(), timestamp(chunk.createdAt()));
        }
    }

    public List<ParentChunk> listParents(UUID spaceId, UUID documentRevisionId) {
        return jdbc.query("""
                SELECT id, space_id, document_revision_id, chunk_index, version_no, heading_path::text,
                       token_start, token_end, char_start, char_end, content_ref, created_at
                FROM parent_chunks WHERE space_id = ? AND document_revision_id = ?
                ORDER BY chunk_index
                """, (rs, row) -> new ParentChunk(
                rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getObject("document_revision_id", UUID.class), rs.getInt("chunk_index"), rs.getInt("version_no"),
                stringList(rs.getString("heading_path")), rs.getInt("token_start"), rs.getInt("token_end"),
                rs.getInt("char_start"), rs.getInt("char_end"), rs.getString("content_ref"),
                instant(rs, "created_at")), spaceId, documentRevisionId);
    }

    public List<ChildChunk> listChildren(UUID spaceId, UUID documentRevisionId) {
        return jdbc.query("""
                SELECT id, space_id, parent_chunk_id, document_revision_id, chunk_index, version_no, heading_path::text,
                       token_start, token_end, char_start, char_end, page_number, sheet, slide_number,
                       line_start, line_end, table_cell, content_ref, text_hash, created_at
                FROM child_chunks WHERE space_id = ? AND document_revision_id = ?
                ORDER BY chunk_index
                """, (rs, row) -> new ChildChunk(
                rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getObject("parent_chunk_id", UUID.class), rs.getObject("document_revision_id", UUID.class),
                rs.getInt("chunk_index"), rs.getInt("version_no"), stringList(rs.getString("heading_path")),
                rs.getInt("token_start"), rs.getInt("token_end"), rs.getInt("char_start"), rs.getInt("char_end"),
                (Integer) rs.getObject("page_number"), rs.getString("sheet"), (Integer) rs.getObject("slide_number"),
                (Integer) rs.getObject("line_start"), (Integer) rs.getObject("line_end"), rs.getString("table_cell"),
                rs.getString("content_ref"), rs.getString("text_hash"), instant(rs, "created_at")),
                spaceId, documentRevisionId);
    }

    /** Creates override version 1. A different target revision than the child forces NEEDS_REVIEW. */
    @Transactional
    public ChunkOverride createOverride(NewChunkOverride input) {
        UUID childRevisionId = jdbc.queryForObject(
                "SELECT document_revision_id FROM child_chunks WHERE space_id = ? AND id = ?",
                UUID.class, input.spaceId(), input.childChunkId());
        OverrideState initialState = ChunkOverrideTransitions.initialStateForRevision(
                input.documentRevisionId(), childRevisionId);
        int nextVersion = findLatestOverride(input.spaceId(), input.childChunkId())
                .map(ChunkOverride::versionNo).map(version -> version + 1).orElse(1);
        jdbc.update("""
                INSERT INTO chunk_overrides
                    (id, space_id, child_chunk_id, document_revision_id, version_no, override_state,
                     override_source, reason, replaced_text_hash, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'MANUAL', ?, ?, ?, ?, ?)
                """, input.id(), input.spaceId(), input.childChunkId(), input.documentRevisionId(),
                nextVersion, initialState.name(), input.reason(), input.replacedTextHash(),
                input.createdBy(), timestamp(input.now()), timestamp(input.now()));
        return findLatestOverride(input.spaceId(), input.childChunkId()).orElseThrow();
    }

    /** Appends a new override version after validating the state machine transition. */
    @Transactional
    public ChunkOverride updateOverrideState(UUID spaceId, UUID overrideId, OverrideState targetState, Instant updatedAt) {
        ChunkOverride current = findById(spaceId, overrideId)
                .orElseThrow(() -> new IllegalArgumentException("override not found in space " + spaceId));
        ChunkOverrideTransitions.requireTransition(current.state(), targetState);
        int nextVersion = current.versionNo() + 1;
        jdbc.update("""
                INSERT INTO chunk_overrides
                    (id, space_id, child_chunk_id, document_revision_id, version_no, override_state,
                     override_source, reason, replaced_text_hash, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'MANUAL', ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), spaceId, current.childChunkId(), current.documentRevisionId(),
                nextVersion, targetState.name(), current.reason(), current.replacedTextHash(),
                current.createdBy(), timestamp(current.createdAt()), timestamp(updatedAt));
        return findLatestOverride(spaceId, current.childChunkId()).orElseThrow();
    }

    public Optional<ChunkOverride> findLatestOverride(UUID spaceId, UUID childChunkId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, space_id, child_chunk_id, document_revision_id, version_no, override_state,
                           reason, replaced_text_hash, created_by, created_at, updated_at
                    FROM chunk_overrides WHERE space_id = ? AND child_chunk_id = ?
                    ORDER BY version_no DESC LIMIT 1
                    """, (rs, row) -> mapOverride(rs), spaceId, childChunkId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<ChunkOverride> findById(UUID spaceId, UUID overrideId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, space_id, child_chunk_id, document_revision_id, version_no, override_state,
                           reason, replaced_text_hash, created_by, created_at, updated_at
                    FROM chunk_overrides WHERE space_id = ? AND id = ?
                    """, (rs, row) -> mapOverride(rs), spaceId, overrideId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public List<ChunkOverride> listOverrides(UUID spaceId, OverrideState state) {
        return jdbc.query("""
                SELECT id, space_id, child_chunk_id, document_revision_id, version_no, override_state,
                       reason, replaced_text_hash, created_by, created_at, updated_at
                FROM chunk_overrides WHERE space_id = ? AND override_state = ?
                ORDER BY updated_at DESC
                """, (rs, row) -> mapOverride(rs), spaceId, state.name());
    }

    private ChunkOverride mapOverride(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ChunkOverride(
                rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getObject("child_chunk_id", UUID.class), rs.getObject("document_revision_id", UUID.class),
                rs.getInt("version_no"), OverrideState.valueOf(rs.getString("override_state")),
                rs.getString("reason"), rs.getString("replaced_text_hash"),
                rs.getObject("created_by", UUID.class), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private String jsonArray(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid heading path", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(String json) {
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception exception) {
            throw new IllegalStateException("corrupt heading_path jsonb", exception);
        }
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
