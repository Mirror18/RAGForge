package com.ragforge.server.answer.integration;

import com.ragforge.server.answer.RetrievalPort;
import com.ragforge.server.provider.adapter.CancellationToken;
import com.ragforge.server.retrieval.EvidenceBundle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RevisionArtifactMaterialResolverTest {
    private static final UUID SPACE = UUID.fromString("018f0f70-8e10-7b14-8f1a-111111111111");
    private static final UUID REVISION = UUID.fromString("018f0f70-8e10-7b14-8f1a-222222222222");
    private static final UUID RUN = UUID.fromString("018f0f70-8e10-7b14-8f1a-333333333333");
    private static final UUID CORRELATION = UUID.fromString("018f0f70-8e10-7b14-8f1a-444444444444");
    private static final String REF = "artifact://chunk-1";
    private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void acceptsOnlyServiceMaterialBoundToEvidenceIdentityAndHash() {
        EvidenceBundle.Evidence evidence = new EvidenceBundle.Evidence(UUID.randomUUID(), SPACE, UUID.randomUUID(),
                REVISION, UUID.randomUUID(), UUID.randomUUID(), REF, HASH,
                new EvidenceBundle.Anchor(List.of(), 0, 1, 0, 4, null, null, null, null, null, null),
                .9, .8, .7, .6, "direct-hit");
        RevisionArtifactMaterialResolver resolver = new RevisionArtifactMaterialResolver((space, revision, ref,
                expectedHash, token) -> new RevisionArtifactMaterialService.Material(space, revision, ref,
                expectedHash, "verified text"));
        RetrievalPort.RetrievalRequest request = new RetrievalPort.RetrievalRequest(SPACE, RUN, CORRELATION,
                "question", List.of(.1));

        assertThat(resolver.resolve(evidence, request, new CancellationToken())).isEqualTo("verified text");
    }

    @Test
    void rejectsServiceMaterialFromAnotherRevision() {
        EvidenceBundle.Evidence evidence = new EvidenceBundle.Evidence(UUID.randomUUID(), SPACE, UUID.randomUUID(),
                REVISION, UUID.randomUUID(), UUID.randomUUID(), REF, HASH,
                new EvidenceBundle.Anchor(List.of(), 0, 1, 0, 4, null, null, null, null, null, null),
                .9, .8, .7, .6, "direct-hit");
        RevisionArtifactMaterialResolver resolver = new RevisionArtifactMaterialResolver((space, revision, ref,
                expectedHash, token) -> new RevisionArtifactMaterialService.Material(space, UUID.randomUUID(), ref,
                expectedHash, "wrong revision"));
        RetrievalPort.RetrievalRequest request = new RetrievalPort.RetrievalRequest(SPACE, RUN, CORRELATION,
                "question", List.of(.1));

        assertThat(resolver.resolve(evidence, request, new CancellationToken())).isNull();
    }
}
