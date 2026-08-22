package com.ragforge.server.answer.integration;

import com.ragforge.server.index.IndexRepository;
import com.ragforge.server.index.IndexState;
import com.ragforge.server.retrieval.ExpansionMode;
import com.ragforge.server.retrieval.RetrievalProfileRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeterministicRetrievalVersionIdentityProviderTest {
    private static final UUID SPACE = UUID.randomUUID();
    private static final UUID INDEX = UUID.randomUUID();
    private static final UUID PROFILE_VERSION = UUID.randomUUID();
    private static final UUID PROFILE = UUID.randomUUID();

    @Test
    void derivesStableEvidenceIdentityFromImmutableVersions() {
        IndexRepository indexes = mock(IndexRepository.class);
        IndexRepository.IndexVersion index = new IndexRepository.IndexVersion(INDEX, SPACE, 7, IndexState.ACTIVE,
                "collection-7", "embedding-v2", "chunking-v3", 12, 48, null,
                Instant.parse("2026-08-22T00:00:00Z"), null, null,
                Instant.parse("2026-08-21T00:00:00Z"));
        when(indexes.findVersion(SPACE, INDEX)).thenReturn(Optional.of(index));
        RetrievalProfileRepository.RetrievalProfileVersion profile = profile(SPACE);
        DeterministicRetrievalVersionIdentityProvider provider =
                new DeterministicRetrievalVersionIdentityProvider(indexes);

        ActiveRetrievalExecutionResolver.VersionIdentity first = provider.resolve(SPACE, INDEX, profile, UUID.randomUUID());
        ActiveRetrievalExecutionResolver.VersionIdentity second = provider.resolve(SPACE, INDEX, profile, UUID.randomUUID());

        assertThat(first).isEqualTo(second);
        assertThat(first.evidenceBundleVersion()).isEqualTo(7);
        assertThat(first.evidenceBundleRef()).isEqualTo("evidence-bundle:" + INDEX + ":" + PROFILE_VERSION + ":2");
        assertThat(first.datasetHash()).matches("[0-9a-f]{64}");
        assertThat(first.configHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void refusesForeignSpaceAndNonActiveIndex() {
        IndexRepository indexes = mock(IndexRepository.class);
        IndexRepository.IndexVersion retired = new IndexRepository.IndexVersion(INDEX, SPACE, 7, IndexState.RETIRED,
                "collection-7", "embedding-v2", "chunking-v3", 12, 48, null,
                Instant.now(), Instant.now(), Instant.now(), Instant.now());
        when(indexes.findVersion(SPACE, INDEX)).thenReturn(Optional.of(retired));
        DeterministicRetrievalVersionIdentityProvider provider =
                new DeterministicRetrievalVersionIdentityProvider(indexes);

        assertThat(provider.resolve(SPACE, INDEX, profile(SPACE), UUID.randomUUID())).isNull();
        assertThat(provider.resolve(UUID.randomUUID(), INDEX, profile(SPACE), UUID.randomUUID())).isNull();
    }

    private static RetrievalProfileRepository.RetrievalProfileVersion profile(UUID space) {
        return new RetrievalProfileRepository.RetrievalProfileVersion(PROFILE_VERSION, space, PROFILE, 2,
                20, 20, 60, .7, .3, 10, 8, ExpansionMode.PARENT_AND_NEIGHBOR,
                2, 3, 4096, Instant.parse("2026-08-22T00:00:00Z"));
    }
}
