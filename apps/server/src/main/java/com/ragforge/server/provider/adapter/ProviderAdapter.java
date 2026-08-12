package com.ragforge.server.provider.adapter;

import java.util.concurrent.CompletionStage;

public interface ProviderAdapter {
    ProviderType providerType();

    CompletionStage<ProviderChatResponse> chat(
            ProviderConnection connection,
            EgressDecision egressDecision,
            ProviderChatRequest request,
            CancellationToken cancellationToken);

    default CompletionStage<ProviderChatResponse> chat(
            ProviderConnection connection,
            EgressDecision egressDecision,
            ProviderChatRequest request) {
        return chat(connection, egressDecision, request, new CancellationToken());
    }
}
