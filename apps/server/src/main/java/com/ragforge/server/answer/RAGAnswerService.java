package com.ragforge.server.answer;

import com.ragforge.server.common.UuidV7;
import com.ragforge.server.provider.adapter.CancellationToken;
import com.ragforge.server.provider.adapter.EgressDecision;
import com.ragforge.server.retrieval.CitationValidator;
import com.ragforge.server.retrieval.EvidenceBundle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Server-side P5-C/D seam: authorize, embed, retrieve, render a versioned prompt,
 * generate once, then validate and project citations from the current Evidence Bundle.
 */
public final class RAGAnswerService {
    private final SpaceAuthorizer authorizer;
    private final QueryEmbeddingProvider embeddingProvider;
    private final RetrievalPort retrievalPort;
    private final RagPromptPort promptPort;
    private final GenerationPort generationPort;
    private final AnswerPersistencePort answerPersistence;
    private final AnswerProvenancePort provenancePort;
    private final GenerationAuditPort generationAudit;
    private final CitationTokenParser tokenParser;
    private final ConcurrentHashMap<IdempotencyScope, Answer> answerCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<IdempotencyScope, Object> idempotencyLocks = new ConcurrentHashMap<>();

    /** Spring's default bean is safe-by-default and refuses an unconfigured production route. */
    public RAGAnswerService() {
        this((spaceId, request) -> {
            throw new SpaceAccessDeniedException("RAG answer ports are not configured");
        }, (request, decision, token) -> {
            throw new IllegalStateException("QueryEmbeddingProvider is not configured");
        }, (request, token) -> {
            throw new IllegalStateException("RetrievalPort is not configured");
        }, (spaceId, promptVersionId, correlationId) -> {
            throw new IllegalStateException("RagPromptPort is not configured");
        }, (request, token) -> CompletableFuture.failedFuture(
                new IllegalStateException("GenerationPort is not configured")),
                new RejectingAnswerPersistence(), provenance -> {
                    throw new IllegalStateException("AnswerProvenancePort is not configured");
                }, GenerationAuditPort.noop());
    }

    public RAGAnswerService(SpaceAuthorizer authorizer, QueryEmbeddingProvider embeddingProvider,
                            RetrievalPort retrievalPort, RagPromptPort promptPort, GenerationPort generationPort,
                            AnswerPersistencePort answerPersistence, AnswerProvenancePort provenancePort) {
        this(authorizer, embeddingProvider, retrievalPort, promptPort, generationPort, answerPersistence,
                provenancePort, GenerationAuditPort.noop());
    }

    public RAGAnswerService(SpaceAuthorizer authorizer, QueryEmbeddingProvider embeddingProvider,
                            RetrievalPort retrievalPort, RagPromptPort promptPort, GenerationPort generationPort,
                            AnswerPersistencePort answerPersistence, AnswerProvenancePort provenancePort,
                            GenerationAuditPort generationAudit) {
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.embeddingProvider = Objects.requireNonNull(embeddingProvider, "embeddingProvider");
        this.retrievalPort = Objects.requireNonNull(retrievalPort, "retrievalPort");
        this.promptPort = Objects.requireNonNull(promptPort, "promptPort");
        this.generationPort = Objects.requireNonNull(generationPort, "generationPort");
        this.answerPersistence = Objects.requireNonNull(answerPersistence, "answerPersistence");
        this.provenancePort = Objects.requireNonNull(provenancePort, "provenancePort");
        this.generationAudit = Objects.requireNonNull(generationAudit, "generationAudit");
        this.tokenParser = new CitationTokenParser();
    }

    public Answer answer(AnswerRequest request) {
        return answer(request, null);
    }

