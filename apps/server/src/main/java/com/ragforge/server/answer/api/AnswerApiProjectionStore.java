package com.ragforge.server.answer.api;

import com.ragforge.server.answer.Answer;
import com.ragforge.server.answer.Citation;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local public projection used by the HTTP adapter until the durable answer history port is wired.
 * It stores only the already-projected answer and never provider prompts or response bodies outside Answer.
 */
public final class AnswerApiProjectionStore {
    private final ConcurrentHashMap<RunKey, Answer> answers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<IdempotencyKey, RunKey> idempotency = new ConcurrentHashMap<>();

    public Answer saveIfAbsent(Answer answer) {
        RunKey runKey = new RunKey(answer.spaceId(), answer.runId());
        IdempotencyKey idempotencyKey = new IdempotencyKey(answer.spaceId(), answer.idempotencyKey());
        RunKey existingRun = idempotency.putIfAbsent(idempotencyKey, runKey);
        if (existingRun != null && !existingRun.equals(runKey)) {
            throw new IllegalStateException("Idempotency key is bound to another run");
        }
        return answers.computeIfAbsent(runKey, ignored -> answer);
    }

    public Optional<Answer> find(UUID spaceId, UUID runId) {
        return Optional.ofNullable(answers.get(new RunKey(spaceId, runId)));
    }

    public Optional<Answer> findByIdempotency(UUID spaceId, String idempotencyKey) {
        RunKey runKey = idempotency.get(new IdempotencyKey(spaceId, idempotencyKey));
        return runKey == null ? Optional.empty() : find(runKey.spaceId(), runKey.runId());
    }

    public CitationPreview preview(UUID spaceId, UUID runId, UUID evidenceId) {
        Answer answer = find(spaceId, runId).orElseThrow(() -> new AnswerApiNotFoundException("Answer not found"));
        return answer.citations().stream()
                .filter(citation -> citation.evidenceId().equals(evidenceId))
                .findFirst()
                .map(citation -> CitationPreview.from(answer, citation))
                .orElseThrow(() -> new AnswerApiNotFoundException("Citation not found"));
    }

    public record CitationPreview(UUID evidenceId, UUID spaceId, UUID correlationId, UUID runId,
                                  UUID evidenceBundleId, int evidenceBundleVersion, String evidenceBundleHash,
                                  UUID indexVersionId, UUID retrievalProfileId, int retrievalProfileVersion,
                                  UUID documentRevisionId, UUID parentChunkId, UUID childChunkId,
                                  String contentRef, String textHash, Object anchor, boolean citationAllowed) {
        private static CitationPreview from(Answer answer, Citation citation) {
            if (!answer.spaceId().equals(citation.spaceId()) || !answer.runId().equals(citation.runId())
                    || !citation.citationAllowed()) {
                throw new AnswerApiNotFoundException("Citation is not available in this space");
            }
            return new CitationPreview(citation.evidenceId(), citation.spaceId(), citation.correlationId(),
                    citation.runId(), citation.evidenceBundleId(), citation.evidenceBundleVersion(),
                    citation.evidenceBundleHash(), citation.indexVersionId(), citation.retrievalProfileId(),
                    citation.retrievalProfileVersion(), citation.documentRevisionId(), citation.parentChunkId(),
                    citation.childChunkId(), citation.contentRef(), citation.textHash(), citation.anchor(), true);
        }
    }

    public static final class AnswerApiNotFoundException extends RuntimeException {
        public AnswerApiNotFoundException(String message) {
            super(message);
        }
    }

    private record RunKey(UUID spaceId, UUID runId) {
    }

    private record IdempotencyKey(UUID spaceId, String value) {
    }
}
