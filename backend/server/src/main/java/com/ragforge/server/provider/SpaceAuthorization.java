package com.ragforge.server.provider;

import com.ragforge.server.common.ApiException;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.identity.UserAdminService;
import com.ragforge.server.space.SpaceRepository;
import com.ragforge.server.space.SpaceRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Centralizes the space boundary for provider and prompt APIs. */
@Component
public class SpaceAuthorization {
    private final SpaceRepository spaces;

    public SpaceAuthorization(SpaceRepository spaces) {
        this.spaces = spaces;
    }

    public SpaceRole requireMember(UUID spaceId, SessionPrincipal principal) {
        return spaces.findRole(spaceId, principal.userId()).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "space_not_found", "Space not found", "Space not found"));
    }

    public void requireWrite(UUID spaceId, SessionPrincipal principal) {
        SpaceRole role = requireMember(spaceId, principal);
        if (role == SpaceRole.VIEWER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "space_editor_required", "Forbidden",
                    "Editor or space admin permission is required");
        }
    }

    public void requireAdmin(UUID spaceId, SessionPrincipal principal) {
        SpaceRole role = requireMember(spaceId, principal);
        if (role != SpaceRole.SPACE_ADMIN && !"PLATFORM_ADMIN".equals(principal.platformRole())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "space_admin_required", "Forbidden",
                    "Space administrator permission is required");
        }
    }

    public void requirePlatformAdmin(UUID spaceId, SessionPrincipal principal) {
        requireMember(spaceId, principal);
        UserAdminService.requirePlatformAdmin(principal);
    }
}
