package com.ragforge.server.answer;

import com.ragforge.server.provider.adapter.CancellationToken;
import com.ragforge.server.provider.adapter.EgressDecision;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

public record AnswerRequest(
        UUID spaceId,
        UUID runId,
        UUID correlationId,
        String idempotencyKey,
        String query,
        UUID promptVersionId,
        UUID modelRouteVersionId,
        UUID modelProfileVersionId,
        String model,
        EgressDecision egressDecision,
        int maxContextTokens,
        Duration timeout,
        String toolSchemaVersionsJson,
        String datasetHash,
        String configHash,
        UUID traceId,
        CancellationToken cancellationToken,
        UUID answerId) {

    public AnswerRequest {
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(correlationId, "correlationId");
        if (idempotencyKey == null || !idempotencyKey.matches("^[A-Za-z0-9._:-]{16,255}$")) {
            throw new IllegalArgumentException("idempotencyKey is invalid");
        }
        if (query == null || query.isBlank() || query.length() > 32_000) {
            throw new IllegalArgumentException("query is invalid");
        }
        Objects.requireNonNull(promptVersionId, "promptVersionId");
        Objects.requireNonNull(modelRouteVersionId, "modelRouteVersionId");
        Objects.requireNonNull(modelProfileVersionId, "modelProfileVersionId");
        if (model == null || model.isBlank() || model.length() > 200) {
            throw new IllegalArgumentException("model is invalid");
        }
        Objects.requireNonNull(egressDecision, "egressDecision");
        if (maxContextTokens <= 0) {
            throw new IllegalArgumentException("maxContextTokens must be positive");
        }
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("timeout is invalid");
        }
        toolSchemaVersionsJson = toolSchemaVersionsJson == null || toolSchemaVersionsJson.isBlank()
                ? "{}" : toolSchemaVersionsJson;
        if (datasetHash == null || !datasetHash.matches("[0-9a-f]{64}")
                || configHash == null || !configHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("datasetHash/configHash are invalid");
        }
        Objects.requireNonNull(traceId, "traceId");
        cancellationToken = cancellationToken == null ? new CancellationToken() : cancellationToken;
        answerId = answerId == null ? com.ragforge.server.common.UuidV7.random() : answerId;
    }

    public AnswerRequest(UUID spaceId, UUID runId, UUID correlationId, String idempotencyKey, String query,
                         UUID promptVersionId, UUID modelRouteVersionId, UUID modelProfileVersionId,
                         String model, EgressDecision egressDecision, int maxContextTokens, Duration timeout,
                         String toolSchemaVersionsJson, String datasetHash, String configHash, UUID traceId,
                         CancellationToken cancellationToken) {
        this(spaceId, runId, correlationId, idempotencyKey, query, promptVersionId, modelRouteVersionId,
                modelProfileVersionId, model, egressDecision, maxContextTokens, timeout, toolSchemaVersionsJson,
                datasetHash, configHash, traceId, cancellationToken, null);
    }

    public AnswerRequest(UUID spaceId, UUID runId, UUID correlationId, String idempotencyKey, String query,
                         UUID promptVersionId, UUID modelRouteVersionId, UUID modelProfileVersionId,
                         String model, EgressDecision egressDecision, int maxContextTokens, Duration timeout,
                         String datasetHash, String configHash) {
        this(spaceId, runId, correlationId, idempotencyKey, query, promptVersionId, modelRouteVersionId,
                modelProfileVersionId, model, egressDecision, maxContextTokens, timeout, "{}", datasetHash,
                configHash, runId, new CancellationToken(), null);
    }
}
