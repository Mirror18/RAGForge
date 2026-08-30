package com.ragforge.server.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.audit.AuditOutboxService;
import com.ragforge.server.common.ApiException;
import com.ragforge.server.common.CorrelationIdFilter;
import com.ragforge.server.common.UuidV7;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.provider.adapter.CancellationToken;
import com.ragforge.server.provider.adapter.ChatMessage;
import com.ragforge.server.provider.adapter.EgressClass;
import com.ragforge.server.provider.adapter.EgressDecision;
import com.ragforge.server.provider.adapter.ModelCapability;
import com.ragforge.server.provider.adapter.ProviderAdapter;
import com.ragforge.server.provider.adapter.ProviderAdapterException;
import com.ragforge.server.provider.adapter.ProviderChatRequest;
import com.ragforge.server.provider.adapter.ProviderEmbeddingRequest;
import com.ragforge.server.provider.adapter.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

@Service
public class ProviderConnectionService {
    private final ProviderRepository providers;
    private final SpaceAuthorization authorization;
    private final Map<com.ragforge.server.provider.adapter.ProviderType, ProviderAdapter> adapters;
    private final ObjectMapper objectMapper;
    private final AuditOutboxService audit;
    private final TransactionTemplate transactions;

    public ProviderConnectionService(ProviderRepository providers, SpaceAuthorization authorization,
                                     List<ProviderAdapter> adapters, ObjectMapper objectMapper,
                                     AuditOutboxService audit, TransactionTemplate transactions) {
        this.providers = providers;
        this.authorization = authorization;
        EnumMap<com.ragforge.server.provider.adapter.ProviderType, ProviderAdapter> configured =
                new EnumMap<>(com.ragforge.server.provider.adapter.ProviderType.class);
        for (ProviderAdapter adapter : adapters) {
            configured.put(adapter.providerType(), adapter);
        }
        this.adapters = Map.copyOf(configured);
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.transactions = transactions;
    }

    @Transactional
    public ProviderConnectionView create(UUID spaceId, ProviderConnectionRequest request,
                                         SessionPrincipal principal, HttpServletRequest servletRequest) {
        authorization.requirePlatformAdmin(spaceId, principal);
        ProviderRepository.ProviderType providerType = providerType(request.providerType());
        ProviderRepository.EgressPolicy egressPolicy = egressPolicy(request.egressClass());
        ProviderRepository.ProviderStatus status = providerStatus(request.status());
        validateEndpoint(request.endpoint());

        UUID id = UuidV7.random();
        Instant now = Instant.now();
        UUID correlationId = correlationId(servletRequest);
        ProviderRepository.ProviderConnection connection = providers.createConnection(
                new ProviderRepository.NewProviderConnection(id, spaceId, "connection-" + id,
                        request.displayName().trim(), providerType, request.endpoint(), request.credentialRef(), null,
                        "NONE", "{}", status, egressPolicy, now, correlationId));
        audit.record("provider.connection.created.v1", principal.userId(), spaceId, connection.id(), correlationId,
                Map.of("providerConnectionId", connection.id(), "providerType", connection.providerType().name(),
                        "egressPolicy", connection.egressPolicy().name(), "status", connection.status().name()));
        return toView(connection);
    }

    public ProviderConnectionTestView test(UUID spaceId, UUID providerConnectionId,
                                           ProviderConnectionTestRequest request, SessionPrincipal principal,
                                           HttpServletRequest servletRequest) {
        authorization.requirePlatformAdmin(spaceId, principal);
        ProviderRepository.ProviderConnection stored = providers.findConnectionInSpace(spaceId, providerConnectionId)
                .orElseThrow(() -> notFound("Provider connection not found"));
        ProviderRepository.RoutePurpose purpose = testPurpose(request.purpose());
        if (stored.egressPolicy() == ProviderRepository.EgressPolicy.CLOUD_ALLOWED
                && !Boolean.TRUE.equals(request.allowCloudProbe())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "cloud_probe_approval_required", "Cloud probe denied",
                    "A cloud provider probe requires explicit approval for this request");
        }

