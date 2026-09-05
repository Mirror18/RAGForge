package com.ragforge.server.agent;

import com.ragforge.server.audit.AuditOutboxService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bridges the redacted tool projection to the existing transactional audit seam. */
@Component
public final class AuditOutboxToolAuditRecorder implements ToolAuditRecorder {
    private final AuditOutboxService audit;

    public AuditOutboxToolAuditRecorder(AuditOutboxService audit) {
        this.audit = audit;
    }

    @Override
    public void record(ToolAuditProjection projection) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", projection.schemaVersion());
        payload.put("tool", projection.tool().value());
        payload.put("authorizationResult", projection.authorizationResult().name());
        payload.put("startedAt", projection.startedAt().toString());
        payload.put("completedAt", projection.completedAt().toString());
        payload.put("outputHash", projection.outputHash());
        payload.put("errorCode", projection.errorCode());
        payload.put("traceId", projection.traceId());
        payload.put("correlationId", projection.correlationId());
        payload.put("idempotencyKey", projection.idempotencyKey());
        payload.put("request", projection.safeRequest());
        audit.record("agent.tool.invoked.v1", projection.actorUserId(), projection.spaceId(),
                projection.traceId(), projection.correlationId(), payload);
    }
}
