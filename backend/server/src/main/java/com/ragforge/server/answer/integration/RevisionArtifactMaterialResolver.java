package com.ragforge.server.answer.integration;

import com.ragforge.server.provider.adapter.CancellationToken;
import com.ragforge.server.answer.RetrievalPort;
import com.ragforge.server.retrieval.EvidenceBundle;

import java.util.Objects;

/** Adapts the revision/artifact service to retrieval without trusting client-provided content. */
public final class RevisionArtifactMaterialResolver implements RetrievalServicePortAdapter.EvidenceMaterialResolver {
    private final RevisionArtifactMaterialService materials;

    public RevisionArtifactMaterialResolver(RevisionArtifactMaterialService materials) {
        this.materials = Objects.requireNonNull(materials, "materials");
    }

    @Override
    public String resolve(EvidenceBundle.Evidence evidence, RetrievalPort.RetrievalRequest request,
                          CancellationToken cancellationToken) {
        if (!request.spaceId().equals(evidence.spaceId()) || cancellationToken.isCancellationRequested()) {
            return null;
        }
        RevisionArtifactMaterialService.Material material = materials.resolve(request.spaceId(),
                evidence.documentRevisionId(), evidence.contentRef(), evidence.textHash(), cancellationToken);
        if (material == null || !request.spaceId().equals(material.spaceId())
                || !evidence.documentRevisionId().equals(material.documentRevisionId())
                || !evidence.contentRef().equals(material.contentRef())
                || !evidence.textHash().equalsIgnoreCase(material.textHash())) {
            return null;
        }
        return material.text();
    }
}
