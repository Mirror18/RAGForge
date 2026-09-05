package com.ragforge.server.answer;

@FunctionalInterface
public interface AnswerProvenancePort {
    void record(AnswerProvenance provenance);
}
