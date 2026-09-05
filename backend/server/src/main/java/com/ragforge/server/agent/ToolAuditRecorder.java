package com.ragforge.server.agent;

/** Audit sink seam; implementations must persist only {@link ToolAuditProjection}. */
@FunctionalInterface
public interface ToolAuditRecorder {
    void record(ToolAuditProjection projection);
}
