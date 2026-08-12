package com.ragforge.server.provider.adapter;

import java.util.UUID;

/** Resolves an opaque credential reference into an Authorization header for one space. */
@FunctionalInterface
public interface CredentialResolver {
    String resolveAuthorization(UUID spaceId, String credentialRef);

    /**
     * Resolves an authorization header with the complete, already space-scoped connection context.
     * The default keeps existing deployment seams source-compatible while allowing implementations
     * to verify auth scheme and egress metadata before resolving an opaque reference.
     */
    default String resolveAuthorization(ProviderConnection connection) {
        return resolveAuthorization(connection.spaceId(), connection.credentialRef());
    }
}
