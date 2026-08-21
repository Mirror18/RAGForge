package com.ragforge.server.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ragforge.server.identity.SessionPrincipal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Space-scoped retrieval tool. It returns provenance, never executable instructions. */
public final class KnowledgeSearchTool {
    public interface Backend {
        KnowledgeSearchResult search(KnowledgeSearchRequest request);
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record KnowledgeSearchRequest(UUID spaceId, String query, Integer limit) {
        public KnowledgeSearchRequest {
            Objects.requireNonNull(spaceId, "spaceId");
            if (query == null || query.isBlank() || query.length() > 4096
                    || query.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("query is invalid");
            }
            limit = limit == null ? 8 : limit;
            if (limit < 1 || limit > 20) {
                throw new IllegalArgumentException("limit is invalid");
            }
            query = query.strip();
        }
    }

    public record KnowledgeSearchHit(UUID evidenceId, UUID spaceId, UUID documentRevisionId,
                                     UUID parentChunkId, UUID childChunkId, String contentRef,
                                     String textHash, double score) {
        public KnowledgeSearchHit {
            if (evidenceId == null || spaceId == null || documentRevisionId == null
                    || parentChunkId == null || childChunkId == null || contentRef == null
                    || contentRef.isBlank() || textHash == null || !textHash.matches("[0-9a-fA-F]{64}")
                    || !Double.isFinite(score)) {
                throw new IllegalArgumentException("search provenance is invalid");
            }
        }
    }

    public record KnowledgeSearchResult(UUID spaceId, List<KnowledgeSearchHit> hits,
                                        boolean abstained, String refusalCode) {
        public KnowledgeSearchResult {
            Objects.requireNonNull(spaceId, "spaceId");
            hits = hits == null ? List.of() : List.copyOf(hits);
            if (hits.size() > 20 || (abstained && (refusalCode == null || refusalCode.isBlank()))) {
                throw new IllegalArgumentException("search result is invalid");
            }
            if (!abstained && hits.isEmpty()) {
                throw new IllegalArgumentException("non-abstained search requires hits");
            }
            for (KnowledgeSearchHit hit : hits) {
                if (!spaceId.equals(hit.spaceId())) {
                    throw new IllegalArgumentException("search result crosses space boundary");
                }
            }
        }
    }

    private final AgentToolAuthorization authorization;
    private final Backend backend;
    private final ToolAuditRecorder audit;

    public KnowledgeSearchTool(AgentToolAuthorization authorization, Backend backend,
                               ToolAuditRecorder audit) {
        this.authorization = authorization;
        this.backend = backend;
        this.audit = audit;
    }

    public KnowledgeSearchResult execute(KnowledgeSearchRequest request, ToolExecutionContext context) {
        Objects.requireNonNull(request, "request");
        Map<String, String> safe = Map.of("queryHash", AgentToolHashing.sha256(request.query()),
                "limit", Integer.toString(request.limit()));
        try {
            authorization.requireRead(request.spaceId(), context);
            KnowledgeSearchResult result = backend.search(request);
            if (result == null || !request.spaceId().equals(result.spaceId())
                    || result.hits().size() > request.limit()) {
                throw new AgentToolSecurityException("BACKEND_SCOPE_VIOLATION");
            }
            String outputHash = AgentToolHashing.sha256(result.toString());
            audit.record(ToolAuditProjection.authorized(AgentToolName.KNOWLEDGE_SEARCH, context,
                    Instant.now(), outputHash, null, safe));
            return result;
        } catch (AgentToolSecurityException exception) {
            auditDenied(context, exception.errorCode(), safe);
            throw exception;
        } catch (RuntimeException exception) {
            audit.record(ToolAuditProjection.authorized(AgentToolName.KNOWLEDGE_SEARCH, context,
                    Instant.now(), null, "TOOL_EXECUTION_FAILED", safe));
            throw exception;
        }
    }

    private void auditDenied(ToolExecutionContext context, String errorCode, Map<String, String> safe) {
        if (context != null) {
            audit.record(ToolAuditProjection.denied(AgentToolName.KNOWLEDGE_SEARCH, context,
                    errorCode, Instant.now(), safe));
        }
    }
}
