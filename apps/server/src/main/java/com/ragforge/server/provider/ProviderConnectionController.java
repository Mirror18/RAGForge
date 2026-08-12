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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/provider-connections")
public class ProviderConnectionController {
    private final ProviderConnectionService service;

    public ProviderConnectionController(ProviderConnectionService service) {
        this.service = service;
    }

    @GetMapping
    public ProviderConnectionService.ProviderConnectionPage list(
            @PathVariable UUID spaceId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal SessionPrincipal principal) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        List<ProviderConnectionService.ProviderConnectionView> items = service.list(spaceId, principal);
        if (items.size() > boundedLimit) {
            items = items.subList(0, boundedLimit);
        }
        return new ProviderConnectionService.ProviderConnectionPage(items, null);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProviderConnectionService.ProviderConnectionView create(
            @PathVariable UUID spaceId,
            @Valid @RequestBody ProviderConnectionService.ProviderConnectionRequest request,
            @AuthenticationPrincipal SessionPrincipal principal,
            HttpServletRequest servletRequest) {
        return service.create(spaceId, request, principal, servletRequest);
    }

    @GetMapping("/{providerConnectionId}")
    public ProviderConnectionService.ProviderConnectionView get(
            @PathVariable UUID spaceId,
            @PathVariable UUID providerConnectionId,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return service.get(spaceId, providerConnectionId, principal);
    }
}
