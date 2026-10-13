package com.ragforge.server.answer.domain;

public final class SpaceAccessDeniedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SpaceAccessDeniedException(String message) {
        super(message);
    }
}
