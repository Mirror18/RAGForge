package com.ragforge.server.answer.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.answer.GenerationPort;
import com.ragforge.server.provider.adapter.CancellationToken;
import com.ragforge.server.provider.adapter.ChatMessage;
import com.ragforge.server.provider.adapter.ModelCapability;
import com.ragforge.server.provider.adapter.ProviderAdapterException;
import com.ragforge.server.provider.adapter.ProviderErrorClass;
import com.ragforge.server.provider.adapter.ProviderChatRequest;
import com.ragforge.server.provider.adapter.ProviderChatResponse;
import com.ragforge.server.provider.adapter.ProviderAdapter;
import com.ragforge.server.provider.adapter.RequestIdentity;
import com.ragforge.server.provider.adapter.EgressDecision;
import com.ragforge.server.provider.adapter.EgressPolicy;
import com.ragforge.server.run.ProviderAdapterRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Explicit bridge from the answer generation port to the existing provider
 * adapter registry. It sends one exact route and rejects unstructured output;
 * it never selects another candidate or changes the egress decision.
 */
public final class ProviderBackedGenerationPort implements GenerationPort {
    private static final int MAX_PROVIDER_OUTPUT_BYTES = 1_000_000;

    private final ProviderRouteResolver routes;
    private final ProviderAdapterRegistry adapters;
    private final ObjectMapper objectMapper;
    private final Phase5IntegrationObserver observer;
    private final Duration defaultTimeout;

    public ProviderBackedGenerationPort(ProviderRouteResolver routes, ProviderAdapterRegistry adapters,
                                        ObjectMapper objectMapper) {
        this(routes, adapters, objectMapper, Duration.ofSeconds(120), Phase5IntegrationObserver.noop());
    }

    public ProviderBackedGenerationPort(ProviderRouteResolver routes, ProviderAdapterRegistry adapters,
                                        ObjectMapper objectMapper, Duration defaultTimeout,
                                        Phase5IntegrationObserver observer) {
        this.routes = Objects.requireNonNull(routes, "routes");
        this.adapters = Objects.requireNonNull(adapters, "adapters");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout");
        if (defaultTimeout.isNegative() || defaultTimeout.isZero()) {
            throw new IllegalArgumentException("defaultTimeout must be positive");
        }
        this.observer = observer == null ? Phase5IntegrationObserver.noop() : observer;
    }

