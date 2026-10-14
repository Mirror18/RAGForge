package com.ragforge.server.answer.application;
import com.ragforge.server.answer.domain.AnswerProvenance;

@FunctionalInterface
public interface AnswerProvenancePort {
    void record(AnswerProvenance provenance);
}
