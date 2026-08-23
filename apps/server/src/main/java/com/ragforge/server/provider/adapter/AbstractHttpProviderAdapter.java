package com.ragforge.server.provider.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.List;
import java.util.ArrayList;

public abstract class AbstractHttpProviderAdapter implements ProviderAdapter {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final CredentialResolver credentialResolver;

    protected AbstractHttpProviderAdapter(HttpClient httpClient, ObjectMapper objectMapper,
                                          CredentialResolver credentialResolver) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.credentialResolver = credentialResolver;
    }

    protected final ObjectMapper objectMapper() {
        return objectMapper;
    }

    protected abstract URI chatEndpoint(URI endpoint);

    protected abstract ObjectNode requestBody(ProviderChatRequest request);

    protected abstract ProviderChatResponse parseResponse(ProviderChatRequest request, String body);

    protected abstract URI embeddingEndpoint(URI endpoint);

    protected abstract ObjectNode embeddingRequestBody(ProviderEmbeddingRequest request);

    protected abstract ProviderEmbeddingResponse parseEmbeddingResponse(ProviderEmbeddingRequest request, String body);

    protected Set<ModelCapability> supportedCapabilities() {
        return Set.of(ModelCapability.CHAT, ModelCapability.EMBEDDING, ModelCapability.USAGE_REPORTING);
    }

    /** Header used for the resolved opaque credential value. */
    protected String credentialHeaderName() {
        return "Authorization";
    }

    @Override
    public final java.util.concurrent.CompletionStage<ProviderChatResponse> chat(
            ProviderConnection connection,
            EgressDecision egressDecision,
            ProviderChatRequest request,
            CancellationToken cancellationToken) {
        try {
            validateCall(connection, egressDecision, request, cancellationToken);
            URI endpoint = chatEndpoint(connection.endpoint());
            String authorization = resolveAuthorization(connection, request);
            String body = objectMapper.writeValueAsString(requestBody(request));
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(request.timeout())
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("X-RAGForge-Request-Id", request.identity().requestId().toString())
                    .header("X-RAGForge-Correlation-Id", request.identity().correlationId().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (request.identity().idempotencyKey() != null) {
                builder.header("Idempotency-Key", request.identity().idempotencyKey());
            }
            if (authorization != null && !authorization.isBlank()) {
                if (authorization.indexOf('\r') >= 0 || authorization.indexOf('\n') >= 0) {
                    throw new ProviderAdapterException(ProviderErrorClass.AUTHENTICATION,
                            "Resolved authorization header is invalid", request.identity().requestId(), 0);
                }
                builder.header(credentialHeaderName(), authorization);
            }
            return send(builder.build(), request, cancellationToken);
        } catch (ProviderAdapterException exception) {
            return CompletableFuture.failedFuture(exception);
        } catch (IOException | RuntimeException exception) {
            return CompletableFuture.failedFuture(new ProviderAdapterException(
                    ProviderErrorClass.INVALID_RESPONSE, "Provider request could not be prepared",
                    request == null || request.identity() == null ? null : request.identity().requestId(), 0));
        }
    }

    @Override
    public final java.util.concurrent.CompletionStage<ProviderEmbeddingResponse> embed(
            ProviderConnection connection, EgressDecision egressDecision, ProviderEmbeddingRequest request,
            CancellationToken cancellationToken) {
        try {
            validateEmbeddingCall(connection, egressDecision, request, cancellationToken);
            String authorization = resolveAuthorization(connection, request.identity().requestId());
            String body = objectMapper.writeValueAsString(embeddingRequestBody(request));
            HttpRequest.Builder builder = HttpRequest.newBuilder(embeddingEndpoint(connection.endpoint()))
                    .timeout(request.timeout()).header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("X-RAGForge-Request-Id", request.identity().requestId().toString())
                    .header("X-RAGForge-Correlation-Id", request.identity().correlationId().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (authorization != null && !authorization.isBlank()) {
                if (authorization.indexOf('\r') >= 0 || authorization.indexOf('\n') >= 0) {
                    throw new ProviderAdapterException(ProviderErrorClass.AUTHENTICATION,
                            "Resolved authorization header is invalid", request.identity().requestId(), 0);
                }
                builder.header(credentialHeaderName(), authorization);
            }
            return sendEmbedding(builder.build(), request, cancellationToken);
        } catch (ProviderAdapterException exception) {
            return CompletableFuture.failedFuture(exception);
        } catch (IOException | RuntimeException exception) {
            return CompletableFuture.failedFuture(new ProviderAdapterException(
                    ProviderErrorClass.INVALID_RESPONSE, "Provider embedding request could not be prepared",
                    request == null || request.identity() == null ? null : request.identity().requestId(), 0));
        }
    }

    private void validateCall(ProviderConnection connection, EgressDecision egressDecision,
                              ProviderChatRequest request, CancellationToken cancellationToken) {
        if (connection == null || egressDecision == null || request == null || cancellationToken == null) {
            throw new ProviderAdapterException(ProviderErrorClass.INVALID_RESPONSE,
                    "Provider request is incomplete");
        }
        if (connection.providerType() != providerType()) {
            throw new ProviderAdapterException(ProviderErrorClass.UNSUPPORTED_CAPABILITY,
                    "Provider adapter does not match the configured provider",
                    request.identity().requestId(), 0);
        }
        EgressPolicy.validateConnection(request.spaceId(), egressDecision, connection);
        if (cancellationToken.isCancellationRequested()) {
            throw cancellation(request.identity().requestId());
        }
        if (request.stream() || request.requiredCapabilities().contains(ModelCapability.STREAMING)) {
            throw new ProviderAdapterException(ProviderErrorClass.UNSUPPORTED_CAPABILITY,
                    "Streaming is not supported by this synchronous adapter",
                    request.identity().requestId(), 0);
        }
        if (!supportedCapabilities().containsAll(request.requiredCapabilities())) {
            throw new ProviderAdapterException(ProviderErrorClass.UNSUPPORTED_CAPABILITY,
                    "Requested provider capability is not supported",
                    request.identity().requestId(), 0);
        }
    }

    private String resolveAuthorization(ProviderConnection connection, ProviderChatRequest request) {
        return resolveAuthorization(connection, request.identity().requestId());
    }

    private String resolveAuthorization(ProviderConnection connection, UUID requestId) {
        if (connection.isExplicitLocalNoAuth()) {
            return null;
        }
        try {
            return credentialResolver.resolveAuthorization(connection);
        } catch (RuntimeException exception) {
            throw new ProviderAdapterException(ProviderErrorClass.AUTHENTICATION,
                    "Provider credential resolution failed", requestId, 0);
        }
    }

    private void validateEmbeddingCall(ProviderConnection connection, EgressDecision egressDecision,
                                       ProviderEmbeddingRequest request, CancellationToken cancellationToken) {
        if (connection == null || egressDecision == null || request == null || cancellationToken == null) {
            throw new ProviderAdapterException(ProviderErrorClass.INVALID_RESPONSE, "Provider request is incomplete");
        }
        if (connection.providerType() != providerType()) {
            throw new ProviderAdapterException(ProviderErrorClass.UNSUPPORTED_CAPABILITY,
                    "Provider adapter does not match the configured provider", request.identity().requestId(), 0);
        }
        EgressPolicy.validateConnection(request.spaceId(), egressDecision, connection);
        if (cancellationToken.isCancellationRequested()) {
            throw cancellation(request.identity().requestId());
        }
        if (!supportedCapabilities().containsAll(request.requiredCapabilities())) {
            throw new ProviderAdapterException(ProviderErrorClass.UNSUPPORTED_CAPABILITY,
                    "Requested provider capability is not supported", request.identity().requestId(), 0);
        }
    }

    private CompletableFuture<ProviderEmbeddingResponse> sendEmbedding(HttpRequest request,
                                                                        ProviderEmbeddingRequest embeddingRequest,
                                                                        CancellationToken cancellationToken) {
        CompletableFuture<ProviderEmbeddingResponse> result = new CompletableFuture<>();
        final CompletableFuture<HttpResponse<String>> transport;
        try {
            transport = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            result.completeExceptionally(mapTransportFailure(exception, embeddingRequest.identity().requestId(), cancellationToken));
            return result;
        }
        cancellationToken.onCancel(() -> { transport.cancel(true); result.completeExceptionally(cancellation(embeddingRequest.identity().requestId())); });
        transport.orTimeout(embeddingRequest.timeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .whenComplete((response, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(mapTransportFailure(failure, embeddingRequest.identity().requestId(), cancellationToken));
                        return;
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        result.completeExceptionally(ProviderErrorMapper.map(response.statusCode(), response.body(),
                                embeddingRequest.identity().requestId(), objectMapper));
                        return;
                    }
                    try {
                        result.complete(parseEmbeddingResponse(embeddingRequest, response.body()));
                    } catch (ProviderAdapterException exception) {
                        result.completeExceptionally(exception);
                    } catch (RuntimeException exception) {
                        result.completeExceptionally(new ProviderAdapterException(ProviderErrorClass.INVALID_RESPONSE,
                                "Provider embedding response was invalid", embeddingRequest.identity().requestId(), response.statusCode()));
                    }
                });
        return result;
    }

    protected final ProviderAdapterException invalidEmbeddingResponse(ProviderEmbeddingRequest request) {
        return new ProviderAdapterException(ProviderErrorClass.INVALID_RESPONSE,
                "Provider embedding response was invalid", request.identity().requestId(), 0);
    }

    private CompletableFuture<ProviderChatResponse> send(HttpRequest request,
                                                         ProviderChatRequest chatRequest,
                                                         CancellationToken cancellationToken) {
        CompletableFuture<ProviderChatResponse> result = new CompletableFuture<>();
        final CompletableFuture<HttpResponse<String>> transport;
        try {
            transport = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            result.completeExceptionally(mapTransportFailure(exception, chatRequest.identity().requestId(),
                    cancellationToken));
            return result;
        }

        cancellationToken.onCancel(() -> {
            transport.cancel(true);
            result.completeExceptionally(cancellation(chatRequest.identity().requestId()));
        });
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) {
                cancellationToken.cancel();
            }
        });
        transport.orTimeout(chatRequest.timeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .whenComplete((response, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(mapTransportFailure(failure,
                                chatRequest.identity().requestId(), cancellationToken));
                        return;
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        result.completeExceptionally(ProviderErrorMapper.map(
                                response.statusCode(), response.body(), chatRequest.identity().requestId(), objectMapper));
                        return;
                    }
                    try {
                        result.complete(parseResponse(chatRequest, response.body()));
                    } catch (ProviderAdapterException exception) {
                        result.completeExceptionally(exception);
                    } catch (RuntimeException exception) {
                        result.completeExceptionally(new ProviderAdapterException(
                                ProviderErrorClass.INVALID_RESPONSE, "Provider response was invalid",
                                chatRequest.identity().requestId(), response.statusCode()));
                    }
                });
        return result;
    }

    private ProviderAdapterException mapTransportFailure(Throwable failure, UUID requestId,
                                                          CancellationToken cancellationToken) {
        Throwable cause = unwrap(failure);
        if (cancellationToken.isCancellationRequested() || cause instanceof CancellationException) {
            return cancellation(requestId);
        }
        if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) {
            return new ProviderAdapterException(ProviderErrorClass.TIMEOUT,
                    "Provider request timed out", requestId, 0);
        }
        if (cause instanceof IOException) {
            return new ProviderAdapterException(ProviderErrorClass.UNAVAILABLE,
                    "Provider endpoint was unavailable", requestId, 0);
        }
        return new ProviderAdapterException(ProviderErrorClass.UNAVAILABLE,
                "Provider request failed", requestId, 0);
    }

    protected final ProviderAdapterException invalidResponse(ProviderChatRequest request) {
        return new ProviderAdapterException(ProviderErrorClass.INVALID_RESPONSE,
                "Provider response was invalid", request.identity().requestId(), 0);
    }

    protected final Long optionalNonNegativeLong(JsonNode object, String field,
                                                 ProviderChatRequest request) {
        if (object == null || object.isMissingNode() || object.isNull() || !object.has(field)) {
            return null;
        }
        JsonNode value = object.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalidResponse(request);
        }
        return value.longValue();
    }

    protected final ProviderUsage usage(JsonNode usageNode, String promptField, String completionField,
                                        String totalField, ProviderChatRequest request) {
        if (usageNode == null || usageNode.isMissingNode() || usageNode.isNull()) {
            return null;
        }
        Long prompt = optionalNonNegativeLong(usageNode, promptField, request);
        Long completion = optionalNonNegativeLong(usageNode, completionField, request);
        Long total = optionalNonNegativeLong(usageNode, totalField, request);
        if (prompt == null && completion == null && total == null) {
            throw invalidResponse(request);
        }
        if (total == null && prompt != null && completion != null) {
            total = prompt + completion;
        }
        return new ProviderUsage(prompt, completion, total, UsageSource.PROVIDER_REPORTED);
    }

    protected static CancellationToken newCancellationToken() {
        return new CancellationToken();
    }

    private static ProviderAdapterException cancellation(UUID requestId) {
        return new ProviderAdapterException(ProviderErrorClass.CANCELLED,
                "Provider request was cancelled", requestId, 0);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}

