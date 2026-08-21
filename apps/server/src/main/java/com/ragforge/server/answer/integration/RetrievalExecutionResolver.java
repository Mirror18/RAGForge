package com.ragforge.server.answer.integration;

import com.ragforge.server.retrieval.RetrievalProfileRepository;

import java.util.UUID;

/** Selects immutable active retrieval/index versions for one space. */
@FunctionalInterface
public interface RetrievalExecutionResolver {
    Execution resolve(UUID spaceId, UUID runId, UUID correlationId);

    record Execution(UUID spaceId, UUID indexVersionId,
                     RetrievalProfileRepository.RetrievalProfileVersion profile,
                     UUID evidenceBundleId, int evidenceBundleVersion, String evidenceBundleRef,
                     String datasetHash, String configHash) {
        public Execution {
            if (spaceId == null || indexVersionId == null || profile == null
                    || !spaceId.equals(profile.spaceId()) || evidenceBundleId == null
                    || evidenceBundleVersion <= 0 || evidenceBundleRef == null
                    || !evidenceBundleRef.matches("^[A-Za-z0-9._:/-]{1,512}$")
                    || datasetHash == null || !datasetHash.matches("[0-9a-f]{64}")
                    || configHash == null || !configHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Retrieval execution identity is invalid");
            }
        }
    }
}
