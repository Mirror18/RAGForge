package com.ragforge.server.space.domain;

import java.util.UUID;
import java.time.Instant;

public record SessionPrincipal(UUID userId, UUID sessionId, String email, String displayName,
                               String csrfToken, String platformRole, Instant expiresAt) {
}
