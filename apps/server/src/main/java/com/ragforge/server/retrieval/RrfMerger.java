package com.ragforge.server.retrieval;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Weighted reciprocal-rank fusion with deterministic tie breaking. */
public final class RrfMerger {
    public record MergedCandidate(
            UUID spaceId,
            UUID indexVersionId,
            UUID childChunkId,
            UUID documentRevisionId,
            UUID parentChunkId,
            String contentRef,
            String textHash,
            double denseScore,
            double bm25Score,
            int denseRank,
            int bm25Rank,
            double rrfScore,
            String searchableText) {
    }

    private RrfMerger() {
    }

    public static List<MergedCandidate> merge(
            UUID spaceId,
            UUID indexVersionId,
            List<RetrievalCandidate> dense,
            List<RetrievalCandidate> bm25,
            int rrfK,
            double denseWeight,
            double bm25Weight) {
        requireScope(spaceId, indexVersionId);
        if (rrfK <= 0 || denseWeight < 0 || denseWeight > 1 || bm25Weight < 0 || bm25Weight > 1) {
            throw new IllegalArgumentException("invalid RRF parameters");
        }
        Map<UUID, Entry> entries = new LinkedHashMap<>();
        add(entries, spaceId, indexVersionId, dense, true, rrfK, denseWeight);
        add(entries, spaceId, indexVersionId, bm25, false, rrfK, bm25Weight);
        return entries.values().stream()
                .map(Entry::toCandidate)
                .sorted(Comparator.comparingDouble(MergedCandidate::rrfScore).reversed()
                        .thenComparing(candidate -> candidate.childChunkId().toString()))
                .toList();
    }

    private static void add(Map<UUID, Entry> entries, UUID spaceId, UUID indexVersionId,
            List<RetrievalCandidate> candidates, boolean dense, int rrfK, double weight) {
        if (candidates == null) {
            throw new NullPointerException("candidate list");
        }
        for (int position = 0; position < candidates.size(); position++) {
            RetrievalCandidate candidate = candidates.get(position);
            if (!spaceId.equals(candidate.spaceId()) || !indexVersionId.equals(candidate.indexVersionId())) {
                throw new IllegalArgumentException("candidate crosses retrieval scope");
            }
            int rank = position + 1;
            Entry entry = entries.computeIfAbsent(candidate.childChunkId(), ignored -> new Entry(candidate));
            entry.mergeMetadata(candidate);
            double contribution = weight / (rrfK + rank);
            if (dense) {
                entry.denseRank = rank;
                entry.denseScore = candidate.sourceScore();
            } else {
                entry.bm25Rank = rank;
                entry.bm25Score = candidate.sourceScore();
            }
            entry.rrfScore += contribution;
        }
    }

    private static void requireScope(UUID spaceId, UUID indexVersionId) {
        if (spaceId == null || indexVersionId == null) {
            throw new IllegalArgumentException("spaceId and indexVersionId are required for RRF");
        }
    }

    private static final class Entry {
        private final RetrievalCandidate first;
        private double denseScore;
        private double bm25Score;
        private int denseRank;
        private int bm25Rank;
        private double rrfScore;
        private String searchableText;

        private Entry(RetrievalCandidate first) {
            this.first = first;
            this.searchableText = first.searchableText();
        }

        private void mergeMetadata(RetrievalCandidate candidate) {
            if (!first.documentRevisionId().equals(candidate.documentRevisionId())
                    || !first.parentChunkId().equals(candidate.parentChunkId())
                    || !first.contentRef().equals(candidate.contentRef())
                    || !first.textHash().equalsIgnoreCase(candidate.textHash())) {
                throw new IllegalArgumentException("dense and BM25 metadata disagree for child " + first.childChunkId());
            }
            if (searchableText.isBlank() && !candidate.searchableText().isBlank()) {
                searchableText = candidate.searchableText();
            }
        }

        private MergedCandidate toCandidate() {
            return new MergedCandidate(first.spaceId(), first.indexVersionId(), first.childChunkId(),
                    first.documentRevisionId(), first.parentChunkId(), first.contentRef(), first.textHash(),
                    denseScore, bm25Score, denseRank, bm25Rank, rrfScore, searchableText);
        }
    }
}
