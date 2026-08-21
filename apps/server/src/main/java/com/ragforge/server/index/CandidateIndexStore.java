package com.ragforge.server.index;

import java.util.List;
import java.util.UUID;

/** Provider-neutral port for isolated candidate vector collections. */
public interface CandidateIndexStore {
    record CandidatePoint(
            UUID id,
            UUID spaceId,
            UUID indexVersionId,
            UUID documentRevisionId,
            UUID parentChunkId,
            String contentRef,
            String textHash,
            List<Double> vector) {
        public CandidatePoint {
            if (id == null || spaceId == null || indexVersionId == null || documentRevisionId == null
                    || parentChunkId == null) {
                throw new NullPointerException("candidate point identity must not be null");
            }
            if (contentRef == null || contentRef.isBlank()) {
                throw new IllegalArgumentException("contentRef must not be blank");
            }
            if (textHash == null || !textHash.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("textHash must be a SHA-256 hex digest");
            }
            if (vector == null || vector.isEmpty() || vector.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
                throw new IllegalArgumentException("vector must contain finite values");
            }
            vector = List.copyOf(vector);
        }
    }

    record CandidateHit(UUID id, double score, UUID spaceId, UUID indexVersionId,
            UUID documentRevisionId, UUID parentChunkId, String contentRef, String textHash) {
    }

    record ValidationResult(int pointCount, int vectorDimension, int orphanCount,
            boolean sampleRetrievalPassed, boolean spaceFilterPassed) {
    }

    void createCollection(String collectionName, int dimension);

    void upsert(String collectionName, List<CandidatePoint> points);

    ValidationResult validate(String collectionName, UUID spaceId, UUID indexVersionId,
            int expectedPointCount, int expectedDimension, List<CandidatePoint> samples);

    List<CandidateHit> search(String collectionName, UUID spaceId, UUID indexVersionId,
            List<Double> queryVector, int limit);

    void deleteCollection(String collectionName);
}
