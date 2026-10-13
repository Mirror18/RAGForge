package com.ragforge.server.answer.infrastructure;

import com.ragforge.server.space.infrastructure.CancellationToken;
import com.ragforge.server.space.infrastructure.EgressDecision;
import com.ragforge.server.space.infrastructure.ProviderAdapter;
import com.ragforge.server.space.infrastructure.ProviderAdapterException;
import com.ragforge.server.space.infrastructure.ProviderChatRequest;
import com.ragforge.server.space.infrastructure.ProviderChatResponse;
import com.ragforge.server.space.infrastructure.ProviderConnection;
import com.ragforge.server.space.infrastructure.ProviderErrorClass;
import com.ragforge.server.space.infrastructure.ProviderType;
import com.ragforge.server.space.infrastructure.ProviderUsage;
import com.ragforge.server.space.infrastructure.UsageSource;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic OpenAI-compatible adapter for test-profile Chat runs. */
@Component
@Profile("test")
public class FakeProviderAdapter implements ProviderAdapter {
    private final ConcurrentHashMap<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    @Override
    public ProviderType providerType() {
        return ProviderType.OPENAI_COMPATIBLE;
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
        if (userText.contains("__fake_block__")) {
            CompletableFuture<ProviderChatResponse> blocked = new CompletableFuture<>();
            cancellationToken.onCancel(() -> blocked.completeExceptionally(new ProviderAdapterException(
                    ProviderErrorClass.CANCELLED, "Fake provider request cancelled", request.identity().requestId(), 0)));
            return blocked;
        }
        if (userText.contains("__fake_timeout_once__")
                && attempts.computeIfAbsent(userText, ignored -> new AtomicInteger()).incrementAndGet() == 1) {
            return CompletableFuture.failedFuture(new ProviderAdapterException(
                    ProviderErrorClass.TIMEOUT, "Fake provider timeout", request.identity().requestId(), 504));
        }
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
