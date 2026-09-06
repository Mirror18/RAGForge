package com.ragforge.server.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL persistence for immutable retrieval execution identities. */
@Repository
public class RetrievalExecutionSnapshotRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public RetrievalExecutionSnapshotRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public Optional<RetrievalExecutionSnapshot> find(UUID spaceId, UUID snapshotId) {
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        List<RetrievalExecutionSnapshot> values = jdbc.query("""
                SELECT snapshot_id, space_id, schema_version, execution_key, index_version_id, profile_id,
                       profile_version, effective_parameters_json, route_version, executor_version,
                       degradation_policy, evidence_bundle_id, evidence_bundle_version, evidence_bundle_ref,
                       dataset_hash, config_hash, created_at
                  FROM retrieval_execution_snapshots
                 WHERE space_id = ? AND snapshot_id = ?
                """, this::map, spaceId, snapshotId);
        return values.stream().findFirst();
    }

    public Optional<RetrievalExecutionSnapshot> findByExecutionKey(UUID spaceId, String executionKey) {
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(executionKey, "executionKey");
        List<RetrievalExecutionSnapshot> values = jdbc.query("""
                SELECT snapshot_id, space_id, schema_version, execution_key, index_version_id, profile_id,
                       profile_version, effective_parameters_json, route_version, executor_version,
                       degradation_policy, evidence_bundle_id, evidence_bundle_version, evidence_bundle_ref,
                       dataset_hash, config_hash, created_at
                  FROM retrieval_execution_snapshots
                 WHERE space_id = ? AND execution_key = ?
                """, this::map, spaceId, executionKey);
        return values.stream().findFirst();
    }

    public RetrievalExecutionSnapshot saveIfAbsent(RetrievalExecutionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Optional<RetrievalExecutionSnapshot> existing = findByExecutionKey(snapshot.spaceId(), snapshot.executionKey());
        if (existing.isPresent()) {
            if (!existing.get().sameExecutionIdentity(snapshot)) {
                throw new IllegalStateException("retrieval execution key is already bound to another snapshot");
            }
            return existing.get();
        }
        try {
            jdbc.update("""
                    INSERT INTO retrieval_execution_snapshots (
                        snapshot_id, space_id, schema_version, execution_key, index_version_id, profile_id,
                        profile_version, effective_parameters_hash, effective_parameters_json, route_version,
                        executor_version, degradation_policy, evidence_bundle_id, evidence_bundle_version,
                        evidence_bundle_ref, dataset_hash, config_hash, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, snapshot.snapshotId(), snapshot.spaceId(), snapshot.schemaVersion(), snapshot.executionKey(),
                    snapshot.indexVersionId(), snapshot.profileId(), snapshot.profileVersion(),
                    snapshot.effectiveParametersHash(), snapshot.effectiveParameters().canonicalJson(),
                    snapshot.routeVersion(), snapshot.executorVersion(), snapshot.degradationPolicy(),
                    snapshot.evidenceBundleId(), snapshot.evidenceBundleVersion(), snapshot.evidenceBundleRef(),
                    snapshot.datasetHash(), snapshot.configHash(), Timestamp.from(snapshot.createdAt()));
            return snapshot;
        } catch (DuplicateKeyException race) {
            return findByExecutionKey(snapshot.spaceId(), snapshot.executionKey())
                    .filter(snapshot::sameExecutionIdentity)
                    .orElseThrow(() -> new IllegalStateException(
                            "retrieval execution key raced with an incompatible snapshot", race));
        }
    }

    private RetrievalExecutionSnapshot map(ResultSet rs, int rowNum) throws SQLException {
        try {
            JsonNode json = objectMapper.readTree(rs.getString("effective_parameters_json"));
            RetrievalExecutionSnapshot.EffectiveParameters parameters = new RetrievalExecutionSnapshot.EffectiveParameters(
                    json.path("denseTopK").asInt(0), json.path("bm25TopK").asInt(0), json.path("rrfK").asInt(0),
                    json.path("rrfDenseWeight").asDouble(Double.NaN), json.path("rrfBm25Weight").asDouble(Double.NaN),
                    json.path("rerankTopK").asInt(0), json.path("maxContextChildren").asInt(0),
                    json.path("expansionMode").asText(null), json.path("maxParentsPerChild").asInt(-1),
                    json.path("maxNeighborsPerParent").asInt(-1), json.path("maxContextTokens").asInt(0));
            Instant createdAt = rs.getTimestamp("created_at").toInstant();
            return new RetrievalExecutionSnapshot(rs.getString("schema_version"), rs.getObject("snapshot_id", UUID.class),
                    rs.getObject("space_id", UUID.class), rs.getString("execution_key"),
                    rs.getObject("index_version_id", UUID.class), rs.getObject("profile_id", UUID.class),
                    rs.getInt("profile_version"), parameters, rs.getString("route_version"),
                    rs.getString("executor_version"), rs.getString("degradation_policy"),
                    rs.getObject("evidence_bundle_id", UUID.class), rs.getInt("evidence_bundle_version"),
                    rs.getString("evidence_bundle_ref"), rs.getString("dataset_hash"),
                    rs.getString("config_hash"), createdAt);
        } catch (Exception failure) {
            throw new SQLException("stored retrieval snapshot is invalid", failure);
        }
    }
}
