package com.ragforge.server.provider.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ProviderAdapterHttpTest {
    private static final UUID SPACE_ID = UUID.randomUUID();
    private static final UUID OTHER_SPACE_ID = UUID.randomUUID();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FAKE_AUTHORIZATION = "Bearer fake-test-header";
    private static final String PROMPT = "prompt-secret-for-test-only";

    private HttpServer server;
    private ExecutorService serverExecutor;
    private URI endpoint;
    private final AtomicReference<HandlerBehavior> behavior = new AtomicReference<>();
    private final AtomicReference<String> observedPath = new AtomicReference<>();
    private final AtomicReference<String> observedAuthorization = new AtomicReference<>();
    private final AtomicReference<String> observedRequestId = new AtomicReference<>();
    private final AtomicReference<String> observedBody = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newFixedThreadPool(12);
        server.setExecutor(serverExecutor);
        server.createContext("/", this::handle);
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        behavior.set((exchange, body) -> respond(exchange, 200,
                "{\"model\":\"test-model\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5}}"));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void ollamaUsesApiChatAndParsesUsageAndIdentity() throws Exception {
        behavior.set((exchange, body) -> respond(exchange, 200,
                "{\"model\":\"llama3\",\"created_at\":\"2026-08-13T00:00:00Z\",\"message\":{\"role\":\"assistant\",\"content\":\"ollama answer\"},\"done\":true,\"prompt_eval_count\":7,\"eval_count\":11}"));
        RequestIdentity identity = identity("ollama");
        ProviderChatResponse response = ollama().chat(connection(ProviderType.OLLAMA), EgressDecision.LOCAL_ONLY,
                request(identity), new CancellationToken()).toCompletableFuture().get(3, TimeUnit.SECONDS);

        assertThat(observedPath.get()).isEqualTo("/api/chat");
        assertThat(observedAuthorization.get()).isEqualTo(FAKE_AUTHORIZATION);
        assertThat(observedRequestId.get()).isEqualTo(identity.requestId().toString());
        assertThat(response.content()).isEqualTo("ollama answer");
        assertThat(response.identity()).isEqualTo(identity);
        assertThat(response.usage()).isEqualTo(new ProviderUsage(7L, 11L, 18L, UsageSource.PROVIDER_REPORTED));
        assertThat(observedBody.get()).contains(PROMPT).contains("\"stream\":false");
    }

    @Test
    void explicitNoneLocalOllamaSendsNoAuthorizationAndDoesNotResolveCredential() throws Exception {
        behavior.set((exchange, body) -> respond(exchange, 200,
                "{\"model\":\"qwen3.5:9b\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                        + "\"done\":true,\"prompt_eval_count\":3,\"eval_count\":2}"));
        AtomicInteger resolutions = new AtomicInteger();
        OllamaProviderAdapter adapter = new OllamaProviderAdapter(HttpClient.newHttpClient(), MAPPER,
                (spaceId, credentialRef) -> {
                    resolutions.incrementAndGet();
                    throw new AssertionError("NONE/local Ollama must not resolve a credential");
                });
        RequestIdentity identity = identity("ollama-none");

        ProviderChatResponse response = adapter.chat(connection(ProviderType.OLLAMA, endpoint, SPACE_ID,
                        EgressClass.LOCAL, "opaque.credential-ref", "NONE"), EgressDecision.LOCAL_ONLY,
                request(identity), new CancellationToken()).toCompletableFuture().get(3, TimeUnit.SECONDS);

        assertThat(resolutions).hasValue(0);
        assertThat(observedAuthorization.get()).isNull();
        assertThat(response.model()).isEqualTo("qwen3.5:9b");
        assertThat(response.usage()).isEqualTo(new ProviderUsage(3L, 2L, 5L, UsageSource.PROVIDER_REPORTED));
    }

    @Test
    void embeddingUsesTheExistingProviderConnectionAndParsesVector() throws Exception {
        behavior.set((exchange, body) -> respond(exchange, 200,
                "{\"model\":\"nomic-embed\",\"embedding\":[0.1, -0.2, 0.3]}"));
        RequestIdentity identity = identity("ollama-embedding");
        ProviderEmbeddingResponse response = ollama().embed(connection(ProviderType.OLLAMA), EgressDecision.LOCAL_ONLY,
                new ProviderEmbeddingRequest(SPACE_ID, identity, "nomic-embed", "question", Duration.ofSeconds(2)),
                new CancellationToken()).toCompletableFuture().get(3, TimeUnit.SECONDS);

        assertThat(observedPath.get()).isEqualTo("/api/embeddings");
        assertThat(response.identity()).isEqualTo(identity);
        assertThat(response.embedding()).containsExactly(0.1, -0.2, 0.3);
        assertThat(observedBody.get()).contains("question");
    }

    @Test
    void nonNoneLocalOllamaWithoutConfiguredCredentialIsRejectedBeforeHttpCall() {
        AtomicInteger resolutions = new AtomicInteger();
        OllamaProviderAdapter adapter = new OllamaProviderAdapter(HttpClient.newHttpClient(), MAPPER,
                (spaceId, credentialRef) -> {
                    resolutions.incrementAndGet();
                    throw new IllegalStateException("credential is not configured");
                });

        ProviderAdapterException error = failure(adapter.chat(connection(ProviderType.OLLAMA, endpoint, SPACE_ID,
                        EgressClass.LOCAL, "opaque.credential-ref", "BEARER"), EgressDecision.LOCAL_ONLY,
                request(identity("ollama-bearer-missing"))));

        assertThat(error.errorClass()).isEqualTo(ProviderErrorClass.AUTHENTICATION);
        assertThat(resolutions).hasValue(1);
        assertThat(requestCount).hasValue(0);
    }

    @Test
    void noneCloudOllamaDoesNotBypassCredentialResolution() {
        AtomicInteger resolutions = new AtomicInteger();
        OllamaProviderAdapter adapter = new OllamaProviderAdapter(HttpClient.newHttpClient(), MAPPER,
                (spaceId, credentialRef) -> {
                    resolutions.incrementAndGet();
                    throw new IllegalStateException("credential is not configured");
                });

        ProviderAdapterException error = failure(adapter.chat(connection(ProviderType.OLLAMA, endpoint, SPACE_ID,
                        EgressClass.CLOUD, "opaque.credential-ref", "NONE"), EgressDecision.CLOUD_ALLOWED,
                request(identity("ollama-none-cloud"))));

        assertThat(error.errorClass()).isEqualTo(ProviderErrorClass.AUTHENTICATION);
        assertThat(resolutions).hasValue(1);
        assertThat(requestCount).hasValue(0);
    }

    @Test
    void noneLocalOpenAiCompatibleDoesNotBypassCredentialResolution() {
        AtomicInteger resolutions = new AtomicInteger();
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter(HttpClient.newHttpClient(), MAPPER,
                (spaceId, credentialRef) -> {
                    resolutions.incrementAndGet();
                    throw new IllegalStateException("credential is not configured");
                });

        ProviderAdapterException error = failure(adapter.chat(connection(ProviderType.OPENAI_COMPATIBLE, endpoint,
                        SPACE_ID, EgressClass.LOCAL, "opaque.credential-ref", "NONE"), EgressDecision.LOCAL_ONLY,
                request(identity("openai-none-local"))));

        assertThat(error.errorClass()).isEqualTo(ProviderErrorClass.AUTHENTICATION);
        assertThat(resolutions).hasValue(1);
        assertThat(requestCount).hasValue(0);
    }

    @Test
    void openAiCompatibleUsesV1ChatCompletionsAndParsesUsage() throws Exception {
        behavior.set((exchange, body) -> respond(exchange, 200,
                "{\"id\":\"chatcmpl-test\",\"model\":\"gpt-test\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"openai answer\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":6,\"total_tokens\":10}}"));
        RequestIdentity identity = identity("openai");
        ProviderConnection connection = connection(ProviderType.OPENAI_COMPATIBLE, endpoint.resolve("/v1"));
        ProviderChatResponse response = openAi().chat(connection, EgressDecision.LOCAL_ONLY,
                request(identity), new CancellationToken()).toCompletableFuture().get(3, TimeUnit.SECONDS);

        assertThat(observedPath.get()).isEqualTo("/v1/chat/completions");
        assertThat(response.model()).isEqualTo("gpt-test");
        assertThat(response.providerResponseId()).isEqualTo("chatcmpl-test");
        assertThat(response.usage().totalTokens()).isEqualTo(10L);
    }

    @Test
    void localOnlyRejectsCloudCandidateWithoutCallingResolver() {
        RouteCandidate cloud = new RouteCandidate(SPACE_ID, UUID.randomUUID(), 1, EgressClass.CLOUD);
        assertThatThrownBy(() -> EgressPolicy.validateCandidates(SPACE_ID, EgressDecision.LOCAL_ONLY, List.of(cloud)))
                .isInstanceOfSatisfying(ProviderAdapterException.class,
                        error -> assertThat(error.errorClass()).isEqualTo(ProviderErrorClass.SPACE_EGRESS_DENIED));
    }

    @Test
    void candidateFromAnotherSpaceIsRejectedByPurePolicy() {
        RouteCandidate otherSpace = new RouteCandidate(OTHER_SPACE_ID, UUID.randomUUID(), 1, EgressClass.LOCAL);
        assertThatThrownBy(() -> EgressPolicy.validateCandidates(SPACE_ID, EgressDecision.CLOUD_ALLOWED, List.of(otherSpace)))
                .isInstanceOfSatisfying(ProviderAdapterException.class,
                        error -> assertThat(error.errorClass()).isEqualTo(ProviderErrorClass.SPACE_EGRESS_DENIED));
    }

    @Test
    void cloudAllowedReturnsExplicitCandidatesInPriorityOrder() {
        RouteCandidate cloud = new RouteCandidate(SPACE_ID, UUID.randomUUID(), 2, EgressClass.CLOUD);
        RouteCandidate local = new RouteCandidate(SPACE_ID, UUID.randomUUID(), 1, EgressClass.LOCAL);
        assertThat(EgressPolicy.validateCandidates(SPACE_ID, EgressDecision.CLOUD_ALLOWED, List.of(cloud, local)))
                .containsExactly(local, cloud);
    }

    @Test
    void adapterRejectsCrossSpaceConnectionBeforeCredentialResolution() {
        AtomicInteger resolutions = new AtomicInteger();
        OllamaProviderAdapter adapter = new OllamaProviderAdapter(MAPPER,
                (spaceId, credentialRef) -> {
                    resolutions.incrementAndGet();
                    return FAKE_AUTHORIZATION;
                });
        ProviderAdapterException error = failure(adapter.chat(connection(ProviderType.OLLAMA, endpoint, OTHER_SPACE_ID),
                EgressDecision.CLOUD_ALLOWED, request(identity("cross-space"))));
        assertThat(error.errorClass()).isEqualTo(ProviderErrorClass.SPACE_EGRESS_DENIED);
        assertThat(resolutions).hasValue(0);
    }

    @ParameterizedTest(name = "HTTP {0} maps to {1}")
    @MethodSource("httpFailures")
    void non2xxResponsesMapToStableErrorClasses(int status, ProviderErrorClass expected, String responseBody) {
        behavior.set((exchange, body) -> respond(exchange, status, responseBody));
        ProviderAdapterException error = failure(openAi().chat(connection(ProviderType.OPENAI_COMPATIBLE),
                EgressDecision.LOCAL_ONLY, request(identity("http-" + status))));
        assertThat(error.errorClass()).isEqualTo(expected);
        assertThat(error.getMessage()).doesNotContain(PROMPT).doesNotContain(FAKE_AUTHORIZATION);
        assertThat(error.providerStatus()).isEqualTo(status);
    }

    static Stream<Arguments> httpFailures() {
        return Stream.of(
                Arguments.of(401, ProviderErrorClass.AUTHENTICATION, "{\"error\":{\"message\":\"bad auth\"}}"),
                Arguments.of(403, ProviderErrorClass.AUTHENTICATION, "{\"error\":{\"message\":\"forbidden\"}}"),
                Arguments.of(404, ProviderErrorClass.MODEL_NOT_FOUND, "{\"error\":{\"code\":\"model_not_found\"}}"),
                Arguments.of(408, ProviderErrorClass.TIMEOUT, "{\"error\":{\"message\":\"request timed out\"}}"),
                Arguments.of(409, ProviderErrorClass.IDEMPOTENCY_CONFLICT, "{\"error\":{\"message\":\"idempotency conflict\"}}"),
                Arguments.of(413, ProviderErrorClass.CONTEXT_OVERFLOW, "{\"error\":{\"code\":\"context_length_exceeded\"}}"),
                Arguments.of(422, ProviderErrorClass.CONTENT_POLICY, "{\"error\":{\"code\":\"content_policy_violation\"}}"),
                Arguments.of(429, ProviderErrorClass.RATE_LIMIT, "{\"error\":{\"code\":\"rate_limit\"}}"),
                Arguments.of(429, ProviderErrorClass.QUOTA, "{\"error\":{\"code\":\"insufficient_quota\"}}"),
                Arguments.of(501, ProviderErrorClass.UNSUPPORTED_CAPABILITY, "{\"error\":{\"code\":\"unsupported\"}}"),
                Arguments.of(503, ProviderErrorClass.UNAVAILABLE, "{\"error\":{\"message\":\"upstream unavailable\"}}"),
                Arguments.of(400, ProviderErrorClass.INVALID_RESPONSE, "{\"error\":{\"message\":\"bad request\"}}"));
    }

    @Test
    void invalidProviderResponseIsClassifiedWithoutExposingPayload() {
        behavior.set((exchange, body) -> respond(exchange, 200,
                "{\"choices\":[],\"prompt\":\"" + PROMPT + "\"}"));
        ProviderAdapterException error = failure(openAi().chat(connection(ProviderType.OPENAI_COMPATIBLE),
                EgressDecision.LOCAL_ONLY, request(identity("invalid-response"))));
        assertThat(error.errorClass()).isEqualTo(ProviderErrorClass.INVALID_RESPONSE);
        assertThat(error.getMessage()).doesNotContain(PROMPT);
    }

    @Test
    void providerTimeoutIsClassified() {
        behavior.set((exchange, body) -> {
            try {
                Thread.sleep(350);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{}");
        });
        ProviderChatRequest shortRequest = new ProviderChatRequest(SPACE_ID, identity("transport-timeout"),
                "test-model", List.of(new ChatMessage("user", PROMPT)), Duration.ofMillis(50), null,
                Set.of(ModelCapability.CHAT), false);
        ProviderAdapterException error = failure(openAi().chat(connection(ProviderType.OPENAI_COMPATIBLE),
                EgressDecision.LOCAL_ONLY, shortRequest));
        assertThat(error.errorClass()).isEqualTo(ProviderErrorClass.TIMEOUT);
    }

    @Test
    void cancellationStopsInFlightRequestAndUsesCancelledClass() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        behavior.set((exchange, body) -> {
            requestStarted.countDown();
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{}");
        });
        CancellationToken token = new CancellationToken();
        CompletableFuture<ProviderChatResponse> call = openAi().chat(connection(ProviderType.OPENAI_COMPATIBLE),
                EgressDecision.LOCAL_ONLY, request(identity("cancel")), token).toCompletableFuture();
        assertThat(requestStarted.await(2, TimeUnit.SECONDS)).isTrue();
        token.cancel();
        ProviderAdapterException error = failure(call);
        assertThat(error.errorClass()).isEqualTo(ProviderErrorClass.CANCELLED);
    }

    @Test
    void providerMismatchAndUnsupportedStreamingAreRejected() {
        ProviderAdapterException mismatch = failure(ollama().chat(connection(ProviderType.OPENAI_COMPATIBLE),
                EgressDecision.LOCAL_ONLY, request(identity("mismatch"))));
        assertThat(mismatch.errorClass()).isEqualTo(ProviderErrorClass.UNSUPPORTED_CAPABILITY);

        ProviderChatRequest streaming = new ProviderChatRequest(SPACE_ID, identity("stream"), "test-model",
                List.of(new ChatMessage("user", PROMPT)), Duration.ofSeconds(1), null,
                Set.of(ModelCapability.CHAT, ModelCapability.STREAMING), true);
        ProviderAdapterException unsupported = failure(openAi().chat(connection(ProviderType.OPENAI_COMPATIBLE),
                EgressDecision.LOCAL_ONLY, streaming));
        assertThat(unsupported.errorClass()).isEqualTo(ProviderErrorClass.UNSUPPORTED_CAPABILITY);
    }

    @Test
    void concurrentCallsKeepRequestIdentityAndAuthorizationIndependent() {
        int calls = 20;
        behavior.set((exchange, body) -> respond(exchange, 200,
                "{\"id\":\"parallel\",\"model\":\"test-model\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"parallel answer\"}}]}"));
        OpenAiCompatibleProviderAdapter adapter = openAi();
        List<RequestIdentity> identities = new ArrayList<>();
        List<CompletableFuture<ProviderChatResponse>> futures = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            RequestIdentity identity = identity("parallel-" + i);
            identities.add(identity);
            futures.add(adapter.chat(connection(ProviderType.OPENAI_COMPATIBLE), EgressDecision.LOCAL_ONLY,
                    request(identity)).toCompletableFuture());
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        for (int i = 0; i < calls; i++) {
            ProviderChatResponse response = futures.get(i).join();
            assertThat(response.content()).isEqualTo("parallel answer");
            assertThat(response.identity()).isEqualTo(identities.get(i));
        }
        assertThat(requestCount).hasValue(calls);
    }

    @Test
    void connectionAndRequestStringFormsRedactSecretAndPrompt() {
        ProviderConnection connection = connection(ProviderType.OLLAMA);
        String rendered = connection + " " + request(identity("redaction"));
        assertThat(rendered).doesNotContain(FAKE_AUTHORIZATION).doesNotContain(PROMPT);
    }

    private OllamaProviderAdapter ollama() {
        return new OllamaProviderAdapter(HttpClient.newHttpClient(), MAPPER,
                (spaceId, credentialRef) -> {
                    assertThat(spaceId).isEqualTo(SPACE_ID);
                    assertThat(credentialRef).isEqualTo("cred.test");
                    return FAKE_AUTHORIZATION;
                });
    }

    private OpenAiCompatibleProviderAdapter openAi() {
        return new OpenAiCompatibleProviderAdapter(HttpClient.newHttpClient(), MAPPER,
                (spaceId, credentialRef) -> {
                    assertThat(spaceId).isEqualTo(SPACE_ID);
                    assertThat(credentialRef).isEqualTo("cred.test");
                    return FAKE_AUTHORIZATION;
                });
    }

    private ProviderConnection connection(ProviderType type) {
        return connection(type, endpoint, SPACE_ID);
    }

    private ProviderConnection connection(ProviderType type, URI connectionEndpoint) {
        return connection(type, connectionEndpoint, SPACE_ID);
    }

    private ProviderConnection connection(ProviderType type, URI connectionEndpoint, UUID spaceId) {
        return new ProviderConnection(spaceId, UUID.randomUUID(), 1, type, EgressClass.LOCAL,
                connectionEndpoint, "cred.test");
    }

    private ProviderConnection connection(ProviderType type, URI connectionEndpoint, UUID spaceId,
                                          EgressClass egressClass, String credentialRef, String authScheme) {
        return new ProviderConnection(spaceId, UUID.randomUUID(), 1, type, egressClass,
                connectionEndpoint, credentialRef, authScheme);
    }

    private ProviderChatRequest request(RequestIdentity identity) {
        return new ProviderChatRequest(SPACE_ID, identity, "test-model",
                List.of(new ChatMessage("user", PROMPT)), Duration.ofSeconds(2), null,
                Set.of(ModelCapability.CHAT), false);
    }

    private static RequestIdentity identity(String suffix) {
        return new RequestIdentity(UUID.randomUUID(), UUID.randomUUID(), "key-" + suffix);
    }

    private void handle(HttpExchange exchange) {
        requestCount.incrementAndGet();
        observedPath.set(exchange.getRequestURI().getPath());
        observedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        observedRequestId.set(exchange.getRequestHeaders().getFirst("X-RAGForge-Request-Id"));
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            observedBody.set(body);
            HandlerBehavior current = behavior.get();
            if (current != null) {
                current.respond(exchange, body);
            }
        } catch (IOException exception) {
            exchange.close();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException ignored) {
            // The client may have cancelled the request; the test asserts the client-side classification.
        } finally {
            exchange.close();
        }
    }

    private static ProviderAdapterException failure(java.util.concurrent.CompletionStage<?> stage) {
        try {
            stage.toCompletableFuture().join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ProviderAdapterException providerException) {
                return providerException;
            }
            throw exception;
        }
        throw new AssertionError("Expected provider adapter failure");
    }

    @FunctionalInterface
    private interface HandlerBehavior {
        void respond(HttpExchange exchange, String body);
    }
}
