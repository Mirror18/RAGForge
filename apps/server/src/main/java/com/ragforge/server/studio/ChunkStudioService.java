package com.ragforge.server.studio;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ragforge.server.audit.AuditOutboxService;
import com.ragforge.server.chunk.ChunkOverrideTransitions;
import com.ragforge.server.chunk.ChunkRepository;
import com.ragforge.server.chunk.OverrideState;
import com.ragforge.server.common.ApiException;
import com.ragforge.server.common.UuidV7;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.provider.SpaceAuthorization;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Application service for the space-isolated Chunk Studio REST projection. */
@Service
public class ChunkStudioService {
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateOverrideRequest(
            @NotNull UUID documentRevisionId,
            @NotBlank @Size(max = 512) String contentRef,
            @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{64}$") String textHash,
            @NotBlank @Size(max = 2000) String reason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record TransitionRequest(
            @NotNull OverrideState targetState,
            @NotNull @Min(1) Integer expectedVersion,
            @NotBlank @Size(max = 2000) String reason) {
    }

    public record ChunkStudioChildProjection(UUID spaceId, UUID documentRevisionId, UUID childChunkId,
            UUID parentChunkId, String contentRef, String textHash, ParentChild parentChild,
            Provenance provenance, Anchor anchor, VectorStatus vectorStatus, OverrideSummary override) {
    }

    public record ParentChild(UUID parentChunkId, UUID childChunkId, String relationship,
            String parentContentRef, int childIndex) {
    }

    public record Provenance(UUID sourceId, UUID documentId, UUID documentRevisionId,
            String sourcePath, int revisionVersion) {
    }

    public record Anchor(List<String> headingPath, Integer pageNumber, String sheet, Integer slideNumber,
            LineRange lineRange, String tableCell) {
    }

    public record LineRange(Integer startLine, Integer endLine) {
    }

    public record VectorStatus(String state, UUID indexVersionId, Integer vectorDimension, Instant updatedAt) {
    }

    public record OverrideSummary(UUID overrideId, OverrideState state, int version, String reason,
            UUID createdBy, Instant createdAt, Instant updatedAt) {
    }

    public record OverrideRecord(UUID overrideId, OverrideState state, int version, String source,
            String reason, String replacedTextHash, UUID createdBy, Instant createdAt, Instant updatedAt) {
    }

    public record OverrideResponse(UUID spaceId, UUID documentRevisionId, UUID childChunkId,
            String contentRef, String textHash, OverrideRecord override) {
    }

    private final SpaceAuthorization authorization;
    private final StudioRepository studio;
    private final ChunkRepository chunks;
    private final AuditOutboxService audit;
    private final ConcurrentHashMap<UUID, Object> transitionLocks = new ConcurrentHashMap<>();

    public ChunkStudioService(SpaceAuthorization authorization, StudioRepository studio,
                              ChunkRepository chunks, AuditOutboxService audit) {
        this.authorization = authorization;
        this.studio = studio;
        this.chunks = chunks;
        this.audit = audit;
    }

    public ChunkStudioChildProjection getChild(UUID spaceId, UUID childChunkId, SessionPrincipal principal) {
        authorization.requireMember(spaceId, principal);
        StudioRepository.ChildStudioRow child = studio.findChild(spaceId, childChunkId)
                .orElseThrow(() -> notFound("chunk_not_found", "Chunk not found"));
        ChunkRepository.ChunkOverride override = chunks.findLatestOverride(spaceId, childChunkId).orElse(null);
        StudioRepository.VectorStatus vector = studio.findVectorStatus(spaceId, childChunkId, child.createdAt());
        return new ChunkStudioChildProjection(spaceId, child.documentRevisionId(), child.childChunkId(),
                child.parentChunkId(), child.childContentRef(), child.childTextHash(),
                new ParentChild(child.parentChunkId(), child.childChunkId(), "CHILD_OF",
                        child.parentContentRef(), child.childIndex()),
                new Provenance(child.sourceId(), child.documentId(), child.documentRevisionId(),
                        child.sourcePath(), child.revisionVersion()),
                new Anchor(child.headingPath(), child.pageNumber(), child.sheet(), child.slideNumber(),
                        child.lineStart() == null ? null : new LineRange(child.lineStart(), child.lineEnd()),
                        child.tableCell()),
                new VectorStatus(vector.state(), vector.indexVersionId(), vector.vectorDimension(), vector.updatedAt()),
                toSummary(override));
    }

    @Transactional
    public OverrideResponse createOverride(UUID spaceId, UUID childChunkId, CreateOverrideRequest request,
                                           SessionPrincipal principal, UUID correlationId) {
        authorization.requireWrite(spaceId, principal);
        if (request == null || request.documentRevisionId() == null || request.textHash() == null
                || request.reason() == null || request.reason().isBlank() || request.reason().length() > 2000
                || !request.textHash().matches("[0-9a-fA-F]{64}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "override_request_invalid", "Invalid override request",
                    "The override request is incomplete");
        }
        StudioRepository.ChildStudioRow child = studio.findChild(spaceId, childChunkId)
                .orElseThrow(() -> notFound("chunk_not_found", "Chunk not found"));
        validateOpaqueContentRef(request.contentRef());
        if (request.reason().length() > 512) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "override_reason_too_long",
                    "Override reason is too long", "The current append-only repository accepts at most 512 characters");
        }
        if (!studio.documentRevisionExists(spaceId, request.documentRevisionId())) {
            throw notFound("document_revision_not_found", "Document revision not found");
        }
        UUID overrideId = UuidV7.random();
        ChunkRepository.ChunkOverride created;
        try {
            created = chunks.createOverride(new ChunkRepository.NewChunkOverride(overrideId, spaceId, childChunkId,
                    request.documentRevisionId(), request.contentRef(), request.reason(),
                    request.textHash().toLowerCase(java.util.Locale.ROOT), principal.userId(), Instant.now()));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "override_invalid", "Invalid override",
                    "The override could not be created");
        }
        String contentRef = created.replacementContentRef() == null
                ? request.contentRef() : created.replacementContentRef();
        audit.record("chunk.override.created", principal.userId(), spaceId, created.id(), correlationId,
                Map.of("childChunkId", childChunkId, "documentRevisionId", request.documentRevisionId(),
                        "overrideId", created.id(), "version", created.versionNo(), "state", created.state().name(),
                        "contentRef", contentRef, "reasonCode", "CLIENT_SUPPLIED"));
        return toResponse(spaceId, created, contentRef);
    }

    @Transactional
    public OverrideResponse transition(UUID spaceId, UUID childChunkId, UUID overrideId,
                                       TransitionRequest request, SessionPrincipal principal, UUID correlationId) {
        authorization.requireWrite(spaceId, principal);
        if (request == null || request.targetState() == null || request.expectedVersion() == null
                || request.expectedVersion() < 1 || request.reason() == null || request.reason().isBlank()
                || request.reason().length() > 2000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "transition_request_invalid", "Invalid transition request",
                    "The transition request is incomplete");
        }
        StudioRepository.ChildStudioRow child = studio.findChild(spaceId, childChunkId)
                .orElseThrow(() -> notFound("chunk_not_found", "Chunk not found"));
        Object lock = transitionLocks.computeIfAbsent(overrideId, ignored -> new Object());
        synchronized (lock) {
            ChunkRepository.ChunkOverride current = chunks.findById(spaceId, overrideId)
                    .orElseThrow(() -> notFound("override_not_found", "Override not found"));
            if (!childChunkId.equals(current.childChunkId()) || !spaceId.equals(current.spaceId())) {
                throw notFound("override_not_found", "Override not found");
            }
            ChunkRepository.ChunkOverride latest = chunks.findLatestOverride(spaceId, childChunkId)
                    .orElseThrow(() -> notFound("override_not_found", "Override not found"));
            if (!latest.id().equals(current.id()) || request.expectedVersion() != current.versionNo()) {
                throw new ApiException(HttpStatus.CONFLICT, "override_version_conflict", "Override version conflict",
                        "The override version is stale");
            }
            try {
                ChunkOverrideTransitions.requireTransition(current.state(), request.targetState());
            } catch (IllegalArgumentException exception) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "override_transition_invalid",
                        "Invalid override transition", "The requested override state transition is not allowed");
            }
            ChunkRepository.ChunkOverride updated;
            try {
                updated = chunks.updateOverrideState(spaceId, overrideId, request.targetState(), Instant.now());
            } catch (RuntimeException exception) {
                throw new ApiException(HttpStatus.CONFLICT, "override_version_conflict", "Override version conflict",
                        "The override could not be transitioned");
            }
            String contentRef = updated.replacementContentRef();
            if (contentRef == null) {
                contentRef = studio.findOverrideContentRef(spaceId, overrideId)
                        .orElseGet(() -> "override:" + overrideId);
            }
            audit.record("chunk.override.transitioned", principal.userId(), spaceId, updated.id(), correlationId,
                    Map.of("childChunkId", childChunkId, "overrideId", updated.id(),
                            "fromState", current.state().name(), "toState", updated.state().name(),
                            "expectedVersion", request.expectedVersion(), "version", updated.versionNo(),
                            "contentRef", contentRef, "reasonCode", "CLIENT_SUPPLIED"));
            return toResponse(spaceId, updated, contentRef);
        }
    }

    private OverrideResponse toResponse(UUID spaceId, ChunkRepository.ChunkOverride override, String contentRef) {
        return new OverrideResponse(spaceId, override.documentRevisionId(), override.childChunkId(), contentRef,
                override.replacedTextHash(), toRecord(override));
    }

    private static OverrideSummary toSummary(ChunkRepository.ChunkOverride override) {
        return override == null ? new OverrideSummary(null, OverrideState.NONE, 0, null, null, null, null)
                : new OverrideSummary(override.id(), override.state(), override.versionNo(), override.reason(),
                        override.createdBy(), override.createdAt(), override.updatedAt());
    }

    private static OverrideRecord toRecord(ChunkRepository.ChunkOverride override) {
        return new OverrideRecord(override.id(), override.state(), override.versionNo(), "MANUAL", override.reason(),
                override.replacedTextHash(), override.createdBy(), override.createdAt(), override.updatedAt());
    }

    private static void validateOpaqueContentRef(String contentRef) {
        if (contentRef == null || contentRef.isBlank() || contentRef.length() > 512
                || contentRef.matches(".*\\s+.*")
                || contentRef.chars().anyMatch(Character::isISOControl)
                || contentRef.matches("(?i).*\\b(fullText|rawText|rawDocument|documentContent|embedding|vector)\\b.*")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "content_ref_invalid", "Invalid content reference",
                    "contentRef must be a non-sensitive opaque reference");
        }
    }

    private static ApiException notFound(String code, String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, code, "Not found", detail);
    }
}
