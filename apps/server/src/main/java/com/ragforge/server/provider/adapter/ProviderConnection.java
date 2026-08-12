package com.ragforge.server.provider.adapter;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/**
 * Parsed, space-scoped provider configuration. The credential field is an opaque reference,
 * never a credential value.
 */
public record ProviderConnection(
        UUID spaceId,
        UUID providerConnectionId,
        long version,
        ProviderType providerType,
        EgressClass egressClass,
        URI endpoint,
        String credentialRef) {

    public ProviderConnection {
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(providerConnectionId, "providerConnectionId");
        Objects.requireNonNull(providerType, "providerType");
        Objects.requireNonNull(egressClass, "egressClass");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(credentialRef, "credentialRef");
        if (version < 1) {
            throw new IllegalArgumentException("Provider connection version must be positive");
        }
        if (!"http".equalsIgnoreCase(endpoint.getScheme())
                && !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("Provider endpoint must use HTTP or HTTPS");
        }
        if (endpoint.getHost() == null || endpoint.getHost().isBlank()
                || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("Provider endpoint contains unsupported address components");
        }
        if (!credentialRef.matches("^[a-z][a-z0-9._:-]{1,127}$")) {
            throw new IllegalArgumentException("Provider credential reference is invalid");
        }
    }

    @Override
    public String toString() {
        return "ProviderConnection[spaceId=%s, providerConnectionId=%s, version=%d, providerType=%s, egressClass=%s, endpoint=<redacted>, credentialRef=<redacted>]"
                .formatted(spaceId, providerConnectionId, version, providerType, egressClass);
    }
}
