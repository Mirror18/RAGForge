package com.ragforge.server.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.common.CorrelationIdFilter;
import com.ragforge.server.identity.SessionPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.Set;

/** REST adapter for the read-safe Chunk Studio child projection and override workflow. */
@org.springframework.web.bind.annotation.RestController
@RequestMapping("/api/v1/spaces/{spaceId}/chunk-studio")
public final class ChunkStudioController {
    private final ChunkStudioService service;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public ChunkStudioController(ChunkStudioService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    ChunkStudioController(ChunkStudioService service) {
        this(service, new ObjectMapper().findAndRegisterModules());
    }

    @GetMapping("/children/{childChunkId}")
    public ChunkStudioService.ChunkStudioChildProjection getChild(
            @PathVariable UUID spaceId, @PathVariable UUID childChunkId,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return service.getChild(spaceId, childChunkId, principal);
    }

    @GetMapping("/lookup")
    public ChunkStudioService.ChunkStudioChildProjection lookup(
            @PathVariable UUID spaceId,
            @RequestParam UUID childChunkId,
            @RequestParam UUID documentRevisionId,
            @RequestParam String contentRef,
            @RequestParam String textHash,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return service.lookup(spaceId, childChunkId, documentRevisionId, contentRef, textHash, principal);
    }

    @PostMapping("/children/{childChunkId}/overrides")
    @ResponseStatus(HttpStatus.CREATED)
    public ChunkStudioService.OverrideResponse createOverride(
            @PathVariable UUID spaceId, @PathVariable UUID childChunkId,
            @RequestBody JsonNode body,
            @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest servletRequest) {
        ChunkStudioService.CreateOverrideRequest request = StudioRequestParser.parse(objectMapper, body,
                ChunkStudioService.CreateOverrideRequest.class,
                Set.of("documentRevisionId", "contentRef", "textHash", "reason"));
        return service.createOverride(spaceId, childChunkId, request, principal, correlationId(servletRequest));
    }

    @PostMapping("/children/{childChunkId}/overrides/{overrideId}/transitions")
    public ChunkStudioService.OverrideResponse transition(
            @PathVariable UUID spaceId, @PathVariable UUID childChunkId, @PathVariable UUID overrideId,
            @RequestBody JsonNode body,
            @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest servletRequest) {
        ChunkStudioService.TransitionRequest request = StudioRequestParser.parse(objectMapper, body,
                ChunkStudioService.TransitionRequest.class, Set.of("targetState", "expectedVersion", "reason"));
        return service.transition(spaceId, childChunkId, overrideId, request, principal,
                correlationId(servletRequest));
    }

    private static UUID correlationId(HttpServletRequest request) {
        return UUID.fromString(CorrelationIdFilter.current(request));
    }
}
