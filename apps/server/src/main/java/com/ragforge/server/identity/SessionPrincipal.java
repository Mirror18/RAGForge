package com.ragforge.server.identity;

import java.util.UUID;
import java.time.Instant;

public record SessionPrincipal(UUID userId, UUID sessionId, String email, String displayName,
                               String csrfToken, String platformRole, Instant expiresAt) {
}
