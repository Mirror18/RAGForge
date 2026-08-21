package com.ragforge.server.retrieval;

import com.ragforge.server.answer.Citation;
import com.ragforge.server.answer.EvidenceBundleSnapshot;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Server-side citation allow-list; model-provided filenames/URLs are never trusted. */
public final class CitationValidator {
    private CitationValidator() {
    }

    public static void requireBundleCitations(EvidenceBundle bundle, UUID spaceId, Collection<UUID> citedEvidenceIds) {
        if (bundle == null || spaceId == null || citedEvidenceIds == null || !spaceId.equals(bundle.spaceId())) {
            throw new IllegalArgumentException("citation bundle and space scope are required");
        }
        Map<UUID, EvidenceBundle.Evidence> allowed = bundle.evidence().stream()
                .collect(Collectors.toUnmodifiableMap(EvidenceBundle.Evidence::evidenceId, Function.identity()));
        for (UUID cited : citedEvidenceIds) {
            EvidenceBundle.Evidence evidence = allowed.get(cited);
            if (evidence == null || !spaceId.equals(evidence.spaceId())) {
                throw new IllegalArgumentException("citation is outside the current evidence bundle");
            }
        }
    }

    /**
     * Projects a citation only after checking every identity field against the current bundle.
     * The method accepts no source filename, URL, quote, or model-produced citation text.
     */
    public static Citation project(EvidenceBundleSnapshot snapshot, UUID spaceId, UUID correlationId,
                                   UUID runId, String idempotencyKey, UUID claimId, UUID evidenceId,
                                   int answerCharStart, int answerCharEnd) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(claimId, "claimId");
        requireBundleCitations(snapshot.bundle(), spaceId, java.util.List.of(evidenceId));
        EvidenceBundle.Evidence evidence = snapshot.bundle().evidence().stream()
                .filter(item -> item.evidenceId().equals(evidenceId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("citation is outside the current evidence bundle"));
        if (!spaceId.equals(evidence.spaceId()) || !snapshot.bundle().indexVersionId().equals(evidence.indexVersionId())) {
            throw new IllegalArgumentException("citation provenance crosses the current space or index");
        }
        return new Citation(evidenceId, claimId, spaceId, correlationId, runId, idempotencyKey,
                snapshot.evidenceBundleId(), snapshot.evidenceBundleVersion(), snapshot.evidenceBundleHash(),
                evidence.indexVersionId(), snapshot.bundle().profileId(), snapshot.bundle().profileVersion(),
                evidence.documentRevisionId(), evidence.parentChunkId(), evidence.childChunkId(),
                evidence.contentRef(), evidence.textHash(), evidence.anchor(), answerCharStart, answerCharEnd);
    }
}
