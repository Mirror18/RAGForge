package com.ragforge.server.identity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.config.SessionProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SessionRepository {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final SessionProperties properties;

    public SessionRepository(JdbcTemplate jdbc, StringRedisTemplate redis, ObjectMapper objectMapper,
                             SessionProperties properties) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void create(UUID sessionId, String tokenHash, UUID userId, String csrfToken,
                       Instant createdAt, Instant expiresAt) {
        jdbc.update("""
                        INSERT INTO sessions (id, token_hash, user_id, csrf_token, created_at, expires_at, last_seen_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, sessionId, tokenHash, userId, csrfToken, Timestamp.from(createdAt),
                Timestamp.from(expiresAt), Timestamp.from(createdAt));
        putActiveSession(tokenHash, new StoredSession(sessionId, userId, csrfToken, expiresAt),
                java.time.Duration.between(Instant.now(), expiresAt));
    }

    public Optional<SessionPrincipal> findActiveByTokenHash(String tokenHash, Instant now) {
        StoredSession stored = readActiveSession(tokenHash);
        if (stored == null || !stored.expiresAt().isAfter(now)) {
            return Optional.empty();
        }
        return jdbc.query("""
                        SELECT email, display_name, platform_role
                        FROM users WHERE id = ? AND status = 'ACTIVE'
                        """, (rs, rowNum) -> new SessionPrincipal(
                        stored.userId(), stored.sessionId(), rs.getString("email"), rs.getString("display_name"),
                        stored.csrfToken(), rs.getString("platform_role"), stored.expiresAt()),
                stored.userId()).stream().findFirst();
    }

    public void touch(UUID sessionId, Instant now) {
        jdbc.update("UPDATE sessions SET last_seen_at = ? WHERE id = ? AND revoked_at IS NULL",
                Timestamp.from(now), sessionId);
    }

    public void revoke(UUID sessionId, Instant now) {
        String tokenHash = jdbc.query("SELECT token_hash FROM sessions WHERE id = ?", (rs, rowNum) -> rs.getString(1),
                sessionId).stream().findFirst().orElse(null);
        if (tokenHash != null) {
            redis.delete(key(tokenHash));
        }
        jdbc.update("UPDATE sessions SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
                Timestamp.from(now), sessionId);
    }

    private void putActiveSession(String tokenHash, StoredSession session, java.time.Duration ttl) {
        try {
            redis.opsForValue().set(key(tokenHash), objectMapper.writeValueAsString(session), ttl);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize active session", exception);
        }
    }

    private StoredSession readActiveSession(String tokenHash) {
        String value = redis.opsForValue().get(key(tokenHash));
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, StoredSession.class);
        } catch (JsonProcessingException exception) {
            redis.delete(key(tokenHash));
            return null;
        }
    }

    private String key(String tokenHash) {
        return properties.getKeyPrefix() + tokenHash;
    }

    private record StoredSession(UUID sessionId, UUID userId, String csrfToken, Instant expiresAt) {
    }
}
