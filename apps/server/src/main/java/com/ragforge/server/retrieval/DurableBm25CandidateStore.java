package com.ragforge.server.retrieval;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
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
import com.ragforge.server.index.CandidateIndexService;

/**
 * Qdrant-backed BM25 store. Qdrant points are the durable lexical facts: the
 * worker writes the validated, internal searchable text beside the versioned
 * candidate payload and this adapter rebuilds BM25 statistics on every search.
 */
@Component
public final class DurableBm25CandidateStore implements Bm25CandidateStore {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final int SCROLL_PAGE_SIZE = 1_000;

    private final URI endpoint;
    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    @Autowired
    public DurableBm25CandidateStore(
            @Value("${ragforge.qdrant.url:http://localhost:6333}") String endpoint,
            @Value("${ragforge.qdrant.api-key:}") String apiKey,
            ObjectMapper objectMapper) {
        this(URI.create(endpoint), apiKey, objectMapper,
                HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build());
    }

    DurableBm25CandidateStore(URI endpoint, String apiKey, ObjectMapper objectMapper, HttpClient http) {
        this.endpoint = normalizeEndpoint(endpoint);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.http = Objects.requireNonNull(http, "http");
    }

    /**
     * A worker-created point may receive a corrected durable payload without
     * changing its vector. This operation is still explicitly scoped by both
     * identifiers and fails if Qdrant rejects the update.
     */
    @Override
    public void upsert(Document document) {
        Objects.requireNonNull(document, "document");
        String collection = CandidateIndexService.collectionFor(document.spaceId(), document.indexVersionId());
        Map<String, Object> payload = Map.of(
                "space_id", document.spaceId().toString(),
                "index_version_id", document.indexVersionId().toString(),
                "document_revision_id", document.documentRevisionId().toString(),
                "parent_chunk_id", document.parentChunkId().toString(),
                "content_ref", document.contentRef(),
                "text_hash", document.textHash().toLowerCase(java.util.Locale.ROOT),
                "text", document.text());
        request("POST", "/collections/" + collection + "/points/payload?wait=true",
                Map.of("points", List.of(document.childChunkId().toString()), "payload", payload));
    }

    @Override
    public List<RetrievalCandidate> search(UUID spaceId, UUID indexVersionId, String query, int limit) {
        requireScope(spaceId, indexVersionId);
        if (query == null || query.isBlank() || limit <= 0 || limit > 100) {
            throw new IllegalArgumentException("BM25 query and limit are invalid");
        }
        List<Document> documents = scroll(spaceId, indexVersionId);
        List<String> queryTerms = terms(query);
        if (queryTerms.isEmpty() || documents.isEmpty()) {
            return List.of();
        }
        List<DocumentStats> stats = documents.stream()
                .map(document -> new DocumentStats(document, terms(document.text())))
                .toList();
        double averageLength = stats.stream().mapToInt(value -> value.terms().size()).average().orElse(1.0);
        List<RetrievalCandidate> candidates = new ArrayList<>();
        for (DocumentStats stat : stats) {
            Map<String, Integer> frequencies = frequencies(stat.terms());
            double score = 0.0;
            for (String queryTerm : queryTerms) {
                int termFrequency = frequencies.getOrDefault(queryTerm, 0);
                if (termFrequency == 0) {
                    continue;
                }
                long documentFrequency = stats.stream()
                        .filter(other -> other.terms().contains(queryTerm)).count();
                double inverseDocumentFrequency = Math.log(1.0
                        + (stats.size() - documentFrequency + 0.5) / (documentFrequency + 0.5));
                double lengthNorm = 1.2 * (1.0 - 0.75 + 0.75 * stat.terms().size() / averageLength);
                score += inverseDocumentFrequency * (termFrequency * 2.2) / (termFrequency + lengthNorm);
            }
            if (score > 0.0) {
                Document document = stat.document();
                candidates.add(new RetrievalCandidate(document.spaceId(), document.indexVersionId(),
                        document.childChunkId(), document.documentRevisionId(), document.parentChunkId(),
                        document.contentRef(), document.textHash(), score, RetrievalCandidate.Source.BM25,
                        document.text()));
            }
        }
        return candidates.stream()
                .sorted(Comparator.comparingDouble(RetrievalCandidate::sourceScore).reversed()
                        .thenComparing(candidate -> candidate.childChunkId().toString()))
                .limit(limit)
                .toList();
    }

