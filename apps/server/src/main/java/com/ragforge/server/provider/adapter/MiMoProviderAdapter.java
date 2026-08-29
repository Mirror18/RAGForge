package com.ragforge.server.provider.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/** Xiaomi MiMo's official OpenAI-compatible chat adapter. */
public final class MiMoProviderAdapter extends OpenAiCompatibleProviderAdapter {
    public MiMoProviderAdapter(ObjectMapper objectMapper, CredentialResolver credentialResolver) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), objectMapper,
                credentialResolver);
    }

    public MiMoProviderAdapter(HttpClient httpClient, ObjectMapper objectMapper,
                               CredentialResolver credentialResolver) {
        super(httpClient, objectMapper, credentialResolver);
    }

    @Override
    public ProviderType providerType() {
        return ProviderType.MIMO;
    }

    @Override
    protected String credentialHeaderName() {
        return "api-key";
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
        root.putObject("thinking").put("type", "disabled");
        root.putObject("response_format").put("type", "json_object");
        if (request.maxOutputTokens() != null) {
            root.put("max_completion_tokens", request.maxOutputTokens());
        }
        return root;
    }

    @Override
    protected URI chatEndpoint(URI endpoint) {
        return ProviderEndpointPaths.openAiChat(endpoint);
    }
}
