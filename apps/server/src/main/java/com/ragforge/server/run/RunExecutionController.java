package com.ragforge.server.run;

import com.ragforge.server.common.CorrelationIdFilter;
import com.ragforge.server.identity.SessionPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public RunExecutionController(RunExecutionService service) {
        this.service = service;
    }

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationResponse createConversation(@PathVariable UUID spaceId,
                                                   @Valid @RequestBody CreateConversationRequest request,
                                                   Authentication authentication) {
        var conversation = service.createConversation(spaceId, principal(authentication), request.title());
        return ConversationResponse.from(conversation);
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
                runRequest, correlationId));
    }

    @GetMapping("/runs/{runId}")
    public RunResponse getRun(@PathVariable UUID spaceId, @PathVariable UUID runId,
                              Authentication authentication) {
        return RunResponse.from(service.getRun(spaceId, runId, principal(authentication)));
    }

    @GetMapping("/runs/{runId}/steps")
    public List<StepResponse> getSteps(@PathVariable UUID spaceId, @PathVariable UUID runId,
                                       Authentication authentication) {
        return service.getSteps(spaceId, runId, principal(authentication)).stream()
                .map(StepResponse::from).toList();
    }

    private static SessionPrincipal principal(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof SessionPrincipal value
                ? value : null;
    }

    public record CreateConversationRequest(@NotBlank @Size(max = 200) String title) {
    }

    public record CreateRunRequest(@NotNull UUID routeVersionId, @NotNull UUID profileVersionId,
                                   @NotNull UUID providerConnectionId, @NotNull UUID promptVersionId,
                                   @NotBlank @Size(max = 32_000) String message,
                                   boolean allowCloudEgress, @jakarta.validation.constraints.Min(1)
                                   @jakarta.validation.constraints.Max(120) Integer timeoutSeconds) {
    }

    public record ConversationResponse(UUID id, UUID spaceId, UUID actorUserId, String title,
                                       java.time.Instant createdAt, java.time.Instant updatedAt) {
        static ConversationResponse from(ConversationRepository.ConversationRecord value) {
            return new ConversationResponse(value.id(), value.spaceId(), value.actorUserId(), value.title(),
                    value.createdAt(), value.updatedAt());
        }
    }

    public record RunResponse(UUID id, UUID spaceId, UUID conversationId, UUID correlationId,
                              String requestKind, String status, UUID routeVersionId, UUID promptVersionId,
                              String inputHash, String outputHash, String errorClass, String errorCode,
                              java.time.Instant startedAt, java.time.Instant completedAt,
                              java.time.Instant createdAt, java.time.Instant updatedAt) {
        static RunResponse from(RunRepository.RunRecord value) {
            return new RunResponse(value.id(), value.spaceId(), value.conversationId(), value.correlationId(),
                    value.requestKind().name(), value.status().name(), value.routeVersionId(), value.promptVersionId(),
                    value.inputHash(), value.outputHash(), value.errorClass() == null ? null : value.errorClass().name(),
                    value.errorCode(), value.startedAt(), value.completedAt(), value.createdAt(), value.updatedAt());
        }
    }

    public record StepResponse(UUID id, UUID runId, String stepKey, String stepType, int attempt,
                               int sequenceNo, String status, String errorClass, String errorCode,
                               java.time.Instant createdAt, java.time.Instant updatedAt) {
        static StepResponse from(RunRepository.StepRecord value) {
            return new StepResponse(value.id(), value.runId(), value.stepKey(), value.stepType().name(),
                    value.attempt(), value.sequenceNo(), value.status().name(),
                    value.errorClass() == null ? null : value.errorClass().name(), value.errorCode(),
                    value.createdAt(), value.updatedAt());
        }
    }
}
