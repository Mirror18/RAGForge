package com.ragforge.server.index;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Minimal official Qdrant REST surface used by the candidate-index boundary. */
@Component
public final class QdrantCandidateIndex implements CandidateIndexStore {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final URI endpoint;
    private final String apiKey;
    private final HttpClient http;
    private final ObjectMapper objectMapper;

    @Autowired
    public QdrantCandidateIndex(
            @Value("${ragforge.qdrant.url:http://localhost:6333}") String endpoint,
            @Value("${ragforge.qdrant.api-key:}") String apiKey,
            ObjectMapper objectMapper) {
        this(URI.create(endpoint), apiKey, HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(), objectMapper);
    }

    public QdrantCandidateIndex(URI endpoint, ObjectMapper objectMapper) {
        this(endpoint, "", HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(), objectMapper);
    }

    QdrantCandidateIndex(URI endpoint, String apiKey, HttpClient http, ObjectMapper objectMapper) {
        this.endpoint = normalizeEndpoint(endpoint);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.http = Objects.requireNonNull(http, "http");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public void createCollection(String collectionName, int dimension) {
        validateCollectionName(collectionName);
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive");
        }
        Map<String, Object> vectors = Map.of("size", dimension, "distance", "Cosine");
        try {
            send("PUT", "/collections/" + collectionName, Map.of("vectors", vectors));
        } catch (QdrantException exception) {
            if (exception.statusCode() != 409) {
                throw exception;
            }
            int existingDimension = collectionDimension(collectionName);
            if (existingDimension != dimension) {
                throw new IllegalStateException("candidate collection exists with a different vector dimension");
            }
        }
    }

    @Override
    public void upsert(String collectionName, List<CandidatePoint> points) {
        validateCollectionName(collectionName);
        Objects.requireNonNull(points, "points");
        if (points.isEmpty()) {
            return;
        }
        List<Map<String, Object>> encoded = new ArrayList<>(points.size());
        for (CandidatePoint point : points) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("space_id", point.spaceId().toString());
            payload.put("index_version_id", point.indexVersionId().toString());
            payload.put("document_revision_id", point.documentRevisionId().toString());
            payload.put("parent_chunk_id", point.parentChunkId().toString());
            payload.put("content_ref", point.contentRef());
            payload.put("text_hash", point.textHash().toLowerCase(java.util.Locale.ROOT));
            encoded.add(Map.of("id", point.id().toString(), "vector", point.vector(), "payload", payload));
        }
        send("PUT", "/collections/" + collectionName + "/points?wait=true", Map.of("points", encoded));
    }

    @Override
    public ValidationResult validate(String collectionName, UUID spaceId, UUID indexVersionId,
            int expectedPointCount, int expectedDimension, List<CandidatePoint> samples) {
        requireScope(spaceId, indexVersionId);
        int count = count(collectionName, spaceId, indexVersionId);
        int dimension = collectionDimension(collectionName);
        boolean spaceFilterPassed = true;
        boolean samplePassed = count == expectedPointCount && dimension == expectedDimension;
        for (CandidatePoint sample : samples) {
            if (!spaceId.equals(sample.spaceId()) || !indexVersionId.equals(sample.indexVersionId())) {
                throw new IllegalArgumentException("validation sample is outside the requested scope");
            }
            List<CandidateHit> hits = search(collectionName, spaceId, indexVersionId, sample.vector(), 10);
            boolean found = hits.stream().anyMatch(hit -> hit.id().equals(sample.id()));
            spaceFilterPassed &= hits.stream().allMatch(hit -> spaceId.equals(hit.spaceId())
                    && indexVersionId.equals(hit.indexVersionId()));
            samplePassed &= found;
        }
        return new ValidationResult(count, dimension, 0, samplePassed, spaceFilterPassed);
    }

    @Override
    public List<CandidateHit> search(String collectionName, UUID spaceId, UUID indexVersionId,
            List<Double> queryVector, int limit) {
        validateCollectionName(collectionName);
        requireScope(spaceId, indexVersionId);
        if (queryVector == null || queryVector.isEmpty() || limit <= 0 || limit > 100) {
            throw new IllegalArgumentException("query vector and limit are invalid");
        }
        Map<String, Object> body = Map.of(
                "vector", queryVector,
                "limit", limit,
                "with_payload", true,
                "filter", scopeFilter(spaceId, indexVersionId));
        JsonNode results = send("POST", "/collections/" + collectionName + "/points/search", body)
                .path("result");
        List<CandidateHit> hits = new ArrayList<>();
        for (JsonNode result : results) {
            JsonNode payload = result.path("payload");
            hits.add(new CandidateHit(
                    UUID.fromString(result.path("id").asText()),
                    result.path("score").asDouble(),
                    UUID.fromString(payload.path("space_id").asText()),
                    UUID.fromString(payload.path("index_version_id").asText()),
                    UUID.fromString(payload.path("document_revision_id").asText()),
                    UUID.fromString(payload.path("parent_chunk_id").asText()),
                    payload.path("content_ref").asText(),
                    payload.path("text_hash").asText()));
        }
        return List.copyOf(hits);
    }

    @Override
    public void deleteCollection(String collectionName) {
        validateCollectionName(collectionName);
        try {
            send("DELETE", "/collections/" + collectionName, null);
        } catch (QdrantException exception) {
            if (exception.statusCode() != 404) {
                throw exception;
            }
        }
    }

    private int count(String collectionName, UUID spaceId, UUID indexVersionId) {
        return send("POST", "/collections/" + collectionName + "/points/count",
                Map.of("exact", true, "filter", scopeFilter(spaceId, indexVersionId)))
                .path("result").path("count").asInt(-1);
    }

    private int collectionDimension(String collectionName) {
        JsonNode vectors = send("GET", "/collections/" + collectionName, null)
                .path("result").path("config").path("params").path("vectors");
        int dimension = vectors.path("size").asInt(-1);
        if (dimension <= 0) {
            throw new IllegalStateException("Qdrant collection has no readable vector dimension");
        }
        return dimension;
    }

    private JsonNode send(String method, String path, Object body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint.resolve(path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json");
            if (!apiKey.isBlank()) {
                builder.header("api-key", apiKey);
            }
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new QdrantException(response.statusCode(), response.body());
            }
            return response.body().isBlank() ? objectMapper.createObjectNode()
                    : objectMapper.readTree(response.body());
        } catch (QdrantException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Qdrant request failed", exception);
        }
    }

    private static Map<String, Object> scopeFilter(UUID spaceId, UUID indexVersionId) {
        return Map.of("must", List.of(
                Map.of("key", "space_id", "match", Map.of("value", spaceId.toString())),
                Map.of("key", "index_version_id", "match", Map.of("value", indexVersionId.toString()))));
    }

    private static void requireScope(UUID spaceId, UUID indexVersionId) {
        if (spaceId == null || indexVersionId == null) {
            throw new IllegalArgumentException("spaceId and indexVersionId are required for candidate access");
        }
    }

    private static void validateCollectionName(String collectionName) {
        if (collectionName == null || !collectionName.matches("[A-Za-z0-9_-]{1,200}")) {
            throw new IllegalArgumentException("candidate collection name is invalid");
        }
    }

    private static URI normalizeEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        String value = endpoint.toString();
        return URI.create(value.endsWith("/") ? value.substring(0, value.length() - 1) : value);
    }

    static final class QdrantException extends RuntimeException {
        private final int statusCode;

        QdrantException(int statusCode, String body) {
            super("Qdrant returned HTTP " + statusCode + ": " + body);
            this.statusCode = statusCode;
        }

        int statusCode() {
            return statusCode;
        }
    }
}
