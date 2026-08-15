package com.ragforge.server.index;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure state-machine rules for index versions; no database required. */
class IndexStateTransitionsTest {

    private static final IndexValidation PASSED = new IndexValidation(
            12, 240, 768, 0, true, true, Instant.parse("2026-08-15T10:00:00Z"));

    @Test
    void allowedTransitionsFollowTheLifecycle() {
        assertThat(IndexStateTransitions.canTransition(IndexState.BUILDING, IndexState.VALIDATING)).isTrue();
        assertThat(IndexStateTransitions.canTransition(IndexState.VALIDATING, IndexState.READY)).isTrue();
        assertThat(IndexStateTransitions.canTransition(IndexState.READY, IndexState.ACTIVE)).isTrue();
        assertThat(IndexStateTransitions.canTransition(IndexState.ACTIVE, IndexState.RETIRED)).isTrue();
        assertThat(IndexStateTransitions.canTransition(IndexState.BUILDING, IndexState.FAILED)).isTrue();
        assertThat(IndexStateTransitions.canTransition(IndexState.VALIDATING, IndexState.FAILED)).isTrue();
        assertThat(IndexStateTransitions.canTransition(IndexState.READY, IndexState.ACTIVE)).isTrue();
        assertThat(IndexStateTransitions.canTransition(IndexState.READY, IndexState.BUILDING)).isFalse();
        assertThat(IndexStateTransitions.canTransition(IndexState.ACTIVE, IndexState.BUILDING)).isFalse();
        assertThat(IndexStateTransitions.canTransition(IndexState.RETIRED, IndexState.ACTIVE)).isFalse();
        assertThat(IndexStateTransitions.canTransition(IndexState.FAILED, IndexState.READY)).isFalse();
    }

    @Test
    void activationRequiresPassedValidation() {
        IndexStateTransitions.requireActivationEligible(PASSED);
        assertThatThrownBy(() -> IndexStateTransitions.requireActivationEligible(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("validation");
        assertThatThrownBy(() -> IndexStateTransitions.requireActivationEligible(new IndexValidation(
                12, 240, 768, 2, false, true, Instant.parse("2026-08-15T10:00:00Z"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sample retrieval");
    }
}
