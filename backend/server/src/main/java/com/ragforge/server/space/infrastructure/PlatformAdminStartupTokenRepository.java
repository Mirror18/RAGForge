package com.ragforge.server.space.infrastructure;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class PlatformAdminStartupTokenRepository {
    private final JdbcTemplate jdbc;

    public PlatformAdminStartupTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasActiveToken() {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM platform_admin_startup_tokens WHERE singleton_id = 1)
                """, Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public Optional<String> findTokenHash() {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT token_hash FROM platform_admin_startup_tokens WHERE singleton_id = 1
                    """, String.class));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public void replace(String tokenHash, Instant issuedAt) {
        jdbc.update("""
                INSERT INTO platform_admin_startup_tokens (singleton_id, token_hash, issued_at)
                VALUES (1, ?, ?)
                ON CONFLICT (singleton_id) DO UPDATE
                SET token_hash = EXCLUDED.token_hash, issued_at = EXCLUDED.issued_at
                """, tokenHash, Timestamp.from(issuedAt));
    }

    public void clear() {
        jdbc.update("DELETE FROM platform_admin_startup_tokens WHERE singleton_id = 1");
    }
}
