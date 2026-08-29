package com.ragforge.server.answer.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.answer.EvidenceBundleSnapshot;
import com.ragforge.server.answer.GenerationPort;
import com.ragforge.server.answer.GenerationStreamObserver;
import com.ragforge.server.provider.adapter.CancellationToken;
import com.ragforge.server.provider.adapter.EgressClass;
import com.ragforge.server.provider.adapter.EgressDecision;
import com.ragforge.server.provider.adapter.ModelCapability;
import com.ragforge.server.provider.adapter.ProviderAdapter;
import com.ragforge.server.provider.adapter.ProviderAdapterException;
import com.ragforge.server.provider.adapter.ProviderChatRequest;
import com.ragforge.server.provider.adapter.ProviderChatResponse;
import com.ragforge.server.provider.adapter.ProviderConnection;
import com.ragforge.server.provider.adapter.ProviderErrorClass;
import com.ragforge.server.provider.adapter.ProviderType;
import com.ragforge.server.provider.adapter.RequestIdentity;
import com.ragforge.server.run.ProviderAdapterRegistry;
import com.ragforge.server.retrieval.EvidenceBundle;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Phase5ProviderIntegrationTest {
    private static final UUID SPACE = UUID.fromString("018f0f70-8e10-7b14-8f1a-111111111111");
    private static final UUID RUN = UUID.fromString("018f0f70-8e10-7b14-8f1a-222222222222");
    private static final UUID CORRELATION = UUID.fromString("018f0f70-8e10-7b14-8f1a-333333333333");
    private static final UUID ROUTE = UUID.fromString("018f0f70-8e10-7b14-8f1a-555555555555");
    private static final UUID PROFILE = UUID.fromString("018f0f70-8e10-7b14-8f1a-666666666666");
    private static final UUID PROMPT = UUID.fromString("018f0f70-8e10-7b14-8f1a-777777777777");
    private static final UUID EVIDENCE = UUID.fromString("018f0f70-8e10-7b14-8f1a-888888888888");
    private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String TOKEN = "018f0f70-8e10-7b14-8f1a-999999999999";

    @Test
    void localOnlyRoutePassesSpaceIdentityAndEgressWithoutFallback() {
        RecordingAdapter adapter = new RecordingAdapter(ProviderType.OLLAMA,
                CompletableFuture.completedFuture(response("local-model", structuredAnswer())));
        ProviderBackedGenerationPort port = port(EgressClass.LOCAL, EgressDecision.LOCAL_ONLY, adapter,
                (space, route, profile, model, decision, correlation) -> route(EgressClass.LOCAL, decision));

        GenerationPort.GenerationResult result = port.generate(request(EgressDecision.LOCAL_ONLY),
                new CancellationToken()).toCompletableFuture().join();

        assertThat(result.egressDecision()).isEqualTo(EgressDecision.LOCAL_ONLY);
        assertThat(result.claims()).singleElement().extracting(GenerationPort.GeneratedClaim::citationTokens)
                .isEqualTo(List.of(TOKEN));
        assertThat(adapter.calls).hasValue(1);
        assertThat(adapter.lastRequest.spaceId()).isEqualTo(SPACE);
        assertThat(adapter.lastRequest.identity().requestId()).isEqualTo(RUN);
        assertThat(adapter.lastRequest.identity().correlationId()).isEqualTo(CORRELATION);
    }

    @Test
    void cloudAllowedIsExplicitAndLocalDecisionCannotSelectCloudRoute() {
        RecordingAdapter cloud = new RecordingAdapter(ProviderType.OPENAI_COMPATIBLE,
                CompletableFuture.completedFuture(response("local-model", structuredAnswer())));
        ProviderBackedGenerationPort cloudPort = port(EgressClass.CLOUD, EgressDecision.CLOUD_ALLOWED, cloud,
                (space, route, profile, model, decision, correlation) -> route(EgressClass.CLOUD, decision));
        assertThat(cloudPort.generate(request(EgressDecision.CLOUD_ALLOWED), new CancellationToken())
                .toCompletableFuture().join().egressDecision()).isEqualTo(EgressDecision.CLOUD_ALLOWED);

        AtomicInteger calls = new AtomicInteger();
        ProviderBackedGenerationPort noFallback = port(EgressClass.CLOUD, EgressDecision.CLOUD_ALLOWED, cloud,
                (space, route, profile, model, decision, correlation) -> {
                    calls.incrementAndGet();
                    return route(EgressClass.CLOUD, EgressDecision.CLOUD_ALLOWED);
                });
        assertThatThrownBy(() -> noFallback.generate(request(EgressDecision.LOCAL_ONLY), new CancellationToken())
                .toCompletableFuture().join())
                .hasRootCauseInstanceOf(ProviderAdapterException.class)
                .hasRootCauseMessage("Resolved generation route does not match the answer request");
        assertThat(calls).hasValue(1);
        assertThat(cloud.calls).hasValue(1);
    }

    @Test
    void staleOptionalProviderCharacterRangeIsDerivedFromClaimText() {
        RecordingAdapter adapter = new RecordingAdapter(ProviderType.OLLAMA,
                CompletableFuture.completedFuture(response("local-model", structuredAnswerWithStaleRange())));
        ProviderBackedGenerationPort port = port(EgressClass.LOCAL, EgressDecision.LOCAL_ONLY, adapter,
                (space, route, profile, model, decision, correlation) -> route(EgressClass.LOCAL, decision));

        GenerationPort.GeneratedClaim claim = port.generate(request(EgressDecision.LOCAL_ONLY), new CancellationToken())
                .toCompletableFuture().join().claims().getFirst();

        assertThat(claim.answerCharStart()).isNull();
        assertThat(claim.answerCharEnd()).isNull();
    }

    @Test
    void streamingProjectsOnlyDecodedAnswerTextAndRetainsFinalCitationValidation() {
        RecordingAdapter adapter = new RecordingAdapter(ProviderType.OLLAMA,
                CompletableFuture.completedFuture(response("local-model", structuredAnswer())),
                List.of("{\"answer_text\":\"Ver", "ified.\",\"claims\":[{\"claim_text\":\"Verified.\",",
                        "\"citation_tokens\":[\"" + TOKEN + "\"]}]}"));
        ProviderBackedGenerationPort port = port(EgressClass.LOCAL, EgressDecision.LOCAL_ONLY, adapter,
                (space, route, profile, model, decision, correlation) -> route(EgressClass.LOCAL, decision));
        List<String> deltas = new ArrayList<>();

        GenerationPort.GenerationResult result = port.generateStreaming(request(EgressDecision.LOCAL_ONLY),
                new CancellationToken(), new GenerationStreamObserver() {
                    @Override public UUID answerId() { return RUN; }
                    @Override public void onDelta(String delta) { deltas.add(delta); }
                }).toCompletableFuture().join();

        assertThat(String.join("", deltas)).isEqualTo("Verified.");
        assertThat(deltas).allMatch(delta -> !delta.contains("answer_text") && !delta.contains("citation_tokens"));
        assertThat(result.claims()).singleElement().extracting(GenerationPort.GeneratedClaim::citationTokens)
                .isEqualTo(List.of(TOKEN));
        assertThat(adapter.lastRequest.stream()).isTrue();
        assertThat(adapter.lastRequest.requiredCapabilities()).contains(ModelCapability.STREAMING);
    }

    @Test
    void providerFailureTimeoutAndCancellationRemainObservableAndDoNotFallback() {
        RecordingAdapter failure = new RecordingAdapter(ProviderType.OLLAMA,
                CompletableFuture.failedFuture(new ProviderAdapterException(ProviderErrorClass.UNAVAILABLE,
                        "provider unavailable", RUN, 503)));
        ProviderBackedGenerationPort failedPort = port(EgressClass.LOCAL, EgressDecision.LOCAL_ONLY, failure,
                (space, route, profile, model, decision, correlation) -> route(EgressClass.LOCAL, decision));
        assertThatThrownBy(() -> failedPort.generate(request(EgressDecision.LOCAL_ONLY), new CancellationToken())
                .toCompletableFuture().join())
                .hasRootCauseInstanceOf(ProviderAdapterException.class)
                .hasRootCauseMessage("provider unavailable");

        RecordingAdapter never = new RecordingAdapter(ProviderType.OLLAMA, new CompletableFuture<>());
        ProviderBackedGenerationPort timeoutPort = new ProviderBackedGenerationPort(
                (space, route, profile, model, decision, correlation) -> route(EgressClass.LOCAL, decision),
                registry(never), new ObjectMapper(), Duration.ofMillis(20), Phase5IntegrationObserver.noop());
        assertThatThrownBy(() -> timeoutPort.generate(request(EgressDecision.LOCAL_ONLY), new CancellationToken())
                .toCompletableFuture().join())
                .hasRootCauseInstanceOf(ProviderAdapterException.class)
                .hasRootCauseMessage("Provider generation timed out");

        CancellationToken cancellation = new CancellationToken();
        cancellation.cancel();
        assertThatThrownBy(() -> failedPort.generate(request(EgressDecision.LOCAL_ONLY), cancellation)
                .toCompletableFuture().join())
                .hasRootCauseInstanceOf(ProviderAdapterException.class)
                .hasRootCauseMessage("Answer generation was cancelled");
    }

    @Test
    void unconfiguredPortsFailClosedInsteadOfReturningSyntheticValues() {
        Phase5IntegrationObserver.Decision[] decision = new Phase5IntegrationObserver.Decision[1];
        Phase5IntegrationObserver observer = value -> decision[0] = value;
        QueryEmbeddingAssertions.assertRejected(new FailClosedQueryEmbeddingProvider(observer));
        assertThat(decision[0].outcome()).isEqualTo("REJECTED");
        assertThat(decision[0].reason()).isEqualTo("UNCONFIGURED");
        assertThatThrownBy(() -> new FailClosedRetrievalPort(observer).retrieve(
                new com.ragforge.server.answer.RetrievalPort.RetrievalRequest(SPACE, RUN, CORRELATION,
                        "question", List.of(0.1)), new CancellationToken()))
                .isInstanceOf(ProviderAdapterException.class)
                .hasMessage("Retrieval route is not configured");
        assertThatThrownBy(() -> new FailClosedGenerationPort(observer).generate(request(EgressDecision.LOCAL_ONLY),
                new CancellationToken()).toCompletableFuture().join())
                .hasRootCauseMessage("Generation route is not configured");
    }

    private static ProviderBackedGenerationPort port(EgressClass egressClass, EgressDecision decision,
                                                      RecordingAdapter adapter, ProviderRouteResolver resolver) {
        return new ProviderBackedGenerationPort(resolver, registry(adapter), new ObjectMapper(),
                Duration.ofSeconds(1), Phase5IntegrationObserver.noop());
    }

    private static ProviderAdapterRegistry registry(RecordingAdapter adapter) {
        return new ProviderAdapterRegistry(List.of(adapter));
    }

    private static ProviderRouteResolver.ResolvedRoute route(EgressClass egressClass, EgressDecision decision) {
        return new ProviderRouteResolver.ResolvedRoute(SPACE, ROUTE, PROFILE, "local-model",
                new ProviderConnection(SPACE, PROFILE, 1,
                        egressClass == EgressClass.LOCAL ? ProviderType.OLLAMA : ProviderType.OPENAI_COMPATIBLE,
                        egressClass, URI.create("http://provider.invalid"), "test-ref", "NONE"),
                egressClass == EgressClass.LOCAL ? ProviderType.OLLAMA : ProviderType.OPENAI_COMPATIBLE, decision);
    }

    private static GenerationPort.GenerationRequest request(EgressDecision decision) {
        return new GenerationPort.GenerationRequest(SPACE, RUN, CORRELATION, "phase5-key-0000001",
                "What is stable?", new com.ragforge.server.answer.RagPromptPort.VersionedRagPrompt(
                        PROMPT, SPACE, "rag-answer", 1, "Answer with JSON.", "opaque:rag-answer-v1", HASH),
                "Answer with JSON.\n<ragforge_evidence>" + TOKEN + "</ragforge_evidence>",
                snapshot(), "local-model", ROUTE, PROFILE, decision);
    }

    private static EvidenceBundleSnapshot snapshot() {
        EvidenceBundle bundle = new EvidenceBundle(SPACE, ROUTE, PROFILE, 1, "q", "q",
                List.of(new EvidenceBundle.Evidence(UUID.fromString(TOKEN), SPACE, ROUTE, PROMPT, PROFILE,
                        EVIDENCE, "content:phase5", HASH,
                        new EvidenceBundle.Anchor(List.of("section"), 0, 2, 0, 10,
                                null, null, null, null, null, null), .9, .8, .7, .6, "direct-hit")), false, null);
        return new EvidenceBundleSnapshot(UUID.fromString("018f0f70-8e10-7b14-8f1a-aaaaaaaaaaaa"), 1, HASH,
                "evidence:phase5", bundle, List.of(new EvidenceBundleSnapshot.EvidenceMaterial(
                        UUID.fromString(TOKEN), "verified material")), HASH, HASH);
    }

    private static String structuredAnswer() {
        return "{\"answer_text\":\"Verified.\",\"claims\":[{\"claim_text\":\"Verified.\",\"citation_tokens\":[\""
                + TOKEN + "\"]}]}";
    }

    private static String structuredAnswerWithStaleRange() {
        return "{\"answer_text\":\"Verified.\",\"claims\":[{\"claim_text\":\"Verified.\","
                + "\"citation_tokens\":[\"" + TOKEN + "\"],\"answer_char_start\":99,\"answer_char_end\":108}]}";
    }

    private static ProviderChatResponse response(String model, String content) {
        return new ProviderChatResponse(new RequestIdentity(RUN, CORRELATION, "phase5-key-0000001"), model,
                content, "stop", null, "provider-response");
    }

    private static final class RecordingAdapter implements ProviderAdapter {
        private final ProviderType type;
        private final CompletionStage<ProviderChatResponse> response;
        private final AtomicInteger calls = new AtomicInteger();
        private ProviderChatRequest lastRequest;
        private final List<String> streamChunks;

        private RecordingAdapter(ProviderType type, CompletionStage<ProviderChatResponse> response) {
            this(type, response, List.of());
        }

        private RecordingAdapter(ProviderType type, CompletionStage<ProviderChatResponse> response,
                                 List<String> streamChunks) {
            this.type = type;
            this.response = response;
            this.streamChunks = List.copyOf(streamChunks);
        }

        @Override
        public ProviderType providerType() {
            return type;
        }

        @Override
        public CompletionStage<ProviderChatResponse> chat(ProviderConnection connection,
                                                           EgressDecision egressDecision,
                                                           ProviderChatRequest request,
                                                           CancellationToken cancellationToken) {
            calls.incrementAndGet();
            lastRequest = request;
            return response;
        }

        @Override
        public CompletionStage<ProviderChatResponse> chatStream(ProviderConnection connection,
                                                                 EgressDecision egressDecision,
                                                                 ProviderChatRequest request,
                                                                 CancellationToken cancellationToken,
                                                                 Consumer<String> deltaConsumer) {
            calls.incrementAndGet();
            lastRequest = request;
            streamChunks.forEach(deltaConsumer);
            return response;
        }
    }

    private static final class QueryEmbeddingAssertions {
        private QueryEmbeddingAssertions() {
        }

        private static void assertRejected(com.ragforge.server.answer.QueryEmbeddingProvider provider) {
            assertThatThrownBy(() -> provider.embed(
                    new com.ragforge.server.answer.QueryEmbeddingProvider.EmbeddingRequest(
                            SPACE, RUN, CORRELATION, "question"), EgressDecision.LOCAL_ONLY,
                    new CancellationToken()))
                    .isInstanceOf(ProviderAdapterException.class)
                    .hasMessage("Query embedding route is not configured");
        }
    }
}