        UUID correlationId = correlationId(servletRequest);
        UUID requestId = UuidV7.random();
        Instant startedAt = Instant.now();
        long startedNanos = System.nanoTime();
        Set<String> verified = new LinkedHashSet<>();
        Integer embeddingDimension = null;
        String errorClass = null;
        boolean retryable = false;
        try {
            validateProbeEndpoint(stored);
            ProviderAdapter adapter = adapters.get(adapterType(stored.providerType()));
            if (adapter == null) {
                throw new ProviderAdapterException(
                        com.ragforge.server.provider.adapter.ProviderErrorClass.UNAVAILABLE,
                        "No provider adapter is configured for the requested provider type");
            }
            var connection = adapterConnection(stored);
            EgressDecision decision = stored.egressPolicy() == ProviderRepository.EgressPolicy.LOCAL_ONLY
                    ? EgressDecision.LOCAL_ONLY : EgressDecision.CLOUD_ALLOWED;
            RequestIdentity identity = new RequestIdentity(requestId, correlationId, null);
            Duration timeout = Duration.ofSeconds(request.timeoutSeconds());
            if (purpose == ProviderRepository.RoutePurpose.CHAT) {
                StringBuilder streamed = new StringBuilder();
                var response = adapter.chatStream(connection, decision,
                        new ProviderChatRequest(spaceId, identity, request.modelName().trim(),
                                List.of(new ChatMessage("user", "Reply with OK.")), timeout, 8,
                                Set.of(ModelCapability.CHAT, ModelCapability.STREAMING), true, Set.of()),
                        new CancellationToken(), delta -> {
                            if (streamed.length() + delta.length() > 4096) {
                                throw new ProviderAdapterException(
                                        com.ragforge.server.provider.adapter.ProviderErrorClass.INVALID_RESPONSE,
                                        "Provider probe stream exceeded its bounded output");
                            }
                            streamed.append(delta);
                        })
                        .toCompletableFuture().join();
                if (streamed.isEmpty() || !streamed.toString().equals(response.content())) {
                    throw new ProviderAdapterException(
                            com.ragforge.server.provider.adapter.ProviderErrorClass.INVALID_RESPONSE,
                            "Provider probe stream was incomplete");
                }
                verified.add("CHAT");
                verified.add("STREAMING");
                if (response.usage() != null) {
                    verified.add("USAGE_REPORTING");
                }
            } else if (purpose == ProviderRepository.RoutePurpose.EMBEDDING) {
                var response = adapter.embed(connection, decision,
                        new ProviderEmbeddingRequest(spaceId, identity, request.modelName().trim(),
                                "ragforge provider connectivity probe", timeout), new CancellationToken())
                        .toCompletableFuture().join();
                verified.add("EMBEDDING");
                embeddingDimension = response.embedding().size();
                if (response.usage() != null) {
                    verified.add("USAGE_REPORTING");
                }
            } else {
                throw new ProviderAdapterException(
                        com.ragforge.server.provider.adapter.ProviderErrorClass.UNSUPPORTED_CAPABILITY,
                        "Rerank probing is not supported by the configured adapter");
            }
        } catch (CompletionException exception) {
            ProviderAdapterException mapped = providerFailure(exception.getCause());
            errorClass = mapped.errorClass().name();
            retryable = mapped.retryable();
        } catch (ProviderAdapterException exception) {
            errorClass = exception.errorClass().name();
            retryable = exception.retryable();
        }

