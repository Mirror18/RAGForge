package com.ragforge.server.provider.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Local-only HTTP adapter for the separately deployable RAGForge AI runtime. */
@Component
@Profile("!test")
public final class AiRuntimeRerankProviderAdapter implements ProviderAdapter {
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final CredentialResolver credentialResolver;

    @Autowired
    public AiRuntimeRerankProviderAdapter(ObjectMapper objectMapper, CredentialResolver credentialResolver) {
        this(HttpClient.newHttpClient(), objectMapper, credentialResolver);
    }

    public AiRuntimeRerankProviderAdapter(HttpClient httpClient, ObjectMapper objectMapper,
                                          CredentialResolver credentialResolver) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.credentialResolver = credentialResolver;
    }

    @Override
    public ProviderType providerType() {
        return ProviderType.AI_RUNTIME;
    }

    @Override
    public java.util.concurrent.CompletionStage<ProviderChatResponse> chat(
            ProviderConnection connection, EgressDecision egressDecision, ProviderChatRequest request,
            CancellationToken cancellationToken) {
        return CompletableFuture.failedFuture(new ProviderAdapterException(
                ProviderErrorClass.UNSUPPORTED_CAPABILITY, "AI runtime adapter only supports rerank",
                request == null || request.identity() == null ? null : request.identity().requestId(), 0));
    }

    @Override
    public java.util.concurrent.CompletionStage<ProviderRerankResponse> rerank(
            ProviderConnection connection, EgressDecision egressDecision, ProviderRerankRequest request,
            CancellationToken cancellationToken) {
        try {
            validate(connection, egressDecision, request, cancellationToken);
            ObjectNode body = objectMapper.createObjectNode();
            body.put("space_id", request.spaceId().toString());
            body.put("model", request.modelName());
            body.put("query", request.query());
            body.put("top_k", request.limit());
            ArrayNode candidates = body.putArray("candidates");
            for (ProviderRerankRequest.Candidate candidate : request.candidates()) {
                ObjectNode item = candidates.addObject();
                item.put("space_id", candidate.spaceId().toString());
                item.put("candidate_id", candidate.candidateId().toString());
                item.put("text", candidate.text());
            }
            String payload = objectMapper.writeValueAsString(body);
            if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES * 2) {
                throw invalid(request, "Rerank request exceeds the adapter body bound", 0);
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(ProviderEndpointPaths.aiRuntimeRerank(connection.endpoint()))
                    .timeout(request.timeout())
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("X-RAGForge-Request-Id", request.identity().requestId().toString())
                    .header("X-RAGForge-Correlation-Id", request.identity().correlationId().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            String authorization = resolveAuthorization(connection, request.identity().requestId());
            if (authorization != null && !authorization.isBlank()) {
                if (authorization.indexOf('\r') >= 0 || authorization.indexOf('\n') >= 0) {
                    throw new ProviderAdapterException(ProviderErrorClass.AUTHENTICATION,
                            "Resolved authorization header is invalid", request.identity().requestId(), 0);
                }
                builder.header("Authorization", authorization);
            }
            return send(builder.build(), request, cancellationToken);
        } catch (ProviderAdapterException exception) {
            return CompletableFuture.failedFuture(exception);
        } catch (IOException | RuntimeException exception) {
            return CompletableFuture.failedFuture(invalid(request, "Rerank request could not be prepared", 0));
        }
    }

    private void validate(ProviderConnection connection, EgressDecision egressDecision,
                          ProviderRerankRequest request, CancellationToken cancellationToken) {
        if (connection == null || egressDecision == null || request == null || cancellationToken == null) {
            throw new ProviderAdapterException(ProviderErrorClass.INVALID_RESPONSE, "Rerank request is incomplete");
        }
        if (connection.providerType() != ProviderType.AI_RUNTIME) {
            throw new ProviderAdapterException(ProviderErrorClass.UNSUPPORTED_CAPABILITY,
                    "AI runtime adapter does not match the configured provider", request.identity().requestId(), 0);
        }
        if (egressDecision != EgressDecision.LOCAL_ONLY || connection.egressClass() != EgressClass.LOCAL) {
            throw new ProviderAdapterException(ProviderErrorClass.SPACE_EGRESS_DENIED,
                    "AI runtime rerank is local-only", request.identity().requestId(), 0);
        }
        EgressPolicy.validateConnection(request.spaceId(), egressDecision, connection);
        if (cancellationToken.isCancellationRequested()) {
            throw new ProviderAdapterException(ProviderErrorClass.CANCELLED, "Rerank request was cancelled",
                    request.identity().requestId(), 0);
        }
        if (!request.requiredCapabilities().contains(ModelCapability.RERANK)) {
            throw new ProviderAdapterException(ProviderErrorClass.UNSUPPORTED_CAPABILITY,
                    "Rerank capability is required", request.identity().requestId(), 0);
        }
    }

    private String resolveAuthorization(ProviderConnection connection, UUID requestId) {
        if ("NONE".equalsIgnoreCase(connection.authScheme())) {
            return null;
        }
        try {
            return credentialResolver.resolveAuthorization(connection);
        } catch (RuntimeException exception) {
            throw new ProviderAdapterException(ProviderErrorClass.AUTHENTICATION,
                    "Provider credential resolution failed", requestId, 0);
        }
    }

    private CompletableFuture<ProviderRerankResponse> send(HttpRequest request, ProviderRerankRequest rerankRequest,
                                                            CancellationToken cancellationToken) {
        CompletableFuture<ProviderRerankResponse> result = new CompletableFuture<>();
        final CompletableFuture<HttpResponse<InputStream>> transport;
        try {
            transport = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (RuntimeException exception) {
            result.completeExceptionally(unavailable(rerankRequest, exception));
            return result;
        }
        cancellationToken.onCancel(() -> {
            transport.cancel(true);
            result.completeExceptionally(new ProviderAdapterException(ProviderErrorClass.CANCELLED,
                    "Rerank request was cancelled", rerankRequest.identity().requestId(), 0));
        });
        transport.orTimeout(rerankRequest.timeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .whenComplete((response, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(mapTransportFailure(failure, rerankRequest));
                        return;
                    }
                    try (InputStream stream = response.body()) {
                        String body = readBounded(stream);
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            result.completeExceptionally(mapStatus(response.statusCode(), rerankRequest));
                            return;
                        }
                        result.complete(parseResponse(rerankRequest, body));
                    } catch (ProviderAdapterException exception) {
                        result.completeExceptionally(exception);
                    } catch (IOException | RuntimeException exception) {
                        result.completeExceptionally(invalid(rerankRequest,
                                "Rerank response was invalid", response.statusCode()));
                    }
                });
        return result;
    }

    private ProviderRerankResponse parseResponse(ProviderRerankRequest request, String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject() || !root.hasNonNull("model")
                    || !request.modelName().equals(root.get("model").asText())) {
                throw invalid(request, "Rerank response model metadata is invalid", 200);
            }
            Set<ModelCapability> capabilities = new HashSet<>();
            JsonNode capabilityNode = root.get("capabilities");
            if (capabilityNode != null && capabilityNode.isArray()) {
                for (JsonNode value : capabilityNode) {
                    try {
                        capabilities.add(ModelCapability.valueOf(value.asText()));
                    } catch (IllegalArgumentException ignored) {
                        // Unknown capability metadata is ignored; RERANK is checked below.
                    }
                }
            }
            if (!capabilities.contains(ModelCapability.RERANK)) {
                throw invalid(request, "Rerank response does not verify RERANK capability", 200);
            }
            JsonNode results = root.get("results");
            if (results == null || !results.isArray() || results.isEmpty()
                    || results.size() > request.limit()) {
                throw invalid(request, "Rerank response result bound is invalid", 200);
            }
            Set<UUID> requested = request.candidates().stream()
                    .map(ProviderRerankRequest.Candidate::candidateId).collect(java.util.stream.Collectors.toSet());
            Set<UUID> returned = new HashSet<>();
            List<ProviderRerankResponse.ScoredCandidate> scored = new ArrayList<>();
            for (JsonNode value : results) {
                UUID candidateId = UUID.fromString(value.path("candidate_id").asText());
                double score = value.path("score").asDouble(Double.NaN);
                if (!requested.contains(candidateId) || !returned.add(candidateId) || !Double.isFinite(score)) {
                    throw invalid(request, "Rerank response candidate identity or score is invalid", 200);
                }
                scored.add(new ProviderRerankResponse.ScoredCandidate(candidateId, score));
            }
            return new ProviderRerankResponse(request.modelName(), capabilities, scored);
        } catch (ProviderAdapterException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid(request, "Rerank response was invalid", 200);
        }
    }

    private static String readBounded(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_RESPONSE_BYTES) {
                throw new ProviderAdapterException(ProviderErrorClass.INVALID_RESPONSE,
                        "Rerank response exceeded its bounded size");
            }
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static ProviderAdapterException mapStatus(int status, ProviderRerankRequest request) {
        ProviderErrorClass error = status == 401 || status == 403 ? ProviderErrorClass.AUTHENTICATION
                : status == 404 ? ProviderErrorClass.MODEL_NOT_FOUND
                : status == 408 || status == 429 ? ProviderErrorClass.TIMEOUT
                : status >= 500 ? ProviderErrorClass.UNAVAILABLE : ProviderErrorClass.INVALID_RESPONSE;
        return new ProviderAdapterException(error, "Rerank provider returned an unsuccessful response",
                request.identity().requestId(), status);
    }

    private static ProviderAdapterException mapTransportFailure(Throwable failure, ProviderRerankRequest request) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        ProviderErrorClass error = cause instanceof java.net.http.HttpTimeoutException
                || cause instanceof java.util.concurrent.TimeoutException
                || cause instanceof java.util.concurrent.CancellationException
                ? ProviderErrorClass.TIMEOUT : ProviderErrorClass.UNAVAILABLE;
        return new ProviderAdapterException(error, "Rerank provider transport failed",
                request.identity().requestId(), 0);
    }

    private static ProviderAdapterException unavailable(ProviderRerankRequest request, Throwable ignored) {
        return new ProviderAdapterException(ProviderErrorClass.UNAVAILABLE, "Rerank provider is unavailable",
                request.identity().requestId(), 0);
    }

    private static ProviderAdapterException invalid(ProviderRerankRequest request, String detail, int status) {
        return new ProviderAdapterException(ProviderErrorClass.INVALID_RESPONSE, detail,
                request == null || request.identity() == null ? null : request.identity().requestId(), status);
    }
}
