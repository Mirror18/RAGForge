package com.ragforge.server.run;

import com.ragforge.server.provider.adapter.CancellationToken;
import com.ragforge.server.provider.adapter.EgressDecision;
import com.ragforge.server.provider.adapter.ProviderAdapter;
import com.ragforge.server.provider.adapter.ProviderAdapterException;
import com.ragforge.server.provider.adapter.ProviderChatRequest;
import com.ragforge.server.provider.adapter.ProviderChatResponse;
import com.ragforge.server.provider.adapter.ProviderConnection;
import com.ragforge.server.provider.adapter.ProviderErrorClass;
import com.ragforge.server.provider.adapter.ProviderType;
import com.ragforge.server.provider.adapter.ProviderUsage;
import com.ragforge.server.provider.adapter.UsageSource;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Deterministic local adapter for the first no-RAG execution slice. */
@Component
@Profile("test")
public class FakeProviderAdapter implements ProviderAdapter {
    @Override
    public ProviderType providerType() {
        return ProviderType.AI_RUNTIME;
    }

    @Override
    public CompletionStage<ProviderChatResponse> chat(ProviderConnection connection,
                                                       EgressDecision egressDecision,
                                                       ProviderChatRequest request,
                                                       CancellationToken cancellationToken) {
        if (cancellationToken.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new ProviderAdapterException(
                    ProviderErrorClass.CANCELLED, "Fake provider request cancelled", request.identity().requestId(), 0));
        }
        String userText = request.messages().get(request.messages().size() - 1).content();
        if (userText.contains("__fake_error__")) {
            return CompletableFuture.failedFuture(new ProviderAdapterException(
                    ProviderErrorClass.INVALID_RESPONSE, "Fake provider failure", request.identity().requestId(), 500));
        }
        String content = "fake completion";
        ProviderUsage usage = new ProviderUsage((long) userText.length(), (long) content.length(),
                (long) userText.length() + content.length(), UsageSource.PROVIDER_REPORTED);
        return CompletableFuture.completedFuture(new ProviderChatResponse(
                request.identity(), request.model(), content, "stop", usage, "fake-response"));
    }
}
