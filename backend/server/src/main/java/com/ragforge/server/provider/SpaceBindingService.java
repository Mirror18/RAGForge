package com.ragforge.server.provider;

import com.ragforge.server.common.ApiException;
import com.ragforge.server.common.CorrelationIdFilter;
import com.ragforge.server.common.UuidV7;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.prompt.PromptRepository;
import com.ragforge.server.space.SpaceRepository;
import com.ragforge.server.space.SpaceRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Application service for immutable, space-scoped provider and prompt bindings. */
@Service
public class SpaceBindingService {
    private static final String BINDING_KEY_PREFIX = "space-binding-";

    private final SpaceBindingRepository bindings;
    private final ProviderRepository providers;
    private final PromptRepository prompts;
    private final SpaceAuthorization authorization;
    private final SpaceRepository spaces;
    private final JdbcTemplate jdbc;

    public SpaceBindingService(SpaceBindingRepository bindings, ProviderRepository providers,
                               PromptRepository prompts, SpaceAuthorization authorization,
                               SpaceRepository spaces, JdbcTemplate jdbc) {
        this.bindings = bindings;
        this.providers = providers;
        this.prompts = prompts;
        this.authorization = authorization;
        this.spaces = spaces;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public SpaceBindingView get(UUID spaceId, SessionPrincipal principal) {
        authorization.requireMember(spaceId, principal);
        return bindings.findCurrent(spaceId)
                .map(SpaceBindingService::toView)
                .orElseThrow(() -> notFound("Space binding not found"));
    }

    @Transactional
    public SpaceBindingView update(UUID spaceId, SpaceBindingUpdateRequest request,
                                   String ifMatch, SessionPrincipal principal,
                                   HttpServletRequest servletRequest) {
        authorization.requireWrite(spaceId, principal);
        int ifMatchVersion = parseIfMatch(ifMatch);
        if (!bindings.lockSpace(spaceId)) {
            throw notFound("Space not found");
        }

        SpaceBindingRepository.SpaceBindingRecord current = bindings.findCurrent(spaceId).orElse(null);
        assertExpectedVersion(current, request.version(), ifMatchVersion);

        Instant now = Instant.now();
        SpaceBindingRepository.CloudAuthorization cloudAuthorization = request.cloudEgressEnabled()
                ? validateCloudAuthorization(spaceId, request.cloudEgressAuthorization(), now)
                : null;
        Map<UUID, RouteInfo> routes = validateRoutes(spaceId, request, cloudAuthorization);
        validatePromptVersion(spaceId, request.promptVersionId());

        int nextVersion = current == null ? 1 : current.version() + 1;
        UUID correlationId = correlationId(servletRequest);
        ProviderRepository.SpaceModelBinding chatBinding = providers.bindRoute(
                new ProviderRepository.NewSpaceModelBinding(
                        UuidV7.random(), spaceId, BINDING_KEY_PREFIX + "chat", nextVersion,
                        ProviderRepository.RoutePurpose.CHAT, request.chatRouteId(),
                        ProviderRepository.BindingStatus.ACTIVE, now, correlationId));
        ProviderRepository.SpaceModelBinding embeddingBinding = providers.bindRoute(
                new ProviderRepository.NewSpaceModelBinding(
                        UuidV7.random(), spaceId, BINDING_KEY_PREFIX + "embedding", nextVersion,
                        ProviderRepository.RoutePurpose.EMBEDDING, request.embeddingRouteId(),
                        ProviderRepository.BindingStatus.ACTIVE, now, correlationId));
        ProviderRepository.SpaceModelBinding rerankBinding = providers.bindRoute(
                new ProviderRepository.NewSpaceModelBinding(
                        UuidV7.random(), spaceId, BINDING_KEY_PREFIX + "rerank", nextVersion,
                        ProviderRepository.RoutePurpose.RERANK, request.rerankRouteId(),
                        ProviderRepository.BindingStatus.ACTIVE, now, correlationId));
        PromptRepository.SpacePromptBinding promptBinding = prompts.bind(
                new PromptRepository.NewSpacePromptBinding(
                        UuidV7.random(), spaceId, BINDING_KEY_PREFIX + "chat", nextVersion,
                        request.promptVersionId(), PromptRepository.BindingStatus.ACTIVE, now, correlationId));

        return toView(bindings.create(new SpaceBindingRepository.NewSpaceBinding(
                UuidV7.random(), spaceId, nextVersion, chatBinding.id(), embeddingBinding.id(), rerankBinding.id(),
                promptBinding.id(), request.cloudEgressEnabled(), cloudAuthorization, now, correlationId)));
    }

    private SpaceBindingRepository.CloudAuthorization validateCloudAuthorization(
            UUID spaceId, CloudEgressAuthorizationRequest request, Instant now) {
        if (request == null) {
            throw cloudAuthorizationInvalid("An explicit cloud authorization is required");
        }
        if (request.approvedAt().isAfter(now)) {
            throw cloudAuthorizationInvalid("Cloud authorization cannot be approved in the future");
        }
        if (!request.expiresAt().isAfter(now) || !request.expiresAt().isAfter(request.approvedAt())) {
            throw cloudAuthorizationInvalid("Cloud authorization is expired");
        }
        String scope = request.scope().trim().toUpperCase(Locale.ROOT);
        if (!scopeCoversChat(scope)) {
            throw cloudAuthorizationInvalid("Cloud authorization scope must cover CHAT");
        }
        SpaceRole approverRole = spaces.findRole(spaceId, request.approvedBy()).orElseThrow(() ->
                cloudAuthorizationInvalid("Cloud authorization approver is not a member of this space"));
        if (approverRole != SpaceRole.SPACE_ADMIN) {
            throw cloudAuthorizationInvalid("Cloud authorization approver must be a space administrator");
        }
        return new SpaceBindingRepository.CloudAuthorization(request.approvalId(), request.approvedBy(),
                request.approvedAt(), request.expiresAt(), scope);
    }

    private Map<UUID, RouteInfo> validateRoutes(UUID spaceId, SpaceBindingUpdateRequest request,
                                                 SpaceBindingRepository.CloudAuthorization cloudAuthorization) {
        Set<UUID> requestedIds = Set.of(request.chatRouteId(), request.embeddingRouteId(), request.rerankRouteId());
        if (requestedIds.size() != 3) {
            throw invalid("routeIds", "Each binding purpose must use a distinct model route");
        }
        List<RouteInfo> routeList = jdbc.query("""
                SELECT id, purpose, egress_policy, allow_cloud_egress, status
                FROM model_route_versions
                WHERE space_id = ? AND id IN (?, ?, ?)
                """, (rs, rowNum) -> new RouteInfo(rs.getObject("id", UUID.class),
                ProviderRepository.RoutePurpose.valueOf(rs.getString("purpose")),
                ProviderRepository.EgressPolicy.valueOf(rs.getString("egress_policy")),
                rs.getBoolean("allow_cloud_egress"), rs.getString("status")), spaceId,
                request.chatRouteId(), request.embeddingRouteId(), request.rerankRouteId());
        if (routeList.size() != 3) {
            throw notFound("A model route was not found in the requested space");
        }
        Map<UUID, RouteInfo> routes = new HashMap<>();
        routeList.forEach(route -> routes.put(route.id(), route));
        assertRoute(routes, request.chatRouteId(), ProviderRepository.RoutePurpose.CHAT);
        assertRoute(routes, request.embeddingRouteId(), ProviderRepository.RoutePurpose.EMBEDDING);
        assertRoute(routes, request.rerankRouteId(), ProviderRepository.RoutePurpose.RERANK);

        for (RouteInfo route : routeList) {
            boolean cloudRoute = route.egressPolicy() == ProviderRepository.EgressPolicy.CLOUD_ALLOWED;
            if (cloudRoute && !request.cloudEgressEnabled()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "cloud_egress_disabled",
                        "Cloud egress is disabled", "A cloud route cannot be bound while cloud egress is disabled");
            }
            if (cloudRoute && (cloudAuthorization == null
                    || !scopeCovers(cloudAuthorization.scope(), route.purpose()))) {
                throw cloudAuthorizationInvalid("Cloud authorization scope does not cover the selected route");
            }
        }
        return routes;
    }

