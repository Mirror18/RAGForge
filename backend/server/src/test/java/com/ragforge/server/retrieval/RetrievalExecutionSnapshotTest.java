package com.ragforge.server.retrieval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrievalExecutionSnapshotTest {
    private static final UUID SPACE = UUID.randomUUID();
    private static final UUID INDEX = UUID.randomUUID();
    private static final UUID PROFILE = UUID.randomUUID();
    private static final UUID EVIDENCE = UUID.randomUUID();
    private static final String HASH = "a".repeat(64);

    @Mock RetrievalExecutionSnapshotRepository repository;

    @Test
    void snapshotIsImmutableAndParameterHashIsStable() {
        RetrievalExecutionSnapshot first = snapshot("run-1");
        RetrievalExecutionSnapshot retry = new RetrievalExecutionSnapshot(
                first.schemaVersion(), UUID.randomUUID(), first.spaceId(), first.executionKey(), first.indexVersionId(),
                first.profileId(), first.profileVersion(), first.effectiveParameters(), first.routeVersion(),
                first.executorVersion(), first.degradationPolicy(), first.evidenceBundleId(),
                first.evidenceBundleVersion(), first.evidenceBundleRef(), first.datasetHash(), first.configHash(),
                first.createdAt().plusSeconds(2));

        assertThat(first.effectiveParametersHash()).hasSize(64).isEqualTo(retry.effectiveParametersHash());
        assertThat(first.sameExecutionIdentity(retry)).isTrue();
    }

    @Test
    void unknownVersionFailsClosed() {
        assertThatThrownBy(() -> new RetrievalExecutionSnapshot("retrieval-execution-snapshot.v99", UUID.randomUUID(),
                SPACE, "run-1", INDEX, PROFILE, 1, parameters(), "route:v1", "executor:v1",
                "NO_UNAUTHORIZED_FALLBACK", EVIDENCE, 1, "evidence:1", HASH, HASH, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown retrieval execution snapshot version");
    }

    @Test
    void replayRequiresCurrentAuthorization() {
        RetrievalExecutionSnapshot expected = snapshot("run-1");
        when(repository.find(eq(SPACE), eq(expected.snapshotId()))).thenReturn(Optional.of(expected));
        RetrievalExecutionSnapshotService service = new RetrievalExecutionSnapshotService(repository);

        assertThatThrownBy(() -> service.requireForReplay(SPACE, expected.snapshotId(), (space, value) -> false))
                .isInstanceOf(SecurityException.class);
        assertThat(service.requireForReplay(SPACE, expected.snapshotId(), (space, value) -> true))
                .isSameAs(expected);
    }

    private static RetrievalExecutionSnapshot snapshot(String executionKey) {
        return new RetrievalExecutionSnapshot(RetrievalExecutionSnapshot.SCHEMA_VERSION, UUID.randomUUID(), SPACE,
                executionKey, INDEX, PROFILE, 1, parameters(), "route:v1", "executor:v1",
                "NO_UNAUTHORIZED_FALLBACK", EVIDENCE, 1, "evidence:1", HASH, HASH, Instant.now());
    }

    private static RetrievalExecutionSnapshot.EffectiveParameters parameters() {
        return new RetrievalExecutionSnapshot.EffectiveParameters(5, 5, 60, .5, .5, 5, 5,
                "NONE", 0, 0, 1000);
    }
}
