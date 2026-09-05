package com.ragforge.server.provider.adapter;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Pure, side-effect-free enforcement for space isolation and explicit egress decisions. */
public final class EgressPolicy {
    private EgressPolicy() {
    }

    public static ProviderConnection validateConnection(UUID requestedSpaceId,
                                                         EgressDecision decision,
                                                         ProviderConnection connection) {
        Objects.requireNonNull(requestedSpaceId, "requestedSpaceId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(connection, "connection");
        if (!requestedSpaceId.equals(connection.spaceId())) {
            throw new ProviderAdapterException(ProviderErrorClass.SPACE_EGRESS_DENIED,
                    "Provider connection is outside the requested space");
        }
        if (decision == EgressDecision.LOCAL_ONLY && connection.egressClass() == EgressClass.CLOUD) {
            throw new ProviderAdapterException(ProviderErrorClass.SPACE_EGRESS_DENIED,
                    "Cloud provider egress is not authorized for this request");
        }
        return connection;
    }

    public static List<RouteCandidate> validateCandidates(UUID requestedSpaceId,
                                                           EgressDecision decision,
                                                           List<RouteCandidate> candidates) {
        Objects.requireNonNull(requestedSpaceId, "requestedSpaceId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty()) {
            throw new ProviderAdapterException(ProviderErrorClass.SPACE_EGRESS_DENIED,
                    "Provider route has no candidates");
        }
        for (RouteCandidate candidate : candidates) {
            if (candidate == null || !requestedSpaceId.equals(candidate.spaceId())) {
                throw new ProviderAdapterException(ProviderErrorClass.SPACE_EGRESS_DENIED,
                        "Provider route contains a candidate from another space");
            }
            if (decision == EgressDecision.LOCAL_ONLY && candidate.egressClass() == EgressClass.CLOUD) {
                throw new ProviderAdapterException(ProviderErrorClass.SPACE_EGRESS_DENIED,
                        "Cloud route candidate is not authorized for this request");
            }
        }
        return candidates.stream()
                .sorted(Comparator.comparingInt(RouteCandidate::priority))
                .toList();
    }
}
