package com.ragforge.server.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Reads a revision through an injected, space-scoped content store; it never accepts a filesystem path. */
public final class DocumentReadTool {
    public interface Backend {
        DocumentReadResult read(DocumentReadRequest request);
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DocumentReadRequest(UUID spaceId, UUID documentId, UUID revisionId, Integer maxBytes) {
        public DocumentReadRequest {
            Objects.requireNonNull(spaceId, "spaceId");
            Objects.requireNonNull(documentId, "documentId");
            maxBytes = maxBytes == null ? 256 * 1024 : maxBytes;
            if (maxBytes < 1 || maxBytes > 1024 * 1024) {
                throw new IllegalArgumentException("maxBytes is invalid");
            }
        }
    }

    public record DocumentReadResult(UUID spaceId, UUID documentId, UUID revisionId,
                                     String mediaType, byte[] body, String contentRef,
                                     String contentHash) {
        public DocumentReadResult {
            Objects.requireNonNull(spaceId, "spaceId");
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(revisionId, "revisionId");
            if (mediaType == null || mediaType.isBlank() || body == null || contentRef == null
                    || contentRef.isBlank() || contentHash == null || !contentHash.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("document result is invalid");
            }
            body = body.clone();
            if (!contentHash.equalsIgnoreCase(AgentToolHashing.sha256(body))) {
                throw new IllegalArgumentException("document content hash mismatch");
            }
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    private final AgentToolAuthorization authorization;
    private final Backend backend;
    private final ToolAuditRecorder audit;

    public DocumentReadTool(AgentToolAuthorization authorization, Backend backend, ToolAuditRecorder audit) {
        this.authorization = authorization;
        this.backend = backend;
        this.audit = audit;
    }

    public DocumentReadResult execute(DocumentReadRequest request, ToolExecutionContext context) {
        Objects.requireNonNull(request, "request");
        Map<String, String> safe = Map.of("documentId", request.documentId().toString(),
                "revisionId", request.revisionId() == null ? "active" : request.revisionId().toString(),
                "maxBytes", Integer.toString(request.maxBytes()));
        try {
            authorization.requireRead(request.spaceId(), context);
            DocumentReadResult result = backend.read(request);
            if (result == null || !request.spaceId().equals(result.spaceId())
                    || !request.documentId().equals(result.documentId())
                    || (request.revisionId() != null && !request.revisionId().equals(result.revisionId()))
                    || result.body().length > request.maxBytes()) {
                throw new AgentToolSecurityException("DOCUMENT_SCOPE_OR_SIZE_VIOLATION");
            }
            audit.record(ToolAuditProjection.authorized(AgentToolName.DOCUMENT_READ, context,
                    Instant.now(), result.contentHash(), null, safe));
            return result;
        } catch (AgentToolSecurityException exception) {
            auditDenied(context, exception.errorCode(), safe);
            throw exception;
        } catch (RuntimeException exception) {
            audit.record(ToolAuditProjection.authorized(AgentToolName.DOCUMENT_READ, context,
                    Instant.now(), null, "TOOL_EXECUTION_FAILED", safe));
            throw exception;
        }
    }

    private void auditDenied(ToolExecutionContext context, String errorCode, Map<String, String> safe) {
        if (context != null) {
            audit.record(ToolAuditProjection.denied(AgentToolName.DOCUMENT_READ, context,
                    errorCode, Instant.now(), safe));
        }
    }
}
