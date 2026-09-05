package com.ragforge.server.run;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryRunEventStoreTest {
    private static final UUID SPACE_ID = UUID.randomUUID();
    private static final UUID OTHER_SPACE_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID CORRELATION_ID = UUID.randomUUID();

    @Test
    void assignsMonotonicSequenceAndReplaysFromNPlusOne() {
        InMemoryRunEventStore store = new InMemoryRunEventStore(1000);
        List<RunEvent> published = IntStream.rangeClosed(1, 5)
                .mapToObj(index -> store.append(event("step.status", "{\"index\":%d}".formatted(index))))
                .toList();

        assertThat(published).extracting(RunEvent::sequence).containsExactly(1L, 2L, 3L, 4L, 5L);

        RunEventStore.ReplayResult replay = store.replay(SPACE_ID, RUN_ID,
                published.get(2).eventId().toString());

        assertThat(replay.cursorStatus()).isEqualTo(RunEventStore.CursorStatus.AVAILABLE);
        assertThat(replay.events()).extracting(RunEvent::sequence).containsExactly(4L, 5L);
    }

    @Test
    void expiredCursorReturnsSnapshotRecoveryInsteadOfPartialReplay() {
        InMemoryRunEventStore store = new InMemoryRunEventStore(2);
        store.append(event("run.status", "{\"status\":\"RUNNING\"}"));
        store.append(event("answer.delta", "{\"text\":\"a\"}"));
        store.append(event("answer.delta", "{\"text\":\"b\"}"));

        RunEventStore.ReplayResult replay = store.replay(SPACE_ID, RUN_ID, "1");

        assertThat(replay.cursorStatus()).isEqualTo(RunEventStore.CursorStatus.UNAVAILABLE);
        assertThat(replay.cursorExpired()).isTrue();
        assertThat(replay.events()).isEmpty();
        assertThat(replay.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.reason()).isEqualTo("cursor_expired");
            assertThat(snapshot.latestSequence()).isEqualTo(3);
            assertThat(snapshot.earliestSequence()).isEqualTo(2);
        });
    }

    @Test
    void cancelIsIdempotentAndPreventsAnswerDelta() {
        InMemoryRunEventStore store = new InMemoryRunEventStore();
        store.append(event("run.status", "{\"status\":\"RUNNING\"}"));

        RunEventStore.CancellationResult first = store.cancel(SPACE_ID, RUN_ID, CORRELATION_ID);
        RunEventStore.CancellationResult duplicate = store.cancel(SPACE_ID, RUN_ID, UUID.randomUUID());

        assertThat(first.firstCancellation()).isTrue();
        assertThat(duplicate.firstCancellation()).isFalse();
        assertThat(duplicate.event()).isEqualTo(first.event());
        assertThatThrownBy(() -> store.append(event("answer.delta", "{\"text\":\"late\"}")))
                .isInstanceOf(RunCancelledException.class);
        assertThat(store.replay(SPACE_ID, RUN_ID, null).events())
                .extracting(RunEvent::type).containsExactly("run.status", "run.status");
    }

    @Test
    void isolatesEventsBySpace() {
        InMemoryRunEventStore store = new InMemoryRunEventStore();
        RunEvent event = store.append(event("answer.delta", "{\"text\":\"private\"}"));

        assertThat(store.replay(OTHER_SPACE_ID, RUN_ID, null).events()).isEmpty();
        assertThat(store.find(OTHER_SPACE_ID, RUN_ID, event.eventId())).isEmpty();
    }

    @Test
    void twentyConcurrentPublishingChainsHaveNoDuplicateSequence() throws Exception {
        InMemoryRunEventStore store = new InMemoryRunEventStore(1000);
        int chains = 20;
        int eventsPerChain = 25;
        var executor = Executors.newFixedThreadPool(chains);
        CountDownLatch ready = new CountDownLatch(chains);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int chain = 0; chain < chains; chain++) {
            int chainNumber = chain;
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                for (int index = 0; index < eventsPerChain; index++) {
                    store.append(event("answer.delta", "{\"chain\":%d,\"index\":%d}"
                            .formatted(chainNumber, index)));
                }
                return null;
            }));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (var future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        List<Long> sequences = store.replay(SPACE_ID, RUN_ID, null).events().stream()
                .map(RunEvent::sequence).toList();
        assertThat(sequences).hasSize(chains * eventsPerChain);
        assertThat(Set.copyOf(sequences)).hasSize(sequences.size());
        assertThat(sequences).containsExactlyElementsOf(IntStream.rangeClosed(1, chains * eventsPerChain)
                .asLongStream().boxed().toList());
    }

    @Test
    void liveSubscriptionReceivesEventsPublishedAfterReplay() {
        InMemoryRunEventStore store = new InMemoryRunEventStore();
        RunEvent first = store.append(event("run.status", "{\"status\":\"RUNNING\"}"));
        List<RunEvent> received = new ArrayList<>();

        RunEventStore.OpenedStream opened = store.openStream(SPACE_ID, RUN_ID, first.eventId().toString(),
                received::add);
        assertThat(opened.replay().events()).isEmpty();
        opened.subscription().activate();
        RunEvent second = store.append(event("answer.delta", "{\"text\":\"live\"}"));

        assertThat(received).containsExactly(second);
        opened.subscription().close();
    }

    @Test
    void rejectsSensitivePayloadKeysRecursively() {
        for (String key : List.of("secret", "apiKey", "accessToken", "documentContent", "fullText",
                "rawDocument", "password")) {
            assertThatThrownBy(() -> store().append(event("run.status", "{\"outer\":{\"%s\":\"x\"}}"
                    .formatted(key))))
                    .isInstanceOf(SensitivePayloadException.class)
                    .hasMessageContaining(key);
        }
    }

    private InMemoryRunEventStore store() {
        return new InMemoryRunEventStore();
    }

    private RunEventDraft event(String type, String payloadJson) {
        return new RunEventDraft(RUN_ID, SPACE_ID, CORRELATION_ID, type, 1, payloadJson);
    }
}
