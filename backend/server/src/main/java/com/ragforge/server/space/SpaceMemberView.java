package com.ragforge.server.space;

import java.util.UUID;

public record SpaceMemberView(UUID spaceId, UUID userId, String email, String displayName,
                              SpaceRole role, long version) {
}
