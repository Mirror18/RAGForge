package com.ragforge.server.answer;

import java.util.UUID;

@FunctionalInterface
public interface SpaceAuthorizer {
    void requireAccess(UUID spaceId, AnswerRequest request);
}
