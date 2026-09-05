package com.ragforge.server.run;

import java.util.Objects;
import java.util.UUID;

/** Event input accepted by an event store; event id, sequence and timestamp are store-owned. */
public record RunEventDraft(UUID runId, UUID spaceId, UUID correlationId, String type, int version,
                            String payloadJson) {
    public RunEventDraft {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
