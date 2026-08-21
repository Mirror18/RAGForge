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
        Objects.requireNonNull(request, "request");
        String normalized = normalizeQuery(request.originalQuery());
        String collection = CandidateIndexService.collectionFor(request.spaceId(), request.indexVersionId());
        List<RetrievalCandidate> dense = denseStore.search(collection, request.spaceId(), request.indexVersionId(),
                        request.queryVector(), request.profile().denseTopK()).stream()
                .map(RetrievalService::denseCandidate)
                .toList();
        List<RetrievalCandidate> bm25 = bm25Store.search(request.spaceId(), request.indexVersionId(), normalized,
                request.profile().bm25TopK());
        List<RrfMerger.MergedCandidate> merged = RrfMerger.merge(request.spaceId(), request.indexVersionId(), dense, bm25,
                request.profile().rrfK(), request.profile().rrfDenseWeight(), request.profile().rrfBm25Weight());
        List<Reranker.Result> reranked = reranker.rerank(normalized, merged, request.profile().rerankTopK());
        List<EvidenceBundle.Evidence> evidence = selectContext(request, reranked);
        boolean abstained = evidence.isEmpty();
        return new EvidenceBundle(request.spaceId(), request.indexVersionId(), request.profile().profileId(),
                request.profile().versionNo(), request.originalQuery(), normalized, evidence, abstained,
                abstained ? "NO_VERIFIED_EVIDENCE" : null);
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
