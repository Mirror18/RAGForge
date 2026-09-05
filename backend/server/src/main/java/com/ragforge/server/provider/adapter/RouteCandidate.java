package com.ragforge.server.provider.adapter;

import java.util.Objects;
import java.util.UUID;

public record RouteCandidate(UUID spaceId, UUID modelProfileId, int priority, EgressClass egressClass) {
    public RouteCandidate {
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(modelProfileId, "modelProfileId");
        Objects.requireNonNull(egressClass, "egressClass");
        if (priority < 1 || priority > 100) {
            throw new IllegalArgumentException("Route candidate priority is invalid");
        }
    }
}
