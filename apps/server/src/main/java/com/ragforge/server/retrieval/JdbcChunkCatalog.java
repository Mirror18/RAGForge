package com.ragforge.server.retrieval;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.ObjectMapper;

/** PostgreSQL adapter for immutable child metadata; every predicate repeats space_id. */
@Repository
public class JdbcChunkCatalog implements ChunkCatalog {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcChunkCatalog(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<ChildMetadata> findChild(UUID spaceId, UUID childChunkId) {
        requireScope(spaceId, childChunkId);
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, space_id, parent_chunk_id, document_revision_id, chunk_index,
                           heading_path::text, token_start, token_end, char_start, char_end,
                           page_number, sheet, slide_number, line_start, line_end, table_cell,
                           content_ref, text_hash
                    FROM child_chunks WHERE space_id = ? AND id = ?
                    """, (rs, row) -> map(rs), spaceId, childChunkId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public List<ChildMetadata> listChildrenByParent(UUID spaceId, UUID parentChunkId) {
        requireScope(spaceId, parentChunkId);
        return jdbc.query("""
                SELECT id, space_id, parent_chunk_id, document_revision_id, chunk_index,
                       heading_path::text, token_start, token_end, char_start, char_end,
                       page_number, sheet, slide_number, line_start, line_end, table_cell,
                       content_ref, text_hash
                FROM child_chunks WHERE space_id = ? AND parent_chunk_id = ?
                ORDER BY chunk_index
                """, (rs, row) -> map(rs), spaceId, parentChunkId);
    }

    private ChildMetadata map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ChildMetadata(
                rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getObject("parent_chunk_id", UUID.class), rs.getObject("document_revision_id", UUID.class),
                rs.getInt("chunk_index"), stringList(rs.getString("heading_path")),
                rs.getInt("token_start"), rs.getInt("token_end"), rs.getInt("char_start"),
                rs.getInt("char_end"), (Integer) rs.getObject("page_number"), rs.getString("sheet"),
                (Integer) rs.getObject("slide_number"), (Integer) rs.getObject("line_start"),
                (Integer) rs.getObject("line_end"), rs.getString("table_cell"),
                rs.getString("content_ref"), rs.getString("text_hash"));
    }

    private List<String> stringList(String json) throws java.sql.SQLException {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception exception) {
            throw new java.sql.SQLException("invalid heading_path", exception);
        }
    }

    private static void requireScope(UUID spaceId, UUID id) {
        if (spaceId == null || id == null) {
            throw new IllegalArgumentException("spaceId and chunk identity are required");
        }
    }
}
