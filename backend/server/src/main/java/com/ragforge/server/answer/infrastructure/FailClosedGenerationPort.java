package com.ragforge.server.answer.infrastructure;

import com.ragforge.server.answer.application.GenerationPort;
import com.ragforge.server.space.infrastructure.CancellationToken;
import com.ragforge.server.space.infrastructure.ProviderAdapterException;
import com.ragforge.server.space.infrastructure.ProviderErrorClass;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** No provider call is attempted until an explicit route-backed generation port is supplied. */
public final class FailClosedGenerationPort implements GenerationPort {
    private final Phase5IntegrationObserver observer;

    public FailClosedGenerationPort() {
        this(Phase5IntegrationObserver.noop());
    }

    public FailClosedGenerationPort(Phase5IntegrationObserver observer) {
        this.observer = observer == null ? Phase5IntegrationObserver.noop() : observer;
    }

    @Override
    public CompletionStage<GenerationResult> generate(GenerationRequest request,
                                                      CancellationToken cancellationToken) {
        if (request == null || cancellationToken == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Generation request is incomplete"));
        }
        observer.record(new Phase5IntegrationObserver.Decision(request.spaceId(), request.runId(),
                request.correlationId(), "generation", "REJECTED", "UNCONFIGURED", request.egressDecision()));
        return CompletableFuture.failedFuture(new ProviderAdapterException(ProviderErrorClass.UNAVAILABLE,
                "Generation route is not configured", request.correlationId(), 0, false));
    }
}
