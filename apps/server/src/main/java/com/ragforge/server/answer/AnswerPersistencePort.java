package com.ragforge.server.answer;

import com.ragforge.server.retrieval.EvidenceBundle;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Redacted answer-history boundary. Implementations must never persist raw prompts,
 * provider request/response bodies, or evidence text. Full answer text is written
 * only through the explicit history method and is subject to the configured retention
 * deadline.
 */
public interface AnswerPersistencePort {
    Optional<PersistedAnswer> find(UUID spaceId, String idempotencyKey);

    PersistedAnswer saveIfAbsent(PersistedAnswer record);

    /** Stores the user-visible answer projection and its server-created provenance. */
    default PersistedAnswer saveIfAbsent(Answer answer) {
        throw new UnsupportedOperationException("full answer history is not configured");
    }

    /** Returns a complete historical answer only after the caller has supplied its space. */
    default Optional<Answer> findAnswer(UUID spaceId, UUID answerId) {
        return Optional.empty();
    }

    /** Returns the answer for one run in the requested space, if retained. */
    default Optional<Answer> findAnswerByRun(UUID spaceId, UUID runId) {
        return Optional.empty();
    }

    /** Returns provenance-only citation metadata; no URL, body, filename, or quote is exposed. */
    default Optional<CitationPreview> findCitationPreview(UUID spaceId, UUID runId, UUID evidenceId) {
        return Optional.empty();
    }

    /** Appends a redacted event identity for replay/audit; event payloads are never stored here. */
    default AnswerEvent appendEvent(AnswerEvent event) {
        throw new UnsupportedOperationException("answer event history is not configured");
    }

    /** Returns retained events in strict sequence order and the requested space. */
    default List<AnswerEvent> replayEvents(UUID spaceId, UUID runId, long afterSequence) {
        return List.of();
    }

    /** Deletes expired answer aggregates and their children; this is the documented purge policy. */
    default int purgeExpired(Instant now) {
        return 0;
    }

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

    record CitationPreview(UUID evidenceId, UUID claimId, UUID spaceId, UUID runId,
                           UUID evidenceBundleId, int evidenceBundleVersion,
                           String evidenceBundleHash, UUID indexVersionId,
                           UUID retrievalProfileId, int retrievalProfileVersion,
                           UUID documentRevisionId, UUID parentChunkId, UUID childChunkId,
                           String contentRef, String textHash, EvidenceBundle.Anchor anchor,
                           int answerCharStart, int answerCharEnd, Instant createdAt) {
        public CitationPreview {
            if (evidenceId == null || claimId == null || spaceId == null || runId == null
                    || evidenceBundleId == null || indexVersionId == null || retrievalProfileId == null
                    || documentRevisionId == null || parentChunkId == null || childChunkId == null
                    || contentRef == null || contentRef.isBlank() || textHash == null
                    || !textHash.matches("[0-9a-fA-F]{64}") || anchor == null || createdAt == null
                    || evidenceBundleVersion <= 0 || retrievalProfileVersion <= 0
                    || evidenceBundleHash == null || !evidenceBundleHash.matches("[0-9a-f]{64}")
                    || answerCharStart < 0 || answerCharEnd < answerCharStart
                    || contentRef.matches(".*(?i)(https?://|www\\.|\\.pdf|\\.docx|\\.md)(.*)")) {
                throw new IllegalArgumentException("citation preview is invalid or contains unsafe metadata");
            }
        }
    }

    record AnswerEvent(UUID eventId, UUID answerId, UUID spaceId, UUID runId,
                       long sequence, EventType type, String payloadHash,
                       String metadataJson, Instant createdAt) {
        public AnswerEvent {
            if (eventId == null || answerId == null || spaceId == null || runId == null
                    || sequence < 0 || type == null || payloadHash == null
                    || !payloadHash.matches("[0-9a-f]{64}") || metadataJson == null
                    || metadataJson.length() > 16_384 || createdAt == null) {
                throw new IllegalArgumentException("answer event is invalid");
            }
        }
    }

    enum EventType {
        ANSWER_DELTA, ANSWER_CITATION, ANSWER_ABSTENTION, ANSWER_TOOL,
        ANSWER_USAGE, ANSWER_ERROR, ANSWER_DONE
    }
}
