package com.ragforge.server.answer.integration;

import com.ragforge.server.provider.adapter.CancellationToken;

import java.util.UUID;

/** Server-side revision/artifact service contract; callers never supply inline evidence text. */
@FunctionalInterface
public interface RevisionArtifactMaterialService {
    Material resolve(UUID spaceId, UUID documentRevisionId, String contentRef,
                     String expectedTextHash, CancellationToken cancellationToken);

    record Material(UUID spaceId, UUID documentRevisionId, String contentRef,
                    String textHash, String text) {
        public Material {
            if (spaceId == null || documentRevisionId == null || contentRef == null || contentRef.isBlank()
                    || textHash == null || !textHash.matches("[0-9a-fA-F]{64}")
                    || text == null || text.isBlank() || text.length() > 100_000) {
                throw new IllegalArgumentException("Revision artifact material is invalid");
            }
        }
    }
}
