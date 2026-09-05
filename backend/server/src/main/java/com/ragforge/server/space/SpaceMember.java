package com.ragforge.server.space;

import java.util.UUID;

public record SpaceMember(UUID spaceId, UUID userId, SpaceRole role, long version) {
}
