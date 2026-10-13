package com.ragforge.server.answer.infrastructure;

import com.ragforge.server.answer.domain.AnswerRequest;
import com.ragforge.server.answer.domain.AnswerAuthorizationContext;
import com.ragforge.server.answer.domain.SpaceAccessDeniedException;
import com.ragforge.server.answer.application.SpaceAuthorizer;

import java.util.UUID;
import com.ragforge.server.answer.domain.Answer;

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

    @Override
    public void requireAccess(UUID spaceId, AnswerRequest request, AnswerAuthorizationContext context) {
        requireAccess(spaceId, request);
    }
}
