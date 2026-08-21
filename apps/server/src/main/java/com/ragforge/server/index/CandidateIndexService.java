package com.ragforge.server.index;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;

/** Builds and validates a space-isolated candidate without publishing it. */
@Service
public final class CandidateIndexService {
    public record BuildRequest(
            UUID spaceId,
            UUID indexVersionId,
            int versionNo,
            String embeddingProfileVersion,
            String chunkingStrategyVersion,
            int documentRevisionCount,
            int embeddingDimension,
            List<CandidateIndexStore.CandidatePoint> points,
            Instant createdAt) {
        public BuildRequest {
            Objects.requireNonNull(spaceId, "spaceId");
            Objects.requireNonNull(indexVersionId, "indexVersionId");
            if (versionNo <= 0 || documentRevisionCount < 0 || embeddingDimension <= 0) {
                throw new IllegalArgumentException("candidate version counts and dimension are invalid");
            }
            if (embeddingProfileVersion == null || embeddingProfileVersion.isBlank()
                    || chunkingStrategyVersion == null || chunkingStrategyVersion.isBlank()) {
                throw new IllegalArgumentException("candidate profile versions must not be blank");
            }
            points = points == null ? List.of() : List.copyOf(points);
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record BuildResult(IndexRepository.IndexVersion indexVersion,
            CandidateIndexStore.ValidationResult validation, boolean ready) {
    }

    private final IndexRepository indexes;
    private final CandidateIndexStore candidateStore;

    public CandidateIndexService(IndexRepository indexes, CandidateIndexStore candidateStore) {
        this.indexes = indexes;
        this.candidateStore = candidateStore;
    }

    public BuildResult build(BuildRequest request) {
        Objects.requireNonNull(request, "request");
        validatePoints(request);
        String collection = collectionFor(request.spaceId(), request.indexVersionId());
        indexes.createVersion(new IndexRepository.NewIndexVersion(
                request.indexVersionId(), request.spaceId(), request.versionNo(), collection,
                request.embeddingProfileVersion(), request.chunkingStrategyVersion(),
                request.documentRevisionCount(), request.points().size(), request.createdAt()));
        try {
            candidateStore.createCollection(collection, request.embeddingDimension());
            candidateStore.upsert(collection, request.points());
            indexes.transitionState(request.spaceId(), request.indexVersionId(), IndexState.VALIDATING);
            CandidateIndexStore.ValidationResult validation = candidateStore.validate(
                    collection, request.spaceId(), request.indexVersionId(), request.points().size(),
                    request.embeddingDimension(), request.points().stream().limit(Math.min(5, request.points().size())).toList());
            boolean passed = validation.pointCount() == request.points().size()
                    && validation.vectorDimension() == request.embeddingDimension()
                    && validation.orphanCount() == 0
                    && validation.sampleRetrievalPassed()
                    && validation.spaceFilterPassed();
            indexes.recordValidation(request.spaceId(), request.indexVersionId(), new IndexValidation(
                    request.documentRevisionCount(), validation.pointCount(), validation.vectorDimension(),
                    validation.orphanCount(), passed, validation.spaceFilterPassed(), Instant.now()));
            if (!passed) {
                indexes.transitionState(request.spaceId(), request.indexVersionId(), IndexState.FAILED);
                return new BuildResult(indexes.findVersion(request.spaceId(), request.indexVersionId()).orElseThrow(),
                        validation, false);
            }
            indexes.transitionState(request.spaceId(), request.indexVersionId(), IndexState.READY);
            return new BuildResult(indexes.findVersion(request.spaceId(), request.indexVersionId()).orElseThrow(),
                    validation, true);
        } catch (RuntimeException exception) {
            indexes.findVersion(request.spaceId(), request.indexVersionId()).ifPresent(current -> {
                if (current.state() == IndexState.BUILDING || current.state() == IndexState.VALIDATING) {
                    indexes.transitionState(request.spaceId(), request.indexVersionId(), IndexState.FAILED);
                }
            });
            throw exception;
        }
    }

    public IndexRepository.ActiveIndexPointer publish(UUID spaceId, UUID indexVersionId, Instant now) {
        if (spaceId == null || indexVersionId == null || now == null) {
            throw new IllegalArgumentException("spaceId, indexVersionId and now are required");
        }
        return indexes.activate(spaceId, indexVersionId, now);
    }

    public static String collectionFor(UUID spaceId, UUID indexVersionId) {
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(indexVersionId, "indexVersionId");
        return "ragforge_" + spaceId.toString().replace("-", "") + "_" + indexVersionId.toString().replace("-", "");
    }

    private static void validatePoints(BuildRequest request) {
        for (CandidateIndexStore.CandidatePoint point : request.points()) {
            if (!request.spaceId().equals(point.spaceId()) || !request.indexVersionId().equals(point.indexVersionId())) {
                throw new IllegalArgumentException("candidate point crosses space or index version boundary");
            }
            if (point.vector().size() != request.embeddingDimension()) {
                throw new IllegalArgumentException("candidate point vector dimension mismatch");
            }
        }
    }
}
