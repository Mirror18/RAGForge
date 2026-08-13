package com.ragforge.ingestion.messaging;

public enum FailureClass {
    SOURCE_UNAVAILABLE(true),
    VERSION_MISMATCH(true),
    PARSER_TIMEOUT(true),
    OCR_UNAVAILABLE(true),
    OBJECT_STORE_UNAVAILABLE(true),
    DATABASE_UNAVAILABLE(true),
    OUTBOX_PUBLISH_UNAVAILABLE(true),
    RULES_INVALID(false),
    UNSUPPORTED_MEDIA_TYPE(false),
    PATH_INVALID(false),
    SPACE_MISMATCH(false),
    SECURITY_POLICY_REJECTED(false),
    PERMANENT(false);

    private final boolean retryable;

    FailureClass(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() { return retryable; }
}
