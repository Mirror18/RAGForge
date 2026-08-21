package com.ragforge.server.answer.integration;

import com.ragforge.server.answer.QueryEmbeddingProvider;
import com.ragforge.server.provider.adapter.CancellationToken;
import com.ragforge.server.provider.adapter.EgressDecision;
import com.ragforge.server.provider.adapter.ProviderAdapterException;
import com.ragforge.server.provider.adapter.ProviderErrorClass;

import java.util.List;

/** No fake vector is ever produced when an embedding route is not configured. */
public final class FailClosedQueryEmbeddingProvider implements QueryEmbeddingProvider {
    private final Phase5IntegrationObserver observer;

    public FailClosedQueryEmbeddingProvider() {
        this(Phase5IntegrationObserver.noop());
    }

    public FailClosedQueryEmbeddingProvider(Phase5IntegrationObserver observer) {
        this.observer = observer == null ? Phase5IntegrationObserver.noop() : observer;
    }

    @Override
    public List<Double> embed(EmbeddingRequest request, EgressDecision egressDecision,
                              CancellationToken cancellationToken) {
        if (request == null || egressDecision == null || cancellationToken == null) {
            throw new IllegalArgumentException("Embedding request is incomplete");
        }
        if (cancellationToken.isCancellationRequested()) {
            throw new ProviderAdapterException(ProviderErrorClass.CANCELLED,
                    "Query embedding was cancelled", request.correlationId(), 0);
        }
        observer.record(new Phase5IntegrationObserver.Decision(request.spaceId(), request.runId(),
                request.correlationId(), "embedding", "REJECTED", "UNCONFIGURED", egressDecision));
        throw new ProviderAdapterException(ProviderErrorClass.UNAVAILABLE,
                "Query embedding route is not configured", request.correlationId(), 0, false);
    }
}
