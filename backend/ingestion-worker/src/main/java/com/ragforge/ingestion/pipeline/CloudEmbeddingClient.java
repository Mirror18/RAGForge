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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** OpenAI-compatible embedding client. Local Ollama is not a product path. */
@Component
public final class CloudEmbeddingClient {
    private final URI endpoint;
    private final String model;
    private final String apiKey;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public CloudEmbeddingClient(ObjectMapper mapper,
                                @Value("${ragforge.embedding.endpoint:}") String endpoint,
                                @Value("${ragforge.embedding.model:}") String model,
                                @Value("${ragforge.embedding.api-key:}") String apiKey) {
        this.endpoint = endpoint == null || endpoint.isBlank() ? null : URI.create(endpoint.trim());
        this.model = model == null ? "" : model.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.mapper = mapper;
    }

    public List<Double> embed(String text) {
        if (endpoint == null || isLocalOllama(endpoint) || model.isBlank()) {
            throw new IllegalStateException(
                    "Embedding requires a space-authorized OpenAI-compatible cloud endpoint");
        }
        try {
            String body = mapper.writeValueAsString(Map.of("model", model, "input", text));
            HttpRequest.Builder builder = HttpRequest.newBuilder(embeddingUri(endpoint))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (!apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("cloud embedding provider unavailable");
            }
            JsonNode values = mapper.readTree(response.body()).path("data").path(0).path("embedding");
            if (!values.isArray() || values.isEmpty()) {
                throw new IllegalStateException("cloud embedding response is invalid");
            }
            ArrayList<Double> result = new ArrayList<>();
            values.forEach(value -> {
                if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
                    throw new IllegalStateException("embedding value is invalid");
                }
                result.add(value.doubleValue());
            });
            return List.copyOf(result);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cloud embedding request interrupted", interrupted);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("cloud embedding request failed", exception);
        }
    }

    private static boolean isLocalOllama(URI endpoint) {
        String host = endpoint.getHost() == null ? "" : endpoint.getHost().toLowerCase(Locale.ROOT);
        int port = endpoint.getPort();
        return "127.0.0.1".equals(host) && (port == 11434 || port == -1 && endpoint.toString().contains(":11434"))
                || "localhost".equals(host) && port == 11434
                || host.contains("ollama");
    }

    private static URI embeddingUri(URI endpoint) {
        String path = endpoint.getPath() == null ? "" : endpoint.getPath();
        if (path.endsWith("/v1/embeddings") || path.endsWith("/embeddings")) {
            return endpoint;
        }
        String base = endpoint.toString().replaceAll("/$", "");
        if (base.endsWith("/v1")) {
            return URI.create(base + "/embeddings");
        }
        return URI.create(base + "/v1/embeddings");
    }
}
