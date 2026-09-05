package com.ragforge.server.agent;

import com.ragforge.server.common.CorrelationIdFilter;
import com.ragforge.server.identity.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Trusted server-side context. The authorized space is never read from tool arguments. */
public record ToolExecutionContext(
        SessionPrincipal principal,
        UUID authorizedSpaceId,
        UUID traceId,
        UUID correlationId,
        String idempotencyKey,
        Instant startedAt) {

    public ToolExecutionContext {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(authorizedSpaceId, "authorizedSpaceId");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(correlationId, "correlationId");
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._~-]{1,255}")) {
            throw new IllegalArgumentException("idempotencyKey is invalid");
        }
        Objects.requireNonNull(startedAt, "startedAt");
    }

    public static ToolExecutionContext from(SessionPrincipal principal, UUID authorizedSpaceId,
                                            UUID traceId, HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        return new ToolExecutionContext(principal, authorizedSpaceId, traceId,
                UUID.fromString(CorrelationIdFilter.current(request)),
                request.getHeader("Idempotency-Key"), Instant.now());
    }
}
