package com.ragforge.server.run;

/** Raised when an event payload contains a field that must never cross the run event boundary. */
public final class SensitivePayloadException extends IllegalArgumentException {
    public SensitivePayloadException(String fieldName) {
        super("Sensitive payload field is not allowed: " + fieldName);
    }
}
