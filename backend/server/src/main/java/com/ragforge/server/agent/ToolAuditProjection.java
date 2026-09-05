package com.ragforge.server.agent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Deliberately small audit projection. It accepts hashes and identifiers only;
 * prompt, document body, headers, credentials and URLs are not representable.
 */
public record ToolAuditProjection(
        int schemaVersion,
        AgentToolName tool,
        UUID actorUserId,
        UUID spaceId,
        AuthorizationResult authorizationResult,
        Instant startedAt,
        Instant completedAt,
        String outputHash,
        String errorCode,
        UUID traceId,
        UUID correlationId,
        String idempotencyKey,
        Map<String, String> safeRequest) {

    public enum AuthorizationResult { AUTHORIZED, DENIED }

    public ToolAuditProjection {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("unsupported audit schema version");
        }
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(actorUserId, "actorUserId");
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(authorizationResult, "authorizationResult");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(correlationId, "correlationId");
        if (completedAt.isBefore(startedAt)
                || idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._~-]{1,255}")) {
            throw new IllegalArgumentException("audit timing or idempotency is invalid");
        }
        if (outputHash != null && !outputHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("audit output hash is invalid");
        }
        if (errorCode != null && !errorCode.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("audit error code is invalid");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        if (safeRequest != null) {
            safeRequest.forEach((key, value) -> {
                if (key == null || value == null || !key.matches("[a-zA-Z0-9_.-]{1,64}")
                        || key.toLowerCase(java.util.Locale.ROOT).matches(
                        ".*(prompt|body|header|secret|token|password|credential|raw|content|url).*")) {
                    throw new IllegalArgumentException("unsafe audit field");
                }
                if (value.length() > 256 || value.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("unsafe audit value");
                }
                copy.put(key, value);
            });
        }
        safeRequest = Map.copyOf(copy);
    }

    public static ToolAuditProjection denied(AgentToolName tool, ToolExecutionContext context,
                                             String errorCode, Instant completedAt,
                                             Map<String, String> safeRequest) {
        return new ToolAuditProjection(1, tool, context.principal().userId(), context.authorizedSpaceId(),
                AuthorizationResult.DENIED, context.startedAt(), completedAt, null, errorCode,
                context.traceId(), context.correlationId(), context.idempotencyKey(), safeRequest);
    }

    public static ToolAuditProjection authorized(AgentToolName tool, ToolExecutionContext context,
                                                 Instant completedAt, String outputHash,
                                                 String errorCode, Map<String, String> safeRequest) {
        return new ToolAuditProjection(1, tool, context.principal().userId(), context.authorizedSpaceId(),
                AuthorizationResult.AUTHORIZED, context.startedAt(), completedAt, outputHash, errorCode,
                context.traceId(), context.correlationId(), context.idempotencyKey(), safeRequest);
    }
}
