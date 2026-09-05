package com.ragforge.server.retrieval;

import com.ragforge.server.provider.ProviderRepository;
import com.ragforge.server.provider.adapter.CancellationToken;
import com.ragforge.server.provider.adapter.EgressDecision;
import com.ragforge.server.provider.adapter.EgressClass;
import com.ragforge.server.provider.adapter.ModelCapability;
import com.ragforge.server.provider.adapter.ProviderAdapter;
import com.ragforge.server.provider.adapter.ProviderAdapterException;
import com.ragforge.server.provider.adapter.ProviderConnection;
import com.ragforge.server.provider.adapter.ProviderErrorClass;
import com.ragforge.server.provider.adapter.ProviderRerankRequest;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Production reranker: only the published, space-bound local AI route is eligible. */
@Primary
@Component
public final class ProviderReranker implements Reranker, SpaceAwareReranker {
    private final ProviderRepository providers;
    private final JdbcTemplate jdbc;
    private final Map<com.ragforge.server.provider.adapter.ProviderType, ProviderAdapter> adapters;

    public ProviderReranker(ProviderRepository providers, JdbcTemplate jdbc, List<ProviderAdapter> adapters) {
        this.providers = providers;
        this.jdbc = jdbc;
        EnumMap<com.ragforge.server.provider.adapter.ProviderType, ProviderAdapter> configured =
                new EnumMap<>(com.ragforge.server.provider.adapter.ProviderType.class);
        for (ProviderAdapter adapter : adapters) {
            configured.put(adapter.providerType(), adapter);
        }
        this.adapters = Map.copyOf(configured);
    }

    @Override
    public List<Result> rerank(String normalizedQuery, List<RrfMerger.MergedCandidate> candidates, int limit) {
        throw unavailable("A space id is required for provider rerank");
    }

    @Override
    public List<Result> rerank(UUID spaceId, String normalizedQuery,
                               List<RrfMerger.MergedCandidate> candidates, int limit) {
        if (spaceId == null || normalizedQuery == null || normalizedQuery.isBlank()
                || candidates == null || candidates.isEmpty() || limit < 1 || limit > 100
                || candidates.size() > ProviderRerankRequest.MAX_CANDIDATES) {
            throw new ProviderAdapterException(ProviderErrorClass.INVALID_RESPONSE,
                    "Provider rerank request is outside its bound");
        }
        for (RrfMerger.MergedCandidate candidate : candidates) {
            if (!spaceId.equals(candidate.spaceId())) {
                throw new ProviderAdapterException(ProviderErrorClass.SPACE_EGRESS_DENIED,
                        "Rerank candidate crosses the requested space boundary");
            }
        }
        BoundRoute route = resolvePublishedRoute(spaceId);
        ProviderAdapter adapter = adapters.get(route.connection().providerType());
        if (adapter == null) {
            throw unavailable("No rerank adapter is configured for the bound provider");
        }
        List<RrfMerger.MergedCandidate> boundedCandidates = candidates.stream()
                .limit(ProviderRerankRequest.MAX_CANDIDATES).toList();
        List<ProviderRerankRequest.Candidate> requestCandidates = boundedCandidates.stream()
                .map(candidate -> new ProviderRerankRequest.Candidate(spaceId, candidate.childChunkId(),
                        boundedText(candidate.searchableText())))
                .toList();
        ProviderRerankRequest request = new ProviderRerankRequest(spaceId,
                new com.ragforge.server.provider.adapter.RequestIdentity(UUID.randomUUID(), UUID.randomUUID(), null),
                route.modelName(), normalizedQuery, requestCandidates,
                Duration.ofSeconds(5), Math.min(limit, requestCandidates.size()), SetHolder.RERANK);
        var response = adapter.rerank(route.connection(), EgressDecision.LOCAL_ONLY, request, new CancellationToken())
                .toCompletableFuture().join();
        Map<UUID, RrfMerger.MergedCandidate> byId = new HashMap<>();
        boundedCandidates.forEach(candidate -> byId.put(candidate.childChunkId(), candidate));
        return response.candidates().stream().map(scored -> {
            RrfMerger.MergedCandidate candidate = byId.get(scored.candidateId());
            if (candidate == null) {
                throw new ProviderAdapterException(ProviderErrorClass.INVALID_RESPONSE,
                        "Rerank response returned an unknown candidate identity");
            }
            return new Result(candidate, scored.score(), "ai-runtime-rerank");
        }).toList();
    }

