package com.ragforge.server.answer.application;

import com.ragforge.server.answer.infrastructure.RunRepository;
import com.ragforge.server.shared.domain.UuidV7;
import com.ragforge.server.answer.domain.AnswerProvenance;

/** Adapter from the answer seam to the immutable V11 run provenance repository. */
public final class V11RunProvenanceRecorder implements AnswerProvenancePort {
    private final RunRepository runs;

    public V11RunProvenanceRecorder(RunRepository runs) {
        this.runs = java.util.Objects.requireNonNull(runs, "runs");
    }

    @Override
    public void record(AnswerProvenance provenance) {
        if (provenance.evidenceBundleId() == null || provenance.ragPromptVersionId() == null
                || provenance.indexVersionId() == null || provenance.retrievalProfileId() == null
                || provenance.modelRouteVersionId() == null || provenance.modelProfileVersionId() == null) {
            throw new IllegalArgumentException("Complete V11 provenance is required");
        }
        runs.createRagRunProvenance(new RunRepository.NewRagRunProvenance(
                UuidV7.random(), provenance.spaceId(), provenance.runId(), provenance.ragPromptVersionId(),
                provenance.promptHash(), provenance.indexVersionId(), provenance.retrievalProfileId(),
                provenance.retrievalProfileVersion(), provenance.modelRouteVersionId(),
                provenance.modelProfileVersionId(), provenance.evidenceBundleVersion(),
                provenance.evidenceBundleHash(), provenance.evidenceBundleRef(), provenance.toolSchemaVersionsJson(),
                provenance.datasetHash(), provenance.configHash(), provenance.traceId(),
                provenance.correlationId(), java.time.Instant.now()));
    }
}
