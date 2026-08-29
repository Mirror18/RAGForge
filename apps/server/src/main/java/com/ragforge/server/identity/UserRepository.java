package com.ragforge.server.identity;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UserAccount create(UUID id, String email, String passwordHash, String displayName) {
        jdbc.update("""
                        INSERT INTO users (id, email, password_hash, display_name, platform_role, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, 'USER', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """, id, email, passwordHash, displayName);
        return findById(id).orElseThrow();
    }

    public UserAccount createPlatformAdmin(UUID id, String email, String passwordHash, String displayName,
                                           java.time.Instant now) {
        jdbc.update("""
                        INSERT INTO users
                            (id, email, password_hash, display_name, platform_role, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, 'PLATFORM_ADMIN', 'ACTIVE', ?, ?)
                        """, id, email, passwordHash, displayName, java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now));
        return findById(id).orElseThrow();
    }

    public Optional<UserAccount> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT id, email, display_name, password_hash, platform_role, status, created_at, updated_at
                            FROM users WHERE id = ?
                            """, (rs, rowNum) -> new UserAccount(rs.getObject("id", UUID.class),
                            rs.getString("email"), rs.getString("display_name"),
                            rs.getString("password_hash"), rs.getString("platform_role"), rs.getString("status"),
                            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), id));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<UserAccount> findByEmail(String email) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT id, email, display_name, password_hash, platform_role, status, created_at, updated_at
                            FROM users WHERE LOWER(email) = LOWER(?) AND status = 'ACTIVE'
                            """, (rs, rowNum) -> new UserAccount(rs.getObject("id", UUID.class),
                            rs.getString("email"), rs.getString("display_name"),
                            rs.getString("password_hash"), rs.getString("platform_role"), rs.getString("status"),
                            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), email));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<UserAccount> findByEmailIncludingDisabled(String email) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT id, email, display_name, password_hash, platform_role, status, created_at, updated_at
                            FROM users WHERE LOWER(email) = LOWER(?)
                            """, (rs, rowNum) -> map(rs), email));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public boolean hasPlatformAdmin() {
        Boolean result = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM users WHERE platform_role = 'PLATFORM_ADMIN' AND status = 'ACTIVE')
                """, Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    public void lockPlatformAdminBootstrap() {
        jdbc.execute("SELECT pg_advisory_xact_lock(7242246630020250829)");
    }

    public boolean exists(UUID id) {
        Boolean result = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id = ?)", Boolean.class, id);
        return Boolean.TRUE.equals(result);
    }

    public List<UserAccount> findAll() {
        return jdbc.query("""
                        SELECT id, email, display_name, password_hash, platform_role, status, created_at, updated_at
                        FROM users ORDER BY created_at, id
                        """, (rs, rowNum) -> map(rs));
    }

    public UserAccount update(UUID id, String displayName, String platformRole, String status,
                              String passwordHash, java.time.Instant now) {
        jdbc.update("""
                        UPDATE users
                        SET display_name = ?, platform_role = ?, status = ?,
                            password_hash = COALESCE(?, password_hash), updated_at = ?
                        WHERE id = ?
                        """, displayName, platformRole, status, passwordHash,
                java.sql.Timestamp.from(now), id);
        return findByIdIncludingDisabled(id).orElseThrow();
    }

    public Optional<UserAccount> findByIdIncludingDisabled(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT id, email, display_name, password_hash, platform_role, status, created_at, updated_at
                            FROM users WHERE id = ?
                            """, (rs, rowNum) -> map(rs), id));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private UserAccount map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UserAccount(rs.getObject("id", UUID.class), rs.getString("email"),
                rs.getString("display_name"), rs.getString("password_hash"), rs.getString("platform_role"),
                rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