    private void assertRoute(Map<UUID, RouteInfo> routes, UUID routeId, ProviderRepository.RoutePurpose purpose) {
        RouteInfo route = routes.get(routeId);
        if (route.purpose() != purpose) {
            throw invalid("routeIds", "Model route purpose does not match the binding purpose");
        }
        if (!route.allowCloudEgress() && route.egressPolicy() == ProviderRepository.EgressPolicy.CLOUD_ALLOWED) {
            throw invalid("routeIds", "Cloud route metadata is inconsistent");
        }
        if (!"PUBLISHED".equals(route.status())) {
            throw invalid("routeIds", "Only published model routes can be bound");
        }
    }

    private void validatePromptVersion(UUID spaceId, UUID promptVersionId) {
        try {
            String status = jdbc.queryForObject("""
                    SELECT status FROM prompt_versions
                    WHERE id = ? AND space_id = ?
                    """, String.class, promptVersionId, spaceId);
            if (!"PUBLISHED".equals(status)) {
                throw invalid("promptVersionId", "Only a published prompt version can be bound");
            }
        } catch (org.springframework.dao.EmptyResultDataAccessException exception) {
            throw notFound("Prompt version was not found in the requested space");
        }
    }

    private static void assertExpectedVersion(SpaceBindingRepository.SpaceBindingRecord current,
                                               int requestVersion, int ifMatchVersion) {
        if (current == null) {
            if (requestVersion != 1 || (ifMatchVersion != 0 && ifMatchVersion != 1)) {
                throw versionMismatch("The initial space binding version must use version 1");
            }
            return;
        }
        if (requestVersion != current.version() || ifMatchVersion != current.version()) {
            throw versionMismatch("Space binding version changed; refresh before updating");
        }
    }

