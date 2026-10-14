package com.ragforge.server.answer.application;
import com.ragforge.server.answer.domain.AnswerProvenance;
import com.ragforge.server.answer.domain.AnswerRequest;

/** Persists redacted provider and RAG step lineage after a verified generation. */
@FunctionalInterface
public interface GenerationAuditPort {
    void record(AnswerRequest request, GenerationPort.GenerationResult generated, AnswerProvenance provenance);

    static GenerationAuditPort noop() {
        return (request, generated, provenance) -> { };
    }
}
