package com.ragforge.server.provider.adapter;

import java.util.UUID;

/** Resolves an opaque credential reference into an Authorization header for one space. */
@FunctionalInterface
public interface CredentialResolver {
    String resolveAuthorization(UUID spaceId, String credentialRef);
}
