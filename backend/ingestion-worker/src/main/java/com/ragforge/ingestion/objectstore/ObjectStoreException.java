package com.ragforge.ingestion.objectstore;

public class ObjectStoreException extends RuntimeException {
    private final ObjectStoreFailure failure;

    public ObjectStoreException(ObjectStoreFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public ObjectStoreException(ObjectStoreFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    public ObjectStoreFailure failure() {
        return failure;
    }
}
