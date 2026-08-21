package com.ragforge.server.answer.integration;

import com.ragforge.server.answer.EvidenceBundleSnapshot;
import com.ragforge.server.answer.RetrievalPort;
import com.ragforge.server.provider.adapter.CancellationToken;
import com.ragforge.server.provider.adapter.ProviderAdapterException;
import com.ragforge.server.provider.adapter.ProviderErrorClass;
import com.ragforge.server.retrieval.EvidenceBundle;
import com.ragforge.server.retrieval.RetrievalService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bridges the Phase 4 retrieval service to the answer Evidence Bundle. The
 * material resolver is mandatory because metadata-only retrieval cannot safely
 * generate an answer.
 */
public final class RetrievalServicePortAdapter implements RetrievalPort {
    @FunctionalInterface
    public interface EvidenceMaterialResolver {
        String resolve(EvidenceBundle.Evidence evidence, RetrievalRequest request,
                       CancellationToken cancellationToken);
    }

    private final RetrievalService retrieval;
    private final RetrievalExecutionResolver executions;
    private final EvidenceMaterialResolver materials;
    private final Phase5IntegrationObserver observer;

    public RetrievalServicePortAdapter(RetrievalService retrieval, RetrievalExecutionResolver executions,
                                       EvidenceMaterialResolver materials) {
        this(retrieval, executions, materials, Phase5IntegrationObserver.noop());
    }

    public RetrievalServicePortAdapter(RetrievalService retrieval, RetrievalExecutionResolver executions,
                                       EvidenceMaterialResolver materials, Phase5IntegrationObserver observer) {
        this.retrieval = Objects.requireNonNull(retrieval, "retrieval");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.observer = observer == null ? Phase5IntegrationObserver.noop() : observer;
    }

    @Override
    public EvidenceBundleSnapshot retrieve(RetrievalRequest request, CancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        if (cancellationToken.isCancellationRequested()) {
            throw new ProviderAdapterException(ProviderErrorClass.CANCELLED,
                    "Retrieval was cancelled", request.correlationId(), 0);
        }
        RetrievalExecutionResolver.Execution execution = executions.resolve(request.spaceId(), request.runId(),
                request.correlationId());
        if (!request.spaceId().equals(execution.spaceId())
                || !request.spaceId().equals(execution.profile().spaceId())) {
            throw new ProviderAdapterException(ProviderErrorClass.SPACE_EGRESS_DENIED,
                    "Retrieval execution crossed a space boundary", request.correlationId(), 0, false);
        }
        EvidenceBundle bundle;
        try {
            bundle = retrieval.retrieve(new RetrievalService.Request(request.spaceId(), execution.indexVersionId(),
                    execution.profile(), request.query(), request.queryEmbedding()));
        } catch (RuntimeException failure) {
            observer.record(new Phase5IntegrationObserver.Decision(request.spaceId(), request.runId(),
                    request.correlationId(), "retrieval", "FAILED", "RETRIEVAL_PROVIDER_FAILURE", null));
            throw failure;
        }
        if (bundle.abstained() || bundle.evidence().isEmpty()) {
            observer.record(new Phase5IntegrationObserver.Decision(request.spaceId(), request.runId(),
                    request.correlationId(), "retrieval", "ABSTAINED", bundle.abstentionReason(), null));
            return snapshot(execution, bundle, List.of());
        }
        List<EvidenceBundleSnapshot.EvidenceMaterial> resolved = new ArrayList<>();
        for (EvidenceBundle.Evidence evidence : bundle.evidence()) {
            if (cancellationToken.isCancellationRequested()) {
                throw new ProviderAdapterException(ProviderErrorClass.CANCELLED,
                        "Evidence material retrieval was cancelled", request.correlationId(), 0);
            }
            String content = materials.resolve(evidence, request, cancellationToken);
            if (content == null || content.isBlank() || content.length() > 100_000) {
                observer.record(new Phase5IntegrationObserver.Decision(request.spaceId(), request.runId(),
                        request.correlationId(), "retrieval", "FAILED", "EVIDENCE_MATERIAL_UNAVAILABLE", null));
                throw new ProviderAdapterException(ProviderErrorClass.UNAVAILABLE,
                        "Verified evidence material is unavailable", request.correlationId(), 0, false);
            }
            resolved.add(new EvidenceBundleSnapshot.EvidenceMaterial(evidence.evidenceId(), content));
        }
        observer.record(new Phase5IntegrationObserver.Decision(request.spaceId(), request.runId(),
                request.correlationId(), "retrieval", "SUCCEEDED", "EVIDENCE_BUNDLE_READY", null));
        return snapshot(execution, bundle, resolved);
    }

    private static EvidenceBundleSnapshot snapshot(RetrievalExecutionResolver.Execution execution,
                                                    EvidenceBundle bundle,
                                                    List<EvidenceBundleSnapshot.EvidenceMaterial> materials) {
        String canonical = bundle.spaceId() + "|" + bundle.indexVersionId() + "|" + bundle.profileId() + "|"
                + bundle.profileVersion() + "|" + bundle.evidence().stream()
                .map(item -> item.evidenceId() + ":" + item.textHash() + ":" + item.anchor().tokenStart()
                        + ":" + item.anchor().tokenEnd()).sorted().reduce("", String::concat);
        return new EvidenceBundleSnapshot(execution.evidenceBundleId(), execution.evidenceBundleVersion(),
                sha256(canonical), execution.evidenceBundleRef(), bundle, materials,
                execution.datasetHash(), execution.configHash());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is required by the runtime", failure);
        }
    }
}
