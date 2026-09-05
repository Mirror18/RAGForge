package com.ragforge.server.provider.adapter;

public record ProviderChatResponse(
        RequestIdentity identity,
        String model,
        String content,
        String finishReason,
        ProviderUsage usage,
        String providerResponseId) {

    public ProviderChatResponse {
        if (identity == null || model == null || content == null) {
            throw new IllegalArgumentException("Provider response is incomplete");
        }
    }

    @Override
    public String toString() {
        return "ProviderChatResponse[identity=%s, model=%s, content=<redacted>, finishReason=%s, usage=%s, providerResponseId=%s]"
                .formatted(identity, model, finishReason, usage, providerResponseId);
    }
}
