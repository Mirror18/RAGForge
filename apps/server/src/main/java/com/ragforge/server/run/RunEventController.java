package com.ragforge.server.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragforge.server.common.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             HttpServletRequest request,
                             HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader(CorrelationIdFilter.HEADER, CorrelationIdFilter.current(request));
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
            opened.replay().events().forEach(event -> send(emitter, event));
            opened.subscription().activate();
        } catch (RuntimeException exception) {
            opened.subscription().close();
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    @PostMapping("/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CancelResponse cancel(@PathVariable UUID spaceId, @PathVariable UUID runId,
                                @Valid @RequestBody(required = false) CancelRunRequest cancelRequest,
                                HttpServletRequest request) {
        // The reason is accepted for the public contract and is intentionally not persisted in this slice.
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

    ObjectNode eventEnvelope(RunEvent event) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("id", event.eventId().toString());
        envelope.put("sequence", event.sequence());
        envelope.put("runId", event.runId().toString());
        envelope.put("spaceId", event.spaceId().toString());
        envelope.put("correlationId", event.correlationId().toString());
        envelope.put("occurredAt", event.occurredAt().toString());
        envelope.put("type", event.type());
        envelope.put("version", "v" + event.version());
        envelope.set("payload", PayloadPolicy.parse(event.payloadJson()));
        return envelope;
    }

    public record CancelResponse(String status, boolean firstCancellation, UUID eventId, long sequence) {
    }

    public record CancelRunRequest(@Size(max = 500) String reason) {
    }
}
