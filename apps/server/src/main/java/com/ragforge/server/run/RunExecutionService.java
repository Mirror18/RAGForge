package com.ragforge.server.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragforge.server.common.ApiException;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.provider.ProviderRepository;
import com.ragforge.server.provider.adapter.CancellationToken;
import com.ragforge.server.provider.adapter.ChatMessage;
import com.ragforge.server.provider.adapter.EgressClass;
import com.ragforge.server.provider.adapter.EgressDecision;
import com.ragforge.server.provider.adapter.EgressPolicy;
import com.ragforge.server.provider.adapter.ModelCapability;
import com.ragforge.server.provider.adapter.ProviderAdapter;
import com.ragforge.server.provider.adapter.ProviderAdapterException;
import com.ragforge.server.provider.adapter.ProviderChatRequest;
import com.ragforge.server.provider.adapter.ProviderChatResponse;
import com.ragforge.server.provider.adapter.ProviderConnection;
import com.ragforge.server.provider.adapter.ProviderErrorClass;
import com.ragforge.server.provider.adapter.ProviderType;
import com.ragforge.server.prompt.PromptRepository;
import com.ragforge.server.space.SpaceRepository;
import com.ragforge.server.space.SpaceRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Minimal synchronous no-RAG conversation execution boundary. */
@Service
public class RunExecutionService {
    private final ConversationRepository conversations;
    private final RunRepository runs;
    private final RunEventService events;
    private final SpaceRepository spaces;
    private final ProviderRepository providers;
    private final PromptRepository prompts;
    private final ProviderAdapterRegistry providerAdapters;
    private final ObjectMapper objectMapper;

