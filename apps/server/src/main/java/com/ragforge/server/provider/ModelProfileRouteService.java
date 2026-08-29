package com.ragforge.server.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.common.ApiException;
import com.ragforge.server.common.CorrelationIdFilter;
import com.ragforge.server.common.UuidV7;
import com.ragforge.server.identity.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Application service for space-isolated, append-only model profiles and routes. */
@Service
public class ModelProfileRouteService {
    private final ProviderRepository providers;
    private final SpaceAuthorization authorization;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ModelProfileRouteService(ProviderRepository providers, SpaceAuthorization authorization,
                                    JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.providers = providers;
        this.authorization = authorization;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ModelProfileView createProfile(UUID spaceId, ModelProfileRequest request,
                                          SessionPrincipal principal, HttpServletRequest servletRequest) {
        authorization.requireAdmin(spaceId, principal);
        ProviderRepository.RoutePurpose purpose = purpose(request.purpose());
        ProviderRepository.ModelProfileStatus status = profileStatus(request.status());
        if (providers.findConnectionInSpace(spaceId, request.providerConnectionId()).isEmpty()) {
            throw notFound("model_profile_not_found", "Provider connection not found in the requested space");
        }

        List<String> declaredCapabilities = normalizedCapabilities(request.capabilities());
        String verifiedCapabilities = "[]";
        if (status == ProviderRepository.ModelProfileStatus.PUBLISHED) {
            ProviderRepository.ProviderTestRun test = providers.findLatestTest(spaceId,
                            request.providerConnectionId(), request.modelName().trim(), purpose)
                    .orElseThrow(() -> invalid("status",
                            "A matching successful provider test is required before publishing"));
            if (test.outcome() != ProviderRepository.TestOutcome.SUCCEEDED) {
                throw invalid("status", "The latest matching provider test did not succeed");
            }
            List<String> verified = strings(test.verifiedCapabilitiesJson());
            if (!verified.containsAll(declaredCapabilities) || !verified.contains(purpose.name())) {
                throw invalid("capabilities", "Declared capabilities exceed the latest verified provider test");
            }
            if (purpose == ProviderRepository.RoutePurpose.EMBEDDING
                    && !java.util.Objects.equals(request.embeddingDimension(), test.embeddingDimension())) {
                throw invalid("embeddingDimension", "Embedding dimension must match the verified provider test");
            }
            verifiedCapabilities = test.verifiedCapabilitiesJson();
        }

        UUID id = UuidV7.random();
        Instant now = Instant.now();
        String profileKey = "profile-" + id;
        ProviderRepository.ModelProfileVersion profile = providers.createProfileVersion(
                new ProviderRepository.NewModelProfileVersion(
                        id, spaceId, request.providerConnectionId(), profileKey, 1, request.modelName().trim(),
                        json(declaredCapabilities), json(Map.of(
                        "purpose", purpose.name(), "usageReporting", usageReporting(request.usageReporting()).name())),
                        verifiedCapabilities, request.contextWindow(), request.maxOutputTokens(), request.embeddingDimension(), null,
                        "{}", null, "{}", status, now, correlationId(servletRequest)));
        return toProfileView(profile);
    }

    public ModelProfilePage listProfiles(UUID spaceId, int limit, SessionPrincipal principal) {
        authorization.requireMember(spaceId, principal);
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        List<UUID> ids = jdbc.query("""
                SELECT id FROM model_profile_versions
                WHERE space_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), spaceId, boundedLimit);
        List<ModelProfileView> items = ids.stream()
                .map(id -> providers.findProfileVersion(spaceId, id).orElseThrow())
                .map(this::toProfileView)
                .toList();
        return new ModelProfilePage(items, null);
    }

    @Transactional
    public ModelRouteView createRoute(UUID spaceId, ModelRouteRequest request,
                                      SessionPrincipal principal, HttpServletRequest servletRequest) {
        authorization.requireAdmin(spaceId, principal);
        ProviderRepository.RoutePurpose purpose = purpose(request.purpose());
        ProviderRepository.EgressPolicy egressPolicy = egressPolicy(request.egressClass());
        ProviderRepository.SelectionPolicy selectionPolicy = selectionPolicy(request.failoverPolicy());
        ProviderRepository.ModelRouteStatus status = routeStatus(request.status());
        Set<Integer> priorities = new HashSet<>();
        List<CandidateInput> candidates = request.candidates().stream().map(candidate -> {
            if (!priorities.add(candidate.priority())) {
                throw invalid("candidates", "Candidate priorities must be unique");
            }
            CandidateInput input = candidateInput(spaceId, purpose, egressPolicy, status, candidate);
            return input;
        }).toList();

        UUID id = UuidV7.random();
        Instant now = Instant.now();
        ProviderRepository.ModelRouteVersion route = providers.createRouteVersion(
                new ProviderRepository.NewModelRouteVersion(
                        id, spaceId, "route-" + id, 1, purpose, egressPolicy,
                        egressPolicy == ProviderRepository.EgressPolicy.CLOUD_ALLOWED,
                        selectionPolicy, json(Map.of("failoverPolicy", request.failoverPolicy().toUpperCase(Locale.ROOT))),
                        status, now, correlationId(servletRequest)));
        for (CandidateInput candidate : candidates) {
            providers.addRouteCandidate(new ProviderRepository.NewRouteCandidate(
                    UuidV7.random(), spaceId, route.id(), candidate.priority(), candidate.profileId(),
                    now, correlationId(servletRequest)));
        }
        return toRouteView(route);
    }

    public ModelRoutePage listRoutes(UUID spaceId, int limit, SessionPrincipal principal) {
        authorization.requireMember(spaceId, principal);
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        List<UUID> ids = jdbc.query("""
                SELECT id FROM model_route_versions
                WHERE space_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), spaceId, boundedLimit);
        List<ModelRouteView> items = ids.stream()
                .map(id -> providers.findRouteVersion(spaceId, id).orElseThrow())
                .map(this::toRouteView)
                .toList();
        return new ModelRoutePage(items, null);
    }

