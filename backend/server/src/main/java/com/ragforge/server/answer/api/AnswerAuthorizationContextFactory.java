package com.ragforge.server.answer.api;

import com.ragforge.server.answer.AnswerAuthorizationContext;
import com.ragforge.server.common.ApiException;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.provider.SpaceAuthorization;
import com.ragforge.server.run.RunRepository;
import com.ragforge.server.space.SpaceRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/** Builds the typed authorization context after the HTTP adapter has authenticated the session. */
@Component
public final class AnswerAuthorizationContextFactory {
    private final SpaceAuthorization authorization;
    private final RunRepository runs;

    public AnswerAuthorizationContextFactory(SpaceAuthorization authorization, RunRepository runs) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.runs = Objects.requireNonNull(runs, "runs");
    }

    public AnswerAuthorizationContext issue(SessionPrincipal principal, UUID spaceId, UUID runId,
                                            UUID correlationId, UUID traceId) {
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_required", "Authentication required",
                    "A valid session is required");
        }
        SpaceRole role = authorization.requireMember(spaceId, principal);
        RunRepository.RunRecord run = runs.findRun(spaceId, runId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "run_not_found", "Run not found", "Run not found"));
        if (run.actorUserId() == null || !principal.userId().equals(run.actorUserId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "run_owner_required", "Forbidden",
                    "The answer run is not owned by the authenticated user");
        }
        if (run.correlationId() != null && !correlationId.equals(run.correlationId())) {
            throw new ApiException(HttpStatus.CONFLICT, "run_correlation_mismatch", "Run conflict",
                    "The answer request correlation does not match the run");
        }
        return new AnswerAuthorizationContext(principal, spaceId, role, runId, correlationId, traceId,
                principal.expiresAt());
    }
}
