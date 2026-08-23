package com.ragforge.ingestion.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public final class QdrantIndexWriter {
    private final String endpoint;
    private final String apiKey;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public QdrantIndexWriter(ObjectMapper mapper, @Value("${ragforge.qdrant.url:http://127.0.0.1:6333}") String endpoint,
                             @Value("${ragforge.qdrant.api-key:}") String apiKey) {
        this.mapper = mapper;
        this.endpoint = endpoint.replaceAll("/$", "");
        this.apiKey = apiKey;
    }

    public void createAndUpsert(String collection, int dimension, UUID spaceId, UUID indexId,
                                List<Point> points) {
        request("PUT", "/collections/" + collection, Map.of("vectors", Map.of("size", dimension, "distance", "Cosine")), true);
        List<Map<String, Object>> encoded = points.stream().map(point -> Map.<String, Object>of(
                "id", point.id().toString(), "vector", point.vector(), "payload", Map.of(
                        "space_id", spaceId.toString(), "index_version_id", indexId.toString(),
                        "document_revision_id", point.revisionId().toString(), "parent_chunk_id", point.parentId().toString(),
                        "content_ref", point.contentRef(), "text_hash", point.textHash()))).toList();
        if (!encoded.isEmpty()) request("PUT", "/collections/" + collection + "/points?wait=true", Map.of("points", encoded), false);
        JsonNode info = request("GET", "/collections/" + collection, null, false);
        int actual = info.path("result").path("config").path("params").path("vectors").path("size").asInt(-1);
        if (actual != dimension) throw new IllegalStateException("candidate index dimension validation failed");
    }

    /**
     * Runs the same filtered search contract used by retrieval before an index
     * can be marked READY.  A collection that only accepts writes is not a
     * usable candidate index, and a search that ignores the space filter must
     * fail closed.
     */
    public Validation validateCandidate(String collection, UUID spaceId, UUID indexId, Point point) {
        JsonNode scoped = search(collection, spaceId, indexId, point.vector());
        boolean sampleRetrievalPassed = containsPoint(scoped, point, spaceId, indexId);

        UUID foreignSpaceId = UUID.nameUUIDFromBytes(
                ("ragforge-forbidden-space:" + spaceId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JsonNode foreign = search(collection, foreignSpaceId, indexId, point.vector());
        boolean spaceFilterPassed = sampleRetrievalPassed && foreign.path("result").isArray()
                && foreign.path("result").isEmpty();
        return new Validation(sampleRetrievalPassed, spaceFilterPassed);
    }

    private JsonNode search(String collection, UUID spaceId, UUID indexId, List<Double> vector) {
        Map<String, Object> filter = Map.of("must", List.of(
                Map.of("key", "space_id", "match", Map.of("value", spaceId.toString())),
                Map.of("key", "index_version_id", "match", Map.of("value", indexId.toString()))));
        return request("POST", "/collections/" + collection + "/points/search",
                Map.of("vector", vector, "limit", 1, "with_payload", true, "filter", filter), false);
    }

    private boolean containsPoint(JsonNode response, Point expected, UUID spaceId, UUID indexId) {
        JsonNode results = response.path("result");
        if (!results.isArray()) return false;
        for (JsonNode hit : results) {
            if (!expected.id().toString().equals(hit.path("id").asText())) continue;
            JsonNode payload = hit.path("payload");
            if (spaceId.toString().equals(payload.path("space_id").asText())
                    && indexId.toString().equals(payload.path("index_version_id").asText())) {
                return true;
            }
        }
        return false;
    }

    private JsonNode request(String method, String path, Object body, boolean allowConflict) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint + path)).timeout(Duration.ofSeconds(90));
            if (apiKey != null && !apiKey.isBlank()) builder.header("api-key", apiKey);
            if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type", "application/json").method(method,
                    HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 409 && allowConflict) return mapper.createObjectNode();
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("Qdrant candidate index operation failed");
            return response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant request interrupted", interrupted);
        } catch (Exception exception) {
            throw new IllegalStateException("Qdrant candidate index operation failed", exception);
        }
    }

    public record Point(UUID id, UUID revisionId, UUID parentId, String contentRef, String textHash, List<Double> vector) { }

    public record Validation(boolean sampleRetrievalPassed, boolean spaceFilterPassed) { }
}
