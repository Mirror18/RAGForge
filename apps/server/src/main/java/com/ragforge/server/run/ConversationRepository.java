package com.ragforge.server.run;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
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
                            SELECT id, space_id, actor_user_id, title, created_at, updated_at, version
                            FROM conversations WHERE id = ? AND space_id = ?
                            """, (rs, rowNum) -> new ConversationRecord(
                    rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                    rs.getObject("actor_user_id", UUID.class), rs.getString("title"),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                    rs.getLong("version")), id, spaceId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public record ConversationRecord(UUID id, UUID spaceId, UUID actorUserId, String title,
                                     Instant createdAt, Instant updatedAt, long version) {
    }
}
