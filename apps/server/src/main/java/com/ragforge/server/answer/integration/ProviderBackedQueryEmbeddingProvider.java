package com.ragforge.server.answer.integration;

import com.ragforge.server.answer.QueryEmbeddingProvider;
import com.ragforge.server.provider.adapter.CancellationToken;
import com.ragforge.server.provider.adapter.EgressDecision;
import com.ragforge.server.provider.adapter.ModelCapability;
import com.ragforge.server.provider.adapter.ProviderAdapter;
import com.ragforge.server.provider.adapter.ProviderAdapterException;
import com.ragforge.server.provider.adapter.ProviderEmbeddingRequest;
import com.ragforge.server.provider.adapter.ProviderEmbeddingResponse;
import com.ragforge.server.provider.adapter.ProviderErrorClass;
import com.ragforge.server.provider.adapter.RequestIdentity;
import com.ragforge.server.run.ProviderAdapterRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

/** Uses the embedding binding and the same provider connection registry as generation. */
public final class ProviderBackedQueryEmbeddingProvider implements QueryEmbeddingProvider {
    private final ProviderRouteResolver routes;
    private final ProviderAdapterRegistry adapters;
    private final Duration timeout;
    private final Phase5IntegrationObserver observer;

    public ProviderBackedQueryEmbeddingProvider(ProviderRouteResolver routes, ProviderAdapterRegistry adapters,
                                                Duration timeout, Phase5IntegrationObserver observer) {
        this.routes = Objects.requireNonNull(routes, "routes");
        this.adapters = Objects.requireNonNull(adapters, "adapters");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.observer = observer == null ? Phase5IntegrationObserver.noop() : observer;
    }

    @Override
    public List<Double> embed(EmbeddingRequest request, EgressDecision decision, CancellationToken cancellationToken) {
        if (cancellationToken.isCancellationRequested()) {
            throw new ProviderAdapterException(ProviderErrorClass.CANCELLED, "Embedding was cancelled",
                    request.correlationId(), 0);
        }
        ProviderRouteResolver.ResolvedRoute route = routes.resolveEmbedding(request.spaceId(), decision,
                request.correlationId());
        if ((decision == EgressDecision.LOCAL_ONLY && route.egressDecision() != EgressDecision.LOCAL_ONLY)
                || (decision == EgressDecision.CLOUD_ALLOWED
                && route.egressDecision() != EgressDecision.LOCAL_ONLY
                && route.egressDecision() != EgressDecision.CLOUD_ALLOWED)
                || !request.spaceId().equals(route.spaceId())) {
            throw new ProviderAdapterException(ProviderErrorClass.SPACE_EGRESS_DENIED,
                    "Embedding route crossed a space boundary", request.correlationId(), 0, false);
        }
        ProviderAdapter adapter = adapters.require(route.providerType());
        ProviderEmbeddingRequest providerRequest = new ProviderEmbeddingRequest(request.spaceId(),
                new RequestIdentity(request.runId(), request.correlationId(), null), route.model(), request.query(), timeout,
                java.util.Set.of(ModelCapability.EMBEDDING));
        ProviderEmbeddingResponse response;
        try {
            response = adapter.embed(route.connection(), route.egressDecision(), providerRequest, cancellationToken)
                    .toCompletableFuture().orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof ProviderAdapterException providerFailure) {
                throw providerFailure;
            }
            if (cancellationToken.isCancellationRequested() || cause instanceof CancellationException) {
                throw new ProviderAdapterException(ProviderErrorClass.CANCELLED, "Embedding was cancelled",
                        request.correlationId(), 0);
            }
            if (cause instanceof TimeoutException) {
                throw new ProviderAdapterException(ProviderErrorClass.TIMEOUT, "Provider embedding timed out",
                        request.correlationId(), 0);
            }
            throw new ProviderAdapterException(ProviderErrorClass.UNAVAILABLE, "Provider embedding failed",
                    request.correlationId(), 0);
        }
        if (response == null || response.identity() == null
                || !request.runId().equals(response.identity().requestId())
                || !request.correlationId().equals(response.identity().correlationId())
                || !route.model().equals(response.model())) {
            throw new ProviderAdapterException(ProviderErrorClass.INVALID_RESPONSE,
                    "Provider embedding response identity is invalid", request.correlationId(), 0, false);
        }
        observer.record(new Phase5IntegrationObserver.Decision(request.spaceId(), request.runId(),
                request.correlationId(), "embedding", "SUCCEEDED", "EXACT_EMBEDDING_ROUTE", route.egressDecision()));
        return response.embedding();
    }
}
