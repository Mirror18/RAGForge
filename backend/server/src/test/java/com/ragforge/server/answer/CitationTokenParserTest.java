package com.ragforge.server.answer;

import com.ragforge.server.retrieval.EvidenceBundle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CitationTokenParserTest {
    private static final UUID SPACE = UUID.fromString("0190f5c2-7c1e-7ac0-8def-1234567890ab");
    private static final UUID INDEX = UUID.fromString("0190f5c2-7c1e-7ac1-8def-1234567890ab");
    private static final UUID PROFILE = UUID.fromString("0190f5c2-7c1e-7ac2-8def-1234567890ab");
    private static final UUID REVISION = UUID.fromString("0190f5c2-7c1e-7ac3-8def-1234567890ab");
    private static final UUID PARENT = UUID.fromString("0190f5c2-7c1e-7ac4-8def-1234567890ab");
    private static final UUID EVIDENCE = UUID.fromString("0190f5c2-7c1e-7ad3-8def-1234567890ab");
    private static final UUID FOREIGN = UUID.fromString("0190f5c2-7c1e-7ad4-8def-1234567890ab");
    private static final String HASH = "a".repeat(64);

    private final CitationTokenParser parser = new CitationTokenParser();

    @Test
    void citationTokensAndEvidenceIdShapesProjectToSameStructuredIds() {
        List<String> fromTokens = parser.normalizeClaimCitationTokens(List.of(EVIDENCE.toString()), null);
        List<String> fromEvidenceId = parser.normalizeClaimCitationTokens(List.of(), EVIDENCE.toString());
        assertThat(fromTokens).isEqualTo(fromEvidenceId);

        EvidenceBundle bundle = bundle();
        assertThat(parser.parse(fromTokens, bundle, SPACE)).containsExactly(EVIDENCE);
        assertThat(parser.parse(fromEvidenceId, bundle, SPACE)).containsExactly(EVIDENCE);
    }

    @Test
    void freeTextEmptyClaimsAndBundleExternalIdsAreRejected() {
        assertThatThrownBy(() -> parser.parse(List.of("see doc above"), bundle(), SPACE))
                .isInstanceOf(CitationTokenParser.CitationTokenException.class)
                .hasMessageContaining("UUIDv7");

        assertThatThrownBy(() -> parser.normalizeClaimCitationTokens(List.of(), null))
                .isInstanceOf(CitationTokenParser.CitationTokenException.class)
                .hasMessageContaining("citation_tokens or evidence_id");

        assertThatThrownBy(() -> parser.parse(List.of(FOREIGN.toString()), bundle(), SPACE))
                .isInstanceOf(CitationTokenParser.CitationTokenException.class);
    }

    @Test
    void conflictingCitationTokensAndEvidenceIdAreRejected() {
        assertThatThrownBy(() -> parser.normalizeClaimCitationTokens(List.of(EVIDENCE.toString()), FOREIGN.toString()))
                .isInstanceOf(CitationTokenParser.CitationTokenException.class)
                .hasMessageContaining("same evidence id");
    }

    private static EvidenceBundle bundle() {
        EvidenceBundle.Evidence evidence = new EvidenceBundle.Evidence(
                EVIDENCE, SPACE, INDEX, REVISION, PARENT, EVIDENCE, "content:alpha", HASH,
                new EvidenceBundle.Anchor(List.of("section"), 0, 2, 0, 10,
                        null, null, null, null, null, null),
                .9, .8, .7, .6, "direct-hit");
        return new EvidenceBundle(SPACE, INDEX, PROFILE, 1, "q", "q", List.of(evidence), false, null);
    }
}
