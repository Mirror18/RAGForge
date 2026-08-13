package com.ragforge.ingestion.messaging;

public class IngestionProcessingException extends RuntimeException {
    private final FailureClass failureClass;

    public IngestionProcessingException(FailureClass failureClass, String message) {
        super(message);
        this.failureClass = failureClass;
    }

    public IngestionProcessingException(FailureClass failureClass, String message, Throwable cause) {
        super(message, cause);
        this.failureClass = failureClass;
    }

    public FailureClass failureClass() { return failureClass; }
}
