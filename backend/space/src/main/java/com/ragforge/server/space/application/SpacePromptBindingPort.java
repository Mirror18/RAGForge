package com.ragforge.server.space.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumer-owned port so space can bind a prompt version without depending on answer infrastructure.
 */
@FunctionalInterface
public interface SpacePromptBindingPort {
    UUID bindActive(UUID id, UUID spaceId, String bindingKey, int versionNo, UUID promptVersionId,
                    Instant now, UUID correlationId);
}