    private List<Document> scroll(UUID spaceId, UUID indexVersionId) {
        String collection = CandidateIndexService.collectionFor(spaceId, indexVersionId);
        List<Document> documents = new ArrayList<>();
        JsonNode offset = null;
        while (true) {
            Map<String, Object> body = new HashMap<>();
            body.put("limit", SCROLL_PAGE_SIZE);
            body.put("with_payload", true);
            body.put("with_vector", false);
            body.put("filter", scopeFilter(spaceId, indexVersionId));
            if (offset != null) {
                body.put("offset", offset);
            }
            JsonNode result = request("POST", "/collections/" + collection + "/points/scroll", body)
                    .path("result");
            JsonNode points = result.path("points");
            if (!points.isArray()) {
                throw new IllegalStateException("Qdrant lexical rebuild returned no point list");
            }
            for (JsonNode point : points) {
                parseDocument(point, spaceId, indexVersionId).ifPresent(documents::add);
            }
            JsonNode next = result.path("next_page_offset");
            if (next.isMissingNode() || next.isNull() || next.asText().isBlank() || points.isEmpty()) {
                break;
            }
            if (next.equals(offset)) {
                throw new IllegalStateException("Qdrant lexical rebuild returned a repeated page offset");
            }
            offset = next;
        }
        return List.copyOf(documents);
    }

    private java.util.Optional<Document> parseDocument(JsonNode point, UUID spaceId, UUID indexVersionId) {
        JsonNode payload = point.path("payload");
        if (!spaceId.toString().equals(payload.path("space_id").asText())
                || !indexVersionId.toString().equals(payload.path("index_version_id").asText())) {
            return java.util.Optional.empty();
        }
        String text = payload.path("text").asText("");
        if (text.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(new Document(spaceId, indexVersionId,
                    UUID.fromString(point.path("id").asText()),
                    UUID.fromString(payload.path("document_revision_id").asText()),
                    UUID.fromString(payload.path("parent_chunk_id").asText()),
                    payload.path("content_ref").asText(), payload.path("text_hash").asText(), text));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Qdrant lexical payload identity is invalid", exception);
        }
    }

    private JsonNode request(String method, String path, Object body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint.resolve(path))
                    .timeout(REQUEST_TIMEOUT).header("Accept", "application/json");
            if (!apiKey.isBlank()) {
                builder.header("api-key", apiKey);
            }
            builder.header("Content-Type", "application/json").method(method,
                    HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Qdrant lexical index operation failed with HTTP " + response.statusCode());
            }
            return response.body().isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant lexical request interrupted", interrupted);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("Qdrant lexical index operation failed", exception);
        }
    }

    private static Map<String, Object> scopeFilter(UUID spaceId, UUID indexVersionId) {
        return Map.of("must", List.of(
                Map.of("key", "space_id", "match", Map.of("value", spaceId.toString())),
                Map.of("key", "index_version_id", "match", Map.of("value", indexVersionId.toString()))));
    }

    private static List<String> terms(String text) {
        List<String> result = new ArrayList<>();
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        StringBuilder token = new StringBuilder();
        for (int offset = 0; offset < lower.length();) {
            int codePoint = lower.codePointAt(offset);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                flushToken(result, token);
                result.add(new String(Character.toChars(codePoint)));
            } else if (Character.isLetterOrDigit(codePoint)) {
                token.appendCodePoint(codePoint);
            } else {
                flushToken(result, token);
            }
            offset += Character.charCount(codePoint);
        }
        flushToken(result, token);
        return result;
    }

    private static void flushToken(List<String> result, StringBuilder token) {
        if (!token.isEmpty()) {
            result.add(token.toString());
            token.setLength(0);
        }
    }

    private static Map<String, Integer> frequencies(List<String> terms) {
        Map<String, Integer> result = new HashMap<>();
        for (String term : terms) {
            result.merge(term, 1, Integer::sum);
        }
        return result;
    }

    private static void requireScope(UUID spaceId, UUID indexVersionId) {
        if (spaceId == null || indexVersionId == null) {
            throw new IllegalArgumentException("spaceId and indexVersionId are required for BM25 access");
        }
    }

    private static URI normalizeEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        String value = endpoint.toString();
        return URI.create(value.endsWith("/") ? value.substring(0, value.length() - 1) : value);
    }

    private record DocumentStats(Document document, List<String> terms) {
    }
}