    private static int parseIfMatch(String value) {
        if (value == null || value.isBlank()) {
            throw versionMismatch("If-Match is required for a versioned update");
        }
        String normalized = value.trim();
        if (normalized.startsWith("W/")) {
            normalized = normalized.substring(2).trim();
        }
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        try {
            int version = Integer.parseInt(normalized);
            if (version < 0) {
                throw new NumberFormatException();
            }
            return version;
        } catch (NumberFormatException exception) {
            throw versionMismatch("If-Match must contain the current binding version");
        }
    }

    private static boolean scopeCoversChat(String scope) {
        return "CHAT".equals(scope) || "ALL".equals(scope);
    }

    private static boolean scopeCovers(String scope, ProviderRepository.RoutePurpose purpose) {
        return "ALL".equals(scope) || purpose.name().equals(scope);
    }

    private static SpaceBindingView toView(SpaceBindingRepository.SpaceBindingRecord binding) {
        SpaceBindingRepository.CloudAuthorization authorization = binding.authorization();
        CloudEgressAuthorizationView authorizationView = authorization == null ? null
                : new CloudEgressAuthorizationView(authorization.approvalId(), authorization.approvedBy(),
                authorization.approvedAt(), authorization.expiresAt(), authorization.scope());
        return new SpaceBindingView(binding.id(), binding.spaceId(), binding.version(), binding.chatRouteId(),
                binding.embeddingRouteId(), binding.rerankRouteId(), binding.promptVersionId(),
                binding.cloudEgressEnabled(), authorizationView, binding.createdAt(), binding.updatedAt());
    }

    private static UUID correlationId(HttpServletRequest request) {
        return UUID.fromString(CorrelationIdFilter.current(request));
    }

    private static ApiException invalid(String field, String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "Validation failed",
                field + ": " + detail);
    }

    private static ApiException cloudAuthorizationInvalid(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "cloud_egress_authorization_invalid",
                "Cloud authorization invalid", detail);
    }

    private static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, "space_binding_not_found", "Space binding not found", detail);
    }

    private static ApiException versionMismatch(String detail) {
        return new ApiException(HttpStatus.PRECONDITION_FAILED, "space_binding_version_mismatch",
                "Precondition failed", detail);
    }

    private record RouteInfo(UUID id, ProviderRepository.RoutePurpose purpose,
                             ProviderRepository.EgressPolicy egressPolicy, boolean allowCloudEgress,
                             String status) {
    }

    public record SpaceBindingUpdateRequest(
            @Min(1) int version,
            @NotNull UUID chatRouteId,
            @NotNull UUID embeddingRouteId,
            @NotNull UUID rerankRouteId,
            @NotNull UUID promptVersionId,
            boolean cloudEgressEnabled,
            @Valid CloudEgressAuthorizationRequest cloudEgressAuthorization) {
    }

    public record CloudEgressAuthorizationRequest(
            @NotNull UUID approvalId,
            @NotNull UUID approvedBy,
            @NotNull Instant approvedAt,
            @NotNull Instant expiresAt,
            @NotNull @Pattern(regexp = "CHAT|EMBEDDING|RERANK|ALL") String scope) {
    }

    public record SpaceBindingView(UUID spaceBindingId, UUID spaceId, int version, UUID chatRouteId,
                                   UUID embeddingRouteId, UUID rerankRouteId, UUID promptVersionId,
                                   boolean cloudEgressEnabled,
                                   CloudEgressAuthorizationView cloudEgressAuthorization,
                                   Instant createdAt, Instant updatedAt) {
    }

    public record CloudEgressAuthorizationView(UUID approvalId, UUID approvedBy, Instant approvedAt,
                                               Instant expiresAt, String scope) {
    }
}
