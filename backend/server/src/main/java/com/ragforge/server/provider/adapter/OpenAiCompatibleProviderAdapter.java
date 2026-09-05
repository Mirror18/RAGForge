package com.ragforge.server.provider.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class OpenAiCompatibleProviderAdapter extends AbstractHttpProviderAdapter {
    public OpenAiCompatibleProviderAdapter(ObjectMapper objectMapper, CredentialResolver credentialResolver) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), objectMapper, credentialResolver);
    }

    public OpenAiCompatibleProviderAdapter(HttpClient httpClient, ObjectMapper objectMapper,
                                           CredentialResolver credentialResolver) {
        super(httpClient, objectMapper, credentialResolver);
    }

    @Override
    public ProviderType providerType() {
        return ProviderType.OPENAI_COMPATIBLE;
    }

    @Override
    protected URI chatEndpoint(URI endpoint) {
        return ProviderEndpointPaths.openAiChat(endpoint);
    }

    @Override
    protected ObjectNode requestBody(ProviderChatRequest request) {
        ObjectNode root = objectMapper().createObjectNode();
        root.put("model", request.model());
        ArrayNode messages = root.putArray("messages");
        request.messages().forEach(message -> {
            ObjectNode item = messages.addObject();
            item.put("role", message.role());
            item.put("content", message.content());
        });
        root.put("stream", request.stream());
        if (request.stream()) {
            root.putObject("stream_options").put("include_usage", true);
        }
        if (request.maxOutputTokens() != null) {
            root.put("max_tokens", request.maxOutputTokens());
        }
        return root;
    }

    @Override
    protected ProviderChatResponse parseResponse(ProviderChatRequest request, String body) {
        try {
            JsonNode root = objectMapper().readTree(body);
            JsonNode choices = root == null ? null : root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                throw invalidResponse(request);
            }
            JsonNode choice = choices.get(0);
            JsonNode message = choice == null ? null : choice.get("message");
            if (message == null || !message.isObject()
                    || !message.has("content") || !message.get("content").isTextual()) {
                throw invalidResponse(request);
            }
            JsonNode usageNode = root.get("usage");
            ProviderUsage usage = usage(usageNode, "prompt_tokens", "completion_tokens", "total_tokens", request);
            String model = textOrNull(root, "model");
            if (model == null || model.isBlank()) {
                model = request.model();
            }
            String finishReason = choice.has("finish_reason") && choice.get("finish_reason").isTextual()
                    ? choice.get("finish_reason").textValue() : null;
            return new ProviderChatResponse(request.identity(), model, message.get("content").textValue(),
                    finishReason, usage, textOrNull(root, "id"));
        } catch (ProviderAdapterException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidResponse(request);
        }
    }

    @Override
    protected ChatStreamChunk parseStreamLine(ProviderChatRequest request, String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return null;
        if (!trimmed.startsWith("data:")) return null;
        String value = trimmed.substring(5).trim();
        if ("[DONE]".equals(value)) return new ChatStreamChunk("", null, null, null, null, true);
        try {
            JsonNode root = objectMapper().readTree(value);
            JsonNode choices = root == null ? null : root.path("choices");
            String delta = "";
            String finishReason = null;
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode choice = choices.get(0);
                JsonNode content = choice.path("delta").path("content");
                if (content.isTextual()) delta = content.textValue();
                if (choice.path("finish_reason").isTextual()) finishReason = choice.path("finish_reason").textValue();
            }
            ProviderUsage usage = usage(root == null ? null : root.get("usage"),
                    "prompt_tokens", "completion_tokens", "total_tokens", request);
            return new ChatStreamChunk(delta, textOrNull(root, "model"), finishReason, usage,
                    textOrNull(root, "id"), false);
        } catch (ProviderAdapterException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidResponse(request);
        }
    }

    @Override
    protected URI embeddingEndpoint(URI endpoint) { return ProviderEndpointPaths.openAiEmbedding(endpoint); }

    @Override
    protected ObjectNode embeddingRequestBody(ProviderEmbeddingRequest request) {
        return objectMapper().createObjectNode().put("model", request.model()).put("input", request.input());
    }

    @Override
    protected ProviderEmbeddingResponse parseEmbeddingResponse(ProviderEmbeddingRequest request, String body) {
        try {
            JsonNode root = objectMapper().readTree(body);
            JsonNode data = root == null ? null : root.get("data");
            if (data == null || !data.isArray() || data.isEmpty() || !data.get(0).has("embedding")) {
                throw invalidEmbeddingResponse(request);
            }
            JsonNode values = data.get(0).get("embedding");
            if (!values.isArray() || values.isEmpty()) throw invalidEmbeddingResponse(request);
            List<Double> embedding = new ArrayList<>();
            for (JsonNode value : values) {
                if (!value.isNumber() || !Double.isFinite(value.doubleValue())) throw invalidEmbeddingResponse(request);
                embedding.add(value.doubleValue());
            }
            return new ProviderEmbeddingResponse(request.identity(), request.model(), embedding, null);
        } catch (ProviderAdapterException exception) { throw exception; }
        catch (Exception exception) { throw invalidEmbeddingResponse(request); }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || !node.get(field).isTextual()) {
            return null;
        }
        return node.get(field).textValue();
    }
}
