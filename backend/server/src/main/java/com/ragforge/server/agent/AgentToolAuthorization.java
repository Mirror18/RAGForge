package com.ragforge.server.agent;

import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.provider.SpaceAuthorization;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Enforces the current server-authorized space before every tool backend call. */
@Component
public final class AgentToolAuthorization {
    private final SpaceAuthorization spaces;

    public AgentToolAuthorization(SpaceAuthorization spaces) {
        this.spaces = spaces;
    }

    public void requireRead(UUID requestedSpaceId, ToolExecutionContext context) {
        if (context == null || requestedSpaceId == null
                || !requestedSpaceId.equals(context.authorizedSpaceId())) {
            throw new AgentToolSecurityException("SPACE_SCOPE_MISMATCH");
        }
        SessionPrincipal principal = context.principal();
        try {
            spaces.requireMember(requestedSpaceId, principal);
        } catch (RuntimeException denied) {
            throw new AgentToolSecurityException("SPACE_NOT_AUTHORIZED");
        }
    }
}
