package com.ragforge.ingestion.messaging;

import java.util.UUID;

public record IngestionJobRequestedPayload(
        UUID jobId,
        UUID sourceId,
        UUID documentRevisionId,
        UUID pipelineVersionId,
        UUID attemptId,
        String operation,
        ArtifactReference artifactRef) {

    public void validate() {
        if (jobId == null || sourceId == null || documentRevisionId == null || pipelineVersionId == null
                || attemptId == null || operation == null || artifactRef == null || artifactRef.artifactId() == null
                || artifactRef.mediaType() == null || artifactRef.byteLength() < 0 || artifactRef.sha256() == null
                || !artifactRef.sha256().matches("^[0-9a-fA-F]{64}$")) {
            throw new EnvelopeValidationException("REQUESTED_PAYLOAD_REQUIRED_FIELD_MISSING");
        }
        if (!SetOfOperations.contains(operation)) {
            throw new EnvelopeValidationException("REQUESTED_OPERATION_INVALID");
        }
    }

    public record ArtifactReference(UUID artifactId, String mediaType, long byteLength, String sha256) { }

    private static final class SetOfOperations {
        private static boolean contains(String value) {
            return value.equals("FULL_SYNC") || value.equals("INCREMENTAL_SYNC")
                    || value.equals("DOCUMENT_UPSERT") || value.equals("DOCUMENT_DELETE");
        }
    }
}
