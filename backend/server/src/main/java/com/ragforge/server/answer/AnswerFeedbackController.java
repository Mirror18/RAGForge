package com.ragforge.server.answer;

import com.ragforge.server.common.ApiException;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.provider.SpaceAuthorization;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/answers/{runId}/feedback")
public class AnswerFeedbackController {
    private final AnswerFeedbackRepository feedback;
    private final SpaceAuthorization authorization;

    public AnswerFeedbackController(AnswerFeedbackRepository feedback, SpaceAuthorization authorization) {
        this.feedback = feedback;
        this.authorization = authorization;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FeedbackResponse create(@PathVariable UUID spaceId, @PathVariable UUID runId,
                                   @Valid @RequestBody FeedbackRequest request,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                   @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                   Authentication authentication) {
        SessionPrincipal principal = principal(authentication);
        if (principal == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_required",
                "Authentication required", "A valid session is required");
        authorization.requireMember(spaceId, principal);
        return FeedbackResponse.from(feedback.save(spaceId, runId, request.evidenceId(), principal.userId(),
                request.sentiment(), request.reason(), commandKey(idempotencyKey), parseVersion(ifMatch, request.version())));
    }

    private static Long parseVersion(String ifMatch, Long bodyVersion) {
        if (bodyVersion != null) return bodyVersion;
        if (ifMatch == null || ifMatch.isBlank()) return null;
        String value = ifMatch.trim().replaceAll("^\"|\"$", "");
        try { return Long.valueOf(value); }
        catch (NumberFormatException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_if_match", "Invalid request", "If-Match must be a version");
        }
    }

    private static String commandKey(String value) {
        if (value == null || value.isBlank()) return "legacy-" + UUID.randomUUID();
        if (!value.matches("^[A-Za-z0-9._:-]{16,255}$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_idempotency_key", "Invalid request",
                    "Idempotency-Key is invalid");
        }
        return value;
    }

    private static SessionPrincipal principal(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof SessionPrincipal value ? value : null;
    }

    public record FeedbackRequest(@NotNull UUID evidenceId, @NotNull String sentiment,
                                  @Size(max = 1000) String reason, Long version) {
    }

    public record FeedbackResponse(UUID id, UUID spaceId, UUID runId, UUID evidenceId, UUID actorUserId,
                                   String sentiment, String reason, long version,
                                   java.time.Instant createdAt, java.time.Instant updatedAt) {
        static FeedbackResponse from(AnswerFeedbackRepository.FeedbackRecord value) {
            return new FeedbackResponse(value.id(), value.spaceId(), value.runId(), value.evidenceId(),
                    value.actorUserId(), value.sentiment(), value.reason(), value.version(), value.createdAt(), value.updatedAt());
        }
    }
}
