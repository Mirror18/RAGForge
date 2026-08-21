package com.ragforge.server.index;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

class QdrantCandidateIndexHttpTest {
    private static final UUID SPACE = UUID.fromString("018f0f70-8e10-7b14-8f1a-111111111111");
    private static final UUID VERSION = UUID.fromString("018f0f70-8e10-7b14-8f1a-222222222222");
    private static final UUID REVISION = UUID.fromString("018f0f70-8e10-7b14-8f1a-333333333333");
    private static final UUID PARENT = UUID.fromString("018f0f70-8e10-7b14-8f1a-444444444444");
    private static final UUID POINT = UUID.fromString("018f0f70-8e10-7b14-8f1a-555555555555");
    private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private HttpServer server;
    private AtomicReference<String> lastBody;
    private QdrantCandidateIndex client;

    @BeforeEach
    void startServer() throws IOException {
        lastBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        client = new QdrantCandidateIndex(
                URI.create("http://localhost:" + server.getAddress().getPort()), new ObjectMapper());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void candidateRequestsContainSpaceAndIndexFilters() {
        String collection = "candidate_test";
        client.createCollection(collection, 3);
        CandidateIndexStore.CandidatePoint point = new CandidateIndexStore.CandidatePoint(
                POINT, SPACE, VERSION, REVISION, PARENT, "s3://space/child-0", HASH, List.of(0.1, 0.2, 0.3));
        client.upsert(collection, List.of(point));
        List<CandidateIndexStore.CandidateHit> hits = client.search(collection, SPACE, VERSION, point.vector(), 5);

        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.id()).isEqualTo(POINT);
            assertThat(hit.spaceId()).isEqualTo(SPACE);
            assertThat(hit.indexVersionId()).isEqualTo(VERSION);
        });
        assertThat(lastBody.get()).contains(SPACE.toString(), VERSION.toString(), "space_id", "index_version_id");
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastBody.set(body);
        String path = exchange.getRequestURI().getPath();
        String response;
        if ("GET".equals(exchange.getRequestMethod())) {
            response = "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":3}}}}}";
        } else if (path.endsWith("/points/count")) {
            response = "{\"result\":{\"count\":1}}";
        } else if (path.endsWith("/points/search")) {
            response = "{\"result\":[{\"id\":\"" + POINT + "\",\"score\":0.99,\"payload\":{"
                    + "\"space_id\":\"" + SPACE + "\",\"index_version_id\":\"" + VERSION + "\","
                    + "\"document_revision_id\":\"" + REVISION + "\",\"parent_chunk_id\":\"" + PARENT
                    + "\",\"content_ref\":\"s3://space/child-0\",\"text_hash\":\"" + HASH + "\"}}]}";
        } else {
            response = "{\"result\":true}";
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
