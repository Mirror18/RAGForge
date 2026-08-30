package com.ragforge.server.run;

import com.ragforge.server.common.CorrelationIdFilter;
import com.ragforge.server.identity.SessionPrincipal;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}")
public class RunExecutionController {
    private final RunExecutionService service;
    private final RunEventService eventService;
    private final JdbcTemplate jdbc;

    public RunExecutionController(RunExecutionService service) {
        this(service, null, null);
    }

    @Autowired
    public RunExecutionController(RunExecutionService service, RunEventService eventService, JdbcTemplate jdbc) {
        this.service = service;
        this.eventService = eventService;
        this.jdbc = jdbc;
    }

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationResponse createConversation(@PathVariable UUID spaceId,
                                                   @Valid @RequestBody CreateConversationRequest request,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                   Authentication authentication) {
        var conversation = service.createConversation(spaceId, principal(authentication), request.title(),
                commandKey(idempotencyKey));
        return ConversationResponse.from(conversation);
    }

    @GetMapping("/conversations")
    public ConversationPageResponse listConversations(@PathVariable UUID spaceId,
                                                      @RequestParam(defaultValue = "false") boolean includeArchived,
                                                      Authentication authentication) {
        return new ConversationPageResponse(service.listConversations(spaceId, principal(authentication), includeArchived)
                .stream().map(ConversationResponse::from).toList());
    }

    @GetMapping("/conversations/{conversationId}/runs")
    public RunPageResponse listConversationRuns(@PathVariable UUID spaceId, @PathVariable UUID conversationId,
                                                Authentication authentication) {
        return new RunPageResponse(service.listConversationRuns(spaceId, conversationId, principal(authentication))
                .stream().map(run -> RunResponse.from(run, jdbc)).toList());
    }

    @PostMapping("/conversations/{conversationId}/archive")
    public ConversationResponse archiveConversation(@PathVariable UUID spaceId, @PathVariable UUID conversationId,
                                                    @RequestBody(required = false) ConversationCommandRequest request,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                    Authentication authentication) {
        if (request == null) {
            return ConversationResponse.from(service.archiveConversation(spaceId, conversationId, principal(authentication)));
        }
        return ConversationResponse.from(service.archiveConversation(spaceId, conversationId, principal(authentication),
                request.version(), commandKey(idempotencyKey), "ARCHIVE"));
    }

    @DeleteMapping("/conversations/{conversationId}")
    public ConversationResponse deleteConversation(@PathVariable UUID spaceId, @PathVariable UUID conversationId,
                                                   @RequestBody(required = false) ConversationCommandRequest request,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                   Authentication authentication) {
        if (request == null) {
            return ConversationResponse.from(service.archiveConversation(spaceId, conversationId, principal(authentication)));
        }
        return ConversationResponse.from(service.archiveConversation(spaceId, conversationId, principal(authentication),
                request.version(), commandKey(idempotencyKey), "DELETE"));
    }

    @PutMapping("/conversations/{conversationId}")
    public ConversationResponse renameConversation(@PathVariable UUID spaceId, @PathVariable UUID conversationId,
                                                   @Valid @RequestBody RenameConversationRequest request,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                   Authentication authentication) {
        return ConversationResponse.from(service.renameConversation(spaceId, conversationId, principal(authentication),
                request.title(), request.version(), commandKey(idempotencyKey)));
    }

    @PostMapping("/conversations/{conversationId}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunResponse createRun(@PathVariable UUID spaceId, @PathVariable UUID conversationId,
                                 @Valid @RequestBody CreateRunRequest request,
                                 Authentication authentication, HttpServletRequest servletRequest) {
        UUID correlationId = UUID.fromString(CorrelationIdFilter.current(servletRequest));
        RunExecutionService.RunRequest runRequest = new RunExecutionService.RunRequest(
                request.routeVersionId(), request.profileVersionId(), request.providerConnectionId(),
                request.promptVersionId(), request.message(), request.allowCloudEgress(),
                request.timeoutSeconds() == null ? 30 : request.timeoutSeconds());
        return RunResponse.from(service.createRun(spaceId, conversationId, principal(authentication),
                runRequest, correlationId), jdbc);
    }

