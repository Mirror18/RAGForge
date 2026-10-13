package com.ragforge.server.answer.application;

/** Audit sink seam; implementations must persist only {@link ToolAuditProjection}. */
@FunctionalInterface
public interface ToolAuditRecorder {
    void record(ToolAuditProjection projection);
}
