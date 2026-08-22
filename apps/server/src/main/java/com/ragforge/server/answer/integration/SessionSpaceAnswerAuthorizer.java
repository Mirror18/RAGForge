package com.ragforge.server.answer.integration;

import com.ragforge.server.answer.AnswerAuthorizationContext;
import com.ragforge.server.answer.AnswerRequest;
import com.ragforge.server.answer.SpaceAccessDeniedException;
import com.ragforge.server.answer.SpaceAuthorizer;
import com.ragforge.server.provider.SpaceAuthorization;
import com.ragforge.server.run.RunRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Re-checks the HTTP-issued authorization evidence at the answer core boundary. */
public final class SessionSpaceAnswerAuthorizer implements SpaceAuthorizer {
    private final SpaceAuthorization authorization;
    private final RunRepository runs;
    private final Clock clock;

    public SessionSpaceAnswerAuthorizer(SpaceAuthorization authorization, RunRepository runs, Clock clock) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void requireAccess(UUID spaceId, AnswerRequest request) {
        throw new SpaceAccessDeniedException("Explicit answer authorization context is required");
    }

    @Override
    public void requireAccess(UUID spaceId, AnswerRequest request, AnswerAuthorizationContext context) {
        if (context == null || !spaceId.equals(context.authorizedSpaceId())
                || !spaceId.equals(request.spaceId()) || !request.runId().equals(context.runId())
                || !request.correlationId().equals(context.correlationId())
                || context.isExpired(Instant.now(clock))) {
            throw new SpaceAccessDeniedException("Answer authorization context is invalid");
        }
        var currentRole = authorization.requireMember(spaceId, context.principal());
        if (currentRole != context.spaceRole()) {
            throw new SpaceAccessDeniedException("Answer space membership changed");
        }
        RunRepository.RunRecord run = runs.findRun(spaceId, request.runId()).orElseThrow(() ->
                new SpaceAccessDeniedException("Answer run is not available in the requested space"));
        if (run.actorUserId() == null || !context.principal().userId().equals(run.actorUserId())
                || (run.correlationId() != null && !request.correlationId().equals(run.correlationId()))) {
            throw new SpaceAccessDeniedException("Answer run ownership is invalid");
        }
    }
}
