package com.ragforge.server.retrieval;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

/** Durable Qdrant payload survives provider reconstruction and remains scoped. */
class DurableBm25CandidateStoreTest {
    private static final UUID SPACE = UUID.fromString("018f0f70-8e10-7b14-8f1a-111111111111");
    private static final UUID FOREIGN_SPACE = UUID.fromString("018f0f70-8e10-7b14-8f1a-999999999999");
    private static final UUID INDEX = UUID.fromString("018f0f70-8e10-7b14-8f1a-222222222222");
    private static final UUID REVISION = UUID.fromString("018f0f70-8e10-7b14-8f1a-333333333333");
    private static final UUID PARENT = UUID.fromString("018f0f70-8e10-7b14-8f1a-444444444444");
    private static final UUID CHILD = UUID.fromString("018f0f70-8e10-7b14-8f1a-aaaaaaaaaaaa");
    private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private HttpServer server;
    private String lastScrollBody;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void restartRebuildsBm25FromDurableVersionedPayload() {
        DurableBm25CandidateStore first = newStore();
        List<RetrievalCandidate> beforeRestart = first.search(SPACE, INDEX, "durable lexical", 10);

        DurableBm25CandidateStore afterRestart = newStore();
        List<RetrievalCandidate> afterRebuild = afterRestart.search(SPACE, INDEX, "durable lexical", 10);

        assertThat(beforeRestart).extracting(RetrievalCandidate::childChunkId).containsExactly(CHILD);
        assertThat(afterRebuild).extracting(RetrievalCandidate::childChunkId).containsExactly(CHILD);
        assertThat(afterRebuild.get(0).searchableText()).contains("durable lexical");
        assertThat(lastScrollBody).contains(SPACE.toString(), INDEX.toString())
                .doesNotContain(FOREIGN_SPACE.toString());
    }

    private DurableBm25CandidateStore newStore() {
        return new DurableBm25CandidateStore(
                java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "", new ObjectMapper(),
                java.net.http.HttpClient.newHttpClient());
    }

    private void respond(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (exchange.getRequestURI().getPath().endsWith("/points/scroll")) {
            lastScrollBody = body;
            write(exchange, """
                    {"result":{"points":[{"id":"%s","payload":{"space_id":"%s","index_version_id":"%s",
                    "document_revision_id":"%s","parent_chunk_id":"%s","content_ref":"opaque://chunk",
                    "text_hash":"%s","text":"durable lexical state"}}]}}
                    """.formatted(CHILD, SPACE, INDEX, REVISION, PARENT, HASH).replace("\n", ""));
            return;
        }
        write(exchange, "{}");
    }

    private static void write(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
