package com.ragforge.server.answer;

import com.ragforge.server.common.UuidV7;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Public answer projection. Every outcome is tenant/run/correlation/idempotency scoped. */
public record Answer(
        String schemaVersion,
        UUID answerId,
        UUID spaceId,
        UUID correlationId,
        UUID runId,
        String idempotencyKey,
        AnswerStatus status,
        String answerText,
        List<Claim> claims,
        List<Citation> citations,
        Abstention abstention,
        List<UUID> toolCallIds,
        AnswerProvenance provenance,
        Instant createdAt) {

    public Answer {
        if (!"v1".equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported answer schema version");
        }
        Objects.requireNonNull(answerId, "answerId");
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(createdAt, "createdAt");
        if (idempotencyKey == null || !idempotencyKey.matches("^[A-Za-z0-9._:-]{16,255}$")) {
            throw new IllegalArgumentException("idempotencyKey is invalid");
        }
        if (!spaceId.equals(provenance.spaceId()) || !correlationId.equals(provenance.correlationId())
                || !runId.equals(provenance.runId()) || !idempotencyKey.equals(provenance.idempotencyKey())) {
            throw new IllegalArgumentException("Answer provenance crosses the response scope");
        }
        claims = claims == null ? List.of() : List.copyOf(claims);
        citations = citations == null ? List.of() : List.copyOf(citations);
        toolCallIds = toolCallIds == null ? List.of() : List.copyOf(toolCallIds);
        if (toolCallIds.stream().anyMatch(Objects::isNull)
                || toolCallIds.size() != toolCallIds.stream().distinct().count()) {
            throw new IllegalArgumentException("Tool call IDs are invalid");
        }
        if (claims.stream().anyMatch(claim -> !spaceId.equals(claim.spaceId())
                || !correlationId.equals(claim.correlationId()) || !runId.equals(claim.runId())
                || !idempotencyKey.equals(claim.idempotencyKey()))) {
            throw new IllegalArgumentException("Claim crosses the answer scope");
        }
        if (citations.stream().anyMatch(citation -> !spaceId.equals(citation.spaceId())
                || !correlationId.equals(citation.correlationId()) || !runId.equals(citation.runId())
                || !idempotencyKey.equals(citation.idempotencyKey()))) {
            throw new IllegalArgumentException("Citation crosses the answer scope");
        }
        if (abstention != null && (!spaceId.equals(abstention.spaceId())
                || !correlationId.equals(abstention.correlationId()) || !runId.equals(abstention.runId())
                || !idempotencyKey.equals(abstention.idempotencyKey()))) {
            throw new IllegalArgumentException("Abstention crosses the answer scope");
        }
        switch (status) {
            case COMPLETED -> {
                if (answerText == null || answerText.isBlank() || claims.isEmpty() || abstention != null) {
                    throw new IllegalArgumentException("Completed answer is incomplete");
                }
            }
            case ABSTAINED -> {
                if (answerText != null || !claims.isEmpty() || !citations.isEmpty() || abstention == null) {
                    throw new IllegalArgumentException("Abstained answer is incomplete");
                }
            }
            case FAILED, CANCELLED -> {
                if (answerText != null || !claims.isEmpty() || !citations.isEmpty() || abstention == null) {
                    throw new IllegalArgumentException("Failed answer cannot expose successful content");
                }
            }
        }
    }

    public static Answer completed(UUID spaceId, UUID correlationId, UUID runId, String idempotencyKey,
                                   String answerText, List<Claim> claims, List<Citation> citations,
                                   AnswerProvenance provenance) {
        return completed(UuidV7.random(), spaceId, correlationId, runId, idempotencyKey, answerText,
                claims, citations, provenance);
    }

    public static Answer completed(UUID answerId, UUID spaceId, UUID correlationId, UUID runId,
                                   String idempotencyKey, String answerText, List<Claim> claims,
                                   List<Citation> citations, AnswerProvenance provenance) {
        return new Answer("v1", answerId, spaceId, correlationId, runId, idempotencyKey,
                AnswerStatus.COMPLETED, answerText, claims, citations, null, List.of(), provenance, Instant.now());
    }

    public static Answer refusal(UUID spaceId, UUID correlationId, UUID runId, String idempotencyKey,
                                 AnswerStatus status, Abstention abstention, AnswerProvenance provenance) {
        return refusal(UuidV7.random(), spaceId, correlationId, runId, idempotencyKey, status, abstention,
                provenance);
    }

    public static Answer refusal(UUID answerId, UUID spaceId, UUID correlationId, UUID runId,
                                 String idempotencyKey, AnswerStatus status, Abstention abstention,
                                 AnswerProvenance provenance) {
        if (status == AnswerStatus.COMPLETED || status == AnswerStatus.ABSTAINED && abstention == null) {
            throw new IllegalArgumentException("Refusal status is invalid");
        }
        return new Answer("v1", answerId, spaceId, correlationId, runId, idempotencyKey,
                status, null, List.of(), List.of(), abstention, List.of(), provenance, Instant.now());
    }
}
