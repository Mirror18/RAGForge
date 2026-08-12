package com.ragforge.server.run;

import com.ragforge.server.common.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunEventServiceTest {
    private static final UUID SPACE_ID = UUID.randomUUID();
    private static final UUID OTHER_SPACE_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID CORRELATION_ID = UUID.randomUUID();

    @Test
    void crossSpaceReplayIsDeniedBeforeStoreAccess() {
        RunRepository repository = mock(RunRepository.class);
        RunEventStore store = mock(RunEventStore.class);
        when(repository.findRun(OTHER_SPACE_ID, RUN_ID)).thenReturn(Optional.empty());
        RunEventService service = new RunEventService(repository, store);

        assertThatThrownBy(() -> service.replay(OTHER_SPACE_ID, RUN_ID, null))
                .isInstanceOf(ApiException.class)
                .hasMessage("Run not found");
        verify(store, never()).replay(any(), any(), any());
    }

    @Test
    void cancellationTransitionsRunAndDelegatesToIdempotentStore() {
        RunRepository repository = mock(RunRepository.class);
        RunEventStore store = mock(RunEventStore.class);
        RunRepository.RunRecord running = run(RunRepository.RunStatus.RUNNING, 3);
        RunRepository.RunRecord cancelled = run(RunRepository.RunStatus.CANCELLED, 4);
        RunEvent event = new RunEvent(UUID.randomUUID(), 1, RUN_ID, SPACE_ID, CORRELATION_ID,
                Instant.now(), "run.status", 1, "{\"status\":\"CANCELLED\"}");
        when(repository.findRun(SPACE_ID, RUN_ID)).thenReturn(Optional.of(running));
        when(repository.transitionRun(eq(SPACE_ID), eq(RUN_ID), eq(RunRepository.RunStatus.CANCELLED),
                eq(RunRepository.ErrorClass.CANCELLED), eq("run_cancelled"), any(), eq(3L)))
                .thenReturn(cancelled);
        when(store.cancel(eq(SPACE_ID), eq(RUN_ID), eq(CORRELATION_ID)))
                .thenReturn(new RunEventStore.CancellationResult(true, event));
        RunEventService service = new RunEventService(repository, store);

        RunEventStore.CancellationResult result = service.cancel(SPACE_ID, RUN_ID, CORRELATION_ID);

        assertThat(result.firstCancellation()).isTrue();
        verify(repository).transitionRun(eq(SPACE_ID), eq(RUN_ID), eq(RunRepository.RunStatus.CANCELLED),
                eq(RunRepository.ErrorClass.CANCELLED), eq("run_cancelled"), any(), eq(3L));
        verify(store).cancel(SPACE_ID, RUN_ID, CORRELATION_ID);
    }

    @Test
    void noCursorOpensWithACompleteInitialSnapshotEvent() {
        RunRepository repository = mock(RunRepository.class);
        InMemoryRunEventStore store = new InMemoryRunEventStore();
        when(repository.findRun(SPACE_ID, RUN_ID)).thenReturn(Optional.of(run(RunRepository.RunStatus.RUNNING, 0)));
        RunEventService service = new RunEventService(repository, store);

        RunEventStore.OpenedStream opened = service.openStream(SPACE_ID, RUN_ID, null, ignored -> {
        });

        assertThat(opened.replay().events()).singleElement().satisfies(event -> {
            assertThat(event.sequence()).isEqualTo(1);
            assertThat(event.type()).isEqualTo("run.snapshot");
            assertThat(event.payloadJson()).contains("\"status\":\"RUNNING\"");
        });
        opened.subscription().close();
    }

    private RunRepository.RunRecord run(RunRepository.RunStatus status, long version) {
        return new RunRepository.RunRecord(RUN_ID, SPACE_ID, null, CORRELATION_ID,
                RunRepository.RequestKind.CHAT, status, null, null, null, null, null, null,
                Instant.now(), Instant.now(), Instant.now(), Instant.now(), version);
    }
}