        long durationMs = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
        ProviderRepository.TestOutcome outcome = errorClass == null
                ? ProviderRepository.TestOutcome.SUCCEEDED : ProviderRepository.TestOutcome.FAILED;
        final String persistedErrorClass = errorClass;
        final boolean persistedRetryable = retryable;
        final Integer persistedEmbeddingDimension = embeddingDimension;
        ProviderRepository.ProviderTestRun run = transactions.execute(status -> {
            ProviderRepository.ProviderTestRun created = providers.createTestRun(
                    new ProviderRepository.NewProviderTestRun(UuidV7.random(), spaceId, providerConnectionId,
                            request.modelName().trim(), purpose, outcome, json(verified),
                            persistedEmbeddingDimension, persistedErrorClass, persistedRetryable, durationMs,
                            principal.userId(), startedAt, correlationId));
            audit.record("provider.connection.tested.v1", principal.userId(), spaceId, providerConnectionId,
                    correlationId, Map.of("providerConnectionId", providerConnectionId, "testRunId", created.id(),
                            "purpose", purpose.name(), "outcome", outcome.name(), "errorClass",
                            persistedErrorClass == null ? "NONE" : persistedErrorClass));
            return created;
        });
        if (run == null) {
            throw new IllegalStateException("Provider test persistence transaction returned no result");
        }
        return toTestView(run);
    }

    public List<ProviderConnectionView> list(UUID spaceId, SessionPrincipal principal) {
        authorization.requireMember(spaceId, principal);
        return providers.listConnections(spaceId).stream().map(ProviderConnectionService::toView).toList();
    }

    public ProviderConnectionView get(UUID spaceId, UUID providerConnectionId, SessionPrincipal principal) {
        authorization.requireMember(spaceId, principal);
        return providers.findConnectionInSpace(spaceId, providerConnectionId)
                .map(ProviderConnectionService::toView)
                .orElseThrow(() -> notFound("Provider connection not found"));
    }

    @Transactional
    public ProviderConnectionView update(UUID spaceId, UUID providerConnectionId,
                                         ProviderConnectionUpdateRequest request, String ifMatch,
                                         SessionPrincipal principal, HttpServletRequest servletRequest) {
        authorization.requirePlatformAdmin(spaceId, principal);
        requireVersion(request.version(), ifMatch);
        ProviderRepository.ProviderConnection current = providers.findConnectionInSpace(spaceId, providerConnectionId)
                .orElseThrow(() -> notFound("Provider connection not found"));
        if (current.version() + 1 != request.version()) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "provider_connection_version_conflict",
                    "Provider connection version conflict", "The provider connection version is stale");
        }
        ProviderRepository.ProviderConnection updated = providers.updateConnection(spaceId, providerConnectionId,
                request.version(), new ProviderRepository.NewProviderConnection(providerConnectionId, spaceId,
                        current.providerKey(), request.displayName().trim(), providerType(request.providerType()),
                        request.endpoint(), request.credentialRef(), current.credentialHash(), current.authScheme(),
                        current.nonSecretHeadersJson(), providerStatus(request.status()), egressPolicy(request.egressClass()),
                        Instant.now(), correlationId(servletRequest))).orElseThrow(() -> new ApiException(
                        HttpStatus.PRECONDITION_FAILED, "provider_connection_version_conflict",
                        "Provider connection version conflict", "The provider connection version is stale"));
        audit.record("provider.connection.updated.v1", principal.userId(), spaceId, providerConnectionId,
                correlationId(servletRequest), Map.of("providerConnectionId", providerConnectionId,
                        "version", request.version()));
        return toView(updated);
    }

    private static ProviderConnectionView toView(ProviderRepository.ProviderConnection connection) {
        return new ProviderConnectionView(connection.id(), connection.spaceId(), connection.version() + 1,
                connection.providerType().name(), connection.egressPolicy() == ProviderRepository.EgressPolicy.LOCAL_ONLY
                ? "LOCAL" : "CLOUD", connection.endpointUri(),
                responseStatus(connection.status()), connection.createdAt(), connection.updatedAt());
    }

    private ProviderConnectionTestView toTestView(ProviderRepository.ProviderTestRun run) {
        try {
            List<String> capabilities = objectMapper.readValue(run.verifiedCapabilitiesJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return new ProviderConnectionTestView(run.id(), run.providerConnectionId(), run.modelName(),
                    run.purpose().name(), run.outcome().name(), capabilities, run.embeddingDimension(),
                    run.errorClass(), run.retryable(), run.durationMs(), run.testedAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored provider test capabilities are invalid", exception);
        }
    }

    private static com.ragforge.server.provider.adapter.ProviderConnection adapterConnection(
            ProviderRepository.ProviderConnection stored) {
        return new com.ragforge.server.provider.adapter.ProviderConnection(stored.spaceId(), stored.id(),
                Math.max(1, stored.version() + 1), adapterType(stored.providerType()),
                stored.egressPolicy() == ProviderRepository.EgressPolicy.LOCAL_ONLY
                        ? EgressClass.LOCAL : EgressClass.CLOUD,
                URI.create(stored.endpointUri()), stored.credentialRef(), stored.authScheme());
    }

    private static void validateProbeEndpoint(ProviderRepository.ProviderConnection stored) {
        URI endpoint = URI.create(stored.endpointUri());
        try {
            InetAddress[] addresses = InetAddress.getAllByName(endpoint.getHost());
            if (addresses.length == 0) {
                throw new UnknownHostException(endpoint.getHost());
            }
            boolean cloud = stored.egressPolicy() == ProviderRepository.EgressPolicy.CLOUD_ALLOWED;
            if (cloud && !"https".equalsIgnoreCase(endpoint.getScheme())) {
                throw deniedProbe("Cloud provider probes require HTTPS");
            }
            for (InetAddress address : addresses) {
                if (cloud && !isPublic(address)) {
                    throw deniedProbe("Cloud provider endpoint resolved to a non-public address");
                }
                if (!cloud && !isAllowedLocal(address)) {
                    throw deniedProbe("Local provider endpoint resolved outside the local network");
                }
            }
        } catch (UnknownHostException exception) {
            throw new ProviderAdapterException(
                    com.ragforge.server.provider.adapter.ProviderErrorClass.UNAVAILABLE,
                    "Provider endpoint DNS resolution failed");
        }
    }

    private static boolean isAllowedLocal(InetAddress address) {
        byte[] bytes = address.getAddress();
        boolean uniqueLocalV6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        return address.isLoopbackAddress() || address.isSiteLocalAddress() || uniqueLocalV6;
    }

    private static boolean isPublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        boolean uniqueLocalV6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        return !address.isAnyLocalAddress() && !address.isLoopbackAddress() && !address.isLinkLocalAddress()
                && !address.isSiteLocalAddress() && !address.isMulticastAddress() && !uniqueLocalV6;
    }

    private static ProviderAdapterException deniedProbe(String detail) {
        return new ProviderAdapterException(
                com.ragforge.server.provider.adapter.ProviderErrorClass.SPACE_EGRESS_DENIED, detail);
    }

    private static com.ragforge.server.provider.adapter.ProviderType adapterType(
            ProviderRepository.ProviderType type) {
        return com.ragforge.server.provider.adapter.ProviderType.valueOf(type.name());
    }

    private static ProviderRepository.RoutePurpose testPurpose(String value) {
        try {
            return ProviderRepository.RoutePurpose.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException exception) {
            throw invalid("purpose", "Provider test purpose is invalid");
        }
    }

    private ProviderAdapterException providerFailure(Throwable failure) {
        if (failure instanceof ProviderAdapterException providerException) {
            return providerException;
        }
        return new ProviderAdapterException(
                com.ragforge.server.provider.adapter.ProviderErrorClass.UNAVAILABLE,
                "Provider test failed without a classified response");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Provider test metadata cannot be serialized", exception);
        }
    }

    private static String responseStatus(ProviderRepository.ProviderStatus status) {
        return status == ProviderRepository.ProviderStatus.UNHEALTHY ? "DISABLED" : status.name();
    }

    private static ProviderRepository.ProviderType providerType(String value) {
        try {
            return ProviderRepository.ProviderType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException exception) {
            throw invalid("providerType", "Provider type is invalid");
        }
    }

    private static ProviderRepository.EgressPolicy egressPolicy(String value) {
        if (value == null || value.isBlank()) {
            return ProviderRepository.EgressPolicy.LOCAL_ONLY;
        }
        try {
            return switch (value.trim().toUpperCase(java.util.Locale.ROOT)) {
                case "LOCAL" -> ProviderRepository.EgressPolicy.LOCAL_ONLY;
                case "CLOUD" -> ProviderRepository.EgressPolicy.CLOUD_ALLOWED;
                default -> throw new IllegalArgumentException();
            };
        } catch (RuntimeException exception) {
            throw invalid("egressClass", "Egress class is invalid");
        }
    }

    private static ProviderRepository.ProviderStatus providerStatus(String value) {
        try {
            return switch (value.trim().toUpperCase(java.util.Locale.ROOT)) {
                case "DRAFT" -> ProviderRepository.ProviderStatus.DRAFT;
                case "ACTIVE" -> ProviderRepository.ProviderStatus.ACTIVE;
                case "DISABLED" -> ProviderRepository.ProviderStatus.DISABLED;
                default -> throw new IllegalArgumentException();
            };
        } catch (RuntimeException exception) {
            throw invalid("status", "Provider connection status is invalid");
        }
    }

    private static void validateEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw invalid("endpoint", "Endpoint must be an absolute URI");
        }
    }

    private static UUID correlationId(HttpServletRequest request) {
        return UUID.fromString(CorrelationIdFilter.current(request));
    }

    private static ApiException invalid(String field, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "Validation failed",
                field + ": " + detail);
    }

    private static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, "provider_connection_not_found", "Provider connection not found",
                detail);
    }

    private static void requireVersion(int version, String ifMatch) {
        if (version < 1 || ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "provider_connection_version_required",
                    "Version precondition required", "A matching version and If-Match header are required");
        }
        String value = ifMatch.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            if (Integer.parseInt(value) != version) {
                throw new ApiException(HttpStatus.PRECONDITION_FAILED, "provider_connection_version_conflict",
                        "Provider connection version conflict", "If-Match does not match the request version");
            }
        } catch (NumberFormatException exception) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "provider_connection_version_conflict",
                    "Provider connection version conflict", "If-Match must contain the public version");
        }
    }

    public record ProviderConnectionRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(min = 1, max = 160)
            String displayName,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "OLLAMA|OPENAI_COMPATIBLE|MIMO|AI_RUNTIME")
            String providerType,
            @jakarta.validation.constraints.Pattern(regexp = "LOCAL|CLOUD")
            String egressClass,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 2048)
            String endpoint,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(min = 2, max = 128)
            @jakarta.validation.constraints.Pattern(regexp = "^[A-Za-z][A-Za-z0-9._:-]{1,127}$")
            String credentialRef,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "DRAFT|ACTIVE|DISABLED")
            String status) {
    }

    public record ProviderConnectionUpdateRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(min = 1, max = 160)
            String displayName,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "OLLAMA|OPENAI_COMPATIBLE|MIMO|AI_RUNTIME") String providerType,
            @jakarta.validation.constraints.Pattern(regexp = "LOCAL|CLOUD") String egressClass,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 2048) String endpoint,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(min = 2, max = 128)
            @jakarta.validation.constraints.Pattern(regexp = "^[A-Za-z][A-Za-z0-9._:-]{1,127}$") String credentialRef,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "DRAFT|ACTIVE|DISABLED") String status,
            @jakarta.validation.constraints.Min(1) int version) {
    }

    public record ProviderConnectionView(UUID providerConnectionId, UUID spaceId, long version,
                                         String providerType, String egressClass, String endpoint,
                                         String status, Instant createdAt, Instant updatedAt) {
    }

    public record ProviderConnectionPage(List<ProviderConnectionView> items, String nextCursor) {
    }

    public record ProviderConnectionTestRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 200) String modelName,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "CHAT|EMBEDDING|RERANK") String purpose,
            @jakarta.validation.constraints.Min(1)
            @jakarta.validation.constraints.Max(30) int timeoutSeconds,
            Boolean allowCloudProbe) {
    }

    public record ProviderConnectionTestView(UUID testRunId, UUID providerConnectionId, String modelName,
                                             String purpose, String outcome, List<String> verifiedCapabilities,
                                             Integer embeddingDimension, String errorClass, boolean retryable,
                                             long durationMs, Instant testedAt) {
    }
}
