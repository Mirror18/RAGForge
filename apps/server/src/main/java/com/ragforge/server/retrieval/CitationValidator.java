package com.ragforge.server.retrieval;

import java.util.Collection;
import java.util.Map;
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
}
