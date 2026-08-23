package com.ragforge.server.provider.adapter;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ProviderChatRequest(
        UUID spaceId,
        RequestIdentity identity,
        String model,
        List<ChatMessage> messages,
        Duration timeout,
        Integer maxOutputTokens,
        Set<ModelCapability> requiredCapabilities,
        boolean stream,
        Set<String> citationTokenAllowList) {

    public ProviderChatRequest {
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(requiredCapabilities, "requiredCapabilities");
        Objects.requireNonNull(citationTokenAllowList, "citationTokenAllowList");
        if (model.isBlank() || model.length() > 200) {
            throw new IllegalArgumentException("Provider model is invalid");
        }
        if (messages.isEmpty() || messages.size() > 512) {
            throw new IllegalArgumentException("Provider chat message count is invalid");
        }
        messages = List.copyOf(messages);
        requiredCapabilities = Set.copyOf(requiredCapabilities);
        citationTokenAllowList = Set.copyOf(citationTokenAllowList);
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Provider timeout must be positive");
        }
        if (maxOutputTokens != null && maxOutputTokens < 1) {
            throw new IllegalArgumentException("Maximum output tokens must be positive");
        }
    }

    public ProviderChatRequest(UUID spaceId, RequestIdentity identity, String model,
                               List<ChatMessage> messages, Duration timeout) {
        this(spaceId, identity, model, messages, timeout, null, Set.of(ModelCapability.CHAT), false, Set.of());
    }

    public ProviderChatRequest(UUID spaceId, RequestIdentity identity, String model,
                               List<ChatMessage> messages, Duration timeout, Integer maxOutputTokens,
                               Set<ModelCapability> requiredCapabilities, boolean stream) {
        this(spaceId, identity, model, messages, timeout, maxOutputTokens, requiredCapabilities, stream, Set.of());
    }

    @Override
    public String toString() {
        return "ProviderChatRequest[spaceId=%s, identity=%s, model=%s, messageCount=%d, timeout=%s, maxOutputTokens=%s, stream=%s, citationTokenAllowListSize=%d]"
                .formatted(spaceId, identity, model, messages.size(), timeout, maxOutputTokens, stream,
                        citationTokenAllowList.size());
    }
}
