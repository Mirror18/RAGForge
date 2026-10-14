package com.ragforge.server.space.domain;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeSpace(UUID id, String name, String description, String status, SpaceRole role, Instant createdAt,
                             Instant updatedAt, long version) {
}
