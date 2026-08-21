package com.ragforge.server.answer;

import java.util.UUID;

@FunctionalInterface
public interface RagPromptPort {
    VersionedRagPrompt load(UUID spaceId, UUID promptVersionId, UUID correlationId);

    record VersionedRagPrompt(UUID id, UUID spaceId, String promptKey, int versionNo, String template,
                              String promptOpaqueRef, String promptHash) {
        public VersionedRagPrompt {
            if (id == null || spaceId == null || promptKey == null || promptKey.isBlank()
                    || versionNo <= 0 || template == null || template.isBlank()
                    || promptOpaqueRef == null || promptOpaqueRef.isBlank()
                    || promptHash == null || !promptHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Versioned RAG prompt is invalid");
            }
        }
    }
}