    private BoundRoute resolvePublishedRoute(UUID spaceId) {
        UUID routeId;
        try {
            routeId = jdbc.queryForObject("""
                    SELECT b.model_route_version_id
                    FROM space_model_bindings b
                    JOIN model_route_versions r
                      ON r.id = b.model_route_version_id AND r.space_id = b.space_id
                    WHERE b.space_id = ? AND b.purpose = 'RERANK' AND b.status = 'ACTIVE'
                      AND r.purpose = 'RERANK' AND r.status = 'PUBLISHED'
                    ORDER BY b.version_no DESC, b.id DESC
                    LIMIT 1
                    """, UUID.class, spaceId);
        } catch (RuntimeException exception) {
            throw unavailable("No published RERANK route is bound to the requested space");
        }
        ProviderRepository.ModelRouteVersion route = providers.findRouteVersion(spaceId, routeId)
                .filter(value -> value.purpose() == ProviderRepository.RoutePurpose.RERANK
                        && value.status() == ProviderRepository.ModelRouteStatus.PUBLISHED
                        && value.egressPolicy() == ProviderRepository.EgressPolicy.LOCAL_ONLY)
                .orElseThrow(() -> unavailable("Bound RERANK route is not published or is not local"));
        ProviderRepository.RouteCandidate candidate = providers.listRouteCandidates(spaceId, route.id()).stream()
                .findFirst().orElseThrow(() -> unavailable("Published RERANK route has no candidate"));
        ProviderRepository.ModelProfileVersion profile = providers.findProfileVersion(spaceId, candidate.profileVersionId())
                .filter(value -> value.status() == ProviderRepository.ModelProfileStatus.PUBLISHED)
                .orElseThrow(() -> unavailable("Bound RERANK profile is not published"));
        ProviderRepository.ProviderConnection stored = providers.findConnectionInSpace(spaceId,
                        profile.providerConnectionId())
                .orElseThrow(() -> unavailable("Bound RERANK provider connection is unavailable"));
        if (stored.providerType() != ProviderRepository.ProviderType.AI_RUNTIME
                || stored.egressPolicy() != ProviderRepository.EgressPolicy.LOCAL_ONLY
                || !spaceId.equals(stored.spaceId())) {
            throw new ProviderAdapterException(ProviderErrorClass.SPACE_EGRESS_DENIED,
                    "RERANK route must use a local AI runtime in the requested space");
        }
        ProviderConnection connection = new ProviderConnection(spaceId, stored.id(),
                Math.max(1, stored.version() + 1),
                com.ragforge.server.provider.adapter.ProviderType.AI_RUNTIME, EgressClass.LOCAL,
                URI.create(stored.endpointUri()), stored.credentialRef(), stored.authScheme());
        return new BoundRoute(route, profile.modelName(), connection);
    }

    private static String boundedText(String text) {
        if (text == null || text.isBlank()) {
            throw new ProviderAdapterException(ProviderErrorClass.INVALID_RESPONSE,
                    "Rerank candidate has no bounded text");
        }
        return text.substring(0, Math.min(text.length(), ProviderRerankRequest.MAX_CANDIDATE_TEXT_CHARS));
    }

    private static ProviderAdapterException unavailable(String detail) {
        return new ProviderAdapterException(ProviderErrorClass.UNAVAILABLE, detail);
    }

    private record BoundRoute(ProviderRepository.ModelRouteVersion route, String modelName,
                              ProviderConnection connection) {
    }

    private static final class SetHolder {
        private static final java.util.Set<ModelCapability> RERANK = java.util.Set.of(ModelCapability.RERANK);
    }
}
