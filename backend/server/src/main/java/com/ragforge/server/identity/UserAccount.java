package com.ragforge.server.identity;

import java.time.Instant;
import java.util.UUID;

public record UserAccount(UUID id, String email, String displayName, String passwordHash, String platformRole,
                          String status, Instant createdAt, Instant updatedAt) {
}
