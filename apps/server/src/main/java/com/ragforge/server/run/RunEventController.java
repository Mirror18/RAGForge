package com.ragforge.server.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragforge.server.common.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

/** HTTP adapter for the run event replay/live stream and idempotent cancellation operation. */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/runs/{runId}")
public class RunEventController {
    private static final long SSE_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();

    private final RunEventService service;
    private final ObjectMapper objectMapper;

    public RunEventController(RunEventService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID spaceId, @PathVariable UUID runId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        RunEventStore.OpenedStream opened = service.openStream(spaceId, runId, lastEventId,
                event -> send(emitter, event));
        emitter.onCompletion(opened.subscription()::close);
        emitter.onTimeout(() -> {
            opened.subscription().close();
            emitter.complete();
        });
        emitter.onError(ignored -> opened.subscription().close());
        try {
            if (opened.replay().snapshotRecovery() != null) {
                sendSnapshot(emitter, opened.replay().snapshotRecovery());
            }
            opened.replay().events().forEach(event -> send(emitter, event));
            opened.subscription().activate();
        } catch (RuntimeException exception) {
            opened.subscription().close();
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    @PostMapping("/cancel")
    public CancelResponse cancel(@PathVariable UUID spaceId, @PathVariable UUID runId,
                                HttpServletRequest request) {
        UUID correlationId = UUID.fromString(CorrelationIdFilter.current(request));
        RunEventStore.CancellationResult result = service.cancel(spaceId, runId, correlationId);
        return new CancelResponse("CANCELLED", result.firstCancellation(), result.event().eventId(),
                result.event().sequence());
    }

    private void send(SseEmitter emitter, RunEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(event.eventId().toString())
                    .name(event.type())
                    .data(eventEnvelope(event), MediaType.APPLICATION_JSON));
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        }
    }

    private void sendSnapshot(SseEmitter emitter, RunEventStore.SnapshotRecovery snapshot) {
        try {
            emitter.send(SseEmitter.event()
                    .name("run.snapshot")
                    .data(snapshotEnvelope(snapshot), MediaType.APPLICATION_JSON));
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        }
    }

    private ObjectNode eventEnvelope(RunEvent event) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", event.eventId().toString());
        envelope.put("sequence", event.sequence());
        envelope.put("runId", event.runId().toString());
        envelope.put("spaceId", event.spaceId().toString());
        envelope.put("correlationId", event.correlationId().toString());
        envelope.put("occurredAt", event.occurredAt().toString());
        envelope.put("type", event.type());
        envelope.put("version", event.version());
        envelope.set("payload", PayloadPolicy.parse(event.payloadJson()));
        return envelope;
    }

    private ObjectNode snapshotEnvelope(RunEventStore.SnapshotRecovery snapshot) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("runId", snapshot.runId().toString());
        envelope.put("spaceId", snapshot.spaceId().toString());
        envelope.put("status", snapshot.status());
        envelope.put("latestSequence", snapshot.latestSequence());
        envelope.put("earliestSequence", snapshot.earliestSequence());
        envelope.put("reason", snapshot.reason());
        envelope.put("resumeFromSequence", snapshot.latestSequence());
        return envelope;
    }

    public record CancelResponse(String status, boolean firstCancellation, UUID eventId, long sequence) {
    }
}