    public Answer answer(AnswerRequest request, AnswerAuthorizationContext authorizationContext) {
        Objects.requireNonNull(request, "request");
        try {
            if (authorizationContext == null) {
                authorizer.requireAccess(request.spaceId(), request);
            } else {
                authorizer.requireAccess(request.spaceId(), request, authorizationContext);
            }
        } catch (SpaceAccessDeniedException | SecurityException denied) {
            return refusal(request, AnswerStatus.ABSTAINED, AbstentionReason.SPACE_ACCESS_DENIED,
                    "The requested knowledge space is not available to this request.", List.of(),
                    unavailableProvenance(request));
        }

        IdempotencyScope scope = new IdempotencyScope(request.spaceId(), request.idempotencyKey());
        Object lock = idempotencyLocks.computeIfAbsent(scope, ignored -> new Object());
        synchronized (lock) {
            Answer cached = answerCache.get(scope);
            if (cached != null) {
                return cached;
            }
            Optional<AnswerPersistencePort.PersistedAnswer> persisted = answerPersistence.find(
                    request.spaceId(), request.idempotencyKey());
            if (persisted.isPresent()) {
                // The durable record intentionally contains no raw output. The same-process response cache
                // is the only place that can replay the answer body without violating the redaction boundary.
                return refusal(request, AnswerStatus.FAILED, AbstentionReason.PROVIDER_UNAVAILABLE,
                        "This idempotent answer is available only as a redacted replay record.", List.of(),
                        unavailableProvenance(request));
            }
            Answer result = execute(request);
            answerCache.put(scope, result);
            return result;
        }
    }

    /** Stable synonym for P5-E callers that model the operation as generation. */
    public Answer generate(AnswerRequest request) {
        return answer(request);
    }

