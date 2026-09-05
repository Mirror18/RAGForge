package com.ragforge.server.provider.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiRuntimeRerankProviderAdapterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID SPACE = UUID.fromString("018f0f70-8e10-7b14-8f1a-111111111111");
    private static final UUID FIRST = UUID.fromString("018f0f70-8e10-7b14-8f1a-222222222222");
    private static final UUID SECOND = UUID.fromString("018f0f70-8e10-7b14-8f1a-333333333333");
    private HttpServer server;
    private String responseBody;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/rerank", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void loopbackResponseRetainsCandidateIdentityAndMetadata() throws Exception {
        var payload = MAPPER.createObjectNode().put("model", "local-rerank-v1");
        payload.putArray("capabilities").add("RERANK");
        payload.putArray("results").addObject().put("candidate_id", FIRST.toString()).put("score", 0.91);
        responseBody = MAPPER.writeValueAsString(payload);

        ProviderRerankResponse response = adapter().rerank(connection(EgressClass.LOCAL), EgressDecision.LOCAL_ONLY,
                request(List.of(candidate(FIRST, "local retrieval"))), new CancellationToken())
                .toCompletableFuture().join();

        assertThat(response.modelName()).isEqualTo("local-rerank-v1");
        assertThat(response.capabilities()).contains(ModelCapability.RERANK);
        assertThat(response.candidates()).extracting(ProviderRerankResponse.ScoredCandidate::candidateId)
                .containsExactly(FIRST);
    }

    @Test
    void requestRejectsForeignSpaceAndDuplicateIdentity() {
        assertThatThrownBy(() -> new ProviderRerankRequest(SPACE, identity(), "model", "query",
                List.of(new ProviderRerankRequest.Candidate(UUID.randomUUID(), FIRST, "text")),
                java.time.Duration.ofSeconds(1), 1, Set.of(ModelCapability.RERANK)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderRerankRequest(SPACE, identity(), "model", "query",
                List.of(candidate(FIRST, "one"), candidate(FIRST, "two")),
                java.time.Duration.ofSeconds(1), 2, Set.of(ModelCapability.RERANK)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidResponseIdentityOrCapabilityFailsClosed() throws Exception {
        var payload = MAPPER.createObjectNode().put("model", "local-rerank-v1");
        payload.putArray("capabilities").add("CHAT");
        payload.putArray("results").addObject().put("candidate_id", SECOND.toString()).put("score", 0.91);
        responseBody = MAPPER.writeValueAsString(payload);

        ProviderAdapterException error = failure(adapter().rerank(connection(EgressClass.LOCAL),
                EgressDecision.LOCAL_ONLY, request(List.of(candidate(FIRST, "text"))), new CancellationToken()));

        assertThat(error.errorClass()).isEqualTo(ProviderErrorClass.INVALID_RESPONSE);
    }

    @Test
    void cloudEgressIsDeniedBeforeNetworkCall() {
        ProviderAdapterException error = failure(adapter().rerank(connection(EgressClass.CLOUD),
                EgressDecision.CLOUD_ALLOWED, request(List.of(candidate(FIRST, "text"))), new CancellationToken()));

        assertThat(error.errorClass()).isEqualTo(ProviderErrorClass.SPACE_EGRESS_DENIED);
    }

    @Test
    void oversizedResponseIsRejected() {
        responseBody = "{" + "\"model\":\"local-rerank-v1\",\"capabilities\":[\"RERANK\"],"
                + "\"results\":[{\"candidate_id\":\"" + FIRST + "\",\"score\":0}],"
                + "\"padding\":\"" + "x".repeat(70_000) + "\"}";

        ProviderAdapterException error = failure(adapter().rerank(connection(EgressClass.LOCAL),
                EgressDecision.LOCAL_ONLY, request(List.of(candidate(FIRST, "text"))), new CancellationToken()));

        assertThat(error.errorClass()).isEqualTo(ProviderErrorClass.INVALID_RESPONSE);
    }

    private AiRuntimeRerankProviderAdapter adapter() {
        return new AiRuntimeRerankProviderAdapter(HttpClient.newHttpClient(), MAPPER,
                (spaceId, credentialRef) -> "Bearer test");
    }

    private ProviderConnection connection(EgressClass egressClass) {
        return new ProviderConnection(SPACE, UUID.randomUUID(), 1, ProviderType.AI_RUNTIME, egressClass,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "cred.test", "BEARER");
    }

    private ProviderRerankRequest request(List<ProviderRerankRequest.Candidate> candidates) {
        return new ProviderRerankRequest(SPACE, identity(), "local-rerank-v1", "local retrieval", candidates,
                java.time.Duration.ofSeconds(2), candidates.size(), Set.of(ModelCapability.RERANK));
    }

    private static ProviderRerankRequest.Candidate candidate(UUID id, String text) {
        return new ProviderRerankRequest.Candidate(SPACE, id, text);
    }

    private static RequestIdentity identity() {
        return new RequestIdentity(UUID.randomUUID(), UUID.randomUUID(), null);
    }

    private void handle(HttpExchange exchange) {
        try {
            byte[] body = responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } catch (IOException ignored) {
        } finally {
            exchange.close();
        }
    }

    private static ProviderAdapterException failure(java.util.concurrent.CompletionStage<?> stage) {
        try {
            stage.toCompletableFuture().join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof ProviderAdapterException providerException) {
                return providerException;
            }
            throw exception;
        }
        throw new AssertionError("Expected provider adapter failure");
    }
}
