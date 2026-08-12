package com.ragforge.server.run;

import com.ragforge.server.provider.adapter.CancellationToken;
import com.ragforge.server.provider.adapter.ChatMessage;
import com.ragforge.server.provider.adapter.EgressClass;
import com.ragforge.server.provider.adapter.EgressDecision;
import com.ragforge.server.provider.adapter.ModelCapability;
import com.ragforge.server.provider.adapter.ProviderChatRequest;
import com.ragforge.server.provider.adapter.ProviderChatResponse;
import com.ragforge.server.provider.adapter.ProviderConnection;
import com.ragforge.server.provider.adapter.ProviderType;
import com.ragforge.server.provider.adapter.RequestIdentity;
import com.ragforge.server.provider.adapter.UsageSource;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RunExecutionConcurrencyTest {
    private static final int CHAIN_COUNT = 20;

    @Test
    void twentyIndependentFakeCloudChainsKeepIdentitySequenceAndUsageDedupe() throws Exception {
        FakeProviderAdapter adapter = new FakeProviderAdapter();
        ConcurrentHashMap<UsageKey, ProviderUsageMarker> usageLedger = new ConcurrentHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(CHAIN_COUNT);
        CountDownLatch ready = new CountDownLatch(CHAIN_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ChainResult>> futures = java.util.stream.IntStream.range(0, CHAIN_COUNT)
                    .mapToObj(index -> executor.submit(() -> runChain(index, adapter, usageLedger, ready, start)))
                    .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ChainResult> chains = futures.stream().map(future -> get(future, 10)).toList();
            assertThat(chains).hasSize(CHAIN_COUNT);
            assertThat(chains.stream().map(ChainResult::runId).distinct()).hasSize(CHAIN_COUNT);
            assertThat(chains.stream().map(ChainResult::providerIdentity).distinct()).hasSize(CHAIN_COUNT);
            assertThat(chains).allSatisfy(chain -> {
                assertThat(chain.responseIdentity()).isEqualTo(chain.providerIdentity());
                assertThat(chain.sequences()).containsExactly(1L, 2L, 3L);
                assertThat(chain.eventTypes()).containsExactly("run.status", "run.status", "run.completed");
            });
            assertThat(usageLedger).hasSize(CHAIN_COUNT);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private ChainResult runChain(int index, FakeProviderAdapter adapter,
                                 ConcurrentHashMap<UsageKey, ProviderUsageMarker> usageLedger,
                                 CountDownLatch ready, CountDownLatch start) throws Exception {
        UUID spaceId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID providerConnectionId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        String providerIdentity = "run-" + runId;
        ProviderConnection connection = new ProviderConnection(spaceId, providerConnectionId, 1,
                ProviderType.AI_RUNTIME, EgressClass.CLOUD,
                URI.create("http://127.0.0.1:" + (18_000 + index)), "fake-ref");
        ProviderChatRequest request = new ProviderChatRequest(spaceId,
                new RequestIdentity(runId, correlationId, providerIdentity), "fake-model",
                List.of(new ChatMessage("user", "cloud-chain-" + index)), Duration.ofSeconds(5),
                256, Set.of(ModelCapability.CHAT), false);
        RunEventStore store = new InMemoryRunEventStore();
        ready.countDown();
        start.await(5, TimeUnit.SECONDS);
        ProviderChatResponse response = adapter.chat(connection, EgressDecision.CLOUD_ALLOWED, request,
                new CancellationToken()).toCompletableFuture().get(5, TimeUnit.SECONDS);

        store.append(runId, spaceId, correlationId, "run.status", 1, "{\"status\":\"QUEUED\"}");
        store.append(runId, spaceId, correlationId, "run.status", 1, "{\"status\":\"RUNNING\"}");
        store.append(runId, spaceId, correlationId, "run.completed", 1, "{\"outputHash\":\"safe\"}");
        UsageKey usageKey = new UsageKey(UUID.randomUUID(), UsageSource.PROVIDER_REPORTED, providerIdentity);
        usageLedger.putIfAbsent(usageKey, new ProviderUsageMarker(response.usage().totalTokens()));
        usageLedger.putIfAbsent(usageKey, new ProviderUsageMarker(response.usage().totalTokens() + 1));
        RunEventStore.ReplayResult replay = store.replay(spaceId, runId, null);
        return new ChainResult(runId, providerIdentity, response.identity().idempotencyKey(),
                replay.events().stream().map(RunEvent::sequence).toList(),
                replay.events().stream().map(RunEvent::type).toList());
    }

    private static ChainResult get(Future<ChainResult> future, int timeoutSeconds) {
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent fake chain failed", exception);
        }
    }

    private record UsageKey(UUID invocationId, UsageSource source, String dedupeKey) {
    }

    private record ProviderUsageMarker(Long totalTokens) {
    }

    private record ChainResult(UUID runId, String providerIdentity, String responseIdentity,
                               List<Long> sequences, List<String> eventTypes) {
    }
}
