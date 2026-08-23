package com.ragforge.server.common;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.Optional;

@Repository
public class IdempotencyRepository {
    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean tryCreate(String principalScope, String key, String requestHash,
                             String method, String requestPath, Instant createdAt) {
        try {
            jdbc.update("""
                    INSERT INTO idempotency_records
                        (principal_scope, idempotency_key, request_hash, method, request_path, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, principalScope, key, requestHash, method, requestPath, Timestamp.from(createdAt));
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    public Optional<Record> find(String principalScope, String key) {
        return jdbc.query("""
                SELECT request_hash, method, request_path, status_code
                FROM idempotency_records
                WHERE principal_scope = ? AND idempotency_key = ?
                """, (rs, rowNum) -> new Record(rs.getString("request_hash"), rs.getString("method"),
                rs.getString("request_path"), (Integer) rs.getObject("status_code")), principalScope, key)
                .stream().findFirst();
    }

    public void markCompleted(String principalScope, String key, int statusCode, Instant completedAt) {
        jdbc.update("""
                UPDATE idempotency_records
                SET status_code = ?, completed_at = ?
                WHERE principal_scope = ? AND idempotency_key = ?
                """, statusCode, Timestamp.from(completedAt), principalScope, key);
    }

    public void updateRequestHash(String principalScope, String key, String requestHash) {
        jdbc.update("UPDATE idempotency_records SET request_hash = ? WHERE principal_scope = ? AND idempotency_key = ?",
                requestHash, principalScope, key);
    }

    public record Record(String requestHash, String method, String requestPath, Integer statusCode) {
    }
}
