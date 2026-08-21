package com.ragforge.server.answer;

import com.ragforge.server.common.UuidV7;
import com.ragforge.server.retrieval.EvidenceBundle;

import java.util.Objects;
import java.util.UUID;

/** Server-created citation projection. No filename, URL, quote, or document text is accepted. */
public record Citation(
        String schemaVersion,
        UUID evidenceId,
        UUID claimId,
        UUID spaceId,
        UUID correlationId,
        UUID runId,
        String idempotencyKey,
        UUID evidenceBundleId,
        int evidenceBundleVersion,
        String evidenceBundleHash,
        UUID indexVersionId,
        UUID retrievalProfileId,
        int retrievalProfileVersion,
        UUID documentRevisionId,
        UUID parentChunkId,
        UUID childChunkId,
        String contentRef,
        String textHash,
        EvidenceBundle.Anchor anchor,
        int answerCharStart,
        int answerCharEnd,
        boolean citationAllowed) {

    public Citation {
        if (!"v1".equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported citation schema version");
        }
        requireIdentity(evidenceId, claimId, spaceId, correlationId, runId, idempotencyKey,
                evidenceBundleId, indexVersionId, retrievalProfileId, documentRevisionId,
                parentChunkId, childChunkId);
        if (evidenceBundleVersion <= 0 || retrievalProfileVersion <= 0
                || evidenceBundleHash == null || !evidenceBundleHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Citation bundle provenance is invalid");
        }
        if (contentRef == null || contentRef.isBlank() || contentRef.length() > 512
                || contentRef.matches(".*(?i)(https?://|www\\.|\\.pdf|\\.docx|\\.md)(.*)")) {
            throw new IllegalArgumentException("Citation content reference is invalid");
        }
        if (textHash == null || !textHash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Citation text hash is invalid");
        }
        Objects.requireNonNull(anchor, "anchor");
        if (answerCharStart < 0 || answerCharEnd < answerCharStart || !citationAllowed) {
            throw new IllegalArgumentException("Citation answer range or allow-list flag is invalid");
        }
    }

    public Citation(UUID evidenceId, UUID claimId, UUID spaceId, UUID correlationId, UUID runId,
                     String idempotencyKey, UUID evidenceBundleId, int evidenceBundleVersion,
                     String evidenceBundleHash, UUID indexVersionId, UUID retrievalProfileId,
                     int retrievalProfileVersion, UUID documentRevisionId, UUID parentChunkId,
                     UUID childChunkId, String contentRef, String textHash, EvidenceBundle.Anchor anchor,
                     int answerCharStart, int answerCharEnd) {
        this("v1", evidenceId, claimId, spaceId, correlationId, runId, idempotencyKey, evidenceBundleId,
                evidenceBundleVersion, evidenceBundleHash, indexVersionId, retrievalProfileId,
                retrievalProfileVersion, documentRevisionId, parentChunkId, childChunkId, contentRef,
                textHash, anchor, answerCharStart, answerCharEnd, true);
    }

    private static void requireIdentity(UUID evidenceId, UUID claimId, UUID spaceId, UUID correlationId,
                                        UUID runId, String idempotencyKey, UUID evidenceBundleId,
                                        UUID indexVersionId, UUID retrievalProfileId, UUID documentRevisionId,
                                        UUID parentChunkId, UUID childChunkId) {
        Objects.requireNonNull(evidenceId, "evidenceId");
        Objects.requireNonNull(claimId, "claimId");
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(evidenceBundleId, "evidenceBundleId");
        Objects.requireNonNull(indexVersionId, "indexVersionId");
        Objects.requireNonNull(retrievalProfileId, "retrievalProfileId");
        Objects.requireNonNull(documentRevisionId, "documentRevisionId");
        Objects.requireNonNull(parentChunkId, "parentChunkId");
        Objects.requireNonNull(childChunkId, "childChunkId");
        if (idempotencyKey == null || !idempotencyKey.matches("^[A-Za-z0-9._:-]{16,255}$")) {
            throw new IllegalArgumentException("idempotencyKey is invalid");
        }
    }
}
