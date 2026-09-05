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

@Component
public final class OllamaEmbeddingClient {
    private final URI endpoint;
    private final String model;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public OllamaEmbeddingClient(ObjectMapper mapper,
                                 @Value("${ragforge.ollama.endpoint:http://127.0.0.1:11434}") String endpoint,
                                 @Value("${ragforge.ollama.embedding-model:nomic-embed-text:latest}") String model) {
        this.endpoint = URI.create(endpoint.replaceAll("/$", "") + "/api/embeddings");
        this.model = model;
        this.mapper = mapper;
    }

    public List<Double> embed(String text) {
        try {
            String body = mapper.writeValueAsString(java.util.Map.of("model", model, "prompt", text));
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(90)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("local embedding provider unavailable");
            JsonNode values = mapper.readTree(response.body()).path("embedding");
            if (!values.isArray() || values.isEmpty()) throw new IllegalStateException("local embedding response is invalid");
            java.util.ArrayList<Double> result = new java.util.ArrayList<>();
            values.forEach(value -> { if (!value.isNumber() || !Double.isFinite(value.doubleValue())) throw new IllegalStateException("embedding value is invalid"); result.add(value.doubleValue()); });
            return List.copyOf(result);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("local embedding request interrupted", interrupted);
        } catch (Exception exception) {
            throw new IllegalStateException("local embedding request failed", exception);
        }
    }
}