    @Override
    public CompletionStage<GenerationResult> generate(GenerationRequest request,
                                                      CancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        if (cancellationToken.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new ProviderAdapterException(ProviderErrorClass.CANCELLED,
                    "Answer generation was cancelled", request.correlationId(), 0));
        }
        final ProviderRouteResolver.ResolvedRoute route;
        final ProviderAdapter adapter;
        try {
            route = routes.resolve(request.spaceId(), request.modelRouteVersionId(),
                    request.modelProfileVersionId(), request.model(), request.egressDecision(),
                    request.correlationId());
            if (!request.spaceId().equals(route.spaceId())
                    || route.egressDecision() != request.egressDecision()
                    || !request.model().equals(route.model())
                    || route.providerType() != route.connection().providerType()) {
                throw new ProviderAdapterException(ProviderErrorClass.SPACE_EGRESS_DENIED,
                        "Resolved generation route does not match the answer request", request.correlationId(), 0, false);
            }
            EgressPolicy.validateConnection(request.spaceId(), request.egressDecision(), route.connection());
            adapter = adapters.require(route.providerType());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        Duration timeout = defaultTimeout;
        ProviderChatRequest providerRequest;
        try {
            providerRequest = new ProviderChatRequest(request.spaceId(),
                    new RequestIdentity(request.runId(), request.correlationId(), request.idempotencyKey()),
                    route.model(), List.of(new ChatMessage("system", request.renderedPrompt()),
                            new ChatMessage("user", request.query())), timeout, null,
                    java.util.Set.of(ModelCapability.CHAT), false);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new ProviderAdapterException(ProviderErrorClass.INVALID_RESPONSE,
                    "Generation request could not be prepared", request.correlationId(), 0, false));
        }
        CompletionStage<ProviderChatResponse> response;
        try {
            response = adapter.chat(route.connection(), request.egressDecision(), providerRequest, cancellationToken);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        CompletableFuture<ProviderChatResponse> providerFuture = response.toCompletableFuture();
        cancellationToken.onCancel(() -> providerFuture.cancel(true));
        return providerFuture.orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .handle((value, failure) -> {
            if (failure != null) {
                Throwable cause = failure instanceof java.util.concurrent.CompletionException
                        && failure.getCause() != null ? failure.getCause() : failure;
                if (cancellationToken.isCancellationRequested()) {
                    throw new ProviderAdapterException(ProviderErrorClass.CANCELLED,
                            "Answer generation was cancelled", request.correlationId(), 0);
                }
                if (cause instanceof java.util.concurrent.TimeoutException) {
                    throw new ProviderAdapterException(ProviderErrorClass.TIMEOUT,
                            "Provider generation timed out", request.correlationId(), 0);
                }
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new ProviderAdapterException(ProviderErrorClass.UNAVAILABLE,
                        "Provider generation failed", request.correlationId(), 0);
            }
            if (cancellationToken.isCancellationRequested()) {
                throw new ProviderAdapterException(ProviderErrorClass.CANCELLED,
                        "Answer generation was cancelled", request.correlationId(), 0);
            }
            if (value == null || value.identity() == null
                    || !request.runId().equals(value.identity().requestId())
                    || value.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PROVIDER_OUTPUT_BYTES) {
                throw new ProviderAdapterException(ProviderErrorClass.INVALID_RESPONSE,
                        "Provider response was invalid", request.correlationId(), 0, false);
            }
            GenerationResult result = parseStructuredResponse(value, request);
            observer.record(new Phase5IntegrationObserver.Decision(request.spaceId(), request.runId(),
                    request.correlationId(), "generation", "SUCCEEDED", "EXACT_ROUTE", request.egressDecision()));
            return result;
        });
    }

    private GenerationResult parseStructuredResponse(ProviderChatResponse response, GenerationRequest request) {
        try {
            JsonNode root = objectMapper.readTree(response.content());
            if (root == null || !root.isObject() || !root.path("answer_text").isTextual()
                    || root.path("answer_text").textValue().isBlank()
                    || !root.path("claims").isArray() || root.path("claims").isEmpty()) {
                throw invalid(request);
            }
            List<GeneratedClaim> claims = new ArrayList<>();
            for (JsonNode claim : root.path("claims")) {
                if (!claim.isObject() || !claim.path("claim_text").isTextual()
                        || claim.path("claim_text").textValue().isBlank()
                        || !claim.path("citation_tokens").isArray()
                        || claim.path("citation_tokens").isEmpty()) {
                    throw invalid(request);
                }
                List<String> tokens = new ArrayList<>();
                for (JsonNode token : claim.path("citation_tokens")) {
                    if (!token.isTextual() || !token.textValue().matches(
                            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[7][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")) {
                        throw invalid(request);
                    }
                    tokens.add(token.textValue());
                }
                Integer start = optionalInt(claim, "answer_char_start", request);
                Integer end = optionalInt(claim, "answer_char_end", request);
                claims.add(new GeneratedClaim(claim.path("claim_text").textValue(), tokens, start, end));
            }
            return new GenerationResult(root.path("answer_text").textValue(), claims,
                    response.model(), request.egressDecision());
        } catch (ProviderAdapterException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid(request);
        }
    }

    private static Integer optionalInt(JsonNode node, String field, GenerationRequest request) {
        if (!node.has(field)) {
            return null;
        }
        JsonNode value = node.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw invalid(request);
        }
        return value.intValue();
    }

    private static ProviderAdapterException invalid(GenerationRequest request) {
        return new ProviderAdapterException(ProviderErrorClass.INVALID_RESPONSE,
                "Provider response did not match the versioned answer schema", request.correlationId(), 0, false);
    }
}
