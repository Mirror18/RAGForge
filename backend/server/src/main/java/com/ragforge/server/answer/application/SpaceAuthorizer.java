package com.ragforge.server.answer.application;

import java.util.UUID;
import com.ragforge.server.answer.domain.AnswerAuthorizationContext;
import com.ragforge.server.answer.domain.AnswerRequest;

@FunctionalInterface
public interface SpaceAuthorizer {
    void requireAccess(UUID spaceId, AnswerRequest request);

    /** Production callers must pass the explicit authorization evidence. */
    default void requireAccess(UUID spaceId, AnswerRequest request, AnswerAuthorizationContext context) {
        requireAccess(spaceId, request);
    }
}
