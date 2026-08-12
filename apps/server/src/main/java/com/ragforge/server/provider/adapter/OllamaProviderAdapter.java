package com.ragforge.server.provider.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

public final class OllamaProviderAdapter extends AbstractHttpProviderAdapter {
    public OllamaProviderAdapter(ObjectMapper objectMapper, CredentialResolver credentialResolver) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), objectMapper, credentialResolver);
    }

    public OllamaProviderAdapter(HttpClient httpClient, ObjectMapper objectMapper,
                                 CredentialResolver credentialResolver) {
        super(httpClient, objectMapper, credentialResolver);
    }

    @Override
    public ProviderType providerType() {
        return ProviderType.OLLAMA;
    }

    @Override
    protected URI chatEndpoint(URI endpoint) {
        return ProviderEndpointPaths.ollamaChat(endpoint);
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
        root.put("stream", false);
        if (request.maxOutputTokens() != null) {
            root.putObject("options").put("num_predict", request.maxOutputTokens());
        }
        return root;
    }

    @Override
    protected ProviderChatResponse parseResponse(ProviderChatRequest request, String body) {
        try {
            JsonNode root = objectMapper().readTree(body);
            JsonNode message = root == null ? null : root.get("message");
            if (message == null || !message.isObject()
                    || !message.has("content") || !message.get("content").isTextual()) {
                throw invalidResponse(request);
            }
            String model = textOrNull(root, "model");
            if (model == null || model.isBlank()) {
                model = request.model();
            }
            Long prompt = optionalNonNegativeLong(root, "prompt_eval_count", request);
            Long completion = optionalNonNegativeLong(root, "eval_count", request);
            ProviderUsage usage = prompt == null && completion == null
                    ? null
                    : new ProviderUsage(prompt, completion,
                    prompt != null && completion != null ? prompt + completion : null,
                    UsageSource.PROVIDER_REPORTED);
            return new ProviderChatResponse(request.identity(), model, message.get("content").textValue(),
                    root.has("done_reason") && root.get("done_reason").isTextual()
                            ? root.get("done_reason").textValue() : null,
                    usage, textOrNull(root, "created_at"));
        } catch (ProviderAdapterException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidResponse(request);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || !node.get(field).isTextual()) {
            return null;
        }
        return node.get(field).textValue();
    }
}
