package com.ragforge.server.answer.api;

import com.ragforge.server.shared.domain.ApiException;
import com.ragforge.server.shared.domain.SessionPrincipal;
import com.ragforge.server.space.domain.SpaceAuthorization;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Read-only space membership gate for answer readiness. */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}")
public class AnswerReadinessController {
    private final AnswerReadinessService readiness;
    private final SpaceAuthorization authorization;

    public AnswerReadinessController(AnswerReadinessService readiness, SpaceAuthorization authorization) {
        this.readiness = readiness;
        this.authorization = authorization;
    }

    @GetMapping(value = "/answer-readiness", produces = MediaType.APPLICATION_JSON_VALUE)
    public AnswerReadinessService.AnswerReadiness get(@PathVariable UUID spaceId, Authentication authentication) {
        SessionPrincipal principal = authentication != null
                && authentication.getPrincipal() instanceof SessionPrincipal value ? value : null;
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_required", "Authentication required",
                    "A valid session is required");
        }
        authorization.requireMember(spaceId, principal);
        return readiness.inspect(spaceId);
    }
}
