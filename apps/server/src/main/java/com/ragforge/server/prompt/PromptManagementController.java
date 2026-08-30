package com.ragforge.server.prompt;

import com.ragforge.server.identity.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** HTTP endpoints for creating and publishing immutable prompt versions. */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}")
public class PromptManagementController {
    private final PromptManagementService service;

    public PromptManagementController(PromptManagementService service) {
        this.service = service;
    }

    @GetMapping("/prompt-templates")
    public PromptManagementService.PromptTemplatePage listTemplates(
            @PathVariable UUID spaceId,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return service.listTemplates(spaceId, principal);
    }

    @PostMapping("/prompt-templates")
    @ResponseStatus(HttpStatus.CREATED)
    public PromptManagementService.PromptTemplateView createTemplate(
            @PathVariable UUID spaceId,
            @Valid @RequestBody PromptManagementService.PromptTemplateRequest request,
            @AuthenticationPrincipal SessionPrincipal principal,
            HttpServletRequest servletRequest) {
        return service.createTemplate(spaceId, request, principal, servletRequest);
    }

    @PostMapping("/prompt-templates/{promptTemplateId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public PromptManagementService.PromptVersionView createVersion(
            @PathVariable UUID spaceId,
            @PathVariable UUID promptTemplateId,
            @Valid @RequestBody PromptManagementService.PromptVersionRequest request,
            @AuthenticationPrincipal SessionPrincipal principal,
            HttpServletRequest servletRequest) {
        return service.createVersion(spaceId, promptTemplateId, request, principal, servletRequest);
    }

    @GetMapping("/prompt-templates/{promptTemplateId}/versions/{promptVersion}")
    public PromptManagementService.PromptVersionView getVersion(
            @PathVariable UUID spaceId,
            @PathVariable UUID promptTemplateId,
            @PathVariable int promptVersion,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return service.getVersion(spaceId, promptTemplateId, promptVersion, principal);
    }

    @PostMapping("/prompt-templates/{promptTemplateId}/versions/{promptVersion}/publish")
    public PromptManagementService.PromptVersionView publishVersion(
            @PathVariable UUID spaceId,
            @PathVariable UUID promptTemplateId,
            @PathVariable int promptVersion,
            @AuthenticationPrincipal SessionPrincipal principal,
            HttpServletRequest servletRequest) {
        return service.publishVersion(spaceId, promptTemplateId, promptVersion, principal, servletRequest);
    }

    @GetMapping("/prompt-bindings")
    public PromptManagementService.PromptBindingView getBinding(
            @PathVariable UUID spaceId, @AuthenticationPrincipal SessionPrincipal principal) {
        return service.getBinding(spaceId, principal);
    }

    @PutMapping("/prompt-bindings")
    public PromptManagementService.PromptBindingView updateBinding(
            @PathVariable UUID spaceId,
            @Valid @RequestBody PromptManagementService.PromptBindingUpdateRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @AuthenticationPrincipal SessionPrincipal principal,
            HttpServletRequest servletRequest) {
        return service.updateBinding(spaceId, request, ifMatch, principal, servletRequest);
    }
}
