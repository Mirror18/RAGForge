package com.ragforge.server.answer;

import java.util.Objects;
import java.util.UUID;

/** Immutable, redacted replay identity for one RAG run. */
public record AnswerProvenance(
        String schemaVersion,
        UUID spaceId,
        UUID correlationId,
        UUID runId,
        String idempotencyKey,
        UUID evidenceBundleId,
        int evidenceBundleVersion,
        String evidenceBundleHash,
        String evidenceBundleRef,
        UUID indexVersionId,
        UUID retrievalProfileId,
        int retrievalProfileVersion,
        UUID ragPromptVersionId,
        String promptHash,
        UUID modelRouteVersionId,
        UUID modelProfileVersionId,
        String modelVersion,
        String toolSchemaVersionsJson,
        String datasetHash,
        String configHash,
        UUID traceId) {

    public AnswerProvenance {
        if (!"v1".equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported provenance schema version");
        }
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(traceId, "traceId");
        if (idempotencyKey == null || !idempotencyKey.matches("^[A-Za-z0-9._:-]{16,255}$")) {
            throw new IllegalArgumentException("idempotencyKey is invalid");
        }
        if (evidenceBundleId != null && (evidenceBundleVersion <= 0
                || evidenceBundleHash == null || !evidenceBundleHash.matches("[0-9a-f]{64}")
                || evidenceBundleRef == null || evidenceBundleRef.isBlank())) {
            throw new IllegalArgumentException("Evidence bundle provenance is invalid");
        }
        if (promptHash != null && !promptHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Prompt hash is invalid");
        }
        if (retrievalProfileId != null && retrievalProfileVersion <= 0) {
            throw new IllegalArgumentException("Retrieval profile version is invalid");
        }
        toolSchemaVersionsJson = toolSchemaVersionsJson == null || toolSchemaVersionsJson.isBlank()
                ? "{}" : toolSchemaVersionsJson;
        if (datasetHash == null || !datasetHash.matches("[0-9a-f]{64}")
                || configHash == null || !configHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Dataset/config hash is invalid");
        }
    }

    public static AnswerProvenance unavailable(UUID spaceId, UUID correlationId, UUID runId,
                                               String idempotencyKey, UUID traceId,
                                               String datasetHash, String configHash) {
        return new AnswerProvenance("v1", spaceId, correlationId, runId, idempotencyKey, null, 0, null,
                null, null, null, 0, null, null, null, null, null, "{}", datasetHash, configHash, traceId);
    }
}
