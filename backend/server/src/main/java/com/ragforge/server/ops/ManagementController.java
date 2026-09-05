package com.ragforge.server.ops;

import com.ragforge.server.common.ApiException;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.provider.SpaceAuthorization;
import com.ragforge.server.space.SpaceRole;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/management")
public class ManagementController {
    private final ManagementOperationsService operations;
    private final SpaceAuthorization authorization;

    public ManagementController(ManagementOperationsService operations, SpaceAuthorization authorization) {
        this.operations = operations;
        this.authorization = authorization;
    }

    @GetMapping("/health")
    public ManagementOperationsService.HealthAggregate health(@PathVariable UUID spaceId,
                                                               @RequestParam Instant from,
                                                               @RequestParam Instant to,
                                                               Authentication authentication) {
        requireManager(spaceId, authentication);
        return operations.aggregateHealth(spaceId, from, to);
    }

    @GetMapping("/cost-usage")
    public ManagementOperationsService.UsageCostPage costUsage(@PathVariable UUID spaceId,
                                                                @RequestParam Instant from,
                                                                @RequestParam Instant to,
                                                                Authentication authentication) {
        requireManager(spaceId, authentication);
        return operations.costUsage(spaceId, from, to);
    }

    @GetMapping("/feedback")
    public ManagementOperationsService.CursorPage<ManagementOperationsService.FeedbackItem> feedback(
            @PathVariable UUID spaceId, @RequestParam Instant from, @RequestParam Instant to,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
            Authentication authentication) {
        requireManager(spaceId, authentication);
        return operations.listFeedback(spaceId, from, to, cursor, limit);
    }

    @GetMapping("/audit/export")
    public ManagementOperationsService.CursorPage<ManagementOperationsService.AuditExportItem> auditExport(
            @PathVariable UUID spaceId, @RequestParam Instant from, @RequestParam Instant to,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
            Authentication authentication) {
        requireManager(spaceId, authentication);
        return operations.exportAudit(spaceId, from, to, cursor, limit);
    }

    private void requireManager(UUID spaceId, Authentication authentication) {
        SessionPrincipal principal = principal(authentication);
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_required", "Authentication required",
                    "A valid session is required");
        }
        if (isPlatformAdmin(authentication)) return;
        SpaceRole role = authorization.requireMember(spaceId, principal);
        if (role != SpaceRole.SPACE_ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "management_role_required", "Forbidden",
                    "A platform administrator or SPACE_ADMIN role is required");
        }
    }

    private static boolean isPlatformAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream().anyMatch(authority -> {
            String value = authority.getAuthority();
            return "PLATFORM_ADMIN".equals(value) || "ROLE_PLATFORM_ADMIN".equals(value);
        });
    }

    private static SessionPrincipal principal(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof SessionPrincipal value ? value : null;
    }
}