    public RunExecutionService(ConversationRepository conversations, RunRepository runs, RunEventService events,
                               SpaceRepository spaces, ProviderRepository providers, PromptRepository prompts,
                               ProviderAdapterRegistry providerAdapters, ObjectMapper objectMapper) {
        this.conversations = Objects.requireNonNull(conversations);
        this.runs = Objects.requireNonNull(runs);
        this.events = Objects.requireNonNull(events);
        this.spaces = Objects.requireNonNull(spaces);
        this.providers = Objects.requireNonNull(providers);
        this.prompts = Objects.requireNonNull(prompts);
        this.providerAdapters = Objects.requireNonNull(providerAdapters);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Transactional
    public ConversationRepository.ConversationRecord createConversation(UUID spaceId, SessionPrincipal principal,
                                                                          String title) {
        requireRole(spaceId, principal, true);
        return conversations.create(UUID.randomUUID(), spaceId, principal.userId(), title, Instant.now());
    }

    /** Creates the run, executes it synchronously, and returns the persisted terminal/failed state. */
    public RunRepository.RunRecord createRun(UUID spaceId, UUID conversationId, SessionPrincipal principal,
                                              RunRequest request, UUID correlationId) {
        requireRole(spaceId, principal, true);
        ConversationRepository.ConversationRecord conversation = conversations.find(spaceId, conversationId)
                .orElseThrow(() -> notFound("conversation_not_found", "Conversation not found"));
        ValidatedRoute route = validateRoute(spaceId, request);
        PromptRepository.PromptVersion prompt = prompts.findVersion(spaceId, request.promptVersionId())
                .filter(item -> item.status() == PromptRepository.PromptStatus.PUBLISHED)
                .orElseThrow(() -> invalid("prompt_not_published", "Prompt version is not published"));

        Instant now = Instant.now();
        UUID runId = UUID.randomUUID();
        String inputHash = sha256(request.message());
        RunRepository.RunRecord run = runs.createRun(new RunRepository.NewRun(runId, spaceId, conversationId,
                principal.userId(), correlationId, RunRepository.RequestKind.CHAT, RunRepository.RunStatus.QUEUED,
                route.route().id(), prompt.id(), inputHash, null, null, null, null, null, now));
        emit(run, "run.status", statusPayload("QUEUED", null));
        execute(run, request, route, prompt);
        return runs.findRun(spaceId, runId).orElseThrow();
    }

    public RunRepository.RunRecord getRun(UUID spaceId, UUID runId, SessionPrincipal principal) {
        requireRole(spaceId, principal, false);
        return runs.findRun(spaceId, runId).orElseThrow(() -> notFound("run_not_found", "Run not found"));
    }

    public List<RunRepository.StepRecord> getSteps(UUID spaceId, UUID runId, SessionPrincipal principal) {
        getRun(spaceId, runId, principal);
        return runs.findSteps(spaceId, runId);
    }

    private void execute(RunRepository.RunRecord run, RunRequest request, ValidatedRoute route,
                         PromptRepository.PromptVersion prompt) {
        UUID spaceId = run.spaceId();
        Instant now = Instant.now();
        RunRepository.RunRecord running = runs.transitionRun(spaceId, run.id(), RunRepository.RunStatus.RUNNING,
                null, null, now, run.version());
        emit(running, "run.status", statusPayload("RUNNING", null));
        UUID stepId = UUID.randomUUID();
        RunRepository.StepRecord step = runs.createStep(new RunRepository.NewStep(stepId, spaceId, run.id(),
                "generate", RunRepository.StepType.GENERATE, 1, 1, RunRepository.RunStatus.QUEUED,
                null, null, now, run.correlationId()));
        emitStep(step);
        CancellationToken cancellation = new CancellationToken();
        UUID invocationId = UUID.randomUUID();
        String providerIdentity = "run-" + run.id();
        try {
            ProviderChatRequest providerRequest = new ProviderChatRequest(spaceId,
                    new com.ragforge.server.provider.adapter.RequestIdentity(run.id(), run.correlationId(), providerIdentity),
                    route.profile().modelName(), List.of(new ChatMessage("system", prompt.template()),
                            new ChatMessage("user", request.message())),
                    Duration.ofSeconds(request.timeoutSeconds()), route.profile().maxOutputTokens(),
                    java.util.Set.of(ModelCapability.CHAT), false);
            ProviderChatResponse response = providerAdapters.require(route.connection().providerType())
                    .chat(route.connection(), route.egressDecision(), providerRequest,
                            cancellation).toCompletableFuture().get(request.timeoutSeconds() + 1L, TimeUnit.SECONDS);
            String outputHash = sha256(response.content());
            runs.createInvocation(new RunRepository.NewModelInvocation(invocationId, spaceId, run.id(), stepId,
                    route.connectionRecord().id(), route.profile().id(), route.route().id(), prompt.id(), providerIdentity,
                    sha256(prompt.template() + "\n" + request.message()), "{\"messageCount\":2}", outputHash,
                    RunRepository.InvocationStatus.SUCCEEDED, null, null, Instant.now(), run.correlationId()));
            recordUsage(response, spaceId, invocationId, providerIdentity, run.correlationId(), request.message(), response.content());
            RunRepository.StepRecord completedStep = runs.updateStep(spaceId, stepId, RunRepository.RunStatus.SUCCEEDED,
                    null, null, Instant.now());
            emitStep(completedStep);
            RunRepository.RunRecord completed = runs.transitionRun(spaceId, run.id(), RunRepository.RunStatus.SUCCEEDED,
                    null, null, Instant.now(), running.version(), outputHash);
            emit(completed, "run.status", statusPayload("SUCCEEDED", null));
            emit(completed, "run.completed", answerPayload(outputHash));
        } catch (Exception failure) {
            Throwable cause = unwrap(failure);
            ProviderErrorClass providerError = cause instanceof ProviderAdapterException exception
                    ? exception.errorClass() : ProviderErrorClass.INVALID_RESPONSE;
            RunRepository.ErrorClass errorClass = mapError(providerError);
            String errorCode = providerError.name().toLowerCase(java.util.Locale.ROOT);
            runs.createInvocation(new RunRepository.NewModelInvocation(invocationId, spaceId, run.id(), stepId,
                    route.connectionRecord().id(), route.profile().id(), route.route().id(), prompt.id(), providerIdentity,
                    sha256(prompt.template() + "\n" + request.message()), "{\"messageCount\":2}", null,
                    providerError == ProviderErrorClass.CANCELLED ? RunRepository.InvocationStatus.CANCELLED
                            : RunRepository.InvocationStatus.FAILED,
                    errorClass, errorCode, Instant.now(), run.correlationId()));
            RunRepository.StepRecord failedStep = runs.updateStep(spaceId, stepId,
                    providerError == ProviderErrorClass.CANCELLED ? RunRepository.RunStatus.CANCELLED : RunRepository.RunStatus.FAILED,
                    errorClass, errorCode, Instant.now());
            emitStep(failedStep);
            RunRepository.RunRecord failed = runs.transitionRun(spaceId, run.id(),
                    providerError == ProviderErrorClass.CANCELLED ? RunRepository.RunStatus.CANCELLED : RunRepository.RunStatus.FAILED,
                    errorClass, errorCode, Instant.now(), running.version());
            emit(failed, "run.status", statusPayload(failed.status().name(), errorCode));
        }
    }

    private void recordUsage(ProviderChatResponse response, UUID spaceId, UUID invocationId, String identity,
                             UUID correlationId, String input, String output) {
        var usage = response.usage();
        RunRepository.UsageSource source = usage != null && usage.source() == com.ragforge.server.provider.adapter.UsageSource.PROVIDER_REPORTED
                ? RunRepository.UsageSource.PROVIDER_REPORTED : RunRepository.UsageSource.LOCAL_ESTIMATE;
        Long inputTokens = usage == null ? (long) input.length() : usage.promptTokens();
        Long outputTokens = usage == null ? (long) output.length() : usage.completionTokens();
        Long totalTokens = usage == null ? inputTokens + outputTokens : usage.totalTokens();
        runs.recordUsage(new RunRepository.NewUsageLedgerEntry(UUID.randomUUID(), spaceId, invocationId, identity,
                source, "run-" + invocationId + "-" + source.name().toLowerCase(java.util.Locale.ROOT), inputTokens,
                outputTokens, totalTokens, BigDecimal.ZERO, "USD", "{\"source\":\"execution\"}", Instant.now(), correlationId));
    }

    private ValidatedRoute validateRoute(UUID spaceId, RunRequest request) {
        ProviderRepository.ModelRouteVersion route = providers.findRouteVersion(spaceId, request.routeVersionId())
                .filter(item -> item.status() == ProviderRepository.ModelRouteStatus.PUBLISHED
                        && item.purpose() == ProviderRepository.RoutePurpose.CHAT)
                .orElseThrow(() -> invalid("route_not_published", "Chat route is not published in this space"));
        ProviderRepository.ModelProfileVersion profile = providers.findProfileVersion(spaceId, request.profileVersionId())
                .filter(item -> item.status() == ProviderRepository.ModelProfileStatus.PUBLISHED)
                .orElseThrow(() -> invalid("profile_not_published", "Model profile is not published in this space"));
        ProviderRepository.ProviderConnection connection = providers.findConnection(spaceId, request.providerConnectionId())
                .filter(item -> item.spaceId() != null && item.spaceId().equals(spaceId)
                        && item.status() == ProviderRepository.ProviderStatus.ACTIVE)
                .orElseThrow(() -> invalid("provider_not_active", "Provider connection is not active in this space"));
        if (!profile.providerConnectionId().equals(connection.id())) {
            throw invalid("provider_profile_mismatch", "Model profile does not use the requested provider connection");
        }
        if (providers.listRouteCandidates(spaceId, route.id()).stream()
                .noneMatch(candidate -> candidate.profileVersionId().equals(profile.id()))) {
            throw invalid("route_profile_mismatch", "Model profile is not a candidate of the requested route");
        }
        if (request.allowCloudEgress() && route.egressPolicy() != ProviderRepository.EgressPolicy.CLOUD_ALLOWED) {
            throw invalid("cloud_egress_not_allowed", "Cloud egress was not explicitly allowed by the route");
        }
        EgressDecision decision = request.allowCloudEgress() ? EgressDecision.CLOUD_ALLOWED : EgressDecision.LOCAL_ONLY;
        EgressClass egressClass = connection.egressPolicy() == ProviderRepository.EgressPolicy.CLOUD_ALLOWED
                ? EgressClass.CLOUD : EgressClass.LOCAL;
        ProviderConnection adapterConnection = new ProviderConnection(spaceId, connection.id(), Math.max(1, connection.version()),
                ProviderType.valueOf(connection.providerType().name()), egressClass,
                URI.create(connection.endpointUri()), connection.credentialRef() == null ? "fake-ref" : connection.credentialRef());
        EgressPolicy.validateConnection(spaceId, decision, adapterConnection);
        return new ValidatedRoute(route, profile, connection, adapterConnection, decision);
    }

    private void requireRole(UUID spaceId, SessionPrincipal principal, boolean write) {
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_required", "Authentication required", "A valid session is required");
        }
        SpaceRole role = spaces.findRole(spaceId, principal.userId()).orElseThrow(() ->
                notFound("space_not_found", "Space not found"));
        if (write && role == SpaceRole.VIEWER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "space_role_denied", "Forbidden", "Editor role is required");
        }
    }

    private void emit(RunRepository.RunRecord run, String type, ObjectNode payload) {
        events.append(run.spaceId(), run.id(), run.correlationId(), type, 1, json(payload));
    }

    private void emitStep(RunRepository.StepRecord step) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("stepId", step.id().toString());
        payload.put("status", step.status().name());
        if (step.errorCode() != null) payload.put("errorCode", step.errorCode());
        events.append(step.spaceId(), step.runId(), step.correlationId(), "step.status", 1, json(payload));
    }

    private ObjectNode statusPayload(String status, String errorCode) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("status", status);
        if (errorCode != null) payload.put("errorCode", errorCode);
        return payload;
    }

    private ObjectNode answerPayload(String outputHash) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("status", "SUCCEEDED");
        payload.put("outputHash", outputHash);
        return payload;
    }

    private String json(ObjectNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Event payload could not be serialized", exception);
        }
    }

    private static RunRepository.ErrorClass mapError(ProviderErrorClass error) {
        try {
            return RunRepository.ErrorClass.valueOf(error.name());
        } catch (IllegalArgumentException ignored) {
            return RunRepository.ErrorClass.INVALID_RESPONSE;
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof java.util.concurrent.ExecutionException
                || current instanceof java.util.concurrent.CompletionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is required by the runtime", exception);
        }
    }

    private static ApiException invalid(String code, String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, "Invalid run request", detail);
    }

    private static ApiException notFound(String code, String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, code, "Not found", detail);
    }

    public record RunRequest(UUID routeVersionId, UUID profileVersionId, UUID providerConnectionId,
                             UUID promptVersionId, String message, boolean allowCloudEgress,
                             int timeoutSeconds) {
        public RunRequest {
            Objects.requireNonNull(routeVersionId);
            Objects.requireNonNull(profileVersionId);
            Objects.requireNonNull(providerConnectionId);
            Objects.requireNonNull(promptVersionId);
            if (message == null || message.isBlank() || message.length() > 32_000) {
                throw new IllegalArgumentException("Run message is invalid");
            }
            if (timeoutSeconds < 1 || timeoutSeconds > 120) {
                throw new IllegalArgumentException("Run timeout is invalid");
            }
        }
    }

    private record ValidatedRoute(ProviderRepository.ModelRouteVersion route,
                                  ProviderRepository.ModelProfileVersion profile,
                                  ProviderRepository.ProviderConnection connectionRecord,
                                  ProviderConnection connection, EgressDecision egressDecision) {
    }
}
