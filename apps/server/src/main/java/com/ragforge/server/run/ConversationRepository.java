package com.ragforge.server.run;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

@Repository
public class ConversationRepository {
    private final JdbcTemplate jdbc;

    public ConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public ConversationRecord create(UUID id, UUID spaceId, UUID actorUserId, String title, Instant now) {
        if (title == null || title.isBlank() || title.length() > 200) {
            throw new IllegalArgumentException("Conversation title is invalid");
        }
        jdbc.update("""
                        INSERT INTO conversations (id, space_id, actor_user_id, title, created_at, updated_at, version)
                        VALUES (?, ?, ?, ?, ?, ?, 0)
                        """, id, spaceId, actorUserId, title.trim(), Timestamp.from(now), Timestamp.from(now));
        return find(spaceId, id).orElseThrow();
    }

    public Optional<ConversationRecord> find(UUID spaceId, UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT id, space_id, actor_user_id, title, status, archived_at, archived_by,
                                   created_at, updated_at, version
                            FROM conversations WHERE id = ? AND space_id = ?
                            """, (rs, rowNum) -> new ConversationRecord(
                    rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                    rs.getObject("actor_user_id", UUID.class), rs.getString("title"),
                    rs.getString("status"), nullableInstant(rs, "archived_at"), rs.getObject("archived_by", UUID.class),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                    rs.getLong("version")), id, spaceId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public List<ConversationRecord> list(UUID spaceId, boolean includeArchived) {
        String statusClause = includeArchived ? "" : " AND status = 'ACTIVE'";
        return jdbc.query("""
                        SELECT id, space_id, actor_user_id, title, status, archived_at, archived_by,
                               created_at, updated_at, version
                        FROM conversations
                        WHERE space_id = ?""" + statusClause + " ORDER BY updated_at DESC, id DESC",
                (rs, rowNum) -> map(rs), spaceId);
    }

    @Transactional
    public ConversationRecord archive(UUID spaceId, UUID id, UUID actorUserId, Instant now) {
        ConversationRecord existing = find(spaceId, id).orElseThrow();
        if ("ARCHIVED".equals(existing.status())) {
            return existing;
        }
        int updated = jdbc.update("""
                        UPDATE conversations
                        SET status = 'ARCHIVED', archived_at = ?, archived_by = ?, updated_at = ?, version = version + 1
                        WHERE id = ? AND space_id = ? AND status = 'ACTIVE'
                        """, Timestamp.from(now), actorUserId, Timestamp.from(now), id, spaceId);
        if (updated != 1) {
            throw new IllegalStateException("Conversation is not active in the requested space");
        }
        return find(spaceId, id).orElseThrow();
    }

    private ConversationRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ConversationRecord(
                rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getObject("actor_user_id", UUID.class), rs.getString("title"), rs.getString("status"),
                nullableInstant(rs, "archived_at"), rs.getObject("archived_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"));
    }

    private static Instant nullableInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record ConversationRecord(UUID id, UUID spaceId, UUID actorUserId, String title,
                                     String status, Instant archivedAt, UUID archivedBy,
                                     Instant createdAt, Instant updatedAt, long version) {
        public ConversationRecord(UUID id, UUID spaceId, UUID actorUserId, String title,
                                  Instant createdAt, Instant updatedAt, long version) {
            this(id, spaceId, actorUserId, title, "ACTIVE", null, null, createdAt, updatedAt, version);
        }
    }
}
