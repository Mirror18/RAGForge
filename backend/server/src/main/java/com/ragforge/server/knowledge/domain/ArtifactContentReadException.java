package com.ragforge.server.knowledge.domain;

/** Redacted failure raised when an immutable artifact cannot be read safely. */
public final class ArtifactContentReadException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ArtifactContentReadException(String message) {
        super(message);
    }

    public ArtifactContentReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
