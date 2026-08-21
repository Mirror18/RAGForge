package com.ragforge.server.answer.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragforge.server.answer.Abstention;
import com.ragforge.server.answer.Answer;
import com.ragforge.server.answer.AnswerPersistencePort;
import com.ragforge.server.answer.AnswerStatus;
import com.ragforge.server.answer.Citation;
import com.ragforge.server.run.RunEventService;

import java.util.Objects;
import java.util.UUID;

/** Publishes only server-projected answer fields to the run event stream. */
public final class AnswerEventPublisher {
    private final RunEventService events;
    private final ObjectMapper objectMapper;
    private final AnswerPersistencePort persistence;

    public AnswerEventPublisher(RunEventService events, ObjectMapper objectMapper) {
        this(events, objectMapper, null);
    }

    public AnswerEventPublisher(RunEventService events, ObjectMapper objectMapper,
                                AnswerPersistencePort persistence) {
        this.events = Objects.requireNonNull(events, "events");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.persistence = persistence;
    }

    public void publish(Answer answer) {
        if (answer.status() == AnswerStatus.COMPLETED) {
            ObjectNode delta = base(answer);
            delta.put("answer_id", answer.answerId().toString());
            delta.put("delta", answer.answerText());
            append(answer, "answer.delta", delta);
            for (Citation citation : answer.citations()) {
                ObjectNode citationPayload = base(answer);
                citationPayload.put("answer_id", answer.answerId().toString());
                citationPayload.put("claim_id", citation.claimId().toString());
                citationPayload.set("citation", citationNode(citation));
                append(answer, "answer.citation", citationPayload);
            }
        } else if (answer.abstention() != null) {
            Abstention refusal = answer.abstention();
            ObjectNode payload = base(answer);
            payload.put("answer_id", answer.answerId().toString());
            ObjectNode abstention = objectMapper.createObjectNode();
            abstention.put("schema_version", "v1");
            abstention.put("abstention_id", refusal.abstentionId().toString());
            abstention.put("space_id", refusal.spaceId().toString());
            abstention.put("correlation_id", refusal.correlationId().toString());
            abstention.put("run_id", refusal.runId().toString());
            abstention.put("reason_code", refusal.reasonCode().name());
            abstention.set("evidence_ids", objectMapper.valueToTree(refusal.evidenceIds()));
            abstention.put("message", refusal.message());
            payload.set("abstention", abstention);
            append(answer, "answer.abstention", payload);
        }
        if (answer.status() == AnswerStatus.FAILED || answer.status() == AnswerStatus.CANCELLED) {
            ObjectNode error = base(answer);
            error.put("answer_id", answer.answerId().toString());
            error.put("code", answer.status() == AnswerStatus.CANCELLED ? "CANCELLED" : "INTERNAL_ERROR");
            error.put("message", answer.abstention() == null ? "Answer failed" : answer.abstention().message());
            error.put("retryable", false);
            append(answer, "answer.error", error);
        }
        ObjectNode done = base(answer);
        done.put("answer_id", answer.answerId().toString());
        done.put("status", answer.status() == AnswerStatus.COMPLETED ? "COMPLETED" : answer.status().name());
        append(answer, "answer.done", done);
    }

    /** Publishes the answer-terminal pair after the run store has accepted cancellation. */
    public void publishCancellation(Answer answer, UUID correlationId) {
        ObjectNode error = base(answer);
        error.put("answer_id", answer.answerId().toString());
        error.put("code", "CANCELLED");
        error.put("message", "Answer request was cancelled.");
        error.put("retryable", false);
        append(answer, "answer.error", error);
        ObjectNode done = base(answer);
        done.put("answer_id", answer.answerId().toString());
        done.put("status", "CANCELLED");
        append(answer, "answer.done", done);
    }

    private ObjectNode base(Answer answer) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("idempotency_key", answer.idempotencyKey());
        payload.put("space_id", answer.spaceId().toString());
        payload.put("correlation_id", answer.correlationId().toString());
        payload.put("run_id", answer.runId().toString());
        return payload;
    }

    private ObjectNode citationNode(Citation citation) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("schema_version", "v1");
        node.put("evidence_id", citation.evidenceId().toString());
        node.put("space_id", citation.spaceId().toString());
        node.put("correlation_id", citation.correlationId().toString());
        node.put("run_id", citation.runId().toString());
        node.put("evidence_bundle_id", citation.evidenceBundleId().toString());
        node.put("index_version_id", citation.indexVersionId().toString());
        node.put("document_revision_id", citation.documentRevisionId().toString());
        node.put("parent_chunk_id", citation.parentChunkId().toString());
        node.put("child_chunk_id", citation.childChunkId().toString());
        node.put("content_ref", citation.contentRef());
        node.put("text_hash", citation.textHash());
        ObjectNode anchor = objectMapper.createObjectNode();
        anchor.set("heading_path", objectMapper.valueToTree(citation.anchor().headingPath()));
        anchor.put("token_start", citation.anchor().tokenStart());
        anchor.put("token_end", citation.anchor().tokenEnd());
        anchor.put("char_start", citation.anchor().charStart());
        anchor.put("char_end", citation.anchor().charEnd());
        if (citation.anchor().pageNumber() != null) anchor.put("page_number", citation.anchor().pageNumber());
        else anchor.putNull("page_number");
        if (citation.anchor().sheet() != null) anchor.put("sheet", citation.anchor().sheet());
        else anchor.putNull("sheet");
        if (citation.anchor().slideNumber() != null) anchor.put("slide_number", citation.anchor().slideNumber());
        else anchor.putNull("slide_number");
        if (citation.anchor().lineStart() != null) anchor.put("line_start", citation.anchor().lineStart());
        else anchor.putNull("line_start");
        if (citation.anchor().lineEnd() != null) anchor.put("line_end", citation.anchor().lineEnd());
        else anchor.putNull("line_end");
        if (citation.anchor().tableCell() != null) anchor.put("table_cell", citation.anchor().tableCell());
        else anchor.putNull("table_cell");
        node.set("anchor", anchor);
        node.put("citation_allowed", true);
        return node;
    }

    private void append(Answer answer, String type, ObjectNode payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            com.ragforge.server.run.RunEvent event = events.append(answer.spaceId(), answer.runId(),
                    answer.correlationId(), type, 1, payloadJson);
            if (persistence != null) {
                persistence.appendEvent(new AnswerPersistencePort.AnswerEvent(event.eventId(), answer.answerId(),
                        answer.spaceId(), answer.runId(), event.sequence(), eventType(type), sha256(payloadJson),
                        "{}", event.occurredAt()));
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Answer event could not be serialized", exception);
        }
    }

    private static AnswerPersistencePort.EventType eventType(String type) {
        return AnswerPersistencePort.EventType.valueOf(type.replace('.', '_').toUpperCase(java.util.Locale.ROOT));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the runtime", exception);
        }
    }
}
