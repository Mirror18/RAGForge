package com.ragforge.server.provider;

import com.ragforge.server.identity.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** HTTP endpoints for versioned model routes and candidate validation. */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/model-routes")
public class ModelRouteController {
    private final ModelProfileRouteService service;

    public ModelRouteController(ModelProfileRouteService service) {
        this.service = service;
    }

    @GetMapping
    public ModelProfileRouteService.ModelRoutePage list(
            @PathVariable UUID spaceId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return service.listRoutes(spaceId, limit, principal);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ModelProfileRouteService.ModelRouteView create(
            @PathVariable UUID spaceId,
            @Valid @RequestBody ModelProfileRouteService.ModelRouteRequest request,
            @AuthenticationPrincipal SessionPrincipal principal,
            HttpServletRequest servletRequest) {
        return service.createRoute(spaceId, request, principal, servletRequest);
    }
}
