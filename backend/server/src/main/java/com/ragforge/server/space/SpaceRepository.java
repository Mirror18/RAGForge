package com.ragforge.server.space;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SpaceRepository {
    private final JdbcTemplate jdbc;

    public SpaceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public KnowledgeSpace create(UUID id, String name, String description, Instant now) {
        jdbc.update("""
                        INSERT INTO knowledge_spaces (id, name, description, status, created_at, updated_at, version)
                        VALUES (?, ?, ?, 'ACTIVE', ?, ?, 0)
                        """, id, name, description, Timestamp.from(now), Timestamp.from(now));
        return findById(id).orElseThrow();
    }

    public void addMembership(UUID spaceId, UUID userId, SpaceRole role, Instant now) {
        jdbc.update("""
                        INSERT INTO space_memberships (space_id, user_id, role, created_at, updated_at, version)
                        VALUES (?, ?, ?, ?, ?, 0)
                        """, spaceId, userId, role.name(), Timestamp.from(now), Timestamp.from(now));
    }

    public List<KnowledgeSpace> findAllForUser(UUID userId) {
        return jdbc.query("""
                        SELECT s.id, s.name, s.description, s.status, m.role, s.created_at, s.updated_at, s.version
                        FROM knowledge_spaces s
                        JOIN space_memberships m ON m.space_id = s.id
                        WHERE m.user_id = ? AND s.status = 'ACTIVE'
                        ORDER BY s.created_at, s.id
                        """, (rs, rowNum) -> mapWithRole(rs), userId);
    }

    public Optional<KnowledgeSpace> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT id, name, description, status, created_at, updated_at, version
                            FROM knowledge_spaces WHERE id = ?
                            """, (rs, rowNum) -> map(rs), id));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<SpaceRole> findRole(UUID spaceId, UUID userId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT role FROM space_memberships WHERE space_id = ? AND user_id = ?",
                    String.class, spaceId, userId)).map(SpaceRole::parse);
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public boolean userExists(UUID userId) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id = ? AND status = 'ACTIVE')", Boolean.class, userId);
        return Boolean.TRUE.equals(exists);
    }

    public List<SpaceMemberView> findMembers(UUID spaceId) {
        return jdbc.query("""
                        SELECT m.space_id, m.user_id, u.email, u.display_name, m.role, m.version
                        FROM space_memberships m JOIN users u ON u.id = m.user_id
                        WHERE m.space_id = ? ORDER BY m.created_at, m.user_id
                        """, (rs, rowNum) -> new SpaceMemberView(rs.getObject("space_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("email"), rs.getString("display_name"),
                SpaceRole.parse(rs.getString("role")), rs.getLong("version")), spaceId);
    }

    public int countAdmins(UUID spaceId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM space_memberships WHERE space_id = ? AND role = 'SPACE_ADMIN'",
                Integer.class, spaceId);
    }

    public boolean updateSpace(UUID spaceId, String name, String description, long expectedVersion, Instant now) {
        return jdbc.update("""
                        UPDATE knowledge_spaces
                        SET name = ?, description = ?, updated_at = ?, version = version + 1
                        WHERE id = ? AND status = 'ACTIVE' AND version = ?
                        """, name, description, Timestamp.from(now), spaceId, expectedVersion) == 1;
    }

    public boolean archive(UUID spaceId, long expectedVersion, Instant now) {
        return jdbc.update("""
                        UPDATE knowledge_spaces SET status = 'ARCHIVED', updated_at = ?, version = version + 1
                        WHERE id = ? AND status = 'ACTIVE' AND version = ?
                        """, Timestamp.from(now), spaceId, expectedVersion) == 1;
    }

    public boolean deleteMembership(UUID spaceId, UUID userId) {
        return jdbc.update("DELETE FROM space_memberships WHERE space_id = ? AND user_id = ?", spaceId, userId) == 1;
    }

    public long upsertMembership(UUID spaceId, UUID userId, SpaceRole role, Instant now) {
        jdbc.update("""
                        INSERT INTO space_memberships (space_id, user_id, role, created_at, updated_at, version)
                        VALUES (?, ?, ?, ?, ?, 0)
                        ON CONFLICT (space_id, user_id) DO UPDATE
                        SET role = EXCLUDED.role, updated_at = EXCLUDED.updated_at,
                            version = space_memberships.version + 1
                        """, spaceId, userId, role.name(), Timestamp.from(now), Timestamp.from(now));
        return jdbc.queryForObject("SELECT version FROM space_memberships WHERE space_id = ? AND user_id = ?",
                Long.class, spaceId, userId);
    }

    private KnowledgeSpace map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new KnowledgeSpace(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("description"), rs.getString("status"), null, rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"));
    }

    private KnowledgeSpace mapWithRole(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new KnowledgeSpace(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("description"), rs.getString("status"), SpaceRole.parse(rs.getString("role")),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"));
    }
}
