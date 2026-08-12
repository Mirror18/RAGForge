package com.ragforge.server.provider;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for versioned provider configuration.
 *
 * <p>All space-scoped reads take a space id. A provider connection may be global
 * ({@code space_id IS NULL}), but it is only usable through a space-aware profile
 * creation check.</p>
 */
@Repository
public class ProviderRepository {
    private final JdbcTemplate jdbc;

    public ProviderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public ProviderConnection createConnection(NewProviderConnection input) {
        jdbc.update("""
                        INSERT INTO provider_connections
                            (id, space_id, provider_key, display_name, provider_type, endpoint_uri,
                             credential_ref, credential_hash, auth_scheme, non_secret_headers,
                             status, egress_policy, created_at, updated_at, correlation_id, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, 0)
                        """, input.id(), input.spaceId(), input.providerKey(), input.displayName(),
                input.providerType().name(), input.endpointUri(), input.credentialRef(), input.credentialHash(),
                input.authScheme(), jsonOrEmpty(input.nonSecretHeadersJson()), input.status().name(),
                input.egressPolicy().name(), timestamp(input.now()), timestamp(input.now()), input.correlationId());
        return findConnection(input.spaceId(), input.id()).orElseThrow();
    }

    public Optional<ProviderConnection> findConnection(UUID spaceId, UUID id) {
        try {
            String sql = spaceId == null
                    ? """
                      SELECT id, space_id, provider_key, display_name, provider_type, endpoint_uri,
                             credential_ref, credential_hash, auth_scheme, non_secret_headers,
                             status, egress_policy, created_at, updated_at, correlation_id, version
                      FROM provider_connections WHERE id = ? AND space_id IS NULL
                      """
                    : """
                      SELECT id, space_id, provider_key, display_name, provider_type, endpoint_uri,
                             credential_ref, credential_hash, auth_scheme, non_secret_headers,
                             status, egress_policy, created_at, updated_at, correlation_id, version
                      FROM provider_connections
                      WHERE id = ? AND (space_id = ? OR space_id IS NULL)
                      """;
            return Optional.ofNullable(jdbc.queryForObject(sql, (rs, rowNum) -> mapConnection(rs),
                    spaceId == null ? new Object[]{id} : new Object[]{id, spaceId}));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    /** Returns only connections owned by the requested space. */
    public List<ProviderConnection> listConnections(UUID spaceId) {
        return jdbc.query("""
                        SELECT id, space_id, provider_key, display_name, provider_type, endpoint_uri,
                               credential_ref, credential_hash, auth_scheme, non_secret_headers,
                               status, egress_policy, created_at, updated_at, correlation_id, version
                        FROM provider_connections
                        WHERE space_id = ?
                        ORDER BY created_at, id
                        """, (rs, rowNum) -> mapConnection(rs), spaceId);
    }

    /** Exact space-scoped lookup used by API authorization paths. */
    public Optional<ProviderConnection> findConnectionInSpace(UUID spaceId, UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT id, space_id, provider_key, display_name, provider_type, endpoint_uri,
                                   credential_ref, credential_hash, auth_scheme, non_secret_headers,
                                   status, egress_policy, created_at, updated_at, correlation_id, version
                            FROM provider_connections
                            WHERE id = ? AND space_id = ?
                            """, (rs, rowNum) -> mapConnection(rs), id, spaceId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Transactional
    public ModelProfileVersion createProfileVersion(NewModelProfileVersion input) {
        assertConnectionAvailable(input.spaceId(), input.providerConnectionId());
        jdbc.update("""
                        INSERT INTO model_profile_versions
                            (id, space_id, provider_connection_id, profile_key, version_no, model_name,
                             capabilities, declared_capabilities, verified_capabilities, context_window,
                             max_output_tokens, embedding_dimension, tokenizer, rate_limit, price_table_ref,
                             allowed_parameters, status, created_at, updated_at, correlation_id)
                        VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?,
                                CAST(? AS jsonb), ?, CAST(? AS jsonb), ?, ?, ?, ?)
                        """, input.id(), input.spaceId(), input.providerConnectionId(), input.profileKey(),
                input.versionNo(), input.modelName(), jsonOrEmptyArray(input.capabilitiesJson()),
                jsonOrEmpty(input.declaredCapabilitiesJson()), jsonOrEmpty(input.verifiedCapabilitiesJson()),
                input.contextWindow(), input.maxOutputTokens(), input.embeddingDimension(), input.tokenizer(),
                jsonOrEmpty(input.rateLimitJson()), input.priceTableRef(), jsonOrEmpty(input.allowedParametersJson()),
                input.status().name(), timestamp(input.now()), timestamp(input.now()), input.correlationId());
        return findProfileVersion(input.spaceId(), input.id()).orElseThrow();
    }

    public Optional<ModelProfileVersion> findProfileVersion(UUID spaceId, UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT id, space_id, provider_connection_id, profile_key, version_no, model_name,
                                   capabilities, declared_capabilities, verified_capabilities, context_window,
                                   max_output_tokens, embedding_dimension, tokenizer, rate_limit, price_table_ref,
                                   allowed_parameters, status, created_at, updated_at, correlation_id
                            FROM model_profile_versions WHERE id = ? AND space_id = ?
                            """, (rs, rowNum) -> mapProfile(rs), id, spaceId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Transactional
    public ModelRouteVersion createRouteVersion(NewModelRouteVersion input) {
        jdbc.update("""
                        INSERT INTO model_route_versions
                            (id, space_id, route_key, version_no, purpose, egress_policy, allow_cloud_egress,
                             selection_policy, compatibility, status, created_at, updated_at, correlation_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)
                        """, input.id(), input.spaceId(), input.routeKey(), input.versionNo(), input.purpose().name(),
                input.egressPolicy().name(), input.allowCloudEgress(), input.selectionPolicy().name(),
                jsonOrEmpty(input.compatibilityJson()), input.status().name(), timestamp(input.now()),
                timestamp(input.now()), input.correlationId());
        return findRouteVersion(input.spaceId(), input.id()).orElseThrow();
    }

    public Optional<ModelRouteVersion> findRouteVersion(UUID spaceId, UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT id, space_id, route_key, version_no, purpose, egress_policy,
                                   allow_cloud_egress, selection_policy, compatibility, status,
                                   created_at, updated_at, correlation_id
                            FROM model_route_versions WHERE id = ? AND space_id = ?
                            """, (rs, rowNum) -> mapRoute(rs), id, spaceId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Transactional
    public RouteCandidate addRouteCandidate(NewRouteCandidate input) {
        int inserted = jdbc.update("""
                        INSERT INTO model_route_candidates
                            (id, space_id, model_route_version_id, candidate_no, model_profile_version_id,
                             created_at, updated_at, correlation_id)
                        SELECT ?, ?, ?, ?, ?, ?, ?, ?
                        WHERE EXISTS (
                            SELECT 1 FROM model_route_versions
                            WHERE id = ? AND space_id = ?
                        )
                        AND EXISTS (
                            SELECT 1 FROM model_profile_versions
                            WHERE id = ? AND space_id = ?
                        )
                        """, input.id(), input.spaceId(), input.routeVersionId(), input.candidateNo(),
                input.profileVersionId(), timestamp(input.now()), timestamp(input.now()), input.correlationId(),
                input.routeVersionId(), input.spaceId(), input.profileVersionId(), input.spaceId());
        if (inserted != 1) {
            throw new IllegalArgumentException("Route and profile must belong to the requested space");
        }
        return findRouteCandidate(input.spaceId(), input.id()).orElseThrow();
    }

    public List<RouteCandidate> listRouteCandidates(UUID spaceId, UUID routeVersionId) {
        return jdbc.query("""
                        SELECT id, space_id, model_route_version_id, candidate_no, model_profile_version_id,
                               created_at, updated_at, correlation_id
                        FROM model_route_candidates
                        WHERE space_id = ? AND model_route_version_id = ?
                        ORDER BY candidate_no
                        """, (rs, rowNum) -> mapCandidate(rs), spaceId, routeVersionId);
    }

    @Transactional
    public SpaceModelBinding bindRoute(NewSpaceModelBinding input) {
        int inserted = jdbc.update("""
                        INSERT INTO space_model_bindings
                            (id, space_id, binding_key, version_no, purpose, model_route_version_id,
                             status, created_at, updated_at, correlation_id)
                        SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                        WHERE EXISTS (
                            SELECT 1 FROM model_route_versions
                            WHERE id = ? AND space_id = ?
                        )
                        """, input.id(), input.spaceId(), input.bindingKey(), input.versionNo(), input.purpose().name(),
                input.routeVersionId(), input.status().name(), timestamp(input.now()), timestamp(input.now()),
                input.correlationId(), input.routeVersionId(), input.spaceId());
        if (inserted != 1) {
            throw new IllegalArgumentException("Route must belong to the requested space");
        }
        return findBinding(input.spaceId(), input.id()).orElseThrow();
    }

    public Optional<SpaceModelBinding> findBinding(UUID spaceId, UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT id, space_id, binding_key, version_no, purpose, model_route_version_id,
                                   status, created_at, updated_at, correlation_id
                            FROM space_model_bindings WHERE id = ? AND space_id = ?
                            """, (rs, rowNum) -> mapBinding(rs), id, spaceId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private void assertConnectionAvailable(UUID spaceId, UUID connectionId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM provider_connections
                    WHERE id = ? AND space_id = ?
                )
                """, Boolean.class, connectionId, spaceId);
        if (!Boolean.TRUE.equals(exists)) {
            throw new IllegalArgumentException("Provider connection is not available to the requested space");
        }
    }

    private Optional<RouteCandidate> findRouteCandidate(UUID spaceId, UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT id, space_id, model_route_version_id, candidate_no, model_profile_version_id,
                                   created_at, updated_at, correlation_id
                            FROM model_route_candidates WHERE id = ? AND space_id = ?
                            """, (rs, rowNum) -> mapCandidate(rs), id, spaceId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private ProviderConnection mapConnection(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ProviderConnection(rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getString("provider_key"), rs.getString("display_name"),
                ProviderType.valueOf(rs.getString("provider_type")), rs.getString("endpoint_uri"),
                rs.getString("credential_ref"), rs.getString("credential_hash"), rs.getString("auth_scheme"),
                rs.getString("non_secret_headers"), ProviderStatus.valueOf(rs.getString("status")),
                EgressPolicy.valueOf(rs.getString("egress_policy")), instant(rs, "created_at"),
                instant(rs, "updated_at"), rs.getObject("correlation_id", UUID.class), rs.getLong("version"));
    }

    private ModelProfileVersion mapProfile(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ModelProfileVersion(rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getObject("provider_connection_id", UUID.class), rs.getString("profile_key"),
                rs.getInt("version_no"), rs.getString("model_name"), rs.getString("capabilities"),
                rs.getString("declared_capabilities"), rs.getString("verified_capabilities"),
                (Integer) rs.getObject("context_window"), (Integer) rs.getObject("max_output_tokens"),
                (Integer) rs.getObject("embedding_dimension"), rs.getString("tokenizer"),
                rs.getString("rate_limit"), rs.getString("price_table_ref"), rs.getString("allowed_parameters"),
                ModelProfileStatus.valueOf(rs.getString("status")), instant(rs, "created_at"),
                instant(rs, "updated_at"), rs.getObject("correlation_id", UUID.class));
    }

    private ModelRouteVersion mapRoute(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ModelRouteVersion(rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getString("route_key"), rs.getInt("version_no"), RoutePurpose.valueOf(rs.getString("purpose")),
                EgressPolicy.valueOf(rs.getString("egress_policy")), rs.getBoolean("allow_cloud_egress"),
                SelectionPolicy.valueOf(rs.getString("selection_policy")), rs.getString("compatibility"),
                ModelRouteStatus.valueOf(rs.getString("status")), instant(rs, "created_at"),
                instant(rs, "updated_at"), rs.getObject("correlation_id", UUID.class));
    }

    private RouteCandidate mapCandidate(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RouteCandidate(rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getObject("model_route_version_id", UUID.class), rs.getInt("candidate_no"),
                rs.getObject("model_profile_version_id", UUID.class), instant(rs, "created_at"),
                instant(rs, "updated_at"), rs.getObject("correlation_id", UUID.class));
    }

    private SpaceModelBinding mapBinding(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SpaceModelBinding(rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getString("binding_key"), rs.getInt("version_no"), RoutePurpose.valueOf(rs.getString("purpose")),
                rs.getObject("model_route_version_id", UUID.class), BindingStatus.valueOf(rs.getString("status")),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getObject("correlation_id", UUID.class));
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static String jsonOrEmpty(String value) {
        return value == null || value.isBlank() ? "{}" : value;
    }

    private static String jsonOrEmptyArray(String value) {
        return value == null || value.isBlank() ? "[]" : value;
    }

    public enum ProviderType { OLLAMA, OPENAI_COMPATIBLE, AI_RUNTIME }

    public enum ProviderStatus { DRAFT, ACTIVE, DISABLED, UNHEALTHY }

    public enum EgressPolicy { LOCAL_ONLY, CLOUD_ALLOWED }

    public enum ModelProfileStatus { DRAFT, PUBLISHED, RETIRED }

    public enum ModelRouteStatus { DRAFT, PUBLISHED, RETIRED }

    public enum RoutePurpose { CHAT, EMBEDDING, RERANK }

    public enum SelectionPolicy { ORDERED_FAILOVER, SINGLE }

    public enum BindingStatus { ACTIVE, RETIRED }

    public record NewProviderConnection(UUID id, UUID spaceId, String providerKey, String displayName,
                                        ProviderType providerType, String endpointUri, String credentialRef,
                                        String credentialHash, String authScheme, String nonSecretHeadersJson,
                                        ProviderStatus status, EgressPolicy egressPolicy, Instant now,
                                        UUID correlationId) {
    }

    public record ProviderConnection(UUID id, UUID spaceId, String providerKey, String displayName,
                                     ProviderType providerType, String endpointUri, String credentialRef,
                                     String credentialHash, String authScheme, String nonSecretHeadersJson,
                                     ProviderStatus status, EgressPolicy egressPolicy, Instant createdAt,
                                     Instant updatedAt, UUID correlationId, long version) {
    }

    public record NewModelProfileVersion(UUID id, UUID spaceId, UUID providerConnectionId, String profileKey,
                                         int versionNo, String modelName, String capabilitiesJson,
                                         String declaredCapabilitiesJson, String verifiedCapabilitiesJson,
                                         Integer contextWindow, Integer maxOutputTokens, Integer embeddingDimension,
                                         String tokenizer, String rateLimitJson, String priceTableRef,
                                         String allowedParametersJson, ModelProfileStatus status, Instant now,
                                         UUID correlationId) {
    }

    public record ModelProfileVersion(UUID id, UUID spaceId, UUID providerConnectionId, String profileKey,
                                     int versionNo, String modelName, String capabilitiesJson,
                                     String declaredCapabilitiesJson, String verifiedCapabilitiesJson,
                                     Integer contextWindow, Integer maxOutputTokens, Integer embeddingDimension,
                                     String tokenizer, String rateLimitJson, String priceTableRef,
                                     String allowedParametersJson, ModelProfileStatus status, Instant createdAt,
                                     Instant updatedAt, UUID correlationId) {
    }

    public record NewModelRouteVersion(UUID id, UUID spaceId, String routeKey, int versionNo,
                                      RoutePurpose purpose, EgressPolicy egressPolicy, boolean allowCloudEgress,
                                      SelectionPolicy selectionPolicy, String compatibilityJson,
                                      ModelRouteStatus status, Instant now, UUID correlationId) {
    }

    public record ModelRouteVersion(UUID id, UUID spaceId, String routeKey, int versionNo, RoutePurpose purpose,
                                    EgressPolicy egressPolicy, boolean allowCloudEgress,
                                    SelectionPolicy selectionPolicy, String compatibilityJson,
                                    ModelRouteStatus status, Instant createdAt, Instant updatedAt,
                                    UUID correlationId) {
    }

    public record NewRouteCandidate(UUID id, UUID spaceId, UUID routeVersionId, int candidateNo,
                                   UUID profileVersionId, Instant now, UUID correlationId) {
    }

    public record RouteCandidate(UUID id, UUID spaceId, UUID routeVersionId, int candidateNo,
                                 UUID profileVersionId, Instant createdAt, Instant updatedAt,
                                 UUID correlationId) {
    }

    public record NewSpaceModelBinding(UUID id, UUID spaceId, String bindingKey, int versionNo,
                                      RoutePurpose purpose, UUID routeVersionId, BindingStatus status,
                                      Instant now, UUID correlationId) {
    }

    public record SpaceModelBinding(UUID id, UUID spaceId, String bindingKey, int versionNo,
                                    RoutePurpose purpose, UUID routeVersionId, BindingStatus status,
                                    Instant createdAt, Instant updatedAt, UUID correlationId) {
    }
}
