package com.ragforge.server.answer;

import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.space.SpaceRole;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Trusted, request-scoped authorization evidence for an answer operation.
 * It is deliberately an in-memory type and is never deserialized from HTTP input.
 */
public record AnswerAuthorizationContext(SessionPrincipal principal, UUID authorizedSpaceId,
                                         SpaceRole spaceRole, UUID runId, UUID correlationId,
                                         UUID traceId, Instant expiresAt) {
    public AnswerAuthorizationContext {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(principal.userId(), "principal.userId");
        Objects.requireNonNull(principal.sessionId(), "principal.sessionId");
        Objects.requireNonNull(authorizedSpaceId, "authorizedSpaceId");
        Objects.requireNonNull(spaceRole, "spaceRole");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public boolean isExpired(Instant now) {
        return now == null || !expiresAt.isAfter(now);
    }
}
