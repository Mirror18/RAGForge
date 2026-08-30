package com.ragforge.server.provider.adapter;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface ProviderAdapter {
    ProviderType providerType();

    CompletionStage<ProviderChatResponse> chat(
            ProviderConnection connection,
            EgressDecision egressDecision,
            ProviderChatRequest request,
            CancellationToken cancellationToken);

    default CompletionStage<ProviderChatResponse> chatStream(
            ProviderConnection connection,
            EgressDecision egressDecision,
            ProviderChatRequest request,
            CancellationToken cancellationToken,
            Consumer<String> deltaConsumer) {
        return CompletableFuture.failedFuture(new ProviderAdapterException(
                ProviderErrorClass.UNSUPPORTED_CAPABILITY, "Provider streaming is not supported",
                request == null || request.identity() == null ? null : request.identity().requestId(), 0));
    }

    default CompletionStage<ProviderEmbeddingResponse> embed(
            ProviderConnection connection,
            EgressDecision egressDecision,
            ProviderEmbeddingRequest request,
            CancellationToken cancellationToken) {
        return CompletableFuture.failedFuture(new ProviderAdapterException(
                ProviderErrorClass.UNSUPPORTED_CAPABILITY, "Provider embedding is not supported",
                request == null || request.identity() == null ? null : request.identity().requestId(), 0));
    }

    default CompletionStage<ProviderRerankResponse> rerank(
            ProviderConnection connection,
            EgressDecision egressDecision,
            ProviderRerankRequest request,
            CancellationToken cancellationToken) {
        return CompletableFuture.failedFuture(new ProviderAdapterException(
                ProviderErrorClass.UNSUPPORTED_CAPABILITY, "Provider rerank is not supported",
                request == null || request.identity() == null ? null : request.identity().requestId(), 0));
    }

    default CompletionStage<ProviderChatResponse> chat(
            ProviderConnection connection,
            EgressDecision egressDecision,
            ProviderChatRequest request) {
        return chat(connection, egressDecision, request, new CancellationToken());
    }
}
