package com.ragforge.server.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragforge.server.common.ApiException;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.provider.ProviderRepository;
import com.ragforge.server.provider.SpaceBindingRepository;
import com.ragforge.server.provider.adapter.CancellationToken;
import com.ragforge.server.provider.adapter.ChatMessage;
import com.ragforge.server.provider.adapter.EgressClass;
import com.ragforge.server.provider.adapter.EgressDecision;
import com.ragforge.server.provider.adapter.EgressPolicy;
import com.ragforge.server.provider.adapter.ModelCapability;
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
import java.util.Set;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Minimal synchronous no-RAG conversation execution boundary. */
@Service
public class RunExecutionService {
    private final ConversationRepository conversations;
    private final RunRepository runs;
    private final RunEventService events;
    private final SpaceRepository spaces;
    private final ProviderRepository providers;
    private final SpaceBindingRepository bindings;
    private final PromptRepository prompts;
    private final ProviderAdapterRegistry providerAdapters;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<UUID, ExecutionControl> controls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, RunRequest> executionRequests = new ConcurrentHashMap<>();
    private static final Set<RunRepository.ErrorClass> RETRYABLE_ERRORS = Set.of(
            RunRepository.ErrorClass.TIMEOUT, RunRepository.ErrorClass.UNAVAILABLE,
            RunRepository.ErrorClass.RATE_LIMIT);

    public RunExecutionService(ConversationRepository conversations, RunRepository runs, RunEventService events,
                               SpaceRepository spaces, ProviderRepository providers, SpaceBindingRepository bindings,
                               PromptRepository prompts,
                               ProviderAdapterRegistry providerAdapters, ObjectMapper objectMapper) {
        this.conversations = Objects.requireNonNull(conversations);
        this.runs = Objects.requireNonNull(runs);
        this.events = Objects.requireNonNull(events);
        this.spaces = Objects.requireNonNull(spaces);
        this.providers = Objects.requireNonNull(providers);
        this.bindings = Objects.requireNonNull(bindings);
        this.prompts = Objects.requireNonNull(prompts);
        this.providerAdapters = Objects.requireNonNull(providerAdapters);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Transactional
    public ConversationRepository.ConversationRecord createConversation(UUID spaceId, SessionPrincipal principal,
                                                                          String title) {
        return createConversation(spaceId, principal, title, "legacy-" + UUID.randomUUID());
    }

    @Transactional
    public ConversationRepository.ConversationRecord createConversation(UUID spaceId, SessionPrincipal principal,
                                                                          String title, String idempotencyKey) {
        requireRole(spaceId, principal, true);
        String requestHash = sha256("CREATE|" + title.trim());
        ConversationRepository.CommandReceipt receipt = conversations.findCommand(spaceId, idempotencyKey).orElse(null);
        if (receipt != null) {
            if (!requestHash.equals(receipt.requestHash()) || !"CREATE".equals(receipt.operation())) {
                throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_conflict", "Command conflict",
                        "Idempotency key is already bound to another conversation command");
            }
            return conversations.find(spaceId, receipt.conversationId()).orElseThrow(() ->
                    notFound("conversation_not_found", "Conversation not found"));
        }
        Instant now = Instant.now();
        ConversationRepository.ConversationRecord created = conversations.create(UUID.randomUUID(), spaceId,
                principal.userId(), title, now);
        conversations.recordCommand(UUID.randomUUID(), spaceId, created.id(), "CREATE", idempotencyKey,
                requestHash, null, created.version(), principal.userId(), now);
        return created;
    }

    public List<ConversationRepository.ConversationRecord> listConversations(UUID spaceId,
                                                                               SessionPrincipal principal,
                                                                               boolean includeArchived) {
        requireRole(spaceId, principal, false);
        return conversations.list(spaceId, includeArchived);
    }

    public List<RunRepository.RunRecord> listConversationRuns(UUID spaceId, UUID conversationId,
                                                               SessionPrincipal principal) {
        requireRole(spaceId, principal, false);
        conversations.find(spaceId, conversationId)
                .orElseThrow(() -> notFound("conversation_not_found", "Conversation not found"));
        return runs.findRuns(spaceId, conversationId);
    }

    @Transactional
    public ConversationRepository.ConversationRecord archiveConversation(UUID spaceId, UUID conversationId,
                                                                          SessionPrincipal principal) {
        ConversationRepository.ConversationRecord current = conversations.find(spaceId, conversationId)
                .orElseThrow(() -> notFound("conversation_not_found", "Conversation not found"));
        return archiveConversation(spaceId, conversationId, principal, current.version(),
                "legacy-" + UUID.randomUUID(), "ARCHIVE");
    }