    @GetMapping("/runs/{runId}")
    public RunSnapshotResponse getRun(@PathVariable UUID spaceId, @PathVariable UUID runId,
                                      Authentication authentication) {
        var principal = principal(authentication);
        var run = service.getRun(spaceId, runId, principal);
        var steps = service.getSteps(spaceId, runId, principal);
        long lastSequence = eventService == null
                ? steps.stream().mapToLong(RunRepository.StepRecord::sequenceNo).max().orElse(0L)
                : eventService.replay(spaceId, runId, null).latestSequence();
        return RunSnapshotResponse.from(run, steps, lastSequence, jdbc);
    }

    @PostMapping("/runs/{runId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunResponse retry(@PathVariable UUID spaceId, @PathVariable UUID runId,
                             Authentication authentication, HttpServletRequest servletRequest) {
        UUID correlationId = UUID.fromString(CorrelationIdFilter.current(servletRequest));
        return RunResponse.from(service.retry(spaceId, runId, principal(authentication), correlationId), jdbc);
    }

    @GetMapping("/runs/{runId}/steps")
    public StepPageResponse getSteps(@PathVariable UUID spaceId, @PathVariable UUID runId,
                                     Authentication authentication) {
        return new StepPageResponse(service.getSteps(spaceId, runId, principal(authentication)).stream()
                .map(StepResponse::from).toList(), null);
    }

    private static SessionPrincipal principal(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof SessionPrincipal value
                ? value : null;
    }

    public record CreateConversationRequest(@NotBlank @Size(max = 200) String title) {
    }

    public record RenameConversationRequest(@NotBlank @Size(max = 200) String title,
                                            @NotNull Long version) {
    }

    public record ConversationCommandRequest(@NotNull Long version) {
    }

    public record CreateRunRequest(@NotNull UUID routeVersionId, @NotNull UUID profileVersionId,
                                   @NotNull UUID providerConnectionId, @NotNull UUID promptVersionId,
                                   @NotBlank @Size(max = 32_000) String message,
                                   boolean allowCloudEgress, @jakarta.validation.constraints.Min(1)
                                   @jakarta.validation.constraints.Max(120) Integer timeoutSeconds) {
    }

    public record ConversationResponse(UUID id, UUID spaceId, UUID actorUserId, String title, String status,
                                       java.time.Instant archivedAt, java.time.Instant createdAt,
                                       java.time.Instant updatedAt, long version) {
        static ConversationResponse from(ConversationRepository.ConversationRecord value) {
            return new ConversationResponse(value.id(), value.spaceId(), value.actorUserId(), value.title(),
                    value.status(), value.archivedAt(), value.createdAt(), value.updatedAt(), value.version());
        }
    }

    public record ConversationPageResponse(List<ConversationResponse> items) {
    }

