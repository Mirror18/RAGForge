package com.ragforge.server.agent;

/** Stable, non-sensitive failure returned by the tool security boundary. */
public final class AgentToolSecurityException extends RuntimeException {
    private final String errorCode;

    public AgentToolSecurityException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
