package com.ragforge.ingestion.messaging;

public interface IngestionSideEffectHandler {
    void handle(IngestionEventEnvelope envelope, IngestionJobRequestedPayload payload);
}
