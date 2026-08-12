package com.ragforge.server.identity;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UserAccount create(UUID id, String email, String passwordHash, String displayName) {
        jdbc.update("""
                        INSERT INTO users (id, email, password_hash, display_name, platform_role, created_at)
                        VALUES (?, ?, ?, ?, 'USER', CURRENT_TIMESTAMP)
                        """, id, email, passwordHash, displayName);
        return findById(id).orElseThrow();
    }

    public Optional<UserAccount> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT id, email, display_name, password_hash, platform_role
                            FROM users WHERE id = ?
                            """, (rs, rowNum) -> new UserAccount(rs.getObject("id", UUID.class),
                            rs.getString("email"), rs.getString("display_name"),
                            rs.getString("password_hash"), rs.getString("platform_role")), id));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<UserAccount> findByEmail(String email) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT id, email, display_name, password_hash, platform_role
                            FROM users WHERE LOWER(email) = LOWER(?)
                            """, (rs, rowNum) -> new UserAccount(rs.getObject("id", UUID.class),
                            rs.getString("email"), rs.getString("display_name"),
                            rs.getString("password_hash"), rs.getString("platform_role")), email));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public boolean exists(UUID id) {
        Boolean result = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id = ?)", Boolean.class, id);
        return Boolean.TRUE.equals(result);
    }
}