    private CandidateInput candidateInput(UUID spaceId, ProviderRepository.RoutePurpose purpose,
                                          ProviderRepository.EgressPolicy routeEgress,
                                          ProviderRepository.ModelRouteStatus routeStatus,
                                          RouteCandidateRequest request) {
        String requestedEgress = normalizeEgressClass(request.egressClass());
        try {
            CandidateInfo info = jdbc.queryForObject("""
                    SELECT p.id, p.status, p.declared_capabilities, c.egress_policy
                    FROM model_profile_versions p
                    JOIN provider_connections c
                      ON c.id = p.provider_connection_id AND c.space_id = p.space_id
                    WHERE p.id = ? AND p.space_id = ?
                    """, (rs, rowNum) -> new CandidateInfo(
                    rs.getObject("id", UUID.class), rs.getString("status"),
                    rs.getString("declared_capabilities"), rs.getString("egress_policy")),
                    request.modelProfileId(), spaceId);
            String actualEgress = "CLOUD_ALLOWED".equals(info.egressPolicy()) ? "CLOUD" : "LOCAL";
            if (!actualEgress.equals(requestedEgress) || !actualEgress.equals(toEgressClass(routeEgress))) {
                throw invalid("candidates", "Candidate egress class does not match the route egress policy");
            }
            if (routeStatus == ProviderRepository.ModelRouteStatus.PUBLISHED
                    && !"PUBLISHED".equals(info.status())) {
                throw invalid("candidates", "An active route requires published model profiles");
            }
            JsonNode metadata = readJson(info.declaredCapabilities());
            if (metadata.hasNonNull("purpose") && !purpose.name().equals(metadata.get("purpose").asText())) {
                throw invalid("candidates", "Candidate purpose does not match the route purpose");
            }
            return new CandidateInput(info.id(), request.priority());
        } catch (EmptyResultDataAccessException exception) {
            throw notFound("model_profile_not_found", "Model profile not found in the requested space");
        }
    }

    private ModelProfileView toProfileView(ProviderRepository.ModelProfileVersion profile) {
        JsonNode metadata = readJson(profile.declaredCapabilitiesJson());
        String purpose = text(metadata, "purpose", "CHAT");
        String usageReporting = text(metadata, "usageReporting", "LOCAL_ESTIMATE");
        return new ModelProfileView(profile.id(), profile.spaceId(), profile.versionNo(),
                profile.providerConnectionId(), purpose, profile.modelName(), strings(profile.capabilitiesJson()),
                strings(profile.verifiedCapabilitiesJson()),
                profile.contextWindow(), profile.maxOutputTokens(), profile.embeddingDimension(), usageReporting,
                profile.status() == ProviderRepository.ModelProfileStatus.RETIRED ? "DISABLED" : profile.status().name(),
                profile.createdAt(), profile.updatedAt());
    }

