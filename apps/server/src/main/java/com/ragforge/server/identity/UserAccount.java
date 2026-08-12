package com.ragforge.server.identity;

import java.util.UUID;

public record UserAccount(UUID id, String email, String displayName, String passwordHash, String platformRole) {
}
