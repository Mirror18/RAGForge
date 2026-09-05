package com.ragforge.server.index;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CandidateIndexServiceTest {
    private static final UUID SPACE = UUID.fromString("018f0f70-8e10-7b14-8f1a-111111111111");
    private static final UUID VERSION = UUID.fromString("018f0f70-8e10-7b14-8f1a-222222222222");
    private static final UUID REVISION = UUID.fromString("018f0f70-8e10-7b14-8f1a-333333333333");
    private static final UUID PARENT = UUID.fromString("018f0f70-8e10-7b14-8f1a-444444444444");
    private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void successfulCandidateReachesReadyButDoesNotPublish() {
        IndexRepository repository = mock(IndexRepository.class);
        FakeStore store = new FakeStore(new CandidateIndexStore.ValidationResult(1, 3, 0, true, true));
        IndexRepository.IndexVersion building = version(IndexState.BUILDING);
        IndexRepository.IndexVersion ready = version(IndexState.READY);
        when(repository.createVersion(any())).thenReturn(building);
        when(repository.findVersion(SPACE, VERSION)).thenReturn(java.util.Optional.of(ready));

        CandidateIndexService service = new CandidateIndexService(repository, store);
        CandidateIndexService.BuildResult result = service.build(request(point(SPACE, VERSION)));

        assertThat(result.ready()).isTrue();
        assertThat(result.indexVersion().state()).isEqualTo(IndexState.READY);
        assertThat(store.createdCollection).isEqualTo(CandidateIndexService.collectionFor(SPACE, VERSION));
        verify(repository).transitionState(SPACE, VERSION, IndexState.VALIDATING);
        verify(repository).transitionState(SPACE, VERSION, IndexState.READY);
        verify(repository, never()).activate(eq(SPACE), eq(VERSION), any());
    }

    @Test
    void validationFailureMarksCandidateFailedAndLeavesActivePointerAlone() {
        IndexRepository repository = mock(IndexRepository.class);
        CandidateIndexStore store = new FakeStore(new CandidateIndexStore.ValidationResult(0, 3, 0, false, true));
        IndexRepository.IndexVersion building = version(IndexState.BUILDING);
        IndexRepository.IndexVersion failed = version(IndexState.FAILED);
        when(repository.createVersion(any())).thenReturn(building);
        when(repository.findVersion(SPACE, VERSION)).thenReturn(java.util.Optional.of(failed));

        CandidateIndexService.BuildResult result = new CandidateIndexService(repository, store).build(request(point(SPACE, VERSION)));

        assertThat(result.ready()).isFalse();
        verify(repository).transitionState(SPACE, VERSION, IndexState.FAILED);
        verify(repository, never()).activate(eq(SPACE), eq(VERSION), any());
    }

    @Test
    void crossSpacePointIsRejectedBeforeAnyCandidateWrite() {
        IndexRepository repository = mock(IndexRepository.class);
        CandidateIndexStore store = mock(CandidateIndexStore.class);
        CandidateIndexStore.CandidatePoint foreign = point(UUID.fromString("018f0f70-8e10-7b14-8f1a-999999999999"), VERSION);

        assertThatThrownBy(() -> new CandidateIndexService(repository, store).build(request(foreign)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("boundary");
        verify(repository, never()).createVersion(any());
        verify(store, never()).createCollection(any(), eq(3));
    }

    private static CandidateIndexService.BuildRequest request(CandidateIndexStore.CandidatePoint point) {
        return new CandidateIndexService.BuildRequest(SPACE, VERSION, 1, "profile-v1", "p4-default-v1", 1, 3,
                List.of(point), Instant.parse("2026-08-21T00:00:00Z"));
    }

    private static CandidateIndexStore.CandidatePoint point(UUID space, UUID version) {
        return new CandidateIndexStore.CandidatePoint(UUID.randomUUID(), space, version, REVISION, PARENT,
                "s3://space/revision/child-0", HASH, List.of(0.1, 0.2, 0.3));
    }

    private static IndexRepository.IndexVersion version(IndexState state) {
        return new IndexRepository.IndexVersion(VERSION, SPACE, 1, state,
                CandidateIndexService.collectionFor(SPACE, VERSION), "profile-v1", "p4-default-v1", 1, 1,
                null, null, null, null, Instant.parse("2026-08-21T00:00:00Z"));
    }

    private static final class FakeStore implements CandidateIndexStore {
        private final ValidationResult validation;
        private String createdCollection;

        private FakeStore(ValidationResult validation) {
            this.validation = validation;
        }

        @Override
        public void createCollection(String collectionName, int dimension) {
            createdCollection = collectionName;
        }

        @Override
        public void upsert(String collectionName, List<CandidatePoint> points) {
        }

        @Override
        public ValidationResult validate(String collectionName, UUID spaceId, UUID indexVersionId,
                int expectedPointCount, int expectedDimension, List<CandidatePoint> samples) {
            return validation;
        }

        @Override
        public List<CandidateHit> search(String collectionName, UUID spaceId, UUID indexVersionId,
                List<Double> queryVector, int limit) {
            return new ArrayList<>();
        }

        @Override
        public void deleteCollection(String collectionName) {
        }
    }
}