final class ProviderErrorMapper {
    private ProviderErrorMapper() {
    }

    static ProviderAdapterException map(int status, String body, UUID requestId, ObjectMapper objectMapper) {
        String classificationText = classificationText(body, objectMapper);
        ProviderErrorClass errorClass;
        if (status == 401 || status == 403) {
            errorClass = ProviderErrorClass.AUTHENTICATION;
        } else if (status == 408 || status == 504) {
            errorClass = ProviderErrorClass.TIMEOUT;
        } else if (status == 409) {
            errorClass = ProviderErrorClass.IDEMPOTENCY_CONFLICT;
        } else if (status == 404 || containsAny(classificationText, "model_not_found", "model not found", "no such model")) {
            errorClass = ProviderErrorClass.MODEL_NOT_FOUND;
        } else if (status == 413 || containsAny(classificationText, "context_length", "context window", "too many tokens", "prompt is too long")) {
            errorClass = ProviderErrorClass.CONTEXT_OVERFLOW;
        } else if (status == 429 && containsAny(classificationText, "quota", "billing", "insufficient_quota")) {
            errorClass = ProviderErrorClass.QUOTA;
        } else if (status == 429) {
            errorClass = ProviderErrorClass.RATE_LIMIT;
        } else if (status == 405 || status == 501 || containsAny(classificationText, "unsupported", "not implemented")) {
            errorClass = ProviderErrorClass.UNSUPPORTED_CAPABILITY;
        } else if (status == 400 || status == 422 || status == 451) {
            errorClass = containsAny(classificationText, "content_policy", "content policy", "content_filter", "safety", "policy")
                    ? ProviderErrorClass.CONTENT_POLICY : ProviderErrorClass.INVALID_RESPONSE;
        } else if (status >= 500 && status <= 599) {
            errorClass = ProviderErrorClass.UNAVAILABLE;
        } else {
            errorClass = ProviderErrorClass.INVALID_RESPONSE;
        }
        return new ProviderAdapterException(errorClass, "Provider returned a non-success response",
                requestId, status);
    }

    private static String classificationText(String body, ObjectMapper objectMapper) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(body.length() > 8192 ? body.substring(0, 8192) : body);
            return root == null ? "" : root.toString().toLowerCase(java.util.Locale.ROOT);
        } catch (IOException | RuntimeException ignored) {
            return "";
        }
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
