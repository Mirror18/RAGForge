package com.ragforge.server.answer.integration;

import com.ragforge.server.answer.AnswerRequest;
import com.ragforge.server.answer.SpaceAccessDeniedException;
import com.ragforge.server.answer.SpaceAuthorizer;

import java.util.UUID;

/** A missing request-to-session binding must deny, never broaden access. */
public final class FailClosedSpaceAuthorizer implements SpaceAuthorizer {
    private final Phase5IntegrationObserver observer;

    public FailClosedSpaceAuthorizer() {
        this(Phase5IntegrationObserver.noop());
    }

    public FailClosedSpaceAuthorizer(Phase5IntegrationObserver observer) {
        this.observer = observer == null ? Phase5IntegrationObserver.noop() : observer;
    }

    @Override
    public void requireAccess(UUID spaceId, AnswerRequest request) {
        if (spaceId == null || request == null || !spaceId.equals(request.spaceId())) {
            throw new SpaceAccessDeniedException("Answer request is outside the requested space");
        }
        observer.record(new Phase5IntegrationObserver.Decision(spaceId, request.runId(),
                request.correlationId(), "authorization", "REJECTED", "UNCONFIGURED", request.egressDecision()));
        throw new SpaceAccessDeniedException("Answer session authorization is not configured");
    }
}
