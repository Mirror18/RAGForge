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
        // Phase 5 generation is a typed JSON contract.  Send the contract to
        // Ollama as a JSON schema so small local models do not have to infer
        // the object shape from prompt prose alone.  The server still parses
        // and allow-lists every citation UUID after the provider response.
        if (request.citationTokenAllowList().isEmpty()) {
            root.put("format", "json");
        } else {
            root.set("format", answerContractSchema(request.citationTokenAllowList()));
        }
        // qwen3.5 exposes reasoning in a separate field; disable it for this
        // synchronous adapter so the typed answer is returned in message.content.
        root.put("think", false);
        root.put("stream", false);
        ObjectNode options = root.putObject("options");
        options.put("temperature", 0);
        options.put("seed", 0);
        if (request.maxOutputTokens() != null) {
            options.put("num_predict", request.maxOutputTokens());
        }
        return root;
    }

    private ObjectNode answerContractSchema(java.util.Set<String> citationTokenAllowList) {
        ObjectNode schema = objectMapper().createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("answer_text").put("type", "string").put("minLength", 1);
        ObjectNode claims = properties.putObject("claims");
        claims.put("type", "array").put("minItems", 1);
        ObjectNode claim = claims.putObject("items");
        claim.put("type", "object");
        ObjectNode claimProperties = claim.putObject("properties");
        claimProperties.putObject("claim_text").put("type", "string").put("minLength", 1);
        ObjectNode tokens = claimProperties.putObject("citation_tokens");
        tokens.put("type", "array").put("minItems", 1);
        ObjectNode tokenSchema = tokens.putObject("items").put("type", "string")
                .put("pattern", "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
        tokenSchema.putArray("enum").addAll(citationTokenAllowList.stream()
                .sorted().map(token -> objectMapper().getNodeFactory().textNode(token)).toList());
        claim.putArray("required").add("claim_text").add("citation_tokens");
        claim.put("additionalProperties", false);
        schema.putArray("required").add("answer_text").add("claims");
        schema.put("additionalProperties", false);
        return schema;
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

    @Override
    protected URI embeddingEndpoint(URI endpoint) { return ProviderEndpointPaths.ollamaEmbedding(endpoint); }

    @Override
    protected ObjectNode embeddingRequestBody(ProviderEmbeddingRequest request) {
        return objectMapper().createObjectNode().put("model", request.model()).put("prompt", request.input());
    }

    @Override
    protected ProviderEmbeddingResponse parseEmbeddingResponse(ProviderEmbeddingRequest request, String body) {
        try {
            JsonNode root = objectMapper().readTree(body);
            JsonNode values = root == null ? null : root.get("embedding");
            if (values == null || !values.isArray() || values.isEmpty()) throw invalidEmbeddingResponse(request);
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
