package com.ragforge.server.retrieval;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.ragforge.server.index.CandidateIndexService;
import com.ragforge.server.index.CandidateIndexStore;

/** Dense + BM25 + RRF + rerank + bounded context expansion orchestration. */
@Service
public final class RetrievalService {
    public record Request(UUID spaceId, UUID indexVersionId,
            RetrievalProfileRepository.RetrievalProfileVersion profile,
            String originalQuery, List<Double> queryVector) {
        public Request {
            Objects.requireNonNull(spaceId, "spaceId");
            Objects.requireNonNull(indexVersionId, "indexVersionId");
            Objects.requireNonNull(profile, "profile");
            if (!spaceId.equals(profile.spaceId())) {
                throw new IllegalArgumentException("retrieval profile crosses space boundary");
            }
            if (originalQuery == null || originalQuery.isBlank() || queryVector == null || queryVector.isEmpty()
                    || queryVector.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
                throw new IllegalArgumentException("originalQuery and queryVector are required");
            }
            queryVector = List.copyOf(queryVector);
        }
    }

    /** Redacted stage candidate metadata for internal observability and adapters. */
    public record TraceCandidate(UUID childChunkId, UUID documentRevisionId, String contentRef,
            String textHash, int rank, double score, String reason) {
        public TraceCandidate {
            Objects.requireNonNull(childChunkId, "childChunkId");
            Objects.requireNonNull(documentRevisionId, "documentRevisionId");
            if (contentRef == null || contentRef.isBlank() || textHash == null
                    || !textHash.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("trace provenance is invalid");
            }
            if (rank <= 0 || !Double.isFinite(score) || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("trace candidate metadata is invalid");
            }
        }
    }

    public record StageTrace(List<TraceCandidate> candidates, double latencyMs) {
        public StageTrace {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            if (!Double.isFinite(latencyMs) || latencyMs < 0) {
                throw new IllegalArgumentException("trace stage latency is invalid");
            }
        }
    }

    public record ContextTrace(List<UUID> childChunkIds, int totalTokens, int maxContextTokens,
            boolean truncated) {
        public ContextTrace {
            childChunkIds = childChunkIds == null ? List.of() : List.copyOf(childChunkIds);
            if (totalTokens < 0 || maxContextTokens < 0) {
                throw new IllegalArgumentException("trace context metrics are invalid");
            }
        }
    }

