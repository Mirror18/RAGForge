package com.ragforge.server.answer.application;

import com.ragforge.server.knowledge.domain.EvidenceBundle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CitationValidatorTest {
    private static final UUID SPACE = UUID.fromString("018f0f70-8e10-7b14-8f1a-111111111111");
    private static final UUID FOREIGN = UUID.fromString("018f0f70-8e10-7b14-8f1a-999999999999");
    private static final UUID INDEX = UUID.fromString("018f0f70-8e10-7b14-8f1a-222222222222");
    private static final UUID EVIDENCE = UUID.fromString("018f0f70-8e10-7b14-8f1a-777777777777");

    @Test
    void requireBundleCitationsRejectsForeignSpaceAndUnknownEvidence() {
        EvidenceBundle.Evidence item = new EvidenceBundle.Evidence(
                EVIDENCE, SPACE, INDEX, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "s3://space/doc",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                new EvidenceBundle.Anchor(List.of(), 0, 1, 0, 1, null, null, null, null, null, null),
                1.0, 1.0, 1.0, 1.0, "top");
        EvidenceBundle bundle = new EvidenceBundle(
                SPACE, INDEX, UUID.randomUUID(), 1, "q", "q", List.of(item), false, null);

        CitationValidator.requireBundleCitations(bundle, SPACE, Set.of(EVIDENCE));
        assertThatThrownBy(() -> CitationValidator.requireBundleCitations(bundle, FOREIGN, Set.of(EVIDENCE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CitationValidator.requireBundleCitations(bundle, SPACE, Set.of(UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
