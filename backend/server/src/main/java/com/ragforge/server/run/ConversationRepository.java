package com.ragforge.server.run;

import com.ragforge.server.ingestion.CursorCodec;
import com.ragforge.server.ingestion.CursorPage;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;

@Repository
public class ConversationRepository {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
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
        return list(spaceId, includeArchived, null, null).items();
    }

    public CursorPage<ConversationRecord> list(UUID spaceId, boolean includeArchived,
                                               String cursor, Integer limit) {
        int pageSize = pageSize(limit);
        CursorCodec.Position position = cursor == null ? null : CursorCodec.decode(cursor);
        String statusClause = includeArchived ? "" : " AND status = 'ACTIVE'";
        StringBuilder sql = new StringBuilder("""
                        SELECT id, space_id, actor_user_id, title, status, archived_at, archived_by,
                               created_at, updated_at, version
                        FROM conversations
                        WHERE space_id = ?""" + statusClause);
        List<Object> args = new ArrayList<>(List.of(spaceId));
        if (position != null) {
            sql.append(" AND (updated_at, id) < (?, ?)");
            args.add(Timestamp.from(position.sortTime()));
            args.add(position.id());
        }
        sql.append(" ORDER BY updated_at DESC, id DESC LIMIT ?");
        args.add(pageSize + 1);
        List<ConversationRecord> rows = jdbc.query(sql.toString(),
                (rs, rowNum) -> map(rs), args.toArray());
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) rows = rows.subList(0, pageSize);
        String nextCursor = hasMore
                ? CursorCodec.encode(new CursorCodec.Position(rows.getLast().updatedAt(), rows.getLast().id()))
                : null;
        return new CursorPage<>(rows, nextCursor);
    }

    private static int pageSize(Integer limit) {
        if (limit == null) return DEFAULT_PAGE_SIZE;
        if (limit < 1 || limit > MAX_PAGE_SIZE) throw new IllegalArgumentException("limit is invalid");
        return limit;
    }

    @Transactional
    public ConversationRecord archive(UUID spaceId, UUID id, UUID actorUserId, Instant now) {
        ConversationRecord existing = find(spaceId, id).orElseThrow();
        return archive(spaceId, id, actorUserId, now, existing.version());
    }

    @Transactional
    public ConversationRecord archive(UUID spaceId, UUID id, UUID actorUserId, Instant now, long expectedVersion) {
        ConversationRecord existing = find(spaceId, id).orElseThrow();
        if ("ARCHIVED".equals(existing.status())) return existing;
        if (existing.version() != expectedVersion) {
            throw new OptimisticLockException("Conversation version has changed");
        }
        int updated = jdbc.update("""
                        UPDATE conversations
                        SET status = 'ARCHIVED', archived_at = ?, archived_by = ?, updated_at = ?, version = version + 1
                        WHERE id = ? AND space_id = ? AND status = 'ACTIVE' AND version = ?
                        """, Timestamp.from(now), actorUserId, Timestamp.from(now), id, spaceId, expectedVersion);
        if (updated != 1) {
            throw new OptimisticLockException("Conversation version has changed");
        }
        return find(spaceId, id).orElseThrow();
    }

    @Transactional
    public ConversationRecord rename(UUID spaceId, UUID id, String title, Instant now, long expectedVersion) {
        if (title == null || title.isBlank() || title.length() > 200) {
            throw new IllegalArgumentException("Conversation title is invalid");
        }
        int updated = jdbc.update("""
                UPDATE conversations SET title = ?, updated_at = ?, version = version + 1
                WHERE id = ? AND space_id = ? AND status = 'ACTIVE' AND version = ?
                """, title.trim(), Timestamp.from(now), id, spaceId, expectedVersion);
        if (updated != 1) throw new OptimisticLockException("Conversation version has changed");
        return find(spaceId, id).orElseThrow();
    }

    public Optional<CommandReceipt> findCommand(UUID spaceId, String idempotencyKey) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, space_id, conversation_id, operation, idempotency_key, request_hash,
                           expected_version, result_version, actor_user_id, created_at
                    FROM conversation_command_receipts
                    WHERE space_id = ? AND idempotency_key = ?
                    """, (rs, row) -> new CommandReceipt(rs.getObject("id", UUID.class),
                    rs.getObject("space_id", UUID.class), rs.getObject("conversation_id", UUID.class),
                    rs.getString("operation"), rs.getString("idempotency_key"), rs.getString("request_hash"),
                    (Long) rs.getObject("expected_version"), (Long) rs.getObject("result_version"),
                    rs.getObject("actor_user_id", UUID.class), rs.getTimestamp("created_at").toInstant()),
                    spaceId, idempotencyKey));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Transactional
    public void recordCommand(UUID id, UUID spaceId, UUID conversationId, String operation,
                              String idempotencyKey, String requestHash, Long expectedVersion,
                              Long resultVersion, UUID actorUserId, Instant now) {
        jdbc.update("""
                INSERT INTO conversation_command_receipts
                    (id, space_id, conversation_id, operation, idempotency_key, request_hash,
                     expected_version, result_version, actor_user_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, spaceId, conversationId, operation, idempotencyKey, requestHash,
                expectedVersion, resultVersion, actorUserId, Timestamp.from(now));
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

    public record CommandReceipt(UUID id, UUID spaceId, UUID conversationId, String operation,
                                 String idempotencyKey, String requestHash, Long expectedVersion,
                                 Long resultVersion, UUID actorUserId, Instant createdAt) {
    }

    public static final class OptimisticLockException extends RuntimeException {
        public OptimisticLockException(String message) {
            super(message);
        }
    }
}