    /** One execution of every retrieval stage; it contains no query vector or searchable text. */
    public record Trace(StageTrace dense, StageTrace bm25, StageTrace rrf, StageTrace rerank,
            ContextTrace context, EvidenceBundle evidence) {
        public Trace {
            Objects.requireNonNull(dense, "dense");
            Objects.requireNonNull(bm25, "bm25");
            Objects.requireNonNull(rrf, "rrf");
            Objects.requireNonNull(rerank, "rerank");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    private final CandidateIndexStore denseStore;
    private final Bm25CandidateStore bm25Store;
    private final ChunkCatalog catalog;
    private final Reranker reranker;

    public RetrievalService(CandidateIndexStore denseStore, Bm25CandidateStore bm25Store,
            ChunkCatalog catalog, Reranker reranker) {
        this.denseStore = denseStore;
        this.bm25Store = bm25Store;
        this.catalog = catalog;
        this.reranker = reranker;
    }

    public EvidenceBundle retrieve(Request request) {
        return trace(request).evidence();
    }

    public Trace trace(Request request) {
        Objects.requireNonNull(request, "request");
        String normalized = normalizeQuery(request.originalQuery());
        String collection = CandidateIndexService.collectionFor(request.spaceId(), request.indexVersionId());
        long denseStarted = System.nanoTime();
        List<RetrievalCandidate> dense = denseStore.search(collection, request.spaceId(), request.indexVersionId(),
                        request.queryVector(), request.profile().denseTopK()).stream()
                .map(RetrievalService::denseCandidate)
                .toList();
        StageTrace denseTrace = new StageTrace(toDenseTrace(dense), elapsedMs(denseStarted));
        long bm25Started = System.nanoTime();
        List<RetrievalCandidate> bm25 = bm25Store.search(request.spaceId(), request.indexVersionId(), normalized,
                request.profile().bm25TopK());
        StageTrace bm25Trace = new StageTrace(toBm25Trace(bm25), elapsedMs(bm25Started));
        long rrfStarted = System.nanoTime();
        List<RrfMerger.MergedCandidate> merged = RrfMerger.merge(request.spaceId(), request.indexVersionId(), dense, bm25,
                request.profile().rrfK(), request.profile().rrfDenseWeight(), request.profile().rrfBm25Weight());
        StageTrace rrfTrace = new StageTrace(toRrfTrace(merged), elapsedMs(rrfStarted));
        long rerankStarted = System.nanoTime();
        List<Reranker.Result> reranked = reranker.rerank(normalized, merged, request.profile().rerankTopK());
        StageTrace rerankTrace = new StageTrace(toRerankTrace(reranked), elapsedMs(rerankStarted));
        List<EvidenceBundle.Evidence> evidence = selectContext(request, reranked);
        boolean abstained = evidence.isEmpty();
        EvidenceBundle bundle = new EvidenceBundle(request.spaceId(), request.indexVersionId(), request.profile().profileId(),
                request.profile().versionNo(), request.originalQuery(), normalized, evidence, abstained,
                abstained ? "NO_VERIFIED_EVIDENCE" : null);
        return new Trace(denseTrace, bm25Trace, rrfTrace, rerankTrace, toContextTrace(request, evidence), bundle);
    }

    public static String normalizeQuery(String query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        String normalized = query.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        return normalized.replaceAll("\\s+", " ");
    }

    private List<EvidenceBundle.Evidence> selectContext(Request request, List<Reranker.Result> reranked) {
        Map<UUID, ContextSelection> selections = new LinkedHashMap<>();
        for (Reranker.Result result : reranked) {
            RrfMerger.MergedCandidate candidate = result.candidate();
            catalog.findChild(request.spaceId(), candidate.childChunkId()).ifPresent(child -> {
                if (!matchesCandidate(candidate, child)) {
                    return;
                }
                selections.putIfAbsent(child.id(), new ContextSelection(child, candidate, result.score(), "direct-hit"));
                if (request.profile().expansionMode() == ExpansionMode.PARENT
                        || request.profile().expansionMode() == ExpansionMode.PARENT_AND_NEIGHBOR) {
                    addSiblings(request, selections, child, candidate, result.score(), "parent-expansion");
                }
                if (request.profile().expansionMode() == ExpansionMode.NEIGHBOR
                        || request.profile().expansionMode() == ExpansionMode.PARENT_AND_NEIGHBOR) {
                    addNeighbors(request, selections, child, candidate, result.score());
                }
            });
        }
        List<EvidenceBundle.Evidence> result = new ArrayList<>();
        int tokenBudget = request.profile().maxContextTokens();
        int usedTokens = 0;
        for (ContextSelection selection : selections.values()) {
            if (result.size() >= request.profile().maxContextChildren()) {
                break;
            }
            int childTokens = Math.max(1, selection.child().tokenEnd() - selection.child().tokenStart());
            if (tokenBudget > 0 && usedTokens + childTokens > tokenBudget) {
                continue;
            }
            usedTokens += childTokens;
            ChunkCatalog.ChildMetadata child = selection.child();
            RrfMerger.MergedCandidate candidate = selection.candidate();
            result.add(new EvidenceBundle.Evidence(
                    evidenceId(request, child.id()), child.spaceId(), candidate.indexVersionId(), child.documentRevisionId(),
                    child.parentChunkId(), child.id(), child.contentRef(), child.textHash(),
                    new EvidenceBundle.Anchor(child.headingPath(), child.tokenStart(), child.tokenEnd(), child.charStart(),
                            child.charEnd(), child.pageNumber(), child.sheet(), child.slideNumber(), child.lineStart(),
                            child.lineEnd(), child.tableCell()), candidate.denseScore(), candidate.bm25Score(),
                    candidate.rrfScore(), selection.rerankScore(), selection.reason()));
        }
        return result;
    }

    private void addSiblings(Request request, Map<UUID, ContextSelection> selections,
            ChunkCatalog.ChildMetadata child, RrfMerger.MergedCandidate candidate, double rerankScore, String reason) {
        if (request.profile().maxParentsPerChild() == 0) {
            return;
        }
        int added = 0;
        for (ChunkCatalog.ChildMetadata sibling : catalog.listChildrenByParent(request.spaceId(), child.parentChunkId())) {
            if (sibling.id().equals(child.id())) {
                continue;
            }
            if (selections.putIfAbsent(sibling.id(), new ContextSelection(sibling, candidate, rerankScore, reason)) == null) {
                added++;
            }
            if (added >= request.profile().maxParentsPerChild()) {
                break;
            }
        }
    }

    private void addNeighbors(Request request, Map<UUID, ContextSelection> selections,
            ChunkCatalog.ChildMetadata child, RrfMerger.MergedCandidate candidate, double rerankScore) {
        int maxNeighbors = request.profile().maxNeighborsPerParent();
        if (maxNeighbors == 0) {
            return;
        }
        List<ChunkCatalog.ChildMetadata> siblings = catalog.listChildrenByParent(request.spaceId(), child.parentChunkId());
        int index = -1;
        for (int position = 0; position < siblings.size(); position++) {
            if (siblings.get(position).id().equals(child.id())) {
                index = position;
                break;
            }
        }
        if (index < 0) {
            return;
        }
        for (int distance = 1; distance <= maxNeighbors; distance++) {
            addNeighborAt(selections, siblings, index - distance, candidate, rerankScore);
            addNeighborAt(selections, siblings, index + distance, candidate, rerankScore);
        }
    }

    private static void addNeighborAt(Map<UUID, ContextSelection> selections, List<ChunkCatalog.ChildMetadata> siblings,
            int index, RrfMerger.MergedCandidate candidate, double rerankScore) {
        if (index >= 0 && index < siblings.size()) {
            ChunkCatalog.ChildMetadata neighbor = siblings.get(index);
            selections.putIfAbsent(neighbor.id(), new ContextSelection(neighbor, candidate, rerankScore, "neighbor-expansion"));
        }
    }

    private static RetrievalCandidate denseCandidate(CandidateIndexStore.CandidateHit hit) {
        return new RetrievalCandidate(hit.spaceId(), hit.indexVersionId(), hit.id(), hit.documentRevisionId(),
                hit.parentChunkId(), hit.contentRef(), hit.textHash(), hit.score(), RetrievalCandidate.Source.DENSE, "");
    }

    private static List<TraceCandidate> toDenseTrace(List<RetrievalCandidate> candidates) {
        return toCandidateTrace(candidates, "dense");
    }

    private static List<TraceCandidate> toBm25Trace(List<RetrievalCandidate> candidates) {
        return toCandidateTrace(candidates, "bm25");
    }

    private static List<TraceCandidate> toCandidateTrace(List<RetrievalCandidate> candidates, String reason) {
        List<TraceCandidate> result = new ArrayList<>();
        for (int position = 0; position < candidates.size(); position++) {
            RetrievalCandidate candidate = candidates.get(position);
            result.add(new TraceCandidate(candidate.childChunkId(), candidate.documentRevisionId(),
                    candidate.contentRef(), candidate.textHash(), position + 1, candidate.sourceScore(), reason));
        }
        return result;
    }

    private static List<TraceCandidate> toRrfTrace(List<RrfMerger.MergedCandidate> candidates) {
        List<TraceCandidate> result = new ArrayList<>();
        for (int position = 0; position < candidates.size(); position++) {
            RrfMerger.MergedCandidate candidate = candidates.get(position);
            result.add(new TraceCandidate(candidate.childChunkId(), candidate.documentRevisionId(),
                    candidate.contentRef(), candidate.textHash(), position + 1, candidate.rrfScore(), "rrf"));
        }
        return result;
    }

    private static List<TraceCandidate> toRerankTrace(List<Reranker.Result> results) {
        List<TraceCandidate> result = new ArrayList<>();
        for (int position = 0; position < results.size(); position++) {
            Reranker.Result value = results.get(position);
            RrfMerger.MergedCandidate candidate = value.candidate();
            result.add(new TraceCandidate(candidate.childChunkId(), candidate.documentRevisionId(),
                    candidate.contentRef(), candidate.textHash(), position + 1, value.score(), value.reason()));
        }
        return result;
    }

    private static ContextTrace toContextTrace(Request request, List<EvidenceBundle.Evidence> evidence) {
        int totalTokens = evidence.stream().mapToInt(value ->
                Math.max(1, value.anchor().tokenEnd() - value.anchor().tokenStart())).sum();
        int maxContextTokens = request.profile().maxContextTokens();
        return new ContextTrace(evidence.stream().map(EvidenceBundle.Evidence::childChunkId).toList(),
                totalTokens, maxContextTokens, totalTokens >= maxContextTokens && maxContextTokens > 0);
    }

    private static double elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000.0;
    }

    private static boolean matchesCandidate(RrfMerger.MergedCandidate candidate, ChunkCatalog.ChildMetadata child) {
        return candidate.spaceId().equals(child.spaceId())
                && candidate.childChunkId().equals(child.id())
                && candidate.documentRevisionId().equals(child.documentRevisionId())
                && candidate.parentChunkId().equals(child.parentChunkId())
                && candidate.contentRef().equals(child.contentRef())
                && candidate.textHash().equalsIgnoreCase(child.textHash());
    }

    private static UUID evidenceId(Request request, UUID childId) {
        return UUID.nameUUIDFromBytes((request.spaceId() + ":" + request.indexVersionId() + ":"
                + request.profile().id() + ":" + request.profile().versionNo() + ":" + childId)
                .getBytes(StandardCharsets.UTF_8));
    }

    private record ContextSelection(ChunkCatalog.ChildMetadata child, RrfMerger.MergedCandidate candidate,
            double rerankScore, String reason) {
    }
}
