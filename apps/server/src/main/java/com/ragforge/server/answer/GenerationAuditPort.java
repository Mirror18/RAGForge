package com.ragforge.server.answer;

/** Persists redacted provider and RAG step lineage after a verified generation. */
@FunctionalInterface
public interface GenerationAuditPort {
    void record(AnswerRequest request, GenerationPort.GenerationResult generated, AnswerProvenance provenance);

    static GenerationAuditPort noop() {
        return (request, generated, provenance) -> { };
    }
}
