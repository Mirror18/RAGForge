package com.ragforge.server.provider;

import com.ragforge.server.identity.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** HTTP endpoints for the versioned provider and prompt binding aggregate. */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/space-bindings")
public class SpaceBindingController {
    private final SpaceBindingService service;

    public SpaceBindingController(SpaceBindingService service) {
        this.service = service;
    }

    @GetMapping
    public SpaceBindingService.SpaceBindingView get(
            @PathVariable UUID spaceId,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return service.get(spaceId, principal);
    }

    @PutMapping
    public SpaceBindingService.SpaceBindingView update(
            @PathVariable UUID spaceId,
            @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody SpaceBindingService.SpaceBindingUpdateRequest request,
            @AuthenticationPrincipal SessionPrincipal principal,
            HttpServletRequest servletRequest) {
        return service.update(spaceId, request, ifMatch, principal, servletRequest);
    }
}
