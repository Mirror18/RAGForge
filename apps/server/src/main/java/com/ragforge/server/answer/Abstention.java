package com.ragforge.server.answer;

import com.ragforge.server.common.UuidV7;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Structured, safe refusal. It deliberately has no prompt, provider response, or document text. */
public record Abstention(
        String schemaVersion,
        UUID abstentionId,
        UUID spaceId,
        UUID correlationId,
        UUID runId,
        String idempotencyKey,
        AbstentionReason reasonCode,
        List<UUID> evidenceIds,
        String message) {

    public Abstention {
        if (!"v1".equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported abstention schema version");
        }
        requireIdentity(abstentionId, spaceId, correlationId, runId, idempotencyKey);
        Objects.requireNonNull(reasonCode, "reasonCode");
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        if (evidenceIds.stream().anyMatch(Objects::isNull)
                || evidenceIds.size() != evidenceIds.stream().distinct().count()) {
            throw new IllegalArgumentException("Abstention evidence IDs are invalid");
        }
        if (message == null || message.isBlank() || message.length() > 2_000) {
            throw new IllegalArgumentException("Abstention message is invalid");
        }
    }

    public Abstention(UUID spaceId, UUID correlationId, UUID runId, String idempotencyKey,
                      AbstentionReason reasonCode, List<UUID> evidenceIds, String message) {
        this("v1", UuidV7.random(), spaceId, correlationId, runId, idempotencyKey,
                reasonCode, evidenceIds, message);
    }

    private static void requireIdentity(UUID abstentionId, UUID spaceId, UUID correlationId, UUID runId,
                                        String idempotencyKey) {
        Objects.requireNonNull(abstentionId, "abstentionId");
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(runId, "runId");
        if (idempotencyKey == null || !idempotencyKey.matches("^[A-Za-z0-9._:-]{16,255}$")) {
            throw new IllegalArgumentException("idempotencyKey is invalid");
        }
    }
}
