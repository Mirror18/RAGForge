package com.ragforge.server.answer.integration;

import com.ragforge.server.answer.AnswerProvenance;
import com.ragforge.server.answer.AnswerRequest;
import com.ragforge.server.answer.GenerationAuditPort;
import com.ragforge.server.answer.GenerationPort;
import com.ragforge.server.common.UuidV7;
import com.ragforge.server.provider.adapter.ProviderUsage;
import com.ragforge.server.run.RunRepository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Redacted, space-scoped persistence for the RAG generation step and provider usage. */
public final class JdbcGenerationAuditPort implements GenerationAuditPort {
    private final RunRepository runs;
    private final ProviderRouteResolver routes;

    public JdbcGenerationAuditPort(RunRepository runs, ProviderRouteResolver routes) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.routes = Objects.requireNonNull(routes, "routes");
    }

    @Override
    public void record(AnswerRequest request, GenerationPort.GenerationResult generated,
                       AnswerProvenance provenance) {
        RunRepository.RunRecord run = runs.findRun(request.spaceId(), request.runId())
                .orElseThrow(() -> new IllegalArgumentException("RAG run is not available in the requested space"));
        if (!run.correlationId().equals(request.correlationId())
                || !run.routeVersionId().equals(request.modelRouteVersionId())) {
            throw new IllegalArgumentException("RAG audit request does not match the run lineage");
        }
        ProviderRouteResolver.ResolvedRoute route = routes.resolve(request.spaceId(), request.modelRouteVersionId(),
                request.modelProfileVersionId(), request.model(), request.egressDecision(), request.correlationId());
        RunRepository.StepRecord step = runs.findSteps(request.spaceId(), request.runId()).stream()
                .filter(candidate -> candidate.stepType() == RunRepository.StepType.GENERATE)
                .findFirst()
                .orElseGet(() -> runs.createStep(new RunRepository.NewStep(UuidV7.random(), request.spaceId(),
                        request.runId(), "rag-generate", RunRepository.StepType.GENERATE, 1, 1,
                        RunRepository.RunStatus.RUNNING, null, null, Instant.now(), request.correlationId())));
        if (!step.runId().equals(request.runId()) || !step.spaceId().equals(request.spaceId())) {
            throw new IllegalArgumentException("RAG audit step crosses the requested space");
        }
        String providerIdentity = "rag-" + request.runId();
        if (runs.findInvocations(request.spaceId(), request.runId()).stream()
                .noneMatch(invocation -> providerIdentity.equals(invocation.providerRequestIdentity()))) {
            UUID invocationId = UuidV7.random();
            String responseHash = sha256(generated.answerText());
            runs.createInvocation(new RunRepository.NewModelInvocation(invocationId, request.spaceId(),
                    request.runId(), step.id(), route.connection().providerConnectionId(), request.modelProfileVersionId(),
                    request.modelRouteVersionId(), run.promptVersionId(), providerIdentity,
                    sha256(provenance.promptHash() + "\n" + request.query()),
                    "{\"messageCount\":2,\"egressDecision\":\"" + request.egressDecision().name() + "\"}",
                    responseHash, RunRepository.InvocationStatus.SUCCEEDED, null, null, Instant.now(),
                    request.correlationId()));
            recordUsage(request, generated.usage(), invocationId, providerIdentity);
            runs.createRagModelInvocationProvenance(new RunRepository.NewRagModelInvocationProvenance(
                    UuidV7.random(), request.spaceId(), request.runId(), step.id(), invocationId,
                    provenance.ragPromptVersionId(), provenance.promptHash(), provenance.indexVersionId(),
                    provenance.retrievalProfileId(), provenance.retrievalProfileVersion(),
                    provenance.modelRouteVersionId(), provenance.modelProfileVersionId(),
                    provenance.evidenceBundleVersion(), provenance.evidenceBundleHash(), provenance.evidenceBundleRef(),
                    provenance.toolSchemaVersionsJson(), provenance.datasetHash(), provenance.configHash(),
                    provenance.traceId(), provenance.correlationId(), Instant.now()));
        }
        if (step.status() != RunRepository.RunStatus.SUCCEEDED) {
            runs.updateStep(request.spaceId(), step.id(), RunRepository.RunStatus.SUCCEEDED, null, null, Instant.now());
        }
        if (runs.findRagStepProvenance(request.spaceId(), step.id()).isEmpty()) {
            runs.createRagStepProvenance(new RunRepository.NewRagStepProvenance(
                    UuidV7.random(), request.spaceId(), request.runId(), step.id(), provenance.ragPromptVersionId(),
                    provenance.promptHash(), provenance.indexVersionId(), provenance.retrievalProfileId(),
                    provenance.retrievalProfileVersion(), provenance.modelRouteVersionId(),
                    provenance.modelProfileVersionId(), provenance.evidenceBundleVersion(),
                    provenance.evidenceBundleHash(), provenance.evidenceBundleRef(), provenance.toolSchemaVersionsJson(),
                    provenance.datasetHash(), provenance.configHash(), provenance.traceId(), provenance.correlationId(),
                    Instant.now()));
        }
    }

    private void recordUsage(AnswerRequest request, ProviderUsage usage, UUID invocationId, String identity) {
        RunRepository.UsageSource source = usage == null || usage.source() != com.ragforge.server.provider.adapter.UsageSource.PROVIDER_REPORTED
                ? RunRepository.UsageSource.LOCAL_ESTIMATE : RunRepository.UsageSource.PROVIDER_REPORTED;
        Long input = usage == null ? (long) request.query().length() : usage.promptTokens();
        Long output = usage == null ? null : usage.completionTokens();
        Long total = usage == null ? input : usage.totalTokens();
        runs.recordUsage(new RunRepository.NewUsageLedgerEntry(UuidV7.random(), request.spaceId(), invocationId,
                identity, source, "rag-" + request.runId(), input, output, total, BigDecimal.ZERO, "USD",
                "{\"source\":\"generation\"}", Instant.now(), request.correlationId()));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
