package com.ragforge.server.answer;

import java.util.Optional;
import java.util.UUID;

/** Redacted idempotency boundary. Implementations must not persist answer text or raw provider payloads. */
public interface AnswerPersistencePort {
    Optional<PersistedAnswer> find(UUID spaceId, String idempotencyKey);

    PersistedAnswer saveIfAbsent(PersistedAnswer record);

    record PersistedAnswer(UUID answerId, UUID spaceId, UUID runId, String idempotencyKey,
                           AnswerStatus status, String answerHash, String citationHash,
                           AnswerProvenance provenance) {
        public PersistedAnswer {
            if (answerId == null || spaceId == null || runId == null || idempotencyKey == null
                    || status == null || answerHash == null || !answerHash.matches("[0-9a-f]{64}")
                    || citationHash == null || !citationHash.matches("[0-9a-f]{64}")
                    || provenance == null || !spaceId.equals(provenance.spaceId())
                    || !runId.equals(provenance.runId()) || !idempotencyKey.equals(provenance.idempotencyKey())) {
                throw new IllegalArgumentException("Persisted answer identity/redaction is invalid");
            }
        }
    }
}
