package com.ragforge.server.answer.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragforge.server.answer.Answer;
import com.ragforge.server.answer.AnswerAuthorizationContext;
import com.ragforge.server.answer.AnswerRequest;
import com.ragforge.server.answer.AnswerPersistencePort;
import com.ragforge.server.answer.AnswerStatus;
import com.ragforge.server.answer.RAGAnswerService;
import com.ragforge.server.answer.GenerationStreamObserver;
import com.ragforge.server.common.ApiException;
import com.ragforge.server.common.CorrelationIdFilter;
import com.ragforge.server.common.UuidV7;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.provider.SpaceAuthorization;
import com.ragforge.server.provider.adapter.CancellationToken;
import com.ragforge.server.provider.adapter.EgressDecision;
import com.ragforge.server.run.RunEvent;
import com.ragforge.server.run.RunEventService;
import com.ragforge.server.run.RunEventStore;
import com.ragforge.server.run.RunRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Versioned answer HTTP adapter. It never accepts provider output as a citation. */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}")
public class AnswerApiController {
    private static final long SSE_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();

    private final RAGAnswerService answers;
    private final RunEventService events;
    private final SpaceAuthorization authorization;
    private final ObjectMapper objectMapper;
    private final AnswerApiProjectionStore projections;
    private final AnswerEventPublisher publisher;
    private final AnswerSseEventAdapter sseAdapter;
    private final AnswerAuthorizationContextFactory authorizationContexts;
    private final ConcurrentMap<RunScope, ActiveGeneration> activeGenerations = new ConcurrentHashMap<>();

    public AnswerApiController(RAGAnswerService answers, RunEventService events, SpaceAuthorization authorization,
                               ObjectMapper objectMapper) {
        this(answers, events, authorization, objectMapper, new AnswerApiProjectionStore());
    }

    @Autowired
    AnswerApiController(RAGAnswerService answers, RunEventService events, SpaceAuthorization authorization,
                        ObjectMapper objectMapper, AnswerPersistencePort persistence,
                        AnswerAuthorizationContextFactory authorizationContexts,
                        RunRepository runs) {
        this(answers, events, authorization, objectMapper, new AnswerApiProjectionStore(persistence),
                authorizationContexts, runs);
    }

    AnswerApiController(RAGAnswerService answers, RunEventService events, SpaceAuthorization authorization,
                        ObjectMapper objectMapper, AnswerApiProjectionStore projections) {
        this(answers, events, authorization, objectMapper, projections, null);
    }

    AnswerApiController(RAGAnswerService answers, RunEventService events, SpaceAuthorization authorization,
                        ObjectMapper objectMapper, AnswerApiProjectionStore projections,
                        AnswerAuthorizationContextFactory authorizationContexts) {
        this(answers, events, authorization, objectMapper, projections, authorizationContexts, null);
    }

    AnswerApiController(RAGAnswerService answers, RunEventService events, SpaceAuthorization authorization,
                        ObjectMapper objectMapper, AnswerApiProjectionStore projections,
                        AnswerAuthorizationContextFactory authorizationContexts, RunRepository runs) {
        this.answers = answers;
        this.events = events;
        this.authorization = authorization;
        this.objectMapper = objectMapper;
        this.projections = projections;
        this.publisher = new AnswerEventPublisher(events, objectMapper, projections.persistence(), runs);
        this.sseAdapter = new AnswerSseEventAdapter(objectMapper);
        this.authorizationContexts = authorizationContexts;
    }