    @Transactional
    public ConversationRepository.ConversationRecord archiveConversation(UUID spaceId, UUID conversationId,
                                                                          SessionPrincipal principal, long expectedVersion,
                                                                          String idempotencyKey, String operation) {
        requireRole(spaceId, principal, true);
        ConversationRepository.ConversationRecord current = conversations.find(spaceId, conversationId)
                .orElseThrow(() -> notFound("conversation_not_found", "Conversation not found"));
        String requestHash = sha256(operation + "|" + conversationId + "|" + expectedVersion);
        ConversationRepository.CommandReceipt receipt = conversations.findCommand(spaceId, idempotencyKey).orElse(null);
        if (receipt != null) {
            if (!requestHash.equals(receipt.requestHash()) || !operation.equals(receipt.operation())) {
                throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_conflict", "Command conflict",
                        "Idempotency key is already bound to another conversation command");
            }
            return conversations.find(spaceId, conversationId).orElseThrow();
        }
        Instant now = Instant.now();
        ConversationRepository.ConversationRecord result;
        try {
            result = conversations.archive(spaceId, conversationId, principal.userId(), now, expectedVersion);
        } catch (ConversationRepository.OptimisticLockException conflict) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "conversation_version_conflict",
                    "Conversation version conflict", "Refresh the conversation before changing it");
        }
        conversations.recordCommand(UUID.randomUUID(), spaceId, conversationId, operation, idempotencyKey,
                requestHash, expectedVersion, result.version(), principal.userId(), now);
        return result;
    }

    @Transactional
    public ConversationRepository.ConversationRecord renameConversation(UUID spaceId, UUID conversationId,
                                                                         SessionPrincipal principal, String title,
                                                                         long expectedVersion, String idempotencyKey) {
        requireRole(spaceId, principal, true);
        conversations.find(spaceId, conversationId)
                .orElseThrow(() -> notFound("conversation_not_found", "Conversation not found"));
        String requestHash = sha256("RENAME|" + conversationId + "|" + title.trim() + "|" + expectedVersion);
        ConversationRepository.CommandReceipt receipt = conversations.findCommand(spaceId, idempotencyKey).orElse(null);
        if (receipt != null) {
            if (!requestHash.equals(receipt.requestHash()) || !"RENAME".equals(receipt.operation())) {
                throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_conflict", "Command conflict",
                        "Idempotency key is already bound to another conversation command");
            }
            return conversations.find(spaceId, conversationId).orElseThrow();
        }
        Instant now = Instant.now();
        ConversationRepository.ConversationRecord result;
        try {
            result = conversations.rename(spaceId, conversationId, title, now, expectedVersion);
        } catch (ConversationRepository.OptimisticLockException conflict) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "conversation_version_conflict",
                    "Conversation version conflict", "Refresh the conversation before changing it");
        }
        conversations.recordCommand(UUID.randomUUID(), spaceId, conversationId, "RENAME", idempotencyKey,
                requestHash, expectedVersion, result.version(), principal.userId(), now);
        return result;
    }

    /** Creates the run, executes it synchronously, and returns the persisted terminal/failed state. */
    public RunRepository.RunRecord createRun(UUID spaceId, UUID conversationId, SessionPrincipal principal,
                                              RunRequest request, UUID correlationId) {
        requireRole(spaceId, principal, true);
        ConversationRepository.ConversationRecord conversation = conversations.find(spaceId, conversationId)
                .orElseThrow(() -> notFound("conversation_not_found", "Conversation not found"));
        if (!"ACTIVE".equals(conversation.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "conversation_archived", "Conversation archived",
                    "Archived conversations cannot receive new questions");
        }
        SpaceBindingRepository.SpaceBindingRecord binding = bindings.findCurrent(spaceId)
                .filter(item -> spaceId.equals(item.spaceId()))
                .orElseThrow(() -> notFound("space_binding_not_found", "Space binding not found"));
        validateBinding(spaceId, request, binding);
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
        executionRequests.put(runId, request);
        ExecutionControl control = new ExecutionControl();
        controls.put(runId, control);
        emit(run, "run.status", statusPayload("QUEUED", null));
        try {
            execute(run, request, route, prompt, control);
        } finally {
            controls.remove(runId, control);
        }
        RunRepository.RunRecord completed = runs.findRun(spaceId, runId).orElseThrow();
        if (completed.status() == RunRepository.RunStatus.CANCELLED
                && runs.findSteps(spaceId, runId).isEmpty()) {
            recordCancellationBeforeExecution(completed, request, route, prompt);
        }
        return runs.findRun(spaceId, runId).orElseThrow();
    }

    public RunRepository.RunRecord cancel(UUID spaceId, UUID runId, SessionPrincipal principal,
                                          UUID correlationId) {
        requireRole(spaceId, principal, true);
        ExecutionControl control = controls.get(runId);
        RunRepository.RunRecord current = runs.findRun(spaceId, runId)
                .orElseThrow(() -> notFound("run_not_found", "Run not found"));
        if (current.status() == RunRepository.RunStatus.QUEUED
                || current.status() == RunRepository.RunStatus.RUNNING
                || current.status() == RunRepository.RunStatus.CANCELLED) {
            try {
                events.cancel(spaceId, runId, correlationId);
            } catch (IllegalStateException race) {
                RunRepository.RunRecord afterRace = runs.findRun(spaceId, runId).orElseThrow();
                if (afterRace.status() != RunRepository.RunStatus.SUCCEEDED
                        && afterRace.status() != RunRepository.RunStatus.FAILED
                        && afterRace.status() != RunRepository.RunStatus.CANCELLED) {
                    throw race;
                }
            }
        }
        // Signal the provider only after the durable transition commits, so the
        // execution future cannot return a stale QUEUED record.
        if (control != null) {
            control.cancel();
        }
        return runs.findRun(spaceId, runId).orElseThrow();
    }

    public RunRepository.RunRecord retry(UUID spaceId, UUID runId, SessionPrincipal principal,
                                         UUID correlationId) {
        requireRole(spaceId, principal, true);
        RunRepository.RunRecord failed = runs.findRun(spaceId, runId)
                .orElseThrow(() -> notFound("run_not_found", "Run not found"));
        if (failed.status() != RunRepository.RunStatus.FAILED
                || !RETRYABLE_ERRORS.contains(failed.errorClass())) {
            throw new ApiException(HttpStatus.CONFLICT, "run_not_retryable", "Run is not retryable",
                    "Only retryable failed runs can be retried");
        }
        RunRequest request = executionRequests.get(runId);
        if (request == null || failed.conversationId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "retry_context_unavailable", "Retry is unavailable",
                    "The execution request is no longer available");
        }
        return createRun(spaceId, failed.conversationId(), principal, request, correlationId);
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
                         PromptRepository.PromptVersion prompt, ExecutionControl control) {
        UUID spaceId = run.spaceId();
        Instant now = Instant.now();
        RunRepository.RunRecord running;
        try {
            if (control.token.isCancellationRequested()
                    || runs.findRun(spaceId, run.id())
                    .map(current -> current.status() == RunRepository.RunStatus.CANCELLED)
                    .orElse(false)) {
                return;
            }
            running = runs.transitionRun(spaceId, run.id(), RunRepository.RunStatus.RUNNING,
                    null, null, now, run.version());
        } catch (IllegalStateException transitionFailure) {
            RunRepository.RunRecord current = runs.findRun(spaceId, run.id()).orElseThrow();
            if (current.status() == RunRepository.RunStatus.CANCELLED) {
                return;
            }
            throw transitionFailure;
        }
        emit(running, "run.status", statusPayload("RUNNING", null));
        UUID stepId = UUID.randomUUID();
        RunRepository.StepRecord step = runs.createStep(new RunRepository.NewStep(stepId, spaceId, run.id(),
                "generate", RunRepository.StepType.GENERATE, 1, 1, RunRepository.RunStatus.QUEUED,
                null, null, now, run.correlationId()));
        emitStep(step);
        CancellationToken cancellation = control.token;
        UUID invocationId = UUID.randomUUID();
        String providerIdentity = "run-" + run.id();
        try {
            ProviderChatRequest providerRequest = new ProviderChatRequest(spaceId,
                    new com.ragforge.server.provider.adapter.RequestIdentity(run.id(), run.correlationId(), providerIdentity),
                    route.profile().modelName(), List.of(new ChatMessage("system", prompt.template()),
                            new ChatMessage("user", request.message())),
                    Duration.ofSeconds(request.timeoutSeconds()), route.profile().maxOutputTokens(),
                    java.util.Set.of(ModelCapability.CHAT), false);
            CompletableFuture<ProviderChatResponse> providerFuture = providerAdapters.require(route.connection().providerType())
                    .chat(route.connection(), route.egressDecision(), providerRequest, cancellation).toCompletableFuture();
            control.providerFuture = providerFuture;
            ProviderChatResponse response = providerFuture.get(request.timeoutSeconds() + 1L, TimeUnit.SECONDS);
            synchronized (control) {
                if (control.token.isCancellationRequested()) {
                    throw new ProviderAdapterException(ProviderErrorClass.CANCELLED,
                            "Provider request cancelled", providerRequest.identity().requestId(), 0);
                }
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
            }
        } catch (Exception failure) {
            Throwable cause = unwrap(failure);
            ProviderErrorClass providerError = control.token.isCancellationRequested()
                    ? ProviderErrorClass.CANCELLED : cause instanceof ProviderAdapterException exception
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
            RunRepository.RunRecord current = runs.findRun(spaceId, run.id()).orElseThrow();
            RunRepository.RunRecord failed = current.status() == RunRepository.RunStatus.CANCELLED
                    ? current : runs.transitionRun(spaceId, run.id(),
                    providerError == ProviderErrorClass.CANCELLED ? RunRepository.RunStatus.CANCELLED : RunRepository.RunStatus.FAILED,
                    errorClass, errorCode, Instant.now(), running.version());
            if (current.status() != RunRepository.RunStatus.CANCELLED) {
                emit(failed, "run.status", statusPayload(failed.status().name(), errorCode));
            }
        }
    }

    private void recordCancellationBeforeExecution(RunRepository.RunRecord run, RunRequest request,
                                                    ValidatedRoute route,
                                                    PromptRepository.PromptVersion prompt) {
        Instant now = Instant.now();
        UUID stepId = UUID.randomUUID();
        runs.createStep(new RunRepository.NewStep(stepId, run.spaceId(), run.id(),
                "generate", RunRepository.StepType.GENERATE, 1, 1, RunRepository.RunStatus.CANCELLED,
                RunRepository.ErrorClass.CANCELLED, "run_cancelled", now, run.correlationId()));
        UUID invocationId = UUID.randomUUID();
        String providerIdentity = "run-" + run.id();
        runs.createInvocation(new RunRepository.NewModelInvocation(invocationId, run.spaceId(), run.id(), stepId,
                route.connectionRecord().id(), route.profile().id(), route.route().id(), prompt.id(), providerIdentity,
                sha256(prompt.template() + "\n" + request.message()), "{\"messageCount\":2}", null,
                RunRepository.InvocationStatus.CANCELLED, RunRepository.ErrorClass.CANCELLED, "run_cancelled",
                now, run.correlationId()));
        emitStep(runs.findSteps(run.spaceId(), run.id()).stream()
                .filter(step -> step.id().equals(stepId)).findFirst().orElseThrow());
    }

    private static final class ExecutionControl {
        private final CancellationToken token = new CancellationToken();
        private volatile CompletableFuture<?> providerFuture;

        private synchronized void cancel() {
            token.cancel();
            CompletableFuture<?> future = providerFuture;
            if (future != null) {
                future.cancel(true);
            }
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

    private void validateBinding(UUID spaceId, RunRequest request,
                                 SpaceBindingRepository.SpaceBindingRecord binding) {
        if (!request.routeVersionId().equals(binding.chatRouteId())) {
            throw invalid("route_not_bound", "Chat route is not the current route bound to this space");
        }
        if (!request.promptVersionId().equals(binding.promptVersionId())) {
            throw invalid("prompt_not_bound", "Prompt version is not the current prompt bound to this space");
        }
        if (request.allowCloudEgress()) {
            if (!binding.cloudEgressEnabled()) {
                throw invalid("cloud_egress_not_bound", "Cloud egress is not enabled by the current space binding");
            }
            SpaceBindingRepository.CloudAuthorization authorization = binding.authorization();
            Instant now = Instant.now();
            if (authorization == null || authorization.expiresAt() == null
                    || !authorization.expiresAt().isAfter(now)
                    || authorization.approvedAt() == null || authorization.approvedAt().isAfter(now)
                    || !scopeCoversChat(authorization.scope())) {
                throw invalid("cloud_egress_unauthorized", "Cloud egress authorization is missing, expired, or does not cover CHAT");
            }
        }
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
        if (request.allowCloudEgress()
                && route.egressPolicy() != ProviderRepository.EgressPolicy.CLOUD_ALLOWED) {
            throw invalid("cloud_egress_not_allowed", "Cloud egress was not explicitly allowed by the route");
        }
        if (!request.allowCloudEgress()
                && route.egressPolicy() != ProviderRepository.EgressPolicy.LOCAL_ONLY) {
            throw invalid("cloud_candidate_not_allowed", "A local-only run cannot use a cloud route candidate");
        }
        if (!request.allowCloudEgress()
                && connection.egressPolicy() != ProviderRepository.EgressPolicy.LOCAL_ONLY) {
            throw invalid("cloud_candidate_not_allowed", "A local-only run cannot use a cloud provider candidate");
        }
        if (request.allowCloudEgress()
                && connection.egressPolicy() != ProviderRepository.EgressPolicy.CLOUD_ALLOWED) {
            throw invalid("cloud_route_candidate_invalid", "A cloud run requires a cloud provider candidate");
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

    private static boolean scopeCoversChat(String scope) {
        if (scope == null) {
            return false;
        }
        String normalized = scope.trim().toUpperCase(java.util.Locale.ROOT);
        return "CHAT".equals(normalized) || "ALL".equals(normalized);
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
