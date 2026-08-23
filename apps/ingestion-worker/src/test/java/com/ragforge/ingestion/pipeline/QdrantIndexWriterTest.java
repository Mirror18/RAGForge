package com.ragforge.ingestion.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QdrantIndexWriterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> searchBodies = new ArrayList<>();
    private HttpServer server;
    private UUID expectedSpaceId;
    private UUID expectedIndexId;
    private UUID expectedPointId;

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
    void validatesFilteredSampleRetrievalAndRejectsForeignSpace() throws Exception {
        UUID spaceId = expectedSpaceId = UUID.randomUUID();
        UUID indexId = expectedIndexId = UUID.randomUUID();
        UUID pointId = expectedPointId = UUID.randomUUID();
        QdrantIndexWriter.Point point = new QdrantIndexWriter.Point(
                pointId, UUID.randomUUID(), UUID.randomUUID(), "opaque://chunk", "a".repeat(64),
                List.of(0.1, 0.2, 0.3));
        QdrantIndexWriter writer = new QdrantIndexWriter(mapper,
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-qdrant-key");

        writer.createAndUpsert("candidate", 3, spaceId, indexId, List.of(point));
        QdrantIndexWriter.Validation validation = writer.validateCandidate("candidate", spaceId, indexId, point);

        assertThat(validation.sampleRetrievalPassed()).isTrue();
        assertThat(validation.spaceFilterPassed()).isTrue();
        assertThat(searchBodies).hasSize(2);
        JsonNode scopedFilter = mapper.readTree(searchBodies.get(0)).path("filter").path("must");
        JsonNode foreignFilter = mapper.readTree(searchBodies.get(1)).path("filter").path("must");
        assertThat(scopedFilter.toString()).contains(spaceId.toString(), indexId.toString());
        assertThat(foreignFilter.toString()).doesNotContain(spaceId.toString());
    }

    private void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String response;
        if ("POST".equals(method) && path.endsWith("/points/search")) {
            searchBodies.add(body);
            boolean foreign = !body.contains("\"value\":\"" + expectedSpaceId + "\"");
            response = foreign ? "{\"result\":[]}" : "{\"result\":[{\"id\":\"" + expectedPointId
                    + "\",\"payload\":{\"space_id\":\"" + expectedSpaceId
                    + "\",\"index_version_id\":\"" + expectedIndexId + "\"}}]}";
        } else if ("GET".equals(method)) {
            response = "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":3}}}}}";
        } else {
            response = "{\"result\":true}";
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

}