    private ModelRouteView toRouteView(ProviderRepository.ModelRouteVersion route) {
        List<RouteCandidateView> candidates = providers.listRouteCandidates(route.spaceId(), route.id()).stream()
                .map(candidate -> new RouteCandidateView(candidate.profileVersionId(), candidate.candidateNo(),
                        candidateEgress(route.spaceId(), candidate.profileVersionId())))
                .toList();
        return new ModelRouteView(route.id(), route.spaceId(), route.versionNo(), route.purpose().name(),
                toEgressClass(route.egressPolicy()),
                route.selectionPolicy() == ProviderRepository.SelectionPolicy.SINGLE ? "NONE" : "SAME_EGRESS_ONLY",
                candidates,
                route.status() == ProviderRepository.ModelRouteStatus.PUBLISHED ? "ACTIVE"
                        : route.status() == ProviderRepository.ModelRouteStatus.RETIRED ? "DISABLED" : "DRAFT",
                route.createdAt(), route.updatedAt());
    }

    private String candidateEgress(UUID spaceId, UUID profileId) {
        String policy = jdbc.queryForObject("""
                SELECT c.egress_policy
                FROM model_profile_versions p
                JOIN provider_connections c
                  ON c.id = p.provider_connection_id AND c.space_id = p.space_id
                WHERE p.id = ? AND p.space_id = ?
                """, String.class, profileId, spaceId);
        return "CLOUD_ALLOWED".equals(policy) ? "CLOUD" : "LOCAL";
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value == null || value.isBlank() ? "{}" : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored model metadata is not valid JSON", exception);
        }
    }

    private List<String> strings(String value) {
        try {
            return objectMapper.readValue(value == null || value.isBlank() ? "[]" : value,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored capabilities are not valid JSON", exception);
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        return node.hasNonNull(field) ? node.get(field).asText() : fallback;
    }

    private static ProviderRepository.RoutePurpose purpose(String value) {
        try {
            return ProviderRepository.RoutePurpose.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw invalid("purpose", "Purpose must be CHAT, EMBEDDING, or RERANK");
        }
    }

    private static ProviderRepository.EgressPolicy egressPolicy(String value) {
        return "CLOUD".equals(normalizeEgressClass(value))
                ? ProviderRepository.EgressPolicy.CLOUD_ALLOWED : ProviderRepository.EgressPolicy.LOCAL_ONLY;
    }

    private static String normalizeEgressClass(String value) {
        if (value == null) {
            throw invalid("egressClass", "Egress class is required");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("LOCAL") && !normalized.equals("CLOUD")) {
            throw invalid("egressClass", "Egress class must be LOCAL or CLOUD");
        }
        return normalized;
    }

    private static String toEgressClass(ProviderRepository.EgressPolicy policy) {
        return policy == ProviderRepository.EgressPolicy.CLOUD_ALLOWED ? "CLOUD" : "LOCAL";
    }

    private static ProviderRepository.SelectionPolicy selectionPolicy(String value) {
        try {
            return "NONE".equals(value.trim().toUpperCase(Locale.ROOT))
                    ? ProviderRepository.SelectionPolicy.SINGLE : ProviderRepository.SelectionPolicy.ORDERED_FAILOVER;
        } catch (RuntimeException exception) {
            throw invalid("failoverPolicy", "Failover policy must be NONE or SAME_EGRESS_ONLY");
        }
    }

    private static ProviderRepository.ModelProfileStatus profileStatus(String value) {
        try {
            return switch (value.trim().toUpperCase(Locale.ROOT)) {
                case "DRAFT" -> ProviderRepository.ModelProfileStatus.DRAFT;
                case "PUBLISHED" -> ProviderRepository.ModelProfileStatus.PUBLISHED;
                case "DISABLED" -> ProviderRepository.ModelProfileStatus.RETIRED;
                default -> throw new IllegalArgumentException();
            };
        } catch (RuntimeException exception) {
            throw invalid("status", "Profile status must be DRAFT, PUBLISHED, or DISABLED");
        }
    }

    private static ProviderRepository.ModelRouteStatus routeStatus(String value) {
        try {
            return switch (value.trim().toUpperCase(Locale.ROOT)) {
                case "DRAFT" -> ProviderRepository.ModelRouteStatus.DRAFT;
                case "ACTIVE" -> ProviderRepository.ModelRouteStatus.PUBLISHED;
                case "DISABLED" -> ProviderRepository.ModelRouteStatus.RETIRED;
                default -> throw new IllegalArgumentException();
            };
        } catch (RuntimeException exception) {
            throw invalid("status", "Route status must be DRAFT, ACTIVE, or DISABLED");
        }
    }

    private static UsageReporting usageReporting(String value) {
        try {
            return UsageReporting.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw invalid("usageReporting", "Usage reporting must be PROVIDER_REPORTED or LOCAL_ESTIMATE");
        }
    }

    private static List<String> normalizedCapabilities(List<String> capabilities) {
        Set<String> allowed = Set.of("CHAT", "EMBEDDING", "RERANK", "STREAMING", "TOOLS", "JSON_SCHEMA",
                "VISION", "USAGE_REPORTING", "CUSTOM_HEADERS");
        List<String> normalized = capabilities.stream()
                .map(value -> value.trim().toUpperCase(Locale.ROOT)).distinct().toList();
        if (normalized.stream().anyMatch(value -> !allowed.contains(value))) {
            throw invalid("capabilities", "Capabilities contain an unsupported value");
        }
        return normalized;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw invalid("request", "Request JSON cannot be serialized");
        }
    }

    private static UUID correlationId(HttpServletRequest request) {
        return UUID.fromString(CorrelationIdFilter.current(request));
    }

    private static ApiException invalid(String field, String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "Validation failed",
                field + ": " + detail);
    }

    private static ApiException notFound(String code, String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, code, "Resource not found", detail);
    }

    private record CandidateInfo(UUID id, String status, String declaredCapabilities, String egressPolicy) {
    }

    private record CandidateInput(UUID profileId, int priority) {
    }

    public enum UsageReporting { PROVIDER_REPORTED, LOCAL_ESTIMATE }

    public record ModelProfileRequest(
            @NotNull UUID providerConnectionId,
            @NotBlank @Pattern(regexp = "CHAT|EMBEDDING|RERANK") String purpose,
            @NotBlank @Size(max = 200) String modelName,
            @NotEmpty @Size(max = 20) List<@NotBlank String> capabilities,
            @NotNull @Min(1) @Max(2_000_000) Integer contextWindow,
            @NotNull @Min(1) @Max(200_000) Integer maxOutputTokens,
            @Min(1) @Max(4096) Integer embeddingDimension,
            @NotBlank @Pattern(regexp = "PROVIDER_REPORTED|LOCAL_ESTIMATE") String usageReporting,
            @NotBlank @Pattern(regexp = "DRAFT|PUBLISHED|DISABLED") String status) {
    }

    public record ModelProfileView(UUID modelProfileId, UUID spaceId, int version, UUID providerConnectionId,
                                   String purpose, String modelName, List<String> capabilities,
                                   List<String> verifiedCapabilities, Integer contextWindow,
                                   Integer maxOutputTokens, Integer embeddingDimension, String usageReporting, String status,
                                   Instant createdAt, Instant updatedAt) {
    }

    public record ModelProfilePage(List<ModelProfileView> items, String nextCursor) {
    }

    public record RouteCandidateRequest(
            @NotNull UUID modelProfileId,
            @Min(1) @Max(100) int priority,
            @NotBlank @Pattern(regexp = "LOCAL|CLOUD") String egressClass) {
    }

    public record ModelRouteRequest(
            @NotBlank @Pattern(regexp = "CHAT|EMBEDDING|RERANK") String purpose,
            @NotBlank @Pattern(regexp = "LOCAL|CLOUD") String egressClass,
            @NotBlank @Pattern(regexp = "NONE|SAME_EGRESS_ONLY") String failoverPolicy,
            @NotEmpty @Size(max = 10) List<@Valid RouteCandidateRequest> candidates,
            @NotBlank @Pattern(regexp = "DRAFT|ACTIVE|DISABLED") String status) {
    }

    public record RouteCandidateView(UUID modelProfileId, int priority, String egressClass) {
    }

    public record ModelRouteView(UUID modelRouteId, UUID spaceId, int version, String purpose,
                                 String egressClass, String failoverPolicy,
                                 List<RouteCandidateView> candidates, String status,
                                 Instant createdAt, Instant updatedAt) {
    }

    public record ModelRoutePage(List<ModelRouteView> items, String nextCursor) {
    }
}
