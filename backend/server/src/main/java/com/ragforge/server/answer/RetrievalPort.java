package com.ragforge.server.answer;

import com.ragforge.server.provider.adapter.CancellationToken;
import com.ragforge.server.retrieval.RetrievalProfileRepository;

import java.util.List;
import java.util.UUID;
import java.util.Objects;

@FunctionalInterface
public interface RetrievalPort {
    EvidenceBundleSnapshot retrieve(RetrievalRequest request, CancellationToken cancellationToken);

    /**
     * Shared application-port trace. Chat may use only the bundle while
     * Playground/Evaluation can inspect redacted stage metadata. Implementors
     * must keep searchable text, vectors and credentials out of the trace.
     */
    default RetrievalTraceSnapshot trace(RetrievalRequest request, CancellationToken cancellationToken) {
        return RetrievalTraceSnapshot.empty(retrieve(request, cancellationToken));
    }

    /** Package adapters may bind an already authorized index/profile pair. */
    default RetrievalTraceSnapshot trace(RetrievalRequest request, UUID indexVersionId,
                                         RetrievalProfileRepository.RetrievalProfileVersion profile,
                                         CancellationToken cancellationToken) {
        return trace(request, cancellationToken);
    }

    record RetrievalRequest(UUID spaceId, UUID runId, UUID correlationId, String query, List<Double> queryEmbedding) {
        public RetrievalRequest {
            if (spaceId == null || runId == null || correlationId == null || query == null || query.isBlank()
                    || queryEmbedding == null || queryEmbedding.isEmpty()
                    || queryEmbedding.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
                throw new IllegalArgumentException("Retrieval request is invalid");
            }
            queryEmbedding = List.copyOf(queryEmbedding);
        }
    }

    record TraceCandidate(UUID childChunkId, UUID documentRevisionId, String contentRef,
                          String textHash, int rank, double score, String reason) {
        public TraceCandidate {
            Objects.requireNonNull(childChunkId, "childChunkId");
            Objects.requireNonNull(documentRevisionId, "documentRevisionId");
            if (contentRef == null || contentRef.isBlank() || textHash == null
                    || !textHash.matches("[0-9a-fA-F]{64}") || rank <= 0 || !Double.isFinite(score)
                    || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("trace candidate metadata is invalid");
            }
        }
    }

    record StageTrace(List<TraceCandidate> candidates, double latencyMs) {
        public StageTrace {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            if (!Double.isFinite(latencyMs) || latencyMs < 0) {
                throw new IllegalArgumentException("trace stage latency is invalid");
            }
        }
    }

    record ContextTrace(List<UUID> childChunkIds, int totalTokens, int maxContextTokens, boolean truncated) {
        public ContextTrace {
            childChunkIds = childChunkIds == null ? List.of() : List.copyOf(childChunkIds);
            if (totalTokens < 0 || maxContextTokens < 0) {
                throw new IllegalArgumentException("trace context metrics are invalid");
            }
        }
    }

    record RetrievalTraceSnapshot(EvidenceBundleSnapshot snapshot, StageTrace dense, StageTrace bm25,
                                  StageTrace rrf, StageTrace rerank, ContextTrace context) {
        public RetrievalTraceSnapshot {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(dense, "dense");
            Objects.requireNonNull(bm25, "bm25");
            Objects.requireNonNull(rrf, "rrf");
            Objects.requireNonNull(rerank, "rerank");
            Objects.requireNonNull(context, "context");
        }

        public static RetrievalTraceSnapshot empty(EvidenceBundleSnapshot snapshot) {
            return new RetrievalTraceSnapshot(snapshot, new StageTrace(List.of(), 0), new StageTrace(List.of(), 0),
                    new StageTrace(List.of(), 0), new StageTrace(List.of(), 0),
                    new ContextTrace(snapshot.bundle().evidence().stream().map(item -> item.childChunkId()).toList(),
                            0, 0, false));
        }
    }
}