    public record RunPageResponse(List<RunResponse> items) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RunResponse(UUID runId, UUID spaceId, UUID conversationId, long version, String status,
                              UUID correlationId, UUID modelRouteId, UUID promptVersionId, UUID usageLedgerId,
                              boolean cancelRequested, RunErrorResponse error,
                              java.time.Instant createdAt, java.time.Instant startedAt,
                              java.time.Instant finishedAt) {
        static RunResponse from(RunRepository.RunRecord value, JdbcTemplate jdbc) {
            return new RunResponse(value.id(), value.spaceId(), value.conversationId(),
                    Math.max(1L, value.version()), value.status().name(), value.correlationId(),
                    value.routeVersionId(), value.promptVersionId(), findUsageLedgerId(value, jdbc),
                    value.status() == RunRepository.RunStatus.CANCELLED
                            || value.errorClass() == RunRepository.ErrorClass.CANCELLED,
                    RunErrorResponse.from(value), value.createdAt(), value.startedAt(), value.completedAt());
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RunSnapshotResponse(UUID runId, UUID spaceId, UUID conversationId, long version, String status,
                                      UUID correlationId, UUID modelRouteId, UUID promptVersionId, UUID usageLedgerId,
                                      boolean cancelRequested, RunErrorResponse error,
                                      java.time.Instant createdAt, java.time.Instant startedAt,
                                      java.time.Instant finishedAt, long lastSequence, List<StepResponse> steps) {
        static RunSnapshotResponse from(RunRepository.RunRecord value, List<RunRepository.StepRecord> steps,
                                        long lastSequence, JdbcTemplate jdbc) {
            RunResponse run = RunResponse.from(value, jdbc);
            return new RunSnapshotResponse(run.runId(), run.spaceId(), run.conversationId(), run.version(),
                    run.status(), run.correlationId(), run.modelRouteId(), run.promptVersionId(),
                    run.usageLedgerId(), run.cancelRequested(), run.error(), run.createdAt(), run.startedAt(),
                    run.finishedAt(), Math.max(0L, lastSequence), steps.stream().map(StepResponse::from).toList());
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record StepPageResponse(List<StepResponse> items, String nextCursor) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record StepResponse(UUID stepId, UUID spaceId, UUID runId, long version, int sequence, String type,
                               String status, UUID correlationId, int attempt, java.time.Instant createdAt,
                               java.time.Instant finishedAt, RunErrorResponse error) {
        static StepResponse from(RunRepository.StepRecord value) {
            boolean finished = value.status() == RunRepository.RunStatus.SUCCEEDED
                    || value.status() == RunRepository.RunStatus.FAILED
                    || value.status() == RunRepository.RunStatus.CANCELLED;
            return new StepResponse(value.id(), value.spaceId(), value.runId(), 1L,
                    Math.max(1, value.sequenceNo()), value.stepType().name(), value.status().name(),
                    value.correlationId(), value.attempt(), value.createdAt(),
                    finished ? value.updatedAt() : null, RunErrorResponse.from(value));
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RunErrorResponse(String errorClass, boolean retryable, String message,
                                   UUID correlationId, Integer retryAfterSeconds) {
        static RunErrorResponse from(RunRepository.RunRecord value) {
            if (value.errorClass() == null) {
                return null;
            }
            return new RunErrorResponse(value.errorClass().name(), retryable(value.errorClass()),
                    safeMessage(value.errorCode()), value.correlationId(), null);
        }

        static RunErrorResponse from(RunRepository.StepRecord value) {
            if (value.errorClass() == null) {
                return null;
            }
            return new RunErrorResponse(value.errorClass().name(), retryable(value.errorClass()),
                    safeMessage(value.errorCode()), value.correlationId(), null);
        }

        private static boolean retryable(RunRepository.ErrorClass errorClass) {
            return errorClass == RunRepository.ErrorClass.TIMEOUT
                    || errorClass == RunRepository.ErrorClass.UNAVAILABLE
                    || errorClass == RunRepository.ErrorClass.RATE_LIMIT;
        }

        private static String safeMessage(String errorCode) {
            return errorCode == null || errorCode.isBlank()
                    ? "Run execution failed" : "Run execution failed: " + errorCode;
        }
    }

    /**
     * A run may have provider-reported and local-estimate ledger rows. Until the public
     * usage projection exists, this field names the actual preferred persisted row: provider
     * reported usage wins, then the oldest local estimate. No synthetic ID is emitted.
     */
    private static UUID findUsageLedgerId(RunRepository.RunRecord value, JdbcTemplate jdbc) {
        if (jdbc == null) {
            return null;
        }
        List<UUID> ids = jdbc.query("""
                        SELECT u.id
                        FROM usage_ledger u
                        JOIN model_invocations i
                          ON i.id = u.model_invocation_id AND i.space_id = u.space_id
                        WHERE u.space_id = ? AND i.space_id = ? AND i.run_id = ?
                        ORDER BY CASE WHEN u.usage_source = 'PROVIDER_REPORTED' THEN 0 ELSE 1 END,
                                 u.created_at, u.id
                        LIMIT 1
                        """, (rs, rowNum) -> rs.getObject("id", UUID.class),
                value.spaceId(), value.spaceId(), value.id());
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private static String commandKey(String value) {
        if (value == null || value.isBlank()) return "legacy-" + UUID.randomUUID();
        if (!value.matches("^[A-Za-z0-9._:-]{16,255}$")) {
            throw new com.ragforge.server.common.ApiException(HttpStatus.BAD_REQUEST, "invalid_idempotency_key",
                    "Invalid request", "Idempotency-Key is invalid");
        }
        return value;
    }
}
