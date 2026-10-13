package com.ragforge.server.answer.infrastructure;

import com.ragforge.server.space.infrastructure.EgressDecision;
import com.ragforge.server.space.infrastructure.ProviderConnection;
import com.ragforge.server.space.infrastructure.ProviderType;

import java.util.UUID;

/** Resolves one exact, already-authorized model route; it never selects a fallback candidate. */
@FunctionalInterface
public interface ProviderRouteResolver {
    ResolvedRoute resolve(UUID spaceId, UUID routeVersionId, UUID profileVersionId,
                          String model, EgressDecision egressDecision, UUID correlationId);

    default ResolvedRoute resolveEmbedding(UUID spaceId, EgressDecision egressDecision, UUID correlationId) {
        throw new IllegalStateException("Embedding route is not configured");
    }

    record ResolvedRoute(UUID spaceId, UUID routeVersionId, UUID profileVersionId, String model,
                         ProviderConnection connection, ProviderType providerType,
                         EgressDecision egressDecision) {
        public ResolvedRoute {
            if (spaceId == null || routeVersionId == null || profileVersionId == null
                    || model == null || model.isBlank() || connection == null
                    || providerType == null || egressDecision == null
                    || !spaceId.equals(connection.spaceId())) {
                throw new IllegalArgumentException("Resolved provider route is incomplete or cross-space");
            }
        }
    }
}
