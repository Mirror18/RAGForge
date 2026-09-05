package com.ragforge.server.answer;

import java.util.UUID;

@FunctionalInterface
public interface SpaceAuthorizer {
    void requireAccess(UUID spaceId, AnswerRequest request);

    /** Production callers must pass the explicit authorization evidence. */
    default void requireAccess(UUID spaceId, AnswerRequest request, AnswerAuthorizationContext context) {
        requireAccess(spaceId, request);
    }
}
