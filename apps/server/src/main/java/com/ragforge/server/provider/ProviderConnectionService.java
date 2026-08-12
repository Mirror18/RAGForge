package com.ragforge.server.provider;

import com.ragforge.server.common.ApiException;
import com.ragforge.server.common.CorrelationIdFilter;
import com.ragforge.server.common.UuidV7;
import com.ragforge.server.identity.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ProviderConnectionService {
    private final ProviderRepository providers;
    private final SpaceAuthorization authorization;

    public ProviderConnectionService(ProviderRepository providers, SpaceAuthorization authorization) {
        this.providers = providers;
        this.authorization = authorization;
    }

    @Transactional
    public ProviderConnectionView create(UUID spaceId, ProviderConnectionRequest request,
                                         SessionPrincipal principal, HttpServletRequest servletRequest) {
        authorization.requireWrite(spaceId, principal);
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
        return toView(connection);
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

    private static ProviderConnectionView toView(ProviderRepository.ProviderConnection connection) {
        return new ProviderConnectionView(connection.id(), connection.spaceId(), connection.version() + 1,
                connection.providerType().name(), connection.egressPolicy() == ProviderRepository.EgressPolicy.LOCAL_ONLY
                ? "LOCAL" : "CLOUD", connection.endpointUri(),
                responseStatus(connection.status()), connection.createdAt(), connection.updatedAt());
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
            if (uri.getScheme() == null || uri.getHost() == null) {
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

    public record ProviderConnectionRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(min = 1, max = 160)
            String displayName,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "OLLAMA|OPENAI_COMPATIBLE|AI_RUNTIME")
            String providerType,
            @jakarta.validation.constraints.Pattern(regexp = "LOCAL|CLOUD")
            String egressClass,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 2048)
            String endpoint,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(min = 2, max = 128)
            @jakarta.validation.constraints.Pattern(regexp = "^[a-z][a-z0-9._:-]{1,127}$")
            String credentialRef,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "DRAFT|ACTIVE|DISABLED")
            String status) {
    }

    public record ProviderConnectionView(UUID providerConnectionId, UUID spaceId, long version,
                                         String providerType, String egressClass, String endpoint,
                                         String status, Instant createdAt, Instant updatedAt) {
    }

    public record ProviderConnectionPage(List<ProviderConnectionView> items, String nextCursor) {
    }
}