    private Answer execute(AnswerRequest request) {
        CancellationToken cancellation = request.cancellationToken();
        if (cancellation.isCancellationRequested()) {
            return refusal(request, AnswerStatus.CANCELLED, AbstentionReason.CANCELLED,
                    "The answer request was cancelled before retrieval.", List.of(), unavailableProvenance(request));
        }

        try {
            List<Double> embedding = embeddingProvider.embed(
                    new QueryEmbeddingProvider.EmbeddingRequest(request.spaceId(), request.runId(),
                            request.correlationId(), request.query()), request.egressDecision(), cancellation);
            if (embedding == null || embedding.isEmpty() || embedding.stream()
                    .anyMatch(value -> value == null || !Double.isFinite(value))) {
                return refusal(request, AnswerStatus.FAILED, AbstentionReason.PROVIDER_UNAVAILABLE,
                        "The query embedding was incomplete.", List.of(), unavailableProvenance(request));
            }
            if (cancellation.isCancellationRequested()) {
                return refusal(request, AnswerStatus.CANCELLED, AbstentionReason.CANCELLED,
                        "The answer request was cancelled during retrieval.", List.of(), unavailableProvenance(request));
            }

            EvidenceBundleSnapshot original = retrievalPort.retrieve(
                    new RetrievalPort.RetrievalRequest(request.spaceId(), request.runId(), request.correlationId(),
                            request.query(), embedding), cancellation);
            validateBundleScope(request, original);
            EvidenceBundleSnapshot bounded = original.limitTo(request.maxContextTokens());
            EvidenceBundle bundle = bounded.bundle();
            if (bundle.abstained() || bundle.evidence().isEmpty()) {
                AbstentionReason reason = mapEvidenceReason(bundle.abstentionReason());
                AnswerProvenance provenance = unavailableProvenance(request, bounded);
                return refusal(request, AnswerStatus.ABSTAINED, reason,
                        safeEvidenceMessage(reason), bundle.evidence().stream()
                                .map(EvidenceBundle.Evidence::evidenceId).toList(), provenance);
            }
            if (cancellation.isCancellationRequested()) {
                return refusal(request, AnswerStatus.CANCELLED, AbstentionReason.CANCELLED,
                        "The answer request was cancelled before generation.", List.of(),
                        unavailableProvenance(request, bounded));
            }

            RagPromptPort.VersionedRagPrompt prompt = promptPort.load(request.spaceId(), request.promptVersionId(),
                    request.correlationId());
            if (prompt == null || !request.spaceId().equals(prompt.spaceId())) {
                return refusal(request, AnswerStatus.FAILED, AbstentionReason.PROVIDER_UNAVAILABLE,
                        "The versioned RAG prompt is unavailable.", List.of(), unavailableProvenance(request, bounded));
            }
            AnswerProvenance provenance = provenance(request, bounded, prompt);
            String renderedPrompt = renderPrompt(prompt, request.query(), bounded);
            // Record the exact redacted run identity before generation so failed/invalid model output
            // cannot erase the prompt/index/profile/evidence lineage.
            provenancePort.record(provenance);
            GenerationPort.GenerationRequest generationRequest = new GenerationPort.GenerationRequest(
                    request.spaceId(), request.runId(), request.correlationId(), request.idempotencyKey(),
                    request.query(), prompt, renderedPrompt, bounded, request.model(),
                    request.modelRouteVersionId(), request.modelProfileVersionId(), request.egressDecision());
            CompletableFuture<GenerationPort.GenerationResult> future = generationPort
                    .generate(generationRequest, cancellation).toCompletableFuture();
            GenerationPort.GenerationResult generated;
            try {
                generated = future.get(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeout) {
                cancellation.cancel();
                future.cancel(true);
                return refusal(request, AnswerStatus.FAILED, AbstentionReason.PROVIDER_UNAVAILABLE,
                        "Answer generation timed out.", List.of(), provenance);
            } catch (CancellationException cancelled) {
                return refusal(request, AnswerStatus.CANCELLED, AbstentionReason.CANCELLED,
                        "The answer request was cancelled during generation.", List.of(), provenance);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                cancellation.cancel();
                return refusal(request, AnswerStatus.CANCELLED, AbstentionReason.CANCELLED,
                        "The answer request was interrupted.", List.of(), provenance);
            } catch (ExecutionException failed) {
                if (cancellation.isCancellationRequested()) {
                    return refusal(request, AnswerStatus.CANCELLED, AbstentionReason.CANCELLED,
                            "The answer request was cancelled during generation.", List.of(), provenance);
                }
                return refusal(request, AnswerStatus.FAILED, AbstentionReason.PROVIDER_UNAVAILABLE,
                        "The answer provider was unavailable.", List.of(), provenance);
            }
            if (cancellation.isCancellationRequested()) {
                return refusal(request, AnswerStatus.CANCELLED, AbstentionReason.CANCELLED,
                        "The answer request was cancelled before citation validation.", List.of(), provenance);
            }
            if (generated == null || generated.egressDecision() != request.egressDecision()) {
                return refusal(request, AnswerStatus.FAILED, AbstentionReason.POLICY_BLOCKED,
                        "The generation route did not satisfy the requested egress policy.", List.of(), provenance);
            }
            generationAudit.record(request, generated, provenance);
            try {
                return complete(request, bounded, generated, provenance);
            } catch (CitationTokenParser.CitationTokenException invalidCitation) {
                return refusal(request, AnswerStatus.FAILED, AbstentionReason.POLICY_BLOCKED,
                        "The generated citation token was not valid for this Evidence Bundle.", List.of(), provenance);
            } catch (IllegalArgumentException invalidProjection) {
                return refusal(request, AnswerStatus.FAILED, AbstentionReason.POLICY_BLOCKED,
                        "The generated answer could not be projected into the answer contract.", List.of(), provenance);
            }
        } catch (CitationTokenParser.CitationTokenException invalidCitation) {
            return refusal(request, AnswerStatus.FAILED, AbstentionReason.POLICY_BLOCKED,
                    "The generated citation token was not valid for this Evidence Bundle.", List.of(),
                    unavailableProvenance(request));
        } catch (SpaceAccessDeniedException | SecurityException denied) {
            return refusal(request, AnswerStatus.ABSTAINED, AbstentionReason.SPACE_ACCESS_DENIED,
                    "The requested knowledge space is not available to this request.", List.of(),
                    unavailableProvenance(request));
        } catch (RuntimeException failed) {
            return refusal(request, AnswerStatus.FAILED, AbstentionReason.PROVIDER_UNAVAILABLE,
                    "The answer pipeline could not produce a verified result.", List.of(),
                    unavailableProvenance(request));
        }
    }

    private Answer complete(AnswerRequest request, EvidenceBundleSnapshot snapshot,
                            GenerationPort.GenerationResult generated, AnswerProvenance provenance) {
        List<Claim> claims = new ArrayList<>();
        List<Citation> citations = new ArrayList<>();
        int searchFrom = 0;
        for (GenerationPort.GeneratedClaim generatedClaim : generated.claims()) {
            int start = generatedClaim.answerCharStart() == null
                    ? generated.answerText().indexOf(generatedClaim.claimText(), searchFrom)
                    : generatedClaim.answerCharStart();
            int end = generatedClaim.answerCharEnd() == null ? start + generatedClaim.claimText().length()
                    : generatedClaim.answerCharEnd();
            if (start < 0 || end < start || end > generated.answerText().length()
                    || !generated.answerText().substring(start, end).equals(generatedClaim.claimText())) {
                throw new CitationTokenParser.CitationTokenException("Claim range is not part of the generated answer");
            }
            List<UUID> evidenceIds = tokenParser.parse(generatedClaim.citationTokens(), snapshot.bundle(),
                    request.spaceId());
            UUID claimId = UuidV7.random();
            Claim claim = new Claim("v1", claimId, request.spaceId(), request.correlationId(), request.runId(),
                    request.idempotencyKey(), generatedClaim.claimText(), evidenceIds, start, end);
            claims.add(claim);
            for (UUID evidenceId : evidenceIds) {
                CitationValidator.requireBundleCitations(snapshot.bundle(), request.spaceId(), List.of(evidenceId));
                citations.add(CitationValidator.project(snapshot, request.spaceId(), request.correlationId(),
                        request.runId(), request.idempotencyKey(), claimId, evidenceId, start, end));
            }
            searchFrom = end;
        }
        Answer answer = Answer.completed(request.spaceId(), request.correlationId(), request.runId(),
                request.idempotencyKey(), generated.answerText(), claims, citations, provenance);
        AnswerPersistencePort.PersistedAnswer persisted = answerPersistence.saveIfAbsent(answer);
        if (!persisted.answerId().equals(answer.answerId())) {
            return refusal(request, AnswerStatus.FAILED, AbstentionReason.POLICY_BLOCKED,
                    "The idempotency key is already bound to another answer result.", List.of(),
                    unavailableProvenance(request));
        }
        return answer;
    }

    private Answer refusal(AnswerRequest request, AnswerStatus status, AbstentionReason reason, String message,
                           List<UUID> evidenceIds, AnswerProvenance provenance) {
        Abstention abstention = new Abstention(request.spaceId(), request.correlationId(), request.runId(),
                request.idempotencyKey(), reason, evidenceIds, message);
        Answer answer = Answer.refusal(request.spaceId(), request.correlationId(), request.runId(),
                request.idempotencyKey(), status, abstention, provenance);
        answerPersistence.saveIfAbsent(new AnswerPersistencePort.PersistedAnswer(answer.answerId(),
                request.spaceId(), request.runId(), request.idempotencyKey(), status, sha256(status.name()),
                sha256(""), provenance));
        return answer;
    }

    private AnswerProvenance provenance(AnswerRequest request, EvidenceBundleSnapshot snapshot,
                                        RagPromptPort.VersionedRagPrompt prompt) {
        return new AnswerProvenance("v1", request.spaceId(), request.correlationId(), request.runId(),
                request.idempotencyKey(), snapshot.evidenceBundleId(), snapshot.evidenceBundleVersion(),
                snapshot.evidenceBundleHash(), snapshot.evidenceBundleRef(), snapshot.bundle().indexVersionId(),
                snapshot.bundle().profileId(), snapshot.bundle().profileVersion(), prompt.id(), prompt.promptHash(),
                request.modelRouteVersionId(), request.modelProfileVersionId(), request.model(),
                request.toolSchemaVersionsJson(), request.datasetHash(), request.configHash(), request.traceId());
    }

    private AnswerProvenance unavailableProvenance(AnswerRequest request) {
        return AnswerProvenance.unavailable(request.spaceId(), request.correlationId(), request.runId(),
                request.idempotencyKey(), request.traceId(), request.datasetHash(), request.configHash());
    }

    private AnswerProvenance unavailableProvenance(AnswerRequest request, EvidenceBundleSnapshot snapshot) {
        return new AnswerProvenance("v1", request.spaceId(), request.correlationId(), request.runId(),
                request.idempotencyKey(), snapshot.evidenceBundleId(), snapshot.evidenceBundleVersion(),
                snapshot.evidenceBundleHash(), snapshot.evidenceBundleRef(), snapshot.bundle().indexVersionId(),
                snapshot.bundle().profileId(), snapshot.bundle().profileVersion(), null, null,
                request.modelRouteVersionId(), request.modelProfileVersionId(), request.model(),
                request.toolSchemaVersionsJson(), request.datasetHash(), request.configHash(), request.traceId());
    }

    private static String renderPrompt(RagPromptPort.VersionedRagPrompt prompt, String query,
                                       EvidenceBundleSnapshot snapshot) {
        String system = prompt.template().replace("{{query}}", query);
        StringBuilder rendered = new StringBuilder(system).append("\n\n<ragforge_evidence>\n");
        Map<UUID, String> material = snapshot.materialById();
        for (EvidenceBundle.Evidence evidence : snapshot.bundle().evidence()) {
            rendered.append("<evidence id=\"").append(evidence.evidenceId()).append("\">\n")
                    .append(material.getOrDefault(evidence.evidenceId(), "[opaque evidence material unavailable]"))
                    .append("\n</evidence>\n");
        }
        return rendered.append("</ragforge_evidence>").toString();
    }

    private static void validateBundleScope(AnswerRequest request, EvidenceBundleSnapshot snapshot) {
        if (snapshot == null || snapshot.bundle() == null || !request.spaceId().equals(snapshot.bundle().spaceId())) {
            throw new IllegalArgumentException("Evidence Bundle is outside the requested space");
        }
        if (snapshot.bundle().evidence().stream().anyMatch(item -> !request.spaceId().equals(item.spaceId())
                || !snapshot.bundle().indexVersionId().equals(item.indexVersionId()))) {
            throw new IllegalArgumentException("Evidence Bundle contains cross-space or stale evidence");
        }
    }

    private static AbstentionReason mapEvidenceReason(String reason) {
        if (reason == null) return AbstentionReason.NO_EVIDENCE;
        return switch (reason.toUpperCase(java.util.Locale.ROOT)) {
            case "LOW_CONFIDENCE" -> AbstentionReason.LOW_CONFIDENCE;
            case "CONFLICTING", "EVIDENCE_CONFLICT", "CONFLICT" -> AbstentionReason.EVIDENCE_CONFLICT;
            case "POLICY_BLOCKED", "CONTEXT_BUDGET_EXCEEDED" -> AbstentionReason.POLICY_BLOCKED;
            default -> AbstentionReason.NO_EVIDENCE;
        };
    }

    private static String safeEvidenceMessage(AbstentionReason reason) {
        return switch (reason) {
            case LOW_CONFIDENCE -> "The available evidence did not meet the confidence threshold.";
            case EVIDENCE_CONFLICT -> "The available evidence was conflicting and could not be reconciled.";
            case POLICY_BLOCKED -> "The available evidence could not fit the configured context policy.";
            default -> "No verified evidence was available for this question.";
        };
    }

    private static String citationCanonical(List<Citation> citations) {
        return citations.stream().map(citation -> citation.claimId() + "|" + citation.evidenceId() + "|"
                + citation.answerCharStart() + "|" + citation.answerCharEnd()).sorted().reduce("",
                (left, right) -> left + right);
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

    private record IdempotencyScope(UUID spaceId, String idempotencyKey) {
    }

    private static final class RejectingAnswerPersistence implements AnswerPersistencePort {
        @Override
        public Optional<PersistedAnswer> find(UUID spaceId, String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public PersistedAnswer saveIfAbsent(PersistedAnswer record) {
            throw new IllegalStateException("AnswerPersistencePort is not configured");
        }
    }
}
