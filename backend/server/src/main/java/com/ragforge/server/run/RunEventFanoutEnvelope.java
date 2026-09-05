package com.ragforge.server.run;

import java.util.Objects;
import java.util.UUID;

/** Minimal Valkey live hint; durable event payloads never cross the fan-out channel. */
public record RunEventFanoutEnvelope(String schemaVersion, UUID eventId, UUID runId, UUID spaceId,
                                     long sequence) {
    public static final String SCHEMA_VERSION = "run-event-hint.v1";

    public RunEventFanoutEnvelope {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported run event fan-out schema");
        }
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(spaceId, "spaceId");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
    }

    public static RunEventFanoutEnvelope from(RunEvent event) {
        Objects.requireNonNull(event, "event");
        return new RunEventFanoutEnvelope(SCHEMA_VERSION, event.eventId(), event.runId(), event.spaceId(),
                event.sequence());
    }
}