    @PostMapping(value = "/answers", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Answer create(@PathVariable UUID spaceId, @Valid @RequestBody CreateAnswerRequest request,
                         @RequestHeader("Idempotency-Key") String idempotencyKey,
                         Authentication authentication, HttpServletRequest servletRequest) {
        requireWrite(spaceId, authentication);
        Answer existing = projections.findByIdempotency(spaceId, idempotencyKey).orElse(null);
        if (existing != null) {
            if (!existing.runId().equals(request.runId())) {
                throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_conflict", "Idempotency conflict",
                        "Idempotency key is already bound to another run");
            }
            return existing;
        }
        UUID correlationId = correlationId(servletRequest);
        RunScope scope = new RunScope(spaceId, request.runId());
        ActiveGeneration active = new ActiveGeneration(UuidV7.random(), new CancellationToken());
        AnswerRequest answerRequest = request.toDomain(spaceId, correlationId, idempotencyKey,
                active.cancellationToken, active.answerId);
        if (activeGenerations.putIfAbsent(scope, active) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "answer_already_running", "Answer conflict",
                    "An answer is already running for this space and run");
        }
        GenerationStreamObserver observer = new GenerationStreamObserver() {
            @Override public UUID answerId() { return active.answerId; }
            @Override public void onDelta(String delta) {
                publisher.publishDelta(answerRequest, active.answerId, delta);
                active.deltaCount.incrementAndGet();
            }
        };
        RunEventStore.Subscription cancellationSubscription = null;
        try {
            java.util.function.Consumer<RunEvent> cancellationConsumer = event -> {
                if (isCancellationEvent(scope, event)) active.cancellationToken.cancel();
            };
            cancellationSubscription = events.subscribe(spaceId, request.runId(), cancellationConsumer);
            events.replay(spaceId, request.runId(), null).events().forEach(cancellationConsumer);
            cancellationSubscription.activate();
            Answer answer = authorizationContexts == null
                    ? answers.answer(answerRequest, null, observer)
                    : answers.answer(answerRequest, authorizationContexts.issue(principal(authentication), spaceId,
                    request.runId(), correlationId, request.runId()), observer);
            Answer stored = projections.saveIfAbsent(answer);
            publisher.publish(stored, active.deltaCount.get() > 0);
            return stored;
        } finally {
            if (cancellationSubscription != null) cancellationSubscription.close();
            activeGenerations.remove(scope, active);
        }
    }

    @GetMapping(value = "/answers/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Answer get(@PathVariable UUID spaceId, @PathVariable UUID runId, Authentication authentication) {
        requireMember(spaceId, authentication);
        return projections.find(spaceId, runId).orElseThrow(() -> notFound("answer_not_found", "Answer not found"));
    }

    @GetMapping(value = "/answers/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID spaceId, @PathVariable UUID runId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             Authentication authentication, HttpServletRequest request,
                             HttpServletResponse response) {
        requireMember(spaceId, authentication);
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader(CorrelationIdFilter.HEADER, CorrelationIdFilter.current(request));
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        RunEventStore.OpenedStream opened = events.openStream(spaceId, runId, lastEventId,
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

    @PostMapping(value = "/answers/{runId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CancelResponse cancel(@PathVariable UUID spaceId, @PathVariable UUID runId,
                                 @Valid @RequestBody(required = false) CancelRequest request,
                                 @RequestHeader("Idempotency-Key") String idempotencyKey,
                                 Authentication authentication, HttpServletRequest servletRequest) {
        requireWrite(spaceId, authentication);
        if (idempotencyKey == null || !idempotencyKey.matches("^[A-Za-z0-9._:-]{16,255}$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_idempotency_key", "Invalid request",
                    "Idempotency-Key is required");
        }
        UUID correlationId = correlationId(servletRequest);
        RunEventStore.CancellationResult result = events.cancel(spaceId, runId, correlationId);
        ActiveGeneration active = activeGenerations.get(new RunScope(spaceId, runId));
        if (active != null) active.cancellationToken.cancel();
        if (result.firstCancellation()) {
            projections.find(spaceId, runId).ifPresent(answer -> publisher.publishCancellation(answer, correlationId));
        }
        return new CancelResponse(runId, spaceId, "CANCELLED", result.firstCancellation(),
                result.event() == null ? null : result.event().eventId(), correlationId,
                request == null ? null : request.reason());
    }

    @GetMapping(value = "/runs/{runId}/citations/{evidenceId}/preview",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AnswerApiProjectionStore.CitationPreview citationPreview(@PathVariable UUID spaceId,
                                                                     @PathVariable UUID runId,
                                                                     @PathVariable UUID evidenceId,
                                                                     Authentication authentication) {
        requireMember(spaceId, authentication);
        return projections.preview(spaceId, runId, evidenceId);
    }

    ObjectNode eventEnvelope(RunEvent event) {
        return sseAdapter.toEnvelope(event);
    }

    AnswerApiProjectionStore projections() {
        return projections;
    }

    private void send(SseEmitter emitter, RunEvent event) {
        try {
            if (sseAdapter.isControlEvent(event)) {
                return;
            }
            ObjectNode envelope = eventEnvelope(event);
            emitter.send(SseEmitter.event().id(event.eventId().toString()).name(event.type())
                    .data(envelope, MediaType.APPLICATION_JSON));
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        } catch (RuntimeException exception) {
            // A protocol violation is observable as a failed stream; it is never converted into a partial answer.
            emitter.completeWithError(exception);
            throw exception;
        }
    }

    private void requireMember(UUID spaceId, Authentication authentication) {
        SessionPrincipal principal = principal(authentication);
        if (principal == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_required",
                "Authentication required", "A valid session is required");
        authorization.requireMember(spaceId, principal);
    }

    private void requireWrite(UUID spaceId, Authentication authentication) {
        SessionPrincipal principal = principal(authentication);
        if (principal == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_required",
                "Authentication required", "A valid session is required");
        authorization.requireWrite(spaceId, principal);
    }

    private static SessionPrincipal principal(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof SessionPrincipal value
                ? value : null;
    }

    private static UUID correlationId(HttpServletRequest request) {
        String value = CorrelationIdFilter.current(request);
        if (!UuidV7.isUuidV7(value)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_correlation_id", "Invalid request",
                    "X-Correlation-Id must be UUIDv7");
        }
        return UUID.fromString(value);
    }

    private static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, "Not found", message);
    }

    private boolean isCancellationEvent(RunScope scope, RunEvent event) {
        if (event == null || !RunEventStore.RUN_STATUS_EVENT_TYPE.equals(event.type())
                || !scope.spaceId.equals(event.spaceId()) || !scope.runId.equals(event.runId())) return false;
        try {
            return "CANCELLED".equals(objectMapper.readTree(event.payloadJson()).path("status").asText());
        } catch (Exception ignored) {
            return false;
        }
    }

    public record CreateAnswerRequest(@NotNull UUID runId, @NotBlank @Size(max = 32_000) String query,
                                      @NotNull UUID promptVersionId, @NotNull UUID modelRouteVersionId,
                                      @NotNull UUID modelProfileVersionId, @NotBlank @Size(max = 200) String model,
                                      @Min(1) @Max(200_000) int maxContextTokens,
                                      @Min(1) @Max(120) Integer timeoutSeconds,
                                      @Size(max = 8_000) String toolSchemaVersionsJson,
                                      @NotBlank @Size(min = 64, max = 64) String datasetHash,
                                      @NotBlank @Size(min = 64, max = 64) String configHash,
                                      Boolean allowCloudEgress) {
        AnswerRequest toDomain(UUID spaceId, UUID correlationId, String idempotencyKey) {
            return toDomain(spaceId, correlationId, idempotencyKey, new CancellationToken());
        }

        AnswerRequest toDomain(UUID spaceId, UUID correlationId, String idempotencyKey,
                               CancellationToken cancellationToken) {
            return toDomain(spaceId, correlationId, idempotencyKey, cancellationToken, null);
        }

        AnswerRequest toDomain(UUID spaceId, UUID correlationId, String idempotencyKey,
                               CancellationToken cancellationToken, UUID answerId) {
            return new AnswerRequest(spaceId, runId, correlationId, idempotencyKey, query, promptVersionId,
                    modelRouteVersionId, modelProfileVersionId, model,
                    Boolean.TRUE.equals(allowCloudEgress) ? EgressDecision.CLOUD_ALLOWED : EgressDecision.LOCAL_ONLY,
                    maxContextTokens, Duration.ofSeconds(timeoutSeconds == null ? 30 : timeoutSeconds),
                    toolSchemaVersionsJson, datasetHash, configHash, runId, cancellationToken, answerId);
        }
    }

    public record CancelRequest(@Size(max = 500) String reason) {
    }

    public record CancelResponse(UUID runId, UUID spaceId, String status, boolean firstCancellation,
                                 UUID eventId, UUID correlationId, String reason) {
    }

    private record RunScope(UUID spaceId, UUID runId) {
    }

    private static final class ActiveGeneration {
        private final UUID answerId;
        private final CancellationToken cancellationToken;
        private final AtomicInteger deltaCount = new AtomicInteger();

        private ActiveGeneration(UUID answerId, CancellationToken cancellationToken) {
            this.answerId = answerId;
            this.cancellationToken = cancellationToken;
        }
    }
}
