package com.ragforge.server.answer.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragforge.server.run.RunEvent;

import java.util.Set;
import java.util.UUID;

/** Converts internal run events into the strict, versioned answer SSE envelope. */
public final class AnswerSseEventAdapter {
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "answer.delta", "answer.citation", "answer.abstention", "answer.tool",
            "answer.usage", "answer.error", "answer.done");
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "password", "secret", "api_key", "access_token", "credential_ref", "authorization",
            "cookie", "prompt", "system_prompt", "full_text", "raw_text", "document_content");

    private final ObjectMapper objectMapper;

    public AnswerSseEventAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Run lifecycle events are internal control records. They are validated and deliberately omitted from the
     * answer contract so a snapshot/cancellation record cannot be mistaken for an answer event or break a replay.
     */
    public boolean isControlEvent(RunEvent event) {
        if (!Set.of("run.snapshot", "run.status", "run.cancel").contains(event.type())) {
            return false;
        }
        JsonNode payload = parsePayload(event.payloadJson());
        rejectForbiddenFields(payload);
        requireIdentity(payload, event);
        return true;
    }

    public ObjectNode toEnvelope(RunEvent event) {
        if (isControlEvent(event)) {
            throw new IllegalArgumentException("Run lifecycle event is not an answer SSE event: " + event.type());
        }
        if (!ALLOWED_TYPES.contains(event.type())) {
            throw new IllegalArgumentException("Unsupported answer SSE event type: " + event.type());
        }
        JsonNode payload = parsePayload(event.payloadJson());
        rejectForbiddenFields(payload);
        String idempotencyKey = text(payload, "idempotency_key", "idempotencyKey");
        if (idempotencyKey == null || !idempotencyKey.matches("^[A-Za-z0-9._:-]{16,255}$")) {
            throw new IllegalArgumentException("Answer SSE event is missing a valid idempotency key");
        }
        requireIdentity(payload, event);
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("schema_version", "v1");
        envelope.put("event_id", event.eventId().toString());
        envelope.put("event_type", event.type());
        envelope.put("sequence", event.sequence());
        envelope.put("idempotency_key", idempotencyKey);
        envelope.put("space_id", event.spaceId().toString());
        envelope.put("correlation_id", event.correlationId().toString());
        envelope.put("run_id", event.runId().toString());
        envelope.put("occurred_at", event.occurredAt().toString());
        ObjectNode publicPayload = (ObjectNode) payload.deepCopy();
        // These fields are an internal bridge because RunEvent has no idempotency field. The public
        // envelope carries them in its required identity fields; payload schemas remain additionalProperties:false.
        publicPayload.remove("idempotency_key");
        publicPayload.remove("idempotencyKey");
        publicPayload.remove("space_id");
        publicPayload.remove("spaceId");
        publicPayload.remove("correlation_id");
        publicPayload.remove("correlationId");
        publicPayload.remove("run_id");
        publicPayload.remove("runId");
        envelope.set("payload", publicPayload);
        return envelope;
    }

    private JsonNode parsePayload(String payloadJson) {
        try {
            JsonNode payload = objectMapper.readTree(payloadJson);
            if (payload == null || !payload.isObject()) {
                throw new IllegalArgumentException("Answer SSE payload must be an object");
            }
            return payload;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Answer SSE payload is malformed", exception);
        }
    }

    private void requireIdentity(JsonNode payload, RunEvent event) {
        UUID spaceId = uuid(payload, "space_id", "spaceId");
        UUID runId = uuid(payload, "run_id", "runId");
        UUID correlationId = uuid(payload, "correlation_id", "correlationId");
        if (spaceId != null && !event.spaceId().equals(spaceId)
                || runId != null && !event.runId().equals(runId)
                || correlationId != null && !event.correlationId().equals(correlationId)) {
            throw new IllegalArgumentException("Answer SSE payload crosses its event scope");
        }
    }

    private void rejectForbiddenFields(JsonNode node) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(field -> {
                if (FORBIDDEN_FIELDS.contains(field.toLowerCase(java.util.Locale.ROOT))) {
                    throw new IllegalArgumentException("Sensitive answer SSE field is not allowed: " + field);
                }
                rejectForbiddenFields(node.get(field));
            });
        } else if (node.isArray()) {
            node.forEach(this::rejectForbiddenFields);
        }
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isTextual()) return value.textValue();
        }
        return null;
    }

    private static UUID uuid(JsonNode node, String... names) {
        String value = text(node, names);
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Answer SSE identity is not a UUID", exception);
        }
    }
}
