package com.ragforge.server.chunk;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure state-machine rules for chunk overrides; no database required. */
class ChunkOverrideTransitionsTest {

    @Test
    void allowedTransitionsFollowTheFreezeMachine() {
        assertThat(ChunkOverrideTransitions.canTransition(OverrideState.NONE, OverrideState.ACTIVE)).isTrue();
        assertThat(ChunkOverrideTransitions.canTransition(OverrideState.ACTIVE, OverrideState.NEEDS_REVIEW)).isTrue();
        assertThat(ChunkOverrideTransitions.canTransition(OverrideState.NEEDS_REVIEW, OverrideState.ACTIVE)).isTrue();
        assertThat(ChunkOverrideTransitions.canTransition(OverrideState.NEEDS_REVIEW, OverrideState.DISCARDED)).isTrue();
        assertThat(ChunkOverrideTransitions.canTransition(OverrideState.DISCARDED, OverrideState.ACTIVE)).isFalse();
        assertThat(ChunkOverrideTransitions.canTransition(OverrideState.ACTIVE, OverrideState.DISCARDED)).isFalse();
        assertThat(ChunkOverrideTransitions.canTransition(OverrideState.NONE, OverrideState.DISCARDED)).isFalse();
    }

    @Test
    void forbiddenTransitionThrows() {
        assertThatThrownBy(() -> ChunkOverrideTransitions.requireTransition(
                OverrideState.DISCARDED, OverrideState.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden");
    }

    @Test
    void overrideTargetingANewerRevisionMustStartInNeedsReview() {
        UUID revision = UUID.randomUUID();
        UUID newerRevision = UUID.randomUUID();
        assertThat(ChunkOverrideTransitions.initialStateForRevision(revision, revision))
                .isEqualTo(OverrideState.ACTIVE);
        assertThat(ChunkOverrideTransitions.initialStateForRevision(newerRevision, revision))
                .isEqualTo(OverrideState.NEEDS_REVIEW);
    }
}
