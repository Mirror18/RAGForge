package com.ragforge.server.answer.integration;

import com.ragforge.server.provider.ProviderRepository;
import com.ragforge.server.provider.SpaceBindingRepository;
import com.ragforge.server.provider.adapter.EgressClass;
import com.ragforge.server.provider.adapter.EgressDecision;
import com.ragforge.server.provider.adapter.EgressPolicy;
import com.ragforge.server.provider.adapter.ProviderConnection;
import com.ragforge.server.provider.adapter.ProviderType;
import com.ragforge.server.provider.adapter.ProviderAdapterException;
import com.ragforge.server.provider.adapter.ProviderErrorClass;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** PostgreSQL-backed, exact route/profile/binding validation for answer generation. */
public final class RepositoryProviderRouteResolver implements ProviderRouteResolver {
    private final ProviderRepository providers;
    private final SpaceBindingRepository bindings;
    private final Phase5IntegrationObserver observer;

    public RepositoryProviderRouteResolver(ProviderRepository providers, SpaceBindingRepository bindings) {
        this(providers, bindings, Phase5IntegrationObserver.noop());
    }

    public RepositoryProviderRouteResolver(ProviderRepository providers, SpaceBindingRepository bindings,
                                           Phase5IntegrationObserver observer) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.observer = observer == null ? Phase5IntegrationObserver.noop() : observer;
    }

    @Override
    public ResolvedRoute resolve(UUID spaceId, UUID routeVersionId, UUID profileVersionId,
                                 String model, EgressDecision decision, UUID correlationId) {
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(routeVersionId, "routeVersionId");
        Objects.requireNonNull(profileVersionId, "profileVersionId");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(correlationId, "correlationId");
        ProviderRepository.ModelRouteVersion route = providers.findRouteVersion(spaceId, routeVersionId)
                .filter(item -> item.status() == ProviderRepository.ModelRouteStatus.PUBLISHED
                        && item.purpose() == ProviderRepository.RoutePurpose.CHAT)
                .orElseThrow(() -> denied(spaceId, correlationId, decision, "ROUTE_NOT_PUBLISHED"));
        ProviderRepository.ModelProfileVersion profile = providers.findProfileVersion(spaceId, profileVersionId)
                .filter(item -> item.status() == ProviderRepository.ModelProfileStatus.PUBLISHED
                        && item.modelName().equals(model))
                .orElseThrow(() -> denied(spaceId, correlationId, decision, "PROFILE_NOT_PUBLISHED_OR_MODEL_MISMATCH"));
        if (!providers.listRouteCandidates(spaceId, route.id()).stream()
                .anyMatch(candidate -> candidate.profileVersionId().equals(profile.id()))) {
            throw denied(spaceId, correlationId, decision, "PROFILE_NOT_A_ROUTE_CANDIDATE");
        }
        ProviderRepository.ProviderConnection connection = providers.findConnectionInSpace(spaceId,
                        profile.providerConnectionId())
                .filter(item -> item.status() == ProviderRepository.ProviderStatus.ACTIVE)
                .orElseThrow(() -> denied(spaceId, correlationId, decision, "PROVIDER_NOT_ACTIVE_IN_SPACE"));
        SpaceBindingRepository.SpaceBindingRecord binding = bindings.findCurrent(spaceId)
                .filter(item -> spaceId.equals(item.spaceId()) && route.id().equals(item.chatRouteId()))
                .orElseThrow(() -> denied(spaceId, correlationId, decision, "CHAT_ROUTE_NOT_BOUND"));
        validateEgress(spaceId, correlationId, decision, route, connection, binding);

        ProviderType providerType = ProviderType.valueOf(connection.providerType().name());
        EgressClass egressClass = connection.egressPolicy() == ProviderRepository.EgressPolicy.CLOUD_ALLOWED
                ? EgressClass.CLOUD : EgressClass.LOCAL;
        ProviderConnection adapterConnection;
        try {
            adapterConnection = new ProviderConnection(spaceId, connection.id(), Math.max(1, connection.version()),
                    providerType, egressClass, URI.create(connection.endpointUri()),
                    connection.credentialRef() == null ? "missing-ref" : connection.credentialRef(),
                    connection.authScheme());
            EgressPolicy.validateConnection(spaceId, decision, adapterConnection);
        } catch (RuntimeException failure) {
            throw denied(spaceId, correlationId, decision, "PROVIDER_CONNECTION_INVALID");
        }
        observer.record(new Phase5IntegrationObserver.Decision(spaceId, correlationId, correlationId,
                "provider-route", "AUTHORIZED", "EXACT_ROUTE", decision));
        return new ResolvedRoute(spaceId, route.id(), profile.id(), profile.modelName(), adapterConnection,
                providerType, decision);
    }

    private void validateEgress(UUID spaceId, UUID correlationId, EgressDecision decision,
                                ProviderRepository.ModelRouteVersion route,
                                ProviderRepository.ProviderConnection connection,
                                SpaceBindingRepository.SpaceBindingRecord binding) {
        if (decision == EgressDecision.LOCAL_ONLY) {
            if (route.egressPolicy() != ProviderRepository.EgressPolicy.LOCAL_ONLY
                    || connection.egressPolicy() != ProviderRepository.EgressPolicy.LOCAL_ONLY) {
                throw denied(spaceId, correlationId, decision, "LOCAL_ONLY_REJECTED_CLOUD_ROUTE");
            }
            return;
        }
        if (route.egressPolicy() != ProviderRepository.EgressPolicy.CLOUD_ALLOWED
                || !route.allowCloudEgress()
                || connection.egressPolicy() != ProviderRepository.EgressPolicy.CLOUD_ALLOWED
                || !binding.cloudEgressEnabled()
                || !validCloudAuthorization(binding.authorization())) {
            throw denied(spaceId, correlationId, decision, "CLOUD_EGRESS_NOT_AUTHORIZED");
        }
    }

    private static boolean validCloudAuthorization(SpaceBindingRepository.CloudAuthorization authorization) {
        if (authorization == null || authorization.approvedAt() == null || authorization.expiresAt() == null
                || !authorization.approvedAt().isBefore(authorization.expiresAt())
                || !authorization.expiresAt().isAfter(Instant.now())) {
            return false;
        }
        String scope = authorization.scope() == null ? "" : authorization.scope().trim().toUpperCase(java.util.Locale.ROOT);
        return "CHAT".equals(scope) || "ALL".equals(scope);
    }

    private ProviderAdapterException denied(UUID spaceId, UUID correlationId,
                                            EgressDecision decision, String reason) {
        observer.record(new Phase5IntegrationObserver.Decision(spaceId, correlationId, correlationId,
                "provider-route", "DENIED", reason, decision));
        return new ProviderAdapterException(ProviderErrorClass.SPACE_EGRESS_DENIED,
                "Provider route is not authorized for this space and egress decision", correlationId, 0, false);
    }
}
