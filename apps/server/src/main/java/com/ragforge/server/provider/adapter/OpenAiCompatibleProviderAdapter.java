package com.ragforge.server.provider.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

public final class OpenAiCompatibleProviderAdapter extends AbstractHttpProviderAdapter {
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
        root.put("stream", false);
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

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || !node.get(field).isTextual()) {
            return null;
        }
        return node.get(field).textValue();
    }
}
