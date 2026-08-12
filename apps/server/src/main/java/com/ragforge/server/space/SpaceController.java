package com.ragforge.server.space;

import com.ragforge.server.identity.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/spaces")
public class SpaceController {
    private final SpaceService spaceService;

    public SpaceController(SpaceService spaceService) {
        this.spaceService = spaceService;
    }

    @GetMapping
    public SpacePage list(Authentication authentication) {
        SessionPrincipal principal = principal(authentication);
        return new SpacePage(spaceService.list(principal).stream().map(SpaceResponse::from).toList(), null);
    }

    @PostMapping
    public ResponseEntity<SpaceResponse> create(Authentication authentication,
                                                @Valid @RequestBody CreateSpaceRequest request,
                                                HttpServletRequest servletRequest) {
        KnowledgeSpace space = spaceService.create(principal(authentication), request.name(), request.description(),
                servletRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(SpaceResponse.from(space));
    }

    @PutMapping("/{spaceId}/members/{userId}")
    public ResponseEntity<SpaceMember> updateMember(Authentication authentication,
                                             @PathVariable UUID spaceId,
                                             @PathVariable UUID userId,
                                             @Valid @RequestBody UpdateMemberRequest request,
                                             HttpServletRequest servletRequest) {
        SpaceMember member = spaceService.updateMember(principal(authentication), spaceId, userId,
                SpaceRole.parse(request.role()), servletRequest);
        return ResponseEntity.ok(member);
    }

    private SessionPrincipal principal(Authentication authentication) {
        return (SessionPrincipal) authentication.getPrincipal();
    }

    public record CreateSpaceRequest(@NotBlank @Size(min = 1, max = 120) String name,
                                     @Size(max = 2000) String description) {
    }

    public record UpdateMemberRequest(@NotBlank String role, Long version) {
    }

    public record SpaceResponse(UUID spaceId, String name, String description, String status, SpaceRole role,
                                java.time.Instant createdAt, long version) {
        static SpaceResponse from(KnowledgeSpace space) {
            return new SpaceResponse(space.id(), space.name(), space.description(), space.status(), space.role(),
                    space.createdAt(), space.version());
        }
    }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record SpacePage(List<SpaceResponse> items, String nextCursor) {
    }
}
