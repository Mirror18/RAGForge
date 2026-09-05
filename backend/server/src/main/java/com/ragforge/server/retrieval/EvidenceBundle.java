package com.ragforge.server.retrieval;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Verified retrieval output; citations can only target evidence IDs in this bundle. */
public record EvidenceBundle(
        UUID spaceId,
        UUID indexVersionId,
        UUID profileId,
        int profileVersion,
        String originalQuery,
        String normalizedQuery,
        List<Evidence> evidence,
        boolean abstained,
        String abstentionReason) {

    public record Anchor(List<String> headingPath, int tokenStart, int tokenEnd, int charStart, int charEnd,
            Integer pageNumber, String sheet, Integer slideNumber, Integer lineStart, Integer lineEnd,
            String tableCell) {
        public Anchor {
            headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
            if (tokenStart < 0 || tokenEnd < tokenStart || charStart < 0 || charEnd < charStart) {
                throw new IllegalArgumentException("evidence anchor range is invalid");
            }
        }
    }

    public record Evidence(
            UUID evidenceId,
            UUID spaceId,
            UUID indexVersionId,
            UUID documentRevisionId,
            UUID parentChunkId,
            UUID childChunkId,
            String contentRef,
            String textHash,
            Anchor anchor,
            double denseScore,
            double bm25Score,
            double rrfScore,
            double rerankScore,
            String inclusionReason) {
        public Evidence {
            if (evidenceId == null || spaceId == null || indexVersionId == null || documentRevisionId == null
                    || parentChunkId == null || childChunkId == null) {
                throw new NullPointerException("evidence identity must not be null");
            }
            if (contentRef == null || contentRef.isBlank() || textHash == null
                    || !textHash.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("evidence provenance is invalid");
            }
            anchor = Objects.requireNonNull(anchor, "anchor");
            if (!Double.isFinite(denseScore) || !Double.isFinite(bm25Score)
                    || !Double.isFinite(rrfScore) || !Double.isFinite(rerankScore)) {
                throw new IllegalArgumentException("evidence scores must be finite");
            }
            if (inclusionReason == null || inclusionReason.isBlank()) {
                throw new IllegalArgumentException("inclusionReason must not be blank");
            }
        }
    }

    public EvidenceBundle {
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(indexVersionId, "indexVersionId");
        Objects.requireNonNull(profileId, "profileId");
        if (profileVersion <= 0 || originalQuery == null || originalQuery.isBlank()
                || normalizedQuery == null || normalizedQuery.isBlank()) {
            throw new IllegalArgumentException("retrieval bundle identity or query is invalid");
        }
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        Set<UUID> ids = new HashSet<>();
        for (Evidence item : evidence) {
            if (!spaceId.equals(item.spaceId()) || !indexVersionId.equals(item.indexVersionId())
                    || !ids.add(item.evidenceId())) {
                throw new IllegalArgumentException("evidence crosses scope or is duplicated");
            }
        }
        if (abstained && (abstentionReason == null || abstentionReason.isBlank())) {
            throw new IllegalArgumentException("abstention reason is required");
        }
        if (!abstained && evidence.isEmpty()) {
            throw new IllegalArgumentException("non-abstained bundle requires evidence");
        }
    }
}
