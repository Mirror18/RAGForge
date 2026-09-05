package com.ragforge.ingestion.connector;

public class ConnectorException extends RuntimeException {
    private final ConnectorFailure failure;

    public ConnectorException(ConnectorFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public ConnectorException(ConnectorFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    public ConnectorFailure failure() {
        return failure;
    }
}
