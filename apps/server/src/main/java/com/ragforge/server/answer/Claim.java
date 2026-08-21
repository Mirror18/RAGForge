package com.ragforge.server.answer;

import com.ragforge.server.common.UuidV7;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A generated claim whose evidence IDs are parsed and allow-listed by the server. */
public record Claim(
        String schemaVersion,
        UUID claimId,
        UUID spaceId,
        UUID correlationId,
        UUID runId,
        String idempotencyKey,
        String claimText,
        List<UUID> citationTokens,
        int answerCharStart,
        int answerCharEnd) {

    public Claim {
        if (!"v1".equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported claim schema version");
        }
        requireIdentity(claimId, spaceId, correlationId, runId, idempotencyKey);
        if (claimText == null || claimText.isBlank() || claimText.length() > 20_000) {
            throw new IllegalArgumentException("Claim text is invalid");
        }
        citationTokens = citationTokens == null ? List.of() : List.copyOf(citationTokens);
        if (citationTokens.isEmpty() || citationTokens.size() > 20
                || citationTokens.stream().anyMatch(Objects::isNull)
                || citationTokens.size() != citationTokens.stream().distinct().count()) {
            throw new IllegalArgumentException("Claim citation tokens are invalid");
        }
        if (answerCharStart < 0 || answerCharEnd < answerCharStart) {
            throw new IllegalArgumentException("Claim answer range is invalid");
        }
    }

    public Claim(UUID spaceId, UUID correlationId, UUID runId, String idempotencyKey, String claimText,
                 List<UUID> citationTokens, int answerCharStart, int answerCharEnd) {
        this("v1", UuidV7.random(), spaceId, correlationId, runId, idempotencyKey, claimText, citationTokens,
                answerCharStart, answerCharEnd);
    }

    /** Compatibility constructor for internal callers that predate the answer idempotency boundary. */
    public Claim(UUID spaceId, UUID correlationId, UUID runId, String claimText, List<UUID> citationTokens,
                 int answerCharStart, int answerCharEnd) {
        this(spaceId, correlationId, runId, "legacy-answer-key-01", claimText, citationTokens,
                answerCharStart, answerCharEnd);
    }

    private static void requireIdentity(UUID claimId, UUID spaceId, UUID correlationId, UUID runId,
                                        String idempotencyKey) {
        Objects.requireNonNull(claimId, "claimId");
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(runId, "runId");
        if (idempotencyKey == null || !idempotencyKey.matches("^[A-Za-z0-9._:-]{16,255}$")) {
            throw new IllegalArgumentException("idempotencyKey is invalid");
        }
    }
}
