package com.ragforge.server.answer.infrastructure;

import com.ragforge.server.answer.application.EvidenceBundleSnapshot;
import com.ragforge.server.answer.application.RetrievalPort;
import com.ragforge.server.space.infrastructure.CancellationToken;
import com.ragforge.server.space.infrastructure.ProviderAdapterException;
import com.ragforge.server.space.infrastructure.ProviderErrorClass;

/** Prevents an unconfigured Spring context from returning an empty fake bundle. */
public final class FailClosedRetrievalPort implements RetrievalPort {
    private final Phase5IntegrationObserver observer;

    public FailClosedRetrievalPort() {
        this(Phase5IntegrationObserver.noop());
    }

    public FailClosedRetrievalPort(Phase5IntegrationObserver observer) {
        this.observer = observer == null ? Phase5IntegrationObserver.noop() : observer;
    }

    @Override
    public EvidenceBundleSnapshot retrieve(RetrievalRequest request, CancellationToken cancellationToken) {
        if (request == null || cancellationToken == null) {
            throw new IllegalArgumentException("Retrieval request is incomplete");
        }
        if (cancellationToken.isCancellationRequested()) {
            throw new ProviderAdapterException(ProviderErrorClass.CANCELLED,
                    "Retrieval was cancelled", request.correlationId(), 0);
        }
        observer.record(new Phase5IntegrationObserver.Decision(request.spaceId(), request.runId(),
                request.correlationId(), "retrieval", "REJECTED", "UNCONFIGURED", null));
        throw new ProviderAdapterException(ProviderErrorClass.UNAVAILABLE,
                "Retrieval route is not configured", request.correlationId(), 0, false);
    }
}
