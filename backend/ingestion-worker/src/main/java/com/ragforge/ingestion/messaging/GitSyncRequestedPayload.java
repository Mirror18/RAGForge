package com.ragforge.ingestion.messaging;

import java.util.UUID;

public record GitSyncRequestedPayload(UUID jobId, UUID sourceId, String operation) {
    public void validate() {
        if (jobId == null || sourceId == null || operation == null
                || !(operation.equals("FULL_SYNC") || operation.equals("INCREMENTAL_SYNC"))) {
            throw new EnvelopeValidationException("SOURCE_SYNC_PAYLOAD_INVALID");
        }
    }
}
